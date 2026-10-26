package com.chengyinhub.business.service.ai;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production Mapper freshness across NEVER synchronization / independent commits, plus real RR.
 * Reuses only frozen fixture setup. Original 10/16 and key recovery 4/5 remain untouched. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class PhotoCheckIntentFreshReadMySqlIT {
    private static final String HASH="a".repeat(64), KEY="original-key";
    private PhotoCheckIntentMySqlIT fixture;
    private PhotoCheckIntentService service;
    private TransactionTemplate transaction;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private AiGatewayService gateway;
    private PhotoCheckVisionService vision;
    private record Stored(String state,String nonce,String reservation,String updatedAt) { }

    @BeforeAll void connect() throws Exception {
        fixture=new PhotoCheckIntentMySqlIT();fixture.connect();
        service=(PhotoCheckIntentService)ReflectionTestUtils.getField(fixture,"service");
        transaction=(TransactionTemplate)ReflectionTestUtils.getField(fixture,"transaction");
        dataSource=(DriverManagerDataSource)ReflectionTestUtils.getField(fixture,"dataSource");
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
        gateway=(AiGatewayService)ReflectionTestUtils.getField(fixture,"gateway");
        vision=(PhotoCheckVisionService)ReflectionTestUtils.getField(fixture,"vision");
        jdbc.execute("CREATE TABLE photo_fresh_read_session_probe (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO photo_fresh_read_session_probe VALUES (8)");
    }
    @BeforeEach void cleanScratch() { fixture.cleanOwnedScratchAndStub(); }
    @AfterAll void close() throws Exception { if(fixture!=null) fixture.close(); }
    private PhotoCheckIntentView prepare() {
        return transaction.execute(status->service.prepare(PhotoCheckIntentServiceTest.command(KEY,"submitted-url",HASH)));
    }
    private TransactionTemplate withoutBusinessTransaction() {
        TransactionTemplate empty=new TransactionTemplate(transaction.getTransactionManager());
        empty.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);return empty;
    }
    private Stored independent(Long id) {
        try(Connection connection=dataSource.getConnection();PreparedStatement query=connection.prepareStatement(
                "SELECT state,nonce,reservation,updated_at FROM photo_check_intent WHERE id=?")) {
            query.setLong(1,id);
            try(ResultSet rows=query.executeQuery()) {
                assertTrue(rows.next());Stored result=new Stored(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4));
                assertFalse(rows.next());return result;
            }
        } catch(SQLException failure) {throw new IllegalStateException(failure);}
    }
    private void noOwnedRowLockAtProviderEntry(Long id) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        try(Connection connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(PreparedStatement query=connection.prepareStatement("SELECT id FROM photo_check_intent WHERE id=? FOR UPDATE NOWAIT")) {
                query.setLong(1,id);try(ResultSet row=query.executeQuery()) {assertTrue(row.next());}
            } finally {connection.rollback();}
        } catch(SQLException failure) {throw new IllegalStateException("provider must not retain an owned-row lock",failure);}
    }
    private static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(10,TimeUnit.SECONDS));}
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }
    private static <T> T result(Future<T> future) {
        try {return future.get(10,TimeUnit.SECONDS);}
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        catch(ExecutionException | TimeoutException failure) {throw new IllegalStateException(failure);}
    }
    @SuppressWarnings("unchecked") private void reviewWithSnapshot(Long id) {
        when(vision.reviewPrepared(any(),any(),any(),any(),any())).thenAnswer(call->{
            noOwnedRowLockAtProviderEntry(id);assertEquals("IN_FLIGHT",independent(id).state());
            Consumer<PhotoCheckVisionService.InputSnapshot> snapshot=call.getArgument(4);
            snapshot.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64),"configured-model","prompt-v1","encoder-v1"));
            return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.concluded(true,93,"original-result"),"RECEIVED","RESULT_RECEIVED");
        });
    }

    @Test void independentlyCommittedReadyRowMustNotReturnCachedPreparedAfterResume() {
        PhotoCheckIntentView prepared=prepare();Stored original=independent(prepared.intentId());reviewWithSnapshot(prepared.intentId());
        withoutBusinessTransaction().execute(status->{
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(PhotoCheckIntentState.PREPARED,service.read(7L,prepared.intentId(),HASH).state()); // prime same SqlSession
            PhotoCheckIntentView api=service.executeOnce(7L,prepared.intentId(),HASH);
            Stored committed=independent(prepared.intentId());
            assertEquals("RESULT_READY",committed.state(),"prove the independent DB commit before asserting API state");
            assertEquals(original.nonce(),committed.nonce());assertEquals(original.reservation(),committed.reservation());
            assertEquals(PhotoCheckIntentState.RESULT_READY,api.state(),"resumed SqlSession must not return cached PREPARED");
            assertEquals("original-result",api.result().getReason());assertTrue(api.result().isPass());
            assertEquals(PhotoCheckIntentState.RESULT_READY,service.read(7L,prepared.intentId(),HASH).state());
            assertEquals(PhotoCheckIntentState.RESULT_READY,service.executeOnce(7L,prepared.intentId(),HASH).state());
            assertEquals(committed,independent(prepared.intentId()));return null;
        });
        verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());verify(gateway,times(1)).planQuotaReservation(any(),any(),any());
    }
    @Test void cachedPreparedLoserOnlyReadsInFlightAndThenOriginalReadyResult() {
        PhotoCheckIntentView prepared=prepare();Stored original=independent(prepared.intentId());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService winner=Executors.newSingleThreadExecutor();
        when(vision.reviewPrepared(any(),any(),any(),any(),any())).thenAnswer(call->{
            noOwnedRowLockAtProviderEntry(prepared.intentId());entered.countDown();await(release);
            return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.degraded("original-first-review"),"UNKNOWN","PROVIDER_RESULT_UNKNOWN");
        });
        try {
            withoutBusinessTransaction().execute(status->{
                assertTrue(TransactionSynchronizationManager.isSynchronizationActive());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(PhotoCheckIntentState.PREPARED,service.read(7L,prepared.intentId(),HASH).state());
                Future<PhotoCheckIntentView> first=winner.submit(()->service.executeOnce(7L,prepared.intentId(),HASH));await(entered);
                Stored inFlight=independent(prepared.intentId());assertEquals("IN_FLIGHT",inFlight.state());
                PhotoCheckIntentView loser=service.executeOnce(7L,prepared.intentId(),HASH);
                assertEquals(PhotoCheckIntentState.IN_FLIGHT,loser.state());assertNull(loser.result());
                assertEquals(inFlight,independent(prepared.intentId()),"loser must not change the original intent");
                verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());release.countDown();
                PhotoCheckIntentView firstResult=result(first);Stored committed=independent(prepared.intentId());
                assertEquals("RESULT_READY",committed.state());
                assertEquals(PhotoCheckIntentState.RESULT_READY,firstResult.state());assertTrue(firstResult.result().isDegraded());
                PhotoCheckIntentView replay=service.read(7L,prepared.intentId(),HASH);
                assertEquals(PhotoCheckIntentState.RESULT_READY,replay.state());assertEquals("UNKNOWN",replay.providerState());
                assertEquals("original-first-review",replay.result().getReason());
                assertEquals(original.nonce(),committed.nonce());assertEquals(original.reservation(),committed.reservation());return null;
            });
        } finally {release.countDown();winner.shutdownNow();}
        verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());verify(gateway,times(1)).planQuotaReservation(any(),any(),any());
    }
    @Test void ownedReadAfterSessionLockUsesCurrentRowDespiteOlderRepeatableReadSnapshot() {
        PhotoCheckIntentView prepared=prepare();Stored original=independent(prepared.intentId());reviewWithSnapshot(prepared.intentId());
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            transaction.execute(status->{
                assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,transaction.getIsolationLevel());
                assertEquals("PREPARED",jdbc.queryForObject("SELECT state FROM photo_check_intent WHERE id=?",String.class,prepared.intentId()));
                PhotoCheckIntentView first=result(worker.submit(()->service.executeOnce(7L,prepared.intentId(),HASH)));
                assertEquals("RESULT_READY",independent(prepared.intentId()).state());assertEquals(PhotoCheckIntentState.RESULT_READY,first.state());
                // Probe only models C lock order; it is not real gameplay authorization.
                assertEquals(8L,jdbc.queryForObject("SELECT id FROM photo_fresh_read_session_probe WHERE id=8 FOR UPDATE",Long.class));
                PhotoCheckIntentView byId=service.read(7L,prepared.intentId(),HASH);
                assertEquals(PhotoCheckIntentState.RESULT_READY,byId.state(),"flushing L1 alone cannot escape an old RR snapshot");
                assertEquals(PhotoCheckIntentState.RESULT_READY,service.read(7L,8L,KEY,HASH).state());
                assertEquals(original.nonce(),independent(prepared.intentId()).nonce());return null;
            });
        } finally {worker.shutdownNow();}
        verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());verify(gateway,times(1)).planQuotaReservation(any(),any(),any());
    }

    @Test @SuppressWarnings("unchecked")
    void executeWithoutCallerReadReleasesOwnResourcesBeforeProviderAndRetainsOriginalResult() {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        PhotoCheckIntentView prepared=prepare();Stored original=independent(prepared.intentId());
        when(vision.reviewPrepared(any(),any(),any(),any(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource),"No method-owned read connection at provider entry");
            assertFalse(TransactionSynchronizationManager.getResourceMap().keySet().stream()
                    .anyMatch(org.apache.ibatis.session.SqlSessionFactory.class::isInstance));
            noOwnedRowLockAtProviderEntry(prepared.intentId());
            Stored claimed=independent(prepared.intentId());assertEquals("IN_FLIGHT",claimed.state());
            assertEquals(original.nonce(),claimed.nonce());assertEquals(original.reservation(),call.getArgument(3));
            Consumer<PhotoCheckVisionService.InputSnapshot> snapshot=call.getArgument(4);
            snapshot.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64),"configured-model","prompt-v1","encoder-v1"));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            assertFalse(TransactionSynchronizationManager.getResourceMap().keySet().stream()
                    .anyMatch(org.apache.ibatis.session.SqlSessionFactory.class::isInstance));
            return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.concluded(true,93,"original-result"),"RECEIVED","RESULT_RECEIVED");
        });
        withoutBusinessTransaction().execute(status->{
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource)); // No caller primer in this scenario.
            PhotoCheckIntentView ready=service.executeOnce(7L,prepared.intentId(),HASH);
            assertEquals(prepared.intentId(),ready.intentId());assertEquals(7L,ready.memberId());
            assertEquals(HASH,ready.requestFingerprint());assertEquals(PhotoCheckIntentState.RESULT_READY,ready.state());
            assertEquals("RECEIVED",ready.providerState());assertTrue(ready.result().isPass());
            assertEquals(93,ready.result().getConfidence());assertEquals("original-result",ready.result().getReason());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource),"Post-result current read must finish inside its short transaction");
            assertFalse(TransactionSynchronizationManager.getResourceMap().keySet().stream()
                    .anyMatch(org.apache.ibatis.session.SqlSessionFactory.class::isInstance));
            PhotoCheckIntentView replay=service.executeOnce(7L,prepared.intentId(),HASH);
            assertEquals(ready.intentId(),replay.intentId());assertEquals(ready.requestFingerprint(),replay.requestFingerprint());
            assertEquals(ready.state(),replay.state());assertEquals(ready.result().getReason(),replay.result().getReason());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            Stored committed=independent(prepared.intentId());assertEquals("RESULT_READY",committed.state());
            assertEquals(original.nonce(),committed.nonce());assertEquals(original.reservation(),committed.reservation());
            return null;
        });
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
        verify(vision,times(1)).reviewPrepared(any(),any(),any(),any(),any());
        verify(gateway,times(1)).planQuotaReservation(any(),any(),any());
    }
}
