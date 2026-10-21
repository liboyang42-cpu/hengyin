package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.Proof;
import com.chengyinhub.business.mapper.PrivateHomeMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Same-evidence reuse stays on the stored proof. A database failure is not relabelled as a duplicate. */
class PrivateHomeEvidenceReuseTest extends PrivateHomeReturnProtocolTest {
    @Test void replayReturnsTheOriginalProof() {
        Proof proof = proof();
        fixture.fixedGrant = "would-resign";
        Proof again = service.verifyReturn("verify", 1, "run:1", "other-handle");
        assertEquals(proof.proofId, again.proofId);
        assertEquals("verify", again.requestId);
        assertEquals("READY", again.status);
        assertEquals(proof.grantId, again.grantId);
        assertEquals(proof.grantFingerprint, again.grantFingerprint);
        assertEquals(proof.issuedAt, again.issuedAt);
        assertEquals(1, count("player_private_home_proof"));
        assertEquals("PrivateHome[redacted]", again.toString());
    }

    @Test void sameEvidenceUnderANewRequestReadsBackAndDoesNotResign() {
        Proof proof = proof();
        fixture.fixedGrant = "would-resign";
        Proof again = service.verifyReturn("second", 1, "run:1", "evidence");
        assertEquals(proof.proofId, again.proofId);
        assertEquals("verify", again.requestId);
        assertEquals(proof.grantId, again.grantId);
        assertEquals(proof.grantFingerprint, again.grantFingerprint);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM player_private_home_proof WHERE request_id='second'", Long.class));
        openRules();
        Proof http = service.verifyReturnHome("third", 1, "run:1", "evidence", here());
        assertEquals(proof.proofId, http.proofId);
        assertEquals("verify", http.requestId);
        assertEquals(0, count("player_private_home_location_evidence"));
        assertEquals(1, count("player_private_home_proof"));
    }

    @Test void sameKeyWithADifferentPayloadConflicts() {
        Proof proof = proof();
        ServiceException scope = assertThrows(ServiceException.class, () -> service.verifyReturn("other-scope", 1, "run:2", "evidence"));
        assertEquals("REQUEST_CONFLICT", scope.getMessage());
        assertNull(scope.getCause());
        assertFalse(scope.getMessage().contains("evidence"));
        assertFalse(scope.getMessage().contains("7"));
        fixture.sharedResource = fixture.resourcePolicy(fixture.current(), "run:1");
        fixture.sharedResource.ruleVersion = "changed-rule";
        assertEquals("REQUEST_CONFLICT", code(() -> service.verifyReturn("other-rule", 1, "run:1", "evidence")));
        assertEquals("SAVED", service.setMyHome("change", 1, "Moved", point()).decision);
        assertEquals("REQUEST_CONFLICT", code(() -> service.verifyReturn("moved", 2, "run:1", "evidence")));
        assertEquals("REVOKED", jdbc.queryForObject("SELECT status FROM player_private_home_proof", String.class));
        assertEquals(proof.grantFingerprint, jdbc.queryForObject("SELECT grant_fingerprint FROM player_private_home_proof", String.class));
        assertNull(jdbc.queryForObject("SELECT grant_id FROM player_private_home_proof", String.class));
        assertEquals(1, count("player_private_home_proof"));
    }

    @Test void concurrentSamePayloadReadsBackOneProof() throws Exception {
        home();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Proof> first = pool.submit(() -> { go.await(); return service.verifyReturn("a", 1, "run:1", "evidence"); });
            Future<Proof> second = pool.submit(() -> { go.await(); return service.verifyReturn("b", 1, "run:1", "evidence"); });
            go.countDown();
            Proof left = first.get(10, TimeUnit.SECONDS);
            Proof right = second.get(10, TimeUnit.SECONDS);
            assertEquals(left.proofId, right.proofId);
            assertEquals(left.requestId, right.requestId);
            assertEquals(1, count("player_private_home_proof"));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentDifferentPayloadConflictsWithoutASecondProof() throws Exception {
        home();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> run1 = pool.submit(() -> { go.await(); return call("c", "run:1"); });
            Future<Object> run2 = pool.submit(() -> { go.await(); return call("d", "run:2"); });
            go.countDown();
            Object one = run1.get(10, TimeUnit.SECONDS);
            Object two = run2.get(10, TimeUnit.SECONDS);
            assertTrue((one instanceof Proof) ^ (two instanceof Proof));
            assertTrue("REQUEST_CONFLICT".equals(one) || "REQUEST_CONFLICT".equals(two));
            assertFalse("REQUEST_CONFLICT".equals(one) && one.toString().contains("evidence"));
            assertEquals(1, count("player_private_home_proof"));
        } finally { pool.shutdownNow(); }
    }

    @Test void storageFailureIsNotADuplicateAndUnprovenKeysStayFailures() {
        home();
        bomb(new IllegalStateException("storage-down"));
        IllegalStateException storage = assertThrows(IllegalStateException.class, () -> service.verifyReturn("storage", 1, "run:1", "evidence"));
        assertEquals("storage-down", storage.getMessage());
        assertEquals(0, count("player_private_home_proof"));
        bomb(new DataIntegrityViolationException("CHECK constraint player_private_home_proof status"));
        DataIntegrityViolationException check = assertThrows(DataIntegrityViolationException.class, () -> service.verifyReturn("check", 1, "run:1", "evidence"));
        assertFalse(check.getMessage().contains("REQUEST_CONFLICT"));
        bomb(new DuplicateKeyException("Duplicate entry for key 'uk_private_home_proof_grant'"));
        assertThrows(DuplicateKeyException.class, () -> service.verifyReturn("unproven-grant", 1, "run:1", "evidence"));
        bomb(new DuplicateKeyException("uk_private_home_proof_grant uk_private_home_proof_request"));
        assertThrows(DuplicateKeyException.class, () -> service.verifyReturn("ambiguous", 1, "run:1", "evidence"));
        bomb(new DuplicateKeyException("Duplicate entry for key 'uk_private_home_proof_request'"));
        assertThrows(DuplicateKeyException.class, () -> service.verifyReturn("unproven-request", 1, "run:1", "evidence"));
        assertEquals(0, count("player_private_home_proof"));
    }

    @Test void anotherOwnerDoesNotReadThisProof() {
        Proof proof = proof();
        fixture.owner.set(8L);
        assertEquals("HOME_VERSION_INVALID", code(() -> service.verifyReturn("foreign", 1, "run:1", "evidence")));
        fixture.owner.set(7L);
        assertEquals(proof.proofId, service.readMyProof("verify").proofId);
        assertEquals(1, count("player_private_home_proof"));
    }

    private Object call(String requestId, String scope) {
        try { return service.verifyReturn(requestId, 1, scope, "evidence"); }
        catch (ServiceException error) { return error.getMessage(); }
    }
    private void bomb(RuntimeException error) {
        PrivateHomeMapper actual = mapper;
        PrivateHomeMapper wrapped = (PrivateHomeMapper) Proxy.newProxyInstance(PrivateHomeMapper.class.getClassLoader(), new Class<?>[]{PrivateHomeMapper.class}, (proxy, method, args) -> {
            if ("insertProof".equals(method.getName())) throw error;
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        ReflectionTestUtils.setField(target, "mapper", wrapped);
    }
}
