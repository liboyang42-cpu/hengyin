# A：347b 资金/核销 unit 与存储失败完整接线

本片只修六个独占测试文件，未修改生产或共享 factory。原 unit 失败来自 run `40220558637` / job `121251116626` / artifact `12609615426` 的 root 已核原 XML；同源存储失败由 root 追加本包。本片没有运行解析器、测试、编译、数据库、子进程、HTTP、渠道或 CI，全部新验证 **NOT_RUN**。179 个失败项不是179个业务 BUG；其它业务/客户端旧结果不转签本片。

- parent：`347bcd0226c3bed71616e38ea7c1391fc76efe5c`。
- 独立分支：`codex/architecture-day-a-funds-fixtures-347b`。
- 独立工作树：`/workspace/hengyin-a-funds-fixtures-347b`。旧工作树与所有已交冻结包保留。
- 后像清单见 `SOURCE_SHA256SUMS`；root 唯一集成和实际复验。

## 1. OWN_C04_22 stale UNKNOWN

实际路径：`syncPendingWithdrawals → prepareChannelQuery → checkedFacts/原 transfer reader → queryTransferResult(originalIntent) → recordChannelFact 当前锁读 → updateReceiptIfProcessing`。

现生产在 `recordChannelFact` 锁内重读已成功或冻结的行时先拒绝，原 fixture 在渠道回包时就替换成这种行，因此本来不会再走后面的 receipt CAS。原失败是过早的交错点与必须调用 receipt CAS 的断言相冲突，不是可以删除该断言的理由。

- 原 `staleUnknownQueryCannotWriteTerminalReceiptOrEscalateFromOldSnapshot` 保留名字、receipt CAS verify、终态不覆写/不升级人工/不返余额等所有原保护。
- 沿既有 `CashWithdrawEvidenceFixture.seedSubmissionIntent` 所声明的历史 FUND_FACT 原单，直接读实际 DTO/原 hash；不增加第二套 snapshot、不用当前 appid 重建原请求。fixture 本来已经包含该历史 DEBIT/SUBMIT，本片未为生产补造 DEBIT。
- 查询必须传相同原 DTO；让并发终态/冻结在 UNKNOWN 通过当前事实锁后、receipt CAS 前获胜，CAS 返回0；将被读取的原单时间也设为旧时间，确保“失败 CAS 阻断后续超时升级”具有实际前提。新增断言还验证观察对象无 lifecycle status、原 DEBIT 仍恰一条且服务不插入新 DEBIT。
- 新增一条普通 `@Test`：`terminalOrFreezeCommittedDuringQueryIsRefusedBeforeAnyNewObservation`，保留原来更早的查询中交错窗口，并明确断言当前事实锁已拒绝时不追加证据、不调用 receipt CAS、不升级、不返余额。没有把原 case 的 verify 反转/删除来拿绿。

这是 Mapper/渠道 double 的实际调用与交错源码，不是 MySQL 锁/CAS/并发已执行证明。

## 2. RestoreMoneyDispatchTest master-SUCCESS 子进程

实际路径：固定原 child `masterObservation → real CashWithdrawServiceImpl → WechatPayServiceImpl → real WechatPaymentSdkGateway.queryTransferResult(original master DTO)`。

`gateway(sdk)` 原 helper 只装四参 `TransferClientBinding`，没有装 `wxPayConfig`。当前真实 gateway 对 master-V1 还会读取 `wxPayConfig.getMerchantId()`；缺配置会被其原不可核验 catch 保守返回 UNKNOWN，不能进入成功 CAS，最终 child 保留 exit1。这个机制来自实际 child/网关源码，未声称取得新 child 动态堆栈或实跑结果。

修复只在原 helper 装真实 `WxPayConfig` 的固定原 fixture 商户 `merchant`、应用 `wx-original`，与 DTO 和四参 SDK binding 原值一致；没有修改生产网关或重算两 V1 hash。原父进程等待/非零失败/清理、全部原 case/金额/原号/hash/UNKNOWN/零新提交/零返款断言保留。新增原 master 场景对 batch/detail SDK 边界实际调用次数的断言，避免“根本没查渠道却看似 UNKNOWN 正常”。

已读取直接恢复 money child `RestoreWithdrawalReconciliationFixture.java`：它使用原 Store owner 和明确的 provider journal double，并不调用这个缺配置的 gateway helper；真实 SDK dispatch restore 负控又在原 guard 处拒绝。本片没有为该 child 改写历史来源或自动补行，故该文件保持原字节。

## 3. DoublePaymentReproductionTest 核销正例

实际路径：controller → 真实 `RegistrationRedemptionApplication` → `verifyAndSettleForOwnStation` → topic/registration/station 当前锁读 → 原核销 CAS/scan/audit → 真实 `recordConfirmedStationFulfillment` → 原主题结算/claim/detail。

原 winner 缺 station ID/delFlag，且手工 registration owner 没装当前 station Mapper/topic fence、有效生成 audit ID 与新 G 端口，因此原归属保护正确拒绝。补齐合法前提，未移除保护：

