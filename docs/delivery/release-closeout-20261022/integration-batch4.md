# 发布收尾 batch4 集成候选记录（2026-10-22）

- 集成分支：`integrate/closeout-batch4-20261022`，对应 draft PR（见 PR 正文），只供人工审阅后合并。
- 基线：本批开始时 #512（batch3）**尚未合入 master**，所以本分支从 `integrate/closeout-batch3-20261022` 的 head `e7fe467a` 切出，PR base 指向该分支。#512 合入 master 后，需要把 PR base 改为 `master`。
- 范围：
  - 不部署、不连生产、不执行任何 DB 变更（迁移只入库文件，不落库）。
  - 没有合并或关闭任何 PR，没有推 master，没有删分支。
  - 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 后续 lane：F6 P1、O5a、O5b 在本批开始时还没有开 PR，本批不纳入。

## 1 合入顺序（全部 `git merge --no-ff <PR head>`）

依赖顺序：DL1 先行（资金路径），F1a 先于 F1b/F1c/F1d，F2 先于 F5（F5 的条件/效果词汇消费 F2 的属性定义），F3 先于 F4。

| 顺序 | PR | Lane | head | 合并提交 | PR 自身 CI（head 上） |
|---|---|---|---|---|---|
| 1 | #513 | DL1 死锁重试（资金路径） | `5cd520be` | `a1393055` | GitHub CI 39827200737 ✅ · Funds 39827200681 ✅ |
| 2 | #514 | F1a 城市定向会话生命周期 | `6e746b30` | `a7d4636d` | 39827215735 ✅ · Funds 39827215752 ✅ |
| 3 | #515 | F1b 城市定向计分 | `458995f7` | `6764870e` | 39826255387 ✅ · Funds 39826255297 ✅ |
| 4 | #516 | F1c /arrive 位置守卫 + 点到店稳定身份 | `525c4342` | `2e6d2c56` | 39827191323 ✅ · Funds 39827191281 ✅ |
| 5 | #517 | F1d 坐标系 / AR 替代任务 / 队伍看板 | `57a5e55c` | `b9c01510` | 39826373825 ✅ · Funds 39826373882 ✅ |
| 6 | #518 | O15 角色槽 slot 写入闸 | `b8c08c19` | `0ef769d4` | 39827207846 ✅ · Funds 39827207683 ✅ |
| 7 | #519 | O14 每日推荐 / 统一搜索 / city_code | `1be14852` | `92dc134c` | 39829143318 ✅ · Funds 39829143360 ✅ |
| 8 | #520 | O3/O9 跨设备续玩按服务端时钟 | `0fe2c513` | `b70f47d0` | 39827752102 ✅ · Funds 39827747904 ✅ |
| 9 | #524 | F2 RPG P0 属性定义 / 角色卡 | `f82e1c87` | `38d105af` | 39828475400 ✅ · Funds 39828475351 ✅ |
| 10 | #521 | F5 故事引擎词汇 | `92aa3703` | `39739ec3` | 39827768860 ✅ · Funds 39827769095 ✅ |
| 11 | #522 | O11/O17 失败事件码 + 容量恢复门禁 | `666f5b63` | `d3f7aa9c` | 39829423585 ✅ · Funds 39829423639 ✅ |
| 12 | #523 | O8/O10 私密位置读路径 + 回执幂等 | `852f3782` | `34b25759` | 39829582327 ✅ · Funds 39829582364 ✅ |
| 13 | #525 | F7 合规（发票 / 未成年挂点） | `c4f434f4` | `f40de620` | 39828368823 ✅ · Funds 39828368676 ✅ |
| 14 | #527 | F3 自由探索套票 Phase 1 | `9982d242` | `fb317aa7` | 39828867926 ✅ · Funds 39828867910 ✅ |
| 15 | #526 | F4 商家支撑后端 | `d87be57f` | `bf6f0af6` | 39828076478 ✅ · Funds 39828076454 ✅ |

