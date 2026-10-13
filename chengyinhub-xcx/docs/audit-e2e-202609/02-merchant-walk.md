# 阶段2 · 商家端只读走查(28 条注册路由全量)

跑法:`node docs/audit-e2e-202609/walk-merchant.js`(放一个 `walk-routes.txt` 就只走那份)。
逐条 `reLaunch` → 等 1.5s → 读逻辑层 `page.data()` → 截图。截图与状态明细已拷进 `shots/`(28 张)和 `logs/walk-merchant.json`;走查日志 `logs/walk-merchant.log`(第一趟)、`logs/walk-merchant-pass2.log`(第二趟,完整 28 条)。

前提说清楚:模拟器账号是已登录的商家(工作台渲染出「城市运动装备馆 · 营业中 · 项目 全部 8」),**读侧可走**;写请求有 develop 版拦截闸(记忆「develop 版生产写拦截闸」),所以「操作 → 写 → 回读」这三步本轮走不了,只有静态对账 + 体验版能补。

## 一、逐页状态(第二趟,28/28 拿到数据)

| 路由 | 逻辑层状态 | 判读 |
|---|---|---|
| `merchant/index/index` | 35 keys | 工作台正常,收入 ¥0.00 是真实数据不是假绿(见 `shots/merchant-index-index.png`) |
| `merchant/apply/index` | `canNext:true` | 申请页可进 |
| `merchant/ledger/index` | `error:true` | 进页即「核销记录加载失败 / 数据服务暂不可用 / 重新加载」——**错误态文案与重试出口都在**(`shots/merchant-ledger-index.png`),但根因本轮定不了,见「未决」 |
| `merchant/ledger/order-detail` | `errorKind:'data'`,`errorText:'缺少核销记录标识'` | 缺参守卫正确且说人话 |
| `merchant/ledger/batch-detail` | `errorKind:'data'`,`errorText:'缺少结算批次标识'` | 同上 |
| `merchant/aftercare/index` | `total:0`,`errorMessage:''` | 售后列表空态正常 |
| `merchant/aftercare/detail` | `canRespond:false,canSubmit:false` | 无单可回,按钮正确置灰 |
| `merchant/predict/index` | `errorMessage:'网络连接失败，请稍后重试'` | 见「未决」:冷进无参,报的是网络而不是缺参 |
| `merchant/profile/index` | `state:'invalid'` | 有显式 invalid 态,好 |
| `merchant/marketing/index` | 22 keys | 页面在,顶部横幅「经营身份校验未通过,请返回工作台重试 · 点击重试」,数字全用「—」占位(**没有假 0**),见 `shots/merchant-marketing-index.png` |
| `merchant/marketing/ai-insight` | `error:'商家资格已失效'` | 与工作台显示的「营业中」矛盾 → 归到「未决」一起复验 |
| `merchant/reviews` `merchant/relation` `merchant/citynode` | 落在工作台 | **未复现的回弹**,见下面「未决」 |
| `merchant/citynode/create` | `canSubmit:false,showLeaveConfirm:false` | 新建据点空表单正常 |
| `merchant/decor/*`(index/ai-npc/coop-setting/gallery/perks) | 均正常拿到 keys | 装修五页可读 |
| `merchant/coop-center` | 21 keys | 正常 |
| `merchant/customer/index` | `emptyTitle:'客户名单这次没取回来'`、`emptySub:'统计里有 1 位客户,但名单没加载出来…'`、`emptyAction:'retry'`、`canSegment/canExport/canMarket/canCoupon:true` | 部分失败的诚实空态:统计出来了、名单没出来,它照实说并给重试 |
| `merchant/customer/detail` | `errorMessage:'客户ID无效'` | 缺参守卫正确 |
| `merchant/game-node` | `errorMessage:'页面参数不完整，请从商家项目卡重新进入'`、`canRetryUnknownWrite:false` | 缺参守卫正确,且 unknown 复位开关在 |
| `merchant/team` | `canShareCreatedInvite:false` | 邀请分享按钮按权限正确关着 |
| `play/merchant/index`、`topic/merchantapply`、`topic/merchantinfo` | 正常 | 商家侧玩法/报名/名片页可读 |

