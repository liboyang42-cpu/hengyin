# 实施文档二（优化 + 新功能）现状盘点 · LANE D2S · 2026-10-22

- 基线：`master @ 063a6c3c`（Merge PR #408）
- 性质：**只读盘点，无任何产品代码改动**。机器可读版见同目录 `survey.json`。
- 路径约定：
  - 迁移脚本实际位于 `chengyinhub-admin/sql/migration_*.sql`。`sql/` 目录下只有 `base_schema_20260818.sql`、`base_seed_20260818.sql`、`quartz.sql`、`ry_20250522.sql` 四个文件。下文 `base:` 指 `sql/base_schema_20260818.sql`。
  - Java 路径省略了公共前缀：`system/…` 指 `chengyinhub-system/src/main/java/com/chengyinhub/business/…`，`admin/…` 指 `chengyinhub-admin/src/main/java/com/chengyinhub/web/…`，测试统一在 `chengyinhub-admin/src/test/java/com/chengyinhub/…` 下。
- 冲突处理：凡是计划与现状不一致的地方，都同时给出「A 跟随现有代码」和「B 跟随计划」两个选项，**不替决策方猜字段**。实施文档二原文不在本仓库内，下文提到的「计划」均指任务卡中的描述。

---

## 1. O-15 主题模式角色分离（ProductRole.slot / revenue_share）

**已有**

- 实体 `system/domain/ProductRole.java`：
  - `slot` 是 String（:19），`revenueShare` 是 BigDecimal（:28）。
  - :27 的注释写明「分成逻辑暂不实现，留给 P0-46/P0-50」。
- slot 取值由字符串常量类定义，**不是 Java enum**：`chengyinhub-common/.../constant/ProductSlot.java:24-33`，四个值为 `CONTENT / ORGANIZER / SUPPLY / DISTRIBUTION`。
  - `isSingleton`（:42）和 `isValid`（:46）**只在测试里被调用**（`ProductRoleServiceTest.java:372-391`）。
- DB 约束（base:4047-4065；来源迁移 `migration_product_role_20260716.sql:124-125`）：
  - `ck_role_slot CHECK (slot in ('CONTENT','ORGANIZER','SUPPLY','DISTRIBUTION'))`（:4064）
  - `ck_role_entity_type`（:4063）、`ck_role_entity_id`（:4062）、`ck_role_active_content_owner`（:4061）
  - `singleton_key` 是生成列，配合 `uk_product_role_singleton`（:4056-4058），只约束 CONTENT 和 ORGANIZER。
  - DISTRIBUTION 的单例约束另由 `migration_20260817_tandianri_r7_distribution_singleton.sql` 提供（`distribution_singleton_key` 列）。
- 写路径 `system/service/impl/ProductRoleServiceImpl.java`：
  - slot 全部硬编码：CONTENT 在 :61/:250，ORGANIZER 在 :135，DISTRIBUTION 在 :173。调用方无法传入自由 slot。
  - ORGANIZER 只允许 `ProductType.FREE_EXPLORE`（:116，错误码 `SALE_ORGANIZER_ONLY_FREE_EXPLORE`）。
  - `isPlatformOwned`（:267-272）fail-closed，被以下 REVSHARE/定价闸门调用：`TopicAdminCommandServiceImpl:49,109`、`MerchantOfferServiceImpl:771`、`OmsTicketServiceImpl:784`、`CmsRegistrationServiceImpl:1433`。
- Mapper 层：`ProductRoleMapper.xml:24-33` 插入时带 `cms_topic` 守卫（`del_flag != 2`；ORGANIZER 要求 `lifecycle in (0,1,2,7)`）。

**缺失**

- **`revenue_share` 完全没有校验**：
  - DB 列为 `decimal(10,4) DEFAULT NULL`（base:4053），没有 CHECK。
  - 没有任何服务写路径会设置它。
  - 唯一的批量写入是 `migration_20260817_tandianri_r7_distribution_backfill.sql:65-67`，写入的值是 `NULL`。
  - 合计为 1、取值在 0..1、按 slot 必填之类的规则都不存在。
- slot 没有 Java 枚举，也没有服务层白名单，只靠 DB CHECK 和硬编码常量。
- `declareContentSlot` 遇到非法 entityType 只记日志后静默返回（:44-57），插入失败被吞掉（:65-72）。

**冲突与选项**

- **C1-1｜分成校验落在哪一层。**
  - A 跟随现有：保持 NULL，分成继续由 `merchant_chapter_offer.per_head_fee` 和结算线承载，ProductRole 只负责声明角色。
  - B 跟随计划：新增 `ck_role_revenue_share`（0..1）+ 服务层按 topic 汇总 ≤ 1 的校验。需要先裁决：分母是全部 slot 还是只算 SUPPLY/DISTRIBUTION；NULL 是否表示「不参与分成」。
- **C1-2｜两份 DDL 不一致。** base 快照中的 `product_role` 缺少 `declared_by` 和 DISTRIBUTION 守卫列，但 mapper 读写了 `declared_by`。
  - 只用 base 建库会失配，任何新迁移都必须以「base + 0815/0817 迁移」为前提。
