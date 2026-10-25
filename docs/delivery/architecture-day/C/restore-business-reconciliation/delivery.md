# 恢复资金差额入口 SOURCE 交付

结论：本包写成可接入的**报名付款恢复演练 SOURCE**，复用原 canonical 备份恢复工具和原资金真实装配；尚未执行，也不等于完整 T20、生产恢复 fence 或全部资金域验收。缺失整条原意图时只读定位并保持 BLOCKED，不生成替代付款/退款/转账。旧后台包和 5b54 章节恢复包冻结未改。

基线 `fa5c263440573992701263d6bee60830844a49b8`；独占分支 `codex/architecture-day-c-restore-reconciliation`，目录 `/workspace/hengyin-c-restore-reconciliation`。新增 4 源文件及本 C 目录；原生产 Java、SQL、脚本、registry/workflow、其它 owner 文件均未改。逐文件后像见 postimages.sha256（不含自身）。

## 真实入口/装配

| 新 SOURCE | 真实调用与责任 |
|---|---|
| ci/restore_business_reconciliation_probe.py | 固定双 MySQL/CID/UUID/source/预算闸；调用原 scripts/db-backup-restore.py backup/restore；独立 SQL typed rows/高水位/provider journal 对照；原错误、UNKNOWN 和精确清理不能变绿 |
| RestoreBusinessReconciliationFixture.java | 仅 test classpath 的固定 main，复用 RegistrationFundsMybatisIntegrationTest.createDatabaseAndService/resetSchema，一次创建新源库；原测试类及其断言一字未动；不把此 factory 指向目标恢复库 |
| 既有生产付款链（未改） | RegistrationOrderServiceImpl.createOrReplay → ICmsRegistrationService.beginPaymentAttempt → WechatPaymentIntentStore.freezePaymentIntent（真实 REQUIRES_NEW 原身份）→ 独立持久 provider journal → WechatVerifiedPaymentFactService.recordVerified → ICmsRegistrationService.handleWechatPayCallback → 原支付 CAS、cms_registration_payment_fact、真实 ums_member_points_detail/member points 原事务 |
| 既有 fixture 边界 | realFacts/realPaymentGuards 为原真实 SQL/事务；原 PaymentCallbackInboxStubs 为明确单测边界，不声称真实 inbox DONE、SDK 验签或微信网络；G claim/通知/增长/报价准入为原 factory port doubles，不冒充 G、IM 或全 HTTP 验收 |
| 恢复 SDK 检查 | 原 WxPayConfig 默认 false → WechatPaymentSdkGateway.init/isReady/isAppReady/createGenericAppOrder，确认拒绝；仅此真实 guard 有证据接口，**不是**真实 restored app 的全域派发关闭证明 |

没有模拟一个“fixture HTTP 成功接口”，没有 production 开关/后门，没有把 source Java 类型放到正常发布 JAR。host gate 明确检查 test 入口不在该 JAR、JAR git SHA、child/完整 classpath 内容摘要。正式源上所有现有业务断言和保护保留。

## 固定五场景

| 原身份 | 备份点 | 故障点/恢复后应观察 |
|---|---|---|
| historical_paid | 原 fixture 历史 master-only id11/T20260724PAY，明确无 frozen hash；真实付款消费+积分账本已提交 | 原 SUCCESS/原付款引用保留，不从 current config 伪造 hash/intents |
| late_success | 真实原订单+冻结请求；provider 已受理 | 备份后 provider SUCCESS、未给业务 ACK；恢复仍原 PAYING/无消费，标外部成功本地未应用，禁止重发 |
| lost_success | 不存在 | 备份后真实建单/冻结/受理/付款消费/奖励；完整本地链不在恢复库；外部 SUCCESS 仍存，标 UNKNOWN_MISSING_WHOLE_ORIGINAL_MANUAL_BLOCK |
| not_sent | 真实冻结原请求；受控 store 没有 SUBMIT | 保留原身份，只有此独占 fixture 可证未调用受控 provider，不能推广成生产“缺 row 即未发” |
| unknown | 真实冻结原请求；受控 provider SUBMIT 后 UNKNOWN | 原 UNKNOWN 原号保留，不释放/重发/补造成成功 |

provider store 是备份域之外 append/fsync 的 test-only 文件，不是第二套业务台账，不是真实渠道账单。它记录原 member 范围中的合成业务 ID、商户/app/币种/金额/原指纹及合成 provider ID；无样本、openid、token/密码或原用户 payload。原 SUBMIT 独立计数，恢复/guard 前后逐条/hash 一致，发现重复即拒绝。历史来源是原 fixture 固定 seed，付款事实由真实服务提交，未直接插 funding/debit/submit 账本。

独立 SQL 保留所有所选表列名/类型/NULL/全行，不丢金额、时间、身份字段；公开只发 row count/hash，完整原/后行只留 private。检查原付款 receipt→verified fact→订单、冻结原请求→订单、奖励→原订单/member 与逐条积分 current/change/after 的精确守恒；原 opening point=100 明示来自原 seed。canonical 完整 schema/table checksum/定义/trigger 比较仍在前，不能拿本包局部读回代替原校验。

