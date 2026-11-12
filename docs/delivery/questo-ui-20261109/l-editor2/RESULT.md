# l-editor2 · 章节编辑器里正文段不能拖(上线小修)

业主原话:「In the chapter editor, the text elements should not be draggable.」

分支 `claude/l-editor2-20261110`,基线 `origin/launch/questo-20261110` @ `6e19181e`。草稿 PR → `launch/questo-20261110`。
拖动内核沿用 l-editor 的做法(`l-editor/RESULT.md` ED-02 / ED-03:长按在逻辑层量几何,跟手、让位、拆段落点在 WXS 渲染层跑),本线只改「谁能被拿起」,不动 WXS 内核和数据层命令。

## 条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| ED2-01 正文段不能拖 | 已修 | 正文段(`type === 'text'`)右侧的六点把手不再渲染;`onStoryDragArm` 按块型拒绝正文段:长按不量几何、不拿起、不震、不选中。点一下仍然是改字(textarea 照常聚焦),长按留给系统选字。原先「正在输入的那段长按留给系统、按把手仍可拖」的特例随之退场(正文已经一律不拖)。 | `chengyinhub-xcx/pages/publish/fabu/index.js`(`onStoryDragArm`)、`chengyinhub-xcx/pages/publish/fabu/index.wxml`(`.story-grip` 的 `wx:if`) | rig:`ED2-editor-before/after`(前:每段正文右侧都有把手;后:只有图片组有)、`ED2-text-longpress-before/after`、`ED2-text-drag-before/after`、`ED2-text-release-before/after`。前:长按长段 → `storyDrag.key = lid3`,往下拖 120px 后松手,顺序变成 `…, 杯底压着一张, 她推开咖啡馆`,该段被选中;后:同样操作 `storyDrag.key` 始终为空、顺序不变、没有选中(截图里的蓝色是 rig 浏览器的鼠标选字,对应真机的系统选字)。单测 `story-article-editor`:新增「正文段长按不拿起、不震、不选中」(输入中 / 不在输入 / 带把手标记 / 下标对不上四种情况)与「WXML 正文段不出把手」 | 真机上长按正文是否只出系统选字菜单、不误触其它手势 |
| ED2-02 其它块照常拖,能落在段间、能拖进段落中间拆段 | 已修(保持) | 图片组、图片、音频、玩法节点、分支、分割线等非正文块照旧长按拿起;正文段仍是落点,也仍可被拖进中间按句末拆成两段(几何里正文段照旧带 `kind / len / bounds`)。 | 同上(未改 `story-drag.wxs`) | rig:`ED2-image-lift / split / drop-before/after`:图片组被拿起 → 拖进「她推开咖啡馆的门…」三句段 → 槽出现在第一句之后 → 松手后数据回读 `text:雨停了, text:她推开咖啡馆的门，铃铛响了一声。, gallery, text:吧台后面没有…, text:杯底压着…`,撤销条「已插到第 2 段中间,段落拆成两段」;前后两边结果一致。单测:原先拿正文段做拖动样例的 4 个用例改用分割线块(拿起量几何 / 原地松手选中 / 早松手 / 拖进段中拆段),`l-editor-story-drag-split`、`l-editor-story-blocks` 未改且全过 | 同 l-editor ED-02 的真机项(长按→拖动一次连续触摸、自动滚屏手感、触感) |

## 门禁

- 单测:`node --test` 跑 `story-article-editor`(34)、`l-editor-story-drag-split`、`l-editor-story-blocks`、`action-ledger`、`publish-story-editor-hit-targets-contract`、`pro-editor-fabu-flow`、`press-feedback-coverage-contract`,共 154 例全过。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`:**`UI-GATE-0:PASS`**,没有新增登记。
- 动作台账:`build-action-ledger.js --write` 重建为 5995 个控件,唯一差异是 fabu 把手那一行的 `visibleCondition` 变成 `{{!storyEditor.preview && block.type !== 'text'}}`;id、行号、条数都不变,没有新增基线条目。
- 只改了发布分包 `pages/publish/fabu`,主包体积不变;没有新 `@keyframes`、没有 WXSS 改动。
- 没跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`;没碰生产、后端、migration。

## rig 说明(只在 rig,不进产品代码)

- rig 用 `claude/cloud-walk-20261101-rig`,叠加 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`、club 的 `harness-patches.diff` 与 r2-create 的 `patch-runtime.py`(WXS 响应事件 / `change:` 观察器 / 受控 textarea)。后端是 launch 底座 `6e19181e` 的 jar(本线没有后端改动),Docker Hub 限流,MySQL / Redis 改从 `mirror.gcr.io` 拉同一 digest(只改了 rig 的候选工作树,不进仓库)。
- 「前」用底座的包拍,「后」用本分支的包拍,同一后端、同一数据、同一操作序列;驱动脚本 `l-editor2/rig/ed2-shots.mjs`。正文与图片组用页面自己的 `_storyCommand` / `_insertStoryMedia` 写入(rig 没有软键盘),长按与拖动是鼠标真实按住拖动。底座里正文被拖动后,脚本先 `undoStoryStep` 撤回原顺序,再拍图片拖动,保证两边图片拖动从同一状态开始。

## 待你决定(都已按默认实现)

1. **整段正文怎么删**:正文不能长按选中后,右上角红 ✕ 对正文段就没有入口了。**默认:清空文字后失焦自动删段、段首退格并入上一段(都是既有行为),以及撤销条。** 要保留「选中正文 → ✕」,可以在正文段上加别的入口(比如 ⋯ 菜单)。
2. **正文段换位置**:正文不能拖以后,要挪一段文字只能剪切粘贴,或者把它周围的非正文块拖过去。**默认如此。**

STATUS: COMPLETE
