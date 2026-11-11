#!/usr/bin/env python3
"""隔离验证真实 XXL-JOB 调度；不连接业务 Nacos、MySQL、Canal 或 ES。"""
from pathlib import Path
import argparse
import datetime
import hashlib
import http.cookiejar
import json
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
SCHEMA_URL = 'https://raw.githubusercontent.com/xuxueli/xxl-job/2.4.0/doc/db/tables_xxl_job.sql'
MAVEN_IMAGE = 'maven:3.9.9-eclipse-temurin-17'
ADMIN_IMAGE = 'xuxueli/xxl-job-admin:2.4.0'
MYSQL_IMAGE = 'mysql:8.0.40'


class Verification:
    def __init__(self, label):
        self.label = label
        self.folder = ROOT / '.codex/canal-reconciliation-xxl-job' / label
        self.folder.mkdir(parents=True, exist_ok=False)
        self.evidence = self.folder / 'evidence'; self.evidence.mkdir()
        self.runtime = self.folder / 'runtime'; self.runtime.mkdir()
        self.temporary = self.folder / 'tmp'; self.temporary.mkdir()
        self.containers = []; self.network = None; self.new_images = []
        self.container_baseline = set(subprocess.check_output(['docker', 'ps', '-aq', '--no-trunc'], cwd=ROOT, text=True).splitlines())
        self.build_label = 'xxl-' + label
        self.build = ROOT / '.codex/canal-question-es-sync/builds' / self.build_label
        self.password = secrets.token_hex(20)
        self.internal_token = secrets.token_hex(24)
        self.executor_token = secrets.token_hex(24)
        self.admin_password = secrets.token_hex(16)
        self.private = [self.password, self.internal_token, self.executor_token, self.admin_password]
        self.report = {'started_at': self.now(), 'run': label, 'checks': [], 'commands': [], 'images': {},
                       'boundary': '真实 Admin/执行器/Feign/HTTP/Runner；对账读写为内存替身'}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    @staticmethod
    def now():
        return datetime.datetime.now(datetime.timezone.utc).isoformat()

    def clean_text(self, text):
        for value in self.private: text = text.replace(value, '<测试凭据已隐藏>')
        return text

    def save(self):
        (self.evidence / 'result.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding='utf-8')

    def command(self, command, timeout=120, data=None, check=True, name=None):
        started = self.now()
        result = subprocess.run(command, cwd=ROOT, input=data, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        output = self.clean_text(result.stdout)
        log = self.evidence / (name or f'command-{len(self.report["commands"]):03d}.log')
        log.write_text(output, encoding='utf-8')
        self.report['commands'].append({'command': self.clean_text(shlex.join(command)), 'cwd': str(ROOT),
                                       'started_at': started, 'finished_at': self.now(),
                                       'exit_code': result.returncode, 'evidence': str(log.relative_to(ROOT))})
        self.save()
        if check and result.returncode:
            raise RuntimeError(f'命令失败，退出码 {result.returncode}，查看 {log.relative_to(ROOT)}')
        return result.stdout.strip()

    def wait(self, predicate, seconds, description):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = predicate()
                if value: return value
            except (OSError, ValueError, urllib.error.URLError):
                pass
            time.sleep(0.2)
        raise AssertionError('等待超时：' + description)

    def record(self, description, **details):
        self.report['checks'].append({'name': description, 'status': 'passed', **details})
        self.save(); print(description, flush=True)

    def create(self, role, image, options, command):
        name = 'codex-' + self.label + '-' + role
        platform = self.report['images'][image]['os'] + '/' + self.report['images'][image]['architecture']
        output = self.command(['docker', 'create', '--platform', platform, '--name', name, '--label', 'codex.run=' + self.label,
                            *options, image, *command])
        ids = re.findall(r'(?m)^[0-9a-f]{64}$', output)
        if len(ids) != 1: raise RuntimeError('容器创建结果缺少明确 ID，请核对本轮容器名称')
        cid = ids[0]
        self.containers.append({'id': cid, 'name': name, 'role': role})
        self.report['containers'] = self.containers; self.save()
        return cid

    def start(self, cid):
        self.command(['docker', 'start', cid])

    def ensure_images(self):
        existing = set(self.command(['docker', 'images', '--no-trunc', '--format', '{{.ID}}']).splitlines())
        self.report['image_baseline'] = sorted(existing)
        for image in [MAVEN_IMAGE, ADMIN_IMAGE, MYSQL_IMAGE]:
            result = subprocess.run(['docker', 'image', 'inspect', image], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
            if result.returncode: self.command(['docker', 'pull', image], timeout=600)
            info = json.loads(self.command(['docker', 'image', 'inspect', image]))[0]
            self.report['images'][image] = {'id': info['Id'], 'architecture': info['Architecture'], 'os': info['Os']}
            if info['Id'] not in existing: self.new_images.append(info['Id'])
        self.save()

    def build_code(self):
        args = [sys.executable, str(ROOT / 'deploy/canal/tests/run_maven.py'), self.build_label,
                '-pl', 'oj-modules/oj-system,oj-modules/oj-job,oj-gateway', '-am', 'install',
                '-Dspring-boot.repackage.skip=true', '-Dtest=*Test,!*ProbeTest', '-Dsurefire.failIfNoSpecifiedTests=false']
        try:
            self.command(args, timeout=600, name='build-launch.log')
        finally:
            inspect = subprocess.run(['docker', 'inspect', '-f', '{{.Id}}', 'codex-canal-' + self.build_label],
                                     cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            if inspect.returncode == 0 and inspect.stdout.strip() not in self.container_baseline:
                self.containers.append({'id': inspect.stdout.strip(), 'name': 'codex-canal-' + self.build_label, 'role': 'build'})
                self.report['containers'] = self.containers; self.save()
        cache = ROOT / '.codex/canal-question-es-sync/maven-cache'
        build_id = self.create('classpath', MAVEN_IMAGE, [
            '--mount', f'type=bind,src={self.build},dst=/workspace',
            '--mount', f'type=bind,src={cache},dst=/maven', '-w', '/workspace',
            '--memory', '1024m', '-e', 'MAVEN_OPTS=-Xmx512m'], [
            'mvn', '-B', '--no-transfer-progress', '-Dmaven.repo.local=/maven',
            '-pl', 'oj-modules/oj-system,oj-modules/oj-job', '-Dmdep.includeScope=test',
            '-Dmdep.outputFile=target/test-classpath.txt', 'dependency:build-classpath'])
        self.command(['docker', 'start', '-a', build_id], timeout=600, name='classpath.log')
        assert self.command(['docker', 'inspect', '-f', '{{.State.ExitCode}}', build_id]) == '0'
        for module in ['oj-system', 'oj-job']:
            assert (self.build / f'oj-modules/{module}/target/test-classpath.txt').is_file()
        self.record('JDK 17 构建、单元与 HTTP 回归通过',
                    maven_evidence=f'.codex/canal-question-es-sync/evidence/{self.build_label}.log')

    def schema(self):
        content = None
        for proxy in [{}, {'https': 'http://127.0.0.1:7897'}]:
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxy))
                with opener.open(SCHEMA_URL, timeout=20) as response: content = response.read()
                break
            except (OSError, urllib.error.URLError):
                continue
        if content is None: raise RuntimeError('无法取得官方 XXL-JOB 2.4.0 测试表结构')
        self.report['schema'] = {'url': SCHEMA_URL, 'sha256': hashlib.sha256(content).hexdigest()}
        sql = content.decode('utf-8')
        sql += "\nUPDATE xxl_job_user SET password='" + hashlib.md5(self.admin_password.encode()).hexdigest() + "' WHERE id=1;\n"
        sql += ("UPDATE xxl_job_group SET app_name='" + self.label + "',address_type=1,address_list='http://" + self.runtime_host + ":9999' WHERE id=1;\n"
                "UPDATE xxl_job_info SET job_desc='隔离题目对账',executor_handler='questionIndexReconcileHandler',"
                "schedule_conf='0 0 2 * * ?',executor_route_strategy='FIRST',executor_block_strategy='DISCARD_LATER',"
                "misfire_strategy='DO_NOTHING',executor_timeout=720,executor_fail_retry_count=0,trigger_status=0 WHERE id=1;\n")
        path = self.temporary / 'schema.sql'; path.write_text(sql, encoding='utf-8')
        return path

    def sql(self, query):
        # 仅连接本脚本创建的隔离 MySQL，无宿主机或业务地址。
        result = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + self.password, self.mysql,
                                 'mysql', '-uroot', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', 'xxl_job'],
                                cwd=ROOT, input=query, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
        if result.returncode: return None
        return result.stdout.strip()

    def http(self, url, data=None, headers=None, timeout=10):
        # 控制请求也在 internal 网络内发出，不为测试容器增加外网出口或宿主机端口。
        args = ['docker', 'exec', '-i', self.backend, 'curl', '--silent', '--show-error', '--noproxy', '*',
                '--connect-timeout', '2', '--max-time', str(timeout), '--cookie-jar', '/evidence/tmp/cookies.txt',
                '--cookie', '/evidence/tmp/cookies.txt', '--write-out', '\n%{http_code}']
        for key, value in (headers or {}).items(): args += ['--header', key + ': ' + value]
        if data is not None: args += ['--data-binary', '@-']
        args.append(url)
        result = subprocess.run(args, cwd=ROOT, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout + 5)
        if result.returncode: raise OSError('隔离网络 HTTP 请求未完成')
        body, status = result.stdout.rsplit(b'\n', 1)
        return int(status), body

    def start_environment(self):
        self.network = self.command(['docker', 'network', 'create', '--internal', '--label', 'codex.run=' + self.label, 'codex-' + self.label])
        self.report['network'] = self.network; self.save()
        assert self.command(['docker', 'network', 'inspect', '-f', '{{.Internal}}', self.network]) == 'true'
        self.report['network_internal'] = True; self.save()
        self.runtime_host = 'codex-' + self.label + '-runtime'
        mysql_host = 'codex-' + self.label + '-mysql'
        self.mysql = self.create('mysql', MYSQL_IMAGE, ['--network', self.network, '--memory', '1024m',
            '--tmpfs', '/var/lib/mysql:rw', '-e', 'MYSQL_ROOT_PASSWORD=' + self.password,
            '--mount', f'type=bind,src={self.schema()},dst=/docker-entrypoint-initdb.d/schema.sql,readonly'],
            ['--innodb-buffer-pool-size=64M', '--max-connections=40', '--performance-schema=OFF'])
        self.start(self.mysql)
        self.wait(lambda: self.sql('SELECT COUNT(*) FROM xxl_job_info;') == '1', 100, '隔离 MySQL 初始化')
        admin_env = self.temporary / 'admin.env'
        admin_env.write_text('\n'.join([
            'TZ=Asia/Shanghai', 'JAVA_TOOL_OPTIONS=-Xms64m -Xmx384m -Duser.timezone=Asia/Shanghai',
            'SPRING_DATASOURCE_URL=jdbc:mysql://' + mysql_host + ':3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai',
            'SPRING_DATASOURCE_USERNAME=root', 'SPRING_DATASOURCE_PASSWORD=' + self.password,
            'PARAMS=--xxl.job.accessToken=' + self.executor_token]) + '\n', encoding='utf-8')
        self.admin = self.create('admin', ADMIN_IMAGE, ['--network', self.network, '--memory', '768m',
                    '--env-file', str(admin_env)], [])
        self.start(self.admin)
        cache = ROOT / '.codex/canal-question-es-sync/maven-cache'
        self.backend = self.create('runtime', MAVEN_IMAGE, ['--network', self.network, '--memory', '1536m',
            '--mount', f'type=bind,src={self.build},dst=/workspace,readonly',
            '--mount', f'type=bind,src={cache},dst=/maven,readonly',
            '--mount', f'type=bind,src={self.folder},dst=/evidence'], ['sleep', '1800'])
        self.start(self.backend)
        self.command(['docker', 'exec', self.backend, 'curl', '--version'])
        java_version = self.command(['docker', 'exec', self.backend, 'java', '-version'], name='jdk17-runtime.log')
        assert 'version "17.' in java_version
        self.report['runtime_java'] = java_version; self.save()
        self.admin_url = 'http://codex-' + self.label + '-admin:8080/xxl-job-admin'
        self.wait(lambda: self.http(self.admin_url + '/toLogin')[0] == 200, 140, '真实 XXL-JOB Admin 启动')
        status, body = self.http(self.admin_url + '/login', urllib.parse.urlencode({'userName': 'admin', 'password': self.admin_password}).encode())
        assert status == 200 and json.loads(body)['code'] == 200
        common = ['-Dspring.cloud.bootstrap.enabled=false', '-Dprobe.directory=/evidence/runtime',
                  '-Dspring.cloud.nacos.config.enabled=false', '-Dspring.cloud.nacos.discovery.enabled=false',
                  '-Dspring.cloud.nacos.config.import-check.enabled=false',
                  '-Dquestion.reconcile.enabled=true', '-Dquestion.reconcile.internal-token=' + self.internal_token,
                  '-Dquestion.reconcile.timeout-millis=3000']
        self.java('oj-system', 'com.lyw.system.questionsync.QuestionReconcileHttpProbe', common)
        self.wait(lambda: self.system_status().get('state') == 'RUNNING', 40, '系统 HTTP/Runner 启动')
        self.java('oj-job', 'com.lyw.job.config.QuestionReconcileJobProbe', common + [
            '-Dprobe.runtime-host=' + self.runtime_host,
            '-Dxxl.job.admin.addresses=http://codex-' + self.label + '-admin:8080/xxl-job-admin',
            '-Dxxl.job.executor.appname=' + self.label, '-Dxxl.job.accessToken=' + self.executor_token,
            '-Dquestion.reconcile.target-url=http://' + self.runtime_host + ':8081'])
        self.wait(lambda: (self.runtime / 'job-ready').exists(), 40, 'oj-job 执行器启动')
        executor_url = 'http://' + self.runtime_host + ':9999/beat'
        self.wait(lambda: self.http(executor_url, b'{}', {'XXL-JOB-ACCESS-TOKEN': self.executor_token, 'Content-Type': 'application/json'})[0] == 200,
                  15, '执行器监听')
        self.system_url = 'http://' + self.runtime_host + ':8081/internal/question-index/reconcile'

    def java(self, module, main, properties):
        classpath = (self.build / f'oj-modules/{module}/target/test-classpath.txt').read_text().strip()
        classpath = f'/workspace/oj-modules/{module}/target/test-classes:/workspace/oj-modules/{module}/target/classes:' + classpath
        args = ['java', '-Xmx256m', *properties, '-cp', classpath, main]
        command = 'exec ' + shlex.join(args) + ' > ' + shlex.quote('/evidence/evidence/' + module + '-runtime.log') + ' 2>&1'
        self.command(['docker', 'exec', '-d', self.backend, 'sh', '-c', command])

    def system_status(self):
        path = self.runtime / 'system-status.json'
        return json.loads(path.read_text()) if path.exists() else {}

    def mode(self, value):
        temporary = self.runtime / 'mode.new'; temporary.write_text(value, encoding='utf-8')
        temporary.replace(self.runtime / 'mode.txt')
        self.wait(lambda: self.system_status().get('mode') == value, 5, '设置隔离数据模式')

    def trigger(self):
        previous = int(self.sql('SELECT COALESCE(MAX(id),0) FROM xxl_job_log;') or '0')
        status, body = self.http(self.admin_url + '/jobinfo/trigger', urllib.parse.urlencode({'id': 1, 'executorParam': ''}).encode())
        assert status == 200 and json.loads(body)['code'] == 200
        return int(self.wait(lambda: self.sql(f'SELECT id FROM xxl_job_log WHERE id>{previous} ORDER BY id LIMIT 1;'), 10, 'Admin 创建实际执行记录'))

    def log(self, log_id):
        raw = self.sql(f'SELECT trigger_code,handle_code,COALESCE(handle_msg,\'\') FROM xxl_job_log WHERE id={log_id};')
        if not raw: return {}
        parts = raw.split('\t', 2)
        return {'id': log_id, 'trigger_code': int(parts[0]), 'handle_code': int(parts[1]), 'handle_msg': parts[2] if len(parts) > 2 else ''}

    def tests(self):
        before = self.system_status()['starts']
        for headers in [{}, {'X-Question-Reconcile-Token': 'wrong'}, {'Authorization': 'Bearer ordinary-user'}]:
            status, body = self.http(self.system_url, b'', headers)
            assert status == 403 and json.loads(body)['data'] is None
        assert self.system_status()['starts'] == before
        self.record('真实 HTTP 拒绝缺失、错误和用户令牌，未触发对账')

        success_id = self.trigger()
        success = self.wait(lambda: (value if (value := self.log(success_id)).get('handle_code') else None), 20, '成功回调')
        self.report['first_callback'] = success; self.save()
        assert success['trigger_code'] == 200 and success['handle_code'] == 200
        assert '修复=1' in success['handle_msg']
        self.record('真实 Admin→执行器→Feign→HTTP→Runner 完成并成功回调', **success)

        self.mode('HOLD'); held = self.trigger()
        self.wait(lambda: self.system_status().get('reconciliationActive'), 5, '对账正在执行')
        assert self.log(held)['handle_code'] == 0
        status, body = self.http(self.system_url, b'', {'X-Question-Reconcile-Token': self.internal_token})
        assert status == 409 and json.loads(body)['data'] is None
        # 平台的丢弃策略也须由真实执行器处理。
        duplicate = self.trigger()
        discarded = self.wait(lambda: (value if (value := self.log(duplicate)).get('trigger_code') == 500 else None), 10, '丢弃重叠调度')
        failed = self.wait(lambda: (value if (value := self.log(held)).get('handle_code') else None), 15, '超时失败回调')
        assert failed['handle_code'] == 500
        self.record('运行中不提前成功，内部并发返回 409，平台丢弃重叠调度', duplicate_log=discarded)
        self.record('服务端缩短预算后真实平台收到失败回调', timeout_millis=3000, **failed)
        self.mode('NORMAL')
        self.wait(lambda: not self.system_status().get('reconciliationActive'), 10, '取消后清理完成')
        assert self.system_status()['state'] == 'RUNNING'

        self.mode('FAIL'); failure_id = self.trigger()
        failure = self.wait(lambda: (value if (value := self.log(failure_id)).get('handle_code') else None), 15, '存储异常回调')
        assert failure['handle_code'] == 500
        self.mode('NORMAL'); self.wait(lambda: self.system_status()['state'] == 'RUNNING', 10, '增量循环恢复')
        recovery_id = self.trigger()
        recovery = self.wait(lambda: (value if (value := self.log(recovery_id)).get('handle_code') else None), 15, '恢复后成功回调')
        assert recovery['handle_code'] == 200
        assert self.system_status()['writerThreads'] == ['question-es-sync']
        self.report['final_system_status'] = self.system_status(); self.save()
        self.record('存储故障失败回传，增量恢复后再次对账成功，读写仍在原消费线程', failure=failure, recovery=recovery)

        status, body = self.http(self.admin_url + '/jobinfo/nextTriggerTime', urllib.parse.urlencode({
            'scheduleType': 'CRON', 'scheduleConf': '0 0 2 * * ?'}).encode())
        next_times = json.loads(body)
        assert status == 200 and next_times['code'] == 200
        assert all(value.endswith('02:00:00') for value in next_times['content'])
        self.record('真实 Admin 解析 Cron，后续触发时刻均为北京时间 02:00', next_times=next_times['content'])
        self.report['production_defaults'] = {'server_timeout_millis': 600000, 'feign_read_millis': 660000, 'job_timeout_seconds': 720}
        self.report['summary'] = 'passed'; self.save()

    def cleanup(self):
        residuals = []
        (self.runtime / 'stop').touch()
        time.sleep(1)
        for item in reversed(self.containers):
            cid = item['id']
            self.command(['docker', 'logs', cid], check=False, name=item['role'] + '-container.log')
            self.command(['docker', 'stop', '-t', '8', cid], check=False)
            self.command(['docker', 'rm', '-fv', cid], check=False)
            result = subprocess.run(['docker', 'ps', '-aq', '--no-trunc'], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode or cid in result.stdout.splitlines(): residuals.append('container-or-unknown:' + cid)
        if self.network:
            self.command(['docker', 'network', 'rm', self.network], check=False)
            result = subprocess.run(['docker', 'network', 'ls', '-q', '--no-trunc'], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode or self.network in result.stdout.splitlines(): residuals.append('network-or-unknown:' + self.network)
        for image in self.new_images:
            in_use = subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'ancestor=' + image], cwd=ROOT, text=True).strip()
            if in_use: residuals.append('image-in-use:' + image)
            else: self.command(['docker', 'rmi', image], check=False)
        # 有证据引用的原始日志保留；独立运行目录和构建副本删除。
        for path in [self.temporary, self.runtime, self.build]:
            if path.exists():
                try: shutil.rmtree(path)
                except OSError: residuals.append(str(path.relative_to(ROOT)))
        for path in self.evidence.glob('*.log'):
            path.write_text(self.clean_text(path.read_text(encoding='utf-8', errors='replace')), encoding='utf-8')
        self.report['cleanup'] = {'residuals': residuals, 'shared_maven_cache': '复用已有项目缓存，保留'}
        self.report['finished_at'] = self.now(); self.save()

    def run(self):
        try:
            self.ensure_images(); self.build_code(); self.start_environment(); self.tests()
        except BaseException as failure:
            self.report['summary'] = 'failed'
            self.report['failure'] = {'type': type(failure).__name__, 'message': self.clean_text(str(failure))}
            self.save(); raise
        finally:
            self.cleanup()
            print('报告：' + str(self.evidence / 'result.json'), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', default=datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3))
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,48}', args.run_id): parser.error('run-id 必须为小写字母、数字、短横线，最长 49 位')
    Verification(args.run_id).run()


if __name__ == '__main__':
    main()
