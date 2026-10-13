# P3 Phase B Player World Creator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Questify P3 player growth, IP world, and creator-center foundations without changing the homepage or topic/activity publishing rules.

**Architecture:** Extend the existing modular monolith with focused SQL tables, Java domains, MyBatis mappers, service interfaces, API controllers, admin controllers, Vue admin pages, and mini program subpackage pages. Phase B consumes Phase A analytics events as growth signals and exposes read-only player/world/creator surfaces to the mini program.

**Tech Stack:** Java 8, Spring Boot 2.5, MyBatis XML, RuoYi Vue 2 + Element UI, WeChat Mini Program, Maven, MySQL migration SQL.

## Global Constraints

- Do not modify `chengyinhub-xcx/pages/index/*`.
- Do not change topic/activity publish flow, fields, validation rules, or business rules.
- Player growth writes must be idempotent by `member_id + mission_code + biz_type + biz_id`.
- Mini program APIs must derive the current member from authentication state where member-specific data is returned.
- Admin write actions require dedicated RBAC permissions.
- Creator income records are informational in Phase B; settlement and withdrawal state changes belong to Phase C.
- Phase B must not introduce paid membership, payment, settlement, or withdrawal behavior.

---

## File Structure

**SQL**
- Create: `chengyinhub-admin/sql/migration_p3_phase_b_player_world_creator.sql`

**Backend Domains**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/MemberGrowth.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/GrowthMission.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/GrowthMissionProgress.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/GrowthBadge.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/MemberBadge.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/IpWorld.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/IpCityChapter.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/IpCharacter.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/IpQuest.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CreatorProfile.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CreatorMetricDaily.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CreatorIncomeDetail.java`

**Backend DTO/VO**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/GrowthMissionCompleteDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/IpWorldQueryDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/CreatorProfileApplyDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/MemberGrowthCenterVO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/IpWorldHomeVO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/CreatorCenterVO.java`

**Backend Mappers**
- Create mapper interfaces with matching XML under `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/` and `chengyinhub-system/src/main/resources/mapper/business/` for every Phase B domain.

**Backend Services**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IGrowthService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IIpWorldService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ICreatorCenterService.java`
- Create implementation files under `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/`.

**Backend Controllers**
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiGrowthController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiIpWorldController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCreatorCenterController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/GrowthController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/IpWorldController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CreatorCenterController.java`

**Admin UI**
- Create: `chengyinhub-ui/src/api/business/growth.js`
- Create: `chengyinhub-ui/src/api/business/ipworld.js`
- Create: `chengyinhub-ui/src/api/business/creator.js`
- Create: `chengyinhub-ui/src/views/business/growth/index.vue`
- Create: `chengyinhub-ui/src/views/business/world/index.vue`
- Create: `chengyinhub-ui/src/views/business/creator/index.vue`

**Mini Program**
- Create: `chengyinhub-xcx/subpackageP3/pages/growth/index/index.js`
- Create: `chengyinhub-xcx/subpackageP3/pages/growth/index/index.json`
- Create: `chengyinhub-xcx/subpackageP3/pages/growth/index/index.wxml`
- Create: `chengyinhub-xcx/subpackageP3/pages/growth/index/index.wxss`
- Create: `chengyinhub-xcx/subpackageP3/pages/world/index/index.js`
- Create: `chengyinhub-xcx/subpackageP3/pages/world/index/index.json`
- Create: `chengyinhub-xcx/subpackageP3/pages/world/index/index.wxml`
- Create: `chengyinhub-xcx/subpackageP3/pages/world/index/index.wxss`
- Create: `chengyinhub-xcx/subpackageP3/pages/creator/index/index.js`
- Create: `chengyinhub-xcx/subpackageP3/pages/creator/index/index.json`
- Create: `chengyinhub-xcx/subpackageP3/pages/creator/index/index.wxml`
- Create: `chengyinhub-xcx/subpackageP3/pages/creator/index/index.wxss`

**Tests**
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiGrowthControllerTest.java`
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiIpWorldControllerTest.java`
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiCreatorCenterControllerTest.java`
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/P3PhaseBAdminControllersTest.java`

---

## Task 1: Phase B Database Migration

**Files:**
- Create: `chengyinhub-admin/sql/migration_p3_phase_b_player_world_creator.sql`

**Interfaces:**
- Produces all tables consumed by Phase B domains and mappers.

- [ ] **Step 1: Write migration SQL**

Create a migration file containing these tables and keys:

