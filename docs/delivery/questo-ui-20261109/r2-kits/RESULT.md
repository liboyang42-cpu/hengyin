# r2-kits · 玩法组件(玩家端 + 作者端)第二轮结果

分支 `claude/r2-kits-20261110`,基于 `origin/staging/questo-review-20261110` 的 `6d51e844`(qx-s1 已写 `STATUS: COMPLETE` 之后)。
范围:ROUND2.md 的 `### r2-kits` 六项 + 全部全局规则。第一轮已批准、不在本节的内容没有动。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| b2-ug-01 逐字猜词(玩家) | 已改 | 题面与格子整组上下左右居中;格子缩小(60×76rpx);按词分组,一个词不折断、词与词之间换行(长答案不再横向滚走——改前 `NICOLAS AND PERNELLE` 只露出 6 格);去掉单独的「第 N 格」输入栏,点格子直接补字(隐藏输入框接键盘,字落在格子里);整词作答改成居中的圆角输入框;已补的格子下沿变白,当前格描边上浮 | `pages/play/components/playkit-hangman/*` | 单测 `questo-b2-player`(分组、无横滑、无「第 N 格」栏);截图 `b2-ug-01`、`-long`、`-typing`、`-solved` 改前/改后 | 真机键盘弹起时隐藏输入框的聚焦与中文输入法 |
| b2-ug-01 逐字猜词(作者) | 已改 | 编辑页「格子」缩小(56×72rpx)并按词分组,长答案不再乱折行;待补格不再写「_」,只留空格子;格子下留出间距 | `pages/publish/temp/index.wxml`、`index.wxss`、`questo-author.js`(`hangmanGroups`) | 单测 `questo-b2-author`(按词分组);截图 `b2-ug-01-author`、`-author-short` | — |
| b2-ug-02 图片选项(玩家) | 已改 | 选项配了图时改成两列图卡:图在上、字母 + 文案在下;选中描边上浮,对/错染描边;纯文字题仍是原来的细线文字行(试玩台合同不变) | `pages/play/components/playkit-qa/*` | 截图 `b2-ug-02` 改前/改后;`playkit-fidelity-contract` 只对纯文字行生效(图卡另一版式) | OSS 图片在真机上的加载 |
| b2-ug-02 图片选项(作者) | 已改 | 选项图与选项文案并排一行(左侧小方图、右侧文案);删掉「图片替代文字」那一栏和红字「配了图就要写替代文字」;存盘时替代文字自动取选项文案(服务端要求配图必带 alt,规则不变) | `pages/publish/temp/index.wxml`、`index.wxss`、`questo-author.js` | 单测(编辑中不报红、存盘自动补 alt、不覆盖已有说明);截图 `b2-ug-02-author` | — |
| b2-ug-03 指认结案(看板) | 已改 | 题目居中;线索板圆角大图;人物卡 3 人一排(4 人 2×2),圆形头像;选中一位后其余变淡、选中卡描边上浮并带勾;证词卡用引号显示;底部只留一枚主按钮「指认 某某」,去掉「再想想」(点别的卡就是换人) | `pages/play/components/playkit-accusation/*` | 截图 `b2-ug-03`、`-selected`、`-wrong` 改前/改后 | — |
| b2-ug-03 指认结案(确认) | 已改 | 不再用系统弹窗;改成本屏底部确认层:大头像 + 「这是最终指认:某某」+ 身份 + 一枚「确认指认」;关闭在右上角(规则 2),没有第二个取消按钮;底色走玩家弹层 token `--cy-comp-sheet-player-bg` + 顶边亮线与上投影(规则 1,不是黑色,不写死灰);确认后才交原 `SUBMIT_QA {optionId}` | 同上 | 单测(不用系统弹窗、右上角关闭、token 底色);截图 `b2-ug-03-confirm` 改前/改后 | 真机上确认层与键盘、返回手势的叠放 |
| b2-ug-03 指认结案(作者) | 已改 | 删掉「待运行验证」标签;人物 = 头像小方图与姓名/身份同一行;证词整行写,「拿到哪条线索后显示」在它下面,不再左右挤成两小格 | `pages/publish/temp/index.wxml`、`index.wxss` | 单测(无「待运行验证」);截图 `b2-ug-03-author`、`-author-2` | — |
| b2-ug-04 结案结果 | 已改 | 结论屏整屏居中:上方动图 + 大字结论(页面标题字号)+ 结局段(卡片标题字号,原为正文小字);正常结案 / 提前结案 = 🎉 庆祝动图,指认失败 = 😵 动图 + 红字,结算确认中 = 🔎 放大镜四处找人(「侦探找凶手」的等待);减弱动效时只出第一帧静图。动图放在 `subpackageTools` 小分包,按分包异步化引用(声明 `componentPlaceholder`),不进主包、也不进已接近单包上限的 `pages/play` 分包 | `pages/play/components/playkit-accusation/*`、新增 `subpackageTools/components/case-anim/*` | 单测(结论 → 动图种类、占位声明、每张素材 < 100KB);截图 `b2-ug-04`、`-early`、`-failed`、`-pending` 改前/改后 | 真机上分包异步加载的首帧(加载前占位是空 view)、GIF 帧率 |
| b2-ug-10 谜面翻转 | 已删 | 玩家端:题面不再镜像/倒序,`clueMode` 整条链路删除;作者端:删掉「谜面呈现」下拉,试玩也不再倒序;服务端:存量配置里的 `clueTransform` 节原样保留(主人说字段可以留),但运行期视图与公开投影都不再改写题面、也不再下发模式 | `pages/play/components/playkit-qa/*`、`playkit/index.wxml`、`utils/playkit-view.js`、`utils/playkit-questo.js`、`pages/publish/temp/*`、`advanced-game-preview.js`;Java `AdvancedGameRuntimeServiceImpl`、`AdvancedGamePublicProjection` | 单测 `questo-b2-player` / `questo-b2-author`;Java `AdvancedGameHelpWiringTest`(改为「永不渲染给玩家」)22 过、`ClueTransformSupportTest` 21 过、`AdvancedGameRuntimeServiceImplTest` 231 过;截图 `b2-ug-10`(玩家)、`b2-ug-10-author`、`-author-text` | — |
| b2-x-cat 玩法目录入口 | 已改(按默认理解,见待你决定 1) | 「选择玩法」半屏恢复第一轮之前的样子(问答组 8 枚,与改前截图逐像素一致),不再单列「逐字猜词」;逐字猜词改在「文字问答」的表单里用开关切换(打开 → 换成逐字猜词表单,已填的正确答案带过去;逐字猜词表单顶部同一个开关关回整词作答) | `pages/publish/utils/publish/node-game-catalog.js`(`pickerKey`)、`pages/publish/temp/r5-editor.js`、`questo-author.js`(`qzHangmanForm`)、`index.wxml` | 单测(选择器不再单列、`pickerKey('hangman') = 'qaText'`、表单两处开关);`generic-gameplay-r5-editor`、`temp-picker-modifiers` 计数 24 → 23;截图 `b2-x-cat` 改前/改后、`b2-x-cat-form-after` | — |

