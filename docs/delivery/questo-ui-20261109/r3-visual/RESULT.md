# r3-visual 结果：票夹翻页、收集册环装卡堆、邀请到账动效、加载与切段过渡

分支 `claude/r3-visual-20261110`，从 `origin/claude/r2-visual-20261110`（`8263e076`，r2-visual 已写 `STATUS: COMPLETE`）开出，草稿 PR #764 指向 r2-visual 分支。

只改了小程序的三个分包页面和它们的单测，外加台账按工具重建。后端、迁移、支付、退款、结算、权限都没动，也没有连生产。主包一个字节没加（主包源码仍是 3160 KB，和 base 相同，按 0.633 折算约 2000 KiB，低于 2048 KiB）。没有新增图片，也没有新增 @keyframes。

截图在 `shots/`，都是走查 rig 实拍：真实小程序包加真实后端 jar，不是真机，390×844、JPEG q70。
- `-before`：base `8263e076` 打的包。
- `-after`：本分支打的包。
- 两组用同一个后端、同一个身份（玩家 9004）、同一套步骤（`rig/shots.sh`）。
- 动效另附横向帧条 `*-frames-before.jpg` / `*-frames-after.jpg`，从左到右是时间顺序。

每张截图我都看过。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| r3-1 票夹 | 已修 | 参考 `refs4/ticket-holder-frames.jpg`：<br>· 一次只放一张大卡，后面不再露出叠放的卡。<br>· 左右滑动换票：往左拖时，当前这张绕左边缘像书页一样卷起（最多 70°），卷起的一侧压出背光阴影；下一张从右边跟着手指滑进来，由 0.92 放大到 1。往右拖是上一张，方向相反。<br>· 松手时拖过 80px 或甩得够快就翻过去，离开的那张整页卷走；否则弹回。<br>· 顶栏改成导航下一行：左边是「票夹」大标题，右边是「+」（去首页探索主题拿票）和本人头像（回「我的」）。没有头像时不显示头像。<br>· 全屏模糊背景仍取当前票的封面（原有）。点卡翻面看入场码、「开始游戏」都不变。<br>· 只动 transform 和 opacity；打开「减少动态效果」时直接换卡，不卷页。 | `subpackageMember/signup/index.{js,wxml,wxss,json}` | `r3-ticket-before/after`、`r3-ticket-drag-2/3/4-*`、`r3-ticket-frames-*`。rig 里只有鼠标，拖动是用 eval 直接调用页面自己的 `onDeckStart/onDeckMove/onDeckEnd` 拍的。单测 `r3-visual-items.test.js`：翻页姿态纯函数（往左、往右、其余卡隐藏、不改布局属性）、松手阈值、顶栏绑定，另改了 `order-ticket-face-contract` 的断言说明 | 真手指的跟手和卷页手感；rig 没有对象存储，票封面和模糊背景读不出来（before 和 after 都是黑底） |
| r3-2 收集册入口 | 已修 | 参考 `refs4/album-ring-stack-frames.jpg`，接在 r2 的过场后面：<br>· 先播 r2 的「邮票落图钉、路线连起来」，约 1.5 秒后（或点一下）邮票收起，换成环装卡堆。<br>· 卡堆是三张彩色卡（勋章 / 邮票 / 藏品）穿在左上角同一个金属环上，正面一张，后两张往右下错开。<br>· 每页上方一句宋体大标题（段名）；底部左边是页点，右边是黑色圆形箭头钮。<br>· 点箭头，正面那张绕环翻过去，下一张成为正面，标题跟着换。<br>· 点卡进入那一段：就是当前这一段就收起过场；是别的段就就地换页（不压页面栈，也不重播）。<br>· 右上角有关闭。<br>· 从哪一段的小卡点进来，就从哪一张开始。<br>· 只在从「我的」三叠小卡进入时出现。打开「减少动态效果」时整段不出，直接是收集册。<br>· 段路由抽成 `album-head/routes.js`，段头和卡堆共用一份。 | `subpackageP3/components/album-intro/*`、`subpackageP3/components/album-head/{index.js,index.wxml,routes.js}` | `r3-album-before/after`、`r3-album-enter-after`、`r3-album-frames-*`。单测：从所在段开始、翻页中不叠第二次、点别的段 redirect 不带 from、点本段收起、只动 transform/opacity、关闭在右上角、减少动态效果不出；r2 的过场单测跟着改了 `<album-intro active>` 这一处 | 翻页中间帧：rig 点击本身就超过 520ms，拍不到卡翻到一半的样子（帧条里只拍到标题交叉淡化），需要真机看；真机帧率 |
| r3-3 分享赚积分 | 已修 | 参考 `refs4/share-earn-credits-frames.jpg`，用在邀请记录页：<br>· 顶部「首购奖励积分」右边加余额胶囊。余额取积分流水最新一行的 `afterPoints`。最新一行正好是邀请奖励时，从它的 `currentPoints` 数到 `afterPoints`（rig 里是 70 → 180）；否则静止显示。读不到就不显示，不写 0。<br>· 大数字「+110」进页时从 0 数上去。<br>· 新增「最新到账」卡，取最新一笔邀请首购奖励（流水 eventType 5）：被邀请人头像框里扫过一道线 → 「+110 积分」放大浮现 → 卡片上滑到位 → 头像角上弹出绿勾。没有到账时整张不出。<br>· 勾用成功绿，因为这一笔是服务端已经入账的事实；记录行里的奖励胶囊照旧保持中性（原合同不变）。<br>· 所有数字都来自 `/api/user/invite_list` 和 `/api/user/points/list`，没有任何前端补的数。 | `subpackageA/components/scene-member-invite-history/index.{js,wxml,wxss,json}` | `r3-invite-before/after`、`r3-invite-scan-after`、`r3-invite-frames-*`。帧条：92/+22 扫描中 → 166/+96 金额浮现 → 180/+110 → 卡到位打勾。单测：`earnFacts` 只取真实行（最新一条不是邀请奖励时静止、缺 afterPoints 不出、没到账不出卡、找不到被邀请人不写名字），数字滚动最后一定落在真实值、只往上数、减少动态效果直接落值；原 `visual-report-content-p1`、`myinvite-load-state` 等合同照旧通过 | 真机数字滚动的流畅度 |
| r3-4 加载与切段过渡 | 已修 | 参考 `refs4/load-tab-transition-frames.jpg`：<br>· 邀请记录：骨架之后，除了原来按月整卡浮上，卡里的每一行再一行行错开浮上来（只用 translate 和 filter:opacity，静止时过渡为 0）。<br>· 收集册切段（勋章 / 邮票 / 藏品）：新的一段从切换方向滑进来（往右的段从右边、往左的段从左边，48px、280ms，页面自己的 `animate`）。减少动态效果时不滑。<br>· 票夹：骨架之后当前那张票从下面升上来（原有入场，改成只升一张）。 | 同上三处 | 单测：切段方向、via 参数、减少动态效果不滑、行级错开存在 | 切段滑入：rig 的 `animate` 只认 opacity，不认 translateX，所以截图上看不到滑动，只能在真机或开发者工具里看 |

