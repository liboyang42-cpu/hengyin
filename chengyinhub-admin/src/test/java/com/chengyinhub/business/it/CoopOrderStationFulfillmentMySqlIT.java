package com.chengyinhub.business.it;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.CoopOrderStationScope;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native canonical SQL/claim/reader/original ledger. The audit/CAS is an explicit source fixture,
 * not a test of A's Spring caller; provider facts remain the existing typed SQL fixtures. */
class CoopOrderStationFulfillmentMySqlIT {
    final Wp04CoopOrderScopeMySqlIT flow = new Wp04CoopOrderScopeMySqlIT();
    CoopOrderStationFulfillmentMapper receipts;
    BusinessAuditEventMapper audits;
    String original701, original702;
    static final String MIGRATION = "migration_20261025_coop_order_station_fulfillment.sql";

    @BeforeEach void confirmedSource() throws Exception {
        flow.canonicalScopeAndCash();
        // flow installs the full canonical station dependency graph exactly once.
        receipts = flow.sql.getMapper(CoopOrderStationFulfillmentMapper.class);
        audits = flow.sql.getMapper(BusinessAuditEventMapper.class);
        flow.storage.db.update("INSERT INTO cms_registration_merchant(id,member_id,topic_id,mode,audit_status,del_flag) VALUES(31,88,11,2,1,0),(32,88,11,2,1,0)");
        flow.storage.db.update("UPDATE cms_registration SET verification_status=0,verifier_id=NULL,verification_time=NULL");
        for (long id : List.of(701L, 702L)) {
            ReflectionTestUtils.invokeMethod(flow, "order", id, 1, "SALES_PERCENT", 88L);
            long station = id == 701 ? 31 : 32;
            long invite = id + 200;
            flow.storage.db.update("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,topic_id,status,deposit_status,supply_station_id,del_flag) "
                    + "VALUES(?,0,'talent',7,'merchant',88,11,1,0,?,0)", invite, station);
            flow.storage.db.update("UPDATE coop_order SET invite_id=? WHERE id=?", invite, id);
            bind(id, station);
        }
        original701 = terms(701); original702 = terms(702);
    }
    @AfterEach void onlyOwnedCleanup() { flow.onlyOwnedCleanup(); }

    @Test void twoConfirmedStationsFreezeIndependentOriginalSalesAndReplayNeverPaysTwice() {
        long audit1 = verify(1, 31, true, false), audit2 = verify(2, 32, true, false);
        assertEquals(2, count("coop_order_station_fulfillment"));
        settle();
        assertMoney("5", "SELECT amount FROM coop_settlement WHERE coop_order_id=701");
        assertMoney("5", "SELECT amount FROM coop_settlement WHERE coop_order_id=702");
        assertEquals("1", flow.storage.db.queryForObject("SELECT verified_reg_ids FROM coop_settlement WHERE coop_order_id=701", String.class));
        assertEquals("2", flow.storage.db.queryForObject("SELECT verified_reg_ids FROM coop_settlement WHERE coop_order_id=702", String.class));
        assertEquals(2, count("settlement_claim")); assertEquals(2, count("coop_settlement_detail"));
        assertEquals(0, flow.storage.db.queryForObject("SELECT COUNT(*) FROM coop_settlement_detail d JOIN coop_settlement h ON h.id=d.settlement_id "
                + "WHERE (h.coop_order_id=701 AND d.registration_id<>1) OR (h.coop_order_id=702 AND d.registration_id<>2)", Integer.class));
        assertMoney("20", "SELECT SUM(original_amount) FROM coop_settlement_original_component");
        assertMoney("1.40", "SELECT amount FROM coop_settlement WHERE line_type='INITIATOR_GUARANTEE'");
        assertEquals(1, flow.storage.db.queryForObject("SELECT original_verified_merchant_count FROM coop_settlement_cash_close", Integer.class));
        int heads = count("coop_settlement"), components = count("coop_settlement_original_component");
        long receipt1 = record(audit1).orElseThrow(), receipt2 = record(audit2).orElseThrow();
        assertEquals(receipt1, record(audit1).orElseThrow()); assertEquals(receipt2, record(audit2).orElseThrow());
        settle(); assertEquals(heads, count("coop_settlement")); assertEquals(components, count("coop_settlement_original_component"));
        assertEquals(2, count("coop_order_station_fulfillment")); assertMoney("0", "SELECT SUM(balance) FROM ums_member");
    }

