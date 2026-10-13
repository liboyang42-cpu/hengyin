import importlib.util
import pathlib
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



class DockerEndpointGuardTest(unittest.TestCase):
    def test_hosted_linux_ci_uses_explicit_daemon(self):
        self.assertEqual('unix:///var/run/docker.sock', gate.docker_endpoint(
            {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
             'DOCKER_HOST': 'tcp://untrusted:2375', 'DOCKER_CONTEXT': 'untrusted'}, 'Linux'))

    def test_mac_rejected_even_with_ci_flags(self):
        with self.assertRaisesRegex(ValueError, 'Linux'):
            gate.docker_endpoint({'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted'}, 'Darwin')

    def test_self_hosted_ci_is_not_implicitly_allowed(self):
        with self.assertRaisesRegex(ValueError, 'Explicit'):
            gate.docker_endpoint({'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'}, 'Linux')

    def test_explicit_test_vps_requires_endpoint_and_identity(self):
        approved = {'CHENGYIN_STARTUP_TARGET': 'test-vps',
                    'CHENGYIN_STARTUP_DOCKER_ENDPOINT': 'unix:///var/run/docker.sock',
                    'CHENGYIN_STARTUP_DOCKER_ID': 'synthetic-test-daemon'}
        self.assertEqual('unix:///var/run/docker.sock', gate.docker_endpoint(approved, 'Linux'))
        for key in approved:
            with self.subTest(missing=key), self.assertRaisesRegex(ValueError, 'Explicit'):
                gate.docker_endpoint({k: v for k, v in approved.items() if k != key}, 'Linux')
        with self.assertRaisesRegex(ValueError, 'Explicit'):
            gate.docker_endpoint(dict(approved, CHENGYIN_STARTUP_DOCKER_ENDPOINT='ssh://other-host'), 'Linux')

    def test_unconfigured_linux_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Explicit'):
            gate.docker_endpoint({}, 'Linux')


if __name__ == '__main__':
    unittest.main()
