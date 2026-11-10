# r2-play · 游玩页第二轮（2026-11-10）

- 分支 `claude/r2-play-20261110`，基线 `origin/staging/questo-review-20261110` @ `6d51e844`（qx-s1 已写 `STATUS: COMPLETE` 之后）。草稿 PR 指向 `staging/questo-review-20261110`。
- 范围：ROUND2.md「r2-play」一节的 9 条 + 全局规则 1/2/4/6。完局 / 结算页不属于本 lane，没动。第一轮已通过、本节没点名的地方保持原样（例如自由探索的店卡仍是半屏）。
- 没有改支付 / 退款 / 结算状态逻辑，没有新迁移，没有碰生产。主包体积不变（`pages/play` 是分包；按 0.633 × 源码字节估算主包约 1974 KiB，改前改后相同）。

## 逐条结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| a1-v3-06 | 已修 | 游玩页里背包、计划、队伍操作这几张半屏原来用的是「纯黑页底」token（`player-page` = #000），全部换成玩家弹层面 token（`player-glass`）；背包整屏页用实底 `player-bg`，不再透出下面的故事字。站点卡本身改成整屏（见 a1-v3-21）。弹层底色的具体数值由 r2-visual 的 token 统一提亮，本 lane 不写死颜色。 | `pages/play/theme-backpack.wxml`、`theme-player-ops.wxml`、`theme-plan-sheet.wxml`、`theme-backpack.wxss` | 截图 `a1-v3-06-before/after`；单测 `r2-play-20261110`「规则 1」 | 合入 r2-visual 的新 token 后的实际颜色 |
| a1-v3-10-53 | 已修 | ① 同一时刻只有一个返回：入口卡在故事流里时，顶栏不再出「返回故事」那颗；试玩时玩法自己的返回在屏上，左上「返回草稿」那颗让开。② 摇一摇：弹球接上和抛硬币 / 掷骰子同一个摇一摇识别器（同阈值、同 900ms 防抖），整屏露出、还没开局时摇一下就开始（中震动），跑动中摇一下给球一脚（随机方向冲量 + 中震动）；四个传感器接口缺一个就不开，隐藏 / 关掉 / 中断后监听一律撤掉。③ 内嵌入口卡改成横卡：左边封面（站点配图，没有图时是玩法图标），中间类型名 + 站名，右边一颗播放钮，去掉「打开」两个字；按下微缩。类型名用作者在玩法目录里看到的名字（弹球、变色就点、倒计时、精准停表、找东西、预测、翻卡、相册），单测核对两边一致。 | `pages/play/index.wxml`、`index.wxss`、`utils/story-kit-card.js`、`components/playkit-ballshake/index.js` | 截图 `a1-v3-10-53-before/after`（试玩路径，弹球站）；单测 `r2-play-20261110` 4 条（入口卡、单一返回、摇一摇开始与撤监听、踢球）；`ballshake-recovery` 46 条全过 | 真机加速度计读数与震动手感；rig 没有传感器 |
| a1-v3-13-14 | 已修 | 顶部标题居中：左右各让 210rpx（右边是胶囊，左边是返回 / 罗盘），试玩和正式同一条中线，过长省略。左上角罗盘点开成地图（地图档）时，生命值 / 背包 / 属性那一行淡出上移让位；收回罗盘再出现（只用 opacity + transform，减少动态时无过渡）。改前地图档时这一行被挤成两行、「2/6」被截断（见改前截图）。 | `pages/play/theme-backpack.wxss`、`play-topbar.wxss` | 截图 `a1-v3-13-14-before/after`（罗盘档，数据行在）、`a1-v3-13-14-map-before/after`（地图档，数据行让开）；单测 | 真机胶囊宽度不同时标题的左右留白 |
| a1-v3-15 | 已修 | 延伸任务入口行加了任务旗标圆标、白底计数徽章、发丝描边和按压微缩；半屏里每站一张任务卡：左边站点配图（没有配图时是序号徽章），右边站名 + 地址（服务端节点字段，没有就不显示）；关闭在右上角。颜色只用玩家弹层 token。 | `pages/play/index.wxml`、`play-topbar.wxml`、`play-topbar.wxss`、`utils/story-kit-card.js` | 截图 `a1-v3-15-before/after`、`a1-v3-15-sheet-before/after`；单测 `qx-a1-play-walkthrough`（行数据带序号 / 图 / 地址）与 `r2-play-20261110` | — |
| a1-v3-16（含问题 V3-21） | 已修 | 背包装载的空格改成看得见的灰方块（原来几乎透明的描边框）；随身物品改成一行两格的正方形：有图铺图，没图写首字，底部一条压暗写名字和数量，点开仍是原来的物品详情；空格是灰方块，至少两行（4 格），件数为奇数时补一格灰方块。原来那张「背包图标 + 还没有物品」的空态卡去掉。 | `pages/play/theme-backpack.wxml`、`theme-backpack.wxss`、`index.wxml`（`tbAttr.pad`） | 截图 `a1-v3-16-before/after`（空背包）、`a1-v3-16-items-before/after`（3 件）；单测 | — |
| a1-v3-17 | 已修 | 入场码弹层去掉外层白卡，标题和到期行直接写在弹层上（弹层 token 色）；码块本身仍是白底方块（黑点码要白底才扫得出），尺寸与票夹背面同值。 | `pages/play/components/scene-qr-ticket/index.wxss` | 截图 `a1-v3-17-before/after`；单测 `dw29-r2-v02-scene-qr-ticket`、`dw29-r3-dot-qr`、`r2-play-20261110` | 真机扫码（rig 用的是占位码图） |
| a1-v3-21 | 已修 | ① 节点打开不再是半屏：城市定向的站点整屏打开，大封面（没配图时是一块深色主视觉）+ 站号角标 + 大站名 + 动作，关闭在右上角胶囊下方，没有抓手。② 定位只在进入这一局时问一次：之后任何入口（继续探索、开地图、导航、到达）都不再弹说明；系统里已经授权（例如玩家去设置里打开后回来）就静默取一次位置。③ 系统拒绝授权时给一张「打开定位」：写明「点右上角「···」→「设置」→「位置信息」，选「仅在使用小程序期间」」，按钮「去设置」打开设置页，打开后回来静默开始定位；这张只出一次。④ 问过且没开定位时，站点卡的主按钮就是「扫码到达」（直接绑原有扫码到达），站点卡下面那句「开始后可开启定位，也可扫码到达 · 可扫码到达」去掉，只保留定位精度不足那一句；从别的入口点到达只给一句轻提示「定位未开启，可扫码到达」。⑤ 提示改成居中弹窗（见 c4-ug-05），b4 合作局共享帮助也一起改成同款居中弹窗。⑥ 背包改成整屏页面：左上返回、居中标题「背包」、从右推入；物品详情、选择费用等子面板仍是半屏，盖在这一页上面。 | `pages/play/index.wxml`、`index.wxss`、`index.js`（只在原行上挂钩，行数不变）、新增 `utils/play-location-once.js`、`theme-backpack.wxml/.wxss`、`play-help.wxml/.wxss` | 截图 `a1-v3-21-node-before/after`（进页选「先用扫码」后点「继续探索」：改前又弹一次定位说明，改后直接进站点整屏）、`a1-v3-21-location-before/after`（进页那一次说明，未变）、`a1-v3-21-location-guide-after`（系统拒绝时的「打开定位」，rig 的定位接口不会失败，用 eval 调同一方法出屏）、背包页见 `a1-v3-16-*-after`；单测 `r2-play-20261110` 3 条、`r29-v3-11-entry-location`、`play-ui-contract`、`play-location-lifecycle-fence`、`roam-revocation-evidence` | 真机「去设置」往返、系统拒绝授权的真实 errMsg；站点整屏在刘海屏上的胶囊避让 |
| c4-ug-05 | 已修 | 提示的关闭钮移到右上角，和标题「提示」同一行；同一规则用于游玩页全部 tb-* 弹层（案件册、各站奖励、延伸任务、背包子面板、队伍操作）。提示本身改为居中弹窗（压暗 + 居中卡片，最高 80% 屏高，内容多时卡内滚动）。 | `pages/play/theme-backpack.wxss`（`.tb-close`）、`play-help.wxml/.wxss` | 截图 `c4-ug-05-before/after`（真实自玩局第 2 站问答，服务端 `enabled:true`）；单测 `qx-b1-play-help-panel`、`r2-play-20261110` | — |
| s0-fixture-play-entry | 已修 | 那块「bug」是进入故事流时的光圈转场：揭开时圆往回缩成屏幕中间一块实心黑圆，深色故事页上看起来像卡住的污点（业主截图正好截在这一刻）。改成揭开时圆继续略放大并整体淡出，画面从整屏均匀亮起，中间不留实心圆；盖上那一段不变。发布页预览的同一份副本同步（单测要求两份逐字一致）。 | `pages/play/components/look-transition/index.wxss`、`pages/publish/components/look-transition/index.wxss` | 截图 `s0-fixture-play-entry-before/after`：真实新局点「开始游戏」后，等转场进入揭开阶段再过 200ms 截图（同一时刻）；rig 里逐帧取样确认转场 1.2 秒内结束、状态清空；单测 `theme-look`、`r2-play-20261110` | — |

