# SUCCESS 发布与原单关闭互斥接缝

结论：B13/df72/B30 的已持久成功检查仍有实际可达提交窗口。此片交 A 独占的一个确定性交错真库测试源码、未应用的11行调用补丁和可直接转发的 B 合同；**未修改 B/root/G/F/现有生产源，未声称窗口已经修复**。B writer 共同互斥与 A/G 锁外 SDK 阶段接线合成后才能闭合。

观察源：root `99d84ce23afe8852d642ba21d791662ad06a9ebd`；B `13becff8bb4099ee1ee2a53e3eef280d7f2f18b3`、`df72a54d73ad168f26277f8af2bf14d1cb4b05b8`、`30d88bdc5401f9b64c0a99656b466d494b454d17`；本片已移到公开 root54 `54ab9282bee31c7b6dd276682f4c86e9903709c9` 为实际父，保留其全部并行源。只取本片增量，保留 root99 的消费 readleaf/factory，不能覆盖它们。

精确顺序：

- Tclose：A `settleClosedPayingOne` → `RegistrationTopicFence.lock`，持 `cms_topic.id`→活动/父单→`cms_registration.id`；B `lockClosablePaymentAttemptForUpdate` 再锁 `wx_pay_order_intent(biz_type,biz_id)` 最新原 attempt；`hasConfirmedExternalSuccess` 在独立纯读事务中返回 false。
- Tfact：此时 SDK 已在另一路返回 SUCCESS，B `recordVerified` 的独立事务只 `INSERT IGNORE wx_verified_payment_fact`，其外键/唯一键没有锁原 registration/intent。df72 的提前检查也没有改变 writer。该事务可提交。
- Tclose：继续原 `closeExpiredPaying`/释放并提交。因 fact 与 CAS 没有共同互斥，单纯再查一次只会挪窗口。

最小共同互斥是现有 `WechatPaymentOrderOwner.lock` 的原业务行。B writer 不反调 topic，不在保存事实后持锁调用业务；因此 writer 原业务→事实、A topic→原业务→attempt→纯读→CAS 不构成 business→topic 逆序。共享 actor/topic 权限仍由 A 原锁下验证；事实发布不做资金到账推断。

**自锁与 SDK 禁区（必须一起合成）**：root99 TeamGroup `cancelActivity` 拿 topic/activity 锁后执行 `settlePayingRegistrationsBeforeCancel`，ClubEventOps 外层也已持 series/活动锁；它们仍可能在这个事务中 queryOrderStatus。G CmsTopic 取消保证金同样在原事务。B13 close 的 currentAttemptClosable 已拒绝事务内 SDK，query 尚无这个入口前拒绝。不能让新独立 writer 在这些旧调用下等待外层同一行，也不能持业务锁做 SDK。锁外预查/持久事实→原事务内重新验证和消费精确原 Reference 的 caller 阶段拆分是必需接缝；此处未用禁用正常取消/假成功/NOT_SUPPORTED 伪装完成。具体 B 边界见 FORWARD-TO-B.md，由 root 协调 B/A/G 成组落源。

root 已接入 A4092+B30+G8ca，原 `releasePaymentAttempt/closeExpiredOne/settlePayingOne` 现在已有 `hasVerifiedSuccessBeforeRelease`。不重复该 patch，不覆盖 helper。A caller 已持共同原业务行，不需要另一把锁/重复读取；writer 修复属B。root补授晚到恢复后，本片另交 `a-closed-success-recovery.review.patch`（base54，仅11行新分支，未应用），在已核验原Ref且已持原业务锁的SUCCESS分支，对payment=3/reg∈{1,3,4}先持久人工恢复身份，再按原失败路径返回，放在G admission之前；不重写denial helper/readleaf。依赖B提案真实API，签名/原身份键详见转发合同8–12项。测试在全部 caller guard 之后的 mapper CAS 前暂停，适配 root 新 helper，不把“第一重 guard 后、最后一重 guard 前”当最终窗口。

新增 `PaymentSuccessCloseSerializationMySqlIT`：1 个普通 @Test，无 skip/动态计数。真实 canonical intent/fact migration、A报名 XML/CAS、B owner/attempt/fact SQL 和原 Spring 事务；topic 投影是明确的实际 JDBC 行锁 adapter，权益为窄 mock，无 SDK client。测试先暂停 close 在最后一重 guard 与真实 CAS SQL 之间，再放真实 writer：旧 B 允许真实 fact commit，随后实际 CAS/关单提交；查库 expected0/actual1 应暴露缺口；新 B 由既有 owner 注入并等待真实注册行，CAS提交后才能插入，原迟到 SUCCESS 须同提交保留精确 Reference、审计与MANUAL_REQUIRED outbox任务；原身份可查询且重复不多建。first原单是真实standalone活动，G mock触发即失败。真实A inline恢复hook外层回滚后任务仍保留，不能只剩已验事实而无人知道。反序要求先保存的 SUCCESS 拒绝关闭。另实际 MySQL NOWAIT 3572 断言证明 registration 与 attempt 锁在持有，两个自有 worker 全部 finally 释放/退出后才清理唯一 scratch schema。

本机仅执行 Python/源码检查、diff/凭据形状核对。Java/MySQL/CI/DDL/SDK **NOT_RUN**，不存在实际红/绿业务回执。该测试作为待统一验证的源码，不是单独可验收的上线片；依赖 B30及 writer/恢复协议实现、root54与该A调用补丁，不能在缺 B 原类型的旧父上宣称独立编译。root 新登记需 1 个固定测试，prefix `architecture_a_success_close_`，GitHub-hosted 且原 B_PAYMENT_INTENT_DDL_APPROVED/B_PAYMENT_FACT_DDL_APPROVED=1；不修改 CI registry 或触发 CI。
