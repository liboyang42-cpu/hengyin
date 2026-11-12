# l-payout 结果：商家 / 俱乐部 / 创作者收款，不走「二清」

分支 `claude/l-payout-20261110`，从 `origin/launch/questo-20261110` 的 `ca1f85b68` 切出（qx-base 已写 `STATUS: COMPLETE`）。

依据：LAUNCH.md 的 l-payout 段和共享规则（含 Money 规则）、`proto/DECISIONS-payout.md`、BRIEF.md 硬限制、根目录 AGENTS.md。

## 一、做法

- **内部账本仍是份额的唯一来源。** 分账金额只读 `coop_settlement_original_component`，也就是现有结算对每笔付款、每条结算行冻结下来的份额。新代码不重算任何份额。
- **谁是卖家：**
  - 官方主题、通行证、平台商品、玩法模板、商家采购、协作保证金：一律由平台当卖家，走现有平台商户号。
  - 商家或俱乐部卖出的门票（主题报名、活动报名）和俱乐部会费：卖家完成「二级商户进件」并拿到 `sub_mchid`、路由开关也打开时，才判定为用卖家的 `sub_mchid` 下单。
  - 判定为纯函数 `SellerOfRecordPolicy`。任何不确定都回落到平台，也就是现有行为。
- **分账与退款：**
  - 结算冻结后，对每笔以二级商户为卖家的已付单生成一条分账计划。平台佣金分给平台商户号，其他已进件的商家 / 俱乐部分到它们自己的 `sub_mchid`，卖家本人的份额留在卖家。
  - 下面几种情况整单转人工，不做部分分账：有接收方还没进件、账本份额之和不等于实付、超过分账比例上限、同一会员对应两个进件主体。
  - 退款前，先按退款比例从每个接收方回退已经分出的钱。全部回退成功后，调用方才可以申请退款。
- **创作者：** 走可插拔接口 `CreatorPayoutProvider`（持牌灵活用工平台）。默认服务商是 `none`，即禁用。
- **开关：** 所有会动钱的路径都在开关之后，默认全部关闭。开关关闭时不读新表、不调微信，现有下单 / 退款 / 结算结果完全不变。
- **幂等与归属：**
  - 每个外部单号都按业务确定性生成：分账单号 `out_order_no`、回退单号 `out_return_no`、进件申请号 `out_request_no`、打款单号 `out_payout_no`。
  - 库里状态一律按「期望旧状态」做 CAS 更新。先在库里抢占，再在事务外调微信；调用结果未知时保持「处理中」，之后按单号查证。
  - 商家端只读接口的商家 id 只取自登录身份，并且要求有 `FINANCE_READ` 权限。

