# D / WP08 signup 本地 IM 原意图候选

结论：已实现首次付款与本地报名成功 IM 原意图同事务登记，提交后只唤醒原 intentId；历史半成品只唤醒已有原意图，无意图不补造。生产补丁仅一个文件，尚未编译或执行 Java/MySQL；这是供受控 CI 验证的隔离候选，不能据此正式集成、合并或部署。

## 身份与范围

- 仓库仅 `https://github.com/liboyang42-cpu/hengyin`，公共参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本轮父 source：`33ffb50086e85fca2238d9ca75df585a46226835`；父 tree：`68eea620c5812d10392a7eb986bed5b5a3d17ea1`。最初检查的联合 source 是 `765ea0366f43029b6fbd33df777e3f86e19c74e7`，已用公共源码增量同步至父 source，再做本补丁。
- 独立工作树 `/workspace/architecture-day-d-signup`，分支 `codex/architecture-day-d-signup-intent`。云端本地 HEAD 仍为公共参考基线；暂存树精确复原上述父 tree，差异、清单和远端新提交均以父 source/tree 为准，不能拿本地 HEAD diff 当本次范围。
- 未切换、重置或修改其他任务工作树；e717 / db5 / 928 / SQL75 冻结候选保持原身份。本轮单独提交，依赖父 source 已有 B 意图合同和 E IM 实现；无私有历史、凭据或新增依赖。
- 明确授权的生产范围：`CmsRegistrationServiceImpl.registerPaymentAfterCommit`、必要接口注入，以及 `completePaymentSuccess` 原 CAS 后的一处 fresh 标志捕获和一处调用传参。未修改 A 资金算法、B 消费合同、E `ImServiceImpl`、G scheduler 或共享 CI/registry。

逐文件 SHA256 见 `CANDIDATE.sha256`；未改共享实现指纹及 Java 用例方法清单见 `evidence.json`。远端 commit/tree 由交付消息给出，避免把提交自身份写入其自身造成循环。

## 失败机制、调用者与根因

原路径在付款提交后才读取 live 报名/主题并调用 `sendSystem`。进程退出、队列拒绝或派发事务失败后只有日志/告警，没有该通知的持久原意图，已完成付款的重放也不会再次注册回调。只在 afterCommit 加重试不能填补提交到唤醒之间的丢失窗口，也会使内容随 live 数据变化。

搜索结果：通知登记方法在生产中仅 `completePaymentSuccess` 一处调用；付款状态机有微信 SUCCESS 与零元付款两个入口。微信入口经 `handleWechatPayCallback` 的 inbox fence，当前外部调用方为 `WechatPayServiceImpl` 和 `OrderCloseTask`；`TeamGroupServiceImpl` 使用 `applyConfirmedWechatPaymentWithinTransaction` 的 MANDATORY 入口。零元入口由 `RegistrationCheckoutServiceImpl.finishOrder` 调用。没有增加外部 API 参数或更改这些调用方。

原 A 状态机在 CAS 失败、历史半成品修复成功后会把 `paymentTransitioned` 设为历史修复标志，因此它不能用于判断“本次首次取得付款权”。已在原 CAS 后、历史修复前捕获 `final boolean freshPaymentClaimed = paymentTransitioned`，仅将该值传给通知生产者；原 CAS 条件、金额/库存/积分/结算/扫描/状态流程不变。

## 修复合同

