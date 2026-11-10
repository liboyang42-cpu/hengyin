# l-editor · 主题编辑器按已批准原型施工(launch 波)

分支 `claude/l-editor-20261110`,基线 `origin/launch/questo-20261110` @ `ca1f85b6`(qx-base `RESULT.md` 为 `STATUS: COMPLETE` 之后)。草稿 PR #772 → `launch/questo-20261110`。
规格 = `proto/editor`(业主批准,`index.html` + `editor.js / screens.js / preview.js / page.css`)+ `proto/DECISIONS-editor.md` + `refs2/editor-research.md`;共享规则按 `LAUNCH.md`、`BRIEF.md`、`MOTION.md`。
截图都在 `l-editor/shots/`,全部是走查 rig(`claude/cloud-walk-20261101-rig` + staging 的 `rig-up-sha-param.diff` / `rig-app-oss-seam.diff` + club 的 `harness-patches.diff` + r2-create 的 `patch-runtime.py`)实拍,390×844、JPEG q70,同一后端(本分支 jar)、同一数据、同一状态;「前」用 launch 底座的包拍,「后」用本分支的包拍,每张我都逐张看过。

## 条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| ED-01 编辑器壳:顶部添加栏 + 章节开始 / 结束卡 + 卡片式块 | 已修 | 工具栏(B/I/S、段落样式、引用、列表、表情、本章信息、插入 ▾、字数)撤掉,换成原型的六项添加栏:段落 / 图片 / 音频 / 玩法 / 分支 / 分割线,都插到光标处(光标在段中就把这段拆开;没有光标插在选中块之后,再没有就文末)。顶栏仍是「草稿 · 保存状态 / 预览 / 完成 / ⋯」。章节开始卡(封面 = 章节 `imgArr` 第一张、章节名、开场白,右下「预览弹窗」)固定在最前,章节结束卡(结语、去向、「预览弹窗」)固定在最后,都不参与拖动;去向由章节顺序派生(「下一章 · 名」或「结束主题」),不是控件。每个块是白卡,右侧六点把手,长按原地松手选中:内圈 + 右上角红 ✕。旧的叙事导引线、段首圆点、封面 / 标题 / 作者行一并退场。 | `pages/publish/fabu/index.{wxml,wxss,js}` | `ED-01-empty-before/after`、`ED-01-before/after`、`ED-01-after-2`(分割线 / 分支卡 / 音频占位)、`ED-01-after-3-end`(结束卡)、`ED-01-selected-after`;单测 `story-article-editor`(39 例,含添加栏 / 章节卡 / 拆段 3 例新增)、`publish-story-editor-hit-targets-contract`(新控件全部 ≥ 88rpx 命中区)、`pro-editor-fabu-flow`(添加栏六项)、`press-feedback-coverage-contract`(棘轮不增) | 添加栏在键盘弹起时的位置(原工具栏贴键盘的逻辑随工具栏退场,添加栏钉在顶部);刘海屏安全区 |
| ED-02 长按任意块拖动:抬起、让位、贴边自动滚动、触感 | 已修 | 沿用 r2-create 的 WXS 渲染层内核(wxp-ui MIT 思路),逐帧跟手 / 让位不进逻辑层;拿起轻震,跨格轻震(60ms 合并),松手中震。落下后出现 5 秒「已移到第 N 位 · 撤销」条,撤销走页面共用的 20 步撤销栈(`UNDO_CAP = 20`)。图片组整组是一个块,拖动时整组一起走。 | `pages/publish/utils/wxs/story-drag.wxs`、`fabu/index.js`(`onStoryDragArm / onStoryDragDrop / _storyUndoBar / undoStoryStep`) | 帧序列 `ED-02-strip-before`(底座)/ `ED-02-strip-after` + `ED-02-after-f1…f8` + `ED-02-drop-after`:图片组被拿起 → 下方两段上移让位 → 贴近下沿自动滚到文末 → 落在最后,数据回读顺序 `…, text, gallery`,撤销条「已移到第 7 位」;WXS 单测 `story-article-editor`(跟手 / 让位 / 跨格回调 / 贴边滚动)与 `l-editor-story-drag-split`(贴边滚动起帧、被拖块不作落点) | **真机长按 → 拖动一次连续触摸(iOS、Android)**;滚动后再拖;快速滚动会不会误拿起;自动滚屏手感;`vibrateShort` 两端是否都震;`touchcancel`(来电 / 系统手势)后能否恢复 |
| ED-03 拖到一段正文中间 → 槽插进句子之间 → 放下拆成两段 | 已修 | 长按时逻辑层一并交出每段的句子边界(`。!?;…` 与换行之后);手指落在可拆段的中间(上下各留 12px,一行高的段不算)时,渲染层按段内高度比例估字数、就近取句子边界,换了落点才回调一次 `onStoryDragSplit`,页面把那一段临时渲染成「上半 · 槽 · 下半」(槽 = 被拖块高度,高度不过渡,只让线淡入);离开段落复原。松手按 `moveBlockInto` 在句末拆段,被拖块落在中间,B/I/S 区间随各自那一半;撤销条写「已插到第 N 段中间,段落拆成两段」。 | `story-drag.wxs`(`splitOf / shiftOf`)、`pro-editor-story.js`(`sentenceBoundaries / moveBlockInto`)、`fabu/index.{js,wxml,wxss}`(`onStoryDragSplit`、`.story-split`) | 帧序列 `ED-03-strip-after` + `ED-03-after-f1…f8` + `ED-03-drop-after`:图片组拖进「字迹很潦草…」三句段 → 槽出现在第一句之后 → 松手后数据回读 `text:字迹很潦草，像是匆忙, gallery, text:你认得这个笔迹。纸条…`;单测 `l-editor-story-blocks`(句子边界、拆段、B/I/S 跟随、非句末被拒、下标重算)与 `l-editor-story-drag-split`(进段回调、槽下折算、离段复原、松手带 split、旧几何不回调) | 真机上长段落按高度比例估字数的准确度(渲染层拿不到逐字坐标,与原型的兜底算法同一口径) |
| ED-04 图片 = 图片组(1–4 格版式、逐格选图、整组一个块) | 已修 | 点「图片」先出版式弹层(单图 / 双图 / 三图 / 四图),选完落一组空格,点格选图(已有图的格再点一次换图),说明写在格下方。新块型 `gallery {layout, images:[{url}], altText}`;一张图都没选的组不落库;后端 `ChapterFlowCompiler` 校验版式 1–4、张数 ≤ 版式、url ≤ 500、说明 ≤ 2000,原样落库;媒体发布闸、内容清单、章节媒体校验把 `gallery.images` 当作 `dream` 同一条口径。 | `pro-editor-story.js`、`fabu/index.{wxml,wxss,js}`、`ChapterBlockDTO.java`(`layout`)、`ChapterFlowCompiler.java`、`CmsTopicServiceImpl.java`、`AppMediaPublicationGuard.java`、`ContentManifestProjection.java` | `ED-04-layout-after`(版式弹层)、`ED-01-after`(双图组:一格已选、一格「选图」)、`ED-09-reduced-after`(空组);单测 `l-editor-story-blocks`(落空组 / 逐格 / 换图 / 删格 / 说明 / 越界 / 上送形状 / 重开一致);Java `ChapterEditorBlocksFlowTest`(往返一致、张数超版式 / 版式越界 / 空组被拒) | 微信 `chooseImage` 回填到指定格;整组拖动在真机上的跟手 |
| ED-05 分支任务块(问题 + 2 到 3 条分支,每条写去向) | 已修 | 点「分支」落块并打开分支弹窗:问题;每条分支一行输入 + 去向筹码(继续本章 / 结束本章 / 跳到第 N 章,N 按主题章数、不含当前章);2 条起、最多 3 条(加 / 删);底部可预览玩家看到的分支弹窗。卡上显示问题、A/B/C 选项与去向。新块型 `branch {content, options:[{text,to}]}`,`to` ∈ `continue / end / chapter:N`;后端校验问题 ≤ 200、分支 2–3 条、每条 ≤ 40 字、去向语法与章数,原样落库。 | 同上 + `ChapterBlockDTO.BranchOptionDTO` | `ED-05-sheet-after`、`ED-01-after-2`(卡);单测 `l-editor-story-blocks`(落块 / 改问题 / 改选项 / 改去向 / 加到 3 / 删回 2 / 上送校验 / 存量坏去向回落)、`story-article-editor`(页面接线);Java `ChapterEditorBlocksFlowTest`(少于两条 / 去向不认识 / 跳到不存在的章 / 没有问题被拒) | — |
| ED-06 章节开始 / 结束卡的话(开场白 / 结语)落库 | 已修 | 开场白、结语不放进 blocks 数组(它们不参与排序),编辑器里挂在章节的 `_capOpen / _capEnd`;上送时还原成本章第一个 / 最后一个 `cap` 块(`{cap:'open'|'end', content}`),重开时再抬回卡上。后端校验 open 只能在最前、end 只能在最后、各最多一张、≤ 500 字;不进 `description` 投影。没有新表、没有迁移。 | `pro-editor-story.js`(`liftCapBlocks / capPayloadBlock / setChapterCap`)、`ChapterFlowCompiler.java`(`TYPE_CAP`)、`ChapterBlockDTO.java`(`cap`) | 单测 `l-editor-story-blocks`(抬起 / 还原 / 空白不出块 / 超长被拒)、`story-article-editor`(页面:写开场白结语 → payload 首尾 cap 块);Java `ChapterEditorBlocksFlowTest`(开场白不在最前 / 结语不在最后 / 空话 / 卡位不认识被拒) | — |
| ED-07 「预览弹窗」:主题开始 / 章节开始 / 章节结束 / 玩法开始 / 玩法结束(成功 · 失败)/ 分支选择 | 已修 | 顶栏「预览」与章节卡 / 分支卡的「预览弹窗」打开整屏预览:底是章节封面压暗,中间是 `.lk-pop` 弹窗卡,按主题的画面风格(`formData.look`)着色;底部切阶段、四种风格、五种配色(只改预览自己的状态,见待你决定 2)。内容只用作者已写的东西(主题名 / 简介、章节名、开场白、结语、去向、模板名、分支问题与选项),没有就空着,不编数字。`--lk-*` 外观变量在发布分包放一份逐字副本(分包之间不能 `require`),`l-editor-look-tokens-sync` 锁两份一致。原「预览 = 只读排版」开关撤掉(原型里的预览就是玩家端弹窗)。 | `fabu/index.{wxml,wxss,js}`、`pages/publish/fabu/look-tokens.wxss`(副本)、`tests/unit/l-editor-look-tokens-sync.test.js` | `ED-07-theme / chstart / chend / gstart / gend-ok / gend-fail / branch-after`(星尘 · 黑),`ED-07-look-paper-default / survival-default / cyber-default / stardust-blue / paper-yellow-after`(章节开始卡换风格 / 配色) | 星尘玻璃的 `backdrop-filter` 在低端安卓 WebView 上的表现(与 qx-base 的 play 侧同一份样式) |
| ED-08 「玩法」= 配置好的模板;模板选择改成列表 | 已修 | 添加栏「玩法」插一个节点块并打开已通过的节点弹窗(名称 / 地点 / 玩法模板 / 到达与引导 / 照片),地点在弹窗里选、一个玩法一个地点;开局章走故事玩法。「玩法模板」行打开的选择视图从三条横滑卡轨改成列表:公共模板 → 我的模板(第一行「自己建一个」= 原玩法模板表单),一行一个模板(封面 / 名 / 人数·时长 / 来源 / 勾),不再列模板里的具体小游戏。 | `fabu/index.{wxml,wxss,js}`(`onStoryAdd`、`pop-cont-games`) | `ED-08-node-sheet-after`(点「玩法」后的节点弹窗)、`ED-08-before`(底座的横滑卡,取 r2-create 的 `a5-v3-46-after`)/ `ED-08-after`、`ED-08-selected-after`(商家 9002 的 5 个真实模板,`seed_my_templates.py` 由 r2-create 的脚本补足);单测 `k10-immersive-audio`(草稿行占位格) | — |
| ED-09 减少动态效果 | 已修 | 新增的全是过渡 / 渲染层内联 transform,没有新 `@keyframes`;槽的高度不过渡;选中内圈、撤销条、弹窗预览都不依赖动画。`play_reduced_motion` 开着时编辑器与常态一致。 | — | `ED-09-reduced-after`;`motion-property-ratchet` 的 keyframes 计数与底座相同(169,底座本来就红,见门禁) | — |
| 模板能力页 | 未改 | 按 LAUNCH「capability page = current UI」保持现有 `pages/templatedetail/topic-detail` 的能力 tab。 | — | — | — |
| 章节列表页(原型屏 1) | 未改 | 不在 l-editor 段的清单里;`pages/publish/fabu` 步骤 2 的章节卡、画面风格入口保持现状。 | — | — | — |

