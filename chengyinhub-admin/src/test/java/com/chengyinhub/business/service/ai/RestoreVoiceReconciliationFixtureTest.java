package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.NpcVoiceEnrollment;
import com.chengyinhub.business.service.RestoreProviderJournal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual enrollment owner with the existing unit transaction/mapper model; not MySQL/durable restore evidence. */
class RestoreVoiceReconciliationFixtureTest {
    private record Event(String name,String operation,String state,Map<String,Object> identity) {}
    private static final class Harness {
        final NpcVoiceEnrollmentServiceTest.Fixture owner=new NpcVoiceEnrollmentServiceTest.Fixture(2);
        final JdbcTemplate db=mock(JdbcTemplate.class);
        final RestoreProviderJournal journal=mock(RestoreProviderJournal.class);
        final List<Event> events=new ArrayList<>();
        final long npc;
        final RestoreVoiceReconciliationFixture.FixtureVoiceProvider provider;
        Harness(long npc)throws Exception {
            this.npc=npc;owner.stored.setId(npc);
            String hash=NpcVoiceEnrollmentService.fingerprint(NpcVoiceEnrollmentServiceTest.SAMPLE);
            when(db.queryForMap(eq(RestoreVoiceReconciliationFixture.FixtureVoiceProvider.ORIGINAL_ROW),eq(npc),eq("sample:"+hash),eq(hash)))
                    .thenAnswer(call->{
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                        assertTrue(owner.commits.get()>=2);return row(owner.row());
                    });
            doAnswer(call->{
                assertEquals("VOICE",call.getArgument(0));
                Map<String,Object> identity=call.getArgument(4);
                events.add(new Event(call.getArgument(1),call.getArgument(2),call.getArgument(3),new TreeMap<>(identity)));return null;
            }).when(journal).append(eq("VOICE"),anyString(),anyString(),anyString(),anyMap());
            when(journal.original("VOICE","known_voice")).thenAnswer(call->new TreeMap<>(events.stream()
                    .filter(event->event.name().equals("known_voice")).findFirst().orElseThrow().identity()));
            when(journal.state(eq("VOICE"),anyString())).thenAnswer(call->events.stream()
                    .filter(event->event.name().equals(call.getArgument(1))).reduce((first,last)->last).orElseThrow().state());
            provider=new RestoreVoiceReconciliationFixture.FixtureVoiceProvider(db,journal,true);use(provider);
        }
        void use(RestoreVoiceReconciliationFixture.FixtureVoiceProvider client) {
            doReturn(true).when(owner.provider).usable();
            doAnswer(call->client.enroll(call.getArgument(0))).when(owner.provider).enroll(anyString());
            doAnswer(call->client.isReady(call.getArgument(0))).when(owner.provider).isReady(anyString());
        }
        NpcProfile enroll(){return owner.service.enroll(88L,2,31L,npc,"media-asset:73",()->RestoreVoiceReconciliationFixture.approvedInput(npc));}
        NpcProfile status(){return owner.service.status(88L,2,31L,npc,null);}
    }
    private static Map<String,Object> row(NpcVoiceEnrollment value) {
        assertNotNull(value);Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",value.getId());result.put("request_id",value.getRequestId());result.put("npc_id",value.getNpcId());
        result.put("actor_member_id",value.getActorMemberId());result.put("scope_type",value.getScopeType());result.put("scope_id",value.getScopeId());
        result.put("sample_sha256",value.getSampleSha256());result.put("sample_reference",value.getSampleReference());
        result.put("profile_version",value.getProfileVersion());result.put("state",value.getState());result.put("voice_id",value.getVoiceId());return result;
    }

