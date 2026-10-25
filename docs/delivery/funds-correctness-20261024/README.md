# 资金正确性修复候选

仅修复既有资金流程，不包含架构拆分、业务费用调整或其他 Claude 批次。

- 微信支付永久保留原商户、应用、原单号、精确金额与请求哈希；UNKNOWN 不允许换号或推定失败。
- 押金结算关闭观察到的原支付尝试后，必须回读同号 CLOSED 并锁定仍为当前尝试才可抵扣；晚到 SUCCESS 仍进入已付款路径。
- 零钱提现在扣余额的同一短事务内写入不可变原扣款/转账证据；提交 CAS 持久化并回读后才调用 SDK。
- 到账/失败事实必须匹配原商户、应用、批次、明细、金额与收款人。缺证或 UNKNOWN 不回补，明确匹配 FAIL 保留原有自动回补规则。
- 人工双审的两次操作都验证证据；第二人必须不同，状态 CAS 与余额回补同事务，异常整体回滚。人工阶段晚到 SUCCESS 可按原号收尾，不能再回补。

## 部署前数据库依赖

本候选必须先按批准流程应用以下两个新增表；本轮只提出 SQL，不执行生产 DDL：

1. `chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql`
2. `chengyinhub-admin/sql/migration_20261024_cash_withdraw_transfer_intent.sql`

两文件均为一次性 CREATE。表已存在时须先核对精确结构与迁移账本，不能盲目重跑、添加 IF NOT EXISTS 吞掉坏表，或按当前配置补造历史渠道。既有支付/提现缺少原证据时保留 UNKNOWN，不自动发送、抵扣或回补。回退应先停止新提交并保留原号映射和全部证据，不能删表回退。

## 验证边界

现有 GitHub-hosted CI 负责编译、相关 Java 与资金回归；既有 real-storage 入口的 `payment-intent-correctness` 批次明确选择原支付 B4/B3、提现原证据 MySQL IT、原提现余额/冻结 MySQL IT。每类须精确数量、0 failure/error/skip，独立清理回读 CLEAN。

测试中的微信 SDK 为受控替身，不代表真实渠道、付款、到账、真机或生产迁移验收。未部署、未上传微信；最终同 SHA 的结果由 PR 回读，不沿用旧候选的绿。
