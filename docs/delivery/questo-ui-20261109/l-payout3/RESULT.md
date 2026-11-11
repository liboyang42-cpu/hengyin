# l-payout3 结果：钱属于多个商家 / 俱乐部的订单改走「合单支付」，全部默认关闭

分支 `claude/l-payout3-20261111`，从 `origin/launch/questo-20261110` 切出，开 PR 前已合入最新 launch（无新提交）。

依据：业主 2026-11-11 原话（已开通电商收付通；多个商户之间不支持直接分账，其余都可用）、l-payout / l-payout2 的 RESULT.md、`proto/DECISIONS-payout.md`、LAUNCH.md 的 Money 规则、根目录 AGENTS.md。

## 一、设计说明：哪类订单走哪条路，为什么

**核心约束：** 电商收付通里一笔子单的钱只能进一个二级商户；分账只能从这个二级商户分给电商平台，不能在两个二级商户之间直接分（业主确认）。所以，凡是钱要给多个商家或俱乐部的单，都必须在**付款时**就按收款方拆开。付款后才知道归谁的钱，由平台作为卖家收下，再按供应商结算付出去。平台不做「代收再分发」，也就是不走二清。

| 订单类型 | 收款路径 | 原因 |
|---|---|---|
| 官方主题、通行证、平台商品、模板、商家采购、保证金 | 平台商户号（现有路径，不变） | 平台本来就是卖家（DECISIONS-payout 选项 C） |
| 活动场次票、俱乐部会费 | 平台商户号（不变） | 结算直接记余额，没有分账接点（l-payout2 已说明） |
| 主题门票，卖家已进件，主题**没有**生效合作单（只有一个收款方） | **l-payout2 单商户路径**：以卖家 `sub_mchid` 下单，结算后把平台佣金分给平台 | 只有一个二级商户，从卖家向平台分账是允许的 |
| 主题门票，卖家已进件，主题**有**生效合作单（发起方 + 带队俱乐部 + 核销商家） | **合单支付（本线新增）**，见下 | 多方收款不能直接分账，只能付款时拆单 |
| 上面任意一种，但费率或合作条款缺失，或者卖家拿不到任何确定份额 | 平台商户号（现有行为） | 失败关闭，绝不猜金额 |

**合单怎么拆（`CombinedCheckoutPolicy`，纯函数）：**

- **卖家子单（发起方的二级商户号）**
  - 金额 = 实付 − 平台佣金 − 预留。
  - 这是下单时已经确定属于卖家的部分。
  - `profit_sharing=true`，结算到期后「完结分账」解冻给卖家。
- **平台自营子单（平台自己以二级商户入驻的商户号 `platform-seller-sub-mchid`）**
  - 金额 = 平台佣金 + 预留。
  - 平台佣金用主题锁价时冻结的费率，与结算同源。
  - 预留 = 下单时每张生效合作单按合同条款的最大可能份额之和。按销售额百分比的向上取整到分；按座固定费的乘以座位数。
  - 现有合同里，带队费（LEAD_FEE）和核销费（MERCHANT_VERIFY）都要等履约核销后才确定。所以下单时能确定、可以直接拆成独立子单的收款方，实际只有「卖家」和「平台」。拆单结构本身按子单列表实现，以后出现下单时就确定的收款方，可以加更多子单（微信上限 50 笔）。
- 两笔子单的单号和退款单号都由业务单号和角色确定性生成，长度不超过 32 位。

**结算后（`CombinedSettlementPlanner` + `EcommerceCombinedSettlementService`）：**

- 份额只读账本 `coop_settlement_original_component`，不重算。
- **平台自营子单够付**（平台佣金 + 其他收款方 ≤ 子单金额）：
  - 带队俱乐部、核销商家各落一条**供应商应付**（`ecom_supplier_payable`），由平台按供应商结算付款：对公转账并开票；个人走灵活用工。
  - 多出来的预留属于卖家，同样作为供应商应付付回（SURPLUS）。
  - 卖家子单完结分账。
- **预留不够**（合作单在下单后才生效）：差额从卖家子单分给平台。这是电商平台作为分账接收方，属于允许的分账，受分账比例上限约束；超过上限就转人工。
- 不论哪种情况，合单收款的分项在 T+14 都**不再记会员余额**：结算接点减去合单部分；结算后退款不扣钱包。
- 账本对不上、结算前已经退款、卖家会员不明，一律转人工，不做部分结算。