- **C1-3｜测试 schema 精度不同。** `TandianriBatch3FundsMybatisIntegrationTest.java:110` 用 `DECIMAL(8,4)`，生产是 `decimal(10,4)`。

**测试挂点**（均在 `business/` 下）

- `service/ProductRoleServiceTest`、`ProductRoleOrganizerActorTest`、`ProductRoleContentSlotBatchTest`、`ProductRoleWiringTest`、`ProductRoleContentOwnerSchemaGuardTest`
- `mapper/ProductRoleMapperSqlTest`：:88 只断言列存在，没有任何分成数值相关的测试。

---

## 2. O-01/O-02 `/arrive` 防作弊

**已有**

- `POST /api/play/arrive`：`admin/controller/api/ApiPlayProgressController.java:1957-2079`
  - `GEOFENCE_METERS = 50`（:2372），`GEOFENCE_ACCURACY_METERS = 80`（:2374）。
  - **不带精度一律放行**（:2059-2065）：

    ```java
    if (StringUtils.isBlank(accuracy)) {
        logger.info("arrive 未上报定位精度 ...");   // 仅留痕，放行
    } else { ... if (acc > GEOFENCE_ACCURACY_METERS) return error("定位精度不足..."); }
    if (dist > GEOFENCE_METERS) return error("还没到这一站附近");
    ```

  - 不带 accuracy 的重载方法传入 `null`（:2081-2088）。
  - 节点没有坐标时整段校验被跳过（:2045）；`vm=5` 的 GPS 校验节点例外，会直接拒绝（:2044）。
  - 端上 `chengyinhub-xcx/pages/play/utils/play-accuracy.js` 的 `accuracyOf` 在缺失、NaN 或负值时返回 `undefined`，字段就不会上报（注释写着「精度缺失(空)一律放行」）。调用点在 `pages/play/index.js:5207`。
- `POST /api/club/lead/arrive`：`ApiClubLeadController.java:148`
  - 没有 accuracy 参数。
  - 走 `ClubGatherArrivalPolicy.validate`，阈值 500 m（`system/service/support/ClubGatherArrivalPolicy.java:27-28`）。
- **速度/位移校验**：
  - `/api/play/arrive` **没有**速度或位移校验，只有 `@RateLimiter(time=10,count=120,MEMBER)`（:1956）。
  - 全仓唯一的瞬移检测在 `ApiRoamController.java:180-189`（`/api/roam/poi/discover`）：`MAX_MOVE_SPEED_MPS = 35.0`（:149），`MIN_MOVE_DIST_M = 300`（:151），参照点取自 `selectLastPoiFoundLoc`。
  - `/api/roam/checkin`（:851-892）没有速度校验，POI 坐标由客户端自报（:863 只校验 200 m 内）。
- **`member_visited_poi` 的身份就是 3 位小数坐标**：
  - DDL 在 base:2713-2724 和 `migration_member_visited_poi.sql:6-17`：`geo_key varchar(24) NOT NULL COMMENT 'POI坐标3位小数'`，`UNIQUE uk_member_geo(member_id, geo_key)`，**没有 poi_id**。
  - geo_key 在 Java 里生成：`ApiRoamController.java:919-921` 用 `String.format("%.3f,%.3f", lat, lng)`，入参是**客户端上报的** poiLat/poiLng（:882）。
  - 首次访问发 `VISIT_XP = 20`（:886）。

**haversine / 距离实现：全部重复清单**

Java 后端：

| 位置 | 方法 | 单位 / 半径 / 公式 |
|---|---|---|
| `ApiPlayProgressController.java:2444` | `haversine` | m / 6371000 / atan2 |
| `ApiRoamController.java:436` | `haversineM` | m / 6371000 / asin |
| `ShopDayProofServiceImpl.java:143` | `haversineMeters` | m / 6371000 / atan2 |
| `RoamExploreDayCandidateServiceImpl.java:161` | `haversineM` | m / 6371000 / asin |
| `OfficialEventV2ServiceImpl.java:2284` | `haversineM` | m / 6371000 / atan2 |
| `ClubGatherArrivalPolicy.java:23-27` | 内联 | m / 6371000 / asin，clamp |
| `TopicRouteCandidatePolicy.java:29,~186` | `segmentDistanceMeters` | m / **6371008.8** / atan2 |
| `TopicRouteSimulator.java:67` | `distance` | m / atan2 |
| `common/utils/DistanceCalculatorUtils.java:14` | `calculateDistance(lon1,lat1,lon2,lat2)` | **km** / 6371 / 保留 2 位小数；参数**经度在前**；解析失败返回 **0.0** |

SQL 层：`6371000*acos(least(1,…))` 写在以下 mapper 中：`CmsTopicMapper.xml:610,643`、`RoamMapper.xml:256,477`、`MerchantLeadMapper.xml:13`、`ApiMapMapper.xml:23`、`RoamHangoutMapper.xml:45,110`、`ApiAiMapper.xml:41`、`MmsMerchantMapper.xml:112`。

小程序端（`chengyinhub-xcx/`）：

