# LANE L21：#21 台账遗留项 + 真库 IT 证据（2026-10-22）

- 依据：`feature/lane-u1-verify-20261022`（PR #485）上的 `lane-u1.md` 第 1 节、第 4 节。#21 台账原文见 `pr/21:docs/plans/mini-claude-ui-20261015/BACKLOG.md:315-320`。
- 基线：`origin/master` @ `f0b43d62`。分支：`feature/lane-l21-leftovers-20261022`。
- 不做：#35 CL6（要等 owner 决定）。

## 结论一览

| # | 项 | 状态 | 改动 | 用例 |
|---|---|---|---|---|
| 1a | fabu `onCreateGame` 防连点 | 已完成 | `pages/publish/fabu/index.js` `onCreateGame`：和 `merchant/index goManageCoupon` 一样用 `_navigating` 锁，在 `complete` 里释放 | `tests/unit/l21-fabu-create-game-nav-guard.test.js` |
| 1b | BL-185 发布前调用 `GET /api/topic/publish-check` | 已完成 | `server-draft.js` 增加一个可选依赖 `publishCheck`，位置在「存草稿 → 回读确认」之后、`beforePublish` / `publish-draft` 之前。fabu 注入 `_topicPublishCheck` | `tests/unit/l21-bl-185-topic-publish-check.test.js` |
| 1c | BL-186 `GET /api/play/journey/stage-end` | 已完成 | `pages/play/index.js` 新增 `loadStageEnd`，在 `openFinish` 里和 `loadMilestone` 一起调用。`index.wxml` 的「本章完成」半屏加一块「这一章」 | `tests/unit/l21-bl-186-stage-end.test.js` |
| 1d | B9 申请 `refundRejected` 订阅 | 已完成，**需要 owner 留意，见下文** | 三个退款确认回调（订单详情、订单列表、参与详情）：已付且非零元的单，用户点「确认」时同步调用 `subscribe.request(['refund','refundRejected'])`，然后再发 `cancel-refund` | `tests/unit/l21-b9-refund-rejected-subscribe.test.js` |
| 2 | `port-f3-growth` 真库 IT，直接跑在 master 头 | **success** | 手动 dispatch，见第 2 节 | — |

## 1. 遗留项细节

### 1a fabu `onCreateGame`

- 连点时只发一次 `wx.navigateTo`。`complete` 回调（成功或失败都会触发）之后可以再进。
- `events.templateCreated` 回传通道保留不变。
- lane D 的云端自动保存没有改动：`saveServerDraft`、`_queueCloudDraftAutosave`、`_cloudSavedKey` 一行没碰。`fabu-cloud-autosave.test.js` 和 `fabu-server-draft.test.js` 全绿。

### 1b BL-185

台账原文：「只能接在编辑既有主题 → 发布之前……有 error 就不发布，只有 warn 时提示后允许继续。」

**接入位置**

- 选在云端草稿发布链路：先存草稿，回读确认，再跑试检查，最后 `publish-draft`。
- 原因：`publish-check` 检查的是**已存进库的那一版**。放在回读之后，检查的内容才等于即将发布的内容。
- 已上架主题的编辑走 `/api/topic/update` 直接生效，没有一个「发布前」的已存版本可以检查，所以这条路径不接。

**行为**

| 试检查结果 | 处理 |
|---|---|
| 有 `level=error` | 不发布。按「草稿已保存，但还没有发布」收口（`stage:'publish'`、`unknown:false`、带回读 detail），文案为「发布前检查未通过：…」 |
| 只有 warn | 先撤掉带遮罩的 loading，弹 `modal`。点「继续发布」才发布；点「再改改」不发布 |
| 没有问题 | 直接发布 |
| 业务拒绝、网络失败、HTTP 异常 | **不拦**，交给 `publish-draft` 在服务端判。用 `complete` 兜底，保证回调只触发一次，saver 不会卡在 busy |

- `server-draft.js` 里只要不注入 `publishCheck`，行为就和原来一样（用例覆盖）。
- 调用方式：`app.sendRequest` 带 `silentError` 的 GET，参数为 `topicId` 加上当前的 `operationScope`（scope 为 MERCHANT 时，后端 `editableDenial` 会走 PROJECT_MANAGE）。

### 1c BL-186

- 取数和降级都照 `_loadLifeEnding` 的 `/journey/ending` 来做。
- 只在以下条件**全部满足**时请求：故事流章节（`chapter.blocks` 是数组）、真玩家、未通关、不是预览或试玩、不是探店日、不是结局章、chapterId 合法。
- 请求是静默的，因为「本章节未配置阶段结算」是常态，不应弹错。
- 只渲染作者写的 `changed[]` 文本和 `open` 文本；内容为空时整块不出。
- 回包迟到时，如果半屏已关、又开了一次、或局号已变，就丢弃。
- 样式复用已有的 `finsheet__game-ending*` 类，wxss 零改动。

### 1d B9 —— 需要 owner 留意

