# r2-visual 结果：共享 token、卡片、收集册、邀请记录、城市目录

分支 `claude/r2-visual-20261110`。起点是 `origin/staging/questo-review-20261110`：先在旧头 `07ce40be` 上开分支并推了 token 提交，等 qx-s1 写出 `STATUS: COMPLETE`（08:23 UTC）后，把 staging `6d51e844` 合进来，之后的改动都在合并后的树上做。

只改了小程序。后端、迁移、支付、退款、结算、权限都没动，也没有连生产。

截图在 `shots/`，都是 390×844、JPEG q70，用走查 rig 实拍：真实小程序包加真实后端 jar，不是真机。
- `-before`：staging `6d51e844` 打的包。
- `-after`：本分支打的包。
- 两组用的是同一个后端、同一个身份（玩家 9004）、同一套步骤。

每张截图我都看过。

## 共享 token（其它 lane 先合这个提交：`1c4260e6`）

| token | 新值 | 用途 |
|---|---|---|
| `--cy-comp-sheet-player-glass` | `rgba(44, 44, 46, .98)`（#2C2C2E 实底） | 玩家弹层面。cy-sheet、cy-scene-sheet、原型 `.psheet`、漫游入口弹层和游玩页各抽屉都读它，原来是 `rgba(28,28,30,.94)` |
| `--cy-comp-sheet-player-lift` | `inset 0 1px 0 rgba(255,255,255,.18), 0 -12px 40px rgba(0,0,0,.55)` | 弹层顶边高光加向上投影，让面板从页面里浮出来 |
| `--cy-comp-sheet-player-edge` | `rgba(255,255,255,.18)` | 需要单独画顶边线时用 |
| `--cy-comp-sheet-role-lift` | 玩家档读 `--cy-comp-sheet-player-lift`，商家档为 `none` | cy-sheet 面板的投影，按宿主主题切换 |
| `--cy-comp-sheet-player-inset-shadow` | 改成 `var(--cy-comp-sheet-player-lift)` | scene-sheet 原来用内阴影把顶边压暗，现在改成抬升 |
| `--cy-comp-sheet-player-card` / `-elevated` / `-raised` / `-surface` | `#3A3A3C` | 弹层里的卡片比面板高一档。原来的 #242424 比新面板还暗 |
| `--cy-comp-sheet-player-surface-strong` / `-interactive` | `#48484A` / `#545456` | 弹层里的控件底 |
| `--cy-comp-sheet-player-text-secondary` | `rgba(255,255,255,.80)` | 弹层二级字，在 #2C2C2E 上对比度 9.5:1 |
| `--cy-comp-sheet-player-muted` | `rgba(255,255,255,.62)` | 弹层弱化字，在 #3A3A3C 卡上 5.48:1（原 .45 只有 4.09:1） |
| `--cy-comp-sheet-player-overlay` | `rgba(0,0,0,.50)` | 遮罩把宿主页再压暗一些 |
| `--cy-color-sheet-handle` | `#8E8E93` | 抓手随弹层提亮 |
| `--cy-color-text-secondary` | `#C7C7C7`（原 #B7B7B7） | 玩家页二级灰字 |
| `--cy-color-text-tertiary` | `#A8A8A8`（原 #919191） | 玩家页三级灰字，压在弹层卡 #3A3A3C 上仍有 4.77:1 |
| `--cy-color-text-placeholder` | `#9E9E9E`（原 #868686） | 占位字 |
| `--cy-comp-bottom-btn-radius` | `var(--cy-radius-pill)` | 全站底部按钮统一圆角。底部动作条 cy-footer-bar、cy-sheet / cy-scene-sheet 的底部按钮、原型 `.btn2`、漫游入口弹层主按钮都读它 |

