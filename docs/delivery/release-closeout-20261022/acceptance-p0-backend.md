# 发布收尾 · P0 验收卡后端半边（Lane AC1，2026-10-22）

> 对应《城瘾小程序实施文档一》§9.1 P0 组（钱 / 核销 / 越权 / 售后 / 注销）。
> 目标：后端行为在 CI 上用**真 MySQL 8.0.45**证明，人工验收只看 UI 与真机行为。
> 本 lane **只加测试，不改生产代码**。没有访问生产，没有调用微信支付（支付通道一律用既有 mock/stub，链路停在 prepay 意图边界）。不涉及任何密钥。

- 基线：master `f0b43d62`（#475 合并后）
- 分支：`feature/lane-ac1-p0-chains-20261022`
- 新增测试提交：`14e6a1b6`（5 个类，共 13 条用例）
- 登记提交：`c53bd024`（真存储批次 `p0-acceptance-ac1`）

## 1. CI 入口

| 简称 | 入口 | 何时跑 | 判定口径 |
|---|---|---|---|
| **BT** | `ci.yml` backend job → `ci/backend-test.sh` 的 `MYBATIS_ITS` | 每个改到后端的 PR，以及 push master | 在 MySQL 8.0.45 service 上跑；`ci/assert_it_ran.py` 回读 surefire 报告，要求真跑且 skipped=0 |
| **FG** | `funds-mysql-it.yml` → `ci/funds-mysql-it-gate.sh` 固定名单 | 改到资金路径的 PR，以及 push master（required） | 50/9/30 计数，skipped=0 |
| **RS** | `real-storage-it.yml`（手动）按 `ci/real-storage-it-registry.json` 批次 | 手动对冻结 SHA | 每类独占 runner 和容器；回读条数等于登记数 |
| **CA** | `chain-acceptance.yml`（手动）→ `scripts/e2e/chain_*.py` | 手动 | 真 HTTP + SQL 回读 |
| **UT** | `ci/backend-test.sh` 的 `mvn -B clean test` | 每个后端 PR | Mockito 或 H2，不连真库 |

本 lane 新增的 5 个类同时登记进 **BT**（每个 PR 都跑）和 **RS** 批次 `p0-acceptance-ac1`（冻结候选可单独复跑，UTC 和 Asia/Shanghai 两档时区都可选）。没有动 FG 的固定名单和计数。

## 2. 新增的真库 IT（本 lane）

| 类 | 用例数 | 真库对象 | 负控（本地实测会变红） |
|---|---|---|---|
| `business/it/P0RedemptionChainMySqlIT` | 5 | 生产 `CmsRegistrationMapper.xml` 的 `verifyIfNotVerified` 和 `clearVerification`；`GroupCodeServiceImpl.redeem` 真事务代理，配真 `coop_group_verify` 唯一键和 `business_audit_event` | 从 XML 删掉 `registration_status = 2` 后，“已取消订单不得核销”变红 |
| `business/it/P0CrossAccountMySqlIT` | 3 | `ContentDraftServiceImpl` 真事务，加 `ContentDraftMapper`、`CmsRegistrationMapper`、`RedemptionMapper` 的 owner 收口 SQL | `selectOwnedById` 去掉 `owner_member_id` 后，B 恢复 A 的草稿成功，用例变红 |
| `service/impl/P0CityRunChainMySqlIT` | 2 | `GameSessionRuntimeServiceImpl.command` 真事务和 `game_session` 行锁，加 it-schema 真 DDL | — |
| `business/it/P0FreeExploreSupplyGateMySqlIT` | 2 | `MerchantChapterOfferMapper.countRequiredChaptersWithoutLiveOffer`，以及调用它的 `PricingServiceImpl.confirmPrice` | — |
| `business/it/P0DeregistrationMySqlIT` | 1 | `AccountDeregistrationServiceImpl.executeDue` 走生产 `AccountDeregistrationMapper.xml`、`UmsMemberMapper`、`OmsOrderMapper`、`CmsRegistrationMapper`；另跑 `UmsMemberServiceImpl.getOrCreateWechatMember` | `anonymizeOrders` 的 WHERE 扩到 B 后，“注销 A 不得触碰 B 的任何行”变红 |

建表只用仓库已有的生产 DDL：

