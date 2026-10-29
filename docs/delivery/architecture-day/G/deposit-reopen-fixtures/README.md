# 保证金重开：B30 caller 夹具增量

Parent: `30d88bdc5401f9b64c0a99656b466d494b454d17`。仅改既有 `CoopInviteServiceImplTest`：setup注入真实 `WechatPaymentIntentStore` / `JdbcWechatPaymentOrderOwner`，JDBC与成功事实读取为单元double；新增3条测试。依赖 root 已接的 B30 `docs/delivery/architecture-day/B/fixed-payment-batch-callers.review.patch` 中 CoopInvite caller（`paymentIntentStore` 字段与原号关闭准入）。B30公共源码尚以review.patch携带caller，故本测试提交须与root该caller合成，不能把裸parent当最终可运行树。公共API仍调用 `prepareDeposit`，不依赖root私有helper名称。

- 未确认关闭：原状态5/金额/原业务号不变，`updateDeposit`零调用。
- 已持久化SUCCESS：即使fixture有关闭时间也拒绝；不再取attempt，零CAS。
- 合法已关闭：依次原业务锁读→原成功事实检查→最新attempt锁读→恰一次更新；返回原 `DEP1T100`，不由CoopInvite生成新业务号。

静态确认原117条 `@Test` 方法全文不变，新声明120，无参数化分支；无生产源码/接口/CI/schema改动。G `CoopDepositSettlementAttemptTest/MySqlIT` 的真实CoopInvite只走 `markDepositPaid`，无重开调用，不需为此新增store装配；API测试mock原公开服务，退款IT准入前拒绝用例不触及重开分支。

已执行diff --check、原case字节比对、B30真实字段/API/SELECT参数核对和有限补丁秘密模式检查。Java/MySQL/CI/provider **NOT_RUN**；事务标记仅单元装配，不证明实际事务/锁或provideralias分配。root统一合成回归，不新增CI/DDL。回退只移除测试增量，不撤销生产关闭保护。
