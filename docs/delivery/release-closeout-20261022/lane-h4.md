# LANE H4 — 10-14 基础整合 PR #7 / #12 / #17 按当前消费方拆分核对

- 参照基线：`master` @ `063a6c3c`（2026-10-22 拉取时 origin/master 即此提交）
- 方法（实施文档一 §6.1「按当前消费方拆分」）：逐个 PR 读取标题/正文与完整文件清单（`<base>...<head>` 三点 diff，等价于 `pulls/<n>/files` 分页全集），把生产改动拆为子项，以**当前 master 上的页面/服务**为消费方，判定：
  - **COVERED**：当前代码已有等价或更强实现（引用当前代码与测试）
  - **MISSING**：当前消费方仍需要而 master 没有 → 需移植
  - **OBSOLETE**：当前已无消费方
- PR 自带的 `docs/verification/**` 证据、`scripts/uiaudit/action-ledger.json`、`tests/lint/ui-integrity-baseline.js` 属于派生/证据文件，不作为子项移植（台账/基线不重生成、不提交）。

## 结论

| PR | 子项数 | COVERED | MISSING | OBSOLETE |
|---|---|---|---|---|
| #7 消息隐私 + 原通知收据 + 事件可靠性 | 14 | 14 | 0 | 0 |
| #12 定位精度 / 点赞方向 / 徽章 | 4 | 3 | 0 | 1 |
| #17 首页定位同意 + 注销清理 | 8 | 8 | 0 | 0 |
| **合计** | **26** | **25** | **0** | **1** |

无 MISSING 子项，**本 lane 不移植任何生产代码**；本 PR 仅交付本核对文档。

## PR #7 — `codex/mini-foundation-b-20261013`（73 文件，base `codex/mini-foundation-cloud-handoff-20261013`）

| # | 子项 | 当前消费方 | 判定 | 证据（master） |
|---|---|---|---|---|
| 7-1 | 原站内通知意图表 + 暂停的投递任务（新 DDL） | `ImServiceImpl` / `ImNotificationDeliveryTask` | COVERED | `chengyinhub-admin/sql/migration_20261013_b_im_notification_intent.sql`（`CREATE TABLE IF NOT EXISTS` + `WHERE NOT EXISTS`，任务 status=1 暂停）；`ci/migration-dependencies.json` 已登记 |
| 7-2 | 通知意图入队/一次投递收据（同 key 异内容拒绝、已终态回原收据） | `ImServiceImpl implements ISystemNotificationIntentService`（`ImServiceImpl.java:59,1311-1316`） | COVERED | `ImNotificationIntentMapper.java/.xml`；测试 `ImNotificationIntentTest`、`ImFoundationBMybatisIntegrationTest` |
| 7-3 | 投递任务固定本轮 maxId、单条失败不饿死后页 | `business/task/ImNotificationDeliveryTask.java` | COVERED | 测试 `ImNotificationDeliveryTaskTest` |
| 7-4 | 发送收据只读回放接口 `/api/im/send-receipt` | 聊天页 `subpackageB/pages/im/chat/index.js:610` | COVERED | `ApiImController.java:117`；`IImService.readSentMessage`；测试 `ImFoundationReadRecoveryTest` |
| 7-5 | 发送前原身份落盘 / 登出与删除会话失效旧回包（`utils/im-send-recovery.js`） | 聊天页 `:500-618,965`；`pages/shezhi/shezhi.js:304` | COVERED | master 版本另增 `epoch()`；测试 `im-foundation-recovery-b.test.js`、`im-send-retry.test.js` |
| 7-6 | 消息增量 `after_id` 连续拉取 | 聊天页 `_pollNewPage`（`:1195-1245`）按可见序游标向后桥接到水位线，无断档 | COVERED（等价实现） | 服务端 `ImServiceImpl.listMessages(...afterId)`（`:322-372`，`selectVisibleAfter`）；`ApiImController.java:55-63` |
| 7-7 | 单调 `readTo` 已读（不回退） | 聊天页 `:799` 传 `read_to` | COVERED（等价实现） | `ImServiceImpl.markRead(m,c,readTo)`（`:1139-1159`，`markReadVisible` 不回退且只认读者可见消息）。PR 的 `markReadTo/countUnreadAfter` 未引入：master 已用不同实现覆盖；且未读计数归 lane F，本 lane 不触碰 |
| 7-8 | 路线卡/会话摘要按当前隐私投影 | 会话列表摘要 `ImServiceImpl.java:148`、消息分页/回执 `projectRouteMessage`（`:173,352,400,857,969`）；聊天页 `refreshRouteProjections`（`:623`） | COVERED | 测试 `ImRouteProjectionTest`。PR 的 `selectLatestForProjection` 未引入，master 用 `projectRouteMessage` 覆盖 |
| 7-9 | 埋点按原批 `accepted===batchSize` 确认、持久化失败不确认、账号代际隔离、失败批不阻断后批 | `utils/analytics.js`（`:72-333`） | COVERED | 测试 `analytics.test.js` |
| 7-10 | 埋点必须带幂等键；同键异内容/成交无原报名事实拒绝 | `ApiAnalyticsController.java:154,183-209`；`AnalyticsEventServiceImpl` + `BizEventLogMapper.selectIdempotentEventForUpdate/selectRegistrationEventFact` | COVERED | 测试 `ApiAnalyticsControllerTest`、`AnalyticsEventServiceImplTest`、`BizEventLogIdempotencyMapperContractTest` |
| 7-11 | 日聚合单日失败不阻断其它日、任务保留失败 | `web/task/AnalyticsAggregationTask.java:34-41` | COVERED | 测试 `AnalyticsAggregationTaskTest` |
| 7-12 | 业务审计日志查询脱敏（手机号/卡号/邮箱/位置/实名等） | `BusinessAuditLogQueryServiceImpl.java:72-122` | COVERED | 测试 `BusinessAuditLogQueryServiceTest` |
| 7-13 | 广场 creativesquare 分页按 id 去重 + 换号清缓存 | `pages/square/list/index.js:1010-1012,1195,1233-1238` | COVERED | 测试 `square-feed-pagination-b.test.js` |
| 7-14 | 旧 fixture 调整（c06/club-detail/malformed-list/play-free-explore 等） | 对应测试文件 | COVERED | 文件均在 master，当前断言已含 PR 所需前置 |