深色逃生口块（`.theme-dark`）里同名的灰字和抓手同步改了。商家白弹层不变。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| 规则 1 弹窗看不见 / 灰字看不清（全局） | 已修 | 新增和改值见上表。玩家弹层从近黑抬到 #2C2C2E 实底，加顶边高光和向上投影；弹层里的卡和字跟着各升一档；玩家页二、三级灰字提亮，对比度都 ≥ 4.5:1。 | `style/tokens.wxss`、`components/cy/sheet`、`components/cy/scene-sheet`、`components/cy/footer-bar`、`style/proto-sheet.wxss`、`pages/roam/index.wxss` | 截图 `a2-v3-18-*`、`a3-v1-p53-*`、`b8-x-p3-01-city-picker-after`。单测 `r2-visual-tokens.test.js` 用公式算对比度（页面底、#1C1C1E、#2C2C2E、#3A3A3C 上逐一 ≥ 4.5），另有负控。 | 真机 `backdrop-filter` 与投影在安卓 WebView 上的表现；真机灰阶观感 |
| 规则 5 底部按钮圆角 | 已修 | 新增 `--cy-comp-bottom-btn-radius`（胶囊），底部动作条、弹层底部按钮和原型半屏按钮都改读它。 | 同上 | `r2-visual-tokens.test.js`，含写死圆角的负控 | — |
| a2-v3-18 | 已修 | 删掉底部「公开位置已模糊处理」；三行图层收进一张分组卡（弹层内卡档），行高放宽，末行没有分隔线；去掉每行下面的副说明（「封面图片节点」等）；标题和第一行之间留出间距。 | `pages/roam/index.wxml`、`index.wxss` | `a2-v3-18-before/after`，`r2-visual-items.test.js` | — |
| a3-v3-02 / a3-v3-03 | 已修 | 订单详情的字段名（订单号、票价、姓名……）从 11px 三级灰改成 12px 二级灰（#C7C7C7）。全站灰字 token 也一起提亮。 | `subpackageMember/components/cy/scene-member-order-detail/index.wxss` | `a3-v3-02-*`、`a3-v3-03-*`。同一位置的字最亮像素从 154 升到 213（0–255）。单测 | — |
| a3-v3-22 | 已修 | 「查看详情 / 查看处理进度 / 查看退款详情」从 160rpx 宽的实心大药丸降成次级小药丸：宽度随字、高 52rpx、浅底白字，命中区仍是 88rpx。主题图是横图时（图片实际宽 ≥ 1.25 倍高），封面从 176 的方图改成 216×144 的横图。付款类主按钮不变。 | `subpackageMember/components/cy/scene-member-order-history/*` | `a3-v3-22-before/after`。单测验证方图不切、16:9 切横版。 | 横图封面没拍到：rig 没有对象存储，封面读不出来（before 和 after 都是灰底占位） |
| a3-v1-p53 | 已修 | 原来是通用场景弹层：让玩家手填「关联报名记录 ID」，列表标题显示「—」，而后端受理口必须带主题，这一点对不上。现在设置里的「投诉建议」改开现成的投诉页：选可投诉的活动 → 选类型 → 写内容 → 填联系方式，数据读 `/api/coop/complaint/topics`，和受理口用同一套判据；页面下面还有「我的处理进展」。页面上不再出现任何 ID 输入。 | `pages/shezhi/shezhi.js`、`tests/unit/qx-a3-20261109.test.js` | `a3-v1-p53-before/after`，单测 | 真实提交没有在 rig 里点（会写真实投诉记录） |
| a4-v3-27 | 已修 | 打开收集册有过场：三枚带齿孔的邮票（勋章、城市、藏品）从上方依次落下钉住，红图钉弹出，虚线路线把三枚连起来，然后整层淡出，露出收集册（参考 `refs2/album-map-stamps-frames.jpg`）。<br>细节：<br>· 只在从「我的」点三叠小卡进入时播一次；段间切换（勋章 / 邮票 / 藏品）不重播。<br>· 全程约 2 秒，点一下可以跳过。<br>· 只用 transform 和 opacity，打开「减少动态效果」（系统或 App 设置）时不播。<br>· 过场写成组件 `album-intro`，挂在 `album-head` 里，三段页面共用。 | `subpackageP3/components/album-intro/*`（新）、`subpackageP3/components/album-head/*`、`subpackageP3/images/album-intro/*`、`subpackageMember/components/cy/profile/index.js` | `a4-v3-27-intro-1-after`、`-intro-2-after`、`a4-v3-27-before/after`，单测 | 真机帧率 |
| a4-v3-34 | 已修 | ① 收集册入口去掉灰色卡底和描边，三叠小卡直接落在页面背景上。<br>② 三个图标换成新的 3D 图（来源见下）。<br>③ 跟手倾斜（V3-34 的回答是「要加上」）：手指按住卡堆拖动时，卡堆跟着手指俯仰、侧转（最大 16°），高光带跟着移动；松手回到静止角度。这部分用 WXS 在视图层直接改样式，不走 setData；「减少动态效果」打开时不倾斜。<br>④ 修了台账工具：`build-action-ledger.js` 原来解析不了 `{{tilt.move}}` 这种 WXS 绑定，会直接报错退出。现在 `xcx-scan.findEventBindings` 能识别「本文件 `<wxs module>` 声明过的模块.函数」，把它标成 WXS 响应事件，台账和绑定门禁都跳过它（它不进页面 JS、不发请求、不跳页）。没声明的模块照旧报错。 | `subpackageMember/components/cy/profile/index.wxml`、`index.wxss`、`pile-tilt.wxs`（新）、`subpackageMember/images/album/*`、`scripts/lib/xcx-scan.js`、`scripts/uiaudit/build-action-ledger.js`、`scripts/wxml-handler-lint.js` | `a4-v3-34-before/after`。单测跑 WXS 函数：拖动 (+40, −20) px 得到 rotateX 8° / rotateY 16°，松手复位，减少动态效果时不转；门禁的正控和负控。台账重建通过，`UI-GATE-0:PASS` | 跟手手感（rig 是鼠标，没有真触摸） |
| a4-v3-36 | 已修 | 漫游护照上面三张档案卡（漫游记录 / 集邮册 / 探索目标）统一成一种样式：同一浅底、居中的静态 3D 图标（世界地图 / 邮票 / 靶心）。（循环浮动会新增 keyframes，`motion-property-ratchet` 判红，所以没有做浮动。）原来的街市照片和外滩楼照片删掉了。官方活动大卡按业主说的不改。玩家自己有轨迹缩略图或邮票图时，仍然先显示自己的图。 | `subpackageRoam/components/roam-photo/*`、`subpackageRoam/assets/icons/*`（新）、`subpackageRoam/assets/photos/SOURCES.md`、`pages/roam/index.js` | `a4-v3-36-before/after`，单测 | — |
| a7-v1-p59 | 已修 | 按 Revolut 邀请页的结构重排：<br>· 顶部不再套卡，直接在页面底上放「首购奖励积分」大数字，下面一行是叠放头像加「已邀请 N 人 · 已到账 M 人」。<br>· 下面是按月分组的一张列表卡。每行：头像、名字、状态（已加入 · 首购待完成），右侧上方是状态胶囊（待解锁 / 发放中 / +N 积分），一律中性灰底。原合同规定奖励不能占用成功绿，所以已到账也不用绿色。胶囊下方是邀请时间。行间细线从文字列起。<br>· 所有数字都来自 `/api/user/invite_list` 和积分流水；奖励没同步到时大数字显示「待同步」，不写 0。<br>· 原来「奖励同步中」那行提示删掉了。入场动效保留。 | `subpackageA/components/scene-member-invite-history/*`、`scripts/selectors*.json` | `a7-v1-p59-before/after`，`myinvite-load-state-contract` 等原合同 | — |
| b8-x-p3-01 | 已修 | 城市不再单独占一行胶囊。顶部标题就是城市名，旁边有向下箭头；点它弹出城市列表（同一份 `/filters` 城市，当前城市打勾），选中即切换。筛选标签只剩一行。读不到城市时，标题是普通标题，不能点。 | `pages/topic/catalog/index.*` | `b8-x-p3-01-before/after`、`b8-x-p3-01-city-picker-after` | — |
| b8-x-p3-02 | 已修 | 作品卡：<br>· 封面满幅 320rpx；没有封面时用首页同一张路线插画，不再是灰底加一个小图标。<br>· 类别胶囊放在左上。<br>· 卡下依次是大标题、「城市 · 时长 · 站数」、价格和团体标签。<br>· 卡面去掉描边，改用抬升底。 | 同上 | `b8-x-p3-02-before/after` | 有真实封面时的样子（rig 没有对象存储） |

