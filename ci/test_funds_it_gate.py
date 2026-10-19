#!/usr/bin/env python3
"""funds_it_gate 的红绿负控:证明门禁能在"静默跳过/缺席/建库报错/少跑"时真的变红,不是恒绿装饰。

纯 Python,不需要 MySQL,可本机随时重放 —— 这层就是 H2 fail-closed 的可运行负控证据。
真正连 MySQL8 跑六套 IT 仍是外部动作(runner 上执行),本负控不冒充那层已验证。
"""
import importlib.util
import os
import pathlib
import re
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


def write_fixed_coupon_report(directory, short):
    import xml.etree.ElementTree as ET
    full = "com.chengyinhub.business.it." + short
    names = funds_it_gate.FIXED_COUPON_METHODS[short]
    root = ET.Element("testsuite", name=full, tests=str(len(names)), skipped="0", failures="0", errors="0")
    for name in names:
        ET.SubElement(root, "testcase", name=name, classname=full)
    path = pathlib.Path(directory) / ("TEST-" + full + ".xml")
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
    return path


def write_merchant_authoring_report(directory, short):
    import xml.etree.ElementTree as ET
    full = "com.chengyinhub.business.service.ai." + short
    names = funds_it_gate.MERCHANT_AUTHORING_METHODS[short]
    root = ET.Element("testsuite", name=full, tests=str(len(names)), skipped="0", failures="0", errors="0")
    for name in names:
        ET.SubElement(root, "testcase", name=name, classname=full)
    path = pathlib.Path(directory) / ("TEST-" + full + ".xml")
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
    return path


def all_required_clean(directory):
    """EXPECTED 里的每一套都产出一份干净报告。

    2026-08-14 并入两套 Explore 真库 IT;2026-08-16 并入四 A 的四套。
    **这个 fixture 必须跟着 EXPECTED 走**:
    少写一套,test_green_when_all_required_ran_fully 会红,而那正是它该有的行为
    (门禁新增一套却没人跑 = fail-closed)。别为了让它绿而去删 EXPECTED 里的条目。
    """
    write_merchant_authoring_report(directory, "MerchantAuthoringCurrentRowsMySqlIT")
    write_merchant_authoring_report(directory, "MerchantAuthoringRevocationRaceMySqlIT")
    write_fixed_coupon_report(directory, "FixedCouponAtomicMySqlIT")
    write_fixed_coupon_report(directory, "FixedCouponSchemaMySqlIT")
    write_report(directory, "CouponCommandServiceMySqlIT", 20)
    write_report(directory, "MerchantNpcKnowledgeDraftMySqlIT", 28)
    write_report(directory, "NonCashRewardMySqlIT", 31)
    write_report(directory, "RendezvousConsentMySqlIT", 12)
    write_report(directory, "CityBoardDraftMySqlIT", 16)
    write_report(directory, "WalkingTargetMySqlIT", 16)
    write_report(directory, "InstalledDraftReceiptMySqlIT", 42)
    write_report(directory, "InstalledDraftReceiptIndexMySqlIT", 10)
    write_report(directory, "FreeClaimInstallationMySqlIT", 43)
    write_report(directory, "FreeInstallScopeSchemaMySqlIT", 7)
    write_report(directory, "FreeModuleSchemaMySqlIT", 13)
    write_report(directory, "FreeModuleClaimMySqlIT", 21)
    write_report(directory, "ModuleReservationMySqlIT", 35)
    write_report(directory, "ModuleTextInstallationMySqlIT", 38)
    write_report(directory, "CityRuntimeMySqlIT", 31)
    write_report(directory, "CityLegacyPointsMySqlIT", 4)
    write_report(directory, "ContentCaptureSnapshotMySqlIT", 9)
    write_report(directory, "ContentCaptureReviewMySqlIT", 27)
    write_report(directory, "ContentCapturePreviewMySqlIT", 18)
    write_report(directory, "ContentCaptureReviewSchemaMySqlIT", 31)
    write_report(directory, "CoopListReadOnlyMybatisIntegrationTest", 5)
    write_report(directory, "RegistrationFundsMybatisIntegrationTest", 92)
    write_report(directory, "RegistrationEquityMybatisIntegrationTest", 5)
    write_report(directory, "RefundPayoutMybatisIntegrationTest", 30)
    write_report(directory, "ExploreAllianceMvpRealDbIT", 5)
    write_report(directory, "ExploreAllianceRedeemRealDbIT", 4)
    write_report(directory, "TandianriBatch0FundsMybatisIntegrationTest", 16)
    write_report(directory, "ShopDayOneTicketMySqlIT", 5)
    write_report(directory, "TandianriBatch3FundsMybatisIntegrationTest", 18)
    write_report(directory, "ClubCorporatePayeeMybatisIntegrationTest", 11)
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
    write_report(directory, "WxPaymentCallbackFencingMySqlIT", 5)
    # 2026-09-16 第二轮:内容回调 fencing IT 入闸(3 条),与上面同病同修。
    write_report(directory, "WxContentCallbackFencingMySqlIT", 3)
    # 2026-09-16 晚:暗测试 23 套(197 条)首跑判绿后入闸;计数与 EXPECTED/SOURCE_FILES 同源。
    for short, tests in (
        ("CoopDepositRefundMybatisIntegrationTest", 27),
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
        ("RegistrationReadContractMybatisIntegrationTest", 5),
        ("ClubWaitlistTokenMybatisIntegrationTest", 7),
        ("CmsMemberTemplateAudioPatchMybatisIntegrationTest", 9),
        ("CmsTemplateLibraryAudioPatchMybatisIntegrationTest", 9),
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
        ("RoamLocationRetentionMySqlIT", 20),
        ("ComplaintPeriodWithdrawalGateMybatisIntegrationTest", 7),
        ("ShareSnapshotRunSessionMybatisIntegrationTest", 5),
        ("PlayerRunSessionOrderingMybatisIntegrationTest", 6),
        ("RoamShareSnapshotConcurrencyMySqlIT", 3),
        ("ClubEventTransferCancellationMySqlIT", 3),
        ("RefundRetryAndRemainderMySqlIT", 5),
        ("SignupNoticeAfterCommitPersistenceMySqlIT", 5),
        ("TopicLikeDesiredStateMySqlIT", 3),
        ("MonitorMissingJobsMySqlIT", 3),
        ("MemberPasswordWidthMySqlIT", 3),
        ("LegacyCategoryForwardGuardMySqlIT", 4),
        ("MissingBusinessSeedsMySqlIT", 4),
        ("RoamPresenceRevokeDurabilityMySqlIT", 10),
        ("RoamPresenceRevokeJobMySqlIT", 4),
    ):
        write_report(directory, short, tests)


class FundsItGateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_green_when_all_required_ran_fully(self):
        all_required_clean(self.dir)
        self.assertEqual([], funds_it_gate.enforce(self.dir))

    def test_red_when_npc_draft_suite_is_skipped(self):
        all_required_clean(self.dir)
        write_report(self.dir, "MerchantNpcKnowledgeDraftMySqlIT", 28, skipped=28)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("MerchantNpcKnowledgeDraftMySqlIT" in p and "skipped" in p for p in problems), problems)

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
            ("skipped", dict(tests=20, skipped=1), "skipped"),
            ("error", dict(tests=20, errors=1), "errors"),
            ("failure", dict(tests=20, failures=1), "failures"),
            ("short", dict(tests=19), "实跑用例"),
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
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 5)
        write_report(self.dir, "RefundPayoutMybatisIntegrationTest", 30)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("skipped" in p for p in problems), problems)

    def test_red_when_a_suite_is_absent(self):
        # 负控②:某套压根没被选中执行(没有报告)→ 缺席也是 fail-closed。
        write_report(self.dir, "RegistrationFundsMybatisIntegrationTest", 59)
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 5)
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
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 5)
        write_report(self.dir, "RefundPayoutMybatisIntegrationTest", 30)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("实跑用例" in p for p in problems), problems)

    def test_red_when_failures_present(self):
        all_required_clean(self.dir)
        write_report(self.dir, "RegistrationEquityMybatisIntegrationTest", 5, failures=1)
        problems = funds_it_gate.enforce(self.dir)
        self.assertTrue(any("failures" in p for p in problems), problems)


_REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
_GATE_SCRIPT = _REPO_ROOT / "ci" / "funds-mysql-it-gate.sh"
_WORKFLOW = _REPO_ROOT / ".github" / "workflows" / "funds-mysql-it.yml"


