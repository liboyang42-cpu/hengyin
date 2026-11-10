# r2-create · 创作者 / 编辑器 第二轮结果

分支 `claude/r2-create-20261110`，基线 `origin/staging/questo-review-20261110` @ `6d51e844`（qx-s1 完成后，`qx-s1/RESULT.md` 为 `STATUS: COMPLETE`）。
截图都在 `r2-create/shots/`，均为走查 rig 实拍：390×844，JPEG q70。「前」用 staging 包拍，「后」用本分支包拍，同一后端、同一数据、同一状态，每张我都逐张看过。
编辑器拖拽的方案按 `refs2/editor-research.md` 定：照 wxp-ui（MIT）的 WXS 渲染层拖拽内核，改写成支持块高不一。

## 条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| a5d 故事编辑器整体改（对标 X Articles） | 已修 | 顶栏左侧是「草稿 · 保存状态」，读页面真实的 `themeSaveState`（已存本机 / 已存云端 / 保存中…），不写「刚刚」。右侧依次是「预览」文字钮、「完成」药丸和「⋯」。⋯ 里有「章节设置」「退出编辑」，原底栏的「下一步」收进这里。工具栏是 B、I、S、段落样式下拉（正文 / 标题 / 副标题）、引用、项目列表、编号列表、表情、本章信息，右端钉着「插入 ▾」和字数。下面依次是 5:2 封面区（写章节 `imgArr` 第一张，玩家端章节卡本来就用它当封面）、大标题「添加标题」（就是章节名）、作者行（头像 + 昵称）和正文「开始撰写」。输入时工具栏贴在键盘上沿（沿用 a5d 的键盘高度）。「预览」切成只读排版。 | `pages/publish/fabu/index.{wxml,wxss,js}` | 截图 `a5d-empty-before/after`、`a5d-written-before/after`、`a5d-format-after`、`a5d-preview-after`、`a5d-insert-menu-after`、`a5d-emoji-after`、`a5d-info-after`；单测 `story-article-editor`（32 例）、`publish-story-editor-hit-targets-contract` | 键盘弹起时工具栏的位置；刘海屏安全区 |
| a5d 每个块都能拖 | 已修 | 每块左侧有一枚把手。长按块或把手即拿起：轻震一下，块放大 1.02 并带投影。之后跟手、其余块让位、贴上下沿自动滚屏，全在渲染层 WXS（`utils/wxs/story-drag.wxs`）里跑，不逐帧 setData。跨过一格回调一次逻辑层轻震（60ms 内合并），松手回调一次改顺序并中震。顺序改完后 epoch +1，WXS 用 `change:epoch` 清掉临时位移，不会闪回。长按后原地松手 = 选中该块。几何还没量完就松手，按原地松手处理，不会卡在拿起态。正在输入的段，长按留给系统选字，把手仍然能拖。单击把手出「上移 / 下移 / 删除」，作为拖不动时的替代路径，也给读屏用。 | `pages/publish/utils/wxs/story-drag.wxs`（新）、`fabu/index.{js,wxml,wxss}`；删除旧的 `utils/publish/story-drag.js`（逐帧 setData 版） | rig 里用鼠标真实长按并拖动：拖拽帧序列 `a5d-drag-f0-lift … f9-drop-before/after` 共 9 帧一组，拼图 `a5d-drag-strip-before/after.jpg`，另有视频 `a5d-drag-after.mp4`。后：图片块被拿起，下方段落上移让位；拖到底边自动滚到文末；落在音频之后，页面数据回读顺序为 `…音频, 图片`。前：旧版拿起的块压在工具栏上，文字被选中。单测在 node 里按原文件执行 WXS：跟手、让位量、跨格回调、贴边滚动与最大 scrollTop、epoch 清位移，以及页面的拿起 / 落下 / 原地松手 / 早松手 / 把手 | **真机长按→拖动一次连续触摸（iOS、Android）**；滚动后再拖；快速滚动时会不会误拿起；自动滚屏手感；`vibrateShort` 在两端是否都震（部分安卓忽略 `type`）；`touchcancel`（来电、系统手势）后能否恢复 |
| a5d 段首退格并入上一段 | 已修 | 非首段的 textarea 值前面垫一个零宽空格。微信 textarea 在段首按退格不发任何事件，垫了之后，退格删掉的是这个字符，页面就能收到一次 input。哨兵没了、正文不变，就并入上一段：光标落在接缝处，B/I/S 区间平移过去。上一块是图片 / 卡片时，空段直接收掉，非空段只选中上一块。光标落在哨兵前又打了字，会摘掉哨兵照常收字，不误判。 | `fabu/index.js`（`_storyUnwrapInput`、`_storyMergeUp`）、`utils/publish/pro-editor-story.js`（`mergeParagraphUp`） | 单测 `story-inline-format`、`story-article-editor`（哨兵收字、退格合并、哨兵前打字） | **零宽哨兵在 iOS / 安卓各输入法下的退格行为**；点在段最前面时光标会不会落在哨兵前 |
| a5d 文字格式（B / I / S、标题、副标题、引用、列表） | 已修 | 正文 `content` 仍是纯文字，所以章节摘要投影、内容安全检测和旧版玩家端都不受影响。正文段新增两个字段：`style`（h1 / h2 / quote / ul / ol）和 `marks`（`[{from,to,type}]`，bold / italic / strike），前后端用同一份白名单与上限。打字、回车拆段、粘贴多段时，格式跟着文字走：在格式区两端新打的字不带格式，在中间打的字带格式。标题后回车起正文，引用和列表接着用同一样式。编号列表按连续段从 1 数起。B/I/S 有选区就作用于选区（`wx.getSelectedTextRange`，输入框配 `hold-keyboard`），没有选区就作用于整段。有格式的段平时按格式显示，点它换成输入框。**后端**：`ChapterBlockDTO` 加 `style`、`marks`；`ChapterFlowCompiler` 校验（只许普通正文段；叙事段、台词不许；样式白名单；区间不越界；最多 200 处）并原样落库。 | `utils/publish/story-inline.js`（新）、`pro-editor-story.js`、`ChapterBlockDTO.java`、`ChapterFlowCompiler.java` | 单测 `story-inline-format`（11 例）；Java `ChapterTextFormatFlowTest`（2 例：往返一致、各类非法拒绝）与既有 `ChapterDividerFlowTest`、`ChapterBlockMoodFlowTest`、`ChapterEndingFlowTest`、`ChapterFlowCopyTest`，共 14 例全过；rig 真实接口往返见 `rig/format-roundtrip.txt`（本分支 jar：`POST /api/topic/v2/create` 带 style / marks，库里 `cms_topic_chapter.blocks` 原样保存，`description` 仍是纯文字；坏样式、越界区间被服务端拒） | 正在输入的那一段里看不到局部加粗（textarea 画不出），离开该段才显示；见待你决定 4 |
| a5-v3-46 玩法模板选择 | 已修 | 方卡从 210 加宽到 288rpx，一屏两张整加第三张露出约四分之一，横滑轨向右出血到屏幕边。「确定」改走弹层自带的底部固定 CTA（`cy-sheet footer`），不再跟在内容后面、下面空一大截。 | `fabu/index.{wxml,wxss}` | `a5-v3-46-before/after`（商家 9002 的 5 个真实模板） | — |
| a5-v3-47 顶部「完成」 | 已修 | 按你「要」的回答，去掉选择视图顶部的「完成」，标题写「玩法模板」，确定只在底部。主题里只能选公共模板、我的模板，或「自己建一个」（原模板表单），一轮已经做到，这轮没动。 | `fabu/index.wxml`、`publish-story-editor-hit-targets-contract.test.js` | `a5-v3-47-before/after`（选中态） | — |
| a5-v3-31 修改原模板 | 已修 | 不再是卡里一颗灰胶囊。现在是卡内一行：蓝字「修改原模板」，右侧「影响 N 个节点」加箭头，与上面「玩法模板」行同一套行结构。「仅为本站另存副本」也改成同样的行。 | `fabu/index.{wxml,wxss}` | `a5-v3-31-before/after` | — |
| b4-21 预设页 | 已修 | 撤掉 §21「从预设开始」按钮和 `pages/publish/preset` 整页（连同 `gameplay-presets.js` 及其单测）。玩法引导页的圆形旋转开场动画保留不动。同步更新 `app.json`（发布分包 −1 页）、`route-matrix.json`，以及路由 / 标题 / 间距 / GET 权限盘点和清理清单。 | `pages/publish/template-intro/*`、`app.json`、`scripts/uiaudit/route-matrix.json`、`docs/design/miniprogram-page-cleanup-1009.md` 等 | `b4-21-before/after` | — |
| b4-u02-01 采用条件 | 已修 | 只画有权威事实的行（地点、语言、版本……），不再摆一排「待补充 / 待核实 / 许可信息未提供」。标题去掉小图标。 | `pages/templatedetail/utils/topic-adoption.js`、`topic-detail/index.{wxml,wxss}` | `b4-u02-01-before/after`（真实 `/info`） | — |
| b4-u02-02 不选城市 | 已修 | 「应用」不再弹「沿用原地点 / 换一个城市」，直接按沿用原地点采用（`bindingIntent=SAME_CITY`；来源没有城市事实时按旧版采用），然后进编辑器。端上不再发 `NEW_CITY`。 | `topic-detail/index.{js,wxml,wxss,json}`、`topic-adoption.js` | 前 `b4-u02-02-before`（弹层）；后 `b4-u02-02-after`（真实 `/use` 返回 `topicId=990016`、`SAME_CITY`，进了编辑器） | — |
| b4-u02-03 地点待办 | 已修 | 每个地点一行：名称、当前地址、状态。行内「替换地点」只换这一处的地址，用编辑器原来的选点器，不再整节点换绑。同城沿用后还没绑定的地点，按同名节点认领，「确认地点」把这一处绑到它（同一条 `/binding`）。认领不到的才出「选择节点」。「看什么 / 怎么做 / 看不到时」三行收掉。 | `pages/publish/components/location-todos/*`、`utils/publish/location-todos.js`、`fabu/index.{wxml,js}`（`onLocationTodoReplace`） | `b4-u02-03-before/after`；点「确认地点」后 `b4-u02-03-confirmed-after`（真实 `/binding`：人民广场站变成「已绑定 · 未现场核验」，待办从 2 项变 1 项） | 替换地点用的原生选点器 |
| b4-u02-04 不需要换城市 | 已修 | 地点待办顶部那行「来源模板 #990014 · 来源修订 #n · 换城采用」删掉（编号是系统信息，不给作者看）。 | `location-todos/index.wxml` | 同 `b4-u02-03-after` | — |
| b4-u02-05 不显示「AI 生成」「来源待确认」 | 已修 | 模板详情节点图下面只留署名。 | `topic-adoption.js`（`creditText`） | 单测 `qx-b4-topic-adoption` | rig 夹具节点没有配图，没截到图 |
| 问题：素材来源编辑「放到模板配置里」 | 已修 | 模板配置页本次上传的封面下面多一行「素材来源」（未声明 / 原创 / 已获授权 / AI 生成 / 公有领域）。点开是小弹层：关闭在右上角；来源用下拉选（全站单选一律下拉），加署名和授权说明；保存钉在底部，错误红字写在弹层里，进出场与本页其它半屏一致。保存走真实的 `PUT /api/media/asset/{id}/provenance`（服务端校验归属与字段）。授权说明必填，因为服务端公开校验对任何来源都要求它。不给作者选「来源不明」。 | `pages/publish/temp/index.{js,wxml,wxss}`、`utils/publish/media-provenance.js`（新） | `provenance-before/after`、`provenance-sheet-after`、`provenance-error-after`（未选来源红字）、`provenance-saved-after`；库回读 `rig/provenance-readback.txt`：`(2, 'original', '商家老板', '本人拍摄')`（本分支 jar）；单测 `r2-create-media-provenance`（4 例） | 只有本次上传拿到素材编号的封面能声明，旧封面只有地址，不出这一行；rig 的内容安全桩拒绝一切上传，所以素材行是 rig 库里放的一条（`seed_media_asset.py`，RIG-DB-ONLY），保存本身走真实接口 |
| a5-v3-29 发布者实名对齐 | 已修 | 票务页区块标题（自玩票、发布者实名等）的左沿对齐下面白卡的左沿，页容器已留页边距，标题不再额外缩进 38rpx。只作用于票务页。 | `fabu/index.wxss` | `a5-v3-29-before/after`（票价改成 ¥10 后出现实名区） | — |
| a3-v3-38 主题卡（和我的订单一样） | 已修 | 照「我的订单」卡的结构：左封面，右侧顶行是档期，状态字靠右（按订单卡的颜色语义），下面依次是标题一行粗体、地址，分隔线下的底行写「报名 · 浏览」或承接进度。卡面仍不放按钮，上下架 / 删除走长按（一轮裁决）。 | `subpackageA/pages/myproject/index.{wxml,wxss,json}` | `a3-v3-38-before/after`（发布者 9003 的真实主题） | — |
| a3-v3-39 模板卡（和我的订单一样） | 已修 | 顶行是「节点玩法」标签和广场状态，审核状态短词靠右；下面依次是标题、引用 · 时长、审核意见；分隔线下底行放操作（提交广场 / 上下架 / 审核记录 / 删除），位置同订单卡的按钮。 | 同上 | `a3-v3-39-before/after`（商家 9002 的真实模板） | — |

