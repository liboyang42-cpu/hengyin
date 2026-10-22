# Lane A — 会费开关闸（N-01 / N-01b，T-1）收口

- 分支：`feature/lane-a-fee-gate-20261022`（从 master @ `063a6c3c` 切出）
- PR：https://github.com/liboyang42-cpu/hengyin/pull/454（draft，目标 master）
- 决策依据：D-12 —— 会费开关关闭时**只拦新的微信预下单**；既有账单仍可查询、关单、对账。
- **N-01b 涉及资金路径，合并前需 owner 审核。**

## 改动（仅 `ClubMembershipServiceImpl` 及其测试类）

| 编号 | 位置 | 改动 |
|---|---|---|
| N-01 | `resumeInvoice` | 在 `reconcileWechatInvoice` 查单之后、`createGenericOrder` 之前调用 `featureGate.requireEnabled()`。开关关闭时，凡需要新建预支付单的恢复请求返回 404「暂未开放」，不再调用微信。查单对账（SUCCESS 补权益 / CLOSED 前向关单）照常执行。 |
| N-01b | `prepareCloseIntent` | 去掉开关闸。归属、快照、锁序，以及「查单 → 关单 → 回读」流程不变。 |
| N-01b | `invoice()` | 改用新拆出的不过闸 `resolveMember`（`requireMember` = 开关闸 + `resolveMember`）。成员与归属校验不变。 |
| N-01b | `requireFinance` | 去掉开关闸，`FINANCE_READ` 权限校验不变。 |

仍受开关闸约束的入口：renew、cancelTerm、requestRefundReview、savePlan、updateSetting、overview、plans。
未改动：金额、路由、微信支付客户端、回调、DDL。

## 验收清单

- [x] 开关关闭时恢复支付不生成新预下单，且对账仍先执行 —— `flagOffResumeStillReconcilesWechatButNeverCreatesNewPrepay`（InOrder：query → gate；`createGenericOrder` / `attachPayParamsCas` 均 never）
- [x] 开关关闭时微信 CLOSED 对账仍落地 —— `flagOffResumeStillAppliesWechatClosedReconcile`
- [x] 开关开启时恢复支付正常下单 —— `flagOnResumeChecksGateAfterReconcileThenCreatesPrepay`（InOrder：query → gate → createGenericOrder）
- [x] 开关关闭时可关单，归属校验仍生效 —— `flagOffCloseInvoiceStillClosesExistingPayingInvoice`、`flagOffCloseInvoiceStillRejectsAnotherMembersInvoice`（403）
- [x] 开关开/关两态下账单查询正常，越权仍拒 —— `invoiceQueryWorksWithFlagOffAndOn`、`flagOffInvoiceQueryStillRejectsCrossMemberRows`（403）
- [x] 开关开/关两态下财务列表正常，仍需 `FINANCE_READ` —— `financeListWorksWithFlagOffAndOnButStillRequiresFinanceRead`
- [x] 其余写入口仍受闸 —— `flagOffStillBlocksRenewAndOtherWriteEntries`（renew / cancelTerm / savePlan 均返回 404）
- [x] 本地验证：`mvn -pl chengyinhub-admin -am test -Dtest=ClubMembershipServiceImplTest,ClubMembershipFeatureGateTest,ClubMembershipExitCallbackTransactionBoundaryTest` → 共 73 个，0 失败。反证：只回退 service 改动时，7 个新测试失败。
- [ ] CI 相关 Java job 全绿 —— 见下节

## CI

见下方「CI 结果」（推送后更新）。

## 待 owner 决策 / 开放项

1. **开关关闭时复用已存参数**：开关关闭时，若 PAYING 账单已存有 `payParamsJson`，`resumeInvoice` 仍会返回这组已有预支付参数。这不是新预下单，D-12 与任务口径都没有要求拦截，但用户仍可能借此支付一张既有账单。请 owner 确认是否接受。
2. 开关关闭时，`prepareResumeIntent` 仍会先写恢复审计回执，之后才被开关闸拦下（属既有行为，未改）。
3. 小程序端：开关关闭后关单 / 账单页不再收到 404。是否需要前端调整入口展示，不在本 lane 范围内。

## 需要 owner 本地手动验证（WeChat DevTools / 测试环境）

- 在测试环境关闭 `feature.flag.clubMembership`，然后：
  - 对一张未带参数的 PAYING 账单「继续支付」，应提示暂未开放，且不调起支付；
  - 「取消订单」应能成功关单；
  - 账单详情与财务列表应能正常打开。
- 打开开关后，重复「继续支付」，应能正常调起支付（`getPhoneNumber` / 支付只能真机测）。
