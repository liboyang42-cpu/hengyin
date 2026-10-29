# CLOSED 原单守卫与退款乱序失败投影

结论：本候选实际修改 A 调用者和夹具；不是 B 源码复制。Java/MySQL/CI/DDL/SDK 均 **NOT_RUN**。

## 身份与集成

- 工作区 `/workspace/hengyin-a-closed-success-guard`，分支 `codex/architecture-day-a-closed-success-guard`。
- 施工父 `2454c644508ca9e4ef1b2c1565d271bd56c1cd92` 是隔离的本地合成父，保留已交 `48d24f8e0fb14ab555003f9e035b981a6807f884` 与 `57e99075c0f98b2ce7592b3521aebc42b78eafec`。
- root 已取前两片，应只 cherry-pick 本片最终单个提交，不能整分支覆盖联合源。保留 root 的出站 `@Qualifier("wechatPaymentSdkGateway") ObjectProvider<WechatPaymentOperations>`、fd350 创建首锁、D 通知、G 原分项等并行 hunks。
- 编译与运行依赖 B `13becff8bb4099ee1ee2a53e3eef280d7f2f18b3` 的真实 `lockClosablePaymentAttemptForUpdate`、`hasConfirmedExternalSuccess`、`findRefundSuccess` API 及实际 fresh reader/Mapper。父本身未复制该 B 源，**不声称本候选可脱离 B 单独编译**。
- 48 冻结不改；报名消费关联 reader 按 root 新裁决由 A-owned 纯读 leaf 提供、G 在原锁/事务内当前读，B 只校验精确 Reference。本片不另接旧的 B 反向依赖报名 Mapper 提案。

## 已确认机制与实际修复

1. `CmsRegistrationServiceImpl.applyWechatPayCallback(CLOSED)` 原调用只验证当前 attempt，不能阻止已持久 PAYMENT SUCCESS、业务回滚后仍被 CLOSED 关掉。改调 B 新 guard；失败继续原 `payment_attempt_conflict`/inbox 重试，不释放积分、不创建新身份、不伪造 DONE。
2. 原 `OrderCloseTask.closeObservedRegistration` 的旧 gateway 先锁报名行，再调用资源释放，没有先拿 topic 栅栏。新增内部窄方法 `ICmsRegistrationService.settleClosedPayingOne(CmsRegistration observed,String observedWxOutTradeNo,String releaseReason,String updateBy,int finalStatus)`；由原 Spring 事务入口复用 `RegistrationTopicFence.lock`，当前复核原 topic/activity/父订单/member/registrationNo，再调用 B 原 attempt + SUCCESS 守卫，最后调用原 `settlePayingOne` CAS/释放实现。TeamGroup.closeLocally 与任务都委托该方法。任务移除已无用途的 TransactionTemplate/manager 注入。原方法 API 和资源释放算法保留，实际生产 caller 已迁移。
3. `RefundServiceImpl.handleRefundNotify` 中 ABNORMAL/CLOSED 原本可在外部 REFUND SUCCESS 已持久、但本地 payout 仍 0 时置 2 并开放人工调查/支付流程。现于原 topic→报名→退款锁内、原金额与身份校验后，通过 B `findRefundSuccess(mchid,refundNo,refundId)` 查确切原事实。已存在返回 false；缺原身份、冲突、读取异常均由原 inbox 失败回滚/重试路径处理，不能按空记录放行。真实 SUCCESS 单独沿其原 Reference 回放，G 冲回与 DONE 原事务顺序不变。PROCESSING 原无写逻辑保留。
4. 本地原终态 1/3/4 本已由 `RefundApplicationMapper.updatePayoutResult` 条件保护；本片没有重写该 CAS、金额、积分、人工双审或通知算法。审核通过仍不等同渠道成功。

## 边界与未闭窗口

