# Lane H5 — 10-14 基础集成 PR #8 / #9 / #14 / #15 按当前消费者拆分收口

- 参照 master：`063a6c3c`（含 #408 master-port 整合）
- 方法（实施文档一 §6.1「按当前消费者拆」）：逐 PR 读描述与完整文件清单（#8 97 / #9 99 / #14 64 / #15 61 个文件），
  对每个文件比对 PR head blob 与 master blob（IDENTICAL / DIFFERS / ABSENT），DIFFERS 与 ABSENT 再逐行/逐符号在 master 上 `git grep` 定位改名或迁址后的当前消费者。
- 本 lane 分支：`feature/lane-h5-1014-8-9-14-15-20261022`。**只加回归测试，不改任何付款/退款/结算/提现行为与路由，不动 app.json / 分包 / C00 保留文件，未重建任何台账/基线。**
- 源 PR 均未合并、未评论、未改标签。

## 汇总

| PR | 子项 | COVERED | MISSING | OBSOLETE | NEEDS_OWNER_APPROVAL |
|---|---|---|---|---|---|
| #8 A包原订单票务/资金恢复 | 23 | 17 | 0 | 2 | 4 |
| #9 C包AI原日补偿/漫游恢复 | 21 | 19 | 0 | 1 | 1 |
| #14 外壳/商家/俱乐部 | 10 | 6 | 1 | 2 | 1 |
| #15 漫游与功能入口 | 9 | 8 | 1 | 0 | 0 |
| **合计** | **63** | **50** | **2** | **5** | **6** |

两条 MISSING 都在小程序侧，移植会改动 C00 保留的 `scripts/uiaudit/action-ledger.json` 盘点摘要（`tests/unit/action-ledger.test.js`「release 前重建 inventory」在任何页面源码改动后必红）。
本 lane 不得提交重建后的台账，因此两项都以下方 CY-HANDOFF 交给 C00，随台账重建一并落地；本 PR 只提交不影响台账的回归测试。

## PR #8 — fix: A包原订单票务资格、维护与资金恢复

逐项明细（含 master 文件:行与测试）见 [lane-h5-pr8-detail.md](lane-h5-pr8-detail.md)。

