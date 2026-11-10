#!/usr/bin/env python3
"""隔离真实 MySQL/Canal/ES 验证。只管理本脚本登记且带专用标签的容器，不连接既有服务。"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / '.codex/canal-question-es-sync'
TASK = 'canal-question-es-sync'


class Integration:
    def __init__(self, label):
        if not re.fullmatch(r'[a-z0-9-]+', label): raise ValueError('无效测试标签')
        self.label = label
        self.started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
        self.folder = BASE / 'integration' / label
        self.folder.mkdir(parents=True, exist_ok=False)
        self.evidence = self.folder / 'evidence'
        self.evidence.mkdir()
        shutil.copy2(Path(__file__).resolve(), self.evidence/'integration-source.py')
        self.network = 'codex-canal-' + label
        self.mysql = self.network + '-mysql'
        self.canal = self.network + '-canal'
        self.es = self.network + '-es'
        self.resources = []
        self.commands = []
        self.results = []
        self.sequence = 0
        self.probe_attempt = 0
        self.probe = None
        self.password = secrets.token_hex(20)
        self.source_password = secrets.token_hex(16)
        self.app_password = secrets.token_hex(20)
        self.writer_password = secrets.token_hex(20)
        self.database = 'lywoj_it'
        self.es_url = None

    def redact(self, text):
        for secret in [self.password, self.source_password, self.app_password, getattr(self, 'writer_password', '')]:
            if secret: text = text.replace(secret, '<已隐藏>')
        return text

    def command(self, arguments, input=None, expected=0, timeout=60, environment=None):
        started = datetime.datetime.now(datetime.timezone.utc).isoformat()
        result = subprocess.run(arguments, input=input, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                cwd=ROOT, timeout=timeout, env=environment)
        number = len(self.commands) + 1
        log = self.evidence / f'command-{number:04d}.log'
        log.write_text(self.redact(result.stdout), encoding='utf-8')
        self.commands.append({'command': arguments, 'cwd': str(ROOT), 'started_at': started,
                              'finished_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                              'exit_code': result.returncode, 'evidence': str(log.relative_to(ROOT))})
        self.save()
        if expected is not None and result.returncode != expected:
            raise RuntimeError(f'命令失败({result.returncode})，见 {log}')
        return result.stdout.strip()

    def save(self):
        data = {'run': self.label, 'started_at':getattr(self,'started_at',None),
                'finished_at':getattr(self,'finished_at',None),'exit_code':getattr(self,'exit_code',None),
                'continuations':getattr(self,'continuations',[]),
                'resources': self.resources, 'commands': self.commands, 'results': self.results}
        (self.folder / 'run.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

    def record(self, name, **details):
        self.results.append({'name': name, 'status': 'passed', 'time': datetime.datetime.now(datetime.timezone.utc).isoformat(), **details})
        self.save()
        print(f'PASS {name}', flush=True)

    def create(self, name, arguments):
        cid = self.command(['docker', 'create', '--name', name, '--label', f'codex.task={TASK}', '--label', f'codex.run={self.label}',
                            '--network', self.network] + arguments)
        self.resources.append({'type': 'container', 'id': cid, 'name': name}); self.save()
        self.command(['docker', 'start', cid])
        return cid

    def sql(self, statement, expected=0):
        return self.command(['docker', 'exec', '-i', self.mysql, 'sh', '-c',
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 --protocol=TCP -uroot --default-character-set=utf8mb4 -N -B'],
                            input=statement, expected=expected)

    def http(self, method, path, body=None, expected=200):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        request = urllib.request.Request(self.es_url + path, data=data, method=method,
                                         headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=8) as response: status, raw = response.status, response.read()
        except urllib.error.HTTPError as error: status, raw = error.code, error.read()
        if expected is not None and status != expected: raise AssertionError(f'ES {method} {path}: HTTP {status}')
        return json.loads(raw) if raw else {}

    def wait(self, predicate, seconds=30, description='条件'):
        deadline = time.monotonic() + seconds
        last = None
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value: return value
            except (OSError, RuntimeError, urllib.error.URLError, AssertionError) as failure: last = type(failure).__name__
            time.sleep(.15)
        raise AssertionError(f'等待超时：{description}，最后异常={last}')

    def port(self, name, port):
        result = self.command(['docker', 'inspect', '-f', '{{(index (index .NetworkSettings.Ports "' + str(port) + '/tcp") 0).HostPort}}', name])
        return int(result)

    def canal_ready(self):
        with socket.create_connection(('127.0.0.1', self.canal_port), timeout=1): return True

    def setup(self):
        network_id = self.command(['docker', 'network', 'create', '--label', f'codex.task={TASK}', '--label', f'codex.run={self.label}', self.network])
        self.resources.append({'type': 'network', 'id': network_id, 'name': self.network}); self.save()
        for name in ['mysql', 'es', 'state', 'config', 'probe']: (self.folder / name).mkdir()
        (self.folder / 'es').chmod(0o777)
        env = self.folder / 'mysql.env'; env.write_text('MYSQL_ROOT_PASSWORD=' + self.password + '\n'); env.chmod(0o600)
        self.create(self.mysql, ['--memory', '768m', '-p', '127.0.0.1::3306', '--env-file', str(env),
                    '--mount', f'type=bind,src={self.folder / "mysql"},dst=/var/lib/mysql', 'mysql:8.0.40',
                    '--server-id=771', '--log-bin=mysql-bin', '--binlog-format=ROW', '--binlog-row-image=FULL',
                    '--character-set-server=utf8mb4', '--collation-server=utf8mb4_general_ci', '--innodb-buffer-pool-size=128M'])
        self.create(self.es, ['--memory', '1536m', '-p', '127.0.0.1::9200', '-e', 'discovery.type=single-node',
                    '-e', 'xpack.security.enabled=false', '-e', 'ES_JAVA_OPTS=-Xms512m -Xmx512m',
                    '-e', 'action.auto_create_index=-idx_question,+*',
                    '--mount', f'type=bind,src={self.folder / "es"},dst=/usr/share/elasticsearch/data',
                    '--mount', f'type=bind,src={ROOT / "deploy/dev/elasticsearch/es-plugins/elsaticsearch-analysis-ik-8.5.3"},dst=/usr/share/elasticsearch/plugins/analysis-ik,readonly',
                    'elasticsearch:8.5.3'])
        self.es_url = 'http://127.0.0.1:' + str(self.port(self.es, 9200))
        self.wait(lambda: self.sql('SELECT 1') == '1', 90, 'MySQL 启动')
        self.wait(lambda: self.http('GET', '/')['version']['number'] == '8.5.3', 90, 'ES 启动')
        schema = (ROOT / 'deploy/dev/sql/init.sql').read_text(encoding='utf-8')
        self.table_sql = re.search(r'create table tb_question\s*\([\s\S]*?;', schema, re.IGNORECASE).group(0)
        self.sql(f'CREATE DATABASE {self.database} CHARACTER SET utf8mb4; USE {self.database};\n' + self.table_sql + '\nCREATE TABLE other_table(id BIGINT PRIMARY KEY, value INT);')
        self.sql(f"CREATE USER 'canal_sync'@'%' IDENTIFIED WITH mysql_native_password BY '{self.source_password}';\n"
                 "GRANT REPLICATION SLAVE,REPLICATION CLIENT ON *.* TO 'canal_sync'@'%';\n"
                 f"GRANT SELECT ON `{self.database}`.* TO 'canal_sync'@'%';\n"
                 f"CREATE USER 'app_sync'@'%' IDENTIFIED BY '{self.app_password}';\n"
                 f"GRANT SELECT ON `{self.database}`.* TO 'app_sync'@'%';")
        self.sql(f"CREATE USER 'question_writer'@'%' IDENTIFIED BY '{self.writer_password}';\n"
                 f"GRANT SELECT,UPDATE ON `{self.database}`.* TO 'question_writer'@'%';")
        # 外部回归的库和账号在起点 P 前准备，避免给 ES 故障场景混入数据库级 DDL。
        self.sql("CREATE DATABASE oj_idempotency; CREATE USER 'oj_test'@'%' IDENTIFIED BY 'oj_test_password'; "
                 "GRANT ALL ON oj_idempotency.* TO 'oj_test'@'%';")
        self.sql(f'USE {self.database};\n' + self.insert(1, '基线甲') + self.insert(2, '基线乙'))
        mapping = {name: {'type': kind} for name, kind in {'questionId':'long','title':'text','difficulty':'byte',
                    'timeLimit':'long','spaceLimit':'long','content':'text','questionCase':'text','mainFuc':'text',
                    'defaultCode':'text','createTime':'date'}.items()}
        mapping['title'].update(analyzer='ik_max_word', search_analyzer='ik_smart')
        mapping['content'].update(analyzer='ik_max_word', search_analyzer='ik_max_word')
        mapping['createTime']['format'] = 'date_hour_minute_second'
        self.mapping = mapping
        self.http('PUT', '/idx_question', {'mappings': {'properties': mapping}})
        self.uuid = self.http('GET', '/idx_question/_settings')['idx_question']['settings']['index']['uuid']
        self.http('PUT', '/idx_question/_doc/1', self.document(1, '旧索引标题'), expected=201)
        self.http('PUT', '/idx_question/_doc/99', self.document(99, '历史多余'), expected=201)
        self.wait(lambda: self.search(99) is not None, description='基线搜索可见')
        self.prepare_canal()
        self.start_canal()
        self.start_probe()
        self.record('隔离服务启动', mysql='8.0.40', canal='1.1.8', es='8.5.3', ik='8.5.3', network=self.network)

    def insert(self, identity, title):
        safe = title.replace("'", "''")
        return f"INSERT INTO tb_question(question_id,title,difficulty,time_limit,space_limit,content,question_case,default_code,main_fuc,create_by,create_time) VALUES({identity},'{safe}',2,1000,1024000,'中文题干\\n第二行','[]','class Solution {{}}','入口',1,'2026-11-10 01:02:03');\n"

    def document(self, identity, title):
        return {'questionId': identity, 'title': title, 'difficulty': 2, 'timeLimit': 1000, 'spaceLimit': 1024000,
                'content':'中文题干\n第二行','questionCase':'[]','defaultCode':'class Solution {}','mainFuc':'入口','createTime':'2026-11-10T01:02:03'}

    def position(self):
        fields = self.sql('SHOW MASTER STATUS').split('\t')
        return fields[0], int(fields[1])

    def prepare_canal(self):
        source = self.sql('SHOW MASTER STATUS; SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED);').splitlines()
        fields = source[0].split('\t'); file, position, timestamp = fields[0], int(fields[1]), int(source[1])
        environment = dict(os.environ, CANAL_DB_NAME=self.database, CANAL_DB_HOST=self.mysql,
                           CANAL_DB_USERNAME='canal_sync', CANAL_DB_PASSWORD=self.source_password)
        result = subprocess.run([sys.executable, str(ROOT/'deploy/canal/prepare.py'), '--env', 'dev', '--output-dir', str(self.folder/'config'),
                                 '--source-file', file, '--source-position', str(position), '--source-timestamp', str(timestamp)], env=environment, text=True, capture_output=True, cwd=ROOT)
        if result.returncode: raise RuntimeError('测试 Canal 配置生成失败')
        connection = {'database':self.database, 'canal.host':self.canal, 'state.directory':'/evidence/integration/'+self.label+'/state',
                      'initial.file':file, 'initial.position':str(position),
                      'jdbc.url':f'jdbc:mysql://{self.mysql}:3306/{self.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai',
                      'source.password':self.source_password, 'app.password':self.app_password, 'es.url':'http://'+self.es+':9200'}
        target = self.folder/'probe/connection.properties'
        target.write_text(''.join(key+'='+value+'\n' for key,value in connection.items()), encoding='ascii'); target.chmod(0o600)
        self.baseline_number=getattr(self,'baseline_number',0)+1
        (self.evidence/f'initial-position-{self.baseline_number}.json').write_text(json.dumps({'file':file,'position':position,'sourceTimestamp':timestamp}))

    def start_canal(self, state=None, config=None):
        state = self.folder/'state' if state is None else state
        config = self.folder/'config' if config is None else config
        self.create(self.canal, ['--memory','768m','-p','127.0.0.1::11111',
                     '--mount',f'type=bind,src={config},dst=/config,readonly',
                     '--mount',f'type=bind,src={state},dst=/state',
                     '--mount',f'type=bind,src={ROOT/"deploy/canal/entrypoint.sh"},dst=/entrypoint.sh,readonly',
                     '--entrypoint','/bin/sh','canal/canal-server:v1.1.8','/entrypoint.sh'])
        self.canal_port = self.port(self.canal,11111)
        self.wait(self.canal_ready, 60, 'Canal TCP 启动')

    def start_probe(self):
        self.probe_attempt += 1
        self.probe_label = f'{self.label}-probe-{self.probe_attempt}'
        for name in ['ready.json','request.json','response.json','before-ack.json','timeout-backend-applied.json']:
            (self.folder/'probe'/name).unlink(missing_ok=True)
        self.sequence = 0
        output = (self.evidence/(self.probe_label+'.log')).open('w')
        command = [sys.executable, str(ROOT/'deploy/canal/tests/run_maven.py'), self.probe_label,
                   '-pl','oj-modules/oj-system','-am','test','-Dtest=CanalIntegrationProbeTest',
                   '-Dsurefire.failIfNoSpecifiedTests=false','-Dcanal.integration=true',
                   '-Dcanal.integration.directory=/evidence/integration/'+self.label+'/probe']
        self.probe = subprocess.Popen(command, env=dict(os.environ,SYNC_TEST_NETWORK=self.network,SYNC_TEST_RUN=self.label), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        output.close()
        def ready():
            if self.probe.poll() is not None: raise AssertionError('Java 探针提前退出，查看 Maven 日志')
            return (self.folder/'probe/ready.json').exists()
        self.wait(ready,90,'Java 探针就绪')
        cid = self.command(['docker','inspect','-f','{{.Id}}','codex-canal-'+self.probe_label])
        self.resources.append({'type':'container','id':cid,'name':'codex-canal-'+self.probe_label}); self.save()

    def control(self, action, expect_ok=True, **arguments):
        self.sequence += 1
        request = self.folder/'probe/request.json'
        temporary = request.with_suffix('.new')
        temporary.write_text(json.dumps({'sequence':self.sequence,'action':action,**arguments}), encoding='utf-8')
        temporary.replace(request)
        def done():
            file = self.folder/'probe/response.json'
            if not file.exists(): return False
            response = json.loads(file.read_text())
            return response if response.get('sequence') == self.sequence else False
        response = self.wait(done,30,'探针 '+action)
        with (self.evidence/'probe-events.jsonl').open('a',encoding='utf-8') as output:
            output.write(json.dumps({'action':action,**response},ensure_ascii=False)+'\n')
        if response.get('ok') != expect_ok: raise AssertionError('探针结果不符：'+json.dumps(response,ensure_ascii=False))
        return response

    def search(self, identity):
        response = self.http('POST','/idx_question/_search', {'query':{'ids':{'values':[str(identity)]}},'size':1})
        hits = response['hits']['hits']
        return hits[0]['_source'] if hits else None

    def visible(self, identity, title, seconds=5):
        def match():
            value = self.search(identity)
            return (value is None) if title is None else (value if value and value['title'] == title else False)
        return self.wait(match, seconds, f'{identity} 搜索变化')

    def change(self, name, statement, identity, title):
        started = time.monotonic()
        self.sql('USE '+self.database+';\n'+statement)
        result = self.visible(identity,title)
        elapsed = round((time.monotonic()-started)*1000, 1)
        assert elapsed <= 5000, f'可见性超时 {elapsed} ms'
        self.record(name, search_latency_ms=elapsed, forced_refresh=False)
        return result

    def core(self):
        check = self.control('maintain',mode='check',confirmed=False)
        assert check['exitCode'] == 2 and check['differences'] == 3
        assert self.search(1)['title'] == '旧索引标题'
        self.command(['docker','stop','--time','20',self.canal])
        repair = self.control('maintain',mode='repair',confirmed=True)
        assert repair['repaired'] == 3
        assert self.http('GET','/idx_question/_settings')['idx_question']['settings']['index']['uuid'] == self.uuid
        assert self.http('GET','/idx_question/_mapping')['idx_question']['mappings']['properties']['title']['search_analyzer'] == 'ik_smart'
        self.command(['docker','start',self.canal]); self.canal_port=self.port(self.canal,11111)
        self.wait(self.canal_ready,60,'Canal 维护后启动')
        self.record('保留索引基线核对及停机修复', differences=check['differences'], repaired=repair['repaired'], uuid_preserved=True)
        self.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON;")
        self.control('start')
        self.wait(lambda:self.control('status').get('state')=='RUNNING',20,'同步器启动')
        idle_start=time.monotonic()
        while time.monotonic()-idle_start < 32:
            assert self.control('status')['state']=='RUNNING'
            time.sleep(1)
        self.record('首次空闲订阅跨周期位点检查', seconds=32)
        big=9007199254740993
        added=self.change('新增秒级可见',self.insert(big,'新增中文😀'),big,'新增中文😀')
        assert added['questionId']==big and added['createTime']=='2026-11-10T01:02:03'
        changed=self.change('完整行更新及 NULL 清除',f"UPDATE tb_question SET title='只改标题',question_case=NULL WHERE question_id={big};",big,'只改标题')
        assert changed['timeLimit']==1000 and changed['content']=='中文题干\n第二行' and changed['questionCase'] is None
        self.change('连续修改最终值',f"START TRANSACTION; UPDATE tb_question SET title='中间值' WHERE question_id={big}; UPDATE tb_question SET title='最后值' WHERE question_id={big}; COMMIT;",big,'最后值')
        self.change('主键变化写入新 ID',f'UPDATE tb_question SET question_id=3 WHERE question_id={big};',3,'最后值')
        self.visible(big,None)
        self.change('删除秒级可见','DELETE FROM tb_question WHERE question_id=3;',3,None)
        self.change('删除后重建',self.insert(3,'重建后最新'),3,'重建后最新')
        assert self.control('status')['questionReads']==0
        selects=int(self.sql("SELECT COUNT(*) FROM mysql.general_log WHERE user_host LIKE 'app_sync[%' AND LOWER(argument) LIKE '%tb_question%' AND LOWER(argument) LIKE 'select%';"))
        self.sql('SET GLOBAL general_log=OFF;')
        assert selects==0
        self.record('正常增量无题目查询',question_reader_calls=0,mysql_logged_question_selects=selects)
        for mode in ['partial-write','before-ack','after-ack']:
            before=self.control('status')['faultsInjected']
            self.control('fault',mode=mode)
            title='故障恢复-'+mode
            self.sql(f"USE {self.database}; START TRANSACTION; UPDATE tb_question SET title='{title}' WHERE question_id IN (1,2); COMMIT;")
            self.visible(1,title,20); self.visible(2,title,20)
            self.wait(lambda:self.control('status')['faultsInjected']>before,10,'故障实际注入')
            self.wait(lambda:self.control('status')['state']=='RUNNING',20,'故障后重新消费')
            self.record('失败重放-'+mode, injection='真实组件外部的测试故障适配器', latest_in_search=True)
        self.command(['docker','stop','--time','20',self.es])
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='ES恢复后补上',time_limit=2345,question_case='[{{\"input\":\"7 8\",\"output\":\"15\"}}]',main_fuc='最新入口' WHERE question_id=1;")
        self.wait(lambda:self.control('status')['state']=='RETRYING',15,'ES 停机进入重试')
        self.friend_regression()
        self.command(['docker','start',self.es])
        self.es_url='http://127.0.0.1:'+str(self.port(self.es,9200))
        self.wait(lambda:self.http('GET','/')['version']['number']=='8.5.3',60,'ES 恢复')
        self.visible(1,'ES恢复后补上',45)
        self.record('真实 ES 停机后自动追赶')
        self.control('stop')
        self.command(['docker','stop','--time','20',self.canal])
        metadata=self.folder/'state/question-sync/meta.dat'
        assert metadata.exists()
        shutil.copy2(metadata,self.evidence/'before-container-recreation-meta.json')
        old=self.command(['docker','inspect','-f','{{.Id}}',self.canal])
        self.command(['docker','rm',self.canal])
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='重建容器后补上' WHERE question_id=2;")
        self.start_canal()
        new=self.command(['docker','inspect','-f','{{.Id}}',self.canal]); assert old!=new
        self.control('start'); self.visible(2,'重建容器后补上',30)
        self.record('重建 Canal 容器复用持久卷续传',old_container=old,new_container=new)

    def friend_regression(self):
        label=self.label+'-friend-real'
        self.command([sys.executable,str(ROOT/'deploy/canal/tests/run_maven.py'),label,'-pl','oj-modules/oj-friend','-am','test',
                      '-Dtest=UserQuestionResultTest,QuestionReadBoundaryTest,QuestionReadBoundaryIntegrationTest,UserQuestionLookupIntegrationTest',
                      '-Dsurefire.failIfNoSpecifiedTests=false','-Doj.integration=true','-Dcanal.business.integration=true',
                      f'-Doj.mysql.url=jdbc:mysql://{self.mysql}:3306/oj_idempotency?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                      '-Dcanal.integration.directory=/evidence/integration/'+self.label+'/probe'],timeout=120,
                     environment=dict(os.environ,SYNC_TEST_NETWORK=self.network,SYNC_TEST_RUN=self.label))
        cid=self.command(['docker','inspect','-f','{{.Id}}','codex-canal-'+label])
        self.resources.append({'type':'container','id':cid,'name':'codex-canal-'+label}); self.save()
        self.record('ES 停机时详情及判题读取真实 MySQL，既有回执和结果查询回归')

    def restart_probe_before_ack(self):
        self.control('fault',mode='pause-before-ack')
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='ACK前真实进程中断' WHERE question_id=1;")
        self.wait(lambda:(self.folder/'probe/before-ack.json').exists(),20,'到达真实写入后的 ACK 边界')
        self.command(['docker','kill','codex-canal-'+self.probe_label])
        self.probe.wait(timeout=30)
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='进程重启后的最新值' WHERE question_id=1;")
        self.start_probe(); self.control('start'); self.visible(1,'进程重启后的最新值',30)
        self.record('ACK 前真实进程被终止，重启后最终追平',expected_probe_exit_code=137)

    def rebaseline(self, reason):
        self.control('stop'); self.command(['docker','stop','--time','20',self.canal])
        repair=self.control('maintain',mode='repair',confirmed=True)
        self.command(['docker','rm',self.canal])
        backup=self.folder/f'state-before-{self.baseline_number+1}'
        (self.folder/'state').rename(backup); (self.folder/'state').mkdir()
        self.prepare_canal(); self.start_canal()
        self.record('隔离人工维护基线-'+reason,repaired=repair['repaired'],metadata_backup=str(backup.relative_to(ROOT)))

    def extended(self):
        self.restart_probe_before_ack()
        self.control('fault',mode='before-ack')
        self.sql(f'USE {self.database}; DELETE FROM tb_question WHERE question_id=3;')
        self.visible(3,None,20)
        self.wait(lambda:self.control('status')['state']=='RUNNING' and self.control('status')['faultsInjected']>0,20,'重复删除批次重放完成')
        self.record('删除事件重复消费成功')
        self.control('stop')
        self.http('PUT','/idx_question/_doc/1',self.document(1,'对账前的过期标题'))
        self.http('DELETE','/idx_question/_doc/2')
        self.http('PUT','/idx_question/_doc/99',self.document(99,'对账多余'),expected=201)
        self.visible(99,'对账多余')
        self.sql(f'USE {self.database}; INSERT INTO other_table VALUES(1,1);')
        self.control('start',reconcileMillis=500,pageSize=1)
        self.visible(1,'进程重启后的最新值',30); self.visible(2,'重建容器后补上',30); self.visible(99,None,30)
        state=self.control('status'); assert state['questionReads']>0 and state['maxConcurrentWrites']==1
        self.record('无关表事务推进位点及分时对账修复',max_concurrent_writes=state['maxConcurrentWrites'])
        # 增量积压中同时包含 TRUNCATE 和随后重插入；对账不能提前绕过控制事件。
        self.control('stop')
        self.sql(f'USE {self.database}; TRUNCATE TABLE tb_question;\n'+self.insert(400,'清表后的新行'))
        self.control('start',reconcileMillis=1,pageSize=1)
        state=self.wait(lambda:(s if (s:=self.control('status'))['state']=='BLOCKED' else False),20,'积压 TRUNCATE 阻塞')
        assert state['questionReads']==0 and self.search(400) is None and self.search(1) is not None
        self.record('积压 TRUNCATE 后重插入阻塞，未被自动对账绕过',question_reads=state['questionReads'])
        self.rebaseline('TRUNCATE 后显式修复')
        self.control('start')
        self.wait(lambda:self.control('status')['state']=='RUNNING',20,'维护恢复')
        self.sql(f'USE {self.database}; ALTER TABLE tb_question ADD COLUMN extra_note INT NULL;')
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'ALTER 阻塞')
        self.record('真实 ALTER 阻塞')
        self.rebaseline('DDL 后核对')
        self.control('start')
        self.wait(lambda:self.control('status')['state']=='RUNNING',20,'RENAME 前启动')
        self.change('旧 DDL 纳入基线后新 UPDATE 可见',"UPDATE tb_question SET title='DDL恢复后的更新' WHERE question_id=400;",400,'DDL恢复后的更新')
        self.sql(f'USE {self.database}; RENAME TABLE tb_question TO archived_question;')
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'RENAME away 阻塞')
        self.record('真实 RENAME away 阻塞')
        self.sql(f'USE {self.database}; RENAME TABLE archived_question TO tb_question;')
        self.rebaseline('恢复原表名')
        self.control('start')
        self.wait(lambda:self.control('status')['state']=='RUNNING',20,'多表 DDL 前启动')
        self.sql(f'USE {self.database}; DROP TABLE other_table,tb_question;')
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'目标不是第一项的 DROP 阻塞')
        self.record('真实多表 DROP 目标非首项仍阻塞')
        self.sql(f'USE {self.database}; '+self.table_sql+self.insert(400,'人工重建测试表'))
        self.rebaseline('恢复隔离测试表')
        self.control('start')
        self.wait(lambda:self.control('status')['state']=='RUNNING',20,'非 FULL 前启动')
        self.sql(f"USE {self.database}; SET SESSION binlog_row_image='MINIMAL'; UPDATE tb_question SET title='不完整更新' WHERE question_id=400;")
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'非完整行阻塞')
        self.record('真实 MINIMAL 行不回查补齐')
        self.rebaseline('不完整事件维护')
        self.control('start',reconcileMillis=1)
        self.wait(lambda:self.control('status')['state']=='RUNNING',20,'MINIMAL DELETE 前启动')
        self.sql(f"USE {self.database}; SET SESSION binlog_row_image='MINIMAL'; DELETE FROM tb_question WHERE question_id=400;")
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'非完整 DELETE 阻塞')
        assert self.search(400) is not None
        self.record('真实 MINIMAL DELETE 阻塞且未借对账删除旧文档')
        self.sql(f'USE {self.database}; '+self.insert(400,'恢复完整行测试数据'))
        self.rebaseline('MINIMAL DELETE 后维护')
        self.control('start'); self.wait(lambda:self.control('status')['state']=='RUNNING',20,'statement 前启动')
        self.sql(f"USE {self.database}; SET SESSION binlog_format='STATEMENT'; UPDATE tb_question SET title='statement变更' WHERE question_id=400;")
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'STATEMENT 阻塞')
        self.record('真实 STATEMENT DML 不静默确认')
        self.rebaseline('statement 后维护')
        self.control('start'); self.wait(lambda:self.control('status')['state']=='RUNNING',20,'位点失效前启动')
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='位点测试基线' WHERE question_id=400;")
        self.visible(400,'位点测试基线',10)
        self.wait(lambda:(self.folder/'state/metadata-established').exists(),10,'有效位点持久化')
        self.sql('FLUSH BINARY LOGS;')
        latest,_=self.position()
        self.sql("PURGE BINARY LOGS TO '"+latest+"';")
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'源文件已清理但 TCP 仍在线')
        assert self.canal_ready(); self.record('位点失效时 TCP 仍在线也能阻塞')
        self.rebaseline('失效位点显式恢复')
        self.control('start'); self.wait(lambda:self.control('status')['state']=='RUNNING',20,'缺索引前启动')
        self.http('DELETE','/idx_question')
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='缺索引时变更' WHERE question_id=400;")
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'缺失索引阻塞')
        assert 'error' in self.http('GET','/idx_question',expected=404)
        self.record('索引缺失后增量未隐式创建')
        result=self.control('maintain',mode='repair',confirmed=True)
        assert result['repaired']>=1; self.visible(400,'缺索引时变更',10)
        self.record('缺失索引仅显式维护创建导入')
        self.control('start')
        file, offset = self.position()
        def caught_up():
            state = self.control('status'); position = state.get('position') or {}
            return state if state['state'] == 'RUNNING' and position.get('file') == file and position.get('offset', 0) >= offset else False
        self.wait(caught_up, 20, 'DELETE 负例前已确认此前事件')
        self.http('DELETE','/idx_question')
        self.sql(f'USE {self.database}; DELETE FROM tb_question WHERE question_id=400;')
        self.wait(lambda:self.control('status')['state']=='BLOCKED',20,'索引缺失时 DELETE 批次阻塞')
        assert 'error' in self.http('GET','/idx_question',expected=404)
        self.record('预检后索引被删除，真实 DELETE 事件失败且未隐式创建')
        self.sql(f'USE {self.database}; '+self.insert(400,'恢复缺索引删除测试'))
        self.control('maintain',mode='repair',confirmed=True)
        self.visible(400,'恢复缺索引删除测试',10)

    def boot_maintenance(self):
        attempt=1
        while (BASE/'builds'/f'{self.label}-package-{attempt}').exists(): attempt+=1
        label=f'{self.label}-package-{attempt}'
        self.command([sys.executable,str(ROOT/'deploy/canal/tests/run_maven.py'),label,'-pl','oj-modules/oj-system','-am','package','-DskipTests'],
                     timeout=120,environment=dict(os.environ,SYNC_TEST_NETWORK=self.network,SYNC_TEST_RUN=self.label))
        cid=self.command(['docker','inspect','-f','{{.Id}}','codex-canal-'+label])
        self.resources.append({'type':'container','id':cid,'name':'codex-canal-'+label}); self.save()
        jar=BASE/'builds'/label/'oj-modules/oj-system/target/oj-system-1.0-SNAPSHOT.jar'
        connection=dict(line.split('=',1) for line in (self.folder/'probe/connection.properties').read_text().splitlines())
        settings={
            'spring.cloud.nacos.config.enabled':'false','spring.cloud.nacos.discovery.enabled':'false',
            'spring.datasource.url':connection['jdbc.url'],'spring.datasource.username':'app_sync','spring.datasource.password':self.app_password,
            'spring.datasource.hikari.connection-timeout':'5000','spring.elasticsearch.uris':connection['es.url'],
            'spring.elasticsearch.socket-timeout':'5s','spring.elasticsearch.connection-timeout':'5s',
            'canal.sync.database':self.database,'canal.sync.host':self.canal,'canal.sync.state-directory':'/canal-state',
            'canal.sync.initial-file':connection['initial.file'],'canal.sync.initial-position':connection['initial.position'],
            'canal.sync.source-jdbc-url':connection['jdbc.url'],'canal.sync.source-username':'canal_sync','canal.sync.source-password':self.source_password,
            'logging.config':'file:/runtime/logback.xml','spring.main.banner-mode':'off'}
        runtime=self.folder/f'boot-{attempt}'; runtime.mkdir()
        configuration=runtime/'application.properties'
        configuration.write_text(''.join(key+'='+value+'\n' for key,value in settings.items()),encoding='ascii'); configuration.chmod(0o600)
        (runtime/'logback.xml').write_text('<configuration><appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="STDOUT"/></root></configuration>',encoding='utf-8')
        for mode in ['check','repair']:
            name=self.network+f'-boot-{attempt}-'+mode
            cid=self.command(['docker','create','--name',name,'--label',f'codex.task={TASK}','--label',f'codex.run={self.label}',
                 '--network',self.network,'--memory','768m','-e','JAVA_TOOL_OPTIONS=-Xmx384m',
                 '-e','SPRING_CLOUD_NACOS_CONFIG_ENABLED=false','-e','SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false',
                 '-e','SPRING_PROFILES_ACTIVE=canal-it','-e','HOST_IP=127.0.0.1',
                 '-e','SPRING_CONFIG_ADDITIONAL_LOCATION=file:/runtime/application.properties',
                 '--mount',f'type=bind,src={jar},dst=/app.jar,readonly',
                 '--mount',f'type=bind,src={ROOT/"deploy/canal/maintain.sh"},dst=/maintain.sh,readonly',
                 '--mount',f'type=bind,src={runtime},dst=/runtime,readonly',
                 '--mount',f'type=bind,src={self.folder/"state"},dst=/canal-state,readonly',
                 '--mount',f'type=bind,src={self.folder},dst=/evidence','-w','/evidence',
                 'maven:3.9.9-eclipse-temurin-17','sh','/maintain.sh',mode,'/app.jar']+(['--confirm-maintenance'] if mode=='repair' else []))
            self.resources.append({'type':'container','id':cid,'name':name}); self.save()
            self.command(['docker','start','-a',cid],timeout=60)
        self.record('完整 maintain.sh 到 JAR 的 check/repair 启动及退出码',jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())

    @staticmethod
    def state_files(folder):
        result = {}
        for path in sorted(folder.rglob('*')):
            if path.is_symlink(): raise AssertionError('隔离 state 不应包含符号链接')
            if path.is_file():
                result[str(path.relative_to(folder))] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                                       'mode': path.stat().st_mode & 0o777}
        return result

    @staticmethod
    def state_cursor(folder):
        try:
            metadata = json.loads((folder/'question-sync/meta.dat').read_text())
            for client in metadata['clientDatas']:
                if client['clientIdentity']['clientId'] == 1001:
                    position = client['cursor']['postion']
                    return position['journalName'], position['position']
        except (OSError, ValueError, KeyError, TypeError): return None

    def point_probe_state(self, folder):
        config = self.folder/'probe/connection.properties'
        value = '/evidence/' + folder.relative_to(BASE).as_posix()
        text, changed = re.subn(r'^state.directory=.*$', 'state.directory=' + value,
                               config.read_text(), flags=re.MULTILINE)
        assert changed == 1
        config.write_text(text, encoding='ascii')

    def migrate_state(self):
        self.control('start')
        self.change('目录迁移前的健康基线', "UPDATE tb_question SET title='目录迁移前基线' WHERE question_id=400;", 400, '目录迁移前基线')
        self.change('目录迁移保留历史题目', self.insert(700, '迁移保留的题目'), 700, '迁移保留的题目')
        self.control('stop'); self.command(['docker','stop','--time','20',self.canal])
        source_state, source_config = self.folder/'state', self.folder/'config'
        self.control('quit'); self.probe.wait(timeout=40)
        original_cursor = self.state_cursor(source_state)
        assert original_cursor is not None and (source_state/'metadata-established').exists()
        original_files = self.state_files(source_state)
        original_config = self.state_files(source_config)
        assert original_files and original_config
        formal = self.folder/'deploy/prod/canal'; formal.mkdir(parents=True)
        shutil.copytree(source_state, formal/'state'); shutil.copytree(source_config, formal/'config')
        assert self.state_files(formal/'state') == original_files
        assert self.state_files(formal/'config') == original_config
        index_uuid = self.http('GET','/idx_question/_settings')['idx_question']['settings']['index']['uuid']
        mapping = self.http('GET','/idx_question/_mapping')
        original_container = self.command(['docker','inspect','-f','{{.Id}}',self.canal])
        self.command(['docker','rm',self.canal])
        self.point_probe_state(formal/'state'); self.start_canal(formal/'state', formal/'config')
        self.start_probe()
        new_container = self.command(['docker','inspect','-f','{{.Id}}',self.canal])
        assert new_container != original_container
        check = self.control('maintain', mode='check', confirmed=False)
        assert check['exitCode'] == 0
        self.control('start')
        self.change('正式目录继续消费', "UPDATE tb_question SET title='正式目录最新值' WHERE question_id=400;", 400, '正式目录最新值')
        self.wait(lambda: self.state_cursor(formal/'state') not in [None, original_cursor], 10, '新目录位点推进并持久化')
        self.control('stop'); self.command(['docker','stop','--time','20',self.canal])
        self.control('quit'); self.probe.wait(timeout=40)
        latest_cursor = self.state_cursor(formal/'state')
        latest_files = self.state_files(formal/'state')
        assert latest_cursor is not None and latest_cursor != original_cursor
        assert self.state_files(source_state) == original_files
        self.record('完整 state 和配置迁至正式目录后重建续传', original_cursor=original_cursor,
                    latest_cursor=latest_cursor, copied_files=len(original_files), old_directory_preserved=True,
                    old_container=original_container, new_container=new_container)
        # 已推进状态的回退必须携带最新 state，旧目录继续留作备份。
        self.command(['docker','rm',self.canal])
        source_state.rename(self.folder/'state-before-migration-return')
        source_config.rename(self.folder/'config-before-migration-return')
        shutil.copytree(formal/'state', source_state); shutil.copytree(formal/'config', source_config)
        assert self.state_files(source_state) == latest_files
        assert self.state_cursor(source_state) == latest_cursor
        assert self.state_files(source_config) == self.state_files(formal/'config')
        self.point_probe_state(source_state); self.start_canal()
        self.start_probe()
        assert self.control('maintain', mode='check', confirmed=False)['exitCode'] == 0
        self.control('start')
        self.change('最新 state 回迁后继续消费', "UPDATE tb_question SET title='回迁后最新值' WHERE question_id=400;", 400, '回迁后最新值')
        self.visible(700, '迁移保留的题目')
        assert self.http('GET','/idx_question/_settings')['idx_question']['settings']['index']['uuid'] == index_uuid
        assert self.http('GET','/idx_question/_mapping') == mapping
        self.control('stop')
        (self.evidence/'migration-state-manifests.json').write_text(json.dumps({'original':original_files,'latest':latest_files,
            'config':original_config,'original_cursor':original_cursor,'latest_cursor':latest_cursor},ensure_ascii=False,indent=2)+'\n')
        self.record('已推进目录携最新 state 回退，索引及历史文档保留', restored_cursor=latest_cursor, index_uuid=index_uuid)

    def drain_writer_before_maintenance(self):
        self.control('start')
        assert self.control('gate', enabled=False)['acceptsNewWrite']
        before = self.search(400)['title']
        script = self.folder/'held-write.sql'
        script.write_text(f"START TRANSACTION; UPDATE {self.database}.tb_question SET title='未提交写入应回滚' WHERE question_id=400; SELECT CONNECTION_ID();\n", encoding='utf-8')
        environment = self.folder/'writer.env'
        environment.write_text('MYSQL_PWD=' + self.writer_password + '\n', encoding='ascii'); environment.chmod(0o600)
        writer = self.network+'-held-writer'
        self.create(writer, ['--memory','256m','--env-file',str(environment),'-e','MYSQL_HOST='+self.mysql,
                    '--mount',f'type=bind,src={script},dst=/held-write.sql,readonly',
                    '--entrypoint','sh','mysql:8.0.40','-c',
                    '(cat /held-write.sql; sleep 120) | mysql --protocol=TCP --host="$MYSQL_HOST" --user=question_writer --default-character-set=utf8mb4 --batch --skip-column-names --unbuffered'])
        def writer_connection():
            lines = self.command(['docker','logs',writer]).splitlines()
            return next((int(line) for line in lines if line.isdigit()), None)
        connection = self.wait(writer_connection, 10, '在途写事务的真实连接 ID')
        transaction_query = f'SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id={connection};'
        self.wait(lambda:self.sql(transaction_query)=='1', 10, '真实事务保持未提交')
        gate = self.control('gate', enabled=True)
        assert gate['blockedWrites'] == 3 and gate['resultCode'] == 3503
        assert self.sql(transaction_query) == '1'
        assert self.sql(f'SELECT title FROM {self.database}.tb_question WHERE question_id=400;') == before
        assert self.control('stop')['state'] == 'STOPPED'
        self.command(['docker','stop','--time','5',writer])
        assert self.command(['docker','inspect','-f','{{.State.Running}}',writer]) == 'false'
        self.wait(lambda:self.sql(transaction_query)=='0', 15, '停止写入进程后事务回滚')
        session_query = f'SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID={connection};'
        assert self.sql(session_query) == '0'
        assert self.sql(f'SELECT title FROM {self.database}.tb_question WHERE question_id=400;') == before
        state = self.control('status')
        assert state['state'] == 'STOPPED' and state['gateEnabled']
        self.record('3503 仅挡新请求，写入进程退出且真实事务回滚后才维护', connection_id=connection,
                    blocked_writes=3, writer_running=False, remaining_transactions=0, consumer_state=state['state'])

    def edges(self):
        # 必须先证明有真实 ACK 的持久游标，不能把首次 null 游标当作既有位点丢失。
        self.control('start')
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='建立持久化游标-{self.probe_attempt}' WHERE question_id=400;")
        self.visible(400,f'建立持久化游标-{self.probe_attempt}',15)
        self.wait(lambda:(self.folder/'state/metadata-established').exists() and self.control('status')['lastAck']>=0,10,'真实 ACK 和持久化建立标记')
        self.control('stop')
        fields=self.http('GET','/idx_question/_mapping')['idx_question']['mappings']['properties']
        wrong=json.loads(json.dumps(fields)); wrong['difficulty']['type']='integer'
        self.http('DELETE','/idx_question'); self.http('PUT','/idx_question',{'mappings':{'properties':wrong}})
        uuid=self.http('GET','/idx_question/_settings')['idx_question']['settings']['index']['uuid']
        self.control('start',reconcileMillis=1)
        state=self.wait(lambda:(s if (s:=self.control('status'))['state']=='BLOCKED' else False),10,'不兼容映射阻塞')
        assert state['questionReads']==0 and 'difficulty' in state['reason']
        self.control('maintain',expect_ok=False,mode='repair',confirmed=True)
        assert self.http('GET','/idx_question/_settings')['idx_question']['settings']['index']['uuid']==uuid
        self.record('不兼容映射同时阻塞增量和 repair，未自动重建')
        # 这是隔离负例的显式恢复，不是应用 repair 的自动重建行为。
        self.http('DELETE','/idx_question'); self.http('PUT','/idx_question',{'mappings':{'properties':fields}})
        self.control('maintain',mode='repair',confirmed=True)
        self.http('PUT','/idx_question/_doc/666',self.document(666,'主库失败时保留'),expected=201)
        self.visible(666,'主库失败时保留')
        self.sql(f"REVOKE SELECT ON `{self.database}`.* FROM 'app_sync'@'%';")
        try:
            failed=self.control('maintain',expect_ok=False,mode='repair',confirmed=True)
            assert failed['error'] in ['SQLException','SQLSyntaxErrorException']
            assert self.search(666) is not None
            self.record('真实 MySQL 读取失败不会误删 ES 文档',error_type=failed['error'])
        finally:
            self.sql(f"GRANT SELECT ON `{self.database}`.* TO 'app_sync'@'%';")
        self.control('maintain',mode='repair',confirmed=True); self.visible(666,None)
        for variable,invalid,valid in [('binlog_row_image','MINIMAL','FULL'),('binlog_format','STATEMENT','ROW')]:
            self.sql(f"SET GLOBAL {variable}='{invalid}';")
            try:
                self.control('start')
                state=self.wait(lambda:(s if (s:=self.control('status'))['state']=='BLOCKED' else False),10,'不支持的源参数')
                assert 'ROW/FULL' in state['reason']
                self.record('源运行参数阻塞-'+variable)
            finally:
                self.control('stop'); self.sql(f"SET GLOBAL {variable}='{valid}';")
        self.command(['docker','stop','--time','20',self.canal])
        metadata=self.folder/'state/question-sync/meta.dat'; backup=self.evidence/f'metadata-loss-recovery-{self.probe_attempt}.json'
        metadata.rename(backup)
        try:
            self.control('start')
            state=self.wait(lambda:(s if (s:=self.control('status'))['state']=='BLOCKED' else False),10,'既有游标丢失')
            assert '元数据丢失' in state['reason']
            self.record('已建立位点丢失不回退初始 P')
        finally:
            self.control('stop'); shutil.copy2(backup,metadata)
            self.command(['docker','start',self.canal]); self.canal_port=self.port(self.canal,11111)
            self.wait(self.canal_ready,60,'恢复测试元数据')

    def response_timeout(self):
        self.control('start')
        self.change('响应超时前的健康基线',"UPDATE tb_question SET title='响应超时测试基线' WHERE question_id=400;",400,'响应超时测试基线')
        self.control('fault',mode='response-timeout')
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='已落盘但响应被延迟' WHERE question_id=400;")
        self.wait(lambda:(self.folder/'probe/timeout-backend-applied.json').exists(),10,'真实 ES 已处理 bulk')
        self.sql(f"USE {self.database}; UPDATE tb_question SET title='请求超时重放后的最新值' WHERE question_id=400;")
        state=self.wait(lambda:(s if (s:=self.control('status'))['requestTimeouts']==1 else False),15,'真正的 SocketTimeoutException')
        assert state['timeoutPreviousAck'] != state['timeoutBatch'] and state['timeoutElapsedMillis']>=5000
        self.visible(400,'请求超时重放后的最新值',20)
        final=self.wait(lambda:(s if (s:=self.control('status'))['state']=='RUNNING' and s['lastAck']>state['timeoutBatch'] else False),20,'超时批次成功重放并 ACK')
        assert final['questionReads']==0
        self.record('真实 ES 已写入但响应读取超时，不 ACK 并重放追平',exception='SocketTimeoutException',
            elapsed_ms=state['timeoutElapsedMillis'],failed_batch=state['timeoutBatch'],previous_ack=state['timeoutPreviousAck'],final_ack=final['lastAck'])
        self.control('stop')

    def collect(self):
        self.command(['docker','exec',self.canal,'sh','-c','tail -n 200 /home/logs/question-sync/question-sync.log'],expected=None)
        for name in [self.canal,self.mysql,self.es]:
            self.command(['docker','logs','--tail','300',name],expected=None)
        if self.probe is not None and self.probe.poll() is None:
            try: self.control('quit'); self.probe.wait(timeout=40)
            except Exception: pass

    def cleanup(self):
        # 仅按登记 ID 删除，且再次核对专用标签；不按名称前缀或全局 prune。
        for resource in reversed(self.resources):
            kind=resource['type']; identity=resource['id']
            check=subprocess.run(['docker',kind if kind=='network' else 'container','inspect',identity],text=True,capture_output=True)
            if check.returncode: continue
            state=json.loads(check.stdout)[0]
            labels=state.get('Labels',{}) if kind=='network' else state.get('Config',{}).get('Labels',{})
            if labels.get('codex.task')!=TASK or labels.get('codex.run')!=self.label: raise RuntimeError('拒绝清理未匹配标签的资源')
            self.command(['docker','network','rm',identity] if kind=='network' else ['docker','rm','-f',identity])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',required=True)
    parser.add_argument('--keep',action='store_true',help='失败调查时保留本轮隔离服务')
    parser.add_argument('--cleanup-only',action='store_true',help='只清理该轮已登记且标签匹配的资源')
    parser.add_argument('--boot-only',action='store_true',help='对保留的隔离服务补验维护 JAR，不重跑无关同步场景')
    parser.add_argument('--edges-only',action='store_true',help='在保留的隔离环境建立新基线后补验失败边界')
    parser.add_argument('--timeout-only',action='store_true',help='补验真实 ES 已处理请求但响应读取超时后的重放')
    args=parser.parse_args()
    if args.cleanup_only or args.boot_only or args.edges_only or args.timeout_only:
        if not re.fullmatch(r'[a-z0-9-]+',args.run): raise ValueError('无效测试标签')
        test=Integration.__new__(Integration); test.label=args.run; test.folder=BASE/'integration'/args.run
        saved=json.loads((test.folder/'run.json').read_text()); test.evidence=test.folder/'evidence'
        test.resources=saved['resources']; test.commands=saved['commands']; test.results=saved['results']
        test.started_at=saved.get('started_at'); test.finished_at=saved.get('finished_at'); test.exit_code=saved.get('exit_code')
        test.continuations=saved.get('continuations',[])
        test.password=test.source_password=test.app_password=''
        if args.boot_only or args.edges_only or args.timeout_only:
            connection=dict(line.split('=',1) for line in (test.folder/'probe/connection.properties').read_text().splitlines())
            test.source_password=connection['source.password']; test.app_password=connection['app.password']
            test.password=(test.folder/'mysql.env').read_text().strip().split('=',1)[1]
            test.database=connection['database']; test.network='codex-canal-'+args.run
            test.canal=test.network+'-canal'; test.mysql=test.network+'-mysql'; test.es=test.network+'-es'
            started=datetime.datetime.now(datetime.timezone.utc).isoformat(); status=1
            try:
                if args.edges_only or args.timeout_only:
                    test.probe=None; test.sequence=0
                    test.probe_attempt=max([int(item['name'].rsplit('-',1)[1]) for item in test.resources
                        if re.fullmatch('codex-canal-'+re.escape(args.run)+r'-probe-[0-9]+',item['name'])],default=0)
                    test.baseline_number=max(int(path.stem.rsplit('-',1)[1]) for path in test.evidence.glob('initial-position-*.json'))
                    test.es_url='http://127.0.0.1:'+str(test.port(test.es,9200))
                    test.start_probe(); test.rebaseline('响应超时补验' if args.timeout_only else '失败边界补验')
                    if args.timeout_only: test.response_timeout()
                    else: test.edges()
                else: test.boot_maintenance()
                status=0
            except Exception: print(test.redact(traceback.format_exc()),file=sys.stderr)
            finally:
                if args.edges_only or args.timeout_only: test.collect()
                test.continuations.append({'stage':'timeout-only' if args.timeout_only else ('edges-only' if args.edges_only else 'boot-only'),'started_at':started,
                    'finished_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exit_code':status})
                test.save()
            return status
        test.cleanup(); test.record('隔离资源清理完成'); return 0
    test=Integration(args.run)
    status=1
    try:
        test.setup(); test.core(); test.extended(); test.response_timeout(); test.edges()
        test.migrate_state(); test.drain_writer_before_maintenance(); test.boot_maintenance()
        assert test.control('status')['gateEnabled']
        test.control('start')
        assert test.control('gate', enabled=False)['acceptsNewWrite']
        test.change('维护后恢复正常同步', "UPDATE tb_question SET title='维护后最新题目' WHERE question_id=400;", 400, '维护后最新题目')
        test.control('stop'); status=0
    except Exception as failure:
        test.results.append({'name':'执行中断','status':'failed','reason':test.redact(traceback.format_exc())}); test.save()
        print(test.redact(str(failure)),file=sys.stderr,flush=True)
    finally:
        test.collect()
        if not args.keep: test.cleanup()
        test.finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(); test.exit_code=status
        test.save()
    return status


if __name__=='__main__': sys.exit(main())
