# DEFER-ROAM 交付（漫游与定位）· 2026-10-24

分支 `chore/defer-roam-20261024` → master，草稿 PR #583。上一轮在 `docs/delivery/bugfix-20261023/BUG-ROAM1.md` / `BUG-ROAM2.md` 里标为 DEFER 的漫游/定位非阻塞项，这里按优先级逐条处理，每条单独提交。

**状态统计**：FIXED 11 · PARTIAL 6 · ALREADY_FIXED 0 · NEEDS_OWNER 2（共 19 条）

## 关键约束：`pages/roam/index.js` 的行号基线

`chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`（C00 保留文件）登记 `pages/roam/index.js` 时用的是 **`文件:行号`** 作键，一共 31 条。这个页面里任何地方净增或净减一行，后面的登记点就都对不上，`lint:ui` 会判红。本 PR 不改这个基线文件，所以：

- 能做到行数不变的修复直接落地（U-092、U-109、U-120、U-124：都是原地替换同一行；新增的 require 追加在已有 require 的同一行末尾）。
- 要增删行的页面改动（U-089 删死代码、U-090 / U-091 / U-117 / U-126 页面接线）只把**工具函数和单测**落进本 PR，页面改动合成一个补丁：
  `docs/delivery/defer-20261024/DEFER-ROAM-C00-roam-page.patch`。
  这个补丁已在本分支 head 上验证过：`git apply` 干净打上；`node --test tests/unit/roam-*.test.js tests/unit/play-ui-contract.test.js` 549 条全过；`lint:scope` 通过。打上以后 `lint:ui` 会报 17 条 `[BASELINE]` 行号漂移，正是需要 C00 重排的那部分。

<!-- CY-HANDOFF: C00 —— 请在下一次集成时一并处理：(1) git apply docs/delivery/defer-20261024/DEFER-ROAM-C00-roam-page.patch；(2) 按 ui-integrity 实测结果，把 pages/roam/index.js 的 U4-DYNAMIC / U5 登记项等量换 key（调用点与理由不变），显式更新 digest；(3) 补丁会删掉 tests/unit/roam-fog-legacy-unreachable.test.js（它锁的是补丁要删的那段死代码）。补丁不改任何 wxml/wxss。 -->

## 逐条