### 排除

无。15 个 PR 在各自 head 上 GitHub CI 和 Funds MySQL IT 都是绿的。#517 的 Mini followup run 39826373840 在列表快照里还显示 in_progress。该 lane 没有改小程序，本 PR 上的 Mini followup 会在合并树上重新跑一遍。

## 2 冲突处理

- `ci/migration-dependencies.json`：9 个 lane（F1a、F1b、F1c、F1d、O14、F2、F7、F3、F4）各自在依赖表头部或尾部追加条目，`git merge` 每次都报冲突。处理方式：
  - 在本地 `.git/info/attributes` 对此文件启用 `merge=union`（不入库），按并集保留全部条目。
  - F2 的尾条目与 F7 的尾条目拼接时丢了一个逗号，JSON 无法解析。单独提交 `cfbcfc0f` 只补回这个逗号，条目不变。合并后共 309 条，可以正常解析。
- `ci/backend-test.sh` 的 `MYBATIS_ITS`：DL1（`MerchantReferralBountyDeadlockRetryMySqlIT`）与 batch3 的 AC1/AC2 条目冲突，F2（`ThemeCharacterMapperMyBatisIT`）和 F3（`FreeExploreBundleLedgerMySqlIT`）也在末尾追加。三方条目和注释全部保留，`bash -n` 通过。
- 自动合并但被多个 lane 同时修改的文件（逐一检查过，改动区域不重叠；合并树上 main 和 test 编译均通过）：
  - `ApiPlayProgressController.java`：F1b、F1c、O8/O10
  - `StateKeyRefs.java`、`AdvancedGameConfigValidator.java`、`TopicRouteRuntimeServiceImpl.java`：F2、F5
  - `PerkLedgerServiceImpl.java`：F3、O11/O17
  - `OfficialEventV2ServiceImpl.java`：F1c、DL1
  - `CmsRegistrationServiceImpl.java`：F3、DL1
- `.gitleaksignore`：F1d 为首个提交里旧开关键名 `orient.geo.wgs84.enabled` 的三处历史指纹加了精确忽略。merge 保留了原提交，指纹仍然有效。当前源码里的键名已改为 `orient.geo.normalize.enabled`。

## 3 迁移（只入库，不执行）

本批新增 23 个迁移，加 2 个只读预检脚本：`_precheck_orient_node_geo_dirty_20261022.sql`、`_precheck_o14_content_city_code_backfill_20261022.sql`。数量的变化见第 9 节：评审修复删掉 F3 一个、新增 O14 一个，总数不变。文件名两两不同，与既有迁移也不重名。**没有改名任何迁移。**

跨 lane 的写入目标只有 `sys_config`。F1b、F1c、F1d、F4 四个 lane 的开关种子键名各不相同，种子都只在键缺失时插入。除此之外，没有两个 lane 建同名表，也没有两个 lane 改同一张业务表。

依赖（取自 `ci/migration-dependencies.json`，执行时按其拓扑序）：