## 动图素材来源与许可(规则 8)

| 文件 | 来源 | 许可 | 处理 | 大小 |
|---|---|---|---|---|
| `subpackageTools/components/case-anim/img/search.gif` / `.png` | Google Noto Animated Emoji U+1F50E 🔎(fonts.gstatic.com/s/e/notoemoji/latest/1f50e) | CC BY 4.0 | 缩到 144px、10 帧/秒、32 色;首帧另存 PNG | 58KB / 11KB |
| `.../img/success.gif` / `.png` | 同上 U+1F389 🎉 | CC BY 4.0 | 144px、12 帧/秒、48 色 | 35KB / 8KB |
| `.../img/fail.gif` / `.png` | 同上 U+1F635 😵 | CC BY 4.0 | 128px、10 帧/秒、32 色 | 91KB / 15KB |

署名写在 `case-anim/index.js` 文件头。LottieFiles 在本容器网络里被代理拒绝(403),所以没用 Lottie;Noto 动图是谷歌官方发布、许可明确,而且不需要引入 lottie 运行库。

## 证据

- 截图在 `shots/`,390×844、JPEG q70,改前 = 基线包(staging,玩法相关源码与 `6d51e844` 完全相同),改后 = 本分支,同一页面、同一份数据、同一组步骤。每张都看过:无空标题、无重叠、无截断、数字不矛盾。
- 玩家屏:真实 `pages/play/index` + 真实 `cy-playkit` 组件;用本树自己的 `pickPlayKit` 把服务端形状的会话视图(字段名照 `AdvancedGameRuntimeServiceImpl` / `HangmanGameplay.publicProjection` / `accusationView`)变成 kit 再渲染。驱动 `rig/kit-shots.mjs`,数据 `rig/kit-states.mjs`,每个状态重开一次页面。
- 作者屏:真实 `pages/publish/temp/index`(编辑玩法模板),创作者 `e2e-openid-9002`,真实后端。驱动 `rig/author-shots.mjs`。选项图与人物名直接写进页面数据(rig 的上传会走内容安全桩,报「安全检查服务异常」,与本项无关)。
- rig:`claude/cloud-walk-20261101-rig` + staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff` + qx-c1 `rig-app-media-seam.diff` + club 分支 `harness-patches.diff`;换包用 `rig/swap-bundle.sh`。
- rig 限制(HARNESS):没有建一个带这几种节点的已发布主题,所以玩家屏不是由真实 `/api/play/advanced/start` 会话驱动的(与第一轮 qx-b2 相同);rig 里 `<icon>` 单选图标不渲染(编辑页「这个是正确答案」左边空白是 rig 现象)。

## 检查

- `node --test tests/unit/questo-b2-player.test.js tests/unit/questo-b2-author.test.js`:全部通过。
- 全量小程序单测 `node --test "tests/unit/*.test.js"`:见 PR 说明里的最终数字。
- `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write`:5878 个控件;`action-ledger.test.js` 分母按重建实测更新(tap 2927 → 2928,合计 5875 → 5878)。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`:`UI-GATE-0:PASS`,没有新增基线条目。
- Java:`mvn -pl chengyinhub-admin -am -Dtest=AdvancedGameHelpWiringTest,ClueTransformSupportTest,AdvancedGameRuntimeServiceImplTest test` 274 个全过。
- 主包:`mainpkg-size-report.js` 3,233,159 字节 × 0.633 ≈ 1998.6 KiB(与 staging 相同,< 2048 KiB);动图只进 `subpackageTools` 分包。

