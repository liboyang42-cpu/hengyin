# Lane AC2：P1 发布验收卡后端自动化覆盖（实施文档一 §9.1 P1 组，36 张）

- 分支：`feature/lane-ac2-p1-chains-20261022`（基于 `origin/master` = `f0b43d62`），一个 DRAFT PR。
- 范围：只做**后端半段**。真机 / UI / 支付 / 线上环境的检查留在文末「仅真机」清单，由人工执行。
- 兄弟车道 AC1（`feature/lane-ac1-p0-chains-20261022`）负责 P0（核销、跨账号、结账/售后、注销）。本车道没有新建或修改 AC1 的测试文件；注销只补了 P1 隐私卡 PR05 需要的「申请幂等 / 仅本人撤回」两点，在本车道自己的新类里。
- 规则遵守：
  - 只改测试代码与 CI 名单；不改生产代码、mapper XML、`sql/` 迁移，不碰生产库，没有密钥。
  - 没有新增 it-schema 文件，所以不需要登记 `ci/migration_ledger.py`。表结构一律取仓库真 DDL：`sql/base_schema_20260818.sql` 加 `chengyinhub-admin/sql/migration_*.sql` 里的 ALTER，按日期顺序执行；另外复用已登记为 `TEST_FIXTURE` 的 `it-schema/generic-route-runtime-it-schema.sql`、`journey-full-flow-it-schema.sql`、`run41-prod-ddl.sql`。
  - 测试暴露的生产缺陷只记录证据，不修。
  - 没有 `@Disabled` / `assume`，没有 `@Nested`（因此不需要用 `Outer*` 选类）。

## 新增真库 IT（全部登记进 `ci/backend-test.sh` 的 `MYBATIS_ITS`）

CI 由 `ci.yml` 的 **Backend tests** job 执行：mysql:8.0.45 一次性容器，跑完由 `ci/assert_it_ran.py` 回读 surefire，要求每个类实跑条数大于 0 且 skipped = 0。