    @Test void confirmedNoneOriginalDoesNotHideLaterIndependentPaidOriginal() {
        JSONObject none = JSON.parseObject(original701); none.put("shareMode", 0); none.put("shareRate", null); none.put("compensationMode", "NONE");
        flow.storage.db.update("UPDATE coop_order SET share_mode=0,share_rate=NULL,terms_snapshot=? WHERE id=701", none.toJSONString());
        verify(1, 31, true, false); verify(2, 32, true, false); settle();
        assertEquals(1, flow.storage.db.queryForObject("SELECT COUNT(*) FROM settlement_claim WHERE source_contract_id=701 AND settlement_route='NONE'", Integer.class));
        assertEquals(1, flow.storage.db.queryForObject("SELECT COUNT(*) FROM settlement_claim WHERE source_contract_id=702 AND settlement_route='COOP_ORDER'", Integer.class));
        assertEquals(0, flow.storage.db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE coop_order_id=701", Integer.class));
        assertMoney("5", "SELECT amount FROM coop_settlement WHERE coop_order_id=702");
        assertEquals("2", flow.storage.db.queryForObject("SELECT verified_reg_ids FROM coop_settlement WHERE coop_order_id=702", String.class));
        assertMoney("20", "SELECT SUM(original_amount) FROM coop_settlement_original_component");
        assertMoney("0", "SELECT SUM(balance) FROM ums_member");
    }

    @Test void v1MissingProofNeverFallsBackToExistingMerchantScanSummary() {
        flow.storage.db.update("UPDATE cms_registration SET verification_status=1,verifier_id=88,verification_time='2026-10-01 09:30:00'");
        assertEquals(2, count("cms_registration_scan")); // Existing legacy aggregate evidence really exists.
        rejectsClose("NO_PROVEN_FULFILLMENT"); assertEquals(0, count("coop_order_station_fulfillment"));
    }

    @Test void changedFrozenPayloadCannotReuseOriginalProofOrReleaseIt() {
        long audit = verify(1, 31, true, false);
        CoopOrderStationFulfillment before = flow.storage.tx.execute(status -> receipts.selectByAuditForUpdate(audit));
        assertNotNull(before);
        // Explicit corruption negative: production E must forbid this frozen-contract rewrite.
        bind(701, 32);
        assertThrows(IllegalStateException.class, () -> record(audit));
        assertEquals(1, count("coop_order_station_fulfillment"));
        var after = flow.storage.tx.execute(status -> receipts.selectByAuditForUpdate(audit));
        assertEquals(before.getId(), after.getId()); assertEquals(before.getSourceFingerprint(), after.getSourceFingerprint());
        assertEquals(31L, after.getStationId());
    }

    @Test void failureAfterActualAuditAndReceiptRollsBackWholeLocalVerification() {
        IllegalStateException injected = assertThrows(IllegalStateException.class, () -> verify(1, 31, true, true));
        assertEquals("INJECTED_AFTER_REAL_STATION_RECEIPT", injected.getMessage());
        assertEquals(0, count("business_audit_event")); assertEquals(0, count("coop_order_station_fulfillment"));
        assertEquals(0, flow.storage.db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=1", Integer.class));
        assertEquals(2, count("cms_registration_payment_fact")); assertEquals(2, count("wx_verified_payment_fact"));
        verify(1, 31, true, false); assertEquals(1, count("coop_order_station_fulfillment"));
    }

    @Test void concurrentSameOriginalAuditReturnsOneRealReceipt() throws Exception {
        long audit = verify(1, 31, false, false); // Arrange an immutable source, not a claim about A's atomic caller.
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> action = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return record(audit).orElseThrow(); };
            Future<Long> first = pool.submit(action), second = pool.submit(action); start.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        assertEquals(1, count("coop_order_station_fulfillment")); assertEquals(1, count("business_audit_event"));
    }

    @Test void duplicateConfirmedStationFailsBeforeAuditAndVerificationCanCommit() {
        bind(702, 31); // An invalid imported contract must not bypass the new writer either.
        assertThrows(IllegalStateException.class, () -> verify(1, 31, true, false));
        assertEquals(0, count("coop_order_station_fulfillment")); assertEquals(0, count("business_audit_event"));
        assertEquals(0, flow.storage.db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=1", Integer.class));
    }

    @Test void laterReversalGenerationRetainsButDisqualifiesOldProof() {
        long original = verify(1, 31, true, false);
        // Synthetic source-order negative only; this does not enable the prohibited TOPIC reversal API.
        flow.storage.tx.executeWithoutResult(status -> {
            flow.topics.selectCmsTopicByIdForUpdate(11L); receipts.selectRegistrationForUpdate(1L);
            BusinessAuditEvent reverse = audit(1, 31, "REGISTRATION_VERIFY_REVERSE", "negative-generation", "{\"verificationStatus\":1}", "{\"verificationStatus\":0}");
            assertEquals(1, audits.insertBusinessAuditEvent(reverse));
            flow.storage.db.update("UPDATE cms_registration SET verification_status=0 WHERE id=1");
        });
        assertThrows(IllegalStateException.class, () -> record(original));
        assertEquals(1, count("coop_order_station_fulfillment")); rejectsClose("NO_PROVEN_FULFILLMENT");
    }

    @Test void rawMapperCannotBorrowAnotherOrderStationRegistrationOrAmount() {
        long audit = verify(1, 31, true, false);
        var source = flow.storage.tx.execute(status -> receipts.selectByAuditForUpdate(audit));
        assertNotNull(source);
        for (String mismatch : List.of("order", "registration", "station", "merchant", "topic", "amount")) {
            var bad = copy(source);
            if (mismatch.equals("order")) bad.setOrderId(702L);
            if (mismatch.equals("registration")) bad.setRegistrationId(2L);
            if (mismatch.equals("station")) bad.setStationId(32L);
            if (mismatch.equals("merchant")) bad.setBeneficiaryMemberId(99L);
            if (mismatch.equals("topic")) bad.setTopicId(99L);
            if (mismatch.equals("amount")) bad.setSalesBasisAmount(new BigDecimal("11"));
            Integer inserted = flow.storage.tx.execute(status -> receipts.insertConfirmed(bad));
            assertNotNull(inserted); assertEquals(0, inserted.intValue(), mismatch);
        }
        var changedPayload = copy(source); changedPayload.setSourceFingerprint("0".repeat(64));
        assertThrows(IllegalStateException.class, () -> CoopOrderStationScope.requireSame(source, changedPayload));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> flow.storage.tx.execute(status -> receipts.insertConfirmed(changedPayload)));
        assertEquals(1, count("coop_order_station_fulfillment"));
    }

    @Test void canonicalReceiptsAreImmutableAndUnsignedParentKeysMatch() {
        verify(1, 31, true, false);
        assertEquals(6, flow.storage.db.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() "
                + "AND table_name='coop_order_station_fulfillment'", Integer.class));
        assertEquals(1, flow.storage.db.queryForObject("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() "
                + "AND table_name='coop_invite' AND column_name='supply_station_id' AND referenced_table_name='cms_registration_merchant' AND referenced_column_name='id'", Integer.class));
        assertEquals(2, flow.storage.db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='coop_order_station_fulfillment' AND column_name IN ('topic_id','beneficiary_member_id') AND column_type='bigint unsigned'", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> flow.storage.db.update("UPDATE coop_order_station_fulfillment SET sales_basis_amount=0"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> flow.storage.db.update("DELETE FROM coop_order_station_fulfillment"));
        assertEquals(1, count("coop_order_station_fulfillment"));
    }

    @Test void noApplicableV1ReturnsEmptyButMalformedV1CannotBecomeLegacy() {
        removeBinding(701); removeBinding(702);
        long audit = verify(1, 31, false, false);
        assertEquals(Optional.empty(), record(audit)); assertEquals(0, count("coop_order_station_fulfillment"));
        JSONObject changed = JSON.parseObject(original701); changed.getJSONObject("supplyBillingScope").put("version", "UNKNOWN");
        flow.storage.db.update("UPDATE coop_order SET terms_snapshot=? WHERE id=701", changed.toJSONString());
        assertThrows(IllegalStateException.class, () -> record(audit)); assertEquals(0, count("coop_order_station_fulfillment"));
        assertEquals(1, count("business_audit_event"));
    }

    @Test void fixedFeeNeverExpandsPurchasedQuantityWithoutStationSeatProof() {
        flow.storage.db.update("UPDATE cms_registration SET order_num=2,ticket_price=5 WHERE id=1");
        JSONObject changed = JSON.parseObject(original701); changed.put("shareMode", 2); changed.put("shareRate", null);
        changed.put("fixedFee", 3); changed.put("compensationMode", "FIXED_PER_FULFILLMENT");
        flow.storage.db.update("UPDATE coop_order SET share_mode=2,share_rate=NULL,fixed_fee=3,terms_snapshot=? WHERE id=701", changed.toJSONString());
        verify(1, 31, true, false); rejectsClose("FIXED_FEE_QUANTITY_UNPROVEN");
        assertEquals(1, count("coop_order_station_fulfillment")); assertEquals(0, count("coop_settlement"));
    }

    @Test void originalFrozenWindowKeepsInclusiveStartAndExclusiveEndWithoutTimezoneTolerance() {
        for (String outside : List.of("2026-10-01 08:59:59", "2026-10-01 10:00:00")) {
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> verifyAt(1, 31, true, false, outside));
            assertTrue(rejected.getMessage().contains("FROZEN_WINDOW_OR_SCOPE_CONFLICT"));
            assertEquals(0, count("business_audit_event")); assertEquals(0, count("coop_order_station_fulfillment"));
            assertEquals(0, flow.storage.db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=1", Integer.class));
        }
        long audit = verifyAt(1, 31, true, false, "2026-10-01 09:00:00");
        var proof = flow.storage.tx.execute(status -> receipts.selectByAuditForUpdate(audit));
        assertNotNull(proof);
        Timestamp start = flow.storage.db.queryForObject("SELECT start_time FROM oms_ticket WHERE id=21", Timestamp.class);
        assertNotNull(start); assertNotNull(proof.getFulfilledAt());
        assertEquals(start.getTime(), proof.getFulfilledAt().getTime());
        assertEquals(1, count("coop_order_station_fulfillment"));
    }

    private long verify(long registration, long station, boolean capture, boolean fail) {
        return verifyAt(registration, station, capture, fail, "2026-10-01 09:30:00");
    }
    private long verifyAt(long registration, long station, boolean capture, boolean fail, String verifiedAt) {
        return flow.storage.tx.execute(status -> {
            flow.topics.selectCmsTopicByIdForUpdate(11L); receipts.selectRegistrationForUpdate(registration);
            assertEquals(1, flow.storage.db.update("UPDATE cms_registration SET verification_status=1,verifier_id=88,verification_time=? "
                    + "WHERE id=? AND verification_status=0 AND payment_status=2 AND registration_status=2", verifiedAt, registration));
            BusinessAuditEvent event = audit(registration, station, CoopOrderStationScope.ACTION, "station-" + registration,
                    "{\"verificationStatus\":0}", "{\"verificationStatus\":1}");
            assertEquals(1, audits.insertBusinessAuditEvent(event)); assertNotNull(event.getId());
            if (capture) assertTrue(flow.claimOwner.recordConfirmedStationFulfillment(event.getId()).isPresent());
            if (fail) throw new IllegalStateException("INJECTED_AFTER_REAL_STATION_RECEIPT");
            return event.getId();
        });
    }
    private BusinessAuditEvent audit(long registration, long station, String action, String request, String before, String after) {
        BusinessAuditEvent event = new BusinessAuditEvent(); event.setDomainType("REGISTRATION"); event.setDomainId(registration);
        event.setOwnerMemberId(88L); event.setActorType("MEMBER"); event.setActorMemberId(88L); event.setActorRoleCode("OWNER");
        event.setAction(action); event.setTargetType("CMS_REGISTRATION"); event.setTargetId(registration); event.setRelationId(station);
        event.setBeforeJson(before); event.setAfterJson(after); event.setRequestId(request);
        // Use the exact persisted source through the same GMT+8 connection as the
        // ticket/window reader. Timestamp.valueOf would interpret this in the JVM zone.
        event.setCreateTime(flow.storage.db.queryForObject("SELECT verification_time FROM cms_registration WHERE id=?", Timestamp.class, registration));
        return event;
    }
    private Optional<Long> record(long audit) { return flow.storage.tx.execute(status -> flow.claimOwner.recordConfirmedStationFulfillment(audit)); }
    private void settle() { ReflectionTestUtils.invokeMethod(flow, "settle"); }
    private void rejectsClose(String reason) { ReflectionTestUtils.invokeMethod(flow, "rejectsWithoutEconomicWrites", reason); }
    private int count(String table) { return flow.storage.db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String terms(long id) { return flow.storage.db.queryForObject("SELECT terms_snapshot FROM coop_order WHERE id=?", String.class, id); }
    private void bind(long order, long station) {
        JSONObject terms = JSON.parseObject(terms(order));
        terms.put("supplyBillingScope", Map.of("version", CoopOrderStationScope.VERSION, "stationId", station, "topicId", 11L, "merchantMemberId", 88L));
        flow.storage.db.update("UPDATE coop_order SET terms_snapshot=? WHERE id=?", terms.toJSONString(), order);
    }
    private void removeBinding(long order) {
        JSONObject terms = JSON.parseObject(terms(order)); terms.remove("supplyBillingScope");
        flow.storage.db.update("UPDATE coop_order SET terms_snapshot=? WHERE id=?", terms.toJSONString(), order);
    }
    private CoopOrderStationFulfillment copy(CoopOrderStationFulfillment source) {
        CoopOrderStationFulfillment target = new CoopOrderStationFulfillment();
        org.springframework.beans.BeanUtils.copyProperties(source, target); target.setId(null); return target;
    }
    private void assertMoney(String expected, String query) {
        assertEquals(0, new BigDecimal(expected).compareTo(flow.storage.db.queryForObject(query, BigDecimal.class)));
    }
}
