# 端到端审查(第二轮)· 编号发现总清单

分支 `fix/audit-wiring-202609` @ `ebf42e7a9` · 归档 2026-09-19 · 边界:**本轮只审不改**。

**批复用法**:按号回「1做 / 2核对 / 3删」(可跳号、可写区间)。未点号的条目一律不动。修复批另开会话执行。

| 计数 | 条数 | 号段 |
|---|---|---|
| P0 | 1 | 1 |
| P1 | 11 | 2–12 |
| P2 | 19 | 13–30、32 |
| 待核(证据不足,不当结论用) | 1 | 31 |

证据目录:`docs/audit-e2e-202609/`(门禁原始输出在 `logs/`,走查截图在 `shots/`)。
每条末尾的「详见」指向本轮分域文档;**文档里没有磁盘证据的断言,不在这张表里**(被否决的 13 条见第七节)。

---

## 一、玩法与 playkit(7 条)

### #1 [P0] 三个挑战玩法永远开不了服务端秒表,提交必被判「还没开始」
- **现象**:「变色就点 / 弹球 / 安静挑战」玩家能玩到出成绩、点提交,成绩永不成立。
- **链路**:组件都 `triggerEvent('start')`(`playkit-reaction/index.js:87`、`playkit-ballshake/index.js:110`、`playkit-quiethold/index.js:135`),分发器 `pages/play/components/playkit/index.wxml:75-87` 三家**都没接 `bind:start`**(同表 countdown `:90`、stopwatch `:95` 有)→ `ACTION_OF` 的 `*:start → START_CHALLENGE`(`utils/playkit-view.js:455-461`)永不触发 → 后端 `serverElapsedMs` 抛 `IllegalStateException(label + "还没开始")`(`AdvancedGameRuntimeServiceImpl.java:1382-1384`,调用 `:1395/:1429/:1452`)。
- **复现**:`publish/fabu` 配一个「变色就点」→ 玩家进游戏 → 完成点击 → 提交 → 报「变色就点还没开始」。
- **为什么没被门禁抓到**:单测只核 `ACTION_OF` 表(表是齐的),视图层少一个 `bind:` 不在任何判据里 —— 本轮新尺子 `event-contract.js` 第 1 条就抓到它。
- 详见 `04-playkit-event-contract.md` F-PK-1。

### #8 [P1] 「沉默点单」的「放弃」按钮按下去什么都不发生
`silentorder` 前端有屏、ACTION_OF 无动作映射。详见 `04-play-mode2-and-kits.md` F-PB-1。

### #9 [P1] 探店日无坐标站点:按钮写「去这家」,点下去把这一站演成已通关
对称缺失:`checkin()` 有 mode2 分支(`pages/play/index.js:3676-3678`),`arrive()` 没有(`:3643-3660` 成功即 `onComplete`)。有前置条件(站点无坐标才发作)。详见 F-PB-2。

### #10 [P1] 跨日任务(slowTask)在任何创作入口都配不出来
玩家侧有实现、分发器有分支、后端有类型,配置侧缺门 —— 一条完整玩法线在创作端是隐形的。详见 F-PB-3。

### #23 [P2] 排行榜拉取失败被说成「还没有排名数据」,并抛未处理 rejection
详见 F-PB-4。

### #24 [P2] 音乐角「听完」那次提交是双死代码,注释与真源互相打脸
详见 F-PB-5。

### #25 [P2 · 潜在] 关高级面板不收 playKit 弹层,两层 sheet 可叠
详见 F-PB-6。

---

## 二、横切:钱与身份(2 条)

### #2 [P1] 俱乐部分享落到主题页时,那条建单永远不带票源归因
`club/detail/index.js:2634` 分享带 `sourceClubId`;主题页建单口 `pages/topic/index/index.js:875-882` 不合并 `attributionPayload`(全仓只有 `pages/activity/baoming/baoming.js:1199` 合并)→ 这一单归平台,**俱乐部票源奖励静默为 0**。详见 `01-cross-cutting.md` F-TS-1。

