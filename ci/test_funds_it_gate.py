#!/usr/bin/env python3
"""funds_it_gate 的红绿负控:证明门禁能在"静默跳过/缺席/建库报错/少跑"时真的变红,不是恒绿装饰。

纯 Python,不需要 MySQL,可本机随时重放 —— 这层就是 H2 fail-closed 的可运行负控证据。
真正连 MySQL8 跑六套 IT 仍是外部动作(runner 上执行),本负控不冒充那层已验证。
"""
import importlib.util
import os
import pathlib
import socket
import subprocess
import tempfile
import threading
import unittest

_MODULE_PATH = pathlib.Path(__file__).resolve().parent / "funds_it_gate.py"
_SPEC = importlib.util.spec_from_file_location("funds_it_gate", _MODULE_PATH)
funds_it_gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(funds_it_gate)


def write_report(directory, short, tests, skipped=0, failures=0, errors=0):
    fqcn = "com.chengyinhub.business.service." + short
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<testsuite name="%s" tests="%d" skipped="%d" failures="%d" errors="%d"></testsuite>\n'
           % (fqcn, tests, skipped, failures, errors))
    with open(os.path.join(directory, "TEST-%s.xml" % fqcn), "w", encoding="utf-8") as handle:
        handle.write(xml)


def all_required_clean(directory):
    """EXPECTED 里的每一套都产出一份干净报告。

    2026-08-14 并入两套 Explore 真库 IT;2026-08-16 并入四 A 的四套。
    **这个 fixture 必须跟着 EXPECTED 走**:
    少写一套,test_green_when_all_required_ran_fully 会红,而那正是它该有的行为
    (门禁新增一套却没人跑 = fail-closed)。别为了让它绿而去删 EXPECTED 里的条目。
    """
    write_report(directory, "CoopListReadOnlyMybatisIntegrationTest", 5)
    write_report(directory, "RegistrationFundsMybatisIntegrationTest", 101)
    write_report(directory, "RegistrationEquityMybatisIntegrationTest", 6)
    write_report(directory, "RefundPayoutMybatisIntegrationTest", 30)
    write_report(directory, "ExploreAllianceMvpRealDbIT", 5)
    write_report(directory, "ExploreAllianceRedeemRealDbIT", 4)
    write_report(directory, "ExploreScanReplayRealDbIT", 4)
    write_report(directory, "TandianriBatch0FundsMybatisIntegrationTest", 17)
    write_report(directory, "ShopDayOneTicketMySqlIT", 5)
    write_report(directory, "TandianriBatch3FundsMybatisIntegrationTest", 18)
    write_report(directory, "ClubCorporatePayeeMybatisIntegrationTest", 14)
    write_report(directory, "ClubEditionCompensationMybatisIntegrationTest", 21)
    write_report(directory, "PlayerWithdrawalCooldownMybatisIntegrationTest", 16)
    write_report(directory, "ClubPayableMybatisIntegrationTest", 20)
    write_report(directory, "CouponLifecycleMybatisIntegrationTest", 16)
    write_report(directory, "CommunityPostCommentIdempotencyMySqlIT", 2)
    write_report(directory, "MysqlItSchemaIsolationTest", 3)
    # 2026-09-15 起登记:R9-16 免费单主办取消真库复现 IT(8 条)。
    write_report(directory, "ActivityCancelFreeOrderMybatisIT", 8)
    write_report(directory, "PlayTeamMybatisIntegrationTest", 21)
    write_report(directory, "PlayTeamNearbyMybatisIntegrationTest", 4)
    # 2026-09-16:支付回调 fencing IT 入闸(5 条);fixture 必须跟着 EXPECTED 走。
    write_report(directory, "WxPaymentCallbackFencingMySqlIT", 6)
    # 2026-09-16 第二轮:内容回调 fencing IT 入闸(3 条),与上面同病同修。
    write_report(directory, "WxContentCallbackFencingMySqlIT", 3)
    # 2026-09-16 晚:暗测试 23 套(197 条)首跑判绿后入闸;计数与 EXPECTED/SOURCE_FILES 同源。
    for short, tests in (
        ("CoopDepositRefundMybatisIntegrationTest", 36),
        ("GrowthMissionProofMybatisIntegrationTest", 25),
        ("MerchantIdentityMybatisIntegrationTest", 21),
        ("MerchantProfilePersistenceMybatisIntegrationTest", 9),
        ("StoreVerifyGrowthDeliveryTransactionTest", 4),
        ("RefundNotificationMybatisIntegrationTest", 9),
        ("SessionRefundClockMybatisIntegrationTest", 3),
        ("SettlementGenerateBatchMybatisIntegrationTest", 3),
        ("FirstReviewPointsClawbackMybatisIntegrationTest", 10),
        ("ClubSettlementSummaryMybatisIntegrationTest", 6),
        ("CoopComplaintCloseMybatisIntegrationTest", 8),
        ("RegistrationReadContractMybatisIntegrationTest", 6),
        ("ClubWaitlistTokenMybatisIntegrationTest", 7),
        ("CmsMemberTemplateAudioPatchMybatisIntegrationTest", 9),
        ("CmsTemplateLibraryAudioPatchMybatisIntegrationTest", 9),
        ("TemplateUseNumFirstAdoptionMybatisIntegrationTest", 3),
        ("ContentInteractionMybatisIntegrationTest", 18),
        ("CreativeSquarePublishIdempotencyMybatisIntegrationTest", 7),
        ("FollowConcurrencyMybatisIntegrationTest", 4),
        ("FollowEditRaceMybatisIntegrationTest", 7),
        ("NearbyRunnerShopsMybatisIntegrationTest", 7),
        # 2026-09-17 release-0917 集成:rule-play-0917 把 funds_it_gate.EXPECTED 5→6(新增存量待审转正真库用例),
        #   自测夹具未同步 → test_green_when_all_required_ran_fully 红;按同源口径同步为 6。
        ("PhotoReviewProgressMybatisIntegrationTest", 6),
        ("SpoilerAccessMybatisIntegrationTest", 7),
        ("SquareEditFollowMybatisIntegrationTest", 7),
        ("RoamSessionWriteMybatisIntegrationTest", 15),
        ("RoamLocationRetentionMySqlIT", 21),
        ("ComplaintPeriodWithdrawalGateMybatisIntegrationTest", 7),
        ("ShareSnapshotRunSessionMybatisIntegrationTest", 5),
        ("PlayerRunSessionOrderingMybatisIntegrationTest", 6),
        ("RoamShareSnapshotConcurrencyMySqlIT", 3),
        ("ClubEventTransferCancellationMySqlIT", 3),
        ("RefundRetryAndRemainderMySqlIT", 10),
        ("SignupNoticeAfterCommitPersistenceMySqlIT", 18),
        ("TopicLikeDesiredStateMySqlIT", 3),
        ("MonitorMissingJobsMySqlIT", 3),
        ("MemberPasswordWidthMySqlIT", 3),
        ("LegacyCategoryForwardGuardMySqlIT", 4),
        ("MissingBusinessSeedsMySqlIT", 4),
        ("RoamPresenceRevokeDurabilityMySqlIT", 10),
        ("RoamPresenceRevokeJobMySqlIT", 4),
        ("WxBillIngestionPersistenceTest", 6),
    ):
        write_report(directory, short, tests)


class FundsItGateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_bill_ingestion_missing_or_skipped_reports_are_not_a_pass(self):
        all_required_clean(self.dir)
        for report in pathlib.Path(self.dir).glob("TEST-*WxBillIngestionPersistenceTest.xml"):
            report.unlink()
        self.assertTrue(any("WxBillIngestionPersistenceTest" in error
                            for error in funds_it_gate.enforce(self.dir)))
        write_report(self.dir, "WxBillIngestionPersistenceTest", 6, skipped=6)
        self.assertTrue(any("WxBillIngestionPersistenceTest" in error
                            for error in funds_it_gate.enforce(self.dir)))

    def test_green_when_all_required_ran_fully(self):
        all_required_clean(self.dir)
        self.assertEqual([], funds_it_gate.enforce(self.dir))

    def test_red_when_newly_added_explore_suite_is_skipped(self):
        # 负控:并入 EXPECTED 的两套 Explore IT 一旦被 skip,必须和资金三套一样判红。
        # 它们原本就是靠 assumeTrue(false) 在库不可达时整套 skip 而"绿"过来的,
        # 这条测试就是钉死那条路已经被堵上。
        all_required_clean(self.dir)
        write_report(self.dir, "ExploreAllianceMvpRealDbIT", 5, skipped=5)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("ExploreAllianceMvpRealDbIT" in p and "skipped" in p for p in problems),
                        "Explore IT 被 skip 必须判红,否则等于把假绿放回来了:%s" % problems)

    def test_red_when_run_ordering_suite_is_missing(self):
        all_required_clean(self.dir)
        for report in pathlib.Path(self.dir).glob("TEST-*PlayerRunSessionOrderingMybatisIntegrationTest.xml"):
            report.unlink()
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("PlayerRunSessionOrderingMybatisIntegrationTest" in p
                            and "无 surefire 报告" in p for p in problems), problems)

    def test_red_when_run_ordering_suite_is_skipped(self):
        all_required_clean(self.dir)
        write_report(self.dir, "PlayerRunSessionOrderingMybatisIntegrationTest", 6, skipped=6)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("PlayerRunSessionOrderingMybatisIntegrationTest" in p
                            and "skipped" in p for p in problems), problems)

    def test_retention_suite_rejects_missing_skipped_errors_failures_and_short_runs(self):
        short = "RoamLocationRetentionMySqlIT"
        for defect, counts, expected in (
            ("missing", None, "无 surefire 报告"),
            ("skipped", dict(tests=21, skipped=1), "skipped"),
            ("error", dict(tests=21, errors=1), "errors"),
            ("failure", dict(tests=21, failures=1), "failures"),
            ("short", dict(tests=20), "实跑用例"),
        ):
            with self.subTest(defect=defect):
                all_required_clean(self.dir)
                if counts is None:
                    for report in pathlib.Path(self.dir).glob("TEST-*" + short + ".xml"):
                        report.unlink()
                else:
                    write_report(self.dir, short, **counts)
                problems = funds_it_gate.enforce(self.dir)
                self.assertTrue(any(short in problem and expected in problem for problem in problems), problems)

    def test_red_when_a_suite_is_skipped(self):
        # 负控①:环境变量缺失 → @EnabledIf 把整套标 skipped,构建仍绿,门禁必须变红。
        write_report(self.dir, "RegistrationFundsMybatisIntegrationTest", 59, skipped=59)
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 6)
        write_report(self.dir, "RefundPayoutMybatisIntegrationTest", 30)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("skipped" in p for p in problems), problems)

    def test_red_when_a_suite_is_absent(self):
        # 负控②:某套压根没被选中执行(没有报告)→ 缺席也是 fail-closed。
        write_report(self.dir, "RegistrationFundsMybatisIntegrationTest", 59)
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 6)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("RefundPayoutMybatisIntegrationTest" in p and "无 surefire 报告" in p
                            for p in problems), problems)

    def test_red_when_beforeall_errors(self):
        # 负控③:runner 上 MySQL 缺前提 → @BeforeAll 建库/连库失败记为 error,门禁必须变红。
        all_required_clean(self.dir)
        write_report(self.dir, "RefundPayoutMybatisIntegrationTest", 30, errors=1)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("errors" in p for p in problems), problems)

    def test_red_when_fewer_tests_ran(self):
        # 负控④:被 -Dtest 过滤或部分方法跳过导致少跑 → 用例数不足也 fail-closed。
        write_report(self.dir, "RegistrationFundsMybatisIntegrationTest", 40)
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 6)
        write_report(self.dir, "RefundPayoutMybatisIntegrationTest", 30)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("实跑用例" in p for p in problems), problems)

    def test_red_when_failures_present(self):
        all_required_clean(self.dir)
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 6, failures=1)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("failures" in p for p in problems), problems)


_REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
_GATE_SCRIPT = _REPO_ROOT / "ci" / "funds-mysql-it-gate.sh"
_WORKFLOW = _REPO_ROOT / ".github" / "workflows" / "funds-mysql-it.yml"


class FundsItExpectedCountTest(unittest.TestCase):
    """EXPECTED 手记数字曾经错过一次(把 @TestInstance 当 @Test 多数了一遍)——
    这里直接从已登记源码正则数 @Test,断言与 EXPECTED 一致,不再靠人肉数对。
    """

    def test_expected_counts_match_source_files(self):
        self.assertEqual(set(funds_it_gate.EXPECTED), set(funds_it_gate.SOURCE_FILES),
                         "EXPECTED 与 SOURCE_FILES 必须是同一套登记")
        for short, rel_path in funds_it_gate.SOURCE_FILES.items():
            path = _REPO_ROOT / rel_path
            with open(path, encoding="utf-8") as handle:
                actual = funds_it_gate.count_test_methods(handle.read())
            self.assertEqual(funds_it_gate.EXPECTED[short], actual,
                              "%s: EXPECTED=%d 与源码实际 @Test 数=%d 不符 —— 数错了或者漏改"
                              % (short, funds_it_gate.EXPECTED[short], actual))

    def test_batch3_suite_is_registered_on_both_execution_and_readback_sides(self):
        short = "TandianriBatch3FundsMybatisIntegrationTest"
        self.assertIn(short, funds_it_gate.EXPECTED)
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))

    def test_roam_retention_suite_is_registered_with_exact_source_count_and_execution_selector(self):
        short = "RoamLocationRetentionMySqlIT"
        self.assertEqual(21, funds_it_gate.EXPECTED[short])
        source = _REPO_ROOT / funds_it_gate.SOURCE_FILES[short]
        source_text = source.read_text(encoding="utf-8")
        self.assertEqual(21, funds_it_gate.count_test_methods(source_text))
        self.assertIn("void realCleanupTaskPreservesFavoriteRouteAndNonLocationFactsAcrossReplay()", source_text)
        import re
        selector = re.search(r"-Dtest=([^\s]+)", _GATE_SCRIPT.read_text(encoding="utf-8")).group(1).split(",")
        self.assertEqual(1, selector.count(short))

    def test_roam_share_cap_suite_is_registered_on_both_execution_and_readback_sides(self):
        short = "RoamShareSnapshotConcurrencyMySqlIT"
        self.assertIn(short, funds_it_gate.EXPECTED)
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))

    def test_club_transfer_cancel_suite_is_registered_on_both_execution_and_readback_sides(self):
        short = "ClubEventTransferCancellationMySqlIT"
        self.assertIn(short, funds_it_gate.EXPECTED)
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))

    def test_coop_list_suite_is_registered_on_both_execution_and_readback_sides(self):
        short = "CoopListReadOnlyMybatisIntegrationTest"
        self.assertEqual(5, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))

    def test_every_registered_suite_is_also_on_the_execution_list(self):
        """回读清单与执行清单必须是同一套。

        只登记 EXPECTED 而忘了加进 -Dtest,那套压根不会被执行 —— 门禁看到「无 surefire 报告」
        才会红,但那时红的原因看起来像环境问题。这里直接把两侧对上,漏一个当场点名。
        """
        gate = _GATE_SCRIPT.read_text(encoding="utf-8")
        missing = [short for short in funds_it_gate.EXPECTED if short not in gate]
        self.assertEqual([], missing,
                         "这些套件登记了回读却没进 -Dtest 执行清单:%s" % missing)
        step = _WORKFLOW.read_text(encoding="utf-8").split(
            "      - name: Run funds MySQL integration gate (fail-closed on missing MySQL)\n", 1)[1].split(
            "      - name: Stop Oracle MySQL scratch\n", 1)[0]
        for flag in ("B_PAYMENT_FACT_DDL_APPROVED", "B_PAYMENT_INTENT_DDL_APPROVED",
                     "A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"):
            self.assertIn("          " + flag + ": '1'", step)
        self.assertIn("CHENGYIN_TEST_MYSQL_HOST: 127.0.0.1", step)
        self.assertIn("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX: _gh${{ github.run_id }}", step)

    def test_four_a_migrations_are_covered_by_the_funds_push_trigger(self):
        """★迁移文件名里的 funds 字样是 load-bearing。

        去掉它 ⇒ on.push.paths 的 migration_*funds*.sql 不再覆盖这份迁移
        ⇒ 改这份迁移不触发资金复验,而且零告警。这里把「文件名真的被 glob 命中」钉住。
        """
        import fnmatch
        globs = [g for g in funds_it_gate.REQUIRED_TRIGGER_PATHS if g.endswith(".sql")]
        four_a = [
            "chengyinhub-admin/sql/migration_20260816_tandianri_batch3_funds_club_payee.sql",
            "chengyinhub-admin/sql/migration_20260816_tandianri_batch3_funds_club_compensation.sql",
            "chengyinhub-admin/sql/migration_20260816_tandianri_batch3_funds_player_cooldown.sql",
            # 历史豁免登记：它决定既有已 SELLING 的 ③ 期是「显式豁免」还是「配置没落」，
            # 同样是资金域事实，改它必须触发资金复验。
            "chengyinhub-admin/sql/migration_20260816_tandianri_batch3_funds_legacy_edition_exemption.sql",
        ]
        for relative in four_a:
            self.assertTrue((_REPO_ROOT / relative).exists(), "迁移文件不存在:" + relative)
            self.assertTrue(any(fnmatch.fnmatch(relative, pattern) for pattern in globs),
                            "%s 不被任何 on.push.paths 的 SQL glob 覆盖 —— 改它不会触发资金复验" % relative)

    def test_gate_cleans_stale_surefire_reports_before_explicit_run(self):
        """本轮漏跑的套件不得被上轮 surefire XML 伪装成"已回读"。

        ★ 2026-08-19 改判据:原来钉的是字面量 "clean install -DskipTests"(两步构建里的第一步)。
        现在 gate 合成了一次 `mvn -pl chengyinhub-admin -am clean test`,clean 与 test 在**同一次调用**里
        —— 比原来更强(原来 clean 与 test 分属两次调用,中间还隔着一次 install)。
        所以判据也跟着变成:真正跑测试的那次 mvn 调用里必须带 clean。
        """
        import re as _re
        gate = _GATE_SCRIPT.read_text(encoding="utf-8")
        test_invocations = [line for line in gate.splitlines()
                            if line.startswith("mvn ") and " test " in line + " "]
        self.assertTrue(test_invocations, "找不到跑测试的 mvn 调用")
        for invocation in test_invocations:
            self.assertTrue(_re.search(r"\bclean\s+test\b", invocation),
                            "跑测试的 mvn 调用必须自带 clean,否则上轮的 surefire XML 会冒充本轮回读:"
                            + invocation)

    def test_count_test_methods_does_not_count_test_instance_or_nested_or_parameterized(self):
        # 负控:证明计数函数不会把 @TestInstance/@Nested/@ParameterizedTest 误当 @Test —— 这正是
        # EXPECTED 之前数错(50/9/30 被数成 51/10/31)的根因。
        source = "\n".join([
            "@TestInstance(TestInstance.Lifecycle.PER_CLASS)",
            "class Foo {",
            "  @Nested",
            "  class Bar {",
            "    @ParameterizedTest",
            "    void a() {}",
            "    @Test",
            "    void b() {}",
            "  }",
            "  @Test(timeout = 1000)",
            "  void c() {}",
            "}",
        ])
        self.assertEqual(2, funds_it_gate.count_test_methods(source))