- `sql/base_schema_20260818.sql` 快照，按表名截取。
- `chengyinhub-admin/sql/migration_*.sql` 原文件。其中 `business_audit_event` 的 `actor_type` 列是按 `migration_approval_governance_20260828.sql` 原样补的同一条 ALTER，因为那个文件里有 DELIMITER 触发器，不能整文件执行。
- 城市跑沿用已登记的 TEST_FIXTURE `it-schema/generic-route-runtime-it-schema.sql`。

**没有新增任何 schema 文件，也没有新增生产 migration**，所以 `ci/migration_ledger.py` 的 TEST_FIXTURE 名单不需要改。

本地证据：一次性容器 `mysql:8.0.45`，JDK 21。13/13 通过，skipped=0。JVM 时区 UTC 和 Asia/Shanghai 两档都跑过；`ci/assert_it_ran.py` 回读为“5 套均真跑、skipped=0”。`ci/test_real_storage_it.py` 共 44 条通过，`real_storage_it.precheck` 对 5 个类的条数、门控和 @Nested 都没有报问题。

### CI 实测（PR #497，head `b237a17a`）

- **BT**：ci.yml “Backend tests” job 119834006934 通过。5 个类各自 `Tests run: 2/3/1/2/5, Failures: 0, Errors: 0, Skipped: 0`；`assert_it_ran.py` 输出“真库 IT 观测通过:52 套均真跑、skipped=0、无错无失败”。
- **RS**：`real-storage-it.yml` 批次 `p0-acceptance-ac1`，source `14e6a1b6`：
  - UTC：run 39781558716，5/5 类通过，Batch summary 通过。
  - Asia/Shanghai：run 39781573198，5/5 类通过，Batch summary 通过。
- **FG**：Funds MySQL IT（50/9/30）通过，计数未变。PR 上其余 required checks 也全部通过。

## 3. 场景 → 测试 → CI → 状态

状态说明：
- **已覆盖(真库)**：断言本身就在真 MySQL 上跑。
- **已覆盖(单测)**：只有 Mockito/H2 证明，没有真库。
- **新增**：本 lane 补的真库 IT。
- **缺口**：仍然没有真库证明，见第 5 节。

路径前缀 `T/` 表示 `chengyinhub-admin/src/test/java/com/chengyinhub/`。

### A. 核销（扫码整单 / 团码）

| 场景 | 测试 | CI | 状态 |
|---|---|---|---|
| 合法码核销成功 | **新增** `P0RedemptionChainMySqlIT.legalTicketVerifiesOnceWhileWrongCancelledUnpaidAndRefundedTicketsStayUntouched`；既有：`T/business/service/impl/GameSessionScanLegalMySqlIT.approvalCompletesOnlyThisStationAndPlayerProjectionOrderAndReceptionAgree`（BT）、`T/business/it/ExploreAllianceRedeemRealDbIT`（FG）、`T/web/controller/api/ApiRegistrationVerifyGateTest.firstScan_succeedsAndAwardsPoints`（UT） | BT, RS, FG | 新增 + 已覆盖(真库) |
| 错码 / 伪造 / 过期 | **新增** 同上用例（不存在的单号写 0 行）和 `groupCodeLegalWrongCancelledAndTwoStaffRaceLeaveExactlyOneLedgerRowAndAudit`（签名不匹配、过期、无合作单商家，台账和审计都是 0 行）；既有：`T/web/controller/api/ApiVerifyGroupCodeTest.redeem_forgedCode_rejected / redeem_expiredCode_rejected / redeem_nonGroupCode_rejected`、`ScanContextSharedGateTest.invalidCode_rejectedByBothEntries`、`ScanDynamicCodeReasonAcceptanceTest`（UT） | BT, RS, UT | 新增 |
| 已取消订单 / 场次 | **新增** 整单：已取消、未支付、已退款都不能核销，且被拒的行逐列不变；团码：已取消场次返回“团码场次无效”，不落行。另有 `manualRefundCaseFreezesTheTicketInTheSameAtomicUpdate`。既有：`RegistrationVerifyAndSettleTest.cancelledActivityRejectsBeforeVerificationAndSettlement`（UT）、`T/business/service/RegistrationFundsMybatisIntegrationTest.pendingManualRefundCaseFreezesVerificationUntilNoRefundDecision`（FG）、`VerifyRefundCasMutualExclusionMyBatisTest`（H2） | BT, RS, FG | 新增 + 已覆盖(真库) |
| 撤销核销 | **新增** `reversedTicketClearsExactlyOnceAndCanThenBeVerifiedByOnlyOneStaff`：还有 ACTIVE 流水时不清位；撤销后只清一次，二次撤销写 0 行；重新核销仍只有一人成功；REVERSED 流水保留，生效唯一键释放。既有：`T/business/service/StoreVerifyGrowthDeliveryTransactionTest.wholeOrderReversalMarksScanLedgerAndDropsPendingGrowth`（FG）、`T/business/it/ExploreScanReplayRealDbIT.reversalAndReplayShareLockOrderAndReversedReceiptCannotReplay`（FG）、`WholeOrderVerificationReverseTest`（UT） | BT, RS, FG | 新增 + 已覆盖(真库) |
| 两名店员同时核销同一码（恰好一人成功） | **新增** 整单：`twoStaffRacingTheSameTicketProduceExactlyOneVerification`，20 轮屏障并发，每轮恰好 1 人成功，落库核销人就是返回成功的那一方。团码：两线程同时 `redeem`，恰好 1 次首次接待，`coop_group_verify` 1 行，审计 1 行。既有（探索章节路径）：`ExploreScanReplayRealDbIT.simultaneousSameTicketScansSerializeAndReturnOneOriginalReceipt`（FG）、`T/business/it/RedeemExploreReadViewMySqlIT`（RS `port-f3-evidence`） | BT, RS, FG | 新增 + 已覆盖(真库) |