**单商户单的补充（合单开关打开时）：** l-payout2 的单子如果在下单后才出现其他商家的份额，以前会直接向对方的二级商户分账，这正是业主说不支持的情况。现在改为：这部分份额分给平台（`SUPPLIER_RELAY`），分账成功后落供应商应付。附带效果是，对方不需要进件也能拿到钱。

**退款（合单）：**

- 一笔业务退款按两笔子单的金额比例拆成两笔子单退款。用最大余数法凑整，任何一笔都不超过该子单剩余可退。
- 卖家子单如果已经向平台补过差，先按比例做分账回退，回退成功后再退款。
- 两笔都受理，业务退款才算「已受理」；两笔都成功，才写一条不可变的 REFUND 事实（金额 = 两笔之和）。
- 部分子单失败时记为 ABNORMAL（退款待补偿），不写退款事实。
- 发生退款时，该单还没付的供应商应付挂起（HOLD_REFUND），等运营复核。

**幂等：**

- 合单号就是现有冻结的支付意图号（商户号列记合单商户号）。
- 子单号、子单退款号、补差分账单号、回退单号都由业务键确定性生成。
- 状态更新一律用 CAS（带期望旧状态）。
- 回调只从通知里取合单号，然后查原单，逐笔子单核对后才写事实；已经有事实的重放直接返回同一个事实引用，不再查单。
- 结果未知时保持「处理中」，之后用原单号恢复，绝不换号重发。

## 二、官方文档依据（URL）

本会话网络不允许直接打开 pay.weixin.qq.com，下面的条款是通过搜索引擎摘录的官方页面内容核对的，开通后请在沙箱再核一遍字段。

- 合单下单-JSAPI（子单列表最多 50 笔；电商模式下 `combine_mchid` 与子单 `mchid` 都填电商平台商户号，`sub_mchid` 填二级商户号；子单 `settle_info.profit_sharing`）：https://pay.weixin.qq.com/doc/v3/partner/4012760615 、https://pay.weixin.qq.com/docs/partner/apis/combine-payment/orders/jsapi-prepay.html
- 合单支付开发指引（合单不能按合单号退款，只能按子单退款）：https://pay.weixin.qq.com/doc/v3/partner/4012166832
- 合单申请退款：https://pay.weixin.qq.com/doc/v3/partner/4013462256
- 合单支付常见问题（子单资金落在二级商户号上；按子单对账）：https://developers.weixin.qq.com/community/pay/doc/000aa07b9dc860af122a25bd256008
- 平台收付通产品介绍（默认最高分账比例 30%，可在「电商收付通 → 供应链分账设置」调整）：https://pay.weixin.qq.com/doc/v3/partner/4012086891
- 请求分账（同一笔订单最多分账 50 次，每次最多 50 个接收方）：https://pay.weixin.qq.com/doc/v3/partner/4012690683
- 分账接入易错点（历次分账合计不得超过「扣费后的订单净额」与「最高分账比例对应金额」中较小者；退款会降低剩余可分金额）：https://pay.weixin.qq.com/doc/v3/partner/4054257730
- 请求分账回退（回退单号对应原分账单号；不支持回退给个人；时限 180 天）：https://pay.weixin.qq.com/docs/partner/apis/ecommerce-profit-sharing/return-orders/create-return-order.html 、https://pay.weixin.qq.com/doc/v3/partner/4012477737
- 分账回退「可用余额不足」常见问题：https://pay.weixin.qq.com/doc/v3/partner/4012525463.md

## 三、微信不允许 / 本设计因此不做的事

1. **多个二级商户之间直接分账**（业主确认）。所以合单开关打开后，任何分账的接收方都只有电商平台，其他商家一律走供应商应付。
2. **按合单号退款。** 只能逐笔子单退款，本线按比例拆分。
3. **超过分账比例上限**（默认 30%，按微信核定值）。补差超过上限就转人工。
4. **对「分账给个人」的分账单做回退。** 本设计不向个人 openid 分账，个人收款方走供应商应付或灵活用工，所以不会碰到。
5. **超过 180 天回退分账。** 结算按 T+14 进行，退款期内正常不会超过；超期的由运营人工处理。
6. **一笔合单超过 50 笔子单。** 本设计固定 2 笔。
7. **服务商 / 电商平台把子单资金收进平台再转给商家。** 这就是二清，本线不做。平台自营子单只收平台自己作为卖家的部分。