| 迁移 | 依赖 | Lane |
|---|---|---|
| `orient_session_lifecycle_20261022` | `20260823_topic_route_graph` | F1a |
| `orient_scoring_20261022` | `20260823_topic_route_graph` | F1b |
| `play_arrive_guard_log_20261022` | — | F1c |
| `member_visited_poi_poi_key_20261022` | `member_visited_poi` | F1c |
| `member_visited_poi_poi_key_backfill_20261022` | `member_visited_poi_poi_key_20261022`、`roam` | F1c |
| `20261022_location_guard_switch_seed` | — | F1c |
| `orient_geo_ar_team_20261022` | —（先跑只读预检 `_precheck_orient_node_geo_dirty_20261022.sql`） | F1d |
| `o14_cms_topic_city_code_20261022` / `o14_cms_activity_city_code_20261022` | — | O14 |
| `o14_city_code_backfill_log_20261022` | — | O14（评审修复新增） |
| `o14_content_city_code_backfill_20261022` | 上面三条；执行前先跑只读预览 `_precheck_o14_content_city_code_backfill_20261022.sql` | O14 |
| `o14_content_search_document_20261022` | — | O14 |
| `o14_reco_search_jobs_20261022` | `o14_content_search_document_20261022`、`p3_platform_foundation` | O14 |
| `20261022_theme_state_key_attribute_def` | `theme_state_key_20260916` | F2 |
| `20261022_theme_character` | `theme_state_key_20260916`、`20261022_theme_state_key_attribute_def` | F2 |
| `20261022_f7_user_invoice` / `20261022_f7_minor_guardian` | — | F7 |
| `doc2_f3_bundle_ledger_20261022` | — | F3 |
| `merchant_desk_receipt_20261022` / `merchant_redemption_anchor_20261022` / `pms_sku_booking_20261022` / `verification_review_evidence_20261022` / `20261022_merchant_desk_switch_seed` | — | F4 |

DL1、O15、O3/O9、F5、O11/O17、O8/O10 没有新增迁移。所有迁移都要在**新 jar 之前**手动落库：先做 chengyin-db-migrate 只读预检，用户确认后执行。

## 4 开关清单（合并后全部默认关闭）

在合并树上逐个核对了代码里的缺省值和迁移种子值。本批没有任何 lane 改 `application*.yml`；改动的 yml 只有两个 CI workflow（`gameplay-http-e2e.yml`、`isolated-pool-recovery.yml`，来自 O11/O17）。

| 开关 | 来源 | 缺省 | Lane |
|---|---|---|---|
| `feature.flag.orientSession` | sys_config（只有严格等于 `true` 才开，不写种子） | 关 | F1a |
| `orient.scoring.enabled` | sys_config 种子 `false` | 关 | F1b |
| `play.arrive.location_guard.mode` | sys_config 种子 `off` | off | F1c |
| `play.arrive.require_accuracy` | sys_config 种子 `false` | 关 | F1c |
| `roam.visited_poi.stable_identity` | sys_config 种子 `false` | 关 | F1c |
| `orient.geo.normalize.enabled`、`orient.alt_task.enabled`、`orient.team.board.enabled`、`orient.safety.enabled` | sys_config 种子 `false`；键缺失也按关处理 | 关 | F1d |
| `chengyin.search.unified.enabled` | `@Value` 缺省 `false`；两个 Quartz 任务以暂停态登记 | 关 | O14 |
| `feature.flag.userInvoice`、`feature.flag.minorMode`、`feature.flag.minorPaymentGate`、`feature.flag.minorOrienteeringGate` | sys_config，键不存在就是关，不写种子 | 关 | F7 |
| `chengyin.free-explore.bundle.ledger-enabled` / `sale-enabled` / `reservation-enabled` | `@Value` 缺省 `false` | 关 | F3 |
| `merchant.desk.f4.enabled` | sys_config 种子 `false` | 关 | F4 |

没有开关的 lane：
- DL1：资金路径缺陷修复，始终生效。
- O15：写入校验，始终生效。现有写路径本来就只写这四个合法常量，所以行为不变。
- O3/O9：小程序续玩排序改按服务端时钟。
- F2：新列全部可空，老主题零行为变化。服务端 `natAuto` 缺省值等于老口径。
- F5：只扩展故事引擎词汇，老配置不受影响。
- O11/O17：事件码，加 CI 恢复门禁。
- O8/O10：读路径收口，加回归测试。

## 5 再生成与 CY-HANDOFF

- 本批只有 O3/O9 改了小程序（`pages/play/index.js`、`utils/play-run-session.js`，加两条单测），都只是逻辑改动，没有新增控件，也没有新增路由。
- 在合并树上运行 `build-action-ledger.js --write` 和 `build-route-matrix.js --write` 各一次，产物**零差异**：
  - 台账仍为 4967 控件；
  - 路由矩阵仍为 145 / 27；
  - `--check` 都 PASS。
