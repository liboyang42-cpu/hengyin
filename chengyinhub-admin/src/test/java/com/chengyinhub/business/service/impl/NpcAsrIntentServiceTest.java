package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcAsrIntent;
import com.chengyinhub.business.mapper.NpcAsrIntentMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real ASR owner algorithm; memory persistence/transaction doubles are unit evidence only, not MySQL. */
class NpcAsrIntentServiceTest {
    static final String RID="e2596106-82d9-4a4f-9ff2-058414599f90";
    static final byte[] AUDIO={1,2,3};
    static class Fixture extends NpcAsrTestFixture { }
    @Test void sameRidConcurrentCallsHaveOnlyOneCommittedProviderWinner() throws Exception {
        var f=new Fixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(f.speech.transcribe(any(),anyString())).thenAnswer(c->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();
            assertTrue(release.await(5,TimeUnit.SECONDS));return "原转写";
        });
        try(var workers=Executors.newFixedThreadPool(2)){
            Future<NpcAsrIntent> first=workers.submit(f::recognize);
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertEquals("IN_FLIGHT",workers.submit(f::recognize).get(5,TimeUnit.SECONDS).getState());
            }finally{release.countDown();}
            assertEquals("RECEIVED",first.get(5,TimeUnit.SECONDS).getState());
        }
        assertEquals("原转写",f.recognize().getTranscript());verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void differentAudioFormatNodeAndMerchantCannotReplaceTheOriginalClaim() {
        var f=new Fixture();var original=f.recognize();
        assertThrows(IllegalArgumentException.class,()->f.service().recognize(7L,RID,21L,41L,new byte[]{9},"wav"));
        assertThrows(IllegalArgumentException.class,()->f.service().recognize(7L,RID,21L,41L,AUDIO,"mp3"));
        assertThrows(IllegalArgumentException.class,()->f.service().recognize(7L,RID,22L,41L,AUDIO,"wav"));
        assertThrows(IllegalArgumentException.class,()->f.service().recognize(7L,RID,21L,42L,AUDIO,"wav"));
        assertEquals(original.getClaimToken(),f.original().getClaimToken());assertEquals(1,f.stored.size());
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void genericFailureTimeoutAndColdOwnerNeverResendUnknown() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenThrow(new AiServiceException("synthetic timeout"));
        assertEquals("UNKNOWN",f.recognize().getState());assertEquals("UNKNOWN",f.service().recognize(7L,RID,21L,41L,AUDIO,"wav").getState());
        assertEquals("ASR_OUTCOME_UNKNOWN",f.service().readOutcome("shop",21L,RID,7L).getAsrReason());
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void positivelyNotSentIsTerminalForTheOriginalIdentityToo() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenThrow(new AiServiceException("not sent",new AiProviderNotSentException()));
        assertEquals("NOT_SENT",f.recognize().getState());assertEquals("NOT_SENT",f.recognize().getState());
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void emptyTextIsAStoredTerminalResultWithoutTextHandoff() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenReturn(null);
        assertEquals("",f.recognize().getTranscript());assertEquals("ASR_EMPTY_TEXT",f.service().readOutcome("shop",21L,RID,7L).getErrorCode());
        assertFalse(f.service().beginHandoff(7L,RID,21L,41L));f.recognize();verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void resultSaveFailureNeverReleasesTheClaimOrReturnsUnpersistedText() {
        var f=new Fixture();doThrow(new IllegalStateException("controlled SQL failure")).when(f.rows).finish(any());
        assertThrows(IllegalStateException.class,f::recognize);
        assertEquals("IN_FLIGHT",f.recognize().getState());assertNull(f.service().read(7L,RID,21L).getTranscript());
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void unknownClaimCommitDoesNotSendEvenWhenTheReceiptBecameVisible() {
        var f=new Fixture();f.lostCommit.set(true);assertThrows(IllegalStateException.class,f::recognize);
        assertEquals("IN_FLIGHT",f.recognize().getState());assertEquals(1,f.stored.size());verifyNoInteractions(f.speech);
    }
    @Test void deletionDuringProviderCallRevokesLateBodyButKeepsIdentity() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenAnswer(c->{f.rows.redactForUser(7L);return "must not survive deletion";});
        var result=f.recognize();assertTrue(result.isBodyErased());assertNull(result.getTranscript());assertEquals("UNKNOWN",result.getState());
        assertEquals("UNKNOWN",f.recognize().getState());assertFalse(f.service().beginHandoff(7L,RID,21L,41L));
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void coldReceivedReadNeverCallsProviderAndHandoffIsOneWayAcrossDeletion() {
        var f=new Fixture();f.recognize();var cold=f.service();
        assertEquals("ASR_RECEIVED",cold.readOutcome("shop",21L,RID,7L).getAsrReason());
        assertTrue(cold.beginHandoff(7L,RID,21L,41L));assertFalse(cold.beginHandoff(7L,RID,21L,41L));
        assertEquals("ASR_TEXT_HANDOFF_STARTED",cold.readOutcome("shop",21L,RID,7L).getAsrReason());
        f.rows.redactForUser(7L);assertEquals("ASR_TEXT_UNAVAILABLE",cold.readOutcome("shop",21L,RID,7L).getErrorCode());
        assertTrue(f.recognize().isBodyErased());verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void expiredBodyAndCrossScopeAreNotNotFoundOrPermissionToRecognizeAgain() {
        var f=new Fixture();f.recognize();f.original().setBodyExpiresAt(new Date(0));
        assertEquals("ASR_TEXT_UNAVAILABLE",f.service().readOutcome("shop",21L,RID,7L).getErrorCode());
        assertNull(f.service().read(7L,RID,21L).getTranscript());assertEquals("原转写",f.original().getTranscript(),"GET must not mutate expired bodies");assertEquals("INVALID_REQUEST",f.service().readOutcome("shop",22L,RID,7L).getErrorCode());
        assertEquals("INVALID_REQUEST",f.service().readOutcome("roam",21L,RID,7L).getErrorCode());
        assertNull(f.service().readOutcome("shop",21L,RID,8L));f.recognize();verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void getStageKeepsOriginalRecoveryCodesAndPerformsNoCleanupOrHandoff() {
        var f=new Fixture();f.recognize();clearInvocations(f.rows,f.speech);
        var received=f.service().readOutcome("shop",21L,RID,7L);
        assertEquals("PROCESSING",received.getOutcomeStatus());assertEquals("REQUEST_IN_PROGRESS",received.getErrorCode());
        assertEquals("RECEIVED",received.getAsrState());assertEquals("ASR_RECEIVED",received.getAsrReason());assertTrue(received.isAsrResumable());
        verify(f.rows,never()).redactExpiredForUser(anyLong());verify(f.rows,never()).beginHandoff(anyLong(),anyString());
        verifyNoInteractions(f.speech);assertFalse(f.original().isHandoffStarted());
        f.original().setState("UNKNOWN");
        var unknown=f.service().readOutcome("shop",21L,RID,7L);
        assertEquals("PROCESSING",unknown.getOutcomeStatus());assertEquals("REQUEST_OUTCOME_UNKNOWN",unknown.getErrorCode());
        assertEquals("UNKNOWN",unknown.getAsrState());assertEquals("ASR_OUTCOME_UNKNOWN",unknown.getAsrReason());assertFalse(unknown.isAsrResumable());
    }
    @Test void existingOuterTransactionIsRejectedBeforeAcquiringAnotherMemberLockOrDispatching() {
        var f=new Fixture();new TransactionTemplate(f.manager).executeWithoutResult(status->assertThrows(IllegalStateException.class,f::recognize));
        assertTrue(f.stored.isEmpty());verifyNoInteractions(f.speech,f.rows,f.users);
    }

    @Test void originalQuotaIsCommittedAndReservedBeforeTheSingleAsrCall() {
        var f=new Fixture();f.recognize();f.recognize();
        String reservation=f.original().getQuotaReservation();assertNotNull(reservation);
        assertTrue(reservation.startsWith("ai:gen:limit:gen:7:"));
        assertTrue(reservation.endsWith("|"+f.original().getClaimToken().replace("-","")));
        var order=inOrder(f.quota,f.speech);
        order.verify(f.quota).planQuotaReservation(eq(7L),eq("npc_shop_chat"),anyString());
        order.verify(f.quota).consumePlannedQuota(7L,"npc_shop_chat",reservation);
        order.verify(f.quota).stageQuota(7L,"npc_shop_chat",reservation,"ASR_STARTED");
        order.verify(f.speech).transcribe(AUDIO,"wav");
        verify(f.quota,times(1)).consumePlannedQuota(anyLong(),anyString(),anyString());
        verify(f.quota,never()).refundNpcAsrNotSent(anyLong(),anyString());
    }
    @Test void unavailableReservationIsLocallyNotSentAndNeverReentersProvider() {
        var f=new Fixture();doThrow(new IllegalStateException("controlled reserve acknowledgement loss"))
                .when(f.quota).consumePlannedQuota(anyLong(),anyString(),anyString());
        assertEquals("NOT_SENT",f.recognize().getState());assertTrue(f.original().isQuotaRefundPending());
        assertEquals("NOT_SENT",f.recognize().getState());verifyNoInteractions(f.speech);
        verify(f.quota).refundNpcAsrNotSent(7L,RID);
        verify(f.quota,times(1)).consumePlannedQuota(anyLong(),anyString(),anyString());
    }
    @Test void uncertainStageCommitStopsBeforeAsrAndKeepsDurableNotSentProof() {
        var f=new Fixture();doThrow(new IllegalStateException("controlled stage commit uncertainty"))
                .when(f.quota).stageQuota(anyLong(),anyString(),anyString(),eq("ASR_STARTED"));
        assertEquals("NOT_SENT",f.recognize().getState());assertTrue(f.original().isQuotaRefundPending());
        verifyNoInteractions(f.speech);verify(f.quota).refundNpcAsrNotSent(7L,RID);
    }
    @Test void unknownShowsQuotaProcessingAndNeverRequestsCompensation() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenThrow(new AiServiceException("controlled timeout"));
        assertEquals("UNKNOWN",f.recognize().getState());
        var result=f.service().readOutcome("shop",21L,RID,7L);
        assertEquals("REQUEST_OUTCOME_UNKNOWN",result.getErrorCode());assertEquals("PROCESSING",result.getQuotaStatus());
        assertTrue(result.getSafeText().contains("额度状态处理中"));assertFalse(result.isRetryable());
        f.recognize();verify(f.quota,never()).refundNpcAsrNotSent(anyLong(),anyString());
        verify(f.quota,never()).retainQuota(anyLong(),anyString(),anyString(),anyString());
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void receivedEmptyTextStillHoldsTheSingleFunctionQuota() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenReturn("");
        assertEquals("",f.recognize().getTranscript());assertEquals("HELD",f.service().readOutcome("shop",21L,RID,7L).getQuotaStatus());
        verify(f.quota).retainQuota(7L,"npc_shop_chat",f.original().getQuotaReservation(),"ASR_RECEIVED");
        verify(f.quota,never()).refundNpcAsrNotSent(anyLong(),anyString());
    }
    @Test void provenNotSentAfterDeletionQueuesOnlyTheOriginalRefundWithoutRestoringBody() {
        var f=new Fixture();when(f.speech.transcribe(any(),anyString())).thenAnswer(call -> {
            f.rows.redactForUser(7L);throw new AiServiceException("not sent",new AiProviderNotSentException());
        });
        var result=f.recognize();assertEquals("NOT_SENT",result.getState());assertTrue(result.isBodyErased());
        assertNull(result.getTranscript());assertTrue(result.isQuotaRefundPending());
        verify(f.quota).refundNpcAsrNotSent(7L,RID);assertNotNull(f.original().getQuotaReservation());
    }
    @Test void legacyWithoutQuotaIdentityIsReadOnlyAndCannotForgeAHandoff() {
        var f=new Fixture();f.recognize();f.original().setQuotaReservation(null);
        assertEquals("ASR_QUOTA_UNAVAILABLE",f.service().readOutcome("shop",21L,RID,7L).getAsrReason());
        assertEquals("REQUEST_OUTCOME_UNKNOWN",f.service().readOutcome("shop",21L,RID,7L).getErrorCode());
        assertThrows(IllegalStateException.class,()->f.service().beginHandoff(7L,RID,21L,41L));
        f.recognize();verify(f.quota,times(1)).consumePlannedQuota(anyLong(),anyString(),anyString());
        verify(f.speech,times(1)).transcribe(any(),anyString());
    }


    @Test void committedTextWinnerUnderTheSameMemberFencePreventsAnyAsrReservation() {
        var f=new Fixture();var text=new com.chengyinhub.business.domain.NpcChatRequest();
        text.setUserId(7L);text.setRequestId(RID);text.setSessionId(-21L);
        when(f.users.selectByUserAndRequestId(7L,RID)).thenReturn(text);
        assertThrows(IllegalStateException.class,f::recognize);
        verifyNoInteractions(f.quota,f.speech);verify(f.rows,never()).insert(any());
    }
}
