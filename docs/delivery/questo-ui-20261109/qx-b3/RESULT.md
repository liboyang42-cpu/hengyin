# qx-b3 结果：玩家闭环 P1–P9 与多作用域榜

分支 `claude/qx-b3-20261109`，基线 `origin/integrate/questo-ui-20261110`（449fb80d）。只改小程序。

## 结论

- 已实现：P7 战报、P1 落地（推荐、礼物、同行席位）、P4 玩法模式、P5 票内席位、P2 推广中心、P8 同行回忆、P9 单关榜与队内榜，以及 §23.4 多作用域榜。
- 这些页面在 rig 里接的都是基线后端的真实接口。所有读写都实际执行并回读过，包括签发席位邀请、领取、撤销和签发推广凭据。
- **新发现的后端缺陷（不属于本 lane，需要处理）：** `GiftEntitlementMapper` 用 `cms_registration.parent_order_no`（utf8mb4_general_ci）去 join `marketing_product_order.order_no`（建表时没写 COLLATE，MySQL 8 默认 utf8mb4_0900_ai_ci），报 `Illegal mix of collations`。
  - 受影响接口：`GET /api/play/player-journey/choices`（P4）和 `POST /api/play/seat-invitations/invite`（P5），两者都返回 500「系统繁忙」。
  - 证据：`rig/choices-500-collation.json`、`rig/invite-500-collation.json`、`rig/collation-stack.txt`，以及截图 `P5-after-invite-unknown`（客户端如实显示「结果还没确认」，保留原 requestId 供查询）。
  - rig 里只改了 rig 库这一列的排序规则才继续走查（HARNESS）。生产库是否同样报错，取决于生产库建表时的默认排序规则，未核实。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证的 | 真机未验 |
