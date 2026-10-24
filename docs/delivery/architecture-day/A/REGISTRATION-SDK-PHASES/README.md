# 报名支付锁外 SDK 与原事务 claim

代码已完成一个可独立组合的小片，基线 `0cb8115ee34e4647a76c76bda608fae62bbd51e1`。本片只修改 A 的 RegistrationCheckoutServiceImpl、CmsRegistrationServiceImpl.beginPaymentAttempt 的准备段及 OrderCloseTask 入口；B/G/D/readleaf/SQL/CI/权限/金额规则均未改。Java/MySQL/CI/SDK/DDL **NOT_RUN**。

- checkout 的 createOrReplay、retryPayment 两重载：NOT_SUPPORTED→NEVER，且直接/自调用入口也明确检查 active transaction。拒绝发生在 minor gate、建单、claim、SDK 前；不悬挂仍持锁的调用者。正常路径仍由原 creation/claim 事务各自提交，再调用 B 预下单。私有 SDK 出口在 catch 外复查，不能把本地阶段拒绝包装成 SDK UNKNOWN。
- beginPaymentAttempt：保留原 API/事务注解/noRollbackFor expired。先核 actor→复用 RegistrationTopicFence 当前锁→按 locked.rows 查询原成功事实→用同一当前行执行原完整可售/收费/过期校验→原 claim CAS。此前普通快照先判断、直接 claim 报名行的锁序已修；返回给 SDK 的是原锁下报名身份、冻结金额与原号。
- 原 preparePaymentRetry 校验正文仅机械下沉 requirePayableRegistration，原公共只读 facade 不改接口；原 claim 尾部、过期释放及金额规则保持。当前已知成功直接拒绝再次发起，交原事实恢复，不解冻/换号/退款。
- OrderCloseTask.reconcileExpiredPaying 在 readiness/扫描/SDK 前拒绝活动外层事务；正常先 query/落 fact 提交，再 handleWechatPayCallback 或 settleClosedPayingOne 的原业务事务。409 topic-first 包装、B30 denial helper、G admission/readleaf 均保留。

精确调用/事务清单在 caller-inventory.json；源码补丁 production.review.patch 已在上述公开父的临时副本 git apply --check，通过不代表 Java 编译。新增 8 个 Spring interceptor/内存事务管理器的边界与正常阶段单测源码、3 个原锁/最新事实/旧快照负控源码。既有 funds96、topic fence、零额/未成年测试文件逐字保留；真实 MySQL 支付/余额/库存验收仍由原真库用例和 root 组合执行，内存事务单测不冒充真库。

B 实际依赖：最新 `9d59271b0d82344b13f7b66ca2e0fb1751d14518` 已提供独立 writer 外层拒绝、WechatPaymentOrderOwner 共同行互斥及无 SDK 的 lockClosable 原签名。本片只调用其在 B30 已有的 hasConfirmedExternalSuccess / WechatPaymentOperations；未重做 B writer，不依赖 A 此前提案 recordClosedBusinessPaymentRecovery。B 新 findPaymentRecovery/completePaymentRecovery 的 A消费完成接线与旧fixture适配留下一独立片，不能以当前片宣称人工票已闭合。

剩余实际违规调用者不是 checkout 内层能修的：TeamGroup.cancelActivityByOwner/ClubOperator/MerchantHost/cancelTopicByHost（REQUIRED）→cancelActivity/cancelPaidSales→settlePayingRegistrationsBeforeCancel 在 topic/activity锁后 queryOrderStatus/closePaymentAttempt；ClubEventOps.cancelOccurrence/OwnerWithClubWrapUp/AuthorizedTopicCancellation 又有更外层系列/俱乐部锁、撤权复验及通知回执逻辑。需在最外层完成短原授权/原支付身份准备并提交→锁外 SDK/持久事实→原完整事务重验身份/最新facts/原CAS。只删 TeamGroup 注解或 NOT_SUPPORTED 均无法闭合。不得在锁外提前绕过原 scope/replay/权限，不能把拒绝正常取消或简单缓存 Map 当已实现。该下一必要片继续同线处理，不等待当前诊断 CI。

回退不能删除原 intent/fact/消费凭据；撤回 guard 会重新允许持锁 SDK，撤回当前读会重现旧快照判断。此提交未部署、未触发新 CI，也不把已有189场景诊断当本片验收。