- **冲突**：lane-H2（`lane-h2.md:50` S-1）和 lane-H5（`lane-h5.md:88`）都把 B9 标成了 **NEEDS_OWNER_APPROVAL**。当时 master 的现行合同是「退款取消不申请订阅」，由 `longrun-e05-03/04` 和 `b01-refund-account-regression` 断言 `subscriptions == []`。
- **本 lane 的处理**：本 lane 任务书明确要求接 B9（同一份任务书只把 CL6 列为「等 owner」），所以照做了。B9 单独成一个提交，**可以整体 revert**。
- **三份旧测试的改动**：从「不申请」改成「已付确认时，在同一手势内申请一次 `refund` + `refundRejected`」。
  - 手势断言保留：确认回调同步段之外调用会直接失败。
  - 失效的确认（换号、detach、旧弹窗）仍然断言**不申请**。
- **微信订阅规则**：
  - 只在用户点「确认退款」的回调里同步调用，加载时不会自动弹。
  - 用户点「再想想」不申请；零元单、未付单的普通取消也不申请。
  - 报名时已经用满 3 个模板（`baoming.js`），退款的两个模板单独申请。
- **订阅结果不影响退款**：退款请求照常发出，不等订阅授权结果，也不因拒绝订阅而中断。
- 订阅弹窗能否在确认手势内真的弹出、会不会挡住退款，**需要真机验证**（`lane-h2.md:110` 的人工清单）。本 lane 只有 node 证据。

## 2. 真库 IT：`port-f3-growth` @ master 头

- 先核对了 workflow 入参（`.github/workflows/real-storage-it.yml`）：`source_sha`（完整 40 位）、`batch`（选项）、`classes`（可选）、`timezone`（UTC 或 Asia/Shanghai）。
- 批次登记在 `ci/real-storage-it-registry.json` 的 `port-f3-growth`，包含唯一一个类 `com.chengyinhub.business.forward20261015.PlayerGrowthConcurrencyMySqlIT`。
- 触发方式：本机 `gh` 的 token 无效，改用 GitHub MCP 的 `actions_run_trigger`（等价于 `gh workflow run`），在 GitHub-hosted runner 上跑。
  - 入参：`ref=master`、`source_sha=f0b43d62d4d127fcd399f080a54acf9477c5d9fb`、`batch=port-f3-growth`、`classes=''`、`timezone=UTC`。

| run id | # | 触发时间 (UTC) | 候选 | 结论 |
|---|---|---|---|---|
| **39780782637** | **217** | 2026-10-22 12:44 | `f0b43d62`（master 头） | **success** |

- 3 个 job 全部 success：
  - `Plan and static precheck`（job 119831182164）
  - `PlayerGrowthConcurrencyMySqlIT`（job 119831377750，「Run class on real storage and read back」12:45:09–12:46:38）
  - `Batch summary`（job 119832469382，fail-closed 汇总）
- 产出的 artifact：`rs-class-PlayerGrowthConcurrencyMySqlIT-a1`、`rs-summary-port-f3-growth-f0b43d62d4d1-a1`。
- 至此，U1 第 1 节说的「对 master 头没有直接跑过」已经补上：**master 头直接为绿**。
- 链接：https://github.com/liboyang42-cpu/hengyin/actions/runs/39780782637

## 3. 验证

- 新增 4 个用例文件，共 27 条，全绿（nav guard 2、BL-185 7、BL-186 5、B9 13）。
- 受影响的既有用例全绿：
  - `fabu-server-draft`、`fabu-cloud-autosave`、`fabu-draft-semantic-recovery`
  - `get-auth-matrix-contract`（新增 2 条 GET 入矩阵：直接 GET 65→66，req 包装 29→30）
  - `longrun-e05-*`、`b01-refund-account-regression`
- 全量 `npm run test:unit`：13592 条，13591 过，1 红。红的是 `action-ledger.test.js`「release 前重建 inventory」，原因是台账漂移（master 上为绿；本地再生成台账后转绿，再生成结果未提交，见文末 CY-HANDOFF）。
- 门禁：`lint:ui`（UI-GATE-0 PASS）、`lint:scope`（PASS）、`lint:requestbody`（0 error）、`lint:danger-confirm`（PASS）、`lint:sticky-bottom-bar`（PASS）。
- 为了让 UI 基线（C00 保留文件）里按行号登记的条目不漂移，改动刻意没有挪动任何已登记行：
  - 新方法放在已登记行之后；
  - 在已登记行之前的改动保持行数不变。
  - 因此 `tests/lint/ui-integrity-baseline.js` 不需要改。
- 按规则**没有运行** `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

<!-- CY-HANDOFF
to: C00（集成）
file: chengyinhub-xcx/scripts/uiaudit/action-ledger.json（C00 保留，本 PR 未提交再生成结果）
why: 本 PR 改了 play/index.wxml(+5 行)、play/index.js 和三个会员订单组件(fabu 改动未引起台账变化)，台账 digest 与行号随之漂移；action-ledger.test.js「release 前重建 inventory」在本分支为红（master 上为绿）。
action: 在合并树上 `npm run audit:actions:update` 后审阅。
local regen counts (仅供核对，未提交): controls 4947 → 4947，新增控件 0；按 (wxml,line,handler) 计 +129 / −129，全部来自 play/index.wxml 插入 5 行导致的行号重键；内容/digest 变化 84 条（play/index.wxml 44、scene-member-order-detail 16、play/theme-backpack 13、scene-member-order-history 9、scene-member-participation-detail 2）。再生成后 action-ledger.test.js「release 前重建 inventory」本地转绿。
-->
