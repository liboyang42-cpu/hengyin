# C07-backend · 商家供给 / 履约 / 多商家权益独占适配器

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C07**  
仓库：`github.com/liboyang42-cpu/hengyin` 仅写本仓（不写旧 `chengyin`、不改小程序）  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c07-merchant-fulfillment-d1ab`  
Head：本文件随后一次提交会前进。隔离测试，不真实支付 / 退款 / 核销 / 发奖。

## 1. 白名单（实际写入）

生产（仅新目录 `chengyinhub-system/.../service/fulfillment/`）：

- `MerchantSupplyDryRun.java`
- `FulfillmentEvidence.java`
- `EntitlementHoldProjection.java`
- `TransferSoldPolicyGate.java`
- `InvoiceHistoryUnknown.java`
- `FourLedgerBillReread.java`

测试（`chengyinhub-admin/src/test/java/.../service/fulfillment/`）：

- `MerchantSupplyDryRunTest.java`
- `FulfillmentEvidenceTest.java`
- `EntitlementHoldProjectionTest.java`
- `TransferSoldPolicyGateTest.java`
- `InvoiceHistoryUnknownTest.java`
- `FourLedgerBillRereadTest.java`
- `C07FirstJourneyIsolationTest.java`

未改：`TopicRouteRuntimeServiceImpl`、`CmsTopicServiceImpl`、13 Authority、17 phoneidentity、`BundleBookingService` / Mapper、36/37/38 既有原生生产候选、商业 Core、资金原 writer、公共 Controller / Mapper / migration、小程序、旧 `chengyin` 仓。无生产 DDL。无共享文件 hunk（dot 接线见 §7）。

## 2. 复用的既有真源（只读，不克隆第二套 writer）

| 既有真源 | 本适配器如何消费 |
|---|---|
| `MerchantDeskServiceImpl#saveSku` 时段/容量范围 + `migration_pms_sku_booking_20261022.sql` | `MerchantSupplyDryRun` 只 dry-run，不写 `capacity_reserved` / stock |
| `FulfillmentWindowResolver` 半开窗 Asia/Shanghai | 有效期事实由调用方先解析再传入 |
| `IPerkLedgerService` / `registration_entitlement` status 0/1/2 | `EntitlementHoldProjection` 投影占用，不调 `reserveEntitlements` / `redeemChapter` |
| `RefundPolicy#evaluate`（纯函数） | `FulfillmentEvidence` 退款试算，不调 `IRefundService` |
| `IMerchantAftercareService` | 售后只投影开案，不调 `respond` |
| `UserInvoiceServiceImpl` + `ManualInvoiceIssuer` | `InvoiceHistoryUnknown`：申请历史 ≠ 发票文件；MANUAL 永不 PRESENT |
| `IFourLedgerReconcileService` / P1-5 四账 | `FourLedgerBillReread` 按原 requestId 回读；缺账单 UNKNOWN，不新开 reconcile |
| `SmsVerificationService`（D4 17） | **明确不是**转赠已售 policy / 指定接收者授权 / 原 receipt |

## 3. 首个可跑旅程（隔离测试）

`C07FirstJourneyIsolationTest`：

1. `MerchantSupplyDryRun` 对 SKU + 有效期 + 营业时段容量 dry-run ACCEPT，不写库存/预占。
2. `EntitlementHoldProjection` 用同一 `originalRequestId` 投影 status=0 占用容量；不 reserve / redeem / award。
3. `FourLedgerBillReread` 回读该 id：微信账单缺失 → UNKNOWN，不新开对账；当前投影 SUCCESS 不能覆盖原 UNKNOWN。
4. `InvoiceHistoryUnknown`：MANUAL SUBMITTED 无文件 → UNKNOWN，不新 `apply()`。
5. `TransferSoldPolicyGate`：远端无已售 policy 原文 → `WAITING_SOURCE`；SMS 验证位不能授权转赠。
6. `FulfillmentEvidence`：预约/改约同 requestId，到店核销与退款试算 / 售后均为证据、不执行 writer。

命令（只跑本组新类）：

```bash
mvn -pl chengyinhub-admin -am test \
  -Dtest=com.chengyinhub.business.service.fulfillment.*Test \
  -Dsurefire.failIfNoSpecifiedTests=false
```

环境：隔离内存夹具 · 无 MySQL / HTTP / 生产库。真实输出见随后 docs 提交。

## 4. WAITING_SOURCE / 未宣称完成

- 转赠已售 policy + 指定接收者授权 + 不可撤销生效：**远端无原文**（未 push 的 D4 28/36/37/38 候选不是本 gate）。`REMOTE_SOLD_POLICY_ORIGINAL_PRESENT=false`。
- 发票文件 / 文件历史：`UserInvoiceApplication` 无 file locator；`ManualInvoiceIssuer.submit` 固定 `accepted=false, providerRef=null`。缺文件 = UNKNOWN，不是新申请。
- 25-App T01–20 / T43–58 原文包未作为 Git 对象签收到本仓；本组 **不是** T01–20 的 100%。
- `BundleBookingService` 归 dot D4，本仓无该类；预约占用仍标「本 PR 不写」于既有 migration 注释。

## 5. 门禁分栏

| 门禁 | 状态 | 说明 |
|---|---|---|
| 静态检查 / 本组单测 | 见随后测试输出 | 仅 `service.fulfillment` 隔离测试 |
| 全模块回归 / 真库 MySQL / HTTP | NOT_RUN | 本组无授权跑生产式环境 |
| CI（dot 21/22 专线） | NOT_RUN | 不重复触发 |
| 真实支付 / 退款 / 核销 / 发奖 | NOT_RUN | 硬边界，未做 |
| 生产 DDL | NOT_RUN | 未做 |
| `gh pr create` | 403 expected | 只 push 分支 |

## 6. 未解决

- 生产接线：供给 dry-run / 权益 hold / 四账回读尚未挂 Mapper（禁止本组改 Mapper）。
- 商家工作台 Controller / 转赠原文 / 发票文件存储未到。
- T01–20 全量条目未在本仓签收，不能勾完。

## 7. Needed hook（do not apply here）

共享 writer 仍归 dot。若日后接线，只把本目录适配器当 collaborator，不要从 Cursor 分支整文件覆盖：

- `MerchantDeskServiceImpl`：saveSku 前可问 `MerchantSupplyDryRun.evaluate`，dry-run 失败不得写 `capacity_reserved`。
- `PerkLedgerServiceImpl#reserveEntitlements`：发行后投影走 `EntitlementHoldProjection`，保留原 requestId。
- `FourLedgerReconcileServiceImpl`：缺账单继续 409 待核；消费者用 `FourLedgerBillReread` 回读同一 requestId。
- 发票：缺文件走 `InvoiceHistoryUnknown`，禁止用同一业务意图再 `apply()` 一条新 request。
- 转赠：在 sold-policy 原文到达前保持 `WAITING_SOURCE`；禁止把 17 SMS 身份当接收者授权。

WAITING_SOURCE：未 push 的 D4 商业 SHA `e16c2c5` `5b92889` 及 25-App T01–20 原文。