## 门禁

- `node tests/lint/ui-integrity.js`：`UI-GATE-0:PASS`（U1–U5、A11Y、Legacy 全过）。
  - U3：门禁原来不认 WXS 经 `ownerInstance.callMethod('名')` 调回的方法，这次修了检查器（`wxsCallMethodNames`），不是登记豁免。
  - U4：17 条既有动态 setData 锚点只是行号位移，脚本逐行校验调用行逐字相同，不新增登记，keyDigest 按门禁算法重算。`_storyCommand` 那条的理由里补写了新增的四个固定键。
- 动作台账：`build-action-ledger.js --write` 重建为 5885 个控件（tap 2939、事件合同 2946）。台账生成器同样修了工具：认「一支为空串的条件绑定」，也认本页 `<wxs module>` 的响应事件；不认识的表达式照旧失败。`action-ledger.test.js` 的计数按重建实测更新，并逐条写了来由。
- 小程序全量单测：`node --test chengyinhub-xcx/tests/unit/*.test.js`，**16988 例全过，0 失败**（staging 上原有的红项在 qx-s1 后已清零，本线没有新增红项）。
- Java：`mvn -pl chengyinhub-admin -am -Dtest=ChapterTextFormatFlowTest,ChapterDividerFlowTest,ChapterBlockMoodFlowTest,ChapterEndingFlowTest,ChapterFlowCopyTest test`，共 14 例，0 失败。
- 主包体积：主包源字节与 staging 相同（3,233,159 B × 0.633 ≈ 1998.6 KiB < 2048 KiB）。本线的改动都在分包里（`pages/publish`、`pages/templatedetail`、`subpackageA`）。
- 没跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。没碰生产，没碰支付 / 退款 / 结算逻辑，没改已有 migration，本线没有新 migration。

