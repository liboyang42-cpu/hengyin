import importlib.util
import pathlib
import os
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('startup_network', pathlib.Path(__file__).with_name('upgrade_startup_network.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def connection(address, port):
    return f'10 connect(9, {{sa_family=AF_INET, sin_port=htons({port}), sin_addr=inet_addr("{address}")}}, 16) = -1 EINPROGRESS'


class NetworkGuardTest(unittest.TestCase):
    def test_actual_numeric_mysql_redis_and_loopback(self):
        trace = '\n'.join([connection('172.18.0.2', 3306), connection('172.18.0.3', 6379), connection('127.0.0.1', 9999)])
        self.assertEqual(3, gate.verify(trace, '172.18.0.2', '172.18.0.3'))

    def test_actual_ipv4_mapped_trace_format(self):
        trace = '10 connect(9, {sa_family=AF_INET6, sin6_port=htons(3306), sin6_flowinfo=htonl(0), inet_pton(AF_INET6, "::ffff:172.18.0.2", &sin6_addr), sin6_scope_id=0}, 28) = -1 EINPROGRESS'
        self.assertEqual(1, gate.verify(trace, '172.18.0.2', '172.18.0.3'))

    def test_external_attempt_fails_even_when_connection_failed(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected destination'):
            gate.verify(connection('203.0.113.1', 443), '172.18.0.2', '172.18.0.3')

    def test_internal_unapproved_port_fails(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected destination'):
            gate.verify(connection('172.18.0.2', 443), '172.18.0.2', '172.18.0.3')

    def test_dns_fails_even_on_loopback(self):
        with self.assertRaisesRegex(ValueError, 'DNS'):
            gate.verify(connection('127.0.0.1', 53), '172.18.0.2', '172.18.0.3')

    def test_empty_trace_cannot_claim_no_external_calls(self):
        with self.assertRaisesRegex(ValueError, 'No socket observations'):
            gate.verify('', '172.18.0.2', '172.18.0.3')

    def test_unparsed_inet_call_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Unrecognized IP destination'):
            gate.verify('connect(9, {sa_family=AF_INET6, unknown}, 28)', '172.18.0.2', '172.18.0.3')


class StartupExecutionTargetTest(unittest.TestCase):
    def test_mac_unapproved_linux_and_self_hosted_reject_before_docker(self):
        script = pathlib.Path(__file__).with_name('upgrade-startup-smoke.sh')
        for system, overrides in [('Darwin', {}), ('Linux', {}),
                ('Linux', {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ENVIRONMENT': 'self-hosted'}),
                ('Linux', {'CHENGYIN_VPS_TEST_RUN': '1', 'CHENGYIN_VPS_SOURCE_ON_DAEMON': '1', 'DOCKER_CONTEXT': 'default'})]:
            with self.subTest(system=system, overrides=overrides), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                marker = directory / 'docker-called'
                (directory / 'uname').write_text('#!/bin/sh\nprintf "%s\\n" "' + system + '"\n')
                (directory / 'docker').write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 99\n')
                for path in directory.iterdir(): path.chmod(0o755)
                env = os.environ.copy()
                for key in ('GITHUB_ACTIONS', 'RUNNER_OS', 'RUNNER_ENVIRONMENT', 'CHENGYIN_VPS_TEST_RUN',
                            'CHENGYIN_VPS_SOURCE_ON_DAEMON', 'DOCKER_CONTEXT', 'DOCKER_HOST'):
                    env.pop(key, None)
                env.update(overrides)
                env['PATH'] = str(directory) + os.pathsep + env['PATH']
                result = subprocess.run(['/bin/bash', str(script)], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(marker.exists(), result.stderr)

    def test_hosted_runner_and_approved_vps_reject_wrong_endpoint_before_any_runtime(self):
        script = pathlib.Path(__file__).with_name('upgrade-startup-smoke.sh')
        for overrides, context in [({'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ENVIRONMENT': 'github-hosted'}, 'default'),
                ({'CHENGYIN_VPS_TEST_RUN': '1', 'CHENGYIN_VPS_SOURCE_ON_DAEMON': '1', 'DOCKER_CONTEXT': 'chengyin-aws'}, 'chengyin-aws')]:
            with self.subTest(context=context), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp); calls = directory / 'calls'
                (directory / 'uname').write_text('#!/bin/sh\necho Linux\n')
                (directory / 'docker').write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "' + str(calls) + '"\necho unix:///unapproved.sock\n')
                for path in directory.iterdir(): path.chmod(0o755)
                env = os.environ.copy()
                for key in ('GITHUB_ACTIONS', 'RUNNER_OS', 'RUNNER_ENVIRONMENT', 'CHENGYIN_VPS_TEST_RUN',
                            'CHENGYIN_VPS_SOURCE_ON_DAEMON', 'DOCKER_CONTEXT', 'DOCKER_HOST'):
                    env.pop(key, None)
                env.update(overrides); env['PATH'] = str(directory) + os.pathsep + env['PATH']
                result = subprocess.run(['/bin/bash', str(script)], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('endpoint differs', result.stderr)
                lines = calls.read_text().splitlines()
                self.assertEqual(1, len(lines))
                self.assertTrue(lines[0].startswith('--context ' + context + ' context inspect ' + context))


if __name__ == '__main__':
    unittest.main()