## 二、新发现(接 report.html 的号)

### F-MC-1 [P2 → 编号 29] 客户导出:下载失败时同屏既说「已就绪」又说「失败」
`pages/merchant/customer/index.js:1167-1168` 在 `wx.downloadFile` 拿到非 200 或没有临时文件时,把状态写成 `exportState:'SUCCESS'` + `exportError:'下载失败，请稍后重试'`。而 `index.wxml:90` 见 SUCCESS 就渲染「导出已就绪（N 行）」、`:93` 继续给一颗「下载并打开」按钮、`:96` 又渲染那条错误。
结果:一次失败的下载在屏幕上同时是「已就绪」和「失败」。不是死路(按钮还能再点),但状态口径自相矛盾。
判级理由:服务端导出任务确实成功了,`SUCCESS` 描述的是任务而不是下载 —— 语义能自圆,但用户读到的是矛盾。

### F-MC-2 [P2 → 编号 30] `decor/story` 生产侧零入口,却有 11 个文件 25 行在守它
`pages/merchant/decor/story/` 有 283 行(index.js 207 / wxml 39 / wxss 19 / json 18),**没进 `app.json`**(装修子页只注册了 `decor/index`、`ai-npc`、`coop-setting`、`gallery`、`perks`,`app.json:131-135`)。
生产侧引用**逐目录扫为 0**:`pages/`、`components/`、`subpackageA/B/Member/Roam`、`app.json` 里没有任何一处 `decor/story` 字面量(既没人 `navigateTo`,也没人 `<navigator>`)。装修首页的故事是内联编辑的(`decor/index.wxml:121-123` 的 `dc-story` 卡 + `m.description`),不跳子页。
但**测试与工具侧持有得多了**(11 个文件 / 25 行,首次只报一行负控是盘查漏了):
- 7 个单测文件在按活页口径要求它:`merchant-decor-rowform-contract`(:52/80/104/105/122)、`merchant-decor-editor-state-contract`(6 处,含 `loadPage` 真跑页面)、`nav-page-title-spacing-contract`(:35/81/105)、`access-gate-contract`(:122 要求 `canManageCoop` 门)、`merchant-b2b-real-icon-contract`(:15)、`behavior-assertions-local`(:64)、`malformed-list-response`(:354)。
- 4 处工具/资产:`scripts/shot-extra-0808.js:473/475`(截图项)、`scripts/uiaudit/build-action-ledger.js:503`(接线台账里 `onSave` 一条)、`scripts/uiaudit/impact-map.json:442`、`scripts/baselines/u4-dead-setdata-field.json:115`(`::keep::A2`)。
危害不在用户而在维护:**一个玩家进不去的页,占了 7 个单测的断言面**。删页要连这 11 个文件一起清,并重建接线台账(改 JS 必重跑 `audit:actions`)。

### F-MC-3 [待核 → 编号 31] reviews / relation / citynode 三页 reLaunch 后落在工作台
证据:第二趟三条都 `redirected:true`、`page.data()` 与工作台同构(35 keys / `statusBarHeight:47`),截图已改名留证(`shots/UNVERIFIED-bounced-to-workbench-merchant-*.png`)。
**但代码里找不到自动跳转**:三页唯一的 `reLaunch('/pages/merchant/index/index')` 都在用户主动的 `onNavBack` 里(`citynode/index.js:116-118`、`reviews/index.js:81-88`、`relation/index.js:218-220`),`onLoad/onShow` 不跳;三页的入口也都在(`relation` 在商家自定义 tabBar `components/tabBar/index.js:10`、`reviews` 在 `marketing/index.js:524` 与 `components/cy/profile/index.js:1657`、`citynode` 在 `decor/index.js:832` 与 `scene-registry.js:84`)。
复跑要 `mp.pageStack()` 二次确认时 DevTools 已被并行会话占走(`pageStack 在 5000ms 内未返回`),本轮没能定论。
→ 请人工在模拟器里从「合作」Tab、营销页「口碑管理」、装修页「城市据点」各点一次;若正常,这条按「探针假阳性」删掉。