- 因此 baseline 没有需要重锚或删除的过期项，`ui-integrity-baseline.js` 未改。
- CY-HANDOFF：15 个 PR 的正文里都没有 `CY-HANDOFF` 行，所以没有可落地的交接，也没有被拒绝的交接。

## 6 需要 owner 审阅的资金相关项

- **#513 DL1（资金路径，PR 自述 owner 已于 2026-10-22 批准方向）**：
  - 赏金等 NESTED savepoint 的 catch 现在只把 1213 / 40001 死锁，以及「回滚到 savepoint 失败」原样抛给外层，由 `@DeadlockRetry` 整笔重试，不再吞掉后在隐式新事务里继续提交。
  - 评审修复 `ab60b971`（RV3 S-513-1）：1205 锁等待超时不再上抛。原因是 1205 只回滚单条语句，savepoint 仍然有效，而多数调用方外层没有 `@DeadlockRetry`。
  - 金额、路由、幂等键、CAS 语义都没有改。
  - 与 F3 都改了 `CmsRegistrationServiceImpl`，与 F1c 都改了 `OfficialEventV2ServiceImpl`，均自动合并，改动区域不重叠。合并前请复核这两处。
- **#527 F3 Phase 1（funds-adjacent）**：
  - 在既有链路上挂了 4 个同事务钩子，开关关闭时全部空转。
  - 待 owner 裁决 K1–K8。其中 K3 最关键：罚没不会置位 `verification_status`，所以整单原路全退可以绕过罚没。**K3 解决前不要开 `reservation-enabled`。**
  - K2 部分退到平台余额，需要新的资金路由。
  - 评审修复 `182faa85`（RV3 B-527-1）：删除了 `migration_doc2_f3_settlement_claim_unit_20261022.sql`。Phase 1 不再改动资金表 `settlement_claim` 的唯一键，IT 改为断言该表不变。单位维度（K7）移到 Phase 2，届时另走资金审批。
- **#518 O15（funds-adjacent，只做写入校验）**：
  - slot 只接受四个封闭取值，否则拒绝，错误码 `PRODUCT_ROLE_SLOT_INVALID`。
  - `revenue_share` 的口径（A/B/C/D）待 owner 决定。本 PR 没有加数值校验。
- 其它在各 PR 正文里标注待 owner 决定的事项，集成没有改变结论：
  - F1a：PREPARED 阶段既有推进入口是否拒绝；
  - F1b：存量 product_type=1 主题是否随全局开关一起生效；
  - F1c：`require_accuracy` 要等最低小程序版本定下来才能开；
  - F1d：未成年监护数据源缺失，开 safety 后未成年人会被拦；
  - F2：属性范围唯一真源；
  - F7：开 `minorMode` 前须先改用户协议第十条和 `release-legal-gate.js`。

## 7 验证

- 本地（合并树）：
  - `mvn -DskipTests test-compile` 通过，覆盖全部模块的 main 和 test。
  - `mvn test -pl chengyinhub-system,chengyinhub-admin -am`：13543 条，Failures 0，Errors 29，Skipped 631。
    - 29 个 Error 分属 12 个 `business.it.*MyBatisTest` 类，全部是本地容器连不上 MySQL（`Communications link failure`），属于环境问题，与本批改动无关。
    - 其中包括 F7 自带的 `ComplianceF7MyBatisTest` 和 O8 的 `O08MemberOnlyTopicLocationMyBatisTest`。这两类在 CI 的真库作业里跑，以本 PR 的 CI 为准。
    - 没有任何断言失败。
  - 门禁全部 PASS：`dup_create_table_gate.py`、`migration-layout-check.sh`、`migration-safety-check.sh`、`classify-runtime-check.sh`、`ci-config-guard.sh`、`api-existence-gate.js`、`silent-error-gate.js`、`test_ci_cost_contract.py`、`test_release_manifest.py`。
  - ledger 和 route-matrix `--check` PASS。O3/O9 两条新单测，加上 action-ledger、route-matrix-freshness，共 58/58 通过。