| id | 优先级 | 状态 | 文件 | 测试 | 说明 |
|---|---|---|---|---|---|
| B:P1-059 | P1 | FIXED | `RoamSession.java`（新增 `STATUS_ACTIVE=0` / `STATUS_FINISHED=1`）、`ApiRoamController.java`、`RoamSessionWriteService.java`、`RoamLocationRetentionService.java`、`RoamOrphanLocationRetentionService.java`、新迁移 `migration_roam_session_status_check_20261024.sql`、`ci/migration-dependencies.json` | `RoamSessionStatusContractTest`（3 条） | 魔法数 0/1 换成常量；新增可重复执行的 `ck_roam_session_status CHECK(status IN (0,1))`。在 MySQL 8.0.45 上实测：重复执行两次正常；插入 2 时报 3819。Mapper XML 里保留字面量 0/1，与常量同义，由契约测试锁住结算 CAS 的 0→1。 |
| C:U-086 | P1 | FIXED | `utils/roam-track-simplify.js` | `roam-track-simplify-cap.test.js` | 新增 `MAX_POINTS_LIMIT=1000`，调用方传更大的值会被钳住；改正误导注释。默认 180 时行为不变。 |
| C:U-089 | P1 | PARTIAL | `tests/unit/roam-fog-legacy-unreachable.test.js`；删除放在 C00 补丁里 | `roam-fog-legacy-unreachable.test.js`（3 条） | 旧 overlay/poly 迷雾链路在生产上走不到（没有任何 `_fogMode='overlay'` 赋值；poly 的唯一入口只在 overlay 分支内被调用），本 PR 先用测试把这一点锁住。约 350 行删除和对应旧测试的清理都在 C00 补丁里，补丁另附一条 blocked 态的回归测试。剩余：wxml 里的 `#fogcv` 和 `map-polygons="{{fogPolys}}"` 绑定按「不改 wxml」的约束保留，`fogPolys` 始终是 `[]`。 |
| C:U-091 | P1 | PARTIAL | `utils/roam-location-source.js`、`utils/roam-progress.js` | `roam-progress-location-source.test.js`（5 条） | 新增可注入的定位源（bgTracker 申请 + 订阅/退订），以及 `planMovePatch` 纯函数（镜头 / 轨迹线 / marker 三条节流）和 `trackPolyline`。`_applyMovePatch` 与 `_flushTrackLine` 的接线放在 C00 补丁里。剩余：`_startReal` 还没改用 location source（要重排 consent/epoch 闭包，属于下一步）。 |
| C:U-092 | P1 | FIXED | `pages/roam/index.js`（原地替换 2 行） | `roam-heading-marker-throttle.test.js`（2 条，含负控：旧实现会判红） | 转向时不再直接调 `_syncMarkers`，改为把 `_mkTimer` 清零，由 `_applyMovePatch` 在同一 tick 同步一次。旧实现在节流窗口已过时，同一个定位点会同步两次。 |
| C:U-102 | P1 | FIXED | 新迁移 `migration_preset_route_node_geo_numeric_20261024.sql`、`ci/migration-dependencies.json` | `PresetRouteNodeGeoMigrationContractTest`（2 条） | 新增 `lng_num` / `lat_num DECIMAL(10,7)` 两个 STORED 生成列，从 varchar 坐标派生（非法或越界时为 NULL），再加 `idx_preset_node_geo(lat_num,lng_num)`。写入方不改，原文本列仍是唯一真源。在 MySQL 8.0.45 STRICT 模式下实测：重复执行、非法文本写入都不报错，包围盒查询走这个索引。目前还没有读取方用到这两列。 |
| C:U-109 | P1 | FIXED | `utils/month-day.js`、`pages/roam/index.js`（2 处，原地替换）、`subpackageRoam/session/index.js`（3 处） | `month-day.test.js`（2 条） | 新增 `monthDay` / `monthDayWeek`。各调用点原有的兜底文案因场景而异（如「日期不可用」、空串），全部保留，输出逐字不变。 |
| C:U-117 | P1 | PARTIAL | `utils/roam-route-geometry.js`、`components/cy/scene-roam-session/route-replay.js` | `roam-route-geometry.test.js`（3 条：300 条随机轨迹与冻结的旧实现逐值一致，另测宽高比） | 新增 `fitRoute`（等比投影 + 适配画框 + 切线段）和 `buildShareArch`。两处的画框、边距和取舍规则全部做成参数，输出不变。组件已经接上；页面 `_buildArchFrom` 的接线在 C00 补丁里。 |
| C:U-120 | P1 | FIXED | `utils/roam-geo-thresholds.js`、`pages/roam/index.js`（原地替换 1 行） | `roam-geo-thresholds.test.js` | `CHECKIN_NEAR_M` 改为 `SHOP_VISIT_RADIUS_M(100) + ROAM_GPS_TOLERANCE_M(30)`，取自共享表，值仍是 130。单测会读后端 Java 常量逐个对照，后端改了值而这里没跟，单测就判红。由服务端下发半径要改接口，本 PR 没做。 |
| C:U-123 | P1 | PARTIAL | `utils/roam-player-memory.js`、`utils/roam-tile-memory-sync.js` | `roam-tile-memory-cap.test.js`（5 条） | 本机迷雾格加上限 `MAX_LOCAL_TILES=20000`（约 760KB，低于微信单个 key 的 1MB 上限）：超出时保留进行中行程的格子，其余按落盘时间从新到旧保留。分页同步改为每 5 页落一次盘，最后一页必落；断网后从已落盘的游标续传。剩余：写入失败时的界面提示（新增可见提示且要改页面，见下方说明）。 |
| C:U-149 | P1 | NEEDS_OWNER | — | — | 章节结局（`ChapterFlowCompiler`）、剧情结局（`GameplayRuntimeConfig`）、主题结局（`ThemeRuntimeConfig`）这三个校验器，各自在本域内都要求「恰好一个兜底」，口径一致，并不矛盾。但运行时的选择器规则不同：剧情结局 `resolveEnding` 是按顺序首个命中，主题结局 `terminalResult` 是按 priority 取最高。合并成一个决策点，就会改变玩家最终拿到哪个结局。 |
| B:P2-034 | P2 | FIXED | 新迁移 `migration_roam_session_distance_comment_20261024.sql`、`RoamSession.java`、`ci/migration-dependencies.json` | 迁移自带 `assert=scalar-true`；MySQL 8.0.45 实测 | 列注释注明「客户端自报、不可信、封顶后落库、只用于展示」。只有列定义指纹一致时才改；`INPLACE` 方式不重建表；定义被改过时 guard 会报错。 |
| C:U-090 | P2 | PARTIAL | `utils/map-scale.js` | `map-scale.test.js`（3 条：单调且每级 2 倍、与旧阶梯逐值一致、两个方向互逆） | 用一张表同时驱动 `halfViewM` 和 `scaleForHalfViewM`。页面两个方法的接线在 C00 补丁里。 |
| C:U-093 | P2 | NEEDS_OWNER | — | — | 回放时长与段数不单调：24 段回放 23 帧约 2.76s，25 段因为 step=2 只有 13 帧约 1.44s。要修就得改回放节奏，这是可见的动效变化。 |
| C:U-103 | P2 | FIXED | 同 B:P1-059 | 同 B:P1-059 | 与 P1-059 是同一问题，同一个提交里修好。 |
| C:U-107 | P2 | FIXED | `utils/roam-poi-group.js` | `roam-poi-group.test.js`（新增交叉校验） | 新增 `shopProgress`，一次归组同时给出 total / collected / doneInSession；`stampProgress` 和 `doneShopCount` 保留为兼容包装，结果不变。 |
| C:U-110 | P2 | FIXED | `utils/roam-geo-thresholds.js`、`utils/roam-track-filter.js` | `roam-geo-thresholds.test.js` | 轨迹过滤里的 accuracy>80 和 35 m/s 改为引用共享表（值不变），其中速度门槛与后端 `MAX_MOVE_SPEED_MPS` 对照。三组阈值管的事情不同（打卡/到点半径、定位精度、玩法围栏），表头写明了各自的职责，没有合并成一个数。 |
| C:U-124 | P2 | FIXED | `pages/roam/index.js`（5 处注释，原地改）、`subpackageRoam/session/index.js`、`utils/roam-share-snapshot.js` | `roam-storage-key-comments.test.js` | 注释里的 `roam_sessions` / `roam_tiles` 改为真实 key `roam_memory_v1:<玩家ID>:sessions` / `:tiles`。 |
| C:U-126 | P2 | PARTIAL | `utils/roam-debug-probe.js` | `roam-debug-probe.test.js`（3 条） | `roamDebugSnapshot` 是只读快照，字段与原 `_dbg` 一致；`attachRoamDebugProbes` 只在 `isDevEnv()` 时挂载探针。删除页面上的探针、在 onLoad 里挂载的改动在 C00 补丁里。 |

