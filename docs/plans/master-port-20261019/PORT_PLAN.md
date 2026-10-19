# 真库已验项移植到 master 的计划（2026-10-19）

> 只是计划文档：本 PR 不移植任何代码、不改登记表、不跑迁移。
> 数据均于 2026-10-19 用 git 对远端引用实测（`git log/diff/show/merge-tree/merge-file`），不是估计；估计处单独标注。
> 引用的真库结果见 #281 的逐批评论。

## (a) 起点：各 lineage 头与 PR #231

| 线 | 分支 | 头 SHA | 真库结果（GitHub-hosted MySQL 8.0.45 / Redis 7.4，UTC） |
|---|---|---|---|
| CORE | `codex/mini-longrun-core-20261015` | `5752ee6f` | core-funds 27/27、ai-quota 22/22（round 1） |
| EXTRA | `codex/extra-six-rolling-20261015` | **`8822ab38`**（#285、#287 squash 后的新头） | e04-07-coupon 8/8、community-e02 14/14、extra-storage 71/71，同一 SHA（round 2，run 39274243719 / 39274767256 / 39275253038） |
| B05 | `codex/core-b05-fixture-20261018` | `2ac55d20` | b05-visibility 3/3（round 1） |
| F3 | `codex/mini-forward-source-20261015` + 修复 draft #294 | **`763dcb43`**（#294 头；lineage 本身 `429aff0c`，登记源 `d592334a`） | `d592334a` 10/11（同主题并发通关死锁）；#294 本地 MySQL 8.0.45 12/12×3，真库 re-run 待 #295 合入 |
| E02-13 | PR #231（以 master 为基） | `b0dc1019`（merge-base `0aab8b76`） | 不在登记表 |

- 四条 lineage 都从 **`8b7eb814`** 分叉（Java 1.8 / Spring Boot 2.5.15）。至今仍是 `java.version 1.8` / Boot `2.5.15`，142 个文件 import `javax.(servlet|validation|annotation)`，0 个 import `jakarta`。
- 拓扑：CORE 与 EXTRA 共享基 `1e403b13`（分叉后 83 个提交）。F3 自 CORE 的 `0d63043a` 分出，带 4 个额外生产提交（`add1534c`、`2babbe28`、`009664d7`、`bde356f3`）。B05 = EXTRA `26d8245b` + 1 个仅测试提交。
- 共享基里的 `231f201e`（squash「小程序底层A/B/C集成」，96 个 src/main+sql 文件，+4678/-963）是 AI 额度、券来源发放、Journey 基线的**唯一来源**。
- 与 master 无 patch 等价提交：`git cherry` 和 subject `comm` 均为 0。
- 等价补丁对：CORE `bda1c4e8` ≡ EXTRA `f3e0198e`（E04-13），CORE `67e3f0a9` ≡ EXTRA `bdd53461`（E03-01），`patch-id` 相同。
- **PR #231 不从 `8b7eb814` 分叉**，它基于 master `0aab8b76`，已是 Java 21 / Boot 4.1.1 / jakarta。

## (b) master 自 `8b7eb814` 以来的变化（实测，至 `e1ae7514`）

- **提交**：146 个（129 个非 merge、17 个 merge），其中 51 个非 merge 提交触及 src/main。
- **`c037f7bc`**「迁入上线前修复并统一Java21与GitHub CI」：单提交改 1772 个文件（+431256/-255077），新增 242 行 `import jakarta.`。
- **总 diff**：2102 个文件（642 A、1460 M、0 D），+510685/-292443。
- **按顶层目录（文件数）**：

  | 目录 | 文件数 |
  |---|---|
  | chengyinhub-xcx | 847 |
  | chengyinhub-admin | 701（main 132、test 534） |
  | chengyinhub-system | 326 |
  | ci | 58 |
  | chengyinhub-framework | 28 |
  | chengyinhub-common | 25 |
  | chengyinhub-quartz | 14 |
  | 根目录文件 | 6 |
  | 其余 | 若干 |

