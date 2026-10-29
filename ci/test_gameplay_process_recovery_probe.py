"""Source controls for the new child adapter, not execution of HTTP/Java/storage recovery."""
import copy
import decimal
import importlib.util
import json
import os
import tempfile
import threading
import unittest
import urllib.parse
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gameplay_recovery_controls', ROOT / 'ci/gameplay_process_recovery_probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class GameplayProcessRecoveryControls(unittest.TestCase):
    def test_child_requires_its_explicit_admission_in_addition_to_original_host_gate(self):
        env = {'CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY': 'true', 'GITHUB_SHA': 'a' * 40}
        with mock.patch.object(probe.owned, 'validate_execution_env') as gate, \
                mock.patch.object(probe, 'command', return_value=('a' * 40).encode()):
            probe.gate(env)
            gate.assert_called_once_with(env)
            for changed in ({}, dict(env, CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY='false'),
                            dict(env, GITHUB_SHA='a' * 39)):
                with self.subTest(env=list(changed)), self.assertRaises(RuntimeError):
                    probe.gate(changed)
        with mock.patch.object(probe.owned, 'validate_execution_env', side_effect=RuntimeError('wrong owner')), \
                mock.patch.object(probe, 'command') as command:
            with self.assertRaises(RuntimeError):
                probe.gate(env)
            command.assert_not_called()

    def test_running_pid_or_closed_context_is_never_physical_exit_evidence(self):
        cid = 'b' * 64
        before = {'Id': cid, 'State': {'Running': True, 'Pid': 123}}
        after = {'Id': cid, 'State': {'Running': False, 'OOMKilled': False, 'ExitCode': 137}}
        probe.require_physical_exit(cid, before, after, 137)
        for mutated in [dict(after, Id='c' * 64),
                        {'Id': cid, 'State': dict(after['State'], Running=True)},
                        {'Id': cid, 'State': dict(after['State'], OOMKilled=True)},
                        {'Id': cid, 'State': dict(after['State'], ExitCode=0)}]:
            with self.subTest(state=mutated), self.assertRaises(RuntimeError):
                probe.require_physical_exit(cid, before, mutated, 137)
        with self.assertRaises(RuntimeError):
            probe.require_physical_exit(cid, before, after, 0)
        with self.assertRaises(RuntimeError):
            probe.require_physical_exit(cid, {'Id': cid, 'State': {'Running': False, 'Pid': 0}}, after, 137)

    def test_capacity_binding_freezes_the_verified_original_recovered_boot_and_artifacts(self):
        report = {key: 'b' * 64 for key in ('jarSha256', 'childSourceSha256', 'childClassSha256',
                    'testClassTreeSha256', 'configSha256', 'fixturePropertiesSha256')}
        report.update(sourceSha='a' * 40, storeProcessIdentity={
            'mysql': {'id': 'm', 'Pid': 1, 'StartedAt': 'original-m', 'networkId': 'n'},
            'redis': {'id': 'r', 'Pid': 2, 'StartedAt': 'original-r', 'networkId': 'n'}})
        report.update(schema='owned-schema', serverUuid='owned-uuid', initialBootId='original-seed', identitiesSha256='d' * 64,
            originalActors={label: {'memberId': member, 'tokenSha256': 'e' * 64} for label, member in (('a', 7), ('b', 8))})
        report['gameplayActorProof'] = {'schema': report['schema'], 'serverUuid': report['serverUuid'],
            'seedBootId': report['initialBootId'], 'identitiesSha256': report['identitiesSha256'],
            'columns': ['id'], 'rowsSha256': 'f' * 64, 'authority': {},
            'actors': {label: dict(actor, rowSha256='f' * 64) for label, actor in report['originalActors'].items()}}
        boot = {key: report[key] for key in ('sourceSha', 'jarSha256', 'childClassSha256', 'configSha256')}
        boot.update(bootId='original-boot', javaPid=21, port=8080)
        app = {'Id': 'c' * 64, 'State': {'Running': True, 'Paused': False, 'OOMKilled': False,
                                       'Pid': 100, 'StartedAt': 'original-app'}}
        binding = probe.capacity_profile_binding(report, boot, app, 'n')
        self.assertEqual(report['fixturePropertiesSha256'], binding['fixturePropertiesSha256'])
        self.assertEqual('original-boot', binding['boot']['bootId'])
        self.assertEqual('c' * 64, binding['appCid'])
        self.assertEqual(100, binding['appPid'])
        self.assertEqual('original-app', binding['appStartedAt'])
        self.assertEqual(report['storeProcessIdentity'], binding['storeProcessIdentity'])
        boot['bootId'] = 'next-boot'; app['State']['Pid'] = 101
        report['storeProcessIdentity']['mysql']['Pid'] = 99
        self.assertEqual('original-boot', binding['boot']['bootId'])
        self.assertEqual(100, binding['appPid'])
        self.assertEqual(1, binding['storeProcessIdentity']['mysql']['Pid'])
        for key, value in (('sourceSha', 'c' * 40), ('jarSha256', 'c' * 64),
                           ('configSha256', 'c' * 64), ('port', 8081), ('javaPid', 0)):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                probe.capacity_profile_binding(report, dict(boot, **{key: value}), app, 'n')
        for key in ('Running', 'Paused', 'OOMKilled'):
            changed = copy.deepcopy(app); changed['State'][key] = key != 'Running'
            with self.subTest(state=key), self.assertRaises(RuntimeError):
                probe.capacity_profile_binding(report, boot, changed, 'n')

    def test_neither_store_may_restart_or_change_while_only_the_child_is_killed(self):
        original = {'mysql': {'id': 'm', 'Pid': 1, 'StartedAt': 'original-m'},
                    'redis': {'id': 'r', 'Pid': 2, 'StartedAt': 'original-r'}}
        probe.assert_store_identity(original, copy.deepcopy(original))
        for store in original:
            for field in original[store]:
                changed = copy.deepcopy(original)
                changed[store][field] = 'changed'
                with self.subTest(store=store, field=field), self.assertRaises(RuntimeError):
                    probe.assert_store_identity(original, changed)

    def test_original_receipt_requires_same_actor_target_input_and_applied_terminal(self):
        request = {'activityId': 12, 'commandId': 'original-command', 'expectedChapterId': 31, 'targetChapterId': 32}
        actor = 9
        body = {'code': 200, 'msg': '已开启:second', 'data': {key: request[key] for key in ('commandId', 'expectedChapterId', 'targetChapterId')}}
        receipt = {'id': 101, 'activity_id': 12, 'actor_member_id': actor, 'request_id': 'lead:' + probe.digest(b'original-command')[:48],
                   'request_hash': probe.digest(b'12:9:31:32'), 'action': 'LEAD_UNLOCK_CHAPTER', 'actor_perspective': 'CLUB_LEAD',
                   'outcome': 'APPLIED', 'result_json': json.dumps(body)}
        self.assertEqual(probe.require_receipt([receipt], request, actor, 1), body)
        self.assertIsNone(probe.require_receipt([], request, actor, 0))
        for field, value in [('actor_member_id', 10), ('activity_id', 13), ('request_id', 'another'),
                             ('request_hash', 'changed'), ('action', 'OTHER'), ('actor_perspective', 'OTHER'),
                             ('outcome', 'UNKNOWN'), ('outcome', 'PENDING')]:
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                probe.require_receipt([dict(receipt, **{field: value})], request, actor, 1)
        for rows in [[], [receipt, receipt]]:
            with self.assertRaises(RuntimeError):
                probe.require_receipt(rows, request, actor, 1)
        for changed in [dict(request, expectedChapterId=32), dict(request, targetChapterId=33)]:
            with self.assertRaises(RuntimeError):
                probe.require_receipt([receipt], changed, actor, 1)
        malformed = dict(receipt, result_json=json.dumps(dict(body, data=dict(body['data'], targetChapterId=33))))
        with self.assertRaises(RuntimeError):
            probe.require_receipt([malformed], request, actor, 1)

    def test_sql_hashes_do_not_ignore_missing_rows_decimal_scale_or_types(self):
        self.assertNotEqual(probe.digest([{'amount': decimal.Decimal('1.00')}]), probe.digest([{'amount': decimal.Decimal('1.0')}]))
        self.assertNotEqual(probe.digest([{'amount': decimal.Decimal('1.00')}]), probe.digest([{'amount': '1.00'}]))
        self.assertNotEqual(probe.digest([{'value': 0}]), probe.digest([{'value': False}]))
        self.assertNotEqual(probe.digest([{'value': b'original'}]), probe.digest([{'value': 'original'}]))
        self.assertNotEqual(probe.digest([{'id': 1}, {'id': 2}]), probe.digest([{'id': 1}]))
        with self.assertRaises(TypeError):
            probe.digest(object())

    def test_accepted_effect_is_only_one_original_cas_not_just_a_receipt_or_http200(self):
        request = {'targetChapterId': 32}
        before = {'state': [{'id': 1, 'current_chapter_id': 31, 'leader_member_id': 9, 'status': 2}]}
        after = {'state': [{'id': 1, 'current_chapter_id': 32, 'leader_member_id': 9, 'status': 2}]}
        probe.require_one_chapter_effect(before, after, request)
        for changed in [before, {'state': []},
                        {'state': [dict(after['state'][0], current_chapter_id=33)]},
                        {'state': [dict(after['state'][0], leader_member_id=10)]},
                        {'state': [dict(after['state'][0], status=3)]}]:
            with self.subTest(after=changed), self.assertRaises(RuntimeError):
                probe.require_one_chapter_effect(before, changed, request)

    def test_private_identity_input_is_exclusive_mode_600_not_a_public_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'identities.json'
            probe.private_json(target, {'token': 'private-canary'})
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(target.read_text()), {'token': 'private-canary'})
            link = Path(directory) / 'foreign.json'
            link.symlink_to(target)
            with self.assertRaises(RuntimeError):
                probe.private_json(link, {})
            pending = target.with_suffix('.json.new')
            pending.write_text('unresolved original write')
            with self.assertRaises(FileExistsError):
                probe.private_json(target, {'token': 'another'})
            self.assertEqual(pending.read_text(), 'unresolved original write')

    def test_business_curve_has_72_distinct_first_intents_in_exact_24_item_batches(self):
        identity = {'CAPACITY_%02d' % i: {'activityId': i + 10, 'commandId': 'original-%d' % i,
                    'expectedChapterId': i * 2 + 1, 'targetChapterId': i * 2 + 2} for i in range(72)}
        plan = probe.capacity_plan(identity)
        self.assertEqual([concurrency for concurrency, _ in plan], [1, 3, 6])
        self.assertEqual([len(keys) for _, keys in plan], [24, 24, 24])
        for changed_field in ('activityId', 'commandId'):
            changed = copy.deepcopy(identity)
            changed['CAPACITY_71'][changed_field] = changed['CAPACITY_00'][changed_field]
            with self.subTest(field=changed_field), self.assertRaises(RuntimeError):
                probe.capacity_plan(changed)

    def protocol_fixture(self, fault=None):
        """In-memory SQL/HTTP protocol doubles, never real business/MySQL acceptance."""
        identities = {'a': {'memberId': 9, 'token': 'actor-a-private-canary'},
                      'b': {'memberId': 10, 'token': 'actor-b-private-canary'}}
        states, receipts, requests, calls = {}, {}, {}, []
        guard = threading.Lock()
        for index in range(72):
            key = 'CAPACITY_%02d' % index
            request = {'activityId': index + 100, 'commandId': 'original-command-%02d' % index,
                       'expectedChapterId': index * 2 + 1, 'targetChapterId': index * 2 + 2}
            identities[key] = request
            requests[request['activityId']] = request
            states[request['activityId']] = {'id': index + 1, 'activity_id': request['activityId'],
                'current_chapter_id': request['expectedChapterId'], 'leader_member_id': 9, 'status': 2}

        def response(request):
            return {'code': 200, 'data': {key: request[key] for key in
                    ('commandId', 'expectedChapterId', 'targetChapterId')}}

        def rows(sql, params=()):
            with guard:
                if sql == 'SELECT * FROM club_lead_state WHERE activity_id=%s':
                    return copy.deepcopy([states[params[0]]])
                if sql.startswith('SELECT * FROM game_command_receipt WHERE activity_id=%s '):
                    request = requests[params[0]]
                    self.assertEqual((params[0], 9, 'lead:' + probe.digest(request['commandId'].encode())[:48]), params)
                    return copy.deepcopy([receipts[params[0]]] if params[0] in receipts else [])
                if sql == 'SELECT * FROM `club_lead_state` ORDER BY id':
                    return copy.deepcopy([states[key] for key in sorted(states)])
                if sql == 'SELECT * FROM `game_command_receipt` ORDER BY id':
                    return copy.deepcopy([receipts[key] for key in sorted(receipts)])
                if sql == 'SELECT * FROM `business_audit_event` ORDER BY id':
                    return []
                for table in probe.owned.FINANCIAL_TABLES:
                    if sql == 'SELECT * FROM `' + table + '` ORDER BY id':
                        return [{'id': 1, 'originalStableTable': table}]
                self.fail('unexpected SQL shape in protocol double')

        def http(endpoint, token, body=None):
            with guard:
                calls.append((endpoint.split('?', 1)[0], token))
                if token == identities['b']['token']:
                    return (dict(response(body), msg='private-response-sql-canary') if fault == 'foreign_admitted'
                            else {'code': 403, 'msg': 'rejected', 'data': None})
                self.assertEqual(identities['a']['token'], token)
                if endpoint.startswith(probe.RECEIPT + '?'):
                    query = urllib.parse.parse_qs(urllib.parse.urlsplit(endpoint).query)
                    request = requests[int(query['activityId'][0])]
                    self.assertEqual([request['commandId']], query['commandId'])
                    self.assertIn(request['activityId'], receipts)
                    return response(request)
                self.assertEqual(probe.COMMAND, endpoint)
                request = requests[body['activityId']]
                self.assertEqual(request, body)
                activity = request['activityId']
                if activity not in receipts:
                    states[activity]['current_chapter_id'] = request['targetChapterId']
                    receipts[activity] = {'id': activity, 'activity_id': activity, 'actor_member_id': 9,
                        'request_id': 'lead:' + probe.digest(request['commandId'].encode())[:48],
                        'request_hash': probe.digest(('%d:9:%d:%d' % (activity, request['expectedChapterId'], request['targetChapterId'])).encode()),
                        'action': 'LEAD_UNLOCK_CHAPTER', 'actor_perspective': 'CLUB_LEAD', 'outcome': 'APPLIED',
                        'result_json': json.dumps(response(request))}
                result = response(request)
                if fault == 'bad_command_body':
                    result['data']['targetChapterId'] += 1
                    result['msg'] = result['sql'] = 'private-response-sql-canary'
                return result
        return identities, http, rows, calls, receipts

    def test_real_shared_curve_consumes_all_original_gameplay_categories(self):
        capacity = probe.load('real_shared_capacity_protocol_control', 'ci/capacity_mixed_probe.py')
        identities, http, rows, calls, receipts = self.protocol_fixture()
        report = probe.observe_core_workload(identities, http, rows, capacity.run_curve,
                                            lambda: self.fail('unexpected deadline in protocol double'))
        self.assertEqual('BUSINESS_ASSERTIONS_PASSED', report['status'])
        self.assertEqual(144, report['realHttpRequests'])
        self.assertEqual(144, len(calls))
        self.assertEqual(72, len(receipts))
        self.assertEqual([1, 3, 6], [wave['concurrency'] for wave in report['waves']])
        for wave in report['waves']:
            self.assertEqual(24, wave['writeCurve']['counts']['SUCCESS'])
            self.assertEqual(16, wave['readbackCurve']['counts']['SUCCESS'])
            self.assertEqual(8, wave['readbackCurve']['counts']['LEGAL_AUTH_REJECTION'])
            self.assertEqual(0, wave['writeCurve']['counts']['UNKNOWN'])
            self.assertEqual(0, wave['readbackCurve']['counts']['UNKNOWN'])

    def test_unknown_command_body_is_counted_but_fails_without_retry_or_private_diagnostics(self):
        capacity = probe.load('real_shared_capacity_unknown_control', 'ci/capacity_mixed_probe.py')
        identities, http, rows, calls, receipts = self.protocol_fixture('bad_command_body')
        report = {}
        with self.assertRaisesRegex(RuntimeError, 'curve omitted or suppressed a real first command'):
            probe.observe_core_workload(identities, http, rows, capacity.run_curve,
                                       lambda: self.fail('unexpected deadline'), report)
        self.assertEqual('NOT_COMPLETED', report['status'])
        self.assertEqual(24, len(calls))
        self.assertEqual(24, len(receipts), 'uncertain response must preserve each original applied identity')
        counts = report['waves'][0]['writeCurve']['counts']
        self.assertEqual(24, counts['UNKNOWN'])
        self.assertEqual(0, counts['SUCCESS'])
        for row in report['waves'][0]['writeCurve']['records']:
            self.assertEqual('GAMEPLAY_OBSERVATION_UNVERIFIED', row['reasonCode'])
            self.assertEqual('COMMAND', row['stage'])
        public = json.dumps(report)
        self.assertNotIn('private-response-sql-canary', public)
        self.assertNotIn(identities['a']['token'], public)
        self.assertNotIn(identities['b']['token'], public)

    def test_foreign_success_remains_unknown_failure_not_a_legal_rejection(self):
        capacity = probe.load('real_shared_capacity_foreign_control', 'ci/capacity_mixed_probe.py')
        identities, http, rows, calls, receipts = self.protocol_fixture('foreign_admitted')
        report = {}
        with self.assertRaisesRegex(RuntimeError, 'curve omitted/suppressed a protected read/replay/rejection'):
            probe.observe_core_workload(identities, http, rows, capacity.run_curve,
                                       lambda: self.fail('unexpected deadline'), report)
        counts = report['waves'][0]['readbackCurve']['counts']
        self.assertEqual(8, counts['UNKNOWN'])
        self.assertEqual(0, counts['LEGAL_AUTH_REJECTION'])
        self.assertEqual(16, counts['SUCCESS'])
        self.assertEqual(48, len(calls))
        self.assertEqual(24, len(receipts))
        self.assertEqual('NOT_COMPLETED', report['status'])
        self.assertNotIn('private-response-sql-canary', json.dumps(report))

    def test_business_success_cannot_finalize_without_exact_original_cleanup_and_physical_exits(self):
        name, schema = 'cy-auth-' + 'a' * 32, 'cy_e2e_auth_' + 'a' * 32
        report = {'sourceSha': 'b' * 40, 'schema': schema, 'result': 'BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING',
                  'ownedAppCid': 'app', 'storeProcessIdentity': {'mysql': {'id': 'mysql'}, 'redis': {'id': 'redis'}},
                  'cases': [{'boundary': phase, 'result': 'ASSERTIONS_PASSED', 'newBootId': 'new-' + phase,
                             'physicalExit': {'code': 137, 'oomKilled': False, 'oldBootId': 'old-' + phase}} for phase in probe.PHASES]}
        outer = {'applicationLogPreserved': True, 'resourceCleanup': {'cleanup': 'CONFIRMED_EXACT_OWNED_REMOVAL',
                 'ownedRemoved': [{'name': name + '-' + kind, 'id': kind, 'independentNoSuch': True} for kind in ('app', 'mysql', 'redis')]}}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(probe, 'gate'), \
                mock.patch.dict(os.environ, {'GITHUB_SHA': 'b' * 40, 'CHENGYIN_CI_GAMEPLAY_CAPACITY': 'false'}):
            out = Path(directory)
            for corruption in ('exit', 'logs', 'foreign-cid', 'missing-case', 'fake-exit'):
                current, cleanup = copy.deepcopy(report), copy.deepcopy(outer)
                if corruption == 'logs': cleanup['applicationLogPreserved'] = False
                if corruption == 'foreign-cid': cleanup['resourceCleanup']['ownedRemoved'][0]['id'] = 'foreign'
                if corruption == 'missing-case': current['cases'].pop()
                if corruption == 'fake-exit': current['cases'][0]['physicalExit']['code'] = 0
                (out / 'gameplay-process-recovery.json').write_text(json.dumps(current))
                (out / 'authenticated-redis-recovery.json').write_text(json.dumps(cleanup))
                with self.subTest(corruption=corruption), self.assertRaises(RuntimeError):
                    probe.finalize(name, schema, out, 1 if corruption == 'exit' else 0)
                self.assertEqual(json.loads((out / 'gameplay-process-recovery.json').read_text())['result'],
                                 'FAIL_OR_UNKNOWN_CLEANUP_OR_SCOPE')
            (out / 'gameplay-process-recovery.json').write_text(json.dumps(report))
            (out / 'authenticated-redis-recovery.json').write_text(json.dumps(outer))
            probe.finalize(name, schema, out, 0)
            self.assertEqual(json.loads((out / 'gameplay-process-recovery.json').read_text())['result'],
                             'PASS_SCOPED_GAMEPLAY_PROCESS_RECOVERY')

    def test_failed_mixed_before_observe_never_calls_missing_capacity_finalizer(self):
        name, schema = 'cy-auth-' + 'a' * 32, 'cy_e2e_auth_' + 'a' * 32
        original = {'sourceSha': 'b' * 40, 'schema': schema, 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                    'failureType': 'ModuleNotFoundError', 'reason': "No module named 'pymysql'"}
        for original_exit in (1, 124):
            with self.subTest(original_exit=original_exit), tempfile.TemporaryDirectory() as directory, \
                    mock.patch.object(probe, 'gate'), \
                    mock.patch.dict(os.environ, {'GITHUB_SHA': 'b' * 40, 'CHENGYIN_CI_GAMEPLAY_CAPACITY': 'true'}), \
                    mock.patch.object(probe, 'load') as shared:
                out = Path(directory)
                (out / 'gameplay-process-recovery.json').write_text(json.dumps(original))
                with self.assertRaisesRegex(RuntimeError, 'original shell/cleanup did not succeed'):
                    probe.finalize(name, schema, out, original_exit)
                shared.assert_not_called()
                written = json.loads((out / 'gameplay-process-recovery.json').read_text())
                self.assertEqual(original['result'], written['businessResultBeforeCleanup'])
                self.assertEqual(original['failureType'], written['failureType'])
                self.assertEqual(original['failureType'], written['firstFailure']['type'])
                self.assertEqual(original['reason'], written['reason'])
                self.assertEqual('FAIL_OR_UNKNOWN_CLEANUP_OR_SCOPE', written['result'])
                self.assertFalse((out / 'capacity-mixed.json').exists())

    def test_original_no_cash_failure_is_not_replaced_by_finalizer_cleanup_failure(self):
        name, schema = 'cy-auth-' + 'a' * 32, 'cy_e2e_auth_' + 'a' * 32
        first = {'type': 'NoCashFixtureError', 'reasonCode': 'GAMEPLAY_MEMBER_BALANCE_NOT_ZERO', 'tables': ['ums_member']}
        report = {'sourceSha': 'b' * 40, 'schema': schema, 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
                  'failureType': first['type'], 'reason': first['reasonCode'], 'firstFailure': first}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(probe, 'gate'), \
                mock.patch.dict(os.environ, {'GITHUB_SHA': 'b' * 40}), mock.patch.object(probe, 'load') as shared:
            out = Path(directory); (out / 'gameplay-process-recovery.json').write_text(json.dumps(report))
            with self.assertRaises(RuntimeError): probe.finalize(name, schema, out, 1)
            shared.assert_not_called()
            written = json.loads((out / 'gameplay-process-recovery.json').read_text())
            self.assertEqual(first, written['firstFailure'])
            self.assertEqual(first['reasonCode'], written['reason'])
            self.assertEqual(report['result'], written['businessResultBeforeCleanup'])
            self.assertIn('finalizationFailureType', written)

    def test_child_is_test_only_and_observes_real_controller_without_fake_success_or_provider(self):
        child = ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java'
        source = child.read_text()
        self.assertIn('new SpringApplication(RuoYiApplication.class, RecoveryConfiguration.class, LostReplyObserver.class)', source)
        self.assertIn('loginByMember(member, "phone", member.getPhone(), null)', source)
        self.assertIn('implements ResponseBodyAdvice<Object>', source)
        self.assertIn('TransactionSynchronizationManager.isActualTransactionActive()', source)
        self.assertIn('return body;', source)
        self.assertIn('@Profile(PROFILE)', source)
        self.assertNotIn('@RestController', source)
        self.assertNotIn('setAuthentication(', source)
        self.assertNotIn('System.exit(', source)
        self.assertNotIn('AjaxResult.success(', source)
        self.assertFalse((ROOT / 'chengyinhub-admin/src/main/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java').exists())

    def test_host_uses_only_exact_owned_kill_and_original_cleanup_namespace(self):
        source = (ROOT / 'ci/gameplay_process_recovery_probe.py').read_text()
        self.assertIn("owned.validate_targets(name, schema", source)
        self.assertIn("DOCKER + ['kill', '--signal', 'KILL', app]", source)
        self.assertIn("DOCKER + ['wait', app]", source)
        self.assertIn("DOCKER + ['start', app]", source)
        self.assertIn("'BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING'", source)
        for forbidden in ['pkill', 'killall', 'FLUSHALL', 'FLUSHDB', 'docker system prune', 'DROP DATABASE']:
            self.assertNotIn(forbidden, source)
        self.assertIn("'rejectedAllGameFactsSha256': digest(all_before)", source)
        self.assertIn("'NOT_APPLICABLE_NO_SUCH_INPUT_ON_THIS_COMMAND'", source)
        self.assertIn('request, concurrency, stage_stop, seconds=45)', source)
        self.assertIn("gameplay_binding=capacity_profile_binding(report, boot, mixed_before, network['Id'])", source)
        self.assertNotIn('sample_callback', source)
        self.assertIn('UNKNOWN_PRESERVE_ORIGINAL', source)



    def test_packaged_scope_is_manual_and_explicit_without_reusing_old_run_action(self):
        env = {'CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY': 'true',
               'CHENGYIN_CI_GAMEPLAY_CAPACITY': 'true',
               'CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY': 'true',
               'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_SHA': 'a' * 40}
        with mock.patch.object(probe.owned, 'validate_execution_env'), \
                mock.patch.object(probe, 'command', return_value=('a' * 40).encode()):
            probe.packaged_gate(env)
            for key, value in [('CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY', 'false'),
                               ('GITHUB_EVENT_NAME', 'pull_request'),
                               ('CHENGYIN_CI_GAMEPLAY_CAPACITY', 'false')]:
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    probe.packaged_gate(dict(env, **{key: value}))

    def test_packaged_jar_requires_real_main_and_unique_nested_production_classes(self):
        # Synthetic ZIPs only exercise this validator, never claim Java/runtime acceptance.
        import io
        import zipfile
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'application.jar'
            def write(main='com.chengyinhub.RuoYiApplication', source='a' * 40, child=False, duplicate=False):
                nested = io.BytesIO()
                with zipfile.ZipFile(nested, 'w') as library:
                    for key in probe.PACKAGED_CLASSES[2:]:
                        library.writestr(key + '.class', key.encode())
                    if duplicate:
                        library.writestr(probe.PACKAGED_CLASSES[0] + '.class', b'duplicate')
                    if child:
                        library.writestr(probe.CHILD_PATH + '.class', b'test-only')
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr('META-INF/MANIFEST.MF',
                        'Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\n'
                        'Start-Class: ' + main + '\r\n\r\n')
                    archive.writestr('BOOT-INF/classes/git.properties', 'git.commit.id.full=' + source)
                    for key in probe.PACKAGED_CLASSES[:2]:
                        archive.writestr('BOOT-INF/classes/' + key + '.class', key.encode())
                    archive.writestr('BOOT-INF/lib/original.jar', nested.getvalue())
            write()
            result = probe.packaged_jar_proof(jar, 'a' * 40)
            self.assertEqual('com.chengyinhub.RuoYiApplication', result['startClass'])
            self.assertFalse(result['testClasspathMountedIntoSubject'])
            self.assertEqual(set(key.replace('/', '.') for key in probe.PACKAGED_CLASSES),
                             set(result['productionClassSha256']))
            self.assertIn('BOOT-INF/lib/original.jar!/', result['productionClassOwners'][
                probe.PACKAGED_CLASSES[-1].replace('/', '.')]['entry'])
            for mutation in ({'main': probe.CHILD}, {'source': 'b' * 40}, {'child': True}, {'duplicate': True}):
                write(**mutation)
                with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                    probe.packaged_jar_proof(jar, 'a' * 40)

    def test_packaged_hard_budget_requires_real_subject_and_rejects_foreign_or_override(self):
        name = 'cy-auth-' + 'a' * 32
        row = {'Id': 'b' * 64, 'Name': '/' + name + '-app', 'Image': 'sha256:owned',
               'State': {'Running': True, 'Paused': False, 'OOMKilled': False, 'Pid': 123, 'StartedAt': 'owned'},
               'Config': {'Image': probe.JDK_IMAGE, 'Labels': {'chengyin.auth-recovery': name},
                   'Entrypoint': ['/trace/strace'],
                   'Cmd': ['-A', '-f', '-s', '0', '-e', 'trace=connect,sendto,sendmsg',
                           '-o', '/evidence/gameplay-packaged-network.trace'] + probe.PACKAGED_COMMAND,
                   'Env': ['GITHUB_SHA=' + 'a' * 40, 'CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY=true']},
               'HostConfig': {'Memory': 1073741824, 'MemorySwap': 1073741824, 'NanoCpus': 1000000000,
                              'PidsLimit': 256, 'ReadonlyRootfs': True, 'Privileged': False,
                              'PortBindings': {}, 'NetworkMode': name},
               'NetworkSettings': {'Networks': {name: {'NetworkID': 'n'}}}}
        with mock.patch.dict(os.environ, {'GITHUB_SHA': 'a' * 40}):
            self.assertEqual('b' * 64, probe.packaged_resource_proof(row, name, 'n')['cid'])
            for field, value in [('Memory', 2 * 1073741824), ('MemorySwap', -1), ('NanoCpus', 2000000000),
                                 ('PidsLimit', 512), ('ReadonlyRootfs', False), ('Privileged', True)]:
                bad = copy.deepcopy(row); bad['HostConfig'][field] = value
                with self.subTest(field=field), self.assertRaises(RuntimeError):
                    probe.packaged_resource_proof(bad, name, 'n')
            for key, value in [('Id', 'foreign'), ('Name', '/foreign')]:
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    probe.packaged_resource_proof(dict(row, **{key: value}), name, 'n')
            for override in ('JAVA_TOOL_OPTIONS=-javaagent:/foreign.jar', 'CLASSPATH=/recovery-testclasses',
                             'LOADER_MAIN=' + probe.CHILD, 'SPRING_PROFILES_ACTIVE=foreign'):
                bad = copy.deepcopy(row); bad['Config']['Env'].append(override)
                with self.subTest(override=override), self.assertRaises(RuntimeError):
                    probe.packaged_resource_proof(bad, name, 'n')
            bad = copy.deepcopy(row); bad['Config']['Cmd'][-len(probe.PACKAGED_COMMAND):] = ['java', '-jar', '/foreign.jar']
            with self.assertRaises(RuntimeError): probe.packaged_resource_proof(bad, name, 'n')

    def test_packaged_jvm_is_bound_to_actual_app_cgroup_not_a_witness_or_heap_claim(self):
        cmd = b'\0'.join(item.encode() for item in probe.PACKAGED_COMMAND) + b'\0'
        stat = b'23 (java) ' + b' '.join([b'S', b'1'] + [b'0'] * 17 + [b'123'])
        group = b'0::/owned-app\n'
        args = [cmd, stat, group, group, b'100000 100000\n', b'1073741824\n', b'0\n', b'256\n']
        self.assertEqual('123', probe.packaged_jvm_proof(*args)['javaStartTicks'])
        for index, bad in [(0, b'java\0-Xmx512m\0'), (1, b'23 (java) S 1'),
                           (2, b'0::/foreign\n'), (4, b'max 100000'), (5, b'2147483648'),
                           (6, b'1073741824'), (7, b'512')]:
            changed = list(args); changed[index] = bad
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                probe.packaged_jvm_proof(*changed)

    def test_takeover_hook_follows_committed_writes_without_extra_business_requests(self):
        capacity = probe.load('packaged_curve_protocol_control', 'ci/capacity_mixed_probe.py')
        identities, http, rows, calls, receipts = self.protocol_fixture()
        stages = []
        def transition(concurrency):
            stages.append((concurrency, len(calls), len(receipts)))
            return None if concurrency != 6 else {'originalIdentityReadbackRequired': True}
        report = probe.observe_core_workload(identities, http, rows, capacity.run_curve,
            lambda: self.fail('unexpected deadline'), before_readback=transition)
        self.assertEqual([(1, 24, 24), (3, 72, 48), (6, 120, 72)], stages)
        self.assertEqual(144, len(calls))
        self.assertEqual(72, len(receipts))
        self.assertEqual('BUSINESS_ASSERTIONS_PASSED', report['status'])
        self.assertEqual({'originalIdentityReadbackRequired': True}, report['waves'][-1]['subjectTransition'])

    def test_failed_transition_preserves_original_committed_intents_and_never_replays(self):
        capacity = probe.load('packaged_transition_failure_control', 'ci/capacity_mixed_probe.py')
        identities, http, rows, calls, receipts = self.protocol_fixture()
        report = {}
        def rejected(_):
            raise RuntimeError('original instance absence unconfirmed')
        with self.assertRaisesRegex(RuntimeError, 'original instance absence unconfirmed'):
            probe.observe_core_workload(identities, http, rows, capacity.run_curve,
                lambda: self.fail('unexpected deadline'), report, before_readback=rejected)
        self.assertEqual('NOT_COMPLETED', report['status'])
        self.assertEqual(24, len(calls))
        self.assertEqual(24, len(receipts), 'original committed receipts survive a failed takeover')

    def test_packaged_receipt_fault_classification_never_invents_unsent_or_success(self):
        original = {'commandId': 'original', 'expectedChapterId': 31, 'targetChapterId': 32}
        self.assertEqual('SUCCESS', probe.packaged_receipt_outcome({'code': 200, 'data': original}, original))
        self.assertEqual('LEGAL_AUTH_REJECTION', probe.packaged_receipt_outcome({'code': 401}, original))
        self.assertEqual('SYSTEM_FAILURE', probe.packaged_receipt_outcome({'code': 500}, original))
        for response in ({'code': 520}, {'code': 409}, {'code': 401, 'data': original}, {}, {'code': '500'}):
            self.assertEqual('UNKNOWN', probe.packaged_receipt_outcome(response, original))
        with self.assertRaises(RuntimeError):
            probe.packaged_receipt_outcome({'code': 200, 'data': dict(original, targetChapterId=33)}, original)

    def test_packaged_failed_business_cannot_call_missing_mixed_finalizer_or_clear_first_error(self):
        name, schema = 'cy-auth-' + 'a' * 32, 'cy_e2e_auth_' + 'a' * 32
        first = {'type': 'TimeoutError', 'reasonCode': 'PACKAGED_SCOPE_NOT_ACCEPTED'}
        original = {'result': 'FAIL_OR_UNKNOWN_NO_RETRY', 'firstFailure': first}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(probe, 'packaged_gate'), \
                mock.patch.object(probe, 'load') as shared:
            out = Path(directory)
            (out / 'gameplay-packaged-capacity.json').write_text(json.dumps(original))
            with self.assertRaises(RuntimeError): probe.finalize_packaged(name, schema, out, 1)
            shared.assert_not_called()
            saved = json.loads((out / 'gameplay-packaged-capacity.json').read_text())
            self.assertEqual(first, saved['firstFailure'])
            self.assertEqual(original['result'], saved['businessResultBeforeCleanup'])
            self.assertEqual('FAIL_OR_UNKNOWN_CLEANUP_OR_SCOPE', saved['result'])

    def test_seed_only_path_is_separate_from_packaged_main_and_has_no_fixture_endpoint(self):
        source = (ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java').read_text()
        host = (ROOT / 'ci/gameplay_process_recovery_probe.py').read_text()
        self.assertIn('config.path("packagedSeedOnly").isBoolean()', source)
        self.assertIn('context.close();', source)
        self.assertIn('CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY', source)
        self.assertIn("retire(True, expected_exit=0)", host)
        self.assertIn("if seed:\n            cmd += ['-v', str(child_classes)", host)
        self.assertIn('boot = boot_main()', host)
        self.assertIn('packaged_subject=True, business_probe=business_probe', host)
        self.assertIn("report['actualBusinessHttpInvocations'] = 146", host)
        self.assertNotIn('@RestController', source)
        self.assertNotIn('setAuthentication(', source)
        self.assertNotIn('System.exit(', source)


    def test_packaged_business_validator_keeps_original_curves_categories_and_economic_facts(self):
        capacity = probe.load('packaged_complete_protocol_control', 'ci/capacity_mixed_probe.py')
        identities, http, rows, calls, receipts = self.protocol_fixture()
        transition = {'oldCid': 'a' * 64, 'newCid': 'b' * 64, 'originalFactsSha256': 'c' * 64,
                      'status': 'ORIGINAL_24_IDENTITY_READ_REPLAY_FOREIGN_AND_ALL_SQL_FACTS_VERIFIED'}
        core = probe.observe_core_workload(identities, http, rows, capacity.run_curve,
            lambda: self.fail('unexpected deadline'), before_readback=lambda size: transition if size == 6 else None)
        subject = {'sourceSha': 'd' * 40, 'jarSha256': 'e' * 64, 'productionClassSha256': {key.replace('/', '.'): 'f' * 64 for key in probe.PACKAGED_CLASSES},
                   'testClasspathMountedIntoSubject': False, 'allObservedJvmThreadsInSameCgroup': True,
                   'memoryBytes': 1073741824, 'memorySwapBytes': 1073741824, 'nanoCpus': 1000000000, 'pidsLimit': 256,
                   'actualMemoryMaxBytes': 1073741824, 'actualSwapMaxBytes': 0, 'actualPidsMax': 256}
        finance = {key: {'rows': 0, 'sha256': '0' * 64} for key in probe.owned.FINANCIAL_TABLES}
        report = {'sourceSha': 'd' * 40, 'jarSha256': 'e' * 64, 'productionClassSha256': subject['productionClassSha256'],
                  'coreBusinessCurve': core, 'instanceTakeover': transition, 'subjects': [subject, copy.deepcopy(subject)],
                  'financialBefore': finance, 'financialAfter': copy.deepcopy(finance),
                  'wholeSystemCapacityAccepted': False, 'q4q8Complete': False, 'actualBusinessHttpInvocations': 146,
                  'businessFaultReadbacks': [{'stage': 'FAULT_HELD', 'category': 'LEGAL_AUTH_REJECTION', 'elapsedSeconds': 1,
                                              'originalIntentSha256': 'a' * 64, 'beforeFactsSha256': 'b' * 64,
                                              'afterFactsSha256': 'b' * 64, 'resultSha256': None},
                                            {'stage': 'RECOVERED', 'category': 'SUCCESS', 'elapsedSeconds': 1,
                                             'originalIntentSha256': 'a' * 64, 'beforeFactsSha256': 'b' * 64,
                                             'afterFactsSha256': 'b' * 64, 'resultSha256': 'c' * 64}]}
        probe.validate_packaged_business(report)
        for mutation in ('whole', 'budget', 'foreign', 'curve-missing', 'economic', 'fault-missing', 'recovery-unknown'):
            changed = copy.deepcopy(report)
            if mutation == 'whole': changed['q4q8Complete'] = True
            if mutation == 'budget': changed['subjects'][0]['actualSwapMaxBytes'] = 1073741824
            if mutation == 'foreign': changed['coreBusinessCurve']['waves'][0]['readbackCurve']['counts']['LEGAL_AUTH_REJECTION'] = 0
            if mutation == 'curve-missing': changed['coreBusinessCurve']['waves'][2]['writeCurve']['records'].pop()
            if mutation == 'economic': changed['financialAfter']['ums_member_balance_detail']['rows'] = 1
            if mutation == 'fault-missing': changed['businessFaultReadbacks'].pop()
            if mutation == 'recovery-unknown': changed['businessFaultReadbacks'][1]['category'] = 'UNKNOWN'
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                probe.validate_packaged_business(changed)


    def test_fault_oracle_allows_only_explicit_owned_redis_pause_and_never_mysql_pause(self):
        name = 'cy-auth-' + 'a' * 32
        objects = {}
        for key in ('mysql', 'redis'):
            objects[name + '-' + key] = {
                'Name': '/' + name + '-' + key, 'Id': key,
                'Config': {'Labels': {'chengyin.auth-recovery': name}},
                'NetworkSettings': {'Networks': {name: {'NetworkID': 'original-network'}}},
                'State': {'Running': True, 'Paused': key == 'redis', 'OOMKilled': False,
                          'Pid': 1 if key == 'mysql' else 2, 'StartedAt': 'original-' + key}}
        with mock.patch.object(probe, 'inspect', side_effect=lambda key: objects[key]):
            with self.assertRaises(RuntimeError): probe.store_identity(name)
            observed = probe.store_identity(name, allow_redis_pause=True)
            self.assertEqual('redis', observed['redis']['id'])
            objects[name + '-mysql']['State']['Paused'] = True
            with self.assertRaises(RuntimeError): probe.store_identity(name, allow_redis_pause=True)

if __name__ == '__main__':
    unittest.main()
