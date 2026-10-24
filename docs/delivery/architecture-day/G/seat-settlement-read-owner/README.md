# 席位结算窄读 owner：启动环修补候选

Parent `3e976bcefb2a14b19600e6bc4a20029767d58b75`；独立工作树 `/workspace/hengyin-g-seat-read-owner`，分支 `codex/architecture-day-g-seat-read-owner`。从本次公共验收源建立，没有回退/覆盖其他 owner。

已给定真实启动阻断：run40130007237/backend job120979619091 的报名 → CoopSettlementClaim → TopicSettlementEvidence → RegistrationSeatService → 报名。此次只拆最后的结算事实读取边，不扩审其他图。

新增同席位域 `com.chengyinhub.business.service.RegistrationSeatSettlementReader`，唯一构造依赖原 `RegistrationSeatMapper`。把原 `verifiedSeatsForGroup(Long topicId, Long activityId, Date cutoff)` 方法体原义搬入，仍 `MANDATORY + rollbackFor=Exception`；先验证完整 scope，再当前锁读 `selectUnprovenMultiGroupOrdersForUpdate`，原历史多人证据不足抛原 409/原消息，最后 `selectVerifiedForGroupForUpdate`。原 command 服务保留同签名、同事务声明的兼容委托，不自开新事务。TopicEvidence 直接注入窄 reader，不依赖命令服务，也没有回调或实例化它。

Mapper/SQL 全部字节保持不变，包括 FOR UPDATE、L1 cache flags、topic/activity/原报名归属、截止时间及历史事实规则。原 SQL 保留已退款的历史席位事实，原现金候选与冻结合同继续负责经济资格/退款处理；本片没有把事实存在改作收费资格。TopicEvidence 的候选/窗口筛选、按 seat ID 去重、原单人证明边界和金额计算不动。负利润持久人工、原分项守恒、付款/退款回执/资金代码均未编辑。

## F 精确 policy 输入（未编辑 F 文件）

新增 managed type **1 个**：`com.chengyinhub.business.service.RegistrationSeatSettlementReader`（`@Service`，原 registration-seat 域）。没有新 interface/config/Mapper/SQL/schema。

依赖变化：

- `TopicSettlementEvidenceService.registrationSeats`：由 `RegistrationSeatService` 改为 `RegistrationSeatSettlementReader`；原四参构造 API 不变。
- `RegistrationSeatService.settlementReader`：新增到该 reader 的必需注入；原命令所需报名/权限/审核依赖不动。
- `RegistrationSeatSettlementReader(RegistrationSeatMapper)`：唯一构造依赖；无 `ICmsRegistrationService`、TopicEvidence/claim 或其他业务服务依赖。
- 原 `RegistrationSeatMapper` 新生产 consumer：该 reader；仅上述两个原 current-read statement。RegistrationSeatService 仍是原命令 Mapper consumer，但结算读方法只委托 reader。

没有 Lazy/Provider、允许循环配置、任意回退 bean 或 mock 启动图。`SupportedStackServiceCyclesTest` / owners/trusted policies / workflow/registry 均留给 F/root 串行接线。

## 直接装配与源码验证范围

- `RegistrationSeatServiceTest`：注入真实 reader+原 Mapper double；原所有方法/断言不变，原 legacy 409 用例经过新委托。
- `CoopSettlementSeatBillingTest`：原 8 case 保留；由 mock 命令服务改为真实 reader+原 Mapper double。原两席位金额、重复 seat 一次、时间窗、缺证据、单人边界、跨 scope 等断言不变。
- `RegistrationSeatMySqlIT`：原 case/断言保留、声明数不变；在原已持锁事务中增加真实 reader 的 topic/activity 原 seat ID 读回、legacy 409 断言；原命令 factory 接同一 Mapper 的 reader。没有修改数据库门禁/DDL/原并发夹具。
- 新 `RegistrationSeatSettlementReaderTest`：4 个普通 @Test 声明：`preservesOriginalScopeCutoffCurrentReadOrderAndFactIdentities`、`rejectsMissingOrInvalidScopeBeforeAnyCurrentRead`、`requiresTheCallersOriginalTransactionBeforeAnyMapperRead`、`doesNotTurnFailedLegacyEvidenceReadIntoZeroFulfillment`。事务用例只验证实际 AOP MANDATORY 的无事务拒绝，不冒充全应用启动或真库。

仅源码/静态 diff 检查。Java、数据库、CI、完整 Spring 启动在本候选均 **NOT_RUN**，root 统一验证；不能把旧源的单测/存储 PASS 移称本源验收。