- **版本**（根 pom，分叉点 → master）：

  | 依赖 | 分叉点 | master |
  |---|---|---|
  | java | 1.8 | 21（`<release>` + `<parameters>true</parameters>`） |
  | spring-boot | 2.5.15 | 4.1.1（去掉 spring-framework 5.3.39 / spring-security 5.7.12 / logback pin） |
  | tomcat | 9.0.120 | 11.0.26 |
  | druid | 1.2.23 | 1.2.28（`druid-spring-boot-4-starter`） |
  | pagehelper-spring-boot-starter | 1.4.7 | 4.1.1 |
  | mybatis | 3.5.13 | 3.5.19（随 pagehelper starter） |
  | mybatis-spring-boot | 2.3.1 | 4.0.1（同上） |
  | pagehelper | 5.3.3 | 6.1.1（同上） |
  | baomidou | 3.5.9 | 3.5.17（只剩 `mybatis-plus-annotation`） |
  | springdoc | 1.6.9 | 3.1.1 |
  | poi | 4.1.2 | 5.5.1 |
  | fastjson2 | 2.0.57 | 2.0.57（不变） |
  | jjwt | 0.9.1 | 0.9.1（不变） |

  其余改名：`mysql-connector-java` → `mysql-connector-j`、starter-web → starter-webmvc、starter-aop → starter-aspectj。测试 surefire 以 `-javaagent` 挂 Mockito 5。JUnit / Mockito / H2 的确切版本因 Maven Central 返回 429 未取到。
- **javax → jakarta**：分叉点 142 个 javax EE 文件，master 上其中 141 个已改 jakarta，master 无残留 javax EE import。
- **Jackson**：master 仍是 Jackson 2 包名（178 个 main 文件 `com.fasterxml.jackson`，0 个 `tools.jackson`）。它经 `spring-boot-jackson2` 桥接，`preferred-json-mapper: jackson2`，因此移植**不需要**改 Jackson 包名。
- **框架**：
  - SecurityConfig 由 `antMatchers` 改为新的 `LegacyAntRequestMatcher`；`SecurityFilterChain` 分叉时已在用。
  - RedisConfig `CachingConfigurerSupport` → `CachingConfigurer`。
  - 新增 RequestIdFilter、RuntimeFailureAlarmFilter、BackgroundSchedulingConfig 等。
- **迁移**：master 新增 34 个 `chengyinhub-admin/sql/` 文件，与 lineage 迁移无同名。

## (c) 逐项：实现提交、文件、与 master 的重叠、Boot 4 / Java 21 影响

“3-way”指 `git merge-file`：master 对 lineage 头、以分叉点为基、整文件合并的冲突块数。因为 lineage 头还带非本项改动，它是上限。38 个类被测驱动的生产文件共 182 个：123 个 master 未改，54 个 master 改过，5 个 lineage 新增。

### core-funds（CORE `5752ee6f`，27）

| 类（@Test） | 实现提交 | 生产文件 | 与 master 重叠（3-way） |
|---|---|---|---|
| OWN_C01_51Test（5，含 @Nested） | `ab3161f9` | FundActionPreflight Mapper.java/.xml、ServiceImpl | 无，master 未改 |
| OWN_C06_30Test（5） | `2c539fcd`、`5d28205d` | UmsMemberBalanceDetailMapper.xml、UmsMemberPointsDetailMapper.xml | master 各 +2/-2，0 冲突 |
| OWN_C04_20MySqlIT（2） | `9e60e0fa`；前置 `cc6a8b9a` | MerchantPayableServiceImpl、MerchantPayableBatchMapper.xml、UmsMemberMapper.java/.xml、PointsAwardServiceImpl | UmsMemberMapper +15 / .xml +34/-8，0 冲突 |
| E04_13Test（2，也在 extra-storage） | `bda1c4e8`（≡ EXTRA `f3e0198e`）+ `cc6a8b9a` | RedemptionRewardClawbackService、UmsMemberMapper.java/.xml、PointsAwardServiceImpl、MerchantPayableBatchMapper.xml | RedemptionRewardClawbackService master +55/-2，0 冲突 |
| OWN_C05_25MySqlIT（4） | `b818c6c7` | PlayerWithdrawalCooldownServiceImpl、SysConfig mapper/service/xml | SysConfigServiceImpl 仅 javax→jakarta，0 冲突 |
| OWN_C04_21MySqlIT（3） | `df54dc03`、`2b1984b5` | 11 个文件，新增 WithdrawalBalanceAdmissionService | **CashWithdrawServiceImpl 2 块**；其余 0 冲突 |
| OWN_C04_19Test（4） | `ed077b31` | PlayerRunSessionMapper.xml | **1 块** |
| OWN_C06_10Test（2） | `9c55cda0`（+3 行） | GameSessionRuntimeServiceImpl | master +2511/-22，**2 块**，需手工落位 |