```sql
CREATE TABLE IF NOT EXISTS member_growth (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  level_no INT NOT NULL DEFAULT 1 COMMENT '等级',
  exp_value INT NOT NULL DEFAULT 0 COMMENT '经验值',
  energy_value INT NOT NULL DEFAULT 0 COMMENT '能量值',
  city_score INT NOT NULL DEFAULT 0 COMMENT '城市探索分',
  last_active_time DATETIME NULL COMMENT '最近活跃时间',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_growth_member (member_id)
) COMMENT='P3玩家成长账户';

CREATE TABLE IF NOT EXISTS growth_mission (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  mission_code VARCHAR(80) NOT NULL COMMENT '任务编码',
  mission_name VARCHAR(120) NOT NULL COMMENT '任务名称',
  trigger_event VARCHAR(80) NOT NULL COMMENT '触发事件',
  exp_reward INT NOT NULL DEFAULT 0 COMMENT '经验奖励',
  energy_reward INT NOT NULL DEFAULT 0 COMMENT '能量奖励',
  daily_limit INT NOT NULL DEFAULT 1 COMMENT '每日上限',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_growth_mission_code (mission_code),
  KEY idx_growth_mission_event (trigger_event, status)
) COMMENT='P3成长任务';

CREATE TABLE IF NOT EXISTS growth_mission_progress (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  mission_code VARCHAR(80) NOT NULL COMMENT '任务编码',
  biz_type VARCHAR(40) NULL COMMENT '业务类型',
  biz_id BIGINT NULL COMMENT '业务ID',
  progress_date DATE NOT NULL COMMENT '进度日期',
  complete_count INT NOT NULL DEFAULT 1 COMMENT '完成次数',
  reward_exp INT NOT NULL DEFAULT 0 COMMENT '已发经验',
  reward_energy INT NOT NULL DEFAULT 0 COMMENT '已发能量',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_growth_progress_idem (member_id, mission_code, biz_type, biz_id, progress_date),
  KEY idx_growth_progress_member_date (member_id, progress_date)
) COMMENT='P3成长任务进度';

CREATE TABLE IF NOT EXISTS growth_badge (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  badge_code VARCHAR(80) NOT NULL COMMENT '徽章编码',
  badge_name VARCHAR(120) NOT NULL COMMENT '徽章名称',
  badge_desc VARCHAR(255) NULL COMMENT '徽章说明',
  icon_url VARCHAR(255) NULL COMMENT '图标',
  unlock_rule VARCHAR(255) NULL COMMENT '解锁规则',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NULL COMMENT '创建时间',
  update_time DATETIME NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_growth_badge_code (badge_code)
) COMMENT='P3成长徽章';

CREATE TABLE IF NOT EXISTS member_badge (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  badge_code VARCHAR(80) NOT NULL COMMENT '徽章编码',
  unlock_time DATETIME NOT NULL COMMENT '解锁时间',
  source_type VARCHAR(40) NULL COMMENT '来源类型',
  source_id BIGINT NULL COMMENT '来源ID',
  create_time DATETIME NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_badge (member_id, badge_code),
  KEY idx_member_badge_member (member_id, unlock_time)
) COMMENT='P3会员徽章';
```

- [ ] **Step 2: Add world and creator tables**

Append `ip_world`, `ip_city_chapter`, `ip_character`, `ip_quest`, `creator_profile`, `creator_metric_daily`, and `creator_income_detail` with indexes on publish status, member ID, world ID, and stat date. Use `DECIMAL(10,2)` for informational income amounts.

- [ ] **Step 3: Add RBAC seed SQL**

Insert menu permission codes:

```text
business:growth:list
business:growth:edit
business:world:list
business:world:edit
business:creator:list
business:creator:review
```

- [ ] **Step 4: Verify migration content**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
p = Path('/Users/arlan/Downloads/chengyin/chengyinhub-admin/sql/migration_p3_phase_b_player_world_creator.sql')
s = p.read_text()
required = [
  'CREATE TABLE IF NOT EXISTS member_growth',
  'CREATE TABLE IF NOT EXISTS ip_world',
  'CREATE TABLE IF NOT EXISTS creator_profile',
  'business:creator:review'
]
missing = [x for x in required if x not in s]
assert not missing, missing
print('migration_p3_phase_b_player_world_creator.sql sanity ok')
PY
```

Expected: `migration_p3_phase_b_player_world_creator.sql sanity ok`.

## Task 2: Growth Backend

**Files:**
- Create all growth domains, DTO/VO, mapper interfaces, mapper XML, service, API controller, admin controller.
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiGrowthControllerTest.java`

**Interfaces:**
- Produces: `IGrowthService.getCenter(Long memberId): MemberGrowthCenterVO`
- Produces: `IGrowthService.completeMission(Long memberId, GrowthMissionCompleteDTO request): int`
- Produces: `IGrowthService.applyEvent(Long memberId, String eventName, String bizType, Long bizId): int`