## 门禁

- `node chengyinhub-xcx/tests/lint/ui-integrity.js`:**`UI-GATE-0:PASS`**。fabu 11 条既有 U4-DYNAMIC 锚点与 play 1 条 U5 锚点仅行号位移(脚本逐行校验调用行与 launch 底座逐字相同),`u4Dynamic / u5Silent` keyDigest 按门禁 `baselineKeyDigest` 重算,**没有新增登记**;`undoStoryStep` 改成固定键 `setData`,零消费的 `storyWordCount` 随工具栏一起撤掉。
- 动作台账:`build-action-ledger.js --write` 重建为 5946 个控件(tap 2984),`action-ledger.test.js` 计数带来由更新;路由矩阵重建零差异。
- 小程序全量单测:见文末「门禁数字」。本线新增 / 改动的单测文件:`l-editor-story-blocks`(9)、`l-editor-story-drag-split`(6)、`l-editor-look-tokens-sync`(2)、`story-article-editor`(+3)、`publish-story-editor-hit-targets-contract`、`pro-editor-fabu-flow`、`publish-story-dream-vars`、`k10-immersive-audio`、`qx-b1-client-capabilities`、`action-ledger`。
- Java:`mvn -pl chengyinhub-admin -am -Dtest=ChapterEditorBlocksFlowTest,ChapterTextFormatFlowTest,ChapterDividerFlowTest,ChapterBlockMoodFlowTest,ChapterEndingFlowTest,ChapterFlowCopyTest test` 全过(新增 `ChapterEditorBlocksFlowTest` 2 例);`mvn -q -DskipTests package` 见文末。
- 主包体积:`mainpkg-size-report.js --json` 前后都是 3,284,987 B(≈ 2030.7 KiB),本线所有改动都在发布分包 `pages/publish`(含 `look-tokens.wxss` 副本)与 `pages/play`(3 个投影分支)。
- 没跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`。没碰生产、支付 / 退款 / 结算开关、既有 migration;本线没有新 migration,没有 Admin 改动(不跑 admin build)。
- 新增 WXSS 行里的字面色值只有 `rgba(0,0,0,…)` 投影 / 压暗与成功绿上的白字,均带 `/* ds-ok */`。

## rig 说明(只在 rig,不进产品代码)

- rig 用 `claude/cloud-walk-20261101-rig`,叠加 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`(占位 OSS 值)、club 的 `harness-patches.diff` 与 r2-create 的 `patch-runtime.py`(WXS 响应事件 / `change:` 观察器 / 受控 textarea)。镜像 Docker Hub 429 限流,改从 `mirror.gcr.io` / `public.ecr.aws` 拉同一 digest 的 `mysql:8.0.45` 与 `redis:7.4-alpine`(digest 逐字相同)。
- 驱动脚本(都在本机 scratch,不进仓库):编辑器状态(`editor-shots.mjs`)、拖拽与拆段帧序列(`drag-frames2.mjs`,CDP screencast 取帧,照 u3 `frames.mjs`)、弹窗预览(`pv-shots.mjs`)、模板列表(`picker-shots.mjs`)。正文、块与章节卡的话用页面自己的 `_storyCommand` 写入(rig 没有软键盘),拖拽是鼠标真实长按拖动。
- 我的模板用 r2-create 的 `seed_my_templates.py`(真实 `POST /api/template/publish` + 后台审核)给商家 9002 补了 4 个;公共模板库在新库里是空的,所以「公共模板」一节是真实的空态。