### ai-quota（CORE `5752ee6f`，22）

| 类（@Test） | 实现提交 | 生产文件 | 与 master 重叠 |
|---|---|---|---|
| AiQuotaReservationRedisIT（15）、AiQuotaRecoveryMySqlRedisIT（7） | 测试只由 squash `231f201e` 加入。AI 子集：AiQuotaReservationRecord、Mapper.java/.xml、AiQuotaRecoveryService/Task、AiGatewayService(Impl)。后续 10 个提交：`cd7ee699` C05-10、`4f34eb14` C05-11、`741edebe` C05-17、`b77e7d52` C05-18、`aa816dcc` C05-19、`91cf1a19` C05-20、`eb15c34f`、`2b8fbd97`、`3b53769c`、`f72ddb14` | 约 8 个 squash 文件 + 6 个后续文件，新增 AiProviderOutcomeUnknown/ResultReceivedException | AiGatewayServiceImpl、OpenAiCompatibleLlmClient、AiSceneRouter master 未改；NpcChatService +30/-7、PhotoCheckVisionService +6，均 0 冲突；AiQuotaReservation* 在 master 不存在 |

**迁移**：`chengyinhub-admin/sql/migration_ai_quota_reservation_recovery_20261013.sql`（master 无，RecoveryIT 直接读它）。

### e04-07-coupon（EXTRA `8822ab38`，8）

| 类（@Test） | 实现提交 | 生产文件 | 与 master 重叠 |
|---|---|---|---|
| E04_07MySqlIT（5） | `923170ab`（E04-07-B）、`dedb44d5`（E04-07-A）、夹具 `a9ad53ae`（#285） | RedemptionRewardClawbackService、TopicCompletionCouponRewardServiceImpl、CmsTopicCompletionCouponRewardMapper.java/.xml、SmsCouponHistoryMapper.xml | 只有 RedemptionRewardClawbackService 重叠，0 冲突 |
| RedemptionRewardClawbackMyBatisTest（3） | 同上 + `f3e0198e` + `cc6a8b9a` | 10 个 | master 已有此类且为 **4 条**（`4b83bdd5` 加 `wholeOrderReversalClawsStoreVerifyRewardsOnceAndKeepsCompletion`） |

**迁移**：`chengyinhub-admin/sql/migration_topic_completion_coupon_history_receipt_20261016.sql`（`history_id`，master 无；夹具自建该列）。

### community-e02（EXTRA `8822ab38`，14）

| 类（@Test） | 实现提交 | 生产文件 | 与 master 重叠 |
|---|---|---|---|
| E02_22Test（9） | `ff7cddb9` | CommunityPostMapper.java/.xml、CommunityPostServiceImpl | **CommunityPostMapper.xml 2 块**（master +69/-12）；ServiceImpl master +135/-12 但 0 冲突 |
| E02_25MySqlIT（2） | `faefbaf8`（E02-24 基线）+ `29fe9236`；夹具 `6c6f82d7` | 同上 | 同上 |
| E02_01 / E02_06 / E02_11Test（1/1/1） | `abd374d1` / `35a8536c` / `b8979dad`；夹具 `6c446369` | CommunityPostServiceImpl | 0 冲突 |

### b05-visibility（B05 `2ac55d20`，3）

