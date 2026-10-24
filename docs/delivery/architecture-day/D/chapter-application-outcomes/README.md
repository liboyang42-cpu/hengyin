# 章节申请审核与撤回通知闭环

Parent `31ec329768ecc2b23e09466bf46754d12fe569d6`，目录 `/workspace/architecture-day-d-application-outcomes`。实际caller仍为 ApiMerchantChapterApplicationController→audit/respondInvite/withdraw/revokeInvite；原CAS成功后、原节点处置后，在原事务经ChapterApplicationInviteNotice.submitOutcome登记原IM/WX意图，afterCommit仅唤醒原ID。四种事件为review/invite-decision/withdraw/invite-revoke，身份均为chapter-app:<原topicScope>:<原applicationId>:<当前持久round>:<event>。原title/body/recipient与理由规范化保持，不序列化Runnable，不混IM与WX任务，原暂停状态保留。

原全部状态CAS WHERE与round生成SQL字节不变。重复审核保持原拒绝、同结论邀请响应和已撤回保持原无副作用返回；未赢CAS绝不登记意图或诊断。审核/撤邀普通行只定位父锁，所有状态/来源/recipient/round改用父锁后的完整当前申请；防RR旧轮次给新决定记错身份。商家撤回改为原task→topic→chapter→application方向，避免原application→node父锁与审核反向；原索引仅辅助缺章历史锁定位，不作授权依据。父章归属变化拒绝并重试，不取新父锁。原无父级已撤回回放仍返回，不制造新状态条件；孤儿数据修复、完整审核task/资质/资金链不在已证范围。

正轮次：复用原CommittedOfficialNoticeService/ISystemNotificationIntentService及原消费者/Quartz；新一轮事件不覆盖旧轮次，旧UNKNOWN/owner/message保持，不能凭租约或HTTP200重发。Controller仍无expectedRound/requestId，本片不宣称跨终态旧客户端命令去重；只保证实际赢原CAS的当前轮次身份。

旧NULL/非法轮次不补1、不建IM消息或WX尝试。保留合法业务状态结果，在现有business_audit_event记UNVERIFIABLE_LEGACY_ROUND等“通知未提交”事实，原scheduler提交后告警；不是送达终态，也不是新outbox/恢复任务。仅复用BusinessAuditEventMapper原insert与原事件FOR UPDATE查询，无新writer namespace/表或泛化insert-ignore API。只对DuplicateKey做完整不可变摘要/状态/actor/target核验后认作原审计回执，其他SQL失败照原事务传播。白名单只存请求摘要/事件/原因/roundKnown，不存通知正文，不把传入owner号伪作实际actor。原审计或IM/WX事实均不删、不更新、不回填。

依赖root当前审计writer consolidation后串行应用：root已选择原insertBusinessAuditEvent及DuplicateKey严格回读的方案/8例源码，80b1126只留参考不请求合入。本片基于31ec，只复用原审计owner，shared XML仅当前读cache flags，写SQL和Mapper接口未改；没有新mapper方法/namespace，不覆盖root投诉修复、C/A/支付、CI/registry、迁移或G任务。请应用本提交delta，不以其旧base整树覆盖root尚未推送的consolidation。

旧49case（36+5unit、FreeExplore8）和额外原ChapterRejectRevoke6全部保留；原10项round IT case/断言不改。只做当前行/locator和typed通知装配，原recipient/title/body与否定断言逐项对应；邀请/轮次旧IT仍保持原“其他通知”double边界，新outcome套件替换为真实owner。新增ChapterApplicationOutcomeMySqlIT 10项普通@Test：两轮审核冻结、两轮撤回、邀请响应回放、零CAS、两类回滚、legacy NULL/拒新payload、旧UNKNOWN保全、真实RR交错、物理父锁先于申请及缺父回放、审核与撤回并发唯一赢家。

新IT用生产application/IM/audit mapper、原服务Spring事务；父行由canonical表JDBC锁适配，资格/节点操作/审核task mapper/告警/WX为边界double，不冒充完整节点/资质/资金、HTTP、Quartz启动或process-kill验收。scratch审计只加载既有canonical初始审计和actor/requestId ALTER，不新增生产DDL。缺受控连接配置硬失败，无skip/Assumptions。

实际仅XML、原CAS/无关方法字节、旧case/断言与字段映射、hash/空白/秘密模式静态检查；Java/MySQL/DDL/架构全门禁/CI均NOT_RUN。原31ec、bc5、80b及共享工作区保留。回退不能删审计/通知或清轮次、重置UNKNOWN，不能退回不维护round的旧写者继续重申。当前片冻结后不再开启新工作，交root串行组合/统一验收。
