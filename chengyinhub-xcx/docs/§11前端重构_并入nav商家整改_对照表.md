# §⑪ 7 个前端重构 → 并入 nav/商家整改方案 对照表

> 结论(2026-07-07 交叉核验):报告 §⑪ 剩余的 7 个前端结构性重构,**不应作为独立任务做**——
> 它们改的页面正是《用户端最终导航…》和《商家端产品架构…》两份方案要重构的同一批文件。
> 单独做 = 重复劳动 + 撞车。正确姿势:**并入对应方案阶段,一次重构一次真机验**。
> 本表给每条一个确切落点。两份方案见 vault:`Documents/城瘾/小程序/{用户端最终导航…, 商家端产品架构…}_2026-07-07.md`。

| §⑪ 重构 | 并入方案 · 章节 | 顺手一起做的动作 | 真机验收 |
|---|---|---|---|
| **FE-16 主包瘦身** | 用户端 §7.1 tabBar(改 app.json + 新增 publish/index、club/home) | 既然已在动 app.json 加新页,同时把 `pages/play`、`pages/roam/*`、`pages/activity/*` 从主包 pages[] 下沉到分包 + 配 preloadRule | 主包页数 ≤40(跑 `node scripts/preflight-release.js`);报名→play、分享深链、roam/activity 入口不 404 |
| **FE-12 index 拆分** | 用户端 §4.1 首页 + §7 | 重构首页时顺手把 1912 行拆成 section 组件 | 首页各段渲染+交互不变(**保留 BE-02 的 `index.js` bannerClick=isMerchantView()**) |
| **FE-11 fabu 拆分** | 用户端 §7.2 发布广场 + 商家 §5.4 模板 | 动发布区时把 fabu 3723 行拆 5 组件(route/plot/common/game-embed/publish-check) | 建主题→路线→剧情→游戏内嵌→发布检查 全程 EventChannel 往返 |
| **FE-04 发布器统一入口** | 用户端 §7.2 发布广场(publish/index 新中枢) | 新发布中枢即统一入口,temp 收窄为纯预览 | 各身份(玩家/主理人/商家)发布链路正确分流 |
| **FE-14 资料页收编** | 用户端 §7.4 我的页 + 商家 §8 页面归属(shanghuziliao/role/center→我的) | 重构「我的」时把 gerenziliao/shanghuziliao/profile/userinfo 编辑能力收进 role/center,4 页留重定向 | 编辑个人/店铺资料保存链通;旧页重定向不断 |
| **FE-10 组件收编** | 用户端 §7.1 + 商家 §5.1 导航组件改造 | 抽公共 UI 到 `components/cy`(采纳率 10%→70%),与 tabBar/nav 组件改造一起 | 三批各抽查视觉一致 |
| **FE-15 色值 token** | 商家 §10 设计规范 + 浅色化收尾 | 浅色化定稿后,top10 页硬编码 hex → `--cy-*` token | 逐页视觉核对无偏色 |

## 不并入、保持独立的 2 条(与 nav/商家方案无关)
- **BE-10 quoteSign 硬校验**:纯支付路径,需给 settlement 接 /quote + 真机验价格一致性,再翻后端硬校验。见 `docs/剩余9条_真机专场执行计划.md` §8。
- **FE-01 碎片/彩蛋成就大屏**:需先拍数据模型(建 member 级收集表?两套勋章合不合?),拍完再后端建表+前端聚合。

## 保护本轮已提交成果(重构时别覆盖回)
- `member/index.js`:**保留 BE-01** `medals: that.data.identityCard.medals`(勋章只由 medal/wall 写),别退回 `play.badgeCount`。
- `app.json`:**保留 FE-01** 已删的 `pages/member/badges/index`,别加回。
- `index.js`:**保留 BE-02** bannerClick 用 `isMerchantView()`,别退回 `userInfo.userType==2`。
- `baoming.js`/`settlement.js`:**保留 BE-03** 的 `requestId` 幂等键。
- 后端 `ApiRegistrationController`:玩家 `/create` 已含 BE-09 原子化 + BE-03 幂等,商家整改只动 `/merchant/*` 分支,别误改玩家 create。
