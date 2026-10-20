# E01 排期读回与历史保护补充

CY-HANDOFF: CODEX

- 任务：E01 / BK-SCHEDULE（承接 L06），协议 CY-COORD-20261015-v1。
- 工作基线：`1e403b135206844a34219753a36e2dee39c7dd59`。
- 工作分支：`codex/extra-e01-regression-20261015`；控制快照：`b8541ad2407d0a360588ab77ad32fe3d1c9eb71e`。
- 状态：`SOURCE_DELIVERED_UNVERIFIED`；仅新增测试源码及本说明，待 C00 审阅/串行交付，未暂存/提交/push。Java=`NOT_RUN`。
- `NO_API_CHANGE`；`TEST_DEPLOYED=NO`。不改变排期、报名、候补或结算政策。

## 覆盖审计

审计对象均来自上述基线，旧测试/业务源码/依赖/CI/DDL/migration 均只读。

| 已有证据 | 已覆盖 | 本次必要缺口 |
| --- | --- | --- |
| `B04ClubEventScheduleReadbackTest`，6 个测试 | 00:30 重开/新增日期、混排返回 null、空列表 null、历史/未知 signup 关闭编辑 | Mapper 为 mock，不能证明 MySQL 时间及历史 SQL 实际生效 |
| `ClubEventOpsServiceImplTest` 的 `futureOnlyEditRejectsRemovingOccurrenceWithAnyRegistrationHistory`、`futureOnlyEditCreatesOnlyMissingDatesAndAdvancesSeriesCas`、缺时刻/权限/审计失败测试 | Service 分支及生成委托 | persistence 为 mock，本次不复制生成委托/权限测试 |
| `ClubEventOpsMapperContractTest.futureEditUsesHalfOpenWindowAndProtectsRegistrationAndSettlementHistory` | XML 文本包含报名/结算及 WAITING/OFFERED/CLAIMED 谓词 | 字符串断言没有实际执行 SQL |
| `ClubEventTransferCancellationMySqlIT`，3 个测试 | 真实 RR/current read、转让×取消并发、退款一次性及 WAITING 取消清理 | 没有排期时间读回、OFFERED/CLAIMED 重排保护，不重复其转让/取消竞态 |
| `ClubEventOccurrenceListTest`、`ClubEventScheduleMaterializerTest` | 普通列表/日期物化逻辑 | 不代替真实 MySQL Mapper 与事务代理写链 |

没有把已有 231 项签收迁移到本新增套件，也没有声称发现已运行复现的业务缺陷。

## 新增测试及预期数量

