# P3 Platform Systems Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all P3 systems for Questify as verifiable platform capabilities: IP world, growth, creator tools, merchant CRM, settlement/withdrawal, analytics funnel, recommendation, and unified audit.

**Architecture:** Keep the existing modular monolith. Add focused domain objects, mappers, services, controllers, SQL migrations, Vue admin pages, and mini program pages without changing the homepage or the topic/activity publish flow.

**Tech Stack:** Java 8, Spring Boot 2.5, MyBatis XML, RuoYi Vue 2 + Element UI, WeChat Mini Program, Maven, MySQL migration SQL.

## Global Constraints

- Do not modify `chengyinhub-xcx/pages/index/*`.
- Do not change topic/activity publish flow, fields, validation rules, or business rules.
- P3 completion requires database migration, domain service, API, page/entry, RBAC permission, and automated verification for every subsystem.
- Financial operations must be transactional, idempotent, auditable, and amount-conserving.
- Mini program APIs must derive the current user from authentication state, not client-supplied member IDs.
- Admin write actions require dedicated RBAC permissions.
- Event collection failure must not block user-facing primary flows.

---

## File Structure

### Phase A: Data And Governance Foundation

**SQL**
- Create: `chengyinhub-admin/sql/migration_p3_platform_foundation.sql`

**Backend Domains**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/BizEventLog.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/BizFunnelDaily.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/AuditTask.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/AuditRecord.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/MemberInterestProfile.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/RecommendationScoreDaily.java`

**Backend DTO/VO**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/AnalyticsEventDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/AnalyticsBatchDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/AuditReviewDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/FunnelSummaryVO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/RecommendationItemVO.java`

**Backend Mappers**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/BizEventLogMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/BizFunnelDailyMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/AuditTaskMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/AuditRecordMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/MemberInterestProfileMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/RecommendationScoreDailyMapper.java`
- Create XML files with matching names under `chengyinhub-system/src/main/resources/mapper/business/`.

**Backend Services**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IAnalyticsEventService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IAuditTaskService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IRecommendationService.java`
- Create: corresponding `impl/*ServiceImpl.java` files.

**Backend Controllers**
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAnalyticsController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRecommendationController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/AnalyticsController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/AuditWorkbenchController.java`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/RecommendationController.java`

**Mini Program**
- Create: `chengyinhub-xcx/utils/analytics.js`
- Modify: selected non-homepage pages to call analytics helper after successful existing actions.
- Create: `chengyinhub-xcx/subpackageP3/pages/growth/index/*` in Phase B, not Phase A.

**Admin UI**
- Create: `chengyinhub-ui/src/api/business/analytics.js`
- Create: `chengyinhub-ui/src/api/business/auditWorkbench.js`
- Create: `chengyinhub-ui/src/api/business/recommendation.js`
- Create: `chengyinhub-ui/src/views/business/analytics/index.vue`
- Create: `chengyinhub-ui/src/views/business/audit/index.vue`
- Create: `chengyinhub-ui/src/views/business/recommendation/index.vue`

