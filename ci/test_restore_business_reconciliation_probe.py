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
                              payment_status=2 if c in paid else 1) for c in identities]
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


class CompositeIntentControls(unittest.TestCase):
    def test_canonical_key_and_allocator_roster_have_no_invented_intent_id(self):
        ddl = (p.ROOT / 'chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql').read_text()
        self.assertIn('PRIMARY KEY (mchid, out_trade_no)', ddl)
        self.assertNotRegex(ddl, r'(?m)^\s*id\s+')
        self.assertNotIn('AUTO_INCREMENT', ddl)
        self.assertIn('wx_pay_order_intent', p.TABLES)
        self.assertEqual({'cms_registration': 'id', 'wx_verified_payment_fact': 'id',
                          'ums_member_points_detail': 'id'}, p.HIGH_IDS)

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
        self.assertEqual(3, tool.mysql.call_count)
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