| ID | 子项（当前消费者） | 状态 | 证据 / 说明 |
|---|---|---|---|
| A-01 | 未付关单扫描游标（OrderCloseTask） | COVERED | OrderCloseTask.java:48,62-68；OrderCloseUnpaidCursorTest |
| A-02 | 退款对账扫描游标（RefundReconcileTask） | COVERED | RefundReconcileTask.java:50,82-127；RefundReconcileTaskTest |
| A-03 | 已付缺码修补扫描（VerificationQrcodeRepairTask） | COVERED | VerificationQrcodeRepairTask.java:32-41；VerificationQrcodeRepairTaskTest |
| A-04 | 候补维护独立游标（ClubWaitlistServiceImpl.maintain） | COVERED | ClubWaitlistMaintenanceCursorTest |
| A-05 | 探店日票到期拒新使用（ExploreTicketUsePolicy / CurrentPlayRegistration） | COVERED | ExploreTicketUsePolicy.java；CurrentPlayRegistration.java:64 |
| A-06 | 扫码/详情入口不投影不可用探店票码（ApiRegistrationController / ApiVerifyController） | COVERED | ApiRegistrationController.java:533,1490；ApiVerifyController.java:110,133-136 |
| A-07 | 探店票不整单结算、自动核销排除 purchase_kind=3（RegistrationAutoVerifyTask） | COVERED | CmsRegistrationMapper.xml:926,957；**本 PR 新增回归** RegistrationAutoVerifyTaskTest |
| A-08 | NPC 当前报名资格要求 cancel_time is null | COVERED | PlayTeamMapper.xml:267 |
| A-09 | 商家订阅 start<=now<end、续费 UPSERT 2 行 | COVERED | MerchantSubscriptionMapper.xml:31；MerchantCommerceServiceTest |
| A-10 | 官方活动 V1 报名锁行计数 | COVERED | OfficialEventServiceImpl.java:103-124；OfficialEventContractTest |
| A-11 | 奖励券来源发放 coupon_source_grant + 迁移 | COVERED | migration 与表已在 master；ci/migration-dependencies.json |
| A-12 | 结算批次占位/绑定回读/回填守卫（SettlementServiceImpl.generateBatch） | COVERED | SettlementServiceImpl.java:68-90；**本 PR 新增回归** SettlementServiceImplTest ×2 |
| A-13 | 提现明确失败：CAS 3→2 与回补同事务（CashWithdrawServiceImpl.refundFailedClaim） | COVERED（已重设计） | CashWithdrawServiceImpl.java:338-350；**本 PR 新增回归** CashWithdrawStateMachineTest（回补 CAS 落空不加余额不告警） |
| A-14 | 提现 prepare：原行与 CW/CD 单号同事务、更新须回 1 才扣款 | **NEEDS_OWNER_APPROVAL** | master CashWithdrawServiceImpl.java:131-145 分开写且不校验更新行数；syncOne 有 CW+id 兜底。改动属资金行为 |
| A-15 | 报名支付回调走 inbox fence（register 独立提交 → process 同事务 DONE） | **NEEDS_OWNER_APPROVAL** | master CmsRegistrationServiceImpl.java:2074-2105 仍 @Transactional + 仅 register 去重；调用方 WechatPayServiceImpl:750、OrderCloseTask:182 |
| A-16 | inbox register 身份冲突检测（同 hash 不同 biz 抛错） | **NEEDS_OWNER_APPROVAL** | master WxPaymentCallbackInboxServiceImpl.java:46-58 无回读比对 |
| A-17 | 活动取消 PAYING 追补走 MANDATORY 事务内确认 | **NEEDS_OWNER_APPROVAL** | 仅在采纳 A-15 时需要；master TeamGroupServiceImpl.java:884 |
| A-18 | 报名成功通知意图写入创建事务 | OBSOLETE | master 改为支付成功后 committedBusinessNotice「registration-signup」 |
| A-19 | 提交后生成核销码新事务、空 URL / 0 行抛错 | COVERED（已重设计） | CmsRegistrationServiceImpl.java:2029-2067,2494-2499 |
| A-20 | 自玩创建 insert!=1 抛错 | COVERED | CmsRegistrationServiceImpl.java:882；SelfPlayPassIdempotencyTest |
| A-21 | 小程序 baoming 原 registrationId 与稳定 eventKey | COVERED | baoming.js:1787,1826；baoming-submit-inline-error.test.js |
| A-22 | 台账仅保原/后置条目（无代码差） | COVERED | 无可移植内容 |
| A-23 | docs/verification/mini-foundation-cloud/A 证据 | OBSOLETE | 绑定 PR head SHA 的分支证据，不描述 master |

## PR #9 — fix: C包AI原日补偿与漫游玩法恢复（含暂停迁移）

逐项明细见 [lane-h5-pr9-detail.md](lane-h5-pr9-detail.md)。57/99 文件与 master 字节一致（含 4 个迁移、两个 quartz 任务类）；其余 42 个文件的 PR 新增行逐条在 master 找到或确认为改名/后续重写。