### B. 跨账号越权

| 场景 | 测试 | CI | 状态 |
|---|---|---|---|
| B 读或改 A 的订单 / 票 | **新增** `P0CrossAccountMySqlIT.userBCannotListPayCancelOrScanUserAOrderAndTicket`：B 的列表只有 B 的单；B 对 A 的单发起支付、取消、首次扫码都写 0 行，A 的行逐列不变；正控为 A 本人可以操作。既有：`T/web/controller/api/ExploreInfoEntitlementsTest.otherMembersTicket_rejected_andNoLedgerLeak`、`ApiVerifyControllerTest.rejects_other_members_registration`（UT）、`T/business/service/PlayTeamMybatisIntegrationTest.otherMembersUnpaidCancelledRefundedAndDeletedOrdersCannotCreateTeams`（FG） | BT, RS, UT, FG | 新增 |
| B 读 A 的核销记录 | **新增** `merchantBCannotReadMerchantARedemptionRecord`；既有：`ApiRegistrationVerifyGateTest.activity_nonOwnerScan_rejected`、`ApiVerifyGroupCodeTest.redeem_leaderFromOtherClubSession_rejected`（UT）、`ExploreAllianceRedeemRealDbIT.merchantCannotRedeemSeatItDoesNotSupply`（FG） | BT, RS | 新增 |
| B 操作 A 的草稿 | **新增** `userBCannotRestoreListEditDeleteOrPublishUserADraft`：覆盖恢复、列表、编辑、删除、发布，以及 A 把草稿发布到 B 的活动（403）。这一项此前**完全没有跨账号用例**。既有（主题、模板、轨迹草稿）：`ApiTopicSaveDraftContractTest.strangerCannotReadDraftInEditDetail`、`TemplateEditOwnershipTest.editingSomeoneElsesTemplateIsRejected`、`ApiTrackControllerToTopicFailClosedTest.foreignDraft_stillRejected`（UT）、`T/business/it/TopicDraftVisibilityMyBatisTest`（真库，UT 步） | BT, RS, UT | 新增 |
| B 操作 A 的俱乐部数据 | 既有：`T/business/it/ClubRoleHttpMySqlIT.anonymousNonOwnerAndForeignScopeCannotAssign`（BT，真 HTTP + 真库）；`ClubCrmCrossClubIsolationTest`、`ClubGovernanceServiceImplTest.crossClubIdorCannotUnbanAndSameClubUnbanUsesVersionedCasWithAudit`、`ClubMembershipServiceImplTest.ownInvoiceReadRejectsCrossClubAndCrossMemberRows`、`ApiStatsClubCompletionFactTest.nonOwnerCannotReadDashboard`（UT） | BT, UT | 已覆盖(真库) |
| 活动删除 / 发布状态 IDOR | 既有：`ActivityPublishRoleGateTest.merchantStaffCannotWriteOwnersClubActivityEvenWithoutClubIdInRequest`、`merchantScopeUpdateCannotTouchAnotherOwnersActivity`、`ActivityAppUpdateTest.stranger_cannotUpdate`（UT）。归属校验在 Controller 层（`OwnershipUtils.notOwner`） | UT | 已覆盖(单测) |