| 类 | @Test | 覆盖的卡 | 本地结果（MySQL 8.0.45） |
|---|---|---|---|
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CityDirectorConsoleMySqlIT.java` | 6 | D1 D2 D3 D4 D5 D6 D8 D9 · M5 M6 · P1 P2 P3 P9 | tests=6 failures=0 errors=0 skipped=0 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ClubJoinGovernanceMySqlIT.java` | 9 | C1 C2 C3 C6 | tests=9 failures=0 errors=0 skipped=0 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/FreeExploreNodeSupplyMySqlIT.java` | 8 | M1 M2 M3 M4 | tests=8 failures=0 errors=0 skipped=0 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/JourneyThoughtProgressMySqlIT.java` | 3 | P5 P6 P7 | tests=3 failures=0 errors=0 skipped=0 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PrivacyVisibilityExportMySqlIT.java` | 4 | PR02 PR04（部分）PR05 | tests=4 failures=0 errors=0 skipped=0 |

共 30 条新真库用例。

**做法：**
- 测试驱动真实 Service 实现（`GameSessionRuntimeServiceImpl`、`ClubMemberServiceImpl`、`ClubGovernanceServiceImpl`、`ChapterMerchantNodeServiceImpl`、`MerchantOfferServiceImpl`、`TopicRouteRuntimeServiceImpl`、`RoamLocationConsentService`、`ConsentRecordServiceImpl` 与注销服务）。
- 装配方式：真 mapper XML，加上 `TransactionInterceptor` 事务代理。
- Mockito 只替换非存储的协作者：IM 投递、内容安全、通知、能力判定、活动行读取。
- `CityDirectorConsoleMySqlIT` 复用 `GameSessionLedgerMySqlFixture`。夹具只多暴露了实现对象引用和 DDL 截取方法，`GameSessionCityEndMySqlIT` / `GameSessionScanLegalMySqlIT` 回归仍为绿。

**负控（逐类做过「临时改坏 → 看到红 → 还原」）：**
- 导演台：本地临时删掉 `insertBroadcastDeliveries` 的已付费过滤（只改本机，未提交），广播测试红：`expected: <[7, 9, 12]> but was: <[7, 8, 9, 12]>`，即未付费成员 8 收到了广播。
- 俱乐部：
  - 计数 +1 断言改成错值后红：`member_count 恰好 +1 ==> expected: <7> but was: <6>`。
  - 过期版本解封断言改坏后红：`expected: <1> but was: <0>`。
  - 跨俱乐部查工单断言改坏后红：`A 的工单在 B 查不到 ==> expected: not <null>`。
- 商家：临时 drop 唯一键 `uk_offer_merchant_chapter` 后，重复入驻测试红（`Expected IllegalStateException … nothing was thrown`）。这同时证明服务层没有自己的重复检查，唯一键是唯一一道防线。
- 念头：把念头块挪到节点块之前，P5 红（`Expected IllegalArgumentException … nothing was thrown`）。
- 隐私：把 40 天前的到访改成 20 天前，PR04 红：`expected: <[avatar-43, avatar-48]> but was: <[avatar-43, avatar-44, avatar-48]>`。

## 卡 → 测试 → CI job → 状态

图例：
- **COVERED-NEW**：本 PR 新增真库 IT。
- **COVERED**：已有测试覆盖，已核对引用。
- **PARTIAL**：已覆盖后端主路径，余项见备注。
- **DEVICE**：后端没有可测的行为，只能真机验证。
- **BUG**：测试暴露生产缺陷。

CI job 缩写：
- **BT** = `ci.yml` → Backend tests（必需检查。`mvn clean test` 跑全部单测，再加 `MYBATIS_ITS` 真库 IT）。
- **FUNDS** = `funds-mysql-it.yml` → Funds MySQL IT（按路径触发，fail-closed）。
- **RS** = `real-storage-it.yml`（手动入口）。
- **GP** = `gameplay-http-e2e.yml`（手动入口）。

单测路径前缀 `chengyinhub-admin/src/test/java/com/chengyinhub/`。

### 俱乐部（7）

| 卡 | 测试 | CI | 状态 |
|---|---|---|---|
| C1 入会申请通过 | `ClubJoinGovernanceMySqlIT.c1_approveJoinRequest_flipsPendingOnce_andBumpsOnlyThisClubsCount`、`c1_approveIsBlockedByActiveBanInThisClubOnly`；单测 `service/impl/ClubLifecycleServiceTest.approve_pendingRequestActivatesAndCountsExactlyOnce` 等 | BT | COVERED-NEW |
| C2 入会申请驳回 | `ClubJoinGovernanceMySqlIT.c2_rejectJoinRequest_persistsReview_andLeavesCountUntouched`；单测 `ClubLifecycleServiceTest.reject_pendingRequestDoesNotGrantMembershipOrCount`、`web/controller/api/PublicClubResponsePrivacyTest.rejectedJoinReasonBelongsOnlyToTheAuthenticatedApplicant` | BT | COVERED-NEW |
| C3 封禁 / 移出 | `ClubJoinGovernanceMySqlIT.c3_banRemovesOnlyThisClubsMembership_andIsScopedToThisClub`、`c3_deleteByClubAndMember_removesOnlyThatClubsRow`、`c3_unbanCas_staleVersionOrForeignClub_changesZeroRows`、`c3_duplicateBanRequestId_violatesUniqueKey`；单测 `service/ClubGovernanceServiceImplTest`、`access/club/ClubBanLeftoverRoleAccessTest` | BT | COVERED-NEW |
| C4 客户详情可见性 | 单测 `service/ClubCrmCrossClubIsolationTest`（SC-CL-CUSTOMER-DETAIL，3 条）、`ClubCrmPrivacyTest`；真库 `it/ClubCrmCheckinDetailMyBatisTest`（到店明细 SQL） | BT | PARTIAL：跨俱乐部隔离只在 mock 层验证。`ClubCrmMapper.selectClubCustomerSummary/Records` 的真库隔离留作后续 |
| C5 分享 | 后端没有分享接口（`onShareAppMessage` 在小程序）。接收方走 `/api/club/detail`：单测 `ApiClubLifecycleTest.publicVisibleOff_hidesTheClubFromListAndBlocksDetailForNonMembers`、`PublicClubResponsePrivacyTest.publicDetailReturnsWhitelistVoWithoutOwnerContactOrApplicationFields`；小程序 `tests/unit/v4-club-share.test.js` | BT | COVERED（后端半段）+ DEVICE |
| C6 举报 | `ClubJoinGovernanceMySqlIT.c6_clubReport_recordsCallerAsReporter_andReplayFindsSameRow`、`c6_pendingSubjectCase_doesNotMatchAcrossClubs`；单测 `service/ClubGovernanceReportAttributionTest`、`ApiClubReportTest` | BT | COVERED-NEW |
| C7 自由探索收件箱 | `it/CoopClubApplyInboxMySqlIT.ownerSeesOnlyApplicationsOnOwnTopicsAndApplicantSeesHistory`；`CoopMapperMyBatisIT`；`service/CoopListReadOnlyMybatisIntegrationTest`；单测 `ApiCoopCandidatesClubAppliesTest` | RS（Inbox IT）/ BT（CoopMapper）/ FUNDS（CoopListReadOnly） | COVERED（读侧）。`/api/coop/handle` 接受/拒绝的真库 CAS 留作后续 |

### 导演台（9）

| 卡 | 测试 | CI | 状态 |
|---|---|---|---|
| D1 准备 | `CityDirectorConsoleMySqlIT.prepareReadyRosterStartChainOnRealRows`：一局一行；站点只来自本主题上线且审核通过的节点；重复准备不新建；越权零写入。另有 `concurrentPrepareCreatesExactlyOneSessionAndOneStationSet`。单测 `GameSessionRuntimeServiceImplTest.prepareRejects*` | BT | COVERED-NEW |
| D2 开局 | 同上：未就绪拒绝；过期版本零写入；开局后 RUNNING、三站 ACTIVE、started_by=导演；重复开局零写入 | BT | COVERED-NEW |
| D3 名单 | 同上：只含已付费且在队的成员，没有隐私字段；未付费 / 退队 / 别场成员不进名单；未付费成员不能被分配角色 | BT | COVERED-NEW |
| D4 全员广播 | `CityDirectorConsoleMySqlIT.broadcastReachesOnlyPaidActiveMembersInScope` | BT | COVERED-NEW |
| D5 定向广播 | 同上（按队伍 / 按角色；别场队伍零写入）。另有 `sameRequestRetryResendsOnlyTheFailedDelivery`（同 requestId 只补发失败投递，已送达行不动）。单测 `GameSessionStationIncidentScopeTest`（SC-CITY-BROADCAST-TARGET） | BT | COVERED-NEW |
| D6 现场事件 | `CityDirectorConsoleMySqlIT.pauseAndResumeTouchOnlyTheChosenStationAndPlayerReadsItBack`：导演台重复暂停得到 FAILED 回执且版本不变；导演台恢复。路线模式见 `GenericRouteRuntimeMySqlIT`（RUN_PAUSE / RUN_INTERRUPT）。单测 `GameSessionStationIncidentScopeTest` | BT | COVERED-NEW |
| D7 结束 | `service/impl/GameSessionCityEndMySqlIT.finishFreezesRecapToOutcomeRowsAndLeavesOrdersStockRewardsAsFulfilled` | BT | COVERED |
| D8 冲突规则 | `CityDirectorConsoleMySqlIT`（重复 / 并发准备、过期版本开局、重复开局）；`GameSessionIndependentRevisionMySqlIT.negativeVersionsAndStrictRoleDirectorAndMerchantCommandsStillRejectBeforeWrites`；GP `FreezeGameHttpE2eIT.onceOnlySettlementUnderRetryRaceCancelAndConflicts` | BT / GP | COVERED-NEW。待产品确认：代码里没有「同一玩家不得同时在两场城市局」的规则，见下文 |
| D9 存活范围 | `CityDirectorConsoleMySqlIT.broadcastReachesOnlyPaidActiveMembersInScope`（退款 / 退队后立即离开投递范围；范围为空时报错且整笔回滚）；路线模式计数范围见 `GenericRouteRuntimeMySqlIT.toolCountsFollowTheirDeclaredOwnerOrWorldScope` | BT | COVERED-NEW |

### 商家节点（6）

| 卡 | 测试 | CI | 状态 |
|---|---|---|---|
| M1 自由探索节点编辑 | `FreeExploreNodeSupplyMySqlIT.m1_editApprovedNode_updatesOnlyMyRow_resetsAuditTraceInDb_neighboursByteIdentical`；单测 `service/FreeExploreSupplyNodeTermsAcceptanceTest$NodeEdit` | BT | COVERED-NEW |
| M2 节点取消 | 取消本身是前端行为，不写库（`chengyinhub-xcx/tests/unit/v4-free-node-cancel.test.js`）。后端半段：`m2_blankAddress_rejected_nodeTableUnchanged`、`m2_invalidCoordinates_rejected_nodeTableUnchanged` | BT | COVERED-NEW + DEVICE |
| M3 供给 | `m3_enroll_persistsServerResolvedFields_andFreezesChapterTerms`、`m3_duplicateEnroll_rejectedByUniqueKey_noSecondRow`、`m3_withdraw_touchesOnlyCallersOwnOffer`、`m3_withdrawSomeoneElsesOffer_rejected_nothingChanges` | BT | COVERED-NEW。只覆盖 TRAFFIC 老路径；V1 招募 PERK 路径仍只有 mock |
| M4 条款 | `m4_sameInvite_sentAndReceivedProjectionCarryIdenticalTerms_strangerSeesNone`（mapper 层）；单测 `FreeExploreSupplyNodeTermsAcceptanceTest$Terms`（6 条） | BT | COVERED-NEW |
| M5 城市局节点就绪 | `CityDirectorConsoleMySqlIT.prepareReadyRosterStartChainOnRealRows`：别家商家零写入；接受 → 就绪，容量 / 服务时段落库，站点版本 +2 | BT | COVERED-NEW |
| M6 城市局节点暂停 | `CityDirectorConsoleMySqlIT.pauseAndResumeTouchOnlyTheChosenStationAndPlayerReadsItBack`：别家商家越权被拒；只动本站一行，邻站逐列不变 | BT | COVERED-NEW |

### 玩家（10）

| 卡 | 测试 | CI | 状态 |
|---|---|---|---|
| P1 城市局进度 | `CityDirectorConsoleMySqlIT.playerViewIsScopedToOwnTeamAndOwnAccount`：本人读回提交与「待核验」状态。另有 `GameSessionScanLegalMySqlIT`、`GameSessionCityEndMySqlIT` | BT | COVERED-NEW |
| P2 队伍间隔离 | 同上：别队视图的 teamActions 只含本队成员，不含 A 的答案，站点个人态不受影响 | BT | COVERED-NEW |
| P3 账号间隔离 | 同上：同队另一账号的 mySubmissions 为空，读不到答案文本；非本场账号读视图被拒 | BT | COVERED-NEW |
| P4 NPC 玩家流程 | `it/NodeNpcMapperMyBatisIT`（未审 / 停用不下发、scope 隔离）、`it/NpcChatUserDataMyBatisIT`（`controllerEntry_loginAOnlySeesAndDeletesOwnData`）；单测 `NpcChatServiceTest`、`ApiPlayNodeNpcPreferenceTest` | BT | PARTIAL：`npc_chat_request` 租约生命周期只有 mock。V1 中 `chatOn()` 写死关闭 |
| P5 念头领取 | `JourneyThoughtProgressMySqlIT.claimIsRefusedBeforeNodeCompletionAndPersistsOnceAfterward`；单测 `ThoughtClaimOnceAcceptanceTest` | BT | COVERED-NEW |
| P6 念头 last（步数基线） | `JourneyThoughtProgressMySqlIT.sameDayLowerReadingKeepsBaselineAndNextDayCountsFully` | BT | COVERED-NEW |
| P7 念头步数 | `JourneyThoughtProgressMySqlIT.reachingNeedMarksDoneAndRaisesLuckMaxExactlyOnce` | BT | COVERED-NEW |
| P8 漫游结束 | `web/controller/api/RoamSessionWriteMybatisIntegrationTest`（`finishedArchiveIsIdempotent…`、`finishWinningLockRejectsLateWrite`、`snapshotFailureRollsBackSessionAndGrowthTogether`、`rejectsOtherOwnerMissingAndEndedWithoutRows`）；单测 `RoamFinishOnceAcceptanceTest` | FUNDS | COVERED |
| P9 进度读回（暂停 / 恢复后刷新） | `CityDirectorConsoleMySqlIT.pauseAndResumeTouchOnlyTheChosenStationAndPlayerReadsItBack`（刷新后看到暂停 / 恢复，版本与库一致）；漫游恢复 `RoamSessionWrite…sameClientKeyRecoversTheExactBootstrapSession` | BT / FUNDS | COVERED-NEW |
| P10 进度读回（断线续玩 / 结局） | `it/JourneyFullFlowMySqlIT`（每步用新 runtime 读 `state()` / `ending()`）、`mapper/PlayerRunSessionOrderingMybatisIntegrationTest`、`ShareSnapshotRunSessionMybatisIntegrationTest.runSessionUpsertReadAndClearAreScoped`、`service/A03EndingSnapshotMySqlIT`、`ThemeRuntimeMySqlIT.ownReplayIsFreshAndKeepsOldResult` | BT / FUNDS / RS | COVERED |

### 隐私（4）

| 卡 | 测试 | CI | 状态 |
|---|---|---|---|
| PR02 按用途撤回 | `PrivacyVisibilityExportMySqlIT.revokingRoamLocationLeavesOtherPurposesUnchangedAndIsAppendOnly`；`mapper/ShopDayMerchantDataBoundaryMyBatisIT.latestConsentIsIsolatedPerMerchantAndAppendOnlyRevokeWins`、`MerchantCrmContactRevealMySqlIT`、`RoamLocationRetentionMySqlIT.revokedConsentRejectsOldAgreeRetry…` | BT / FUNDS | COVERED-NEW |
| PR03 位置可见性 | `RoamPresenceRevokeDurabilityMySqlIT`（10 条）、`service/RoamPresenceRevokeJobMySqlIT`、`RoamLocationRetentionMySqlIT`；单测 `RoamPresenceControllerTest`、`RoamLocationRevokeHidesPresenceTest` | FUNDS | COVERED |
| PR04 历史可见性 | `PrivacyVisibilityExportMySqlIT.shopVisitorAvatarsHonourWindowShopSourceAndDeletedAccounts`（30 天窗口 / 别店 / 别来源 / 注销账号排除）；`MemberPrivacyMyBatisIT`、`RoamLocationRetentionMySqlIT.expiredOrOrphanVisitCannotExposeIdentityBeforeCleanup…` | BT / FUNDS | **BUG**：撤回 `roam_location` 后，到访头像仍然露出，见下文 BUG-AC2-01 |
| PR05 导出 / 删除申请 | `PrivacyVisibilityExportMySqlIT.exportNeverCrossesMembersAndForeignCursorIsRejected`、`deregistrationApplyIsIdempotentAndOnlyOwnerCancelRestores`；`web/controller/api/PersonalExplorationExportMySqlIT`（7 条）、`NpcChatUserDataMyBatisIT`、`AccountDeregistrationRaceMySqlIT` | BT / RS | COVERED-NEW |

**合计 36 张：**
- COVERED-NEW：27 张（C1-3、C6，D1-6、D8、D9，M1-6，P1-3、P5-7、P9，PR02、PR05）。
- COVERED：6 张，已有覆盖、本次只核对引用（C5、C7、D7、P8、P10、PR03）。
- PARTIAL：2 张（C4、P4）。
- BUG：1 张（PR04）。

其中 C5、M2 另有真机部分。

## 发现的问题

### BUG-AC2-01（PR04）：撤回位置同意后，商家卡「谁在这儿打过卡」头像仍露出

- **位置：** `chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml` 的 `selectShopVisitorAvatars`，供 `POST /api/roam/shop/visitors` 使用（`ApiRoamController.shopVisitors`，`ApiRoamController.java:322-336`）。
- **原因：**
  - SQL 只按会话状态和 30 天窗口、`ums_member.del_flag=0`、头像非空过滤，**不读 `consent_record`**。
  - 控制器拿到结果后也不过滤。
  - `RoamOrphanLocationRetentionMapper.shops` 只清理孤儿或过期的到访，不处理撤回。
- **影响：** 撤回者的头像会继续露出——已结束的会话最多 30 天，进行中的会话一直露出。
- **复现：** 本地 MySQL 8.0.45 + 真服务 `RoamLocationConsentService` 写入。
  - 41 / 42 / 43 三人同意 `roam_location`，都到访 source_type=1、source_id=501 的店：
    - 41：已结束会话，2 天前。
    - 42：进行中会话。
    - 43：已结束会话，3 天前。
  - 41 通过真服务 REVOKE；42 关闭在场可见（`presence_visible=0`）。
  - 库内状态：
    - `consent=[…{id=9,member_id=41,roam_location,REVOKE}]`
    - `session=[{41,status=1,presence_visible=0},{42,status=0,presence_visible=0},{43,status=1,presence_visible=0}]`
  - 结果：`selectShopVisitorAvatars(1,[501],10) => [avatar-42, avatar-41, avatar-43]`。
  - 期望：撤回者 41 不应出现。
- **处理：**
  - 按车道规则不修。
  - 失败断言没有提交，也没有用 `@Disabled` 压住。`PrivacyVisibilityExportMySqlIT` 类注释记录了这个缺口。
  - 修复时把「41 撤回后不在头像里」加回该类即可作为回归。
  - 「关闭在场可见是否也应从头像隐藏」是产品口径问题：`presence_visible` 默认为 0，只有显式开启才为 1，直接按它过滤会把几乎所有人都隐藏，需要产品先定口径。

### 待产品确认（不是缺陷）

- **D8：** 代码里没有「同一玩家不能同时身处两场并发城市局」的规则。队伍按活动划分（`owner_type=2 AND owner_id=activityId`），`selectActiveTeamId` 取 `order by id desc limit 1`。如果验收卡要求跨场互斥，需要先定规则再补测试。
- **D5：** `isActiveTeam` 不检查付费。队伍里没有已付费成员时，定向广播得到 `GAME_BROADCAST_NO_RECIPIENT` 并整笔回滚，测试已覆盖。这是预期行为，记录在此供核对。

## 仅真机（后端无法替代）

- C5 分享卡片唤起、分享路径与落地页渲染；M2 前端取消不提交。
- 导演台、商家台、玩家端的页面渲染、按钮可用态和文案（含暂停原因、预计恢复时间的展示）。
- 广播在真机上的 IM 送达与订阅消息（IM 外部通道在 IT 中是 mock）。
- 念头步数来自微信运动的真实读数（`VerifiedWeRunService` / 真机授权）；P6 跨日边界在真机时区下的表现。
- NPC 对话（P4）的 AI 供应商回复与内容安全；V1 线上开关。
- 位置相关（PR03 / P8）：真机定位授权与撤回、后台定位停止、在场可见开关的 UI。
- PR05 导出文件在真机上的下载 / 保存与注销流程的短信、客服环节。
- 所有付费前置（报名支付）都按规则不在本车道触发。

## CI

见 PR 描述与下方回执（PR 打开后更新）。
