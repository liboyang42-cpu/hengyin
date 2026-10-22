# Lane D：自动云草稿（Doc 1 §5.1 包 D）

- 分支：`feature/lane-d-autosave-20261022`，基线 master `063a6c3c`（2026-10-22 `git fetch github master` 回读）
- 旧本地来源（只读）：`/Users/arlan/Downloads/hengyin/chengyinhub-xcx/pages/publish/fabu/index.js`（7335 行，基于 `fbe9993d`）、`pages/publish/utils/publish/server-draft.js`、`style/tokens.wxss`
- 方法差异证据：`hengyin-release-closeout-handoff-20261022/evidence/fabu-method-difference.json`（本地独有 3 个、master 独有 33 个）
- 写入范围：`pages/publish/fabu/index.{js,wxml,wxss}`、`pages/publish/utils/publish/server-draft.js`、`style/tokens.wxss`（仅六项 token）、定向单测。未整文件恢复；后端合同未改。

## 1 行为清单（先于代码写定）

### 1.1 从旧本地保全的增量（逐项核对后才移植）

| # | 旧本地行为 | 处置 |
|---|---|---|
| L1 | `_queueCloudDraftAutosave`：每次草稿编辑后 2000 ms 防抖，合并成一次 `saveServerDraft({ automatic: true })` | 移植，接到 master 当前 saver 与 `_buildSubmitPayload` |
| L2 | `_cancelCloudDraftAutosave`：清定时器；`onHide`/`onUnload`/`_doSubmit`/保存开始时调用 | 移植 |
| L3 | `_cloudDraftEditorOpen`：章节/站点/故事流/票种/运行配置/初始状态/路线对应/模式/预检/章节预览弹窗打开时不上云 | 移植，并补上 master 新增的弹层（storyVarSheet、runtimeZeroEditor、survivalEditor、structureEditor、valueEditor、thoughtsEditor、categorySheetVisible、三个时间选择器） |
| L4 | setData 包装：草稿 patch → 先写本机 → 标「未保存」→ 排队；排队中且弹窗已关 → 接力排队 | 移植；本机写失败时标「本机未存」而不是「未保存」 |
| L5 | 自动保存不弹「先起名字」、不盖 loading、不弹结果面板 | 移植（`quiet` 只抑制 payload 组装异常的 toast） |
| L6 | 保存在途又编辑（`hasChanges`）：不换回读版本、不报已保存、重新排队 | 移植到 master saver（仅保存草稿路径；发布路径 master 已有 `localChanges`） |
| L7 | `onHide` 立刻再存一次云端 | **不移植**：§5.1 第 5 步不承诺离页前存上云，且选图/选点/玩法编辑跳转也会触发 onHide；每次编辑已先落本机，onShow 回来接力排队 |
| L8 | 标题旁状态胶囊 `pd-save-state`（已保存/未保存/保存中/待确认） | 移植并细分为六态：已存本机 / 本机未存 / 保存中 / 已存云端 / 云端未存 / 待确认 |
| L9 | 删除底栏「保存草稿」按钮 | **不移植**：验收要求手动保存与恢复稿，master 的手动按钮保留，与自动保存串行 |
| L10 | 旧 `semanticReadbackIssue` | **不移植**：主体已在 master `readbackIssue` + `savedContentIssue` |
| L11 | 旧 saver 的 `snapshot` 深拷贝 / `active===operation` 结构 | **不移植**：master saver 已有 `isCurrent`、`pendingPublish`、`beforePublish`、UNKNOWN 收口，只加 `hasChanges` 一个钩子 |
| T1–T6 | `tokens.wxss` `.theme-topic-editor` 内六项：`--cy-topic-name-size`、`--cy-topic-name-tracking`、`--cy-topic-save-saved-bg/-text`、`--cy-topic-save-unsaved-bg/-text` | 六项原值移植；四个 save token 由状态胶囊使用；两个 name token 只定义、**不改**当前标题字号（Runbook：保留当前 typography 合同），是否切换由设计决定 |

### 1.2 移植后必须成立的行为（= 单测断言）

