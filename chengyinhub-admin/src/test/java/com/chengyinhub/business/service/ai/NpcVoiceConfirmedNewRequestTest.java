package com.chengyinhub.business.service.ai;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.ai.NpcVoiceEnrollmentServiceTest.Fixture;
import static com.chengyinhub.business.service.ai.NpcVoiceEnrollmentServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Source-only candidate: controlled mapper/transaction doubles, not real storage acceptance. */
class NpcVoiceConfirmedNewRequestTest {
    private static final String NEW = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER = "00000000-0000-4000-8000-000000000002";
    private NpcProfile independent(Fixture f, String request, Long original, boolean consent, String sample) {
        return f.service.enroll(ACTOR, f.stored.getScopeType(), SCOPE, NPC, sample, request, original, consent, () -> "approved-input");
    }
    private Long unknown(Fixture f) {
        doThrow(new AiServiceException("original transport unknown")).when(f.provider).enroll(anyString());
        assertEquals("UNKNOWN", f.enroll().getVoiceIntentState()); return f.row().getId();
    }

    @Test void explicitConfirmationCreatesIndependentIdentityAndKeepsOriginalUnknownFingerprintAndOccupancy() {
        for (int scope : new int[]{2,4}) {
            Fixture f = new Fixture(scope); Long original = unknown(f);
            String hash = f.row().getSampleSha256(), originalRequest = f.row().getRequestId();
            doReturn("voice-new").when(f.provider).enroll(anyString());
            NpcProfile next = independent(f, NEW, original, true, SAMPLE);
            assertEquals("READY", next.getVoiceIntentState()); assertEquals(NEW, next.getVoiceRequestId());
            assertNotEquals(original, next.getVoiceIntentId()); assertEquals(original, f.rows.get(NEW).getPreviousIntentId());
            assertEquals(2, f.rows.size()); assertEquals(hash, f.row().getSampleSha256());
            assertEquals("UNKNOWN", f.row().getState()); assertEquals(originalRequest, f.row().getRequestId());
            assertNull(f.row().getVoiceId()); assertEquals("voice-new", f.stored.getVoiceId());
            assertEquals(next.getVoiceIntentId(), independent(f, NEW, original, true, SAMPLE).getVoiceIntentId());
            verify(f.provider,times(2)).enroll(anyString());
        }
    }

    @Test void absentConsentWrongOriginalAndASecondConfirmationKeyCannotDispatch() {
        Fixture f = new Fixture(2); Long original = unknown(f);
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> independent(f, NEW, original, false, SAMPLE));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> independent(f, NEW, original + 1, true, SAMPLE));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> independent(f, NEW, null, false, SAMPLE));
        assertEquals(1, f.rows.size()); verify(f.provider,times(1)).enroll(anyString());
        doReturn("voice-new").when(f.provider).enroll(anyString()); independent(f, NEW, original, true, SAMPLE);
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> independent(f, OTHER, original, true, SAMPLE));
        verify(f.provider,times(2)).enroll(anyString()); assertEquals(2,f.rows.size());
    }

    @Test void sameRequestWithChangedSampleOrPredecessorIsRejectedBeforeSigningOrProvider() {
        Fixture f = new Fixture(4); Long original = unknown(f);
        doReturn("voice-new").when(f.provider).enroll(anyString()); independent(f, NEW, original, true, SAMPLE);
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> independent(f, NEW, original, true, "media-asset:74"));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> independent(f, NEW, original+1, true, SAMPLE));
        verify(f.provider,times(2)).enroll(anyString()); assertEquals(2,f.rows.size());
    }

    @Test void oldStatusAndRecoveryCannotEnrollOrSelectTheUnknownOriginalOverTheNewVoice() {
        Fixture f = new Fixture(2); Long original = unknown(f); String oldRequest=f.row().getRequestId();
        doReturn("voice-new").when(f.provider).enroll(anyString()); independent(f, NEW, original, true, SAMPLE);
        for(int i=0;i<3;i++) {
            NpcProfile old=f.service.status(ACTOR,2,SCOPE,NPC,oldRequest);
            assertEquals(original,old.getVoiceIntentId()); assertEquals("UNKNOWN",old.getVoiceIntentState());
            assertFalse(old.getVoiceSelected()); assertNull(old.getVoiceSample());
            assertEquals("voice-new",f.stored.getVoiceId());
        }
        assertEquals(2,f.service.history(ACTOR,2,SCOPE,NPC).size());
        assertTrue(f.service.history(ACTOR,2,SCOPE,NPC).stream().allMatch(row ->
                !row.containsKey("voiceSample")&&!row.containsKey("voiceId")&&!row.containsKey("actorMemberId")));
        verify(f.provider,times(2)).enroll(anyString());
    }

    @Test void otherActorCannotReadOriginalMetadataReplayOrConfirmAndItsHistoryHasNoOriginal() {
        Fixture f = new Fixture(4); Long original = unknown(f);
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> f.service.status(99L,4,SCOPE,NPC,f.row().getRequestId()));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class, () -> f.service.enroll(99L,4,SCOPE,NPC,SAMPLE,
                NEW,original,true,()->"never-sign"));
        assertTrue(f.service.history(99L,4,SCOPE,NPC).isEmpty());
        verify(f.provider,times(1)).enroll(anyString()); assertEquals(1,f.rows.size());
    }

    @Test void oldInFlightResultArrivingAfterExplicitNewRequestOnlyCompletesTheOldIntent() throws Exception {
        Fixture f = new Fixture(2); CountDownLatch sent=new CountDownLatch(1), release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i -> {
            if(calls.incrementAndGet()==1) {sent.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));return "voice-old-late";}
            return "voice-new";
        }).when(f.provider).enroll(anyString());
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<NpcProfile> old=worker.submit(f::enroll); assertTrue(sent.await(5,TimeUnit.SECONDS));
            Long original=f.row().getId(); assertEquals("IN_FLIGHT",f.row().getState());
            independent(f,NEW,original,true,SAMPLE); release.countDown();
            assertFalse(old.get(5,TimeUnit.SECONDS).getVoiceSelected());
            assertEquals("voice-old-late",f.row().getVoiceId()); assertEquals("READY",f.row().getState());
            assertEquals("voice-new",f.stored.getVoiceId()); assertEquals("voice-new",f.rows.get(NEW).getVoiceId());
            verify(f.provider,times(2)).enroll(anyString());
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
