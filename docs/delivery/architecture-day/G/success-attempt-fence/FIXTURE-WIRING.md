# 共同消费守卫 fixture 增量

结论：G域源码候选为 `0bad5ffb595c48462a2dbfe3f6567d3e967a0f46`（parent `ce4cd7d8baddcb62aad85b212fd0d89b9fd4aec1`）。本增量只交共享测试 review.patch，不修改共享测试原文件。Java/MySQL/CI/provider 全部 **NOT_RUN**；下述为源码核对，不是验收通过。

## root 串行应用

1. `historical-fixture-wiring.review.patch`：WechatVerifiedFactTestSupport.memoryHistoricalFacts 仅追加共同守卫的精确 latest SQL，无 intent 返回空；不改变 wireUnitReads、realFacts、真实 SQL 工厂或公共守卫。不从事实反推 current。原工厂3个声明及断言保留，第3个增加真实共同守卫原号重放断言，另加1条无hash但provider别名拒绝、原事实保留断言（声明3→4，未执行）。原带hash recordVerified 拒绝断言保留。
2. `signup-historical-owner.review.patch`：SignupNoticeAfterCommitPersistenceMySqlIT 的历史事实原支付为 REG99，默认 owner 却返回ID1；实际 A 成功消费后 completePaymentRecovery 会严格比对原业务ID，不能凭无ticket略过。只安装明确的 owner 端口适配，读取该夹具既有 registration_fact 行的ID/金额/状态，不从fact取身份或虚构intent；付款/IM/回滚/并发/队伍原断言不变。该类验证IM实际持久化，其付款事实和owner锁仍是明确夹具边界，不能声称付款锁/真实当前intent获真库验证。
3. `a-common-admission.review.patch`（0bad原交付）：在A共同 checkVerifiedPaymentAdmission 插入 requireConsumablePayment，覆盖取消preflight和真实消费；勿仅改真实消费分支。

两新patch按0bad/ce4c现存文件生成，源文件sha256见 fixture-patch-source-sha256.txt；root并行装配后按hunk串行合入。没有改A Funds.activityCancellation/cancelActivity、ClubEventTransfer.wireServices或B六工厂原文件。

## 本轮 helper 实际消费追踪

| 路径 | 源码核对与处理 |
| --- | --- |
| wireUnitReads → WechatVerifiedPaymentFactServiceTest | 事实持久化/读回/恢复API测试，无A/G成功消费；默认无latest不能被当冻结事实准入。保持原源码。 |
| wireUnitReads → WechatVerifiedPaymentConsumptionGuardTest | root4条有独立可变current行并覆写factIntentJdbc；含closed、身份冲突、带hash缺intent、legacy。未碰。 |
| installUnitFacts/setField(mapper) → gateway tests | WechatVerifiedPaymentFactGatewayTest、WechatPaymentSuccessRecoveryTest的领域owner均为mock，不进入A/G消费；只验证fact/router不能冒充current准入。未补统一true。 |
| gateway → WechatPayCallbackForgedSignatureTest.RegistrationFailureFixture | 进入真实A；wireParser显式安装WechatPaymentIntentTestSupport空intent map，覆盖默认factIntentJdbc；真实原查询产出的历史事实hash=NULL且providerNo=businessNo。共同legacy可准入到原CAS冲突；这些用例没有成功回执，不需要凭事实造current。 |
| memoryHistoricalFacts → SignupNoticeAfterCommitPersistenceMySqlIT | 真实A成功消费；需要精确latest无intent端口及REG99原owner适配，即上列两patch。 |
| memoryHistoricalFacts → WechatVerifiedFactFactoryTest | 增真实共同消费正/负边界源码，明确内存工厂范围。 |
| memoryHistoricalFacts → ARefundFactFixture → RefundNotificationMybatisIntegrationTest / RefundRetryAndRemainderMySqlIT / RefundManualPayoutTest | 原REFUND事实，不调用仅PAYMENT消费guard。原退款断言/金额不动。 |
| G CoopInviteServiceImplTest / CoopDepositSettlementAttemptTest / CoopDepositRefundMybatisIntegrationTest | 0bad已逐个给明确原Reference的requireConsumablePayment端口double；错误引用/准入失败拒绝。不是MySQL当前attempt证明。 |
| G CoopDepositSettlementAttemptMySqlIT | 原场景覆盖factBusinessOwner与factIntentJdbc为实际原DB；追加真实B服务场景使用realFacts。原SQL9→源码11，保持真实current，不从fact反推、不统一true。 |
| A RegistrationFundsMybatisIntegrationTest / RegistrationTopicFenceMySqlIT / RegistrationCallbackIntentMySqlIT / Wp04PaidHalfStateMySqlIT / Wp04ApprovedRefundFenceMySqlIT | 使用actual realFacts原JDBC；Funds localPaymentGuards也是realPaymentGuards spy（与外呼double分离）。没有改其B工厂/取消区域。 |
| A PaymentCallbackFenceEntryTest / VerifiedRegistrationReceiptGuardTest / RegistrationPaymentClaimFenceTest | 分别在金额/引用/忙回执/begin-payment前置否认，未实际进入新成功消费；不添加无条件准入桩。 |

Funds接缝定位：ce4c/0bad 的 RegistrationFundsMybatisIntegrationTest 全文没有 CoopInviteServiceImpl、depositVerifiedFacts 或 paymentIntentStore，也未创建deposit owner；已有A verifiedFacts与localPaymentGuards均接实际DB。故本次没有伪造一个Funds deposit hunk。如果root dirty另加了该owner，只应在该新增装配点绑定它的原业务 current store，不能整份覆盖现工厂。G实际deposit SQL夹具端口已如上单列。

## 静态与剩余

- 两patch `git apply --check`、组合暂存检查、`git diff --check`；原case名/原断言文本保留检查通过。仅静态。
- 未执行Java编译/单元/MySQL/provider/CI；不增加专项CI或改registry。
- root需串行合A共同准入与两测试patch，统一回归。E拥有的CLM/Commerce证明夹具不在本片修改，沿同一共同签名由E接线。
