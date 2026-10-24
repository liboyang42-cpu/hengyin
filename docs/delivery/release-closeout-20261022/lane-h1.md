# Lane H1：旧 10-15 修复 PR #28 / #29 / #32 对账（实施文档一 §6.1）

- 对账基准：live `origin/master` = `063a6c3c`（与任务给定参考一致）。旧 PR 基线均为 `1e403b13`（不在 master 祖先链上，因此逐文件比对旧 PR head 与 master，再逐项看 master 是否保留/加强该要求）。
- 旧 PR head：#28 `4a943501`、#29 `d3468d38`、#32 `ca5e69f6`。旧 PR 本身不合并、不关闭、不评论；本 lane 只移植 MISSING 项。
- 方法：`git diff 1e403b13 oldpr-N`（旧 PR diff）抽取要求 → `git diff oldpr-N origin/master -- <文件>` 核对 master 是否删改该行 → 引用 master 当前代码/回归。旧 PR 的 39 份 EXTRA6 证据文档与 2 份 README 在 master 上逐字相同（SAME），其“交接/待决”段落也逐条核对。
- 状态口径：COVERED = master 已有且引用代码/回归；MISSING = 本分支已最小修复 + 回归；NEEDS_OWNER_APPROVAL = 涉及券值/发放政策/核销资金链合同，按规则不改，仅登记。

## 汇总

| PR | 要求数 | COVERED | MISSING（本分支已修） | NEEDS_OWNER_APPROVAL |
|---|---|---|---|---|
| #28 回执单调 / 作者快照 / NPC 删除并发 / 后台 token / 发布 UNKNOWN | 9 | 8 | 1 | 0 |
| #29 排期候补终态 / 回执 / 时刻解析 | 10 | 9 | 1 | 0 |
| #32 券生命周期 | 12 | 9 | 0 | 3 |
| **合计** | **31** | **26** | **2** | **3** |

## PR #28（`fix: 补完回执单调、作者快照和NPC删除并发问题`）

| # | 旧要求 | 状态 | master 证据 / 本分支修复 |
|---|---|---|---|
| 28-1 | L01 玩家投影/回执 revision 单调：较旧 projection 不得覆盖当前 revision | COVERED | `chengyinhub-xcx/pages/play/index.js:2607`（同 session 旧 revision 投影丢弃）；`pages/play/utils/player-game-module.js:515`（`mergeConfirmedReceipt` 取 `Math.max`）；回归 `tests/unit/player-game-session-entry-contract.test.js`、`tests/unit/player-game-module.test.js` |
| 28-2 | L01 迟到请求只改匹配的 pending/账号/活动/会话 | COVERED | `pages/play/index.js:2487` `_isPlayerPendingContextCurrent`；`:2572-2579` 投影请求 epoch+账号+活动+会话闸 |
| 28-3 | L01 刷新保留同身份内存原命令；在途不变成可重试 | COVERED | `pages/play/index.js:2656` 起 `memoryIndex` 同身份比对；回归同上 entry-contract |
| 28-4 | L02 作者 edit-detail 聚合入口 RR 只读事务 | COVERED | `chengyinhub-admin/.../api/ApiTopicController.java:828`；回归 `B02TopicEditDetailSnapshotMyBatisTest`（与旧 PR 逐字相同） |
| 28-5 | L03 同会员行短事务覆盖准入/finish+日志/删除；provider 期间不持锁 | COVERED | `chengyinhub-system/.../impl/NpcChatWriteService.java:35,46,70,85`；`NpcChatDataService.java:64`；回归 `A02NpcDeleteBarrierMySqlIT`、`NpcChatUserDataMyBatisIT` |
| 28-6 | L03 晚到 worker 读当前持久行，不回内存答案 | COVERED | `NpcChatService.java:346`（语音后重读）、`:525` `persistedOutcome`（master 另加 UNKNOWN 恢复）；回归 `NpcChatServiceTest` |
| 28-7 | L03 删除擦除答案副本、撤 lease、保留请求身份；删除后持久读取与迟到写不得恢复旧答案 | **MISSING → 已修** | master 之后把语音回答持久为 `npc_chat_request.audio_asset_id`（`NpcChatService.attachVoice` → `bindApprovedTextAudio`），但 `redactChatRequestsByUserId` 只清 `safe_text`：删除后同 requestId 回放仍经 `attachApprovedAudio` 返回已删回答的音频，迟到的语音 worker 也能把音频绑回已擦除回执。修复 `NpcChatRequestMapper.xml`：删除同时 `audio_asset_id = null`；`bindApprovedTextAudio` 增 `safe_text is not null`。回归 `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/NpcChatDeleteAudioRedactionMyBatisTest.java`（2 方法，真实 mapper XML，含负控） |
| 28-8 | L04 后台日志 null/undefined token 显示“未知”，真实 0 仍 0 | COVERED | `chengyinhub-ui/src/views/business/ailog/index.vue:225`；回归 `chengyinhub-ui/tests/unit/a02-ailog-usage.test.js`（逐字相同） |
| 28-9 | L05 发布 write 前持久 UNKNOWN；丢响应不重复 create；旧账号/卸载/重读回调不清当前草稿 | COVERED | `pages/publish/utils/publish/publish-workflow.js:33,39`；`pages/publish/fabu/index.js:7114`；`pages/publish/utils/publish/pro-editor-draft.js:103`（发布页实际引用的分包副本；主包 `utils/publish/pro-editor-draft.js` 仅社区 post-compose 使用，未带 publishAttempt 不影响发布链）；回归 `tests/unit/b02-publish-write-unknown.test.js`、`longrun-own-c02-05/26.test.js`、`publish-workflow.test.js` |