文件：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B04ScheduleReadbackMySqlIT.java`。

预期 **5 个 JUnit 方法**；启用且前提齐全时目标 Surefire `tests=5, failures=0, errors=0, skipped=0`，此处为验收目标，尚无运行结果。循环向量不虚增方法数。

| 方法 | 预期事实 |
| --- | --- |
| `assemblyTimesSurviveRealMapperReadbackAcrossJvmZones` | UTC、America/Los_Angeles、Asia/Shanghai JVM 默认时区下分别写入上海 00:30/16:45；真实 Mapper+Service detail/list 时刻及日期一致，独立 JDBC instant 读回一致，共 6 向量 |
| `mixedAndEmptyFutureRowsReturnNullInsteadOfInventingDefault` | 上海 00:30/16:45 混排的 detail/list startTime 为 null；取消全部未来 occurrence 后为 null 且 detail futureDates 为空 |
| `everyProtectedFactClosesReadbackAndRejectsRemovalWithoutCommittedWrites` | 报名状态 1/2/3/4、WAITING/OFFERED/CLAIMED、结算共 8 向量；真实 count=1，signup 仅成功报名为 1，其余为 0；editable=false，事务代理 updateFuture 移除失败 code=409；独立 JDBC 回读 occurrence/activity/series 未改且未生成新 occurrence |
| `registrationAndSettlementCommittedAfterPrecheckAreRejectedByWriteCas` | 真实前检查为 0 后，另一 JDBC 连接提交已取消报名或结算；生产 cancelFutureActivityCas 实际返回 0，活动仍上架（2 向量）。这是确定顺序的检查后写防护，不冒称并发锁覆盖 |
| `unchangedFutureDatesAdvanceVersionOnceWithoutRegeneratingOccurrence` | 无保护事实时 editable=true；真实事务保留原日期并将 series version 0→1；旧 expectedVersion 重试失败，不再生成，原时刻仍 00:30 |

真实执行边界：生产 `ClubEventOpsMapper.xml`、`ClubEventOpsServiceImpl`、物化器和 Spring `TransactionInterceptor`；MyBatis 与事务管理器共用同一 DataSource 实例，写后用另一 DataSource/JDBC 连接看已提交事实。授权固定 owner fixture 使用 mock，未覆盖 RBAC；开场、角色赋予、lead/member 协作者为未走到的 stub，两个相关方法断言没有调用开场/角色服务，不能称其为真实生成集成验证。

不制造 SQL 无法产生的 null signup：生产 count(*) 正常返回 0；未知 signup 的 fail-closed 已由既有 B04 普通测试覆盖。生产 occurrence_at 是 NOT NULL，本次 null 指既定 startTime 混排/空集合语义，不放宽 DDL 填造非法 null 行。新日期完整生成、WAITLIST 准入与重排竞态、HTTP/运行包绑定、微信/iOS及部署均未在本新增套件验收。

## 后续重新授权时的运行说明（当前禁止执行）

Cloud **未运行 Java/javac/Maven/MySQL/Redis/服务**，只做源码审阅及轻量静态检查。离线 budget212 无网络且不支持真库，不能执行此套件；不得套用旧 1152MiB 驱动。最新指令要求全部不验证：当前 Cloud/本地均不运行下列命令，不等待或手动重跑 CI。下列仅保留未来重新获得明确运行授权时可审阅的精确命令与前提，不构成当前执行请求；未来须由本地验证方在批准通道/当时资源预算内串行单 Maven 容器执行。

环境前提：可用的仓库依赖缓存或获准依赖通道、兼容本项目的 Java/Maven、Oracle MySQL 8+ 独占测试实例；测试账户具有 CREATE/DROP DATABASE 与表/数据权限。无需 Redis、应用服务、真实支付或生产数据。显式设置如下变量，目标与凭据由验证方安全注入，禁止写入本仓库：

```text
CHENGYIN_FUNDS_MYSQL_IT=1
CHENGYIN_TEST_MYSQL_HOST=<授权测试实例>
CHENGYIN_TEST_MYSQL_PORT=<授权测试端口>
CHENGYIN_TEST_MYSQL_DATABASE_BASE=<短测试前缀>
CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX=<本批独占后缀，非空>
CHENGYIN_TEST_MYSQL_USER=<测试账户>
CHENGYIN_TEST_MYSQL_PASSWORD=<测试密码，可显式为空>
```

从仓库根目录，在以上变量已 export 的环境执行精确命令：

```bash
mvn -B -pl chengyinhub-admin -am -Dtest=B04ScheduleReadbackMySqlIT -Dsurefire.failIfNoSpecifiedTests=false -Djunit.jupiter.execution.parallel.enabled=false test
```

未设置 enable=1 时 JUnit 按 opt-in 禁用，本情况不算通过；**显式启用后**缺任一必要变量、MySQL 不可达/非 Oracle MySQL 8+、DDL/Mapper 不存在、权限不足均必须失败，不使用 assume/异常吞掉/静默跳过。应核 Surefire 方法数与 skipped=0，保留候选完整 SHA、源码未改、退出码和 XML 报告。

每次库名由既有 `MysqlIntegrationTestConfig` 验证标识符，形如 `<base>_e01_<随机12位><suffix>`（总长必须≤64）。先 CREATE（无 DROP-before-CREATE），只在新 scratch 内提取现有 `run41-prod-ddl.sql` 的 club/cms_activity/cms_registration 和既有 event-ops migration 的 series/occurrence/waitlist 六表，使用既有 IT 行尾分号边界以避免 COMMENT 内分号截断；不执行整份 migration，不改共享工具或迁移。结束仅 DROP 本次成功创建的 scratch，information_schema 回读不存在；setup 部分失败也走清理。时区方法 finally 恢复 JVM 原值，必须关闭并行并单套件运行。

当前证据：Java 编译/5 方法真实运行/清库结果均 **未验证**；本文件没有测试通过、合并、迁移执行或部署声明。静态检查不能替代这些证据。发现运行失败须保留原失败断言，交 C00 与原写者裁定，不由 E01 修改业务源码。
