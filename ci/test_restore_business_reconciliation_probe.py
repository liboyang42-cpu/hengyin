"""Control SOURCE only. Synthetic comparisons are not a MySQL/provider/restore acceptance."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('restore_business_probe', HERE / 'restore_business_reconciliation_probe.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def table(rows):
    names = list(rows[0]) if rows else []
    return {'columns': [[name, 'fixture-control-only'] for name in names],
            'rows': [[None if row.get(name) is None else str(row[name]) for name in names] for row in rows]}


def scenario():
    commands = {}
    for number, case in enumerate(('historical_paid', 'late_success', 'not_sent', 'unknown', 'lost_success'), 11):
        commands[case] = {'case': case, 'scope': 'a' * 32, 'domain': 'REGISTRATION', 'bizId': number,
                          'intentId': None if number == 11 else number - 11,
                          'businessNo': 'T20260724PAY' if number == 11 else 'ORDER' + str(number),
                          'wxNo': 'T20260724PAY' if number == 11 else 'WX' + str(number),
                          'fingerprint': None if number == 11 else 'b' * 64, 'totalFen': 10000,
                          'providerId': 'TX' + str(number), 'mchid': 'mch-funds-it', 'appid': 'wx-funds-it', 'currency': 'CNY',
                          'origin': 'HISTORICAL_MASTER_ONLY:T20260724PAY:original-funds-fixture' if number == 11 else 'FROZEN_ORIGINAL'}
    def snapshot(lost):
        identities = list(commands.values())[:5 if lost else 4]
        paid = [commands['historical_paid']] + ([commands['lost_success']] if lost else [])
        registrations = [dict(id=c['bizId'], registration_no=c['businessNo'], wechat_payment_amount='100.00',
                              payment_status=2 if c in paid else 1) for c in identities]
        frozen = [dict(id=c['intentId'], biz_id=c['bizId'], request_hash=c['fingerprint'], out_trade_no=c['wxNo'],
                       business_out_trade_no=c['businessNo'], total_fen=c['totalFen']) for c in identities if c['intentId']]
        facts = [dict(id=i + 1, fact_hash='f' * 64, transaction_id=c['providerId'], fact_kind='PAYMENT',
                      mchid=c['mchid'], appid=c['appid'], currency=c['currency'], total_fen=c['totalFen'],
                      business_out_trade_no=c['businessNo'], wx_out_trade_no=c['wxNo'],
                      intent_request_hash=c['fingerprint']) for i, c in enumerate(paid)]
        receipts = [dict(registration_id=c['bizId'], registration_no=c['businessNo'], payment_fact_id=i + 1,
                         payment_fact_hash='f' * 64, transaction_id=c['providerId']) for i, c in enumerate(paid)]
        entries = [dict(id=1, member_id=7, current_points='100.00', change_points='10.00', after_points='110.00', event_type=2, event_id=0),
                   dict(id=2, member_id=7, current_points='110.00', change_points='5.00', after_points='115.00', event_type=3, event_id=11)]
        if lost:
            entries.append(dict(id=3, member_id=7, current_points='115.00', change_points='5.00', after_points='120.00', event_type=3, event_id=15))
        contents = {'cms_registration': registrations, 'wx_pay_order_intent': frozen, 'wx_verified_payment_fact': facts,
                    'cms_registration_payment_fact': receipts, 'ums_member': [dict(id=7, point=120 if lost else 115)],
                    'ums_member_points_detail': entries, 'ums_member_balance_detail': [], 'refund_application': [],
                    'cms_registration_scan': []}
        return {'tables': {t: table(rows) for t, rows in contents.items()},
                'highWater': {t: {'max': 15 if lost else 14, 'next': 16 if lost else 15} for t in p.HIGH_IDS}}
    before = {'records': [{'original': 'prefix'}]}
    after = {'records': before['records'] + [{'late': 'controlled'}], 'commands': commands,
             'states': {'historical_paid': 'SUCCESS', 'late_success': 'SUCCESS', 'lost_success': 'SUCCESS',
                        'not_sent': 'NOT_SENT', 'unknown': 'UNKNOWN'},
             'submits': {'historical_paid': 1, 'late_success': 1, 'lost_success': 1, 'not_sent': 0, 'unknown': 1}}
    backup, fault = snapshot(False), snapshot(True)
    return backup, fault, copy.deepcopy(backup), before, after


def replace(snapshot, name, key, value):
    entry = snapshot['tables'][name]
    entry['rows'][0][[c[0] for c in entry['columns']].index(key)] = str(value)


class ReconciliationControls(unittest.TestCase):
    def test_complete_fixed_timeline_is_observation_and_always_blocks_release(self):
        result = p.reconcile(*scenario())
        self.assertEqual('BLOCKED', result['candidateState'])
        self.assertIs(False, result['dispatchRelease'])
        self.assertEqual(5, len(result['differences']))
        self.assertEqual('UNKNOWN_MISSING_WHOLE_ORIGINAL_MANUAL_BLOCK',
                         next(d['status'] for d in result['differences'] if d['case'] == 'lost_success'))

    def test_typed_row_difference_is_not_ignored(self):
        values = scenario(); replace(values[2], 'cms_registration', 'payment_status', 0)
        with self.assertRaisesRegex(ValueError, 'typed rows'): p.reconcile(*values)

    def test_auto_increment_restoration_does_not_authorize_reuse(self):
        values = scenario()
        for s in (values[0], values[2]): s['highWater']['cms_registration']['next'] = 16
        with self.assertRaisesRegex(ValueError, 'high-water'): p.reconcile(*values)

    def test_missing_intent_is_never_not_sent(self):
        values = scenario(); values[4]['states']['lost_success'] = 'NOT_SENT'
        with self.assertRaisesRegex(ValueError, 'timeline'): p.reconcile(*values)

    def test_lost_whole_intent_requires_actual_source_consumption(self):
        values = scenario(); values[1]['tables']['cms_registration_payment_fact']['rows'].pop()
        with self.assertRaisesRegex(ValueError, 'actually produced'): p.reconcile(*values)

    def test_provider_history_must_survive_restore(self):
        values = scenario(); values[4]['records'][0] = {'rolled': 'back'}
        with self.assertRaisesRegex(ValueError, 'rolled back'): p.reconcile(*values)

    def test_duplicate_provider_submit_is_rejected(self):
        values = scenario(); values[4]['submits']['late_success'] = 2
        with self.assertRaisesRegex(ValueError, 'duplicate'): p.reconcile(*values)

    def test_different_original_amount_cannot_be_normalized(self):
        values = scenario(); values[4]['commands']['lost_success']['totalFen'] = 10001
        with self.assertRaisesRegex(ValueError, 'business money'): p.reconcile(*values)

    def test_different_original_hash_cannot_be_normalized(self):
        with self.subTest(fence='consumed_original_fact_hash'):
            # Preserve the original negative input: its consumed fact rejects the changed
            # fingerprint before the later frozen-intent comparison can be reached.
            values = scenario(); values[4]['commands']['lost_success']['fingerprint'] = 'c' * 64
            with self.assertRaisesRegex(ValueError, '^provider original money/identity not consumed exactly$'):
                p.reconcile(*values)
        with self.subTest(fence='unconsumed_frozen_request_hash'):
            # No consumption receipt exists here, so the unchanged original frozen hash
            # must independently reject the same fingerprint-only alteration.
            values = scenario(); values[4]['commands']['late_success']['fingerprint'] = 'c' * 64
            with self.assertRaisesRegex(ValueError, '^original frozen request/amount differs$'):
                p.reconcile(*values)

    def test_different_original_business_identity_cannot_be_normalized(self):
        values = scenario(); values[4]['commands']['lost_success']['businessNo'] = 'another-order'
        with self.assertRaisesRegex(ValueError, 'source identity'): p.reconcile(*values)

    def test_receipt_reference_must_exist_not_only_a_success_status(self):
        values = scenario()
        for s in (values[0], values[2]): replace(s, 'cms_registration_payment_fact', 'payment_fact_id', 999)
        with self.assertRaisesRegex(ValueError, 'receipt/reference'): p.reconcile(*values)

    def test_full_points_balance_and_ledger_are_exact(self):
        values = scenario()
        for s in (values[0], values[2]): replace(s, 'ums_member', 'point', 116)
        with self.assertRaisesRegex(ValueError, 'conserve points'): p.reconcile(*values)

    def test_no_widening_into_refund_cash_fixture(self):
        values = scenario()
        for s in (values[0], values[2]): s['tables']['refund_application'] = table([{'id': 20}])
        with self.assertRaisesRegex(ValueError, 'unowned cash/refund'): p.reconcile(*values)

    def test_partial_case_roster_cannot_pass(self):
        values = scenario(); del values[4]['commands']['unknown']
        with self.assertRaisesRegex(ValueError, 'five'): p.reconcile(*values)


class JournalControls(unittest.TestCase):
    def write(self, path, rows):
        previous = '0' * 64
        with path.open('wb') as f:
            for i, (command, state) in enumerate(rows, 1):
                payload = json.dumps({'sequence': i, 'previous': previous, 'at': '2026-10-25T01:00:00Z',
                                      'command': command, 'state': state})
                previous = p.sha(payload.encode())
                f.write((json.dumps({'payload': payload, 'sha256': previous}) + '\n').encode())
        path.chmod(0o600)

    def test_journal_is_independent_and_exact_original(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'provider.jsonl'; c = scenario()[4]['commands']['late_success']
            self.write(path, [(c, 'SUBMIT'), (c, 'ACCEPTED'), (c, 'SUCCESS')])
            state = p.journal(path, 'a' * 32)
            self.assertEqual(c, state['commands']['late_success']); self.assertEqual(1, state['submits']['late_success'])

    def test_journal_truncation_is_unknown_not_empty_success(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'provider.jsonl'; path.write_text(''); path.chmod(0o600)
            with self.assertRaisesRegex(ValueError, 'incomplete'): p.journal(path, 'a' * 32)

    def test_rehashed_changed_command_is_still_refused(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'provider.jsonl'; c = scenario()[4]['commands']['late_success']
            changed = dict(c, totalFen=10001)
            self.write(path, [(c, 'SUBMIT'), (changed, 'SUCCESS')])
            with self.assertRaisesRegex(ValueError, 'identity/amount'): p.journal(path, 'a' * 32)

    def test_wrong_scope_and_second_submit_are_refused(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'provider.jsonl'; c = scenario()[4]['commands']['late_success']
            self.write(path, [(c, 'SUBMIT'), (c, 'SUBMIT')])
            with self.assertRaisesRegex(ValueError, 'duplicate'): p.journal(path, 'a' * 32)
            with self.assertRaisesRegex(ValueError, 'foreign'): p.journal(path, 'b' * 32)

    def test_legacy_hash_cannot_be_guessed_from_current_configuration(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'provider.jsonl'; c = scenario()[4]['commands']['historical_paid']
            c['fingerprint'] = 'f' * 64; self.write(path, [(c, 'SUBMIT')])
            with self.assertRaisesRegex(ValueError, 'legacy'): p.journal(path, 'a' * 32)


class CanonicalToolControls(unittest.TestCase):
    """Delegate the real unmodified canonical function. Mocks below ONLY prove refusal ordering, not MySQL."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / 'backup.sql'; self.file.write_bytes(b'controlled-sql-not-executed')
        self.tool = p.module('canonical_restore_control', 'scripts/db-backup-restore.py')
        self.database = 'owned_fixture'
        self.args = argparse.Namespace(backup=self.file, database=self.database, defaults_file=Path('/not-used'),
                                       server_uuid='target', confirm_test_target=True)
        self.snapshot = {'tables': {'t': {'rows': 1, 'checksum': '1'}}, 'views': [],
                         'objects': {'ROUTINES': 0, 'TRIGGERS': 0, 'EVENTS': 0},
                         'definitions': {'DATABASE:owned_fixture': 'a' * 64, 'TABLE:t': 'b' * 64}}
        self.manifest = {'formatVersion': 3, 'definitionAlgorithm': 'mysql8-column-charset-v1',
                         'serverUuid': 'source', 'mysqlVersion': '8.0.45', 'database': self.database,
                         'sha256': self.tool.digest(self.file), 'snapshot': self.snapshot}

    def rejected(self, message, *, manifest=None, identity=None, target=None, scheduler='OFF', imported=None):
        Path(str(self.file) + '.json').write_text(json.dumps(manifest or self.manifest))
        empty = {'tables': {}, 'views': [], 'objects': {'ROUTINES': 0, 'TRIGGERS': 0, 'EVENTS': 0},
                 'definitions': {'DATABASE:owned_fixture': 'a' * 64}}
        with patch.object(self.tool, 'identify', return_value=identity or ['target', '8.0.45', self.database]), \
             patch.object(self.tool, 'mysql', return_value=scheduler), \
             patch.object(self.tool, 'snapshot', side_effect=[target or empty, imported or self.snapshot]), \
             patch.object(self.tool.subprocess, 'run') as process:
            process.return_value.returncode = 0
            with self.assertRaisesRegex(ValueError, message): self.tool.restore(self.args)
            if imported is None: process.assert_not_called()

    def test_truncated_manifest_refuses_before_any_target_write(self):
        changed = copy.deepcopy(self.manifest); del changed['snapshot']['definitions']['TABLE:t']
        self.rejected('complete definition', manifest=changed)

    def test_legacy_manifest_cannot_silently_upgrade(self):
        changed = copy.deepcopy(self.manifest); changed['formatVersion'] = 2
        self.rejected('complete definition', manifest=changed)

    def test_wrong_hash_or_truncated_dump_is_refused(self):
        self.file.write_bytes(b'truncated'); self.rejected('hash differs')

    def test_same_uuid_never_becomes_a_convenient_single_mysql_drill(self):
        self.rejected('different MySQL', identity=['source', '8.0.45', self.database])

    def test_different_version_is_refused(self):
        self.rejected('same version', identity=['target', '8.4.0', self.database])

    def test_nonempty_restore_target_is_not_dropped_to_pass(self):
        self.rejected('must be empty', target=self.snapshot)

    def test_database_definition_drift_refused_before_import(self):
        target = {'tables': {}, 'views': [], 'objects': {}, 'definitions': {'DATABASE:owned_fixture': 'c' * 64}}
        self.rejected('database definition differs', target=target)

    def test_scheduler_on_refuses_restore(self):
        self.rejected('event scheduler disabled', scheduler='ON')

    def test_imported_row_difference_fails_full_canonical_verification(self):
        changed = copy.deepcopy(self.snapshot); changed['tables']['t']['rows'] = 2
        self.rejected('failed verification', imported=changed)

    def test_imported_definition_difference_is_not_hidden(self):
        changed = copy.deepcopy(self.snapshot); changed['definitions']['TABLE:t'] = 'c' * 64
        self.rejected('failed verification', imported=changed)

    def test_failed_dump_cannot_produce_a_verified_backup(self):
        output = Path(self.temp.name) / 'new.sql'
        self.args.output = output
        with patch.object(self.tool, 'identify', return_value=['source', '8.0.45', self.database]), \
             patch.object(self.tool, 'snapshot', return_value=self.snapshot), \
             patch.object(self.tool.subprocess, 'run') as process:
            process.return_value.returncode = 1
            with self.assertRaisesRegex(ValueError, 'Dump failed'): self.tool.backup(self.args)
        self.assertFalse(output.exists()); self.assertFalse(Path(str(output) + '.partial').exists())