## 跨 lane

1. r2-play:`pages/play/index.js` 第 7598 行还把节点的 `clueTransform` 塞进会话视图。本 lane 已让 `pickPlayKit` 不再读它,所以这一句现在是无效赋值;那个文件归 r2-play,我没动,请 r2-play 顺手删掉。
2. r2-visual:确认层底色用的是 `--cy-comp-sheet-player-bg`、顶边用 `--cy-comp-sheet-player-border`、关闭钮用 `--cy-comp-sheet-player-close-bg/-fg`。r2-visual 抬亮玩家弹层 token 后这里会自动跟上;底部按钮圆角统一后,玩法屏 `.g-btn` 与确认层按钮(现为 56rpx 胶囊)请一起换成新 token。

## 待你决定

1. **b2-x-cat「不是这种啊 不是表单吗」的意思。** 我的理解:逐字猜词不该在「选择玩法」里单独多出一枚标签(那一枚把问答组撑乱了),而应该是「文字问答」表单里的一种作答方式。**已实现的默认**:选择器恢复原样;文字问答表单里加「逐字猜词」开关。如果你的意思是别的(比如整个选择玩法改成表单式列表),告诉我。
2. **指认看板去掉了「再想想」。** 点别的人物卡就是换人,确认层右上角关闭就是反悔,所以不再放第二个按钮。**已实现的默认**:底部只留「指认 某某」。
3. **逐字猜词的格子比 88rpx 点击下限小。** 你要求格子小一点;格子现为 60×76rpx(作者端 56×72rpx)。**已实现的默认**:按你的要求缩小;点到哪一格都会弹键盘补字,点不准的代价很低。
4. **动图素材。** 用的是 Google Noto 动态表情(CC BY 4.0,需署名,已在代码与本文件署名)。**已实现的默认**:🔎 等待 / 🎉 结案 / 😵 失败。如果你想要更「侦探」的定制动图(人物拿放大镜),需要你提供或授权购买素材。
5. **谜面翻转的存量数据。** 已经存了 `clueTransform` 的模板,字段照存不删,只是永远不再呈现。**已实现的默认**:不做数据清理(清理需要对线上库写迁移,不在本 lane 范围)。

STATUS: COMPLETE