| 类（@Test） | 实现提交 | 生产文件 | 与 master 重叠 |
|---|---|---|---|
| B05CommunityVisibilityMySqlIT（3） | 测试 `ba910732`、`2ac55d20`；行为来自共享基 `e437a527`（+ `abd374d1` / `2ff5c10d`） | CommunityPostMapper.xml（1 行） | 与 E02 同一文件，2 块 |

### f3-growth（F3 + #294 `763dcb43`，12）

| 类（@Test） | 实现提交 | 生产文件 | 与 master 重叠 |
|---|---|---|---|
| PlayerGrowthConcurrencyMySqlIT（12） | `2babbe28` 串行玩家成长写入；#294 `763dcb43` 死锁重试 | `2babbe28`：PlayerProgressionServiceImpl、PlayerGrowthMapper.java/.xml，master **均未改**。#294：DeadlockRetry 注解/拦截器/配置（新文件）+ 5 个文件上的 12 处入口注解 | 注解所在文件 master 改动：ApiPlayProgressController +69/-9、TopicRouteRuntimeServiceImpl +1400/-138、CmsRegistrationServiceImpl +282/-64、TopicCompletionAwardServiceImpl +8/-3、PreferencePlayService 未改。#294 只加注解行，按方法名手工落位；master 新增的事务入口需按 #294 的副作用审计口径重新审计后再决定是否加注解 |

master 仍是 marker-first：`TopicCompletionAwardServiceImpl` 约 66–94 行，配合 `PlayerProgressionServiceImpl.onTopicComplete`。因此**同一死锁在 master 上同样存在**。

### extra-storage（EXTRA `8822ab38`，71）

| 类（@Test） | 实现提交 | 与 master 重叠 |
|---|---|---|
| E01_06 / E01_09Test（2/2） | 无生产提交（`NO_CHANGE_SOURCE_REVIEW`，只有测试） | 依赖 ClubWaitlistServiceImpl / ClubEventOpsMapper.xml 的 EXTRA 状态 |
| E01_10Test（3） | `106f2e5a` | ClubEventOpsServiceImpl **1 块** |
| E01_18Test（5）/ ClubWaitlistTokenMybatisIntegrationTest（7） | `8b4ae47a` | ClubWaitlistServiceImpl +42、ClubEventOpsMapper.xml +30/-6，0 冲突；master 也有 ClubWaitlistToken（7，同名） |
| E04_06Test（2） | `07bbbdc7` | **SmsCouponHistoryServiceImpl 2 块**（master +17/-19） |
| E04_10Test（3） | `a8f9cc4b` | 同上 + **SmsCouponMapper.java/.xml 各 1 块** |
| E04_13Test（2） | `f3e0198e` + `cc6a8b9a` | 同 core-funds |
| E04_14Test（2）/ CouponLifecycleMybatisIntegrationTest（12） | `0097e1c6`（同文件还有 `4902cc1c`、`07ff820c`） | SmsCouponHistoryServiceImpl 2 块；**master 上 CouponLifecycle 为 16 条** |
| E02_10 / E02_18 / E02_20Test（1/1/4） | `2ff5c10d` / `75e1abc2` / `acc03a06` | CommunityPostMapper.xml 2 块（E02_20） |
| E02_15 / E02_16Test（4/3） | 无生产提交（只有测试） | – |
| A03EndingSnapshotMySqlIT（3） | `38513c90`、`bdd53461`、`15912d5c`；**方法 3 还需 `8822ab38`**（#287） | **TopicRouteRuntimeServiceImpl：master +1400/-138，EXTRA 3-way 5 块** |
| B03CouponLifecycleMySqlIT（4） | `231f201e` 的券来源发放子集（CouponSourceGrant、Mapper.java/.xml、`grantCouponBySource`）+ `07bbbdc7` | SmsCouponHistoryServiceImpl 2 块；CouponSourceGrantMapper.xml master 无 |
| B04ScheduleReadbackMySqlIT（5） | `1352b6af`（ClubEventOpsServiceImpl +40、ClubEventSeriesView、xcx event-ops） | ClubEventOpsServiceImpl 1 块 |
| JourneyFullFlowMySqlIT（4） | `231f201e` + EXTRA journey 栈（`67bfe34f`、`0b301c9b`、`38513c90`、`bdd53461`、`15912d5c`、`ace11a90`、`b5eb8866`、`3e1349ff`、`a37ce191`、`31c66e77`、`129d08ea`、`0ee9a998`） | TopicRouteRuntimeServiceImpl 5 块；**master 同名类方法 4 断言相反**（见风险） |
| RoamShareSnapshotConcurrencyMySqlIT（2） | `ffba8a3e`、`d2b2a8a5` | ApiRoamShareController +35/-4，0 冲突；**master 上该类为 3 条** |