1. 连续输入只在停手 2 秒后发 **一次** 云端保存。
2. 未命名主题不建空主题、不弹提示；`_buildSubmitPayload` 组装失败时自动保存不弹 toast。
3. 已发布主题（`editingTopicId && !editIsDraft`）或回填未完成时不进自动草稿。
4. 本机存储写失败：状态胶囊为「本机未存」，不伪造可恢复稿。
5. 断网：本机稿保留，状态为「云端未存」，不弹面板、不暂停后续编辑触发的重试。
6. A 账号的定时器 / 在途响应在切到 B 后不发请求、不改页面与 B 的本机存储。
7. 保存中继续编辑：回读通过也不换上回读版本、不显示「已存云端」，接上服务端版本号后重新排队；之后只发一次。
8. 自动保存在途时点发布：不并发写同一草稿（提示稍后再发布）；发布在途时定时器不起保存。
9. UNKNOWN：首建丢回包后下次保存沿用原 requestId / draftUuid，不新建；保存丢回包标「待确认」且不自动盲重试；发布结果待确认（`_publishAttempt`）期间自动保存全部停下。
10. CAS / 确定失败（如「路线配置已更新」）：标「云端未存」，只提示一次并暂停自动保存，不覆盖本机内容；手动保存重新放行。
11. 回读章节 / 站点身份不符：不清本机恢复稿，不标「已存云端」，暂停自动保存。
12. 保存在途期间改了模板绑定：回读版本不回退绑定。
13. 编辑弹窗打开（未提交内容）时不上云；关闭后接力保存，弹窗内容不被回读覆盖。
14. 隐藏取消调度、回来接力；卸载取消调度且不再发请求；重开时从本机恢复稿恢复并排队上云。
15. 手动「保存草稿」与自动保存同一 saver、同一身份；回读通过后「已存云端」，内容未变不重复上传。

## 2 实施结果

- PR：https://github.com/liboyang42-cpu/hengyin/pull/463（草稿，目标 master）
- 提交：`ca3792c3`（行为清单）、`eb210fc1`（实现 + 单测），以及本报告的更新
- 改动文件：`pages/publish/fabu/index.{js,wxml,wxss}`、`pages/publish/utils/publish/server-draft.js`（只加可选 `hasChanges` 钩子）、`style/tokens.wxss`（六项 token）、`tests/unit/fabu-cloud-autosave.test.js`（新增 17 例）
- 后端合同：未改。已核对 `CmsTopicServiceImpl.saveRouteDefinition` 的 `configVersion` CAS：每次 update 都会 +1。前端在「保存中继续编辑」这条路径上接上本次写入的版本号，因此不需要改后端。

### 实现与 §5.1 七步对照
1. 增量与 token：见 §1.1。L7、L9、L10、L11 有意不移植，理由写在表里。
2. 接线：复用 master `getServerDraftSaver`（`isCurrent`、`pendingPublish`、`beforePublish`、上下文换代）和 `_buildSubmitPayload({ draft: true })`，没有另写一套 saver。
3. 先写本机再上云：setData 包装里先 `_persistDraftEnvelope()`，再 `_onDraftEdited`，然后按 2 秒防抖排队。未命名时不发请求、不弹提示；payload 组装失败时用 `quiet` 抑制提示。
4. 串行：saver 忙或 `submitting` 时定时器保留排队，等在途请求结束后的 setData 接力；`submitForm` / `_doSubmit` 在草稿保存在途时提示后返回，不并发写。保存中继续编辑走 `hasChanges` → `_onServerDraftSuperseded`：不换回读版本，不标已存云端。
5. 隐藏、卸载、换号：`onHide` / `onUnload` 取消调度；定时器触发时再核对账号和 scope；`_cloudAutosaveBlocked` 核对 `draftMemberId` 与当前账号。离页时不强行上云。
6. UNKNOWN：首建沿用 `publishIntent` 的稳定 requestId（按 draftUuid）；保存丢包标「待确认」，不自动重试，下次编辑或手动保存时用同一身份再存；`_publishAttempt` 存在时自动保存整体停下。
7. 回读不符（内容、章节节点身份、版本）：按 master 原路径失败，不清本机恢复稿，并暂停自动保存。「已存云端」只在 `onSuccess`（语义回读通过）后设置。

