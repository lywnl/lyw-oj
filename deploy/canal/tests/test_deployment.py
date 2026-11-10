"""核对实际 Compose 渲染与维护脚本参数；临时文件只放项目 .codex。"""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        base = ROOT / '.codex/canal-question-es-sync/config-tests'
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=base)
        self.folder = Path(self.temporary.name)
        self.empty_env = self.folder / 'empty.env'
        self.empty_env.write_text('', encoding='utf-8')

    def tearDown(self): self.temporary.cleanup()

    def compose(self, environment, **overrides):
        variables = dict(os.environ, CANAL_STATE_DIR='', CANAL_CONFIG_DIR='')
        variables.update(overrides)
        result = subprocess.run(['docker', 'compose', '--env-file', str(self.empty_env), '-f',
                                 str(ROOT / 'deploy' / environment / 'docker-compose.yml'),
                                 '--profile', 'question-sync', 'config', '--format', 'json'],
                                cwd=ROOT, env=variables, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, 'Compose 渲染失败；不得启动服务代替配置检查')
        return json.loads(result.stdout)['services']

    @staticmethod
    def volumes(service): return {item['target']: item for item in service['volumes']}

    def assert_shared_state(self, services, state, config):
        canal = self.volumes(services['oj-canal-server'])
        application = self.volumes(services['oj-system'])
        self.assertEqual(str(state), canal['/state']['source'])
        self.assertEqual(str(state), application['/canal-state']['source'])
        self.assertTrue(application['/canal-state']['read_only'])
        self.assertFalse(canal['/state'].get('read_only', False))
        self.assertEqual(str(config), canal['/config']['source'])
        self.assertTrue(canal['/config']['read_only'])

    def test_production_defaults_use_formal_shared_directories(self):
        self.assert_shared_state(self.compose('prod'), ROOT / 'deploy/prod/canal/state', ROOT / 'deploy/prod/canal/config')

    def test_production_overrides_still_share_one_state(self):
        state, config = self.folder / 'state', self.folder / 'config'
        self.assert_shared_state(self.compose('prod', CANAL_STATE_DIR=str(state), CANAL_CONFIG_DIR=str(config)), state, config)

    def test_development_keeps_its_isolated_directories(self):
        canal = self.volumes(self.compose('dev')['oj-canal-server'])
        self.assertEqual(str(ROOT / '.codex/canal/dev/state'), canal['/state']['source'])
        self.assertEqual(str(ROOT / '.codex/canal/dev/config'), canal['/config']['source'])

    def test_generated_production_files_are_ignored(self):
        paths = ['deploy/prod/canal/state/question-sync/meta.dat', 'deploy/prod/canal/config/instance.properties',
                 'deploy/prod/canal/backups/state/meta.dat']
        result = subprocess.run(['git', 'check-ignore', '--no-index', *paths], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(0, result.returncode)
        self.assertEqual(set(paths), set(result.stdout.splitlines()))

    def test_maintenance_script_requires_confirmation_without_setting_online_gate(self):
        java = self.folder / 'java'
        java.write_text('#!/bin/sh\nprintf \'%s\\n\' "$@" > "$CANAL_TEST_ARGUMENTS"\n', encoding='utf-8')
        java.chmod(0o755)
        jar = self.folder / 'test.jar'; jar.touch()
        arguments = self.folder / 'arguments.txt'
        environment = dict(os.environ, PATH=str(self.folder) + os.pathsep + os.environ.get('PATH', ''),
                           CANAL_TEST_ARGUMENTS=str(arguments))
        command = ['sh', str(ROOT / 'deploy/canal/maintain.sh')]
        failed = subprocess.run(command + ['repair', str(jar)], env=environment, capture_output=True)
        self.assertEqual(2, failed.returncode); self.assertFalse(arguments.exists())
        for mode in ['check', 'repair']:
            result = subprocess.run(command + [mode, str(jar)] + (['--confirm-maintenance'] if mode == 'repair' else []),
                                    env=environment, capture_output=True)
            self.assertEqual(0, result.returncode)
            values = arguments.read_text().splitlines()
            self.assertIn('--canal.sync.enabled=false', values)
            self.assertIn('--canal.sync.mode=' + mode, values)
            self.assertIn('--canal.sync.maintenance-confirmed=' + str(mode == 'repair').lower(), values)
            self.assertFalse(any(value.startswith('--question.maintenance.enabled') for value in values))


if __name__ == '__main__': unittest.main()
