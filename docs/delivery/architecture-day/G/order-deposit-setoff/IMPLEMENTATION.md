# 自动抵扣原订单应收：源码候选（NOT_RUN）

生产代码已实现不可变抵扣接线；Java 编译、单测、MySQL、CI 均未运行，不是验收完成。

- 公共业务基线：`7b1412f4cd37d293576499e92c7d44b36ee7c766`（E 原 attempt 关单、事务外精确回读、typedReference、短事务改动保留）。
- 本片 parent：`24fcaa092c2190da84533162641c130e7ff48d56`；该 parent 只冻结 A 单测供体，没有生产改动。
- 分支：`codex/architecture-day-g-order-deposit-setoff`。
- 我并非唯一开发者。`CoopSettlementTest.java` 已恢复到公共基线且不在生产增量中；A 独占该 unit 文件。先前工作保存在 parent 的 `unit-donor.review.patch`，两个原 50 元场景未丢弃，按 root 转 A 串行装配。

## 原链与实际改动

`settleTopic` 原 topic 首锁/现金与退款 current read → `settleCoopTopicFromCashBasis` 准备原计划 → 抵扣邀约当前预检 → persist 原毛额 head/claim/detail → `applyUnpaidDepositSetoffs` → 原净额与保证金收款守恒 → 现有 OriginalProducer.freezeOriginals → 写 `DEPOSIT_DEDUCTION` 正收款行 → 原保证金退款流程。所有本地写入仍在原主题事务，未新增事务或外呼。

不再改原 head.amount 或冻结原分项金额。先落原头是为了使用已有 claim/detail 和 append-only 调整机制。保证金收款行在原分项冻结之后插入，是为了避免被当成原票款分润二次冻结；冻结失败、收款行失败或邀约 CAS 失败均向原事务抛出。

仅同主题、同应缴方、COOP_ORDER 原待付正净额可扣；不扣平台/发起人非订单应收，不扣余额或他人保证金。上限为剩余欠款、原 claim 可用额、原头当前净额。invite ID → order ID/head ID → registration ID/claim ID 稳定排序。零/不足保留真实余欠，不改已批准退款算法。

复用原签名：`appendExecutedAdjustment(Long claimId, String eventKey, BigDecimal signedAmount)`。负调整 event 为 `UNPAID_DEPOSIT:<topic>:<invite>:<order>:<head>`，原 unique `(claim_id,entry_kind,event_key)` 防重；负额回读校验原 claim/head、状态和金额。正收款行 `source_contract_type=DEPOSIT_SETOFF`、`source_contract_id=实际负调整ID`，可沿不可变凭证定位原订单、报名 claim、邀约。无假 claim/会员，无新表/DDL。

当前 `deposit_status=1` 保持拒绝，未明支付不因抵扣放行；E 的锁外查证和原 attempt CLOSED 确认路径未改。完全收齐沿原 status3 语义保留原 deposit_amount；部分收齐 status5/金额为剩余欠款。重复收款不再追加调整、平台行或邀约 CAS。

原有调整、退款和 payout 继续用当前净额；这片不修改退款算法/资金 owner。两个既有 FOR UPDATE statement 仅补 `useCache=false flushCache=true`，SQL 内容不变，确保同事务插入后读到当前调整回执。

## 文件与测试源码

生产符号：`CmsTopicServiceImpl.settleCoopTopicFromCashBasis/applyUnpaidDepositSetoffs` 及其私有校验 helper；两个上述 XML 属性。接口、公共 DTO、claim append 方法、生产 schema、CI/registry 无变化。逐文件 SHA256 见 `SOURCE_SHA256SUMS`。

沿原 `Wp04OriginalRefundMySqlIT` 增加 **10 个普通 @Test 声明**（不是实跑 case 数）：

- `unpaidDepositOriginalGrossAndReceiptFreezeAtomicallyThenPartialRefundUsesNet`
- `unpaidDepositPayoutUsesAdjustedNetOnceAndKeepsOriginalGross`
- `unpaidDepositInsufficientOriginalReceivableRetainsRemainingAndReplaysNoEffect`
- `unpaidDepositZeroDebtKeepsAllMoneyAndCreatesNoReceipt`
- `unpaidDepositMultipleInvitesUseStableOriginalIdOrderAndCannotOverdraw`
- `unpaidDepositMultipleOriginalOrdersAllocateByFrozenOrderIdAndRetainGross`
- `unpaidDepositFailureAfterAdjustmentAndCasRollsBackAllLocalEffects`
- `unpaidDepositConcurrentTransactionsWriteOneOriginalEventAndOnePlatformReceipt`
- `unpaidDepositCannotUseAnotherMembersOriginalReceivable`
- `unpaidDepositUnknownPaymentRemainsBlockedWithZeroReceipts`

保留原全部声明/业务断言与隔离准入/清理。扩展阶段使用 canonical 原表与原 migration、真实 claim persist/append owner、原 Mapper/XML、真实 Spring/JDBC 事务；未手工伪造调整。付款事实仍由原 B typed fixture producer/reader生成，不能称实际渠道验证。原 prepared-plan/终态 CAS/inbox SQL fixture 的范围说明保留，新增抵扣与净额 payout 是真实 G 私有生产方法调用，不声称覆盖外部支付、prepare 全链或通知。

新断言包括原头/原分项不变、负调整和正收款互相关联、退款继续按原分项且净额读回、实际净额入余额/流水且重试不重复、多个邀约/订单确定顺序、他人原应收不可借用、未知支付零效果、失败回滚和双事务并发只写一次。同一 fixture 的 deferred 分支只延后实际抵扣以安排回滚/并发交错，不伪造调整回执。

## 已执行与未执行

已执行：`git diff --check`；静态确认 A unit 文件与基线字节无差异、原 MySQL @Test 声明保留；记录源码 SHA256。没有运行任何 Java/Python 业务模拟、Java 编译、单测、MySQL、CI 或 provider。新增 10 声明与 A 单测 donor 均 **NOT_RUN**。root 统一编译/真实库验收后才能认定行为通过。

迁移影响：复用现表唯一键和已有字段，无新增 SQL。回退须停止新结算写入后回退源码；已写调整/平台收款凭证/邀约状态不得删除或把原 gross 当 net 再付。历史原分项/调整/退款记录保留。