## rig 说明（只在 rig，不进产品代码）

- rig 用 `claude/cloud-walk-20261101-rig`，叠加了 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（OSS 占位值，不是凭据）和 club 分支的 `harness-patches.diff`。
- `r2-create/rig/patch-runtime.py`：rig 运行时补上真机 WebView 有、无头运行时缺的三样东西。
  - WXS 响应事件拿到 `(event, ownerInstance)`，以及 ComponentDescriptor 的 setStyle / addClass / getState / selectComponent / callMethod / requestAnimationFrame。
  - WXS `change:prop` 观察器。
  - 受控 textarea：聚焦时也应用新值，支持 focus、cursor 和 auto-height（同 qx-a5d 当时的补丁）。
  - 没有这三条，rig 里 WXS 拖拽根本不会动，textarea 一律 150px 高。
- 驱动脚本：`editor-shots.mjs`（编辑器与拖拽帧）、`adopt-shots.mjs`、`picker-shots.mjs`、`ticket-shots.mjs`、`provenance-shots.mjs`、`project-shots.mjs`。
- 夹具：
  - staging 的 `free_orient_theme.py` 建出主题 990014。qx-s1 的 `save_as_template.py` 另存为模板 990015，`rig_city_code.py` 给它补来源城市（RIG-DB-ONLY）。
  - `seed_my_templates.py` 用真实发布接口加审核，给商家 9002 发了 4 个节点玩法，让选择横滑轨有足够的卡。
  - `seed_media_asset.py`（RIG-DB-ONLY）放了一条已审素材，因为内容安全桩拒绝一切上传。
  - `format_roundtrip.py` 用本分支 jar 做格式往返。
