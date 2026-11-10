# l-after 结果：主题之外的页面 —— 结算四步、统一邀请页、票夹、收集册卡堆、游戏主题库

- 分支 `claude/l-after-20261110`，从 `origin/launch/questo-20261110`（qx-base 写出 `STATUS: COMPLETE` 的 `ca1f85b6`）切出。草稿 PR 合入 `launch/questo-20261110`；没有合并、没有标为就绪；没有推 master / integrate / staging / 其他 lane 分支；没有碰生产、VPS、私有仓；没有新增或修改迁移；没有跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交密钥。
- 只改小程序。没有改 Java、SQL、支付、退款、结算、权限。规格来源：`LAUNCH.md` l-after 段 + `proto/game` 的 st-overview / st-rank / st-rate / st-share / sh-invite / wl-wallet / al-album / ps-library（业主批准）+ `MOTION.md`。
- 截图在 `shots/`：390 宽、JPEG q70，都是走查 rig 的真实页面 + 真实后端（不是真机）。`-before` 用底座 `125de3bd` 的包（qx-base 之后只多一个 docs 提交），`-after` 用本分支的包，同一后端 jar（本 lane 不改后端）、同一身份（玩家 9004）、同一套步骤（`rig/shots.sh`）。每张图我都看过。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| A1 结算·总览（proto st-overview） | fixed | 回到上一版通过的纯黑整屏居中版式；金色对勾换成 `cy-illus` 奖杯（300rpx）+ 两点闪光 + 金色光晕；「已通关」/ 主题名 /「本关第 N 名」/ 两格（分数 · 节点）依次升起（M1，`stagger-hero` 90ms），分数 1.1s 后计数上来（`countIn` + `count.wxs`）；没计分不出分数格，用时不再出现在总览；不再撒彩旗（整局只庆祝一次）。减少动态效果直接终态。 | `subpackageJourney/pages/settle/index.{wxml,wxss,js,json}`、`subpackageJourney/components/cy-illus/*`（play 规范实现的逐字副本） | rig 真实通关主题 990014（两站到达 + 答题「3」）后打开结算：`settle-overview-before/after`（after 为 4.5s 终态）、`settle-overview-f1-after`（1.2s 中间帧）。单测 `l-after-items`（结构、无 keyframes、减少动态效果）、`r2-finish-settlement`（按日期改注） | 真机上插图进场 / 待机的帧率 |
| A2 结算·排行榜（proto st-rank，业主「全屏弹窗 不超过微信导航栏」） | fixed | 榜单成为导航栏（含步骤条）以下的近全屏弹层，从下方升起（`--cy-motion-sheet-full-in`）；`rank-board` 新增 `preset="settle"`：只给「全体 / 队内」（全体在前，服务端有队伍才出队内），不探队伍榜、没有「更多」；月桂按原型重画成细茎 12 对小叶（`images/laurel.svg`，右枝 `scaleX(-1)`），两枝 `.6 → 1` 弹入（`--cy-ease-spring`，450ms），168rpx 等宽名次 120ms 后按位滚动 520ms（`--cy-ease-roll`），落定轻震一下，「前 N%」滚完后升起；累计 / 本周与「完成节点数」列表卡沿用。战报页仍用原来的全口径（preset 为空）。 | `rank-board/index.{js,wxml,wxss}`、`images/laurel.svg`、`settle/index.*` | `settle-rank-before/after`、`settle-rank-f1-after`（弹层升起中间帧）。单测 `l-after-items`（preset 口径、延时常量、月桂 24 片叶、无 keyframes）、`r2-finish-settlement`（boardTabs 原口径不变） | rig 只有单人局：「队内」页签没截到；「前 N%」需要服务端上榜总数 ≥ 5，rig 只有 1 人上榜，没出 |
| A3 结算·评价（proto st-rate） | fixed | 五颗金星依次点亮（每颗错开 `--cy-motion-micro`，点星轻震）；点星后出 6 个标签片（故事好 / 地点选得好 / 谜题有意思 / 路太远 / 提示不够 / 太难了，错开升起），点中的随文字一起提交（写在正文前）；文本框 + `0 / 500`；没星「先选星级」、没内容「写一句再提交」，红字都在字段下面；服务端拒绝原文照样写在字段下；提交成功出居中弹窗（星星插图 +「谢谢」）→「去分享」。 | `settle/index.*` | `settle-rate-before/after`、`settle-rate-stars-after`（点一颗星后：金星 + 标签片）、`settle-rate-error-before/after`。单测 `l-after-items` | 真实提交评价本轮没跑（rig 里玩家 9004 已在 r2-finish 轮提交过同主题评价，服务端按频控拒绝属正常，未截图） |
| A4 结算·分享（proto st-share；业主「底部只有一行：分享 + 完成」） | fixed | 帖文 / 故事分段；卡片回到上一版结构（封面淡入底 → 没有封面时深色渐变、`cy-illus` 勋章、已通关、主题名、节点 / 分 / 名次、城瘾）；底部一行「分享」（open-type=share）+「完成」；「保存图片 / 分享视频」撤掉；隐藏画布仍按 r3-share 版式画一张，只给微信分享卡片图（`onShareAppMessage` 的 `imageUrl`）。 | `settle/index.*`、`tests/unit/r3-share-cards.test.js`（按日期改注） | `settle-share-before/after`、`settle-share-story-after`。单测 `l-after-items`、`r3-share-cards` | 微信分享卡片图（rig 不能真分享）|
| B 统一邀请页（proto sh-invite，业主「Spotify-style invite for all invites」） | fixed | `cy-share-card` 新增 `kind=invite`：卡片完整可见（封面压暗铺满，没封面就是色块）、眉题「邀请 · 和我一起走这条路」、大斜体主题名、竖向路线点（最多三站）、一行「X 邀请你 · 还有 N 个席位」（席位数只在宿主给了数字时写）、信封插图（宿主传 data URI，可缺）、城瘾 + 码；宿主给了码（个人码）就不再取主题码；导出图同版式；色块、渠道一排、「发给微信好友」沿用。三个邀请入口都改走它：① 同行席位页「邀请」→ 邀请页（主题封面 / 名称、邀请人、剩余席位、信封），原邀请半屏撤掉，已邀请 / 撤回列表留在页面里；② 队伍详情「邀请队友」→ 邀请页（队名、队长、空席数），发给微信好友仍按邀请码落加入页；③ 我的 → 邀请好友 → 邀请页，码是本人个人码（`/api/user/player-code`）。主包只加这一小段（约 +2.5 KB 源码）。 | `components/cy/share-card/index.{js,wxml,wxss}`、`subpackageJourney/pages/seats/index.*`、`subpackageJourney/utils/lobby-view.js`（`openCount`）、`pages/team/detail/index.{js,wxml,json}`、`subpackageMember/components/cy/profile/index.{js,wxml}` | `invite-me-after`（我的 → 邀请好友：rig 里调用页面自己的 `goInvite`，个人码接口真实返回）。单测 `l-after-items`（眉题 / 邀请人行 / 席位数不是数字不写 / 给码不取码 / 三个入口接线）、`r3-share-cards` | 同行席位页与队伍详情的邀请页没截图：rig 里没有多席位票和队伍夹具（见 待你决定 3）；真机分享卡片 |
| C 票夹（proto wl-wallet；业主「tap flips to QR, mirror page-curl swipe, fixed 开始游戏」） | fixed | 点卡翻面看码、底部固定「▶ 开始游戏」、卷页换票、模糊封面背景都是现有的（r3-visual），本次补三样：① 顶栏大标题变「票夹 ▾」，点开弹出 全部 / 即将开始 / 已通关（只按服务端 `topicCompleted` 分；票夹仍只放能用的票，不是「可用 / 已用 / 已过期」）；② 卷页时一道跟手的镜面高光带（`--sx` 从 110% 扫到 −20%，`--spec` 随进度），卷面微斜切 `skewY`；③ 卡下倒影（`-webkit-box-reflect`）。减少动态效果照旧直接换卡。 | `subpackageMember/signup/index.{wxml,wxss,js}`、`tests/unit/{r3-visual-items,order-ticket-face-contract}.test.js`（按日期改注） | `wallet-before/after`、`wallet-flip-before/after`、`wallet-drag-1..4-before/after`（拖动帧：after 可见高光带与倒影）、`wallet-filter-after`、`wallet-filter-done-after`。单测 `l-after-items`（筛选、姿态里的 `--spec/--sx/skewY`）、`r3-visual-items`、`order-ticket-face-contract` 等 8 个票夹合同 | 真手指的跟手；封面 / 模糊背景（rig 没有对象存储） |
| D 收集册环装卡堆（proto al-album） | fixed | 三张卡上的图换成空态同款插图集（勋章 → `badge`、邮票 → `stamp`、藏品 → `gem`，`cy-illus` 的 P3 副本），卡左下角写段名；翻页、环、点、箭头保留（已通过）。 | `subpackageP3/components/album-intro/index.{wxml,wxss,json}`、`subpackageP3/components/album-head/routes.js`、`subpackageP3/components/cy-illus/*` | `album-ring-before/after`、`album-ring-2-before/after`、`album-flip-before/after`、`album-flip-2-before/after`（从「我的」三叠小卡的「邮票」进入）。单测 `l-after-items`、`r3-visual-items` | 原型第四张「通关」卡没加：收集册只有三段路由，也没有「通关数」事实（见 待你决定 4） |
| E 游戏主题库（proto ps-library；业主「one large club-style card per row, gradient bottom, no count」） | fixed | 新页 `subpackageMember/market/library`：标题「游戏主题库」不写数量；筛选 全部 / 城市定向 / 自由探索（`product_type` 1 / 2）；一行一张俱乐部式卡（`photo-card` 结构 + `club-card` 的四段黑渐变）：封面铺满、顶行「城」标 +「¥0 可玩」柠檬绿片 + 城市、主题名、一行数据（类型 · N 站 · 约 X 分钟）、标签片 + 右箭头；首屏错开升起（M2）；点卡进主题详情；下拉刷新 / 触底加载更多。列表读现有公开目录 `POST /api/public-catalog/list`；「¥0 可玩」只在行上 `passCovered === true` 时出（通行证覆盖事实由 l-pass 补，数据口集中在 `utils/pass-library.js`）。通行证页加一行「游戏主题库」入口。 | `subpackageMember/market/library/*`（新）、`subpackageMember/utils/pass-library.js`（新）、`subpackageMember/market/passes/index.{wxml,js,wxss}`、`app.json`（分包加一页） | `library-after`、`library-free-after`（自由探索筛选）。单测 `l-after-items`（筛选参数、卡片映射、覆盖事实只认 true、页面注册） | 封面（rig 没有对象存储）；「¥0 可玩」价签：rig 服务端还没有 `passCovered` 字段，截图里没有价签（按规则不画） |
| F `cy-illus` 修正（底座组件，顺手修） | fixed | 底座移植的 25 张插图里 20 张的物件层画不出来：原型是 HTML 内联 `<svg>`，同一标签里「公共属性串 + 覆盖属性」出现重复属性（两个 `stroke-width`），HTML 容忍，但 data URI 的 `<image>` 走 XML 解析，重复属性是致命错误。在 `art.js` 出 data URI 前按原型意图保留最后一次出现的属性值（`dedupeAttrs`），25 张全部能画；play 规范实现与两个副本同步（底座单测锁定一致）。 | `pages/play/components/cy-illus/art.js` + 两个副本 | 25 张解码后全部通过 XML 解析；headless Chromium 实渲染 7 张（奖杯 / 闪光 / 勋章 / 星 / 邮戳 / 宝石 / 信封）`naturalWidth` 全为 120；`launch-look-tokens-illus` 6 例通过 | — |

