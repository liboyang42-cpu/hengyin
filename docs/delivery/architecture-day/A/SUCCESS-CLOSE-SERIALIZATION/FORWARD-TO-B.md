B/root 可直接接的最小合同（不是已实现或已验收声明）：

1. B13/df72/B30 `WechatVerifiedPaymentFactService.recordVerified` 当前仅独立写 fact 表；`WechatPaymentIntentStore.lockClosablePaymentAttemptForUpdate`/freeze/recordClosed/retry 则持 `WechatPaymentOrderOwner.lock(businessNo)` 原业务行及原 attempt。它们目前没有共同的事实发布互斥。
2. 复用现有 `WechatPaymentOrderOwner`，PAYMENT/REFUND 的独立事实发布短事务必须先取得 `candidate.businessOutTradeNo()` 对应的**原业务行**，再核对候选原 merchant/wx alias/frozen request identity（允许真实历史 alias），再写 immutable fact/原拒绝审计并提交。business→attempt→fact；不反向请求 topic/报名 Service；不要 FactService→IntentStore→FactService 循环注入。无第二套锁表/锁服务。
3. **不能单给现有 REQUIRES_NEW 方法加业务/attempt 锁。** `recordVerified` 的外层入口先判调用方无活动事务，再开启独立可写短事务；必须移除会在入口检查前启动事务的现有 public REQUIRES_NEW 注解，或使用等价明确入口/已有受控叶子。冲突拒绝审计仍提交后抛原 ConflictException，不能因 TransactionTemplate 默认回滚而丢审计。
4. SDK 与事实发布分阶段：所有支付/退款 query/notification verification/preorder/close 的 SDK 调用都在 DB 锁外；入口若处于业务事务，必须在 SDK 前拒绝，而不是等 recordVerified 阶段才发现。A/G 事务内查单调用者需先完成锁外原身份查证/持久事实，再进入原业务事务消费精确 Reference，最后锁下复核 scope/CAS；不能 NOT_SUPPORTED 悬挂持锁事务去外呼，不能异步队列替代提交。
5. 不以 `Snapshot.payable=false`/本地已关单/已有更晚 alias 拒绝保存真正验过的原 SUCCESS，也不把它 ACK 掉。关单先提交时，该原 SUCCESS 随后仍须保存原 Reference 供恢复；事实先提交时，关单/重试/释放必须拒绝。本协议只规定提交顺序，不决定迟到付款退款/重开金额规则。
6. A4092 已有业务锁→原 attempt guard→CAS；root 又已接 hasVerifiedSuccessBeforeRelease 到 releasePaymentAttempt/closeExpiredOne/settlePayingOne。A 当前生产 caller 不需要增加另一把锁或再查一次；本片不重交/覆盖该 helper。不重交已有 denial helper；A 新增的 closed-success 恢复调用精确 patch 见第8项。最后一处读取后仍有同一窗口，只有 B writer 共同互斥能让这套 caller 成立。
7. 新增 A 自有 `PaymentSuccessCloseSerializationMySqlIT` 单一固定 @Test，真实 owner/attempt/fact/报名 CAS SQL；Spring 按类型注入现有 owner，旧 B 不会在夹具初始化因缺新字段失败。在所有 caller guard（包括 root 新 helper）之后、真实 closeExpiredPaying SQL 前阻塞 CAS，writer 必须等原行；旧 B 可真实提交 fact，随后真实 CAS 提交，再由“expected0/actual1”发布时序断言暴露缺口。反序与迟到事实保存也在同一用例。尚未执行 Java/MySQL/CI。

特别需要 B 确认：原业务记录缺失/无法锁定时的真实事实留存路径，不得把读错/锁错当不存在，也不得创建假业务行；保留已验事实与拒绝审计身份并 FAIL/UNKNOWN 待调查。现有四类真实 owner 行存在的主链不依赖此未知分支。

8. root54 A caller 的最小新增接缝：已核 B原Ref/金额、持原 topic/activity/报名锁，且 `payment_status=3 && registration_status∈{1,3,4}` 时，在 G admission 之前调用 **提案 API** `public void recordClosedBusinessPaymentRecovery(Reference original,String businessType,Long businessId,String businessOutTradeNo)`，参数为原Ref/"REGISTRATION"/原报名ID/原报名号，然后原失败重试返回；不调用G、不恢复库存、不退款、不伪造普通DONE。精确 hunk 在 `a-closed-success-recovery.review.patch`，base root54，尚未应用；需 B 真实方法同批提供。
9. 上述 public recovery 方法只独立提交审计/outbox，不再取原业务/attempt锁或调用SDK（A外层已持锁），并以 requireVerified 校验PAYMENT原Ref及 businessOutTradeNo。B recordVerified 中的 closed 原单分支则在**同一事实写事务**直接复用审计/outbox核心，不能调用独立 recovery 方法去读自己未提交的fact。两路径均严格readback，不把重复enqueue=false当已成功。
10. 提案持久身份供 B/root 对齐（无新表）：audit reason=`CLOSED_BUSINESS_SUCCESS`，original_fact_id=原Ref.id，candidate_fact_hash=原Ref.hash；outbox idempotency_key=`PAYMENT_SUCCESS_AFTER_CLOSE:`+原Ref.hash，biz_type=现有PAYMENT_QUERY(1)、biz_id=原业务ID、money_affecting=1/state=MANUAL_REQUIRED(4)。这里是人工资金冲突处理身份，禁止自动dispatcher，不发起一次额外收费/退款。若B已有其它精确键/方法，由root转真实契约后定点改测试，不加多形状fallback。
11. B 的原业务快照目前只有 payable，不能用 payable=false 一概判冲突（正常2/2重放也不可支付）。A 只授权以上精确本地失败/关闭状态作为本片分类；在已有Jdbc owner原行锁的当前读里取得这些状态，保留原业务类型/ID，不调用G判 standalone。其它域由各owner给同样明确分类，未知不得默认到账或自动回补。
12. 测试第一原单为 owner_type=2、活动topic_id=NULL：late fact与人工恢复任务必须同提交可读；原Ref重放不得多一任务。再经真实A inline入口调用新hook、故意回滚外层，原任务/审计保留，G mock配置为触发即失败；第二主题单覆盖事实先提交→关单拒绝。全部仍为1固定@Test源码，NOT_RUN。

13. 人工任务必须能沿既有查询/告警入口被发现；建议只在新建任务事务提交后沿现有 AlarmService 节流告警，记录原Ref/业务ID，不含原始provider正文。任务持久性不依赖告警投递成功，不能把日志/告警调用当已通知人工或任务已处理。A测试核对持久任务与禁止自动领取；不冒称真实告警送达或人工UI已经验收。
