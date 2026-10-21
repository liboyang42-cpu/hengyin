package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2. No production values, accounts, or rewards are created by the protocol itself. */
class PrivateHomeReturnProtocolTest extends PrivateHomeTransactionTest {
    PrivateHomeLocationPolicy openRules() {
        PrivateHomeLocationPolicy policy = new PrivateHomeLocationPolicy();
        policy.setEnabled(true); policy.setVersion("test-only"); policy.setResourceScope("run:1");
        policy.setResourceType("STAMINA"); policy.setResourceCap(10L); policy.setRadiusMeters(150L);
        policy.setDwellMillis(1000L); policy.setFreshnessMillis(60_000L); policy.setAccuracyMaxMeters(50L);
        policy.setVerifyCooldownMillis(0L); policy.setChangeCooldownMillis(0L); policy.setRestoreCooldownMillis(0L);
        policy.setProofTtlMillis(1000L); policy.setLocationRetentionTtlMillis(60_000L); policy.setActivationDelayMillis(0L);
        ReflectionTestUtils.setField(target, "locationPolicy", policy);
        return policy;
    }
    Observation here() {
        Observation observation = new Observation();
        observation.latitude = new BigDecimal("30.123456"); observation.longitude = new BigDecimal("120.123456");
        observation.datum = "WGS84"; observation.accuracyMeters = 10L; observation.observedAtEpochMillis = NOW;
        observation.dwellMillis = 1000L; observation.permission = "GRANTED"; observation.network = "KNOWN";
        return observation;
    }
    String code(Throwing call) { return assertThrows(ServiceException.class, call::run).getMessage(); }
    @FunctionalInterface interface Throwing { void run(); }