1. 要求原付款实际事务及同步均活跃；无事务、非法首位 ID/recipient 在 lookup/enqueue/唤醒前拒绝。意图依赖缺失或持久化失败不得假装接受，也不得退回直发。
2. `registrationIds.get(0)` 保留原顺序，事件键固定为 `registration-signup:<原首位registrationId>`。不排序，不逐 sibling 发卡，不生成临时事件号。
3. 先 `findIntentId` 回读原键。已有原意图只取 ID，不依据 live recipient/topic 重建内容；历史修复找不到原意图不创建。首次 winner 且原意图不存在时，确认原付款行、收件人与付款状态，再以既有 `signupNoticeExtra` 生成一张原卡片并调用既有 `ISystemNotificationIntentService.enqueue`。
4. 固定原 recipient、`MSG_CARD`、`报名成功`、原 extraJson；原付款与 `PENDING` 意图同一数据库事务提交。无主题独立活动卡片沿用原 helper 的既有语义，未新增缺字段 fallback。
5. 提交后只调用 `deliverSystemNotification(signupIntentId)`；删除此事件的旧 `sendSystem` 直发，避免双轨。队列拒绝、投递失败、丢唤醒均保留原 `PENDING` 源，由既有 `imNotificationDeliveryTask.deliverPending()` 恢复。
6. B 消费者锁原意图，原消息及终态 `DELIVERED/message_id` 同事务提交。重复/丢 ACK 使用同一 ID 回读原收据，不创建新业务身份；本次只复用该合同，没有修改消费者。
7. QR 循环与 WX 分支及事务同步尾部逐字保留；没有宣称修复 WX 的 UNKNOWN、授权消耗或 QR 的全部恢复。WX 没有新增查询接口或盲重发。历史已支付且没有原意图的数据不回填。

锁顺序：原付款 CAS/从单付款行 → 原事件意图键 lookup/insert（原付款短事务）；提交释放付款锁后，独立派发事务为原 intent 行 → B 原 system conversation/message/member 写入。既有恢复任务只读 pending 页并逐 intent 交给 B；它不反向取得报名行锁。未将微信调用包入原付款数据库事务，未把租约当成送达证据，未引入全站 outbox 或限流依赖。

## 最小测试与实际证据

先写 6 个 Python 源码合同检查；修补前实际结果为 1 pass、2 failures、3 errors，指出原代码没有 fresh 捕获、同事务原意图登记及原 ID 唤醒。修补后 6/6 通过。它们是静态源码检查，不证明 Java 可编译或真实事务成立。

本轮实际执行的命令、输出和范围核对见 `verification.txt`，可在仓库根目录运行 `python3 docs/delivery/architecture-day/D/signup-intent/verify_scope.py` 复核：Python signup 6 项 + 前轮 SQL 范围合同 6 项，共 12/12 通过；diff whitespace、原资金方法授权外字节、QR/WX 分支、共享实现指纹、旧测试方法保留及凭据模式检查通过。没有运行 Maven、JUnit、MySQL/Redis、后端、CI 或生产 DDL。

Java 测试源码（全部未执行）：

| 测试类 | 数量 | 作用与限制 |
| --- | ---: | --- |
| `RegistrationSignupIntentTest` | 9 | 原事务前提、首位顺序、一卡、历史无/有意图、冻结内容、拒绝与冲突零唤醒。手工同步 fixture 仅验证 producer 决策，不证明 JDBC 事务。 |
| `SignupNoticeAfterCommitPersistenceMySqlIT` | 18 | 保留原 5 个方法，扩展原付款+意图原子回滚、真实 B mapper/消费者、恢复和并发测试。报名事实使用 JDBC fixture adapter，不能冒充生产资金 mapper 验证。 |
| `StandaloneCheckoutSignupNoticeAcceptanceTest` | 4 | 保留原 4 个方法；零元付款改验证只登记一张原卡、提交前无投递、提交后唤醒一次、无旧直发。 |

S1–S8 覆盖索引（均为待受控执行）：