| 位置 | 说明 |
|---|---|
| `utils/geo.js:5` | `haversine`，m / asin，规范实现 |
| `pages/publish/utils/publish/publish-stats.js:9` | `haversineKm`，km |
| `pages/publish/utils/publish/topic-route-graph.js:484` | `distanceMeters` |
| `pages/play/index.js:7539` | `_reviewDistanceKm` 内联，km |
| `utils/roam-geo.js:52` | `distM`，等距矩形近似，111320 m/° |
| `utils/roam-track-simplify.js:24-28` | 同上 |
| `tests/unit/official-solo-story-seed-contract.test.js:105` | 测试内又复制了一份 |

**冲突与选项**

- **C2-1｜空精度。**
  - A 跟随现有：放行并留痕。测试 `ApiPlayGpsGeofenceValidationTest#blankAccuracyIsAllowedForLegacyClients`（:287）把这个行为锁定了。
  - B 跟随计划：空精度拒绝或降级为扫码。需要同时改端上 `play-accuracy.js`，并改写上述测试，还要评估还在使用旧版小程序的用户。
- **C2-2｜速度校验的数据源。** `/arrive` 目前没有「上一次可信定位」的存储。
  - 可选做法一：复用 `player_node_progress.checkin_time` 加节点坐标来推算速度。
  - 可选做法二：新增定位轨迹表（需要写 migration）。
  - 两种都要先裁决阈值。roam 的 35 m/s 是否沿用，需要明确。
- **C2-3｜member_visited_poi 的身份。**
  - A：保留 geo_key，但改用服务端 POI 坐标生成。
  - B：按计划改成 poi_id 维度。这样就需要可信的 POI 源，roam 的 POI 目前来自客户端。
- **C2-4｜距离工具收敛。** 地球半径有 6371000 和 6371008.8 两种取值，单位有 m 和 km 混用，`DistanceCalculatorUtils` 失败时返回 0（坏数据会被当成「就在原地」）。
  - 收敛到一个工具类属于跨模块改动，按 AGENTS.md 需先问。

**测试挂点**

- `web/controller/api/` 下：`ApiPlayGpsGeofenceValidationTest`（:287/:301/:311）、`ClubGatherArrivalControllerTest`、`ClubLeadFlowTest`、`RoamCheckinTest`（:26，geoKey 用例）、`ApiRoamCoordinateBoundaryTest`、`ApiPlayProgressCompletionTruthTest`
- `business/it/ClubGatherArrivalMySqlIT`
- 小程序 `tests/unit/` 下：`geo.test.js`、`roam-geo.test.js`、`publish-stats.test.js`、`roam-finish-distance.test.js`

---

## 3. F1 城市定向运行时（product_type=1）

**已有**

- **`product_type`**：
  - base:1336，`tinyint DEFAULT NULL`。注释写明 NULL 表示「存量未回填或混票」，读侧会回落到票的 mode 推断。
  - 常量 `ProductType.ORIENTEERING=1` / `FREE_EXPLORE=2`（common `ProductType.java:28,31`）。
  - 回落逻辑见 `ApiPlayProgressController.java:2484-2529`。
- **`topic_route_session`**：`migration_20260823_topic_route_graph.sql:25-46`
  - 列：`id, activity_id(0=自玩), topic_id, member_id, play_instance_id varchar(64) default 'default', config_version, config_snapshot_json, state_json, status tinyint('0进行中 1已到终点'), current_node_id, recommended_node_id, seed, version, completed_at, create_time, update_time`
  - 唯一键：`uk_topic_route_owner(activity_id,topic_id,member_id,play_instance_id)`
  - 伴随表 `topic_route_action`（幂等回执，:48-62）
  - 状态常量 `system/domain/TopicRouteSession.java:10-12`：`STATUS_RUNNING=0 / STATUS_COMPLETED=1 / STATUS_ENDED=2`。**DDL 注释里没有 2。**
  - 映射在 `TopicRouteRuntimeServiceImpl.statusOf`（:529）；ENDED 来自主题 END（:4330）。
- **`orient_session_clock`：不存在**（检索 `orient_session_clock|OrientSessionClock|orient_` 0 命中）。
- **端点**：不存在独立的 start / prepare / abandon / task-result。
  - 会话是懒创建的：`ensureBranchSession`（:1150）、`ensureJourneySession`（:2067）。
  - 现有推进入口，`/api/play` 下：
    - `route-state` :1026、`checkin` :1327、`answer` :1480、`sensor-result` :1901、`arrive` :1957、`photo` :2140
    - 汇聚到 `advanceProgress`（:2909-2949），再到 `TopicRouteCompletionCoordinator.advance`（`admin/service/TopicRouteCompletionCoordinator.java:42`）
  - 其他：
    - `/api/play/journey/opening/complete`（`ApiPlayJourneyActionController:161`）
    - `/api/play/theme/*`（`ApiThemeRuntimeController`）
    - `/api/play/check/{roll,reroll,settle,rest}`（`ApiPlayCheckController`）
  - 「放弃」只能通过主题 action `END` 实现：`TopicRouteRuntimeServiceImpl.java:4325` 调用 `terminal(…,"ABANDONED")`。
  - 另有一套平行运行时 `/api/play/advanced/{start,action,state,leaderboard}`（`ApiAdvancedPlayController:34,46`），使用表 `game_runtime_session`：
    - status `0进行中 1完成 2失败 3超时`
    - `score INT`（`migration_20260822_advanced_game_runtime.sql:38-39`）
