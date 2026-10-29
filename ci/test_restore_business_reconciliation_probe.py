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
                          'attemptNo': None if number == 11 else 1,
                          'businessNo': 'T20260724PAY' if number == 11 else 'ORDER' + str(number),
                          'wxNo': 'T20260724PAY' if number == 11 else 'WX' + str(number),
                          'fingerprint': None if number == 11 else 'b' * 64, 'totalFen': 10000,
                          'providerId': 'TX' + str(number), 'mchid': 'mch-funds-it', 'appid': 'wx-funds-it', 'currency': 'CNY',
                          'origin': 'HISTORICAL_MASTER_ONLY:T20260724PAY:original-funds-fixture' if number == 11 else 'FROZEN_ORIGINAL'}
    def snapshot(lost):
        identities = list(commands.values())[:5 if lost else 4]
        paid = [commands['historical_paid']] + ([commands['lost_success']] if lost else [])
        registrations = [dict(id=c['bizId'], registration_no=c['businessNo'], wechat_payment_amount='100.00',
                              payable_amount='100.00', fee_platform_rate='0.1234', member_id=7,
                              owner_type=1 if c['case'] == 'historical_paid' else 2,
                              owner_id=99 if c['case'] == 'historical_paid' else 55,
                              ticket_id=9, purchase_kind=1,
                              registration_status=2 if c in paid else 1, payment_status=2 if c in paid else 1) for c in identities]
        frozen = [dict(mchid=c['mchid'], appid=c['appid'], biz_type='REGISTRATION', biz_id=c['bizId'],
                       attempt_no=c['attemptNo'], request_hash=c['fingerprint'], out_trade_no=c['wxNo'],
                       business_out_trade_no=c['businessNo'], trade_type='JSAPI', currency=c['currency'],
                       total_fen=c['totalFen'], closed_confirmed_at=None) for c in identities if c['attemptNo'] is not None]
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
        claims, details = [], []
        if lost:
            for i, (role, receiver, amount) in enumerate((('merchant', 1, '87.66'), ('platform', 0, '12.34'))):
                claims.append(dict(id=4 + i, commercial_context_type='ACTIVITY', commercial_context_id=55,
                    fulfillment_type='REGISTRATION', fulfillment_id=15, beneficiary_role=role.upper(), beneficiary_id=receiver,
                    service_unit='MERCHANT_PARTICIPATION', settlement_route='REGISTRATION_SPLIT',
                    source_contract_type='DIRECT_REGISTRATION', source_contract_id=15, status='ACTIVE'))
                details.append(dict(id=1 + i, claim_id=4 + i, batch_id=0, merchant_id=1, source_type='registration', source_id=15,
                    receiver_type=role, receiver_id=receiver, gross_amount=amount, commission_amount='0.00',
                    settlement_amount=amount, status=0, entry_kind='ORIGIN', event_key='ORIGIN',
                    idempotency_key='registration:15:' + role + ':' + str(receiver)))
        contents = {'cms_registration': registrations, 'wx_pay_order_intent': frozen, 'wx_verified_payment_fact': facts,
                    'cms_registration_payment_fact': receipts, 'ums_member': [dict(id=7, point=120 if lost else 115)],
                    'ums_member_points_detail': entries, 'ums_member_balance_detail': [], 'refund_application': [],
                    'cms_registration_scan': [], 'cms_activity': [dict(id=55, member_id=1, topic_id=None,
                        status=1, publish_status=1, del_flag=0)],
                    'oms_ticket': [dict(id=9, owner_type=2, owner_id=55, remaining_inventory=6 if lost else 7)],
                    'settlement_rule': [dict(id=8, merchant_id=0, biz_type='activity', platform_commission_rate='0.1234',
                        merchant_rate='0.8766', creator_rate='0.0000', inviter_rate='0.0000', status=1, del_flag=0)],
                    'settlement_claim': claims, 'settlement_detail': details}
        water = {t: {'max': 15 if lost else 14, 'next': 16 if lost else 15} for t in p.HIGH_IDS}
        water.update(settlement_claim={'max': 5 if lost else 0, 'next': 6 if lost else 4},
                     settlement_detail={'max': 2 if lost else 0, 'next': 3 if lost else 1}, settlement_rule={'max': 8, 'next': 9})
        return {'tables': {t: table(rows) for t, rows in contents.items()},
                'highWater': water}
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


