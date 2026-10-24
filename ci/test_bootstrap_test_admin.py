import importlib.util
from pathlib import Path
import unittest
import os
import subprocess
import tempfile
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location('bootstrap_test_admin', Path(__file__).resolve().parents[1] / 'scripts/bootstrap-test-admin.py')
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)
UUID = '12345678-1234-1234-1234-123456789012'

class BootstrapAdminContract(unittest.TestCase):
    def test_only_explicit_test_schema_and_uuid_are_allowed(self):
        bootstrap.validate_target('cy_e2e_owned', UUID, True)
        for schema, uuid, confirm in [('chengyinhubdb', UUID, True), ('cy_e2e_owned', '', True), ('cy_e2e_owned', UUID, False), ('cy_e2e_x;DROP TABLE sys_user', UUID, True)]:
            with self.assertRaises(ValueError): bootstrap.validate_target(schema, uuid, confirm)

    def test_mac_remote_host_and_invalid_port_refuse_before_db_import(self):
        with patch.object(bootstrap.sys, 'platform', 'darwin'):
            with self.assertRaises(ValueError): bootstrap.validate_runtime_target('127.0.0.1', 3306)
        with patch.object(bootstrap.sys, 'platform', 'linux'):
            bootstrap.validate_runtime_target('127.0.0.1', 3306)
            for host, port in [('example.invalid', 3306), ('127.0.0.1', 0), ('127.0.0.1', 65536)]:
                with self.assertRaises(ValueError): bootstrap.validate_runtime_target(host, port)

    def test_strong_password_required_without_defaults_or_truncation(self):
        bootstrap.validate_password('Source-Check-8294')
        for password in [None, *bootstrap.DEFAULT_PASSWORDS, 'samelettersforever', 'x'*65, 'Short-84']:
            with self.assertRaises(ValueError): bootstrap.validate_password(password)

    def test_password_length_matches_current_login_range(self):
        for length in (12, 20):
            with self.subTest(accepted_length=length):
                bootstrap.validate_password('Aa1!' + 'x' * (length - 4))
        for length in (11, 21, 64):
            with self.subTest(rejected_length=length):
                with self.assertRaisesRegex(ValueError, '12 to 20'):
                    bootstrap.validate_password('Aa1!' + 'x' * (length - 4))

    def test_unicode_password_maximum_uses_java_utf16_units(self):
        boundary = 'Aa1!' + '\U0001f600' * 8
        bootstrap.validate_password(boundary)
        with self.assertRaises(ValueError):
            bootstrap.validate_password(boundary + 'x')

    def test_unicode_overlength_refuses_before_database_or_hashing(self):
        connection = MagicMock()
        crypto = MagicMock()
        with self.assertRaises(ValueError):
            bootstrap.initialize(connection, 'admin', 'Aa1!' + '\U0001f600' * 8 + 'x', UUID, crypto)
        connection.cursor.assert_not_called()
        crypto.crypt.assert_not_called()
        crypto.mksalt.assert_not_called()
        connection.commit.assert_not_called()

    def test_wrong_actual_uuid_refuses_all_account_writes_and_rolls_back(self):
        connection = MagicMock(); cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = ('00000000-0000-0000-0000-000000000000', '8.0.45')
        with self.assertRaises(ValueError):
            bootstrap.initialize(connection, 'admin', 'Source-Check-8294', UUID, MagicMock())
        self.assertEqual(['SELECT @@server_uuid, VERSION()'], [call.args[0] for call in cursor.execute.call_args_list])
        connection.commit.assert_not_called(); connection.rollback.assert_called_once()

    def test_existing_strong_admin_password_is_never_rotated(self):
        connection = MagicMock(); cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = (UUID, '8.0.45')
        cursor.fetchall.return_value = [(1, 'admin', '$2b$synthetic-not-a-default', None, '0')]
        crypto = MagicMock(); crypto.crypt.return_value = '$2b$different-synthetic-result'
        with self.assertRaisesRegex(ValueError, 'no rotation permitted'):
            bootstrap.initialize(connection, 'admin', 'Source-Check-8294', UUID, crypto)
        self.assertTrue(all(call.args[0].startswith('SELECT ') for call in cursor.execute.call_args_list))
        connection.commit.assert_not_called(); connection.rollback.assert_called_once()
        crypto.mksalt.assert_not_called()

    def test_existing_or_used_or_deleted_accounts_are_not_rotated(self):
        admin = (1, 'admin', 'not-read-here', None, '0')
        bootstrap.validate_fresh_users([admin])
        ry = (2, 'ry', 'not-read-here', None, '0')
        bootstrap.validate_fresh_users([ry])
        bootstrap.validate_fresh_users([admin, ry])
        bootstrap.validate_fresh_users([])
        for rows in [[admin, (2, 'reviewer', 'hash', None, '0')], [admin, admin], [(1, 'admin', 'hash', 'used', '0')], [(1, 'ry', 'hash', None, '2')]]:
            with self.assertRaises(ValueError): bootstrap.validate_fresh_users(rows)

class RestartMacSafetyContract(unittest.TestCase):
    def test_admin_restart_and_mock_restart_fixture_refuse_mac_before_runtime(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); marker = directory / 'java-called'
            (directory / 'uname').write_text('#!/bin/sh\necho Darwin\n')
            (directory / 'java').write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 99\n')
            for path in directory.iterdir(): path.chmod(0o755)
            env = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['/bin/bash', str(root / 'run-chengyinhub-admin.sh')],
                                    capture_output=True, text=True, env=env)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Mac Java execution is forbidden', result.stderr)
            self.assertFalse(marker.exists())
            fixture_spec = importlib.util.spec_from_file_location('restart_fixture', root / 'scripts/test-admin-graceful-restart.py')
            fixture = importlib.util.module_from_spec(fixture_spec); fixture_spec.loader.exec_module(fixture)
            with patch.object(fixture.sys, 'platform', 'darwin'), patch.object(fixture.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, 'Mac process fixture'):
                    fixture.main()
                spawn.assert_not_called()

if __name__ == '__main__': unittest.main()