    @Test void missingRulesStayClosedAndDoNotMint() {
        home();
        assertEquals("HOME_RULES_UNAVAILABLE", code(() -> service.verifyReturnHome("v", 1, "run:1", "evidence", here())));
        assertEquals(0, count("player_private_home_proof"));
        assertEquals(0, count("player_private_home_location_evidence"));
    }
    @Test void locationFailuresAreDistinctAndDoNotSucceed() {
        home(); openRules();
        Observation denied = here(); denied.permission = "DENIED";
        assertEquals("HOME_LOCATION_DENIED", code(() -> service.verifyReturnHome("d", 1, "run:1", "evidence", denied)));
        Observation offline = here(); offline.network = "UNKNOWN";
        assertEquals("HOME_NETWORK_UNKNOWN", code(() -> service.verifyReturnHome("n", 1, "run:1", "evidence", offline)));
        Observation coarse = here(); coarse.accuracyMeters = 51L;
        assertEquals("HOME_ACCURACY_INSUFFICIENT", code(() -> service.verifyReturnHome("a", 1, "run:1", "evidence", coarse)));
        Observation stale = here(); stale.observedAtEpochMillis = NOW - 60_001;
        assertEquals("HOME_LOCATION_STALE", code(() -> service.verifyReturnHome("s", 1, "run:1", "evidence", stale)));
        Observation brief = here(); brief.dwellMillis = 999L;
        assertEquals("HOME_DWELL_INSUFFICIENT", code(() -> service.verifyReturnHome("w", 1, "run:1", "evidence", brief)));
        Observation away = here(); away.latitude = new BigDecimal("31.000000");
        assertEquals("HOME_OUT_OF_RADIUS", code(() -> service.verifyReturnHome("r", 1, "run:1", "evidence", away)));
        assertEquals(0, count("player_private_home_proof"));
        assertEquals(2, balance("run:1"));
    }
    @Test void verifiedReplayConflictsOnStoredFieldsAndClearsEvidenceOnDelete() {
        home(); openRules();
        Proof proof = service.verifyReturnHome("verify", 1, "run:1", "evidence", here());
        assertEquals("READY", proof.status);
        assertEquals(1, count("player_private_home_location_evidence"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE upper(table_name)='PLAYER_PRIVATE_HOME_LOCATION_EVIDENCE' AND upper(column_name) IN ('LATITUDE','LONGITUDE','LABEL')", Long.class));
        Observation other = here(); other.permission = "DENIED";
        assertEquals(proof.proofId, service.verifyReturnHome("verify", 1, "run:1", "other-handle", other).proofId);
        assertEquals("REQUEST_CONFLICT", code(() -> service.verifyReturnHome("verify", 1, "run:2", "evidence", here())));
        assertEquals("REQUEST_CONFLICT", code(() -> service.verifyReturnHome("verify", 0, "run:1", "evidence", here())));
        assertEquals("DELETED", service.deleteMyHome("delete", 1).decision);
        assertEquals(0, count("player_private_home_location_evidence"));
        assertNull(service.getMyHome().latitude);
        assertEquals("HOME_RESULT_ABSENT", code(() -> { fixture.owner.set(8L); service.readMyProof("verify"); }));
        fixture.owner.set(7L);
        assertEquals(proof.proofId, service.readMyProof("verify").proofId);
        assertEquals("PROOF_REVOKED", service.restore("restore", proof.proofId, "run:1").decision);
        assertEquals(2, balance("run:1"));
    }
    @Test void lostResponseReadbackDoesNotCreditTwice() {
        Proof proof = proof();
        Receipt first = service.restore("restore", proof.proofId, "run:1");
        assertEquals(first.receiptId, service.readMyReceipt("restore").receiptId);
        assertEquals(first.receiptId, service.restore("restore", proof.proofId, "run:1").receiptId);
        assertEquals("REQUEST_CONFLICT", code(() -> service.restore("restore", proof.proofId, "run:2")));
        assertEquals(10, balance("run:1"));
        assertEquals("HOME_RESULT_ABSENT", code(() -> { fixture.owner.set(8L); service.readMyReceipt("restore"); }));
    }
    @Test void verifyCooldownAndDeleteRaceLeaveNoCredit() throws Exception {
        home(); PrivateHomeLocationPolicy rules = openRules(); rules.setVerifyCooldownMillis(5_000L);
        assertEquals("READY", service.verifyReturnHome("first", 1, "run:1", "evidence", here()).status);
        assertEquals("HOME_VERIFY_COOLDOWN", code(() -> service.verifyReturnHome("second", 1, "run:1", "evidence", here())));
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> verify = pool.submit(() -> { go.await(); try { return service.verifyReturnHome("race", 1, "run:1", "evidence", here()); } catch (ServiceException e) { return e.getMessage(); } });
            Future<String> delete = pool.submit(() -> { go.await(); return service.deleteMyHome("race-delete", 1).decision; });
            go.countDown();
            Object verified = verify.get(10, TimeUnit.SECONDS);
            assertEquals("DELETED", delete.get(10, TimeUnit.SECONDS));
            assertTrue(verified instanceof Proof || "HOME_VERSION_INVALID".equals(verified) || "HOME_VERIFY_COOLDOWN".equals(verified));
        } finally { pool.shutdownNow(); }
        assertEquals("DELETED", service.getMyHome().status);
        assertNull(service.getMyHome().latitude);
        assertEquals(0, count("player_private_home_location_evidence"));
        assertEquals(2, balance("run:1"));
    }
    @Test void realAdapterCasRejectsFundsAndCapDrift() {
        home();
        jdbc.update("INSERT INTO player_private_home_resource(owner_id,resource_scope,resource_type,current_value,cap_value,version) VALUES (7,'run:1','STAMINA',2,10,0)");
        PrivateHomeResourceAccountAdapter adapter = new PrivateHomeResourceAccountAdapter(ds);
        assertSame(ds, adapter.transactionDataSource());
        ReflectionTestUtils.setField(target, "resources", adapter);
        Proof proof = service.verifyReturn("adapter", 1, "run:1", "evidence");
        Receipt receipt = service.restore("credit", proof.proofId, "run:1");
        assertEquals("RESTORED", receipt.decision);
        assertEquals(10L, jdbc.queryForObject("SELECT current_value FROM player_private_home_resource WHERE owner_id=7 AND resource_scope='run:1'", Long.class));
        ResourcePolicy money = new ResourcePolicy(); money.scope = "sponsor-budget"; money.resourceType = "STAMINA"; money.cap = 10L;
        assertEquals("RESOURCE_NOT_AUTHORIZED", code(() -> adapter.lockAuthorized(new Actor(7, 1), money)));
        ResourcePolicy drift = new ResourcePolicy(); drift.scope = "run:1"; drift.resourceType = "STAMINA"; drift.cap = 99L;
        assertEquals("RESOURCE_NOT_AUTHORIZED", code(() -> adapter.lockAuthorized(new Actor(7, 1), drift)));
        assertNull(adapter.lockAuthorized(new Actor(8, 1), copy(drift, 10L, "run:1")));
    }
    private static ResourcePolicy copy(ResourcePolicy ignored, long cap, String scope) {
        ResourcePolicy policy = new ResourcePolicy(); policy.scope = scope; policy.resourceType = "STAMINA"; policy.cap = cap; return policy;
    }
    @Test void authorityBeanStaysOffAndIncompletePolicyDoesNotGrant() {
        new ApplicationContextRunner().withUserConfiguration(PrivateHomeAuthorityConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(PrivateHomeAuthority.class).isEmpty());
                    assertTrue(context.getBeansOfType(PrivateHomeTrustedEvidence.class).isEmpty());
                    assertTrue(context.getBeansOfType(PrivateHomeResources.class).isEmpty());
                });
        PrivateHomeLocationPolicy policy = new PrivateHomeLocationPolicy();
        PrivateHomeConfiguredAuthority authority = new PrivateHomeConfiguredAuthority(policy, new PrivateHomeFailClosedEvidence());
        assertEquals("HOME_RULES_UNAVAILABLE", code(() -> authority.requireNative(new Actor(7, 1))));
        assertFalse(authority.placementAllowed(new Actor(7, 1), point()));
        assertNull(authority.verifyArrival(new Actor(7, 1), new Home(), new ResourcePolicy(), "evidence"));
        openRules();
        ReflectionTestUtils.setField(authority, "rules", ReflectionTestUtils.getField(target, "locationPolicy"));
        assertNull(authority.verifyArrival(new Actor(7, 1), activeHome(), resource("run:1"), "evidence"));
        ((PrivateHomeLocationPolicy) ReflectionTestUtils.getField(target, "locationPolicy")).setArrivalGrantMode(PrivateHomeConfiguredAuthority.GRANT);
        ((PrivateHomeLocationPolicy) ReflectionTestUtils.getField(target, "locationPolicy")).setPlacementRuleVersion(PrivateHomeConfiguredAuthority.PLACEMENT);
        assertNull(authority.verifyArrival(new Actor(7, 1), activeHome(), resource("run:1"), "evidence"));
        assertTrue(authority.placementAllowed(new Actor(7, 1), point()));
    }
    private Home activeHome() { Home home = new Home(); home.version = 1L; home.status = "ACTIVE"; return home; }
    private static ResourcePolicy resource(String scope) {
        ResourcePolicy policy = new ResourcePolicy(); policy.scope = scope; policy.scopeRevision = "test-only"; policy.ruleVersion = "test-only"; policy.resourceType = "STAMINA"; return policy;
    }
}