class CompositeIntentControls(unittest.TestCase):
    def test_canonical_key_and_allocator_roster_have_no_invented_intent_id(self):
        ddl = (p.ROOT / 'chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql').read_text()
        self.assertIn('PRIMARY KEY (mchid, out_trade_no)', ddl)
        self.assertNotRegex(ddl, r'(?m)^\s*id\s+')
        self.assertNotIn('AUTO_INCREMENT', ddl)
        self.assertIn('wx_pay_order_intent', p.TABLES)
        self.assertEqual({'cms_registration': 'id', 'wx_verified_payment_fact': 'id',
                          'ums_member_points_detail': 'id', 'settlement_claim': 'id',
                          'settlement_detail': 'id', 'settlement_rule': 'id'}, p.HIGH_IDS)

    def test_real_composite_identity_survives_restore_and_lost_attempt_stays_blocked(self):
        values = scenario(); result = p.reconcile(*values)
        for command in values[4]['commands'].values(): self.assertNotIn('intentId', command)
        for snap in values[:3]:
            for row in p.dictionaries(snap, 'wx_pay_order_intent'): self.assertNotIn('id', row)
            self.assertNotIn('wx_pay_order_intent', snap['highWater'])
        for difference in result['differences']:
            command = values[4]['commands'][difference['case']]
            self.assertNotIn('intentId', difference)
            for key in ('mchid', 'wxNo', 'businessNo', 'bizId', 'fingerprint', 'attemptNo', 'totalFen'):
                self.assertEqual(command[key], difference[key])
        lost = values[4]['commands']['lost_success']
        risks = [r for r in result['highWaterRisks'] if r['table'] == 'wx_pay_order_intent']
        self.assertEqual(1, len(risks)); risk = risks[0]
        self.assertEqual((lost['mchid'], str(lost['bizId']), lost['businessNo'], 1, 0),
            tuple(risk[k] for k in ('mchid', 'biz_id', 'business_out_trade_no', 'preFaultAttemptMax', 'restoreAttemptMax')))
        self.assertEqual('POSSIBLE_ORIGINAL_ATTEMPT_REUSE_DO_NOT_ALLOCATE', risk['status'])
        self.assertTrue(any(r['table'] == 'cms_registration' for r in result['highWaterRisks']))
        self.assertEqual('BLOCKED', result['candidateState']); self.assertIs(False, result['dispatchRelease'])

    def test_each_frozen_component_must_match_even_when_backup_and_restore_share_the_bad_row(self):
        for field, replacement in [('mchid', 'other-merchant'), ('appid', 'other-app'), ('out_trade_no', 'OTHER_ALIAS'),
                ('business_out_trade_no', 'OTHER_ORDER'), ('biz_type', 'COMMERCE'), ('biz_id', 999),
                ('attempt_no', 2), ('request_hash', 'c' * 64), ('trade_type', 'APP'), ('currency', 'USD'),
                ('total_fen', 10001), ('closed_confirmed_at', '2026-10-25T00:00:00Z')]:
            values = scenario()
            for snap in values[:3]: replace(snap, 'wx_pay_order_intent', field, replacement)
            with self.subTest(field=field), self.assertRaises(ValueError): p.reconcile(*values)

    def test_capture_keeps_typed_intent_rows_but_only_queries_real_numeric_allocators(self):
        from unittest import mock
        source = scenario()[0]
        tool = mock.Mock(); tool.quoted.side_effect = lambda name: '`' + name + '`'
        tool.mysql.return_value = '[15,16]'
        with patch.object(p, 'table_rows', side_effect=lambda unused, args, name: source['tables'][name]):
            observed = p.capture(tool, object())
        self.assertEqual(source['tables']['wx_pay_order_intent'], observed['tables']['wx_pay_order_intent'])
        self.assertEqual(set(p.HIGH_IDS), set(observed['highWater']))
        self.assertEqual(6, tool.mysql.call_count)
        for call in tool.mysql.call_args_list:
            self.assertIn('MAX(`id`)', call.args[1]); self.assertNotIn('wx_pay_order_intent', call.args[1])

    def test_journal_requires_original_attempt_and_rejects_fake_id_or_historical_backfill(self):
        writer = JournalControls()
        for field, replacement in [('attemptNo', None), ('attemptNo', True), ('attemptNo', 0),
                ('attemptNo', -1), ('attemptNo', 2147483648), ('intentId', 12)]:
            with self.subTest(field=field, value=replacement), tempfile.TemporaryDirectory() as temp:
                command = dict(scenario()[4]['commands']['late_success'], **{field: replacement})
                path = Path(temp) / 'journal'; writer.write(path, [(command, 'SUBMIT')])
                with self.assertRaises(ValueError): p.journal(path, 'a' * 32)
        for field, replacement in [('attemptNo', 1), ('wxNo', 'NEW_ALIAS')]:
            with self.subTest(historical=field), tempfile.TemporaryDirectory() as temp:
                command = dict(scenario()[4]['commands']['historical_paid'], **{field: replacement})
                path = Path(temp) / 'journal'; writer.write(path, [(command, 'SUBMIT')])
                with self.assertRaisesRegex(ValueError, 'legacy'): p.journal(path, 'a' * 32)


class ActivityPaymentSourceControls(unittest.TestCase):
    def test_registration_detail_source_and_activity_claim_context_are_distinct_original_identities(self):
        values = scenario()
        p.activity_receivables(values[1])
        details = p.dictionaries(values[1], 'settlement_detail')
        claims = p.dictionaries(values[1], 'settlement_claim')
        self.assertEqual({'registration'}, {r['source_type'] for r in details})
        self.assertEqual({'ACTIVITY'}, {r['commercial_context_type'] for r in claims})
        for field, wrong in [('source_type', 'activity'), ('idempotency_key', 'activity:15:merchant:1')]:
            rejected = scenario(); replace(rejected[1], 'settlement_detail', field, wrong)
            with self.subTest(field=field), self.assertRaises(ValueError): p.reconcile(*rejected)

    def test_paid_explore_missing_ticket_or_nonordinary_activity_cannot_masquerade_as_this_cash_fixture(self):
        for name, field, wrong in [('cms_registration', 'purchase_kind', 3),
                ('cms_registration', 'ticket_id', 0), ('cms_activity', 'topic_id', 99),
                ('cms_activity', 'status', 0), ('cms_activity', 'publish_status', 0), ('cms_activity', 'del_flag', 1)]:
            values = scenario()
            entry = values[1]['tables'][name]
            # The paid ACTIVITY is the last registration, not the historical TOPIC in row zero.
            index = -1 if name == 'cms_registration' else 0
            entry['rows'][index][[c[0] for c in entry['columns']].index(field)] = str(wrong)
            with self.subTest(field=field), self.assertRaises(ValueError): p.reconcile(*values)

    def test_original_activity_cash_and_claims_commit_only_in_fault_and_stay_absent_after_restore(self):
        backup, fault, restored, before, after = scenario()
        result = p.reconcile(backup, fault, restored, before, after)
        for snap in (backup, restored):
            self.assertEqual([], p.dictionaries(snap, 'settlement_claim'))
            self.assertEqual([], p.dictionaries(snap, 'settlement_detail'))
        self.assertEqual(2, len(p.dictionaries(fault, 'settlement_claim')))
        self.assertEqual(2, len(p.dictionaries(fault, 'settlement_detail')))
        self.assertTrue(any(r['table'] == 'settlement_claim' for r in result['highWaterRisks']))
        self.assertTrue(any(r['table'] == 'settlement_detail' for r in result['highWaterRisks']))
        self.assertEqual('BLOCKED', result['candidateState']); self.assertIs(False, result['dispatchRelease'])
        self.assertEqual('UNKNOWN_MISSING_WHOLE_ORIGINAL_MANUAL_BLOCK',
            next(r['status'] for r in result['differences'] if r['case'] == 'lost_success'))

    def test_wrong_activity_cash_claim_actor_or_duplicate_side_effect_is_rejected(self):
        for table_name, field, value in [('settlement_detail', 'settlement_amount', '99.00'),
                ('settlement_detail', 'receiver_id', 999), ('settlement_detail', 'claim_id', 999),
                ('settlement_detail', 'status', 1), ('settlement_claim', 'fulfillment_id', 999),
                ('settlement_claim', 'commercial_context_id', 99), ('settlement_claim', 'beneficiary_role', 'CREATOR')]:
            values = scenario(); replace(values[1], table_name, field, value)
            with self.subTest(field=field, table=table_name), self.assertRaises(ValueError): p.reconcile(*values)
        values = scenario(); values[1]['tables']['settlement_detail']['rows'].append(
            list(values[1]['tables']['settlement_detail']['rows'][0]))
        with self.assertRaises(ValueError): p.reconcile(*values)