## 全局规则落在本 lane 的地方

1. 主题外观只在主题内：结算 / 排行榜 / 评价 / 分享 / 邀请 / 票夹 / 主题库全部是玩家黑底白字（`theme-dark` token），没有挂 `look--*`，也没有玻璃 / 锈蚀。
2. 数字只来自服务端：名次、分数、节点、剩余席位、通行证覆盖都读接口；读不到整格 / 整行不出，不写 0。
3. 动效全部是过渡 / 已有插图组件：本 lane 新增 `@keyframes` 为 0（棘轮仍 146），减少动态效果（系统 + 应用内开关）时直接终态；`cy-illus` 在分包里是 play 规范实现的逐字副本（底座单测锁定）。
4. 关闭在右上、底部按钮固定在底部、圆角走按钮 token；没有帮助文案（唯一一句「评价会公开，只显示昵称。」是业主批准原型里的）。
5. 不新建插件 / 引擎：榜单沿用 `rank-board`，邀请沿用 `cy-share-card`，主题库沿用公开目录接口与 `photo-card` 结构。

## 门禁

| 门禁 | 结果 |
|---|---|
| `node chengyinhub-xcx/tests/lint/ui-integrity.js`（最终头） | **UI-GATE-0:PASS**（U1–U5、A11Y、Legacy 全 PASS；没有新增任何基线条目。中途报过 4 条基线行号漂移 + 1 条零消费字段，都改代码收掉：票夹筛选常量改成文件尾函数声明、「我的」新 require 并到同一行、同行席位 `pendingInvite` 改实例字段） |
| `build-route-matrix.js --json` / `build-action-ledger.js --write` | 路由 168（+1 游戏主题库）、控件 5946（底座 5936，+10：主题库筛选 / 卡 / 重试、票夹筛选、结算标签片 / 去分享 / 完成、队伍邀请钮改 bindtap、同行席位撤邀请半屏）；重建后 `git status` 无 diff |
| `scripts/ds-hardcode-gate.sh`（暂存本分支全部改动） | 通过，exit 0（画布导出图与卡片压字处行尾 `/* ds-ok */`） |
| keyframes 棘轮 | 本 lane 新增 `@keyframes` 0 个；棘轮上限 146 未改，实测仍 169（与底座相同，qx-base RESULT 已记） |
| 主包体积 `mainpkg-size-report.js --json` | `mainTotal` 3,293,808 字节 ≈ **2036.1 KiB < 2048**（底座 2030.7；+5.4 KiB 来自 `cy-share-card` 的邀请卡、队伍详情页与 `app.json`；其余新文件全在分包） |
| 单测 | 新增 `tests/unit/l-after-items.test.js` 7 例通过；本 lane 触及的 43 个单测文件共 480 例：470 通过、10 失败 → 其中 8 条是本 lane 新页 / 新控件带来的冻结计数与正则，已按日期改注后全部通过（台账 tap 2979→2985、事件合同 2957→2961、external 367→366、本地 tap 2612→2619、本地事件 2861→2865；cy-page-title 页面 91→92；路由矩阵 167→168；标题盘点 167→168 / custom 166→167；票夹 F14 正则；店铺分享卡 `wx:else`；r2-finish / r3-share / r3-visual / order-ticket-face 各 1 处）；剩下 2 条（`motion-property-ratchet` 169>146、`reduced-motion-coverage-contract` 的 r3-share / ai-npc / 队伍 / 落地页 keyframes）在底座同一组文件上同样失败（已在底座树实跑对照：127 通过 / 同 2 条失败），不是本 lane 引入 |
| `cy-illus` 插图 | 25 张物件层解码后全部通过 XML 解析（修前 20 张失败）；Chromium 实渲染 7 张 `naturalWidth` 均为 120 |
| 没有跑 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（brief 禁止）；Java / 管理端构建（本 lane 不改后端与管理端） |

