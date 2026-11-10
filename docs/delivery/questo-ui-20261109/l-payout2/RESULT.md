# l-payout2 结果：把电商收付通接进现有支付链路（全部默认关闭）

分支 `claude/l-payout2-20261110`，从 `origin/claude/l-payout-20261110`（`a03b4ea4`）切出，开 PR 前已合入最新 `origin/launch/questo-20261110`。

依据：LAUNCH.md 共享规则与 Money 规则、`proto/DECISIONS-payout.md`、BRIEF.md 硬限制、l-payout 的 RESULT.md（其 P-09 即本线任务）、根目录 AGENTS.md。业主已授权本线把新收款路径接进支付链路。

## 一、做法

- **开关全关 = 逐字不变。** 每个接点都写成「新组件存在 且 `payout.ecommerce.enabled` 为真」才进入新分支；为假时不读新表、不调微信、不改任何返回值。用接线前后同一组特征测试证明（见第三节）。
- **内部账本仍是份额唯一来源。** 分账金额只读结算冻结的 `coop_settlement_original_component`；本线不重算任何份额。
- **与平台商户号共用同一套不可变证据。** 卖家单的支付意图照样冻结在 `wx_pay_order_intent`（商户号列记 `sub_mchid`），支付成功照样写 `wx_verified_payment_fact`；领域回调、退款都只认事实引用，所以报名状态机、退款单状态机一行没改。
- **只接主题门票。** 活动场次票、俱乐部会费的结算是直接记会员余额，没有分账接点，本期一律走平台商户号（`SellerOfRecordPolicy` 仍判定它们，但下单接点只对 `owner_type=1` 生效）。
- **已开始的支付不换收款主体。** 同一报名单在平台商户号下冻结过意图就一直走平台；登记过卖家收款单就一直走卖家。