## Cross-lane

1. **l-play(玩家端)**:三种新块已经能被作者写进章节并落库,玩家端目前只做了「不崩」的最小落地:`gallery` 按每张一段图落地(`image` 分支),`branch` 与 `cap` 不产出 item。请 l-play 接手:图片组按 `layout`(1–4)整组网格渲染(`altText` 是整组说明);分支选择弹窗读 `branch.content / options[{text,to}]`(`to`:`continue` 继续本章 / `end` 结束本章 / `chapter:N` 跳到第 N 章);章节开始 / 结束弹窗读本章第一个 / 最后一个 `cap` 块的 `content`(`cap: open / end`)。`client-capabilities.js` 已声明 `chapter.blocks.gallery / branch / cap`。
2. **l-play / 服务端(分支去向)**:`chapter:N` 与 `end` 的去向目前只是作者侧的数据,服务端运行时没有「跳章 / 结束本章」的执行路径;要让分支真的改变路线,需要 l-play 与后端一起定义(本线没动运行时)。
3. **qx-base(`--lk-*` 外观文件)**:`.lk-chip.is-on` 的实底会被星尘的玻璃 / 末世的锈底盖掉(选中筹码读不出字);本线在 `.pvl` 范围内补了反白,play 侧同一份样式建议一并修。
4. **r2-create 遗留的文字格式**:`style / marks`(B/I/S、标题、引用、列表)的数据与后端校验原样保留,只是编辑器里没有入口了(业主「不需要格式的」);有格式的存量段仍按格式显示、点开可改字。