### 图标与图片来源

图标全部来自 Microsoft Fluent Emoji 3D，MIT 许可，© Microsoft Corporation，取自 github.com/microsoft/fluentui-emoji：

| 文件 | 内容 |
|---|---|
| `subpackageMember/images/album/medal-3d.png` | 1st place medal |
| `subpackageMember/images/album/stamp-3d.png` | Cityscape 放进齿孔邮票框合成 |
| `subpackageMember/images/album/object-3d.png` | Amphora |
| `subpackageRoam/assets/icons/history.png` | World map |
| `subpackageRoam/assets/icons/stamp.png` | 同上邮票 |
| `subpackageRoam/assets/icons/goals.png` | Bullseye |
| `subpackageP3/images/album-intro/*` | 过场用的三张 144px 小图 |

各目录都有 `SOURCES.md`。图片全部放在分包里，主包没有新增图片。

### 体积

- `mainpkg-size-report.js` 显示主包源码 3160 KB，base 是 3159 KB。新增的主包内容只有 token 和样式。
- 按 0.633 折算约 2000 KiB，在 2048 KiB 以内。不过余量已经很薄，这是 base 本身的状况。

## 测试与门禁

- 新增单测：`tests/unit/r2-visual-tokens.test.js`（3 条）、`tests/unit/r2-visual-items.test.js`（7 条）。
- 跟着改值的旧合同：`qx-a2-visual-system`、`sheet-halfscreen-glass-contract`、`club-onboarding-figma-contract`、`member-medal-icon-contract`、`profile-album-dark-p22`（业主这次要求去掉卡底，所以它原来「和排名卡同一张卡」的断言改成「直接落在深色背景上、不回浅色纸页」）、`qx-a3-20261109`、`myinvite-load-state-contract`、`screenshot-component-fixture-contract`、`action-ledger`。断言的意图都没变，只是追上了新的值。
- 台账：`build-action-ledger.js --write` 从 5875 增到 5881。tap 2927 → 2930，事件合同 2948 → 2951；本地 tap 2561 → 2564，本地事件合同 2852 → 2855。
  - 收集册过场：跳过 +1，拦截滑动 +1。
  - 订单封面判横图：bindload ×2。
  - 城市目录：城市名入口和城市列表行 tap +2，cy-tabs 的 change −1，城市弹层的 close +1。