## 二、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 风险 |
|---|---|---|---|---|---|
| W-00 接线前特征测试 | fixed | 三条金标准：报名 JSAPI 预下单请求与调起参数、原路退款请求与受理结果、T+14 到期入余额（金额 / 流水 / 终态）。接线前在基线 `a03b4ea4` 跑一次并留档 | `PayoutSwitchOffCharacterizationTest.java`、`l-payout2/evidence/characterization-before.txt` | 基线：现有 21 类支付 / 退款 / 结算测试 192 条 + 金标准 3 条全过 | — |
| W-01 新迁移 | fixed | `migration_20261110_ecommerce_payout_wiring.sql`：新表 `ecom_refund`（卖家单退款：先回退后退款的进度）；两条**暂停状态**的定时任务；后台菜单「资金售后 → 电商收付通」+ 操作按钮权限，**零角色授权**。已登记依赖 `20261110_ecommerce_payout` | 迁移、`ci/migration-dependencies.json` | `migration-layout-check.sh`、`migration-safety-check.sh`、`dup_create_table_gate.py` 全过；真库 IT 执行两份迁移并重复执行一次（幂等） | 生产库未执行（由你执行） |
| W-02 下单按卖家路由 | fixed | `WechatPaymentSdkGateway.createOrder` 开头：开关打开时交给 `EcommercePartnerPaymentAdapter.createOrder`；主题门票 + 卖家已完成进件 + 路由开关打开 → 以 `sub_mchid` 冻结意图、写 `ecom_order_seller`、服务商 JSAPI 下单（`profit_sharing=true`）；其余返回 null 走原路径 | `WechatPaymentSdkGateway.java`、`EcommercePartnerPaymentAdapter.java`、`WechatPaymentIntentStore.java`（+`hasAnyAttempt` 只读方法） | `EcommercePartnerPaymentAdapterTest$PlaceOrder`（3 条）：已进件走卖家；未进件 / 活动票 / 平台已冻结意图走平台；路由开关关不判定 | 未连微信；App 支付通道不做卖家路由（卖家单若改走 App 会被意图冲突挡住，属失败关闭） |
| W-03 查单 / 关单 / 锁支付尝试 | fixed | `queryOrderStatus`、`closeOrder`、`closePaymentAttempt`、`lockPaymentAttemptForUpdate`、`lockClosablePaymentAttemptForUpdate`：卖家单改用服务商查单 / 关单与 `sub_mchid`；原单逐字段核对（sp_mchid、sub_mchid、单号、appid、金额、冻结意图）后才写事实 | 同上 | `lockAndCloseUseTheSellerMerchantNumber`；`queryOrderStatus` 经回调用例覆盖 | 主动查单与超时关单任务对卖家单走新分支，未连微信实测 |
| W-04 支付回调 | fixed | 新端点 `POST /api/registration/ecommerce-callback`（`@Anonymous`）。开关关：直接回 FAIL。开关开：电商平台 APIv3 验签 → 按原 sub_mchid / 单号查原单逐字段核对（含成功时间）→ 写不可变 PAYMENT 事实、绑定 `transaction_id`（记「待分账」）→ 交给现有 `WechatPaymentCallbackRouter`；已有事实时重放不再查单 | `ApiRegistrationController.java`、`IWechatPayService.java`、`WechatPayServiceImpl.java`、`WechatEcommercePayGateway.java` | `Callback`（2 条）：同一通知两次只写一次事实、两次返回同一事实引用；外来 sp_mchid / 金额不符 / 未登记卖家一律拒绝且不写事实；开关关回 FAIL 零调用 | 通知解密字段名以 SDK `partnerpayments` 模型为准，需沙箱核对 |
| W-05 退款前回退分账 | fixed | `submitRefund(原支付事实…)`：卖家单 → 写 `ecom_refund`（退款单号幂等）→ 按退款比例回退已分出的分账 → **全部回退成功**才申请电商退款。回退处理中 / 失败 → 返回 UNKNOWN，原退款单保持处理中；之后原退款单号的恢复查证 `queryRefundFacts` 会继续回退并提交，**绝不换号重发**。电商退款结果未知 → 停在 SUBMITTING，重试用同一单号。微信退款 CLOSED / ABNORMAL → 记为「退款待补偿」 | `WechatPaymentSdkGateway.java`、`EcommercePartnerPaymentAdapter.java`、`EcommerceProfitShareService.java` | `Refund`（6 条）：先回退后退款且重复提交零新调用；回退处理中不退款、恢复查证同号完成；回退失败挡住退款、运营重试回退后恢复；提交超时同号重试且不二次回退；退款异常不计入累计、累计超实付拒绝；未分账直接退款 | 补偿（退款失败后已回退的钱在卖家手里）只能人工处理，见待你决定 4 |
| W-06 退款回调 | fixed | 新端点 `POST /api/registration/ecommerce-refund-callback`：只取退款单号定位，事实一律以原支付事实为界重新查电商退款单；SUCCESS 写不可变 REFUND 事实后交给现有退款回调路由 | 同 W-04 | `failedReturnBlocksRefundUntilRetriedReturnSucceeds` 覆盖 SUCCESS 写事实 | 同上 |
| W-07 结算点落分账计划 | fixed | `executeTopicSettlement` 每个主题事务提交后调 `EcommerceSettlementGuard.afterTopicSettled` → `planTopic`（幂等）；失败只记日志，定时任务兜底补齐。结算前已退款的卖家单直接转人工（`REFUNDED_BEFORE_SHARE`） | `CmsTopicServiceImpl.java`、`EcommerceSettlementGuard.java`、`EcommerceProfitShareService.java` | `plannedSplitIsFrozenToManualWhenRefundComesFirst`；真库 IT 补计划 SQL | — |
| W-08 分账在 T+14 同一时点提交 | fixed | `submitPlanned` 只提交「分账单涉及的每张结算头都已到 `payable_time`、主题无未结客诉」的计划（与 T+14 入余额同口径），不再一结算就分 | `EcomPayoutMapper.xml`（`selectDuePlannedShareOrders`） | 真库 IT：一张头未到期 / 有未结客诉 / 头已删除都不提交；l-payout 端到端 IT 加了未到期不提交 | — |
| W-09 T+14 不再给卖家单份额记余额 | fixed | `payoutOneDueCoopSettlement`：开关打开时，结算头净额减去「由二级商户收款的分项（原始份额 + 原始退款调整）」后再记余额；整头都是卖家单时按 0 元关账；减出负数失败关闭不记账 | `CmsTopicServiceImpl.java`、`EcomPayoutMapper.xml`（`sumPartnerPortionForSettlement`） | `EcommerceSettlementWiringTest`（4 条）：混合头只记平台部分；全卖家头 0 元关账；超额失败；开关关不读；真库 IT 只计事实商户号 = 登记 sub_mchid 的分项 | — |
| W-10 结算后退款不扣会员钱包 | fixed | `CoopSettlementOriginalLedger`：已打款结算头上的卖家单分项退款，按「未打款」冲回（不扣钱包、不记欠款），钱由分账回退从微信侧追回，避免双重追回 | `CoopSettlementOriginalLedger.java` | 开关关时零读取（特征测试 + 全量单测）；开关开时依赖 `countPartnerPaymentFact`（真库 IT 覆盖） | 账本冲回这一支没有单独的开关打开单测（构造成本高），已列入待你决定 6 |
| W-11 定时任务 | fixed | `ecommercePayoutTask.shareAndRetry()`：补计划 → 提交到期 → 查分账 → 查回退；`ecommercePayoutTask.syncOnboarding()`：刷新审核中进件。开关关直接返回；失败先告警再抛 | `web/task/EcommercePayoutTask.java`、迁移 | `EcommercePayoutTaskTest`（2 条） | 迁移以暂停状态写入，需手动启用 |
| W-12 运营端页面 | fixed | 新页「资金售后 → 电商收付通」四个标签：进件审核（登记、刷新、同步）、分账记录（展开明细、提交到期、查询处理中）、回退与退款、失败重试（转人工分账「重新计划」、失败回退「原单号重试」、退款待补偿）；顶部只读显示三个开关状态。后台接口全部 `@PreAuthorize` | `chengyinhub-ui/src/views/business/finance/ecommerce-payout/index.vue`、`chengyinhub-ui/src/api/business/ecommercePayout.js`、`EcommercePayoutAdminController.java`、`EcommercePayoutQueryService.java` | `npm run build:prod` 见第三节；`role_permission_contract.py` 三段全过（619 个后台端点全部带权限） | 未在浏览器实拍（本线没有起后台的 rig）；「重新计划」只对从未提交微信、原因属于计划阶段、且无任何退款的人工单出现 |
| W-13 真库 IT | fixed | 新类 `EcomPayoutWiringMySqlIT`（6 条），登记新批次 `launch-l-payout2`；`EcomPayoutMapperMySqlIT` 随接线补表并新增「未到期不提交」断言 | 两个 IT、`ci/real-storage-it-registry.json`、`.github/workflows/real-storage-it.yml`、`ci/test_real_storage_it.py` | 见第三节运行号 | — |