- [ ] **Step 1: Write failing tests**

Test cases:

```text
creates growth account when member has no growth row
completes mission only once for same member mission biz and date
applies active mission rewards to member growth
rejects mission completion without member id
```

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiGrowthControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `ApiGrowthController` does not exist.

- [ ] **Step 2: Implement domains and mappers**

Implement Java fields matching the SQL columns. Mapper methods required:

```text
MemberGrowth selectByMemberId(Long memberId)
int insertMemberGrowth(MemberGrowth row)
int updateMemberGrowthReward(MemberGrowth row)
GrowthMission selectActiveByCode(String missionCode)
List<GrowthMission> selectActiveByEvent(String triggerEvent)
int insertMissionProgress(GrowthMissionProgress row)
List<GrowthMissionProgress> selectMemberProgress(Long memberId)
```

- [ ] **Step 3: Implement growth service**

Behavior:

```text
getCenter creates a default account if missing, then returns growth, active missions, progress, and badges.
completeMission inserts one progress row using the unique key. Duplicate key returns 0 accepted rewards.
applyEvent finds active missions by trigger event and calls completeMission for each mission.
```

- [ ] **Step 4: Implement controllers**

Mini program:

```text
GET /api/growth/center
POST /api/growth/mission/complete
```

Admin:

```text
GET /business/growth/list
GET /business/growth/mission/list
POST /business/growth/mission
```

- [ ] **Step 5: Verify**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiGrowthControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

## Task 3: IP World Backend

**Files:**
- Create all world domains, DTO/VO, mapper interfaces, mapper XML, service, API controller, admin controller.
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiIpWorldControllerTest.java`

**Interfaces:**
- Produces: `IIpWorldService.getHome(IpWorldQueryDTO query): IpWorldHomeVO`
- Produces: `IIpWorldService.listPublishedWorlds(String cityCode): List<IpWorld>`
- Produces: `IIpWorldService.listPublishedQuests(Long worldId): List<IpQuest>`

- [ ] **Step 1: Write failing tests**

Test cases:

```text
world home returns only published worlds chapters characters and quests
world home can filter by city code
world home keeps empty arrays when no content exists
admin controller exposes list endpoint with world permission
```

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiIpWorldControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `ApiIpWorldController` does not exist.

- [ ] **Step 2: Implement world mappers**

Mapper methods required:

```text
List<IpWorld> selectPublishedWorlds(String cityCode)
List<IpCityChapter> selectPublishedChapters(Long worldId)
List<IpCharacter> selectPublishedCharacters(Long worldId)
List<IpQuest> selectPublishedQuests(Long worldId)
List<IpWorld> selectAdminWorldList(IpWorld query)
```

- [ ] **Step 3: Implement world service**

Behavior:

```text
getHome returns the first published world if worldId is empty.
All child lists must filter status = '0' and deleted = '0' where the table has deleted.
Sort worlds and children by sort_no ascending, then id descending.
```

- [ ] **Step 4: Implement controllers**

Mini program:

```text
GET /api/world/home
GET /api/world/quests
```

Admin:

```text
GET /business/world/list
GET /business/world/{id}
POST /business/world
PUT /business/world
```

- [ ] **Step 5: Verify**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiIpWorldControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

## Task 4: Creator Backend

**Files:**
- Create all creator domains, DTO/VO, mapper interfaces, mapper XML, service, API controller, admin controller.
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiCreatorCenterControllerTest.java`

**Interfaces:**
- Produces: `ICreatorCenterService.getCenter(Long memberId): CreatorCenterVO`
- Produces: `ICreatorCenterService.apply(Long memberId, CreatorProfileApplyDTO request): int`
- Produces: `ICreatorCenterService.aggregateDaily(String statDate): int`

- [ ] **Step 1: Write failing tests**

Test cases:

```text
get center returns not_applied state when no creator profile exists
apply creates pending creator profile for current member
apply does not allow one member to create duplicate profile
center returns metric and income summaries without changing settlement state
```

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiCreatorCenterControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `ApiCreatorCenterController` does not exist.

- [ ] **Step 2: Implement creator mappers**

Mapper methods required:

```text
CreatorProfile selectByMemberId(Long memberId)
int insertCreatorProfile(CreatorProfile row)
int updateCreatorProfileReview(CreatorProfile row)
CreatorMetricDaily selectLatestMetric(Long creatorId)
List<CreatorIncomeDetail> selectRecentIncome(Long creatorId)
```

- [ ] **Step 3: Implement creator service**

Behavior:

