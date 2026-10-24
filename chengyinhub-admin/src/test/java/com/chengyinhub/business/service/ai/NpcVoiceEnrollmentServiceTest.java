package com.chengyinhub.business.service.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.NpcVoiceEnrollment;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.NpcVoiceEnrollmentMapper;
import com.chengyinhub.common.utils.http.HttpRequestException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controlled transaction/mapper doubles, not MySQL or a real Spring transaction acceptance claim. */
@Timeout(20)
public class NpcVoiceEnrollmentServiceTest
{
    public static final String SAMPLE = "media-asset:73";
    public static final Long NPC = 555L, SCOPE = 31L, ACTOR = 88L;

    public static class Fixture
    {
        public final NpcVoiceEnrollmentMapper intents = mock(NpcVoiceEnrollmentMapper.class);
        public final NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        public final VoiceCloneClient provider = mock(VoiceCloneClient.class);
        public final ReentrantLock lock = new ReentrantLock();
        public NpcProfile stored = new NpcProfile();
        public Map<String,NpcVoiceEnrollment> rows = new HashMap<>();
        public final AtomicInteger commits = new AtomicInteger(), attempts = new AtomicInteger(), signatures = new AtomicInteger();
        public final NpcVoiceEnrollmentService service;
        private long nextId = 1;
        private NpcProfile backupProfile;
        private Map<String,NpcVoiceEnrollment> backupRows;
        public boolean failComplete;

