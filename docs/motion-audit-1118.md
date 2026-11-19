# 页面打开与动效审计（motion-1118）

日期：2026-11-18　分支：hengyin `fix/motion-1118` / chengyin-ios `fix/motion-1118`

## 约束与方法

- 不改视觉：不重画、不换样式、不加装饰动效；只调整已有动效，或补主流 App 都有、我们缺的动效（占位换成内容时交叉淡入、按压反馈、双击缩放过渡）。
- 参照：微信（tab 切换无过场、页面右滑推入）、iOS 系统 App（Mail/设置/App Store：占位换成内容时交叉淡入，行按下有高亮，结果页总有「完成」按钮）、Photos（缩放带过渡）。
- 小程序：脚本逐页静态扫描 app.json 全部 173 页（主包 5 + 分包 168），读每页的 wxml/wxss/js/json。扫描项有：page-motion 入场、cy-fly-in/rise-in、骨架屏、弹层、tabs、@keyframes、字面时长、toast、按压。另用一个识别嵌套的解析器找出 `<cy-skeleton wx:if>` 后面同级的 elif/else 分支。
- 已有的保障，本轮没有重复造：按压反馈覆盖率棘轮（全仓缺口 0）、全局 `prefers-reduced-motion`（app.wxss 把时长压到 0.01ms）、App 内的减少动效开关（utils/motion-preference）、弹层进出场时长对（sheet-in 300 / sheet-out 250，出比进短）、motion-property 棘轮（不准新增布局属性动画和 keyframes）。
- iOS：只读子代理扫了 1599 个 Swift 文件（报告附在第 3 节），我逐条复核后再动手。

## 1. 双端共用的动效 token

唯一对照表是 `docs/design/motion-tokens.json`，chengyin-ios 的 `docs/motion-tokens.json` 是它的逐字拷贝。

| token | 时长 | 用途 | 小程序 | iOS |
|---|---|---|---|---|
| press | 80ms | 按压回弹 | --cy-motion-press | QuestifyMotionToken.press |
| fast | 140ms | chip/tab/按钮状态切换 | --cy-motion-fast | .fast（QuestifyMotion.press / .fast） |
| crossfade | 150ms | chip 选中淡切 | --cy-motion-crossfade | .crossfade |
| fadeSwap | 200ms | 骨架屏换成内容 | --cy-motion-fade-swap | .fadeSwap（QuestifyMotion.content） |
| standard | 220ms | 局部状态 / 回弹 | --cy-motion-standard | .standard（QuestifyMotion.release） |
| sheetIn / sheetOut | 300 / 250ms | 半屏升起 / 落下 | --cy-motion-sheet-in/out | .sheetIn/.sheetOut（系统 sheet 自带） |
| slow | 350ms | 浮层 / 页面级 | --cy-motion-slow | .slow |
| celebrate | 600ms | 完成 / 徽章 | --cy-motion-celebrate | .celebrate |
| 缓动 | out .22,.61,.36,1 · standard .4,0,.2,1 · spring .2,1.4,.5,1 · pageRise .2,.82,.2,1 等 | | --cy-ease-* | QuestifyMotionToken.ease* |

漂移检查：小程序侧由 `tests/unit/motion-1118-contract.test.js` 逐值比对 tokens.wxss 和 json；iOS 侧由 `tools/check_native_motion_tokens.py` 和 `Tests/ContractChecks/test_native_motion_tokens.py` 比对 Swift 和 json。

iOS 现有三个动效的取值有改动：
- press：120ms → 140ms（fast），曲线改为 ease-out token；
- release：240ms → 220ms，弹性系数 bounce 不变；
- content：180ms → 200ms（fadeSwap）。

## 2. 小程序共享组件（改一处，所有使用它的页面跟着变）