### C. 城市跑 / NPC / 冻结

| 场景 | 测试 | CI | 状态 |
|---|---|---|---|
| 城市跑恢复竞态 | **新增** `P0CityRunChainMySqlIT.twoClubTerminalsResumingThePausedStationTogetherTransitionItExactlyOnce`：两终端同一版本并发 `CLUB_STATION_RESUME`，恰好 1 个 APPLIED，另一个返回 `GAME_STATION_ALREADY_ACTIVE`；站点 revision +1，session revision +1，APPLIED 回执 1 条。既有：`GameSessionStationIncidentScopeTest.resumeWhoseStationCasLosesTheRaceFailsWithAClearConflictAndNoSessionBump`（UT）、`T/business/service/impl/GameSessionIndependentRevisionMySqlIT`（BT）。漫游开局恢复：`T/web/controller/api/RoamSessionWriteMybatisIntegrationTest.concurrentBootstrapUsesOneOwnerKeyAndKeepsLegacyAndOtherOwnersIndependent`（FG）、`RoamSessionResumeAcceptanceTest`（UT） | BT, RS, FG | 新增 + 已覆盖(真库) |
| NPC 节点 | 既有：`T/business/it/NodeNpcMapperMyBatisIT`，8 条，包括 `playerReadSeesOnlyApprovedAndEnabled`、`oneNpcPerNodeIsEnforcedByUniqueKey`、`nodeNpcWritesAreScopeIsolated`、`concurrentVoiceClaimsHaveOnlyOneWinner`（BT）；`NodeNpcStationScopeAcceptanceTest.nonOwnerIsRefusedOnEveryWriteEntryWithoutAnyRowWritten`、`NodeNpcManageRoleAcceptanceTest`（UT）；`NpcCurrentRegistrationMySqlIT`（BT） | BT, UT | 已覆盖(真库：mapper 层)。`NodeNpcService` 的 owner 闸只有单测，见第 5 节 |
| 冻结后无状态变化 | **新增** `finishedSessionIsFrozenAgainstPlayerAndStaffWrites`：FINISH 之后，玩家的 SUBMIT/HINT/REVEAL 和俱乐部的 RESUME/PAUSE/FINISH/UNLOCK_CHAPTER 全部拒绝，7 张 game_* 表与 FINISH 后的快照逐行一致，原 FINISH 只回放。既有：`GameSessionCityEndMySqlIT.finishFreezesRecapToOutcomeRowsAndLeavesOrdersStockRewardsAsFulfilled`（BT）；自由探索锁价后审核冻结：`ChapterMerchantNodeAuditFreezeAcceptanceTest`（4 条，UT） | BT, RS, UT | 新增（会话冻结）；自由探索审核冻结只有单测 |

### D. 自由探索：价格 / 结账 / 售后

