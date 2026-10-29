# B：90e 资金与完整恢复组合源码收口

绑定公共父提交 `90e18550ae75e83641e85bf4bb218f08922c7d3e`，tree `55ad70d6e20746d7b47ce368ca1d08f2014b59ff`。本片只修一处确定的真实 MySQL 夹具查询错误，没有修改生产资金规则、SQL、CI、登记、其他 owner 源或原案例断言。

## 确定问题与修补

`RestoreMoneyDispatchMySqlIT.Probe.main` 首次调用 `financialRows(db)`（父源码第74行）时，helper 第138行给所有表统一拼接 `ORDER BY id`。但规范 `migration_20261024_registration_payment_fact.sql` 第6–13行定义消费回执表主键为 `registration_id`，没有 `id` 列。因此真实 MySQL 会在第一个资金快照处拒绝查询，尚未到新建/扣款拒绝及已核成功事实保留断言；这是源码确定的夹具阻断，不是已运行复现或生产资金缺陷。

现在该表按自己的 `registration_id` 排序，其余七表仍按原 `id` 排序。仍读取全部列和全部原行。原1个测试、3处快照调用、金额/高水位/拒绝/原事实读回/清理断言全部保留；没有把真实 SQL 换成 mock，也没有新增声明冒充执行证据。

## 本次有界源码核对

- A197ac0恢复守卫与相邻入口：报名创建/重放/checkout、微信意图/SDK、两腿提现/余额准入、退款审核/派发/回调、原后台对账、会员/商城/押金/结算和取消入口。新资金派发在持久新经济身份及实际外呼前受启动 guard 约束；关键 broad catch 前保留 `Blocked` 原类型。原号查询与验证事实路径仍独立存在。
- 两V1：`CashWithdrawTransferIntent` 显式区分 FUND_FACT 与 TRANSFER_INTENT；原 hash 各按原结构校验。提现原短事务、两侧证据矛盾拒绝、master-only不造DEBIT、原号查询终态/UNKNOWN、原账户及CAS路径均沿现存实现。本片没有回填或转换历史身份。
- 支付/退款回调：SDK事实核验与持久化在业务消费之前；报名/退款在原inbox登记后恢复模式拒绝消费，会员/商城/押金在原领域 mutation 前拒绝；未把暂停变成NOT_SENT、失败退款资格或DONE。原正常模式消费合同未改。
- B物理进程：14条原源码及Store装配已读；真实admission/Mapper/TX、精确Process handle/PID/startInstant、固定闸点、SIGKILL退出/重启身份、独立SQL/保护行和精确建库清理均为待执行源码。本片不改原B14、CashFundEvidence64或TransferIntent9。
- C七cohort：payment-gap原五时间线；withdrawal/refund/voice各complete/gap的实际owner、两V1/原Reference/provider journal、typed全行/高水位核验、三次hold启动与完备后的normal原号读回；宿主实际命令、同build classpath、JAR `BOOT-INF/classes/git.properties` full SHA、外部journal与清理分层已核。没有改C源码或其原37+35控制。

## 边界与交付

除上述夹具排序错误，所核对调用链没有新增已确证阻断；此表述不是“无BUG”或运行通过。旧payment-gap仍只证明其显式 `SDK_DISABLED_ONLY` 子边界，`allDispatchDomainsFenced=false` 且恢复放行BLOCKED；不能把它签成全域恢复准入。三组complete的正常启动也只覆盖固定测试原身份，不授权生产恢复或真实渠道派发。

交付只含一个现有Java测试文件和本README的增量。仅执行源码读取、差分/摘要及patch可应用性检查；未执行测试、编译、Node/Python控制、Java/JVM、MySQL/Redis、HTTP、provider、物理故障或CI，全部 **NOT_RUN**。原候选绿色/源码计数不转签本片，后续同源集中验收由root安排。