| 组件 | 现在 | 问题 | 参照 | 修法 | 尺寸 / 状态 |
|---|---|---|---|---|---|
| cy-skeleton 换下后的分支（全仓 92 个 wxml，约 270 个分支） | 加载完成后，空态、错误态和内容直接挂载 | 骨架屏消失后新内容「啪」地出现，只有约 67 处之前挂了 cy-fade-in | App Store / Mail：占位换成内容时交叉淡入 | 补 cy-fade-in（复用 fade-in.wxss 里已有的 keyframes，不新增）；用到它的隔离组件补 @import；新增契约，防止以后回退 | S ✅ |
| cy-tabs icon 档 | 用字面值 .25s、.45s 加自定义过冲曲线、.3s 加默认 ease | 时长和曲线不在 token 梯子上；开了减少动效后，只有图标停，透明度和下划线还在动 | iOS 分段控件：220ms 标准曲线 | 改为 standard 加 ease-standard，图标用 slow 加 ease-spring；开了减少动效时三处一起停 | S ✅ |
| cy-loading-mask 转圈 | 900ms 一圈 | 和 cy-btn、cy-empty 同样形制的转圈（700ms）转速不一样 | 系统转圈全 App 同速 | 改读 --cy-motion-spinner-cycle | S ✅ |
| `<block wx:else>` 内容分支（85 处） | 内容直接出现 | block 不生成节点，挂不上 class | 同上 | v2：不换结构，给分支里每个直接子元素挂 cy-fade-in（嵌套 block 递归；原生组件、自带入场的子元素跳过），62 个 wxml 共 86 处、303 个子元素 | ✅ v2 |
| 玩法与原型页（play / roam / pass-reveal / look-transition / ai-insight / ai-npc / signup / topic 首页等） | 字面时长和曲线是照业主定稿原型逐值写的 | 不读 token | 统一时长梯子 | 收回 token 就等于改业主已批准的设计，要业主逐屏拍板 | L（留档） |
| page-motion 交错延迟（30ms 一级，最多 240ms） | 多个列表页各写一份 nth-child 延迟 | 字面值，但属于交错步长，不是时长 | — | 可以收成 `--cy-motion-stagger`，纯重构 | L（留档，收益低） |

## 3. 小程序逐页（app.json 顺序，玩家端与商家端都在内）

说明：
- 「已修」指本页或它直接引用的组件有骨架屏分支补了淡入。
- 按压反馈由全仓棘轮保证，没有缺口，所以不逐页重复列。
- 页面之间的转场都是微信系统转场（右滑推入，返回时左滑），小程序没法自定义，也不该改。