- **计分**：
  - **没有任何 `*_centi` 列**，也**没有计分流水表**。
  - 积分规则在 `cms_points_setting`（`migration_play_progress_points.sql`，event_type 11/12/13）。
- **排行榜**：
  - `GET /api/play/leaderboard`（:3332）= 按 `player_node_progress.status=1` 计数，平局时 `max(checkin_time)` 早者在前（`PlayerNodeProgressMapper.xml:166-178`）。
  - 不使用 Redis ZSet。
  - 可按活动隐藏（`GameSessionRuntimeServiceImpl.java:2702`）。
- **队伍**：
  - `play_team` / `play_team_member`（`migration_20260820_play_team_core.sql:2-30`）
    - team.status：`0招募中 1已锁定 2进行中 3已结束 4已解散`
    - member.status：`0待审核 1已加入 2已退出 3已移出 4已拒绝`
  - `game_team_role_assignment`（`migration_20260823_game_session_runtime.sql:72`）
  - API 在 `ApiPlayTeamController`（`/api/team`）
- **坐标**：
  - `cms_topic_node.longitude/latitude` 是 `varchar(255)`（base:1585-1586）。
  - **全仓没有 WGS84↔GCJ02 转换**。端上统一用 `type:'gcj02'`（`pages/play/index.js:4472,4696`、`utils/location/location-manager.js:121`）。
  - 已有的脏值防护：
    - `parseGeoCoordinate`（:2459，拒绝 NaN/越界）
    - `TopicBundleReviewService.java:461-479`（中国范围包围盒，拒绝 0 和默认上海坐标）
    - `TopicRouteRuntimeServiceImpl.java:1717`
    - `CoordinateUtils.java:22-52`
    - `ChapterMerchantNodeServiceImpl.java:381`
  - **没有经纬度对调检测，也没有清洗 SQL。**
- **LocationGuard：不存在。**
  - 小程序里的 `isMock` 指演示模式（`pages/play/index.js:360`）。
  - 最接近的是 roam 专用的 `chengyinhub-xcx/utils/roam-track-filter.js:4-17`：要求 gcj02、精度 ≤ 80、单步跳变 3-120 m、速度 ≤ 35 m/s。

**冲突与选项**

- **C3-1｜会话时钟。**
  - A：把计时放进 `topic_route_session.state_json` / `game_runtime_session`。
  - B：按计划新建 `orient_session_clock` 表。需要裁决它与 `topic_route_session` 是 1:1，还是挂在 `play_instance_id` 上。
- **C3-2｜计分精度。**
  - 现有计分只有 `game_runtime_session.score INT` 和节点计数。计划里的 `_centi` 是新语义，需要新列加上流水表。
  - 排行榜要从「节点数」切换到「分数」吗？这会改变既有 `/leaderboard` 的契约，需要裁决。
- **C3-3｜两条运行时并存。**
  - 定向应该落在 `topic_route_session`（路线图）还是 `game_runtime_session`（advanced）？两者状态语义不同（ENDED vs 失败/超时）。
- **C3-4｜坐标系。**
  - 计划若要求以 WGS84 入库，就与「全链路默认 GCJ02、varchar 存储」冲突。
  - 建议先做只读脏值统计（走 chengyin-db-migrate skill 预检），再决定是否加 decimal 影子列。
- **C3-5｜放弃语义。**
  - 现有的 abandon 只存在于主题 END 路径。新增 `/abandon` 端点需要定义它与 `STATUS_ENDED=2` 的关系，同时补上 DDL 注释。

**测试挂点**

- `business/service/impl/` 下：`TopicRouteRuntimeServiceImplTest`、`TopicRouteCheckServiceImplTest`
- `business/it/` 下：`JourneyFullFlowMySqlIT`（schema 在 `src/test/resources/it-schema/journey-full-flow-it-schema.sql`）、`AdvancedGameRuntimeMySqlIT`
- `business/mapper/PlayerLeaderboardScopeMyBatisTest`
- `web/controller/api/ApiPlayGpsGeofenceValidationTest`
- 小程序：`roam-track-filter.test.js`、`map-team.test.js`

---

## 4. F2 RPG P0

**已有**

- **`theme_state_key`**：只在 `migration_theme_state_key_20260916.sql:6-18` 中定义，**base 快照里没有**。
  - 列：`id, topic_id, state_key varchar(64), key_type varchar(16), scope varchar(16), label varchar(128), initial_value INT default 0, create_time, update_time`，唯一键 `uk_theme_state_key(topic_id,state_key)`。
  - state_key 注释规定格式为 `^(clue|relation|tag|counter)\.[a-z][a-z0-9_]{0,47}$`。
  - key_type 取值 `flag/counter/relation/tag`；scope 取值 `run/party/deployment`，注释写明「本轮只实现 run」。
  - 相关代码：
    - `ThemeStateKeyMapper.xml`、`system/service/support/ThemeStateKeyService.java`
    - API `ApiTopicStateKeyController`
    - 发布闸门 `CmsTopicServiceImpl.java:3129-3221,3270,3312,3666`
