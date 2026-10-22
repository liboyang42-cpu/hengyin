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