## PR #29（`[E01] 排期候补回归与终态回读修复`）

| # | 旧要求 | 状态 | master 证据 / 本分支修复 |
|---|---|---|---|
| 29-1 | E01-07 myStatus 过期 offer 当前读，避免并发 CLAIMED 被 RR 旧快照误标 EXPIRED | COVERED | `ClubWaitlistServiceImpl.java:162`；回归 `E01_07Test`、`B04ScheduleReadbackMySqlIT` |
| 29-2 | E01-13 新 WAITING 准入：活动上架/未结束、报名截止未到 | COVERED | `ClubWaitlistServiceImpl.java:74,78`；`ClubEventOpsMapper.xml:308`；回归 `E01_13Test` |
| 29-3 | E01-06 promoteNext 只拦新 hold，下架后旧 offer 取消/释放仍提交 | COVERED | `ClubWaitlistServiceImpl.java:244`；回归 `E01_06Test`、`ClubWaitlistPromotionMembershipRaceTransactionTest` |
| 29-4 | E01-10 系列读接口同快照（RR 只读） | COVERED | `ClubEventOpsServiceImpl.java:112,128,150`；回归 `E01_10Test` |
| 29-5 | E01-14 ONCE/CUSTOM 跨上海日重发先核原指纹，不被“今天”挡 | COVERED | `ClubEventOpsServiceImpl.java:220`；`ClubEventScheduleMaterializer.java:56`；回归 `E01_14Test` |
| 29-6 | E01-12 排期入口 HH:mm STRICT（拒 24:00） | COVERED | `ClubEventOpsServiceImpl.java:58`；回归 `E01_12Test` |
| 29-7 | E01-12 交接：直接开团 `ClubSessionServiceImpl.open` 同类 SMART 解析把 24:00 静默落为当天 00:00 | **MISSING → 已修** | 本机 `jshell` 实证：`LocalTime.parse("24:00", ofPattern("HH:mm"))` → `00:00`，STRICT → 拒绝。修复 `ClubSessionServiceImpl.assemblyMoment` 用 `ResolverStyle.STRICT`，沿用既有 `IllegalArgumentException("集合时刻格式应为 HH:mm…")`；回归 `ClubSessionServiceImplTest.openWithHourTwentyFourIsRejectedInsteadOfMidnight`（既有 00:00 显式午夜用例保持） |
| 29-8 | E01-09/16 终态 SQL 不回退、occurrence CAS 0 行整体回滚 | COVERED | 生产代码无改动结论（NO_CHANGE）；回归 `E01_09Test`、`E01_16Test` |
| 29-9 | E01-17 广播受众回执（已报名/候补 distinct member，按 club/activity 归属） | COVERED | 生产 SQL 无改动结论；回归 `E01_17Test`（逐字相同） |
| 29-10 | E01-18 实际状态写入入口与 5 方法真库回归 | COVERED | 回归 `E01_18Test`；master 将未来窗收紧为“上海明日 00:00”（`ClubEventOpsServiceImpl.futureDateBoundary`）属后续加强，保留 |

E01-01/02/03/04/05/08/11/15 为旧 PR 内 NO_CHANGE_SOURCE_REVIEW（无缺陷结论），master 未回退相关代码，不计入要求数。