class HostBoundaryControls(unittest.TestCase):
    def test_missing_or_wrong_hosted_scope_is_refused(self):
        with self.assertRaisesRegex(ValueError, 'hosted scope'): p.hosted({})

    def test_cleanup_keeps_original_failure_and_does_not_claim_ready(self):
        source = (HERE / 'restore_business_reconciliation_probe.py').read_text()
        self.assertIn("original_exit == 0 and result['processExit'] == 0", source)
        self.assertIn("auth.require_absent(kind, identifier, check.returncode, check.stderr)", source)
        self.assertIn("'candidateState': 'BLOCKED', 'dispatchRelease': False", source)
        self.assertIn("result['schemaCleanup'] = 'UNKNOWN' if cleanup_failed", source)
        self.assertNotIn('docker rm', source); self.assertNotIn('prune', source)

    def test_fixed_child_uses_real_owners_and_original_fixture_without_mutating_them(self):
        source = (p.ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RestoreBusinessReconciliationFixture.java').read_text()
        for exact in ('original.createDatabaseAndService()', 'original.resetSchema()', 'orders.createOrReplay(7L, request)',
                      'registrations.beginPaymentAttempt', '"freezePaymentIntent"', 'facts.recordVerified(paid)',
                      'registrations.handleWechatPayCallback(callback)', 'file.force(true)', 'gateway.init()',
                      'gateway.createGenericAppOrder', '"SDK_DISABLED_ONLY"'):
            self.assertIn(exact, source)
        self.assertNotIn('DROP DATABASE', source.replace('// Host owns exact source/target cleanup. Never call the old fixture\'s DROP lifecycle here.', ''))

    def finalization(self, original_exit=0, process_exit=0, missing=True):
        root = tempfile.TemporaryDirectory(); self.addCleanup(root.cleanup)
        directory = Path(root.name)
        resources = {'scope': 'a' * 32, 'sourceSha': 'b' * 40,
                     'source': {'cid': 'c' * 64}, 'target': {'cid': 'd' * 64}}
        manifest = directory / 'resources.json'; manifest.write_text(json.dumps(resources)); manifest.chmod(0o600)
        p.save(directory / 'reconciliation.json', {'sourceSha': resources['sourceSha'], 'scope': resources['scope'],
               'resourcesSha256': p.sha(manifest.read_bytes()), 'processExit': process_exit,
               'schemaCleanup': 'CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT', 'businessResult': 'FAIL' if process_exit else 'SCOPED'})
        def inspect(argv, **kwargs):
            kind, identity = argv[-3], argv[-1]
            return p.subprocess.CompletedProcess(argv, 1 if missing else 0, b'{}',
                    ('Error: No such ' + kind + ': ' + identity).encode() if missing else b'')
        with patch.object(p, 'hosted'), patch.dict(p.os.environ, {'GITHUB_SHA': 'b' * 40}), \
             patch.object(p.subprocess, 'run', side_effect=inspect):
            if original_exit or process_exit or not missing:
                with self.assertRaises(ValueError): p.finalize(manifest, directory, original_exit)
            else:
                p.finalize(manifest, directory, original_exit)
        return json.loads((directory / 'finalized.json').read_text())

    def test_outer_original_failure_cannot_become_green_after_cleanup(self):
        result = self.finalization(original_exit=7)
        self.assertEqual('FAIL', result['result']); self.assertEqual(7, result['originalExit'])

    def test_partial_resource_cleanup_does_not_become_success(self):
        result = self.finalization(missing=False)
        self.assertEqual('FAIL', result['result']); self.assertEqual('UNKNOWN', result['resourceCleanup'])

    def test_inner_failure_survives_successful_resource_cleanup(self):
        self.assertEqual('FAIL', self.finalization(process_exit=1)['result'])

    def test_successful_scoped_observation_still_never_authorizes_dispatch(self):
        result = self.finalization()
        self.assertEqual('SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED', result['result'])
        self.assertEqual('BLOCKED', result['candidateState']); self.assertIs(False, result['dispatchRelease'])


if __name__ == '__main__':
    unittest.main()