```text
getCenter returns applyStatus not_applied, pending, approved, or rejected.
apply inserts a pending profile and never accepts memberId from client payload.
aggregateDaily reads content counters from existing topic/activity/square tables and writes creator_metric_daily snapshots.
Income detail is read-only in Phase B.
```

- [ ] **Step 4: Implement controllers**

Mini program:

```text
GET /api/creator/center
POST /api/creator/apply
```

Admin:

```text
GET /business/creator/list
GET /business/creator/{id}
POST /business/creator/review
```

- [ ] **Step 5: Verify**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiCreatorCenterControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

## Task 5: Phase B Admin UI

**Files:**
- Create API wrappers and Vue pages listed in File Structure.
- Test: `npm --prefix chengyinhub-ui run build:stage`

**Interfaces:**
- Consumes Phase B business controllers.

- [ ] **Step 1: Add API wrappers**

Functions:

```text
listGrowthMembers(query)
listGrowthMissions(query)
saveGrowthMission(data)
listWorlds(query)
saveWorld(data)
listCreators(query)
reviewCreator(data)
```

- [ ] **Step 2: Add Vue pages**

Each page must include:

```text
loading state
empty state
table list
search form
permission-gated write action
```

- [ ] **Step 3: Verify build**

Run:

```bash
npm --prefix chengyinhub-ui run build:stage
```

Expected: build succeeds.

## Task 6: Mini Program Phase B Pages

**Files:**
- Create mini program pages listed in File Structure.
- Modify: `chengyinhub-xcx/app.json` only if `subpackageP3` is not already declared.

**Interfaces:**
- Consumes Phase B mini program API controllers.

- [ ] **Step 1: Add growth page**

Page behavior:

```text
onLoad requests /api/growth/center
renders level, exp, energy, missions, and badges
shows empty state when missions and badges are empty
does not block page render if API fails
```

- [ ] **Step 2: Add world page**

Page behavior:

```text
onLoad requests /api/world/home
renders worlds, chapters, characters, and quests
shows empty state when no published world exists
does not navigate into homepage or publish pages
```

- [ ] **Step 3: Add creator page**

Page behavior:

```text
onLoad requests /api/creator/center
renders apply status, metrics, and recent income
submits apply form to /api/creator/apply
does not create settlement or withdrawal actions
```

- [ ] **Step 4: Verify mini program syntax and homepage invariant**

Run:

```bash
node --check chengyinhub-xcx/subpackageP3/pages/growth/index/index.js
node --check chengyinhub-xcx/subpackageP3/pages/world/index/index.js
node --check chengyinhub-xcx/subpackageP3/pages/creator/index/index.js
python3 -m json.tool chengyinhub-xcx/app.json
git diff --name-only -- chengyinhub-xcx/pages/index
```

Expected: node and json checks pass. The homepage diff command may show pre-existing user changes, but Phase B must not add new homepage edits.

## Task 7: Phase B Integration Verification And Docs

**Files:**
- Modify: `docs/城瘾小程序整改路线图.md`
- Modify: `/Users/arlan/Documents/城瘾/小程序/城瘾小程序整改路线图.md`

**Interfaces:**
- Consumes verification evidence from Tasks 1-6.

- [ ] **Step 1: Run backend verification**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiGrowthControllerTest,ApiIpWorldControllerTest,ApiCreatorCenterControllerTest,P3PhaseBAdminControllersTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all Phase B tests pass.

- [ ] **Step 2: Run frontend and mini program verification**

Run:

```bash
npm --prefix chengyinhub-ui run build:stage
find chengyinhub-xcx/subpackageP3 -name '*.js' -print0 | xargs -0 -n1 node --check
```

Expected: admin build succeeds and all subpackage JS syntax checks pass.

- [ ] **Step 3: Update route docs**

Add a Phase B record with:

```text
P3 Phase B: 玩家成长/IP世界观/创作者中心
已完成文件
验证命令
未触碰首页
未改变发布流程规则
下一阶段：P3 Phase C 商家CRM/结算/提现状态机
```

## Self-Review

Spec coverage:

- 玩家成长体系: Tasks 1, 2, 5, 6, 7.
- IP 世界观专区: Tasks 1, 3, 5, 6, 7.
- 创作者工具: Tasks 1, 4, 5, 6, 7.
- RBAC: Tasks 1, 2, 3, 4, 5.
- Tests and verification: Tasks 2, 3, 4, 5, 6, 7.

Risk controls:

- Phase B avoids homepage files.
- Phase B does not modify publish flow validations or rules.
- Phase B creator income is read-only and does not touch settlement or withdrawal state.
- Growth mission completion is idempotent by database unique key.
