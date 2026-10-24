# WP04 外层取消首锁增量

源码完成、静态检查通过；**Java/JUnit/MySQL/CI/清理均未运行**。直接父为 `ae1606843cca6ed847d84059640b1bab6e1decdc`，独立目录 `/workspace/hengyin-a-wp04-outer-fence`、分支 `codex/architecture-day-a-wp04-outer-fence`。

生产只修改授权的 TeamGroup、ClubEventOps 及同域 `RegistrationTopicFence`：

- `cancelPaidRegistration/cancelPaidRegistrationByOwner` 在报名/退款锁之前取得同 topic/父活动栅栏，并以锁下当前报名、topic/activity 执行原资格与原主办方判据。俱乐部 owner 使用原 ClubMapper 的当前锁读；预览仍只读，没有给预览加锁。
- `TeamGroup.cancelActivity` 改为 topic→activity，之后原取消、支付定性、退款、CAS、通知次序不变。`refundResolvingOrder` 起至文件末尾的原金额/库存/退款算法逐字节保持。
- `ClubEventOps.cancelOccurrence/cancelActivityWithClubWrapUp` 在原 series/club/activity 锁之前先锁真实 topic，然后保留原 series 联查活动的相对次序。原联查 SQL `FOR UPDATE` 已包含 activity，未把 activity 提前到 series 之前。原锁读后重验 activity 的 ID/topic/club/member，与原系列/current scope 一致才允许角色收回、退款和 campaign。当前仍拒绝原不支持的状态，不改岗位政策。
- `RegistrationTopicFence.LockedOrder` 增加同次当前锁读的 topic/activity，供包装层复用，避免在旧 RR 快照里重读普通投影来授权。纯同域 helper，无新增服务/依赖/锁表。

没有修改 CmsTopic、C 的票码/核销/客户端、B SDK、G 金额/分项、根入口或 CI。没有应用 G terminal review.patch；审核批准仍不等于退款 SUCCESS。

新增 `OuterCancellationTopicFenceTest` 8 个普通 @Test。已有 `RegistrationTopicFenceMySqlIT` 保留原9项并新增4项（现13）：真实 topic 等待期间 activity/registration/refund 仍可 NOWAIT 锁读；外层不能提前进入 series；活动父关系/会员变化不产生取消/退款效果；合法空销售活动确实取消。series 存在性使用窄 mock，这4项不冒充真实 series SQL或分项金额验收。

原 `ClubEventTransferCancellationMySqlIT` 三项仍保留全部断言数量和失权拒绝/单退/回执/库存/独占清理核对。仅补真实父 topic seed、两个投影字段、真实 Topic XML 和新增 Mapper 装配。第二请求现在更早阻塞于 topic，因此旧 series 重试屏障改为真实 Topic Mapper 调用屏障，仍断言未完成直到转让提交；未删场景或增加等待。其余四个旧单测只补共用 fixture 当前读接线，未改业务预期。

实际执行仅 Python 复用冻结 `scan_java`、原算法/保护文件字节比对、断言数检查、`git diff --check`。文件 hash／实际固定入口数见 `static-result.json`；所有新旧 Java 场景在本候选上均 NOT_RUN。

下一项同线事实：`CmsRegistrationServiceImpl.createRegistrationFromRequest` 的候补 claim 会先取得 series/activity，随后进入可能取得 topic 的探索票创建路径。新 topic 首锁不能只修取消；需在实际 claim 前统一父 topic，并在原活动锁点复验，保留原 token/库存/扣款事务。本片未抢改 ClubWaitlistService。

B `6d641fa802cdf1277a158ce0b7633dcf126b5e10` 已实际 fetch/read：真实 `WechatVerifiedPaymentFact.Reference`、`WechatVerifiedPaymentFactService.requireVerified`、`registerVerified/processVerified`。本外层 delta 本身尚无 B 类型依赖；下一独立 A guard 增量将直接依赖这些真实类型和不可变 receipt，不能把 Map 或默认成功当事实，也不复制 B 文件。root 需同 B0ff 出站拆分组合；G 原分项终态接口仍由 G 提供，本片不造占位实现。
