# A 原付款消费事实与 G 准入：只读夹具检查

结论：排除 B callback、root/D signup 与 G Wp04 readleaf 后，**未找到另一个应补 A 消费 mapper 的成功付款工厂**；A48 已同时修了 Funds / TopicFence 两个实际成功付款工厂，root 必须把这两份测试差异一并取回。最新协调反馈：root 已补四个真实付款工厂的 G collaborator，**本报告不再提它们的修改**。剩余仅 **G3 的 verified busy-retain 单位夹具**；另外发现真实 gateway 负控工厂的 verified reader/inbox 前置缺口。没有修改生产、旧 fixture 或 root 文件，没有执行 Java/MySQL/CI，不把静态判断写成实跑 failure/pass。

审查源：root `0d32d888e29c6d5ef51322b10d7aca736be856ff`；A `48d24f8e0fb14ab555003f9e035b981a6807f884`（实际功能引入 `7ba40018`，48d 仅规范 review.patch 格式）；G 接口及 A 接缝提案 `8ca8f49180f967ef9cc218026a12e552922df50f`。G/root 新合成源尚未冻结，本报告只按已给精确 API/提案核对，不杜撰一个已编译合成 SHA。不能把 A 旧父整文件覆盖 0d32：0d32 还有 SDK qualifier/候补父锁等并行修复。

C 源码候选 `30676682dfa161dba593167cb06ffef9ee21a109` 与原 C 工作树均保留冻结。本报告独立 docs-only 分支/worktree 基于 0d32，不把读投影插进支付验收批次。

## 实际触达条件

- A 新消费链：`handleWechatPayCallback`（真实 `processVerified` mutation）或 `applyConfirmedWechatPaymentWithinTransaction` → `applyWechatPayCallback` 的 **可信 PAYMENT/SUCCESS** → 原 scope/金额/关账检查 → `completePaymentSuccess` 返回 SUCCESS → `recordPaymentConsumption`。后者先要求原可写事务、锁后本地 2/2/transaction/金额一致，再 `registrationPaymentFactMapper.insertConsumed` 和精确 `selectByRegistrationForUpdate` 读回同 Reference。
- `completeZeroAmountPayment → completePaymentSuccess` **不经过 recordPaymentConsumption**。报价、预下单、只建单、核销、CLOSED/REFUND、金额不符、processor busy 和故意 CAS 冲突也不能仅因字符串 SUCCESS/FAIL 就当成成功付款。
- G 提案 `requirePaidHalfStateCashAdmission(Long registrationId, Reference)` 在可信 SUCCESS 原事务、原 CAS 前调用；外层 `processVerified=false` / 已回滚后，只要已有原 verifiedReference，就调用 `retainClosedPaidHalfStateSuccess(Long,Reference)`。因此 **busy/拒绝也可能需要 G 依赖，且缺字段 NPE 可能在 recovery catch 内被转换成 FAIL**。
- 普通 A 单位/独立 SQL fixture 可明确 mock G，保留其原 A/B/通知验证范围；真实 G 准入/关账/recovery/MySQL 仍由 G 自己的场景证明。不得加 production nullable fallback、伪造 paid/VERIFIED/DONE，或把 mock 结果报作 G 验收。

## 两个已由 A48 提供完整消费装配的工厂（不要漏合）

|工厂/调用链|0d32 原本缺项、A48 已有修正|最小整合要求|
|---|---|---|
|`RegistrationFundsMybatisIntegrationTest#createDatabaseAndService`（0d32:109/132，A48:109/134）；`callback()` → `registrationService.handleWechatPayCallback`，如 `shopDayPaymentFreezesValidClubSourceAtSuccess`|A48 target:139–141 注入 B `verifiedPaymentFacts` 和真实 `CmsRegistrationPaymentFactMapper`；`buildFactory`:2675 加原 mapper/XML；`resetSchema`:254/359 原表清理后应用消费 migration；B canonical migration 与 `callback`:2435–2448 的真实 `recordVerified` 原身份已接。|取 A48 对此文件的**差异**，不是仅补一个 mock/null 字段。B `wx_verified_payment_fact` + A `cms_registration_payment_fact` 的 canonical DDL/实际 mapper/原事实 ID+hash 和库内 2/2 行必须齐全。普通 callback scope 仍沿原 inbox/PTM；A 原 96 case 不删。|
|`RegistrationTopicFenceMySqlIT#paymentService`（0d32:154，A48:159）；`applyPaidInline` → 实际 `applyConfirmedWechatPaymentWithinTransaction`|A48:165–166 注入真实 verified reader/consumed mapper；setup/reset 加 B/A migrations、mapper/XML、FK 顺序、真实 `paymentReceipts` delegate，新增原事实/回滚/重复负控。第二个 `new CmsRegistrationServiceImpl`（0d32:272）是候补 claim 故意抛错的 creation fixture，不能误算付款成功工厂。|保留 A48 原真实 mapper/typed fact/事务及每个回滚/并发断言。不能用返回 1 的空 mapper 替代读回一致校验，也不能只加表名而漏 FK/原事实。|