    @Test void sameSampleAcrossTwoNpcsRetainsOneRequestLabelAndTwoScopedOriginals()throws Exception {
        // The existing model has one mapper/profile per NPC. Exercise the real
        // owner twice; this is key-generation/journal evidence, not MySQL proof.
        Harness known=new Harness(555),unknown=new Harness(556);
        NpcProfile ready=known.enroll(),unresolved=unknown.enroll();
        known.provider.requireState(RestoreVoiceReconciliationFixture.Operation.SOURCE_KNOWN,
                RestoreVoiceReconciliationFixture.IntentState.READY,ready,"original known result");
        unknown.provider.requireState(RestoreVoiceReconciliationFixture.Operation.SOURCE_UNKNOWN,
                RestoreVoiceReconciliationFixture.IntentState.UNKNOWN,unresolved,"original unknown result");
        String original="sample:"+NpcVoiceEnrollmentService.fingerprint("media-asset:73");
        assertEquals(original,ready.getVoiceRequestId());assertEquals(original,unresolved.getVoiceRequestId());
        Map<String,Object> first=known.events.get(0).identity(),second=unknown.events.get(0).identity();
        assertInstanceOf(String.class,first.get("request_id"));assertInstanceOf(String.class,second.get("request_id"));
        assertEquals(first.get("request_id"),second.get("request_id"));
        assertEquals(555L,((Number)first.get("npc_id")).longValue());
        assertEquals(556L,((Number)second.get("npc_id")).longValue());
        assertEquals(1L,((Number)first.get("profile_version")).longValue());
        assertEquals(1L,((Number)second.get("profile_version")).longValue());
        assertEquals(3L,known.owner.row().getProfileVersion().longValue());
        assertEquals(1L,unknown.owner.row().getProfileVersion().longValue());
        assertEquals(List.of("SUBMIT","RESULT","QUERY"),known.events.stream().map(Event::operation).toList());
        assertEquals(List.of("SUBMIT","RESULT"),unknown.events.stream().map(Event::operation).toList());
        assertNull(unknown.owner.row().getVoiceId());
        assertEquals("VOICE_PROVIDER_OUTCOME_UNKNOWN",unknown.owner.row().getReasonCode());
        assertEquals(1,known.provider.submits());assertEquals(1,known.provider.queries());
        assertEquals(1,unknown.provider.submits());assertEquals(0,unknown.provider.queries());
        verify(unknown.owner.provider,never()).isReady(anyString());
    }

