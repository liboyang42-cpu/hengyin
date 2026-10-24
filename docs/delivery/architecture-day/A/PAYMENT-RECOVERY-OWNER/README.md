# A 原消费完成与关闭报名恢复 hook

实际生产小片：仅 CmsRegistrationServiceImpl 两处接线，parent f079889b4ba98c83aa754ced8e9540058c100197。**依赖真实 B9d59271b API；未 Java/MySQL/CI/SDK/DDL 验证**。没有动 B认领的 factory/seed、原 tests、G/readleaf/409 wrapper/denial helper/通知/金额/CAS 算法。

1. 实际 SUCCESS 已完成原Ref/原报名号/精确金额核验且持原 topic/activity/报名锁后，payment=3、registration∈{1,3,4} 只读 B findPaymentRecovery(originalRef)，严格核正 ticketID、原businessID、原Ref、MANUAL_REQUIRED=4，然后保留原失败返回。不进 G admission，不伪造 DONE、退款或重开容量；缺任务或错身份明确失败，不能当空放行。standalone 活动也走这道 A 恢复判断，不硬塞主题结算owner。
2. recordPaymentConsumption 原当前2/2/交易号/金额、A immutable receipt 的原注册ID/原订单号/原交易/原Ref 全部校验完成后，调用 B completePaymentRecovery(originalRef,registrationId)。与原业务/原回执/DONE 同事务；失败整笔回滚，不能独立提交“完成”或靠普通inbox冒作消费证明。无队列时由B原API按合同无操作，已有2状态按原身份幂等。

production.review.patch 可在精确parent上应用；静态确认现有否认helper、current claim、topic-first包装、支付算法、通知方法体保留。root 的477/189实跑失败是既有证据，不能被本片覆盖或称通过。新的实际 RecoveryTicket 接线替代 A2aa 文档里的提案 recordClosedBusinessPaymentRecovery API；那个旧review.patch不得原样应用。2aa交错测试的真实B9字段/任务键适配仍待A独立专属增量；B负责其已认领的共享fact工厂/seed。

已知边界：历史已有fact、本地已失败但无冻结CLOSED意图/无恢复票时，本片明确报缺持久恢复任务，不自动补造支付或人工完成；B/root需为该历史调查形成原身份可查询的交接。TeamGroup/ClubEventOps锁外SDK三阶段仍在独立工作区施工，本head不夹不完整中间态，也不宣称整个资金批次闭合。
