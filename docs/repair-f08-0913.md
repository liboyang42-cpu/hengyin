# F08 修复记录：开局表单中途放弃（确认层被遮挡 + 放弃未清草稿）

日期：2026-09-13 · 范围：小程序「附近的局」组局表单的放弃流程（仅前端）
来源：`样板实跑报告.md` · P1 / F08（UI40、UI42）；报告 `样板实跑报告.md:76` 验收

本记录分三部分：
- **A（已交付）** 确认层被原表单盖住 → 层级修复。
- **B（已交付）** 独立 Spec 复核发现 P2：确认放弃后 `form/startDate/startTime` 未即时清空。
- **C（本次补漏）** 异步选择器（选点/选封面）回调无草稿身份保护，放弃后晚到会重新写脏。

> ⚠️ **纠正上次过早结论**：A 部分的复现步骤曾写「点『不开了』→ 草稿清空」，
> 那是**未证实的预期**。A 只改了层级，`confirmDiscard` 仍只关层 + `_formDirty=false`，
> 草稿要等下次 `openCreate` 才清 —— 与「不开了清掉未提交草稿并回到地图」不符。B 修正之。
> ⚠️ **再纠正 B 里一句**：B 曾断言「模态选择器的回调不会在确认放弃之后才落库」——
> 那是**静态猜测**。C 用真实页面 handler + 可控回调证明该路径确实可复现（见 C）。

## A. 确认层被原表单盖住

### 根因

`subpackageRoam/nearby/index.wxml` 的 `askDiscard` 确认层与 `sheet === 'create'` 开局表单
在 DOM 里同时存在（`askDiscard` 时 `sheet` 仍为 `'create'`），两者都用
`style/proto-sheet.wxss` 的 `.psheet { z-index:70 }` 与 `.pdim { z-index:60 }`。
同档时由 DOM 顺序决定绘制先后，create 表单排在确认层**之后** ⇒ 后绘制者盖住确认层。

### 修复

复用现有层级 token 的「面板压自身遮罩」写法（与 `style/components.wxss` 的
`.cy-mask`/`.cy-sheet`、`activity-picker` 同构）：

- `style/proto-sheet.wxss`：新增 `.pdim--confirm { z-index:var(--cy-z-sheet) }`（遮罩，800）
  与 `.psheet--confirm { z-index:calc(var(--cy-z-sheet) + 1) }`（面板，801）。
- `subpackageRoam/nearby/index.wxml`：确认层的 `.pdim`/`.psheet` 各挂对应修饰。

未改分档天花板（`--cy-z-celebrate` 仍 990），未动原型半屏自己的 60/70 基数，未引入新皮肤。

## B. 确认放弃未即时清空草稿（本次补修）

### 根因

`subpackageRoam/nearby/index.js` 的 `confirmDiscard`（约 512 行）只有
`this._formDirty = false; this._closeSheetNow();` —— 关掉确认层与表单层，但
`form`（标题/说明/地点/封面）、`startDate`、`startTime` 原样留在 `data` 里，
要等下次 `openCreate` 才被 `setData({ form: emptyForm(), startDate:'', startTime:'' })` 覆盖。
「放弃」并没有在放弃那一刻清掉草稿。

### 修复（最小）

把清空草稿抽成唯一入口，`openCreate` 与 `confirmDiscard` 共用，避免两处各写一遍字段：

- 新增 `_resetCreateDraft(callback)`：`setData({ form: emptyForm(), startDate:'', startTime:'', formError:'' }, callback)`。
- `openCreate()` 改为 `this._formDirty = false; this._resetCreateDraft(() => this._openSheet('create'));`（行为不变）。
- `confirmDiscard()` 改为 `this._formDirty = false; this._resetCreateDraft(); this._closeSheetNow();`（当场清空 + 回地图）。

「继续填」（`cancelDiscard`）**不动**：只收确认层，全部字段/日期/`_formDirty` 原样保留。

### 上传 / 异步回执的影响

B 当时认为模态选择器的回调不会在放弃之后落库，属**静态猜测**；C 证明异步回调确实能写回。
提交 `submitCreate` 的成功回调在闭包里捕获了 `const f = this.data.form`（标题等），
与 `this.data.form` 的重置互不影响；「提交在途时放弃」是既有的并发场景，不在本记录范围。

## C. 异步选择器的草稿身份保护（本次补漏）

### 根因

`pickPlace`（`pickLocation` → `wx.chooseLocation`）与 `pickCover`（`app.chooseImage`）
的回调直接 `setData` 写 `form` 并置 `_formDirty = true`，**不校验这次选择属于哪一份草稿**。
确认放弃会 `_resetCreateDraft()` 清空，但选择器回调可能晚到：
- 写回**已放弃**的草稿（数据回来、脏标记被置回）；
- 若之后又 `openCreate()` 开了新草稿，旧回调会**污染新草稿**。

### 修复（最小，草稿世代）

- `_resetCreateDraft()`（openCreate 与 confirmDiscard 的唯一重置入口）每次 `this._draftGeneration++`。
- `pickPlace` / `pickCover` 在**发起时**捕获 `generation`，回调里若
  `generation !== this._draftGeneration` 直接弃权，不 setData、不置 `_formDirty`。
- 未新增依赖、未新建框架；同一草稿内（含「继续填」未重置）回调照常生效。

## 改动文件（A + B + C）