class FundsItTriggerPathCoverageTest(unittest.TestCase):
    """② 单改真库 IT 源文件必须触发真库复验。

    病象(2026-09-16 实证):on.push.paths 与 dorny filters 的 test 侧只覆盖
    `service/**MybatisIntegrationTest.java` 与 `business/it/ExploreAlliance*RealDbIT.java`,
    单改 `business/it/Wx*FencingMySqlIT.java` 或 `service/impl/ActivityCancelFreeOrderMybatisIT.java`
    不会触发本 job,而且零告警。下面两条把补上的 pattern 钉在 REQUIRED_TRIGGER_PATHS 与
    workflow 两处 filter 上;第三条是摘掉 push 侧 pattern 的负控。
    """

    _NEW_PATTERNS = (
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/**",
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/*MybatisIT.java",
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/*MySqlIT.java",
        # 2026-09-16 晚补:暗测试 23 套里 mapper/ 与 web/controller/api/ 各一套。
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/**",
        "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/**",
    )

    def test_new_patterns_match_the_it_files_they_must_cover(self):
        # fnmatch 的 `*` 跨 `/`(比 GitHub glob 更宽),所以"fnmatch 命中"对 GitHub 侧成立;
        # "fnmatch 不命中"则 GitHub 一定不命中 —— 这条断言只会漏报,不会误报。
        import fnmatch
        globs = list(funds_it_gate.REQUIRED_TRIGGER_PATHS)
        covered = [
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
            "WxContentCallbackFencingMySqlIT.java",
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
            "WxPaymentCallbackFencingMySqlIT.java",
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
            "ExploreAllianceMvpRealDbIT.java",
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/"
            "ActivityCancelFreeOrderMybatisIT.java",
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
            "ClubEventTransferCancellationMySqlIT.java",
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/"
            "RegistrationReadContractMybatisIntegrationTest.java",
            "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/"
            "RoamSessionWriteMybatisIntegrationTest.java",
        ]
        for relative in covered:
            self.assertTrue(any(fnmatch.fnmatch(relative, pattern) for pattern in globs),
                            "%s 不被任何资金触发路径 glob 命中 —— 单改它不触发真库复验" % relative)

    def test_new_patterns_are_present_in_both_workflow_filter_blocks(self):
        # 门禁的 enforce_trigger_contract 只管 on.push.paths;dorny filters 走的是同一份
        # pattern 列表,漏改一侧 PR 侧就仍旧不跑。两处各一次 = 恰好 2 处。
        workflow = _WORKFLOW.read_text(encoding="utf-8")
        for pattern in self._NEW_PATTERNS:
            quoted = "'%s'" % pattern
            self.assertEqual(2, workflow.count(quoted),
                             "%s 必须同时出现在 on.push.paths 与 dorny filters(现 %d 处)"
                             % (pattern, workflow.count(quoted)))

    def test_red_when_business_it_pattern_is_missing_from_push_trigger(self):
        pattern_line = "      - 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/**'\n"
        original = _WORKFLOW.read_text(encoding="utf-8")
        broken = original.replace(pattern_line, "", 1)
        self.assertNotEqual(original, broken, "fixture 必须真摘掉 business/it 触发路径")
        problems = funds_it_gate.enforce_workflow(broken, _GATE_SCRIPT.read_text(encoding="utf-8"))
        self.assertTrue(any("business/it" in p for p in problems), problems)


# workflow 声明要注入的 4 个 secret;门禁脚本必须逐个 fail-closed，缺一个都不许静默继续。
_DECLARED_SECRETS = (
    "CHENGYIN_TEST_MYSQL_HOST",
    "CHENGYIN_TEST_MYSQL_PORT",
    "CHENGYIN_TEST_MYSQL_USER",
    "CHENGYIN_TEST_MYSQL_PASSWORD",
)


def run_preflight(env):
    return subprocess.run(["bash", str(_GATE_SCRIPT), "preflight"],
                          cwd=str(_REPO_ROOT), env=env,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


class ListeningPort(object):
    """真实监听一个端口，作为 preflight 的绿控——没有它，红只能证明"永远红"。"""

    def __enter__(self):
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(1)
        self.port = self.server.getsockname()[1]
        self.thread = threading.Thread(target=self._accept)
        self.thread.daemon = True
        self.thread.start()
        return self

    def _accept(self):
        try:
            connection, _ = self.server.accept()
            connection.close()
        except OSError:
            pass

    def __exit__(self, *unused):
        self.server.close()


class FundsItPreflightTest(unittest.TestCase):
    """fail-closed 前置的负控：缺任一声明 secret / MySQL 不可达都必须非零退出。"""

    def base_env(self, port):
        env = dict(os.environ)
        env.update({
            "CHENGYIN_TEST_MYSQL_HOST": "127.0.0.1",
            "CHENGYIN_TEST_MYSQL_PORT": str(port),
            "CHENGYIN_TEST_MYSQL_USER": "root",
            "CHENGYIN_TEST_MYSQL_PASSWORD": "",
        })
        return env

    def test_green_when_all_declared_secrets_present_and_port_reachable(self):
        with ListeningPort() as listener:
            result = run_preflight(self.base_env(listener.port))
        self.assertEqual(0, result.returncode, result.stdout.decode("utf-8", "replace"))

    def test_red_when_any_declared_secret_is_missing(self):
        with ListeningPort() as listener:
            for secret in _DECLARED_SECRETS:
                env = self.base_env(listener.port)
                env.pop(secret)
                result = run_preflight(env)
                self.assertNotEqual(0, result.returncode,
                                    "缺 %s 仍然通过 = 门禁没有 fail-closed" % secret)
                self.assertIn(secret, result.stdout.decode("utf-8", "replace"))

    def test_red_when_mysql_unreachable(self):
        env = self.base_env(1)   # 1 号端口不会有 MySQL 在听
        result = run_preflight(env)
        self.assertNotEqual(0, result.returncode)


class FundsItWorkflowContractTest(unittest.TestCase):
    """workflow 自身的结构合同：标签有 TEMP 标记、有超时上限、权限最小、注入的 secret 都被校验。"""

    def setUp(self):
        self.workflow = _WORKFLOW.read_text(encoding="utf-8")
        self.gate = _GATE_SCRIPT.read_text(encoding="utf-8")

    def test_repo_workflow_satisfies_contract(self):
        self.assertEqual([], funds_it_gate.enforce_workflow(self.workflow, self.gate))

    def cleanup_script(self):
        step = self.workflow.split("      - name: Stop Oracle MySQL scratch\n", 1)[1]
        lines = step.split("        run: |\n", 1)[1].splitlines()
        script = []
        for line in lines:
            if line and not line.startswith("          "):
                break
            script.append(line[10:])
        return "\n".join(script) + "\n"

    def run_cleanup(self, script, rm_exit, ps_exit, names):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            docker = root / "docker"
            docker.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "with Path(os.environ['FAKE_CALLS']).open('a') as f:\n"
                "    f.write(json.dumps(sys.argv[1:])+'\\n')\n"
                "if sys.argv[1:] == ['rm', '-f', '-v', os.environ['MYSQL_CONTAINER']]:\n"
                "    raise SystemExit(int(os.environ['FAKE_RM_EXIT']))\n"
                "if sys.argv[1:] == ['ps', '-a', '--format', '{{.Names}}']:\n"
                "    print(os.environ['FAKE_NAMES'])\n"
                "    raise SystemExit(int(os.environ['FAKE_PS_EXIT']))\n"
                "raise SystemExit(99)\n", encoding="utf-8")
            docker.chmod(0o700)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ.get("PATH", ""),
                       MYSQL_CONTAINER="cy-funds-fixture-1", FAKE_CALLS=str(root / "calls"),
                       FAKE_RM_EXIT=str(rm_exit), FAKE_PS_EXIT=str(ps_exit), FAKE_NAMES=names)
            result = subprocess.run(["bash", "-e", "-c", script], env=env,
                                    capture_output=True, text=True, timeout=10)
            calls = (root / "calls").read_text(encoding="utf-8").splitlines()
            self.assertEqual([
                '["rm", "-f", "-v", "cy-funds-fixture-1"]',
                '["ps", "-a", "--format", "{{.Names}}"]',
            ], calls)
            return result

    def test_cleanup_actual_block_requires_successful_exact_absence_readback(self):
        for rm_exit, ps_exit, names, succeeds in (
            (0, 0, "", True),
            (1, 0, "", True),
            (0, 7, "", False),
            (0, 7, "unrelated-fixture", False),
            (0, 0, "cy-funds-fixture-1", False),
            (1, 0, "cy-funds-fixture-1", False),
            (0, 0, "cy-funds-fixture-1-suffix\nprefix-cy-funds-fixture-1", True),
        ):
            with self.subTest(rm_exit=rm_exit, ps_exit=ps_exit, names=names):
                result = self.run_cleanup(self.cleanup_script(), rm_exit, ps_exit, names)
                self.assertEqual(succeeds, result.returncode == 0, result.stdout + result.stderr)

    def test_original_cleanup_pipeline_falsely_accepts_failed_empty_ps(self):
        original = ('docker rm -f -v "$MYSQL_CONTAINER" >/dev/null 2>&1 || true\n'
                    '! docker ps -a --format \'{{.Names}}\' | grep -Fxq "$MYSQL_CONTAINER"\n')
        self.assertEqual(0, self.run_cleanup(original, 0, 7, "").returncode)

    def test_red_when_cleanup_loses_ps_success_or_exact_name_guard(self):
        for before, after in (
            ('if ! names="$(docker ps', 'if names="$(docker ps'),
            ('if [ "$name" = "$MYSQL_CONTAINER" ]; then', 'if false; then'),
        ):
            with self.subTest(before=before):
                problems = funds_it_gate.enforce_workflow(self.workflow.replace(before, after), self.gate)
                self.assertTrue(any("独立确认 docker ps" in p for p in problems), problems)

    def test_red_when_self_hosted_label_loses_temp_marker(self):
        self.assertIn("runs-on: ubuntu-latest", self.workflow)
        broken = self.workflow.replace(
            "runs-on: ubuntu-latest",
            "runs-on: [self-hosted, macOS, ARM64, chengyin]", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("TEMP" in p for p in problems), problems)

    def test_red_when_expensive_it_is_not_scoped_to_funds_paths(self):
        broken = self.workflow.replace(
            "FUNDS: ${{ needs.funds_it_gate_selftest.outputs.funds }}",
            "FUNDS: ${{ needs.missing.outputs.funds }}", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("funds 输出" in p for p in problems), problems)

    def test_red_when_a_job_has_no_timeout(self):
        broken = self.workflow.replace("    timeout-minutes:", "    x-timeout-minutes:", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("timeout-minutes" in p for p in problems), problems)

    def test_red_when_workflow_injects_a_secret_the_gate_never_checks(self):
        broken = self.workflow.replace(
            "CHENGYIN_TEST_MYSQL_PASSWORD: ''",
            "CHENGYIN_TEST_MYSQL_PASSWORD: ''\n"
            "          CHENGYIN_TEST_MYSQL_EXTRA: ${{ secrets.CHENGYIN_TEST_MYSQL_EXTRA }}", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("CHENGYIN_TEST_MYSQL_EXTRA" in p for p in problems), problems)

    def test_red_when_mysql_image_or_readback_is_not_exact_8045(self):
        broken = self.workflow.replace("mysql:8.0.45", "mysql:latest")
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("8.0.45" in p for p in problems), problems)

        broken = self.workflow.replace('identity="$(docker exec', 'identity="$(printf')
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("8.0.45" in p for p in problems), problems)

    def test_red_when_funds_gate_falls_back_to_machine_3306(self):
        broken = self.workflow.replace(
            "CHENGYIN_TEST_MYSQL_PORT: ${{ steps.mysql.outputs.port }}",
            "CHENGYIN_TEST_MYSQL_PORT: 3306", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("动态端口" in p for p in problems), problems)

    def test_red_when_scratch_cleanup_is_removed_or_registered_too_late(self):
        broken = self.workflow.replace('docker rm -f -v "$MYSQL_CONTAINER"', 'echo "$MYSQL_CONTAINER"')
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("清理" in p for p in problems), problems)

        output = 'echo "container=$container" >> "$GITHUB_OUTPUT"'
        broken = self.workflow.replace(output, "", 1).replace("docker run -d", f"docker run -d\n          {output}", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("启动前登记" in p for p in problems), problems)

    def test_red_when_permissions_are_widened(self):
        broken = self.workflow.replace("  contents: read", "  contents: write", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("permissions" in p for p in problems), problems)

    def test_red_when_pull_request_trigger_is_missing(self):
        broken = self.workflow.replace("  pull_request:", "  pull_request_disabled:", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("pull_request" in p for p in problems), problems)

    def test_red_when_pull_request_is_path_filtered_before_becoming_required(self):
        broken = self.workflow.replace(
            "  pull_request:\n",
            "  pull_request:\n    paths:\n      - 'chengyinhub-system/**'\n",
            1,
        )
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("required context" in p for p in problems), problems)

    def test_red_when_main_required_job_can_be_skipped_after_selftest_failure(self):
        broken = self.workflow.replace("    if: ${{ always() }}\n", "", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("if: always" in p for p in problems), problems)

    def test_red_when_main_required_job_does_not_propagate_selftest_result(self):
        broken = self.workflow.replace(
            "${{ needs.funds_it_gate_selftest.result }}",
            "success",
            1,
        )
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("回读并传播" in p for p in problems), problems)

    def test_red_when_funds_migration_path_is_missing_from_trigger(self):
        broken = self.workflow.replace(
            "      - 'chengyinhub-admin/sql/migration_*funds*.sql'\n", "", 1)
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("migration_*funds*.sql" in p for p in problems), problems)

    def test_red_when_tandianri_backfill_migration_path_is_missing_from_trigger(self):
        required = "      - 'chengyinhub-admin/sql/migration_20260814_tandianri_product_type_backfill.sql'\n"
        broken = self.workflow.replace(required, "", 1)
        self.assertNotEqual(self.workflow, broken, "fixture 必须真摘掉探店日资金迁移触发路径")
        problems = funds_it_gate.enforce_workflow(broken, self.gate)
        self.assertTrue(any("tandianri_product_type_backfill" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