- 全量小程序单测和 `ui-integrity.js` 的结果见文末。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## Rig

- 用 `claude/cloud-walk-20261101-rig` 的 worktree，打了以下补丁：
  - `claude/cloud-walk-20261101-club` 的 `harness-patches.diff`；
  - staging 的 `rig-up-sha-param.diff` 和 `rig-app-oss-seam.diff`（OSS 填的是占位值，不是密钥）。
- 后端用 staging 打的 jar。
- 切换 before / after 时只重打浏览器包、重启 web 服务：`rig/switch.sh <树>`，拍照用 `rig/shots.sh <输出目录> before|after`。
- 测试库夹具（只在一次性库里）写在 `rig/fixture.sql.txt`：
  - 用 `staging/rig/free_orient_theme.py` 建出主题和订单 74；
  - 用 `seed-walk.py add-players 4` 建 4 个被邀请人；
  - 给两个俱乐部填了城市，给主题填了 city_code；
  - 克隆出「西湖北线」（杭州）和「苏州河慢走」（上海）两个主题，让目录有两座城市可以切换。
- 收集册入口要用 eval 把 `profileMetricState` 置成 ready 才会显示，数据都是真实的 0。qx-a4 也是这么拍的。
- HARNESS 限制：
  - 没有对象存储，所以订单和目录的真实封面都读不出来；
  - 只有鼠标没有真触摸，跟手倾斜只能用单测验证。

## 跨 lane

- **r2-play：** 游玩页的 `.sheet`、`.pbar--tools`、`.gp2`、`.chpick` 本来就读 `--cy-comp-sheet-player-glass`，所以已经自动变成新的抬升灰。我没改 `pages/play/**`。如果要加顶边高光和投影，请在这些选择器上加 `box-shadow: var(--cy-comp-sheet-player-lift)`。底部按钮请读 `--cy-comp-bottom-btn-radius`。
- **r2-finish / r2-join / r2-kits / r2-merchant：** 弹层和底部按钮请直接用上表的 token，不要写死灰色或圆角。
- **r2-create（a3-v3-38 / a3-v3-39「和我的订单一样」）：** 订单卡这次只改了两处：查看类动作降成次级小药丸（`.orderbut2.orderbut-play`），横图主题封面改成 216×144（`.orderbox_li_con_tu--wide`）。卡片骨架还是 qx-a3 的 V3-22 结构。

## 待你决定

1. **底部按钮统一成什么圆角。** 默认用胶囊（`--cy-radius-pill`），因为游玩页、漫游、投诉建议等大部分底部按钮本来就是胶囊。想换成 12px 圆角矩形，只要改这一个 token。
2. **收集册三个图标。** 默认：勋章用金色「1」奖牌；邮票没有现成的 3D 邮票图，所以把 3D 城市图放进齿孔邮票框合成了一张；藏品用古陶罐。如果你有更想要的图库（比如付费 3D 库），换文件就行，路径不变。
3. **投诉建议入口。** 默认改开现成的投诉页，不再用弹层。原来的 `settings-feedback` 场景定义还留着，深链进来照旧能打开；要不要删，你说一声。
4. **图层弹层每行下面的小字。** 「封面图片节点」「队长头像与剩余名额」「公开内容，位置做合并显示」按「不写多余提示」的规则一起删了。要保留哪一行，告诉我。
5. **玩家灰字整体提亮。** 这一项影响所有玩家页的二、三级字，商家页不变。如果哪一页因此显得太亮，请指给我看。
6. **漫游护照三张卡要不要动起来。** 默认是静态 3D 图标。循环浮动要新增 keyframe，而 keyframes 棘轮（`motion-property-ratchet`，上限 146 组）不允许新增。如果你要动起来，需要先批准放宽这个棘轮，或者换成 Lottie 动图。
7. **订单卡横图封面。** 默认的判断是图片宽 ≥ 1.25 倍高就换成横版。rig 里读不到封面，需要你在真机上看一眼。
