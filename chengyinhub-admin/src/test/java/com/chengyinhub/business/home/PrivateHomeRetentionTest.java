package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.common.exception.ServiceException;
import java.time.*;
import java.lang.reflect.*;
import com.chengyinhub.business.mapper.PrivateHomeMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic isolated data only. Inherits existing deletion/restore/replay/owner transaction cases. */
class PrivateHomeRetentionTest extends PrivateHomeTransactionTest {
    PrivateHomeRetentionPolicy approved(long grace) {
        PrivateHomeRetentionPolicy p = new PrivateHomeRetentionPolicy();
        p.setEnabled(true); p.setVersion("test-only-policy"); p.setExpiredReferenceGraceMillis(grace);
        ReflectionTestUtils.setField(target,"retention",p); return p;
    }
    void time(long millis) { ReflectionTestUtils.setField(target,"clock",Clock.fixed(Instant.ofEpochMilli(millis),ZoneOffset.UTC)); }
    String grant(String id) { return jdbc.queryForObject("SELECT grant_id FROM player_private_home_proof WHERE proof_id=?",String.class,id); }
    @Test void defaultsAndPartialOrInvalidPolicyFailBeforeAnyWrite() {
        Proof p=proof(); time(NOW+2000);
        assertEquals("HOME_RETENTION_DISABLED",assertThrows(ServiceException.class,service::cleanupMyExpiredReferences).getMessage());
        PrivateHomeRetentionPolicy policy=approved(0); policy.setExpiredReferenceGraceMillis(null);
        assertEquals("HOME_RETENTION_POLICY_REQUIRED",assertThrows(ServiceException.class,service::cleanupMyExpiredReferences).getMessage());
        policy.setExpiredReferenceGraceMillis(-1L);
        assertThrows(ServiceException.class,service::cleanupMyExpiredReferences);
        policy.setExpiredReferenceGraceMillis(0L); policy.setVersion(null);
        assertThrows(ServiceException.class,service::cleanupMyExpiredReferences);
        assertNotNull(grant(p.proofId)); assertEquals(1,count("player_private_home_proof"));
    }
    @Test void retentionBoundaryIsInclusiveAndDoesNotExpireEarly() {
        Proof p=proof(); approved(100); time(NOW+1099);
        assertEquals(0,service.cleanupMyExpiredReferences()); assertNotNull(grant(p.proofId));
        time(NOW+1100); assertEquals(1,service.cleanupMyExpiredReferences()); assertNull(grant(p.proofId));
        assertEquals(0,service.cleanupMyExpiredReferences());
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM player_private_home_proof",String.class));
        assertEquals(p.grantFingerprint,jdbc.queryForObject("SELECT grant_fingerprint FROM player_private_home_proof",String.class));
        assertEquals(1,count("player_private_home_mutation")); assertEquals(1L,service.getMyHome().version);
    }
    @Test void futureClockUnderflowFailsClosedWithoutLosingReference() {
        Proof p=proof(); approved(Long.MAX_VALUE);
        assertEquals("HOME_RETENTION_CLOCK_INVALID",assertThrows(ServiceException.class,service::cleanupMyExpiredReferences).getMessage());
        assertNotNull(grant(p.proofId)); time(-1);
        assertThrows(ServiceException.class,service::cleanupMyExpiredReferences);
    }
    @Test void onlyAuthenticatedOwnerCanCleanAndAbsentOwnerIsNotCreated() {
        Proof p=proof(); approved(0); time(NOW+1000);
        fixture.owner.set(8L); assertEquals(0,service.cleanupMyExpiredReferences());
        assertEquals(1,count("player_private_home")); assertNotNull(grant(p.proofId));
        fixture.owner.set(7L); assertEquals(1,service.cleanupMyExpiredReferences());
    }
    @Test void cleanupPreservesExpiredProofRequestAndPreventsReminting() {
        fixture.fixedGrant="opaque-test-grant"; Proof p=proof(); approved(0); time(NOW+1000);
        service.cleanupMyExpiredReferences();
        Proof replay=service.verifyReturn("verify",1,"run:1","evidence");
        assertEquals(p.proofId,replay.proofId); assertEquals("EXPIRED",replay.status); assertNull(replay.grantId);
        time(NOW); // Even clock rollback cannot reuse the retained grant fingerprint under a new request.
        assertThrows(Exception.class,()->service.verifyReturn("new-request",1,"run:1","evidence"));
        assertEquals(1,count("player_private_home_proof"));
    }
    @Test void cleanupPreservesReceiptAndDeleteTombstoneReplay() {
        Proof p=proof(); Receipt receipt=service.restore("restore",p.proofId,"run:1");
        Mutation deletion=service.deleteMyHome("delete",1); approved(0); time(NOW+5000);
        assertEquals(0,service.cleanupMyExpiredReferences());
        assertEquals(receipt.receiptId,service.restore("restore",p.proofId,"run:1").receiptId);
        assertEquals(deletion.resultVersion,service.deleteMyHome("delete",1).resultVersion);
        HomeView view=service.getMyHome(); assertEquals("DELETED",view.status); assertEquals(2L,view.version);
        assertNull(view.latitude); assertNull(view.longitude); assertNull(view.label);
        assertEquals(1,count("player_private_home_proof")); assertEquals(1,count("player_private_home_restore"));
        assertEquals(2,count("player_private_home_mutation")); assertEquals(10,balance("run:1"));
    }
    @Test void policyChangeDuringCleanupRollsBackReferenceExpiry() {
        Proof p=proof(); approved(0); time(NOW+1000);
        PrivateHomeRetentionPolicy changing=new PrivateHomeRetentionPolicy() {
            @Override public void revalidate(Snapshot snapshot) { throw new ServiceException("HOME_RETENTION_POLICY_CHANGED"); }
        };
        changing.setEnabled(true); changing.setVersion("test-only"); changing.setExpiredReferenceGraceMillis(0L);
        ReflectionTestUtils.setField(target,"retention",changing);
        assertThrows(ServiceException.class,service::cleanupMyExpiredReferences); assertNotNull(grant(p.proofId));
    }
    @Test void concurrentCleanupAndExpiredRestoreKeepSingleStableReceipt() throws Exception {
        Proof p=proof(); approved(0); time(NOW+1000);
        ExecutorService pool=Executors.newFixedThreadPool(3); CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Integer> a=pool.submit(()->{go.await();return service.cleanupMyExpiredReferences();});
            Future<Integer> b=pool.submit(()->{go.await();return service.cleanupMyExpiredReferences();});
            Future<Receipt> c=pool.submit(()->{go.await();return service.restore("expired",p.proofId,"run:1");});
            go.countDown(); assertTrue(a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS)<=1);
            Receipt receipt=c.get(10,TimeUnit.SECONDS); assertEquals("PROOF_EXPIRED",receipt.decision);
            assertEquals(receipt.receiptId,service.restore("expired",p.proofId,"run:1").receiptId);
            assertNull(grant(p.proofId)); assertEquals(1,count("player_private_home_restore")); assertEquals(2,balance("run:1"));
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentCleanupAndDeletionKeepVersionAndScrubbedPoint() throws Exception {
        Proof p=proof(); approved(0); time(NOW+1000);
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Integer> a=pool.submit(()->{go.await();return service.cleanupMyExpiredReferences();});
            Future<Mutation> b=pool.submit(()->{go.await();return service.deleteMyHome("delete",1);}); go.countDown();
            a.get(10,TimeUnit.SECONDS); assertEquals("DELETED",b.get(10,TimeUnit.SECONDS).decision);
            assertEquals(2L,service.getMyHome().version); assertNull(service.getMyHome().latitude); assertNull(grant(p.proofId));
            assertEquals(1,count("player_private_home_proof"));
        } finally { pool.shutdownNow(); }
    }
    @Test void revocationWhileWaitingForOwnerLockRollsBackCleanup() throws Exception { blockedCleanup(false); }
    @Test void policyDisableWhileWaitingForOwnerLockRollsBackCleanup() throws Exception { blockedCleanup(true); }
    void blockedCleanup(boolean disablePolicy) throws Exception {
        Proof proof=proof(); PrivateHomeRetentionPolicy policy=approved(0);time(NOW+1000);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), attempting=new CountDownLatch(1), release=new CountDownLatch(1);
        PrivateHomeMapper actual=mapper;
        PrivateHomeMapper watching=(PrivateHomeMapper)Proxy.newProxyInstance(PrivateHomeMapper.class.getClassLoader(),new Class<?>[]{PrivateHomeMapper.class},(proxy,method,args)->{
            if(method.getName().equals("lockHome")) attempting.countDown();
            try{return method.invoke(actual,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
        ReflectionTestUtils.setField(target,"mapper",watching);
        try {
            Future<?> holder=workers.submit(()->new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
                jdbc.queryForObject("SELECT owner_id FROM player_private_home WHERE owner_id=7 FOR UPDATE",Long.class);locked.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException error){throw new AssertionError(error);}
                return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<Integer> cleanup=workers.submit(service::cleanupMyExpiredReferences);
            assertTrue(attempting.await(5,TimeUnit.SECONDS));assertFalse(cleanup.isDone());
            if(disablePolicy)policy.setEnabled(false);else fixture.revoked=true;
            release.countDown();holder.get(5,TimeUnit.SECONDS);
            ExecutionException failure=assertThrows(ExecutionException.class,()->cleanup.get(5,TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ServiceException);
            assertEquals(disablePolicy?"HOME_RETENTION_DISABLED":"HOME_UNAUTHORIZED",failure.getCause().getMessage());
            assertNotNull(grant(proof.proofId));assertEquals("READY",jdbc.queryForObject("SELECT status FROM player_private_home_proof WHERE proof_id=?",String.class,proof.proofId));
            assertEquals(1,count("player_private_home_proof"));
        } finally {release.countDown();workers.shutdownNow();}
    }
}