- CI：run ID 见第 8 节。

## 8 本 PR 的 CI

说明：`ci.yml` 和 `funds-mysql-it.yml` 的 `pull_request` 触发只认 base=master 等固定分支。本 PR 的 base 是 batch3 集成分支，所以这两个 workflow 在本分支上改用 `workflow_dispatch` 手动触发。

### 首轮（head `e2f3df5a`）

- GitHub CI run 39834725556：31 个 job 中 30 个 success，1 个 failure（`Backend tests`）。
- 失败原因是跨批语义冲突，不是 flake：
  - batch3 #499 AC2 的 `JourneyThoughtProgressMySqlIT` 自建 schema 时只套了 `migration_theme_state_key_20260916.sql`。
  - batch4 #524 F2 让 `ThemeStateKeyMapper.xml` 改为查询扩展列，3 条用例报 `Unknown column 'min_value'`。
  - F2 自己已经在 `JourneyFullFlowMySqlIT`、`GameplayReskinMySqlIT`、`A03EndingSnapshotMySqlIT` 中追加了 `migration_20261022_theme_state_key_attribute_def.sql`。AC2 的这条 IT 是 F2 分支之后才进 batch3 的，所以没有覆盖到。
- 修复：在该 IT 的 `applyMigrations` 中按同样写法紧跟追加 F2 迁移。断言不变，没有跳过或放宽任何用例。
- 全仓复查：套用 `migration_theme_state_key_20260916.sql` 却没有追加 F2 迁移的 IT，只有这一个。

### 修复后（head `5c9e7139`）——全绿

| workflow | 事件 | run | 结论 |
|---|---|---|---|
| GitHub CI | workflow_dispatch | 39836155769 | success（含 Backend tests） |
| Funds MySQL IT | workflow_dispatch | 39836160409 | success |
| Mini followup focused regression | pull_request | 39836157798 | success |
| Isolated pool recovery | pull_request | 39836157718 | success |
| Isolated authenticated Redis recovery | pull_request | 39836157837 | success |

- 首轮 head `e2f3df5a` 上的 Funds MySQL IT 39834729553 也是 success。
- 本节之后只追加了纯文档提交，以 `5c9e7139` 的检查为准。
- #512 合入 master、本 PR base 改为 master 后，`pull_request` 事件会自动重跑全部必需 check。

## 9 集成后并入的评审修复（另一会话推送）

RV3 评审（#529）之后，各 lane 修复了评审发现，另一集成会话把修复并入本分支：

| 合并提交 | lane 修复 | 内容 |
|---|---|---|
| `943cd6c4` | #513 DL1 `ab60b971` | 1205 锁等待超时不再上抛；只有死锁，或 savepoint 已失效时才交给 `@DeadlockRetry` |
| `cad50eec` | #519 O14 `e08ba609` | city_code 回填只认 `club.city` 和父主题继承，不再对地址做子串匹配；新增回填日志表、只读预览和 `O14CityCodeBackfillMySqlIT` |
| `cb793e71` | #527 F3 `182faa85` | 删除 settlement_claim 唯一键迁移；Phase 1 不再动资金表 |

修复后 head `cb793e71` 的 CI 全部 success：

| workflow | 事件 | run |
|---|---|---|
| GitHub CI | workflow_dispatch | 39866446843 |
| Funds MySQL IT | workflow_dispatch | 39866450110 |
| Mini followup focused regression | pull_request | 39866418956 |
| Isolated pool recovery | pull_request | 39866418846 |
| Isolated authenticated Redis recovery | pull_request | 39866418838 |

第 3、6 节已按修复后的状态更新：迁移表、DL1 和 F3 的说明。开关清单不变，全部默认关闭。