- **`theme_character`：不存在。**
  - 唯一与角色相关的表是 `ip_character`（base:2474），只有名称、头像、简介，没有属性。
  - 类属性值只存在于 run 状态里的 `sys.hp` / `sys.luck`。
- **`GameplayCheckEngine`**（`system/service/support/GameplayCheckEngine.java`）：
  - d20：`die()` 只接受 1..20（:97）；生产随机源在 `GameSessionRuntimeServiceImpl.java:1885`。
  - 优势/劣势：(:46-60)，同时存在时相互抵消（:48）。
  - mods 只读取 `label / value / when` 三个字段（:64-73）。
  - **modCap**：`Math.min(modSum, rules.getModCap())`（:75），默认值 6（`JourneyRules.java:55`），校验范围 0..10（`JourneyRulesValidator.java:133`）。
  - **自然 20 / 1**（:76-79）：`kept==20` 判 `CRIT` 必定成功，`kept==1` 判 `FUMBLE` 必定失败（`PlayCheckReceipt.java:19-20`）。
  - 重掷消耗 1 点 `sys.luck`（:23）；attemptId 必须匹配 `advanced:[0-9]+:[0-9]+`，容量 4096（:21）。
  - **`check.skill` 只是展示文本**：
    - `AdvancedGameConfigValidator.java:2099` 只校验它是可选文本
    - 被 `JourneyReviewTexts.java:216` 和 `AdvancedGamePublicProjection.java:507` 使用
  - **`mods[].var` 不存在**：mods 校验（:2117-2134）要求 `when` + `value`，没有 `var`。
  - `critEffects` / `fumbleEffects` 由调用方负责应用（validator :2110-2111）。

**冲突与选项**

- **C4-1｜skill 绑定属性。**
  - A：skill 继续当标签用，修正值仍走静态 `value + when`。
  - B：按计划引入角色属性表 + `mods[].var`（从状态变量读取修正值）。B 需要裁决三件事：
    - var 的命名空间：沿用 `counter.*`，还是新增 `stat.*`？后者要扩展 state_key 正则和发布闸门。
    - 数据放在 `theme_character` 表里还是放在 run 状态里。
    - scope：现在只实现了 run。
- **C4-2｜modCap 只封顶不封底。** 负数修正没有下限，需要确认计划是否要对称的 `[-cap, cap]`。
- **C4-3｜两条掷骰入口。** 旧路径 `TopicRouteRuntimeServiceImpl.java:3216` 调 `roll(...)`，attempt 路径 :3440 调 `rollAttempt`。扩展规则时两条都要覆盖。

**测试挂点**

- `business/it/GenericGameplayHttpE2eIT.java`（1104 行）。主要方法：
  - `realHttpUsesTheProductionController…`（:509）
  - `r5HttpCheckRollRerollSubmitHasAuthoritativeActions(easy|medium|hard)`（:855）
  - `r5HttpPassInsufficientCost…`（:834，通过反射把 d20 固定为 20）
  - `r5HttpTeamCheckRerollIsCaptainOnly…`（:981）
  - 投票、恢复、相册等，共 18 个。
- **IT 未覆盖**：mods、modCap、CRIT/FUMBLE 结果、优势/劣势、crit/fumbleEffects。
- 单测 `business/service/support/GameplayCheckEngineTest` 只有 2 个用例（:10, :26）。

---

## 5. F3 自由探索（product_type=2）

**已有：完整链路 章节 → 商家报价 → 权益 → 核销 → 结算主张 → 应付**

- **`cms_topic_chapter`**（base:1470-1498）：
  - 主要列：`terms_mode`（`TRAFFIC/REVSHARE/PERK`，CHECK `ck_chapter_terms`）、`max_merchant`、`required`、`perk_min_value`、`max_per_head_fee`、`allowed_validation_methods`、`max_node_xp`、`blocks`。
  - 后续迁移追加的列：
    - `need_group`、`atmosphere_preset`
    - `circle_candidate_code / circle_role / circle_policy / circle_enabled`
    - `audio_url`、`finish_time`
  - 完成规则在 `cms_topic.complete_rule_json`（`migration_20260820_new_city_setup_plan.sql:14`）。
- **`merchant_chapter_offer`**（base:2763-2788）：
  - 列：`quota_total / quota_used`（`ck_offer_quota`）、`valid_end`、`terms_mode`、`per_head_fee`、`perk_id`（`ck_offer_terms`）、`status`。
  - 唯一键 `uk_offer_merchant_chapter(chapter_id, merchant_id)`。
  - 后续追加：`withdraw_reason / withdraw_required / replacement_of_offer_id`（20261003）、`payer_club_id`（20261004）。
  - 配额操作在 `MerchantChapterOfferMapper.xml`：`consumeQuota` :160、`releaseQuota` :170。
- **`registration_entitlement`**（base:4383-4398）：
  - status：`0待核销 1已核销 2失效`
  - invalid_reason：`REFUND/ORGANIZER_CANCEL/FULFILLMENT_GAP/MANUAL`
  - 唯一键 `uk_entitlement(registration_id, chapter_id)`
  - 由 `PerkLedgerServiceImpl.reserveEntitlements`（:216/:277）生成，只覆盖 required 章节。
