# ca0e 最终真库失败定点修补（源码候选，未验收）

Parent：`d19276f56b3eb2a34ed8fab06e41ea8ea654a0d0`。
独立工作树 `/workspace/hengyin-g-notice-fixtures-d192`，分支 `codex/architecture-day-g-notice-fixtures-d192`。不是独占仓库；没有修改 A/E/shared helper、生产方法、历史 migration 或 CI。

## 已写入的增量

- `SignupNoticeAfterCommitPersistenceMySqlIT.concurrentPaymentClaimLoserDoesNotCreateAnotherIntentOrWake`：原屏障在已经持有原报名行锁的 `claimPaymentSuccess` 中等待第二线程；第二线程正在等同一行锁，原日志为 `CyclicBarrier.dowait` TimeoutException。将屏障前移到两个工作线程进入业务事务之前；原 SQL 条件 CAS、两个 SUCCESS、唯一 intent、仅赢家两个 wake、恢复后唯一消息全部不动。没有 mock 新的成功入口或删除锁。
- `ClmCommercePaymentProofMySqlIT`：旧审计 migration 及其严格原始形状断言照常执行；再按已有 `ClubJoinGovernanceMySqlIT` 的装配方式，抽取并原样执行 `migration_approval_governance_20260828.sql` 中唯一的 `ALTER TABLE business_audit_event`（actor_type、request_id 非空）。不手写缩减审计表、不 mock 审计。所有原支付事实/FK/hash/current/closed/恢复票/权益断言不动。
- `CommittedOfficialNoticeMySqlFixture`：**仅失败诊断，尚未修复六类前检失败**。D→C canonical 顺序原本已经正确，未找到有证据支持的装配顺序调整。保留完整 C 原前检/DDL/读回，不更改旧迁移、不绕过 CAST。失败后附加本测试独占库的 MySQL 版本、sql_mode、原 SHOW CREATE、列属性和 CHECK_CLAUSE，并重新抛出同一失败；不读通知正文/支付数据。现有日志没有这些实际元数据，不能从“JSON_EXTRACT 负分支失败”猜具体不匹配项。

## 必须由 root 串行应用的共享接缝

`shared-consumption-denial.review.patch` 只改变 `WechatVerifiedPaymentFactService.requireConsumablePayment` 的事务回滚声明：MANDATORY 和所有 current/closed/fact 校验仍在，ConflictException 仍原样抛出；明确的纯读准入拒绝不再把调用者的原事务标为 rollback-only，其他异常照常回滚。该方法只 fresh-read / lock，不写 fact、ticket 或业务行。

实际链：`ClubMembershipServiceImpl.handlePaidInvoice` 的 TransactionTemplate → `applyVerifiedPayment` → `currentInvoicePayment` → 真实事务代理的 `requireConsumablePayment` 抛 ConflictException → 原领域捕获并返回 false → 原事务提交抛 UnexpectedRollbackException。Commerce 同样通过 `currentCommercePayment` 捕获。原 `latestClosedOriginalSuccessRetainsItsFactAndManualTicketWithoutLocalConsumption` 继续要求 false、零本地效果、原 fact 保留、原 ticket=4，且新 attempt 后仍拒绝；不把其预期改为异常，不吞 UNKNOWN/数据库错误。共享生产文件未直接编辑，供 root/A 串行合并。

## 既有执行证据与剩余项

只读取既有 `ca0e0d2598727f6e567c802b7a664f9634d945c6` 的 run40122516880 完成日志：Signup job120957030519 的屏障 TimeoutException；CLM job120957029408 的 actor_type 缺列与原消费链 UnexpectedRollbackException；BeforeStart job120957029411 的 C migration statement#1 / ASSERT_FAILED_before_start_schema_preflight。没有下载到原 XML，不将日志称为 XML。

六类共用 notice fixture 的前检失败仍 **OPEN**：BeforeStartNotice / CommittedOfficialNotice / CoopComplaintCommittedNotice / ComplaintCloseReporterNotice / ChapterApplicationRound / ChapterApplicationOutcome。需要真实失败时上述元数据来裁定；如确属 canonical 前检问题，应交 root 受控前向修正，不能改已有 migration 或弱化门禁。

本候选 Java / MySQL / Redis / provider / CI 均 **NOT_RUN**。仅编辑源码及 git diff 静态检查；不宣称已修复通过或最终验收完成。原 case、wrapper、业务断言不删不改，未新增业务场景、schema 或支付事实。
