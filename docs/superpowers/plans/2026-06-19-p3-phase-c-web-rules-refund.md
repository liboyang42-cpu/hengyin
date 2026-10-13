# P3 Phase C Web Rules Refund Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add RuoYi-style Web management for settlement rules, settlement batches, merchant CRM, and refund review using normal local-life app finance rules.

**Architecture:** Backend stores editable settlement rules and refund applications. Refund approval uses a guarded state machine; if a refunded source was already settled, the system creates a negative settlement detail as an adjustment. Vue pages use the existing RuoYi table/search/dialog pattern.

**Tech Stack:** Java 8, Spring Boot, MyBatis XML, RuoYi Vue2 + Element UI, JUnit 5, Mockito, MySQL migration SQL.

## Global Constraints

- Web admin pages may follow existing RuoYi style without Figma.
- Do not modify homepage files under `chengyinhub-xcx/pages/index`.
- Do not modify small-program settlement pages in this pass; existing paths are `chengyinhub-xcx/pages/settlement/settlement` and `chengyinhub-xcx/pages/topic/settlement/settlement`.
- Normal app refund default: activity/topic start before cutoff can be refunded, verified registrations cannot be refunded, settled refunds create negative adjustment details.
- Financial operations must be idempotent, auditable, and guarded by permissions.

---

### Task 1: Settlement Rule Configuration

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/SettlementRule.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SettlementRuleMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ISettlementRuleService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SettlementRuleServiceImpl.java`
- Create: `chengyinhub-system/src/main/resources/mapper/business/SettlementRuleMapper.xml`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/SettlementRuleController.java`
- Create: `chengyinhub-ui/src/api/business/settlementRule.js`
- Create: `chengyinhub-ui/src/views/business/settlementrule/index.vue`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/SettlementRuleServiceImplTest.java`

**Interfaces:**
- Produces: `SettlementRule getActiveRule(Long merchantId, String bizType)`
- Produces: `int saveRule(SettlementRule rule)`

- [ ] Write failing tests for default rule and explicit merchant rule lookup.
- [ ] Implement domain, mapper, service, controller, API wrapper, and RuoYi page.
- [ ] Run targeted tests.

### Task 2: Refund Review and Adjustment

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/RefundApplication.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/RefundReviewDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/RefundApplicationMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IRefundService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RefundServiceImpl.java`
- Create: `chengyinhub-system/src/main/resources/mapper/business/RefundApplicationMapper.xml`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SettlementDetailMapper.java`
- Modify: `chengyinhub-system/src/main/resources/mapper/business/SettlementDetailMapper.xml`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/RefundController.java`
- Create: `chengyinhub-ui/src/api/business/refund.js`
- Create: `chengyinhub-ui/src/views/business/refund/index.vue`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/RefundServiceImplTest.java`

**Interfaces:**
- Produces: `int reviewRefund(RefundReviewDTO request, Long operatorId)`
- Produces: `int insertAdjustment(SettlementDetail detail)`

- [ ] Write failing tests: verified registration cannot be approved; settled refund creates negative adjustment once.
- [ ] Implement refund state machine and adjustment detail insertion.
- [ ] Run targeted tests.

### Task 3: Web Pages for CRM and Settlement

**Files:**
- Create: `chengyinhub-ui/src/api/business/crm.js`
- Create: `chengyinhub-ui/src/api/business/settlement.js`
- Create: `chengyinhub-ui/src/views/business/crm/index.vue`
- Create: `chengyinhub-ui/src/views/business/settlement/index.vue`
- Modify: `chengyinhub-ui/src/api/business/memberwithdrawal.js`
- Modify: `chengyinhub-ui/src/views/business/memberwithdrawal/index.vue`

**Interfaces:**
- Consumes existing endpoints: `/business/crm/*`, `/business/settlement/*`, `/business/memberwithdrawal/review`

- [ ] Add RuoYi table/search pages for CRM and settlement.
- [ ] Change withdrawal Web page to call guarded review endpoint, not generic update.
- [ ] Run UI build.

### Task 4: Migration and Documentation

**Files:**
- Create: `chengyinhub-admin/sql/migration_p3_phase_c_web_rules_refund.sql`
- Modify: `docs/城瘾小程序整改路线图.md`
- Modify: `/Users/arlan/Documents/城瘾/小程序/城瘾小程序整改路线图.md`

**Interfaces:**
- Produces tables: `settlement_rule`, `refund_application`
- Produces permissions: `business:settlementrule:list`, `business:settlementrule:edit`, `business:refund:list`, `business:refund:review`

- [ ] Add SQL tables, indexes, permissions, and default platform rules.
- [ ] Record implementation and remaining work.
- [ ] Verify backend tests, backend compile, UI build.

## Self-Review

- Scope is focused on Web/RuoYi and backend finance rules; small-program pages are explicitly excluded.
- No placeholders are used.
- Method names and file paths are consistent with existing Phase C code.
