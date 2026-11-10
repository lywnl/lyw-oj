from pathlib import Path
import datetime, hashlib, json, os, re, shutil, subprocess, sys

project = Path(__file__).resolve().parents[3]
base = project / '.codex/canal-question-es-sync'
if len(sys.argv) < 3: raise SystemExit('用法：python3 run_maven.py 唯一英文标签 Maven参数')
label = sys.argv[1]
if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', label): raise SystemExit('测试标签只能包含小写字母、数字和短横线')
build = base / 'builds' / label
cache = base / 'maven-cache'
evidence = base / 'evidence'
if build.exists() or (evidence / (label + '-command.json')).exists(): raise SystemExit('标签已使用，请换新标签以保留既有证据')
for path in [build, cache, evidence]: path.mkdir(parents=True, exist_ok=True)
(base / 'tmp').mkdir(exist_ok=True)
for name in ['oj-api', 'oj-gateway', 'oj-modules', 'oj-commom']:
    target = build / name
    if target.exists(): shutil.rmtree(target)
    shutil.copytree(project / name, target, ignore=shutil.ignore_patterns('target', '.DS_Store', 'user-code-pool'))
shutil.copy2(project / 'pom.xml', build / 'pom.xml')
snapshot = {}
for path in sorted(build.rglob('*')):
    if path.is_file(): snapshot[str(path.relative_to(build))] = hashlib.sha256(path.read_bytes()).hexdigest()
(evidence / (label + '-sources.json')).write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding='utf-8')
name = 'codex-canal-' + label
cmd = ['docker', 'create', '--name', name, '--label', 'codex.task=canal-question-es-sync',
       '--memory', '1536m', '-e', 'MAVEN_OPTS=-Xmx768m',
       '--mount', f'type=bind,src={build},dst=/workspace',
       '--mount', f'type=bind,src={cache},dst=/maven',
       '--mount', f'type=bind,src={base},dst=/evidence',
       '-w', '/workspace']
network = os.environ.get('SYNC_TEST_NETWORK')
if network: cmd += ['--network', network]
run_label = os.environ.get('SYNC_TEST_RUN')
if run_label: cmd += ['--label', 'codex.run=' + run_label]
cmd += ['maven:3.9.9-eclipse-temurin-17', 'mvn', '-B', '--no-transfer-progress', '-Dmaven.repo.local=/maven', '-Djava.io.tmpdir=/evidence/tmp'] + sys.argv[2:]
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
cid = subprocess.check_output(cmd, text=True).strip()
with (base / 'containers.jsonl').open('a', encoding='utf-8') as output:
    output.write(json.dumps({'id': cid, 'name': name, 'role': label}) + '\n')
with (evidence / (label + '.log')).open('w', encoding='utf-8') as output:
    attach = subprocess.run(['docker', 'start', '-a', cid], stdout=output, stderr=subprocess.STDOUT)
code = int(subprocess.check_output(['docker', 'inspect', '-f', '{{.State.ExitCode}}', cid], text=True).strip())
record = {'command': cmd, 'cwd': str(project), 'started_at': started,
          'finished_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'exit_code': code, 'attach_exit_code': attach.returncode, 'container': cid,
          'source_snapshot': label + '-sources.json', 'evidence': label + '.log'}
(evidence / (label + '-command.json')).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
for folder in build.rglob('surefire-reports'):
    destination = evidence / label / folder.parent.parent.relative_to(build)
    shutil.copytree(folder, destination, dirs_exist_ok=True)
print(f'{label}: exit={code}; log={evidence / (label + ".log")}')
sys.exit(code)