| 文件 | 改动 |
|---|---|
| `chengyinhub-xcx/style/proto-sheet.wxss` | A：新增 `.pdim--confirm` / `.psheet--confirm` 层级修饰（复用 token） |
| `chengyinhub-xcx/subpackageRoam/nearby/index.wxml` | A：`askDiscard` 确认层挂两个修饰（仅加 class） |
| `chengyinhub-xcx/subpackageRoam/nearby/index.js` | B：新增 `_resetCreateDraft`；`confirmDiscard` 即时清空；`openCreate` 复用。C：`_resetCreateDraft` 递增 `_draftGeneration`；`pickPlace`/`pickCover` 按世代弃权迟到回调 |
| `chengyinhub-xcx/tests/unit/nearby-discard-layer-contract.test.js` | A 层级契约 + 两条负控；B 行为契约 + 一条负控；C 5 条异步回调行为（含 3 条 RED 锚点） |

## 验证

### A 层级（静态契约）

```
node --test tests/unit/nearby-discard-layer-contract.test.js
# 层级 3 条：确认层必须压过 create 表单；两条负控(去修饰 / 退回同档)判红
```

### B 行为（先 RED 再 GREEN）

```
node --test tests/unit/nearby-discard-layer-contract.test.js
```

- RED（旧码）：`F08 行为:确认放弃当场清空未提交草稿并回到地图` 判红 ——
  `放弃后 form 必须回到空表单`（form.title 等仍在）。
- GREEN（修复后）：**7 条全过**，覆盖：
  - 确认放弃当场清空 `form` / `startDate` / `startTime`、`_formDirty=false`、`askDiscard=false`、`sheet=''`（回地图）；
  - 「继续填」保留全部字段、日期与 `_formDirty`，确认层收起但表单还在；
  - 确认放弃后再次打开是干净表单，无上一轮残留；
  - 负控：`confirmDiscard` 退回「只关层不清草稿」必须判红。

### C 异步回调（先 RED 再 GREEN，真实页面 handler）

行为测试用真实 `pickPlace`/`pickCover` + 可控 `wx.chooseLocation` / `app.chooseImage`
回调（`holdPlacePick`/`holdCoverPick` 扣住回调再手动触发），不是静态猜测：

- RED（旧码，3 条判红）：
  - `放弃后迟到的选点回调不得写回已放弃草稿` —— `form.addressName` 被写回、`_formDirty` 被置真；
  - `放弃后再开新草稿,旧选点回调不得污染新草稿` —— 旧回调写进新草稿；
  - `封面回调同样受草稿身份保护` —— 放弃后迟到封面写回 `form.coverUrl`。
- GREEN（修复后，**12 条全过**）另覆盖：
  - 同一草稿内正常选点仍生效；
  - 「继续填」(`cancelDiscard`) 后在途选点仍生效（未重置世代）；
  - 新草稿内正常封面仍生效。

### 相关测试与 lint

```
node --test tests/unit/nearby-discard-layer-contract.test.js \
          tests/unit/roam-hangout.test.js \
          tests/unit/wxml-balanced-tags-contract.test.js \
          tests/unit/fabu-story-audio-preview-contract.test.js
# tests 40 · pass 40 · fail 0

npm run lint:scope                       # JS-SCOPE PASS: 495 个生产 JS
node scripts/a11y-tap-target-lint.js     # 过小命中区 0 处(基线 0)
```

### 门禁状态（按总控指示不改生成物，集成后统一处理）

- `npm run lint:ui` → **FAIL，但全部落在 `pages/publish/fabu/index.js`（22 条，属另一任务 F14
  的增行漂移）；`subpackageRoam/nearby` 零命中** —— 即 B 未给 UI 基线带来新漂移。
- `node scripts/uiaudit/build-action-ledger.js --check` → **FAIL**。重建 `+554/-477`、
  `controlCount 3963→3964`：其中 fabu 381 条（F14，含新增控件 `onPreviewStoryAudio`）、
  `subpackageRoam/nearby/index.js` 62 条（B+C 的**行号漂移，无新控件**）。
  **未改 `action-ledger.json`**，由总控集成后统一生成。
- 未为规避门禁压缩代码。

## 复现步骤与预期（供总控原生截图验收）

1. 打开「附近的局」→ 工具抽屉 → 「在这里开一局」。
2. 填标题/说明/选地点/封面/日期时间（`_formDirty` 置真）。
3. 点右上 ✕：确认层可见（A 的层级修复），其遮罩盖住表单。
4. 点「继续填」：确认层消失，**所有字段、日期、脏标记都在**。
5. 再点 ✕ → 点「不开了」：**当场清空草稿**、关闭表单、回到地图；再次打开是空表单。
6. 对照 `样板实跑报告.md:76`：背景表单不能截获确认点击；两条分支都可用真实可见命中区完成。
7. C 异步晚到：点「选一个点」/「封面」拉起选择器后，先关表单并确认放弃；选择器回调晚到 →
   不得写回已放弃草稿；随后再开新表单，旧回调也不得污染新草稿。

## 未验证项

- 未做原生/真机截图：真实可见点击与两条分支由总控后续截图验收。
- 未改后端、未连生产；未动 F20（已由总控 commit `a4c44d980`）/F14 文件。
- 未 push / 未开 PR / 未合并；未改 `action-ledger.json`、`ui-integrity-baseline.js`。

## 总控原生点击补验

在既有9763实例以真实节点tap覆盖继续填写保留草稿、再次关闭后放弃立即清空、工具入口重新打开空草稿。截图已实际查看。`/tmp/chengyin-f08-native-taps/receipt.json`记录8次tap；`cleanup.json`确认恢复并断开。原Orca AX读取失败不再作为本次自动点击的阻塞。日期通过方法设为合成测试种子；所有请求受控，创建/提交0次，不代表真人、真机或真实发布流程。
