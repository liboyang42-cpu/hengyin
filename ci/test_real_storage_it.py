#!/usr/bin/env python3
"""real_storage_it 的红绿负控：证明门禁在「嵌套类被丢、整类静默跳过、少跑/多跑/重复、门控值写错、
登记表过期、workflow 被改成自动触发或把输入拼进脚本」时真的变红。

纯 Python，不需要 Java / MySQL / Redis，可本机随时重放；真正连库执行只在手动 workflow 里发生，
本负控不冒充那层已验证。
"""
import copy
import importlib.util
import json
import pathlib
import re
import tempfile
import unittest
import os
import textwrap
from unittest import mock

_HERE = pathlib.Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("real_storage_it", _HERE / "real_storage_it.py")
rs = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(rs)

WORKFLOW = _HERE.parent / ".github" / "workflows" / "real-storage-it.yml"
SHA = "5752ee6fd302dab9e610f65cb8f3dc5b2b66201d"
C01 = "com.chengyinhub.business.service.impl.OWN_C01_51Test"
E04 = "com.chengyinhub.business.longrun.E04_13Test"
AI_REDIS = "com.chengyinhub.business.service.ai.impl.AiQuotaReservationRedisIT"
AI_BOTH = "com.chengyinhub.business.service.ai.AiQuotaRecoveryMySqlRedisIT"
E02_22 = "com.chengyinhub.business.longrun.E02_22Test"

SAMPLE_JAVA = '''package com.example;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Javadoc mentioning @Test and @Disabled must not count. */
class SampleTest {
    // @Test commented out
    /* @Test
       void ghost() {} */
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/x"; // @Test after a URL
    private static final String TEXT = "@Test inside a string";
    private static final char QUOTE = '"';

    @TestInstance(TestInstance.Lifecycle.PER_CLASS) static class Helper { }

    @Test void a() { }
    @org.junit.jupiter.api.Test void b() { }
    @Test
    void c() { }

    @Nested
    @EnabledIfEnvironmentVariable(named = "CHENGYIN_RUN_SAMPLE_MYSQL", matches = "true")
    class RealMysql {
        @Test void d() { }
    }
}
'''


def registry():
    return rs.load_registry()


def write_suite(directory, name, cases):
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<testsuite name="%s" tests="%d">' % (name, len(cases))]
    for classname, method, status in cases:
        if status == "passed":
            lines.append('  <testcase name="%s" classname="%s" time="0.01"/>' % (method, classname))
        else:
            lines.append('  <testcase name="%s" classname="%s" time="0.01"><%s message="x"/></testcase>'
                         % (method, classname, status))
    lines.append("</testsuite>")
    pathlib.Path(directory, "TEST-%s.xml" % name).write_text("\n".join(lines), encoding="utf-8")


def c01_cases(nested=True, status="passed"):
    cases = [(C01, "outer%d" % index, status) for index in range(4)]
    if nested:
        cases.append((C01 + "$RealMysqlRepeatableRead", "committedWinner", status))
    return cases


