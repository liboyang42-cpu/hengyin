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
    def test_registry_is_valid(self):
        self.assertEqual([], rs.validate_registry(registry()))

    def test_batch_totals_match_the_remaining_receipt(self):
        # 23 条真库 + OWN_C01_51 外层 4 条普通；AI 15+7；E04-07 5+5（原回执41/43新增回归）；E02-22 9 + E02-25 2 + 三夹具 3；B05 3；F3 12（含死锁重试负控）。
        # 类条数全局唯一，按 master 侧登记：冲回 3→4、券生命周期 12→16、分享快照并发 2→3（extra-storage 71→76）。
        # 漏斗最大年份修复 4（含 TIMESTAMPADD 负控，master）。
        # port-prelaunch-69(W8)：退款通知 9 + 退款重试余量 5 + 漫游定位留存 21。
        # p0-acceptance-ac1(AC1)：核销 5 + 跨账号 3 + 城市跑 2 + 自由探索供给闸 2 + 注销 1。
        # port-*：同批 master 侧移植，类与条数同原批；port-core-funds 27；port-ai-quota 22；port-f3-growth 12；port-f3-evidence 4。
        expected = {"core-funds": 27, "ai-quota": 22, "e04-07-coupon": 10, "community-e02": 14,
                    "b05-visibility": 3, "f3-growth": 12, "extra-storage": 131,
                    "port-e04-07-coupon": 10, "port-community-e02": 14, "port-b05-visibility": 3,
                    "port-extra-storage": 131,
                    "port-core-funds": 27, "port-ai-quota": 22,
                    "port-f3-growth": 12, "port-f3-evidence": 4,
                    "funnel-max-date": 4, "port-prelaunch-69": 35,
                    "p0-acceptance-ac1": 13, "architecture-day-batch1": 36,
                    "architecture-day-wave2": 44, "architecture-day-photo": 15,
                    "architecture-day-broadcast-migration": 10, "architecture-day-settlement": 7,
                    "architecture-day-state-recovery": 50,
                    "architecture-day-runtime-governance": 36,
                    "architecture-day-registration-write": 109,
                    "architecture-day-im-revocation": 2,
                    "architecture-day-w19-owner-notice": 29,
                    "architecture-day-play-scheduler": 16,
                    "architecture-day-cash-expand": 30,
                    "architecture-day-ai-not-sent": 4,
                    "architecture-day-lead-admission": 14}
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
            "com.chengyinhub.business.it.TemplateAdoptionRoundTripMySqlIT": (4, None),
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
            "com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest": (94, {"CHENGYIN_FUNDS_MYSQL_IT": "1"}),
            "com.chengyinhub.business.it.MerchantRegistrationCancellationMySqlIT": (15, {}),
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
    if "      max-parallel: 20\n" not in text or "      fail-fast: false\n" not in text:
        problems.append("matrix must keep max-parallel: 20 and fail-fast: false")
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
            "secrets": self.text.replace("TIMEZONE: ${{ inputs.timezone }}",
                                         "TIMEZONE: ${{ secrets.DB_PASSWORD }}", 1),
            "write permission": self.text.replace("  contents: read\n", "  contents: write\n", 1),
        }
        for label, mutated in mutations.items():
            with self.subTest(label):
                self.assertNotEqual(self.text, mutated, "negative control could not mutate: " + label)
                self.assertTrue(workflow_problems(mutated, registry()), label)


if __name__ == "__main__":
    unittest.main()