| ID | 子项（当前消费者） | 状态 | 证据 / 说明 |
|---|---|---|---|
| C-01 | AI 原日/原池/nonce 预留收据（AiGatewayServiceImpl.consumeQuota） | COVERED | 见明细 |
| C-02 | ai_quota_reservation + 补偿任务 aiQuotaRecoveryTask（status='1' 暂停） | COVERED | 迁移一致；AiQuotaRecoveryTask.java:7。**启用调度属 owner 决定** |
| C-03 | 网关退/留分类（模型错误时） | **NEEDS_OWNER_APPROVAL** | 代码已在 master 且更严：模型错误保留预留（AiProviderOutcomeUnknownException，测试 modelErrorKeepsOriginalReservationForUnknownOutcome），PR 版本为退还。D-08 未决，需 owner 确认 |
| C-04 | 拍照校验独立配额池（PhotoCheckVisionService） | COVERED | PhotoCheckQuotaPoolTest |
| C-05 | 玩家主题草稿走 Creator 池（AiCreatorServiceImpl） | COVERED | 文件一致 |
| C-06 | NPC 租约 token/fence（NpcChatService） | COVERED | NpcChatRequestMapper.xml:82,89；NpcChatServiceTest |
| C-07 | NPC 店铺已存答案只读重放（ApiAiNpcController） | COVERED | ApiAiNpcPassExpiryTest |
| C-08 | 漫游 POI 掉落原来源投递 roam_reward_delivery + 游标 | COVERED | ApiRoamController.java:206-246；RoamRewardDeliveryService |
| C-09 | 漫游徽章投递 roam_badge_delivery | COVERED | RoamBadgeDeliveryTest |
| C-10 | 结算锁序 member→session（改名 lockClaimOwner） | COVERED | ApiRoamController.java:753 |
| C-11 | 结束后迟到记录归档 roam_session_record_archive | COVERED | RoamSessionArchiveTest |
| C-12 | 小程序 tile dirty 仅在落盘校验后清除 | COVERED | roam/index.js:1497-1501；roam-recovery-flow.test.js |
| C-13 | 小程序结束会话归档上传/回滚/徽章收据门控 | COVERED | roam/index.js:1698-1774,2002-2030 |
| C-14 | 真实定位启动竞态守卫（改名 _realStarting/_realStartEpoch） | COVERED | roam/index.js:3779-3862；roam-track-filter.test.js |
| C-15 | 漫游缓存 epoch 失效 | COVERED | roam-tile-memory-sync.test.js |
| C-16 | WeRun 旧解密整数校验 | COVERED | WeRunDecryptorTest |
| C-17 | WeRun PKCS#7 逐字节校验 | COVERED（已重设计） | 严格路径逐字节；旧 decrypt 有意宽松且有测试钉住，不移植 |
| C-18 | 旧计步「须今天且不早于 syncedAt」防重放 | COVERED | AdvancedGameRuntimeServiceImpl.java:1814-1821；缺专门测试（见下「后续」） |
| C-19 | 当前使用资格（免费报名 payment0 保持 status/owner） | COVERED（已扩展） | CurrentPlayRegistration.java:19-71 |
| C-20 | 商家节点配额计数 FOR UPDATE | COVERED | RoamQuotaCurrentReadContractTest |
| C-21 | docs/verification/mini-foundation-cloud/C 证据 | OBSOLETE | 分支证据，不描述 master |

## PR #14 — fix: 任务2 外壳/商家/俱乐部

| ID | 子项（当前消费者） | 状态 | 证据 / 说明 |
|---|---|---|---|
| A2/B3 | 装修页物理返回守卫（pages/merchant/decor） | COVERED | decor/index.wxml:549-553、index.js:774 onNativeBackGuard；merchant-decor-back-guard-contract.test.js |
| A5/B10 | 媒体预览统一 current（utils/media-preview.js） | COVERED | 文件一致；decor/index.js:6、publish/activity/index.js:2 等接入；mini-media-preview-recovery.test.js |
| B7a | 定位兜底：pages/searchmap 初始化走 last-location + 提示条 | **MISSING** | master searchmap/index.js:58,141 仍写死坐标；utils/location/last-location.js 已在 master（roam、nearby、fabu 已用）。补丁已写好并本地验证，因改动台账摘要交 C00（见 CY-HANDOFF） |
| B7b | 定位兜底：pages/square/list mapLatitude/mapLongitude | OBSOLETE | square/list/index.wxml 无 `<map>`，两字段无渲染消费者 |
| B9 | 退款申请点请求 refund/refundRejected 订阅 | **NEEDS_OWNER_APPROVAL** | PR 本身也跳过；入口在 subpackageMember 订单页，属退款流程，需 owner 决定 |
| B11 | wx:key 对象数组 `*this`→index | COVERED | 真缺陷 merchantinfo sysCategoryList 已是 `wx:key="index"`（merchantinfo.wxml:165）；剩余 `*this` 均为原始值数组，合法 |
| C8a | 据点打卡码文本兜底（merchant/citynode） | COVERED | citynode/index.js:8,372（迁址 pages/merchant/utils/code-text.js）；wxml:127 cy-code-text；code-text-fallback-contract.test.js |
| C8b | 站点打卡码文本兜底（topic/merchantinfo） | COVERED | merchantinfo.js:21,3871；wxml:499-504 长按复制 |
| C8c | 团码 club/group-code 文本兜底 | OBSOLETE | PR 刻意未做：与既有契约 CU-C-78 冲突（无码图即错误、不铺长令牌） |
| C10 | 俱乐部置顶公告（club/detail） | COVERED | club/detail/index.json:5、wxml:100；club-pinned-notice.test.js |
| C3 | 门店认领 sheet（merchant/apply） | COVERED | pages/merchant/components/merchant-claim-sheet（逻辑迁址 pages/merchant/utils/merchant-claim.js）；merchant-claim.test.js |