- 同原 topic/merchant 的 station `601`、auditStatus1/del0，候选和当前锁读使用同一控制行；原 registration 明确未核销，仍是原已支付/已报名成功行。
- 原 registration service 用现有 `TransactionInterceptor`/注解和既有 unit transaction fixture 代理；station 当前读、核销 CAS、audit insert 均断言 Spring 原事务 active。topic close 使用其真实 REQUIRES_NEW 入口对应的既有 unit manager。Mapper doubles 不证明物理 SQL commit/rollback，未新增生产 no-op/fallback。
- audit insert=1 后写真实控制 generated ID `701`，保存实际事件供同事务 G reader 回读；补齐真实 topic/registration/audit/order 读取装配，并将已有真实 `CoopSettlementClaimServiceImpl` 同时接入核销 owner。
- 原订单 terms 没有 `supplyBillingScope`，由真正 `CoopOrderStationScope.read`/G owner 返回 Optional.empty；未 mock 统一成功，也没有给旧订单补造新 V1 归属凭证。新断言核对 audit 的原 registration/merchant/station 关联，并断言没有插入新 V1 receipt。
- 原正例名称及 ¥100 基数、¥30 唯一 coop 商家款、零 settlement_detail、原 claim/order/registration 身份、重复结算不二写/不二付等全部原断言保留。

## 4. CashWithdrawFundEvidenceMySqlIT 原64项装配

原 job `121252559756` / artifact `12609359481`：root 实际记录64项、0failure/7error、CLEAN。没有据此声称其它场景覆盖本片。

- `originalIntent(w)` 原来是 Mockito `argThat` helper，不是持久 DTO；真实 gateway 三正例把它赋值再调用 `requestHash()`，必然得到 null，并残留未消费 matcher 污染后续 stubbing。将 matcher 改为语义明确的 `originalIntentMatcher` 并适配所有原 stub caller；值读取改为 `readOriginalIntent`，从该提现唯一 SUBMIT_INTENT 当前证据读原 DTO、校验原 hash/单据关联。不是挑最新 fact 或用当前配置重造 hash。
- 两个既有真实 Mapper spy 注入点已经是 `doReturn/doAnswer` 风格，本片没有改成 `when(any())` 执行真实写方法，也没有修改那些回滚业务断言。
- 原 `outerTransactionIsSuspendedBeforeSubmittingToChannel` 保留名字/回滚后余额70与原 SUBMIT 数量断言。当前生产明确要求事务外调用，不能靠恢复自动悬挂任意持锁事务来迎合旧夹具。现在先断言 active outer TX 直接调用确实零行/零SDK拒绝，再在无资金行锁的测试 caller 边界用真实 DataSourceTransactionManager 的 NOT_SUPPORTED template 暂停该受控 outer TX；原 `accepted()` 继续在 SDK 边界核无 active TX、已提交两侧原证据与原 hash；恢复后回滚原 outer TX，原扣款/提交仍独立留存。
- 原64普通测试方法/SDK未入证明/原一次扣退/UNKNOWN/回滚/金额/清理断言全部保留，无新测试声明或生产注解改动。

## 5. FundsProcessRecoveryMySqlIT 原14项时间序列化

原 job `121252564953` / artifact `12609691145`：root 实际记录14 error、CLEAN。失败在报告完整保护行的 Jackson 序列化，`frozen_until` 是 JDBC LocalDateTime，默认 ObjectMapper 未注册已安装模块。

仅为该测试原 ObjectMapper 注册现有 `JavaTimeModule`（与 `JsonUtils`、`TopicContentGraphMySqlFixture` 使用相同已安装模块），其余 mapper 默认选项不改。原 `SELECT *`、时间/类型/金额列、完整比较范围及 digest 输入全部保留；没有转成忽略时间、窄投影或字符串兜底。原14项物理 kill/reboot、原身份、资金、高水位、UUID、cleanup 源码均保留。`FundsRecoveryProcessProbe` 只序列化其固定诊断/基本协议字段，不是这次完整保护行的序列化 owner，故无需改动。

## 6. RestoreMoneyDispatchMySqlIT 原1项与子进程安全诊断

原 job `121252565184` / artifact `12608719142`：root 实际记录固定 child exit1 failure、CLEAN；旧回执没有可公开的具体阶段，不能把未知原因伪写成唯一已实证根因。

源码存在确定的 canonical 表排序错误：`wx_pay_order_intent` 原主键是 `(mchid,out_trade_no)`，没有 `id`；完整行快照仍以 `ORDER BY id` 读取它。改为该真实复合主键排序；保留此前 `cms_registration_payment_fact.registration_id` 的正确主键、全部 `SELECT *` 列和原比较范围。没有改 schema/旧 SQL 或放松高水位/hold/原 fact/回调/清理断言。

两个原 restore child（本类与 unit `RestoreMoneyDispatchTest`）现通过固定小于128的非零退出码携带源码枚举 phase/type，父用例的失败信息只输出 `childExit/phase/type` 与原固定 scenario。非零仍必失败，不跳子进程/不自动重跑；进程外异常或信号码保留 UNKNOWN。禁止输出 exception message、env、密码、SQL、provider 或原始堆栈，仍丢弃原 stdout/stderr。SQL child 初始化纳入同一错误捕获，保留首次失败；原 cleanup/精确库名/独立 absent 核验与失败语义不减项。没有运行这个新诊断协议；root 复验仍可能显露其它更早前置，不能只凭源码声称原1项已通过。

## 交付范围与验证状态

仅六份测试源码与本 A 目录文档。`CashWithdrawServiceImpl`、`WechatPaymentSdkGateway`、`CmsRegistrationServiceImpl`、`RestoreWithdrawalReconciliationFixture`、G 结算、H game、D helper、C/F 入口及 root registry/CI 均未修改。没有新依赖、表、权限、费用或历史 DDL。

原所有失败保留为原运行证据；本片只新增1条普通单测声明，不是1条通过。只做源编辑、git diff/hash/公开前秘密扫描；**新行为、解析检查、Node/Java/编译、MySQL、真实渠道、故障和 CI 均 NOT_RUN**。root 按同源组合安排验证，不能用本源码修复声明关闭运行验收。