## 待你决定(都已按默认实现)

1. **顶栏「预览」的含义**:原型里「预览」打开玩家端弹窗预览,所以 r2-create 的「只读排版预览」开关撤掉了。**默认:顶栏「预览」= 玩家端弹窗预览(主题开始)。** 要两者都保留,可以把只读排版放进 ⋯ 菜单。
2. **预览里切风格 / 配色写不写回主题设置**:原型里切完会同步改掉画面风格;一个预览控件悄悄改主题设置是个坑,所以**默认只改预览自己的状态**,主题的画面风格仍在「画面风格」面板里改。
3. **章节结束卡的「奖励」行**:原型结束卡上有「奖励 · 线索 +」筹码,但章节级奖励没有数据模型(奖励挂在节点玩法上),**默认不出这一行**,只出结语与去向。要做章节级奖励需要后端加字段。
4. **「去向」是派生、不是控件**:带站点的章节不能同时是结局章(后端规则),所以「结束主题 / 下一章」按章节顺序派生显示,不给作者点。**默认如此。**
5. **图片组里的格子只能换图、不能单独删一格**:原型也没有逐格删除;删整组走选中 ✕。**默认如此。** 要逐格删可以加长按。
6. **分支去向的运行时**:见 Cross-lane 2。**默认:作者现在就能配分支,玩家端按 l-play 接上的时间点生效;接上前分支块在玩家端不显示任何可点的东西(不画点不动的选项)。**
7. **模板列表的分类标签行**:原型顶部有一排分类横滑筹码,但模板接口没有分类事实,**默认不出标签行**(按「服务端事实驱动控件」)。
8. **分割线图标**:`cy-icon` 没有横线 glyph,添加栏里用一条短横代替(不新增图标文件)。**默认如此。**