rig：`claude/cloud-walk-20261101-rig` 工作树 + staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（对象存储占位值，不是凭据）+ club 分支的 `harness-patches.diff`；后端 jar 用底座 `125de3bd` 构建（本 lane 不改后端）；`rig/switch.sh` 切 before / after 包，`rig/shots.sh` 拍照。夹具：staging 的 `free_orient_theme.py` 跑三次（玩家 9004 三张零元票,主题 990014–990016），玩家 9004 在 rig 里真实走完 990014（两站到达、答题「3」,`/api/play/arrive` 与结算接口全是真实返回）。HARNESS 限制：没有对象存储（封面 / 模糊背景 / 主题库封面读不出）、只有鼠标（票夹拖动用 eval 喂页面自己的触摸处理）、个人码接口在 rig 里没返回码图。

## Cross-lane

1. **l-pass（通行证 / 主题库 API）**：主题库页的「¥0 可玩」只认列表行上的 `passCovered === true`，数据口在 `subpackageMember/utils/pass-library.js`（`coveredByPass` / `query`），换成通行证专用接口只改这一个文件；通行证页（`market/passes`）我只加了一行「游戏主题库」入口，页面其余未动。
2. **l-misc（队伍小程序码）**：队伍详情的邀请页已预留 `inviteCard.qr`，按邀请码生成码的接口接上后把图地址填进去即可（`openInvite`）。
3. **l-play（游玩页）**：「查看结算」入口（`pages/play/index.js openSettlement`）一行没改，仍 `navigateTo` 结算页；结算页不再撒彩旗，整局的唯一庆祝留给运行结束屏。
4. **qx-base / 所有用插图的 lane**：`cy-illus` 的 `art.js` 修了重复 SVG 属性（见 F 行），play 规范实现与 Journey / P3 两个副本已同步；其他 lane 若已复制了旧版 `art.js`，合并时以这一版为准（底座单测会在副本不一致时报红）。
5. 既有 10 条 staging 上就红的单测（qx-s2 §六）本 lane 没碰；本 lane 改的 4 处断言都写了日期与原因。