**Tests**
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAnalyticsControllerTest.java`
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/AuditWorkbenchControllerTest.java`
- Create: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiRecommendationControllerTest.java`

### Phase B: Player, World, Creator

- Growth: `member_growth`, `growth_rule`, `growth_mission`, `growth_mission_progress`, `growth_badge`, `member_badge`.
- World: `ip_world`, `ip_city_chapter`, `ip_character`, `ip_quest`.
- Creator: `creator_profile`, `creator_metric_daily`, `creator_income_detail`.
- Mini program pages under `subpackageP3/pages/growth`, `subpackageP3/pages/world`, and `subpackageP3/pages/creator`.
- Admin pages under `src/views/business/growth`, `src/views/business/world`, and `src/views/business/creator`.

### Phase C: Merchant CRM And Finance

- CRM: `merchant_customer_profile`, `merchant_customer_tag`.
- Settlement: `settlement_batch`, `settlement_detail`.
- Withdrawal: upgrade existing withdrawal service into a guarded state machine.
- Mini program pages under `subpackageP3/pages/merchant-crm` and `subpackageP3/pages/settlement`.
- Admin pages under `src/views/business/crm` and `src/views/business/settlement`.

### Phase D: Integration And Completion Audit

- Menu/RBAC migration for every admin module.
- End-to-end tests for role isolation, amount conservation, audit transitions, and mini program entry visibility.
- Route docs and Obsidian sync.
- P3 completion audit against `docs/superpowers/specs/2026-06-18-p3-platform-systems-design.md`.

---

## Task 1: Phase A Database Migration

**Files:**
- Create: `chengyinhub-admin/sql/migration_p3_platform_foundation.sql`

**Interfaces:**
- Produces tables consumed by Phase A Java domains and mappers.

- [ ] **Step 1: Create the migration file**

Create `chengyinhub-admin/sql/migration_p3_platform_foundation.sql` with tables:

```sql
CREATE TABLE IF NOT EXISTS biz_event_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  event_name VARCHAR(80) NOT NULL COMMENT '事件名称',
  member_id BIGINT NULL COMMENT '会员ID',
  anonymous_id VARCHAR(80) NULL COMMENT '匿名ID',
  session_id VARCHAR(80) NULL COMMENT '会话ID',
  source VARCHAR(80) NULL COMMENT '来源',
  page_path VARCHAR(255) NULL COMMENT '页面路径',
  biz_type VARCHAR(40) NULL COMMENT '业务类型',
  biz_id BIGINT NULL COMMENT '业务ID',
  city_code VARCHAR(40) NULL COMMENT '城市编码',
  properties_json TEXT NULL COMMENT '扩展属性JSON',
  idempotency_key VARCHAR(120) NULL COMMENT '幂等键',
  occurred_at DATETIME NOT NULL COMMENT '发生时间',
  create_time DATETIME NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_biz_event_name_time (event_name, occurred_at),
  KEY idx_biz_event_member_time (member_id, occurred_at),
  KEY idx_biz_event_biz_time (biz_type, biz_id, occurred_at),
  UNIQUE KEY uk_biz_event_idem (event_name, member_id, idempotency_key)
) COMMENT='P3行为事件日志';
```

- [ ] **Step 2: Add the remaining Phase A tables**

Add `biz_funnel_daily`, `audit_task`, `audit_record`, `member_interest_profile`, and `recommendation_score_daily` with indexes matching the design spec.

- [ ] **Step 3: Add RBAC menu seed SQL**

Insert menu permissions for:

```text
business:analytics:list
business:audit:list
business:audit:review
business:recommendation:list
business:recommendation:config
```

- [ ] **Step 4: Verify SQL syntax**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
p = Path('/Users/arlan/Downloads/chengyin/chengyinhub-admin/sql/migration_p3_platform_foundation.sql')
s = p.read_text()
assert 'CREATE TABLE IF NOT EXISTS biz_event_log' in s
assert 'CREATE TABLE IF NOT EXISTS audit_task' in s
assert 'business:audit:review' in s
print('migration_p3_platform_foundation.sql sanity ok')
PY
```

Expected: `migration_p3_platform_foundation.sql sanity ok`.

## Task 2: Analytics Event Backend