class CouponCommandIntegrationContractTest(unittest.TestCase):
    def test_all_prior_suite_counts_are_unchanged(self):
        import hashlib
        import json
        legacy = {k: v for k, v in funds_it_gate.EXPECTED.items() if k not in {"CoopListReadOnlyMybatisIntegrationTest", "MerchantAuthoringCurrentRowsMySqlIT", "MerchantAuthoringRevocationRaceMySqlIT", "CouponCommandServiceMySqlIT", "FixedCouponAtomicMySqlIT", "FixedCouponSchemaMySqlIT"}}
        self.assertEqual("2fb7e46c9240af2487fc8d7045b870b4d6eda104e6042457499805b3501f6f14", hashlib.sha256(json.dumps(legacy, sort_keys=True).encode()).hexdigest())
        self.assertEqual(20, funds_it_gate.EXPECTED["CouponCommandServiceMySqlIT"])
        self.assertEqual(5, funds_it_gate.EXPECTED["CoopListReadOnlyMybatisIntegrationTest"])

    def test_coupon_skip_remains_red(self):
        with tempfile.TemporaryDirectory() as directory:
            all_required_clean(directory)
            write_report(directory, "CouponCommandServiceMySqlIT", 20, skipped=20)
            self.assertTrue(any("CouponCommandServiceMySqlIT" in p and "skipped" in p for p in funds_it_gate.enforce(directory)))

    def test_coupon_same_reactor_binding_and_artifact(self):
        gate = _GATE_SCRIPT.read_text(encoding="utf-8")
        workflow = _WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(1, gate.count("mvn -B -pl chengyinhub-admin -am clean test"))
        selectors = re.search(r'  -Dtest=([^\n]+)', gate).group(1).split(',')
        self.assertEqual(1, selectors.count("CouponCommandServiceMySqlIT"))
        self.assertEqual(set(funds_it_gate.EXPECTED), set(selectors))
        self.assertIn('coupon_commit="$(git rev-parse HEAD)"', gate)
        self.assertIn("coupon_run_id=", gate)
        self.assertIn('coupon_source_sha="$(python3 ci/coupon_command_mysql_gate.py --fingerprint-source .)"', gate)
        self.assertIn('-Dcoupon.command.test.commit="$coupon_commit" -Dcoupon.command.test.run="$coupon_run_id" -Dcoupon.command.test.sourceSha256="$coupon_source_sha"', gate)
        self.assertIn('python3 ci/coupon_command_mysql_gate.py chengyinhub-admin/target/surefire-reports --commit "$coupon_commit" --run-id "$coupon_run_id" --source-sha256 "$coupon_source_sha" --output artifacts/coupon-command-mysql-proof.json', gate)
        self.assertIn('ci/test_coupon_command_mysql_gate.py', workflow)
        self.assertIn('name: coupon-command-mysql-${{ github.sha }}-${{ github.run_attempt }}', workflow)
        self.assertIn('artifacts/coupon-command-mysql-proof.json', workflow)
        self.assertIn('chengyinhub-admin/target/surefire-reports/*CouponCommandServiceMySqlIT*', workflow)
        for path in ('chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CouponCommandServiceMySqlIT.java', 'chengyinhub-admin/src/test/resources/fixtures/coupon-command-existing-schema.sql', 'chengyinhub-admin/sql/migration_coupon_command_receipt.sql', 'chengyinhub-admin/sql/migration_coupon_dedup.sql', 'ci/coupon_command_mysql_gate.py', 'ci/test_coupon_command_mysql_gate.py'):
            self.assertIn(path, funds_it_gate.REQUIRED_TRIGGER_PATHS)
            self.assertEqual(2, workflow.count("'" + path + "'"))