## 测试与门禁

- 新增单测 `tests/unit/r3-visual-items.test.js`，9 条，全部通过。
- 跟着改值的旧合同：
  - `order-ticket-face-contract`：只改断言说明（「顶栏居中」改成「导航下一行左对齐」），断言本身不变。
  - `r2-visual-items`：`<album-intro />` 改成 `<album-intro active="{{active}}" />`。
  - `action-ledger`：分母更新，tap 2930 → 2935，本地 tap 2564 → 2569，总数 5881 → 5886。
- 台账：`build-action-ledger.js --write` 从 5881 增到 5886。
  - 票夹顶栏「+」和头像，+2。
  - 收集册卡堆的关闭、下一页、点卡进段，+3。
- `node tests/lint/ui-integrity.js`：`UI-GATE-0:PASS`。没有新增基线项。票夹页把新增代码并进已有行，所以 U5 基线里 `signup/index.js:425` 那条的行号没有变。
- 全量小程序单测（`node --test tests/unit/*.test.js`）：16986 条全部通过，0 失败。第一次全量跑出 6 条失败（台账分母 3 处、组件选择器不允许 `:nth-child(n+5)` 1 条、收集册卡和邮票段缺按压反馈 2 条），都已修掉后重跑。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## Rig

- 和 r2-visual 同一套：`claude/cloud-walk-20261101-rig` 的 worktree，打 `harness-patches.diff`、staging 的 `rig-up-sha-param.diff` 和 `rig-app-oss-seam.diff`。对象存储填的是占位值，不是密钥。
- 后端用 base `8263e076` 打的 jar。
- 切换 before / after 用 `rig/switch.sh <树>`，拍照用 `rig/shots.sh <输出目录> before|after`。
- 测试库夹具（只在一次性库 cy_e2e_51 里）写在 `rig/fixture.sql.txt`：
  - 玩家 9004 的三张票：`free_orient_theme.py` 跑了三次，走的是真实零元报名链。
  - 4 个被邀请人：`seed-walk.py add-players 4`。
  - 积分规则三行（后台配置项，测试库原本是空的）。
  - 把夹具玩家 9008 设成 9004 邀请的人，再让 9008 走真实零元报名。邀请首购奖励 +110 是 `CmsRegistrationServiceImpl.completePaymentSuccess` 自己发的，积分流水一行都没有手写。
- HARNESS 限制：
  - 没有对象存储，所以票封面、票夹模糊背景读不出来。
  - 只有鼠标没有真触摸，拖动用 eval 喂页面自己的触摸处理函数。
  - rig 的 `animate` 不认 translateX。
  - 9004 没有头像，所以票夹顶栏的头像在截图里没有出现（没有头像不显示，这是设计行为）。

## 待你决定

1. **票夹顶栏的筛选（参考图里的「即将开始 ▾」）。** 默认没做。你 10-28 定过票夹只放能用的票、不要「可用 / 已用 / 已过期」切换，9-09 也删掉了「路线 / 场次」切换，所以剩下能筛的只有排序。要加的话告诉我按什么分（比如按开场时间先后）。
2. **票夹顶栏「+」去哪里。** 默认去首页探索主题（拿新票的地方）。头像默认回「我的」。
3. **收集册卡的三种颜色。** 默认照参考图用荧光绿（勋章）、天蓝（邮票）、洋红（藏品）。想换成城瘾自己的色板，改三行就行。
4. **收集册卡堆的大标题。** 默认只写段名（勋章 / 邮票 / 藏品）。参考图是一句两行的口号，但「不写多余文案」的规则不允许，所以没写。要不要在段名下面放真实数量（比如「已收 3 枚」）？放的话要多读一次接口。
5. **到账卡的绿勾。** 默认用成功绿，因为它只在服务端已经入账的那一笔上出现。记录行里的奖励胶囊照旧是中性灰。如果你希望整页都不用绿，告诉我。
6. **余额胶囊。** 默认只在邀请记录页显示，数字取积分流水最新一行。如果你想让它和「我的」页的积分是同一个来源，需要后端给一个余额字段。

STATUS: COMPLETE