### #3 [P1] 三处「付了钱但结果不明」的入口点了没反应
`unknown` 是 checkout 终态且无复位接口(`utils/checkout/checkout-workflow.js`),三宿主只撤 loading 不给理由:`components/cy/scene-member-order-detail/index.js:521-524`、`components/cy/scene-member-order-history/index.js:351-354`、`pages/topic/index/index.js:1076-1079`。正确先例已在仓里:`baoming.js:1563-1575`、`coop/list/index.js:609-612`、`merchant/game-node/index.js:693-698`。详见 `01-unknown-state.md` F-UK-1。

---

## 三、玩家主干(2 条)

### #4 [P1] 库存为空的票种显示「余票待确认」,点下去说「已售罄」并硬拦
`components/cy/scene-play-activity-detail/index.js:53-56` 把 `remaining === null` 算成 `_available=false`,`:281-284` 据此拦截;字段确实可空(`sql/base_schema_20260818.sql:3700`)。详见 `03-player-trunk.md` F-PL-1。

### #13 [P2] 截止校验只在详情组件里
深链直入 baoming 要填完整张表才被后端拒(`scene-play-activity-detail/index.js:285-288`)。详见 F-PL-2。

---

## 四、内容与发布(8 条)

### #5 [P1] temp「据点玩法形态必选」这条校验拦不住发布
错误写了(`pages/publish/temp/index.js:1918-1920`)、红字也渲染(`index.wxml:137`),但 `validateForm` 放行清单 `:1982` 少 `interactionType`(同文件预览兜底 `:1990-1994` 里有)→ 不选也能发,商家以为配好了,玩家侧据点空洞。详见 `05-publish-chain.md` F-PB-P1。

### #6 [P1] simple 置灰主 CTA 没有 disabledtap,唯一的自解释文案永远出不来
`pages/publish/simple/index.wxml:83` 只 `bindtap`;`components/cy/btn/index.js:35-40` 禁用态吞 tap;全页 `disabledtap` 命中 0,那句「请逐个在地图中确认地点后再继续」(`simple/index.js:230`)等不到执行。详见 F-SM-1。

### #14 [P2] simple 整块旧「快速发布」链路是孤儿,且它的反馈函数本来就渲染不出来
`onPublish/onSaveDraft/onClubChange` 零绑定;`tip()` 写的 `data.toast` 在 wxml 从不渲染 → 若要重新接线必须先换通道。详见 F-SM-2。

### #15 [P2] simple 发布成功没有任何去详情的路(死变量)
`topicUrl` 算出后未使用(`simple/index.js:632-637`);落点本身符合 2026-09-06 裁决,只报赘码。详见 F-SM-3。

### #16 [P2] 主题发布成功埋点的 bizId 恒为 null
`pages/publish/fabu/index.js:5708` 取 `resData.id`,而后端返回标量(`ApiTopicController.java:464`)。⚠️ 别照抄「主页会空白」那条 —— `:5713` 才是对的。详见 F-AN-1 与本文第七节。

### #17 [P2] 活动草稿续写恒回第 0 步
`subpackageA/pages/myproject/index.js:290` → `pages/publish/activity/index.js:72`,全页仅用户点击改 step。详见 F-DR-1。

### #18 [P2] AI 安全预检只覆盖 fabu 一条链路(但后端不漏)
其余三条提交口无前端 advisory;**服务端每条都有 `checkText`**(`ApiTemplateController.java:329/380` 等),故从 P1 降 P2。详见 F-SEC-1。

### #19 [P2] `topicadd` 生产零引用,但仍被 7 处测试/脚本持有
上轮遗留的删除期(2026-10)裁决。详见 F-TA-1。

---

## 五、组件契约与俱乐部弹层(3 条)

### #7 [P1] 角色分配弹层的「选择成员」是假的 → 会把角色写给错的人
组件如实抛出成员点击(`pages/club/components/club-director-role-sheet/index.js:26` `triggerEvent('selectmember', …)`),而且那一列**是可点的**(`index.wxml:4` 的 `selectable bind:rowtap="onMemberTap"`);宿主 `pages/club/topic-detail/index.wxml:262-268` 接了另外四个事件,独独没接这个 —— 全仓 wxml 里 `selectmember` 监听 **0 处**。
后果不是「只是不灵」:`director.js` 的 `openRoleAssignment()` 在用户点某一行成员时就把 `roleDraft.teamId/memberId` 定死,`confirmRoleAssignment()` 直接拿草稿提交 → **「在弹层里改选另一个人再确认」会把角色写到最初点进来的那名成员身上,且界面没有任何提示**。权限域错写,定 P1。详见 `04-playkit-event-contract.md` F-PK-2。

