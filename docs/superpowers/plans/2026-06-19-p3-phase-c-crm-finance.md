# P3 Phase C CRM Finance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the backend foundation for merchant CRM, settlement batches, and guarded withdrawal review without changing any existing page.

**Architecture:** Phase C is backend-first. Database tables hold CRM aggregates and settlement ledgers; services enforce merchant isolation, idempotency, amount conservation, and withdrawal state transitions; controllers expose admin API contracts only.

**Tech Stack:** Java 8, Spring Boot, MyBatis XML mappers, RuoYi `BaseController`, JUnit 5, Mockito, MySQL migration SQL.

## Global Constraints

- Do not modify homepage files under `chengyinhub-xcx/pages/index`.
- Do not modify or create any page files in `chengyinhub-ui/src/views`, `chengyinhub-xcx/pages`, or `chengyinhub-xcx/subpackageP3`.
- Do not change topic publish flow, activity publish flow, or their business rules.
- If a new page is required, record the path and Figma requirements only; do not create the page.
- Use TDD for service and controller behavior.
- Financial operations must be transactional, auditable, idempotent, and amount-conserving.
- Admin write endpoints must have dedicated RBAC permission keys.

---

### Task 1: Merchant CRM Backend

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/MerchantCustomerProfile.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/MerchantCustomerTag.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/MerchantCrmSummaryVO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/MerchantCustomerProfileMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/MerchantCustomerTagMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IMerchantCrmService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmServiceImpl.java`
- Create: `chengyinhub-system/src/main/resources/mapper/business/MerchantCustomerProfileMapper.xml`
- Create: `chengyinhub-system/src/main/resources/mapper/business/MerchantCustomerTagMapper.xml`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MerchantCrmController.java`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/MerchantCrmServiceImplTest.java`

**Interfaces:**
- Produces: `List<MerchantCustomerProfile> selectCustomerList(MerchantCustomerProfile query)`
- Produces: `int aggregateMerchantCustomers(Long merchantId)`
- Produces: `MerchantCrmSummaryVO getSummary(Long merchantId)`

- [ ] **Step 1: Write failing tests**

```java
@Test
void listRequiresMerchantId() {
    MerchantCrmServiceImpl service = new MerchantCrmServiceImpl();
    assertThrows(IllegalArgumentException.class, () -> service.selectCustomerList(new MerchantCustomerProfile()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl chengyinhub-admin -am -Dtest=MerchantCrmServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because `MerchantCrmServiceImpl` does not exist.

- [ ] **Step 3: Implement minimal CRM code**

Create domain, mapper, service, XML, and admin controller. The service must reject list/aggregate calls without `merchantId`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl chengyinhub-admin -am -Dtest=MerchantCrmServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 2: Settlement Backend

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/SettlementBatch.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/SettlementDetail.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/SettlementGenerateDTO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/SettlementSummaryVO.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SettlementBatchMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SettlementDetailMapper.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ISettlementService.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SettlementServiceImpl.java`
- Create: `chengyinhub-system/src/main/resources/mapper/business/SettlementBatchMapper.xml`
- Create: `chengyinhub-system/src/main/resources/mapper/business/SettlementDetailMapper.xml`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/SettlementController.java`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/SettlementServiceImplTest.java`

**Interfaces:**
- Produces: `Long generateBatch(SettlementGenerateDTO request)`
- Produces: `List<SettlementBatch> selectBatchList(SettlementBatch query)`
- Produces: `List<SettlementDetail> selectDetailList(SettlementDetail query)`
- Produces: `SettlementSummaryVO getSummary(Long merchantId)`

- [ ] **Step 1: Write failing tests**

```java
@Test
void generateBatchReturnsExistingBatchForSameIdempotencyKey() {
    SettlementGenerateDTO request = new SettlementGenerateDTO();
    request.setMerchantId(9L);
    request.setBatchNo("S20260619001");
    request.setIdempotencyKey("merchant-9-20260619");
    assertEquals(88L, service.generateBatch(request));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl chengyinhub-admin -am -Dtest=SettlementServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because settlement service does not exist.

- [ ] **Step 3: Implement minimal settlement code**

Create settlement domain, mapper, XML, service, and admin controller. If an existing batch is found by idempotency key, return it without inserting another batch.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl chengyinhub-admin -am -Dtest=SettlementServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 3: Withdrawal State Machine

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/WithdrawalReviewDTO.java`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/IUmsMemberWithdrawalService.java`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/UmsMemberWithdrawalServiceImpl.java`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/UmsMemberWithdrawalMapper.java`
- Modify: `chengyinhub-system/src/main/resources/mapper/business/UmsMemberWithdrawalMapper.xml`
- Modify: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/UmsMemberWithdrawalController.java`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/WithdrawalStateMachineServiceTest.java`

**Interfaces:**
- Produces: `int reviewWithdrawal(WithdrawalReviewDTO request, Long operatorId)`
- Produces mapper method: `int updateWithdrawalStatusIfPending(Long id, Long status, Long auditBy, Date auditTime, String remark)`

- [ ] **Step 1: Write failing tests**

```java
@Test
void rejectedPendingWithdrawalRefundsOnce() {
    WithdrawalReviewDTO request = new WithdrawalReviewDTO();
    request.setId(100L);
    request.setStatus(2L);
    assertEquals(1, service.reviewWithdrawal(request, 7L));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl chengyinhub-admin -am -Dtest=WithdrawalStateMachineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because `reviewWithdrawal` does not exist.

- [ ] **Step 3: Implement minimal withdrawal state machine**

Allow only status `1` approved or `2` rejected. Use conditional update where current status is `0`. Refund only after the conditional update succeeds and only for rejection.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl chengyinhub-admin -am -Dtest=WithdrawalStateMachineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 4: Migration and Documentation

**Files:**
- Create: `chengyinhub-admin/sql/migration_p3_phase_c_crm_finance.sql`
- Modify: `docs/城瘾小程序整改路线图.md`
- Modify: `/Users/arlan/Documents/城瘾/小程序/城瘾小程序整改路线图.md`

**Interfaces:**
- Produces SQL tables: `merchant_customer_profile`, `merchant_customer_tag`, `settlement_batch`, `settlement_detail`
- Produces RBAC keys: `business:crm:list`, `business:crm:aggregate`, `business:settlement:list`, `business:settlement:generate`, `business:memberwithdrawal:review`

- [ ] **Step 1: Write migration**

Add CRM and settlement tables with indexes and unique keys for merchant isolation and idempotency.

- [ ] **Step 2: Record page requirements without creating pages**

Admin CRM Figma target: `chengyinhub-ui/src/views/business/crm/index.vue`.
Admin settlement Figma target: `chengyinhub-ui/src/views/business/settlement/index.vue`.
Mini merchant CRM Figma target: `chengyinhub-xcx/subpackageP3/pages/merchant-crm/index/index`.
Mini settlement Figma target: `chengyinhub-xcx/subpackageP3/pages/settlement/index/index`.

- [ ] **Step 3: Verify**

Run: `mvn -pl chengyinhub-admin -am -Dtest=MerchantCrmServiceImplTest,SettlementServiceImplTest,WithdrawalStateMachineServiceTest,P3PhaseCControllersTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

Run: `mvn -pl chengyinhub-admin -am -DskipTests compile`
Expected: PASS.

Run: `git diff --name-only -- chengyinhub-ui/src/views/business/crm chengyinhub-ui/src/views/business/settlement chengyinhub-xcx/subpackageP3/pages/merchant-crm chengyinhub-xcx/subpackageP3/pages/settlement`
Expected: no output.

## Self-Review

- Spec coverage: CRM backend, settlement backend, withdrawal state machine, migration, RBAC keys, documentation, and no-page constraint are covered.
- Placeholder scan: no deferred implementation markers are used.
- Type consistency: service and mapper method names are consistent across tasks.