`231f201e` 的 B03 关联迁移 `migration_mini_foundation_coupon_source_grant_20261013.sql` 在 master 不存在，测试自建表。

### PR #231（E02-13）

- **提交**：`5ee9d50d` 社区私有附件短签与稳定媒体身份重试；`b0dc1019` 社区附件短签固定使用HTTPS。
- **文件**：18 个，+1206/-42。
  - main：ApiCommonController、AuditWorkbenchController、FileOssUploadUtils、ICommunityPostService、CommunityPostServiceImpl。
  - 测试与前端：6 个 Java 测试、UI 2 个、xcx 4 个。
- **与 master 重叠**：自 `0aab8b76` 以来 master 未改这 18 个文件；`git merge-tree --write-tree origin/master origin/pr231` 干净。

### Boot 4 / Java 21 影响小结（实测）

- **javax**：实现文件里只有 NpcChatService（`javax.annotation.Resource`）和 SysConfigServiceImpl（`javax.annotation.PostConstruct`）。master 已是 jakarta，3-way 时保留 master 的 import。另有 3 个只读 DTO 用 `javax.validation`，master 也已是 jakarta。
- **Spring API**：所有项文件都**未使用**以下 API：RestTemplate builder、HandlerInterceptorAdapter、WebSecurityConfigurerAdapter、`@ConstructorBinding`、Spring `StringUtils` / Base64Utils / ListenableFuture。
- **异常翻译**：#294 捕获 `DeadlockLoserDataAccessException`（Spring 6.0.3 起 deprecated）和 `CannotAcquireLockException`。移植时要按 master（Spring 7）实际把 MySQL 1213/1205 翻译成的类型来捕获，例如 `PessimisticLockingFailureException` 家族，并用 IT 负控证实。
- **MyBatis**：3.5.13 → 3.5.19、mybatis-spring 2.x → 4.0.1、pagehelper 5 → 6。38 个测试用的是普通 `SqlSessionFactoryBuilder` / `XMLMapperBuilder` / `SqlSessionFactoryBean` / `TransactionInterceptor`，源码兼容。要关注 `XMLMapperBuilder` 构造签名和 `SqlSessionTemplate` 行为有无变化（编译即知）。
- **测试基础**：38 个都是 JUnit 5，无 javax、无 H2、无 JUnit 4、无 Boot test slice。master 用 Mockito 5（inline mock maker、`-javaagent`），`mock(...)` 与 `ReflectionTestUtils` 用法兼容。
- **JDK**：登记表已有 `jdk_for_java_version: {"21": "21"}`，master 源在 runner 上会用 JDK 21。

## (d) 移植顺序与做法

**做法**：
- 从 master 新开**一条**集成分支（例：`feature/master-port-real-storage-20261019`）。
- **逐项** cherry-pick 或手工移植上表的实现提交，**不合并任何 lineage 分支**：lineage 头带大量非本项改动和 Java 8 基线。
- 每项一个提交，连同测试，在 Java 21 下编译并跑该项相关普通测试。
- 每完成一组，用真库入口对集成分支头跑对应批次。
- `231f201e` 只摘对应子集（AI 额度、券来源发放），不整包搬。

**顺序**：资金、额度、并发优先；组内先无冲突后有冲突。