> 注：上表 11 行中 C8a/C8b 合并计为 1 个子项（打卡码文本兜底的两个消费者），计数口径 10。

## PR #15 — feat: 任务3 漫游与功能入口

| ID | 子项（当前消费者） | 状态 | 证据 / 说明 |
|---|---|---|---|
| B8 | ROAM_POI_RADIUS_M 共用（pages/roam） | COVERED | roam/index.js:113,1398（已扩展为 BL-01 筛选半径回落常量）,2586；**本 PR 新增回归** lane-h5-roam-features-regression.test.js |
| C9 | 首页继续上次漫游条 | COVERED（迁址） | subpackageRoam/components/scene-roam-resume-bar；pages/index/index.json:17、wxml:111；**本 PR 新增回归** |
| C5a | 官方路线列表/详情页与整形 | COVERED | subpackageRoam/official-routes、official-route、utils/official-route.js 与 PR 一致，已在 app.json:276-277；**本 PR 新增整形回归** |
| C5b | 漫游页「官方线路」入口 | **MISSING** | master 无任何 navigateTo('/subpackageRoam/official-routes/index')，页面不可达。补入口需改 pages/roam/index.{js,wxml} 并重建台账 → 交 C00（见 CY-HANDOFF） |
| C6 | 集邮册「收到的」tab | COVERED | scene-roam-stamp-album/index.js:68；stamp-album-received-end.test.js |
| C1 | NPC 清除对话 | COVERED（迁址） | subpackageMember/components/cy/npc-chat/index.js:143-230；danger-actions 'ai.npc.chatData.delete'；**本 PR 新增回归** |
| C2 | 举报队伍 | COVERED（迁址） | subpackageMember/components/cy/team-report-sheet；team/detail wxml:65,76；team-report-sheet-contract.test.js |
| C4 | 积分明细 | COVERED | subpackageMember/points-history；profile/index.js:1527；points-history-*.test.js |
| C7 | 主题状态键 | COVERED | pages/publish/components/topic-state-keys（一致）；fabu topic-detail-sheet.wxml:218；topic-state-keys-contract.test.js |

## NEEDS_OWNER_APPROVAL 清单

1. **#8 A-14** 提现 prepare 原行与 CW/CD 单号同事务、更新须回 1 才扣款（资金）。
2. **#8 A-15** 报名支付回调接入 inbox fence（register 独立提交、process 同事务 DONE）（支付）。
3. **#8 A-16** inbox register 身份冲突检测（支付）。
4. **#8 A-17** 活动取消 PAYING 追补走 MANDATORY 事务内确认（仅随 A-15）（支付）。
5. **#9 C-03** 模型错误时 AI 配额退还还是保留——master 现为保留，PR 为退还；D-08 未决。另：C-02/C-08 的 aiQuotaRecoveryTask、roamRewardDeliveryTask 仍为暂停（status='1'），是否启用由 owner 决定。
6. **#14 B9** 退款申请提交点加 refund/refundRejected 订阅请求（退款流程，subpackageMember）。

## 本 PR 提交内容（只加测试）

| 文件 | 覆盖 |
|---|---|
| chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/SettlementServiceImplTest.java | #8 A-12：占位写入 0 行 / 无主键时不绑定不回填；bindBatch 行数与回读不一致时不回填 |
| chengyinhub-admin/src/test/java/com/chengyinhub/business/service/CashWithdrawStateMachineTest.java | #8 A-13：渠道失败抢占成功但 3→2 回补 CAS 落空 → 不加余额、不告警、只尝试一次 |
| chengyinhub-admin/src/test/java/com/chengyinhub/business/task/RegistrationAutoVerifyTaskTest.java | #8 A-07：自动核销扫描与置位两条语句都排除 purchase_kind=3 |
| chengyinhub-xcx/tests/unit/lane-h5-roam-features-regression.test.js | #15 B8 / C9 / C5a / C1 迁址后的当前消费者回归 |

本地：`node --test chengyinhub-xcx/tests/unit/lane-h5-roam-features-regression.test.js` 5/5 通过。全量 `node --test "tests/unit/**/*.test.js"`（chengyinhub-xcx）13479/13479 通过；Java 测试以本 draft PR 的 GitHub-hosted CI 为准。