|---|---|---|---|---|---|
| 首链 P7→P1→P4→P5 | fixed（链路部分为 fixture） | 战报分享会签发推广凭据，落地页 `k=r`；席位页邀请生成 `k=s` 链接；新账号打开后看到玩法模式，领取席位，再「进入本局」回到同一主题的游玩页 | 见下面各行 | rig 实跑：新账号 `e2e-b3-friend` 领到第 2 席（`P4-P5-after-claimed`），然后进入游玩页（`P1-after-enter-run`）。<br>**局的完成事实和 3 人票的支付事实是 SQL fixture 写的**（`rig/seed-b3.sql.txt`）：本基线已停售 rig 自带开售链（「四店固定补偿产品已停止新开售」），chain 走不到真实完局。 | 真实完局→战报；微信分享卡；真实支付 |
| P7 本局战报 | fixed | 新页 `subpackageJourney/pages/report`，结构照抄游玩完成面板。<br>结果、完成节点、用时、解谜分都读服务端，读不到不补 0：用时显示「未知」，未计分显示「本局不计分」。<br>名次只用服务端 `rank`。显式 runId 读的局与返回不一致就拒绝显示。<br>入口两处：完成面板「本局战报」行；主题记录卡「本局战报」按钮（按 sessionId 重开同一局）。 | `subpackageJourney/pages/report/*`、`subpackageJourney/utils/*`、`pages/play/index.{js,wxml}`、`components/cy/theme-records/*`、`utils/theme-records.js`、`pages/topic/index/index.wxml` | rig 截图：正常 `P7-after`、进行中 `P7-after-in-progress`、别人的局 `P7-after-not-found`、未登录 `P7-after-unauthorized`、入口 `P7-entry-before/after`。<br>入口截图是用 setData 打开完成面板的（HARNESS），点击后进入的是真实战报。单测已覆盖。 | 主题记录卡按钮：rig 里没有主题会话，未走到 |
| P9 单关与队内榜 | fixed | 战报「名次」打开榜单面板（half 档固定）。<br>单关榜 `scope=quest`，口径写「完成节点数」。<br>只有 `choices` 给出队伍时才出「队内」页签，读 `/api/orient/team/detail`，没完成任务的成员不补名次。 | 同上 | `P9-after` 是真数据，并列名次由服务端排。 | 队内榜：rig 没有组队主题，未走到。<br>队伍之间榜（`/api/orient/team/ranking`）未接，见 needs owner |
| §23.4 多作用域榜 | fixed | 世界、单关、城市、国家、俱乐部 × 累计/本周。<br>周界直接显示服务端的 `periodStart/periodEnd`（结束排他）。<br>`me.rank` 为空显示「暂无名次」，不显示 0 分名次。<br>服务端返回的作用域和请求的不一致时，不冒充所选榜。<br>缺冻结地理的局写「N 局缺少冻结地理，未计入」。 | 同上 | `S234-after-week/city/world`：城市、国家页签是用 URL 传入的冻结码打开的，rig 数据带 `contentGeography`。 | 见 needs owner：城市、国家、俱乐部码目前没有服务端来源 |
| P8 同行回忆 | fixed | 战报「本局回忆」面板：本人照片和节点时间线，人数大于 1 才写「N 人同行」。<br>`photoShareCapability` 不是 AVAILABLE 时，不展示同行私照。 | 同上 | `P8-after` 是真数据，照片是 fixture 里的站内图。 | 多人局、撤回同意 |
| P1 推荐/礼物/同行落地 | fixed | 新页 `subpackageJourney/pages/landing`。<br>打开链接只读：不领取、不建单、不归因。<br>普通推荐只写「朋友邀请你一起玩」；只有礼物 VALID 才写「朋友送你一局」。<br>失效、过期、撤销、不可见各有文案；`registrationState=CLOSED` 时不出按钮。<br>领取写口保留 requestId，结果未知就用同一个号查。切账号后重新读取。 | `subpackageJourney/pages/landing/*` | rig 截图：<br>有效推荐 `P1-after-referral`（点「去看看」进入公开主题页 `P1-after-referral-target`）<br>报名关闭 `P1-after-referral-closed`<br>主题不可见 `P1-after-referral-unavailable`<br>伪造链接 `P1-after-referral-invalid`<br>席位链接 `P1-after-seat`<br>撤销后的链接 `P5-after-claim-revoked` | 礼物：rig 里没有可售商品，无法签发礼物码，只做了单测；该写口属 b5 范围 |
| P4 玩法模式 | fixed（只放在落地页） | 只列服务端给的模式，并写出进度、提示、计分各自是「各自 / 全队共享 / 全场共享」。<br>不可用的模式给原因，缺端能力时不可选。<br>`configState` 不可用就不显示模式块。 | `subpackageJourney/utils/journey-view.js`、`landing/*` | `P4-P5-after-claimed`、`P1-after-referral` | 详情页和报名确认步骤里的 P4 未放，见 needs owner |
| P5 票内席位 | fixed | 新页 `subpackageJourney/pages/seats`，入口在订单详情「同行席位」段（仅已付且 `orderNum>1` 时出现）。<br>席位数和状态全读服务端：「还可邀请」读不到就显示「未知」。<br>只有服务端声明 `seatCancelSupported` 时才出「撤销」，撤销用红色并走危险确认。<br>邀请结果未知时保留同一个 requestId 供查询。**释放已领取席位不出按钮**（§24.3）。 | `subpackageJourney/pages/seats/*`、`subpackageMember/components/cy/scene-member-order-detail/index.{js,wxml}`、`utils/danger-actions.js` | rig 截图：<br>`P5-after`<br>`P5-after-invited`（邀请真实签发）<br>`P5-after-two-invites`<br>`P5-after-cancel-confirm`<br>`P5-after-cancelled`（真实撤销）<br>`P5-after-not-buyer`（服务端 403 原文）<br>`P5-after-invite-unknown`<br>`P5-entry-before/after` | 两人抢最后一席；退款与加座后的席位守恒 |
| P2 推广中心 | fixed | 新页 `subpackageJourney/pages/promo`，入口在设置「邀请与收益」。<br>待确认、已确认、已标记付款、已冲销四栏分开，金额读不到写「未知」，多币种写「多币种暂不合计」，没有分配就不显示金额栏。<br>邀请链接只列有效或已失效，不显示内部状态码。<br>没有提现按钮：服务端回 `payout.available=false`。 | `subpackageJourney/pages/promo/*`、`pages/shezhi/shezhi.{js,wxml}`、`pages/shezhi/images/icon_lc_gift.svg`（lucide ISC，与同目录图标同族） | rig 截图：`P2-after`（有真实签发的链接）、`P2-after-empty`、`P2-after-unauthorized`、`P2-entry-before/after` | 有分配与冲销的真实数据 |

