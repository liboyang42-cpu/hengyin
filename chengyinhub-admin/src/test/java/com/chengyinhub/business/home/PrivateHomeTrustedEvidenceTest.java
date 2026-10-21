package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.business.home.PrivateHomeTrustedEvidence.Query;
import com.chengyinhub.business.home.PrivateHomeTrustedEvidence.Verdict;
import com.chengyinhub.common.exception.ServiceException;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic evidence lives only in this test. It is not a Spring bean and not a connected provider. */
class PrivateHomeTrustedEvidenceTest extends PrivateHomeReturnProtocolTest {
    @Test void formatValidHandleAndSelfReportDoNotGrant() {
        home();
        PrivateHomeLocationPolicy policy = armed();
        PrivateHomeFailClosedEvidence closed = new PrivateHomeFailClosedEvidence();
        wire(policy, closed);
        Observation forged = here();
        forged.accuracyMeters = 0L;
        forged.dwellMillis = 86_400_000L;
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturnHome("forged", 1, "run:1", "evidence", forged)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("internal", 1, "run:1", "device-integrity-pass")));
        ReflectionTestUtils.setField(target, "authority", fixture);
        ReflectionTestUtils.setField(target, "trustedEvidence", closed);
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("stamp", 1, "run:1", "evidence")));
        assertEquals(0, count("player_private_home_proof"));
    }

    @Test void storedVerdictBindsEveryFieldAndRejectsASecondMint() {
        home();
        PrivateHomeLocationPolicy policy = armed();
        Store store = new Store();
        long freshUntil = NOW + 60_000L;
        store.put(new Verdict("challenge-1", 7L, 1L, "run:1", "test-only", freshUntil));
        wire(policy, store);
        Proof proof = service.verifyReturn("internal", 1, "run:1", "challenge-1");
        assertEquals(7L, proof.ownerId);
        assertEquals(1L, proof.homeVersion);
        assertEquals("run:1", proof.scope);
        assertEquals("test-only", proof.ruleVersion);
        assertEquals(NOW + 1_000L, proof.expiresAt);
        assertTrue(proof.expiresAt <= freshUntil);
        assertNotEquals("challenge-1", proof.grantId);
        assertEquals(PrivateHomeTrustedEvidence.fingerprint("challenge-1"), proof.grantFingerprint);
        assertEquals("PrivateHome[redacted]", proof.toString());
        assertEquals(proof.grantFingerprint, jdbc.queryForObject(
                "SELECT grant_fingerprint FROM player_private_home_proof WHERE request_id='internal'", String.class));
        assertFalse(proof.grantFingerprint.contains("challenge"));
        assertEquals(proof.proofId, service.verifyReturnHome("internal", 1, "run:1", "challenge-1", here()).proofId);

        Store http = new Store();
        http.put(new Verdict("challenge-http", 7L, 1L, "run:1", "test-only", freshUntil));
        wire(policy, http);
        Proof external = service.verifyReturnHome("external", 1, "run:1", "challenge-http", here());
        assertEquals("READY", external.status);
        assertEquals(PrivateHomeTrustedEvidence.fingerprint("challenge-http"), external.grantFingerprint);
        Proof reuse = service.verifyReturn("reuse", 1, "run:1", "challenge-http");
        assertEquals(external.proofId, reuse.proofId);
        assertEquals(external.requestId, reuse.requestId);
        assertEquals(2, count("player_private_home_proof"));
    }

    @Test void mismatchStaleAndLieDoNotMint() {
        home();
        PrivateHomeLocationPolicy policy = armed();
        long fresh = NOW + 60_000L;
        wire(policy, store(new Verdict("other-id", 7, 1, "run:1", "test-only", fresh)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("a", 1, "run:1", "evidence")));
        wire(policy, store(new Verdict("evidence", 8, 1, "run:1", "test-only", fresh)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("b", 1, "run:1", "evidence")));
        wire(policy, store(new Verdict("evidence", 7, 2, "run:1", "test-only", fresh)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("c", 1, "run:1", "evidence")));
        wire(policy, store(new Verdict("evidence", 7, 1, "other-scope", "test-only", fresh)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("d", 1, "run:1", "evidence")));
        wire(policy, store(new Verdict("evidence", 7, 1, "run:1", "other-rule", fresh)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("e", 1, "run:1", "evidence")));
        wire(policy, store(new Verdict("evidence", 7, 1, "run:1", "test-only", NOW)));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("f", 1, "run:1", "evidence")));
        ReflectionTestUtils.setField(target, "authority", fixture);
        ReflectionTestUtils.setField(target, "trustedEvidence", (PrivateHomeTrustedEvidence) query ->
                new Verdict(query.evidenceId, query.ownerId, query.homeVersion, "other-scope", query.ruleVersion, fresh));
        assertEquals("ARRIVAL_NOT_VERIFIED", code(() -> service.verifyReturn("g", 1, "run:1", "evidence")));
        assertEquals(0, count("player_private_home_proof"));
    }

    @Test void expiredReadbackProjectsWithoutWriting() {
        home();
        PrivateHomeLocationPolicy policy = armed();
        Store store = new Store();
        store.put(new Verdict("challenge-1", 7L, 1L, "run:1", "test-only", NOW + 60_000L));
        wire(policy, store);
        service.verifyReturn("verify", 1, "run:1", "challenge-1");
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(Instant.ofEpochMilli(NOW + 1000), ZoneOffset.UTC));
        Proof read = service.readMyProof("verify");
        assertEquals("EXPIRED", read.status);
        assertEquals("EXPIRED", "READY".equals(read.status) ? "VERIFIED" : read.status);
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM player_private_home_proof", String.class));
        assertNotNull(jdbc.queryForObject("SELECT grant_id FROM player_private_home_proof", String.class));
    }

    @Test void enabledAuthorityBeanStaysFailClosed() {
        new ApplicationContextRunner().withUserConfiguration(PrivateHomeAuthorityConfiguration.class)
                .withBean(PrivateHomeLocationPolicy.class, PrivateHomeLocationPolicy::new)
                .withBean(DataSource.class, () -> new DriverManagerDataSource(
                        "jdbc:h2:mem:evidence" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""))
                .withPropertyValues("app.private-home.authority-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    PrivateHomeTrustedEvidence evidence = context.getBean(PrivateHomeTrustedEvidence.class);
                    assertInstanceOf(PrivateHomeFailClosedEvidence.class, evidence);
                    assertNull(evidence.resolve(new Query(7, 1, "run:1", "evidence", "test-only", NOW)));
                    PrivateHomeLocationPolicy policy = context.getBean(PrivateHomeLocationPolicy.class);
                    policy.setEnabled(true); policy.setVersion("test-only"); policy.setResourceScope("run:1");
                    policy.setResourceType("STAMINA"); policy.setResourceCap(10L); policy.setRadiusMeters(150L);
                    policy.setDwellMillis(1000L); policy.setFreshnessMillis(60_000L); policy.setAccuracyMaxMeters(50L);
                    policy.setVerifyCooldownMillis(0L); policy.setChangeCooldownMillis(0L); policy.setRestoreCooldownMillis(0L);
                    policy.setProofTtlMillis(1000L); policy.setLocationRetentionTtlMillis(60_000L); policy.setActivationDelayMillis(0L);
                    policy.setArrivalGrantMode(PrivateHomeConfiguredAuthority.GRANT);
                    policy.setPlacementRuleVersion(PrivateHomeConfiguredAuthority.PLACEMENT);
                    Home home = new Home(); home.version = 1L; home.status = "ACTIVE";
                    ResourcePolicy resource = new ResourcePolicy();
                    resource.scope = "run:1"; resource.scopeRevision = "test-only"; resource.ruleVersion = "test-only";
                    assertNull(context.getBean(PrivateHomeAuthority.class).verifyArrival(new Actor(7, 1), home, resource, "evidence"));
                });
    }

    private PrivateHomeLocationPolicy armed() {
        PrivateHomeLocationPolicy policy = openRules();
        policy.setArrivalGrantMode(PrivateHomeConfiguredAuthority.GRANT);
        policy.setPlacementRuleVersion(PrivateHomeConfiguredAuthority.PLACEMENT);
        return policy;
    }
    private void wire(PrivateHomeLocationPolicy policy, PrivateHomeTrustedEvidence evidence) {
        ReflectionTestUtils.setField(target, "authority", new PrivateHomeConfiguredAuthority(policy, evidence));
        ReflectionTestUtils.setField(target, "trustedEvidence", evidence);
    }
    private static Store store(Verdict verdict) { Store store = new Store(); store.put(verdict); return store; }

    /** Returns the stored record or null. Never copies the query onto a verdict. */
    static final class Store implements PrivateHomeTrustedEvidence {
        private final Map<String, Verdict> records = new HashMap<>();
        void put(Verdict verdict) { records.put(verdict.evidenceId, verdict); }
        @Override public Verdict resolve(Query query) {
            if (query == null || query.evidenceId == null) return null;
            Verdict stored = records.get(query.evidenceId);
            if (stored == null || stored.ownerId != query.ownerId || stored.homeVersion != query.homeVersion
                    || stored.resourceScope == null || !stored.resourceScope.equals(query.resourceScope)
                    || stored.ruleVersion == null || !stored.ruleVersion.equals(query.ruleVersion)
                    || stored.freshUntil <= query.now) return null;
            return stored;
        }
    }
}