## 后续（未在本 PR 做）

- #9 C-18 旧计步防重放缺专门测试：AdvancedGameRuntimeServiceImplTest 需构造非 v1 SUBMIT_STEPS + 旧解密密文（昨天时间戳 / 早于 syncedAt）。涉及 WeRunDecryptor 静态解密的夹具改造，未在无本地 JUnit 的情况下盲写。
- #8 非资金测试缺口：A-19 空 URL/0 行守卫、A-03 修补扫描探店到期谓词、A-06 详情页码遮蔽。

<!-- CY-HANDOFF
to: C00（台账 / app 壳负责人）
from: lane-h5
why: 以下两项为 MISSING 且非资金/非策略，但改动小程序页面源码会使 scripts/uiaudit/action-ledger.json 的 inventory 摘要失效
     （tests/unit/action-ledger.test.js「release 前重建 inventory」必红）。本 lane 不得提交重建后的台账，请随台账重建一并落地。

1) #14 B7a pages/searchmap 定位兜底（已在本 lane 本地验证：专项 5 条测试通过；全量单测仅台账摘要一条红）
```diff
diff --git a/chengyinhub-xcx/pages/searchmap/index.js b/chengyinhub-xcx/pages/searchmap/index.js
index eb6b2c97..8076be72 100644
--- a/chengyinhub-xcx/pages/searchmap/index.js
+++ b/chengyinhub-xcx/pages/searchmap/index.js
@@ -7,6 +7,7 @@ const { openScene } = require('../../utils/scene-entry.js');
 const { buildNodeLevelMarkerStyle } = require('../roam/node-level-style.js');
 const { isRecordList } = require('../../utils/response-shape.js');
 const { appendByIndex } = require('../../utils/list-append.js');
+const lastLocation = require('../../utils/location/last-location.js');
 const DATE_YEARS = Array.from({ length: 81 }, (_, index) => 1970 + index);
 const DATE_MONTHS = Array.from({ length: 12 }, (_, index) => index + 1);
 
@@ -55,8 +56,10 @@ Page({
     searchKeyword: '',
     
     // 地图相关数据
-    latitude: 31.230416, // 上海坐标
-    longitude: 121.473701,
+    // 初始坐标由 onLoad 经 last-location 兜底（上次成功位置 → 固定坐标），不再写死城市
+    latitude: lastLocation.FIXED.latitude,
+    longitude: lastLocation.FIXED.longitude,
+    locationHint: '',
     scale: 14,
     markers: [],
     activityMarkers: [],
@@ -138,8 +141,8 @@ Page({
     
     // 地图区域信息
     regionInfo: {
-      latitude: 31.230416,
-      longitude: 121.473701,
+      latitude: lastLocation.FIXED.latitude,
+      longitude: lastLocation.FIXED.longitude,
       latitudeDelta: 0.1,
       longitudeDelta: 0.1
     },
@@ -454,7 +457,9 @@ Page({
         if (!this._isCurrentSpatialInput(spatialEpoch)) return;
         const { latitude, longitude } = res;
         this._hasLocation = true;
+        lastLocation.save(res);
         this.setData({
+          locationHint: '',
           latitude,
           longitude,
           scale: 16,
@@ -1152,6 +1157,15 @@ Page({
       navTop: navRow.navTop,
       navRight: navRow.navRight,
     });
+    // 定位兜底：上次成功位置 → 固定坐标，并提示「未获取定位」；用户主动定位成功后由 moveToLocation 覆盖
+    const fb = lastLocation.resolve();
+    this.setData({
+      latitude: fb.latitude,
+      longitude: fb.longitude,
+      'regionInfo.latitude': fb.latitude,
+      'regionInfo.longitude': fb.longitude,
+      locationHint: fb.hint,
+    });
     this.setupNetworkState();
 
 
diff --git a/chengyinhub-xcx/pages/searchmap/index.wxml b/chengyinhub-xcx/pages/searchmap/index.wxml
index 4694630e..5be03815 100644
--- a/chengyinhub-xcx/pages/searchmap/index.wxml
+++ b/chengyinhub-xcx/pages/searchmap/index.wxml
@@ -51,6 +51,7 @@
     <cover-view class="location-btn" bindtap="requestLocation" aria-role="button" aria-label="定位到当前位置" hover-class="cy-pressed" hover-stay-time="80" hover-stop-propagation="true">
       <cover-view class="location-btn__label">定位</cover-view>
     </cover-view>
+    <cover-view wx:if="{{locationHint && !locationError}}" class="smap-location-hint">{{locationHint}}</cover-view>
     <cover-view wx:if="{{locationError}}" class="smap-location-error">
       <cover-view class="smap-location-error__message">{{locationMessage}}</cover-view>
       <cover-view class="smap-location-error__action" bindtap="recoverLocation" aria-role="button" aria-label="{{locationAction === 'open-setting' ? '打开设置开启定位权限' : '重新定位'}}" hover-class="cy-pressed" hover-stay-time="80" hover-stop-propagation="true">{{locationAction === 'open-setting' ? '去设置' : '重试定位'}}</cover-view>
diff --git a/chengyinhub-xcx/pages/searchmap/index.wxss b/chengyinhub-xcx/pages/searchmap/index.wxss
index 1966c85a..b360f9ac 100644
--- a/chengyinhub-xcx/pages/searchmap/index.wxss
+++ b/chengyinhub-xcx/pages/searchmap/index.wxss
@@ -53,6 +53,14 @@
   font-size:var(--cy-type-caption);
 }
 .smap-location-error__message{ flex:1; }
+.smap-location-hint{
+  position:absolute; left:var(--cy-space-4); right:var(--cy-space-4);
+  top:calc(var(--cy-space-8) + var(--cy-space-4) + env(safe-area-inset-top)); z-index:6;
+  padding:var(--cy-space-2) var(--cy-space-3);
+  background:var(--cy-color-bg-surface); border:1rpx solid var(--cy-color-border-strong);
+  border-radius:var(--cy-radius-md); color:var(--cy-color-text-secondary);
+  font-size:var(--cy-type-caption);
+}
 .smap-location-error__action{
   flex:none; min-height:88rpx; padding:0 var(--cy-space-2);
   display:flex; align-items:center;
```
   配套回归（落地时一并加入 tests/unit/）：断言 searchmap 不含 31.230416/121.473701、onLoad 调 lastLocation.resolve() 并写 locationHint、
   moveToLocation 成功分支 lastLocation.save(res) 且清 locationHint、失败分支不保存、wxml 提示条 wx:if="{{locationHint && !locationError}}"。