class ResourceTransportControls(unittest.TestCase):
    def resources(self, relay=False):
        scope = 'a' * 32; name = 'cy-restore-' + scope
        network = {'Id': 'e' * 64, 'Name': name, 'Internal': True,
                   'Labels': {'chengyin.restore-business': scope}}
        resources = {'scope': scope, 'schema': 'cy_restore_' + scope + '_funds_core_it'}
        rows = []
        for i, side in enumerate(('source', 'target')):
            port = 33080 + i; cid = ('c' if i == 0 else 'd') * 64
            resources[side] = {'cid': cid, 'port': port,
                'uuid': ('1' if i == 0 else '2') * 8 + '-1111-1111-1111-111111111111'}
            if relay:
                resources[side].update(transport='OWNED_MYSQL_LOOPBACK_RELAY', relay={'controlledDescriptor': side})
            rows.append({'Id': cid, 'Name': '/' + name + '-' + side,
                'Config': {'Image': p.MYSQL_IMAGE, 'Labels': {'chengyin.restore-business': scope}},
                'State': {'Running': True, 'Paused': False, 'OOMKilled': False},
                'HostConfig': {'Memory': 1073741824, 'NanoCpus': 1000000000},
                'NetworkSettings': {'Networks': {name: {'NetworkID': network['Id']}},
                    'Ports': {'3306/tcp': None if relay else [{'HostIp': '127.0.0.1', 'HostPort': str(port)}]}}})
        return resources, rows, network

    def test_original_published_binding_contract_is_preserved_without_transport_descriptor(self):
        resources, rows, network = self.resources()
        with patch.object(p, 'module') as loader:
            p.validate_resources(resources, rows, network)
            loader.assert_not_called()
            for value in (None, [], [{'HostIp': '0.0.0.0', 'HostPort': '33080'}],
                          [{'HostIp': '127.0.0.1', 'HostPort': '33081'}]):
                changed = copy.deepcopy(rows); changed[0]['NetworkSettings']['Ports']['3306/tcp'] = value
                with self.assertRaises(ValueError): p.validate_resources(resources, changed, network)

    def test_relay_branch_requires_host_proof_for_both_original_endpoints_and_never_bypasses_other_guards(self):
        resources, rows, network = self.resources(relay=True)
        with patch.object(p, 'module') as loader:
            proof = loader.return_value.validate_relay_descriptor
            p.validate_resources(resources, rows, network)
            self.assertEqual(2, proof.call_count)
            for i, side in enumerate(('source', 'target')):
                self.assertEqual((resources['scope'], side, resources[side], network, rows[i]), proof.call_args_list[i].args)
                self.assertEqual(('restore_owned_mysql_host', 'ci/restore_business_reconciliation_host.py'),
                                 loader.call_args_list[i].args)
            proof.side_effect = ValueError('unproved original listener')
            with self.assertRaisesRegex(ValueError, 'unproved original listener'):
                p.validate_resources(resources, rows, network)
            proof.side_effect = None
            for keys, value in [(['source', 'transport'], 'arbitrary'), (['target', 'cid'], resources['source']['cid']),
                                (['target', 'port'], resources['source']['port'])]:
                changed = copy.deepcopy(resources); changed[keys[0]][keys[1]] = value
                with self.assertRaises(ValueError): p.validate_resources(changed, rows, network)
            for keys, value in [(['HostConfig', 'Memory'], 1), (['Config', 'Image'], 'foreign'),
                               (['NetworkSettings', 'Ports', '33060/tcp'], [{'HostIp': '0.0.0.0', 'HostPort': '33060'}])]:
                changed = copy.deepcopy(rows); target = changed[0]
                for key in keys[:-1]: target = target[key]
                target[keys[-1]] = value
                with self.assertRaises(ValueError): p.validate_resources(resources, changed, network)


