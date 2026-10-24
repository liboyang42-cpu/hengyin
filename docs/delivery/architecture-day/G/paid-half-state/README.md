> 后续 H/Ae6 修正：本目录 A patch 现在以 `e6dc689a` 为精确基底，使用真实 `checkedVerifiedPayment(...).reference()`，G准入位于Ae6早返回之前；只在失败回滚后补保留。完整父从范围与防双冲见 [后续片](../approved-refund-fence/RECOVERY.md)。原6df覆盖仍按Git历史/当时证据解释。

# G 已付半状态错关账 P1：源码候选，未真验

Parent `de7f432ec6f0c1c6225168ecf2e4808210de42e0`，独占工作树 `/workspace/hengyin-g-paid-half-state`。root 请求中 `7da3e4ea385596aa850dc760f2cf6adc6c554602` 到本 parent 的现金 DTO/证据 SQL 字节未变；采用已纳 881/E/父锁的最新公共源，未回退他人代码。Java/MySQL/CI 均 NOT_RUN，无 DDL 执行。

## 已实现

1. 原 `settleTopic` topic 首锁→CAS→`lockTopicCashBasis`→两个无 payment/registration/verification 过滤的当前锁读→`TopicSettlementCashBasis.fromLockedRows`。在完整集合进入现金/核销过滤前检查 payment=2/registration=1，抛 `MANUAL_REPAIR:PAID_REGISTRATION_HALF_STATE_UNRESOLVED:registrationId=...`。即使旧 SUM=0/未核销，也不能进入零现金关账；抛错使原 topic CAS 同事务回滚。原 PAYING=1 闸门独立保留。修复不自动改报名状态、金额、奖励或退款。
2. 真实 `CoopClosedPaymentRecovery`：直接消费 B `Reference` / `requireVerified`，匹配原报名号、微信实付/transaction；从真实活动父关系定位，不拿数字相等猜 scope。原 topic 首锁→完整当前目标读→close/head 读，识别已关账2/1；以及已经被旧路径修成2/2、但零关账没有任何现金头的可证差异。使用原 outbox enqueue，key=`COOP_PAID_HALF_LATE_SUCCESS:<原factID>:<原hash>`，biz_id=原报名，money_affecting=1、MANUAL_REQUIRED=4，写后锁读核对同身份/状态，重复不换号。保留原 B fact、原 inbox 和原 DONE；不造 VERIFIED、原现金头或费用。
3. `ICoopSettlementClaimService` 两个真实实现：外部阶段 `retainClosedPaidHalfStateSuccess` 必须无业务事务；内部 `requirePaidHalfStateCashAdmission` 必须加入原事务，在 A 的 repair/CAS 前重验。若观察到未关账后又关账，内部拒绝并在回滚释放 topic 锁后经自身 Spring proxy 的 REQUIRES_NEW 保留待处理身份；不持原锁另开事务自锁。失败仍保留原 B fact/inbox，告警可重投同身份，不把保留失败报业务成功。

## A 共享接线（本批未修改 A 文件）

`a-callback-admission.review.patch` 当前以实际 A/e6 `e6dc689a` 为精确基底；其 checkedVerifiedPayment(...).reference()、registerVerified/processVerified 已存在，不重复加旧B helper。已在临时公共源码副本 dry-run（fuzz=0），不修改A工作文件。

- G内部准入位于锁下SUCCESS分支、Ae6已关账早返回及completePaymentSuccess/CAS之前；范围包含完整原父从单。
- 原processVerified失败、业务事务回滚释放后，再保留原fact/hash身份；G的afterRollback也覆盖inline失败。
- 不改原注册/消费/DONE/ACK合同，不造缺失inline绑定，不自动调资金。

A/B 这些 hunk 尚未由本片实际接入，因此不声称完整 callback/ACK 关闭。root 转发给 A 合成；G 只交实现与精确 patch，不覆盖其文件。

## 复现/验证源

新增2条 DTO unit：¥10的2/1未核销、与其他90元正常单混合/已核销半态、PAYING保留独立闸门；原4条断言保留。新增 `Wp04PaidHalfStateMySqlIT` 4普通声明：真实私有 settleTopic + 原 topic CAS 回滚；实际 A 历史修复 helper/真实 XML 读回10元重新进入原合作资格（无coop仍按原规则拒结，不能零关）；既有错关账→A修成2/2→SettlementEntry主题支付跳过→旧DONE→无现金头的¥10差异保留；外层观察/内层关账竞态后真实回滚与 durable workitem。使用实际 B 存储 owner 的 typed fixture，不是 SDK/provider 真验。topic adapter 为真实 JDBC 锁/CAS；旧DONE为显式历史夹具，不称整条A回调已执行。

`remove-guard-negative-only.review.patch` 只撤新增关账 guard，供 root 对相同测试/真实SQL做旧源负控；禁止 merge/deploy。未实际跑，不声明已红/已绿或DB漏算比例。测试gate `WP04_PAID_HALF_STATE_DDL_APPROVED=1`，github-hosted、显式回环连接/UUID独占库；只清理由本次确实 CREATE 的库，原migration不修改。

## 剩余

本片只闭合“已付半状态错关+可证晚成功未纳账”的拒绝/恢复身份接缝，不计算收口后新分账。普通 PAYING 的更广 late SUCCESS、历史2/2且已有其他现金头但缺逐订单归属的反推、原分项生产扩展/部分退款与负毛利仍未闭。待办身份不会自动调 SDK、退款、扣保证金、造债或重算其他订单。累计舍入/跨订单尾差/负毛利责任三题未答，不选算法。既有零销售修复不得回写假正数或抹原 DONE。完整验收仍需 root 合成 A/B 后执行并发/真实服务回归。

上一片一对一 producer 独立为 `4d6f4a565d8d42c7cca1f960c1000c7fa09205bd`（parent 881）；不包含在本片 parent，也不作为本 P1 的依赖。