    @Test void knownVoiceUsesCommittedClaimThenCommittedOriginalIdAndFreshNormalOwnerNeverDispatches()throws Exception {
        Harness f=new Harness(555);NpcProfile result=f.enroll();
        f.provider.requireState(RestoreVoiceReconciliationFixture.Operation.SOURCE_KNOWN,RestoreVoiceReconciliationFixture.IntentState.READY,result,"original provider-ready persisted");
        assertEquals("READY",f.owner.row().getState());assertEquals(2,result.getVoiceStatus());assertEquals(Boolean.TRUE,result.getVoiceSelected());
        assertEquals(RestoreVoiceReconciliationFixture.FixtureVoiceProvider.KNOWN_VOICE,f.owner.row().getVoiceId());
        assertEquals(List.of("SUBMIT","RESULT","QUERY"),f.events.stream().map(Event::operation).toList());
        Map<String,Object> admitted=f.events.get(0).identity();
        for(Event event:f.events)assertEquals(admitted,event.identity());
        assertEquals(((Number)admitted.get("profile_version")).longValue()+2,f.owner.row().getProfileVersion().longValue());
        assertEquals(1,f.provider.submits());assertEquals(1,f.provider.queries());
        String original=JSON.toJSONString(row(f.owner.row()),JSONWriter.Feature.MapSortField);
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.owner.service.status(99L,2,31L,555L,result.getVoiceRequestId()));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.owner.service.status(88L,2,32L,555L,null));
        assertThrows(NpcVoiceEnrollmentService.Rejected.class,()->f.owner.service.enroll(88L,4,31L,555L,"media-asset:73",()->"never-sign"));
        var normal=new RestoreVoiceReconciliationFixture.FixtureVoiceProvider(f.db,f.journal,false);f.use(normal);
        assertEquals(result.getVoiceIntentId(),f.enroll().getVoiceIntentId());assertEquals("READY",f.status().getVoiceIntentState());
        assertEquals(original,JSON.toJSONString(row(f.owner.row()),JSONWriter.Feature.MapSortField));
        assertEquals(0,normal.submits());assertEquals(0,normal.queries());verify(f.owner.provider,times(1)).enroll(anyString());
    }

    @Test void unqueryableOutcomeKeepsOriginalUnknownAndNeverSearchesOrReenrolls()throws Exception {
        Harness f=new Harness(556);NpcProfile unknown=f.enroll();
        f.provider.requireState(RestoreVoiceReconciliationFixture.Operation.SOURCE_UNKNOWN,RestoreVoiceReconciliationFixture.IntentState.UNKNOWN,unknown,"unqueryable original remains UNKNOWN");
        assertEquals("UNKNOWN",f.owner.row().getState());assertNull(f.owner.row().getVoiceId());assertEquals(1,unknown.getVoiceStatus());
        assertEquals("VOICE_PROVIDER_OUTCOME_UNKNOWN",f.owner.row().getReasonCode());
        assertEquals(List.of("SUBMIT","RESULT"),f.events.stream().map(Event::operation).toList());
        assertNull(f.events.get(0).identity().get("voiceId"));assertEquals(f.events.get(0).identity(),f.events.get(1).identity());
        assertEquals(unknown.getVoiceIntentId(),f.enroll().getVoiceIntentId());assertEquals("UNKNOWN",f.status().getVoiceIntentState());
        assertEquals(1,f.provider.submits());assertEquals(0,f.provider.queries());verify(f.owner.provider,never()).isReady(anyString());
    }

    @Test void fixtureQueryFailureKeepsPendingIdAndIsNotSilentlyRelabeledAsProviderUnknown()throws Exception {
        Harness f=new Harness(555);String canary="provider-text-and-private-audio-canary";
        doThrow(new IllegalStateException(canary)).when(f.journal).original("VOICE","known_voice");
        NpcProfile result=f.enroll();assertEquals("PENDING",result.getVoiceIntentState());
        assertEquals(RestoreVoiceReconciliationFixture.FixtureVoiceProvider.KNOWN_VOICE,f.owner.row().getVoiceId());
        var failure=assertThrows(RestoreVoiceReconciliationFixture.VoiceFixtureFailure.class,()->f.provider.requireState(
            RestoreVoiceReconciliationFixture.Operation.SOURCE_KNOWN,RestoreVoiceReconciliationFixture.IntentState.READY,result,"original provider-ready persisted"));
        assertEquals("QUERY_JOURNAL",failure.observation().get("providerStep"));assertEquals("PENDING",failure.observation().get("intentState"));
        assertFalse(JSON.toJSONString(failure.observation()).contains(canary));assertEquals(1,f.provider.submits());
    }

    @Test void failedClaimReadCannotInventReadyOrExternalFacts()throws Exception {
        Harness f=new Harness(555);String hash=NpcVoiceEnrollmentService.fingerprint("media-asset:73");
        doThrow(new IllegalStateException("controlled missing original row")).when(f.db).queryForMap(
            RestoreVoiceReconciliationFixture.FixtureVoiceProvider.ORIGINAL_ROW,555L,"sample:"+hash,hash);
        NpcProfile result=f.enroll();assertEquals("UNKNOWN",result.getVoiceIntentState());assertTrue(f.events.isEmpty());
        var failure=assertThrows(RestoreVoiceReconciliationFixture.VoiceFixtureFailure.class,()->f.provider.requireState(
            RestoreVoiceReconciliationFixture.Operation.SOURCE_KNOWN,RestoreVoiceReconciliationFixture.IntentState.READY,result,"original provider-ready persisted"));
        assertEquals("SUBMIT_ROW",failure.observation().get("providerStep"));assertEquals(0,f.provider.submits());
    }

    @Test void originalIdentityIsStableThroughTheActualJournalJsonShapeAndObservationIsFinite() {
        Map<String,Object> original=new LinkedHashMap<>();original.put("id",1L);original.put("request_id","sample:"+"a".repeat(64));
        original.put("npc_id",555L);original.put("actor_member_id",88L);original.put("scope_type",2);original.put("scope_id",31L);
        original.put("sample_sha256","a".repeat(64));original.put("sample_reference","media-asset:73");original.put("profile_version",1L);
        Map<String,Object> identity=RestoreVoiceReconciliationFixture.identity(original,RestoreVoiceReconciliationFixture.FixtureVoiceProvider.KNOWN_VOICE);
        assertEquals(new ArrayList<>(new TreeSet<>(identity.keySet())),new ArrayList<>(identity.keySet()));
        var decoded=JSON.parseObject(JSON.toJSONString(identity,JSONWriter.Feature.WriteMapNullValue));
        assertEquals(JSON.toJSONString(new LinkedHashMap<>(decoded),JSONWriter.Feature.MapSortField),JSON.toJSONString(decoded,JSONWriter.Feature.MapSortField));
        assertEquals(1L,((Number)decoded.get("profile_version")).longValue());
        NpcProfile profile=new NpcProfile();profile.setVoiceIntentState("private-provider-payload");profile.setVoiceStatus(99);
        var failure=new RestoreVoiceReconciliationFixture.VoiceFixtureFailure(RestoreVoiceReconciliationFixture.Operation.SOURCE_KNOWN,
            RestoreVoiceReconciliationFixture.IntentState.READY,profile,RestoreVoiceReconciliationFixture.ProviderStep.NONE,null);
        assertEquals(Set.of("operation","expected","intentState","profileState","selection","providerStep"),failure.observation().keySet());
        assertEquals("UNREVIEWED",failure.observation().get("intentState"));assertEquals("UNREVIEWED",failure.observation().get("profileState"));
        assertFalse(JSON.toJSONString(failure.observation()).contains("private-provider-payload"));
    }
}