## 待你决定

1. **票夹筛选**：原型顶上是「票夹 ▾」筛选，但你 10-28 定过票夹不做「可用 / 已用 / 已过期」切换。**默认做法：按原型加筛选，但只分 全部 / 即将开始 / 已通关（按服务端 `topicCompleted`），票夹本身仍只放能用的票**。不想要就删 `toggleWalFilter / pickWalFilter` 那十几行。
2. **结算总览的「用时」**：原型两格只有 分数 / 节点，上一版还有用时。**默认做法：按原型只留两格**；用时仍在战报页。
3. **同行席位 / 队伍的邀请页没有截图**：rig 里没有多席位票（需要双人 / 团队票）和队伍夹具，两个入口的接线有单测、页面结构与「我的 → 邀请好友」同一张卡。**默认做法：不另造夹具**；真机或你的测试库里点一次即可。
4. **收集册第四张「通关」卡**：原型卡堆有四张（勋章 / 邮票 / 藏品 / 通关），现有收集册只有三段路由，也没有「通关数」事实。**默认做法：三张**；要第四张需要先定它点进去的页面和数字来源。
5. **评价的标签片**：既有评论写口要求有文字，**默认做法：点中的标签片拼成文字随正文一起提交**（只点标签不写字也能提交）；要存成结构化标签需要后端加字段。
6. **邀请页的个人码**：rig 里 `/api/user/player-code` 没有返回码图（截图里右下角空着），产品里码出现在右下白底格；**默认做法：读不到就不画码格，不占位**。

STATUS: PARTIAL - 代码、单测、门禁、rig 截图全部完成；只差同行席位 / 队伍详情两个邀请入口与榜单「队内」页签的截图（rig 没有多席位票和队伍夹具，接线有单测），以及真机上的插图帧率、分享卡片图与个人码