1. **资金，无冲突**：OWN-C01-51 `ab3161f9` → OWN-C06-30 `2c539fcd` + `5d28205d` → OWN-C04-20 `cc6a8b9a` + `9e60e0fa` → E04-13 `bda1c4e8` → OWN-C05-25 `b818c6c7`。
2. **资金，小冲突**：OWN-C04-21 `df54dc03` + `2b1984b5`（CashWithdrawServiceImpl 2 块）→ OWN-C04-19 `ed077b31`（XML 1 块）→ OWN-C06-10 `9c55cda0`（GameSessionRuntimeServiceImpl 手工落 3 行）。
3. **券资金**：
   - E04-07 `dedb44d5` + `923170ab` + 夹具 `a9ad53ae` + `history_id` 迁移文件；
   - 再 E04-06 / 10 / 14 + CouponLifecycle（`07bbbdc7`、`a8f9cc4b`、`0097e1c6`、`4902cc1c`、`07ff820c`；SmsCouponHistoryServiceImpl 2 块、SmsCouponMapper 1+1 块）；
   - 再 B03（`231f201e` 券来源发放子集 + 迁移文件）。
4. **AI 额度**：`231f201e` 的 AI 子集 + `migration_ai_quota_reservation_recovery_20261013.sql` + 10 个后续提交。AI 主文件 master 未改，可直接 apply。
5. **并发**：
   - F3 `2babbe28`（3 文件 master 未改）+ #294 死锁重试（含 Spring 7 异常类型确认）；
   - E01-18 / ClubWaitlistToken `8b4ae47a`；
   - E02-22 `ff7cddb9`、E02-25 `faefbaf8` + `29fe9236`、E02-20 `acc03a06`（CommunityPostMapper.xml 2 块）；
   - RoamShare `ffba8a3e` + `d2b2a8a5`，测试取 master 的 3 条版本。
6. **快照读 / 可见性**：E02-01 / 06 / 10 / 11 / 18、B05 `e437a527`、E01-10 `106f2e5a`、B04 `1352b6af`（ClubEventOpsServiceImpl 1 块）；仅测试项 E01-06 / 09、E02-15 / 16 只搬测试。
7. **最后**：A03（`38513c90`、`bdd53461`、`15912d5c`、`8822ab38`）与 JourneyFullFlow。TopicRouteRuntimeServiceImpl 冲突最多，且 Journey 方法 4 的预期行为需 owner 先拍板。
8. **PR #231**：master-based、合并干净，可在任一步之间按普通 PR 合入 master。若集成分支已改 CommunityPostServiceImpl，则在第 5/6 步之后 rebase 或合入集成分支再验，避免与 E02 系列交叉。

## (e) 登记表 / 批次如何对 master 重新登记与重跑

- 登记改动单独走 draft PR 到 master，由 owner 评审合入。workflow 从 master 读登记表，`ci/test_real_storage_it.py` 的批次总数断言要同步改。
- 每批的 `lineage` 改为集成分支名，`registered_source` 改为该批全部移植完成后的集成分支头 SHA。批次名保持不变，以便与 #281 的历史结果对照。
- 条数变化（以 master 上已有同名类为准，已核实）：

| 批次 | 现登记 | 移植到 master 后 | 变化原因 |
|---|---|---|---|
| core-funds | 27 | 27 | – |
| ai-quota | 22 | 22 | – |
| e04-07-coupon | 8 | **9** | RedemptionRewardClawbackMyBatisTest 3 → 4（master 多 1 条，EXTRA 改动 3 条的方法体） |
| community-e02 | 14 | 14 | – |
| b05-visibility | 3 | 3 | – |
| f3-growth | 11（#295 后 12） | **12** | #294 负控 |
| extra-storage | 71 | **76** | CouponLifecycleMybatisIntegrationTest 12 → 16（EXTRA 的改名替换 master 的旧方法）；RoamShareSnapshotConcurrencyMySqlIT 2 → 3；JourneyFullFlow 4 = 4（断言冲突待定）；ClubWaitlistToken 7 = 7 |
| **合计** | 156 | **163** | |

- E04_13Test 同时登记在 core-funds 与 extra-storage。移植后只有一份源（CORE 与 EXTRA 两份相同），跑在合并后的 RedemptionRewardClawbackService 上。两个批次都保留，各跑一遍。
- 重跑顺序同 (d)：每组移植完即跑该组批次。全部移植后对同一集成分支头**一次性重跑全部 7 批**，作为移植结论。失败按 round 1 规则处理：harness 问题开 draft PR 到 master；候选缺陷只修该项，再定点 re-run。
- `max-parallel: 20`、镜像 pin、门控变量不变。

