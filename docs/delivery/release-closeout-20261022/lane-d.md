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