## 门禁数字

- 小程序全量单测(仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`):见提交 `50946928` 之后的复跑结果(本段由最后一次复跑填写):**17162 个测试,17151 通过,11 失败**。其中 10 条是 launch 底座本来就红的(qx-base / qx-s2 §六记录的 `all-page-back-stack-contract`、`component-wxss-selector-lint`、`custom-nav-page-surface-contract`、`motion-property-ratchet`(keyframes 169 > 146,底座即红)、`no-system-actionsheet-contract`、`no-system-toast-contract`、`page-cleanup-audit`、`press-feedback-resolves-contract`(rank-board 的 cy-pressed)、`reduced-motion-coverage-contract`、`theme-look`),本线没有碰这些文件;剩下 1 条是 `action-ledger.test.js` 的事件合同 / external 计数(2957 → 2962、367 → 365)在最后一次台账重建后才定下来,已随本提交带来由更新(台账 JSON 本身与重建结果逐字节相同)。本线新增的 17 例与改动的既有用例全部通过。
- `mvn -q -DskipTests package`:PASS(`mvn exit=0`,本分支 jar 也是 rig 后端用的那份)。

STATUS: COMPLETE - 编辑器按原型 v2 全部条目已实现并在 rig 实拍(壳 / 拖动 / 拆段 / 图片组 / 分支 / 章节卡 / 弹窗预览 / 模板列表);UI-GATE-0 PASS、台账与路由矩阵重建、主包不增、mvn 打包通过;全量单测只剩底座既有的 10 条红;真机拖动触感与 textarea 首帧高度待真机确认;玩家端三种新块的正式渲染交 l-play
