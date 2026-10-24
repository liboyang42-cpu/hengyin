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
        assertEquals("ASR_OUTCOME_UNKNOWN",f.service().readOutcome("shop",21L,RID,7L).getErrorCode());
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
        var f=new Fixture();when(f.rows.finish(any())).thenThrow(new IllegalStateException("controlled SQL failure"));
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
        assertEquals("ASR_RECEIVED",cold.readOutcome("shop",21L,RID,7L).getErrorCode());
        assertTrue(cold.beginHandoff(7L,RID,21L,41L));assertFalse(cold.beginHandoff(7L,RID,21L,41L));
        assertEquals("ASR_TEXT_HANDOFF_STARTED",cold.readOutcome("shop",21L,RID,7L).getErrorCode());
        f.rows.redactForUser(7L);assertEquals("ASR_TEXT_UNAVAILABLE",cold.readOutcome("shop",21L,RID,7L).getErrorCode());
        assertTrue(f.recognize().isBodyErased());verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void expiredBodyAndCrossScopeAreNotNotFoundOrPermissionToRecognizeAgain() {
        var f=new Fixture();f.recognize();f.original().setBodyExpiresAt(new Date(0));
        assertEquals("ASR_TEXT_UNAVAILABLE",f.service().readOutcome("shop",21L,RID,7L).getErrorCode());
        assertNull(f.original().getTranscript());assertEquals("INVALID_REQUEST",f.service().readOutcome("shop",22L,RID,7L).getErrorCode());
        assertEquals("INVALID_REQUEST",f.service().readOutcome("roam",21L,RID,7L).getErrorCode());
        assertNull(f.service().readOutcome("shop",21L,RID,8L));f.recognize();verify(f.speech,times(1)).transcribe(any(),anyString());
    }
    @Test void existingOuterTransactionIsRejectedBeforeAcquiringAnotherMemberLockOrDispatching() {
        var f=new Fixture();new TransactionTemplate(f.manager).executeWithoutResult(status->assertThrows(IllegalStateException.class,f::recognize));
        assertTrue(f.stored.isEmpty());verifyNoInteractions(f.speech,f.rows,f.users);
    }
}
