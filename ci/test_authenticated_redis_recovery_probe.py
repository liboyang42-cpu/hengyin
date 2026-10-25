"""Pure controls for the isolated fixture; no Docker, HTTP or database calls."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
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

    def test_existing_mysql_relay_adapter_keeps_public_integer_port_and_original_endpoint_reader(self):
        name = 'cy-auth-' + 'a' * 32
        endpoint = {'id': 'b' * 64, 'networkId': 'c' * 64, 'ip': '172.19.0.2', 'pid': 123}
        # This source control replaces only the transport core; the original handler/negative tests remain below.
        from contextlib import contextmanager
        server = mock.Mock(); server.server_address = ('127.0.0.1', 35000)
        events = []
        @contextmanager
        def core(expected, current):
            self.assertEqual(endpoint, expected); self.assertEqual(endpoint, current())
            events.append('bind')
            try: yield server
            finally: events.append('close')
        with mock.patch.object(probe, 'mysql_endpoint', return_value=endpoint) as reader, \
             mock.patch.object(probe, '_owned_mysql_relay', side_effect=core):
            with probe.mysql_relay(name, {'endpoint': endpoint}, suffix='restore-mysql') as port:
                self.assertEqual(35000, port)
                self.assertIs(type(port), int)
                self.assertEqual(['bind'], events)
            reader.assert_called_once_with(name, 'restore-mysql')
        self.assertEqual(['bind', 'close'], events)

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

    def cleanup_fixture(self, out, report, *, app=False, logs='ok', daemon_error=False, restore=False):
        """Protocol double only: exact owned resources; never invokes Docker."""
        name = 'cy-auth-' + 'a' * 32
        path = out / 'authenticated-redis-recovery.json'
        if isinstance(report, str):
            path.write_text(report)
        elif report is not None:
            probe.write_report(out, path.name, report)
        objects = {
            name + '-' + suffix: {
                'Id': str(i) * 64, 'Name': '/' + name + '-' + suffix,
                'Config': {'Labels': {'chengyin.auth-recovery': name}},
                'State': {'Running': False, 'Paused': False},
            }
            for i, suffix in enumerate(('app', 'mysql', 'redis') + (('restore-mysql',) if restore else ()), 1)
            if app or suffix != 'app'
        }
        if restore:
            objects[name + '-restore-mysql']['Config']['Image'] = probe.RESTORE_MYSQL_IMAGE
            objects[name + '-restore-mysql']['NetworkSettings'] = {'Networks': {name: {}}}
        network = {'Name': name, 'Id': 'd' * 64, 'Internal': True,
                   'Labels': {'chengyin.auth-recovery': name}}
        network_present = True
        removed = []

        def run(args, **kwargs):
            operation = args[len(probe.DOCKER):]
            if operation[:2] == ['container', 'inspect']:
                target = operation[2]
                if daemon_error and target == name + '-app':
                    return subprocess.CompletedProcess(args, 1, b'', b'Cannot connect to the Docker daemon')
                obj = objects.get(target) or next((o for o in objects.values() if o['Id'] == target), None)
                if obj:
                    return subprocess.CompletedProcess(args, 0, json.dumps([obj]).encode(), b'')
                return subprocess.CompletedProcess(args, 1, b'', ('Error: No such container: ' + target).encode())
            if operation[:2] == ['network', 'inspect']:
                if network_present:
                    return subprocess.CompletedProcess(args, 0, json.dumps([network]).encode(), b'')
                return subprocess.CompletedProcess(args, 1, b'', ('Error: No such network: ' + name).encode())
            if operation[:1] == ['logs']:
                if logs == 'timeout':
                    raise subprocess.TimeoutExpired(args, 20)
                return subprocess.CompletedProcess(args, 0 if logs == 'ok' else 1, b'diagnostic', b'')
            raise AssertionError('unexpected subprocess: ' + repr(operation))

        def command(args, **kwargs):
            nonlocal network_present
            operation = args[len(probe.DOCKER):]
            if operation[:2] == ['rm', '-f']:
                target = next(k for k, v in objects.items() if v['Id'] == operation[2])
                removed.append(target)
                del objects[target]
                return b''
            if operation[:2] == ['network', 'rm']:
                self.assertEqual(operation[2], network['Id'])
                network_present = False
                return b''
            raise AssertionError('unexpected command: ' + repr(operation))

        with mock.patch.object(probe.subprocess, 'run', side_effect=run), \
                mock.patch.object(probe, 'command', side_effect=command), \
                mock.patch.dict(probe.os.environ, {'CHENGYIN_CI_RESTORE_HTTP_STARTUP': 'true' if restore else 'false'}):
            try:
                probe.cleanup(name, 'cy_e2e_auth_' + 'a' * 32, out, out / 'private.json', 0)
            except RuntimeError as exc:
                probe.record_phase_failure(out, 'cleanup', exc)
        return json.loads(path.read_text()), removed, network_present

    def test_pre_app_failure_retains_first_cause_with_confirmed_cleanup_and_no_log_required(self):
        for phase in ('prepare', 'mysql-ready'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
                failure = {'fixturePhase': phase, 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                           'failureType': 'RuntimeError', 'reason': 'full schema builder failed; no continuation'}
                report, removed, network = self.cleanup_fixture(Path(directory), failure)
                for key, value in failure.items():
                    self.assertEqual(report[key], value)
                self.assertEqual(report['applicationLogStatus'], 'NOT_STARTED')
                self.assertFalse(report['applicationLogPreserved'])
                self.assertNotIn('cleanupFailure', report)
                self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')
                self.assertEqual(len(removed), 2)
                self.assertTrue(all(o['independentNoSuch'] for o in report['resourceCleanup']['ownedRemoved']))
                self.assertFalse(network)

    def test_missing_app_without_confirmed_pre_app_failure_is_still_unknown(self):
        for original in (None, {'result': 'PASS_AUTHENTICATED_REFUND_LEASE_REDIS_RECOVERY'},
                         {'fixturePhase': 'exercise', 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                          'failureType': 'RuntimeError', 'reason': 'request unknown'},
                         {'fixturePhase': 'prepare', 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                          'failureType': 'RuntimeError', 'events': [{'stage': 'claim_request_intent'}]}):
            with self.subTest(original=original), tempfile.TemporaryDirectory() as directory:
                report, removed, network = self.cleanup_fixture(Path(directory), original)
                self.assertEqual(report['applicationLogStatus'], 'UNAVAILABLE')
                self.assertEqual(report['cleanupFailure']['fixturePhase'], 'cleanup')
                self.assertEqual(report['result'], 'FAIL_OR_UNKNOWN_NO_RETRY')
                self.assertEqual(len(removed), 2)
                self.assertFalse(network)

    def test_existing_app_log_failure_never_becomes_not_started_or_skips_removal(self):
        for logs in ('error', 'timeout'):
            with self.subTest(logs=logs), tempfile.TemporaryDirectory() as directory:
                first = {'fixturePhase': 'prepare', 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                         'failureType': 'ValueError', 'reason': 'original prepare failure'}
                report, removed, network = self.cleanup_fixture(Path(directory), first, app=True, logs=logs)
                for key, value in first.items():
                    self.assertEqual(report[key], value)
                self.assertEqual(report['applicationLogStatus'], 'UNAVAILABLE')
                self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')
                self.assertIn('application logs unavailable', report['cleanupFailure']['reason'])
                self.assertEqual(len(removed), 3)
                self.assertFalse(network)

    def test_daemon_failure_is_not_app_absence_or_confirmed_resource_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            first = {'fixturePhase': 'prepare', 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                     'failureType': 'RuntimeError', 'reason': 'original prepare failure'}
            report, removed, network = self.cleanup_fixture(Path(directory), first, daemon_error=True)
            for key, value in first.items():
                self.assertEqual(report[key], value)
            self.assertEqual(report['resourceCleanup']['cleanup'], 'NOT_CONFIRMED')
            self.assertNotIn('applicationLogStatus', report)
            self.assertIn('absence not independently confirmed', report['cleanupFailure']['reason'])
            self.assertEqual(removed, [])
            self.assertTrue(network)

    def test_successful_app_logs_preserve_original_business_result_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            original = {'result': 'PASS_AUTHENTICATED_REFUND_LEASE_REDIS_RECOVERY'}
            report, removed, network = self.cleanup_fixture(Path(directory), original, app=True)
            self.assertEqual(report['result'], original['result'])
            self.assertEqual(report['applicationLogStatus'], 'PRESERVED')
            self.assertTrue(report['applicationLogPreserved'])
            self.assertNotIn('cleanupFailure', report)
            self.assertEqual(len(removed), 3)
            self.assertFalse(network)

    def test_corrupt_report_does_not_skip_exact_cleanup_or_claim_not_started(self):
        for original, error_type in (('{"private":"not-public-canary"', 'JSONDecodeError'),
                                     ('[]', 'ValueError')):
            for app in (False, True):
                with self.subTest(original=error_type, app=app), tempfile.TemporaryDirectory() as directory:
                    report, removed, network = self.cleanup_fixture(Path(directory), original, app=app)
                    self.assertEqual(report['result'], 'FAIL_UNKNOWN')
                    self.assertEqual(report['reportReadFailure'], {'type': error_type})
                    self.assertEqual(report['fixturePhase'], 'cleanup')
                    self.assertIn('original phase unknown', report['reason'])
                    self.assertNotEqual(report['applicationLogStatus'], 'NOT_STARTED')
                    self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')
                    self.assertEqual(len(removed), 3 if app else 2)
                    self.assertTrue(all(o['independentNoSuch'] for o in report['resourceCleanup']['ownedRemoved']))
                    self.assertFalse(network)
                    self.assertNotIn('not-public-canary', json.dumps(report))

        # An earlier daemon failure leaves the corrupt file in place for the
        # second reader; it must still record UNKNOWN without leaking its bytes.
        with tempfile.TemporaryDirectory() as directory:
            report, removed, network = self.cleanup_fixture(
                Path(directory), '{"private":"not-public-canary"', daemon_error=True)
            self.assertEqual(report['result'], 'FAIL_UNKNOWN')
            self.assertEqual(report['reportReadFailure'], {'type': 'JSONDecodeError'})
            self.assertEqual(report['resourceCleanup']['cleanup'], 'NOT_CONFIRMED')
            self.assertIn('absence not independently confirmed', report['cleanupFailure']['reason'])
            self.assertEqual(removed, [])
            self.assertTrue(network)
            self.assertNotIn('not-public-canary', json.dumps(report))

    def test_unreadable_report_does_not_skip_cleanup_or_expose_read_error_content(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            original_read = Path.read_text
            failed = False

            def read(path, *args, **kwargs):
                nonlocal failed
                if path == out / 'authenticated-redis-recovery.json' and not failed:
                    failed = True
                    raise PermissionError('not-public-canary')
                return original_read(path, *args, **kwargs)

            with mock.patch.object(Path, 'read_text', read):
                report, removed, network = self.cleanup_fixture(out, {'result': 'NOT_COMPLETED'}, app=True)
            self.assertEqual(report['result'], 'FAIL_UNKNOWN')
            self.assertEqual(report['reportReadFailure'], {'type': 'PermissionError'})
            self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')
            self.assertEqual(len(removed), 3)
            self.assertFalse(network)
            self.assertNotIn('not-public-canary', json.dumps(report))

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            with mock.patch.object(Path, 'read_text', side_effect=PermissionError('not-public-canary')):
                failure = probe.record_phase_failure(out, 'cleanup', RuntimeError('cleanup removal unknown'))
            report = json.loads((out / 'authenticated-redis-recovery.json').read_text())
            self.assertEqual(failure['result'], 'FAIL_UNKNOWN')
            self.assertEqual(report['reportReadFailure'], {'type': 'PermissionError'})
            self.assertEqual(report['resourceCleanup']['cleanup'], 'NOT_CONFIRMED')
            self.assertNotIn('not-public-canary', json.dumps(report))


    def test_restore_target_is_a_fixed_slot_in_the_same_owned_network(self):
        name = 'cy-auth-' + 'a' * 32
        expected = {'id': 'b' * 64, 'networkId': 'c' * 64, 'ip': '172.19.0.3', 'pid': 124}
        obj = {'Id': expected['id'], 'Name': '/' + name + '-restore-mysql',
               'Config': {'Image': probe.RESTORE_MYSQL_IMAGE, 'Labels': {'chengyin.auth-recovery': name}},
               'State': {'Running': True, 'Paused': False, 'OOMKilled': False, 'Restarting': False, 'Pid': 124},
               'NetworkSettings': {'Networks': {name: {'NetworkID': expected['networkId'], 'IPAddress': expected['ip']}}}}
        network = {'Id': expected['networkId'], 'Name': name, 'Internal': True,
                   'Labels': {'chengyin.auth-recovery': name}, 'IPAM': {'Config': [{'Subnet': '172.19.0.0/16'}]}}
        with mock.patch.object(probe, 'command', side_effect=[json.dumps([obj]), json.dumps([network])]) as run:
            self.assertEqual(probe.mysql_endpoint(name, 'restore-mysql'), expected)
            self.assertEqual(run.call_args_list[0].args[0][-1], name + '-restore-mysql')
        for kind in ('label', 'image', 'network'):
            changed = copy.deepcopy(obj)
            if kind == 'label':
                changed['Config']['Labels']['chengyin.auth-recovery'] = 'other-task'
            elif kind == 'image':
                changed['Config']['Image'] = 'foreign-image'
            else:
                changed['NetworkSettings']['Networks'] = {'other-network': {}}
            with self.subTest(kind=kind), mock.patch.object(probe, 'command', side_effect=[json.dumps([changed]), json.dumps([network])]), self.assertRaises(RuntimeError):
                probe.mysql_endpoint(name, 'restore-mysql')
        for suffix in ('source', '../mysql', 'foreign-mysql', ''):
            with self.subTest(suffix=suffix), mock.patch.object(probe, 'command') as run, self.assertRaises(RuntimeError):
                probe.mysql_endpoint(name, suffix)
            run.assert_not_called()

    def test_restore_relay_uses_original_loopback_owner_and_preserves_phase_failure(self):
        name = 'cy-auth-' + 'a' * 32
        endpoint = {'id': 'b' * 64, 'networkId': 'c' * 64, 'ip': '172.19.0.3', 'pid': 124}
        for fail in (False, True):
            with self.subTest(fail=fail):
                server, listener = mock.Mock(), mock.Mock()
                server.server_address = ('127.0.0.1', 35001)
                listener.is_alive.return_value = False
                with mock.patch.object(probe, 'mysql_endpoint', return_value=endpoint) as identity, \
                        mock.patch.object(probe.socketserver, 'ThreadingTCPServer', return_value=server) as bind, \
                        mock.patch.object(probe.threading, 'Thread', return_value=listener):
                    if fail:
                        with self.assertRaisesRegex(RuntimeError, 'original restore phase'):
                            with probe.mysql_relay(name, {'endpoint': endpoint}, suffix='restore-mysql'):
                                raise RuntimeError('original restore phase')
                    else:
                        with probe.mysql_relay(name, {'endpoint': endpoint}, suffix='restore-mysql') as port:
                            self.assertEqual(port, 35001)
                    self.assertEqual(identity.call_args_list, [mock.call(name, 'restore-mysql')] * (1 if fail else 2))
                    self.assertEqual(bind.call_args.args[0], ('127.0.0.1', 0))
                    server.shutdown.assert_called_once()
                    server.server_close.assert_called_once()
                    listener.join.assert_called_once_with(timeout=5)

    def test_restore_native_readiness_preserves_source_receipt_and_rejects_same_uuid(self):
        name = 'cy-auth-' + 'a' * 32
        source_uuid = '11111111-1111-4111-8111-111111111111'
        for same_uuid in (False, True):
            with self.subTest(same_uuid=same_uuid), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                source = {'mysqlReadiness': {'result': 'OWNED_MYSQL_TCP_READY', 'id': 'b' * 64,
                          'serverUuid': source_uuid, 'version': '8.0.45', 'endpoint': {'networkId': 'c' * 64}}}
                original = json.dumps(source)
                (out / 'schema-summary.json').write_text(original)
                target_uuid = source_uuid if same_uuid else '22222222-2222-4222-8222-222222222222'
                obj = {'Id': 'd' * 64, 'Name': '/' + name + '-restore-mysql',
                       'Config': {'Labels': {'chengyin.auth-recovery': name}},
                       'State': {'Pid': 124, 'Running': True, 'Paused': False, 'Restarting': False, 'ExitCode': 0, 'OOMKilled': False},
                       'HostConfig': {'PortBindings': None}, 'NetworkSettings': {'Ports': {}}}
                native = subprocess.CompletedProcess([], 0, (target_uuid + '\t8.0.45\n').encode(), b'')
                logs = subprocess.CompletedProcess([], 0, b'controlled native diagnostic', b'')
                endpoint = {'id': 'd' * 64, 'networkId': 'c' * 64, 'ip': '172.19.0.3', 'pid': 124}
                with mock.patch.object(probe, 'command', return_value=json.dumps([obj]).encode()), \
                        mock.patch.object(probe, 'mysql_endpoint', return_value=endpoint), \
                        mock.patch.object(probe.subprocess, 'run', side_effect=[native, logs]):
                    if same_uuid:
                        with self.assertRaisesRegex(RuntimeError, 'independent same-version'):
                            probe.mysql_ready(name, 'cy_e2e_auth_' + 'a' * 32, out, out / 'private.json', 0, suffix='restore-mysql')
                    else:
                        probe.mysql_ready(name, 'cy_e2e_auth_' + 'a' * 32, out, out / 'private.json', 0, suffix='restore-mysql')
                self.assertEqual((out / 'schema-summary.json').read_text(), original)
                facts = json.loads((out / 'restore-mysql-readiness.json').read_text())['mysqlReadiness']
                self.assertEqual(facts['result'], 'FAIL_NO_RESTART' if same_uuid else 'OWNED_MYSQL_TCP_READY')
                self.assertEqual(facts['serverUuid'], target_uuid)

    def test_restore_cleanup_removes_only_four_fixed_slots_and_requires_known_cid(self):
        name = 'cy-auth-' + 'a' * 32
        for changed in (False, True):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                (out / 'restore-mysql-readiness.json').write_text(json.dumps({'mysqlReadiness': {'id': ('9' if changed else '4') * 64}}))
                report, removed, network = self.cleanup_fixture(out, {'result': 'BUSINESS_ASSERTIONS_PASSED_AWAITING_CLEANUP'}, app=True, restore=True)
                if changed:
                    self.assertNotIn(name + '-restore-mysql', removed)
                    self.assertTrue(network)
                    self.assertNotEqual(report.get('resourceCleanup', {}).get('cleanup'), 'CONFIRMED_EXACT_OWNED_REMOVAL')
                else:
                    self.assertEqual(removed, [name + '-' + suffix for suffix in ('app', 'restore-mysql', 'mysql', 'redis')])
                    self.assertFalse(network)
                    self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')

    def test_restore_readiness_failure_before_app_keeps_original_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            original = {'fixturePhase': 'restore-mysql-ready', 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                        'failureType': 'ReadinessError', 'reason': 'original controlled failure'}
            report, removed, network = self.cleanup_fixture(out, original, restore=True)
            self.assertEqual(report['result'], original['result'])
            self.assertEqual(report['fixturePhase'], original['fixturePhase'])
            self.assertEqual(report['applicationLogStatus'], 'NOT_STARTED')
            self.assertEqual(len(removed), 3)
            self.assertFalse(network)
            self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')

    @staticmethod
    def restore_prestart_env():
        return {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin',
                'GITHUB_SHA': 'b' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}

    @staticmethod
    def restore_prestart_report():
        return {'sourceSha': 'b' * 40, 'schema': 'cy_e2e_auth_' + 'a' * 32,
                'jarSha256': 'c' * 64,
                'runId': '123', 'runAttempt': '1', 'applicationStartAttempted': False,
                'boots': [], 'completeBoots': [], 'checks': [], 'ownedAppCids': [], 'removedAppCids': [],
                'applicationStop': 'NO_ACTIVE_APP_SLOT', 'result': 'NOT_COMPLETED',
                'privateCleanup': 'EXACT_OWN_SUBTREE_REMOVED',
                'phase': 'SOURCE_DATABASE_PREPARATION', 'firstFailurePhase': 'SOURCE_DATABASE_PREPARATION',
                'firstFailureType': 'ModuleNotFoundError', 'productionDispatchRelease': False,
                'sourceMysqlCid': '2' * 64, 'targetMysqlCid': '4' * 64, 'redisCid': '3' * 64}

    def test_same_run_restore_before_first_start_has_no_log_but_stays_failed_after_exact_cleanup(self):
        for phase in ('PRE_APPLICATION_PREPARATION', 'SOURCE_DATABASE_PREPARATION'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.dict(probe.os.environ, self.restore_prestart_env(), clear=True):
                out = Path(directory); original = self.restore_prestart_report()
                original.update(phase=phase, firstFailurePhase=phase, firstFailureType='_ControlledFailure')
                probe.write_report(out, 'restore-http-application.json', original)
                report, removed, network = self.cleanup_fixture(out, None, restore=True)
                self.assertEqual(report['applicationLogStatus'], 'NOT_STARTED')
                self.assertFalse(report['applicationLogPreserved'])
                self.assertEqual(report['result'], 'FAIL_BEFORE_BUSINESS_PROBE')
                self.assertNotIn('cleanupFailure', report)
                self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')
                self.assertEqual(len(removed), 3)
                self.assertFalse(network)

    def test_restore_prestart_marker_never_accepts_started_unknown_or_foreign_evidence(self):
        changes = [('applicationStartAttempted', True), ('applicationStartAttempted', None),
                   ('applicationStartAttempted', 0), ('applicationStartAttempted', 'false'),
                   ('boots', [{'appCid': '1' * 64}]), ('ownedAppCids', ['1' * 64]),
                   ('completeBoots', [{}]), ('completeBoots', {}), ('checks', [{}]),
                   ('jarSha256', ''), ('jarSha256', None), ('jarSha256', 'G' * 64),
                   ('privateCleanup', 'UNKNOWN'), ('firstFailurePhase', None),
                   ('firstFailurePhase', 'SOURCE_APPLICATION_START'),
                   ('removedAppCids', ['1' * 64]), ('applicationStop', 'UNKNOWN'),
                   ('sourceSha', 'c' * 40), ('schema', 'foreign'), ('runId', 'old'), ('runAttempt', '2'),
                   ('sourceMysqlCid', '9' * 64), ('targetMysqlCid', '2' * 64),
                   ('result', 'SCOPED_RESTORE_HTTP_STARTUP_AND_REAL_OWNER_HOLD_VERIFIED'),
                   ('firstFailureType', {'canary': 'never accept dynamic cause'}), ('productionDispatchRelease', True)]
        for key, value in changes:
            with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.dict(probe.os.environ, self.restore_prestart_env(), clear=True):
                out = Path(directory); original = self.restore_prestart_report(); original[key] = value
                probe.write_report(out, 'restore-http-application.json', original)
                report, removed, network = self.cleanup_fixture(out, None, restore=True)
                self.assertEqual(report['applicationLogStatus'], 'UNAVAILABLE')
                self.assertIn('cleanupFailure', report)
                self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')
                self.assertEqual(len(removed), 3)
                self.assertFalse(network)
        for key in ('sourceSha', 'schema', 'runId', 'runAttempt', 'jarSha256', 'applicationStartAttempted',
                    'boots', 'completeBoots', 'checks', 'ownedAppCids', 'removedAppCids', 'applicationStop',
                    'result', 'privateCleanup', 'firstFailurePhase', 'firstFailureType', 'productionDispatchRelease'):
            with self.subTest(missing=key), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.dict(probe.os.environ, self.restore_prestart_env(), clear=True):
                out = Path(directory); original = self.restore_prestart_report(); original.pop(key)
                probe.write_report(out, 'restore-http-application.json', original)
                report, removed, network = self.cleanup_fixture(out, None, restore=True)
                self.assertEqual(report['applicationLogStatus'], 'UNAVAILABLE')
                self.assertIn('cleanupFailure', report)
                self.assertEqual(len(removed), 3)
                self.assertFalse(network)

    def test_restore_prestart_missing_corrupt_or_linked_report_never_waives_logs(self):
        for case in ('missing', 'corrupt', 'array', 'missing_marker', 'symlink', 'original_auth_corrupt', 'original_auth_pass'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.dict(probe.os.environ, self.restore_prestart_env(), clear=True):
                out = Path(directory); target = out / 'restore-http-application.json'
                if case == 'corrupt': target.write_text('{bad')
                elif case == 'array': target.write_text('[]')
                elif case == 'missing_marker':
                    original = self.restore_prestart_report(); original.pop('applicationStartAttempted')
                    probe.write_report(out, target.name, original)
                elif case == 'symlink':
                    source = out / 'controlled.json'; source.write_text(json.dumps(self.restore_prestart_report()))
                    target.symlink_to(source)
                elif case.startswith('original_auth_'):
                    probe.write_report(out, target.name, self.restore_prestart_report())
                original_auth = ('{bad' if case == 'original_auth_corrupt' else
                                 {'result': 'PASS_AUTHENTICATED_REFUND_LEASE_REDIS_RECOVERY'} if case == 'original_auth_pass' else None)
                report, removed, network = self.cleanup_fixture(out, original_auth, restore=True)
                self.assertEqual(report['applicationLogStatus'], 'UNAVAILABLE')
                self.assertIn('cleanupFailure', report)
                self.assertEqual(len(removed), 3)
                self.assertFalse(network)

    def test_restore_prestart_cannot_waive_real_app_log_failure_or_unknown_physical_absence(self):
        for case in ('app_log_error', 'daemon', 'pull_request', 'normal_mode'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.dict(probe.os.environ, self.restore_prestart_env(), clear=True):
                out = Path(directory)
                probe.write_report(out, 'restore-http-application.json', self.restore_prestart_report())
                if case == 'pull_request': probe.os.environ['GITHUB_EVENT_NAME'] = 'pull_request'
                report, removed, network = self.cleanup_fixture(out, None, restore=case != 'normal_mode',
                    app=case == 'app_log_error', logs='error', daemon_error=case == 'daemon')
                self.assertNotEqual(report.get('applicationLogStatus'), 'NOT_STARTED')
                self.assertIn('cleanupFailure', report)
                if case == 'daemon':
                    self.assertTrue(network)
                    self.assertEqual(report['resourceCleanup']['cleanup'], 'NOT_CONFIRMED')
                else:
                    self.assertFalse(network)
                    self.assertEqual(report['resourceCleanup']['cleanup'], 'CONFIRMED_EXACT_OWNED_REMOVAL')

    def test_isolated_driver_install_uses_owned_venv_and_keeps_original_modes_and_I(self):
        workflow = (path.parents[1] / '.github/workflows/isolated-authenticated-redis-recovery.yml').read_text()
        original = workflow.split('      - name: Install existing pinned schema client\n', 1)[1].split('      - name:', 1)[0]
        isolated = workflow.split('      - name: Install the same pinned driver in the task-owned isolated Python\n', 1)[1].split('      - name:', 1)[0]
        modes = "github.event_name == 'workflow_dispatch' && (inputs.probe_mode == 'restore-application-startup' || inputs.probe_mode == 'gameplay-process-recovery' || inputs.probe_mode == 'gameplay-capacity-mixed')"
        self.assertIn('if: ${{ !(' + modes + ') }}', original)
        self.assertIn('if: ${{ ' + modes + ' }}', isolated)
        self.assertIn('run: python3 -m pip install --disable-pip-version-check PyMySQL==1.1.1', original)
        for text in ('python3 -m venv "$venv"', '"$venv/bin/python3" -I -m pip install --no-user',
                     'PyMySQL==1.1.1', "importlib.metadata.version('PyMySQL') == '1.1.1'",
                     'sys.prefix != sys.base_prefix', 'crypt.METHOD_BLOWFISH',
                     'printf \'%s\\n\' "$venv/bin" >> "$GITHUB_PATH"'):
            self.assertIn(text, isolated)
        self.assertNotIn('--system-site-packages', isolated)
        self.assertNotIn('PYTHONPATH', isolated)
        cleanup = workflow.split('      - name: Remove only this task-owned isolated Python\n', 1)[1].split('      - name:', 1)[0]
        self.assertIn("always() && steps.isolated_python.outcome != 'skipped'", cleanup)
        self.assertIn('"$venv/.chengyin-owner"', cleanup)
        self.assertIn('test ! -L "$venv"', cleanup)
        self.assertIn('rm -rf -- "$venv"', cleanup)
        self.assertLess(workflow.index('bash ci/authenticated-redis-recovery.sh'), workflow.index('Remove only this task-owned isolated Python'))
        self.assertLess(workflow.index('Remove only this task-owned isolated Python'), workflow.index('Preserve explicit safe evidence'))

    def test_restore_startup_admission_is_manual_exclusive_and_child_mode_only(self):
        env = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ENVIRONMENT': 'github-hosted',
               'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_WORKFLOW': 'Isolated authenticated Redis recovery',
               'GITHUB_JOB': 'isolated-authenticated-redis-recovery', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_WORKFLOW_REF': 'liboyang42-cpu/hengyin/.github/workflows/isolated-authenticated-redis-recovery.yml@refs/heads/fixed',
               'CHENGYIN_CI_AUTH_REDIS_RECOVERY': 'true', 'CHENGYIN_CI_RESTORE_HTTP_STARTUP': 'true'}
        with mock.patch.object(probe.sys, 'platform', 'linux'):
            probe.validate_execution_env(env)
            for key, value in (('GITHUB_EVENT_NAME', 'pull_request'), ('CHENGYIN_RESTORE_MODE', ''),
                               ('CHENGYIN_RESTORE_MODE', 'false'), ('CHENGYIN_CI_CAPACITY_MIXED', 'true'),
                               ('CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY', 'true'), ('CHENGYIN_CI_GAMEPLAY_CAPACITY', 'true')):
                with self.subTest(key=key, value=value), self.assertRaises(RuntimeError):
                    probe.validate_execution_env(dict(env, **{key: value}))


    def test_restore_runtime_user_is_limited_to_the_original_restored_schema(self):
        name = 'cy-auth-' + 'a' * 32
        schema = 'cy_e2e_auth_' + 'a' * 32
        source_uuid = '11111111-1111-4111-8111-111111111111'
        target_uuid = '22222222-2222-4222-8222-222222222222'
        env = {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ENVIRONMENT': 'github-hosted',
               'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_WORKFLOW': 'Isolated authenticated Redis recovery',
               'GITHUB_JOB': 'isolated-authenticated-redis-recovery', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_WORKFLOW_REF': 'liboyang42-cpu/hengyin/.github/workflows/isolated-authenticated-redis-recovery.yml@refs/heads/fixed',
               'GITHUB_SHA': 'a' * 40, 'CHENGYIN_CI_AUTH_REDIS_RECOVERY': 'true', 'CHENGYIN_CI_RESTORE_HTTP_STARTUP': 'true'}
        for case in ('complete', 'foreign-server', 'schema-missing', 'user-exists', 'wide-grants', 'unsafe-private'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                source = {'result': 'OWNED_MYSQL_TCP_READY', 'serverUuid': source_uuid, 'id': 'b' * 64, 'version': '8.0.45'}
                target = {'result': 'OWNED_MYSQL_TCP_READY', 'serverUuid': target_uuid, 'id': 'c' * 64, 'version': '8.0.45',
                          'endpoint': {'id': 'c' * 64, 'networkId': 'd' * 64, 'ip': '172.19.0.3', 'pid': 124}}
                (out / 'schema-summary.json').write_text(json.dumps({'mysqlReadiness': source}))
                (out / 'restore-mysql-readiness.json').write_text(json.dumps({'mysqlReadiness': target}))
                private = out / 'private.json'
                private.write_text(json.dumps({'schema': schema, 'serverUuid': source_uuid,
                                               'redactions': ['temporary-admin-secret'], 'password': 'temporary-admin-secret'}))
                private.chmod(0o644 if case == 'unsafe-private' else 0o600)
                before = private.read_bytes()
                db, cur, transport = mock.MagicMock(), mock.MagicMock(), mock.MagicMock()
                db.__enter__.return_value = db
                db.cursor.return_value.__enter__.return_value = cur
                transport.__enter__.return_value = 35002
                transport.__exit__.return_value = False
                values = [(target_uuid,), (1,), (1,), (0,), (0,)]
                if case == 'foreign-server':
                    values[0] = (source_uuid,)
                elif case == 'schema-missing':
                    values[1] = (0,)
                elif case == 'user-exists':
                    values[3] = (1,)
                cur.fetchone.side_effect = values
                grants = [(schema, action, 'NO') for action in ('DELETE', 'INSERT', 'SELECT', 'UPDATE')]
                cur.fetchall.return_value = grants + ([(schema, 'CREATE', 'NO')] if case == 'wide-grants' else [])
                with mock.patch.dict(probe.os.environ, env, clear=True), \
                        mock.patch.object(probe.sys, 'platform', 'linux'), \
                        mock.patch.object(probe, 'mysql_relay', return_value=transport) as relay, \
                        mock.patch.object(probe, 'database', return_value=db) as connect:
                    if case == 'complete':
                        probe.prepare_restore_runtime_user(name, schema, out, private, 0)
                    else:
                        with self.assertRaises(RuntimeError):
                            probe.prepare_restore_runtime_user(name, schema, out, private, 0)
                writes = [call.args[0] for call in cur.execute.call_args_list
                          if call.args[0].startswith(('CREATE ', 'GRANT '))]
                report = out / 'restore-runtime-user.json'
                if case == 'complete':
                    relay.assert_called_once_with(name, target, suffix='restore-mysql')
                    connect.assert_called_once_with(35002)
                    self.assertEqual(writes, ["CREATE USER 'startup_probe'@'%%' IDENTIFIED BY %s",
                                     'GRANT SELECT,INSERT,UPDATE,DELETE ON `' + schema + "`.* TO 'startup_probe'@'%'"])
                    self.assertEqual(writes[0] % ("'synthetic-local-only'",),
                                     "CREATE USER 'startup_probe'@'%' IDENTIFIED BY 'synthetic-local-only'")
                    saved = json.loads(report.read_text())
                    self.assertEqual(saved['result'], 'OWNED_RESTORED_RUNTIME_USER_PREPARED')
                    self.assertEqual(saved['serverUuid'], target_uuid)
                    self.assertEqual(saved['sourceSha'], 'a' * 40)
                    self.assertEqual(saved['privileges'], [list(row) for row in grants])
                    self.assertNotIn('synthetic-local-only', report.read_text())
                    self.assertNotIn('temporary-admin-secret', report.read_text())
                else:
                    self.assertFalse(report.exists())
                    if case != 'wide-grants':
                        self.assertEqual(writes, [])
                self.assertEqual(private.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
