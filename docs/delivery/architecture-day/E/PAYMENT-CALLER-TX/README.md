# E CLM / MerchantCommerce 支付 caller 精确补丁

基线 `0cb8115ee34e4647a76c76bda608fae62bbd51e1`（已含公开 `54ab9282bee31c7b6dd276682f4c86e9903709c9` 布局），独立分支 `codex/architecture-day-e-payment-caller-tx`，工作树 `/workspace/hengyin-e-clm-review`。仅交本目录 **callers.review.patch + fixtures.review.patch**，两共享生产类/两旧测试本体未编辑，root 串行应用。没有改 SDK/fact owner/A/G/金额/权限/SQL/schema。

**实际结论：0cb 正常代理路径已在锁外 prepay/query/close，不重复拆。** 尚须封住 NOT_SUPPORTED 隐式挂起实际 outer Tx（其物理锁不会随挂起释放），以及直接/自调用绕过注解后误把网络包在 outer Tx 的路径。patch 将五个网络入口改 NEVER，且第一行检查实际受管事务；CLM 私有 transaction() 显式 REQUIRES_NEW。Merchant 原 REQUIRES_NEW 已存在，逐字保留。不是删除事务，也不把资金域回调改成无事务。

| 实际入口/调用 | 查证/外呼 | 本地锁与原 CAS（保持） |
| --- | --- | --- |
| CLM renew | prepareRenewalIntent 短事务提交后 createGenericOrder；之后 attachPrepayParams 新短事务 | 原成员/club/invoice 锁、scope/冻结快照、requestId/idempotency、attach CAS 全保留 |
| CLM resumeInvoice | prepareResumeIntent 提交后 reconcileWechatInvoice→queryOrderStatus；需 prepay 才 createGenericOrder | applyWechatInvoiceObservation 内 SUCCESS 用 template→self handlePaidInvoice，CLOSED 用 template→closeObservedInvoice |
| CLM closeInvoice | prepareCloseIntent 提交→queryOrderStatus→closePaymentAttempt 原 observedWxNo→query 终态 | closeObservedInvoice 在短事务调用**本地** lockClosablePaymentAttemptForUpdate，再原 version CAS + 审计 |
| Commerce createOrder→private paymentResponse | 原 order intent 短事务提交后 createGenericOrder | 原 member/merchant/biz/request 身份与持久 orderSn 不变 |
| Commerce reconcileOrder | queryOrderStatus；弃单才 closePaymentAttempt 原 observedWxNo | SUCCESS template→self handlePaidOrder（原订单 CAS+grant 原子）；CLOSED template→closeObservedOrder（本地原 attempt/业务行锁+原关闭 CAS） |

两个 self handlePaid* 的 @Transactional 确实不会被同类调用拦截，但外层**显式 template 已建立实际事务**，不能为“修 self-call”删除它；本补丁保留这些方法正文与注解。0cb 本地 lockClosable 端口由 WechatPaymentIntentStore 获取原业务行→latest attempt 锁，再检查已持久成功事实；它不是 SDK 查询，不移出 CAS 事务。8 个 SDK 外呼源码站点、2 个本地锁端口站点及调用顺序逐字保留。B 的 SDK 外部查证/独立持久事实/拒绝 active Tx 实现仍由 B/root 验收，本片不声称已证明它们的物理锁/持久性。

真实已核调用者：ApiClubMembershipController 的 renew/resume/close，ApiMerchantCommerceController.createOrder，MerchantCommerceReconcileTask.reconcile→reconcileOrder 均未声明 outer transaction；它们正常行为不变。治理退出/支付回调调用的是本地 handle*，不是这五个网络入口，仍保留原业务事务。按0cb公开 WechatPaymentOperations 四个既有 query/close/lock 签名生成，无新增接口猜测。

测试补丁保留所有原正常金额、scope、CAS、权益与幂等场景；CLM 76→78、Commerce17→19，仅新增四个边界测试源码。CLM 原三例“允许 ambient transaction 挂起后 SDK”与本轮拒绝持锁 outer Tx 的合同冲突，明确转换为通过原 Spring proxy/事务跟踪双验 NEVER 拒绝、原 caller Tx 保持、mapper/SDK=0，不删场景/skip。跟踪双精确补 NEVER 的空事务/拒绝，以及 REQUIRES_NEW 的原深度恢复；不把 NEVER 错模拟为 REQUIRED。其余73个 CLM 与17个 Commerce 原 test body/断言逐字保留。

实际执行：verify-static.py 在临时原副本先 apply --check/apply 两片，再核对：CLM其余70个/Commerce其余17个方法体、全部 paid/grant/closed CAS、scope/快照/SDK参数/幂等规则未改；所有原 case 都有明确映射，未删 case/skip；文件门禁、diff check、有限六模式扫描。**Java/JUnit/MySQL/真实 provider/CI/完整 gitleaks 全 NOT_RUN**；97是合成后的 unit 源码条数，不是实跑通过数。没有执行 DDL、支付、关单或生产操作。

root 集成：先按目标方法应用 callers.review.patch，再应用 fixtures.review.patch，与 B fact/SDK 候选同源统一验证。若新的调用者仍持 outer transaction，应由其 owner 拆外层编排，不可退回 NOT_SUPPORTED 掩盖持锁，也不可绕过本地 CAS/receipt。暂无新业务决定或金额规则；本片不能单独解除整批资金验收门槛。