### #26 [P2] `cy-upload` 的失败重试两个宿主都没接,「禁静默丢图」形同虚设
`pages/publish/activity/index.wxml:101-109`、`pages/club/apply/index.wxml:154` 都既不传 `errorIndexes` 也不绑 `bind:retry`(`components/cy/upload/index.js:13,30-34` 是失败态唯一入口)。裁决口径:兑现(补宿主)或删掉这段假保证。详见 `04-playkit-event-contract.md` F-PK-3。

### #27 [P2] `cy-calendar` 的 `maxRange/overrange` 全仓无使用者
越界时组件**既不落值也不提示**,只抛 `overrange` 后 return(`components/cy/calendar/index.js:127-134`);全仓没有任何页面监听 sheet 的 `overrange`,`maxRange` 除组件内部零引用。详见 F-PK-4。

> 同类余量还有 8 条(footer-bar ×3、result-sheet ×2、free-map exit、merchant-card service、timeline action),全部因宿主从不传门控属性而**今天不可能出现在界面上**,判无害、不编号,但**决定事件契约门禁能否挂线**:先裁这 8 条,见 `04-playkit-event-contract.md` C 类与 `06-gate-blindspots.md`。

---

## 六、漫游与核销码(3 条)

### #20 [P2] 据点码在商家首页扫码口被拒,且不告诉商家该去哪核
`utils/verification-scan.js:20` → `pages/merchant/index/index.js:856-860` 只 toast。护栏本身正确(`tests/unit/verification-scan.test.js:42-47`),缺的是「请从据点页核销」这句指路。详见 `04-roam-and-codes.md` F-RM-1。

### #21 [P2] `hangoutId` 是死参数:传了,落地页压根不接
`pages/roam/index.js:629` 传,`subpackageRoam/nearby/index.js:104` 的 `onLoad()` 连形参都没有。详见 F-RM-2。

### #22 [P2] 出码倒计时兜底 TTL 两端分叉(300s vs 60s)
`subpackageRoam/citynode-code/index.js:51` 用 300s,`pages/merchant/game-node/index.js:569` 用 60s,服务端真值只有一档 `DYN_TTL_MS=300_000`。今天不发作(字段恒在)。详见 F-RM-3。

---

## 七、商家端(4 条)

### #29 [P2] 客户导出:下载失败时同屏既说「已就绪」又说「失败」
`pages/merchant/customer/index.js:1167-1168` 在 `wx.downloadFile` 非 200 / 无临时文件时写 `exportState:'SUCCESS'` **并**写 `exportError:'下载失败，请稍后重试'`;`index.wxml:90` 见 SUCCESS 渲染「导出已就绪（N 行）」、`:93` 继续给「下载并打开」按钮、`:96` 又渲染那条错误。
**复现**:客户页 → 导出 → 断网或让下载返回非 200。屏幕自相矛盾(不是死路,按钮还能点)。
**判级理由**:`SUCCESS` 描述的是服务端导出任务而非本地下载,语义能自圆,但用户读到的是矛盾。证据截图 `shots/merchant-customer-index.png`。详见 `02-merchant-walk.md` F-MC-1。

### #30 [P2] `decor/story` 生产侧零入口,却有 11 个文件 25 行在守它
`pages/merchant/decor/story/` 共 283 行(index.js 207 / wxml 39 / wxss 19 / json 18),**没进 `app.json`**(装修子页只注册 index/ai-npc/coop-setting/gallery/perks,`app.json:131-135`);**生产目录逐目录扫为零** —— `pages/`、`components/`、四个 subpackage、`app.json` 里没有任何 `decor/story` 字面量。装修首页的故事是内联编辑(`pages/merchant/decor/index.wxml:121-123`)。
但测试与工具侧持有 **11 个文件 / 25 行**:7 个单测文件在按活页口径要求它(`merchant-decor-rowform-contract`、`merchant-decor-editor-state-contract` 含 `loadPage` 真跑、`nav-page-title-spacing-contract`、`access-gate-contract:122` 要 `canManageCoop` 门、`merchant-b2b-real-icon-contract:15`、`behavior-assertions-local:64`、`malformed-list-response:354`),外加 `scripts/shot-extra-0808.js:473/475`、`scripts/uiaudit/build-action-ledger.js:503`、`scripts/uiaudit/impact-map.json:442`、`scripts/baselines/u4-dead-setdata-field.json:115`。
危害在维护不在用户:**一个玩家进不去的页占了 7 个单测的断言面**。若判删,要连这 11 个文件一起清并重建接线台账(改 JS 必重跑 `audit:actions`)。详见 F-MC-2。