### 已知取舍
- `BRANCH_GRAPH` 主题首建时，`routeGraphJson` 会随 `editingTopicId` 变化换一种序列化，`hasChanges` 会判为有变化，结果多存一次：第二次是 update，回读后收敛。没有数据风险。
- 自动保存在途时点「发布」，只提示「正在保存草稿，保存完成后再发布」，需要再点一次，不自动续发。
- 两个 `--cy-topic-name-*` token 只定义、未应用，保留当前标题字号合同，待设计确认。

## 3 验证

- 新增单测：`node --test tests/unit/fabu-cloud-autosave.test.js` 17/17 通过
- 相关回归：fabu-* / b02 / pro-editor-* / publish-* / topic-editor-theme / wxss-undefined-token 共 792 个，全部通过
- 全量 `node --test "tests/unit/**/*.test.js"`：13491 个，通过 13490。失败的 1 个是 `action-ledger.test.js:1672`「release 前重建 inventory」，原因是行号移动导致台账 byte-exact 不一致。按要求不提交再生成的台账，计数见下
- 子门禁逐项单跑，全部 rc=0：js-scope-lint、ds-hardcode-gate --ci、undefined-token-gate、wxss-selector-lint、component-wxss-selector-lint、button-reset-specificity-lint、wxml-conditional-lint、cy-sheet-attr-lint、component-attr-lint、component-path-resolve-lint、wxml-handler-lint、js-orphan-handler-lint、setdata-dead-field-lint、a11y-tappable-name-lint、a11y-tap-target-lint、pulldown-refresh-lint、sticky-bottom-bar-gate、ui-theme-unit-lint --theme/--units、verify:wechat-release
- `tests/lint/ui-integrity.js --warn-as-error`：UI-GATE-0 共 24 项，全部是 `fabu/index.js` 12 条 U4-DYNAMIC 基线的行号漂移（12 条过期 + 12 条新行号），没有新增动态 setData
- gitleaks 8.30.1（本机没有 8.21.2）：`063a6c3c..HEAD` 无泄漏
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未启动 DevTools；无 Java / Maven / Docker

### 需集成方统一再生成（本分支不提交）
| 文件 | 旧 → 新 |
|---|---|
| `scripts/uiaudit/action-ledger.json` | controlCount 4947 → 4947；`pages/publish/fabu/index.wxml` 236 个控件 id 行号重编；56 条控件 handler 行号移动；没有新增或删除 handler |
| `tests/lint/ui-integrity-baseline.js`（C00） | `fabu/index.js` U4-DYNAMIC 12 条行号：758→837、1010→1089、2698→2786、3758→3852、4139→4233、5202→5296、5461→5555、6117→6211、6127→6221、6180→6274、6521→6615、8419→8581 |

## 4 DevTools 手工核对（协调方本地执行）

前置：候选后端为测试 VPS，不是生产；回读确认后端版本与库目标；用一个已登录的作者账号 A 和另一个账号 B。