- **`redemption`**（base:4303-4325）：
  - status：`ACTIVE/REVERSED`
  - 两个生成列 `active_key` / `active_idem_key`，均为 UNIQUE。效果是每张票每个章节同时只能有一条有效核销。
  - 写入路径：`PerkLedgerServiceImpl.redeemChapter`（:378），由 `CmsRegistrationServiceImpl.java:3226` 和 `/scan_qr_code_chapter`（`ApiRegistrationController.java:1320`）触发。
- **`settlement_claim`**（base:4698-4716）：
  - `fulfillment_type`：`REGISTRATION/REDEMPTION`，**不只有 REDEMPTION**。REGISTRATION 的写入见 `SettlementEntryServiceImpl.java:348-350` 和 `CoopSettlementClaimServiceImpl.java:141-144`。
  - `service_unit`：`CHAPTER_SUPPLY/MERCHANT_PARTICIPATION/ORGANIZER_LEAD`，是类别，**不是数量单位**。
  - `settlement_route`：`NONE/CHAPTER_OFFER/COOP_ORDER/REGISTRATION_SPLIT`。
  - 7 列唯一键里没有数量维度。全仓没有 `claim_type` 列。
- **`merchant_payable`**（base:2906-2926）：
  - `claim_id bigint DEFAULT NULL`，注释「存量待历史裁决;新写必填」。
  - `entry_type`：`ACCRUAL/REVERSAL`；唯一键 `uk_merchant_payable_claim_event(claim_id,entry_type,event_key)`。
  - 计提逻辑 `MerchantPayableServiceImpl.accrueOnRedemption`：
    - 先 `settlementClaimService.claim(...)`（:113），再 `setClaimId`（:160）。
    - `requireComplete` 要求所有字段非空（`SettlementClaimServiceImpl:54-60`）。
    - 金额固定为「人头费 × 1」（:171 注释）。
  - 当前 claim 在 `platformPays` 闸门（:118）**之前**创建，所以 PERK/TRAFFIC 也会占用一条 `route=NONE` 的 claim。
- **`VerificationRiskService`**（`system/service/impl/VerificationRiskService.java`）：
  - 高核销率阈值 `@Value("${merchant.verification-risk.high-rate-threshold:0.90}")`（:25）
  - 最小样本 5（:27）；1 分钟突发阈值 8（:26）
  - 判定式 `highRate = !shopDay && rate >= 0.90`（:47）。**自由探索跳过高核销率规则，保留突发检测**；NULL 视为非自由探索。
  - 命中后只写人工复核队列 `VerificationReview` 并发告警，不做任何资金动作。

**缺失**（全部经 grep 确认）

- **有效期**：只有 `merchant_chapter_offer.valid_end`，且到期不会翻转 status。权益和核销都没有 valid_from/valid_to/expire。
- **发放（grant）**：无。
- **预约（reservation）**：无。权益 status=0 兼作容量占位；仓内的 reservation 只与 AI 配额有关。
- **单位码（unit_code）**：无。
- **持有人余额**：无。容量只在报价维度（quota），权益是每张票每个章节一个 0/1/2 状态。

**冲突与选项**

- **C5-1｜多次 / 多单位核销。** `redemption.active_key` 和 `uk_entitlement` 从结构上限定了「每票每章节一次」。
  - A：保持一次性权益。
  - B：按计划改成余额模型，需要新增「权益单位 / 余额」表并调整唯一键。这是 DDL 变更，需走预检和用户确认。
- **C5-2｜settlement_claim 的唯一键没有单位或数量。** 多单位计提会撞键。
  - 要么在唯一键里加 `unit_seq` / `unit_code`，要么一个单位一个 fulfillment。
- **C5-3｜claim_id 的强制性。** DDL 可空，代码事实上必填。
  - 冲销路径会把存量 NULL 复制下去（:201）。
  - 计划若要求 NOT NULL，需要先裁决存量 NULL 行怎么处理。
- **C5-4｜「只有 REDEMPTION 才产生 claim」与现状不符**：REGISTRATION 路径已经在写 claim。
  - 选择 A，则把计划表述改为「fulfillment_type ∈ {REGISTRATION, REDEMPTION}」。
- **C5-5｜0.90 规则对自由探索的豁免。** 计划若要求对自由探索也启用（或按单位重新计算核销率），需要改 `VerificationRiskFreeExploreTest`。

**测试挂点**（均在 `business/` 下）

- `service/MerchantPayableServiceTest`、`service/SettlementClaimServiceImplTest`
- `mapper/SettlementClaimMapperSqlTest`、`mapper/MerchantPayableSqlTest`
- `service/PerkLedgerServiceTest`、`service/VerificationRiskFreeExploreTest`
- 以下只按文件名检索到，路径未逐一确认：`VerificationRiskServiceTest`、`VerificationRiskQueueMySqlIT`、`ExploreRedeem*Test`、`ExploreAllianceRedeemRealDbIT`、`FreeExplorePriceLockAcceptanceTest`、`MerchantChapterOfferWithdrawTest`

---

## 6. F5 故事引擎词汇（TopicRouteEngine）