class RegistryContractTest(unittest.TestCase):
    def test_callback_inbox_registration_and_scratch_admission_are_exact(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.RegistrationCallbackIntentMySqlIT'
        self.assertEqual([fqcn], reg['batches']['architecture-day-payment-inbox']['classes'])
        self.assertEqual(14, reg['classes'][fqcn]['tests'])
        self.assertEqual(['mysql'], reg['classes'][fqcn]['storage'])
        self.assertEqual({'B_PAYMENT_INTENT_DDL_APPROVED': '1'}, reg['classes'][fqcn]['gates'])
        line = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                    if line.strip().startswith('B_PAYMENT_INTENT_DDL_APPROVED:'))
        self.assertEqual("B_PAYMENT_INTENT_DDL_APPROVED: ${{ (matrix.simple == 'WechatPaymentIntentMySqlIT' || matrix.simple == 'RegistrationCallbackIntentMySqlIT' || matrix.simple == 'CoopDepositSettlementAttemptMySqlIT' || matrix.simple == 'CashWithdrawFundEvidenceMySqlIT' || matrix.simple == 'RefundPayoutMybatisIntegrationTest' || matrix.simple == 'FundsProcessRecoveryMySqlIT') && '1' || '' }}", line)
        flag = "A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"
        line = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                    if line.strip().startswith(flag + ":"))
        self.assertEqual("A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED: ${{ (matrix.simple == 'RegistrationCallbackIntentMySqlIT' || matrix.simple == 'RegistrationTopicFenceMySqlIT' || matrix.simple == 'RegistrationFundsMybatisIntegrationTest' || matrix.simple == 'RefundPayoutMybatisIntegrationTest' || matrix.simple == 'RefundRetryAndRemainderMySqlIT' || matrix.simple == 'RestoreMoneyDispatchMySqlIT') && '1' || '' }}", line)
        self.assertEqual({flag: "1"}, rs.child_env({flag: "1"}, {}))

    def test_refund_lifecycle_batch_has_unique_original_suites_and_exact_gates(self):
        reg = registry()
        expected = {
            'com.chengyinhub.business.service.RefundPayoutMybatisIntegrationTest': 30,
            'com.chengyinhub.business.service.impl.RefundRetryAndRemainderMySqlIT': 10,
            'com.chengyinhub.business.service.CoopDepositRefundMybatisIntegrationTest': 36,
        }
        self.assertEqual(list(expected), reg['batches']['architecture-day-refund-lifecycle']['classes'])
        self.assertEqual(76, sum(expected.values()))
        self.assertEqual([], workflow_problems(WORKFLOW.read_text(), reg))
        for fqcn, count in expected.items():
            with self.subTest(fqcn=fqcn):
                entry = reg['classes'][fqcn]
                self.assertEqual(count, entry['tests'])
                self.assertEqual(['mysql'], entry['storage'])
                expected_gates = {'CHENGYIN_FUNDS_MYSQL_IT': '1'}
                if fqcn == 'com.chengyinhub.business.service.RefundPayoutMybatisIntegrationTest':
                    expected_gates['B_PAYMENT_INTENT_DDL_APPROVED'] = '1'
                self.assertEqual(expected_gates, entry['gates'])
                facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
                self.assertEqual([], problems)
                self.assertEqual(count, facts['declared_tests'])
                env = rs.class_env(reg, fqcn, mysql_port=33060, suffix='_unit_a1', timezone='UTC')
                self.assertEqual('1', env['CHENGYIN_FUNDS_MYSQL_IT'])
                self.assertEqual('127.0.0.1', env['CHENGYIN_TEST_MYSQL_HOST'])
                self.assertEqual('_unit_a1', env['CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX'])
        self.assertIn(next(n for n in expected if n.endswith('.RefundRetryAndRemainderMySqlIT')),
                      reg['batches']['port-prelaunch-69']['classes'])

    def test_refund_disabled_gate_or_stale_count_fails_existing_precheck(self):
        for fqcn in registry()['batches']['architecture-day-refund-lifecycle']['classes']:
            for mutation in ('gate', 'count'):
                with self.subTest(fqcn=fqcn, mutation=mutation):
                    reg = registry()
                    if mutation == 'gate': reg['classes'][fqcn]['gates'] = {}
                    else: reg['classes'][fqcn]['tests'] -= 1
                    _, problems = rs.precheck(reg, _HERE.parent, fqcn)
                    self.assertTrue(problems, 'missing gate or stale count must fail, never silently skip')

    def test_registered_literal_scratch_admissions_are_present_in_workflow(self):
        workflow = WORKFLOW.read_text().splitlines()
        for fqcn in registry()['classes']:
            source = _HERE.parent / 'chengyinhub-admin/src/test/java' / (fqcn.replace('.', '/') + '.java')
            for flag in re.findall(r'assertEquals\(\s*"1"\s*,\s*System.getenv\("([A-Z0-9_]+)"\)\)',
                                   rs.strip_java_comments(source.read_text())):
                with self.subTest(fqcn=fqcn, flag=flag):
                    line = next(line.strip() for line in workflow if line.strip().startswith(flag + ':'))
                    consumers = re.findall(r"matrix.simple == '([^']+)'", line)
                    self.assertIn(fqcn.rsplit('.', 1)[1], consumers)

    def test_topic_cash_fixture_admission_survives_child_environment_filter(self):
        flag = 'WP04_TOPIC_CASH_DDL_APPROVED'
        line = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                    if line.strip().startswith(flag + ':'))
        self.assertEqual("WP04_TOPIC_CASH_DDL_APPROVED: ${{ matrix.simple == 'Wp04TopicCashFenceMySqlIT' && '1' || '' }}", line)
        source = _HERE.parent / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp04TopicCashFenceMySqlIT.java'
        self.assertIn('assertEquals("1", System.getenv("' + flag + '"))', source.read_text())
        self.assertEqual({flag: '1'}, rs.child_env({flag: '1', 'CHENGYIN_FUNDS_MYSQL_IT': '1'}, {}))

    def test_registry_is_valid(self):
        self.assertEqual([], rs.validate_registry(registry()))

    def test_verified_payment_facts_have_exact_isolated_storage_admission(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.WechatVerifiedPaymentFactMySqlIT'
        self.assertEqual([fqcn], reg['batches']['architecture-day-verified-payment-facts']['classes'])
        self.assertEqual({'tests': 4, 'storage': ['mysql'], 'gates': {}}, reg['classes'][fqcn])
        flag = 'B_PAYMENT_FACT_DDL_APPROVED'
        line = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                    if line.strip().startswith(flag + ':'))
        self.assertEqual("B_PAYMENT_FACT_DDL_APPROVED: ${{ (matrix.simple == 'OmsOrderCommerceGuardMySqlIT' || matrix.simple == 'ClmCommercePaymentProofMySqlIT' || matrix.simple == 'WechatVerifiedPaymentFactMySqlIT' || matrix.simple == 'RegistrationTopicFenceMySqlIT' || matrix.simple == 'RefundNotificationMybatisIntegrationTest' || matrix.simple == 'RefundRetryAndRemainderMySqlIT' || matrix.simple == 'RegistrationFundsMybatisIntegrationTest' || matrix.simple == 'RefundPayoutMybatisIntegrationTest' || matrix.simple == 'CrossDomainFaultIsolationMySqlIT' || matrix.simple == 'RestoreMoneyDispatchMySqlIT') && '1' || '' }}", line)
        self.assertEqual({flag: '1'}, rs.child_env({flag: '1'}, {}))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(4, facts['declared_tests'])

    def test_original_refund_has_exact_isolated_storage_admission(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.Wp04OriginalRefundMySqlIT'
        self.assertEqual([fqcn], reg['batches']['architecture-day-original-refund']['classes'])
        self.assertEqual({'tests': 25, 'storage': ['mysql'], 'gates': {}}, reg['classes'][fqcn])
        flag = 'WP04_ORIGINAL_REFUND_DDL_APPROVED'
        line = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                    if line.strip().startswith(flag + ':'))
        self.assertEqual("WP04_ORIGINAL_REFUND_DDL_APPROVED: ${{ (matrix.simple == 'Wp04OriginalRefundMySqlIT' || matrix.simple == 'Wp04CoopOrderScopeMySqlIT' || matrix.simple == 'CoopOrderStationFulfillmentMySqlIT' || matrix.simple == 'Wp04OriginalProducerMySqlIT' || matrix.simple == 'CoopSettlementManualCaseReadMySqlIT') && '1' || '' }}", line)
        self.assertEqual({flag: '1'}, rs.child_env({flag: '1'}, {}))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(25, facts['declared_tests'])

    def test_original_producer_keeps_both_exact_scratch_admissions(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.Wp04OriginalProducerMySqlIT'
        self.assertEqual(3, reg['classes'][fqcn]['tests'])
        workflow = WORKFLOW.read_text()
        self.assertIn("WP04_ORIGINAL_PRODUCER_DDL_APPROVED: ${{ matrix.simple == 'Wp04OriginalProducerMySqlIT' && '1' || '' }}", workflow)
        source = (_HERE.parent / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp04OriginalProducerMySqlIT.java').read_text()
        self.assertIn('System.getenv("WP04_ORIGINAL_PRODUCER_DDL_APPROVED")', source)
        self.assertIn('db.isolatedOriginalSchema()', source)
        flags = {'WP04_ORIGINAL_REFUND_DDL_APPROVED': '1', 'WP04_ORIGINAL_PRODUCER_DDL_APPROVED': '1'}
        self.assertEqual(flags, rs.child_env(flags, {}))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(3, facts['declared_tests'])

    def test_manual_case_read_keeps_exact_scratch_admission(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.CoopSettlementManualCaseReadMySqlIT'
        self.assertEqual({'tests': 3, 'storage': ['mysql'], 'gates': {}, 'note': reg['classes'][fqcn]['note']}, reg['classes'][fqcn])
        self.assertIn("WP04_MANUAL_CASE_READ_DDL_APPROVED: ${{ matrix.simple == 'CoopSettlementManualCaseReadMySqlIT' && '1' || '' }}", WORKFLOW.read_text())
        source = (_HERE.parent / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CoopSettlementManualCaseReadMySqlIT.java').read_text()
        self.assertIn('schema.isolatedOriginalSchema()', source)
        self.assertIn('System.getenv("WP04_MANUAL_CASE_READ_DDL_APPROVED")', source)
        flags = {'WP04_ORIGINAL_REFUND_DDL_APPROVED': '1', 'WP04_MANUAL_CASE_READ_DDL_APPROVED': '1'}
        self.assertEqual(flags, rs.child_env(flags, {}))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(3, facts['declared_tests'])

    def test_payment_intent_requires_its_exact_hosted_ddl_approval(self):
        reg = registry()
        members = reg["batches"]["payment-intent-correctness"]["classes"]
        self.assertEqual(4, len(members))
        for fqcn, count in zip(members[:2], (4, 14)):
            with self.subTest(fqcn=fqcn):
                self.assertEqual(count, reg["classes"][fqcn]["tests"])
                report, problems = rs.precheck(reg, _HERE.parent, fqcn)
                self.assertEqual([], problems)
                self.assertEqual([{"named": "B_PAYMENT_INTENT_DDL_APPROVED", "matches": "1"}], report["source_gates"])
                for gates in ({}, {"B_PAYMENT_INTENT_DDL_APPROVED": "0"}):
                    rejected = copy.deepcopy(reg)
                    rejected["classes"][fqcn]["gates"] = gates
                    self.assertTrue(rs.precheck(rejected, _HERE.parent, fqcn)[1])
                env = rs.class_env(reg, fqcn, mysql_port=40001, suffix="_gh123456a1")
                self.assertEqual("1", env["B_PAYMENT_INTENT_DDL_APPROVED"])
                self.assertEqual("127.0.0.1", env["CHENGYIN_MYBATIS_IT_HOST"])
        for fqcn, count in zip(members[2:], (9, 3)):
            self.assertEqual(count, reg["classes"][fqcn]["tests"])
            self.assertEqual({"CHENGYIN_FUNDS_MYSQL_IT": "1"}, reg["classes"][fqcn]["gates"])
            self.assertEqual([], rs.precheck(reg, _HERE.parent, fqcn)[1])
            rejected = copy.deepcopy(reg)
            rejected["classes"][fqcn]["gates"] = {}
            self.assertTrue(rs.precheck(rejected, _HERE.parent, fqcn)[1])
        inherited = {"PATH": "/usr/bin", "B_PAYMENT_INTENT_DDL_APPROVED": "1"}
        self.assertNotIn("B_PAYMENT_INTENT_DDL_APPROVED", rs.child_env(inherited, {}))
        self.assertEqual([], rs.scan_java('// assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));')["gates"])

    def test_deposit_attempt_requires_its_existing_exact_payment_intent_admission(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.service.impl.CoopDepositSettlementAttemptMySqlIT'
        flag = 'B_PAYMENT_INTENT_DDL_APPROVED'
        self.assertEqual({'tests': 12, 'storage': ['mysql'], 'gates': {flag: '1'}},
                         {key: reg['classes'][fqcn][key] for key in ('tests', 'storage', 'gates')})
        for batch in ('architecture-day-deposit-attempt', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(12, facts['declared_tests'])
        self.assertEqual([{'named': flag, 'matches': '1'}], facts['source_gates'])
        for gates in ({}, {flag: '0'}, {flag: 'true'}):
            rejected = copy.deepcopy(reg)
            rejected['classes'][fqcn]['gates'] = gates
            with self.subTest(gates=gates):
                self.assertTrue(rs.precheck(rejected, _HERE.parent, fqcn)[1],
                                'missing/wrong explicit approval must fail before storage, never silently skip')
        inherited = {'PATH': '/usr/bin', flag: '1'}
        self.assertNotIn(flag, rs.child_env(inherited, {}))
        extra = rs.class_env(reg, fqcn, mysql_port=40001, suffix='_gh123456a1')
        self.assertEqual('1', extra[flag])
        self.assertEqual('1', rs.child_env({'PATH': '/usr/bin', flag: '0'}, extra)[flag])
        approval_line = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                             if line.strip().startswith(flag + ':'))
        self.assertIn("matrix.simple == 'CoopDepositSettlementAttemptMySqlIT'", approval_line)

    def test_order_scope_uses_original_isolated_fixture_without_broadening_admission(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.Wp04CoopOrderScopeMySqlIT'
        self.assertEqual({'tests': 11, 'storage': ['mysql'], 'gates': {}}, reg['classes'][fqcn])
        for batch in ('architecture-day-funds-closure', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(11, facts['declared_tests'])
        source = (_HERE.parent / facts['path']).read_text()
        self.assertIn('new Wp04OriginalRefundMySqlIT()', source)
        self.assertIn('storage.isolatedOriginalSchema()', source)
        self.assertIn('storage.dropOnlyThisSuccessfullyCreatedDatabase()', source)
        inherited = (_HERE.parent / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp04OriginalRefundMySqlIT.java').read_text()
        self.assertIn('assertEquals("1",System.getenv("WP04_ORIGINAL_REFUND_DDL_APPROVED"))', inherited)
        self.assertIn('assertEquals("github-hosted",System.getenv("RUNNER_ENVIRONMENT"))', inherited)

    def test_station_redemption_caller_uses_owned_hosted_database_and_keeps_g_boundary_explicit(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.RegistrationStationFulfillmentMySqlIT'
        self.assertEqual({'tests': 9, 'storage': ['mysql'], 'gates': {}, 'database_base': 'architecture_a'}, reg['classes'][fqcn])
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(9, facts['declared_tests'])
        for batch in ('architecture-day-registration-write', 'architecture-day-funds-closure', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        source = (_HERE.parent / facts['path']).read_text()
        self.assertIn('assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"))', source)
        self.assertIn('assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key))', source)
        self.assertIn('if (created)', source)
        self.assertIn('DROP DATABASE', source)
        self.assertIn('WHERE schema_name=?', source)
        self.assertIn('mock(ICoopSettlementClaimService.class)', source)

    def test_confirmed_station_proof_uses_canonical_source_and_original_isolated_gate(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.CoopOrderStationFulfillmentMySqlIT'
        self.assertEqual({'tests': 13, 'storage': ['mysql'], 'gates': {}}, reg['classes'][fqcn])
        for batch in ('architecture-day-funds-closure', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(13, facts['declared_tests'])
        source = (_HERE.parent / facts['path']).read_text()
        self.assertIn('new Wp04CoopOrderScopeMySqlIT()', source)
        self.assertIn('flow.canonicalScopeAndCash()', source)
        self.assertIn('flow.onlyOwnedCleanup()', source)
        self.assertIn('migration_20261025_coop_order_station_fulfillment.sql', source)
        inherited = (_HERE.parent / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp04OriginalRefundMySqlIT.java').read_text()
        self.assertIn('assertEquals("1",System.getenv("WP04_ORIGINAL_REFUND_DDL_APPROVED"))', inherited)

    def test_supply_station_invite_source_requires_real_mysql_and_owned_cleanup(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.service.impl.CoopSupplyStationMySqlIT'
        self.assertEqual({'tests': 7, 'storage': ['mysql'], 'gates': {}}, reg['classes'][fqcn])
        for batch in ('architecture-day-funds-closure', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(7, facts['declared_tests'])
        source = (_HERE.parent / facts['path']).read_text()
        self.assertIn('Explicit scratch connection required: ', source)
        self.assertIn('CHENGYIN_MYBATIS_IT_', source)
        self.assertIn('if(ownedSchema==null)return;', source)
        self.assertIn('DROP DATABASE', source)
        self.assertIn('WHERE schema_name=?', source)
        self.assertIn('new CoopInviteServiceImpl()', source)
        self.assertIn('new DataSourceTransactionManager(source)', source)

    def test_reuse_publication_and_declarations_cannot_omit_real_storage_source_cases(self):
        reg = registry()
        expected = {
            'com.chengyinhub.business.it.TemplateAdoptionRoundTripMySqlIT': 6,
            'com.chengyinhub.business.it.ThemeStateKeyDeclarationMySqlIT': 6,
            'com.chengyinhub.business.service.impl.TopicPublicationBindingMySqlIT': 9,
        }
        for fqcn, count in expected.items():
            with self.subTest(fqcn=fqcn):
                self.assertEqual(count, reg['classes'][fqcn]['tests'])
                self.assertEqual(['mysql'], reg['classes'][fqcn]['storage'])
                self.assertEqual({}, reg['classes'][fqcn]['gates'])
                self.assertEqual(1, reg['batches']['architecture-day-integrated-changes']['classes'].count(fqcn))
                facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
                self.assertEqual([], problems)
                self.assertEqual(count, facts['declared_tests'])
                stale = copy.deepcopy(reg)
                stale['classes'][fqcn]['tests'] -= 1
                self.assertTrue(rs.precheck(stale, _HERE.parent, fqcn)[1])

    def test_funds_process_source_has_exact_gates_count_and_unique_batch_routes(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.service.impl.FundsProcessRecoveryMySqlIT'
        gates = {'B_PAYMENT_INTENT_DDL_APPROVED': '1', 'B_FUNDS_PROCESS_RECOVERY_APPROVED': '1'}
        self.assertEqual({'tests': 14, 'storage': ['mysql'], 'gates': gates,
                          'database_base': 'architecture_a'},
                         {key: reg['classes'][fqcn][key] for key in ('tests', 'storage', 'gates', 'database_base')})
        for batch in ('architecture-day-funds-closure', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(14, facts['declared_tests'])
        for flag in gates:
            denied = copy.deepcopy(reg)
            denied['classes'][fqcn]['gates'].pop(flag)
            self.assertTrue(rs.precheck(denied, _HERE.parent, fqcn)[1])
        stale = copy.deepcopy(reg)
        stale['classes'][fqcn]['tests'] = 13
        self.assertTrue(rs.precheck(stale, _HERE.parent, fqcn)[1])
        env = rs.class_env(reg, fqcn, mysql_port=33060, suffix='_unit_b_funds')
        for flag in gates:
            self.assertEqual('1', env[flag])
            self.assertNotIn(flag, rs.child_env({flag: '1', 'PATH': '/usr/bin'}, {}))
            self.assertEqual([], rs.scan_java('// assertEquals("1", System.getenv("' + flag + '"));')['gates'])
        self.assertIsNone(rs.GATE_NAME_RE.fullmatch('B_FUNDS_ARBITRARY_APPROVED'))
        self.assertEqual([], workflow_problems(WORKFLOW.read_text(), reg))
        self.assertIn("B_FUNDS_PROCESS_RECOVERY_APPROVED: " +
                      "${{ matrix.simple == 'FundsProcessRecoveryMySqlIT' && '1' || '' }}",
                      WORKFLOW.read_text())

    def test_funds_process_artifact_step_exports_only_exact_source_json(self):
        workflow = WORKFLOW.read_text()
        step = workflow.split('      - name: Collect funds process JSON receipts\n', 1)[1]
        step = step.split('      - name: Upload class receipt\n', 1)[0]
        self.assertIn("if: always() && matrix.simple == 'FundsProcessRecoveryMySqlIT'", step)
        script = textwrap.dedent(step.split("          python3 - <<'PY'\n", 1)[1]
                                 .split('          PY\n', 1)[0])
        for case in ('valid', 'missing', 'bad_sha', 'symlink', 'invalid_json', 'foreign_identity', 'foreign_workflow', 'missing_run', 'missing_identity'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                original = pathlib.Path.cwd()
                try:
                    os.chdir(temp)
                    source = pathlib.Path('candidate/chengyinhub-admin/target/funds-process-recovery') / SHA / 'run'
                    if case != 'missing':
                        source.mkdir(parents=True)
                        (source / 'identity.json').write_text('{bad' if case == 'invalid_json'
                                                             else json.dumps({'sourceSha': '0' * 40 if case == 'foreign_identity' else SHA}))
                        (source / 'stdout.log').write_text('private diagnostic sentinel')
                        if case == 'missing_identity':
                            (source / 'identity.json').rename(source / 'other.json')
                    if case == 'symlink':
                        outside = pathlib.Path('outside.json')
                        outside.write_text('{}')
                        (source / 'escape.json').symlink_to(outside.resolve())
                    with mock.patch.dict(os.environ, {'SOURCE_SHA': 'not-a-sha' if case == 'bad_sha' else SHA,
                                                     'GITHUB_SHA': '0' * 40 if case == 'foreign_workflow' else SHA,
                                                     'GITHUB_RUN_ID': '' if case == 'missing_run' else '123',
                                                     'GITHUB_RUN_ATTEMPT': '2'}):
                        if case == 'valid':
                            exec(script, {})
                            output = pathlib.Path('out/funds-process-recovery') / SHA / 'run'
                            self.assertEqual({'sourceSha': SHA}, json.loads((output / 'identity.json').read_text()))
                            self.assertEqual(['identity.json'], sorted(path.name for path in output.iterdir()))
                            index = json.loads(pathlib.Path('out/funds-process-evidence-index.json').read_text())
                            self.assertEqual((SHA, '123', '2'), (index['sourceSha'], index['runId'], index['runAttempt']))
                            self.assertEqual({'run/identity.json': __import__('hashlib').sha256((output / 'identity.json').read_bytes()).hexdigest()}, index['files'])
                        else:
                            with self.assertRaises(ValueError):
                                exec(script, {})
                    self.assertEqual([], list(pathlib.Path('out').rglob('*.log')))
                    self.assertEqual([], list(pathlib.Path('out').rglob('escape.json')))
                finally:
                    os.chdir(original)

    def test_cross_domain_isolation_has_both_stores_and_its_existing_fact_approval(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.it.CrossDomainFaultIsolationMySqlIT'
        self.assertEqual({'tests': 1, 'storage': ['mysql', 'redis'], 'gates': {}},
                         {key: reg['classes'][fqcn][key] for key in ('tests', 'storage', 'gates')})
        self.assertEqual(1, reg['batches']['architecture-day-integrated-changes']['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(1, facts['declared_tests'])
        stale = copy.deepcopy(reg)
        stale['classes'][fqcn]['tests'] = 0
        self.assertTrue(rs.precheck(stale, _HERE.parent, fqcn)[1])
        env = rs.class_env(reg, fqcn, mysql_port=33060, redis_port=36379, suffix='_unit_isolation')
        self.assertEqual('127.0.0.1', env['CHENGYIN_MYBATIS_IT_HOST'])
        self.assertEqual('33060', env['CHENGYIN_MYBATIS_IT_PORT'])
        self.assertIn('CHENGYIN_MYBATIS_IT_PASSWORD', env)
        self.assertEqual('127.0.0.1', env['CHENGYIN_AI_REDIS_IT_HOST'])
        self.assertEqual('36379', env['CHENGYIN_AI_REDIS_IT_PORT'])
        self.assertEqual('0', env['CHENGYIN_AI_REDIS_IT_DATABASE'])
        helper = (_HERE.parent / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainStorageFixture.java').read_text()
        self.assertIn('assertEquals("1", required("B_PAYMENT_FACT_DDL_APPROVED"))', helper)
        flag = next(line.strip() for line in WORKFLOW.read_text().splitlines()
                    if line.strip().startswith('B_PAYMENT_FACT_DDL_APPROVED:'))
        self.assertIn("matrix.simple == 'CrossDomainFaultIsolationMySqlIT'", flag)
        self.assertEqual([], workflow_problems(WORKFLOW.read_text(), reg))

    def test_restore_money_source_routes_the_fixed_child_and_both_original_fact_approvals(self):
        reg = registry()
        fqcn = 'com.chengyinhub.business.service.RestoreMoneyDispatchMySqlIT'
        self.assertEqual({'tests': 1, 'storage': ['mysql'], 'gates': {}, 'database_base': 'architecture_a'},
                         {key: reg['classes'][fqcn][key] for key in ('tests', 'storage', 'gates', 'database_base')})
        for batch in ('architecture-day-funds-closure', 'architecture-day-integrated-changes'):
            self.assertEqual(1, reg['batches'][batch]['classes'].count(fqcn))
        facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
        self.assertEqual([], problems)
        self.assertEqual(1, facts['declared_tests'])
        stale = copy.deepcopy(reg)
        stale['classes'][fqcn]['tests'] = 0
        self.assertTrue(rs.precheck(stale, _HERE.parent, fqcn)[1])
        env = rs.class_env(reg, fqcn, mysql_port=33060, suffix='_unit_restore')
        self.assertEqual('architecture_a', env['CHENGYIN_TEST_MYSQL_DATABASE_BASE'])
        self.assertEqual('_unit_restore', env['CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX'])
        self.assertEqual('127.0.0.1', env['CHENGYIN_TEST_MYSQL_HOST'])
        workflow = WORKFLOW.read_text()
        for flag in ('B_PAYMENT_FACT_DDL_APPROVED', 'A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED'):
            line = next(line.strip() for line in workflow.splitlines() if line.strip().startswith(flag + ':'))
            self.assertIn("matrix.simple == 'RestoreMoneyDispatchMySqlIT'", line)
        self.assertNotIn('CHENGYIN_RESTORE_MODE:', workflow)
        self.assertEqual([], workflow_problems(workflow, reg))

    def test_batch_totals_match_the_remaining_receipt(self):
        # 23 条真库 + OWN_C01_51 外层 4 条普通；AI 15+7；E04-07 5+5（原回执41/43新增回归）；E02-22 9 + E02-25 2 + 三夹具 3；B05 3；F3 12（含死锁重试负控）。
        # 类条数全局唯一，按 master 侧登记：冲回 3→4、券生命周期 12→16、分享快照并发 2→3（extra-storage 71→76）。
        # 漏斗最大年份修复 4（含 TIMESTAMPADD 负控，master）。
        # port-prelaunch-69(W8)：退款通知 9 + 退款重试余量 10 + 漫游定位留存 21。
        # p0-acceptance-ac1(AC1)：核销 5 + 跨账号 3 + 城市跑 2 + 自由探索供给闸 2 + 注销 1。
        # port-*：同批 master 侧移植，类与条数同原批；port-core-funds 27；port-ai-quota 22；port-f3-growth 12；port-f3-evidence 4。
        expected = {"core-funds": 27, "ai-quota": 24, "e04-07-coupon": 10, "community-e02": 14,
                    "b05-visibility": 3, "f3-growth": 12, "extra-storage": 137,
                    "port-e04-07-coupon": 10, "port-community-e02": 14, "port-b05-visibility": 3,
                    "port-extra-storage": 137,
                    "port-core-funds": 27, "port-ai-quota": 24,
                    "port-f3-growth": 12, "port-f3-evidence": 4,
                    "funnel-max-date": 4, "port-prelaunch-69": 40,
                    "p0-acceptance-ac1": 13, "architecture-day-batch1": 79,
                    "architecture-day-wave2": 87, "architecture-day-photo": 15,
                    "architecture-day-broadcast-migration": 12, "architecture-day-settlement": 7,
                    "architecture-day-state-recovery": 51,
                    "architecture-day-runtime-governance": 37,
                    "architecture-day-registration-write": 125,
                    "architecture-day-im-revocation": 2,
                    "architecture-day-w19-owner-notice": 29,
                    "architecture-day-play-scheduler": 16,
                    "architecture-day-cash-expand": 45,
                    "architecture-day-ai-not-sent": 4,
                    "architecture-day-lead-admission": 14,
                    "architecture-day-topic-copy": 28,
                    "architecture-day-topic-authoring": 32,
                    "architecture-day-theme-intent": 23,
                    "architecture-day-payment-intent": 4,
                    "architecture-day-payment-inbox": 14,
                    "architecture-day-verified-payment-facts": 4,
                    "architecture-day-registration-seats": 7,
                    "architecture-day-original-refund": 25,
                    "architecture-day-deposit-attempt": 12,
                    "architecture-day-topic-content-budget": 15,
                    "architecture-day-committed-official-notice": 19,
                    "architecture-day-complaint-committed-notice": 11,
                    "architecture-day-complaint-notice-rejection": 8,
                    "architecture-day-chapter-application-round": 20,
                    "architecture-day-citynode-original-receipt": 6,
                    "architecture-day-registration-topic-fence": 46,
                    "architecture-day-topic-cash-fence": 5,
                    "architecture-day-scheduler-two-process": 3,
                    "architecture-day-cash-recovery": 10,
                    "architecture-day-funds-closure": 279,
                    "architecture-day-npc-voice-asr": 14,
                    "architecture-day-clm-commerce-proof": 8,
                    "architecture-day-media-retry-budget": 8,
                    "architecture-day-before-start-notice": 12,
                    "architecture-day-commerce-generic-guard": 6,
                    "architecture-day-refund-lifecycle": 76,
                    "architecture-day-integrated-changes": 933, "payment-intent-correctness": 30}
        reg = registry()
        totals = {name: sum(reg["classes"][fqcn]["tests"] for fqcn in batch["classes"])
                  for name, batch in reg["batches"].items()}
        self.assertEqual(expected, totals)

    def test_reviewed_implicit_it_suites_have_explicit_fresh_storage_routes(self):
        reg = registry()
        expected = {
            "com.chengyinhub.business.it.FreeSelfPlayCreateRealDbIT": (1, "CHENGYIN_SELFCREATE_IT_MYSQL"),
            "com.chengyinhub.business.service.impl.ImLegacyReadLockMySqlIT": (5, "CHENGYIN_IM_LEGACY_LOCK_MYSQL_IT"),
            "com.chengyinhub.business.it.CoopClubApplyInboxMySqlIT": (1, None),
            "com.chengyinhub.business.it.CoopServiceRealDbIT": (1, None),
            "com.chengyinhub.business.it.TemplateAdoptionRoundTripMySqlIT": (6, None),
            "com.chengyinhub.business.mapper.ActivityCancelPlayerProjectionMyBatisIT": (1, None),
            "com.chengyinhub.business.service.TemplateClearFieldsMySqlIT": (3, None),
            "com.chengyinhub.business.service.impl.GameplayReskinMySqlIT": (1, None),
            "com.chengyinhub.business.it.UmsMemberRankMySqlIT": (1, "CHENGYIN_FUNDS_MYSQL_IT"),
        }
        for fqcn, (tests, gate) in expected.items():
            with self.subTest(fqcn=fqcn):
                self.assertEqual({"tests": tests, "storage": ["mysql"],
                                  "gates": {gate: "1"} if gate else {},
                                  "database_base": "cyit"}, reg["classes"][fqcn])
                for batch in ("extra-storage", "port-extra-storage"):
                    self.assertEqual(1, reg["batches"][batch]["classes"].count(fqcn))
                facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
                self.assertEqual([], problems)
                self.assertEqual([], facts["nested"])

    def test_registration_and_revocation_batches_require_current_counts_and_real_gates(self):
        reg = registry()
        expected = {
            "com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest": (101, {"CHENGYIN_FUNDS_MYSQL_IT": "1"}),
            "com.chengyinhub.business.it.MerchantRegistrationCancellationMySqlIT": (15, {}),
            "com.chengyinhub.business.it.RegistrationStationFulfillmentMySqlIT": (9, {}),
        }
        self.assertEqual(list(expected), reg["batches"]["architecture-day-registration-write"]["classes"])
        revoked = "com.chengyinhub.business.service.impl.ImActivityTeamRevocationMySqlIT"
        self.assertEqual([revoked], reg["batches"]["architecture-day-im-revocation"]["classes"])
        expected[revoked] = (2, {})
        for fqcn, (count, gates) in expected.items():
            self.assertEqual(count, reg["classes"][fqcn]["tests"])
            self.assertEqual(gates, reg["classes"][fqcn]["gates"])
            facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
            self.assertEqual([], problems)
            self.assertEqual(count, facts["declared_tests"])
            self.assertEqual([], facts["nested"])

    def test_w19_owner_notification_requires_new_intent_and_original_supply_regression(self):
        reg = registry()
        expected = {
            "com.chengyinhub.business.service.impl.MerchantOfferOwnerNoticeIntentMySqlIT": (21, {"CHENGYIN_FUNDS_MYSQL_IT": "1"}),
            "com.chengyinhub.business.it.FreeExploreNodeSupplyMySqlIT": (8, {}),
        }
        self.assertEqual(list(expected), reg["batches"]["architecture-day-w19-owner-notice"]["classes"])
        for fqcn, (count, gates) in expected.items():
            self.assertEqual(count, reg["classes"][fqcn]["tests"])
            self.assertEqual(gates, reg["classes"][fqcn]["gates"])
            facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
            self.assertEqual([], problems)
            self.assertEqual(count, facts["declared_tests"])
            self.assertEqual([], facts["nested"])

    def test_play_and_scheduler_batch_preserves_exact_non_dynamic_cases(self):
        reg = registry()
        expected = {
            "com.chengyinhub.business.it.PlayHintUnlockMySqlIT": 10,
            "com.chengyinhub.quartz.util.SchedulerSessionLockMySqlIT": 3,
            "com.chengyinhub.quartz.util.SchedulerHandoffBoundaryMySqlIT": 3,
        }
        self.assertEqual(list(expected), reg["batches"]["architecture-day-play-scheduler"]["classes"])
        for fqcn, count in expected.items():
            self.assertEqual(count, reg["classes"][fqcn]["tests"])
            facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
            self.assertEqual([], problems)
            self.assertEqual(count, facts["declared_tests"])
            self.assertEqual([], facts["nested"])

    def test_cloudten_routes_keep_their_namespace_and_never_claim_hosted_is_vps(self):
        reg = registry()
        expected = {
            "com.chengyinhub.business.service.PersonalExplorationGoalMySqlIT": 8,
            "com.chengyinhub.business.service.ExplorationAnniversaryMySqlIT": 9,
            "com.chengyinhub.web.controller.api.PersonalExplorationExportMySqlIT": 7,
        }
        runner = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
                  "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux"}
        suffix = rs.database_suffix(123456789, 1)
        for fqcn, tests in expected.items():
            with self.subTest(fqcn=fqcn):
                self.assertEqual({"tests": tests, "storage": ["mysql"],
                                  "gates": {"CHENGYIN_TEN_FEATURES_MYSQL_IT": "1"},
                                  "database_base": "cy_cloudten_it"}, reg["classes"][fqcn])
                for batch in ("extra-storage", "port-extra-storage"):
                    self.assertEqual(1, reg["batches"][batch]["classes"].count(fqcn))
                facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
                self.assertEqual([], problems)
                self.assertEqual([], facts["nested"])
                env = rs.class_env(reg, fqcn, mysql_port=40001, suffix=suffix, timezone="Asia/Shanghai")
                self.assertEqual("cy_cloudten_it", env["CHENGYIN_TEST_MYSQL_DATABASE_BASE"])
                self.assertEqual("127.0.0.1", env["CHENGYIN_TEST_MYSQL_HOST"])
                self.assertEqual("40001", env["CHENGYIN_TEST_MYSQL_PORT"])
                self.assertEqual(suffix, env["CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX"])
                self.assertEqual("Asia/Shanghai", env["TZ"])
                self.assertNotIn("CHENGYIN_TEN_FEATURES_VPS", env)
                child = rs.child_env(dict(runner, CHENGYIN_TEN_FEATURES_VPS="1"), env)
                self.assertNotIn("CHENGYIN_TEN_FEATURES_VPS", child)
                for key, value in runner.items():
                    self.assertEqual(value, child[key])
                for gates in ({}, {"CHENGYIN_TEN_FEATURES_MYSQL_IT": "0"},
                              {"CHENGYIN_TEN_FEATURES_MYSQL_IT": "1", "CHENGYIN_TEN_FEATURES_VPS": "1"}):
                    mutated = copy.deepcopy(reg)
                    mutated["classes"][fqcn]["gates"] = gates
                    self.assertTrue(rs.precheck(mutated, _HERE.parent, fqcn)[1], gates)

    def test_existing_gated_asset_capacity_and_im_suites_have_matching_extra_routes(self):
        reg = registry()
        expected = {
            "com.chengyinhub.business.service.B03AssetMybatisIntegrationTest": (7, "CHENGYIN_FUNDS_MYSQL_IT"),
            "com.chengyinhub.business.service.OfficialV1CapacityMySqlTest": (2, "CHENGYIN_FUNDS_MYSQL_IT"),
            "com.chengyinhub.business.service.impl.ImFoundationBMybatisIntegrationTest": (4, "CHENGYIN_IM_FOUNDATION_B_MYSQL_IT"),
        }
        for fqcn, (tests, gate) in expected.items():
            with self.subTest(fqcn=fqcn):
                self.assertEqual({"tests": tests, "storage": ["mysql"], "gates": {gate: "1"},
                                  "database_base": "cyit"}, reg["classes"][fqcn])
                for batch in ("extra-storage", "port-extra-storage"):
                    self.assertEqual(1, reg["batches"][batch]["classes"].count(fqcn))
                facts, problems = rs.precheck(reg, _HERE.parent, fqcn)
                self.assertEqual([], problems)
                self.assertEqual([], facts["nested"])

    def test_negative_controls(self):
        mutations = {
            "unpinned image": lambda r: r["images"].update(mysql="mysql:8.0.45"),
            "image version drift": lambda r: r.update(mysql_version="8.0.44"),
            "unknown class in batch": lambda r: r["batches"]["core-funds"]["classes"].append("com.x.Missing"),
            "class in no batch": lambda r: r["classes"].update({"com.x.Orphan": {"tests": 1, "storage": ["mysql"],
                                                                                  "gates": {}}}),
            "zero tests": lambda r: r["classes"][E04].update(tests=0),
            "bad gate name": lambda r: r["classes"][E04]["gates"].update({"FUNDS": "1"}),
            "unknown storage": lambda r: r["classes"][E04].update(storage=["h2"]),
            "unknown field": lambda r: r["classes"][E04].update(expected_skips=1),
            "duplicate class in batch": lambda r: r["batches"]["core-funds"]["classes"].append(E04),
            "short registered source": lambda r: r["batches"]["core-funds"].update(registered_source="5752ee6f"),
            "base too long for C01 schema": lambda r: r.update(default_database_base="chengyin_it_x"),
        }
        for label, mutate in mutations.items():
            mutated = copy.deepcopy(registry())
            mutate(mutated)
            with self.subTest(label):
                self.assertTrue(rs.validate_registry(mutated), label)

    def test_schema_name_budgets(self):
        reg = registry()
        suffix = rs.database_suffix(9999999999, 99)
        self.assertEqual(rs.SUFFIX_MAX_LEN, len(suffix))
        self.assertRegex(suffix, rs.A03_SUFFIX_RE)
        base = reg["default_database_base"]
        # OWN_C01_51Test: base + "_own_c01_51_" + 32 位 UUID + 后缀。
        self.assertLessEqual(len(base) + len("_own_c01_51_") + 32 + len(suffix), 64)
        # PlayerGrowthConcurrencyMySqlIT 要求配置库名 ≤ 47，再追加 UUID16。
        self.assertLessEqual(len(base + "_f3_growth" + suffix), 47)
        # OWN_C04_19Test 在 MyBatis 库名里至少要留 12 位随机身份。
        self.assertGreaterEqual(64 - len("chengyin_mybatis_c04_run_order_" + suffix), 12)
        self.assertLessEqual(len("e04_scratch_e04_13_it" + suffix), 64)
        with self.assertRaises(rs.GateError):
            rs.database_suffix(1, 100)


class JavaScanTest(unittest.TestCase):
    def test_counts_only_real_annotations(self):
        scan = rs.scan_java(SAMPLE_JAVA)
        self.assertEqual(4, scan["declared_tests"])
        self.assertEqual(["RealMysql"], scan["nested"])
        self.assertEqual([("CHENGYIN_RUN_SAMPLE_MYSQL", "true")], scan["gates"])
        self.assertEqual([], scan["conditions"])
        self.assertEqual([], scan["dynamic"])

    def test_flags_dynamic_and_disabled(self):
        mutated = (SAMPLE_JAVA.replace("@Test void a()", "@ParameterizedTest void a()", 1)
                   .replace("@Test\n    void c()", "@Disabled @Test\n    void c()", 1))
        self.assertEqual(2, sum(token in mutated for token in ("@ParameterizedTest void a()", "@Disabled @Test")))
        scan = rs.scan_java(mutated)
        self.assertEqual(["ParameterizedTest"], scan["dynamic"])
        self.assertEqual(["Disabled"], scan["conditions"])

    def test_gate_values_must_satisfy_source(self):
        gates = [("CHENGYIN_RUN_SAMPLE_MYSQL", "true")]
        self.assertEqual([], rs.check_gates(gates, {"CHENGYIN_RUN_SAMPLE_MYSQL": "true"}))
        self.assertTrue(rs.check_gates(gates, {"CHENGYIN_RUN_SAMPLE_MYSQL": "1"}))
        self.assertTrue(rs.check_gates(gates, {}))
        self.assertTrue(rs.check_gates(gates, {"CHENGYIN_RUN_SAMPLE_MYSQL": "true", "CHENGYIN_STALE_IT": "1"}))

    def test_precheck_against_candidate_tree(self):
        reg = {"module": "admin", "jdk_for_java_version": {"1.8": "11"},
               "classes": {"com.example.SampleTest": {"tests": 4, "storage": ["mysql"],
                                                      "gates": {"CHENGYIN_RUN_SAMPLE_MYSQL": "true"},
                                                      "nested": ["RealMysql"]}}}
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root, "admin/src/test/java/com/example/SampleTest.java")
            path.parent.mkdir(parents=True)
            path.write_text(SAMPLE_JAVA, encoding="utf-8")
            pathlib.Path(root, "pom.xml").write_text("<properties><java.version>1.8</java.version></properties>",
                                                     encoding="utf-8")
            facts, problems = rs.precheck(reg, root, "com.example.SampleTest")
            self.assertEqual([], problems)
            self.assertEqual("11", facts["jdk"])
            mutations = {
                "count drift": lambda r: r.update(tests=5),
                "nested not registered (surefire would drop it)": lambda r: r.update(nested=[]),
                "gate value true vs 1": lambda r: r.update(gates={"CHENGYIN_RUN_SAMPLE_MYSQL": "1"}),
            }
            for label, mutate in mutations.items():
                mutated = copy.deepcopy(reg)
                mutate(mutated["classes"]["com.example.SampleTest"])
                with self.subTest(label):
                    self.assertTrue(rs.precheck(mutated, root, "com.example.SampleTest")[1], label)
            with self.assertRaises(rs.GateError):
                rs.precheck(reg, root, "com.example.Missing")

    def test_hard_coded_database_base_must_match_registry(self):
        pinned = SAMPLE_JAVA.replace("    @Test void a() { }",
                                     '    @BeforeAll void setUp() {\n'
                                     '        assertEquals("e04_scratch", System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE"));\n'
                                     '    }\n    @Test void a() { }', 1)
        self.assertIn("e04_scratch", pinned)
        self.assertEqual(["e04_scratch"], rs.scan_java(pinned)["required_database_bases"])
        reg = {"module": "admin", "jdk_for_java_version": {"1.8": "11"}, "default_database_base": "cyit",
               "classes": {"com.example.SampleTest": {"tests": 4, "storage": ["mysql"],
                                                      "gates": {"CHENGYIN_RUN_SAMPLE_MYSQL": "true"},
                                                      "nested": ["RealMysql"]}}}
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root, "admin/src/test/java/com/example/SampleTest.java")
            path.parent.mkdir(parents=True)
            path.write_text(pinned, encoding="utf-8")
            pathlib.Path(root, "pom.xml").write_text("<properties><java.version>1.8</java.version></properties>",
                                                     encoding="utf-8")
            problems = rs.precheck(reg, root, "com.example.SampleTest")[1]
            self.assertTrue(any("e04_scratch" in problem for problem in problems), problems)
            reg["classes"]["com.example.SampleTest"]["database_base"] = "e04_scratch"
            self.assertEqual([], rs.precheck(reg, root, "com.example.SampleTest")[1])


class ReportGateTest(unittest.TestCase):
    def verify(self, cases, files=1, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            for index in range(files):
                write_suite(directory, "%s%d" % (C01, index), cases if index == 0 else [])
            found = rs.collect_testcases(directory)
        return rs.verify_class_reports(found, C01, 5, ["RealMysqlRepeatableRead"], **kwargs)

    def test_green_with_nested(self):
        summary, problems = self.verify(c01_cases())
        self.assertEqual([], problems)
        self.assertEqual(5, summary["passed"])
        self.assertIn(C01 + "$RealMysqlRepeatableRead", summary["classes"])

    def test_red_when_surefire_drops_the_nested_class(self):
        _, problems = self.verify(c01_cases(nested=False))
        self.assertTrue(any("实跑 4 条" in problem for problem in problems))
        self.assertTrue(any("RealMysqlRepeatableRead" in problem for problem in problems))

    def test_red_when_gate_closed_and_class_skipped(self):
        self.assertTrue(self.verify(c01_cases(status="skipped"))[1])

    def test_red_on_failure_or_error(self):
        for status in ("failure", "error"):
            cases = c01_cases()
            cases[0] = (cases[0][0], cases[0][1], status)
            with self.subTest(status):
                self.assertTrue(self.verify(cases)[1])

    def test_red_on_duplicate_and_foreign_cases(self):
        self.assertTrue(self.verify(c01_cases() + [c01_cases()[0]])[1])
        self.assertTrue(self.verify(c01_cases() + [("com.example.Other", "x", "passed")])[1])

    def test_red_without_reports(self):
        _, problems = rs.verify_class_reports(rs.collect_testcases("/nonexistent/surefire"), C01, 5)
        self.assertTrue(problems)

    def test_unreadable_report_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            pathlib.Path(directory, "TEST-broken.xml").write_text("<testsuite", encoding="utf-8")
            with self.assertRaises(rs.GateError):
                rs.collect_testcases(directory)


class EnvProfileTest(unittest.TestCase):
    def test_e04_base_and_only_own_gate(self):
        env = rs.class_env(registry(), E04, mysql_port=40001, suffix="_gh000001a1")
        self.assertEqual("e04_scratch", env["CHENGYIN_TEST_MYSQL_DATABASE_BASE"])
        self.assertEqual("1", env["CHENGYIN_FUNDS_MYSQL_IT"])
        gates = sorted(key for key in env if re.search(r"_(IT|MYSQL)$", key) and key.startswith("CHENGYIN_"))
        self.assertEqual(["CHENGYIN_FUNDS_MYSQL_IT"], gates)
        for key in ("CHENGYIN_TEST_MYSQL_PASSWORD", "CHENGYIN_MYBATIS_IT_PASSWORD", "CHENGYIN_AI_MYSQL_IT_PASSWORD"):
            self.assertEqual("", env[key])
        self.assertEqual("127.0.0.1", env["CHENGYIN_TEST_MYSQL_HOST"])
        self.assertNotIn("CHENGYIN_AI_REDIS_IT_PORT", env)

    def test_c01_gate_is_literal_true(self):
        env = rs.class_env(registry(), C01, mysql_port=40001, suffix="_gh000001a1")
        self.assertEqual("true", env["CHENGYIN_RUN_OWN_C01_51_MYSQL"])
        self.assertNotIn("CHENGYIN_FUNDS_MYSQL_IT", env)
        self.assertEqual(registry()["default_database_base"], env["CHENGYIN_TEST_MYSQL_DATABASE_BASE"])

    def test_redis_only_and_mixed_storage(self):
        redis_only = rs.class_env(registry(), AI_REDIS, redis_port=40002)
        self.assertEqual("127.0.0.1", redis_only["CHENGYIN_AI_REDIS_IT_HOST"])
        self.assertFalse([key for key in redis_only if "MYSQL" in key and key != "CHENGYIN_AI_RESERVATION_REDIS_IT"])
        both = rs.class_env(registry(), AI_BOTH, mysql_port=40001, redis_port=40002, suffix="_gh000001a1")
        self.assertEqual("40001", both["CHENGYIN_AI_MYSQL_IT_PORT"])
        self.assertEqual("40002", both["CHENGYIN_AI_REDIS_IT_PORT"])
        with self.assertRaises(rs.GateError):
            rs.class_env(registry(), AI_BOTH, mysql_port=40001, suffix="_gh000001a1")

    def test_inherited_gates_are_dropped(self):
        extra = rs.class_env(registry(), E02_22, mysql_port=40001, suffix="_gh000001a1")
        env = rs.child_env({"PATH": "/usr/bin", "CHENGYIN_FUNDS_MYSQL_IT": "1"}, extra)
        self.assertNotIn("CHENGYIN_FUNDS_MYSQL_IT", env)
        self.assertEqual("1", env["CHENGYIN_E02_22_MYSQL_IT"])
        self.assertEqual("/usr/bin", env["PATH"])

    def test_public_env_never_carries_password_values(self):
        shown = rs.public_env({"CHENGYIN_TEST_MYSQL_PASSWORD": "", "CHENGYIN_AI_REDIS_IT_PASSWORD": "s3cret"})
        self.assertEqual("<explicit-empty>", shown["CHENGYIN_TEST_MYSQL_PASSWORD"])
        self.assertEqual("<redacted>", shown["CHENGYIN_AI_REDIS_IT_PASSWORD"])


class PlanTest(unittest.TestCase):
    def test_full_batch_and_subset(self):
        self.assertEqual(8, len(rs.plan(registry(), SHA, "core-funds", "", "UTC")))
        subset = rs.plan(registry(), SHA, "core-funds", " OWN_C01_51Test , E04_13Test ", "Asia/Shanghai")
        self.assertEqual(["E04_13Test", "OWN_C01_51Test"], [item["simple"] for item in subset])

    def test_rejects_bad_inputs(self):
        bad = {
            "short sha": (SHA[:12], "core-funds", "", "UTC"),
            "uppercase sha": (SHA.upper(), "core-funds", "", "UTC"),
            "unknown batch": (SHA, "everything", "", "UTC"),
            "unknown timezone": (SHA, "core-funds", "", "PST"),
            "class outside batch": (SHA, "core-funds", "E02_22Test", "UTC"),
            "duplicate class": (SHA, "core-funds", "E04_13Test,E04_13Test", "UTC"),
            "shell characters": (SHA, "core-funds", "E04_13Test;id", "UTC"),
        }
        for label, args in bad.items():
            with self.subTest(label):
                with self.assertRaises(rs.GateError):
                    rs.plan(registry(), *args)


class SummaryTest(unittest.TestCase):
    def summarize(self, receipts, planned_simple=("E04_13Test", "OWN_C01_51Test")):
        reg = registry()
        planned = [item for item in rs.plan(reg, SHA, "core-funds", "", "UTC") if item["simple"] in planned_simple]
        with tempfile.TemporaryDirectory() as directory:
            for index, receipt in enumerate(receipts):
                folder = pathlib.Path(directory, "rs-class-%d" % index)
                folder.mkdir()
                (folder / "receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
            return rs.summarize(reg, directory, planned, "core-funds", SHA)[0]

    @staticmethod
    def receipt(fqcn, verdict="PASS", attempt="1", sha=SHA):
        return {"class": fqcn, "batch": "core-funds", "source_sha": sha, "verdict": verdict,
                "workflow": {"run_attempt": attempt}, "executed": {"tests": 2}, "cleanup": {"status": "CLEAN"}}

    def test_all_pass(self):
        summary = self.summarize([self.receipt(E04), self.receipt(C01)])
        self.assertEqual("PASS", summary["verdict"])
        self.assertTrue(summary["subset"], "2 of 8 core-funds classes must be labelled as a subset")

    def test_missing_failed_mismatched_or_unplanned_receipts_fail(self):
        self.assertEqual("FAIL", self.summarize([self.receipt(E04)])["verdict"])
        self.assertEqual("FAIL", self.summarize([self.receipt(E04), self.receipt(C01, "FAIL")])["verdict"])
        self.assertEqual("FAIL", self.summarize([self.receipt(E04), self.receipt(C01, sha="0" * 40)])["verdict"])
        self.assertEqual("FAIL", self.summarize([self.receipt(E04), self.receipt(C01)], ("E04_13Test",))["verdict"])

    def test_latest_attempt_wins(self):
        receipts = [self.receipt(E04), self.receipt(C01, "FAIL", "1"), self.receipt(C01, "PASS", "2")]
        self.assertEqual("PASS", self.summarize(receipts)["verdict"])

    def test_nonclean_or_missing_cleanup_cannot_be_hidden_by_pass_receipt(self):
        for cleanup in ({"status": "LEFTOVER"}, {"status": "UNKNOWN", "error": "container gone"},
                        {}, None):
            receipt = self.receipt(C01)
            if cleanup is None:
                receipt.pop("cleanup")
            else:
                receipt["cleanup"] = cleanup
            with self.subTest(cleanup=cleanup):
                summary = self.summarize([self.receipt(E04), receipt])
                self.assertEqual("FAIL", summary["verdict"])
                self.assertEqual("FAIL", summary["classes"][1]["verdict"])
                self.assertTrue(any("cleanup" in problem for problem in summary["problems"]))


class FinalEntryBindingTest(unittest.TestCase):
    WORKFLOW = {'repository': 'liboyang42-cpu/hengyin', 'run_id': '123', 'run_attempt': '2',
                'workflow_sha': SHA, 'workflow_ref': 'refs/heads/codex/candidate', 'job': 'summary'}

    def test_parent_restore_mode_is_absent_not_an_approval_or_silently_filtered(self):
        self.assertEqual({'PATH': '/bin'}, rs.child_env({'PATH': '/bin'}, {}))
        for value in ('', 'false', 'true', 'invalid', None):
            for base, extra in (({'CHENGYIN_RESTORE_MODE': value}, {}),
                                ({}, {'CHENGYIN_RESTORE_MODE': value})):
                with self.subTest(value=value, extra=bool(extra)), self.assertRaises(rs.GateError):
                    rs.child_env(base, extra)
        reg = registry()
        reg['classes'][E04]['gates']['CHENGYIN_RESTORE_MODE'] = 'true'
        self.assertTrue(rs.validate_class_entry(E04, reg['classes'][E04]))
        self.assertEqual(('B_PAYMENT_INTENT_DDL_APPROVED', 'B_FUNDS_PROCESS_RECOVERY_APPROVED'),
                         rs.REVIEWED_APPROVAL_GATES)

    def test_B_native_identity_requires_same_workflow_sha_without_context_replacement(self):
        fqcn = 'com.chengyinhub.business.service.impl.FundsProcessRecoveryMySqlIT'
        env = {'GITHUB_SHA': SHA}
        rs.require_class_source(fqcn, SHA, env)
        self.assertEqual({'GITHUB_SHA': SHA}, env)
        for observed in ('', '0' * 40):
            with self.subTest(observed=observed), self.assertRaises(rs.GateError):
                rs.require_class_source(fqcn, SHA, {'GITHUB_SHA': observed})
        # Original two-source M1 remains supported; it does not emit B's GITHUB_SHA directory.
        rs.require_class_source('com.chengyinhub.business.it.PlayHintUnlockMySqlIT', SHA, {})

    def receipt(self, fqcn):
        count = registry()['classes'][fqcn]['tests']
        return dict(SummaryTest.receipt(fqcn), schema=rs.RECEIPT_SCHEMA, expected_tests=count,
                    workflow=dict(self.WORKFLOW, job='storage_it'),
                    executed={'tests': count, 'failures': 0, 'errors': 0, 'skipped': 0},
                    maven={'exit_code': 0}, precheck={'candidate': {'sha': SHA}},
                    cleanup={'status': 'CLEAN', 'mysql_leftover_schemas': []},
                    teardown={'mysql': {'removed': True}})

    def summary(self, receipts):
        reg = registry()
        planned = [item for item in rs.plan(reg, SHA, 'core-funds', '', 'UTC') if item['class'] in (E04, C01)]
        with tempfile.TemporaryDirectory() as temp:
            for index, receipt in enumerate(receipts):
                path = pathlib.Path(temp, str(index)); path.mkdir()
                (path / 'receipt.json').write_text(json.dumps(receipt))
            return rs.summarize(reg, temp, planned, 'core-funds', SHA, workflow=self.WORKFLOW)[0]

    def test_same_run_exact_original_execution_and_cleanup_receipts_pass(self):
        self.assertEqual('PASS', self.summary([self.receipt(E04), self.receipt(C01)])['verdict'])

    def test_foreign_duplicate_missing_or_incomplete_receipts_cannot_be_promoted(self):
        cases = ('run_id', 'run_attempt', 'workflow_sha', 'repository', 'workflow_ref', 'job',
                 'duplicate', 'missing', 'schema', 'count', 'skip', 'maven', 'candidate',
                 'unknown_cleanup', 'missing_readback', 'missing_teardown', 'malformed', 'boolean_exit', 'unknown_class')
        for case in cases:
            receipts = [self.receipt(E04), self.receipt(C01)]
            receipt = receipts[-1]
            if case in self.WORKFLOW:
                receipt['workflow'][case] = 'foreign'
            elif case == 'duplicate': receipts.append(copy.deepcopy(receipt))
            elif case == 'missing': receipts.pop()
            elif case == 'schema': receipt['schema'] = 'foreign'
            elif case == 'count': receipt['executed']['tests'] -= 1
            elif case == 'skip': receipt['executed']['skipped'] = 1
            elif case == 'maven': receipt['maven']['exit_code'] = 98
            elif case == 'candidate': receipt['precheck']['candidate']['sha'] = '0' * 40
            elif case == 'unknown_cleanup': receipt['cleanup']['status'] = 'UNKNOWN'
            elif case == 'missing_readback': receipt['cleanup'].pop('mysql_leftover_schemas')
            elif case == 'missing_teardown': receipt.pop('teardown')
            elif case == 'malformed': receipt['executed'] = None
            elif case == 'boolean_exit': receipt['maven']['exit_code'] = False
            elif case == 'unknown_class': receipt['class'] = ''
            with self.subTest(case=case):
                self.assertEqual('FAIL', self.summary(receipts)['verdict'])

    def test_summary_cli_and_download_bind_the_current_attempt(self):
        env = dict(GITHUB_REPOSITORY=self.WORKFLOW['repository'], GITHUB_RUN_ID='123',
                   GITHUB_RUN_ATTEMPT='2', GITHUB_SHA=SHA, GITHUB_REF=self.WORKFLOW['workflow_ref'],
                   GITHUB_JOB='summary', SOURCE_SHA=SHA, BATCH='core-funds', PLANNED='[]')
        with mock.patch.object(rs, 'summarize', side_effect=rs.GateError('controlled stop')) as call:
            with self.assertRaises(rs.GateError):
                rs.cmd_summarize(mock.Mock(), env)
        self.assertEqual(self.WORKFLOW, {key: call.call_args.kwargs['workflow'][key] for key in self.WORKFLOW})
        self.assertIn('pattern: rs-class-*-a${{ github.run_attempt }}', WORKFLOW.read_text())

    def test_theme_runtime_keeps_native_parameterized_backend_entry(self):
        reg = registry()
        self.assertFalse(any(rs.simple_name(name) == 'ThemeRuntimeMySqlIT' for name in reg['classes']))
        source = (_HERE / 'backend-test.sh').read_text()
        self.assertIn('ThemeRuntimeMySqlIT', source)
        self.assertIn('MYBATIS_ITS', source)
        self.assertIn('assert_it_ran.py', source)
        self.assertIn('-Dtest="$IT_LIST"', source)

    def test_failed_collection_or_matrix_cannot_be_hidden_by_pass_receipts(self):
        for result in ('success', 'failure', 'cancelled', 'UNKNOWN'):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as temp:
                env = dict(GITHUB_REPOSITORY=self.WORKFLOW['repository'], GITHUB_RUN_ID='123',
                           GITHUB_RUN_ATTEMPT='2', GITHUB_SHA=SHA, GITHUB_REF=self.WORKFLOW['workflow_ref'],
                           GITHUB_JOB='summary', SOURCE_SHA=SHA, BATCH='core-funds', PLANNED='[]',
                           STORAGE_MATRIX_RESULT=result)
                args = __import__('argparse').Namespace(receipts=temp, out=str(pathlib.Path(temp, 'out')))
                with mock.patch.object(rs, 'summarize', return_value=({'verdict': 'PASS', 'problems': []}, [])), \
                     mock.patch.object(rs, 'summary_markdown', return_value='controlled summary'):
                    code = rs.cmd_summarize(args, env)
                self.assertEqual(0 if result == 'success' else 1, code)
                written = json.loads(pathlib.Path(args.out, 'summary.json').read_text())
                self.assertEqual('PASS' if result == 'success' else 'FAIL', written['verdict'])
                self.assertEqual(result, written['storage_matrix_result'])
        self.assertEqual(2, WORKFLOW.read_text().count('STORAGE_MATRIX_RESULT: ${{ needs.storage_it.result }}'))


class FakeClock:
    def __init__(self, step):
        self.now = 0.0
        self.step = step

    def monotonic(self):
        self.now += self.step
        return self.now

    def sleep(self, _seconds):
        pass

    def time(self):
        return self.now


class Patch:
    """最小 monkeypatch：测完把 real_storage_it 的属性还原。"""

    def __init__(self, test, **values):
        self.saved = {name: getattr(rs, name) for name in values}
        for name, value in values.items():
            setattr(rs, name, value)
        test.addCleanup(self.restore)

    def restore(self):
        for name, value in self.saved.items():
            setattr(rs, name, value)


def fake_docker(facts_row, schemas=("information_schema", "mysql", "performance_schema", "sys"), port_line=None):
    calls = []

    def docker(*args, check=True):
        calls.append(args)
        if args[0] == "port":
            return port_line if port_line is not None else "127.0.0.1:40001\n"
        if args[0] == "exec" and "-e" in args:
            sql = args[-1]
            if sql == "SELECT 1":
                return "1\n"
            if sql.startswith("SELECT VERSION()"):
                return "\t".join(facts_row) + "\n"
            if "schemata" in sql:
                return "\n".join(schemas) + "\n"
        if args[0] == "ps":
            return ""
        return ""
    return docker, calls


MYSQL_ROW = ["8.0.45", "MySQL Community Server - GPL", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES", "REPEATABLE-READ",
             "+00:00", "UTC", "utf8mb4", "utf8mb4_0900_ai_ci", "0", "50", "1", "1"]


class StorageTest(unittest.TestCase):
    def test_mysql_identity_and_facts(self):
        docker, calls = fake_docker(MYSQL_ROW)
        Patch(self, docker=docker, mysql_handshake_version=lambda port: "8.0.45", time=FakeClock(1))
        facts = rs.start_mysql(registry(), "cy-rs-1-1-x-mysql", "UTC")
        self.assertEqual(40001, facts["port"])
        self.assertEqual("REPEATABLE-READ", facts["transaction_isolation"])
        run = [call for call in calls if call[0] == "run"][0]
        self.assertIn("127.0.0.1::3306", run)
        self.assertIn("--default-time-zone=+00:00", run)
        self.assertIn(registry()["images"]["mysql"], run)

    def test_mysql_red_paths(self):
        cases = {
            "not oracle community": (dict(enumerate(MYSQL_ROW)), {1: "Percona Server"}, "8.0.45", ()),
            "time zone drift": (dict(enumerate(MYSQL_ROW)), {4: "+08:00"}, "8.0.45", ()),
            "pre-existing schema": (dict(enumerate(MYSQL_ROW)), {}, "8.0.45", ("chengyinhubdb",)),
            "port points elsewhere": (dict(enumerate(MYSQL_ROW)), {}, "5.7.44", ()),
        }
        for label, (row, change, handshake, extra_schemas) in cases.items():
            row.update(change)
            schemas = ("information_schema", "mysql", "performance_schema", "sys") + extra_schemas
            docker, _ = fake_docker([row[index] for index in range(len(MYSQL_ROW))], schemas)
            with self.subTest(label):
                Patch(self, docker=docker, mysql_handshake_version=lambda port, seen=handshake: seen,
                      time=FakeClock(100))
                with self.assertRaises(rs.GateError):
                    rs.start_mysql(registry(), "cy-rs-1-1-x-mysql", "UTC")

    def test_port_must_be_loopback(self):
        docker, _ = fake_docker(MYSQL_ROW, port_line="0.0.0.0:40001\n")
        Patch(self, docker=docker)
        with self.assertRaises(rs.GateError):
            rs.mapped_port("x", 3306)

    def test_handshake_and_ping_over_loopback(self):
        import socket
        import threading

        def serve(payload, reply_to_ping=False):
            server = socket.socket()
            server.bind(("127.0.0.1", 0))
            server.listen(1)

            def handle():
                connection, _ = server.accept()
                if reply_to_ping:
                    connection.recv(16)
                connection.sendall(payload)
                connection.close()
                server.close()
            threading.Thread(target=handle, daemon=True).start()
            return server.getsockname()[1]

        greeting = b"\x4a\x00\x00\x00\x0a" + b"8.0.45\x00" + b"\x00" * 40
        self.assertEqual("8.0.45", rs.mysql_handshake_version(serve(greeting)))
        self.assertIsNone(rs.mysql_handshake_version(serve(b"")))
        self.assertTrue(rs.redis_ping(serve(b"+PONG\r\n", reply_to_ping=True)))


class RunFlowTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(str(self.root), ignore_errors=True))
        self.source = self.root / "candidate"
        self.out = self.root / "out"
        self.environ = {"TEST_CLASS": C01, "SOURCE_SHA": SHA, "BATCH": "core-funds", "TIMEZONE": "UTC",
                        "GITHUB_RUN_ID": "123456789", "GITHUB_RUN_ATTEMPT": "1", "PATH": "/usr/bin",
                        "CHENGYIN_FUNDS_MYSQL_IT": "1"}
        rs.merge_receipt(self.out, {"class": C01, "problems": [], "verdict": "INCOMPLETE",
                                    "storage": {"mysql": {"container": "c", "port": 40001,
                                                          "schemas_before": ["mysql", "sys"]}}})
        self.seen = {}

    def maven(self, cases):
        def run_maven(source_dir, module, selectors, env, log_path):
            self.seen.update(selectors=selectors, env=env)
            reports = pathlib.Path(source_dir, module, "target", "surefire-reports")
            reports.mkdir(parents=True, exist_ok=True)
            write_suite(reports, C01, cases)
            pathlib.Path(log_path).write_text("[INFO] --- maven-surefire-plugin:3.2.5:test (default-test)\n",
                                              encoding="utf-8")
            return 0
        return run_maven

    def run_class(self, cases, schemas_after=("mysql", "sys")):
        Patch(self, run_maven=self.maven(cases), mysql_schemas=lambda name: list(schemas_after),
              first_line=lambda command: "stub")
        code = rs.main(["run", "--source", str(self.source), "--out", str(self.out)], self.environ)
        return code, rs.read_receipt(self.out)

    def test_pass_with_nested_selected_and_only_own_gate(self):
        code, receipt = self.run_class(c01_cases())
        self.assertEqual(0, code)
        self.assertEqual("PASS", receipt["verdict"])
        self.assertIn(C01 + "$RealMysqlRepeatableRead", self.seen["selectors"])
        self.assertEqual("true", self.seen["env"]["CHENGYIN_RUN_OWN_C01_51_MYSQL"])
        self.assertNotIn("CHENGYIN_FUNDS_MYSQL_IT", self.seen["env"])
        self.assertEqual("<explicit-empty>", receipt["env_profile"]["CHENGYIN_TEST_MYSQL_PASSWORD"])
        self.assertEqual("3.2.5", receipt["toolchain"]["surefire"])
        self.assertEqual("CLEAN", receipt["cleanup"]["status"])
        self.assertTrue((self.out / "surefire-reports").is_dir())
        raw = self.out / "surefire-reports" / ("TEST-%s.xml" % C01)
        self.assertEqual({raw.name: __import__('hashlib').sha256(raw.read_bytes()).hexdigest()},
                         receipt['surefire_xml_sha256'])

    def test_fail_when_nested_dropped_and_leftover_recorded(self):
        code, receipt = self.run_class(c01_cases(nested=False), schemas_after=("mysql", "sys", "cyit_leak"))
        self.assertEqual(1, code)
        self.assertEqual("FAIL", receipt["verdict"])
        self.assertEqual(["cyit_leak"], receipt["cleanup"]["mysql_leftover_schemas"])

    def test_cleanup_query_failure_keeps_results(self):
        def gone(name):
            raise rs.GateError("container gone")
        Patch(self, run_maven=self.maven(c01_cases()), mysql_schemas=gone, first_line=lambda command: "stub")
        self.assertEqual(1, rs.main(["run", "--source", str(self.source), "--out", str(self.out)], self.environ))
        receipt = rs.read_receipt(self.out)
        self.assertEqual("FAIL", receipt["verdict"])
        self.assertEqual(5, receipt["executed"]["tests"])
        self.assertEqual("UNKNOWN", receipt["cleanup"]["status"])
        self.assertEqual("container gone", receipt["cleanup"]["error"])

    def test_leftover_fails_even_when_every_case_passes(self):
        code, receipt = self.run_class(c01_cases(), schemas_after=("mysql", "sys", "cyit_leak"))
        self.assertEqual(1, code)
        self.assertEqual("FAIL", receipt["verdict"])
        self.assertEqual(5, receipt["executed"]["passed"])
        self.assertEqual("LEFTOVER", receipt["cleanup"]["status"])
        self.assertEqual(["cyit_leak"], receipt["cleanup"]["mysql_leftover_schemas"])

    def test_missing_schema_readback_fails_and_preserves_xml_results(self):
        code, receipt = self.run_class(c01_cases(), schemas_after=())
        self.assertEqual(1, code)
        self.assertEqual("FAIL", receipt["verdict"])
        self.assertEqual(5, receipt["executed"]["passed"])
        self.assertEqual("UNKNOWN", receipt["cleanup"]["status"])
        self.assertTrue((self.out / "surefire-reports").is_dir())

    def test_surefire_version_reads_maven_39_lines_for_the_target_module(self):
        log = ("[INFO] --- surefire:3.5.2:test (default-test) @ chengyinhub-common ---\n"
               "[INFO] --- surefire:3.6.0:test (default-test) @ chengyinhub-system ---\n")
        self.assertEqual("3.6.0", rs.surefire_version(log, "chengyinhub-system"))
        self.assertEqual("3.5.2", rs.surefire_version(log, "chengyinhub-common"))
        legacy = "[INFO] --- maven-surefire-plugin:3.0.0-M5:test (default-test) @ chengyinhub-system ---\n"
        self.assertEqual("3.0.0-M5", rs.surefire_version(legacy, "chengyinhub-system"))
        self.assertIsNone(rs.surefire_version("[INFO] --- compiler:3.8.1:testCompile (default) @ x ---\n", "x"))

    def test_refuses_to_run_after_failed_precheck(self):
        rs.fail_receipt(self.out, "precheck", ["count drift"])
        Patch(self, run_maven=lambda *args: self.fail("maven must not run"))
        self.assertEqual(1, rs.main(["run", "--source", str(self.source), "--out", str(self.out)], self.environ))
        self.assertEqual("FAIL", rs.read_receipt(self.out)["verdict"])

    def test_teardown_is_recorded(self):
        names = [{"c"}, set()]
        Patch(self, docker=lambda *args, check=True: "", container_names=lambda: names.pop())
        self.assertEqual(0, rs.main(["storage-stop", "--out", str(self.out)], self.environ))
        self.assertEqual({"mysql": {"container": "c", "removed": True}}, rs.read_receipt(self.out)["teardown"])


def workflow_problems(text, reg):
    problems = []
    events = re.search(r"^on:\n([\s\S]*?)(?=^\S)", text, re.MULTILINE)
    if events is None or re.findall(r"^  ([\w-]+):", events.group(1), re.MULTILINE) != ["workflow_dispatch"]:
        problems.append("must be manual-only (workflow_dispatch)")
    batch = re.search(r"^      batch:\n[\s\S]*?options:\n((?:          - .+\n)+)", text, re.MULTILINE)
    options = re.findall(r"- (\S+)", batch.group(1)) if batch else []
    if options != list(reg["batches"]):
        problems.append("batch choices %s != registry %s" % (options, list(reg["batches"])))
    if "\npermissions:\n  contents: read\n" not in text or re.search(r":\s*write\b", text):
        problems.append("permissions must be contents: read only")
    if "secrets." in text:
        problems.append("must not use repository secrets")
    if "if: github.repository == 'liboyang42-cpu/hengyin'" not in text:
        problems.append("plan job must be guarded to the public repository")
    runs_on = re.findall(r"^\s+runs-on:\s*(.+)$", text, re.MULTILINE)
    if not runs_on or any(value.strip() != "ubuntu-latest" for value in runs_on):
        problems.append("every job must run on GitHub-hosted ubuntu-latest")
    if len(re.findall(r"^    timeout-minutes: \d+$", text, re.MULTILINE)) != len(runs_on):
        problems.append("every job needs timeout-minutes")
    matrix_jobs = [block for block in re.findall(r"^  [A-Za-z0-9_]+:\n([\s\S]*?)(?=^  [A-Za-z0-9_]+:|\Z)", text, re.MULTILINE)
                   if re.search(r"^      matrix:\s*$", block, re.MULTILINE)]
    if not matrix_jobs or any("      max-parallel: 20\n" not in block or "      fail-fast: false\n" not in block
                              for block in matrix_jobs):
        problems.append("every matrix must keep max-parallel: 20 and fail-fast: false")
    steps = re.split(r"\n(?=      - )", text)
    for step in steps:
        if "uses: actions/checkout@" in step and "persist-credentials: false" not in step:
            problems.append("checkout without persist-credentials: false")
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)(?:- )?run:\s*(.*)$", line)
        if not match:
            continue
        body = [match.group(2)]
        if match.group(2).strip() in ("|", ">", "|-", ">-"):
            indent = len(match.group(1))
            for following in lines[index + 1:]:
                if following.strip() and len(following) - len(following.lstrip()) <= indent:
                    break
                body.append(following)
        if any("${{" in part for part in body):
            problems.append("expression interpolated into a run script: %s" % line.strip())
    return problems


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_contract(self):
        self.assertEqual([], workflow_problems(self.text, registry()))

    def test_negative_controls(self):
        mutations = {
            "automatic push": self.text.replace("on:\n", "on:\n  push:\n    branches: [master]\n", 1),
            "pull_request": self.text.replace("on:\n", "on:\n  pull_request:\n", 1),
            "input in script": self.text.replace(
                "run: python3 ci/real_storage_it.py plan --source candidate",
                "run: python3 ci/real_storage_it.py plan --source candidate --classes '${{ inputs.classes }}'", 1),
            "credential persisted": self.text.replace("          persist-credentials: false\n", "", 1),
            "self-hosted": self.text.replace("runs-on: ubuntu-latest", "runs-on: [self-hosted, chengyin]", 1),
            "parallel cap": self.text.replace("max-parallel: 20", "max-parallel: 50", 1),
            "batch choice drift": self.text.replace("          - extra-storage\n", "", 1),
            "batch choice order": self.text.replace("          - payment-intent-correctness\n          - core-funds\n",
                                                    "          - core-funds\n          - payment-intent-correctness\n", 1),
            "secrets": self.text.replace("TIMEZONE: ${{ inputs.timezone }}",
                                         "TIMEZONE: ${{ secrets.DB_PASSWORD }}", 1),
            "write permission": self.text.replace("  contents: read\n", "  contents: write\n", 1),
        }
        for label, mutated in mutations.items():
            with self.subTest(label):
                self.assertNotEqual(self.text, mutated, "negative control could not mutate: " + label)
                self.assertTrue(workflow_problems(mutated, registry()), label)



class M1PairTest(unittest.TestCase):
    """Gate-only synthetic receipts/HEAD stubs. These are not Java/MySQL/canonical evidence."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.env = {"SOURCE_SHA": SHA, "OLD_SOURCE_SHA": "a" * 40, "BATCH": rs.M1_PAIR_BATCH,
                    "CLASSES": "", "TIMEZONE": "UTC", "GITHUB_RUN_ID": "40050022063", "GITHUB_RUN_ATTEMPT": "2",
                    "GITHUB_SHA": "b" * 40, "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
                    "PAIR_STORAGE_RESULT": "success", "PAIR_SUMMARY_RESULT": "success"}
        self.old, self.new = self.root / "old", self.root / "new"

    def receipts(self):
        records = []
        suffix = rs.database_suffix(self.env["GITHUB_RUN_ID"], self.env["GITHUB_RUN_ATTEMPT"])
        for side, directory, sha in (("old", self.old, self.env["OLD_SOURCE_SHA"]), ("new", self.new, SHA)):
            for fqcn, count in rs.M1_PAIR_CLASSES.items():
                record = {"schema": rs.RECEIPT_SCHEMA, "class": fqcn, "source_sha": sha, "batch": rs.M1_PAIR_BATCH,
                          "workflow": rs.workflow_facts(self.env), "verdict": "PASS", "maven": {"exit_code": 0},
                          "precheck": {"candidate": {"sha": sha}}, "cleanup": {"status": "CLEAN", "mysql_leftover_schemas": []},
                          "env_profile": {"CHENGYIN_MYBATIS_IT_DB_SUFFIX": suffix, "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX": suffix},
                          "executed": {"tests": count, "passed": count, "failures": 0, "errors": 0, "skipped": 0,
                                       "testcases": [{"classname": fqcn, "name": "synthetic%d" % i, "status": "passed"} for i in range(count)]}}
                path = directory / rs.simple_name(fqcn) / "receipt.json"
                records.append((path, record))
        return records

    def verify(self, mutate=None, env=None):
        records = self.receipts()
        if mutate:
            mutate(records)
        for path, record in records:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(record))
        return rs.verify_m1_pair_receipts(registry(), self.old, self.new, env or self.env)

    def test_pair_request_requires_two_distinct_complete_sources(self):
        self.assertEqual({"old": "a" * 40, "new": SHA}, rs.m1_pair_request(registry(), self.env))
        for change in ({"OLD_SOURCE_SHA": ""}, {"OLD_SOURCE_SHA": SHA}, {"SOURCE_SHA": "branch"},
                       {"OLD_SOURCE_SHA": "a" * 39}, {"OLD_SOURCE_SHA": "a" * 40 + "\n"}):
            with self.subTest(change=change), self.assertRaises(rs.GateError):
                rs.m1_pair_request(registry(), dict(self.env, **change))

    def test_pair_rejects_other_batches_subsets_and_timezones(self):
        for change in ({"BATCH": "architecture-day-topic-copy"}, {"CLASSES": "PlayHintUnlockMySqlIT"},
                       {"CLASSES": "PlayHintUnlockMySqlIT,PlayLeadAdmissionFailureMySqlIT"}, {"TIMEZONE": "invalid"}):
            with self.subTest(change=change), self.assertRaises(rs.GateError):
                rs.m1_pair_request(registry(), dict(self.env, **change))

    def test_pair_rejects_registry_class_count_storage_or_gate_drift(self):
        cls = next(iter(rs.M1_PAIR_CLASSES))
        for field, value in (("tests", 11), ("nested", ["Hidden"]), ("storage", ["mysql", "redis"]), ("gates", {"CHENGYIN_BAD": "1"})):
            reg = copy.deepcopy(registry()); reg["classes"][cls][field] = value
            with self.subTest(field=field), self.assertRaises(rs.GateError):rs.m1_pair_request(reg, self.env)
        for members in ([cls], [cls, cls], [cls, C01]):
            reg = copy.deepcopy(registry()); reg["batches"][rs.M1_PAIR_BATCH]["classes"] = members
            with self.assertRaises(rs.GateError):rs.m1_pair_request(reg, self.env)

    def plan_args(self, source=None):
        return ["m1-pair-plan", "--source", str(source or _HERE.parent), "--old-source", str(source or _HERE.parent),
                "--out", str(self.root / "out"), "--github-output", str(self.root / "outputs")]

    def test_pair_plan_reuses_real_source_precheck_and_binds_four_jobs(self):
        # Only Git HEAD metadata is stubbed; actual current source/static gates are read.
        from unittest.mock import patch
        import types
        with patch.object(rs.subprocess, "run", side_effect=[types.SimpleNamespace(stdout="a" * 40 + "\n"), types.SimpleNamespace(stdout=SHA + "\n")]):
            self.assertEqual(0, rs.main(self.plan_args(), self.env))
        outputs = dict(line.split("=", 1) for line in (self.root / "outputs").read_text().splitlines())
        matrix, sides, planned = (json.loads(outputs[key]) for key in ("matrix", "sides", "planned"))
        self.assertEqual(4, len(matrix)); self.assertEqual(2, len(planned)); self.assertEqual(2, len(sides))
        self.assertEqual({(side, sha, cls) for side, sha in (("old", "a" * 40), ("new", SHA)) for cls in rs.M1_PAIR_CLASSES},
                         {(item["side"], item["source_sha"], item["class"]) for item in matrix})
        proof = json.loads((self.root / "out/pair-plan.json").read_text())
        self.assertEqual(14, proof["tests_per_side"]); self.assertEqual(matrix, proof["matrix"])

    def test_pair_plan_wrong_head_publishes_no_matrix(self):
        from unittest.mock import patch
        import types
        for heads in ([SHA], ["a" * 40, "a" * 40]):
            with self.subTest(heads=heads), patch.object(rs.subprocess, "run", side_effect=[types.SimpleNamespace(stdout=head + "\n") for head in heads]):
                self.assertEqual(1, rs.main(self.plan_args(), self.env))
            self.assertFalse((self.root / "outputs").exists()); self.assertFalse((self.root / "out/pair-plan.json").exists())

    def test_pair_plan_missing_old_and_real_dynamic_tests_fail_before_matrix(self):
        self.assertEqual(1, rs.main(self.plan_args(), dict(self.env, OLD_SOURCE_SHA="")))
        from unittest.mock import patch
        import types
        source = self.root / "dynamic-source"
        (source / "pom.xml").parent.mkdir(parents=True)
        (source / "pom.xml").write_text("<project><java.version>21</java.version></project>")
        for cls, count in rs.M1_PAIR_CLASSES.items():
            path = source / "chengyinhub-admin/src/test/java" / (cls.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("class Sample {" + "".join("@Test void t%d(){}" % i for i in range(count)) + "@ParameterizedTest void hidden(){} }")
        with patch.object(rs.subprocess, "run", return_value=types.SimpleNamespace(stdout="a" * 40 + "\n")):
            self.assertEqual(1, rs.main(self.plan_args(source), self.env))
        self.assertFalse((self.root / "outputs").exists())

    def test_ordinary_plan_outputs_remain_exact_original_matrix_and_sha12(self):
        from unittest.mock import patch
        import types
        args = ["plan", "--source", str(_HERE.parent), "--github-output", str(self.root / "outputs")]
        with patch.object(rs.subprocess, "run", return_value=types.SimpleNamespace(stdout=SHA + "\n")):
            self.assertEqual(0, rs.main(args, dict(self.env, OLD_SOURCE_SHA="")))
        outputs = dict(line.split("=", 1) for line in (self.root / "outputs").read_text().splitlines())
        self.assertEqual({"matrix", "sha12"}, set(outputs)); self.assertEqual(SHA[:12], outputs["sha12"])
        self.assertEqual(rs.plan(registry(), SHA, rs.M1_PAIR_BATCH, "", "UTC"), json.loads(outputs["matrix"]))
        self.assertTrue(all(set(item) == {"class", "simple"} for item in json.loads(outputs["matrix"])))

    def test_pair_provenance_success_is_explicitly_not_canonical_verdict(self):
        result = self.verify()
        self.assertEqual("PASS", result["verdict"]); self.assertEqual(4, len(result["receipts"]))
        self.assertTrue(result["not_canonical_verdict"]); self.assertEqual("_gh022063a2", result["suffix"])

    def test_pair_rejects_cross_source_precheck_batch_or_missing_old(self):
        for field, value in (("source_sha", SHA), ("batch", "core-funds"), ("schema", "other")):
            with self.subTest(field=field), self.assertRaises(rs.GateError):
                self.verify(lambda rows: rows[0][1].update({field: value}))
        with self.assertRaises(rs.GateError):self.verify(lambda rows: rows[0][1]["precheck"]["candidate"].update(sha=SHA))
        with self.assertRaises(rs.GateError):self.verify(lambda rows: rows[-1][1].update(source_sha=self.env["OLD_SOURCE_SHA"]))
        with self.assertRaises(rs.GateError):self.verify(env=dict(self.env, OLD_SOURCE_SHA=""))

    def test_pair_rejects_cross_run_attempt_workflow_or_repository(self):
        for field, value in (("run_id", "40050022064"), ("run_attempt", "1"), ("workflow_sha", "c" * 40), ("repository", "other/repo")):
            with self.subTest(field=field), self.assertRaises(rs.GateError):
                self.verify(lambda rows: rows[0][1]["workflow"].update({field: value}))
        for change in ({"GITHUB_RUN_ID": ""}, {"GITHUB_RUN_ATTEMPT": "0"}, {"GITHUB_SHA": "branch"}, {"GITHUB_REPOSITORY": "private/other"}):
            with self.assertRaises(rs.GateError):self.verify(env=dict(self.env, **change))

    def test_pair_rejects_any_suffix_override(self):
        for key in ("CHENGYIN_MYBATIS_IT_DB_SUFFIX", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX"):
            with self.assertRaises(rs.GateError):self.verify(lambda rows: rows[0][1]["env_profile"].update({key: "_gh022064a2"}))

    def test_pair_rejects_nonclean_maven_failure_skip_count_or_duplicate_cases(self):
        changes = [lambda r: r.update(verdict="FAIL"), lambda r: r["cleanup"].update(status="UNKNOWN"),
                   lambda r: r["cleanup"].update(mysql_leftover_schemas=["leftover"]), lambda r: r["cleanup"].pop("mysql_leftover_schemas"),
                   lambda r: r["maven"].update(exit_code=1), lambda r: r["executed"].update(skipped=1),
                   lambda r: r["executed"].update(tests=9), lambda r: r["executed"]["testcases"][0].update(status="skipped"),
                   lambda r: r["executed"]["testcases"].__setitem__(0, r["executed"]["testcases"][1])]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(rs.GateError):self.verify(lambda rows: change(rows[0][1]))

    def test_pair_rejects_missing_duplicate_or_foreign_class_artifact(self):
        with self.assertRaises(rs.GateError):self.verify(lambda rows: rows.pop())
        # Fresh paths from the previous partial attempt are overwritten by verify().
        with self.assertRaises(rs.GateError):self.verify(lambda rows: rows[0][1].update({"class": rows[1][1]["class"]}))
        with self.assertRaises(rs.GateError):self.verify(lambda rows: rows[0][1].update({"class": C01}))
        extra = self.old / "unexpected/receipt.json"; extra.parent.mkdir(parents=True); extra.write_text("{}")
        with self.assertRaises(rs.GateError):self.verify()

    def test_failed_source_summary_or_storage_prevents_provenance_pass(self):
        for key in ("PAIR_STORAGE_RESULT", "PAIR_SUMMARY_RESULT"):
            with self.assertRaises(rs.GateError):self.verify(env=dict(self.env, **{key: "failure"}))

    def test_pair_verify_cli_fails_closed_and_preserves_failure_artifact(self):
        out = self.root / "pair-provenance.json"
        self.assertEqual(1, rs.main(["m1-pair-verify", "--old-receipts", str(self.old), "--new-receipts", str(self.new), "--out", str(out)], self.env))
        self.assertEqual("FAIL", json.loads(out.read_text())["verdict"])


def m1_workflow_problems(text):
    required = ["      old_source_sha:\n", "if: inputs.old_source_sha == ''", "id: pair_plan",
                "run: python3 ci/real_storage_it.py m1-pair-plan --source candidate --old-source old-candidate",
                "SOURCE_SHA: ${{ matrix.source_sha || inputs.source_sha }}", "VALIDATED_SOURCE_SHA: ${{ matrix.source_sha || inputs.source_sha }}",
                "ref: ${{ matrix.source_sha || inputs.source_sha }}", "pair_summary:\n",
                "include: ${{ fromJSON(needs.plan.outputs.pair_sides) }}", "PLANNED: ${{ needs.plan.outputs.pair_planned }}",
                "pattern: rs-m1-${{ matrix.side }}-${{ matrix.source_sha }}-*-a${{ github.run_attempt }}",
                "name: rs-m1-summary-${{ matrix.side }}-${{ matrix.source_sha }}-a${{ github.run_attempt }}",
                "format('rs-m1-{0}-{1}-{2}-a{3}', matrix.side, matrix.source_sha, matrix.simple, github.run_attempt)",
                "needs: [plan, storage_it, pair_summary]", "PAIR_SUMMARY_RESULT: ${{ needs.pair_summary.result }}",
                "run: python3 gate/ci/real_storage_it.py m1-pair-verify --old-receipts old-receipts --new-receipts new-receipts",
                "ref: f671227c0bd483b290a72c04ca2ed60bb5f7748e", "evidence/docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/compare_receipts.py",
                "format('rs-class-{0}-a{1}', matrix.simple, github.run_attempt)",
                "python3 evidence/docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/test_compare_receipts.py",
                "python3 evidence/docs/delivery/architecture-day/C/evidence/wp09-m1-comparator-patches/test_member_fk_review.py",
                "pattern: rs-class-*-a${{ github.run_attempt }}", "PLANNED: ${{ needs.plan.outputs.matrix }}"]
    problems = ["missing M1 workflow contract: " + item for item in required if item not in text]
    for job, mode in (("summary", "=="), ("pair_summary", "!="), ("compare", "!=")):
        block = re.search(r"^  " + job + r":\n([\s\S]*?)(?=^  \w+:|\Z)", text, re.MULTILINE)
        if not block or "inputs.old_source_sha " + mode + " ''" not in block.group(1):problems.append("wrong mode guard: " + job)
    if re.search(r"^\s+(?:GITHUB_RUN_ID|GITHUB_RUN_ATTEMPT|CHENGYIN_MYBATIS_IT_DB_SUFFIX):", text, re.MULTILINE):problems.append("original run/suffix overridden")
    return problems


class M1PairWorkflowTest(unittest.TestCase):
    def test_each_matrix_keeps_its_own_parallel_limit(self):
        text = WORKFLOW.read_text()
        prefix, pair = text.split("  pair_summary:\n", 1)
        for old, new in [("      max-parallel: 20\n", "      max-parallel: 50\n"),
                         ("      max-parallel: 20\n", ""),
                         ("      fail-fast: false\n", "      fail-fast: true\n")]:
            with self.subTest(old=old, new=new):
                mutated = prefix + "  pair_summary:\n" + pair.replace(old, new, 1)
                self.assertNotEqual(text, mutated)
                self.assertTrue(workflow_problems(mutated, registry()))

    def test_pair_workflow_and_original_manual_gates(self):
        text = WORKFLOW.read_text()
        self.assertEqual([], workflow_problems(text, registry()))
        self.assertEqual([], m1_workflow_problems(text))

    def test_pair_artifact_source_attempt_freeze_and_mode_negative_controls(self):
        text = WORKFLOW.read_text()
        for old, new in [("matrix.source_sha }}-*-a${{ github.run_attempt }}", "matrix.source_sha }}-*"),
                         ("ref: f671227c0bd483b290a72c04ca2ed60bb5f7748e", "ref: master"),
                         ("python3 evidence/docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/test_compare_receipts.py", "true"),
                         ("python3 evidence/docs/delivery/architecture-day/C/evidence/wp09-m1-comparator-patches/test_member_fk_review.py", "true"),
                         ("VALIDATED_SOURCE_SHA: ${{ matrix.source_sha || inputs.source_sha }}", "VALIDATED_SOURCE_SHA: ${{ inputs.source_sha }}"),
                         ("inputs.old_source_sha == ''", "inputs.old_source_sha != ''"),
                         ("PAIR_SUMMARY_RESULT: ${{ needs.pair_summary.result }}", "PAIR_SUMMARY_RESULT: success"),
                         ("pattern: rs-class-*-a${{ github.run_attempt }}", "pattern: rs-m1-*")]:
            with self.subTest(old=old):
                mutated = text.replace(old, new)
                self.assertNotEqual(text, mutated); self.assertTrue(m1_workflow_problems(mutated))


if __name__ == "__main__":
    unittest.main()
