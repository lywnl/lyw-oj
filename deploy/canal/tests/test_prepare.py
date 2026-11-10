"""配置生成边界；所有临时文件位于项目 .codex。"""
from pathlib import Path
import importlib.util
import os
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('canal_prepare', ROOT / 'deploy/canal/prepare.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareTest(unittest.TestCase):
    def setUp(self):
        base = ROOT / '.codex/canal-question-es-sync/config-tests'
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=base)
        self.output = Path(self.temporary.name) / 'config'

    def tearDown(self): self.temporary.cleanup()

    def generate(self, password, env='dev', **extra):
        with patch.dict(os.environ, {'CANAL_DB_PASSWORD': password, **extra}, clear=True):
            return prepare.prepare(self.output, env, 'mysql-bin.000001', 157, 1794247259000)

    def test_actual_report_password_byte_limit_and_no_silent_truncation(self):
        self.generate('x' * 32)
        with self.assertRaisesRegex(ValueError, '32'): self.generate('x' * 33)
        self.generate('中' * 10)
        with self.assertRaisesRegex(ValueError, '32'): self.generate('中' * 11)

    def test_production_never_infers_database_from_nacos(self):
        with self.assertRaises(ValueError): self.generate('synthetic-password', 'prod', MYSQL_SERVICE_DB_NAME='nacos')
        summary = self.generate('synthetic-password', 'prod', CANAL_DB_NAME='business')
        self.assertEqual('business', summary['database'])

    def test_restricted_account_tsdb_blacklist_and_persistent_paths(self):
        self.generate('synthetic-password')
        text = (self.output / 'instance.properties').read_text()
        self.assertIn('canal.instance.filter.black.regex=', text)
        self.assertIn('performance_schema', text)
        self.assertIn('canal.instance.master.timestamp=1794247259000', text)
        self.assertIn('lywoj\\\\.tb_question', text)
        sql = (self.output / 'account.sql').read_text()
        self.assertIn('GRANT SELECT ON `lywoj`.*', sql)
        self.assertNotIn('GRANT SELECT ON *.*', sql)
        self.assertEqual(0o600, (self.output / 'instance.properties').stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.output / 'account.sql').stat().st_mode & 0o777)
        self.generate('synthetic-password', CANAL_DB_NAME='business_db')
        self.assertIn('GRANT SELECT ON `business\\_db`.*', (self.output / 'account.sql').read_text())

    def test_explicit_start_is_required_and_credentials_are_escaped(self):
        with patch.dict(os.environ, {'CANAL_DB_PASSWORD': 'synthetic-password'}, clear=True):
            with self.assertRaises(ValueError): prepare.prepare(self.output, 'dev', '', 0, 1794247259000)
            with self.assertRaises(ValueError): prepare.prepare(self.output, 'dev', 'mysql-bin.000001', 157, 0)
        self.generate('a\\b:= 密码')
        self.assertIn('\\u5bc6', (self.output / 'instance.properties').read_text())


if __name__ == '__main__': unittest.main()