## 二、条目

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| P-01 资金开关与表结构 | fixed | 新增迁移（只加 5 张新表：二级商户进件、按卖家下单记录、分账单、分账明细、分账回退）和 `creator_payout_request`，已登记依赖。`PayoutSwitches` 汇总全部开关，默认全关 | `chengyinhub-admin/sql/migration_20261110_ecommerce_payout.sql`、`ci/migration-dependencies.json`、`payout/PayoutSwitches.java`、`domain/Ecom*.java`、`mapper/EcomPayoutMapper.java(.xml)` | `migration-layout-check.sh`、`migration-safety-check.sh`、`dup_create_table_gate.py` 全过；真库 IT 原样执行迁移 | 生产库未执行（按规定由你执行） |
| P-02 卖家判定 | fixed（判定）／ not done（接入下单，见 P-09） | `SellerOfRecordPolicy` 与 `EcommerceSellerResolver`：报名单 → 主题 / 活动 → 俱乐部或已通过审核的商家 → 进件状态 | `payout/SellerOfRecordPolicy.java`、`payout/EcommerceSellerResolver.java` | `SellerOfRecordPolicyTest`（6 条）、`EcommerceSellerResolverTest`（5 条）：开关关闭时零读取；官方主题、通行证、平台商品固定由平台卖；未进件、冻结、商户号非法都回落平台 | — |
| P-03 按账本分账 | fixed（运营端触发） | `ProfitSharePlanner` 由账本推出接收方并合并同一商户号；`EcommerceProfitShareService` 计划 → 抢占 → 请求分账（`finish=true`）→ 查询推进；无可分份额时直接「完结分账」解冻给卖家 | `payout/ProfitSharePlanner.java`、`payout/EcommerceProfitShareService.java`、`payout/WechatEcommercePayGateway.java` | `ProfitSharePlannerTest`（11 条）、`EcommercePayoutServicesTest$ProfitShare`：计划幂等、超时保持处理中、接收方关闭转人工、未进件或歧义转人工且绝不提交 | 未连微信（无商户号与证书） |
| P-04 退款前回退分账 | fixed（服务）／ not done（接入退款主流程，见 P-09） | `returnForRefund`：按比例回退，多次部分退款累计精确；同一退款单号对同一接收方只建一笔；还没分账时冻结计划（`REFUNDED_BEFORE_SHARE`）；开关关闭但已有分账单时返回「处理中」，挡住退款 | 同上 | 单测：全额 / 部分 / 失败 / 处理中 / 幂等 / 先退后分；真库 IT 端到端：4000 + 6000 两次退款回退 1000 + 1500 = 2500 分，并按商户号核对 | 同上 |
| P-05 二级商户进件 | fixed | 登记（幂等，每个商家 / 俱乐部一条）、提交资料（敏感字段服务端用微信平台公钥加密，不落库）、刷新状态（只向前推进；FINISH 必须带 `sub_mchid`，且只写一次）、批量刷新 | `payout/EcommerceSubMerchantService.java`、`payout/ApplymentState.java`、`web/controller/business/EcommercePayoutAdminController.java` | `EcommercePayoutServicesTest$SubMerchant`（5 条）；真库 IT 覆盖唯一键、CHECK、CAS、`sub_mchid` 只写一次 | 未连微信 |
| P-06 商家「收款账户」页 | fixed | 新页 `pages/merchant/payout-account`（浅色商家域，结构照「核销记录」）。商家首页「更多」里加「收款账户」，只在 `canReadFinance` 时出现。页面显示服务端事实：未开放 / 未开通 / 各进件状态 / 驳回原因（原样显示微信描述）/ 掩码商户号；待签约或待验证时才出现「复制签约 / 验证链接」 | `chengyinhub-xcx/pages/merchant/payout-account/*`、`pages/merchant/index/index.{wxml,js}`、`app.json`（商家分包 +1 页）、`web/controller/api/ApiMerchantPayoutAccountController.java` | `merchant-payout-account.test.js`（6 条）；rig 截图 PA-01 前后、PA-02 ～ PA-07；`UI-GATE-0:PASS`；route-matrix 与台账已重建 | 剪贴板复制、签约链接在微信内打开 |
| P-07 创作者打款接口 | fixed（接口，默认禁用） | `CreatorPayoutProvider` + `DisabledCreatorPayoutProvider`（`none`）+ `CreatorPayoutService`：按「来源 + 创作者」幂等建单，同一来源金额不一致时拒绝，结果未知时保持「已请求」 | `payout/CreatorPayout*.java`、`DisabledCreatorPayoutProvider.java` | `EcommercePayoutServicesTest$Creator`（3 条）；真库 IT 覆盖唯一键与 CAS | 没有接入任何真实服务商 |
| P-08 真库 IT | fixed | `EcomPayoutMapperMySqlIT`（9 条）登记为新批次 `launch-l-payout`（registry + workflow 选项 + 计数测试） | `chengyinhub-admin/src/test/.../payout/EcomPayoutMapperMySqlIT.java`、`ci/real-storage-it-registry.json`、`.github/workflows/real-storage-it.yml`、`ci/test_real_storage_it.py` | Real Storage IT run [42842384570](https://github.com/liboyang42-cpu/hengyin/actions/runs/42842384570) 在 `ffe1dfbaa` **success**（之后的提交没有改 mapper、迁移和 IT）；`python3 -m unittest ci.test_real_storage_it` 92 条通过 | — |
| P-09 接入现有支付链路（下单 / 回调 / 退款 / T+14 入余额） | not done | 没有改 `WechatPaymentSdkGateway.createOrder`、回调事实闸门和 `RefundServiceImpl`。原因见下 | — | — | — |

### P-09 为什么没接，接的时候要做什么

现有支付事实链路从头到尾绑定单一商户号：

- `wx_pay_order_intent` 冻结单据、`verifiedPaymentFacts`、关单、查单、`submitRefund` 都要求 `mchid ==` 平台凭据商户号。
- 回调解析的是直连 `Transaction`。而服务商 / 电商单的通知是 `sp_mchid` + `sub_mchid` 结构。

如果只在下单处改用 `sub_mchid`，玩家付了钱，回调会被事实闸门拒掉，订单永远不会变成已付。这比不接更糟。所以 `seller-routing-enabled` 打开后目前没有任何效果，收款账户页也不再宣称「已按卖家收款」。

接入需要四步（建议由支付链路原 owner 做）：

1. **下单：** 在 `createOrder(registration)` 先调 `EcommerceSellerResolver.decide`。判定为二级商户时：
   - 按 `mchid=sub_mchid` 冻结 intent；
   - 走 `EcommercePayGateway.prepayJsapi`（服务商 JSAPI，`settle_info.profit_sharing=true`）；
   - 写 `ecom_order_seller`。
2. **回调：** 新增电商通知入口（`payout.ecommerce.notify-url`），用服务商查单接口核对后：
   - 以 `mchid=sub_mchid` 记 `wx_verified_payment_fact`；
   - `bindOrderSellerTransaction`；
   - 交给现有 `WechatPaymentCallbackRouter`。
3. **退款：** 在 `submitRefund(reference…)` 里，如果原支付事实的 `mchid` 是已登记的 `sub_mchid`：
   - 先调 `returnForRefund`，只有结果是 `ALL_RETURNED` 或 `NOT_APPLICABLE` 才继续；
   - 再走 `EcommercePayGateway.createRefund`（电商退款）。
4. **结算：**
   - 主题结算冻结后调 `planTopic(topicId)`，再由定时任务调 `submitPlanned` / `syncProcessing`。需要新增 `sys_job` 迁移；现在只能在运营端手动触发。
   - 已经由微信分给对方的结算行，T+14 入余额时必须跳过（`payoutOneDueCoopSettlement` 加判定），否则会重复付钱。

## 三、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -q -DskipTests install`（`LC_ALL=C.UTF-8`） | 通过 |
| 本线 Java 单测 `-Dtest=ProfitSharePlannerTest,SellerOfRecordPolicyTest,EcommercePayoutServicesTest*,EcommerceSellerResolverTest` | 42 条全部通过（11 + 6 + 20 + 5） |
| Real Storage IT `launch-l-payout` | success（run 42842384570，9/9） |
| 小程序全量单测 `node --test chengyinhub-xcx/tests/unit/*.test.js` | 失败的 10 条与底座 `ca1f85b68` 完全相同（逐条比对），本线新增 6 条全部通过。新页带来的计数棘轮已逐条更新并写明原因：路由 167→168、cy-page-title 91→92、标题盘点 167→168 / custom 166→167、tap 2979→2981、本地 tap 2612→2613、external tap 367→368、事件合同 2957→2960 / 本地 2861→2864、控件 5936→5941、商家更多菜单动作顺序 |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | UI-GATE-0:PASS（没有新增 baseline） |
| route-matrix `--check` / 台账 `--write` | 168 routes PASS / 5941 controls PASS |
| 主包体积 | 2030.7 KiB（与底座相同；新页在商家分包） |
| `@keyframes` | 本线 +0 |
| `ci/role_permission_contract.py` | 三段全过（609 个后台端点全部带 @PreAuthorize） |
| `ci/api-existence-gate.js` | A=0 |
| architecture gate | 只在 GitHub CI 的 PR 上下文中运行，本地 fail-closed；交给 PR CI |
| 没有运行 | `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`（按规定） |

截图都在 `l-payout/shots/`，390 宽，q70，均为 rig 实拍：

- 已审阅：PA-01 前后（更多菜单）、PA-02（开关关闭：暂未开放）、PA-03（未开通）、PA-04（审核中）、PA-05（待签约 + 复制链接）、PA-06（已开通，掩码商户号）、PA-07（已驳回，显示驳回原因）。
- PA-04 ～ PA-07 是开关打开（假 `sp_mchid`，这条只读路径不调微信）后直接写 rig 库 fixture 拍的。
- 无权限态未拍：rig 里 `cy-error auto-back` 的失败半屏渲染不出来，现有「核销记录」页同样是空白（HARNESS）。
- 加载失败态未拍。

## 四、官方文档核对说明

本会话的网络不允许访问 `pay.wechatpay.cn` / `pay.weixin.qq.com`，无法在线逐字核对官方文档。

- **SDK 模型（已核对）：** 分账、回退、完结、电商退款、服务商 JSAPI 下单，全部用官方 `wechatpay-java 0.2.15` 自带的模型（`ecommerceprofitsharing`、`ecommercerefund`、`partnerpayments.jsapi`），字段名以 SDK 为准。
- **进件接口（未核对）：** 二级商户进件两个接口 SDK 没有模型，按官方 APIv3 路径实现：
  - 提交：`POST /v3/ecommerce/applyments/`
  - 查询：`GET /v3/ecommerce/applyments/out-request-no/{out_request_no}`
- **敏感字段（未核对）：** 需要加密的字段列在 `WechatEcommercePayGateway.SENSITIVE_PATHS`。
- 开通后，请先在沙箱 / 测试商户核对这两处。

## 五、你需要向微信支付申请 / 配置的清单

1. 以公司主体在微信支付服务商平台申请成为「电商平台」，开通「电商收付通」产品。准备：
   - 营业执照、法人证件、对公账户；
   - 小程序（已上线）信息、ICP 备案；
   - 平台交易规则与二级商户入驻协议、结算周期说明。

   开通后得到电商平台商户号 → `payout.ecommerce.sp-mchid`。
2. 在服务商平台把小程序 AppID 关联到电商平台商户号 → `payout.ecommerce.sp-appid`。
3. 在电商平台商户号下：
   - 申请 API 证书（私钥文件 + 证书序列号）→ `payout.ecommerce.private-key-path`、`merchant-serial-number`；
   - 设置 APIv3 密钥 → `api-v3-key`。

   这三项只放服务器环境变量 / 文件，不进仓库。
4. 开通「分账」能力，确认电商平台分账比例上限 → `payout.ecommerce.max-share-percent`（代码默认 30，以微信核定为准）。确认平台佣金的接收商户号，默认就是 `sp_mchid` → 可选 `platform-receiver-mchid`。
5. 准备两个公网 https 回调地址：支付通知 → `payout.ecommerce.notify-url`，退款通知 → `refund-notify-url`。（P-09 接入后才会用到。）
6. 每个要直接收款的商家 / 俱乐部：
   - 收集进件资料（主体类型、营业执照、法人证件、结算银行账户、超级管理员信息、店铺 / 小程序信息）；
   - 由运营在后台 `POST /business/ecommerce-payout/sub-merchant/register` → `/{id}/submit` 提交；
   - 超级管理员按「收款账户」页复制的链接完成账户验证 / 签约，状态到「已开通」即得到 `sub_mchid`。
7. 个人创作者：选定一家持牌灵活用工平台并签约，拿到它的 API 文档后实现一个 `CreatorPayoutProvider`，再配置 `payout.creator.enabled=true`、`payout.creator.provider=<代码>`。
8. 法务 / 税务确认：
   - 平台佣金由平台开票，二级商户自行开票；
   - 退款时先回退分账的规则写进商家协议；
   - 官方主题与通行证仍由平台为卖家，平台全额开票。

## 六、配置项（全部默认关闭或为空）

`payout.ecommerce.enabled`、`payout.ecommerce.seller-routing-enabled`、`payout.ecommerce.profit-sharing-enabled`、`payout.ecommerce.sp-mchid`、`payout.ecommerce.sp-appid`、`payout.ecommerce.platform-receiver-mchid`、`payout.ecommerce.max-share-percent`（30）、`payout.ecommerce.private-key-path`、`payout.ecommerce.merchant-serial-number`、`payout.ecommerce.api-v3-key`、`payout.ecommerce.notify-url`、`payout.ecommerce.refund-notify-url`、`payout.creator.enabled`、`payout.creator.provider`（none）。

## 七、Cross-lane

- **l-pass：** 通行证在 `SellerOfRecordPolicy` 里固定为 `PLATFORM_PASS`（平台卖家）。l-pass 不需要调用本线代码，继续走平台商户号。
- **qx-final：**
  - 部署顺序：迁移 `migration_20261110_ecommerce_payout.sql` 无依赖，可以和其他新迁移一起按台账执行。
  - 部署清单写上第六节的配置项，全部保持关闭。
  - Real Storage IT 增加批次 `launch-l-payout`。
- **商家首页（r2-merchant / l-misc）：** 本线只在「更多」菜单里加了一行，排在「核销记录」之后。
- **共享文件：** `app.json` 商家分包 +1 页；`action-ledger.json`、`route-matrix.json` 已重建；`real-storage-it.yml` +1 个批次选项。

## 待你决定

1. **下单 / 回调 / 退款是否由本线继续接入（P-09）？** 默认：不接，路由开关打开也无效，由支付链路原 owner 按第二节四步接入，并补回调真库 IT 与 Gameplay HTTP E2E。
2. **账本里个人收款方（发起人会员、俱乐部主理人个人）没有进件主体时怎么办？** 默认：整单转人工（`RECEIVER_NOT_ONBOARDED`），不分账，也不退回平台代收。可选做法：改用微信「分账到个人 openid」，需要你确认合规。
3. **同一会员既是商家主账号又是俱乐部主理人、两边都进件时，账本行该打给谁？** 默认：转人工。
4. **分账比例上限** 默认 30%。超过时整单转人工；协作主题平台佣金 + 核销费可能超过 30%，需要按微信核定的上限调整。
5. **被驳回 / 已作废后重新进件** 默认不支持（每个主体一个固定申请号）。需要时再加「换号重提」。
6. **定时任务**（刷新进件、计划 / 提交 / 同步分账）默认没有注册 `sys_job`，只能在运营端手动触发。接入 P-09 时一起加迁移。
7. **俱乐部端收款账户页** 默认没做（LAUNCH 只要求商家页）。俱乐部进件状态目前只能在运营端查看。
8. **运营端 Vue 页面** 默认没做，只提供带权限的后台接口 `business:ecommercepayout:query / edit`。角色菜单 SQL 也没加，目前只有超级管理员可用。

STATUS: PARTIAL - 电商收付通进件、按账本分账与分账回退、创作者打款接口、商家收款账户页、真库 IT 均已完成且默认关闭；尚未接入现有下单 / 支付回调 / 退款 / T+14 入余额链路（P-09，需支付链路 owner 与你确认后接入），运营端页面与定时任务未做
