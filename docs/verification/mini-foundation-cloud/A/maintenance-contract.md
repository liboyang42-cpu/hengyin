# A3/A4/A5维护窄合同

基线：交接分支；仅A业务文件。Java/MySQL/Redis未运行，必须用授权VPS或既有CI补验。

## 维护扫描

退款：`selectStalePayoutScanUpperBound(minutes)`固定本轮最大ID；`selectStalePayoutProcessingAfter(minutes, afterId, maxId, limit)`按ID升序、严格大于游标、不超过上界，每页200。游标在尝试业务操作前推进；单项异常不阻断后页。保留原处理/派发租约及确定性退款号，不改变未知结果政策。退款现有Mapper.java未列所有权，由A parent授权本业务扩展。

出码：parent提供`getPaidMissingQrcodeScanUpperBound()`与`listPaidMissingQrcodeIdsAfter(long,long,int)`，沿原近7天/已支付/无码条件并过滤不可用探索票；task每页100、固定MAX，单项失败仍前进。重试继续由generateVerificationQrcode当前资格与CAS处理。

任务bean同步单轮，保存跨轮ID游标；退款每轮最多1000、出码最多500，每页200/100，未达MAX停轮保留游标，空页/到MAX才归零。重启游标归零；若任务频繁重启仍存在运行公平断点。固定MAX不把扫描中新增对象无限纳入本轮。

## 原来源奖励券

A新增`grantCouponBySource(memberId,couponId,sourceType,sourceId)`：稳定原来源唯一收据，原成员/券不可改变，领取计数、券历史及回执同事务；回放返回原historyId；失败保留原来源且不造新对象。C拥有漫游发现触发，已接受reward_drop/log.id来源合同并接入口。主题grantOnce现已有同事务唯一日志，不另造协议或撤销后永久阻止再次原规则通关。

## 通知

原报名Checkout直接sendSystem并吞异常已移除。A保存原registration身份事件、B通过已提供窄接口投递消息，未知不可盲重发。本文件不授权修改B投递层。

## 订阅/原账

active列表需同时`start_date <= now()`及`end_date > now()`（null继续原无限边界）；恰好结束拒绝当前权益。Settlement保持纯账本，历史分成快照与现有CAS提款不改政策；只修确定写入丢失，真实数据库守恒未验。

初始扫描文件：RefundReconcileTask.java、RefundApplicationMapper.java/xml、VerificationQrcodeRepairTask.java、MerchantSubscriptionMapper.xml；对应JUnit测试；本目录文档。券/通知新业务文件在合同确认后追加。

## 已定实施补充

B先导窄接口 `ISystemNotificationIntentService.enqueue(Long,Integer,String,String,String)` 与 `findIntentId(String)`：Signup helper必须主建单事务存在（MANDATORY），固定 `registration-signup:<primaryId>`；先查原收据，现态活动不重造原payload。parent在Cms创建新订单事务接入，selfplay原pending回放不生成意图。Checkout旧sendSystem/吞异常移除。仅固化意图，不表示送达，运行需B投递实现及测试migration。

奖励原ID：`IRewardDropService.findOriginalDrop(...)`仅回原type3券，map新增`dropLogId`，读取log原param，不受当前rule/feature.flag变更影响，不恢复XP。`rollDrop`新log和A`coupon_source_grant`意图同事务；`grantCouponBySource(...,"reward_drop",log.id)`核log归属/type/coupon模板，再锁来源回执→锁当前coupon→原领取计数→history→回执，同事务。成功重放返同historyId；原legacy log缺来源回执为UNKNOWN，禁止盲补。新DDL在 `chengyinhub-admin/sql/migration_mini_foundation_coupon_source_grant_20261013.sql`，仅测试目标待验，无生产执行。

新增A业务所有权：CouponSourceGrant.java、CouponSourceGrantMapper.java/xml、RewardDropLogMapper.java/xml、RewardDropServiceImpl.java（A parent协调单一原奖励来源真源）、ISmsCouponHistoryService及IRewardDropService签名、RegistrationSignupNotificationService.java。

A5确定修补：SettlementServiceImpl校验批次插入=1和生成ID、bind实际数量=回读条数、回填总额=1，否则主事务回滚；原幂等key不得返回另一商家批次。纯账本仍不打款。CashWithdrawServiceImpl建账及CW/CD原号同事务落库成功后才允许扣余额/调微信，准备失败整体回滚不留未扣款假在途账；失败不自动猜扣款或回补。

订阅续订使用MySQL ON DUPLICATE UPDATE返回2，MerchantCommerceService接受1/2，0仍失败回滚。不改变原权益期限叠加与原开关。

CashWithdrawFailureRecoveryService新增原账事务：仅明确微信FAIL/未受理调用；CAS处理中未冻结→拿行锁回读原member/amount→addBalance→原单FAILED_REFUNDED，全部同一事务。写终态失败或进程崩溃前不留单独余额回补。事务失败后调用方只CAS原处理中转人工，不覆盖并发终态；UNKNOWN/PROCESSING查询路径保留。既有人工双人复核流程不改。真实事务竞争及commit应答丢失未运行，不能称原账完整验收。

新增/调整JUnit（均未执行）：RefundReconcileTaskTest、VerificationQrcodeRepairTaskTest（永久失败跨页/跨轮），CouponSourceGrantTest（原来源归属、同回执/unknown/disabled/写回失败），RewardDropServiceTest（新log同事务意图、原payload无XP回放），RegistrationSignupNotificationServiceTest（原key/payload/重复/归属/失败），MerchantSubscriptionBoundaryMyBatisTest（实际XML H2时间边界，未来/恰好结束/历史），MerchantCommerceServiceTest（MySQL续订rows=2），SettlementServiceImplTest（跨商家幂等/总额写失败），CashWithdrawServiceTest/CashWithdrawStateMachineTest/CashWithdrawFrozenRequeryH2Test与CashWithdrawFailureRecoveryTransactionTest（持久原账guard，同事务故障/双worker）。创建相关四旧fixture新增通知依赖，parent调整RegistrationFunds fixture。MySQL、H2、Java编译都未在云端执行。