### #31 [待核] reviews / relation / citynode 三页 reLaunch 后落在工作台
走查第二趟三条都 `redirected:true`、逻辑层与工作台同构(截图已改名留证:`shots/UNVERIFIED-bounced-to-workbench-merchant-{reviews,relation,citynode}-index.png`)。
**但代码里找不到自动跳转**:三页唯一的 `reLaunch('/pages/merchant/index/index')` 都在用户主动的 `onNavBack`(`citynode/index.js:116-118`、`reviews/index.js:81-88`、`relation/index.js:218-220`),`onLoad/onShow` 不跳;三页入口也都在(`components/tabBar/index.js:10`、`marketing/index.js:524`、`cy/profile/index.js:1657`、`decor/index.js:832`、`scene-registry.js:84`)。
复跑需 `mp.pageStack()` 二次确认时 DevTools 被并行会话占走(`pageStack 在 5000ms 内未返回`),**本轮不定论**。
→ **请人工**在模拟器从「合作」Tab、营销页「口碑管理」、装修页「城市据点」各点一次;正常则这条按探针假阳性删掉。详见 F-MC-3。

### #32 [P2] `merchant-ledger` 冒烟测的是渲染,不是连通
`tests/automator/merchant-ledger-settlement-smoke.js:17-24` 进页后直接 `page.setData({overview, entries, batches})` 灌假数据再断言,**这条冒烟是绿的**;而本轮同一台模拟器冷进该页的真实状态是 `error:true`(`shots/merchant-ledger-index.png`)。
它不撒谎(名字写着视觉/交互冒烟),但计划书要求它「核对账目口径」—— 它核的是自己灌进去的口径。裁决:补一条只断言「请求成功且 overview 来自响应」的连通用例,或在挂线时标明「渲染类」,别让人当回归。详见 F-MC-4。

---

## 八、门禁与 CI(3 条)

### #11 [P1] 本分支现在开 PR 必挂 CI,且是本分支自己造成的
`node scripts/ui-validity-gates.js` 实跑 `EXIT=1`(U4/U5,原始输出 `logs/ui-validity.log`),CI 确实跑它(`ci/xcx-check.sh:245-246`)。
根因已定位:`02f9105f1` 删掉了 `pages/publish/temp/index.js` 对 `nodeName/themeName` 的读者(master 上原在 `:839/:842`),使基线里的 `::A2` 条目跳成 `A1`(`scripts/setdata-dead-field-lint.js:117,44-45` 的判据)—— **基线文件一个字没动,代码变了**。U5 同文件白名单 5→4 同批。
修法二选一:把 `themeName/nodeName` 连 `onLoad` 的 setData(`:638-639`)一起删干净,或 `--update-baseline` 并写明理由。⚠️ 删除本身功能上是干净的,别为了绿去恢复读者。

### #12 [P1] 四条 automator 冒烟全在 CI 之外,红的那个没人看见
`grep -n "automator" ci/xcx-check.sh` 零命中;`test:automator:stamp-camera-crop` 当前红(`logs/automator-stamp-camera-crop.log`)。另有未挂 script 的 `merchant-aftercare-smoke.js`、`publish-flow.js` 同理。
裁决点:模拟器测试要 DevTools 服务端口,有基础设施成本;折中是先把「有确定性断言、不依赖登录态」的几条挂上,并先修红。

### #28 [P2] `sticky-bottom-bar-gate` 是唯一真孤儿,且当前红 5 条
`package.json`/`ci/`/其他脚本零引用(`logs/sticky-bottom-bar-gate.log`):`.pop__catch`、`.ds`、`.fx-bg`、`.fx-hero`、`cy-proto-cam`。
挂线前先裁决:`.fx-bg/.fx-hero` 听名字像**背景层**、`cy-proto-cam` 像相机浮层,可能是规则误判 —— 一挂就常驻红。