| 场景 | 测试 | CI | 状态 |
|---|---|---|---|
| 价格（锁价）/ 需补供给 | **新增** `P0FreeExploreSupplyGateMySqlIT`：无供给、失效、过期、只覆盖可选章，这几种情况下 `confirmPrice` 都在任何写入前拒绝；补齐后放行到下一道闸。既有：`FreeExplorePriceLockAcceptanceTest`（10 条）、`RequiredChapterSupplyGateTest`、`FreeExploreSupplyNodeTermsAcceptanceTest`、`OrdinarySupplyResumeTest`（UT）；真 HTTP 锁价：`scripts/e2e/chain_merchant_club.py` → `/api/topic/pricing/confirm`（CA） | BT, RS, UT, CA | 新增 |
| 结账（停在 prepay 意图边界，不调支付） | 既有：`RegistrationFundsMybatisIntegrationTest.applicationServiceOwnsQuoteCreateReplayPreorderAndResponseMapping`、`concurrentCreateOrReplayReturnsOneWinnerForTheSameRequestId`（FG，微信 mock）；`StandaloneCheckoutSignupNoticeAcceptanceTest.userPublishedStandalonePaidOrderIsRefusedBeforeAnyWechatPrepayOnRetry`、`ZeroAmountCheckoutRosterV2Test`（UT）；`T/business/it/FreeSelfPlayCreateRealDbIT`（RS `extra-storage`）；`scripts/e2e/chain_player.py`（CA，桩支付） | FG, UT, RS, CA | 已覆盖(真库)。FREE_EXPLORE 专属夹具缺口见第 5 节 |
| 退出 / 0 元 | 既有：`T/business/service/impl/ActivityCancelFreeOrderMybatisIT`（8 条，FG，主理人侧）；`FreeOrderCancelAcceptanceTest.freeSingleOrderCancelReturnsStockAndEligibilityWithoutMoneyOrPoints`、`FreeOrderCancelEntryGateAcceptanceTest`、`FreeSessionCancelReleaseV2Test`（UT）；`scripts/e2e/free_cancel_acceptance.py`（手动） | FG, UT | 已覆盖(真库：主理人侧)。玩家侧只有单测 |
| 取消通知 | 既有：`RefundNotificationMybatisIntegrationTest.cancellationRollbackDispatchesNeitherMoneyNorArrivalNotification`（FG 和 RS `port-prelaunch-69`）；`ClubEventTransferCancellationMySqlIT`（FG）；`ActivityCancelTest.cancellationAndFirstNoticeRollBackWhenSecondNoticeFailsThenRetryCommitsOnlyOnce`（UT） | FG, RS, UT | 已覆盖(真库) |
| 部分（部分章节售后） | 既有：`PartialChapterAftercareTest.playerAndOrganizerCreateSameScopeWithoutPayoutOrProgressChanges`、`RefundPartialClawbackTest`（UT）；退款余量：`RefundRetryAndRemainderMySqlIT`（FG 和 RS）；本 lane 新增的 `manualRefundCaseFreezesTheTicketInTheSameAtomicUpdate` 覆盖工单冻结核销 | FG, RS, UT, BT | 已覆盖(单测)。部分章节建单本身见第 5 节 |
| 替换锁定 | 既有：`LockedSupplyReplacementAcceptanceTest.replacementWithRaisedPriceIsRefusedWithoutAnyWrite` 等 4 条，`LockedSupplyReplacementPolicyAcceptanceTest` 8 条（UT） | UT | 已覆盖(单测) |

### E. 注销

| 场景 | 测试 | CI | 状态 |
|---|---|---|---|
| 清理只限本人 | **新增** `P0DeregistrationMySqlIT`：B 的 11 张表在注销前后、重新注册前后逐列不变；会话只撤 A。既有：`AccountDeregistrationCompletionPersistenceTest`、`AccountDeregistrationDueBatchAcceptanceTest`（H2，UT）；`T/business/it/AccountDeregistrationRaceMySqlIT`（BT） | BT, RS, UT | 新增 |
| 财务记录保留 | **新增** 同一用例：报名、订单、退款、银行提现、零钱提现、积分流水的行数与金额/状态列逐列不变，只抹 PII。此前只有“mapper 用的是 UPDATE”这一间接证据 | BT, RS | 新增 |
| 重新注册隔离 | **新增** 同一用例：同一 openid 再登录得到新 id，`del_flag=0`；openid 只命中新账号；新账号查不到旧报名、订单、退款；旧财务行仍挂在旧 id；新账号只有自己的注册赠送流水。此前**没有任何测试** | BT, RS | 新增 |

## 4. 覆盖统计

按场景行计（共 22 行：核销 5、跨账号 5、城市跑 3、自由探索 6、注销 3）：

| 状态 | 行数 | 行 |
|---|---|---|
| 本 lane 新增真库 IT | 14 | 核销 5，跨账号 3（订单票、核销记录、草稿），城市跑 2（恢复竞态、冻结），自由探索 1（价格/需补供给），注销 3 |
| 原本已有真库覆盖，本 lane 未新增 | 5 | 俱乐部数据，NPC 节点（mapper 层），结账到 prepay，退出/0 元（主理人侧），取消通知 |
| 只有单测（Mockito/H2） | 3 | 活动删除/发布状态 IDOR，部分章节售后，替换锁定 |

新增用例：5 个类，13 条，全部同时登记进 BT 和 RS。

## 5. 仍缺真库证明的后端缺口（未在本 lane 补）