## 证据是怎么做的

- **rig**：`claude/cloud-walk-20261101-rig` 单独 worktree；打上 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（OSS 占位值，不是凭据）、qx-c1 的 `rig-app-media-seam.diff`、club 分支的 `harness-patches.diff`；Docker 镜像源用 `mirror.gcr.io`。后端从基线 `6d51e844` 构建（含 qx-c4/c5 的自玩局提示阶梯），改前截图的前端包也从基线构建，改后截图的前端包从本分支构建（`CAND=<本分支> mpweb/build.sh`），后端不变。
- **两种状态来源**：
  - 真实局：staging 的 `free_orient_theme.py` 建的免费城市定向主题，玩家 9004，真实 `/api/play/*`。`rig/fixture-run.txt` 是本次建局记录（签名已打码）。第 1 站先用 `rig/walk-setup.json` 打卡一次，改前 / 改后同一状态（继续探索打开第 2 站问答）。s0 需要「新开一局」，改前用新主题 990015、改后用新主题 990016，步骤相同。
  - 试玩路径：游玩页自己的预览入口（`pages/play/index?preview=1` + `applyPreviewData`，与编辑器试玩同一入口），数据由 `rig/gen.js` 生成（沿用 qx-a1，节点 1 加了封面、延伸站加了地址）。背包内容、HUD 数据行、入场码就绪态用 eval 写入本页数据（只在 rig 里），同一段 eval 用于改前和改后。
