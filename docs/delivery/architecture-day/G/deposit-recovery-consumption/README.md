# 保证金原SUCCESS消费与提交后退款查证（源码候选）

Parent `a726fed552a5ef047c75b60e751c8893edcdbddd`（锁外查证片），本增量依赖实际B `9d59271b0d82344b13f7b66ca2e0fb1751d14518` 的API和测试装配。两片须由root组合；不把B生产文件或历史复制进本提交。独占G分支 `codex/architecture-day-g-deposit-recovery-consumption-20261024`，未修改其他owner正文。

## 实际接线

- `CmsTopic.resolvePendingDepositPaymentForSettlement`仍锁外SDK、原topic→invite→attempt短Tx；SUCCESS分支在原mark/readback之后校验本次B Reference。严格匹配原付款业务号、provider attempt、CNY分币、TX与数据库成功消费字段；真实恢复票仅认相同Reference/businessId/state4或2，MANDATORY `completePaymentRecovery`与本地成功CAS同Tx。无票不造票，缺引用/错误票/错误凭据抛出并回滚，不生成第二种VERIFIED。
- `CoopInvite.markDepositPaid(String)`保留NEVER+显式active拒绝和真实短Tx，self-invocation进入新增四参重载时已有实际Tx。新增 `markDepositPaid(no,cents,tx,Reference)` 保留旧三参API，只在确切B事实与原本地paid-TX消费凭据一致后完成恢复票。不直接访问B mapper、不默认成功、不自动退款/重开状态5/权益；原不允许的消费仍失败并保留票4。
- `CoopDepositRefundService`的同步外呼改为 `afterCompletion(STATUS_COMMITTED)` 才以NOT_SUPPORTED脱离**已经物理提交**的资源，再调用原refresh。这个私有回调是唯一脱离点；ROLLBACK不运行。普通refresh带actual外层Tx立即拒绝，不能把该技巧套用到未提交业务。原持久退款意图、lease/token、独立claim/finish事务及金额校验保留。

## 真实生产caller尚需root串行应用

`shared-callers.review.patch` 只含 `WechatPaymentCallbackRouter` 的coop-deposit分支与 `ApiCoopController.depositStatus`：原三参改带本次SDK的verified_fact_id/hash四参；缺引用不fallback。两文件正文未修改。对B9公开原文件 `git apply --check` 已过。只有root接上这两处，生产回调/主动状态读取才不会丢Reference而留“已消费但恢复票仍4”；未接时不能宣称全入口完成。root还需随此迁移它们原mock调用/字段夹具，不能把成功改拒绝或skip。

## 新/旧测试源码

- `CoopDepositSettlementAttemptTest` 14→16普通声明；新增原恢复票在mutation Tx中完成、跨business票不得完成。旧SUCCESS fixture补本次真实typed proof/reference；原状态/金额/原号、零抵扣断言保留。
- `CoopDepositSettlementAttemptMySqlIT` 8→9：旧8场景保留，B9 fixture改读真实业务行/原intent（旧8的fact mapper仍是明确unit double，不称完整B存储验证）。新增一条单独应用原B migration及base inbox/outbox，使用B实际realFacts/当前业务锁和原SDK double：原CLOSED后SUCCESS持久fact+票4→故障注入票完成异常→本地CAS完整回滚而fact/票4保留→同Reference重试→本地paid-TX与票2提交，fact/outbox各1，无退款/重开。所有DDL仅测试源码，在已批准独占UUID scratch目录内，尚未执行。
- `CoopDepositRefundMybatisIntegrationTest` 27→29：原查单拒绝/成功断言保留、只补typed原事实夹具；新增物理提交后的意图可见且SDK无active Tx，以及未提交refresh拒绝且SDK零调用。原rollback不派发等场景不删。

统计仅源码声明；无参数化拆分、无skip、无CI/registry改动。静态已执行diff检查、Java分隔符检查（不是编译）、B9真实接口/源码核对、COMMITTED前置静态检查、共享patch应用检查、有限补丁秘密模式与逐文件SHA256。Java/MySQL/provider/CI全部 **NOT_RUN**。没有Mock/H2/Python资金通过声明。

无金额分配/退款资格/负债/舍入算法变更，无生产DDL/部署。回退不得删原fact/outbox/退款意图；B新writer与本caller须同源统一回归，root拥有最终集成。a379/411与root消费leaf保持原样，不覆盖ledger。