2) #15 C5b 漫游页「官方线路」入口：subpackageRoam/official-routes 已注册但不可达。PR #15 原实现为
   pages/roam/index.js 增 `openOfficialRoutes() { wx.navigateTo({ url: '/subpackageRoam/official-routes/index' }); }`，
   pages/roam/index.wxml entry-map 区增
   `<view class="entry-map__roam" bindtap="openOfficialRoutes" aria-role="button" aria-label="官方线路" hover-class="cy-pressed" hover-stay-time="80"><text>官方线路</text></view>`。
   需按当前 roam 页入口布局确认位置，并重建 action-ledger / route-matrix。
-->

## 需要的微信开发者工具 / 真机手动检查

1. **searchmap 兜底（落地后）**：首次进入（无缓存）显示「未获取定位，当前显示默认位置」；点「定位」成功后提示消失、再次进入显示「上次所在位置」；拒绝定位权限时只显示错误条（去设置），不同时显示提示条；cover-view 在深浅色下可读。
2. **官方线路入口（落地后）**：漫游页入口可见可点，列表 → 详情 → openLocation；空列表与失败态。
3. **#15 已覆盖项回看**：首页「继续上次漫游」条（需未结算漫游记录）关闭与跳转；集邮册「收到的」tab 分页；NPC 清除对话危险确认层级与成功后清空；队伍详情举报半屏；个人页积分明细；主题状态键面板（编辑已有主题）。
4. **#14 已覆盖项回看**：装修页物理返回/侧滑按 放弃确认 > 字段面板 > 图片编辑 关闭；认领 sheet 返回键与四态；据点/站点打卡码无图时文本码与长按复制；俱乐部置顶公告与滚动定位。
5. **#8 资金链（仅观察，不改行为）**：探店日票活动结束后订单详情不再展示核销码；免费报名不进入付款；无需在体验版做付款/退款/提现实操。