**Files:**
- Create Java domains, DTOs, mappers, XML, service, and API controller listed under Phase A.
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAnalyticsControllerTest.java`

**Interfaces:**
- Produces: `IAnalyticsEventService.recordBatch(Long memberId, AnalyticsBatchDTO request): int`
- Produces: `IAnalyticsEventService.aggregateDaily(String statDate): int`
- Consumes: `biz_event_log` and `biz_funnel_daily`.

- [ ] **Step 1: Write tests for whitelist, size limit, and idempotency**

Test cases:

```text
rejects unknown event name
rejects more than 50 events per batch
does not duplicate event with same event_name/member_id/idempotency_key
allows anonymous non-financial events
```

- [ ] **Step 2: Implement DTO validation**

Allowed event names must be the exact list in the design spec. Batch size must be `1..50`. `propertiesJson` max serialized length is `4000`.

- [ ] **Step 3: Implement mapper insert and duplicate ignore**

Use database unique key for idempotency. Duplicate insertion returns no user-facing failure.

- [ ] **Step 4: Implement `/api/analytics/events/batch`**

Controller returns:

```json
{ "code": 200, "msg": "ok", "data": { "accepted": 3 } }
```

- [ ] **Step 5: Verify**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiAnalyticsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

## Task 3: Unified Audit Foundation

**Files:**
- Create `AuditTask`, `AuditRecord`, mappers, XML, service, controller, tests.

**Interfaces:**
- Produces: `IAuditTaskService.submit(String bizType, Long bizId, Long submitterId, String sourceType, String snapshotJson): Long`
- Produces: `IAuditTaskService.review(Long taskId, Long operatorId, AuditReviewDTO request): int`

- [ ] **Step 1: Write tests for valid transitions**

Allowed transitions:

```text
PENDING -> APPROVED
PENDING -> REJECTED
PENDING -> ESCALATED
ESCALATED -> APPROVED
ESCALATED -> REJECTED
```

Rejected transitions:

```text
APPROVED -> REJECTED
REJECTED -> APPROVED
CANCELLED -> APPROVED
```

- [ ] **Step 2: Implement service state guard**

Use optimistic `version` update. If update count is `0`, return conflict error.

- [ ] **Step 3: Implement admin controller**

Endpoints:

```text
GET /business/audit/list
GET /business/audit/{id}
POST /business/audit/review
```

- [ ] **Step 4: Verify**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=AuditWorkbenchControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

## Task 4: Recommendation Foundation

**Files:**
- Create profile/score domains, mappers, service, API controller, admin controller, tests.

**Interfaces:**
- Produces: `IRecommendationService.updateProfileFromEvent(BizEventLog event): void`
- Produces: `IRecommendationService.listForMember(Long memberId, String candidateType, int limit): List<RecommendationItemVO>`

- [ ] **Step 1: Write tests for filtering**

Recommendation must not return deleted, unapproved, or unpublished content.

- [ ] **Step 2: Implement cold start fallback**

When no member profile exists, return approved recommended topics/activities by quality and recency.

- [ ] **Step 3: Implement event-driven profile update**

Map event weights:

```text
content_view = 1
content_like = 3
content_favorite = 4
signup_submit = 5
payment_success = 8
```

- [ ] **Step 4: Verify**

Run:

```bash
mvn -pl chengyinhub-admin -am -Dtest=ApiRecommendationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

## Task 5: Mini Program Analytics Helper

**Files:**
- Create: `chengyinhub-xcx/utils/analytics.js`
- Modify non-homepage pages only after successful existing actions.

**Interfaces:**
- Produces: `track(eventName, payload)`
- Produces: `flush()`
- Produces: `setSessionContext(ctx)`

- [ ] **Step 1: Create queue helper**

The helper stores failed batches in `wx` storage key `p3_analytics_queue_v1`. It silently drops unknown events.

- [ ] **Step 2: Add calls to non-homepage success points**

Instrument:

```text
search_submit
content_view
content_like
content_share
signup_submit
payment_success
topic_publish_success
activity_publish_success
template_reuse
coupon_verify
withdrawal_submit
```

- [ ] **Step 3: Verify JS syntax and homepage invariant**

Run:

```bash
node --check chengyinhub-xcx/utils/analytics.js
git diff --name-only -- chengyinhub-xcx/pages/index
```

Expected: node passes; homepage diff output is empty.

## Task 6: Phase A Admin UI

**Files:**
- Create API wrappers and Vue pages listed under Phase A.