- 编辑器截图里正文、格式是用页面自己的 `_storyCommand` 写进去的（rig 没有软键盘），拖拽是鼠标真实长按拖动。
- 玩法引导页在 rig 里显示「暂无精选示例」，因为新库没有精选数据。

## Cross-lane

- **r2-play（玩家端故事流渲染）**：服务端已原样保存并下发 `style` / `marks`，但玩家端 `pages/play/**` 现在仍按纯文字显示。要显示标题、引用、列表和段内加粗，可以直接用 `pages/publish/utils/publish/story-inline.js` 的 `segments(content, marks)`（切成带 bold / italic / strike 的小段）和 `listNumbers(blocks)`。本线没碰 play 文件。
- **r2-join**：票务页「自玩票」整段的撤除归 r2-join（a5-v3-29 自玩票模型）。本线只改了区块标题对齐，同一个 `step3.wxml` 里没动自玩票那段。
- **r2-visual**：底部按钮圆角 token 由 r2-visual 统一，本线的新底部按钮都用现成的 `cy-btn` / `cy-sheet footer`，没有写死圆角。

## 待你决定

1. **顶栏「发布」药丸**：X 的位置在这里写的是「完成」，没写「发布」，因为一章不能单独发布，发布在票务页走「检查并发布」。**默认：「完成」= 收起编辑器回到章节卡。**
2. **「展开」按钮没做**：编辑器已经是从胶囊下沿开始的全高弹层，再展开也没有空间（右上是微信胶囊）。**默认：不出这个按钮。**
3. **「链接」按钮没做**：产品口径不放站外链接，站内也还没有可链接的对象。**默认：不出这个按钮。**
4. **B/I/S 的显示时机**：小程序 textarea 画不出局部加粗，所以正在输入的那一段里看不到格式，离开该段后才按格式显示。另外 rig 读不到选区，没有选区时作用于整段。**默认如此。** 要做到输入时实时显示，得把正在编辑的段换成微信 `<editor>` 组件（只支持 WebView 渲染，存的是 delta），是另一档工作量。
5. **退格合并**：用零宽哨兵实现，单测和 rig 都过了，但各家输入法的退格行为**必须真机确认**。**默认已上；若真机不灵，退回一轮做法（空段失焦自动删、✕ 删段）。**
6. **地点待办里「票务之类的」逐项替换**：`binding-status` 没有票务项事实，这次只做了逐个替换地点。**默认：票务项不出**，等你说明「票务」具体指哪一项（节点优惠 / 商家券？）。
7. **采用结果未知**：`/use` 没有请求号，网络失败后再点「应用」可能多复制一份草稿。**默认：与改版前一致，允许再点。** 要真正幂等，需要后端给 `/use` 加 `requestId`。
8. **预设能力的后端**：前端入口和页面撤了，后端 `/api/preset/gameplay/*` 和预设草稿接口原样保留。**默认：不动后端**，要下线另开单。
9. **素材来源只给本次上传的封面声明**：旧封面只有地址，拿不到素材编号。模板里其它上传位（选项图、相册图……）这轮没加。**默认：先只放封面。** 要扩到其它上传位，告诉我哪几个。
10. **我的项目卡的状态位置**：一轮按你「审核中和标题应该在一行」放在了标题行；这轮按「和我的订单一样」挪到了顶行右侧，跟订单卡一致。**默认按本轮。** 主题卡仍不放按钮（长按管理），模板卡底行放操作。

STATUS: COMPLETE
