package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.io.ClassPathResource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** State-machine units and real Spring/MyBatis binding controls with JDBC/row doubles; MySqlIT owns database evidence. */
class PhotoCheckIntentServiceTest {
    private static final String HASH = "a".repeat(64);
    private PhotoCheckIntentMapper mapper;
    private AiGatewayService gateway;
    private PhotoCheckVisionService vision;
    private ConsentGuard consent;
    private AppMediaReferenceGuard media;
    private PhotoCheckIntentService service;
    private PhotoCheckIntentRow row;
    private PlatformTransactionManager transactions;

    static PhotoCheckIntentCommand command(String key, String reference, String hash) {
        return new PhotoCheckIntentCommand(7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L,
                1, 2, 3, "original-instance", key, hash, reference, "a red door", "b".repeat(64));
    }
    @BeforeEach void setup() throws Exception {
        mapper = mock(PhotoCheckIntentMapper.class); gateway = mock(AiGatewayService.class);
        vision = mock(PhotoCheckVisionService.class); consent = mock(ConsentGuard.class);
        media = mock(AppMediaReferenceGuard.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = new PhotoCheckIntentService(mapper, gateway, vision, consent, media, transactions);
        row = new PhotoCheckIntentRow(); row.setId(1L); row.setMemberId(7L); row.setSessionId(8L);
        row.setActionKey("original-key"); row.setRequestFingerprint(HASH); row.setImageReference("media-asset:42");
        row.setContextJson(new ObjectMapper().writeValueAsString(command("original-key", "media-asset:42", HASH)));
        row.setState("PREPARED"); row.setReservation("ai:gen:limit:photo:7:20261023|" + "0".repeat(32));
        when(mapper.selectOwned(1L, 7L, HASH)).thenAnswer(call -> row);
        when(mapper.selectForUpdate(1L, HASH)).thenAnswer(call -> row);
        when(mapper.claim(1L, 7L, HASH)).thenAnswer(call -> { row.setState("IN_FLIGHT"); return 1; });
        when(mapper.saveSnapshot(any())).thenReturn(1);
        when(mapper.saveResult(any())).thenAnswer(call -> { row.setState("RESULT_READY"); return 1; });
        when(mapper.markUnknown(1L, HASH)).thenAnswer(call -> { row.setState("UNKNOWN"); return 1; });
    }
    @AfterEach void clearTransactionFlag() { TransactionSynchronizationManager.clear(); }
    private void inTransaction() { TransactionSynchronizationManager.setActualTransactionActive(true); }
    private void ready(boolean degraded) {
        row.setState("RESULT_READY"); row.setResultPass(!degraded); row.setResultConfidence(degraded ? 0 : 87);
        row.setResultReason("original result"); row.setResultDegraded(degraded); row.setProviderState("RECEIVED");
    }
    @Test void mandatoryAndNeverGuardsRejectBeforeAnyWork() {
        assertThrows(IllegalStateException.class, () -> service.prepare(command("key", "media-asset:42", HASH)));
        assertThrows(IllegalStateException.class, () -> service.markApplied(1L, HASH, 99L));
        inTransaction();
        assertThrows(IllegalStateException.class, () -> service.executeOnce(7L, 1L, HASH));
        verifyNoInteractions(mapper, gateway, vision, consent, media);
    }
    @Test void consentRefusalHasNoIntentQuotaOrProviderEffects() {
        inTransaction(); doThrow(new ServiceException("consent required")).when(consent)
                .requireCurrentAgreement(7L, "photo_model_processing", "object_photo");
        assertThrows(ServiceException.class, () -> service.prepare(command("key", "media-asset:42", HASH)));
        verify(mapper, never()).insertPrepared(any()); verifyNoInteractions(gateway, vision, media);
    }
    @Test void approvedButNotOwnedMediaRefusalPrecedesPlanning() {
        inTransaction(); when(media.requireAttachment(7L, "submitted", true)).thenReturn("media-asset:42");
        when(media.processingUrl("media-asset:42", 7L, "IMAGE")).thenThrow(new ServiceException("not owned"));
        assertThrows(ServiceException.class, () -> service.prepare(command("key", "submitted", HASH)));
        verify(mapper, never()).insertPrepared(any()); verifyNoInteractions(gateway, vision);
    }
    @Test void prepareStoresCanonicalFullContextAndOriginalPlanBeforeConsumption() throws Exception {
        inTransaction(); String signed = "https://media.invalid/input?signature=do-not-persist";
        when(media.requireAttachment(7L, signed, true)).thenReturn("media-asset:42");
        when(media.processingUrl("media-asset:42", 7L, "IMAGE")).thenReturn("transient-processing-url");
        when(gateway.planQuotaReservation(eq(7L), eq("photo_check"), anyString())).thenAnswer(call ->
                "ai:gen:limit:photo:7:20261023|" + call.getArgument(2));
        when(mapper.selectByKeyForUpdate(7L, 8L, "original-key")).thenReturn(row);
        service.prepare(command("original-key", signed, HASH));
        ArgumentCaptor<PhotoCheckIntentRow> captured = ArgumentCaptor.forClass(PhotoCheckIntentRow.class);
        verify(mapper).insertPrepared(captured.capture()); PhotoCheckIntentRow saved = captured.getValue();
        assertFalse(saved.getContextJson().contains("signature")); assertFalse(saved.getContextJson().contains("transient"));
        PhotoCheckIntentCommand context = new ObjectMapper().readValue(saved.getContextJson(), PhotoCheckIntentCommand.class);
        assertEquals(command("original-key", "media-asset:42", HASH), context);
        assertEquals(HASH, saved.getRequestFingerprint()); assertEquals("20261023", saved.getOriginalDay());
        assertTrue(saved.getNonce().matches("[0-9a-f]{32}")); assertTrue(saved.getReservation().endsWith("|" + saved.getNonce()));
        verify(gateway, never()).consumePlannedQuota(any(), any(), any()); verifyNoInteractions(vision);
    }
    @Test void sameKeySameHashReturnsOriginalWithoutNewSecurityOrQuotaActions() {
        inTransaction(); when(mapper.selectByKey(7L, 8L, "original-key")).thenReturn(row);
        assertEquals(1L, service.prepare(command("original-key", "different-url", HASH)).intentId());
        verifyNoInteractions(gateway, vision, consent, media); verify(mapper, never()).insertPrepared(any());
    }
    @Test void sameKeyDifferentFingerprintRejectsWithoutEffects() {
        inTransaction(); when(mapper.selectByKey(7L, 8L, "original-key")).thenReturn(row);
        assertThrows(ServiceException.class, () -> service.prepare(command("original-key", "media-asset:42", "c".repeat(64))));
        verifyNoInteractions(gateway, vision, consent, media); verify(mapper, never()).insertPrepared(any());
    }
    @Test void readRequiresOriginalOwnerAndFingerprintAndDoesNoExternalWork() {
        assertThrows(ServiceException.class, () -> service.read(88L, 1L, HASH));
        assertThrows(ServiceException.class, () -> service.read(7L, 1L, "d".repeat(64)));
        assertNull(service.read(7L, 1L, HASH).result()); verifyNoInteractions(gateway, vision, consent, media);
        verify(mapper, never()).claim(any(), any(), any());
    }
    @Test void damagedSessionBindingFailsClosed() throws Exception {
        row.setSessionId(999L);
        assertThrows(ServiceException.class, () -> service.read(7L, 1L, HASH)); verifyNoInteractions(vision, gateway);
    }
    @Test void inFlightAndUnknownNeverResendOrSynthesizeDegraded() {
        for (String state : new String[]{"IN_FLIGHT", "UNKNOWN"}) {
            row.setState(state); PhotoCheckIntentView seen = service.executeOnce(7L, 1L, HASH);
            assertEquals(state, seen.state().name()); assertNull(seen.result());
        }
        verifyNoInteractions(vision, gateway); verify(mapper, never()).claim(any(), any(), any());
    }
    @Test void losingCasOnlyReadsOriginalInFlight() {
        when(mapper.claim(1L, 7L, HASH)).thenAnswer(call -> { row.setState("IN_FLIGHT"); return 0; });
        assertNull(service.executeOnce(7L, 1L, HASH).result()); verifyNoInteractions(vision, gateway);
    }
    @Test @SuppressWarnings("unchecked") void firstActualReviewPersistsSnapshotBeforeOriginalResultAndReplaysIt() {
        when(vision.reviewPrepared(eq(7L), eq("media-asset:42"), eq("a red door"), eq(row.getReservation()), any()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    Consumer<PhotoCheckVisionService.InputSnapshot> callback = call.getArgument(4);
                    callback.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64), "configured-model", "p1", "e1"));
                    verify(mapper).saveSnapshot(any());
                    return new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.concluded(true, 87, "door"), "RECEIVED", "PHOTO_RESULT");
                });
        PhotoCheckIntentView first = service.executeOnce(7L, 1L, HASH);
        assertTrue(first.result().isPass()); assertEquals(87, first.result().getConfidence());
        assertTrue(service.executeOnce(7L, 1L, HASH).result().isPass());
        verify(vision, times(1)).reviewPrepared(any(), any(), any(), any(), any());
        var order = inOrder(mapper); order.verify(mapper).claim(1L, 7L, HASH);
        order.verify(mapper).saveSnapshot(any()); order.verify(mapper).saveResult(any());
    }
    @Test void firstActualDegradedResultAndProviderUnknownAreStoredSeparately() {
        when(vision.reviewPrepared(any(), any(), any(), any(), any())).thenReturn(
                new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.degraded("timeout"), "UNKNOWN", "MODEL_UNKNOWN"));
        PhotoCheckIntentView first = service.executeOnce(7L, 1L, HASH);
        assertEquals(PhotoCheckIntentState.RESULT_READY, first.state()); assertEquals("UNKNOWN", first.providerState());
        assertTrue(first.result().isDegraded()); assertFalse(first.result().isPass());
    }
    @Test void thrownSecurityRefusalIsNotConvertedToFallbackResult() {
        when(vision.reviewPrepared(any(), any(), any(), any(), any())).thenThrow(new ServiceException("withdrawn"));
        assertThrows(ServiceException.class, () -> service.executeOnce(7L, 1L, HASH));
        assertEquals("UNKNOWN", row.getState()); assertNull(service.executeOnce(7L, 1L, HASH).result());
        verify(mapper, never()).saveResult(any()); verify(vision, times(1)).reviewPrepared(any(), any(), any(), any(), any());
    }
    @Test @SuppressWarnings("unchecked") void snapshotPersistenceFailureStopsBeforeModelWork() {
        when(mapper.saveSnapshot(any())).thenReturn(0);
        when(vision.reviewPrepared(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<PhotoCheckVisionService.InputSnapshot> callback = call.getArgument(4);
            callback.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64), "model", "p1", "e1"));
            fail("provider work must not follow a failed snapshot callback"); return null;
        });
        assertThrows(IllegalStateException.class, () -> service.executeOnce(7L, 1L, HASH));
        verify(mapper, never()).saveResult(any()); assertEquals("UNKNOWN", row.getState());
    }
    @Test void markAppliedRequiresOriginalReadyResultAndMatchingReceiptAndSupportsSameReceiptReplay() {
        inTransaction(); when(mapper.countMatchingReceipt(1L, HASH, 99L)).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.markApplied(1L, HASH, 99L));
        ready(false); assertThrows(ServiceException.class, () -> service.markApplied(1L, HASH, 100L));
        when(mapper.markApplied(1L, HASH, 99L)).thenAnswer(call -> { row.setState("APPLIED"); row.setAttemptLogId(99L); return 1; });
        service.markApplied(1L, HASH, 99L); service.markApplied(1L, HASH, 99L);
        verify(mapper, times(1)).markApplied(1L, HASH, 99L);
        assertThrows(ServiceException.class, () -> service.markApplied(1L, HASH, 100L));
    }
    @Test void applyCasFailurePropagatesToBusinessTransaction() {
        inTransaction(); ready(true); when(mapper.countMatchingReceipt(1L, HASH, 99L)).thenReturn(1);
        when(mapper.markApplied(1L, HASH, 99L)).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.markApplied(1L, HASH, 99L));
    }

    @Test void losingClaimUsesThreeIndependentBoundariesAndKeepsPublicReadCallerOwned() {
        // when(claim(...)) would execute setup's existing state-changing Answer
        // while restubbing, before executeOnce can observe its original PREPARED row.
        doAnswer(call -> { assertEquals("PREPARED", row.getState()); row.setState("IN_FLIGHT"); return 0; })
                .when(mapper).claim(1L, 7L, HASH);
        assertEquals("PREPARED", row.getState());
        verifyNoInteractions(transactions); verify(mapper, never()).claim(any(), any(), any());
        PhotoCheckIntentView current = service.executeOnce(7L, 1L, HASH);
        assertEquals(1L, current.intentId()); assertEquals(HASH, current.requestFingerprint());
        assertEquals(PhotoCheckIntentState.IN_FLIGHT, current.state()); assertNull(current.result());
        ArgumentCaptor<TransactionDefinition> boundaries = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(3)).getTransaction(boundaries.capture());
        boundaries.getAllValues().forEach(definition ->
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior()));
        verify(transactions, times(3)).commit(any()); verify(transactions, never()).rollback(any());
        verify(mapper, times(2)).selectOwned(1L, 7L, HASH); verifyNoInteractions(vision, gateway);
        var order = inOrder(transactions, mapper);
        order.verify(transactions).getTransaction(any()); order.verify(mapper).selectOwned(1L, 7L, HASH);
        order.verify(transactions).commit(any());
        order.verify(transactions).getTransaction(any()); order.verify(mapper).claim(1L, 7L, HASH);
        order.verify(transactions).commit(any());
        order.verify(transactions).getTransaction(any()); order.verify(mapper).selectOwned(1L, 7L, HASH);
        order.verify(transactions).commit(any()); order.verifyNoMoreInteractions();
        service.read(7L, 1L, HASH); // Public read still belongs to its caller's lock/transaction.
        verify(transactions, times(3)).getTransaction(any());
        verify(transactions, times(3)).commit(any()); verify(transactions, never()).rollback(any());
        verifyNoMoreInteractions(transactions); verifyNoInteractions(vision, gateway, consent, media);
    }

    @Test void firstOwnedReadFailureRollsBackBeforeClaimOrProviderAndPreservesOriginalError() {
        RuntimeException unavailable = new IllegalStateException("controlled current read failure");
        when(mapper.selectOwned(1L, 7L, HASH)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> service.executeOnce(7L, 1L, HASH)));
        verify(transactions).getTransaction(any()); verify(transactions).rollback(any());
        verify(transactions, never()).commit(any()); verify(mapper, never()).claim(any(), any(), any());
        verify(mapper, never()).markUnknown(any(), any()); verify(mapper, never()).saveResult(any());
        assertEquals("PREPARED", row.getState()); verifyNoInteractions(vision, gateway, consent, media);
    }

    @Test void finalOwnedReadFailureCannotEraseCommittedResultOrPermitAnotherProviderCall() {
        RuntimeException unavailable = new IllegalStateException("controlled result read failure");
        when(vision.reviewPrepared(any(), any(), any(), any(), any())).thenReturn(
                new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.degraded("original uncertain"), "UNKNOWN", "MODEL_UNKNOWN"));
        when(mapper.selectOwned(1L, 7L, HASH)).thenReturn(row).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> service.executeOnce(7L, 1L, HASH)));
        assertEquals("RESULT_READY", row.getState()); assertEquals("UNKNOWN", row.getProviderState());
        doReturn(row).when(mapper).selectOwned(1L, 7L, HASH);
        PhotoCheckIntentView original = service.executeOnce(7L, 1L, HASH);
        assertEquals(1L, original.intentId()); assertEquals(HASH, original.requestFingerprint());
        assertEquals("UNKNOWN", original.providerState()); assertTrue(original.result().isDegraded());
        verify(mapper).saveResult(any()); verify(mapper, never()).markUnknown(any(), any());
        verify(vision, times(1)).reviewPrepared(any(), any(), any(), any(), any()); verifyNoInteractions(gateway);
    }
    @Test
    void photoExecuteProxyReleasesOwnReadsBeforeProviderAndAfterResultReadback() throws Exception {
        photoExecuteReadLifecycle("RECEIVED");
    }

    @Test
    void photoExecuteProxyLosingClaimReadsCurrentOriginalInShortTransaction() throws Exception {
        photoExecuteReadLifecycle("CLAIM_LOST");
    }

    @Test
    void photoExecuteProxyUnknownResultKeepsIdentityAndNeverReexecutes() throws Exception {
        photoExecuteReadLifecycle("UNKNOWN_RESULT");
    }

    @Test
    void photoExecuteProxyThrownFailureClosesOwnResourcesAndRetainsUnknown() throws Exception {
        photoExecuteReadLifecycle("THROWN_UNKNOWN");
    }

    private static void photoExecuteReadLifecycle(String outcome) throws Exception {
        assertTrue(java.util.Set.of("RECEIVED", "CLAIM_LOST", "UNKNOWN_RESULT", "THROWN_UNKNOWN").contains(outcome));
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        String hash = "a".repeat(64), nonce = "0".repeat(32);
        var original = new PhotoCheckIntentRow();
        original.setId(1L); original.setMemberId(7L); original.setSessionId(8L);
        original.setActionKey("original-key"); original.setRequestFingerprint(hash);
        original.setImageReference("media-asset:42"); original.setState("PREPARED");
        original.setNonce(nonce); original.setOriginalDay("20261023");
        original.setReservation("ai:gen:limit:photo:7:20261023|" + nonce);
        original.setContextJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new PhotoCheckIntentCommand(7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L,
                        1, 2, 3, "original-instance", "original-key", hash, "media-asset:42", "a red door", "b".repeat(64))));
        String frozenContext = original.getContextJson(), reservation = original.getReservation();
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var live = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var currentReads = new java.util.concurrent.atomic.AtomicInteger();
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        DataSource source = mock(DataSource.class);
        // Finite JDBC doubles prove Spring/MyBatis binding and actual close calls.
        // The cursor is empty: the original row below is an explicit mapper row
        // model, not a claim of SQL isolation, a live provider or real MySQL data.
        when(source.getConnection()).thenAnswer(open -> {
            Connection connection = mock(Connection.class);
            var connectionClosed = new java.util.concurrent.atomic.AtomicBoolean();
            var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
            opened.incrementAndGet(); peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
            when(connection.isClosed()).thenAnswer(call -> connectionClosed.get());
            when(connection.getMetaData()).thenReturn(mock(java.sql.DatabaseMetaData.class));
            doAnswer(call -> { commits.incrementAndGet(); return null; }).when(connection).commit();
            doAnswer(call -> {
                assertTrue(connectionClosed.compareAndSet(false, true), "Each borrowed connection closes once");
                closed.incrementAndGet(); live.decrementAndGet(); return null;
            }).when(connection).close();
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                boolean select = sql.stripLeading().startsWith("SELECT");
                int changed = outcome.equals("CLAIM_LOST") && sql.contains("SET state='IN_FLIGHT'") ? 0 : 1;
                when(statement.getConnection()).thenReturn(connection);
                when(statement.execute()).thenReturn(select);
                when(statement.getUpdateCount()).thenReturn(select ? -1 : changed, -1);
                ResultSet empty = mock(ResultSet.class);
                when(empty.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
                when(statement.getResultSet()).thenReturn(empty);
                return statement;
            });
            return connection;
        });
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/PhotoCheckIntentMapper.xml"));
        var sessions = factory.getObject(); assertNotNull(sessions);
        PhotoCheckIntentMapper sqlMapper = new SqlSessionTemplate(sessions).getMapper(PhotoCheckIntentMapper.class);
        PhotoCheckIntentMapper mapper = mock(PhotoCheckIntentMapper.class, org.mockito.AdditionalAnswers.delegatesTo(sqlMapper));
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "Owned reads need an independent actual transaction");
            assertNull(sqlMapper.selectOwned(1L, 7L, hash)); // Execute the real mapped SELECT/binding/connection path.
            assertTrue(TransactionSynchronizationManager.hasResource(sessions));
            Connection bound = ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection();
            assertFalse(bound.isClosed()); assertFalse(bound.getAutoCommit());
            assertEquals(1, live.get()); currentReads.incrementAndGet();
            var current = new PhotoCheckIntentRow(); org.springframework.beans.BeanUtils.copyProperties(original, current);
            return current; // Fresh controlled row for each read, never a cached PREPARED object.
        }).when(mapper).selectOwned(1L, 7L, hash);
        doAnswer(call -> {
            int changed = sqlMapper.claim(1L, 7L, hash);
            assertEquals(outcome.equals("CLAIM_LOST") ? 0 : 1, changed);
            original.setState("IN_FLIGHT"); // In CLAIM_LOST the controlled competing writer won.
            return changed;
        }).when(mapper).claim(1L, 7L, hash);
        doAnswer(call -> {
            PhotoCheckIntentRow snapshot = call.getArgument(0);
            assertEquals(1L, snapshot.getId()); assertEquals(hash, snapshot.getRequestFingerprint());
            return sqlMapper.saveSnapshot(snapshot);
        }).when(mapper).saveSnapshot(any());
        doAnswer(call -> {
            PhotoCheckIntentRow saved = call.getArgument(0);
            int changed = sqlMapper.saveResult(saved); assertEquals(1, changed);
            org.springframework.beans.BeanUtils.copyProperties(saved, original); original.setState("RESULT_READY");
            return changed;
        }).when(mapper).saveResult(any());
        doAnswer(call -> {
            int changed = sqlMapper.markUnknown(1L, hash); assertEquals(1, changed);
            original.setState("UNKNOWN"); original.setProviderState("UNKNOWN"); original.setReasonCode("EXECUTION_UNCERTAIN");
            return changed;
        }).when(mapper).markUnknown(1L, hash);
        var manager = new DataSourceTransactionManager(source);
        assertEquals(org.springframework.transaction.support.AbstractPlatformTransactionManager.SYNCHRONIZATION_ALWAYS,
                manager.getTransactionSynchronization());
        AiGatewayService gateway = mock(AiGatewayService.class);
        PhotoCheckVisionService vision = mock(PhotoCheckVisionService.class);
        ConsentGuard consent = mock(ConsentGuard.class);
        AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        RuntimeException uncertain = new IllegalStateException("controlled provider outcome unknown");
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive(), "Keep the real NEVER empty synchronization");
            assertFalse(TransactionSynchronizationManager.hasResource(source));
            assertFalse(TransactionSynchronizationManager.hasResource(sessions));
            assertEquals(0, live.get(), "Initial owned read and claim must release their physical connections before provider entry");
            assertEquals("IN_FLIGHT", original.getState()); assertEquals(reservation, call.getArgument(3));
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<PhotoCheckVisionService.InputSnapshot> snapshot = call.getArgument(4);
            snapshot.accept(new PhotoCheckVisionService.InputSnapshot("e".repeat(64), "model", "p1", "e1"));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(source));
            assertFalse(TransactionSynchronizationManager.hasResource(sessions)); assertEquals(0, live.get());
            if (outcome.equals("THROWN_UNKNOWN")) throw uncertain;
            return outcome.equals("UNKNOWN_RESULT")
                    ? new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.degraded("original uncertain"), "UNKNOWN", "MODEL_UNKNOWN")
                    : new PhotoCheckVisionService.PreparedReview(PhotoCheckVisionResult.concluded(true, 87, "original result"), "RECEIVED", "PHOTO_RESULT");
        }).when(vision).reviewPrepared(any(), any(), any(), any(), any());
        var target = new PhotoCheckIntentService(mapper, gateway, vision, consent, media, manager);
        var proxy = new org.springframework.aop.framework.ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        PhotoCheckIntentService service = (PhotoCheckIntentService) proxy.getProxy();
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        // Annotation enforcement by the real manager, not a direct call with a manually set flag.
        new TransactionTemplate(manager).execute(status -> {
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                    () -> service.executeOnce(7L, 1L, hash));
            verifyNoInteractions(mapper, gateway, vision, consent, media); return null;
        });
        var empty = new TransactionTemplate(manager); empty.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
        empty.execute(status -> {
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (outcome.equals("THROWN_UNKNOWN")) {
                assertSame(uncertain, assertThrows(IllegalStateException.class, () -> service.executeOnce(7L, 1L, hash)));
                assertEquals("UNKNOWN", original.getState());
                assertEquals(PhotoCheckIntentState.UNKNOWN, service.executeOnce(7L, 1L, hash).state());
                assertEquals(2, currentReads.get());
                verify(mapper).markUnknown(1L, hash); verify(mapper, never()).saveResult(any());
            } else {
                var seen = service.executeOnce(7L, 1L, hash);
                assertEquals(1L, seen.intentId()); assertEquals(7L, seen.memberId()); assertEquals(hash, seen.requestFingerprint());
                if (outcome.equals("CLAIM_LOST")) {
                    assertEquals(PhotoCheckIntentState.IN_FLIGHT, seen.state()); assertNull(seen.result());
                    assertEquals(2, currentReads.get()); verifyNoInteractions(vision);
                } else {
                    assertEquals(PhotoCheckIntentState.RESULT_READY, seen.state());
                    assertEquals(outcome.equals("UNKNOWN_RESULT") ? "UNKNOWN" : "RECEIVED", seen.providerState());
                    assertEquals(outcome.equals("UNKNOWN_RESULT"), seen.result().isDegraded());
                    assertEquals(2, currentReads.get(), "Initial and post-result reads both use the actual short boundary");
                    var replay = service.executeOnce(7L, 1L, hash);
                    assertEquals(seen.intentId(), replay.intentId()); assertEquals(seen.memberId(), replay.memberId());
                    assertEquals(seen.sessionId(), replay.sessionId()); assertEquals(seen.actionKey(), replay.actionKey());
                    assertEquals(seen.requestFingerprint(), replay.requestFingerprint()); assertEquals(seen.state(), replay.state());
                    assertEquals(seen.providerState(), replay.providerState()); assertEquals(seen.reasonCode(), replay.reasonCode());
                    assertEquals(seen.result().isPass(), replay.result().isPass());
                    assertEquals(seen.result().isDegraded(), replay.result().isDegraded());
                    assertEquals(seen.result().getConfidence(), replay.result().getConfidence());
                    assertEquals(seen.result().getReason(), replay.result().getReason()); assertEquals(3, currentReads.get());
                    verify(mapper).saveResult(any()); verify(mapper, never()).markUnknown(any(), any());
                }
            }
            assertEquals(frozenContext, original.getContextJson()); assertEquals(nonce, original.getNonce());
            assertEquals(reservation, original.getReservation()); assertEquals("20261023", original.getOriginalDay());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(source), "Final/loser/replay read must not bind a connection to the caller's empty synchronization");
            assertFalse(TransactionSynchronizationManager.hasResource(sessions)); assertEquals(0, live.get());
            assertEquals(opened.get(), closed.get()); assertEquals(1, peak.get()); return null;
        });
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.hasResource(source));
        assertFalse(TransactionSynchronizationManager.hasResource(sessions)); assertEquals(0, live.get());
        assertEquals(opened.get(), closed.get()); assertTrue(commits.get() >= 3);
        verify(mapper).claim(1L, 7L, hash);
        verify(vision, times(outcome.equals("CLAIM_LOST") ? 0 : 1)).reviewPrepared(any(), any(), any(), any(), any());
        verifyNoInteractions(gateway, consent, media); // No replanning, consuming, retaining or refunding through a new quota path.
    }

}