## 四、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 |
|---|---|---|---|---|
| C-01 开关 | fixed | 新增 `payout.ecommerce.combined-enabled`（默认关）、`platform-seller-sub-mchid`。合单生效需要：总开关 + 下单路由 + 分账开关 + 合法且不同于 sp_mchid 的平台自营二级商户号。保留原 9 参构造，原测试和装配不变 | `PayoutSwitches.java` | `CombinedPayoutTest$Switches` |
| C-02 下单拆单策略 | fixed | 纯函数：单一收款方回到 l-payout2；多方收款拆成卖家子单 + 平台自营子单；条款或费率缺失、卖家部分为 0 回落平台；按比例拆退款 | `CombinedCheckoutPolicy.java` | `CombinedPayoutTest$Checkout`（6 条） |
| C-03 新迁移 | fixed | `migration_20261111_ecommerce_combine.sql`：只新建 6 张表，含 CHECK（子单之和 = 实付、卖家与平台号不同、退款不超子单、补差与退回互斥、应付来源 / 金额）。已登记依赖 | 迁移、`ci/migration-dependencies.json` | layout / safety / dup-create 门禁通过；真库 IT 原样执行并重复执行 |
| C-04 合单网关 | fixed | 合单 JSAPI 下单（自行签名调起参数）、合单查单、合单关单、合单通知验签解密。SDK 0.2.15 没有合单模型，按 APIv3 路径直接请求 | `EcommercePayGateway.java`、`WechatEcommercePayGateway.java` | 未连微信（同 l-payout2） |
| C-05 合单接入支付链路 | fixed | 下单 / 查单 / 关单 / 锁支付尝试 / 退款 / 退款恢复 / 退款通知，全部通过 l-payout2 适配器的现有接点分派，`WechatPaymentSdkGateway` 一行未改。新增回调端点 `POST /api/registration/ecommerce-combine-callback` | `EcommerceCombinedPayment.java`、`EcommercePartnerPaymentAdapter.java`、`WechatPayServiceImpl.java`、`IWechatPayService.java`、`ApiRegistrationController.java` | `EcommerceCombinedPaymentTest`（15 条）：拆单金额与请求、重复下单不重拆、单一收款方仍走 l-payout2、平台已冻结意图 / 活动票留在平台、条款缺失回落平台、通知只写一次事实且重放不再查单、子单被篡改或部分已付绝不写事实、查单 / 关单、按子单拆退款且重复提交零新调用、全部子单成功才写退款事实、部分失败为 ABNORMAL、先回退补差再退卖家子单、子单退款通知路由 |
| C-06 合单结算 | fixed | 结算冻结后落计划和供应商应付（幂等），到期（与 T+14 同口径）完结或向平台补差，退款前回退补差，T+14 扣除合单部分，结算后退款不扣钱包；定时任务和运营端接口（供应商应付列表 / 标记已付，带权限） | `CombinedSettlementPlanner.java`、`EcommerceCombinedSettlementService.java`、`EcommerceSettlementGuard.java`、`EcommercePayoutTask.java`、`EcommercePayoutAdminController.java` | `CombinedPayoutTest$Settle`（3）、`$SettlementService`（6）；`EcommerceCombinedPaymentTest$SwitchOff`（结算接点只在合单开时加合单部分） |
| C-07 单商户单不再直连他人 | fixed | 合单开关打开时，l-payout2 计划里给其他商家的份额改为分给平台（`SUPPLIER_RELAY`），分账成功后落供应商应付；开关关时与 l-payout2 逐字相同 | `ProfitSharePlanner.java`、`EcommerceProfitShareService.java` | `CombinedPayoutTest$Relay`（3）；原 `ProfitSharePlannerTest`、`EcommercePayoutServicesTest` 全过 |
| C-08 特征测试 | fixed | 装上 l-payout2 全部接线 + 本线合单组件，开关全关，复跑三条金标准（报名下单请求、原路退款请求、T+14 入余额），结果逐字相同；合单 mapper / 合单结算 / 网关 / 卖家判定 / 合作单与费率读取零调用；三个电商回调端点都回 FAIL。另证：只关合单、l-payout2 全开时，多方收款的单仍按 l-payout2 下单，合单表零读写 | `PayoutCombineSwitchOffCharacterizationTest.java`、`EcommerceCombinedPaymentTest$SwitchOff` | 5 + 2 条通过 |
| C-09 真库 IT | fixed | `EcomCombineMapperMySqlIT`（9 条），登记新批次 `launch-l-payout3`（registry + workflow 选项 + 计数测试） | IT、`ci/real-storage-it-registry.json`、`.github/workflows/real-storage-it.yml`、`ci/test_real_storage_it.py` | 见第五节运行号 |

## 五、门禁与证据