---

## 九、本轮已否决 / 已撤回的判断(修复批别照抄)

| 断言(来源) | 复核结果 |
|---|---|
| 「20 个门禁脚本没挂 npm script = 没执法」(代理) | **判据错**。17 个由 `ci/xcx-check.sh` 直接调;真孤儿只有 1 个(#28);`state-visibility-gate` 被 `ui-validity-gates.js:13` 收编为 U6,裸跑那 39 条是基线内存量 |
| 「`fabu/index.js:5713 const tid = resData` 会拼出 `topicId=[object Object]` 让主页空白」(代理) | **反了**。后端返回标量(`ApiTopicController.java:464`),`:5713` 正确;错的是 `:5708` 埋点 → #16 |
| 「提现页 catch 静默、错误位没人填」(代理) | **否**。`tixian.js:278/286/299` 每条失败都写 `submitError`,`tixian.wxml:74` 的 `cy-inline-error` 带重试;余额另有 `balanceErr` |
| 「coupon-qr 每次 onShow 无脑起轮询」(代理) | **否**。有 `couponHistoryId` 前置守卫,`onHide/onUnload` 清定时器,`useStatus==1` 自停 |
| 「searchmap 兜底 maxPrice=1000 会静默屏蔽高价活动」(代理) | **否**。1000 是本页滑杆上限,页面明写「价格区间:¥0 - ¥1000」 |
| 「`qr-citynode` 未注册会走到『场景配置缺失』」(代理) | **否**。`utils/scene-registry.js:50` 有登记;场景 id ↔ 注册表 ↔ `app.json` 双向全量扫描 0 缺号 |
| 「`myinvite.js:133-147` 丢参」「`scene-param-lost.test.js` 红」「`withdrawal-preflight.js:26` 缺 header 会 415」(代理) | **三条全否**:文件只有 26 行 / 测试文件不存在 / 四处调用都显式带 JSON header |
| 「商家『口碑』页没有入口」(**我自己**) | **我自己判错并已撤回**:第一条 grep 用 `grep -v 'pages/merchant/reviews/'`,而导航 URL 本身含这段字符串,把两条真入口(`marketing/index.js:524`、`cy/profile/index.js:1657`)一起过滤掉了。锚定 `^` 重跑即见 |
| 「`decor/story` 全仓只剩一行负控在引用」(**我自己,首版 #30**) | **盘查漏了,已改成实数**:分向重数后是 11 个文件 / 25 行(7 单测 + 4 工具资产),生产侧确实零入口。方向不变、量级差一个数量级。教训:**判失效类结论要分「生产目录」与「测试/工具」两向扫**,不能拿一条 grep 的尾巴当全貌 |
| 「`cy-footer-bar` 三个 tap 零绑定 = 五处底栏按钮全哑」「`cy-result-sheet` 同理八处」(探针首版) | **假**。宿主都把它当布局壳,按钮在 slot 里自己 `bindtap`;`primary/primaryText` 那套 props 全仓无人传 → 按钮根本不画 |
| 「`cy-avatar` tap 27 宿主零绑定」(探针首版) | **假**。尺子只认 `bind:tap` 不认 `catchtap`;`pages/club/detail/index.wxml:330/762` 一直有接。修尺子后从 24 条降到 23 条 |

---

## 十、真机 / 体验版遗留清单(本轮按边界不验)

1. 五种码的真实相机核销(团码 / 活动票 / 主题票 / 券 / 据点码)与重复核销反馈。
2. 微信支付真付,含 `unknown` 后的服务端回读。
3. `getPhoneNumber` 授权与 80058。
4. 小程序码 `scene` 入场的实机落地。
5. **商家写流程**:核销、提现、装修保存、团队成员增删、售后回复、据点投放/认领、优惠券发放 —— develop 工具版有生产写拦截闸,模拟器发不出写请求,须体验版 / 测试服 `ext.json`。
6. RBAC 边界:非 owner 员工操作被拒时的反馈,需要真账号。
7. `merchant/ledger` 冷进 `error:true`、`ai-insight` 报「商家资格已失效」而工作台显示「营业中」、`merchant/predict` 冷进报「网络连接失败」而非缺参 —— 三条都可能是写闸/登录态副作用,**与 #31 一起在模拟器人工复验**。