## 三、门禁与证据

| 门禁 | 结果 |
|---|---|
| 接线前特征测试（基线 `a03b4ea4`） | 21 类 192 条 + 金标准 3 条全过（`evidence/characterization-before.txt`） |
| 接线后同一组特征测试 + 开关全关接线复跑 | 200 条全过：同 192 条 + 金标准 3 条 + 装上接线复跑金标准 3 条（附加断言：新 mapper / 网关 / 卖家判定 / 分账服务 / 意图与事实存储零调用）+ 回调与结算接点开关关零调用 2 条（`evidence/characterization-after.txt`） |
| 本线单测 | 69 条全过：l-payout 原 42 条 + 适配器 13 + 结算接线 4 + 定时任务 2 + 特征 8 |
| 后端全量单测 `mvn -pl chengyinhub-system,chengyinhub-admin test` | 13113 条：15 个类未过，均与本线无关——14 个 `*.it.*MyBatisTest` 本容器连不上 MySQL；1 条 `GameplaySupportMatrixTest` 玩法目录计数 32≠33（本线未改玩法文件，基线已有）。明细 `evidence/full-test.txt` |
| 合入最新 `launch/questo-20261110`（`50135d56e`）后复跑 | 21 类特征测试 + 金标准 + 本线单测共 298 条，297 过，仅上面那条玩法目录计数；`mvn -DskipTests install` 通过；冲突只在 `ci/test_real_storage_it.py` 的批次计数（两边都保留：l-pass 236、l-payout 9、l-payout2 6） |
| Real Storage IT `launch-l-payout2` | **success**，run [42869274914](https://github.com/liboyang42-cpu/hengyin/actions/runs/42869274914) @ `2a645f80ae33`（6/6）；首跑 run 42866716453 @ `d4d229202c37` 亦 success |
| Real Storage IT `launch-l-payout`（回归） | **success**，run [42869269715](https://github.com/liboyang42-cpu/hengyin/actions/runs/42869269715) @ `2a645f80ae33`（9/9）。首跑 run 42866728872 失败 1 条：分账计划开始读 `ecom_refund`、提交改按结算头到期，旧 IT 的库里没有这些表；已补表并新增「未到期不提交」断言后通过 |
| `python3 -m unittest ci.test_real_storage_it` | 92 条通过 |
| 迁移门禁 | layout / safety / dup-create 全过 |
| `ci/role_permission_contract.py` | 三段全过 |
| 后台 `npm run build:prod` | 通过（lockfile 指向的 npmmirror 在本会话被拦，用 `--replace-registry-host` 改走 npmjs 安装同一锁定版本） |
| 没有运行 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规定）；小程序本线未改 |

## 四、开关清单（全部默认关闭；只能放服务器环境变量 / 配置，不进仓库）

| 配置项 | 默认 | 作用 |
|---|---|---|
| `payout.ecommerce.enabled` | `false` | 总开关。为假时所有接点不进入新分支；两个电商回调端点直接回 FAIL |
| `payout.ecommerce.sp-mchid` | 空 | 电商平台商户号。为空时总开关视为关 |
| `payout.ecommerce.sp-appid` | 空 | 关联到电商平台商户号的**小程序 AppID**（服务商 JSAPI 用 sp_openid，必须就是现在小程序的 AppID）。为空时路由开关视为关 |
| `payout.ecommerce.seller-routing-enabled` | `false` | 主题门票按卖家 `sub_mchid` 下单（需总开关） |
| `payout.ecommerce.profit-sharing-enabled` | `false` | 结算后落分账计划、到期提交分账、退款前回退分账、定时任务（需总开关） |
| `payout.ecommerce.platform-receiver-mchid` | 空（= sp_mchid） | 平台佣金的分账接收商户号 |
| `payout.ecommerce.max-share-percent` | `30` | 分账比例上限，按微信核定值填 |
| `payout.ecommerce.private-key-path` | 空 | 电商平台商户 API 证书私钥文件路径 |
| `payout.ecommerce.merchant-serial-number` | 空 | API 证书序列号 |
| `payout.ecommerce.api-v3-key` | 空 | APIv3 密钥（也用于两个回调的验签解密） |
| `payout.ecommerce.notify-url` | 空 | 支付通知地址，指向 `https://<你的域名>/prod-api/api/registration/ecommerce-callback` |
| `payout.ecommerce.refund-notify-url` | 空 | 退款通知地址，指向 `https://<你的域名>/prod-api/api/registration/ecommerce-refund-callback` |
| `payout.creator.enabled` / `payout.creator.provider` | `false` / `none` | 创作者灵活用工打款（l-payout，未接任何服务商） |
| 定时任务 `ecommercePayoutTask.shareAndRetry()`、`ecommercePayoutTask.syncOnboarding()` | 暂停 | 后台「定时任务」里手动启用 |

**打开顺序（建议）：** 执行两份迁移 → 配齐凭据与回调地址、只开 `enabled` → 运营登记并提交进件、启用 `syncOnboarding` → 至少一个商家「已开通」后开 `seller-routing-enabled`，用一笔小额主题门票真机走完付款与退款 → 开 `profit-sharing-enabled`、启用 `shareAndRetry`。**任何卖家单还没结清时不要关 `enabled`**：关了以后这些单的查单、退款会回到平台商户号路径而查不到（失败关闭，不会多付，但会卡住）。

## 五、你要在微信支付做的事

1. 以公司主体在微信支付服务商平台申请成为**电商平台**，开通「电商收付通」，拿到电商平台商户号 → `sp-mchid`。准备营业执照、法人证件、对公账户、已上线小程序与 ICP 备案、平台交易规则与二级商户入驻协议、结算周期说明。
2. 在服务商平台把**现在的小程序 AppID** 关联到电商平台商户号 → `sp-appid`。
3. 在电商平台商户号下申请 API 证书（私钥文件 + 序列号）、设置 APIv3 密钥 → `private-key-path`、`merchant-serial-number`、`api-v3-key`，只放服务器。
4. 开通「分账」，确认分账比例上限 → `max-share-percent`；确认平台佣金接收商户号（默认就是电商平台商户号）。
5. 在商户平台把支付通知 / 退款通知地址配成第四节两个回调地址（也可以只靠下单 / 退款请求里带的地址）。
6. 每个直接收款的商家 / 俱乐部：运营在后台「电商收付通 → 进件审核」登记，再按微信进件字段提交资料（`POST /business/ecommerce-payout/sub-merchant/{id}/submit`）；超级管理员按小程序「收款账户」页复制的链接完成验证 / 签约，到「已开通」即有 `sub_mchid`。
7. 上线前在沙箱或测试二级商户上核对：服务商 JSAPI 下单 / 查单 / 关单、支付通知解密字段、电商退款查询与退款通知字段、进件两个接口与敏感字段加密（本会话无法访问微信官方文档，字段以 SDK 0.2.15 模型为准）。
8. 法务 / 税务：平台佣金平台开票、二级商户自行开票；「退款先回退分账」写进商家协议；官方主题与通行证仍由平台为卖家。

## 六、Cross-lane

- **qx-final：** 部署顺序 `migration_20261110_ecommerce_payout.sql` → `migration_20261110_ecommerce_payout_wiring.sql`；部署清单写上第四节开关（全部保持关闭）与两条暂停任务；Real Storage IT 增加批次 `launch-l-payout2`。
- **l-pass：** 通行证仍固定平台卖家，不受影响。
- **共享文件：** `real-storage-it.yml` +1 个批次选项；`ApiRegistrationController` +2 个回调端点；`WechatPaymentSdkGateway` / `CmsTopicServiceImpl` / `CoopSettlementOriginalLedger` 各加了开关判定的接点。

## 待你决定

1. **个人收款方（发起人会员、俱乐部主理人个人）没有进件主体**时，这一单整单转人工，钱冻结在卖家二级商户里不动。默认：转人工，运营在「失败重试」里等对方进件后点「重新计划」。可选：改用微信「分账到个人 openid」，需你确认合规。
2. **分账比例上限 30%**：协作主题平台佣金 + 核销费可能超过，超过时整单转人工。请按微信核定值改 `max-share-percent`。
3. **活动场次票、俱乐部会费**本期不按卖家收款（它们的结算直接记余额，没有分账接点）。默认：继续走平台。要接的话需要给活动结算加同样的接点。
4. **退款失败后的补偿**：回退已经成功、微信退款却 CLOSED / ABNORMAL 时，钱回到了卖家二级商户，分账单已完结不能再分。默认：列在「退款待补偿」，由运营人工处理（重新发起退款或线下结算）。
5. **结算前已发生部分退款的卖家单**：默认整单转人工（`REFUNDED_BEFORE_SHARE`），不能「重新计划」，需人工按退款后的账本处理。
6. **结算后退款冲回的开关打开分支**（W-10）只有真库 SQL 与全量回归覆盖，没有单独的端到端单测。默认：保持现状，真机联调时专门验一笔「结算后退款」。
7. **定时任务以暂停状态写入**。默认：开通后由你手动启用；不想手动的话可以改成迁移直接启用（开关关时任务本身是空操作）。

STATUS: PARTIAL - 下单 / 回调 / 退款 / 结算 / 定时任务 / 运营端页面已全部接入且默认关闭，特征测试、单测、真库 IT、后台构建均通过；运营端新页没有浏览器实拍截图（本线没有可起后台的 rig），全部微信接口未连沙箱实测（需你开通电商收付通后联调）