**Interfaces:**
- Consumes backend business controllers from Tasks 2-4.

- [ ] **Step 1: Add API wrappers**

Functions:

```text
listFunnel(query)
listAuditTasks(query)
reviewAuditTask(data)
listRecommendationScores(query)
```

- [ ] **Step 2: Add Vue pages**

Pages must include filters, table empty state, loading state, and RBAC-controlled actions.

- [ ] **Step 3: Verify build**

Run:

```bash
npm --prefix chengyinhub-ui run build:stage
```

Expected: build succeeds.

## Task 7: Phase B Detailed Plan

**Files:**
- Create: `docs/superpowers/plans/2026-06-18-p3-phase-b-player-world-creator.md`

**Interfaces:**
- Consumes Phase A event and recommendation services.
- Produces growth, world, and creator services.

- [ ] **Step 1: Write detailed implementation plan for growth, world, and creator**

Plan must include SQL, Java domains, mappers, services, APIs, admin pages, mini program pages, and tests.

- [ ] **Step 2: Self-review**

Run:

```bash
rg -n "TBD|TODO|implement later|类似|同上" docs/superpowers/plans/2026-06-18-p3-phase-b-player-world-creator.md
```

Expected: no output.

## Task 8: Phase C Detailed Plan

**Files:**
- Create: `docs/superpowers/plans/2026-06-18-p3-phase-c-crm-finance.md`

**Interfaces:**
- Consumes existing order, registration, coupon, balance, and withdrawal services.
- Produces CRM aggregation, settlement batch, settlement detail, and withdrawal state machine.

- [ ] **Step 1: Write detailed implementation plan for CRM and finance**

Plan must include amount conservation tests and withdrawal transition tests.

- [ ] **Step 2: Self-review**

Run:

```bash
rg -n "TBD|TODO|implement later|类似|同上" docs/superpowers/plans/2026-06-18-p3-phase-c-crm-finance.md
```

Expected: no output.

## Task 9: Phase D Completion Audit

**Files:**
- Create: `docs/p3-completion-audit.md`
- Modify: `docs/城瘾小程序整改路线图.md`
- Modify: `/Users/arlan/Documents/城瘾/小程序/城瘾小程序整改路线图.md`

**Interfaces:**
- Consumes all implementation and verification evidence.

- [ ] **Step 1: Build requirement-by-requirement matrix**

Rows:

```text
IP 世界观专区
玩家成长体系
创作者工具
商家 CRM
财务/结算/提现闭环
埋点和增长漏斗
推荐系统
审核后台
```

Columns:

```text
SQL
Domain
Service
API
Admin UI
Mini Program
RBAC
Tests
Verification Command
Status
```

- [ ] **Step 2: Run final verification**

Run:

```bash
mvn -pl chengyinhub-admin -am -DskipTests compile
mvn -pl chengyinhub-admin -am test
npm --prefix chengyinhub-ui run build:stage
find chengyinhub-xcx -name '*.js' -not -path '*/node_modules/*' -print0 | xargs -0 -n1 node --check
python3 -m json.tool chengyinhub-xcx/app.json
git diff --name-only -- chengyinhub-xcx/pages/index
```

Expected: all commands pass; homepage diff output is empty.

---

## Self-Review

Spec coverage:

- Analytics and funnel: Tasks 1, 2, 5, 6, 9.
- Unified audit: Tasks 1, 3, 6, 9.
- Recommendation: Tasks 1, 4, 6, 9.
- Growth, world, creator: Task 7 creates the detailed executable plan, Phase B implementation follows that plan.
- CRM and finance: Task 8 creates the detailed executable plan, Phase C implementation follows that plan.
- Completion audit: Task 9.

Known sequencing:

- Do not start Phase B before Phase A tests pass.
- Do not start Phase C withdrawal changes before settlement table migration exists.
- Do not mark P3 complete before Task 9 proves all eight systems.