- 脚本：`rig/all.sh <before|after> [all|pv|real|tasks|bag]`、`rig/play.mjs`、`rig/play-driver.mjs`（加了 `waitEval`，用于等转场阶段）、`rig/gen.js`、`rig/walk-setup.json`、`rig/walk-location-guide.json`。
- 每张截图都看过：没有空标题、重叠、截断或互相矛盾的数字。改前 `a1-v3-21-node-before` 正好拍到「拒绝后点继续探索又弹一次定位说明」，就是业主说的「后面就不需要提示了」。

## 测试

- 新增 `tests/unit/r2-play-20261110.test.js`，13 条全过。
- 按新合同改的旧测试（每处都写了注释说明是本轮业主意见）：`qx-a1-play-walkthrough`（入口卡数据多两个字段、任务行多序号 / 图 / 地址、入口卡类名、背包空态）、`qx-b1-play-help-panel`（提示改居中弹窗）、`r29-v3-11-entry-location` 与 `play-ui-contract`（定位一局只问一次；系统已授权时静默取位置）、`play-location-lifecycle-fence`（第二次请求按新进入这一局处理）、`roam-revocation-evidence`（接入一局一问的真实现）、`overlay-levels-20261023-contract`（背包不再是 cy-sheet）、`dw29-r2-v02-scene-qr-ticket`（到期行不再与票夹白卡同色）、`action-ledger`（台账计数按重建实测更新并写明增减来源）。
- 动作台账：`build-action-ledger.js --write` 重建为 5877 个控件；`action-ledger`、`action-ledger-handler-sources` 共 75 条全过；没有出现「一个点击两次写入」的新条目。
- `press-feedback-coverage-contract` 全过（两个提示弹窗的压暗层按规范写 `hover-class="none"`）。
- `node tests/lint/ui-integrity.js`：`UI-GATE-0:PASS`。
- 涉及本次改动文件的 283 个单测文件（游玩页、背包、提示、顶栏、入口卡、入场码、转场、弹球、摇一摇、定位、台账、按压反馈）一起重跑：2770 条通过，0 条失败。
- 没有新增关键帧（仓库有关键帧只减不增的棘轮）；动效全部是 transition 或复用已有关键帧。

## 跨 lane

1. **r2-visual**：弹层底色 / 次级文字对比度 / 底部按钮圆角的 token 由 r2-visual 改。本 lane 只把游玩页里用纯黑页底当弹层面的地方换成玩家弹层 token，合入后颜色以 r2-visual 的 token 为准。
2. **r2-create（`pages/publish/components/look-transition/index.wxss`）**：这是玩家页转场的逐字副本，`theme-look` 单测要求两份完全一致，所以同步改了这一份；没有动发布页别的文件。
3. **r2-finish**：完局 / 结算页没动。

## 待你决定

1. **背包「新页面」**：做成游玩页里的整屏页面层（左上返回、居中标题、从右推入），没有做成独立路由。原因：背包的全部状态和 7 个子面板（物品详情、选费用、选奖励、领奖、交易、计划、合作帮助）都挂在这一局的游玩页上，独立路由要把整套状态机迁出去，属于大改。**默认已实现：页面层。** 如果一定要真正的新路由，需要单独一轮。
2. **「地图放大时隐藏数据行」**：理解为左上角罗盘点开成地图档（雷达变大）时隐藏生命值 / 背包那一行，收回罗盘再出现。雷达不能手势缩放，所以没有按 `bindregionchange` 的缩放级别接。**默认已实现：随地图档开合。**
3. **站点整屏**：只改城市定向的站点；自由探索的店卡第一轮已通过，保持半屏。**默认：只改城市定向。**
4. **摇一摇范围**：弹球接了摇一摇；故事流里内嵌的掷骰子仍然不摇（2026-09-18 业主裁决「走路误摇」），整屏掷骰子 / 抛硬币原本就能摇。**默认：只补弹球。**
5. **入口卡类型名**：用作者玩法目录里的名字（例如「弹球」），没有用口头的「摇一摇」。**默认：目录名。**
6. **问过但没开定位、从别的入口点到达**：不再弹说明，只给一句轻提示「定位未开启，可扫码到达」；站点卡主按钮直接是「扫码到达」。**默认：轻提示。** 如果连这句都不要，删掉即可。
7. **合作局共享帮助（b4）**：也是「提示」，一并改成居中弹窗。**默认：一起改。**
8. **随身物品空格数量**：至少两行（4 格），件数为奇数时补一格。**默认如此。**

STATUS: COMPLETE
