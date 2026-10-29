package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** New key recovery regressions; reuse only the frozen real MySQL/Spring setup, not its ten tests.
 * Session lock probe models lock order only, not real C gameplay authorization. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class PhotoCheckIntentRecoveryMySqlIT {
    private static final String HASH="a".repeat(64), KEY="original-key";
    private PhotoCheckIntentMySqlIT fixture;
    private PhotoCheckIntentService service;
    private PhotoCheckIntentMapper mapper;
    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;
    private AiGatewayService gateway;
    private PhotoCheckVisionService vision;
    private ConsentGuard consent;
    private AppMediaReferenceGuard media;

    @BeforeAll void connect() throws Exception {
        fixture=new PhotoCheckIntentMySqlIT();fixture.connect();
        service=(PhotoCheckIntentService)ReflectionTestUtils.getField(fixture,"service");
        mapper=(PhotoCheckIntentMapper)ReflectionTestUtils.getField(fixture,"mapper");
        transaction=(TransactionTemplate)ReflectionTestUtils.getField(fixture,"transaction");
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
        gateway=(AiGatewayService)ReflectionTestUtils.getField(fixture,"gateway");
        vision=(PhotoCheckVisionService)ReflectionTestUtils.getField(fixture,"vision");
        consent=(ConsentGuard)ReflectionTestUtils.getField(fixture,"consent");
        media=(AppMediaReferenceGuard)ReflectionTestUtils.getField(fixture,"media");
        jdbc.execute("CREATE TABLE photo_recovery_session_probe (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO photo_recovery_session_probe VALUES (8)");
    }
    @BeforeEach void cleanScratch() { fixture.cleanOwnedScratchAndStub(); }
    @AfterAll void close() throws Exception { if(fixture!=null) fixture.close(); }
    private PhotoCheckIntentCommand command() { return PhotoCheckIntentServiceTest.command(KEY,"submitted-url",HASH); }
    private void lockOriginalSessionProbe() {
        assertEquals(8L,jdbc.queryForObject("SELECT id FROM photo_recovery_session_probe WHERE id=8 FOR UPDATE",Long.class));
    }
    private PhotoCheckIntentView readAfterSessionLock() {
        return transaction.execute(status->{lockOriginalSessionProbe();return service.read(7L,8L,KEY,HASH);});
    }
    private Map<String,Object> stored() { return jdbc.queryForMap("SELECT nonce,reservation,state,updated_at FROM photo_check_intent"); }
    private void forgetPriorCalls() { clearInvocations(gateway,vision,consent,media); }
    private void noExternalWork() { verifyNoInteractions(gateway,vision,consent,media); }

    @Test @SuppressWarnings("unchecked") void lostPrepareResponseRecoversOriginalNonceAndPersistedResultByKey() {
        // Deliberately discard the prepare response: the recovering client has no intentId.
        transaction.execute(status->{lockOriginalSessionProbe();service.prepare(command());return null;});
        Map<String,Object> original=stored();forgetPriorCalls();
        PhotoCheckIntentView recovered=readAfterSessionLock();
        assertNotNull(recovered);assertEquals(PhotoCheckIntentState.PREPARED,recovered.state());assertNull(recovered.result());
        assertEquals(original,stored());noExternalWork();
        when(vision.reviewPrepared(any(),any(),any(),eq(String.valueOf(original.get("reservation"))),any())).thenAnswer(call->{
            Consumer<PhotoCheckVisionService.InputSnapshot> snapshot=call.getArgument(4);
            snapshot.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64),"configured-model","prompt-v1","encoder-v1"));
            return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.concluded(true,91,"original-stored-result"),"RECEIVED","RESULT_RECEIVED");
        });
        service.executeOnce(7L,recovered.intentId(),HASH);
        Map<String,Object> savedResult=stored();assertEquals(original.get("nonce"),savedResult.get("nonce"));
        assertEquals(original.get("reservation"),savedResult.get("reservation"));forgetPriorCalls();
        PhotoCheckIntentView replay=readAfterSessionLock();
        assertEquals(recovered.intentId(),replay.intentId());assertEquals(PhotoCheckIntentState.RESULT_READY,replay.state());
        assertTrue(replay.result().isPass());assertEquals(91,replay.result().getConfidence());
        assertEquals("original-stored-result",replay.result().getReason());assertEquals("RECEIVED",replay.providerState());
        assertEquals(savedResult,stored());noExternalWork();
    }
    @Test void exactMissingOwnerAndKeyReturnNullWhileHashConflictIs409AndAllAreSideEffectFree() {
        transaction.execute(status->{lockOriginalSessionProbe();service.prepare(command());return null;});
        Map<String,Object> before=stored();forgetPriorCalls();
        transaction.execute(status->{
            lockOriginalSessionProbe();assertNull(service.read(99L,8L,KEY,HASH));
            assertNull(service.read(7L,8L,"missing-key",HASH));
            assertEquals(409,assertThrows(ServiceException.class,()->service.read(7L,8L,KEY,"b".repeat(64))).getCode());
            return null;
        });
        assertEquals(before,stored());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM photo_check_intent",Integer.class));noExternalWork();
    }
    @Test void repeatableReadEmptySnapshotBeforeSessionLockSeesConcurrentCommittedOriginal() throws Exception {
        CountDownLatch preparedButUncommitted=new CountDownLatch(1),readerSawEmpty=new CountDownLatch(1);
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            Future<PhotoCheckIntentView> original=writer.submit(()->transaction.execute(status->{
                lockOriginalSessionProbe();PhotoCheckIntentView prepared=service.prepare(command());preparedButUncommitted.countDown();
                try {assertTrue(readerSawEmpty.await(10,TimeUnit.SECONDS));}
                catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                return prepared;
            }));
            assertTrue(preparedButUncommitted.await(10,TimeUnit.SECONDS));
            PhotoCheckIntentView recovered=transaction.execute(status->{
                assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,transaction.getIsolationLevel());
                assertNull(mapper.selectByKey(7L,8L,KEY)); // RR snapshot and first-level cached null
                readerSawEmpty.countDown();
                lockOriginalSessionProbe(); // writer must commit and release the original session first
                PhotoCheckIntentView current=service.read(7L,8L,KEY,HASH);
                assertNotNull(current);assertEquals(PhotoCheckIntentState.PREPARED,current.state());
                // The ordinary query still sees the old DB read view; it cannot replace FOR UPDATE.
                assertNull(mapper.selectByKey(7L,8L,KEY));
                return current;
            });
            assertEquals(original.get(10,TimeUnit.SECONDS).intentId(),recovered.intentId());
            assertTrue(String.valueOf(stored().get("nonce")).matches("[0-9a-f]{32}"));
        } finally {readerSawEmpty.countDown();writer.shutdownNow();}
        verify(gateway,times(1)).planQuotaReservation(any(),any(),any());verifyNoInteractions(vision);
    }
    @Test void sameLockingStatementCachedNullIsFlushedBeforeSecondCurrentRead() throws Exception {
        // READ COMMITTED disables absent-row gap locks, isolating the MyBatis first-level cache
        // regression. The separate test above exercises the MySQL RR old-snapshot boundary.
        TransactionTemplate reader=new TransactionTemplate(transaction.getTransactionManager());
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            PhotoCheckIntentView recovered=reader.execute(status->{
                lockOriginalSessionProbe();assertNull(service.read(7L,8L,KEY,HASH));
                // Controlled admission writer locks only the intent, never session in reverse order.
                Future<PhotoCheckIntentView> prepared=writer.submit(()->transaction.execute(write->service.prepare(command())));
                try {
                    PhotoCheckIntentView winner=prepared.get(10,TimeUnit.SECONDS);
                    PhotoCheckIntentView current=service.read(7L,8L,KEY,HASH); // exact same statement+parameters+SqlSession
                    assertNotNull(current,"cached null must not hide the committed original");
                    assertEquals(winner.intentId(),current.intentId());return current;
                } catch(InterruptedException failure) {
                    Thread.currentThread().interrupt();throw new IllegalStateException(failure);
                } catch(ExecutionException | TimeoutException failure) {throw new IllegalStateException(failure);}
            });
            assertNotNull(recovered);assertEquals(PhotoCheckIntentState.PREPARED,recovered.state());
            assertTrue(String.valueOf(stored().get("nonce")).matches("[0-9a-f]{32}"));
        } finally {writer.shutdownNow();}
        verify(gateway,times(1)).planQuotaReservation(any(),any(),any());verifyNoInteractions(vision);
    }
}