        public Fixture(int scopeType)
        {
            stored.setId(NPC); stored.setScopeType(scopeType); stored.setScopeId(SCOPE); stored.setVoiceStatus(0);
            PlatformTransactionManager transactions = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
                    lock.lock(); backupProfile=copy(stored); backupRows=new HashMap<>();
                    rows.forEach((hash,row)->backupRows.put(hash,copy(row)));
                    TransactionSynchronizationManager.setActualTransactionActive(true);return new SimpleTransactionStatus();
                }
                public void commit(TransactionStatus status) {
                    commits.incrementAndGet();TransactionSynchronizationManager.setActualTransactionActive(false);lock.unlock();
                }
                public void rollback(TransactionStatus status) {
                    stored=backupProfile;rows=backupRows;TransactionSynchronizationManager.setActualTransactionActive(false);lock.unlock();
                }
            };
            when(intents.lockProfile(anyLong(),anyInt(),anyLong())).thenAnswer(i->
                    stored.getId().equals(i.getArgument(0)) && stored.getScopeType().equals(i.getArgument(1))
                    && stored.getScopeId().equals(i.getArgument(2)) ? copy(stored) : null);
            when(intents.find(anyLong(),anyString())).thenAnswer(i->copy(rows.get(i.getArgument(1))));
            when(intents.findRequest(anyLong(),anyString())).thenAnswer(i->copy(rows.get(i.getArgument(1))));
            when(intents.findById(anyLong(),anyLong())).thenAnswer(i->rows.values().stream()
                    .filter(r->r.getId().equals(i.getArgument(1))).findFirst().map(Fixture::copy).orElse(null));
            when(intents.findLatest(anyLong())).thenAnswer(i->rows.values().stream()
                    .max(java.util.Comparator.comparing(NpcVoiceEnrollment::getId)).map(Fixture::copy).orElse(null));
            when(intents.history(anyLong(),anyLong())).thenAnswer(i->rows.values().stream()
                    .filter(r->r.getActorMemberId().equals(i.getArgument(1)))
                    .sorted(java.util.Comparator.comparing(NpcVoiceEnrollment::getId).reversed()).limit(20).map(Fixture::copy).toList());
            when(intents.findUnresolved(NPC)).thenAnswer(i->rows.values().stream()
                    .filter(r->r.getState().equals("IN_FLIGHT")||r.getState().equals("UNKNOWN")).findFirst().map(Fixture::copy).orElse(null));
            when(intents.insert(any())).thenAnswer(i->{NpcVoiceEnrollment row=i.getArgument(0);
                assertFalse(rows.containsKey(key(row)));row.setId(nextId++);rows.put(key(row),copy(row));return 1;});
            when(intents.claim(anyLong())).thenAnswer(i->{NpcVoiceEnrollment row=rows.values().stream()
                .filter(r->r.getId().equals(i.getArgument(0))).findFirst().orElseThrow();
                if(!row.getState().equals("PREPARED"))return 0;row.setState("IN_FLIGHT");return 1;});
            when(intents.complete(any())).thenAnswer(i->{if(failComplete)throw new IllegalStateException("controlled DB failure");
                NpcVoiceEnrollment row=i.getArgument(0);rows.put(key(row),copy(row));return 1;});
            when(profiles.updateMerchantNpcVoice(any())).thenAnswer(i->write(i.getArgument(0)));
            when(profiles.updateNodeNpcVoice(any())).thenAnswer(i->write(i.getArgument(0)));
            when(provider.usable()).thenReturn(true);
            when(provider.enroll(anyString())).thenAnswer(i->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(commits.get()>=2);assertEquals("IN_FLIGHT",rows.get(NpcVoiceEnrollmentService.fingerprint(SAMPLE)).getState());
                attempts.incrementAndGet();return "voice-original";});
            when(provider.isReady(anyString())).thenReturn(true);
            service=new NpcVoiceEnrollmentService(intents,profiles,provider,transactions);
        }
        private int write(NpcProfile change) {
            if(!stored.getVoiceVersion().equals(change.getVoiceVersion()))return 0;
            stored=copy(change);stored.setVoiceVersion(change.getVoiceVersion()+1);return 1;
        }
        private static String key(NpcVoiceEnrollment row) { return row.getRequestId().startsWith("sample:") ? row.getSampleSha256() : row.getRequestId(); }
        public NpcProfile enroll() {return service.enroll(ACTOR,stored.getScopeType(),SCOPE,NPC,SAMPLE,
                ()->{signatures.incrementAndGet();return "approved-private-provider-input";});}
        public NpcProfile status() {return service.status(stored.getScopeType(),SCOPE,NPC);}
        public NpcVoiceEnrollment row() {return rows.get(NpcVoiceEnrollmentService.fingerprint(SAMPLE));}
        public void reset() {stored.setVoiceId(null);stored.setVoiceStatus(0);stored.setVoiceVersion(stored.getVoiceVersion()+1);}
        public static NpcProfile copy(NpcProfile value) {if(value==null)return null;NpcProfile result=new NpcProfile();BeanUtils.copyProperties(value,result);return result;}
        public static NpcVoiceEnrollment copy(NpcVoiceEnrollment value) {if(value==null)return null;NpcVoiceEnrollment result=new NpcVoiceEnrollment();BeanUtils.copyProperties(value,result);return result;}
    }

    @Test void readyResultReplaysSameIdentityAndNeverSignsOrEnrollsTwice() {
        for(int scope:new int[]{2,4}) {
            Fixture f=new Fixture(scope);NpcProfile first=f.enroll();
            assertEquals(2,first.getVoiceStatus());assertEquals("READY",first.getVoiceIntentState());
            assertEquals("voice-original",f.row().getVoiceId());Long id=f.row().getId();
            assertEquals(id,f.enroll().getVoiceIntentId());assertEquals(id,f.status().getVoiceIntentId());
            assertEquals(1,f.attempts.get());assertEquals(1,f.signatures.get());
            assertEquals(SAMPLE,f.row().getSampleReference());assertEquals(ACTOR,f.row().getActorMemberId());
        }
    }

    @Test void dispatchedTimeoutIsUnknownAndRepeatedEnrollmentCannotResendOrEraseOriginalSample() {
        Fixture f=new Fixture(2);f.stored.setVoiceId("prior-known-id");
        doThrow(new AiServiceException("unconfirmed",new java.net.SocketTimeoutException())).when(f.provider).enroll(anyString());
        assertEquals("UNKNOWN",f.enroll().getVoiceIntentState());
        Long id=f.row().getId();assertEquals(1,f.stored.getVoiceStatus());assertEquals("prior-known-id",f.stored.getVoiceId());
        assertEquals(id,f.enroll().getVoiceIntentId());assertEquals("UNKNOWN",f.status().getVoiceIntentState());
        assertEquals(SAMPLE,f.stored.getVoiceSample());verify(f.provider,times(1)).enroll(anyString());verify(f.provider,never()).isReady(anyString());
    }

    @Test void onlyTrustedNotSentProofCanProduceNotSentAndItStillDoesNotReleaseTheIdentity() {
        Fixture f=new Fixture(4);
        doThrow(new AiServiceException("local refusal",new AiProviderNotSentException())).when(f.provider).enroll(anyString());
        NpcProfile result=f.enroll();assertEquals("NOT_SENT",result.getVoiceIntentState());assertEquals(3,result.getVoiceStatus());
        assertEquals("VOICE_PROVIDER_NOT_SENT",result.getVoiceReasonCode());assertEquals(result.getVoiceIntentId(),f.enroll().getVoiceIntentId());
        verify(f.provider,times(1)).enroll(anyString());
    }

    @Test void statusHttpFailureGenericRuntimeAndUntrustedCauseAreNeverNotSent() {
        for(RuntimeException failure:new RuntimeException[]{new AiServiceException("local not sent words"),
                new AiServiceException("HTTP status",new HttpRequestException("failure",503,true)),
                new IllegalStateException("runtime",new AiProviderNotSentException())}) {
            Fixture f=new Fixture(2);doThrow(failure).when(f.provider).enroll(anyString());
            assertEquals("UNKNOWN",f.enroll().getVoiceIntentState());assertEquals("UNKNOWN",f.row().getState());
        }
    }

    @Test void knownVoiceIsDurableBeforeQueryFailureAndPollingOnlyQueriesTheSameId() {
        Fixture f=new Fixture(4);doAnswer(i->{
            assertEquals("PENDING",f.row().getState());assertEquals("voice-original",f.row().getVoiceId());
            throw new AiServiceException("query response lost");}).when(f.provider).isReady("voice-original");
        assertEquals("PENDING",f.enroll().getVoiceIntentState());assertEquals("voice-original",f.status().getVoiceId());
        doReturn(true).when(f.provider).isReady("voice-original");
        assertEquals("READY",f.status().getVoiceIntentState());assertEquals(2,f.stored.getVoiceStatus());
        assertEquals(1,f.attempts.get());verify(f.provider,times(3)).isReady("voice-original");
    }

    @Test void resetDuringCreateCannotDiscardDurableVoiceFactOrResurrectTheProfile() {
        Fixture f=new Fixture(2);doAnswer(i->{f.reset();return "voice-original";}).when(f.provider).enroll(anyString());
        NpcProfile result=f.enroll();assertEquals(0,result.getVoiceStatus());assertEquals("VOICE_PROFILE_CHANGED",result.getVoiceReasonCode());assertNull(f.stored.getVoiceId());
        assertEquals("voice-original",f.row().getVoiceId());assertEquals("READY",f.row().getState());
        assertEquals(0,f.enroll().getVoiceStatus());verify(f.provider,times(1)).enroll(anyString());
    }

    @Test void resetDuringFailedOrPendingQueryReturnsCurrentProfileWithoutLosingKnownIntentId() {
        for (boolean failed : new boolean[]{false,true}) {
            Fixture f=new Fixture(4);
            doAnswer(i->{f.reset();if(failed)throw new AiServiceException("query unavailable");return false;})
                    .when(f.provider).isReady("voice-original");
            NpcProfile result=f.enroll();
            assertEquals(0,result.getVoiceStatus());assertNull(result.getVoiceId());
            assertEquals("PENDING",result.getVoiceIntentState());assertEquals("VOICE_PROFILE_CHANGED",result.getVoiceReasonCode());
            assertEquals("voice-original",f.row().getVoiceId());Long intent=f.row().getId();
            assertEquals(intent,f.enroll().getVoiceIntentId());assertEquals(0,f.stored.getVoiceStatus());
            verify(f.provider,times(1)).enroll(anyString());verify(f.provider,times(1)).isReady("voice-original");
        }
    }

    @Test void resetAndDifferentSampleDoNotMakeUnconfirmedExternalAttemptSafeToRepeat() {
        Fixture f=new Fixture(4);doThrow(new AiServiceException("lost")).when(f.provider).enroll(anyString());
        f.enroll();Long original=f.row().getId();f.reset();assertEquals(original,f.enroll().getVoiceIntentId());
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.service.enroll(ACTOR,4,SCOPE,NPC,"media-asset:74",()->"unreachable"));
        verify(f.provider,times(1)).enroll(anyString());assertEquals("UNKNOWN",f.row().getState());
    }

    @Test void crashAfterDurableClaimLeavesInFlightAndReplaysNeverDispatch() {
        Fixture f=new Fixture(2);doThrow(new AssertionError("controlled crash")).when(f.provider).enroll(anyString());
        assertThrows(AssertionError.class,f::enroll);assertEquals("IN_FLIGHT",f.row().getState());
        assertEquals("IN_FLIGHT",f.enroll().getVoiceIntentState());verify(f.provider,times(1)).enroll(anyString());
    }

    @Test void resultCommitFailureRollsBackProfileButDoesNotReopenTheAlreadyCommittedClaim() {
        Fixture f=new Fixture(2);f.failComplete=true;
        assertThrows(NpcVoiceEnrollmentService.StateUnavailableException.class,f::enroll);
        assertEquals("IN_FLIGHT",f.row().getState());assertNull(f.stored.getVoiceId());assertEquals(1,f.stored.getVoiceStatus());
        assertEquals("IN_FLIGHT",f.enroll().getVoiceIntentState());assertEquals(1,f.attempts.get());
    }

    @Test void securityRefusalIsBeforeAnyClaimOrProfileWrite() {
        Fixture f=new Fixture(2);Supplier<String> rejected=()->{throw new com.chengyinhub.common.exception.ServiceException("forbidden",403);};
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                ()->f.service.enroll(ACTOR,2,SCOPE,NPC,SAMPLE,rejected));
        assertTrue(f.rows.isEmpty());assertEquals(0L,f.stored.getVoiceVersion());verify(f.provider,never()).enroll(anyString());
        verify(f.intents,never()).insert(any());verifyNoInteractions(f.profiles);
    }

    @Test void wrongScopeOrInvalidIdentityAndBusinessTransactionCannotReachProvider() {
        Fixture f=new Fixture(2);
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.service.enroll(ACTOR,4,SCOPE,NPC,SAMPLE,()->"unused"));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.service.enroll(ACTOR,2,99L,NPC,SAMPLE,()->"unused"));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.service.enroll(ACTOR,2,SCOPE,NPC,"https://raw-input.invalid",()->"unused"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(NpcVoiceEnrollmentService.Rejected.class,f::enroll);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verify(f.provider,never()).enroll(anyString());assertTrue(f.rows.isEmpty());
    }

    @Test void databaseFailureIsUnavailableNotAbsentAndCannotCreateAnotherIdentity() {
        Fixture f=new Fixture(4);doThrow(new IllegalStateException("DB disconnected")).when(f.intents).find(anyLong(),anyString());
        assertThrows(NpcVoiceEnrollmentService.StateUnavailableException.class,f::enroll);verify(f.provider,never()).enroll(anyString());
        assertTrue(f.rows.isEmpty());
    }

    @Test void historicalFailedSampleIsUnknownNotEvidenceForAnotherEnrollment() {
        Fixture f=new Fixture(2);f.stored.setVoiceSample(SAMPLE);f.stored.setVoiceStatus(3);
        assertEquals("UNKNOWN",f.enroll().getVoiceIntentState());assertEquals("VOICE_LEGACY_UNBOUND",f.status().getVoiceReasonCode());
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.service.enroll(ACTOR,2,SCOPE,NPC,"media-asset:74",()->"unused"));
        verify(f.provider,never()).enroll(anyString());assertTrue(f.rows.isEmpty());
    }

    @Test void concurrentSameSampleLoserSeesCommittedInFlightAndNeverCallsProvider() throws Exception {
        Fixture f=new Fixture(4);CountDownLatch dispatched=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            dispatched.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "voice-original";}).when(f.provider).enroll(anyString());
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<NpcProfile> winner=executor.submit(f::enroll);assertTrue(dispatched.await(5,TimeUnit.SECONDS));
            assertEquals("IN_FLIGHT",f.enroll().getVoiceIntentState());assertEquals(1,f.rows.size());
            release.countDown();assertEquals("READY",winner.get(5,TimeUnit.SECONDS).getVoiceIntentState());
            verify(f.provider,times(1)).enroll(anyString());
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