- B guard 的“无已持久成功”针对其受控读取时已经提交的事实；本片不声称覆盖随后发生的独立事实提交。B 的意图/事实生产串行化与重复 claim 修复仍归 B/root，不能以本片替代。
- 新 `settleClosedPayingOne` 是内部调用口，前提仍是原调用者已查证/确认关闭；新 guard 本身不把 NOT_FOUND、超时或 UNKNOWN 变成未执行证明。调用者原查单和 SDK 时序未改。
- `OrderCloseTask.closeExpired` 的本地待支付超时入口不是本片的 CLOSED 观察路径；未将它推断成已受理失败，也未扩改其金额/释放规则。
- CLM/商城/保证金不属 A，`other-domain-owners.review.patch`、`other-domain-fixtures.review.patch` 与 `g-deposit-close.review.patch` **未应用**；上下文在 B13 精确公开 blob 临时副本上通过 `git apply --check`，来源/目标 SHA256 见 `owner-patch-bases.json`。保证金精确符号是 `CmsTopicServiceImpl` 原 `updateDepositIfCurrent(1→5)` 前守卫，沿其原 topic/invite 锁及同事务 CAS；仅一行 API 与对应两份夹具符号迁移。由 root 交 owner 串行应用；B/G/CI/schema 正文零改动。

## 测试源码与验证

`verify_static.py` 复用冻结 `ci/real_storage_it.py.scan_java` 统计普通 @Test；只读 Git/Python，不启动 Java/存储/CI。实际执行结果见 `static-result.json`。

| 源码 | 父→本片固定 @Test | 实际执行 |
|---|---:|---|
| RegistrationTopicFenceMySqlIT | 34→45 | NOT_RUN |
| RegistrationFundsMybatisIntegrationTest | 96→96 | NOT_RUN |
| PaymentCallbackFenceEntryTest | 10→11 | NOT_RUN |
| RefundNotificationMybatisIntegrationTest | 9→9 | NOT_RUN |
| RefundManualPayoutTest | 19→23 | NOT_RUN |
| PayingSettlementSingleImplementationContractTest | 2→2 | NOT_RUN |
| OrderClosePayingReconcileTaskContractTest | 2→3 | NOT_RUN |

新增真库源码覆盖：已存付款 SUCCESS 拒绝 CLOSED、无成功事实的首次合法 CLOSED、原关单 CAS 一次且权益释放窄 mock 调用一次（真实积分/库存仍保留原 96 条断言）、topic 阻塞期间新成功提交后的 fresh read、原 scope/member 改变拒绝、旧 wx attempt 拒绝、释放异常整单回滚、ABNORMAL/CLOSED 两种已存退款 SUCCESS 阻断后原成功可恢复、两种确无成功事实的合法人工调查路径。B gate 在此为窄 mock，事实 reader/业务 XML/inbox/事务为真库接线，**不冒充 B SDK/intent SQL 两端验收**。新增单位源码还覆盖读取不可用与缺 refundId 零变更，以及任务不得回退到无 guard 的释放口。

原 34/96/10/9/19 个测试正文逐字保留（96 条只将既有 fixture stub 的旧 guard 名替换为新名）；另两份 contract 原用例保留，仅随调用迁移并补 guard/唯一资源 owner 断言。原经济 CAS/释放、付款消费、退款终态/G/通知方法体核对不变。`git diff --check` 与新增差异的凭据形状扫描通过；不以此替代编译或业务验收。

## 迁移、回退和下一项

本片没有 schema/DDL/依赖/权限变更。沿用原持久事实与消费关联，不删除任何事实、回执或审计；回退会重新暴露已确认漏洞，不应只撤 caller guard 而保留新网关成功恢复语义。统一验证前必须组合 B 真 API、root 当前 source/registry/fixture，并验证普通 CLOSED、持久成功恢复、并发与回滚；本片没有 dispatch。随后继续按 root 合成结果核对尚未接完的消费 reader/G 事实接缝，不修改冻结 48 或他人源。