1. **替换锁定**（`ChapterMerchantNodeServiceImpl.audit` 的替换和通知）、**部分章节售后建单**、**需补供给的恢复**（`topicSupplyAutoOnlineBlockReason`）：只有 Mockito。依赖链很长，补真库 IT 需要搭 cms_topic_node、merchant_chapter_application、通知表的成套夹具，建议单开一个 lane。
2. **玩家侧免费单取消**（`TeamGroupServiceImpl.cancelPaidRegistration`，FREE 订单）：真库只覆盖主理人侧；`free_cancel_acceptance.py` 是手动入口，没有接进任何 workflow。
3. **FREE_EXPLORE 结账到 prepay**：`RegistrationFundsMybatisIntegrationTest` 的夹具里没有 FREE_EXPLORE / 章节权益行，没有按必选章逐一断言 `registration_entitlement`。
4. **`NodeNpcService` 与活动删除 / 发布状态的 owner 闸**：只在 Controller/Service 的 Mockito 层；SQL 本身不按 owner 收口，这是设计如此。
5. **自由探索锁价后审核冻结**（`SC-FREE-FROZEN-GUARD` 的仓库口径）：只有 Mockito。

## 6. 发现的问题

**本 lane 没有发现生产缺陷。**13 条新用例第一次在真库上跑，全部符合生产代码的现有语义，没有因为断言不成立而需要改断言的情况。只记两条测试侧的观察，不是生产缺陷：

- `TandianriBatch0FundsMybatisIntegrationTest` 自建的 `coop_group_verify` 没有唯一键，和生产 DDL `uk_group_verify_activity_merchant (activity_id, merchant_member_id)` 不一致。团码幂等真正靠的是这个唯一键加 `INSERT IGNORE`，本 lane 的 `P0RedemptionChainMySqlIT` 改用了 base schema 原样 DDL 来证明它。生产 DDL 里 `activity_id` 允许 NULL，NULL 不参与唯一判定；当前 `GroupCodeServiceImpl.redeem` 一定会写入 activityId，所以不构成缺陷。
- 城市跑夹具如果用 MySQL 的 `now()` 写服务窗口，在 JVM 与 MySQL 时区不同（Asia/Shanghai 对 UTC）时会误判 `GAME_STATION_OUTSIDE_SERVICE_WINDOW`。本 lane 的新类改用 JVM 时间写入。既有的 `GameSessionIndependentRevisionMySqlIT` 仍然用 `now()`，在 RS 选 Asia/Shanghai 时可能出现同样的夹具假红（BT 只跑 UTC，不受影响）。

## 7. 每张卡剩下的真机检查（后端已由上表证明，人工只看这些）

| 卡 | 人工 / 真机仍需确认 |
|---|---|
| 扫码核销 | 真机摄像头扫码与小程序码解析；动态码刷新节奏和过期提示文案；商家端成功、已核销、已取消三种提示的 UI；弱网下重复点击时按钮的防抖表现 |
| 团码核销 | 领队端出码二维码清晰度和 5 分钟倒计时；商家端扫团码后“已记录接待该团 / 已记录过”的展示；两台真机同时扫时的界面提示 |
| 撤销核销 | 后台撤销入口的二次确认、撤销原因填写；玩家端票状态回到“未核销”的刷新时机 |
| 跨账号 | 用两个真实微信号切换登录后，分享链接、订单详情、草稿箱、俱乐部管理页对他人数据的空态和报错文案；不出现短暂闪现他人数据 |
| 城市跑恢复 / NPC / 冻结 | 两台导演或商家终端同时点“恢复”时，后到一方看到“站点已在接待”的提示；NPC 语音、形象在真机上的加载；结束后玩家端按钮置灰、提交入口消失 |
| 自由探索价格 / 结账 | 锁价页“需补供给”的阻断提示；结账页金额、积分展示；**真实微信支付弹窗与回调（CI 不调支付，必须真机）** |
| 售后（退出 / 0 元 / 取消通知 / 部分 / 替换锁定 / 需补） | 订阅消息和站内信在真机上的到达与跳转；0 元退出不出现支付或退款入口；部分售后、替换锁定、需补供给的页面文案；上表第 5 节缺口对应的路径需要人工完整走一遍 |
| 注销 | 短信或微信二次确认的真机流程；7 天冷静期展示与撤回；到期后旧登录态在真机上被踢下线（Redis 会话撤销只有 Mockito 证明）；同一微信号重新进入是全新账号，看不到历史订单 |