上述两处若 root **仅**拿 A 生产字段/SQL、没拿测试差异，可信 SUCCESS 静态链上会在新字段/statement/表或原事实条件处失败；这不是声称已经在本轮跑出了 NPE。A48 本身已有完整修正，本检查不再发一套竞争实现。

## G collaborator：四个工厂已补，余一处 busy-retain

|标识/位置|实际路径与缺口|最小 fixture 改法|
|---|---|---|
|G1 **root 已补，仅保留触达证据** `RegistrationFundsMybatisIntegrationTest#createDatabaseAndService` 的 **registrationTarget**|A48 只在 `refundService` 注入原 `coopSettlementClaimService`（约:207）；它不是付款 target 的注入。可信 SUCCESS 在本地 CAS 前就触达新 require，失败后还可能走 retain。|给注册付款 target 单独注入 `mock(ICoopSettlementClaimService.class)`；不要误以为退款 bean 的同名字段已装齐。独立 mock 避免污染原 refund owner 的交互断言；保留原 A SQL/金额/消费事实回读断言。|
|G2 **root 已补，仅保留触达证据** `RegistrationTopicFenceMySqlIT#paymentService` 的 **paymentTarget**|A48 给 refundTarget 注入 refundOwner（约:153），但 paymentTarget 没有新字段。inline SUCCESS 即触达 require；真实 G 会扩张到其 own recovery 表，不应悄悄把 A 锁/消费 IT 改成未声明的 G 集成 IT。|仅在付款工厂显式 mock G；不动真实 registration/verified fact/consumed SQL，也不修改 original locked scope / connection/latch/rollback。|
|G3 `VerifiedRegistrationReceiptGuardTest#fixtures` 中 **payments**；`validPaymentUsesVerifiedProcessorAndDoesNotTreatBusyAsCompleted`:90|已有合法 Reference/reader，但 processor 故意不执行 mutation而返回 false；新外层 retain 仍触达 G。字段未配时其 NPE 会被 recovery catch 吞成 FAIL，旧断言只看 FAIL 可能假绿。此场景不该造一个已成功消费的 mapper/receipt。|只给 payments 注入显式 G mock；保留原 busy/零 CAS 断言，追加精确原 registrationId=1 / `payment.reference()` 的 retain 调用断言，证明不是缺字段 catch 导致 FAIL。无须消费 schema。|

`g-busy-retain-fixture.review.patch` **只给 G3**，未应用：以 0d32 Guard 为精确 preimage，新增 mock/透明边界注释和一条精确 retain 交互断言，零删除行、零删除测试声明/旧断言。retention 的 true 是显式 mock collaborator 结果，不是数据库待办持久化证据。已实际 `git apply --check`，没有运行 Java 编译/测试。

root 最新确认：A Funds/TopicFence、B `RegistrationCallbackIntentMySqlIT`、D `SignupNoticeAfterCommitPersistenceMySqlIT` 四个实际成功付款工厂已补 G mock，分别保留其原 96/46/13/4 断言；这些是协调方反馈，不是本报告实跑统计。本报告不重复这四处 patch。root 正补 actual A inline→actual G admission→rollback→原 manual 身份读回的真实链，本报告不提前声称通过。G Wp04 readleaf 仍排除，真实 G guard suite 不能套用这个 mock。

## 另一个 verified-fact 前置缺口：真实 gateway 失败夹具

`WechatPayCallbackForgedSignatureTest.RegistrationFailureFixture`（0d32:600–629）实际 `new CmsRegistrationServiceImpl`，只注入 registration mapper/alarm/attempt gateway/no-arg inbox stub；`wireParser` 只给 gateway 装自己的 `verifiedFacts`，没有把同一个 reader 装到注册服务。`verifiedRegistrationPaymentConflictAlertsOnceAndKeepsFail`:473–482 原本要走 **可信 SUCCESS → CAS=0 → payment_state_conflict**：

