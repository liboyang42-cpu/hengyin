# F07 到账通知触发时点修复

状态：冻结待 Standards / Spec 独立审查，未 commit / push / PR / merge。未连接生产或发送真实通知。

基线按总控明确指定，从 root 集成工作区当前 `b3f10b232f4af56767a3adb7decbb2b5428465bc` 建立独立 worktree `repair-refund-notification-0913`，分支 `codex/repair-refund-notification-0913`。该基线已含本轮 F07 Controller 取消反馈修复；没有回退主 checkout。

## 问题与既有真源

`TeamGroupServiceImpl.refundOneAfterInventoryRestored` 在取消事务内调用 `notifyRefund`，现金尚未 afterCommit 派发就通知“已退款”；无现金单票则传金额0，是否用了积分都一样。纯积分整单分支没有该调用，不能扩大为所有取消都会误发。

真实路径：`INotifyService.notifyRefund(Long memberId, String topicName, String amount): boolean` → `NotifyServiceImpl` → `NotifyEvent.REFUND` → `WxSubscribeChannel` → `WxSubscribeMsgServiceImpl`，模板字段 `phrase5=已退款`、`amount1=现金金额`，收件人由退款会员解析微信 openid。站内信通道当前仅处理 TEAM_STATUS。

真实渠道 SUCCESS 来自 `WechatPayServiceImpl` 验签解密回调或主动退款查单，进入 `RefundServiceImpl.handleRefundNotify`。原 `updatePayoutTerminal` 只改状态，并没有到账提醒；不能把取消时的发送解释为回调成功通知。

## 最小改动

仅4文件：

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java`：移除取消受理中的错误到账事件触发，保留全部取消、派发、返积分和权益操作。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RefundServiceImpl.java`：首次 SUCCESS 更新成功或人工复核 3→4 成功时，调用同一个提交后通知辅助方法，沿用原 notifyRefund 能力与退款单收款会员/实际退款金额。
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RefundNotificationMybatisIntegrationTest.java`。
- 本文。

不修改 Mapper、数据库迁移、资金状态规则、退款金额、API、页面或模板。原 `updatePayoutResult` 只允许 NULL/0/2/5/6 → 终态，排除1；返回1才是首次 SUCCESS。返回0后已有终态的幂等 ACK 不触发通知。人工复核使用原 `markPayoutVerified` 的3→4条件更新，重复为0。

复用现有 `TransactionSynchronizationManager` / `TransactionSynchronizationAdapter.afterCommit` 模式。有外层事务时注册提交后回调，回滚不发；无外层事务的渠道处理沿用 MyBatis 单次更新提交，在 mapper 成功返回后发送，不额外改变原渠道处理的事务隔离语义。未知收件人、缺失/非正金额记录错误，不能给模板补0。

## RED → GREEN 与验证

实际环境：MySQL **8.0.45**，本机端口3317，使用 `MysqlIntegrationTestConfig`。测试每次强制创建新 UUID schema，CREATE 不带 IF NOT EXISTS，cleanup 只删除本次创建的库；没有读取或修改已有业务库。退款表使用仓库 base_schema 的真实 DDL，退款服务、MyBatis Mapper、事务管理器与 NotifyServiceImpl 均为真实实例，通知末端是捕获通道，无微信连接。取消调用使用真实 TeamGroupServiceImpl，相关报名/积分/派发依赖用测试替身，其业务正确性不由本测试重新宣称。

三轮 RED：

1. `/tmp/refund-notify-success-red.log`：真正到账提交后通知数期望1，实际0。
2. `/tmp/refund-notify-manual-red.log`：人工复核事务提交前已发送，负控失败。
3. `/tmp/refund-notify-cancel-red.log`：零现金、零积分取消受理时生成到账事件，负控失败。

对应 GREEN：`/tmp/refund-notify-success-green.log`、`/tmp/refund-notify-manual-green.log`、`/tmp/refund-notify-cancel-green.log`。

最终相关测试58项，全部通过，0失败/错误/跳过：

- RefundNotificationMybatisIntegrationTest：9项真实MySQL服务/通知测试。
- RefundManualPayoutTest：19项。
- RefundPayoutAtomicityContractTest：3项。
- NotifyFanoutContractTest：18项。
- WxSubscribeMsgSendErrcodeTest：9项。

日志 `/tmp/refund-notify-final.log`。补充独立连接回读观测后重跑9项，日志 `/tmp/refund-notify-observer-green.log`：捕获通知时由另一数据源连接读取 payout_status，正向明确读到已提交1/人工4，不拿发送线程的未提交值证明提交。

覆盖：现金部分退款真实金额12.50而非原支付30，正确payer91；首次成功通知保留；两个并发SUCCESS仅一次通知；重复回调不重发；SUCCESS事务回滚不发且回滚后可重试；人工复核提交/回滚/重复/经办人自审；取消现金/零元/积分及混合支付均不宣称到账，现金派发仍只在提交后执行，取消回滚不派发；PROCESSING和待人工不冒充到账；无效收件人/金额不补零；通道返回false或抛异常保留日志且不回滚退款终态。

复跑：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_PORT=3317 mvn -q -pl chengyinhub-admin -am -Dtest=RefundNotificationMybatisIntegrationTest,RefundManualPayoutTest,RefundPayoutAtomicityContractTest,NotifyFanoutContractTest,WxSubscribeMsgSendErrcodeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 恢复边界

既有通知是 best-effort，只有逐通道失败/全部未送达日志，没有退款通知 outbox、持久去重或自动补发合同。本次保留这些日志，并增加带 refundId 的未送达/异常定位。不新增重试框架，不把发送失败说成送达，也不承诺 exactly-once 或最终必达。

提交后到通知发送前进程中断、模板缺失/订阅未授权、渠道拒绝等仍可能导致未送达；重复渠道回调只 ACK 已有终态，不补发通知。正常到账通知能力已保留并有正向测试，不能以“全部不发”通过回归。真实微信配置、订阅资格、送达、生产部署均未验证。
