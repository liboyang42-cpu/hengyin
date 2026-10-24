# 报名 callback + 永久意图 + inbox 真库组合回归

结论：新增 `com.chengyinhub.business.it.RegistrationCallbackIntentMySqlIT`，共3项 @Test，组合真实网关、真实SDK验签解密、真实报名/inbox/claim/mutation服务、事务代理、原MyBatis XML与仓库原schema。源码未运行Java/MySQL，不宣称验收通过。只有新增测试与B交付文档；生产、现有C/D/root fixture、依赖、退款/结算政策、CI清单零改。

## 基线与隔离

父基线 `a3fdd1650f26d88ec8c0cccd30bb37488cda06a9`（其父为0cbd，仅修CLM W2成功夹具的CAS后状态回读），工作树 `/workspace/architecture-day-b-h-tests`、分支 `codex/architecture-day-b-h-regression-tests`。之前V01及V02三态断言全部保留；a3fdd中精确修正SUCCESS夹具的状态推进，不改变生产。root的 `97cd641c6f4c494038f3fc7b0511272eebaa3240` 只修共享E2E stub及其契约测试，生产服务与0cbd一致；本候选没有复制/覆盖该共享修复，需root按精确delta组合后统一验证。

## 精确装配与原schema

复用现有包内 `P0AcceptanceSchema`，按原样从 `sql/base_schema_20260818.sql` 提取以下原DDL，不新造简化domain表、不修改DDL：cms_registration、oms_ticket、ums_member、ums_member_points_detail、cms_points_setting、cms_topic_node、cms_member_template、cms_registration_scan、registration_entitlement、cms_topic。

静态逐列核对真实mapper与快照发现两个明确历史缺口：UmsMemberMapper需要app_open_id；CmsRegistrationMapper需要first_successful_scan_at。通过原 `migration_app_open_id.sql` 和 `migration_20260930_registration_first_successful_scan.sql` 补齐，不手写ALTER或放宽mapper查询。其余使用原 `migration_wx_payment_callback_inbox_outbox_20260725.sql`、原B `migration_20261023_wx_pay_order_intent.sql`。这些DDL只能在root已批准的临时MySQL job执行，本云VM未运行。

SqlSessionFactoryBean + SpringManaged事务连接默认 + DataSourceTransactionManager；六个真实mapper XML：CmsRegistration、UmsMember、UmsMemberPointsDetail、CmsPointsSetting、CmsRegistrationScan、WxPaymentCallbackInbox。

真实服务：WechatPayServiceImpl、CmsRegistrationServiceImpl、CmsPointsSettingServiceImpl、SettlementEntryServiceImpl、WxPaymentCallbackInboxServiceImpl、WxPaymentCallbackClaimService、WxPaymentCallbackMutationService。register/REQUIRES_NEW claim/release/REQUIRED mutation分别经过真实Spring事务代理；没有passThrough inbox、没有mapper mock、没有仿制JDBC domain adapter。

签名：每项运行时生成RSA密钥及AES key，真实官方NotificationParser验证/解密AES-GCM通知；只将官方JsapiService原单查询作为受控double，独立提供原mchid/appid/channel/CNY/金额/交易号/状态。新下单冻结、当前尝试栅栏、归属/金额/状态CAS及inbox DONE都走真实代码和SQL。

原owner_type=1主题票合同在支付阶段不做商家结算，真实SettlementEntryServiceImpl执行其既有分支，不以成功替身代替。核销扫码权益、预占registration_entitlement、票库存、买家积分余额和两条积分账本均用原schema读写/回读。外部二维码通知只在真实CommittedBusinessNotice的post-commit排队点用scheduler double观察，不发HTTP、不启动线程、不伪造业务写成功。

## 三项最小场景及断言

1. `signedSuccessCommitsRegistrationLedgerScansAndDoneTogetherAndDuplicateDoesNotRebook`：篡改签名先拒绝且不查SDK/不落inbox；合法签名但金额999分对原1000分拒绝且不落inbox。正确签名/原金额后，报名PAID+成功、真实积分80→95、两条命名空间积分账本、一个核销scan和inbox DONE提交；重复通知同inbox id/fence、不重账、不重发权益/通知。
2. `exceptionAfterBusinessAndDoneSqlRollsBothBackAndSameInboxIdentityRecovers`：真实业务写及真实markDone UPDATE已经执行后、事务提交前注入一次异常；报名/积分/账本/scan及DONE同时回滚，inbox原行按owner/fence释放为RETRYABLE。重投完全相同通知恢复原inbox id/hash/biz/event，attempt/fence从1递增2，最终只一份权益/账本。再次重投不重记。
3. `oldSignedClosedW1CannotCloseOrReleaseNewW2AndLaterW2SuccessStaysPaid`：官方原号CLOSED核验后真实freeze生成APP W2。旧W1的签名CLOSED对抗通知经原单事实校验进入真实inbox，真实mutation事务内当前尝试栅栏拒绝，业务仍PAYING、库存/积分/预占权益零变化，inbox不是DONE。随后W2真实成功按原单记账；再重投旧通知仍不能覆盖新交易、DONE、新渠道或释放资源，旧号/旧hash永久保留。

## 原子提交的可观测证据设计

新测试内的MyBatis Executor interceptor只观察真实映射SQL，既不改返回值也不代替执行。它在真实CmsRegistrationMapper.claimPaymentSuccess之后记录物理CONNECTION_ID；在真实WxPaymentCallbackInboxMapper.markDone前后核对同一连接/活动事务。

在markDone之前：当前事务内读报名PAID、积分/账本/scan已写、inbox PROCESSING；执行实际UPDATE后当前事务内读DONE。上述两个时点各用 `DriverManagerDataSource.getConnection()` 取得新的独立物理连接（不走DataSourceUtils），读回仍为原PAYING、80积分、零账本/scan及已独立提交的PROCESSING。故不能把先提交业务或先提交DONE当作原子成功。故障恰在实际DONE SQL后注入，验证两者一起回滚。提交后scheduler排队点的第二连接须同时看到PAID+DONE及全部业务结果。

此处是真实SQL拦截的故障注入，不把Mock/H2当MySQL，不以手写表镜像原业务。旧W1通知是有意构造的签名对抗事件，不声称微信正常支付通知会推送CLOSED。

## 实际验证与root运行入口

本云VM仅执行轻量Python/静态检查：3个@Test、原schema/原migration/真实XML资源存在、快照缺列精确核对、禁止passThrough/领域mapper mock、git diff --check、文件范围/秘密文件及凭据标记扫描、补丁应用后字节比对。Java/JUnit/MySQL/真实微信/生产DDL执行数均0。没有新增依赖、环境、CI dispatch或运行器。

root须在获准GitHub-hosted临时MySQL/JDK21路径登记新类，按新组合SHA显式执行 `-Dtest=RegistrationCallbackIntentMySqlIT`（默认Surefire不会选*IT）。沿用B四项的 `B_PAYMENT_INTENT_DDL_APPROVED=1`、`RUNNER_ENVIRONMENT=github-hosted` 及受控 `CHENGYIN_MYBATIS_IT_*`，只允许回环MySQL；独立随机schema为chengyin_mybatis_bregcb_*，结束只删除本schema并回读CLEAN。缺前提硬失败，不跳过。需实际3/0/0/0、source SHA及CLEAN后才可结案。

不新增生产schema或生产迁移，不涉及生产回退；本候选只新增验收覆盖。剩余实际风险是尚未编译/运行，需root按同SHA核对schema装配、SDK真实签名解析、故障回滚及旧attempt路径。
