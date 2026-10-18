"""Pure controls for the isolated fixture; no Docker, HTTP or database calls."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock

path = Path(__file__).with_name('authenticated_redis_recovery_probe.py')
spec = importlib.util.spec_from_file_location('auth_redis_probe', path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class AuthenticatedRedisSafetyTest(unittest.TestCase):
    def test_only_exact_hosted_execution_scope_is_accepted(self):
        env = {
            'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux',
            'RUNNER_ENVIRONMENT': 'github-hosted',
            'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin',
            'GITHUB_WORKFLOW': 'Isolated authenticated Redis recovery',
            'GITHUB_JOB': 'isolated-authenticated-redis-recovery',
            'GITHUB_EVENT_NAME': 'pull_request',
            'GITHUB_WORKFLOW_REF': 'liboyang42-cpu/hengyin/.github/workflows/isolated-authenticated-redis-recovery.yml@refs/pull/69/merge',
            'CHENGYIN_CI_AUTH_REDIS_RECOVERY': 'true',
        }
        with mock.patch.object(probe.sys, 'platform', 'linux'):
            probe.validate_execution_env(env)
            probe.validate_execution_env(dict(env, GITHUB_EVENT_NAME='workflow_dispatch'))
            for key, value in [
                ('DOCKER_HOST', 'ssh://foreign'), ('DOCKER_CONTEXT', 'chengyin-vps-sandbox'),
                ('RUNNER_ENVIRONMENT', 'self-hosted'), ('GITHUB_JOB', 'backend'),
                ('GITHUB_REPOSITORY', 'foreign/repo'), ('GITHUB_EVENT_NAME', 'push'),
                ('CHENGYIN_CI_AUTH_REDIS_RECOVERY', 'false'),
                ('GITHUB_WORKFLOW_REF', 'foreign/workflow@refs/heads/master'),
            ]:
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    probe.validate_execution_env(dict(env, **{key: value}))
        with mock.patch.object(probe.sys, 'platform', 'darwin'), self.assertRaises(RuntimeError):
            probe.validate_execution_env(env)

    def test_foreign_target_or_nonce_is_rejected(self):
        nonce = 'a' * 32
        name, schema = 'cy-auth-' + nonce, 'cy_e2e_auth_' + nonce
        network = {'Name': name, 'Id': 'd' * 64, 'Internal': True,
                   'Labels': {'chengyin.auth-recovery': name}}
        objects = [
            {'Id': str(i) * 64, 'Name': '/' + name + '-' + suffix,
             'Config': {'Labels': {'chengyin.auth-recovery': name}},
             'NetworkSettings': {'Networks': {name: {'NetworkID': network['Id']}}},
             'State': {'Running': True, 'Paused': False, 'OOMKilled': False}}
            for i, suffix in enumerate(['app', 'mysql', 'redis'], 1)
        ]
        endpoint = 'unix:///var/run/docker.sock'
        probe.validate_targets(name, schema, endpoint, objects, network)
        for kind in ['foreign-name', 'wrong-network', 'external-network', 'paused', 'oom', 'wrong-nonce',
                     'foreign-container-label', 'foreign-network-label', 'remote-daemon']:
            changed, net = copy.deepcopy(objects), copy.deepcopy(network)
            scoped_schema, scoped_endpoint = schema, endpoint
            if kind == 'foreign-name': changed[2]['Name'] = '/persistent-uat-redis'
            elif kind == 'wrong-network': changed[1]['NetworkSettings']['Networks'][name]['NetworkID'] = 'foreign'
            elif kind == 'external-network': net['Internal'] = False
            elif kind == 'paused': changed[2]['State']['Paused'] = True
            elif kind == 'oom': changed[0]['State']['OOMKilled'] = True
            elif kind == 'wrong-nonce': scoped_schema = 'cy_e2e_auth_' + 'b' * 32
            elif kind == 'foreign-container-label': changed[2]['Config']['Labels']['chengyin.auth-recovery'] = 'foreign'
            elif kind == 'foreign-network-label': net['Labels']['chengyin.auth-recovery'] = 'foreign'
            else: scoped_endpoint = 'ssh://foreign'
            with self.subTest(kind=kind), self.assertRaises(RuntimeError):
                probe.validate_targets(name, scoped_schema, scoped_endpoint, changed, net)

    def test_bootstrap_requires_empty_users_and_exact_active_admin_role(self):
        role = [('admin', '0', '0')]
        probe.validate_bootstrap_state(0, role)
        for count, roles in [(1, role), (0, []), (0, role * 2), (0, [('admin', '1', '0')]), (0, [('operator', '0', '0')])]:
            with self.subTest(count=count, roles=roles), self.assertRaises(RuntimeError):
                probe.validate_bootstrap_state(count, roles)

    def test_captcha_lookup_is_one_exact_key_without_pattern_or_override(self):
        uuid = 'a' * 32
        self.assertEqual(probe.captcha_key(uuid), 'captcha_codes:' + uuid)
        for bad in ['', 'a' * 31, 'A' * 32, uuid + '\n', 'captcha_codes:' + uuid, '*', None]:
            with self.subTest(uuid=bad), self.assertRaises(RuntimeError):
                probe.captcha_key(bad)

    def test_only_declared_lease_fields_can_change_and_failure_requires_all_equal(self):
        before = {'id': 1, 'payout_status': 2, 'refund_no': None, 'refund_amount': '1.00',
                  'payout_lease_owner': 1, 'payout_lease_token': 'lease-canary',
                  'payout_lease_expires_at': 'expiry', 'update_time': 'before'}
        allowed = {'payout_lease_owner', 'payout_lease_token', 'payout_lease_expires_at', 'update_time'}
        released = dict(before, payout_lease_owner=None, payout_lease_token=None,
                        payout_lease_expires_at=None, update_time='after')
        probe.assert_refund_transition(before, released, allowed)
        probe.assert_refund_transition(before, dict(before), set())
        for after in [dict(released, refund_amount='0.00'), dict(released, payout_status=3),
                      dict(released, id=2), dict(released, unexpected='field')]:
            with self.subTest(fields=list(after)), self.assertRaises(RuntimeError):
                probe.assert_refund_transition(before, after, allowed)
        with self.assertRaises(RuntimeError):
            probe.assert_refund_transition(before, released, set())

    def test_transport_unknown_or_business_success_cannot_pass_as_auth_failure(self):
        for http in [200, 401]:
            probe.assert_auth_failure({'http': http, 'businessCode': 401})
        for response in [{'http': 200, 'businessCode': 200}, {'http': 500, 'businessCode': 500},
                         {'http': 401, 'businessCode': 401, 'transportUnknown': True},
                         {'http': None, 'businessCode': 401}]:
            with self.subTest(response=response), self.assertRaises(RuntimeError):
                probe.assert_auth_failure(response)

    def test_evidence_redaction_preserves_error_but_not_private_values(self):
        body = json.dumps({'password': 'canary', 'nested': {'leaseToken': 'lease-canary'}})
        raw = 'ERROR dependency unavailable\nAuthorization: Bearer session-canary\n' + body + '\n' + '错' * 9000
        clean = probe.redact(raw, secrets=('canary', 'lease-canary', 'session-canary'))
        self.assertIn('ERROR dependency unavailable', clean)
        self.assertNotIn('canary', clean)
        self.assertLessEqual(len(clean.encode('utf-8')), 4096)
        self.assertNotIn('bearer-canary', probe.redact('ERROR Authorization: Bearer bearer-canary'))

    def test_full_log_redaction_retains_diagnostics_and_unrelated_digits(self):
        root_secret = 'root-canary-' + 'x' * 20
        admin_secret = 'admin-canary-' + 'y' * 20
        lease_secret = 'lease-canary-' + 'z' * 20
        jwt = '.'.join(['eyJhbGciOiJIUzI1NiJ9', 'eyJzdWIiOiIxIn0', 'c2ln' * 12])
        raw = ('ERROR password=' + root_secret + '\nadmin password=' + admin_secret
               + '\nAuthorization: Bearer ' + jwt + '\nleaseToken=' + lease_secret
               + '\ncaptcha code=7 uuid=' + 'a' * 32 + ' img=aGVsbG8=\n'
               + 'INFO code=401 time=17:07\n' + 'q' * 6000 + '\nEND_DIAGNOSTICS\n')
        clean = probe.redact_log(raw, (root_secret, admin_secret, jwt, lease_secret))
        for secret in (root_secret, admin_secret, jwt, lease_secret):
            self.assertNotIn(secret, clean)
        self.assertNotIn('code=7', clean)
        self.assertNotIn('a' * 32, clean)
        self.assertNotIn('aGVsbG8=', clean)
        self.assertIn('INFO code=401 time=17:07', clean)
        self.assertIn('q' * 6000, clean)
        self.assertTrue(clean.endswith('END_DIAGNOSTICS\n'))

    def test_mysql_relay_binds_loopback_owned_target_and_closes_on_failure(self):
        name = 'cy-auth-' + 'a' * 32
        expected = {'id': 'b' * 64, 'networkId': 'c' * 64, 'ip': '172.19.0.2', 'pid': 123}
        obj = {'Id': expected['id'], 'Name': '/' + name + '-mysql',
               'Config': {'Labels': {'chengyin.auth-recovery': name}},
               'State': {'Running': True, 'Paused': False, 'OOMKilled': False,
                         'Restarting': False, 'Pid': expected['pid']},
               'NetworkSettings': {'Networks': {name: {'NetworkID': expected['networkId'],
                                                      'IPAddress': expected['ip']}}}}
        network = {'Id': expected['networkId'], 'Name': name, 'Internal': True,
                   'Labels': {'chengyin.auth-recovery': name},
                   'IPAM': {'Config': [{'Subnet': '172.19.0.0/16'}]}}
        with mock.patch.object(probe, 'command', side_effect=[json.dumps([obj]), json.dumps([network])]):
            self.assertEqual(probe.mysql_endpoint(name), expected)
        foreign = copy.deepcopy(obj)
        foreign['Config']['Labels']['chengyin.auth-recovery'] = 'other-task'
        with mock.patch.object(probe, 'command', side_effect=[json.dumps([foreign]), json.dumps([network])]), \
                self.assertRaises(RuntimeError):
            probe.mysql_endpoint(name)

        baseline = {'endpoint': expected}
        with mock.patch.object(probe, 'mysql_endpoint', return_value=dict(expected, pid=124)), \
                mock.patch.object(probe.socketserver, 'ThreadingTCPServer') as bind, \
                self.assertRaises(RuntimeError):
            with probe.mysql_relay(name, baseline):
                self.fail('changed endpoint must not bind')
        bind.assert_not_called()

        for failure in ('phase', 'endpoint', 'late-close'):
            with self.subTest(failure=failure):
                accept_changed = failure == 'endpoint'
                server, listener, client, upstream = (mock.Mock() for _ in range(4))
                server.server_address = ('127.0.0.1', 35000)
                listener.is_alive.return_value = False
                client.recv.return_value = b''
                upstream.recv.side_effect = [b'tail-after-client-eof', b'']
                server.server_close.side_effect = RuntimeError('close failure')
                endpoints = [expected, dict(expected, pid=124) if accept_changed else expected, expected]
                with mock.patch.object(probe, 'mysql_endpoint', side_effect=endpoints), \
                        mock.patch.object(probe.socketserver, 'ThreadingTCPServer', return_value=server) as bind, \
                        mock.patch.object(probe.threading, 'Thread', return_value=listener), \
                        mock.patch.object(probe.socket, 'create_connection', return_value=upstream) as connect, \
                        mock.patch.object(probe.select, 'select', side_effect=[
                            ([client], [], []), ([upstream], [], []), ([upstream], [], [])]):
                    with self.assertRaisesRegex(RuntimeError, 'phase failure' if failure == 'phase' else 'relay failed'):
                        with probe.mysql_relay(name, baseline) as port:
                            self.assertEqual(port, 35000)
                            self.assertEqual(bind.call_args.args[0], ('127.0.0.1', 0))
                            handler = object.__new__(bind.call_args.args[1])
                            handler.request = client
                            handler.handle()
                            if not accept_changed:
                                connect.assert_called_once_with((expected['ip'], 3306), timeout=5)
                                upstream.shutdown.assert_called_once_with(probe.socket.SHUT_WR)
                                client.sendall.assert_called_once_with(b'tail-after-client-eof')
                                client.shutdown.assert_called_once_with(probe.socket.SHUT_WR)
                                upstream.close.assert_called_once()
                                if failure == 'phase':
                                    raise RuntimeError('phase failure')
                    if accept_changed:
                        connect.assert_not_called()
                    server.shutdown.assert_called_once()
                    server.server_close.assert_called_once()
                    listener.join.assert_called_once_with(timeout=5)

    def test_cleanup_requires_typed_missing_exact_resource_not_context_error(self):
        cid = 'a' * 64
        network = 'cy-auth-' + 'b' * 32
        probe.require_absent('container', cid, 1, ('Error: No such container: ' + cid + '\n').encode())
        probe.require_absent('container', cid, 1, 'Error response from daemon: No such container: ' + cid)
        probe.require_absent('network', network, 1, 'Error: No such network: ' + network)
        probe.require_absent('network', network, 1, 'Error response from daemon: network ' + network + ' not found')
        cases = [
            ('container', cid, 1, 'context default: context not found'),
            ('network', network, 1, 'Cannot connect to the Docker daemon: no such file or directory'),
            ('container', cid, 1, 'Error: No such container: ' + 'c' * 64),
            ('network', network, 1, 'Error response from daemon: network foreign not found'),
            ('container', cid, 0, 'Error: No such container: ' + cid),
            ('container', cid, 1, 'Error: No such container: ' + cid + '\ncontext not found'),
        ]
        for kind, target, code, error in cases:
            with self.subTest(kind=kind, error=error), self.assertRaises(RuntimeError):
                probe.require_absent(kind, target, code, error)


if __name__ == '__main__':
    unittest.main()