1. 新建主题，先不起名：随便改章节或描述，等 5 秒以上。Network 里不应有 `/api/topic/v2/create`，也不应有 toast；标题旁显示「已存本机」。
2. 输入主题名并连续打字约 5 秒：停手 2 秒后只出现**一次** create（带 `saveDraft:1`、`requestId`），随后一次 `edit-detail`。胶囊依次显示「保存中」→「已存云端」；不弹结果面板，不出现全屏 loading。
3. 不再修改，等 10 秒：不应有新请求。
4. 用慢网（Network throttling），在 create 发出后、回读返回前继续打字：回读后标题保持最新输入，胶囊为「已存本机」；2 秒后只有一次 `/api/topic/v2/update`（同一主题 id，`configVersion` 为服务端新值），且该 update 不报「路线配置已更新」。
5. 打开章节或站点编辑弹窗，修改但不点完成，等 5 秒：不应有保存请求；点「完成」或关闭后，2 秒内保存一次，弹窗里的内容没有被回读冲掉。
6. 给站点换绑玩法模板，在保存在途时再换一次：最终显示最后一次绑定，不回退。
7. 设为离线（Offline）后编辑：胶囊为「待确认」或「云端未存」，不弹面板；杀进程重开，同一草稿可从本机恢复。恢复网络后再编辑，请求沿用原主题（update）或原 requestId，不新建第二份草稿（在「我的项目」核对只有一份）。
8. 后台切走再回来（onHide/onShow），以及跳到选图、选点再返回：离开期间不发请求，回来 2 秒后补存一次。
9. 自动保存在途时点「发布」：提示「正在保存草稿，保存完成后再发布」，不出现两条并发写；保存完成后再点发布，走 `update(saveDraft=1)` → `edit-detail` → `publish-draft`。
10. 打开一个已发布主题编辑：改动只显示「已存本机」，不出现任何 create/update 草稿请求。
11. 账号 A 编辑后 1 秒内退出并切到账号 B（或清会话后用 B 登录）：A 的保存不发出；B 打开编辑页时看不到 A 的稿件或状态。
12. 「保存草稿」按钮：手动点击时出现加载层和「草稿已保存到云端」面板，胶囊为「已存云端」，之后不重复上传。
13. 本机存储写满（如果能模拟）：胶囊为「本机未存」，并有一次「本地草稿保存失败」提示。
14. 在两个设备或窗口同时编辑同一草稿，制造 `configVersion` 冲突：只提示一次「自动保存未成功：…」，之后编辑不再自动发请求；手动「保存草稿」可以再试。

## 5 未完成 / 阻塞
- 真机与跨进程持久化验收（§9）不由源码测试覆盖，按上面的 DevTools 和真机清单执行。
- `action-ledger.json` 和 `ui-integrity-baseline.js` 需集成方在合并候选上统一再生成。
- 两个 topic-name token 是否应用到标题字号，待设计决定。

## 6 CI（GitHub-hosted，PR #463，head `c8edb347`）
- 「GitHub CI」run `39758725261`：24 项通过，3 项失败。3 项失败都来自上面「需集成方统一再生成」的两个文件，没有其它失败：
  - `Mini program unit shard (8/20)`：只有 `action-ledger.test.js:1672`（台账 byte-exact 新鲜度）失败
  - `Mini program static gates`：`ACTION-LEDGER FAIL`（同一原因）；`UI-GATE-0: FAIL (24 violations)` = `fabu/index.js` 12 条 U4-DYNAMIC 行号漂移（`动态 setData 63/51 已登记` 中多出的 12 条就是这 12 条换了行号），其它子门禁通过（Legacy error 0）
  - `Mini program static checks`：汇总上面两项（UNIT_RESULT / STATIC_RESULT = failure）
- 「Funds MySQL IT」run `39758725221`：通过

## 7 DevTools 核对结果（本地，2026-10-22）

### 7.1 环境与安全回读
- 被测源码：`git archive HEAD chengyinhub-xcx`（HEAD `3176489c`）解到 `$TMPDIR/laned2/` 的临时副本。DevTools 没有打开本 worktree，也没有打开 `~/Downloads/hengyin`。
- 副本里 `utils/config.js` 的 `API_BASE_URL` 和 develop `apiBaseUrl` 都写死为 `http://127.0.0.1:8132`，`project.config` / `project.private.config` 设 `urlCheck:false`。每步前断言 `getApp().globalData.siteBaseUrl === 'http://127.0.0.1:8132'`，全部通过。
- 后端回读：`GET http://127.0.0.1:8132/` 返回「欢迎使用RuoYi后台管理框架，当前版本：v3.9.0」。这是经 SSH 转发的测试 VPS 迁移预览构建，不是生产。8099 未开，未使用。`actuator/info` 等版本端点需要登录（401），没有更细的构建号可读。
- 账号：测试玩家 uid **990044**（role=player），走 `/api/login/code` 登录。没有发生任何支付、手机号绑定或资金操作。
- 网络观测：在 app service 里用 `Object.defineProperty(wx,'request')` 包一层（原函数保存后在每步结束时恢复），逐条记录 url / method / body 键 / 回包码，且**拦截任何非 127.0.0.1:8132 的请求**。全部步骤 0 条被拦截，即没有任何请求发往非回环地址。注：`mockWxMethod('request')` 的回调会丢掉 `res.data.data`（登录因此报 noData），所以没有使用它。
- 定时器：全部用真实时间，**没有用假定时器**。
- 自动化：DevTools CLI `auto --auto-port 9473`，`miniprogram-automator` 0.12.1。每个脚本结束都 disconnect。没有关闭 IDE，也没有动其它项目窗口。
- 测试数据：在 8132 测试库为 990044 建了草稿 990054–990064（每次一份，`/api/project/my` 回读确认没有重复草稿），没有发布成功的主题。