按 source 故障时已分配 MAX/恢复 AUTO_INCREMENT 独立列可能复用的 ID；不 ALTER、不分配试探、不按成功恢复了 AUTO_INCREMENT 定义就 READY。记录真实 backup/fault/restore 时间及实际间隔；业务 RPO 未经实际损失测量/认可、RTO 因解除派发阻断未完成，均保留 UNKNOWN，不填承诺分钟数。

## 配套控制源码（全部 NOT_RUN）

- Python 37 个 test 方法：固定五场景、完整 typed row 差异、ID 高水位、缺失不能改 NOT_SENT、源库必须真实消费、provider 历史不能回滚、重复 SUBMIT、原金额/hash/业务号、receipt 引用、精确积分守恒、不得悄悄扩进退款现金、完整场景清单、journal 完整/hash/原身份与历史 NULL；直接调用原 canonical 函数的 manifest v2/v3 缺损、截断 dump、UUID/版本/非空目标/数据库定义/事件调度、恢复后行/定义差异、dump 失败控制；原 exit/inner failure/部分清理、成功观察仍禁止派发等。
- RestoreBusinessReconciliationFixtureTest 1 个 JUnit test：真实默认 disabled SDK init/readiness/create 拒绝，allDispatchDomainsFenced=false 且 release BLOCKED。
- 5 个真实数据库/provider 时间线是上述固定 child 中的演练场景，**没有运行**，不是额外已登记或已通过的 MySQL @Test。
- 原 ci/test_db_backup_restore.py 未改、未执行；其已有受控 mock 控制不当作恢复或资金事实差额验收。

本会话无 Node/Python/Java 测试、无语法编译、无 MySQL/Redis/容器/HTTP/provider/备份恢复/故障/CI。仅文件阅读、源码写入、git/摘要及定点文本检查。root 后续同源统一审查/执行，不能把本包 SOURCE 数量写成 PASS。

## 精确剩余接缝/禁止假闭合

1. **全域恢复派发 fence 未由本包实现。** `WechatPaymentSdkGateway.init` 的 wx.pay.enabled=false 阻止 fresh SDK 初始化；`submitRefund(Reference,...)` 检查已初始化 clients，`RefundPayoutServiceImpl.payoutAfterReview/doPayout`、`RefundReconcileTask.reconcile`、`CashWithdrawServiceImpl.syncPendingWithdrawals` 等是不同实际派发/恢复入口。只停 scheduler 或只检查 fresh gateway 不能证明这些入口全部封锁。恢复库暂不启动真实 app，且报告明确这**不是**“派发已关闭”的充分证据。本包因此无 READY/解除功能。root 若补全，须在各现有派发 owner 的提交准入处统一接恢复状态，保留读回/回调/对账，并由原 owner 决定审核后开放；不能让 fixture 开关冒充生产 fence。
2. **整条原意图缺失的安全回填/编号保留没有在本包获准写合同。** `WechatPaymentIntentStore.freezePaymentIntent` 的原 domain lock/fingerprint/latest attempt 以本地原业务存在为前提；`JdbcWechatPaymentOrderOwner.lock` 对丢失订单抛 MissingOrderException。`WechatVerifiedPaymentFactService.recordSerialized` 会保留可确认事实及业务缺失拒绝，不是授权新建业务；不得借它新下单。提现 `CashWithdrawServiceImpl` 的原 CW+id/CD+id 更要求外部缺失号区间被保留。root 最小后续需以原渠道不可变账单+备份前后身份索引进行双人关联，并在各原编号分配 owner 防复用，逐笔恢复原关联而非按 current config/金额猜；具体政策/DDL不在 C 自行实现。
3. **退款/转账/供应商任务的真实 restore fixture 仍未覆盖。** 本包只触达报名付款/奖励；refund_application 和余额明细必须保持空。B 的 FundsProcessRecovery/MySqlIT/Probe 未改也未借用其通过；task/voice/AI 与 G/E/H 文件未动。需要其 owner 原意图/真实 provider 读回/金额及状态合同后扩充对应独立演练，不以空表等价声称这些业务完成 T20。
4. **root 资源接线及真实运行缺失。** 固定双实例/存储/child/classpath/outer cleanup/finalize 协议见 runner-contract.md；没有自启容器，没有修改共享 workflow/registry。未来相同 source 的 private artifact 留存、外层只清自有资源、终态 absence 与真实原始输出均必需。清理 UNKNOWN/未实测 RPO/RTO/未解除派发阻断不能验收为完整灾备可用。

回退仅移除本次新测试入口/工具源码；不触及任何持久数据。未来演练失败保首因、完整 raw 私有证据和 UNKNOWN，不恢复对外写、不重试覆盖目标、不清生产库。