class NpcDraftIntegrationContractTest(unittest.TestCase):
    def test_npc_proof_binds_the_same_clean_reactor_and_has_artifact(self):
        gate = _GATE_SCRIPT.read_text(encoding="utf-8")
        workflow = _WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(1, gate.count("mvn -B -pl chengyinhub-admin -am clean test"))
        selectors = re.search(r'  -Dtest=([^\n]+)', gate).group(1).split(',')
        self.assertEqual(1, selectors.count('MerchantNpcKnowledgeDraftMySqlIT'))
        self.assertIn('npc_commit="$(git rev-parse HEAD)"', gate)
        self.assertIn("npc_run_id=", gate)
        self.assertIn('-Dnpc.draft.test.commit="$npc_commit" -Dnpc.draft.test.run="$npc_run_id"', gate)
        self.assertIn('python3 ci/npc_draft_mysql_gate.py chengyinhub-admin/target/surefire-reports --commit "$npc_commit" --run-id "$npc_run_id" --output artifacts/npc-draft-mysql-proof.json', gate)
        self.assertIn('ci/test_npc_draft_mysql_gate.py', workflow)
        self.assertIn('artifacts/npc-draft-mysql-proof.json', workflow)
        self.assertIn('chengyinhub-admin/target/surefire-reports/*MerchantNpcKnowledgeDraftMySqlIT*', workflow)
        for path in (
            "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/npc/**",
            "chengyinhub-admin/src/test/resources/fixtures/merchant-npc-existing-schema.sql",
            "chengyinhub-admin/sql/migration_20261004_merchant_npc_knowledge_drafts.sql",
            "ci/npc_draft_mysql_gate.py", "ci/test_npc_draft_mysql_gate.py",
        ):
            self.assertIn(path, funds_it_gate.REQUIRED_TRIGGER_PATHS)
            self.assertEqual(2, workflow.count("'" + path + "'"))


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
        self.assertEqual(20, funds_it_gate.EXPECTED[short])
        source = _REPO_ROOT / funds_it_gate.SOURCE_FILES[short]
        self.assertEqual(20, funds_it_gate.count_test_methods(source.read_text(encoding="utf-8")))
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

    def test_walking_target_suite_rejects_missing_skipped_failed_errored_and_under_counted_reports(self):
        short = "WalkingTargetMySqlIT"
        self.assertEqual(16, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))
        for field in ("skipped", "failures", "errors"):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                write_report(directory, short, 16, **{field: 1})
                self.assertTrue(funds_it_gate.enforce(directory))
        with tempfile.TemporaryDirectory() as directory:
            all_required_clean(directory)
            write_report(directory, short, 15)
            self.assertTrue(funds_it_gate.enforce(directory))
            for path in pathlib.Path(directory).glob("*WalkingTargetMySqlIT.xml"):
                path.unlink()
            self.assertTrue(funds_it_gate.enforce(directory))
    def test_city_board_draft_is_registered_and_fail_closed_for_every_missing_evidence_kind(self):
        short = "CityBoardDraftMySqlIT"
        self.assertEqual(16, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))
        for kwargs in ({"skipped": 1}, {"errors": 1}, {"failures": 1}, {"tests": 15}):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                self.assertEqual([], funds_it_gate.enforce(directory))
                write_report(directory, short, **{"tests": 16, **kwargs})
                self.assertTrue(any(short in problem for problem in funds_it_gate.enforce(directory)))
        with tempfile.TemporaryDirectory() as directory:
            all_required_clean(directory)
            for report in pathlib.Path(directory).glob("*CityBoardDraftMySqlIT.xml"):
                report.unlink()
            self.assertTrue(any(short in problem for problem in funds_it_gate.enforce(directory)))

    def test_city_board_draft_uses_original_mysql_migration_without_h2_inheritance(self):
        path = _REPO_ROOT / funds_it_gate.SOURCE_FILES["CityBoardDraftMySqlIT"]
        text = path.read_text(encoding="utf-8")
        self.assertEqual(16, funds_it_gate.count_test_methods(text))
        for required in ('named="CHENGYIN_FUNDS_MYSQL_IT", matches="1"', 'mysql.open("boarddraft")',
                         'mysql.apply(MIGRATION)', 'mysql.close()', '20261003_city_board_draft_candidate',
                         'new JdbcCityBoardDraftStore(source)', 'CityBoardDraftService.Mode.ISOLATED_DRAFT_EVALUATION'):
            self.assertIn(required, text)
        self.assertNotIn("jdbc:h2", text)
        self.assertNotIn("extends CityBoardDraftPersistenceTest", text)
        migration = "chengyinhub-admin/sql/migration_20261003_city_board_draft_candidate.sql"
        self.assertIn(migration, funds_it_gate.REQUIRED_TRIGGER_PATHS)
        self.assertEqual(2, _WORKFLOW.read_text(encoding="utf-8").count("'" + migration + "'"))

    def test_capture_preview_is_registered_and_fail_closed_for_every_missing_evidence_kind(self):
        short = "ContentCapturePreviewMySqlIT"
        self.assertEqual(18, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))
        for kwargs in ({"skipped": 1}, {"errors": 1}, {"failures": 1}, {"tests": 17}):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                values = {"tests": 18, **kwargs}
                write_report(directory, short, **values)
                self.assertTrue(any(short in problem for problem in funds_it_gate.enforce(directory)))
        with tempfile.TemporaryDirectory() as directory:
            all_required_clean(directory)
            for report in pathlib.Path(directory).glob("*ContentCapturePreviewMySqlIT.xml"):
                report.unlink()
            self.assertTrue(any(short in problem for problem in funds_it_gate.enforce(directory)))

    def test_capture_review_live_schema_suite_is_registered_with_fail_closed_readback(self):
        short = "ContentCaptureReviewSchemaMySqlIT"
        self.assertEqual(31, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))
        for field in ("skipped", "failures", "errors"):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                write_report(directory, short, 31, **{field: 1})
                self.assertTrue(funds_it_gate.enforce(directory))

    def test_capture_review_suite_is_registered_with_fail_closed_readback(self):
        short = "ContentCaptureReviewMySqlIT"
        self.assertEqual(27, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))
        for field in ("skipped", "failures", "errors"):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                write_report(directory, short, 27, **{field: 1})
                self.assertTrue(funds_it_gate.enforce(directory))

    def test_capture_review_transport_requires_all_27_cases(self):
        short = "ContentCaptureReviewMySqlIT"
        for count in (0, 20, 26):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                write_report(directory, short, count)
                self.assertTrue(funds_it_gate.enforce(directory))
        with tempfile.TemporaryDirectory() as directory:
            all_required_clean(directory)
            for report in pathlib.Path(directory).glob("*ContentCaptureReviewMySqlIT.xml"):
                report.unlink()
            self.assertTrue(funds_it_gate.enforce(directory))

    def test_capture_snapshot_suite_is_registered_with_fail_closed_readback(self):
        short = "ContentCaptureSnapshotMySqlIT"
        self.assertEqual(9, funds_it_gate.EXPECTED[short])
        self.assertIn(short, funds_it_gate.SOURCE_FILES)
        self.assertIn(short, _GATE_SCRIPT.read_text(encoding="utf-8"))
        for field in ("skipped", "failures", "errors"):
            with tempfile.TemporaryDirectory() as directory:
                all_required_clean(directory)
                write_report(directory, short, 9, **{field: 1})
                self.assertTrue(funds_it_gate.enforce(directory))

    def test_module_city_suites_fail_closed_on_missing_skipped_failed_or_short_report(self):
        for short, expected in (("InstalledDraftReceiptIndexMySqlIT", 10), ("InstalledDraftReceiptMySqlIT", 42), ("FreeClaimInstallationMySqlIT", 43), ("FreeInstallScopeSchemaMySqlIT", 7), ("FreeModuleSchemaMySqlIT", 13), ("FreeModuleClaimMySqlIT", 21), ("ModuleReservationMySqlIT", 35), ("ModuleTextInstallationMySqlIT", 38),
                                ("CityRuntimeMySqlIT", 31), ("CityLegacyPointsMySqlIT", 4)):
            self.assertEqual(expected, funds_it_gate.EXPECTED[short])
            for bad in ("missing", "skipped", "failures", "errors", "short"):
                with self.subTest(suite=short, failure=bad), tempfile.TemporaryDirectory() as directory:
                    all_required_clean(directory)
                    self.assertEqual([], funds_it_gate.enforce(directory))
                    if bad == "missing":
                        os.unlink(os.path.join(directory, "TEST-com.chengyinhub.business.service." + short + ".xml"))
                    else:
                        kwargs = {bad: 1} if bad in ("skipped", "failures", "errors") else {}
                        write_report(directory, short, expected - (bad == "short"), **kwargs)
                    self.assertTrue(funds_it_gate.enforce(directory), short + ": " + bad)

    def test_module_city_inherited_suites_replace_h2_setup_and_keep_all_assertions(self):
        for short, base in (("InstalledDraftReceiptMySqlIT", "InstalledDraftReceiptReaderTest"), ("FreeClaimInstallationMySqlIT", "FreeClaimInstallationTransactionTest"),
                            ("ModuleReservationMySqlIT", "ModuleInstallReservationTransactionTest"),
                            ("ModuleTextInstallationMySqlIT", "ModuleTextDraftInstallationTransactionTest"),
                            ("CityRuntimeMySqlIT", "CityRuntimePersistenceH2Test")):
            source = _REPO_ROOT / funds_it_gate.SOURCE_FILES[short]
            subclass = source.parent / (short + ".java")
            text = subclass.read_text(encoding="utf-8")
            self.assertIn("extends " + base, text)
            self.assertIn("@EnabledIfEnvironmentVariable", text)
            self.assertIn('named="CHENGYIN_FUNDS_MYSQL_IT", matches="1"', text)
            self.assertIn("createDataSource()", text)
            self.assertIn("mysql.open(", text)
            self.assertIn("applySchema(JdbcTemplate jdbc)", text)
            self.assertIn("mysql.apply(", text)
            self.assertIn("mysql.close()", text)
            self.assertNotIn("jdbc:h2", text)
            self.assertEqual(0, funds_it_gate.count_test_methods(text), "Shared assertions must not be shadowed")
        text = (_REPO_ROOT / "chengyinhub-admin/src/test/java/com/chengyinhub/business/marketplace/reservation/ModuleReservationMySqlIT.java").read_text()
        self.assertIn('return "REPEATABLE READ"', text)
        self.assertNotIn('"SNAPSHOT"', text)
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


class FixedCouponExactMethodGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.expected = {"FixedCouponAtomicMySqlIT": 17, "FixedCouponSchemaMySqlIT": 13}
        self.paths = {short: write_fixed_coupon_report(self.directory.name, short) for short in self.expected}

    def problems(self):
        return funds_it_gate.enforce(self.directory.name, self.expected)

    def mutate(self, short, operation):
        import xml.etree.ElementTree as ET
        tree = ET.parse(self.paths[short]); operation(tree.getroot()); tree.write(self.paths[short], encoding="utf-8")

    def test_complete_thirty_exact_methods_pass(self):
        self.assertEqual(30, sum(self.expected.values()))
        self.assertEqual([], self.problems())

    def test_each_missing_suite_is_rejected(self):
        for short in self.expected:
            with self.subTest(short=short):
                path = self.paths[short]; original = path.read_bytes(); path.unlink()
                self.assertTrue(self.problems()); path.write_bytes(original)

    def test_exact_source_method_identity_and_existing_selector_set(self):
        for short, names in funds_it_gate.FIXED_COUPON_METHODS.items():
            source = (_REPO_ROOT / funds_it_gate.SOURCE_FILES[short]).read_text()
            self.assertEqual(list(names), re.findall(r"@Test\s+void\s+(\w+)\s*\(", source))
            self.assertEqual(len(names), funds_it_gate.EXPECTED[short])
        selectors = re.search(r'  -Dtest=([^\n]+)', _GATE_SCRIPT.read_text()).group(1).split(',')
        self.assertEqual(set(selectors), set(funds_it_gate.EXPECTED)); self.assertEqual(len(selectors), len(set(selectors)))

    def test_missing_duplicated_replaced_or_extra_method_rejected(self):
        import xml.etree.ElementTree as ET
        operations = [lambda r: r.remove(r.find("testcase")),
                      lambda r: r.append(ET.fromstring(ET.tostring(r.find("testcase")))),
                      lambda r: r.find("testcase").set("name", "differentMethod"),
                      lambda r: ET.SubElement(r, "testcase", name="unregisteredMethod", classname=r.get("name"))]
        for short in self.expected:
            for operation in operations:
                with self.subTest(short=short, operation=operation):
                    original = self.paths[short].read_bytes(); self.mutate(short, operation)
                    self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_hidden_case_skip_failure_error_is_rejected_even_with_zero_headers(self):
        import xml.etree.ElementTree as ET
        for short in self.expected:
            for tag in ["skipped", "failure", "error"]:
                original = self.paths[short].read_bytes(); self.mutate(short, lambda r: ET.SubElement(r.find("testcase"), tag))
                self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_suite_headers_do_not_override_actual_method_inventory(self):
        for short in self.expected:
            for attribute, value in [("tests", "1"), ("tests", "99"), ("skipped", "1"), ("failures", "1"), ("errors", "1")]:
                original = self.paths[short].read_bytes(); self.mutate(short, lambda r: r.set(attribute, value))
                self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_same_short_class_in_wrong_package_is_rejected(self):
        for short in self.expected:
            original = self.paths[short].read_bytes()
            self.mutate(short, lambda r: r.set("name", "wrong.package." + short))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(original)
            self.mutate(short, lambda r: r.find("testcase").set("classname", "wrong.package." + short))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_duplicate_suite_report_is_rejected(self):
        duplicate = pathlib.Path(self.directory.name) / "TEST-duplicate.xml"
        duplicate.write_bytes(self.paths["FixedCouponAtomicMySqlIT"].read_bytes())
        self.assertTrue(self.problems())


    def test_exact_hosted_302_method_display_identity_is_accepted(self):
        for short in self.expected:
            def convert(root):
                root.set("version", "3.0.2")
                for case in root.findall("testcase"):
                    case.set("classname", case.get("name") + "()")
            self.mutate(short, convert)
        self.assertEqual([], self.problems())

    def test_hosted_identity_refuses_wrong_version_wrong_method_and_mixed_forms(self):
        for short in self.expected:
            original = self.paths[short].read_bytes()
            def convert(root):
                root.set("version", "3.0.2")
                for case in root.findall("testcase"):
                    case.set("classname", case.get("name") + "()")
            self.mutate(short, convert)
            hosted = self.paths[short].read_bytes()
            self.mutate(short, lambda root: root.set("version", "3.0"))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(hosted)
            self.mutate(short, lambda root: root.find("testcase").set("classname", "unrelated()"))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(hosted)
            if self.expected[short] > 1:
                self.mutate(short, lambda root: root.find("testcase").set("classname", root.get("name")))
                self.assertTrue(self.problems())
            self.paths[short].write_bytes(original)

    def test_hidden_retry_failures_are_rejected_with_zero_summary_errors(self):
        import xml.etree.ElementTree as ET
        for short in self.expected:
            for tag in ["flakyFailure", "flakyError", "rerunFailure", "rerunError"]:
                original = self.paths[short].read_bytes()
                self.mutate(short, lambda root: ET.SubElement(root.find("testcase"), tag))
                self.assertTrue(self.problems()); self.paths[short].write_bytes(original)


class MerchantAuthoringExactMethodGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.expected = {'MerchantAuthoringCurrentRowsMySqlIT': 7, 'MerchantAuthoringRevocationRaceMySqlIT': 1}
        self.paths = {short: write_merchant_authoring_report(self.directory.name, short) for short in self.expected}

    def problems(self):
        return funds_it_gate.enforce(self.directory.name, self.expected)

    def mutate(self, short, operation):
        import xml.etree.ElementTree as ET
        tree = ET.parse(self.paths[short]); operation(tree.getroot()); tree.write(self.paths[short], encoding="utf-8")

    def test_complete_eight_exact_methods_pass(self):
        self.assertEqual(8, sum(self.expected.values()))
        self.assertEqual([], self.problems())

    def test_each_missing_suite_is_rejected(self):
        for short in self.expected:
            with self.subTest(short=short):
                path = self.paths[short]; original = path.read_bytes(); path.unlink()
                self.assertTrue(self.problems()); path.write_bytes(original)

    def test_exact_source_method_identity_and_existing_selector_set(self):
        for short, names in funds_it_gate.MERCHANT_AUTHORING_METHODS.items():
            source = (_REPO_ROOT / funds_it_gate.SOURCE_FILES[short]).read_text()
            self.assertEqual(list(names), re.findall(r"@Test\s+void\s+(\w+)\s*\(", source))
            self.assertEqual(len(names), funds_it_gate.EXPECTED[short])
        selectors = re.search(r'  -Dtest=([^\n]+)', _GATE_SCRIPT.read_text()).group(1).split(',')
        self.assertEqual(set(selectors), set(funds_it_gate.EXPECTED)); self.assertEqual(len(selectors), len(set(selectors)))

    def test_missing_duplicated_replaced_or_extra_method_rejected(self):
        import xml.etree.ElementTree as ET
        operations = [lambda r: r.remove(r.find("testcase")),
                      lambda r: r.append(ET.fromstring(ET.tostring(r.find("testcase")))),
                      lambda r: r.find("testcase").set("name", "differentMethod"),
                      lambda r: ET.SubElement(r, "testcase", name="unregisteredMethod", classname=r.get("name"))]
        for short in self.expected:
            for operation in operations:
                with self.subTest(short=short, operation=operation):
                    original = self.paths[short].read_bytes(); self.mutate(short, operation)
                    self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_hidden_case_skip_failure_error_is_rejected_even_with_zero_headers(self):
        import xml.etree.ElementTree as ET
        for short in self.expected:
            for tag in ["skipped", "failure", "error"]:
                original = self.paths[short].read_bytes(); self.mutate(short, lambda r: ET.SubElement(r.find("testcase"), tag))
                self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_suite_headers_do_not_override_actual_method_inventory(self):
        for short in self.expected:
            for attribute, value in [("tests", "0"), ("tests", "99"), ("skipped", "1"), ("failures", "1"), ("errors", "1")]:
                original = self.paths[short].read_bytes(); self.mutate(short, lambda r: r.set(attribute, value))
                self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_same_short_class_in_wrong_package_is_rejected(self):
        for short in self.expected:
            original = self.paths[short].read_bytes()
            self.mutate(short, lambda r: r.set("name", "wrong.package." + short))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(original)
            self.mutate(short, lambda r: r.find("testcase").set("classname", "wrong.package." + short))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(original)

    def test_duplicate_suite_report_is_rejected(self):
        duplicate = pathlib.Path(self.directory.name) / "TEST-duplicate.xml"
        duplicate.write_bytes(self.paths["MerchantAuthoringCurrentRowsMySqlIT"].read_bytes())
        self.assertTrue(self.problems())

    def test_test_only_authoring_changes_trigger_push_and_changed_filter(self):
        path = "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/MerchantAuthoring*MySql*.java"
        workflow = _WORKFLOW.read_text()
        self.assertEqual(2, workflow.count(path))
        self.assertEqual([], funds_it_gate.enforce_workflow(workflow, _GATE_SCRIPT.read_text()))
        self.assertTrue(funds_it_gate.enforce_workflow(workflow.replace(path, "removed/path"), _GATE_SCRIPT.read_text()))
        push_only = workflow.replace("              - '" + path + "'\n", "")
        self.assertTrue(funds_it_gate.enforce_workflow(push_only, _GATE_SCRIPT.read_text()))
        filter_only = workflow.replace("      - '" + path + "'\n", "", 1)
        self.assertTrue(funds_it_gate.enforce_workflow(filter_only, _GATE_SCRIPT.read_text()))


    def test_exact_hosted_302_method_display_identity_is_accepted(self):
        for short in self.expected:
            def convert(root):
                root.set("version", "3.0.2")
                for case in root.findall("testcase"):
                    case.set("classname", case.get("name") + "()")
            self.mutate(short, convert)
        self.assertEqual([], self.problems())

    def test_hosted_identity_refuses_wrong_version_wrong_method_and_mixed_forms(self):
        for short in self.expected:
            original = self.paths[short].read_bytes()
            def convert(root):
                root.set("version", "3.0.2")
                for case in root.findall("testcase"):
                    case.set("classname", case.get("name") + "()")
            self.mutate(short, convert)
            hosted = self.paths[short].read_bytes()
            self.mutate(short, lambda root: root.set("version", "3.0"))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(hosted)
            self.mutate(short, lambda root: root.find("testcase").set("classname", "unrelated()"))
            self.assertTrue(self.problems()); self.paths[short].write_bytes(hosted)
            if self.expected[short] > 1:
                self.mutate(short, lambda root: root.find("testcase").set("classname", root.get("name")))
                self.assertTrue(self.problems())
            self.paths[short].write_bytes(original)

    def test_hidden_retry_failures_are_rejected_with_zero_summary_errors(self):
        import xml.etree.ElementTree as ET
        for short in self.expected:
            for tag in ["flakyFailure", "flakyError", "rerunFailure", "rerunError"]:
                original = self.paths[short].read_bytes()
                self.mutate(short, lambda root: ET.SubElement(root.find("testcase"), tag))
                self.assertTrue(self.problems()); self.paths[short].write_bytes(original)


class FixedAuthoringEvidenceRetentionTest(unittest.TestCase):
    def test_only_four_exact_mysql_xml_reports_are_retained_even_after_test_failure(self):
        workflow = _WORKFLOW.read_text()
        block = workflow.split("      - name: Retain exact fixed-coupon and authoring MySQL reports\n", 1)[1].split("      - name: Stop Oracle MySQL scratch", 1)[0]
        self.assertIn("if: always() && steps.scope.outputs.run == 'true'", block)
        self.assertIn("name: fixed-authoring-mysql-${{ github.sha }}-${{ github.run_attempt }}", block)
        expected = {
            "chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.it.FixedCouponAtomicMySqlIT.xml",
            "chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.it.FixedCouponSchemaMySqlIT.xml",
            "chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.service.ai.MerchantAuthoringCurrentRowsMySqlIT.xml",
            "chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.service.ai.MerchantAuthoringRevocationRaceMySqlIT.xml",
        }
        actual = {line.strip() for line in block.splitlines() if line.strip().startswith("chengyinhub-")}
        self.assertEqual(expected, actual)
        self.assertNotIn("*", block)
        self.assertNotIn("continue-on-error", block)


if __name__ == "__main__":
    unittest.main()
