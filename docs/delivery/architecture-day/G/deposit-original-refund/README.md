# 押金退款原付款身份接线

结论：本片将 CoopDepositRefundService.refresh 的生产提交调用改为 B 新六参端口，Reference 为第一参；不再使用五参 submitRefund。parent `53680615aa339e3dbcf46109329399beae5fa371`，独立分支 `codex/architecture-day-g-deposit-original-refund-20261024`。只改G退款Service、原Mapper接口/XML及两类测试，不覆盖其他owner。

**组合依赖**：parent尚无B新六参声明/实现，root必须同批串行装配实际 B 端口：
`submitRefund(Reference originalPayment, String outTradeNo, String outRefundNo, BigDecimal refundAmount, BigDecimal totalAmount, String reason)`。
本片没有代改B接口、SDK、代理、stub/其它implementations；单独parent+本片不是独立可编译制品。Java/单元/MySQL/CI/provider全部 **NOT_RUN**。

## 实际链路

既有 requestLocked → beginDepositRefund(固定原退款号/退款额) → 物理提交后afterCompletion资源脱离 → refresh领取原邀约租约 → 锁外查原退款号 → 仅NOT_FOUND进入派发准备 → 新短事务锁原邀约、复核同token/原no/金额/已保存paid TX → Mapper按 **PAYMENT + 原业务号 + 已保存paid TX** 精确读不可变事实（最多2条用于拒绝歧义，不选latest）→ B requireVerified原ID/hash → 核对原biz/TX/币种/原全额及退款快照 → 原租约renew成功提交 → 锁外六参submitRefund → 原token回写。

- 已保存的 deposit_paid_transaction_id + deposit_out_trade_no 是既有本地消费锚点。用这两个持久原值唯一定位现存immutable事实，不需要新增表/列、历史回填或新成功推断。精确fact查询是本次REQUIRES_NEW短事务在邀约锁后的首次一致性读取，B原fact writer亦先锁同邀约，故不继承旧RR快照；SQL禁缓存/清缓存，不增加全fact表的FOR SHARE扫描锁。歧义不按当前merchant、最大ID、最新成功或current alias择一。
- Reference由确切锚点查询并经B读取原事实，作为本次不可变派发快照绑定原退款号/金额。后续重试仍从相同持久原锚点精确核对；多出冲突证据只会拒发。
- 原总额来自可信原totalFen，必须等邀约已存deposit_amount；退款额沿已存deposit_refund_amount，不生成/重算金额，正数、分可精确表示且不超过原总额。
- 同根因的handle读回将refund与冻结退款额比较、total与原deposit_amount比较，避免把退款额当原总额；不新增退款申请资格或部分退款金额算法。
- 缺原TX、缺fact、多fact或原金额冲突：UNKNOWN、原号/金额/held状态/事实保留，告警并可按原号对账；绝不生成DEFINITIVE_FAILED/退款成功或切另一原单。原证据恢复后同号可继续。存储技术异常继续抛给原reconcile失败路径，不伪造成渠道否认。
- 原legacy4/归档仅查不发、PROCESSING只查、真实终态读回、晚回包不覆盖SUCCESS、退款与抵扣互斥、提交前回滚零外呼、afterCompletion脱离边界均保留。

## 测试源码与静态

CoopDepositRefundMybatisIntegrationTest 原29个普通@Test及业务断言保留，新增7个（源码声明36，未执行）：

1. refundUsesConsumedTransactionEvenWhenANewerSuccessHasAnotherProviderAlias
2. missingConsumedTransactionNeverDispatchesOrGuessesTheOnlyAvailableSuccess
3. unrelatedSuccessCannotReplaceTheConsumedTransactionWithoutItsOwnFact
4. ambiguousOriginalTransactionAcrossMerchantsNeverSelectsTheLatestOrCurrentMerchant
5. frozenRefundAmountDoesNotReplaceTheOriginalPaymentTotal（只装配既存冻结快照，非新金额规则）
6. originalPaidAmountMismatchPreservesTheFrozenRefundAndNeverSubmits
7. recoveredOriginalEvidenceResumesTheSameFrozenRefundInsteadOfCreatingAnotherIdentity

原SQL夹具追加加载现有B canonical inbox/fact/intent DDL和actual realFacts，独立付款证据在业务事务前建立；不由fact反推current、不以统一true替代锁/SQL。第101活跃退款行显式补其自己的paid TX/原事实，其余100条归档只查断言保留。SDK仍是double，不能声称真实provider验证。

CoopDepositRefundAlarmTest 原3条不删/不减断言，仅装配明确的原Reference证据端口并迁六参SDK桩；不当作真库证明。原源码断言/方法名保留、XML解析、diff空白、生产唯一submit六参形状及原afterCompletion字节检查为轻量静态范围。未运行Java/DB/CI，无验收结果。

迁移：本片无新DDL。依赖B现有已审事实表和接口合成；测试仅声明其原DDL在获准scratch路径执行，由root统一安排。回退代码保留原业务/事实/退款身份；不能用旧五参执行器重新派发已获新证据绑定的退款。不得删除事实或清零资金记录。