## 怎么验证的

- 单测：`node --test chengyinhub-xcx/tests/unit/qx-b3-player-journey.test.js`，10/10 通过。覆盖口径如下：
  - 未知不显示 0；
  - 不伪造名次；
  - 作用域被改写时拒绝显示；
  - 未完成任务的队员不补名次；
  - 私照受同意事实约束；
  - 模式和能力闸；
  - 撤销与释放按钮；
  - 推荐不承诺免费；
  - 不出提现按钮；
  - 分包注册与入口存在。
- UI 门禁：路由矩阵用 `node chengyinhub-xcx/scripts/uiaudit/build-route-matrix.js --json` 重建（新增 4 条路由），动作台账用 `build-action-ledger.js --write` 重建。`node chengyinhub-xcx/tests/lint/ui-integrity.js` 输出 `UI-GATE-0:PASS`，没有新增任何基线条目；为此把 `openRunReport` 放到了 `pages/play/index.js` 现有基线行之后。
- rig 截图按 brief 规格：390 宽，JPEG q70，在 `shots/`。
- rig 的改动都只在 rig 侧，不进产品代码：
  - dockerd 改用 mirror.gcr.io，因为 Docker Hub 返回 429；
  - rig 的 `app.py` 透传假的 `CHENGYIN_OSS_*`，否则 `/api/play/nodes` 报「对象存储配置缺失」；
  - 席位邀请那一列的排序规则，见上面的缺陷；
  - fixture SQL 在 `rig/seed-b3.sql.txt`。

## needs owner（每项写了已经实现的默认做法）

1. **排序规则缺陷**（上面那条）。默认：客户端如实显示 500 为「结果还没确认」，不重发新号。修后端 mapper 或迁移属于其他 lane 或后端。
2. **城市、国家、俱乐部榜的作用域码没有服务端来源。** 战报接口不返回本局冻结的 `contentGeography` 和俱乐部 ID，游玩页也没有这些字段。默认：这三个页签只在调用方明确传入码时出现，平时只有单关和世界。建议战报 VO 增加冻结的 `cityCode`、`countryCode`、`clubId`。
3. **P5 释放已领取席位。** 服务端回 `seatReleaseSupported=true`，但清单 §24.3 写明保护仍在退修。默认：不出释放按钮。
4. **P4 放在主题详情或报名确认步骤**会改到 b4 的详情页。默认：这次只放在落地页，也就是被邀请的路径。
5. **P9 队伍之间榜**（`/api/orient/team/ranking`）。默认：没接，队内只接成员名次。
6. **礼物落地写口** `POST /api/marketing/product/gifts/claim` 已按合同接线（requestId 幂等），但 rig 里签发不出礼物码，未实跑。

## Cross-lane

- `subpackageMember/components/cy/scene-member-order-detail/index.{js,wxml}` 也是 qx-a3（V3-02/03）在改的文件：我在「支付信息」段后面加了一个独立的「同行席位」段（带段标题，不与上一张卡相连）。合并时要和 a3 的卡片间距调整一起看。
- 共享文件只做了最小改动：
  - `app.json`：新增 `subpackageJourney` 分包；
  - `scripts/uiaudit/action-ledger.json`、`route-matrix.json`：工具重建；
  - `utils/danger-actions.js`：新增 `seat.invite-cancel`；
  - `components/cy/theme-records`：新增 `report` 事件。
- P4 的端能力比对，等 qx-b1 提供 `supportedCapabilities` 清单后接上。`choicesView(data, supported)` 已经留了参数；现在不传，表示本端没有声明，所以不据此拦截。

STATUS: PARTIAL - 局的完成和支付事实是 SQL fixture（rig 自带开售链在本基线已停售），所以「真实完局→战报」这一段没有实跑；P4 未放进详情页；队伍之间榜未接；礼物落地未实跑；城市、国家、俱乐部码等服务端字段
