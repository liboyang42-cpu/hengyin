# 自玩票走小程序虚拟支付（XPAY）— 2026-11-13

范围：平台自有主题（`product_role` CONTENT 槽 `entity_type=PLATFORM`）的**城市定向自玩票**。
自由探索票（商家权益、线下服务）与活动票不动，仍走普通微信支付（JSAPI）。

## 默认不改变任何现状

新开关 `chengyin.selfplay.xpay.enabled`，**默认 false**。关闭时：
- 路由恒为 JSAPI（没有冻结行的单），建单/支付/回调/退款与改动前逐字一致；
- 迁移未执行也不影响（读不到表按"无冻结行"处理，只有开关开启时才把缺表当部署错误抛出）。

## 复用，没有第二套支付

| 复用点 | 位置 |
| --- | --- |
| 签名（paySig / 用户态 signature / signData） | `PlatformPassService.xpayClientParams` + `XpaySigner` |
| OfferID / AppKey / env / apiBase / 就绪判断 | `platform-pass.xpay.*`（`PlatformPassProperties.Xpay`） |
| 服务端接口（query_order / refund_order / notify_provide_goods） | `XpayGateway` / `HttpXpayGateway` |
| 推送入口（同一个消息推送 URL） | `ApiContentSecurityController.onXpayEvent`：PPO 单归通行证，其余按自玩票冻结行认领，互不重叠 |
| 开通内核（库存/结算/积分/核销码） | `CmsRegistrationServiceImpl.completePaymentSuccess`（与 JSAPI 回调同一内核） |
| 原路退款的审批→打款状态机 | `RefundPayoutServiceImpl` 中仅加一个 XPAY 分支，退款号/租约/人工队列沿用 |

新增文件：`SelfPlayXpayService`、`SelfPlayXpayProperties`、`SelfPlayXpayException`、`SelfPlayXpayMapper`（均在 `platformpass` 包，**未改动通行证的任何文件**）；迁移 `chengyinhub-admin/sql/migration_20261113_selfplay_xpay.sql`（只新建 `cms_registration_xpay`，不动 `cms_registration`）。

## 行为保证

| 要求 | 实现 |
| --- | --- |
| 服务端决定渠道，客户端不能强制 JSAPI | `SelfPlayXpayService.routeFor`：已有冻结行→XPAY；开关开+平台自有+自玩通行证+金额有对应道具+尚无任何 JSAPI 尝试→XPAY。XPAY 单走 `/pay/app` 被拒；XPAY 配置不可用返回"支付暂不可用"，**不降级 JSAPI** |
| 首次签发冻结商品/环境/金额 | `/api/registration/pay` 带 `jsCode` 时 `insert ignore` 写 `cms_registration_xpay`（product_id / xpay_env / price_fen）；之后配置怎么改都按冻结值签；env 与冻结不一致拒签（`XPAY_ENV_CHANGED`）。未带 jsCode 只回渠道标记，不冻结 |
| 只认已验证支付才开通 | 推送/前端 confirm 都只触发回查；回查须 `order_id` 一致、`paid_fee == order_fee == 冻结价` 才调 `completeXpayPayment`；不一致告警并拒绝 |
| 发货确认可补偿 | 开通后 `delivery_confirmed=0`，回查状态为"待发货"时补报 `notify_provide_goods`；失败不影响已开通，`@Scheduled` 对账（默认 60s）继续补报 |
| 渠道发起的退款关闭报名、停止游玩 | 推送/对账回查到已退款（status 5/8，且退款额=冻结价）→ `applyXpayChannelRefund`：置 payment=4/registration=3（游玩闸读这两列）、释放权益、追回赏金/积分，并把处理中的退款单置成功；非整额退款作冲突告警，不静默关闭 |
| 重复/迟到通知幂等 | 开通 CAS（`claimPaymentSuccess` + 同交易号重复直接 SUCCESS）；冻结行状态 CAS；已退款行再来通知直接返回 |
| 已付但报名已关闭（过期/取消后才付成） | 记 `PAID_CLOSED` 并告警，自动按固定退款号 `XR+报名号` 整单退款，对账按同号重试 |
| 过期/取消不凭本地时钟关单 | `OrderCloseTask` 与用户取消前先过 `readyToClose` 围栏：回查已付→开通并拒绝关单；回查失败→不关；确认未付→放行 |
| 平台发起退款（审批后打款） | `RefundPayoutServiceImpl` XPAY 分支：仅整单退，固定退款号，受理→处理中，渠道拒绝→人工队列，结果未知保留原号 |
| 旧 JSAPI 单照旧 | 没有冻结行、或已发起过 JSAPI 尝试（payment_status≠0）的单恒为 JSAPI，回调/退款路径未改 |