说明：PR #7 正文所述 A 包「首次报名原事务触发 enqueue」的生产方不在本 PR 文件清单内（PR 明确写在 A 独立分支），不计入本表。

## PR #12 — `claude/backend-play-like-badge-20261014`（10 文件）

| # | 子项 | 当前消费方 | 判定 | 证据（master） |
|---|---|---|---|---|
| 12-1 | B1 到点打卡 GPS 精度闸（>80m 拒绝，先于距离判断；空白兼容放行） | `ApiPlayProgressController.arrive`（`:1960-2086`，`GEOFENCE_ACCURACY_METERS=80` `:2374`）；端上 `pages/play/utils/play-accuracy.js` | COVERED | 测试 `ApiPlayGpsGeofenceValidationTest:280-322` |
| 12-2 | B4 `/api/topic/like` 显式 `type=1/0` 幂等 | `pages/mylike/mylike.js:367`、`components/cy/scene-route-content/index.js:356`（均传 `type:0`） | COVERED（更强） | `ApiTopicController.topicLike` → `cmsTopicLikeService.setLiked(desired)`；测试 `TopicLikeRetryDirectionV2Test`、`ApiTopicLikeExistenceTest`、`TopicLikeDesiredStateTest`、`TopicLikeDesiredStateMySqlIT`。PR 的 `ApiTopicLikeDirectionTest` 由上述测试取代 |
| 12-3 | B4 `type` 缺省保留旧「切换」语义 | 无：当前所有调用方都显式传 `type` | OBSOLETE | master 缺省按点赞（`desired=1`）处理，不再有依赖切换语义的消费方 |
| 12-4 | B5 平台徽章墙排除俱乐部徽章、个人墙保留本人已得俱乐部徽章 | `BadgeReadModelServiceImpl`；`GrowthBadgeMapper.xml:57-66`（`club_id is null` + `selectActiveClubByCodes`） | COVERED | 测试 `GrowthBadgeMapperXmlTest`、`BadgeReadModelServiceImplTest:44-60` |

## PR #17 — `feature/prelaunch-acceptance-20261015`（27 文件）