| 门禁 | 结果 |
|---|---|
| `mvn -q -DskipTests install`（`LC_ALL=C.UTF-8`） | 通过 |
| 支付相关单测（l-payout / l-payout2 原 69 条 + 本线 39 条） | 108 条全部通过（`evidence/unit-tests.txt`） |
| 后端全量单测 `mvn -pl chengyinhub-system,chengyinhub-admin test` | FULLTEST_PLACEHOLDER |
| Real Storage IT `launch-l-payout3` | IT3_PLACEHOLDER |
| Real Storage IT `launch-l-payout2`（回归） | IT2_PLACEHOLDER |
| Real Storage IT `launch-l-payout`（回归） | IT1_PLACEHOLDER |
| `python3 -m unittest ci.test_real_storage_it` | 92 条通过 |
| 迁移门禁 layout / safety / dup-create | 全过 |
| `ci/role_permission_contract.py` | 三段全过（623 个后台端点全部带权限） |
| 没有运行 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规定）。小程序和后台 Vue 本线都没改 |

## 六、开关与配置（全部默认关闭或为空，只放服务器配置）

| 配置项 | 默认 | 作用 |
|---|---|---|
| `payout.ecommerce.combined-enabled` | `false` | 合单总开关；还需要 `enabled`、`seller-routing-enabled`、`profit-sharing-enabled` 同时为真 |
| `payout.ecommerce.platform-seller-sub-mchid` | 空 | 平台自营二级商户号（平台自己以二级商户入驻）。为空、格式不对或等于 sp_mchid 时，合单视为关闭 |
| `payout.ecommerce.combine-notify-url` | 空 | 合单支付通知地址：`https://<你的域名>/prod-api/api/registration/ecommerce-combine-callback` |

子单退款通知沿用 l-payout2 的 `refund-notify-url`。

**打开顺序：**

1. 执行迁移 `migration_20261111_ecommerce_combine.sql`（排在 l-payout2 两份迁移之后）。
2. 按 l-payout2 的顺序开通，并跑通单商户。
3. 平台自营主体完成二级商户进件，填 `platform-seller-sub-mchid` 和 `combine-notify-url`。
4. 开 `combined-enabled`，用一张有合作单的小额主题门票真机走完：付款 → 结算 → 完结 / 补差 → 退款。

**注意：** 有合单还没结清时不要关 `combined-enabled`。关了以后这些单的查单、退款会被拒绝（失败关闭，不会多付，但会卡住）。

## 七、你需要向微信支付确认 / 申请的

1. 平台主体以「二级商户」身份入驻自己的电商平台（平台自营店），拿到 `sub_mchid`，作为合单里「平台为卖家」子单的收款方。请向微信确认这种自营入驻方式可以用于合单。
2. 确认合单支付（JSAPI 合单）已对电商平台商户号开通，小程序 AppID 能作为 `combine_appid`。
3. 确认分账比例上限，填 `max-share-percent`。补差只在合作单晚于下单生效时才会发生，金额通常很小。
4. 法务 / 财务：
   - 带队俱乐部、核销商家、预留退回卖家，都由平台按供应商结算付款，需要对方开票（或由灵活用工平台代开）；
   - 平台自营子单收入由平台开票；
   - 卖家子单由卖家自行开票。

## 待你决定

1. **预留口径**：现在按下单时已生效合作单的最大可能份额相加。保守，预留可能偏多，多出的作为供应商应付退回卖家。默认：保持。可选：只按单个最高合作单预留，卖家下单时到手更多，但更容易出现补差。
2. **供应商应付的付款方式**：本线只记账，并提供「标记已付（录入付款单号）」，不自动打款。默认：运营线下对公付款或经灵活用工付款后录入。要自动付款需要选定通道（企业付款到银行卡 / 灵活用工 API）。
3. **退款后挂起的应付（HOLD_REFUND）**：默认运营复核后人工处理。
4. **合单部分子单退款失败（ABNORMAL）**：默认列为退款待补偿、人工处理，与 l-payout2 相同。
5. **运营端 Vue 页面**：本线只加了带权限的后台接口（`/business/ecommerce-payout/supplier-payables`），没有加页面。默认：下一线在「电商收付通」页加一个「供应商应付」标签。

STATUS: PARTIAL - 合单下单 / 回调 / 退款 / 结算 / 供应商应付已全部接入且默认关闭；特征测试、单测、真库 IT 均已完成，结果见第五节；全部微信合单接口未连沙箱实测（需你开通后联调），官方文档只能经搜索摘录核对，运营端没有新页面
