#!/usr/bin/env python3
"""资金 MySQL 集成测试门禁:强制已登记套件真跑、skipped=0、无错无失败、用例数达标。

★ 回执≠观测:`mvn test` 退出 0 / surefire 一片绿,都不证明已登记套件真的连上 MySQL 跑过。
  它们用 @EnabledIfEnvironmentVariable(CHENGYIN_FUNDS_MYSQL_IT=1) 门控 —— 环境变量一旦缺失,
  整套被 JUnit 标记为 skipped,构建照样"绿"。这正是本门禁要把它变红的哑巴故障:静默跳过 = 没验证。

本门禁从 surefire 的 TEST-*.xml 读回每套自己产生的 tests/skipped/errors/failures:
  · 某套没有报告 → 根本没被选中执行(环境没就绪)→ fail-closed
  · skipped != 0    → 被 @EnabledIf 静默跳过 → fail-closed
  · errors  != 0    → @BeforeAll 建库/连库失败即记为 error → fail-closed
  · failures!= 0    → 断言失败
  · 实跑用例 < 期望 → 少跑了(部分被跳过/过滤)→ fail-closed
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

REQUIRED_TRIGGER_PATHS = (
    "chengyinhub-admin/src/main/**",
    "chengyinhub-system/src/main/**",
    "chengyinhub-admin/sql/migration_*funds*.sql",
    "chengyinhub-admin/sql/migration_20260814_tandianri_product_type_backfill.sql",
    # 2026-09-16:test 侧此前只覆盖 service/**MybatisIntegrationTest.java 与
    # business/it/ExploreAlliance*RealDbIT.java ⇒ 单改 WxPayment/WxContent fencing 或
    # service/impl/ActivityCancelFreeOrderMybatisIT 不触发真库 job,零告警。两条都钉死。
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/**",
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/*MybatisIT.java",
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/*MySqlIT.java",
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/*MySqlIT.java",
    # 2026-09-16 暗测试 23 套里 2 套落在这两个目录(mapper/RegistrationReadContract、
    # web/controller/api/RoamSessionWrite),原来的 test 侧 glob 都覆盖不到 —— 单改不触发复验。
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/**",
    "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/**",
    "chengyinhub-admin/sql/migration_20261012_member_password_width.sql",
    "chengyinhub-admin/sql/migration_20261012_roam_presence_revoke_intent.sql",
    "chengyinhub-admin/sql/migration_20261012_roam_presence_revoke_job.sql",
    "chengyinhub-admin/sql/migration_20261029_activity_cancellation_receipt.sql",
    "chengyinhub-admin/sql/migration_20261029_topic_cancellation_receipt.sql",
)


def _extract_on_triggers(workflow_text):
    """提取 on 块的各触发器内容；不用未锁定的 YAML 依赖。"""
    triggers = {}
    current = None
    in_on = False
    for line in workflow_text.splitlines():
        if re.match(r"^on:\s*$", line):
            in_on = True
            continue
        if not in_on:
            continue
        if re.match(r"^\S", line):
            break
        match = re.match(r"^  (\S+):\s*$", line)
        if match:
            current = match.group(1)
            triggers[current] = []
        elif current is not None:
            triggers[current].append(line)
    return triggers


def enforce_trigger_contract(workflow_text):
    """master push 覆盖资金路径；PR 无 paths，才能安全设为 required。"""
    triggers = _extract_on_triggers(workflow_text)
    if not triggers:
        return ["on: 缺失或格式不对，无法核对触发覆盖"]
    problems = []
    if "pull_request" not in triggers:
        problems.append("on.pull_request 缺失 —— 资金 PR 不会触发本门禁")
    else:
        pull_request_body = "\n".join(triggers["pull_request"])
        if "master" not in pull_request_body:
            problems.append("on.pull_request.branches 必须包含 master")
        if re.search(r"^\s+paths(?:-ignore)?:", pull_request_body, re.MULTILINE):
            problems.append("on.pull_request 不得设 paths/paths-ignore —— required context 必须对每个 PR 产生终态")
    if "master" not in "\n".join(triggers.get("push", [])):
        problems.append("on.push.branches 必须包含 master")
    push_body = "\n".join(triggers.get("push", []))
    for required in REQUIRED_TRIGGER_PATHS:
        if required not in push_body:
            problems.append("on.push.paths 缺 %s —— 相关资金改动可能不会触发复验" % required)
    return problems

# class 短名 -> 最少实跑用例数(skipped 必须为 0)。
# 59/9/30 对应三套 @Test 数。RefundPayout 的 30 条覆盖派发、人工租约与过期 token fencing。
# ⚠️ 这个数字曾经手滑数错(把 @TestInstance 当成 @Test 算了一遍,50/9/30 错写成 51/10/31)——
# 见 count_test_methods() 和 ci/test_funds_it_gate.py 里的自测,直接从源码正则数,不再手记。
EXPECTED = {
    "CommunityPostCommentIdempotencyMySqlIT": 2,
    "CoopListReadOnlyMybatisIntegrationTest": 5,
    # 2026-08-17 R9-P1:59 → 60,+1 = forgedClubMarkerFromPublicCreateCannotSurviveIntoPaymentFreeze
    # (自报 sourceClubId 经真实 create 落库后,真实冻结 SQL 仍必须归平台)。
    # 2026-08-19:类间隔离契约本身也进登记 —— 它有三条负控(共用 schema 必撞、information_schema
    # 真查不到对方的表、A 的遗留数据不许把 B 的 fail-closed 前置喂饱)。不登记就等于隔离没人看。
    "MysqlItSchemaIsolationTest": 3,
    # 2026-09-15 CR-527:60 → 62,+2 = expiredOrderRejectionClosesTheOrderAndReleasesHeldResources
    # (过期支付口拒绝必须即时关单回补库存/退积分)、unexpiredOrderKeepsPayingWindowAndIsNotClosed
    # (未过期照常取支付参数且不得误关)。加用例不改这里,test_expected_counts_match_source_files 当场红。
    # 2026-09-15 §5-3:62 → 65,+3 = 三种查单结果各一条(SUCCESS 按回调补记不关单回补 /
    # NOTPAY 先关微信单再本地关单回补库存积分权益 / 查单失败保持不动并告警)。
    # 2026-09-15 P1-PT-7:65 → 67,+2 = 多票父单主单 PAYING 时老关单任务不得拆关从单(查到 SUCCESS 整单补记)/
    # 查到 NOTPAY 主单从单整单关单回补。
    # 2026-09-15 裁决2:67 → 70,+3 = ORDER_NOT_EXIST 过期超 30 分钟宽限关单回补 / 宽限内不动 /
    # 200 个卡单不饿死后到的过期 PAYING 单(游标分页)。
    # 2026-09-15 裁决3:70 → 73,+3 = 微信关单失败不得本地关单(NOTPAY / PAYERROR 各一)/
    # PAYERROR 先 closeOrder 成功才本地关单。
    # 2026-09-15 P1-PT-8:73 → 76,+3 = 主办取消活动遇 PAYING 单:微信已付先补记再整单退 /
    # 未付先关微信单再本地关单回补 / 查单失败拒绝取消且活动订单不动。
    # 2026-09-15 清单1/2:76 → 79,+3 = 取消两段式(另一单查单失败时不得先关掉前面那单的微信订单)/
    # 取消遇 ORDER_NOT_EXIST 宽限内拒绝并给出可重试分钟数 / 过宽限本地关单回补后照常取消。
    # 2026-09-15 清单5:79 → 80,+1 = ORDER_NOT_EXIST 宽限拉到 2 小时(prepay_id 有效期),
    # 过期 45 分钟仍不得关单。
    # 2026-09-15 清单6:80 → 81,+1 = 单轮扫描上限 500、游标留到下一轮接着扫。
    # 2026-09-15 清单7:81 → 83,+2 = 取消路径查单失败 / 微信关单失败都要告警运营。
    # 2026-09-15 自审补:83 → 84,+1 = point_used=0 时关单语句自身也必须带取消来源(清单 8 真收口)。
    # 2026-09-15 遗留清单3:86 → 87,+1 = 多票父单在主办取消路径下从单/主单终态都是已取消而非已过期。
    # 2026-09-15 遗留清单6:87 → 88,+1 = 应用重启游标归零后重扫不会二次关单二次回补(幂等证明)。
    # 2026-09-19 RUN-01:88 → 89,+1 = autoVerifyExpiredSessions_scansOnlyEligibleAndCasGuardsRace。
    # 2026-09-21 CYV-P1-003:+2 人工退款冻结/解冻与 RR 旧快照并发负控；+1 服务端版本锁定 8.0.45。
    # 2026-10-03:92 → 93，新增已取消活动在 RR 旧快照下仍拒核销的真库用例。
    # Frozen merge retains both original source sets: 101 real @Test methods.
    "RegistrationFundsMybatisIntegrationTest": 101,
    # 2026-09-15:9 → 10,+1 = cityNodeFrozenCouponSurvivesStockFailureAndConcurrentRetry
    # (f178b58ae 有意新增:据点冻结券在库存失败后保留权益、并发重试只发一张)。
    # 2026-09-17 release-0917 集成:fix-club-0917 拍板6 停用 verify_answer,删掉 5 条答题验票真库 IT
    #   (concurrentAnswerVerification… / wrongAnswer… / merchantAnswerIsDerived… / activityRegistrationDerives… / templateAnswerIsRejected…),
    #   但未同步本处 → 源码 @Test 实为 5。10 → 5。
    # 合并保留原回执丢响应后恢复用例，共 6 条。
    "RegistrationEquityMybatisIntegrationTest": 6,
    "RefundPayoutMybatisIntegrationTest": 30,
    # 2026-08-14 并入:③ 自由探索联盟局的两套真库 IT(5/4 条 @Test)。
    # 它们此前从未在任何 CI 里执行过(类名 *IT + 无 surefire/failsafe 配置 ⇒ 默认 include 不收),
    # 且内部用 assumeTrue(false) 在库不可达时整套 skip —— skip 算绿,等于永远不会红。
    # 现在:由 gate 脚本点名跑,库不可达改为硬失败,再由本门禁回读 skipped=0。
    "ExploreAllianceMvpRealDbIT": 5,
    "ExploreAllianceRedeemRealDbIT": 4,
    # 2026-10-19 并入:F-L3-08 按章核销重放的并发证明(SC-SCAN-RACE,4 条 @Test)。
    # 它和上面两套用同一个 ExploreItDatabase,但从未被点名 ⇒ 任何 CI 都没在真库上跑过它。
    "ExploreScanReplayRealDbIT": 4,
    # 2026-09-16 提现闸(用户硬规则「等所有玩家都退不了才可提现」):14 → 16,+2 = 活动/主题结算选单
    # 在仍有已付未退未核销、退款截止未到的票时不入选(活动结束早于集合 / 自由定向票期长于主题)。
    "TandianriBatch0FundsMybatisIntegrationTest": 17,
    "ShopDayOneTicketMySqlIT": 5,
    # 2026-08-17 R9:11 → 18,+7 = R6 产品迁移真库重放与两向歧义(此前零执行证据)、
    # R6 资金终结闭环 11 条回读逐条消费、R7 两条写前 guard 的三条错 schema 负控
    # (错表达式生成列 / 复合 UNIQUE / 同名非唯一索引)、R6 两条新 guard 的错 schema 负控
    # (attribution 缺去重唯一键 / XP 唯一键建在错列)。每条都点名是第几条 guard 判红。
    "TandianriBatch3FundsMybatisIntegrationTest": 18,
    # 2026-08-16 并入:探店日「四个 A」的四套真库 IT。
    # 数字是源码 @Test 精确数,不是下限 —— ci/test_funds_it_gate.py 的
    # test_expected_counts_match_source_files 会拿 SOURCE_FILES 现数来比,加一条不改这里当场红。
    # 2026-08-16 R3:+1 = thePayeeVerifyAndPayoutPermissionsLandOnTwoDifferentRoles
    # (把 remark 里那句「与审核权限互斥分派」做成可回读的事实)。
    "ClubCorporatePayeeMybatisIntegrationTest": 14,
    # 2026-08-16 R4:18 → 21,+3 = 已决质量行必须带 reason 的 DB CHECK、
    # business:clubextra:approve 真授到 cy_operator、business:topic:query 真授到两个资金角色。
    "ClubEditionCompensationMybatisIntegrationTest": 21,
    # 2026-08-16 R3:14 → 16。假权限那条断言反转为「专用改写权限存在且真授到了角色」,
    # 另 +2 = 改写审计表的 DB CHECK 与四样事实(前值/后值/理由/操作人)逐行回读。
    "PlayerWithdrawalCooldownMybatisIntegrationTest": 16,
    # 2026-08-16 R3:+1 = aLockedPayableBlocksAConcurrentBatchAttach(红冲锁定读真的拦住并发挂靠,
    # 只有真 InnoDB 证得了)。
    # 2026-08-16 R4:17 → 18,+1 = aReaccrualHoldsItsComponentRowsAgainstAConcurrentBatchAttach
    # (重计提的 component 锁定读同样要拦住并发挂靠;没有它,负向差额行会落在批次之外 ⇒ 多付)。
    "ClubPayableMybatisIntegrationTest": 20,
    # 2026-09-15 R9-16:免费(0 元)订单的主办取消真库复现 IT,8 条。
    # 此前它不在 -Dtest 也不在本清单里 —— 删掉 fallback 再推,CI 照样全绿(活证据不入闸)。
    # 位置在 service/impl/ 且类名是 *MybatisIT(不是 *MybatisIntegrationTest),既不在原 -Dtest,
    # 也不在 on.push.paths 的 `service/**MybatisIntegrationTest.java` glob 里。
    "ActivityCancelFreeOrderMybatisIT": 8,
    # 2026-09-15 并入地图组队 P 方案的公开申请/附近队伍两套真库 IT:
    # 它们与上面各套同用 @EnabledIfEnvironmentVariable(CHENGYIN_FUNDS_MYSQL_IT=1) +
    # MysqlIntegrationTestConfig 的唯一 UUID scratch,普通 CI(无 MySQL)会把它们静默 skip 仍"绿"。
    # 登记进 EXPECTED/-Dtest/自测 fixture 三侧,跳过或漏跑一律 fail-closed。
    "PlayTeamMybatisIntegrationTest": 21,
    "PlayTeamNearbyMybatisIntegrationTest": 4,
    # 2026-09-13 R9-40 优惠券使用期生命周期:原6条 = start 前拒且零核销/结算/审计副作用 /
    # end 后拒并落过期 / start 与合法历史券(start NULL)各核销一次 / 精确 start=end 端点包含 /
    # claimUnusedCoupon SQL 自身拒绝 start 前 / 已开始券并发只结算一次。
    # +3 真实领取:最后一张并发不超发、不限量正常计数、领取记录失败回滚库存后可重试。
    # +2 撤销状态 1/2 保留历史事实，以及初读后撤销由真实 CAS/当前读识别。
    # 2026-09-15 F1 +1:selectSmsCouponHistoryList 的 delFlag 条件经真实 XML 排除软删行,不传则不过滤。
    "CouponLifecycleMybatisIntegrationTest": 16,
    # 2026-09-16 并入支付回调 fencing 真库 IT(5 条 @Test)。
    # 病象:它自己发明了门控变量 CHENGYIN_PAYMENT_MYSQL_IT(全仓真库 IT 统一用
    # CHENGYIN_FUNDS_MYSQL_IT),又不在 EXPECTED/-Dtest 里 ⇒ CI 里这 5 条永远 skip、永远算绿。
    # 与上面 Explore 两套是同一个病。现门控已改回标准变量,并登记三侧:
    # EXPECTED(这里)+ SOURCE_FILES + ci/funds-mysql-it-gate.sh 的 -Dtest,
    # 少一侧当场红:漏 EXPECTED=回读没人管,漏 SOURCE_FILES=计数没人对,漏 -Dtest=压根不跑。
    # 2026-10-22 W3:+1 同 hash 换业务身份在真唯一键上拒收(6 条)。
    "WxPaymentCallbackFencingMySqlIT": 6,
    # 2026-09-16 第二轮并入内容回调 fencing 真库 IT(3 条 @Test),与上面同病:
    # 自造门控变量 CHENGYIN_CONTENT_MYSQL_IT 且未登记 —— CI 里 3 条永远 skip 永远绿。
    # 现门控改标准 CHENGYIN_FUNDS_MYSQL_IT + MysqlIntegrationTestConfig 唯一临时 schema,
    # 同样登记三侧(本 EXPECTED + SOURCE_FILES + ci/funds-mysql-it-gate.sh 的 -Dtest)。
    "WxContentCallbackFencingMySqlIT": 3,
    # 2026-09-16 晚:暗测试真跑 —— 上面扫描到的 23 套(197 条 @Test)CI 从未执行过,
    # 本机 8.0.45 容器首跑定红绿后再入闸。A 组 5 套(CoopDepositRefund/GrowthMissionProof/
    # MerchantIdentity/MerchantProfilePersistence/StoreVerifyGrowthDeliveryTransaction)原用
    # 自造变量(CHENGYIN_DEPOSIT/GROWTH/MERCHANT_IDENTITY/MERCHANT_PROFILE_MYSQL_IT)且
    # Merchant/Coop 三套硬编码 3317,已统一为标准变量 + MysqlIntegrationTestConfig;
    # C 组 18 套原用标准变量但三处均未登记。每套首跑证据与红绿分类见
    # ~/Documents/城瘾/小程序/修复交接材料/worktree收口_0915/暗测试真跑_报告.md。
    # 合并保留原支付事实/退款恢复两组用例，共 36 条。
    "CoopDepositRefundMybatisIntegrationTest": 36,
    "GrowthMissionProofMybatisIntegrationTest": 25,
    "MerchantIdentityMybatisIntegrationTest": 21,
    # 2026-09-24 CU-M-78 +1 条(解除招牌主推显式清空 featured_id,真 XML + 真库回读),8 → 9。
    "MerchantProfilePersistenceMybatisIntegrationTest": 9,
    # 2026-10-15 PR10 复审 +1 条(整单核销撤销:真库标记核销台账已撤销、作废未发放成长待办),3 → 4。
    "StoreVerifyGrowthDeliveryTransactionTest": 4,
    "RefundNotificationMybatisIntegrationTest": 9,
    "SessionRefundClockMybatisIntegrationTest": 3,
    "SettlementGenerateBatchMybatisIntegrationTest": 3,
    # FX-10(2026-09-18):退款收回首评积分,真库验证「还参与过没有」的口径、扣到 0、重复/并发只扣一次。
    "FirstReviewPointsClawbackMybatisIntegrationTest": 10,
    "ClubSettlementSummaryMybatisIntegrationTest": 6,
    "CoopComplaintCloseMybatisIntegrationTest": 8,
    # 2026-10-22 店铺参谋改版 +1 条(主卡 7/30/90 天走势、页头店名/头像、v3 缓存走真库真 XML),5 → 6。
    "RegistrationReadContractMybatisIntegrationTest": 6,
    "ClubWaitlistTokenMybatisIntegrationTest": 7,
    "CmsMemberTemplateAudioPatchMybatisIntegrationTest": 9,
    "CmsTemplateLibraryAudioPatchMybatisIntegrationTest": 9,
    # 2026-10-20 AUDIT-G2-5:模板市场 use_num 按会员首次采用计数(同人重复/删了再建不涨、他人首次 +1)。
    "TemplateUseNumFirstAdoptionMybatisIntegrationTest": 3,
    # 2026-09-17 3-11 修复 +3 条(对象存不存在都不发分 / 未参与者不发分 / 主题认其下活动已核销票),14 → 17。
    # 2026-09-18 RV-p3 F3:+1 会员隔离用例(别人的已支付票不能给评价人换分),17 → 18。
    "ContentInteractionMybatisIntegrationTest": 18,
    "CreativeSquarePublishIdempotencyMybatisIntegrationTest": 7,
    "FollowConcurrencyMybatisIntegrationTest": 4,
    "FollowEditRaceMybatisIntegrationTest": 7,
    # 2026-10-15 PR10 复审 +1 条(瞬移基线按会话取,真库回读),6 → 7。
    "NearbyRunnerShopsMybatisIntegrationTest": 7,
    # 2026-09-17 拍照一律机审:原 5 条(人工审核三条 CAS)→ 6 条:
    # 通过 CAS 恰好一次 / 存量驳回态同 CAS 转正 / 已完成行永不再通过 /
    # 按 id 取行 / 待审重复插入撞唯一键 / 存量待审行读侧不算完成。
    "PhotoReviewProgressMybatisIntegrationTest": 6,
    "SpoilerAccessMybatisIntegrationTest": 7,
    "SquareEditFollowMybatisIntegrationTest": 7,
    # 2026-10-13 小程序底层C包移植到 master:12 → 15,+3 = 结束后档案幂等 / 徽章失败不撤原结算 / 原XP快照并发只发一次。
    "RoamSessionWriteMybatisIntegrationTest": 15,
    # Actual retention/claim dedupe, rollback, owner races, anonymous totals and consent revocation SQL.
    # +1: real cleanup task preserves favorite routes, non-location facts and reward dedupe across replay.
    "RoamLocationRetentionMySqlIT": 21,
    # 2026-09-17 客诉期提现闸(9-16 晚拍板 1-5):到期前不可提现 / 到期后可提现 / 涉诉只冻结部分
    # (含结案补结与退款作废)/ 超期退款欠款从后续结算抵扣,共 5 条。
    # 2026-09-17 总控裁定补做:+2 平台人工倒扣余额不足扣到 0 + 差额记欠款 / 按加锁读的当前余额算(非事务快照)且重复复核空转。
    "ComplaintPeriodWithdrawalGateMybatisIntegrationTest": 7,
    # 2026-09-17 第二轮拍板 17/22:足迹分享快照 + 进行中游戏会话的真迁移/真 XML IT(4 条 @Test)。
    "ShareSnapshotRunSessionMybatisIntegrationTest": 5,
    "PlayerRunSessionOrderingMybatisIntegrationTest": 6,
    # 2026-09-21 CYV-P2-002:49 条时两个并发首次发布必须被会员行锁串行化；
    # 达到上限后更新既有 token 不得误删其他分享。
    "RoamShareSnapshotConcurrencyMySqlIT": 3,
    # 2026-09-21 CYV-P1-002:主理人转让与活动取消在 MySQL RR/RC 下的锁后权限、
    # 精确 topic/club/activity scope 和并发退款幂等。
    "ClubEventTransferCancellationMySqlIT": 13,
    # Frozen merge retains master nine financial cases plus original UNKNOWN recovery.
    "RefundRetryAndRemainderMySqlIT": 10,
    "SignupNoticeAfterCommitPersistenceMySqlIT": 18,
    "TopicLikeDesiredStateMySqlIT": 3,
    "MonitorMissingJobsMySqlIT": 3,
    "MemberPasswordWidthMySqlIT": 3,
    "LegacyCategoryForwardGuardMySqlIT": 4,
    "MissingBusinessSeedsMySqlIT": 4,
    "RoamPresenceRevokeDurabilityMySqlIT": 10,
    "RoamPresenceRevokeJobMySqlIT": 4,
    "WxBillIngestionPersistenceTest": 6,
}

# class 短名 -> 该测试类所在源文件(相对仓库根),供 count_test_methods() 自测用。
SOURCE_FILES = {
    "WxBillIngestionPersistenceTest": "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/WxBillIngestionPersistenceTest.java",
    "CommunityPostCommentIdempotencyMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/CommunityPostCommentIdempotencyMySqlIT.java",
    "MissingBusinessSeedsMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MissingBusinessSeedsMySqlIT.java",
    "RefundRetryAndRemainderMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/RefundRetryAndRemainderMySqlIT.java",
    "SignupNoticeAfterCommitPersistenceMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/SignupNoticeAfterCommitPersistenceMySqlIT.java",
    "TopicLikeDesiredStateMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicLikeDesiredStateMySqlIT.java",
    "MonitorMissingJobsMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MonitorMissingJobsMySqlIT.java",
    "MemberPasswordWidthMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MemberPasswordWidthMySqlIT.java",
    "LegacyCategoryForwardGuardMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/LegacyCategoryForwardGuardMySqlIT.java",
    "RoamPresenceRevokeDurabilityMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/RoamPresenceRevokeDurabilityMySqlIT.java",
    "RoamPresenceRevokeJobMySqlIT": "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RoamPresenceRevokeJobMySqlIT.java",
    "CoopListReadOnlyMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "CoopListReadOnlyMybatisIntegrationTest.java",
    "MysqlItSchemaIsolationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "MysqlItSchemaIsolationTest.java",
    "RegistrationFundsMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "RegistrationFundsMybatisIntegrationTest.java",
    "RegistrationEquityMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "RegistrationEquityMybatisIntegrationTest.java",
    "RefundPayoutMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "RefundPayoutMybatisIntegrationTest.java",
    "ExploreAllianceMvpRealDbIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
        "ExploreAllianceMvpRealDbIT.java",
    "ExploreAllianceRedeemRealDbIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
        "ExploreAllianceRedeemRealDbIT.java",
    "ExploreScanReplayRealDbIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
        "ExploreScanReplayRealDbIT.java",
    "TandianriBatch0FundsMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "TandianriBatch0FundsMybatisIntegrationTest.java",
    "ShopDayOneTicketMySqlIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ShopDayOneTicketMySqlIT.java",
    "TandianriBatch3FundsMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "TandianriBatch3FundsMybatisIntegrationTest.java",
    "ClubCorporatePayeeMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ClubCorporatePayeeMybatisIntegrationTest.java",
    "ClubEditionCompensationMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ClubEditionCompensationMybatisIntegrationTest.java",
    "PlayerWithdrawalCooldownMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "PlayerWithdrawalCooldownMybatisIntegrationTest.java",
    "ClubPayableMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ClubPayableMybatisIntegrationTest.java",
    "ActivityCancelFreeOrderMybatisIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/"
        "ActivityCancelFreeOrderMybatisIT.java",
    "PlayTeamMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "PlayTeamMybatisIntegrationTest.java",
    "PlayTeamNearbyMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "PlayTeamNearbyMybatisIntegrationTest.java",
    "CouponLifecycleMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "CouponLifecycleMybatisIntegrationTest.java",
    "WxPaymentCallbackFencingMySqlIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
        "WxPaymentCallbackFencingMySqlIT.java",
    "WxContentCallbackFencingMySqlIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/"
        "WxContentCallbackFencingMySqlIT.java",
    # 2026-09-16 暗测试 23 套(与上面 EXPECTED 同批登记,计数由 count_test_methods 现数)。
    "CoopDepositRefundMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "CoopDepositRefundMybatisIntegrationTest.java",
    "GrowthMissionProofMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "GrowthMissionProofMybatisIntegrationTest.java",
    "MerchantIdentityMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "MerchantIdentityMybatisIntegrationTest.java",
    "MerchantProfilePersistenceMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "MerchantProfilePersistenceMybatisIntegrationTest.java",
    "StoreVerifyGrowthDeliveryTransactionTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "StoreVerifyGrowthDeliveryTransactionTest.java",
    "RefundNotificationMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "RefundNotificationMybatisIntegrationTest.java",
    "SessionRefundClockMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "SessionRefundClockMybatisIntegrationTest.java",
    "SettlementGenerateBatchMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "SettlementGenerateBatchMybatisIntegrationTest.java",
    "FirstReviewPointsClawbackMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "FirstReviewPointsClawbackMybatisIntegrationTest.java",
    "ClubSettlementSummaryMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ClubSettlementSummaryMybatisIntegrationTest.java",
    "CoopComplaintCloseMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/"
        "CoopComplaintCloseMybatisIntegrationTest.java",
    "RegistrationReadContractMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/"
        "RegistrationReadContractMybatisIntegrationTest.java",
    "ClubWaitlistTokenMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ClubWaitlistTokenMybatisIntegrationTest.java",
    "CmsMemberTemplateAudioPatchMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "CmsMemberTemplateAudioPatchMybatisIntegrationTest.java",
    "CmsTemplateLibraryAudioPatchMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "CmsTemplateLibraryAudioPatchMybatisIntegrationTest.java",
    "TemplateUseNumFirstAdoptionMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "TemplateUseNumFirstAdoptionMybatisIntegrationTest.java",
    "ContentInteractionMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ContentInteractionMybatisIntegrationTest.java",
    "CreativeSquarePublishIdempotencyMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "CreativeSquarePublishIdempotencyMybatisIntegrationTest.java",
    "FollowConcurrencyMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "FollowConcurrencyMybatisIntegrationTest.java",
    "FollowEditRaceMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "FollowEditRaceMybatisIntegrationTest.java",
    "NearbyRunnerShopsMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "NearbyRunnerShopsMybatisIntegrationTest.java",
    "PhotoReviewProgressMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "PhotoReviewProgressMybatisIntegrationTest.java",
    "SpoilerAccessMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "SpoilerAccessMybatisIntegrationTest.java",
    "SquareEditFollowMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "SquareEditFollowMybatisIntegrationTest.java",
    "RoamLocationRetentionMySqlIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/"
        "RoamLocationRetentionMySqlIT.java",
    "RoamSessionWriteMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/"
        "RoamSessionWriteMybatisIntegrationTest.java",
    "ComplaintPeriodWithdrawalGateMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ComplaintPeriodWithdrawalGateMybatisIntegrationTest.java",
    "ShareSnapshotRunSessionMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/"
        "ShareSnapshotRunSessionMybatisIntegrationTest.java",
    "PlayerRunSessionOrderingMybatisIntegrationTest":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/"
        "PlayerRunSessionOrderingMybatisIntegrationTest.java",
    "RoamShareSnapshotConcurrencyMySqlIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/"
        "RoamShareSnapshotConcurrencyMySqlIT.java",
    "ClubEventTransferCancellationMySqlIT":
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/"
        "ClubEventTransferCancellationMySqlIT.java",
}

# 只认真正的 @Test 注解(独占一行,可带括号参数)。\b 挡住 @TestInstance/@TestFactory 这类
# 前缀相同但不是 @Test 的注解 —— 两个字母都是 word char,中间没有边界,\b 不会误配。
_TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.MULTILINE)


def count_test_methods(java_source_text):
    """从测试类源码里数真实 @Test 方法数,不数 @TestInstance/@Nested/@ParameterizedTest。"""
    return len(_TEST_ANNOTATION.findall(java_source_text))


def load_reports(reports_dir):
    found = {}
    for path in glob.glob(os.path.join(reports_dir, "TEST-*.xml")):
        root = ET.parse(path).getroot()
        name = root.get("name", "")
        short = name.rsplit(".", 1)[-1]
        found[short] = {
            "tests": int(root.get("tests", "0")),
            "skipped": int(root.get("skipped", "0")),
            "failures": int(root.get("failures", "0")),
            "errors": int(root.get("errors", "0")),
            "name": name,
        }
    return found


def enforce(reports_dir, expected=EXPECTED):
    found = load_reports(reports_dir)
    problems = []
    for short, min_tests in sorted(expected.items()):
        report = found.get(short)
        if report is None:
            problems.append(
                "%s: 无 surefire 报告 —— 该套没被执行(CHENGYIN_FUNDS_MYSQL_IT 未就绪被跳过?),fail-closed" % short)
            continue
        if report["skipped"] != 0:
            problems.append("%s: skipped=%d(必须为 0)—— 被 @EnabledIf 静默跳过,等于没验证"
                            % (short, report["skipped"]))
        if report["errors"] != 0:
            problems.append("%s: errors=%d —— 建库/连库/迁移失败即记 error,fail-closed"
                            % (short, report["errors"]))
        if report["failures"] != 0:
            problems.append("%s: failures=%d" % (short, report["failures"]))
        ran = report["tests"] - report["skipped"]
        if ran < min_tests:
            problems.append("%s: 实跑用例 %d < 期望 %d" % (short, ran, min_tests))
    return problems


def enforce_workflow(workflow_text, gate_text):
    """workflow 自身的结构合同。

    这层管的不是"跑出来的结果"，而是"这个门禁有没有被悄悄改成跑不起来/管不住"：
      · self-hosted 标签必须带 TEMP 标记 —— 无标记的 self-hosted 是静默回潮（#145 那种旧分支覆盖）
      · 每个 job 必须有 timeout-minutes —— runner 离线/挂死时排队到超时才有一个终态，
        否则一直 queued，既不绿也不红，等于没有门禁
      · permissions 必须是最小的 contents: read
      · workflow 注入的每个 CHENGYIN_TEST_MYSQL_* secret，门禁脚本都必须真去校验它，
        否则就是"声明了却不校验"的假前提
    """
    problems = list(enforce_trigger_contract(workflow_text))

    runs_on = re.findall(r"^\s*runs-on:.*$", workflow_text, re.MULTILINE)
    for line in runs_on:
        if "self-hosted" in line and "TEMP" not in line:
            problems.append("runs-on 使用 self-hosted 却没有 TEMP 标记(疑似静默回潮): " + line.strip())
    timeouts = re.findall(r"^\s*timeout-minutes:\s*\d+\s*$", workflow_text, re.MULTILINE)
    if len(timeouts) < len(runs_on):
        problems.append("每个 job 都要有 timeout-minutes(现 %d 个 timeout / %d 个 job):"
                        "runner 离线时 job 会一直排队,既不绿也不红" % (len(timeouts), len(runs_on)))

    if not re.search(r"^permissions:\s*\n\s*contents:\s*read\s*$", workflow_text, re.MULTILINE):
        problems.append("permissions 必须显式收敛为 contents: read")

    main_job = re.search(
        r"^  funds_mysql_it:\s*$([\s\S]*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)",
        workflow_text,
        re.MULTILINE,
    )
    main_job_text = main_job.group(1) if main_job else ""
    if not main_job:
        problems.append("Funds MySQL 主 job 缺失")
    if "    if: ${{ always() }}" not in main_job_text:
        problems.append("Funds MySQL 主 job 必须 if: always()，否则前置自测红时 required context 会 skipped")
    if "needs.funds_it_gate_selftest.result" not in main_job_text:
        problems.append("Funds MySQL 主 job 必须回读并传播前置自测结果")
    # required check 仍要对每个 PR 出终态(所以 on.pull_request 不得设 paths),
    # 但真库 IT 不得在改 UI/小程序的 PR 上烧额度。
    if "dorny/paths-filter" not in workflow_text:
        problems.append("必须用路径过滤决定要不要跑真库 IT")
    if "FUNDS: ${{ needs.funds_it_gate_selftest.outputs.funds }}" not in main_job_text:
        problems.append("真库 IT 必须读自测 job 的 funds 输出，不得每个 UI/小程序 PR 都跑 20 分钟 MySQL")
    if "steps.scope.outputs.run" not in main_job_text:
        problems.append("真库 IT 的 checkout/java/mvn 必须按 steps.scope.outputs.run 跳过")
    if ("mysql:8.0.45" not in main_job_text
            or "identity=\"$(docker exec" not in main_job_text
            or "SELECT CONCAT(VERSION(), \"|\", @@version_comment)" not in main_job_text
            or "test \"$identity\" = '8.0.45|MySQL Community Server - GPL'" not in main_job_text):
        problems.append("资金 IT 必须启动并回读 Oracle MySQL 8.0.45")
    if "CHENGYIN_TEST_MYSQL_PORT: ${{ steps.mysql.outputs.port }}" not in main_job_text:
        problems.append("资金 IT 必须使用 scratch MySQL 的动态端口，不得回落机器 3306")
    if ("if: always() && steps.mysql.outputs.container != ''" not in main_job_text
            or "docker rm -f -v \"$MYSQL_CONTAINER\"" not in main_job_text):
        problems.append("资金 IT 必须 always() 清理 scratch MySQL 容器")
    cleanup_step = re.search(
        r"^      - name: Stop Oracle MySQL scratch\n([\s\S]*?)(?=^      - |\Z)",
        main_job_text, re.MULTILINE,
    )
    cleanup_text = cleanup_step.group(1) if cleanup_step else ""
    if ('''if ! names="$(docker ps -a --format '{{.Names}}' 2>/dev/null)"; then
            echo "::error::Funds scratch absence readback failed"
            exit 1
          fi''' not in cleanup_text
            or '''while IFS= read -r name; do
            if [ "$name" = "$MYSQL_CONTAINER" ]; then
              echo "::error::Funds scratch container remains"
              exit 1
            fi
          done <<< "$names"''' not in cleanup_text):
        problems.append("资金 IT 清理必须独立确认 docker ps 成功，并精确拒绝同名残留容器")
    container_output = main_job_text.find('echo "container=$container" >> "$GITHUB_OUTPUT"')
    if container_output < 0 or container_output > main_job_text.find("docker run -d"):
        problems.append("资金 IT 必须在容器启动前登记名称，确保启动后失败仍可清理")

    injected = set(re.findall(r"(CHENGYIN_TEST_MYSQL_[A-Z_]+):\s*\$\{\{\s*secrets\.", workflow_text))
    for secret in sorted(injected):
        if secret not in gate_text:
            problems.append("%s 被 workflow 注入却不在门禁脚本的校验清单里 —— 声明了却不校验 = 没有前提"
                            % secret)
    return problems


def main(argv):
    if len(argv) > 2 and argv[1] == "workflow":
        workflow_text = open(argv[2], encoding="utf-8").read()
        gate_text = open(argv[3], encoding="utf-8").read()
        problems = enforce_workflow(workflow_text, gate_text)
        if problems:
            sys.stderr.write("❌ 资金 IT workflow 结构合同未通过:\n")
            for problem in problems:
                sys.stderr.write("  - " + problem + "\n")
            return 1
        print("✅ 资金 IT workflow 结构合同通过:标签带 TEMP、每 job 有超时、权限最小、注入 secret 均被校验。")
        return 0
    reports_dir = argv[1] if len(argv) > 1 else "chengyinhub-admin/target/surefire-reports"
    problems = enforce(reports_dir)
    if problems:
        sys.stderr.write("❌ 资金 MySQL IT 门禁未通过:\n")
        for problem in problems:
            sys.stderr.write("  - " + problem + "\n")
        return 1
    print("✅ 资金 MySQL IT 门禁通过:所有已登记 IT 均真跑、skipped=0、无错无失败、用例数达标。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