## NEEDS_OWNER

- **C:U-149**：结局兜底要不要统一成一个选择器？如果统一，用哪条规则：剧情结局的「按顺序首个命中」，还是主题结局的「priority 最高」？
- **C:U-093**：长轨迹回放应该怎么定节奏？建议改成「帧数 = min(段数, 24)、每帧均匀分配」，这样时长随段数单调不减。是否接受这个回放节奏的变化？

另外 C:U-123 剩下的「本机迷雾写入失败时的界面提示」是新增的可见提示，需要先确认文案和展示形式；它也要改 `pages/roam/index.js`，受同一个行号基线约束。

## wxml / wxss

本 PR 和 C00 补丁都**没有任何 wxml/wxss 改动**。

## 验证

- 小程序：`node --test` 跑了 roam-* / play-ui-contract / privacy-* 等相关单测，全部通过；`npm run lint:ui`（UI-GATE-0:PASS）、`npm run lint:scope`（JS-SCOPE PASS）通过。按约束没有运行 `ci/xcx-check.sh`。
- 后端：`mvn -pl chengyinhub-admin test -Dtest=RoamSessionStatusContractTest,RoamFinishCasTest,RoamCheckinTest,RoamSessionResumeAcceptanceTest,PresetRouteNodeGeoMigrationContractTest` 通过。
- 迁移：`ci/migration-safety-check.sh` 和 `ci/migration-layout-check.sh` 通过；三个新迁移都在本地 MySQL 8.0.45 容器里从 base schema 建表后执行了两次，验证可重复执行，并验证了约束和 guard 的报错路径。
- CI 运行记录见 PR #583 的 checks（run ID 在下方，PR 期间持续更新）。

## CI 运行

| commit | workflow run | 结论 |
|---|---|---|
| `74ad356a`（B:P1-059 + C:U-086） | GitHub CI 40061491401 · Funds MySQL IT 40061491249 · Mini followup 40061491317 · Redis recovery 40061491569 | 全部成功 |
| `138c34fd` / `390458bd` / `0593e1c0` / `2fb0e21d` / `bbebfa4d` | GitHub CI 40062514456 / 40062776433 / 40063406024 / 40063686501 / 40064187953 | 「Mini program static checks」的 DS 硬编码防回潮门报红：`utils/roam-progress.js` 原样搬入了页面的轨迹线色值。已在 `251c9213` 修复，改为取 play 路线 token，取值不变。其余检查均成功；中间几次 run 被后续 push 取消 |
| `7afc2f08`（修复 + 合入 master） | GitHub CI 40064504422 · Funds MySQL IT 40064504484 · Mini followup 40064504465 · Redis recovery 40064504429 | 全部成功（check-runs：32 success、3 skipped） |