### 7.2 逐步结果

| # | 核对项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 未命名时编辑 | PASS | 5.5 秒内没有 create/update，没有 toast；胶囊「已存本机」。`01-unnamed-local-only.png` |
| 2 | 连续输入只保存一次 | PASS | 12 次输入，间隔 450 ms；输入期间 0 次写入；最后一次按键后 **2029 ms** 发出唯一一次 `v2/create`（`saveDraft:1`，带 requestId），随后一次 `edit-detail`；胶囊 已存本机→保存中→已存云端；没有结果面板，`cy-loading` 0 次。`02-autosaved-cloud.png` |
| 3 | 不改动 10 秒 | PASS | 10 秒内 0 个请求 |
| 4 | 回读在途时继续编辑 | SIMULATED-PASS | 回读人为延迟 3 秒（代替 Network throttling）。顺序为 create →（延迟）edit-detail → **一次** `v2/update`，`id=990056`，`configVersion:"1"` 来自服务端，回包 200，没有「路线配置已更新」；最终标题是第二版，回读后 configVersion=2，「已存云端」。`04-edit-during-save.png` |
| 5 | 弹窗未提交内容 | PASS | 章节弹窗开着 5.5 秒：0 次写入，弹窗字段保持「D2弹窗内未提交章节」；点完成后只有一次 create，章节随之保存，回读后章节保留。`05-dialog-open-no-save.png` |
| 6 | 保存在途时换绑玩法模板 | NEEDS_USER_REAL_DEVICE | 990044 在 8132 上没有任何可绑的玩法模板（`tempList`、`publicTempList` 都为空），无法真实绑定；伪造回读需要构造完整 `savedContentIssue` 合同，不算真实复现。逻辑已由单测「保存在途时改了模板绑定」覆盖。手工步骤见 7.4 |
| 7 | 断网 / 结果不明 / 重开 | SIMULATED-PASS | 首次 create **真实到达服务端**（建出 990060），但客户端拿到的是失败（模拟丢回包）。胶囊 →「待确认」；本机信封里有该稿；6 秒内不自动重试。用 reLaunch 代替杀进程重开：恢复同一 draftUuid 和标题，随后 create **沿用同一 requestId**，服务端回 `recovered:true`、topicId 990060，接着 update + 回读 →「已存云端」，没有建出第二份。`07-offline-pending.png` |
| 8 | 隐藏 / 返回 | PASS | 编辑后 400 ms 跳到隐私页（真实 onHide）：离开 4.5 秒 0 次写入，本机信封在；返回后（真实 onShow）保存一次 →「已存云端」 |
| 9 | 保存与发布竞争 | PASS（附发现 F-1） | 自动保存回读在途时调 `submitForm`：toast「正在保存草稿，保存完成后再发布」，写入仍只有 1 次。保存完成后发布，顺序为 `v2/update` → `edit-detail` → `publish-draft`；publish-draft 在途时编辑没有触发自动保存。服务端拒绝发布（「专业主题至少需要一个章节和一个节点」，空主题预期如此），页面显示「草稿已保存，但还没有发布」。`09-publish-while-saving.png`、`09-publish-result.png` |
| 10 | 已发布主题 | SIMULATED-PASS | 测试库里没有 990044 已发布的主题，把真实 `edit-detail` 回包改成 `isDraft:false` 打开 990054：改名后 6 秒 0 次 create/update，「保存草稿」按钮不显示，胶囊「已存本机」。`10-published-no-autosave.png` |
| 11 | A 号切到 B 号 | SIMULATED-PASS | 编辑后 600 ms 把 `getApp().getUserID` 改为返回 990045：5 秒内 0 次写入，A 页不标已存云端；以 B 身份重开新稿，标题为空，`draftMemberId` 为 990045，没有任何写入。结束后恢复 990044。没有第二个真实账号，token 仍是 A 的，所以只验证了客户端守卫 |
| 12 | 手动「保存草稿」 | PASS | 点按钮：`cy-loading`「正在保存草稿」、结果面板「草稿已保存到云端」、胶囊「已存云端」；之后 8 秒 0 个请求。`12-manual-save-sheet.png` |
| 13 | 本机存储写满 | SIMULATED-PASS | `wx.setStorageSync` 改成抛 quota 错误：胶囊「本机未存」，toast「本地草稿保存失败，请检查存储空间」只弹一次。恢复后云端保存照常（create 990063）。`13-storage-full.png` |
| 14 | CAS 冲突 | SIMULATED-PASS | 已存云端后，下一次 `v2/update` 被模拟回 `{code:500,msg:'路线配置已更新，请刷新后重试'}`：胶囊「云端未存」，toast「自动保存未成功：路线配置已更新，请刷新后重试」只弹一次；继续编辑 6 秒 0 次写入（已暂停）；手动「保存草稿」立即发出真实 update，内容是最新输入 →「已存云端」。`14-cas-conflict.png` |
| 7/3 补 | 重开已存云端的草稿，不改内容 | ~~FAIL（F-2）~~ → **FIXED（`bc42f3b1`，复验 PASS，见 7.6）** | 打开 990057（未修改）后约 2 秒发出一次多余的 `v2/update`，内容与云端相同。在 990058 上复现：重开前本机已有该主题信封，页面 `_localDraftRestored=true` 且没有 `_cloudSavedKey`，没有任何编辑就排了自动保存。`15-reopen-cloud-draft.png` |

