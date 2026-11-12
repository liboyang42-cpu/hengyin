# l-play · 主题内游玩 UI（launch 波次，2026-11-10）

- 分支 `claude/l-play-20261110`，从 `origin/launch/questo-20261110`（qx-base 写出 `STATUS: COMPLETE` 后的 `ca1f85b6`）切出。草稿 PR #771 指向 `launch/questo-20261110`，没有合并、没有标记 ready。
- 范围：LAUNCH.md 的 l-play 一段 —— 按业主批准的 `proto/game/index.html` 第二组「游玩与阶段弹窗」施工：主题开始、章节开始（波点数字）、故事流（定稿文字动效 + 顶栏雷达 / 体力 / 背包 / 进度）、地点卡（整屏照片 + 玻璃面板）、玩法开始弹窗 → 嵌入 / 全屏、章节结束整屏 + 细粉尘消融、本局完成（绿 + 插图）、岔路弹窗、延伸任务翻页卡、背包页、提示弹窗、入场码翻面更大。只改 `pages/play/**`，玩法组件内部（kits）没动。
- 没有改后端、支付 / 退款 / 结算状态、迁移、生产；没有跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`；没有密钥。`app.json` 没动；动作台账按重建实测更新（见门禁）。
- 主包体积：`mainpkg-size-report.js --json` 前后都是 3,284,987 字节 ≈ 2030.7 KiB（新代码全在 `pages/play` 分包）。

## 逐项结果

截图都在 `shots/`，390 宽、JPEG q70，`<ID>-before.jpg` 来自 launch 底座的包（`ca1f85b6`），`<ID>-after.jpg` 来自本分支，同一页面、同一份试玩数据、同一组步骤（`rig/gen.js` + `rig/shots.sh`，走游玩页自己的 `?preview=1` 试玩入口与 `applyPreviewData`，外观为星尘 · 黑）。每张都看过：没有空标题、重叠、截断或互相矛盾的数字。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| ch-start 章节开始 · 波点数字 | 已修 | 章节门不再画三棱锥（业主「没有再画立方体」）。开场 = 满屏星点飞拢、锁成金色方块像素的章节数字「01」（KKQJxL 定稿 tiles 参数：格 9 · 方块 7.4 · 高光扫过），下面章眉 / 章名 / 说明 / 数据逐字解码，底部「读下去」亮起；点它（或点任意处）方块炸散回星点、整屏淡出进正文；停 3.6 秒自己进（2026-09-24 业主「章节门不要每章都点」的规则不变，页面兜底计时 4.2 / 10.8 秒也没动）。序章没有数字。门屏底色 / 字色 / 按钮读 `--lk-*`，没有外观时回落原型深色。 | `pages/play/components/chapter-flip/{index.js,index.wxml,index.wxss,index.json}`、`utils/chapter-flip-data.js`（`info.number`） | `ch-start-before/after`、`ch-start-2-after`（读下去后的正文）；单测 `l-play-20261110`「章节开场」、`chapter-flip*`、`p08-r4/r5d` | 真机画布帧率 |
| ch-end 章节结束 · 整屏 · 粉尘消融 | 已修 | r2-finish 的居中卡片退场。上一章完成 = 整屏：顶部本章照片渐入底色、星点锁成方块章节号压在照片上、成功插图（`cy-illus success` + 两点闪光）、「第 N 章完成 · 章名」、三格数据（用时 / 本章得分 / 坐标）从 0 滚上来（mm:ss 按秒滚、a/b 只滚 a、拿不到的格子整格不出）、底部「下一站：…」。点下一站（或停 5.2 秒）：照片按像素取色化成 1–2 px 的细粉尘，从右上角起带湍流往右上吹散（画布，`benchmarkLevel` < 20 时粒子减半），文字同步吹走，方块炸回星点；1.1 秒后下一章整屏浮现、星点再锁成「02」。减少动态效果：不吹散、数据直接写终值。 | 同上 + `chapter-flip-data.js`（`endInfo.cover`） | `ch-end-before/after`（完成屏）、`ch-end-dissolve-after`（吹散中）、`ch-end-next-after`（下一章开场）；单测 `l-play-20261110`「章节完成」 | 真机粉尘帧率（rig 里照片是占位图，粉尘偏暗） |
| story 故事流顶栏 | 已修 | 顶栏保持现有游玩页那一套（左上雷达 / 罗盘、体力心形、背包 n/m），补上原型的进度药丸「N/M」（底边一条随进度长，读 `--lk-*`）；体力 / 背包不再被挤成两行。文字动效沿用定稿 v3（`theme-look.js STYLE_TEXT`，未动）。 | `pages/play/index.wxml`（hud 行）、`play-stage.wxss` | `hud-before/after` | — |
| station 地点卡 · 整屏照片 + 玻璃面板 | 已修 | 城市定向的站点：照片铺满整屏（不再模糊 / 磨砂），站序药丸「第 N 站」与营业态在顶上，左上只有返回（没有分享、没有 ×）；信息放在一块盖在照片上的玻璃面板里（`.lk-pop`：星尘是透光果冻玻璃，其他外观 92% 实底）：名称、地址 · 营业时间、一段介绍、三个数字（距离 / 步行分钟 / 开放时段，只写拿得到的：距离要有定位或上一站，步行按 80 m/min 算，全天开放不写）、这一站绑定的那一个玩法横卡（封面 · 名字 · 一句话 · 钥匙插图），底部「导航」+ 主动作（到了 / 扫码到达 / 开启定位 / 开始任务）一排。自由探索的店卡不变。 | `pages/play/index.wxml`（节点详情卡段）、`index.js`（`openSheet` 一行挂 `_sheetFacts`）、`play-stage.wxss`、`utils/play-stage-pops.js` | `station-before/after`（试玩：无定位 → 主按钮「开启定位」；营业时间 09:00-17:00 → 「09–17 开放」）；单测 `l-play-20261110`「城市定向地点卡」、`r2-play-20261110` a1-v3-21、`play-ui-contract` | 真机定位后的「120 米 / 2 分钟步行」（rig 没有位置） |
| kit-start 玩法开始弹窗 → 嵌入 / 全屏 | 已修 | 业主「先是弹窗提示 进入游戏 然后游戏开始之后有两种情况 嵌入和全屏」：故事流的玩法入口卡（要整屏的那批 + 分支）、故事流里的玩法入口（非自动内嵌的 cue）、地点卡的「开始任务」点开先看一张居中弹窗：封面（没有就线索插图）、「模板 · 类型」「地点 · 站名」标签、名字、一句话、绑定地点 · 已到、「嵌入故事流 / 全屏」分段、「开始」。选「全屏」= 同一个 kit 盖整屏（不另开会话），退出只收回到流里；地点卡 / 入口卡进来只有整屏一种，不出分段。类型名用作者在玩法目录里看到的名字，认不出就不写。自动内嵌的玩法（2026-09-24 批准「读到就铺开」）照旧，不插弹窗。 | `pages/play/play-stage.wxml`（新）、`utils/play-stage-pops.js`（新）、`utils/story-kit-card.js`（`playKitState` 第 5 参 / `_collapseKitCard`）、`index.wxml`（cue / 入口卡 / 地点卡改绑 `openKitStart`）、`index.js`（`playKitState` 调用一行） | `kit-entry-before/after`、`kit-start-before/after`（弹球入口卡 → 弹窗：模板 · 弹球）；单测 `l-play-20261110`「玩法开始弹窗」 | 真机上「全屏」退出回流的滚动位置 |
| run-end 本局完成 · 绿 · 插图 | 已修 | 业主「应该是绿色的 不应该是对勾 应该是动画 获得了什么线索或勋章之类的」：结局整屏泛绿（成功绿 22% 叠在玩家黑上），勋章插图（`cy-illus badge`）落下 + 光晕 + 一圈光环外扩 + 两点闪光，「本局完成 · 获得勋章」、勋章名、结局名 · 摘要，共享彩旗一次；「获得勋章」与勋章名只在这一局真夺到勋章时写（`_wonMedals`，与完成页勋章堆同口径），没有就只说「本局完成」+ 成功插图。r2-finish 的白印章 / 光环 keyframes 删掉，全部改过渡（顺带修了 `theme-look` 对故事文字动效段之后不许有 animation 的那条红）。 | `index.wxml`（`pv-ending` 段）、`index.js`（`_showEnding` 一行挂 `_endingReward`）、`index.wxss`（删印章 / 光环块）、`play-stage.wxss` | `run-end-before/after`（eval 注入 `_wonMedals` 一枚 + 真结局卡）；单测 `l-play-20261110`「本局完成屏」、`play-chapter-story-blocks-contract`、`play-monochrome-contract` | 真机彩旗与光环同屏 |
| branch 岔路弹窗 | 已修 | 分支剧情在流里是一张入口卡（钥匙插图 · 「分支剧情」· 名字），点开是居中弹窗（名字 + ×），弹窗里仍是 `cy-playkit`（分支）本体：题干、正文、三个选项，提交链不变；选完的反馈也在弹窗里。不再盖整屏，故事流留在后面。 | `utils/story-kit-card.js`（`KIT_POP_FROM_CARD`、`pop`、`cardIllus`）、`play-stage.wxml`、`index.wxml`（`playKit.pop` 两处豁免）、`play-stage.wxss` | `branch-entry-before/after`、`branch-before/after`；单测 `l-play-20261110`「岔路」、`qx-a1-play-walkthrough` | — |
| ext 延伸任务 · 翻页卡 | 已修 | 业主「改成弹窗」「三个横向滑动的任务 … 有图 有任务说明 任务的信息」→ 原型翻页卡：居中弹窗，一次一张（配图或序号 · 第 i / n · 名字 · 一句说明 · 地点），点卡或右下箭头这张绕竖轴翻过去、下一张翻进来（透视写在每张卡自己的 transform 里，不在父级开 3D 上下文；减少动态效果直接切），圆点显示第几张，「去做」打开那一站的地点卡。说明取节点字段原样，没有不出。 | `play-topbar.wxml`（原 cy-sheet 段）、`utils/story-kit-card.js`（rows 多 `desc`、打开从第一张起）、`utils/play-stage-pops.js`（`flipExtTask` / `goExtTask`）、`play-stage.wxss` | `ext-entry-before/after`、`ext-before/after`、`ext-2-after`（翻到第二张）；单测 `l-play-20261110`「延伸任务翻页卡」、`r2-play-20261110` a1-v3-15、`qx-a1` V3-15 | 真机翻页手感 |
| bag 背包页 | 已修 | 页面结构沿用上一轮通过的整屏背包；没有配图的物品按名字挑一张插图（钥 → 钥匙、信 / 邮 → 信封、票 / 证 → 通行证、图 / 盘 → 雷达、书 / 卷 / 账 → 任务、币 / 分 → 积分，其余藏品），线索卡右上一枚线索插图，待领奖励行一枚积分插图。 | `theme-backpack.wxml`、`utils/theme-backpack.js`（`illusFor`）、`play-stage.wxss` | `bag-before/after`、`bag-clues-before/after`（rig 直接注入物品，没有走 `illusFor`，所以无图物品都是藏品插图；真数据按名字分） | — |
| hint 提示弹窗 | 已通过 | 上一轮通过（c4-ug-05），未动。 | — | — | — |
| qr 入场码 · 翻面 · 更大 | 已修 | 业主「可以的 但二维码可以大一点的」+ 原型 gp-qr：入场码是一张 600×920rpx 的票卡（来源票夹 `.wc__flip`）：正面主题封面 + 「入场码」+ 标题 + 到期行，码生成好 1.1 秒后自动绕竖轴翻到背面，背面米白卡 + 600rpx 白底码块（码 360 → 540），点一下翻回正面；减少动态效果直接到背面。 | `components/scene-qr-ticket/{index.wxml,index.wxss,index.js}`、`index.wxml`（宿主传 `cover`） | `qr-before/after`（背面）、`qr-front-after`（点一下翻回正面）；单测 `dw29-r2-v02-scene-qr-ticket`（码尺寸改 540，加翻面断言）、`l-play-20261110` | 真机扫码（rig 是占位码图）、翻面手感 |
| start 主题开始 | 已通过 | 上一轮通过，未动。 | — | — | — |
| cy-illus（底座，跨线修） | 已修 | qx-base 移植的 25 张插图里，物件层 SVG 由原型字符串拼接，同一标签上出现两个同名属性（`stroke` / `stroke-width`）。HTML 解析器容忍（取第一个），但 `<image>` 加载 SVG 走 XML 解析，重复属性 = 不合法 → 物件层整张不出，只剩白色底形（本 lane 第一轮截图里全部插图都只有白底）。在 `uri()` 里按 HTML 的取法去重（保留第一个），画面与原型一致；在 Chrome 里逐张探过，25 张物件层全部能加载。 | `components/cy-illus/art.js` | 单测 `l-play-20261110`「cy-illus 修」（逐张检查无重复属性）；`launch-look-tokens-illus` 仍过 | 真机 `<image>` 对 data URI SVG 的支持 |

## 门禁

- 单测（本 lane 相关文件，`node --test`）：`l-play-20261110`（12 条，新增）、`chapter-flip`、`chapter-flip-round3`、`play-chapter-hold-screen-p08-r4`、`play-chapter-hold-map-p08-r5d`、`r2-play-20261110`、`qx-a1-play-walkthrough`、`play-chapter-story-blocks-contract`、`theme-look`、`play-monochrome-contract`、`dw29-r2-v02-scene-qr-ticket`、`dw29-r3-dot-qr`、`play-ui-contract`、`overlay-levels-20261023-contract`、`press-feedback-coverage-contract`、`launch-look-tokens-illus`、`registration-seat-contract`、`scene-content-contract`、`remaining-scene-state-contract`、`primary-button-height-contract`、`r14-game-session-flow` 全部通过。按新合同改的旧断言（每处带日期注释）：`p08-r4`（「轻点跳过」→「读下去」按钮）、`r2-play` a1-v3-15（半屏 → 弹窗翻页卡）、`qx-a1` V3-10/53/11 与 V3-15（`playKitState` 多 `pop` / `cardIllus`、任务行多 `desc`、去做 = `goExtTask`）、`dw29-r2-v02`（码 360 → 540 + 翻面）、`action-ledger-handler-sources`（`onPlayKitAction` 控件 2 → 3）、`r11-g4-play-walk`（F15 眉头多勋章分支）、`action-ledger`（计数按重建实测，见下）。
- 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`）：17156 条，17143 过，13 红。其中 9 条是底座 `qx-s2/RESULT.md` §六登记的既有红（keyframes 棘轮、减动效覆盖、结算页根栈返回 / 顶栏标题 / 原生 toast / 页面清理清单、榜单组件系统 ActionSheet / 标签选择器 / `cy-pressed`），本 lane 没碰那些文件；底座第 10 条 `theme-look`（index.wxss 文字动效段之后有 `@keyframes`）因删掉 `pv-ending-ring` 已转绿。另 4 条是本 lane 新合同在整跑时还没对上的旧断言，整跑后已逐条改好并单独复跑通过：`action-ledger` 外部 tap 367 → 374（重建实测）、`action-ledger-handler-sources` 的 `onPlayKitAction` 控件 2 → 3（岔路弹窗里的内嵌 `cy-playkit`，五条请求计划逐个核过一致）、`qx-a1` V3-10/53 入口卡 `expandPlayKit` → `openKitStart`、`r11-g4` F15 结局眉头（夺到勋章写「获得勋章」，其余仍按「这一生 / 本局」分）。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`：**UI-GATE-0:PASS**（U2/U3/U4/U5/A11Y/Legacy 全 PASS），没有新增 baseline 条目；`pages/play/index.js` 只加单行挂钩，行数 9490 不变。
- 动作台账：`build-action-ledger.js --write` 重建为 5954 个控件（底座 5936，+18：玩法开始弹窗的关闭 / 嵌入 / 全屏 / 开始、地点卡横卡与导航、延伸任务翻页卡的翻 / 去做 / 箭头、岔路弹窗关闭、入场码翻面、章节门读下去 / 下一站）；`action-ledger.test.js` 的 tap 2979 → 2994、事件合同 2957 → 2960、local tap 2612 → 2620、local 事件 2861 → 2863、合计 5936 → 5954，R30 选章两条控件 id 只是行号位移（321 → 322、530 → 534）。
- 减弱动态覆盖 / keyframes 棘轮：本 lane **没有新增任何 `@keyframes`**（章节门、粉尘、翻页、翻面、光环全是过渡 / 画布），并删掉 r2-finish 的 `pv-ending-ring` 一组（实测 169 → 168；上限 146 仍是底座既有的红，其余 22 组属 r2-finish / r3-share / r2-join / r2-merchant）。新动画全部有 `--reduced` / `.play--reduced-motion` / `is-reduced` 兜底或走 `reducedMotion` 条件。
- `scripts/ds-hardcode-gate.sh`（暂存区模式）：新增 wxss 行里的字面值都是原型 px×2 或回落值，行尾带 `/* ds-ok */`，门禁无 ✗。
- 布局属性动画 / `transition: all`：0 新增。主包体积不变（上文）。

## rig

- 走查 rig：`claude/cloud-walk-20261101-rig` 单独 worktree，打上 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（占位 OSS 值）、qx-c1 的 `rig-app-media-seam.diff`、club 分支的 `harness-patches.diff`、qx-u1 的 `rig-wxs-event-shim.diff`；Docker Hub 429 限流，`/etc/docker/daemon.json` 加了 `mirror.gcr.io` 镜像源。后端 jar 从 launch 底座构建（本 lane 不改后端）。
- 状态全部来自游玩页自己的试玩入口（`?preview=1` + `applyPreviewData`，与编辑器试玩同一入口），数据由 `rig/gen.js` 生成（从 r2-play 的复制：加了分支 / 弹球配置、两章主题、延伸任务说明、外观）；章节完成屏用 `showChapterGate` 对第二章出屏，背包 / 入场码 / 本局完成用 eval 写入本页数据（只在 rig 里，改前改后同一段）。脚本 `rig/shots.sh <before|after> [id]`。
- rig 的局限：OSS 配图是占位灰图，所以粉尘颜色偏暗、入场码是占位码；没有位置，地点卡的距离 / 步行两格不出（只出开放时段）。

## Cross-lane

1. **qx-base / 所有用插图的 lane**：`cy-illus/art.js` 的重复属性修法见上表最后一行；其他分包逐字复制 `cy-illus` 时请复制修后的版本（`launch-look-tokens-illus` 的副本一致性断言会要求）。
2. **l-kits**：玩法开始弹窗选「全屏」后用的是 `playKit.show` 盖整屏的既有 `cy-playkit`，kit 内部没动；岔路弹窗容器挂了 `.chfull__kit`（同一组 `--pk-*` 嵌入变量）+ `--pk-embed-*` 改读 `--lk-*`，分支组件样式没动。
3. **l-after**：结局卡（`pv-ending`）仍在主题内（泛绿是业主要的），结算 / 排行榜 / 评价 / 分享在 l-after 那边按 `.lk-plain` 玩家黑。
4. **qx-final**：`r2-finish` 遗留的 `theme-look` 红（故事文字动效段之后的 `@keyframes`）本 lane 已修；`motion-property-ratchet` 的 keyframes 上限仍红（底座既有），其余 22 组不在本 lane 文件里。

## 待你决定

1. **章节门要不要等玩家点「读下去」**：原型有「读下去」按钮；2026-09-24 你定过「章节门不要每章都要点」。**默认已实现：两样都有** —— 开场屏停 3.6 秒自己进，「读下去」可提前；完成屏停 5.2 秒自己吹散，「下一站」可提前。要改成必须点，把 `chapter-flip/index.js` 的 `START_AUTO_MS` 计时和页面兜底计时去掉即可。
2. **自动内嵌的玩法不插「玩法开始弹窗」**：2026-09-24 批准的「内嵌玩法读到就直接铺开」保留；弹窗只在入口卡（要整屏的玩法 / 分支）、非自动入口和地点卡「开始任务」处出现。**默认如此。** 若希望每个玩法都先弹窗再铺开，需要改自动内嵌的时机。
3. **「嵌入 / 全屏」分段只在故事流入口出**：地点卡与入口卡进来的玩法本就只有整屏一种，不出分段。**默认如此。**
4. **本局完成屏的「+N 积分」**：原型有积分药丸；结局时刻拿不到服务端的积分增量事实（完成页 / 结算才有），**默认不写**，不拿本地数凑。
5. **章节门的星点 / 方块颜色**：画布里星点是原型浅色、方块是金色（KKQJxL 定稿），浅底配色（白）上星点看不见。**默认不改**；要跟外观走需要把 `--lk-*` 读进画布（组件拿不到 CSS 变量，要从页面传入）。
6. **星尘弹窗的压暗**：原型玻璃下压暗 .18；故事流底部的白色「继续探索」会透过玻璃糊成一道白光，**默认压到 .4**（仍看得见星野）。
7. **入场码尺寸**：码 360 → 540、码块 600rpx（原型 300×300 px → 600rpx）。**默认如此。**

STATUS: COMPLETE