| 页面 | 端 | 打开方式 | 现在什么在动 | 问题 | 参照 | 修法 | 尺寸 |
|---|---|---|---|---|---|---|---|
| `pages/index/index` | 玩家 | tab 页:底栏切换即显(无过场,同微信) | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 4 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 2 组;cy-toast 淡入/快收 | 页面 wxss 字面时长/曲线 5 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/template/index` | 玩家 | tab 页:底栏切换即显(无过场,同微信) | 注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/talent/list/index` | 玩家 | tab 页:底栏切换即显(无过场,同微信) | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 3 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 2 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/member/index/index` | 玩家 | tab 页:底栏切换即显(无过场,同微信) | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/roam/index` | 玩家 | tab 页:底栏切换即显(无过场,同微信) | 注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 11 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 15 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 24 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/play/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 8 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 9 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 24 组;cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 71 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/play/merchant/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 未见问题 | — | — | — |
| `pages/play/circle/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/play/poi-challenge/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/activity/detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `pages/activity/baoming/baoming` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 6 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/activity/list/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 1 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/activity/official-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/activity/official-inbox/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/activity/official-mine/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | <block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | 同上 | 待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | L |
| `pages/activity/official-organizer/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageA/pages/myproject/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 1 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageA/pages/infomation/infomation` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageA/pages/infomationdetail/infomationdetail` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageA/pages/assetcenter/income-detail/income-detail` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动 | 未见问题 | — | — | — |
| `subpackageA/pages/assetcenter/earnings/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 9 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageA/pages/assetcenter/analysis/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageA/pages/video-bounty/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageP3/pages/growthcenter/index/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 2 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageP3/pages/growthcenter/leaderboard/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;@keyframes 2 组 | 页面 wxss 字面时长/曲线 4 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageP3/pages/stamp-camera/index/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageP3/pages/stamp-album/index/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 3 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageP3/pages/badge-wall/index/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 6 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageP3/pages/badge-3d/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageP3/pages/object-cards/index/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 2 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageB/pages/im/list/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageB/pages/im/chat/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 7 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组;cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 2 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/publish/fabu/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 18 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 10 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 页面 wxss 字面时长/曲线 11 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/publish/simple/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/publish/temp/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 11 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 4 组;cy-toast 淡入/快收 | 未见问题 | — | — | — |
| `pages/publish/activity/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 7 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/publish/topicadd/topicadd` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 壳页:内容在场景组件内,随组件渲染 | 未见问题 | — | — | — |
| `pages/publish/template-intro/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 2 组 | 页面 wxss 字面时长/曲线 2 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/club/apply/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/club/badges/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/create/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/club/customer-detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/customers/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/club/detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 18 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 5 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 3 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/club/edit/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/club/enroll/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/checkin-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/event-ops/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 4 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/governance/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/group-code/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/club/join-requests/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/notify/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/payout-receiver/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/club/roles/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/settlement/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/topic-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 16 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 3 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/club/topic-story/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/club/workbench/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/topic/index/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 8 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 4 组;cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 25 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/topic/merchantinfo/merchantinfo` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 7 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组;cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 5 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/topic/merchantapply/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 6 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 未见问题 | — | — | — |
| `pages/topic/pricing/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/topic/pricing/partner/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | <block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | 同上 | 待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | L |
| `pages/topic/catalog/index` | 玩家 | wx.navigateTo 系统右滑推入 | 壳页:内容在场景组件内,随组件渲染 | 未见问题 | — | — | — |
| `pages/merchant/index/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 13 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 5 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/merchant/apply/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;注册弹层组件 6 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/ledger/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/merchant/ledger/order-detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/ledger/batch-detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/aftercare/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/predict/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/aftercare/detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/profile/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | <block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | 同上 | 待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | L |
| `pages/merchant/marketing/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 5 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/merchant/reviews/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/marketing/ai-insight/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 页面 wxss 字面时长/曲线 14 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/merchant/relation/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/merchant/citynode/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/merchant/citynode/create/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/merchant/decor/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 8 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 2 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/decor/ai-npc/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 9 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 13 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/merchant/decor/coop-setting/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/decor/gallery/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/decor/perks/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/coop-center/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/customer/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 6 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 1 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/merchant/game-node/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 6 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/merchant/customer/detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/verify-records/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动 | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/merchant/bundle-reservations/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/payout-account/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/merchant/crm-campaigns/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/merchant/team/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/coop/list/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 2 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/coop/invite-detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/coop/invite/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 4 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/coop/nearby/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `pages/coop/finance/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/coop/settlement-detail/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `pages/coop/withdraw/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/coop/withdraw/records/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `pages/square/detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 2 组 | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/search2/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/search2/result/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/searchmap/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 8 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 未见问题 | — | — | — |
| `pages/templatedetail/templatedetail` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/templatedetail/topic-preview/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 3 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/templatedetail/topic-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/address/address` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/addressinfo/addressinfo` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/shezhi/shezhi` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 11 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/shezhi/about/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/shezhi/marketing/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `pages/gerenziliao/gerenziliao` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 8 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);cy-toast 淡入/快收 | 页面 wxss 字面时长/曲线 1 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `pages/userinfo/userinfo` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `pages/mylike/mylike` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/crop/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/free-explore/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/character/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/orient-score/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/invoice/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageMember/invoice-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/invoice-apply/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/invoice-titles/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageMember/invoice-title-edit/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/complaint/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | <block> 内容分支 3 处无淡入(block 不出节点,挂不上 class) | 同上 | 待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | L |
| `subpackageMember/coupon-wallet/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageMember/coupon-qr/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);@keyframes 1 组 | 页面 wxss 字面时长/曲线 1 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageMember/coupon/coupon` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageMember/couponInfo/couponInfo` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/mycanyu/mycanyu` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageMember/mycanyuinfo/mycanyuinfo` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageMember/myinvite/myinvite` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageMember/mytemplate/mytemplate` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageMember/order/order` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageMember/orderinfo/orderinfo` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageMember/points-history/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageMember/report-mine/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageMember/report-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageMember/appeal-mine/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageMember/signup/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 页面 wxss 字面时长/曲线 8 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageMember/tixian/tixian` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageMember/tixianjilu/tixianjilu` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 骨架屏→空态/错误态/内容 硬切(啪地换上) | iOS 系统 App / App Store:占位→内容交叉淡入 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms) | S |
| `subpackageMember/market/passes/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageMember/market/pass/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageMember/market/gift/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 2 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageMember/market/membership/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageMember/market/pass-order/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageMember/market/library/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 2 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageRoam/citynode-code/index` | 商家/主理人 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageRoam/history/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageRoam/poi-detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageRoam/session/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageRoam/nearby/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);原型自带半屏/遮罩 1 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 12 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageRoam/citystamp/index` | 玩家 | wx.navigateTo 系统右滑推入 | 按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 页面 wxss 字面时长/曲线 2 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageRoam/my-teams/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageRoam/official-routes/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackageRoam/official-route/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageTools/monthly-recap/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageTools/exploration-goals/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageTools/anniversary/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-fly-in/rise-in 卡片入场;cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageTools/exploration-export/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/privacy/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `pages/agreement/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `pages/deregister/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 4 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard) | 未见问题 | — | — | — |
| `subpackageJourney/pages/report/index` | 玩家 | wx.navigateTo 系统右滑推入 | cy-skeleton 首载;注册弹层组件 3 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class) | iOS 系统 App / App Store:占位→内容交叉淡入;同上 | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档) | S |
| `subpackageJourney/pages/landing/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-fly-in/rise-in 卡片入场;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮);@keyframes 1 组 | 页面 wxss 字面时长/曲线 8 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |
| `subpackageJourney/pages/seats/index` | 玩家 | wx.navigateTo 系统右滑推入 | 注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 未见问题 | — | — | — |
| `subpackageJourney/pages/promo/index` | 玩家 | wx.navigateTo 系统右滑推入 | 壳页:内容在场景组件内,随组件渲染 | 未见问题 | — | — | — |
| `subpackageJourney/pages/settle/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);原型自带半屏/遮罩 1 处;cy-tabs 指示块滑动;按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);页面 wxss 字面时长/曲线 4 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/team/detail/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 5 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 2 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `pages/team/join/index` | 玩家 | wx.navigateTo 系统右滑推入 | page-motion 列表/区块上浮(仅 countIn);cy-skeleton 首载;注册弹层组件 2 个(cy-sheet/scene-sheet 升起 300/落下 250ms,ease-standard);按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 骨架屏→空态/错误态/内容 硬切(啪地换上);<block> 内容分支 1 处无淡入(block 不出节点,挂不上 class);页面 wxss 字面时长/曲线 1 处,未读 token | iOS 系统 App / App Store:占位→内容交叉淡入;同上;统一时长梯子(iOS HIG / M3 duration token) | ✅ 已修:分支挂 cy-fade-in(--cy-motion-fade-swap 200ms);待做:把 block 换成带 cy-fade-in 的 view 或子节点逐个挂;需逐页核版式(L,留文档);待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | S+L |
| `subpackagePass/reveal/index` | 玩家 | wx.navigateTo 系统右滑推入 | 按压态覆盖(全仓缺口 0,press-feedback-coverage 棘轮) | 页面 wxss 字面时长/曲线 4 处,未读 token | 统一时长梯子(iOS HIG / M3 duration token) | 待做:逐条核对原型逐值动效后收回 token(多为业主定稿原型值,改即改设计) | L |

## 4. iOS（SwiftUI）逐页

基线：
- 全 App 只有 2 处 `withAnimation`、3 处 `.animation(_:value:)`；`matchedGeometryEffect`、`repeatForever` 和 `.redacted` 都是 0 处。
- `.animation` 都带了 `value:`；没有 `.disabled` 跟动画绑在一起。
- 页面转场全部用系统 push / sheet，和 iOS 原生一致，保持不动。

| 页面（文件） | 打开方式 | 现在什么在动 | 问题 | 参照 | 修法 | 尺寸 / 状态 |
|---|---|---|---|---|---|---|
| SessionRootView（QuestifyApp.swift） | App 根，TabView 5 个 tab | tab 切换即时显示 | 无 | iOS tab 栏切换没有过场 | 保持 | — |
| WelcomeView | 未登录时的根页面；设置和登录以 sheet 打开 | 系统按钮按压 | 无 | 设置 | — | — |
| HomeFeedView | Tab 0；详情页 push；5 个入口以 sheet 打开 | 卡片按压（QuestifyCardButtonStyle），封面淡入 | 顶部的 ProgressView 行加载完消失，列表整体上跳 | App Store Today：灰占位卡淡切成内容 | 用 `.redacted` 占位行加 content 淡入 | M（留档：要定占位样式，等同新组件） |
| ActivityBrowserView | Tab 1，push | 卡片按压 | 转圈直接换成列表、空态或错误态，内容硬跳出来 | Mail / App Store 淡切 | 在 ZStack 上加 `.animation(QuestifyMotion.content, value: 是否在转圈)`，开减少动效时不播 | S ✅ |
| RoamBrowserView | Tab 3；详情以 sheet 打开；城市、附近、体验 push | 无 | 行用了 `.buttonStyle(.plain)`，按下没有反馈；详情 sheet 没有设 detents，一打开就是全屏 | 地图地点卡：中/大两档 detents，行按下高亮 | 行改用 QuestifyCardButtonStyle ✅；detents 要定档位，属于设计决策 | S ✅ / M（留档） |
| ClubHomeView | Tab 2 | 俱乐部卡片按压 | 定位转圈换成内容时硬切 | 设置 | 同 ActivityBrowser 的做法：List 上挂 `.animation(QuestifyMotion.content, value: 定位 phase)`（Phase 已是 Equatable），开减少动效时不播 | S ✅ |
| AccountView | Tab 4，Form | 系统行高亮 | 无 | 设置 | — | — |
| ActivityDetailView / TopicDetailView / SquareDetailView | push / sheet | 无 | 转圈换成内容时硬切；Square 有 3 个转圈各自弹出 | App Store 详情页：骨架淡入 | 统一的「加载容器」：redacted 占位加淡切 | M（留档） |
| SearchMapExplorerView | push；区域和筛选以 sheet 打开 | 卡片按压 | 结果转圈硬切 | 地图 | 同加载容器 | M（留档） |
| TeamHomeView | push | 无 | 行用 `.plain`，按下没有反馈 | 设置行按下高亮 | 改用 QuestifyCardButtonStyle | S ✅ |
| PlaySessionView | push | 无 | 节点行用 `.plain` | — | 同上 | S ✅ |
| PlayerJourneyViews（2 处） | push；详情以 sheet [.large] 打开 | 无 | 行用 `.plain` | — | 同上 | S ✅ |
| CityPlayerView | push | 无 | 点位行用 `.plain` | — | 同上 | S ✅ |
| MerchantOrdersView | push；详情 sheet [.medium,.large] | 无 | 行用 `.plain` | Mail 行按下高亮 | 同上 | S ✅ |
| MerchantProjects / Content | push | 无 | 卡片用 `.plain` | App Store 卡片按下会缩一下 | 同上 | S ✅ |
| MerchantOperations 入口 / TemplateBrowser 模板行 | push | 无 | 卡片用 `.plain` | 同上 | 改后 CI 中 MerchantTemplate* UI 流程用例失败，已恢复 `.plain`，留档待真机排查 | M（留档） |
| DiscoveryHome / SocialGuideTemplate / ClubActivityList / ActivityPeople / FreeExploration（2 处） | push / 内嵌 | 无 | 用 `.plain` | — | 同上 | S ✅ |
| CoopRelationDiscoveryView | push | 无 | 用 `.plain` | — | 契约 test_coop_profile_entry_lifetime 明确要求 plain 加矩形点击区，保持现状 | —（按契约保留） |
| ObjectBadgeViews（徽章墙） | push | 无 | 用 `.plain` | — | 契约 test_object_badge_wall_layout 锁定了这个版式，保持现状 | —（按契约保留） |
| ClubPostDiscussion（社区帖） | 内嵌在动态流里 | 无 | 帖子正文用 `.onTapGesture`，按下没有反馈；展开、收起没有动画 | Mail 会话展开有动画 | 改成 Button 加卡片样式，展开包 withAnimation | M（留档：要改手势语义，需真机核对滚动冲突） |
| ClubOperationsWorkspaceView | sheet | Form 上有 `.easeInOut(0.18)` | 用了字面曲线；只认系统的减少动效设置，没认 App 内开关 | — | 改为 QuestifyMotion.content 加 @QuestifyReduceMotion | 已回退：改后 CI 中 ClubOperationsFlowTests 两例失败，恢复原写法，留档待真机排查（M） |
| GrowthCenter（成长中心） | 从账户页 push | 开了减少动效时去掉动画 | 只认系统设置 | — | 改用 @QuestifyReduceMotion | S ✅ |
| PlayObjectCardRevealView | 内嵌在玩法里 | 帧切换 `.easeOut(0.12)` | 字面时长；只认系统设置 | — | 改为 QuestifyMotion.fast（140ms）加 @QuestifyReduceMotion | S ✅ |
| PlayKitSensorChallengeView（指南针） | 玩法组件 | 指针 `.linear(0.1)` | 只认系统设置 | 指南针 App 的指针是线性跟随 | 改用 @QuestifyReduceMotion；保留 0.1s 线性（跟随传感器，不属于交互时长梯子） | S ✅ |
| PlayKitSpatialRevealView（AR） | sheet [.large] | SceneKit 落下、生长 | 只认系统设置；SCNAction 用字面时长 | — | 改用 @QuestifyReduceMotion ✅；SCNAction 时长是 3D 编排，留档 | S ✅ / L |
| NativeMediaGallery | fullScreenCover | 封面上滑 | 不是从缩略图放大出来的 | Photos 从缩略图放大 | iOS 18 `.navigationTransition(.zoom)`，但目标版本是 17，要加 #available | L（留档） |
| ClubPostImageGallery | sheet [.large] | 无 | 双击缩放直接跳到目标大小 | Photos 双击缩放有过渡 | 用 withAnimation(QuestifyMotion.release) 包住，开减少动效时不播 | S ✅ |
| RoamStampViews（集章草稿） | push | 预览展开带淡入 | 收起时直接消失，展开和收起不对称 | — | 收起也走 QuestifyMotion.content | S ✅ |
| PaymentProviderReturnSheet | sheet，禁止下拉关闭 | 无 | 支付成功后强制等 2 秒自动关闭，期间没有「完成」，也不能下拉关闭；成功时没有对勾动画，也没有触感 | Apple Pay：对勾加触感，然后关闭 | 加「完成」按钮、淡切、`.sensoryFeedback(.success)` | M（留档：支付链路，按规矩不碰支付相关代码） |
| PublishingContextHandoffViews（发布结果） | sheet，禁止下拉关闭 | 无 | 能跳转时会隐藏「完成」并强制等 2 秒 | 系统结果页总有「完成」 | 始终显示「完成」，保留自动关闭 | S ✅ |
| 模板「已保存 / 已发布」面板 | sheet [.medium] | 无 | 有按钮；成功时没有触感 | — | 面板出现时一次 `.sensoryFeedback(.success)`（保存、发布两个面板） | S ✅ |
| Medium-detent sheet（提现客服、俱乐部资料、收入、订单、举报、步行导航） | sheet | 系统 | 无 | 地图 | — | — |
| RoamPosterScan / RoamStampCamera | fullScreenCover | 系统 | 无（相机全屏） | 相机 | — | — |

iOS 字面动画清单：
- QuestifyMotion.swift 的 press、release、content 已收回 token ✅；
- ClubOperationsViews:54 ✅；
- PlayObjectCardRevealView:80 ✅；
- PlayKitSensorChallengeView:143 保留线性跟随；
- PlayKitSpatialRevealView 的 4 个 SCNAction 留 L。

## 5. L 项汇总（只留档，本轮不做）

1. ~~小程序 85 处 `<block>` 内容分支补淡入~~：v2 已落地（见第 8 节）。
2. 小程序玩法和原型页里的字面时长（play / roam / pass-reveal / look-transition / ai-insight / ai-npc / signup / topic 首页 / landing 等）：这些是业主定稿的原型值，收回 token 等于改设计。
3. 小程序 page-motion 交错延迟收成 stagger token：纯重构，收益低。
4. iOS 统一加载容器（`.redacted` 占位加淡切），覆盖首页、详情、搜索等页面里 352 个只有转圈的加载态：要新组件，也要定占位样式。
5. iOS 漫游详情 sheet 的 detents、社区帖改成 Button、支付返回页补「完成」和触感、媒体库从缩略图放大、AR 的 SCNAction 时长。

## 6. 验证

- 小程序：`npm run test:unit` 共 17919 个用例全部通过（含新增的 motion-1118 契约）；`npm run lint`（ui-integrity 加 scope）通过。
- 有 13 份旧契约用整串正则锁定属性顺序。处理方式：把 class 统一追加到标签末尾；有 7 份旧契约仍需放宽，只把正则改成允许多出一个可选的 ` cy-fade-in`，断言的语义没变。
- iOS：
  - `python3 tools/current_ci.py refresh/check --base $(git merge-base HEAD origin/main)` 结果为 PASS_CURRENT_SOURCE_LEDGER；
  - `Tests/ContractChecks` 全量 4322 个用例，失败数比 main 少 1，没有新增失败；
  - `tools/check_native_motion_tokens.py` 结果 OK。
  - `tools/check_swift_syntax.py` 跑不起来：依赖装不上，pip 校验哈希不一致。
  - Linux 上没法编译 Swift，也跑不了 UI 测试，所以模拟器和真机都还没验证。

## 7. 结论

- 小程序 S 项已全部落地：骨架屏分支淡入（92 个 wxml）、cy-tabs icon 档改用 token、loading-mask 转圈同速、双端 token 对照表加契约。
- iOS 已落地的 S 项：token 镜像、4 处改用 App 自己的减少动效开关、15 处补按压反馈（商家运营入口、模板行、俱乐部运营工作台三处因 CI UI 用例失败已回退，见第 4 节）、活动列表淡切、双击缩放过渡、集章预览收起动画、发布结果「完成」常显。
- 剩余 2 个 S 项已补：ClubHome 定位态淡切、模板保存/发布结果面板成功触感（iOS #152）。
- M / L 项都留在本文档（第 2、4、5 节）。

STATUS: S 项全部落地；iOS 改动已在 CI 编译并跑单测/UI 用例（结果见 PR），小程序未在开发者工具截图自验，需负责人在开发者工具和真机上按 PR STATUS 评论逐页核对

## 8. v2（2026-11-19）：页面打开时图片与数字不再「啪」地出现

负责人在微信开发者工具里录了 master 2dc79dcf 的开页帧（测试后端，图片慢）。以下是录到的问题和处理：

| 页面 | 录到的现象 | 处理 |
|---|---|---|
| `pages/topic/index`（路线详情） | 约 2.5 秒只有黑底加标题，之后封面照片突然出现 | 封面屏有图时先铺主题占位色，Ken Burns 图层（`.kb`）在第一张图 bindload 后淡入；单图封面同样处理。第二屏头图的 swiper 先铺占位色，照片在上面淡入。头图区本来就是定高（90vh，swiper 绝对定位），照片到了版式不动 |
| `subpackageMember/signup`（票夹） | 卡片滑上来时是空的深色块，之后照片突然出现 | 封面框 `.wc__im` 挂占位色，随卡一起滑入；照片 bindload 后淡入 |
| `subpackageA/pages/myproject` | 缩略图先空白再突然出现 | 缩略图改为「定尺寸外框（占位色）+ 框里的图淡入」，外框沿用原尺寸和圆角 |
| `subpackageMember/order/order`（卡片组件 scene-member-order-history） | 同上 | 外框挂占位色，图淡入；宽图换框与淡入放在同一次 setData 里，图在最终的框里淡入 |
| `subpackageP3/pages/growthcenter` | 积分先显示旧值 31，再跳到 50 | 新回包改写已经显示的积分时，从屏上的值数到新值（`page-motion.countFrom`）；起点和新值放在同一次 setData 里，不会先闪一帧。头像和徽章图标也用了占位色加淡入 |

共用写法：
- `style/img-fade.wxss`：`.cy-img-ph` 是占位色（`--cy-color-skeleton-base`，跟着主题变），`.cy-img-fade` / `.cy-img-fade--on` 只过渡 opacity，用 transition 不用 @keyframes，时长读 `--cy-motion-fade-swap`。系统减少动效设置和 `.cy-motion-reduced` 下都直接落地；用 App 内减少动效开关的页面（topic / signup）各补了一行。经 `components.wxss` 全局引入，隔离组件需要自己 @import。
- `utils/img-fade.js`：`onImgFadeLoad` 只在 bindload 时标记 `imgOn[key]`；binderror 不标记，占位色留着。
- L2 不动：业主定稿的字面时长（Ken Burns 1.2s/9s、票夹卡 .45s、模糊背景 .8s 等）原样保留，淡入加在外层或单独的图层上。

契约：`tests/unit/motion-1118-contract.test.js` 新增 ④（block 分支子元素淡入及负控）、⑤（img-fade 的样式、处理函数、列出页面的远程图和占位外框）、⑥（countFrom 和成长中心的接线）。