1. `checkedVerifiedPayment` 先读未装的 `registrationService.verifiedPaymentFacts`，NPE 被入口 catch 转成 `verified_fact_missing_or_conflicting`，尚未到新 mapper/G。仅断言 FAIL 会掩盖这个差距；该原 case 还有精确 alarm 和 CAS 观察，应保留。
2. 即使只补 reader，`PaymentCallbackInboxStubs.passThrough()` 的无 reader 形式在 `registerVerified/processVerified` 仍拒绝，不能到原 CAS。
3. 该 registration 没有 ownerType/ownerId，不能跳过 `RegistrationTopicFence` 来凑绿；补这个 case 自己的合法原 scope/当前锁读，让新父锁读与原 CAS 后读分别可观察。
4. 然后显式 mock G，让原负控到达它本来要验证的 CAS 冲突；这里 CAS 不成功，**无需伪造消费 receipt**。若未来增加成功正控，才需要原可写事务、本地 2/2 原 transaction/金额及精确 consumed receipt。

最小接线方向：从实际 gateway 取其既有 `verifiedFacts`，同一对象注入 actual registration；inbox 改用带同一 reader 的既有 `passThrough(PTM, reader)` 重载；补原合法 scope/锁读；在 actual registration 注入 G mock。保留原金额、签名/独立查单、零经济变化、精确 alarm 与目标 ID/锁序断言；不能全部改 any/只测早拒绝。此项是**已有 verified/fence fixture 模型的静态缺口**，不是宣称本轮已跑出某失败，更不是 A 新 mapper 造成的生产漏洞。本报告不改共享 gateway fixture，以免与 B/root 适配竞争。

## 已核实无需盲补的工厂

- `PaymentCallbackFenceEntryTest`：前两条 SUCCESS 是 **CLOSED** 或已有 DONE；带 SUCCESS 的金额例为 99 对原100，提前拒；inline 同样金额拒绝。没有 admitted PAYMENT/SUCCESS，不因名称就注入成功事实。
- `StandaloneCheckoutSignupNoticeAcceptanceTest#signupNoticeIsSentOnlyOnceAfterZeroAmountConfirmation`：实际 `completeZeroAmountPayment`，不经过 A消费/G两方法。正价报价/重试不等于已付。
- `RegistrationTopicFenceTest#zeroPaymentRechecksOriginalMemberBeforeAnyPaymentCas`：零元/原身份故意冲突，不需已验证微信事实。
- `WechatPayCallbackForgedSignatureTest` 其余真实工厂是 missing registration / malformed payload 等早拒；大量正向 HTTP/签名测试使用 **ICmsRegistrationService mock**，不是执行该消费实现。
- 其余创建/自玩/积分/核销/QR/read/回滚负控见完整 inventory。消费仅有上述两个生产调用点；实际现金成功的其他生产入口是 `TeamGroupServiceImpl` 取消处理中 APPLY_PAYMENT → inline apply，已在上述 A SQL 工厂覆盖的服务装配内。不要把 verifyAndSettle 当付款确认。
- `SupportedStackServiceCyclesTest#registerInertCollaborators`:472–499 按所有真实类 `@Autowired` 字段类型自动注册依赖，新增 mapper/G 类型会被其装配闭包处理；不执行付款，无须加消费表或伪造事实。这不是实际 Spring/MySQL 付款验收。

## 完整性与剩余

`factory-inventory.json` 固定 0d32：66 个 Java 文件提及该实现，52 文件有 63 个 concrete new 表达式；另一个 Spring 反射装配，剩余13为源码/元数据/接口 mock 等引用。每处 constructor 附路径/行号，保留实例变量/方法提及与分类，方法提及不是动态覆盖率。全 src/test 未见其他语言/XML 的该实现工厂，也未见测试中手工实现 ICoopSettlementClaimService 的匿名类因此漏新抽象方法；Mockito/动态 proxy不需新增方法体。

轻量实际检查只有公共 git 源读取/限定文本调用链检查、提案应用检查、原测试声明数/零删除行检查、diff/秘密扫描。没有 Java/DB/CI、没有生产访问，不能给新的 source-bound 单测/真库通过数。root 统一合成 A/B/D/G 后执行；普通 A 的 G mock 范围需与 G 真实 guard/MySQL 单独标记。
