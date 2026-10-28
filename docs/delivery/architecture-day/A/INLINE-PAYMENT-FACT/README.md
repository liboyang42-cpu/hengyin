# 原支付成功消费引用：A 源码候选

独立分支 `codex/architecture-day-a-inline-payment-receipt`，直接父为先行调用链 `146f956b3ee454e2429ea17d5fdcf8aa17169ca9`（其父e6）。fixture `57e99075` 是另一个已推兄弟片，root串行合成；保留root已有fd350创建首锁、366席位guard、e6终态hook及B出站Qualifier，不能整旧blob覆盖。**Java/JUnit/MySQL/CI及DDL均未执行**。

A实际源码：新增窄 `cms_registration_payment_fact`，registration_id主键、payment_fact_id唯一、原registration_no/TX/hash、FK到原报名/B事实、UPDATE/DELETE拒绝trigger、无TTL/租约/状态/历史回填。`CmsRegistrationServiceImpl`先冻结本次调用Map；两条真实入口共用SUCCESS分支，在原业务/CAS/同单副作用成功后、原事务提交/DONE之前，当前读原2/2/TX/精确分金额，INSERT...SELECT真实B PAYMENT并严格读回exact reference。冲突/写失败抛错回滚本地效果，B已独立成立事实保留。inline从不领取独立inbox或伪造DONE。两入口共用关联，避免inline后另一个同号事实被普通回调ACK；原零额付款路径没有外部现金事实，不制造PAYMENT引用。

`RegistrationPaymentFactReceipt`与同域Mapper是类型化leaf，无新生产服务/平台/线程池/依赖、没有重算金额/权限/退款资格。`wx_pay_order_intent`继续只表达原SDK意图/渠道关闭，未用它伪装本地成功，未碰B表或网关。

## 必须由owner合成的精确review.patch（未应用）

- **B-consumed-reader**：基于实际B f6f3825；复用既有内部readFresh读取A不可变关联和B canonical事实。只有真实无行才Optional.empty；SQL/校验错误传播，empty不是外部NOT_RUN。返回确切Stored，不挑任意同号PAYMENT，不在topic锁下锁/claim inbox。
- **G-consumed-component**：基于实际G8818837；明确原关联存在时必须匹配提案Reference，冲突不能退旧路；真正无新关联才保留原PAYMENT/DONE证明。新A关联走单独的INSERT statement，根本不查询可变inbox，避免inline原topic锁反向等待另一个callback的inbox。旧/新statement共用机械下沉的原SQL前缀/冻结范围，原分项金额/尾差/负亏损算法未改。G仍须补真实消费/冲突/并发测试与现有fixture接线，不把本patch当已验收。
- **D-signup-fixture**：基于root公开07359；未改D源。保留其原JDBC registration_fact通知fixture，补显式B canonical memory事实及同事务严格SQL receipt adapter、当前parent/topic读。原IM/rollback断言不变；不把该adapter说成A canonical FK/trigger或provider真库证明，后者由A实际XML类覆盖。

## A测试源码与真实未运行

`RegistrationTopicFenceMySqlIT`原25方法正文逐字保留，本分支新增9普通@Test→34：exact原关联/重复、写后异常回滚、换merchant/ref拒绝、原payer/从单不重领、非SUCCESS零消费、UPDATE/DELETE负控、已关账保留事实，以及普通/inline交叉入口身份一致。真实A/B/领域XML与事务；原G/通知侧仍是明确窄mock，不声称实际G钱包算法。改用B `realFacts(source)`，消费B已装配的readFresh事务manager，不手工漏字段。

原资金96测试正文和全部业务断言逐字保留，新增required mapper/canonical迁移装配；其SUCCESS查单fixture复用同一显式canonical支付fact，解决原raw Map不能经过f31的问题。原静态事务合同仅跟随冻结Map变量名，原assert均保留。

这两个已隔离schema的fixture按原每例reset重建**本类自有**新表；不关trigger、不在业务执行时删证据。新增受控门槛 `A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED=1`，仍需B原门槛。root登记源码实际条数/迁移依赖后统一跑，未改CI或registry。

## 迁移与余项

SQL仅source提案：受控预检B fact unsigned id、原registration signed id、MySQL8/InnoDB/strict/FK checks、全部同名对象与精确约束；不IF NOT EXISTS吞冲突。先B canonical fact表，再本表；无旧数据回填。DDL自动提交，生产中断需逐对象核对，不盲重跑；回退停新consumer并保留所有表/唯一身份/原事实，不DROP/DELETE资金或按TTL放号。没有生产执行授权。

A新关联只有原业务事务提交才可见，能证明“此原报名成功消费了此fact”，不表示该报名当前仍可用，也不授权再次支付或退款。B/G补丁、D fixture仍需root/owner合成；G原计划producer、尾差/部分退款/负亏损及已关账最终补账仍未闭合。缺关联不等于外部未执行，保留原事实人工查证。全部实际编译/真库并发/回滚/清理/外部SDK验证仍NOT_RUN，静态结果/依赖hash见static-result.json与consumer-patch-bases.json。