逐步 JSON 证据：`lane-d-shots/devtools-evidence.json`（时间为每步内的相对毫秒）。

### 7.3 发现

**F-2（已修复：`bc42f3b1`，复验见 7.6）：重开已存云端的草稿会无编辑地再上传一次**
- 现象：草稿保存成功后关掉，再打开同一份草稿，页面先提示「已恢复上次本地草稿」，然后约 2 秒后自动发 `v2/update`。服务端每次 update 都物理重建站点并把 `configVersion` +1，所以每次打开都会换站点编号、推进版本。
- 根因：真机 / DevTools 的 `setData` 回调是异步的。`_runServerDraft.onSuccess` 换上回读版本时，`applyEditingTopic` → `_finishEditingDraftBootstrap` 的 adopted 分支会**同步**把 `_draftAutosaveReady` 设回 true，并删掉本机恢复稿。之后才执行的那批 setData 回调看到 ready=true 且是草稿 patch，于是又写回一份与云端相同的本机信封（同一机制也让空白新稿一打开就显示「已存本机」）。下次 `loadEditingTopic` 读到这份信封，按「本机恢复稿」处理：`_localDraftRestored=true`，不标已存云端，`_onDraftEdited` 排队上传。单测里 mock 的 `setData` 回调是同步的，所以没有暴露。
- 修复位置：`chengyinhub-xcx/pages/publish/fabu/index.js` 的 `_finishEditingDraftBootstrap` adopted 分支，把 `this._draftAutosaveReady = true;` 放进一个 `setData({}, cb)` 回调里，让换版本产生的回调先在 ready=false 下跑完，不再回写信封。新稿 `onLoad` 尾部的 `_draftAutosaveReady = true` 同理。作为兜底，`loadEditingTopic` 成功分支在本机恢复稿与云端回读的 `_cloudDraftKey()` 相同时，应按「已存云端」处理并不排队。再补一条单测：用延迟执行回调的 `setData` 走一遍「保存 → 重开」，断言没有写请求。

