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
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** State-machine units only. Real transaction/unique-index evidence belongs to MySqlIT. */
class PhotoCheckIntentServiceTest {
    private static final String HASH = "a".repeat(64);
    private PhotoCheckIntentMapper mapper;
    private AiGatewayService gateway;
    private PhotoCheckVisionService vision;
    private ConsentGuard consent;
    private AppMediaReferenceGuard media;
    private PhotoCheckIntentService service;
    private PhotoCheckIntentRow row;

    static PhotoCheckIntentCommand command(String key, String reference, String hash) {
        return new PhotoCheckIntentCommand(7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L,
                1, 2, 3, "original-instance", key, hash, reference, "a red door", "b".repeat(64));
    }
    @BeforeEach void setup() throws Exception {
        mapper = mock(PhotoCheckIntentMapper.class); gateway = mock(AiGatewayService.class);
        vision = mock(PhotoCheckVisionService.class); consent = mock(ConsentGuard.class);
        media = mock(AppMediaReferenceGuard.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
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
}
