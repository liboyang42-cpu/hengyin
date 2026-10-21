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

/** A new request for stored evidence is rejected. Only the original request reads it back. */
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

    @Test void sameEvidenceUnderANewRequestIsRejected() {
        Proof proof = proof();
        fixture.fixedGrant = "would-resign";
        ServiceException rejected = assertThrows(ServiceException.class, () -> service.verifyReturn("second", 1, "run:1", "evidence"));
        assertEquals("REQUEST_CONFLICT", rejected.getMessage());
        assertNull(rejected.getCause());
        assertFalse(rejected.getMessage().contains("evidence"));
        assertFalse(rejected.getMessage().contains("7"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM player_private_home_proof WHERE request_id='second'", Long.class));
        assertEquals(proof.proofId, jdbc.queryForObject("SELECT proof_id FROM player_private_home_proof", String.class));
        assertEquals(proof.grantId, jdbc.queryForObject("SELECT grant_id FROM player_private_home_proof", String.class));
        openRules();
        assertEquals("REQUEST_CONFLICT", code(() -> service.verifyReturnHome("third", 1, "run:1", "evidence", here())));
        assertEquals(0, count("player_private_home_location_evidence"));
        assertEquals(1, count("player_private_home_proof"));
        assertEquals(proof.proofId, service.verifyReturn("verify", 1, "run:1", "evidence").proofId);
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

    @Test void concurrentSamePayloadRejectsTheSecondRequest() throws Exception {
        home();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> first = pool.submit(() -> { go.await(); return call("a", "run:1"); });
            Future<Object> second = pool.submit(() -> { go.await(); return call("b", "run:1"); });
            go.countDown();
            Object left = first.get(10, TimeUnit.SECONDS);
            Object right = second.get(10, TimeUnit.SECONDS);
            assertTrue((left instanceof Proof) ^ (right instanceof Proof));
            assertTrue("REQUEST_CONFLICT".equals(left) || "REQUEST_CONFLICT".equals(right));
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

    @Test void nonDuplicateFailureWithAWinningRowStillThrows() {
        Proof proof = proof();
        bomb(new DataIntegrityViolationException("mentioned uk_private_home_proof_grant but this is not a duplicate"));
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> service.verifyReturn("other", 1, "run:1", "other-evidence"));
        assertFalse(failure instanceof DuplicateKeyException);
        assertFalse(failure.getMessage().contains("REQUEST_CONFLICT"));
        bomb(new DuplicateKeyException("Duplicate entry for key 'uk_private_home_proof_grant'"));
        assertThrows(DuplicateKeyException.class, () -> service.verifyReturn("forced-grant", 1, "run:1", "another-evidence"));
        assertEquals(1, count("player_private_home_proof"));
        assertEquals(proof.proofId, jdbc.queryForObject("SELECT proof_id FROM player_private_home_proof", String.class));
        assertEquals("verify", jdbc.queryForObject("SELECT request_id FROM player_private_home_proof", String.class));
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