### F-MC-4 [P2 → 编号 32] merchant-ledger 冒烟测的是渲染,不是连通
`tests/automator/merchant-ledger-settlement-smoke.js:17-24` 进页后直接 `page.setData({overview, entries, batches})` 灌假数据再断言。本轮同一台模拟器上,冷进这一页的真实结果是 `error:true`(见上表),而这条冒烟**绿**。
它不撒谎(名字里就写着视觉与交互冒烟),但计划书要求它「核对账目口径」——它核的是自己灌进去的口径。要么加一条不断言假数据、只断言「请求成功且 overview 来自响应」的连通用例,要么在 CI 挂线时把它标成「渲染类」,别让人误当回归。

## 三、本轮排除掉的商家端嫌疑(记下来免得下轮重跑)

| 嫌疑 | 结论 | 依据 |
|---|---|---|
| `merchant/reviews:151/160` 动态 method 会不会 415 | 不会 | 只带 query 无 body,后端 `@RequestParam`(`ApiMerchantReviewController.java:40-42`) |
| `components/cy/profile:55` 动态 url 会不会 415 | 不会 | 16 个落点逐个对到后端裸参签名,详见 `00-baseline.md` 第一节表 |
| `cy-footer-bar` 三个 tap 事件零宿主绑定 = 底栏按钮全哑? | 假 | 五个宿主都把它当**布局壳**用,按钮在 slot 里自己 `bindtap`(`address.wxml:41-45`、`addressinfo.wxml:49-51`、`gerenziliao.wxml:107-110`、`customer/index.wxml:227-233`、`coupon.wxml:48-50`);`primary/secondary` 那套 props 全仓无人传 |
| `cy-result-sheet` primary/secondary 零绑定 = 八处发布成功面板按钮哑? | 假 | 八个宿主都只传 `show/kind/title/…`,从不传 `primaryText`;而组件在 `primaryText` 为空时根本不画按钮(`result-sheet/index.js:47-52` + `index.wxml`) |
| `cy-avatar` 的 `tap` 27 宿主零绑定 | 假 | 宿主在标签上直接挂原生 `catchtap`(`club/detail/index.wxml:330/762`),组件的 `clickable` 只是样式开关 |
| `cy-free-map` 的 `exit` 三宿主零绑定 | 假 | `showExit` 默认 false,全仓只有 `play/index.wxml:31` 显式传 `false`,退出键从没画出来 |
| `cy-info-pop` open/close、`cy-scene-deep-link` close、`cy-search` clear、`cy-cover-fallback` covererror、`club-game-code-sheet` saved | 假 | 五个组件都在自己内部把事做完(开合/回退/清值/兜底图/存相册后 toast),抛事件只是可选钩子 |
| `cy-timeline` action、`cy-merchant-card` service | 假(暂时) | 两处按钮都由数据字段门控(`item.action`、`serviceText`),全仓没有任何调用方喂过这两个字段 → 按钮不画。是「未启用的能力」,不是坏按钮 |
| `cy-calendar` 的 `pick` 在 `date-range-sheet` 里没接 | 假 | range 模式下组件同时抛 `change`(`calendar/index.js:135`),宿主接的是 `change` |
| 商家「口碑」页没有入口(我一度这么判) | **我自己判错,已撤回** | 第一条 grep 用了 `grep -v 'pages/merchant/reviews/'`,而导航 URL 本身含这段字符串,把两条真入口(`marketing/index.js:524`、`cy/profile/index.js:1657`)一起过滤掉了。锚定 `^` 重跑即见 |

## 四、商家端本轮没覆盖到的(必须说)

- **写流程**:核销、提现、装修保存、团队成员增删、售后回复、据点投放/认领、优惠券发放 —— develop 版写闸拦死,静态侧只能到「请求体编码对、危险动作有确认」(这两条分别由 `lint:requestbody` 与 `danger-confirm` 门禁绿覆盖)。要验收得走体验版/测试服 `ext.json`。
- **RBAC 边界**:非 owner 员工操作被拒时的反馈,需要真账号,本轮未验。
- **核销五类码 × 四个调用点**:静态对账结论在 `04-roam-and-codes.md`(F-RM-1/2/3),真机扫码在遗留清单。