## 小程序

- `utils/checkout/selfplay-xpay-pay.js`（新）：签发（wx.login→`/pay`+jsCode）、`wx.requestVirtualPayment`、支付后先 `/api/registration/xpay/confirm` 再走原订单状态核账。
- `pages/topic/index/index.js`（selfPlayBuy→checkout 流）、订单列表/订单详情的"继续支付"：服务端返回 `payChannel:'XPAY'` 时走虚拟支付。
- 设备不支持 `requestVirtualPayment`：提示"当前微信版本暂不支持购买，请升级微信后重试"，**不调用 `wx.requestPayment`**。
- 支付 SDK 成功回调不算已付；非取消的失败也先回查一次（SDK 失败≠没扣款）。

## 业主需要在微信后台/部署侧配置

1. **虚拟支付商品（道具）**：在小程序虚拟支付后台为**每一个自玩票价位**建一个道具，价格与主题 `self_play_price` 完全一致（例：39.90 元 → 一个 3990 分的道具）。
2. **价位→道具映射**（分为键）：`chengyin.selfplay.xpay.goods=3990=<道具ID>,4990=<道具ID>`。金额不在映射里的单不走虚拟支付（该单仍走 JSAPI，不会出现"虚拟支付单卖错价"）。
3. **OfferID / AppKey / env**：复用通行证配置 `platform-pass.xpay.offer-id / app-key / env / enabled`（AppKey 只经环境变量注入）。`env` 默认 1=沙箱，**上线前改 0=现网**；沙箱不推送发货通知，靠 confirm 回查开通。
4. **消息推送**：虚拟支付事件沿用现有消息推送 URL（`xpay_goods_deliver_notify`、`xpay_refund_notify`），无需新增地址。
5. **执行迁移**（走 chengyin-db-migrate 只读预检 + 业主确认）：`migration_20261113_selfplay_xpay.sql`，须**先于**把 `chengyin.selfplay.xpay.enabled` 置 true。
6. 最后把 `chengyin.selfplay.xpay.enabled=true`。回滚：置 false 即可，已冻结的 XPAY 单仍按冻结值完成支付/退款/对账，不会被误转 JSAPI。

## 已知边界（诚实记录）

- 退款仅支持**整单**；渠道侧部分退款会告警并拒绝关闭（需人工核对）。
- 渠道（微信侧）发起的退款到账后，分成/结算冲回由告警提示财务核对，本次没有臆造自动冲销规则。
- XPAY 单没有 JSAPI 的"原支付事实回执"（`registration_payment_fact`），依赖该回执的下游（如发票、营销商品原路核对）对这类单不适用；自玩票的发票口径需业主确认。
- `ci/xcx-check.sh` 的 action ledger（`scripts/uiaudit/action-ledger.json`）未重新生成；新增端点 `/api/registration/xpay/confirm` 已在后端存在。
- PR 描述已补「影响分类 ③」迁移声明。
- 本次没有新增真库 IT（新表只被注解 Mapper 读写，单测用 mock；未改既有表，既有真库夹具无需变更）。

## 验证

- 小程序：`node --test tests/unit/selfplay-xpay-pay.test.js` + 相关既有用例全绿（见 PR）。
- 后端：单测 `SelfPlayXpayServiceTest`、`SelfPlayXpayPropertiesTest`、`SelfPlayXpayCheckoutRoutingTest`，并扩展 `RegistrationPaymentRetryContractTest`；Java 构建/测试按仓库规定只在 CI 跑。

STATUS: PARTIAL - CI 结果待确认