**F-1（PASS 附注，master 原有路径）：发布阶段失败会用回读版本覆盖发布在途期间的编辑**
- 现象：publish-draft 在途时（DevTools 自动化绕过遮罩）把标题改成「…-发布中再改」，发布被服务端拒绝后，`_onServerDraftFailed` 的 `res.stage==='publish' && res.detail && res.unknown===false` 分支 `applyEditingTopic(res.detail)`，标题被换回「D2-保存发布竞争」，胶囊显示「已存云端」。
- 真实界面里，发布期间有全屏 `cy-loading`「正在保存并发布」遮罩（已记录），用户基本无法在这时输入，所以风险低；但它与 §5.1 第 4 步「旧响应不得覆盖」口径不一致。
- 修复位置：同文件 `_onServerDraftFailed` 的这个换版本分支，套用发布成功路径的 `draftRevision !== this._draftContentRevision()` 判断（`_runServerDraft` 已有 `draftRevision`）。有后续修改时不换版本，只接上版本号并保持「已存本机」。

**观察（不判失败）**
- O-1：刚打开的已发布主题，加载后胶囊显示「已存本机」（与 F-2 同一机制）。修 F-2 后复验：加载后胶囊为空（`d3-s10`），已消失。
- O-2：发布被拒后，页内错误条标题是 master 原有文案「这次还没有保存」，同时胶囊显示「已存云端」（草稿确实已存），两者口径冲突。建议发布阶段失败时错误条标题改成「还没有发布」（`index.wxml` 的 `slopes-submit-error`）。

### 7.4 需要真机或第二个账号的手工步骤
- **#6 模板绑定不回退（NEEDS_USER_REAL_DEVICE）**：用一个至少有一个玩法模板的作者账号（测试库）。新建主题 → 加一章一个站点 → 站点绑模板 X → 等「已存云端」→ 开慢网（DevTools Network: Slow 3G 或真机弱网）→ 把站点换绑模板 Y，并在「保存中」期间再换回 Z → 等「已存云端」。核对：站点最终是 Z；最后一次 update 的 `chapters[].nodes[].templateId` 是 Z；重开后仍是 Z。
- **#11 真实双账号**：真机账号 A 编辑后 1 秒内退出登录、用 B 登录。核对 A 的保存没有发出（抓包或后台），B 打开编辑页看不到 A 的稿件或状态。
- **#7 真杀进程**：真机编辑 → 飞行模式 → 从多任务里划掉小程序 → 关飞行模式重开。核对恢复同一草稿、`requestId` 相同、「我的项目」只有一份。
- **#14 真并发**：两台设备打开同一草稿，先后改动，核对后保存的一端只提示一次「自动保存未成功：…」，之后编辑不再自动发请求，手动保存可用。
- **F-2 修复后复验**：保存成功后关掉、重开同一草稿，10 秒内不应有任何 create/update，胶囊直接是「已存云端」，也不应提示「已恢复上次本地草稿」。

### 7.5 结论
本轮执行的 14 + 1 项中，13 项 PASS 或 SIMULATED-PASS，1 项 NEEDS_USER_REAL_DEVICE（#6），1 项 FAIL（F-2）。F-2 已在 `bc42f3b1` 修复并复验通过（见 7.6）。F-1 与 O-2 是 master 原有行为 / 文案，留作后续，本 PR 不改。

### 7.6 F-2 修复与复验（本地，2026-10-22）

