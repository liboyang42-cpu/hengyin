# R3-CLOUD 后端集成合并记录（integration/backend-app-r3-cloud）

- 日期：2026-11-15（定时任务云端执行）
- 基线：`origin/integration/backend-app-r3` @ `eaed33f4d8ba62fef22a24cacec6e2131dcebe72`（r3 存在，未回落到 r2）
- 本分支只推 `integration/backend-app-r3-cloud`；未推 `integration/backend-app-r3`、master/main，未部署，未动 ledger / gitleaks 文件。

## 1. 分支清单与 SHA

| # | 分支 | origin SHA | 处理 |
|---|------|-----------|------|
| 1 | fix/r5-be-infra-stubs | `32b425c3311e75b206b6b9ad5de20db25b9c10a3` | 已包含在 r3，跳过 |
| 2 | fix/api-smoke-4xx | `a67acd4c26cd2d2a1fb35a4cf36a1b6ab21da8a4` | 已包含在 r3，跳过 |
| 3 | fix/play-nodes-500 | `c1c1a1a44262cdb8f48d814788e4f41488b38746` | 已包含在 r3，跳过 |
| 4 | fix/api-smoke-500-r2 | `83d5cc6f4faef1194c963cce3645e5dd4afa4de1` | 合并（有冲突） |
| 5 | feature/sys-weather | `80df276d1cd6fbd8c08e60062f6af0278ccdd2fc` | 已包含在 r3，跳过 |
| 6 | feature/sys-citygame | `eb610901aa1c309a10f9af5b16d1b4a9d2dbaa7e` | 合并（无冲突） |
| 7 | feature/sys-random | `57027c2744f0315e6d148284c04e25a11e1a25ed` | 合并（有冲突） |
| 8 | feature/sys-ainpc | `36a0a7e85f040d2d339f86f8dc90bbfe0d90b5a7` | 合并（有冲突） |
| 9 | feature/ingame-channels | `280fcf2419f2dee62d90c1b2338e30481650838b` | 合并（有冲突；含 citygame 提交，已先合入） |
| 10 | feature/extgame-money | `c71445b6bb91983751aae495f74a93f379efcf18` | 合并（有冲突） |
| 11 | feature/game-types | `f9e107746e1856ba55cbb1290b6d825f15f5dde4` | 合并（无冲突） |

每次合并都是 `--no-ff`，提交信息为 `integ/r3-cloud: merge origin/<branch> (<sha9>)`。

## 2. 冲突与解决

1. **fix/api-smoke-500-r2** —— `ApiCartController.java`、`ApiCartControllerAppAuthTest.java`
   r3 这边已是超集：`orderSettlement` 在 `try` 外先取 `getAppUserName()`（未登录 401 不会被 `catch ServiceException` 吞成业务错误），测试多一个 `unauthenticatedCallerThrows401`。两处都保留 r3 版本；该分支其余 4xx/503 映射改动自动合入。
2. **feature/sys-random** —— `CityGameActionService.java`（ROLL_ROUND）
   citygame 把掷骰统一到 `executeUnifiedDiceRoll`（审计 + 行动力 + 板版本）；sys-random 把掷骰随机源换成共享 `GameRandom`（`effectiveRandom(...)`，域 `CITY_DICE`，有审计）。解决方式：分支保留 citygame 的统一路径，并把 `executeUnifiedDiceRoll` 内的 `random.applyAsInt(totalWeight)` 改成 `effectiveRandom("MOVE", requestId, memberId, boardId, diceId).applyAsInt(totalWeight)`。这样 `/game/action` ROLL_ROUND、`/action-budget/command`、`/dice/roll` 三个入口都走 GameRandom；`effectiveRandom` 的签名不变（`SysRandomCallersWiringTest` 通过反射调用）；测试构造器注入的 `random` 仍然生效。CHALLENGE 对决仍按 sys-random 的写法使用 `effectiveRandom("CHALLENGE", …)`。
3. **feature/sys-ainpc** —— `E2eTestProvidersProfileTest.java`
   Profile 清单两边各加一项，两项都保留：`E2eTestTopicWeatherProvider`（weather）和 `E2eTestNpcDialogueResponder`（ainpc）。
4. **feature/ingame-channels** —— `PlayBasicCommandApplication.java`（扫码打卡三条路径）
   r3 加了 `attachDispatchedTask(...)`（sys-random 盲盒任务派发），ingame-channels 加了 `inGameChannelService.postPlayEvent(EVENT_CHECKIN, …)`。两段都保留：先挂任务，再发频道事件，最后返回。FREE_COLLECT 分支返回 `proofData`，这样派发的任务仍在响应里。
5. **feature/extgame-money** —— `PlayNodeQueryApplication.java`
   两边各加一个 `@Autowired(required=false)` 字段（`topicWeatherEventService`、`externalGameMonetization`），两个都保留。

### 合并后的修正（独立提交）

- `ci/migration-dependencies.json`：
  - feature/sys-citygame 把整个文件重新缩进成多行格式，内容没变（已用脚本核对：r3 的 479 条依赖一条没删、一条没改）。但 `CreatorIncomeDetailNoWritePathTest` 要按文本精确匹配单行写法，所以这次失败是真失败。修法：恢复 r3 原有的紧凑格式，只在末尾追加新条目，diff 只剩 6 行新增。
  - 迁移安全门（`ci/migration-safety-check.sh` [5/5]）发现 5 个新迁移没有声明依赖，已补上（只增不删）：
    - `20261114_weather_event_rule: []`（只新建表）
    - `20261115_external_game_monetization: []`（只新建表，无外键）
    - `20261115_external_game_monetization_menus: [20261115_external_game_monetization, 20261012_missing_business_menu]`（挂在 `audit-center` 目录下）
    - `20261115_sys_ainpc: [npc_profile, 20261119_merchant_gap, 20261012_missing_business_menu]`（ALTER 了 `npc_profile`、`merchant_npc_avatar_job`；菜单挂在「供给与发布」下）
    - `20261123_sys_random: [20261012_missing_business_menu]`（菜单挂在 `supply` 下）
    - citygame 自带的 `20261123_sys_citygame` 条目保留。