| 场景 | 关键断言/源码用例 |
| --- | --- |
| S1 原付款事务登记 | IT `successfulPaymentQueuesOnlyAfterCommitAndBothIndependentWritesReallyPersist`：事务内已有一条 PENDING、零消息/零队列；回调后独立事务写入且不随无关外层事务回滚。 |
| S2 拒绝、回滚零意图 | IT `rollbackOfThePaymentTransactionDoesNotQueueQrOrSendNotice`、`intentWriteFailureRollsBackPaymentAndIntentWithNoWake`、`rejectedAmountLeavesPaymentIntentMessageAndWakeUnchanged`；producer 非法身份/原 recipient 冲突/缺事务拒绝。 |
| S3 提交到唤醒窗口 | IT `commitBeforeWakeLossRecoversSameIntentWithFreshTaskAndLateWakeDoesNotDuplicate`、`rejectedQueueDoesNotTurnCommittedPaymentIntoFailure`。重建 task/丢内存队列模型，不是实际进程 kill。 |
| S4 重复/并发 worker | IT `twoRecoveryWorkersProduceOneMessageAndOneUnread`，同 intent 得同 messageId、消息和 unread 各一次；consumer 行锁由 B 所有。 |
| S5 丢提交 ACK | IT `lostPaymentCommitResponseReplaysWithoutAnotherIntentOrWake` 与 `lostCommitAcknowledgementReadsOriginalReceiptRatherThanSendingAgain`；在真实事务返回后注入异常模型 ACK 丢失，不冒充实际网络故障。 |
| S6 冲突、冻结 live | IT `sameEventDifferentPayloadIsRejectedWithoutReplacingOriginal`、`liveRecipientAndTopicChangesDoNotRebuildFrozenDelivery`；冲突由既有 B 接口拒绝，原意图不替换。 |
| S7 winner 与历史 | IT `concurrentPaymentClaimLoserDoesNotCreateAnotherIntentOrWake`、`conflictingCompletedPaymentCannotProduceIntentAfterLosingCas`、两个 `historicalHalfState...`；历史无意图不补造，有原意图只唤醒原号。 |
| S8 原顺序/通道隔离 | IT `originalFirstRegistrationIdentityIsKeptWhenSiblingHasSmallerId`，主99/从98仍用99一张卡、两次QR；原 QR/IM 独立失败两项保留。未调用真实 WX。 |

原卡片 helper 两项、资金 CAS 静态合同一项及 B 意图单测/真库测试仅列待执行依赖，未修改、未宣称通过。`evidence.json` 明确标记 `executed_by_D:false`。

## 数据、恢复注册与回退

本候选无新 migration、无 schema 变更、无 DDL 执行。依赖父 source 已有 B `im_notification_intent`、唯一键和本地消息幂等约束，必须与原报名使用同一事务管理器/数据库。部署前只读预检与 A fixture、registry 协调项见 `integration-notes.md`。

G 负责恢复任务注册/精确 target 校验。未改 scheduler、sys_job 注册或启停状态；默认暂停与保留既有暂停的合同不变。未检查实际数据库，也未启用任何任务；若该任务在受控环境未正确注册并启用，丢唤醒后的自动恢复仍不可宣称可用。

回退不能删除意图、消息、回执或审计，不能清表或释放事件身份。直接回退到旧 signup `sendSystem` 可能使已存在原意图与旧直发重复；应先停止本候选的新 producer 流量并清点/按原号处理 pending，保留 B 原意图消费者与数据，以独立修复向前推进。具体生产执行仍需用户既定部署授权，本候选不执行。

## 集成前阻塞/未验证

- 未编译，9 项 producer 单测、18 项 MySQL IT、4 项更新后的验收及 B/A 相关回归均未运行。受控验证失败须修复，不能削减断言、扩大 baseline 或跳过。
- A/root 需在其所有的 `RegistrationFundsMybatisIntegrationTest.setUp()` 正确注入新增域意图依赖及 IM fixture；该手工装配目前没有，正式成功付款路径会 fail closed。本候选未跨权修改该文件。
- root/G 需用原 runner 登记既有标准 gate 的 18 项 IT 并验收 XML 精确数量、失败/错误/跳过均为零；本候选不改共享 registry 或 dispatch CI。
- 原付款数据库实际同源、B 表/唯一键、G 精确恢复 target 与 paused 配置尚未现场只读核对；没有用 fixture 结果替代这些验收。
- 原 QR/WX 分支仍是原合同，本轮没有外部 UNKNOWN 的恢复能力或真实微信验证。

协调者报告父 source33 的迁移 layout/safety 已实际通过、D 广播 10 项新 harness 正安排受控真库；这些属于父候选证据，与本轮 signup 测试执行严格分开。