**修复**（`bc42f3b1`，只改 `pages/publish/fabu/index.js` 与 `tests/unit/fabu-cloud-autosave.test.js`）
- 根因修复：`_installDraftAutosave` 的 setData 包装改为在**发起 setData 的那一刻**判定是否算草稿编辑（`draftEdit = _draftAutosaveReady && _isDraftPatch(patch)`）。回调执行时只在 `draftEdit && _draftAutosaveReady` 时写本机并标脏。回填和换上回读版本都是在 `_draftAutosaveReady=false` 时发起的，它们的异步回调不再回写恢复稿，也不再把草稿标脏。旧本地版本原本就是这样判定的。
- 配套：这些异步回调过去也会「顺带」把恢复稿排进自动保存，现在改为显式处理。新增 `_markRestoredDraft`：恢复稿内容与云端已确认的内容（`_cloudLoadedKey`，在换上恢复稿之前记下）相同时，只标「已存云端」；不同时标「已存本机」并置排队标记，定时器和其它编辑一样由 setData 包装接力。用在三处：`loadEditingTopic` 读到恢复稿、冲突弹窗选「使用本地草稿」、旧草稿入口确认。加载和重试读取本身不直接调用保存，所以 `retryLoadEditingTopic` 在台账里不会多出写接口。
- 单测：新增「F-2：…重开不编辑零写入，之后一次编辑只存一次」（用延迟执行的 setData 回调模拟真机）和「F-2 配套：恢复稿与云端不同才上传一次，相同不发请求」。负控：只回退包装那一行，F-2 用例变红。`fabu-cloud-autosave` 19/19；fabu-* / b02 / pro-editor-* / publish-* 共 779 个全部通过；全量 13493 个中通过 13492，唯一失败仍是 `action-ledger.test.js:1672` byte-exact 台账。
- 子门禁（单跑）：js-scope-lint、ds-hardcode-gate --ci、undefined-token、wxss-selector、wxml-conditional、wxml-handler、js-orphan-handler、setdata-dead-field、a11y-tappable-name、a11y-tap-target、component-attr、ui-theme-unit --theme、verify:wechat-release 全部 rc=0；ui-integrity 仍只有 12 条 U4-DYNAMIC 行号漂移（24 violations）。
- 需集成方再生成的计数（更新）：`action-ledger.json` controlCount 4947 → 4947，`fabu/index.wxml` 236 个 id 重编，56 条 handler 行号移动，没有新增或删除 handler，也没有新增写接口；`ui-integrity-baseline.js` 的 `fabu/index.js` U4-DYNAMIC 行号：758→849、1010→1105、2698→2806、3758→3872、4139→4253、5202→5316、5461→5575、6117→6231、6127→6241、6180→6294、6521→6635、8419→8601。

**DevTools 复验**：全新 `git archive HEAD`（`bc42f3b1`）临时副本，安全措施同 7.1：config 写死 `http://127.0.0.1:8132`，`urlCheck:false`，每步断言 siteBaseUrl，非回环请求一律拦截（0 次），后端横幅仍为 RuoYi v3.9.0，uid 990044，自动化端口 9474。没有动其它项目窗口，没有关闭 IDE，每步结束 disconnect。

| 核对 | 结果 | 证据 |
|---|---|---|
| 重开已存云端的草稿，不编辑，等 10 秒 | **PASS** | 新建并自动保存 990065 →「已存云端」→ 重开前本机没有该主题的恢复稿（0 份）→ `?id=990065` 重开：10 秒内只有 `state-keys`、`edit-detail` 两个读请求，**0 次 create/update**；胶囊一直是「已存云端」，`_localDraftRestored=false`。`d3-16-reopen-no-edit.png` |
| 重开后一次真实编辑 | **PASS** | 改名后 **2025 ms** 发出唯一一次 `v2/update`（id 990065，`configVersion:"1"`，回包 200）；之后 6 秒没有再写；最终「已存云端」，标题为新名 |
| #2 / #3 连续输入与静置 | **PASS** | 最后一次按键后 2030 ms 发出唯一一次 create（990066），输入期间 0 次写入，胶囊 已存本机→保存中→已存云端；静置 10 秒 0 个请求。`d3-02-autosaved-cloud.png` |
| #10 已发布主题（SIMULATED） | **SIMULATED-PASS** | `edit-detail` 回包改为 `isDraft:false` 打开 990054：加载后胶囊为空（O-1 已消失），改名后 6 秒 0 次写入，不显示「保存草稿」按钮。`d3-10-published-no-autosave.png` |

逐项 JSON：`lane-d-shots/devtools-evidence.json` 中的 `d3-s16`、`d3-s02_03`、`d3-s10`。本轮在 8132 测试库新增测试草稿 990065、990066。

**仍为后续项（本 PR 不改）**：F-1（发布阶段失败用回读版本覆盖发布在途期间的编辑，master 原有路径）、O-2（发布被拒时错误条「这次还没有保存」与「已存云端」口径冲突）、#6 模板绑定的真机核对、7.4 中的真机 / 双账号 / 双设备步骤。
