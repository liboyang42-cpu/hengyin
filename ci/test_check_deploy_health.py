import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('deploy_health', Path(__file__).with_name('check_deploy_health.py'))
health = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(health)


def encoded(value):
    return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')


# Structurally valid fixture, never an authenticated account or production credential.
TOKEN = encoded({'alg': 'HS512'}) + '.' + encoded({'fixture': True}) + '.' + base64.urlsafe_b64encode(b'x' * 64).decode().rstrip('=')
UP = json.dumps({'status': 'UP', 'components': {'db': {'status': 'UP'}, 'redis': {'status': 'UP'}}})


class DeployHealthTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'token'
        self.path.write_text(TOKEN)
        self.path.chmod(0o600)

    def test_private_token_file_accepts_only_owner_regular_0400_or_0600(self):
        for mode in (0o400, 0o600):
            self.path.chmod(mode)
            self.assertEqual(TOKEN, health.private_token(str(self.path)))
        for mode in (0o644, 0o640, 0o700, 0o4600):
            self.path.chmod(mode)
            with self.assertRaises(health.HealthCheckError):
                health.private_token(str(self.path))

    def test_symlink_directory_missing_relative_are_rejected(self):
        alias = self.path.with_name('alias')
        alias.symlink_to(self.path)
        for path in (str(alias), str(self.path.parent), str(self.path.with_name('missing')), 'relative-token'):
            with self.subTest(path=path), self.assertRaises(health.HealthCheckError):
                health.private_token(path)

    def test_different_owner_is_rejected(self):
        with patch.object(health.os, 'geteuid', return_value=os.geteuid() + 1):
            with self.assertRaises(health.HealthCheckError):
                health.private_token(str(self.path))

    def test_malformed_jwt_or_header_injection_is_rejected(self):
        for token in ('', 'opaque', TOKEN + '\n', TOKEN + '\r\nX-Fake: yes', TOKEN.replace(encoded({'alg': 'HS512'}), encoded({'alg': 'none'})), 'e30.e30.eA', 'x' * 20000):
            self.path.write_text(token)
            with self.subTest(token_length=len(token)), self.assertRaises(health.HealthCheckError):
                health.private_token(str(self.path))

    def test_three_actual_up_statuses_required(self):
        health.require_up(200, UP)
        for code in (401, 500, 302, 204):
            with self.subTest(code=code), self.assertRaises(health.HealthCheckError):
                health.require_up(code, UP)
        for body in ('not-json', '{}', '{"status":"UP"}', 'null', '[]', json.dumps({'status': 'UP', 'components': {'db': {'status': 'UP'}}})):
            with self.subTest(body=body), self.assertRaises(health.HealthCheckError):
                health.require_up(200, body)
        for key in ('root', 'db', 'redis'):
            row = json.loads(UP)
            target = row if key == 'root' else row['components'][key]
            target['status'] = 'DOWN'
            with self.subTest(key=key), self.assertRaises(health.HealthCheckError):
                health.require_up(200, json.dumps(row))

    def test_curl_has_fixed_loopback_no_redirect_no_proxy_and_token_only_on_stdin(self):
        with patch.object(health.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, UP + '\n200')) as runner:
            health.check(str(self.path))
        args, kwargs = runner.call_args
        command = args[0]
        self.assertNotIn(TOKEN, ' '.join(command))
        self.assertEqual(health.HEALTH_URL, command[-1])
        self.assertEqual('--disable', command[1])
        self.assertNotIn('--location', command)
        self.assertNotIn('-L', command)
        self.assertIn('--noproxy', command)
        self.assertIn('--proto', command)
        self.assertEqual('header = "Authorization: Bearer ' + TOKEN + '"\n', kwargs['input'])
        self.assertEqual(subprocess.DEVNULL, kwargs['stderr'])
        self.assertLessEqual(kwargs['timeout'], 5)

    def test_transport_failure_never_parses_fake_up(self):
        for response in (subprocess.CompletedProcess([], 7, UP + '\n200'), subprocess.CompletedProcess([], 0, UP + '\n302')):
            with patch.object(health.subprocess, 'run', return_value=response), self.assertRaises(health.HealthCheckError):
                health.check(str(self.path))
        with patch.object(health.subprocess, 'run', side_effect=subprocess.TimeoutExpired([], 5)), self.assertRaises(health.HealthCheckError):
            health.check(str(self.path))

    def test_cli_output_never_contains_token(self):
        with patch.object(health.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, UP + '\n401')), patch('builtins.print') as printer:
            self.assertEqual(1, health.main([str(self.path)]))
            self.assertNotIn(TOKEN, str(printer.call_args_list))

    def test_unsafe_remote_token_path_exits_before_any_ssh_or_secret_use(self):
        push = Path(__file__).with_name('deploy-push.sh').resolve()
        for value in ('', 'relative', '/tmp/../token', '/tmp/./token', '/tmp/token;printf bad', '/tmp/token\\nInjected', '/tmp/..'):
            env = {'PATH': os.environ['PATH'], 'DEPLOY_HEALTH_TOKEN_FILE': value}
            result = subprocess.run(['bash', str(push), 'f' * 40], env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            with self.subTest(path=value):
                self.assertNotEqual(0, result.returncode)
                self.assertIn('DEPLOY_HEALTH_TOKEN_FILE', result.stdout + result.stderr)
                self.assertNotIn('ECS_SSH_HOST', result.stdout + result.stderr)

    def test_missing_configuration_precedes_any_jar_replacement_or_upload(self):
        root = Path(__file__).parent
        restart = (root / 'deploy-restart.sh').read_text()
        verify = (root / 'verify-deployed.sh').read_text()
        push = (root / 'deploy-push.sh').read_text()
        self.assertLess(restart.index('--validate-token-file'), restart.index('mv -f "$NEW_JAR" "$JAR"'))
        self.assertLess(push.index('DEPLOY_HEALTH_TOKEN_FILE 未设置'), push.index('retry_jar_upload "$stage_jar"'))
        self.assertLess(push.index('--validate-token-file'), push.index('retry_jar_upload "$stage_jar"'))
        self.assertIn('python3 "$HEALTH_CHECKER" "$HEALTH_TOKEN_FILE"', restart)
        self.assertIn('python3 "$HEALTH_CHECKER" "$HEALTH_TOKEN_FILE"', verify)
        self.assertNotIn('curl -s -o /dev/null', restart)
        self.assertIn("'$health_checker' '$DEPLOY_HEALTH_TOKEN_FILE'", push)

    def test_actual_service_preflight_precedes_transport_and_failure_stops_upload(self):
        root = Path(__file__).resolve().parents[1]
        push = (root / 'ci/deploy-push.sh').read_text()
        call = 'retry_ssh_stdin scripts/production-preflight.sh ssh "${ssh_opts[@]}" "$target" \'bash -s -- --service\''
        self.assertEqual(1, push.splitlines().count(call))
        self.assertLess(push.index(call), push.index('retry_ssh_stdin ci/preflight-h8-schema.sh'))
        self.assertLess(push.index(call), push.index('created_health_checker='))
        self.assertLess(push.index(call), push.index('retry_jar_upload "$stage_jar"'))
        for status in (0, 17):
            with self.subTest(status=status):
                capture = self.path.with_name('preflight-source')
                shell = ('set -euo pipefail\n. ci/deploy-lib.sh\nssh_opts=(-o fixture)\ntarget=fixture.invalid\n'
                         'ssh() { cat > "$PREFLIGHT_CAPTURE"; return "$PREFLIGHT_RESULT"; }\n'
                         + call + '\nprintf "would-upload\\n"\n')
                result = subprocess.run(['bash', '-c', shell], cwd=root,
                                        env={**os.environ, 'PREFLIGHT_CAPTURE': str(capture),
                                             'PREFLIGHT_RESULT': str(status)}, text=True,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
                self.assertEqual(status, result.returncode)
                self.assertEqual((root / 'scripts/production-preflight.sh').read_bytes(), capture.read_bytes())
                self.assertEqual('would-upload\n' if status == 0 else '', result.stdout)


if __name__ == '__main__':
    unittest.main()