**已有**：`system/service/support/TopicRouteEngine.java`（371 行，纯引擎，不访问 DB）

**`conditionsPass`**（:151-173）：参数是数组，元素之间为 AND；不是数组时视为通过。

| op | 含义 | 位置 |
|---|---|---|
| `ITEM_HELD` / `ITEM_ACQUIRED` | `themeRuntime.inventory` / `acquired[itemId] >= quantity` | :155 |
| `NODE_COMPLETED` | `nodeId` 已完成 | :160 |
| `HAS_TAG` | 拥有标签 `value` | :164 |
| `EQ / NE / GT / GTE / LT / LTE` | 对 `variables[var]` 做数值比较；变量缺失视为 false | `compare` :176-183 |

其他 op 一律抛出「不支持的条件操作」。

**`applyEffects`**（:188-209）：

| op | 含义 |
|---|---|
| `ADD_TAG` | 添加标签 `value`（:192） |
| `SET` | 设置变量，需非空 `var`（:200） |
| `INC` | 递增，变量缺失按 0 处理（:201） |
| `FLOOR` | 取最大值，仅限 `counter.*` 变量（:204） |

其他 op 一律抛出「不支持的 effect」。

- 状态序列化为 `TopicRouteEngine.State`（:300-370），存入 `topic_route_session.state_json`。
- 被以下运行时复用：`PlayEncounterServiceImpl`、`PlayJourneyStoryServiceImpl`、`AdvancedGameRuntimeServiceImpl`、`GameSessionRuntimeServiceImpl`。后者在 :5097 另有一份旧的对象格式 `applyEffects`。

**缺失**

- 条件组合 OR / NOT
- 效果 `REMOVE_TAG` / `DEC` / `GOTO`，以及物品增减类效果

**冲突与选项**

- **C6-1｜发布校验不认 `FLOOR`。** 引擎支持 FLOOR，但 `TopicRouteConfigValidator.java:24` 的 `EFFECTS = set("SET","INC","ADD_TAG")` 和 `AdvancedGameConfigValidator.java:82` 都没有它。
- **C6-2｜操作名词表复制了 6 份。** 新增任何 op 都必须同步修改全部：
  - `TopicRouteConfigValidator.java:24-25`
  - `ThemeRuntimeConfig.java:309`
  - `AdvancedGameConfigValidator.java:80-82`
  - `TopicRoutePublishGate.java:189-198`
  - `JourneyStoryValidator.java:73,449-474`
  - `GameSessionRuntimeServiceImpl.java:141`
  - A：逐处补齐。B：按计划抽出统一词汇表常量。B 属于跨模块改动，需先问。

**测试挂点**

- `business/service/support/TopicRouteEngineTest` 有 4 个用例，**都没有直接覆盖各个 op**。
- 间接覆盖：`TopicRouteConfigValidatorTest`、`GameplayTemplateEffectsTest`、`JourneyChoiceEffectsTest`、`GenericRouteRuntimeServiceTest`，以及 `src/test/resources/journey/seventh-pier/` 下的夹具。

---

## 7. F7 合规（发票 / 未成年）

**发票**：只有后台的「结算批次发票台账」，**没有面向用户的发票申请或开具**，也没有税控对接。

- `merchant_payable_batch.invoice_status / invoice_no / red_invoice_no`（base:2961-2963，CHECK 在 :2977）
  - 服务 `IMerchantPayableService.java:116,119`，注释 :55-56 写明无税控、红冲由人工处理。
  - 后台接口 `MerchantPayableBatchController.java:116,133`：`PUT /{id}/invoice`、`PUT /{id}/invoice-red`。
- `club_payable_batch` 有同样三列（`migration_20260816_tandianri_batch3_funds_club_compensation.sql:278`）。
  - 后台接口 `TopicEditionFinalizationController.java:157,167`。
- `club_corporate_payee_profile.invoice_type / tax_no`（tax_no 加密存储，`…_club_payee.sql:24-25`）。
- 小程序商家账本只做展示：`pages/merchant/ledger/index.js:538,756`。
- 命名陷阱：`club_membership_invoice` 是会员费支付单，**不是发票**。

**未成年 / 监护**

- 用户协议第十条写明「暂不面向未成年人…未满十八周岁请勿注册」（`chengyinhub-xcx/utils/agreement-docs.js:20`），发布闸门 `scripts/release-legal-gate.js` 会校验这段文本是否存在。
- **代码层面没有年龄闸门、监护模式或防沉迷**：
  - 检索 `防沉迷|anti.?addiction|isAdult|ageGate` 无实现。
  - 唯一的年龄判断是夜间主题圈层，`CircleExploreServiceImpl.java:352-357`，按 `ums_member.birthday` 判断，birthday 为 NULL 时拒绝。
- 实名只覆盖发布者：
  - 表 `ums_member_identity`（`migration_ums_member_identity_20260919.sql:32`）
  - `MemberIdentityService`、`/api/publisher/identity`
  - `IdCardUtils` 只校验格式，不推导年龄。
- 商家亲子供给字段：`merchant_circle_supply_profile.guardian_mode / guardian_present_throughout`（`CircleSupplyPolicy.java:99-104`）。
- 同意记录 `consent_record`（base:1657），对应接口 `/api/compliance/consents`、`/api/agreement/consent`。