| # | 子项 | 当前消费方 | 判定 | 证据（master） |
|---|---|---|---|---|
| 17-1 | 服务端 `home_city_location` 场景：白名单、带基线写入、回读校验 | `ApiComplianceController.java:36,100` | COVERED | 测试 `ApiComplianceControllerTest:243-296` |
| 17-2 | `RoamLocationConsentService.record(scene,...)`；撤回首页定位不影响「附近的人」；迟到旧 AGREE 不能在 REVOKE 后复活 | `RoamLocationConsentService.java:32`；`ConsentRecordServiceImpl.java:34,66` | COVERED | 测试 `RoamLocationRevokeHidesPresenceTest:51-115`、`ConsentRecordServiceImplTest:86` |
| 17-3 | `ConsentRecordMapper.selectLatestLocation[ForUpdate]` | 同上 | COVERED | 同上 |
| 17-4 | 首页：先查同意 → 单独用途弹窗 → 写 AGREE 回执匹配后才 `wx.getLocation`；撤回/换号/卸载后迟到回执不能定位；`onHide`/`onUnload` 递增 epoch 停止 | `pages/index/index.js:127-133,570-600,1503-1600` | COVERED | 测试 `home-location-scope-epoch.test.js`（含 `onUnload` `:79`、`onHide` `:118-142`）、`index-identity-recheck-after-login.test.js` |
| 17-5 | 隐私页首页城市定位开启/关闭开关 | `pages/privacy/index.js:12-44,307-360`；`index.wxml:29` | COVERED（UI 已按后续列表样式重排） | 测试 `privacy-submit-state-contract.test.js`、`privacy-location-withdraw-immediate.test.js` |
| 17-6 | 初次隐私提示精简为一句话 | `pages/privacy/index.wxml`（pending 态仅「我们只在你使用相关功能时处理必要信息。」+ 协议勾选） | COVERED（已被后续改版取代且更短） | 测试 `privacy-gate-no-hang-contract.test.js` |
| 17-7 | `consent-client` 按 owner+scene 分代、首页场景版本校验、409 只清本 requestId | `utils/compliance/consent-client.js:20-167` | COVERED | 测试 `consent-client.test.js` |
| 17-8 | 注销：账号已不存在 / 已注销分支也清理**本人**地址 | `AccountDeregistrationServiceImpl.java:293-299`（`deleteMemberAddresses(memberId)`，SQL 仅 `where member_id=#{memberId}`） | COVERED | 测试 `AccountDeregistrationCompletionPersistenceTest:71-77`（断言 1001 清空、他人 1002 保留，资金表不涉及） |

附：PR #17 的 `app.json` 权限说明（「用于确定首页城市、到点打卡判定与漫游足迹记录」）与 `wechat-release-contract.js` 精确定位互斥检查已在 master（`app.json:328-329`、`scripts/wechat-release-contract.js:113-116`）；本 lane 未改 `app.json`。

## 本地验证（Node，仅 chengyinhub-xcx）

在 `063a6c3c` 上运行与上述子项相关的 15 个单测文件：

```
node --test tests/unit/{analytics,im-foundation-recovery-b,square-feed-pagination-b,im-send-retry,
  im-biz-failure-not-success,im-chat-report-and-block-state,im-uiux-recovery-contract,consent-client,
  home-location-scope-epoch,privacy-submit-state-contract,privacy-location-withdraw-immediate,
  wechat-release-contract,app-config,index-identity-recheck-after-login,privacy-gate-no-hang-contract}.test.js
# tests 412 / pass 412 / fail 0 / skipped 0
```

Java 测试由本 draft PR 的 GitHub-hosted CI 执行（本会话未运行 Maven）。未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未重生成台账/基线。

## 需人工在微信开发者工具 / 真机确认

1. **首页城市定位（真机）**：首次进入首页 → 弹「首页城市定位」用途说明 → 拒绝不定位；同意后才调用精确定位并显示城市；在隐私页关闭后返回首页不再定位；定位中切后台（onHide）或返回（onUnload）后不再弹定位/不写城市。
2. **系统权限弹窗**：`scope.userLocation` 说明文字显示为「用于确定首页城市、到点打卡判定与漫游足迹记录」；拒绝系统授权时首页提示可重试且不崩。
3. **到点打卡精度闸（真机户外/室内各一次）**：精度 > 80m 时提示「定位精度不足，请到开阔处或扫码完成」，扫码路径不受影响。
4. **IM 发送恢复（真机弱网）**：发送中杀进程/断网后重进聊天，只回读原收据、不重复发送；退出登录换号后旧回包不出现在新账号会话。
5. **路线卡隐私**：对方把路线改为私密后，会话列表摘要与聊天内历史路线卡不再显示原名称（显示「路线已不可用」）。
6. **广场分页**：连续上拉不出现重复卡片；切换账号后广场不残留上一账号的待审/驳回帖。
7. **个人徽章墙**：平台墙不出现俱乐部专属徽章；本人已获得的俱乐部徽章仍可见。
8. **注销**：在测试库（非生产）走一次已注销账号的到期执行，确认仅本人收货地址被清、他人地址与资金/订单记录不变。
9. **通知意图任务**：`im_notification_intent` 迁移需用户在授权目标库手动执行；`sys_job` 任务默认暂停，联调验证后由用户启用。

## 与其它 lane / 约束的关系

- 未触碰 `ImConversationMember` 未读逻辑（lane F）、同意台账（lane E）、俱乐部费用、社区审核、CI 门禁、排行榜、发布相关文件。
- 未修改 C00 保留文件；未恢复旧包或旧 `app.json`；无新依赖、无新 DDL。
- 关于 `useDevUser()`：当前 master 上不存在该函数（`utils/session/session-manager.js:16` 注释记录 F-W1-01 已删除假用户兜底），与仓库 `AGENTS.md` 一致；本 lane 未做任何登录/会话改动。