class ChildExitControls(unittest.TestCase):
    def identity(self):
        return dict(sourceSha='a' * 40, scope='b' * 32, boot='c' * 32, cohort='payment-gap', childClass=p.CHILD)

    def typed(self):
        identity = self.identity()
        return dict(protocol='RESTORE_CHILD_FAILURE_V1', stage='SOURCE_SCHEMA',
            **{key: identity[key] for key in ('sourceSha', 'scope', 'boot', 'cohort')},
            causeTypes=['org.springframework.jdbc.BadSqlGrammarException', 'java.sql.SQLSyntaxErrorException'],
            sqlState='42S22', sqlErrorCode=1054, origin={'class': p.CHILD, 'line': 99})

    def capture(self, raw):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'child.stderr'; path.write_bytes(raw); path.chmod(0o600)
            return p.child_failure(path, self.identity())

    def test_original_typed_child_failure_is_bound_and_raw_secret_is_only_hashed(self):
        raw = b'PRIVATE_SECRET_CANARY sql/defaults/path\nRESTORE_CHILD_FAILURE_V1:' + json.dumps(self.typed()).encode() + b'\n'
        evidence = self.capture(raw)
        self.assertEqual('TYPED_FAILURE', evidence['diagnosticState'])
        self.assertEqual(self.typed(), evidence['javaFailure'])
        self.assertEqual((len(raw), p.sha(raw)), (evidence['stderrBytes'], evidence['stderrSha256']))
        self.assertNotIn('PRIVATE', json.dumps(evidence)); self.assertNotIn('sql/defaults', json.dumps(evidence))

    def test_callback_diagnostic_is_a_fixed_optional_category_never_a_payload_or_new_acceptance(self):
        row = dict(self.typed(), stage='AFTER_BACKUP', callback={'result': 'FAIL', 'reason': 'MUTATION_EXCEPTION'})
        raw = b'RESTORE_CHILD_FAILURE_V1:' + json.dumps(row).encode() + b'\n'
        observed = self.capture(raw)
        self.assertEqual('TYPED_FAILURE', observed['diagnosticState'])
        self.assertEqual(row['callback'], observed['javaFailure']['callback'])
        for callback in ({'result': 'PRIVATE_SECRET_CANARY', 'reason': 'UNKNOWN'},
                         {'result': 'FAIL', 'reason': 'PRIVATE_SECRET_CANARY'},
                         {'result': 'FAIL', 'reason': 'UNKNOWN', 'payload': 'PRIVATE_SECRET_CANARY'}):
            bad = self.capture(b'RESTORE_CHILD_FAILURE_V1:' + json.dumps(dict(self.typed(), stage='AFTER_BACKUP', callback=callback)).encode() + b'\n')
            self.assertEqual('UNREADABLE_OR_UNTRUSTED', bad['diagnosticState'])
            self.assertNotIn('PRIVATE', json.dumps(bad)); self.assertNotIn('javaFailure', bad)

    def test_wrong_boot_scope_unknown_type_payload_or_duplicate_marker_is_not_public_evidence(self):
        for key, value in [('boot', 'f' * 32), ('scope', 'f' * 32), ('sourceSha', 'f' * 40),
                           ('cohort', 'refund-gap'), ('causeTypes', ['PRIVATE_SECRET_CANARY']),
                           ('stage', 'PRIVATE_SECRET_CANARY'), ('sqlState', 'SECRT'), ('sqlErrorCode', True),
                           ('origin', {'class': 'PRIVATE_SECRET_CANARY', 'line': 99}),
                           ('message', 'PRIVATE_SECRET_CANARY')]:
            row = dict(self.typed(), **{key: value})
            result = self.capture(b'RESTORE_CHILD_FAILURE_V1:' + json.dumps(row).encode() + b'\n')
            self.assertEqual('UNREADABLE_OR_UNTRUSTED', result['diagnosticState'])
            self.assertNotIn('javaFailure', result); self.assertNotIn('PRIVATE', json.dumps(result))
        marker = b'RESTORE_CHILD_FAILURE_V1:' + json.dumps(self.typed()).encode() + b'\n'
        self.assertEqual('UNREADABLE_OR_UNTRUSTED', self.capture(marker + marker)['diagnosticState'])
        self.assertEqual('EXCEEDS_FIXED_BUDGET', self.capture(b'x' * 1048577)['diagnosticState'])

    def test_voice_observation_is_fixed_enum_only_and_bound_to_original_child_phase(self):
        identity = dict(self.identity(), cohort='voice-complete', phase='SOURCE',
                        childClass='com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixture')
        voice = dict(operation='SOURCE_KNOWN', expected='READY', intentState='PENDING',
                     profileState='PENDING', selection='SELECTED', providerStep='QUERY_JOURNAL')
        with patch.object(self, 'identity', return_value=identity):
            row = dict(self.typed(), stage='SOURCE_TIMELINE', voice=voice, causeTypes=['java.lang.IllegalStateException'],
                       sqlState=None, sqlErrorCode=None, origin={'class':identity['childClass'], 'line':99})
            def capture(value):
                return self.capture(b'RESTORE_CHILD_FAILURE_V1:' + json.dumps(value).encode() + b'\n')
            observed = capture(row)
            self.assertEqual('TYPED_FAILURE', observed['diagnosticState'])
            self.assertEqual(voice, observed['javaFailure']['voice'])
            for key in voice:
                bad = dict(row, voice=dict(voice, **{key:'PRIVATE_SECRET_CANARY'}))
                result = capture(bad)
                self.assertEqual('UNREADABLE_OR_UNTRUSTED', result['diagnosticState']); self.assertNotIn('PRIVATE', json.dumps(result))
            for bad in (dict(row, voice=dict(voice, payload='PRIVATE_SECRET_CANARY')),
                        dict(row, callback={'result':'FAIL','reason':'UNKNOWN'}),
                        dict(row, stage='SOURCE_SCHEMA')):
                self.assertEqual('UNREADABLE_OR_UNTRUSTED', capture(bad)['diagnosticState'])
            with patch.object(self, 'identity', return_value=dict(identity, childClass=p.CHILD)):
                self.assertEqual('UNREADABLE_OR_UNTRUSTED', capture(row)['diagnosticState'])
            with patch.object(self, 'identity', return_value=dict(identity, phase='NORMAL_READBACK')):
                self.assertEqual('UNREADABLE_OR_UNTRUSTED', capture(row)['diagnosticState'])

    def test_unknown_raw_exit_is_unknown_and_only_exact_fixed_launcher_header_is_classified(self):
        self.assertEqual('NO_TYPED_FAILURE', self.capture(b'PRIVATE_SECRET_CANARY')['diagnosticState'])
        raw = ('Error: Could not find or load main class ' + p.CHILD + '\nPRIVATE_SECRET_CANARY').encode()
        result = self.capture(raw)
        self.assertEqual('REVIEWED_LAUNCHER_FAILURE', result['diagnosticState'])
        self.assertEqual('JVM_MAIN_CLASS_NOT_FOUND', result['launcherCategory'])
        self.assertNotIn('PRIVATE', json.dumps(result))
        self.assertEqual('NO_TYPED_FAILURE', self.capture(raw.replace(p.CHILD.encode(), b'foreign.Main'))['diagnosticState'])

    def test_stdout_eof_preserves_fixed_boundary_error_type_and_original_exit(self):
        from unittest import mock
        child = mock.Mock(); child.stdout.fileno.return_value = 10; child.wait.return_value = 17
        with patch.object(p.time, 'monotonic', return_value=0), patch.object(p.select, 'select', return_value=([child.stdout], [], [])), \
             patch.object(p.os, 'read', return_value=b''):
            with self.assertRaises(ValueError) as caught: p.wait_phase(child, 'BACKUP_READY', 10)
        self.assertIsInstance(caught.exception, p.ChildBoundaryFailure)
        self.assertEqual('CHILD_EOF_BEFORE_BOUNDARY', caught.exception.code)
        self.assertEqual('fixture child exited before fixed boundary', str(caught.exception))
        child.wait.assert_called_once_with(timeout=1)

    def test_source_boundary_failure_keeps_unconfirmed_schema_and_original_typed_child_exit(self):
        # Orchestration control with a controlled launcher; it neither starts Java nor proves MySQL behavior.
        from unittest import mock
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(); repo = root / 'repo'; private = root / 'private'; output = root / 'out'
            private.mkdir(mode=0o700); output.mkdir()
            classes = repo / 'chengyinhub-admin/target/test-classes'; classes.mkdir(parents=True)
            child_class = classes / (p.CHILD.replace('.', '/') + '.class'); child_class.parent.mkdir(parents=True)
            child_class.write_bytes(b'controlled-class-source-only')
            diagnostic = classes / 'com/chengyinhub/business/service/RestoreChildDiagnostics.class'
            diagnostic.write_bytes(b'controlled-diagnostic-source-only')
            jar = repo / 'chengyinhub-admin/target/chengyinhub-admin.jar'
            with zipfile.ZipFile(jar, 'w') as package:
                package.writestr('BOOT-INF/classes/git.properties', 'git.commit.id=' + 'b' * 40 + '\n')
            resources, rows, network = ResourceTransportControls().resources()
            resources.update(sourceSha='b' * 40, cohort='payment-gap', testClasspathFile=str(private / 'cp.txt'))
            p.save(private / 'resources.json', resources)
            (private / 'cp.txt').write_text(str(classes)); (private / 'cp.txt').chmod(0o600)
            for side in ('source', 'target'):
                path = private / (side + '.cnf')
                path.write_text('[client]\nhost=127.0.0.1\nport=%d\nuser=root\npassword=%s\nprotocol=tcp\n' %
                    (resources[side]['port'], 'x' * 24)); path.chmod(0o600)
                resources[side]['defaultsFile'] = str(path)
            (private / 'resources.json').write_text(json.dumps(resources))
            tool = mock.Mock(); tool.digest.side_effect = lambda path: p.sha(Path(path).read_bytes())
            auth = mock.Mock()
            auth.command.side_effect = lambda argv: json.dumps([network if 'network' in argv else
                next(row for row in rows if row['Id'] == argv[-1])]).encode()
            domains = mock.Mock(); domains.SPECS = {}
            domains.failure_context.return_value={'contractCode':'UNKNOWN_DOMAIN_FACT_CONTRACT'}
            def modules(name, path):
                if path.endswith('restore_business_domain_facts.py'): return domains
                if path.endswith('db-backup-restore.py'): return tool
                if path.endswith('authenticated_redis_recovery_probe.py'): return auth
                self.fail('unexpected module')
            def sql(argv, **kwargs):
                if kwargs['input'].startswith(b'SELECT @@server_uuid'):
                    side = 'source' if 'source.cnf' in argv[1] else 'target'
                    raw = (resources[side]['uuid'] + '\t8.0.45\tOFF').encode()
                else: raw = b'0'
                return p.subprocess.CompletedProcess(argv, 0, raw, b'')
            child = mock.Mock(pid=12345); child.stdout.fileno.return_value = 10
            child.wait.return_value = 17; child.poll.return_value = 17
            def launch(argv, **kwargs):
                row = dict(self.typed(), sourceSha=resources['sourceSha'], scope=resources['scope'],
                           boot=kwargs['env']['CHENGYIN_RESTORE_BOOT'])
                kwargs['stderr'].write(b'PRIVATE_SECRET_CANARY\nRESTORE_CHILD_FAILURE_V1:' + json.dumps(row).encode() + b'\n')
                self.assertEqual(['java', '-Xmx512m', '-cp', str(classes), p.CHILD], argv)
                self.assertEqual('127.0.0.1', kwargs['env']['CHENGYIN_TEST_MYSQL_HOST'])
                return child
            env = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_JOB': 'isolated-restore-business-reconciliation', 'CHENGYIN_CI_RESTORE_BUSINESS': 'true',
                'GITHUB_SHA': 'b' * 40, 'GITHUB_RUN_ID': '101', 'GITHUB_RUN_ATTEMPT': '1'}
            with patch.object(p, 'ROOT', repo), patch.dict(p.os.environ, env, clear=True), \
                 patch.object(p, 'module', side_effect=modules), patch.object(p.subprocess, 'check_output', return_value='b' * 40), \
                 patch.object(p.subprocess, 'run', side_effect=sql), patch.object(p.subprocess, 'Popen', side_effect=launch), \
                 patch.object(p.signal, 'signal'), patch.object(p.signal, 'alarm'), patch.object(p.time, 'monotonic', return_value=0), \
                 patch.object(p.select, 'select', return_value=([child.stdout], [], [])), patch.object(p.os, 'read', return_value=b''):
                with self.assertRaises(ValueError): p.run(private / 'resources.json', output, private)
            result = json.loads((output / 'reconciliation.json').read_text())
            self.assertEqual(('FAIL', 'BLOCKED', 1, 'UNKNOWN'), tuple(result[k] for k in
                ('businessResult', 'candidateState', 'processExit', 'schemaCleanup')))
            self.assertEqual(['source-create-unconfirmed'], result['cleanupFailures'])
            self.assertEqual('CHILD_EOF_BEFORE_BOUNDARY', result['primaryReasonCode'])
            self.assertEqual((12345, 17, 'TYPED_FAILURE'), tuple(result['childExits'][0][k]
                for k in ('pid', 'returnCode', 'diagnosticState')))
            self.assertEqual('SOURCE_SCHEMA', result['childExits'][0]['javaFailure']['stage'])
            self.assertEqual(p.sha(child_class.read_bytes()), result['childExits'][0]['testChildSha256'])
            self.assertFalse(result['dispatchRelease']); self.assertNotIn('PRIVATE', json.dumps(result))
            tool.backup.assert_not_called(); tool.restore.assert_not_called(); child.kill.assert_not_called()