## PR #32（`[E04] 券生命周期回归与真实调用链审计`）

| # | 旧要求 | 状态 | master 证据 |
|---|---|---|---|
| 32-1 | E04-03 停发/失效以当前行原子拒发（防旧模板快照） | COVERED | `SmsCouponHistoryServiceImpl.java:274`；`SmsCouponMapper.xml:222`（master 另加可发放期）；回归 `B03CouponLifecycleMySqlIT`、`E04_03Test` |
| 32-2 | E04-10 核销时当前 status=3 零行回滚；停发 4/NULL 仍可核销 | COVERED | `SmsCouponHistoryServiceImpl.java:135`；`SmsCouponMapper.xml:233`；回归 `E04_10Test`、`SmsCouponDisabledTest` |
| 32-3 | E04-14 核销首读锁定当前历史身份 | COVERED | `SmsCouponHistoryServiceImpl.java:64`；回归 `E04_14Test` |
| 32-4 | E04-06 撤销核销先锁历史（history→coupon 锁序） | COVERED | `SmsCouponHistoryServiceImpl.java:190`；回归 `E04_06Test`、`SmsCouponReverseTest` |
| 32-5 | E04-09 后台新增历史与统计同事务 | COVERED | `SmsCouponHistoryServiceImpl.java:408` 前 `@Transactional`；回归 `E04_09Test` |
| 32-6 | E04-13 clawPoints 持会员锁后当前读余额 | COVERED | `RedemptionRewardClawbackService.java:154`；回归 `E04_13Test` |
| 32-7 | E04-07 通关券持久原 history_id，撤销只认原回执 | COVERED | `TopicCompletionCouponRewardServiceImpl.java:39,45`；`CmsTopicCompletionCouponRewardMapper.xml:12`；`SmsCouponHistoryMapper.xml:188`；迁移 `chengyinhub-admin/sql/migration_topic_completion_coupon_history_receipt_20261016.sql`（已在 master，未改）；回归 `E04_07Test`、`E04_07MySqlIT`、`RedemptionRewardClawbackMyBatisTest` |
| 32-8 | E04-07-A/B 原身份缺失（旧 NULL 行）只审计不动券，其他冲正继续 | COVERED | `RedemptionRewardClawbackService.java:271`（UNKNOWN_IDENTITY_NOT_RECOVERED）；旧文档记录为真人已拍板 |
| 32-9 | E04-11 批量过期仅本会员当前未用行 | COVERED | NO_CHANGE 结论；回归 `E04_11Test` |
| 32-10 | E04-02 同会员同模板限 N 张 | NEEDS_OWNER_APPROVAL | 旧 PR 明确“无既定产品规则，不凭空新增”；属发券政策，不改 |
| 32-11 | E04-09 后台新增历史时计数 UPDATE 零行是否应拒绝 | NEEDS_OWNER_APPROVAL | 旧 PR 明确“需另审后台导入/历史修订合同”；影响券发放统计口径，不改 |
| 32-12 | E04-14 QR 动态凭据在签发/验签与 Service 锁之间的 history 改归属窗口（token 不随 member_id 变更失效） | NEEDS_OWNER_APPROVAL | 旧 PR 明确“需新合同（版本/API）另行决策”；属核销→结算资金链合同，不改 |

## 本分支改动（仅 MISSING 项）

- `chengyinhub-system/src/main/resources/mapper/business/NpcChatRequestMapper.xml`：删除擦 `audio_asset_id`；绑定要求 `safe_text is not null`（28-7）。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubSessionServiceImpl.java`：集合时刻 STRICT 解析（29-7）。
- 回归：`NpcChatDeleteAudioRedactionMyBatisTest`（新增 2 方法）、`ClubSessionServiceImplTest`（+1 方法）。
- 无 DDL、无新依赖、无 API/DTO 变化；未改 C00 保留文件、CI 门禁、ledger/baseline；未触碰其他 lane 文件（publish/fabu 仅只读核对）。

## 验证

- 本地：容器内 Maven 无离线依赖缓存（`-o` 解析 BOM 失败），Java 测试按任务要求交 GitHub-hosted CI 执行，结果见草稿 PR checks。
- `jshell` 实测 SMART/STRICT 对 `24:00` 的差异（见 29-7）。
- TEST_DEPLOYED=NO；未连接任何生产/测试库。