## (f) 风险与工作量估计

**风险**
1. **TopicRouteRuntimeServiceImpl**：master +1400/-138，EXTRA 3-way 5 块，影响 A03 和 Journey。手工合并错一处就会改到主题运行时；放最后做，并以 A03 / JourneyFullFlow 真库 + 现有 TopicRoute* 普通测试兜底。
2. **JourneyFullFlowMySqlIT 方法 4 语义相反**：`231f201e` 改为「无唯一动作键时锁序仍保证幂等重放」，master 仍断言「会丢幂等重放」。条数相同，门禁查不出来，**需 owner 决定以哪边为准**。
3. **死锁重试在 Spring 7 下的异常类型**（见 (c)）。若捕获类型不对，重试会静默失效，必须用负控 IT 证实。
4. **吞掉死锁异常的旧 catch**（#294 审计发现，未修）：`logAttempt`、`refreshBingo` / 宾果格奖励、拓展赏金 NESTED savepoint、风控 `inspectAndQueue`。死锁落在这些点时重试不触发，并会在 MySQL 已整笔回滚后继续执行。建议单列一项修复。
5. **`231f201e` 只摘子集**：漏带依赖会编译失败（会立即暴露），多带则把无关基线带进 master。按 `deps_*` 清单逐文件核对。
6. **SmsCouponHistoryServiceImpl / CommunityPostMapper.xml 的冲突块都在券和社区资金路径上**：合并后必须跑全部券 / 社区批次，不能只跑单类。
7. **迁移文件**：只进仓库，不在线上执行；夹具自建列和表，所以真库 IT 过了**不代表**迁移本身在持久库上执行过。
8. **Mockito 5 / JDK 21 下的测试行为**（如 inline mock、反射访问），编译后首轮跑可能出现与业务无关的失败，需当作 harness 问题区分处理。

**工作量（估计，单人工作日）**

| 步骤 | 估计 |
|---|---|
| 1–2 资金 | 1.5 |
| 3 券 | 1.5 |
| 4 AI 额度 | 1.5 |
| 5 并发（含重试的 Spring 7 适配） | 1.5 |
| 6 快照 / 可见性 | 0.5 |
| 7 A03 / Journey（不含 owner 决策等待） | 1.5 |
| #231 | 0.25 |
| 登记 PR + 7 批全量重跑与回帖 | 0.5 |
| **合计** | **约 8–9 个工作日**，区间 7–11 天，主要不确定性在第 7 步 |

真机 / VPS HTTP 验收另计（见 (g)）。

## (g) 未决事项

- **F3 证据测试**：「最大合法年份」与「RR 旧 read view」两个问题仍需专门的证据测试，当前 `PlayerGrowthConcurrencyMySqlIT` 不覆盖（登记 note 已注明）。
- **F3 死锁重试真库 re-run**：等 #295（登记 11 → 12）合入后，在 `763dcb43` 跑 `f3-growth`。
- **迁移另行执行**：例如 E04-07 的 `history_id`（`migration_topic_completion_coupon_history_receipt_20261016.sql`）、AI 额度 `migration_ai_quota_reservation_recovery_20261013.sql`、B03 券来源发放表。按 chengyin-db-migrate 流程做只读预检，经 owner 确认后另行执行；本计划和真库 IT 都不执行它们。
- **集成后的 HTTP 业务验收**：在 VPS 上用真实身份做，由 owner 的本地会话负责。真库 IT 不覆盖真 HTTP、真实身份和真机。
- **JourneyFullFlow 方法 4** 以哪边为准（风险 2）。
- **吞异常 catch** 是否单列修复（风险 4）。
- **`RedemptionRewardClawbackMyBatisTest` 的 cleanup**：门禁报 `LEFTOVER`（该夹具保留 `chengyin_mybatis_*` 库，容器随即删除），不影响判定。是否让门禁把它视为预期，待 owner 决定。