**冲突与选项**

- **C7-1｜未成年策略。**
  - A：维持协议层面的「不面向未成年」，补一个注册时的 18+ 确认（写入 consent_record，新增 doc_type）。
  - B：按计划做监护模式，需要年龄来源（birthday 或实名推导）、监护关系表和时长限制。都是全新建设。
- **C7-2｜用户发票。** 全新建设：申请表、抬头、状态机，是否对接电子发票需要裁决。命名要避开 `club_membership_invoice`。

**测试挂点**

- Java：`MemberIdentityServiceTest`、`IdCardUtilsTest`、`ApiComplianceControllerTest`、`ApiAgreementControllerTest`、`ConsentGuardTest`、`CircleExploreServiceTest`、`MerchantPayableSqlTest`、`ClubPayableMapperSqlTest`
- 小程序：`agreement-page-contract.test.js:28`、`consent-client.test.js`、`merchant-ledger-display-contract.test.js`

---

## 8. O-14 推荐 / 城市 / 搜索

**已有**

- **`insertRecommendationScoreDaily`**：
  - 定义在 `RecommendationScoreDailyMapper.java:13` 和 `RecommendationScoreDailyMapper.xml:7-12`。表 DDL 在 base:4280，唯一键 `uk_reco_daily_candidate`。
  - **调用方为 0**，全仓 grep 只命中这两处定义。
  - 没有 Quartz/@Scheduled 写入方。
  - 读侧 `RecommendationServiceImpl.java:52` 在表为空时恒定回落到 `selectColdStartItems`（:60，按最新排序）。
  - 读接口：`/api/recommendation/list`、`/business/recommendation/scores`。
- **city_code**：
  - **`cms_topic` 和 `cms_activity` 都没有 city_code / city / adcode 列。**
    - cms_topic 只有 `address_name`（base:1342）。
    - cms_activity 有 `address_name / longitude / latitude / address`（base:858-861）。
  - 城市筛选是对 `address_name` 做 `LIKE '%kw%'`：
    - 契约校验 `DiscoveryFilterContract.java:91-96`
    - 话题：`CmsTopicMapper.xml:342-343,752`
    - 活动：`CmsActivityMapper.xml:79,181`
  - 有 city_code 的表：`community_post`、`roam_*`、`ip_world/ip_chapter`、`biz_funnel_daily`、`member_interest_profile`。
  - `chengyinhub-admin/sql/seed_official_content_template.sql:7` 注释掉的示例往 `cms_topic` 插入 `city_code`，**这一列并不存在**。
- **搜索文档投影**：
  - 唯一的索引表是 `community_search_document`（`migration_community_post_platform_20260909.sql:681-700`，base 快照中没有）。
  - `document_type` 的 CHECK 允许 POST/MEMBER/TOPIC/COMMUNITY/POI，**但只有 POST 被投影**：`CommunityPostMapper.xml:506-515`，`search_text = title + body`。
  - 查询方式是 `LIKE`（:138-142），没有 FULLTEXT 索引，也没有 ES。
  - 刷新方式：
    - `CommunityPostServiceImpl` 内联写入
    - outbox（:1468）
    - Quartz `communityPostMaintenanceTask` 每分钟执行
  - `cms_topic` / `cms_activity` 都不在索引里。

**冲突与选项**

- **C8-1｜城市维度。**
  - A：继续用 `address_name LIKE`。
  - B：按计划给 cms_topic/cms_activity 加 `city_code`，需要 migration、回填规则（坐标反查还是人工），并调整 mapper 和发现页契约。
- **C8-2｜推荐写入方。** 需要新建每日打分任务（Quartz）。打分口径（漏斗 `biz_funnel_daily`、兴趣 `member_interest_profile`）需要裁决。
- **C8-3｜搜索投影。**
  - A：沿用 `community_search_document`，扩展 TOPIC/ACTIVITY 类型（CHECK 已经允许 TOPIC，但不允许 ACTIVITY）。
  - B：另建独立的内容搜索表。

**测试挂点**

- Java：`ApiRecommendationControllerTest:29-35`、`P3AdminControllersTest:76-88`（只覆盖读侧）、`DiscoveryFilterContractTest`、`DraftPollFilterSqlContractTest`、`CommunityPostServiceImplTest`、`CommunityPostMapperMyBatisTest`、`CommunityPostMigrationContractTest`
- 小程序：`discover-search.test.js`、`c01-home-search-state-contract.test.js`

---

## 需先问（按 AGENTS.md「Ask first」）汇总

- 涉及线上 DDL 的项：C1-1(B)、C2-2/C2-3(B)、C3-1/C3-2/C3-4、C4-1(B)、C5-1/C5-2/C5-3、C7、C8-1(B)、C8-3(B)。
  - 全部需要走 chengyin-db-migrate skill 做只读预检，并取得用户确认。
- 跨模块收敛：C2-4（距离工具）、C6-2（词汇表）。
- 校验逻辑改变既有契约：C2-1（空精度）、C3-2（排行榜口径）、C5-5（0.90 豁免）。