class HostPostSourceControls(unittest.TestCase):
    """Original real canonical/domain predicates with transport doubles; every orchestration result is FAIL."""
    def test_database_options_are_read_from_original_source_and_not_target_defaults(self):
        from unittest import mock
        tool=mock.Mock();tool.quoted.side_effect=lambda name:'`'+name+'`'
        source=object()
        for collation in ('utf8mb4_general_ci','utf8mb4_0900_ai_ci','utf8mb4_bin'):
            tool.mysql.return_value=json.dumps(['utf8mb4',collation])
            self.assertEqual(p.database_create_sql(tool,source,'owned_fixture'),
                             'CREATE DATABASE `owned_fixture` CHARACTER SET utf8mb4 COLLATE '+collation)
            self.assertIs(tool.mysql.call_args.args[0],source)
        for value in ([],['utf8mb4'],['latin1','latin1_swedish_ci'],['utf8mb4','utf8mb4_bin; PRIVATE_SQL'],['utf8mb4',None]):
            tool.mysql.return_value=json.dumps(value)
            with self.assertRaisesRegex(ValueError,'source fixture database options'):p.database_create_sql(tool,source,'owned_fixture')

    def test_failure_projection_only_maps_exact_literal_from_trusted_source(self):
        from unittest import mock
        tool=mock.Mock();tool.mysql.return_value='[]'
        try:p.database_create_sql(tool,object(),'PRIVATE_SCHEMA')
        except ValueError as failure:
            result=p.host_failure(failure,{'stage':'TARGET_SCHEMA_CREATE','step':'DATABASE_OPTIONS','side':'source'})
        self.assertEqual(result['reasonCode'],'DATABASE_OPTIONS_UNAVAILABLE')
        self.assertTrue(any(row['function']=='database_create_sql' for row in result['locations']))
        self.assertNotIn('PRIVATE_SCHEMA',json.dumps(result))
        for message in ('source fixture database options unavailable','source fixture database options unavailable PRIVATE_SQL',
                        'password=PRIVATE_TOKEN SELECT PRIVATE_BODY'):
            try:raise ValueError(message)
            except ValueError as failure:result=p.host_failure(failure,{'stage':'PRIVATE_PATH','step':[],'side':'PRIVATE_ACCOUNT','tableIndex':999})
            self.assertEqual(result['reasonCode'],'UNKNOWN_PRIVATE_FAILURE')
            self.assertEqual((result['stage'],result['step']),('UNKNOWN','UNKNOWN'))
            for marker in ('PRIVATE_PATH','PRIVATE_ACCOUNT','PRIVATE_TOKEN','PRIVATE_SQL','PRIVATE_BODY'):
                self.assertNotIn(marker,json.dumps(result))
            self.assertNotIn('tableIndex',result)

    def test_capture_records_only_registered_index_and_keeps_typed_row_failure(self):
        from unittest import mock
        tool=mock.Mock();tool.mysql.return_value='';progress={'stage':'RESTORED_CAPTURE','step':'TABLE_ROWS','side':'target'}
        domains=mock.Mock();domains.ALL_TABLES=set()
        with patch.object(p,'module',return_value=domains):
            try:p.capture(tool,object(),tables=('cms_registration',),high_ids={},progress=progress)
            except ValueError as failure:result=p.host_failure(failure,progress)
        self.assertEqual(result['reasonCode'],'TYPED_TABLE_METADATA_MISSING');self.assertEqual(result['tableIndex'],0)
        self.assertEqual(result['step'],'TABLE_ROWS');self.assertNotIn('cms_registration',json.dumps(result))

    def diagnostic_payload(self):
        return {'stage':'CANONICAL_RESTORE','step':'CANONICAL_CALL','side':'target',
                'reasonCode':'CANONICAL_DATABASE_DEFINITION_MISMATCH',
                'locations':[{'source':'canonical','function':'restore','line':1}]}

    def test_outer_json_projection_is_finite_and_rejects_extra_private_or_untrusted_locations(self):
        value=self.diagnostic_payload();self.assertEqual(p.safe_host_failure(value),value)
        changed=[]
        extra=copy.deepcopy(value);extra['sql']='PRIVATE_SQL';changed.append(extra)
        for key,invalid in (('stage','PRIVATE_STAGE'),('step',[]),('reasonCode','PRIVATE_REASON'),('side','PRIVATE_ACCOUNT'),('tableIndex',256)):
            row=copy.deepcopy(value);row[key]=invalid;changed.append(row)
        for location in ({'source':'canonical','function':'PRIVATE_FUNCTION','line':1},
                         {'source':'PRIVATE_PATH','function':'restore','line':1},
                         {'source':'canonical','function':'restore','line':True},
                         {'source':'canonical','function':'restore','line':100001}):
            row=copy.deepcopy(value);row['locations']=[location];changed.append(row)
        row=copy.deepcopy(value);row['locations']*=9;changed.append(row)
        for row in changed:self.assertIsNone(p.safe_host_failure(row))

    def test_outer_actual_consumer_preserves_bound_host_stage_and_never_copies_untrusted_receipt(self):
        spec=importlib.util.spec_from_file_location('post_source_outer_host',HERE/'restore_business_reconciliation_host.py')
        host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
        for alteration in ('none','probe-hash','extra-private','foreign-source'):
            with self.subTest(alteration=alteration),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);private=root/'private';output=root/'out';private.mkdir(mode=0o700);output.mkdir()
                worker=host.Host(root,private,output,'a'*32,'withdrawal-complete')
                host.write_private(worker.resources,'{}')
                row={'sourceSha':'b'*40,'scope':'a'*32,'cohort':'withdrawal-complete','runId':'101','runAttempt':'1',
                     'resourcesSha256':p.sha(worker.resources.read_bytes()),'probeSourceSha256':p.sha(Path(p.__file__).read_bytes()),
                     'businessResult':'FAIL','processExit':1,'primaryFailureType':'ValueError',
                     'primaryReasonCode':'CANONICAL_DATABASE_DEFINITION_MISMATCH','hostFailure':self.diagnostic_payload(),
                     'primaryReason':'PRIVATE_TOKEN'}
                if alteration=='probe-hash':row['probeSourceSha256']='f'*64
                if alteration=='extra-private':row['hostFailure']['sql']='PRIVATE_SQL'
                if alteration=='foreign-source':row['sourceSha']='f'*40
                host.write_private(output/'reconciliation.json',json.dumps(row))
                with patch.dict(p.os.environ,{'GITHUB_SHA':'b'*40,'GITHUB_RUN_ID':'101','GITHUB_RUN_ATTEMPT':'1'},clear=True):
                    worker.remember_probe_exit(1)
                self.assertEqual((worker.first_failure['operation'],worker.first_failure['returnCode']),('PROBE_RUN',1))
                if alteration=='none':
                    self.assertEqual(worker.first_failure['hostFailure'],self.diagnostic_payload())
                    self.assertEqual(worker.first_failure['primaryReasonCode'],'CANONICAL_DATABASE_DEFINITION_MISMATCH')
                else:self.assertNotIn('hostFailure',worker.first_failure)
                self.assertNotIn('PRIVATE_TOKEN',json.dumps(worker.first_failure));self.assertNotIn('PRIVATE_SQL',json.dumps(worker.first_failure))

    def post_source_failure(self,canonical_failure):
        from unittest import mock
        import zipfile
        tool=p.module('post_source_canonical_control','scripts/db-backup-restore.py')
        domains=p.module('post_source_domain_control','ci/restore_business_domain_facts.py')
        cohort='withdrawal-complete';child_name=domains.SPECS[cohort]['child']
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();repo=root/'repo';private=root/'private';output=root/'out'
            private.mkdir(mode=0o700);output.mkdir()
            classes=repo/'chengyinhub-admin/target/test-classes';classes.mkdir(parents=True)
            child_class=classes/(child_name.replace('.','/')+'.class');child_class.parent.mkdir(parents=True)
            child_class.write_bytes(b'SOURCE_CONTROL_NOT_JAVA')
            diagnostic=classes/'com/chengyinhub/business/service/RestoreChildDiagnostics.class';diagnostic.parent.mkdir(parents=True,exist_ok=True)
            diagnostic.write_bytes(b'SOURCE_CONTROL_DIAGNOSTIC')
            jar=repo/'chengyinhub-admin/target/chengyinhub-admin.jar'
            with zipfile.ZipFile(jar,'w') as package:package.writestr('BOOT-INF/classes/git.properties','git.commit.id='+'b'*40+'\n')
            resources,objects,network=ResourceTransportControls().resources()
            resources.update(sourceSha='b'*40,cohort=cohort,testClasspathFile=str(private/'cp.txt'))
            (private/'cp.txt').write_text(str(classes));(private/'cp.txt').chmod(0o600)
            for side in ('source','target'):
                path=private/(side+'.cnf');path.write_text('[client]\nhost=127.0.0.1\nport=%d\nuser=root\npassword=%s\nprotocol=tcp\n' %
                    (resources[side]['port'],'x'*24));path.chmod(0o600);resources[side]['defaultsFile']=str(path)
            p.save(private/'resources.json',resources)
            canonical_snapshot={'tables':{'t':{'rows':1,'checksum':'1'}},'views':[],
                'objects':{'ROUTINES':0,'TRIGGERS':0,'EVENTS':0},
                'definitions':{'DATABASE:'+resources['schema']:'a'*64,'TABLE:t':'b'*64}}
            empty={'tables':{},'views':[],'objects':{'ROUTINES':0,'TRIGGERS':0,'EVENTS':0},
                   'definitions':{'DATABASE:'+resources['schema']:('c' if canonical_failure else 'a')*64}}
            def backup(args):
                args.output.write_bytes(b'CONTROL_SQL_NOT_EXECUTED');args.output.chmod(0o600)
                p.save(Path(str(args.output)+'.json'),{'formatVersion':3,'definitionAlgorithm':'mysql8-column-charset-v1',
                    'serverUuid':resources['source']['uuid'],'mysqlVersion':'8.0.45','database':resources['schema'],
                    'sha256':tool.digest(args.output),'snapshot':canonical_snapshot})
            auth=mock.Mock();auth.command.side_effect=lambda argv:json.dumps([network if 'network' in argv else
                next(row for row in objects if row['Id']==argv[-1])]).encode()
            def modules(name,path):
                return {'ci/restore_business_domain_facts.py':domains,'scripts/db-backup-restore.py':tool,
                        'ci/authenticated_redis_recovery_probe.py':auth}[path]
            statements=[];imports=[]
            def transport(argv,**kwargs):
                if 'stdin' in kwargs:
                    imports.append('canonical-original-import');return p.subprocess.CompletedProcess(argv,0,b'',b'')
                sql=kwargs['input'];statements.append(sql)
                if sql.startswith(b'SELECT @@server_uuid'):
                    side='source' if 'source.cnf' in argv[1] else 'target'
                    raw=(resources[side]['uuid']+'\t8.0.45\tOFF').encode() if b'VERSION()' in sql else resources[side]['uuid'].encode()
                else:raw=b'0'
                return p.subprocess.CompletedProcess(argv,0,raw,b'')
            child=mock.Mock(pid=12345);child.wait.return_value=0;child.poll.return_value=0
            fake_rows=[{'tables':{},'highWater':{},'control':'backup'},{'tables':{},'highWater':{},'control':'fault'},
                       {'tables':{},'highWater':{},'control':'restored'}]
            canonical_reads=[]
            def canonical_state(args):
                canonical_reads.append(args.server_uuid)
                return empty if len(canonical_reads)==1 else canonical_snapshot
            def mysql(args,sql,**kwargs):
                return json.dumps(['utf8mb4','utf8mb4_general_ci']) if 'DEFAULT_CHARACTER_SET_NAME' in sql else 'OFF'
            env={'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted','RUNNER_OS':'Linux',
                'GITHUB_REPOSITORY':'liboyang42-cpu/hengyin','GITHUB_EVENT_NAME':'workflow_dispatch',
                'GITHUB_JOB':'isolated-restore-business-reconciliation','CHENGYIN_CI_RESTORE_BUSINESS':'true',
                'GITHUB_SHA':'b'*40,'GITHUB_RUN_ID':'101','GITHUB_RUN_ATTEMPT':'1'}
            with patch.object(p,'ROOT',repo),patch.dict(p.os.environ,env,clear=True),patch.object(p,'module',side_effect=modules),\
                 patch.object(p.subprocess,'check_output',return_value='b'*40),patch.object(p.subprocess,'run',side_effect=transport),\
                 patch.object(p.subprocess,'Popen',return_value=child) as launch,patch.object(p,'wait_phase'),\
                 patch.object(p,'capture',side_effect=fake_rows),patch.object(domains,'journal',return_value={'records':[]}),\
                 patch.object(tool,'backup',side_effect=backup),patch.object(tool,'mysql',side_effect=mysql),\
                 patch.object(tool,'identify',return_value=[resources['target']['uuid'],'8.0.45',resources['schema']]),\
                 patch.object(tool,'snapshot',side_effect=canonical_state),patch.object(p.signal,'signal'),patch.object(p.signal,'alarm'),\
                 patch.object(p.time,'monotonic',return_value=0):
                with self.assertRaisesRegex(ValueError,'isolated drill failed'):p.run(private/'resources.json',output,private)
            result=json.loads((output/'reconciliation.json').read_text())
            self.assertEqual(launch.call_count,1);self.assertEqual(child.wait.call_args.kwargs,{'timeout':20})
            self.assertEqual((result['businessResult'],result['processExit'],result['schemaCleanup']),
                             ('FAIL',1,'CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT'))
            self.assertEqual((result['childExits'][0]['phase'],result['childExits'][0]['returnCode'],result['childExits'][0]['diagnosticState']),
                             ('SOURCE',0,'NO_TYPED_FAILURE'))
            self.assertEqual((result['cohort'],result['runId'],result['runAttempt']),('withdrawal-complete','101','1'))
            self.assertEqual(result['jarSha256'],p.sha(jar.read_bytes()));self.assertFalse(result['dispatchRelease'])
            self.assertTrue(any(b'CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci' in sql for sql in statements))
            self.assertNotIn('CONTROL_SQL_NOT_EXECUTED',json.dumps(result));self.assertNotIn(str(private),json.dumps(result))
            return result,imports

    def test_source_zero_canonical_rejection_is_located_without_starting_restored_owner(self):
        result,imports=self.post_source_failure(True)
        self.assertEqual(result['hostFailure']['stage'],'CANONICAL_RESTORE')
        self.assertEqual(result['primaryReasonCode'],'CANONICAL_DATABASE_DEFINITION_MISMATCH')
        self.assertTrue(any(row['source']=='canonical' and row['function']=='restore' for row in result['hostFailure']['locations']))
        self.assertEqual(imports,[])

    def test_source_zero_real_domain_rejection_retains_original_predicate_and_cleanup(self):
        result,imports=self.post_source_failure(False)
        self.assertEqual(result['hostFailure']['stage'],'DOMAIN_EVALUATE')
        self.assertEqual(result['primaryReasonCode'],'UNKNOWN_PRIVATE_FAILURE')
        self.assertEqual(result['domainContractFailure'],{'contractCode':'RESTORED_TYPED_SNAPSHOT'})
        self.assertTrue(any(row['source']=='domain' and row['function']=='evaluate' for row in result['hostFailure']['locations']))
        self.assertEqual(imports,['canonical-original-import'])


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
        with patch.object(p, 'hosted'), patch.dict(p.os.environ, {'GITHUB_SHA': 'b' * 40, 'GITHUB_RUN_ID': '101', 'GITHUB_RUN_ATTEMPT': '1'}), \
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
        result = self.finalization(process_exit=1)
        self.assertEqual('FAIL', result['result'])
        self.assertEqual('RECORDED', result['finalization'])
        self.assertEqual('EXACT_ABSENCE', result['resourceCleanup'])
        self.assertEqual(('101', '1'), (result['runId'], result['runAttempt']))

    def test_successful_scoped_observation_still_never_authorizes_dispatch(self):
        result = self.finalization()
        self.assertEqual('SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED', result['result'])
        self.assertEqual('BLOCKED', result['candidateState']); self.assertIs(False, result['dispatchRelease'])


if __name__ == '__main__':
    unittest.main()
