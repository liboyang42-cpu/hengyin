# 旧退款 fixture 接真实事实/current-read 合同

本片 **test-only**，父 `e6dc689acd16f8354096fa6017cec85b36171c4e`，分支 `codex/architecture-day-a-refund-verified-fixtures`；Java/JUnit/MySQL/CI全部未运行。inline先行调用链 `146f956b` 的另一工作区保留，非本片生产变更。

五条授权路径保留全部原case/业务assert与verify（逐个调用文本静态有序子序列核对）。RefundNotification真库9例、RefundRetry真库5例、Manual unit19例、Service unit30例；VerifiedFacts保留11普通@Test与原2组13参数（源码预期37实例，未实跑），未删/skip/改为拒绝正控。

- Notification：真实领域XML/current activity22/registration11/history，B `realFacts(ds)`、canonical B/inbox/原两迁移；每例重建 `PaymentCallbackInboxStubs.passThrough(tx,facts)`让原mutation真实执行/加入外层事务。保留独立连接通知读回、并发、回滚与四种现金/积分取消断言。该suite不证明inbox DONE，桩明确不记DONE。
- Retry：原SQL断言要求两笔独立refund inbox，故保持**真实**B inbox/claim/mutation+领域Mapper/事务/sidecar，补齐装配，不用mock true或内存数量代替。沿原20/48/68金额、重放、审核并发及cleanup断言。活动20当前读来自真实XML/行，未借活动ID伪造topic。
- Manual：显式原topic/registration/history、同一原交易号、G mock及B canonical memory fact reader；原回调正控经passThrough执行mutation。旧CAS=0案例保留“旧观察/当前精确终态或冲突态”区别，当前行改从原history锁读，不靠旧selectByRefundNo快照。
- VerifiedFacts：复用已实际读取的 B f6f3825 SDK分离fixture与原client绑定，业务用同一gateway事实reader，passThrough执行原handler，补topic/registration/history/G装配。原SDK query/notification匹配、所有无效字段/金额/当前终态/通知断言保留；providerRefund先构造Amount后thenReturn，避免嵌套mock导致UnfinishedStubbing。SDK是明确受控mock，无真实付款/退款、验签或生产成功声明。
- Service：补齐原registration99/activity20/member8的明确单位fixture身份/current-read/history及无新分项G mock；保留原业务guard、maker≠checker、金额/负向调整/CAS并发/日志assert。没有生产fallback或改经济策略。

唯一新helper `ARefundFactFixture`：Memory仅单位fixture，调用B公开setField/MemoryMapper读校验API（B自带wireUnitReads处理只读事务manager）；真库类调用同一builder时用实际realFacts。只有调用方明确提供合法CNY SUCCESS与完整数额才写fixture DTO/canonical reference；不将UNKNOWN/缺字段升级、不mock requireVerified恒真。

明确依赖实际fetch B `f6f382500ff1a1dfd6fad120b3895f70ae563278` 的真实fact reader与测试support，以及root的B SDK拆分/G单参接口/本A e6源；未复制或修改B helper/生产/SQL/CI。两真库需受控runner设置B_PAYMENT_FACT_DDL_APPROVED=1并按source登记，候选只写迁移加载接线，未执行任何DDL。静态结果/文件hash见static-result.json；剩余编译、全部真实执行与同头验收由root安排。