- 回滚脚本按迁移布局门改用 `bak_` 前缀（不在库上执行，不进 release manifest）：
  - `rollback_20261114_weather_event_rule.sql` → `bak_weather_event_rule_20261114_rollback.sql`（r3 基线上本来就违反这道门；已同步 `WeatherEventRuleMySqlIT` 和 `SYS-weather.md` 里的引用）
  - `rollback_20261115_external_game_monetization.sql` → `bak_external_game_monetization_20261115_rollback.sql`
- 所有 `migration_*.sql` 都没改过内容，也没删任何迁移；新增迁移都是 `CREATE TABLE IF NOT EXISTS`、按 information_schema 判断后才 ADD COLUMN，以及 NOT EXISTS 守卫的菜单/配置插入，全部是增量变更。

门禁结果：`bash ci/migration-layout-check.sh` 通过；`bash ci/migration-safety-check.sh` 通过（“本批新增 37 个迁移均被共享台账覆盖，全部依赖边顺序正确”）。

## 3. 测试

命令：`LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q test`

- **第一次（按任务给的命令原样运行）**：`chengyinhub-admin` 的测试 fork 报 `java.lang.OutOfMemoryError: Java heap space` 崩溃。原因是根 pom 默认 `argLine=-Xmx512m`，而 CI（`.github/workflows/ci.yml:568-569`）用的是 `MAVEN_OPTS=-Xmx1024m` 加 `-DargLine=-Xmx2048m -Dspring.test.context.cache.maxSize=4`。崩溃前报告 12613 run / 0 failures / 34 errors / 563 skipped，但约 1100 个测试类没有跑到。属于环境配置问题，pom 没改。
- **第二次（按 CI 的堆参数）**：`MAVEN_OPTS=-Xmx1024m mvn -B -q test "-DargLine=-Xmx2048m -Dspring.test.context.cache.maxSize=4"`
  - **Tests run 19736，Failures 1，Errors 34，Skipped 713**（共 2424 个测试类，源码中的测试类全部跑到）
  - 真失败 1 个：`CreatorIncomeDetailNoWritePathTest.idempotencyUniqueKeyMigrationIsPresentAndRegistered`，原因是上面说的 JSON 重新缩进，已修复。
  - 修复后，引用 `migration-dependencies.json` 的 10 个测试类全部重跑通过（`mvn -pl chengyinhub-admin -am test -Dtest=CommunityPostMigrationContractTest,OrderClosePayingReconcileTaskContractTest,TopicSourceMenuMigrationSqlTest,MerchantCrmPhase1AContractTest,CreatorIncomeDetailNoWritePathTest,R6FundsMigrationContractTest,ActivityCancelManualRefundCaseContractTest,MmsMerchantMemberIndexMigrationContractTest,ApprovalGovernanceMigrationContractTest,ClubMembershipMigrationContractTest`，rc=0）。
  - 修复后：**真失败 0**。

## 4. 仅环境原因的失败（需要 MySQL，本容器没有）

34 个 error 全部是 `IllegalStateException: 建 IT 专属库失败(...)：Communications link failure`，分布在 14 个 `com.chengyinhub.business.it.*MyBatisTest` 类：

AuditReportLaneMyBatisTest、ClubCrmCheckinDetailMyBatisTest、ClubDissolutionGateMyBatisTest、ClubTopicManageStatsMyBatisTest、ComplianceF7MyBatisTest、MerchantDashboardAggregationMyBatisTest、MerchantNumbersAgreeMyBatisTest、O08MemberOnlyTopicLocationMyBatisTest、PublicActivityScopeGateMyBatisTest、PublicTopicFeedGateMyBatisTest、PublicVisibilityMyBatisTest、RedemptionRewardClawbackMyBatisTest、TopicDraftVisibilityMyBatisTest、TopicSyncPostRestoreMyBatisTest。

713 个 skipped 主要是按环境变量开关的 MySQL / 真库 IT（例如本批新增的 `SysRandomMySqlIT`、`ExternalGameMonetizationMysqlIntegrationTest`、`WeatherEventRuleMySqlIT`），这次没有运行。它们要在 CI 或测试 VPS 的 MySQL 上验证。

## 5. 货币（只有一种：积分）

- 合并后的 diff 和全仓都搜过 `城币|CITY_COIN|city_coin|cityCoin|CityCoin`：**0 处命中**。没有任何分支引入第二种币，所以没有需要转换的地方。
- 核对 feature/extgame-money：流量激励 `trafficRewardMode=POINTS` 经 `walletBridge.creditPoints(...)` 记入**积分**（流水文案「外部游戏流量日结积分」）。`currency='CNY'` / `price_fen` 只是外部游戏授权订单的人民币计价（法币），不是游戏内币种。另外 `TEST_MONEY` 模式只用于测试收益。
- 只有一处表述需要注意，没有改：`migration_20261115_external_game_monetization.sql` 里 `revenue_mode` 的列注释写的是 “in-game points/coins”，实际含义就是积分。为了不让迁移文件和 feature 分支产生差异，这次没改它。以后如果要改这条注释，要单独出补丁。
- 其他新增的 “coin” 都是 PlayKit 的抛硬币玩法（`flipCoin` / `DOMAIN_PLAYKIT_COIN`），不是货币。
