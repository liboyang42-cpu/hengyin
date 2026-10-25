"""Source controls for the new child adapter, not execution of HTTP/Java/storage recovery."""
import copy
import decimal
import importlib.util
import json
import os
import tempfile
import unittest
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
        self.assertNotIn('sample_callback', source)
        self.assertIn('UNKNOWN_PRESERVE_ORIGINAL', source)


if __name__ == '__main__':
    unittest.main()
