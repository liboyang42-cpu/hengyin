package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy;
import com.chengyinhub.business.service.support.ThemeFirstIdentity;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual first-start service flow; mapper boundaries are synthetic, not a MySQL transaction test. */
class ThemeFirstIdentityContractTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final class Fixture {
        final TopicRouteSessionMapper sessions=mock(TopicRouteSessionMapper.class);
        final CmsTopicMapper topics=mock(CmsTopicMapper.class);
        final CmsTopicNodeMapper nodes=mock(CmsTopicNodeMapper.class);
        final CmsMemberTemplateMapper templates=mock(CmsMemberTemplateMapper.class);
        final PlayerNodeProgressMapper progress=mock(PlayerNodeProgressMapper.class);
        final CmsActivityMapper activities=mock(CmsActivityMapper.class);
        final CmsRegistrationMapper registrations=mock(CmsRegistrationMapper.class);
        final CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class);
        final AtomicReference<TopicRouteSession> current=new AtomicReference<>(), first=new AtomicReference<>();
        final AtomicLong ids=new AtomicLong(30);
        final CmsTopic topic=new CmsTopic();
        final ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();
        final TopicRouteRuntimeServiceImpl runtime;
        Fixture(boolean theme) {
            topic.setId(10L);topic.setDelFlag(0);topic.setRouteMode("LINEAR");topic.setPublishedConfigVersion(1L);
            topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":6}}");
            if(theme)topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":3,\"replayEnabled\":true,\"items\":[]}");
            when(topics.selectCmsTopicById(10L)).thenReturn(topic);
            List<CmsTopicNode> live=new ArrayList<>();
            for(long id=11;id<=12;id++){CmsTopicNode n=new CmsTopicNode();n.setId(id);n.setTopicId(10L);n.setName("node"+id);n.setStatus(1L);n.setSortId(id);live.add(n);}
            when(nodes.selectCmsTopicNodeList(any())).thenAnswer(call->{CmsTopicNode p=call.getArgument(0);assertEquals(Long.valueOf(10),p.getTopicId());assertEquals(Long.valueOf(1),p.getStatus());return live;});
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
            when(registrations.selectCmsRegistrationList(any())).thenAnswer(call->{CmsRegistration p=call.getArgument(0);
                return Long.valueOf(7).equals(p.getMemberId()) && Long.valueOf(1).equals(p.getOwnerType()) && Long.valueOf(10).equals(p.getOwnerId()) && Integer.valueOf(2).equals(p.getRegistrationStatus())
                        ? Collections.singletonList(new CmsRegistration()) : Collections.emptyList();});
            when(sessions.selectCurrentByOwner(0L,10L,7L)).thenAnswer(call->current.get());
            when(sessions.selectCurrentByOwnerForUpdate(0L,10L,7L)).thenAnswer(call->current.get());
            when(sessions.selectByOwner(eq(0L),eq(10L),eq(7L),anyString())).thenAnswer(call->{TopicRouteSession row=first.get();return row!=null && row.getPlayInstanceId().equals(call.getArgument(3))?row:null;});
            when(sessions.selectByOwnerForUpdate(eq(0L),eq(10L),eq(7L),anyString())).thenAnswer(call->{TopicRouteSession row=first.get();return row!=null && row.getPlayInstanceId().equals(call.getArgument(3))?row:null;});
            when(sessions.selectAccessibleById(anyLong(),eq(7L))).thenAnswer(call->{long id=call.getArgument(0);TopicRouteSession row=first.get();return row!=null && row.getId()==id?row:null;});
            when(sessions.insertSession(any())).thenAnswer(call->store(call.getArgument(0)));
            when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);
                assertEquals(Long.valueOf(7),p.getMemberId());assertEquals(Long.valueOf(10),p.getTopicId());assertEquals(Long.valueOf(0),p.getActivityId());assertEquals("default",p.getPlayInstanceId());
                if(theme)assertNotNull(first.get(),"New theme must persist its own session before any current-progress read");
                PlayerNodeProgress old=new PlayerNodeProgress();old.setMemberId(7L);old.setTopicId(10L);old.setActivityId(0L);old.setNodeId(11L);old.setStatus(1);old.setPlayInstanceId("default");return Collections.singletonList(old);});
            runtime=new TopicRouteRuntimeServiceImpl(sessions,topics,nodes,templates,progress,activities,registrations,null,null,()->123L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
            UmsMemberMapper firstMembers=mock(UmsMemberMapper.class);when(firstMembers.lockMemberById(7L)).thenReturn(7L);ReflectionTestUtils.setField(runtime,"originalAwardMemberMapper",firstMembers);
            when(sessions.selectCurrentCandidatesByOwners(anyList())).thenAnswer(call->current.get()==null?Collections.emptyList():Collections.singletonList(current.get()));
            ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);ReflectionTestUtils.setField(runtime,"cmsTopicChapterMapper",chapters);
        }
        int store(TopicRouteSession s){s.setId(ids.incrementAndGet());if(!first.compareAndSet(null,s))throw new DuplicateKeyException("synthetic exact owner/instance unique key");current.set(s);return 1;}
        TopicRouteState start(){return runtime.state(7L,10L,0L);}
    }
    @Test void firstThemeStartSucceedsWithoutInheritingOldDefaultCompletion() throws Exception {
        Fixture f=new Fixture(true);TopicRouteState state=f.start();
        assertNotNull(state.getSessionId());assertEquals(Long.valueOf(11),state.getCurrentNodeId());assertEquals(TopicRouteState.PLAYABLE,state.getNodeStates().get(11L));
        TopicRouteSession row=f.first.get();assertEquals("theme-first:10:0",row.getPlayInstanceId());assertTrue(ThemeFirstIdentity.isFirst(row));assertEquals(Integer.valueOf(0),row.getVersion());
        assertTrue(JSON.readTree(row.getStateJson()).path("completedNodeIds").isArray());assertEquals(0,JSON.readTree(row.getStateJson()).path("completedNodeIds").size());assertTrue(JSON.readTree(row.getConfigSnapshotJson()).path("themeConfig").isObject());
        verify(f.sessions,times(1)).insertSession(any());
    }
    @Test void branchFirstStartUsesTheSamePersistedOriginAndIgnoresDefaultProgress() {
        Fixture f=new Fixture(true);f.topic.setRouteMode("BRANCH_GRAPH");
        f.topic.setPublishedRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":11,\"terminalNodeIds\":[12],\"edges\":[{\"id\":\"go\",\"fromNodeId\":11,\"toNodeId\":12,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        TopicRouteState state=f.start();assertEquals(11L,state.getCurrentNodeId());assertEquals("theme-first:10:0",f.first.get().getPlayInstanceId());assertTrue(ThemeFirstIdentity.isFirst(f.first.get()));verifyNoInteractions(f.progress);assertEquals(state.getSessionId(),f.start().getSessionId());verify(f.sessions,times(1)).insertSession(any());
    }
    @Test void activityFirstIdentityNeverFallsBackToSelfPlay() {
        Fixture f=new Fixture(true);CmsActivity activity=new CmsActivity();activity.setId(5L);activity.setTopicId(10L);activity.setStatus(1L);activity.setDelFlag(0);when(f.activities.selectCmsActivityById(5L)).thenReturn(activity);
        doAnswer(call->{CmsRegistration p=call.getArgument(0);return Long.valueOf(7).equals(p.getMemberId())&&Long.valueOf(2).equals(p.getOwnerType())&&Long.valueOf(5).equals(p.getOwnerId())?Collections.singletonList(new CmsRegistration()):Collections.emptyList();}).when(f.registrations).selectCmsRegistrationList(any());
        when(f.sessions.selectCurrentByOwner(5L,10L,7L)).thenAnswer(call->f.current.get());when(f.sessions.selectCurrentByOwnerForUpdate(5L,10L,7L)).thenAnswer(call->f.current.get());
        TopicRouteState state=f.runtime.state(7L,10L,5L);assertEquals(11L,state.getCurrentNodeId());assertEquals("theme-first:10:5",f.first.get().getPlayInstanceId());assertTrue(ThemeFirstIdentity.isFirst(f.first.get()));assertThrows(IllegalStateException.class,()->f.runtime.state(7L,10L,0L));verify(f.sessions,times(1)).insertSession(any());
    }
    @Test void publicThemeViewCanCreateTheFirstRunWithoutAPreseededSession() {
        Fixture f=new Fixture(true);Map<String,Object> view=f.runtime.themeView(7L,10L,0L,null);
        assertEquals(f.first.get().getId(),view.get("sessionId"));assertEquals("theme-first:10:0",view.get("playInstanceId"));assertEquals(Integer.valueOf(0),view.get("version"));
    }
    @Test void sameStartRetryKeepsFrozenStateVersionAndSessionIdentity() throws Exception {
        Fixture f=new Fixture(true);TopicRouteState before=f.start();TopicRouteSession stored=f.first.get();
        com.fasterxml.jackson.databind.node.ObjectNode played=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(stored.getStateJson());played.with("variables").put("sys.hp",3);stored.setStateJson(JSON.writeValueAsString(played));
        String state=stored.getStateJson(),snapshot=stored.getConfigSnapshotJson();
        stored.setVersion(4);f.topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":99,\"items\":[]}");
        TopicRouteState retry=f.start();assertEquals(before.getSessionId(),retry.getSessionId());assertEquals(Integer.valueOf(4),retry.getVersion());assertEquals(state,stored.getStateJson());assertEquals(snapshot,stored.getConfigSnapshotJson());verify(f.sessions,times(1)).insertSession(any());
    }
    @Test void competingFirstStartsReturnTheSameUniqueWinner() throws Exception {
        Fixture f=new Fixture(true);CyclicBarrier barrier=new CyclicBarrier(2);
        doAnswer(call->{barrier.await(10,TimeUnit.SECONDS);return f.store(call.getArgument(0));}).when(f.sessions).insertSession(any());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<TopicRouteState> a=pool.submit(f::start),b=pool.submit(f::start);int winners=0,conflicts=0;
            for(Future<TopicRouteState> result:Arrays.asList(a,b))try{TopicRouteState resultState=result.get(15,TimeUnit.SECONDS);assertEquals(f.first.get().getId(),resultState.getSessionId());winners++;}catch(ExecutionException conflict){assertTrue(conflict.getCause() instanceof ServiceException);assertEquals(409,((ServiceException)conflict.getCause()).getCode());conflicts++;}
            assertEquals(1,winners);assertEquals(1,conflicts);assertEquals(f.first.get().getId(),f.start().getSessionId());}
        finally{pool.shutdownNow();}
        verify(f.sessions,times(2)).insertSession(any());assertEquals(Integer.valueOf(0),f.first.get().getVersion());
    }
    @Test void winnerAppearingAfterMemberBarrierRequiresRetryBeforeAnyNewRouteAcquisition() {
        Fixture f=new Fixture(true);f.start();TopicRouteSession winner=f.first.get();f.current.set(null);f.first.set(null);clearInvocations(f.sessions);
        UmsMemberMapper members=(UmsMemberMapper)ReflectionTestUtils.getField(f.runtime,"originalAwardMemberMapper");
        when(members.lockMemberById(7L)).thenAnswer(call->{f.first.set(winner);f.current.set(winner);return 7L;});
        assertEquals(409,assertThrows(ServiceException.class,f::start).getCode());verify(f.sessions,never()).insertSession(any());verify(f.sessions,never()).selectCurrentByOwnerForUpdate(anyLong(),anyLong(),anyLong());
        assertEquals(winner.getId(),f.start().getSessionId());
    }
    @Test void firstStartRejectsActualOuterRepeatableReadRatherThanPretendingAnnotationChangesIt() {
        Fixture f=new Fixture(true);org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:first_start_isolation_"+UUID.randomUUID(),"sa","");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->tx.execute(status->f.start()));verify(f.sessions,never()).insertSession(any());tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);assertNotNull(tx.execute(status->f.start()).getSessionId());
    }
    @Test void firstStartStillRequiresFeatureEntitlementAndExactScope() {
        Fixture f=new Fixture(true);assertThrows(IllegalStateException.class,()->f.runtime.state(8L,10L,0L));assertThrows(IllegalArgumentException.class,()->f.runtime.state(7L,10L,5L));
        ReflectionTestUtils.setField(f.policy,"enabled",false);assertThrows(ServiceException.class,f::start);verify(f.sessions,never()).insertSession(any());
    }
    @Test void unstartedThemeReadAndWriteSelectorsStillRejectWithoutCreatingAnything() {
        Fixture f=new Fixture(true);
        assertThrows(ServiceException.class,()->f.runtime.playInstance(7L,10L,0L));assertThrows(ServiceException.class,()->f.runtime.assertCurrentPlayInstance(7L,10L,0L,"default"));
        verify(f.sessions,never()).insertSession(any());verifyNoInteractions(f.progress);
    }
    @Test void nonThemeJourneyStillSeedsExistingOwnedDefaultCompletion() throws Exception {
        Fixture f=new Fixture(false);TopicRouteState state=f.start();assertEquals(Long.valueOf(12),state.getCurrentNodeId());assertEquals(TopicRouteState.COMPLETED,state.getNodeStates().get(11L));
        assertEquals(1,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").size());assertEquals(11L,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").get(0).asLong());assertFalse(JSON.readTree(f.first.get().getConfigSnapshotJson()).has("themeConfig"));
    }
    @Test void currentReplayIsNeverResetAndHistoricalSnapshotRemainsReadOnly() {
        Fixture f=new Fixture(true);f.start();TopicRouteSession old=f.first.get();String frozen=old.getStateJson();
        TopicRouteSession replay=new TopicRouteSession();org.springframework.beans.BeanUtils.copyProperties(old,replay);replay.setId(80L);replay.setPlayInstanceId("replay:80");replay.setVersion(6);f.current.set(replay);
        when(f.progress.selectPlayerNodeProgressListForInstance(any(),eq("replay:80"))).thenReturn(Collections.emptyList());
        assertEquals(Long.valueOf(80),f.start().getSessionId());assertEquals(Integer.valueOf(6),replay.getVersion());assertEquals(frozen,replay.getStateJson());
        assertEquals(old.getId(),f.runtime.themeView(7L,10L,0L,old.getId()).get("sessionId"));assertEquals(frozen,old.getStateJson());
        assertThrows(ServiceException.class,()->f.runtime.assertCurrentPlayInstance(7L,10L,0L,"default"));verify(f.sessions,times(1)).insertSession(any());verify(f.sessions,never()).updateStateCas(any());
    }
    @Test void foreignCurrentTupleCannotBeReusedByFirstStart() {
        Fixture f=new Fixture(true);TopicRouteSession foreign=new TopicRouteSession();foreign.setId(31L);foreign.setMemberId(8L);foreign.setTopicId(10L);foreign.setActivityId(0L);foreign.setPlayInstanceId("default");foreign.setConfigSnapshotJson("{\"themeConfig\":{}}");f.current.set(foreign);
        assertEquals("无权访问这一局",assertThrows(ServiceException.class,f::start).getMessage());verify(f.sessions,never()).insertSession(any());
    }
    @Test void firstIdentityIsOwnerBoundPersistedAndCanonical() throws Exception {
        Fixture f=new Fixture(true);f.start();TopicRouteSession stored=f.first.get();
        assertTrue(ThemeFirstIdentity.isFirst(stored));
        for(String field:Arrays.asList("memberId","topicId","activityId")) {
            TopicRouteSession bad=new TopicRouteSession();org.springframework.beans.BeanUtils.copyProperties(stored,bad);
            com.fasterxml.jackson.databind.node.ObjectNode root=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(bad.getConfigSnapshotJson());
            ((com.fasterxml.jackson.databind.node.ObjectNode)root.get(ThemeFirstIdentity.ORIGIN_KEY)).put(field,999L);
            bad.setConfigSnapshotJson(root.toString());assertThrows(ServiceException.class,()->ThemeRuntimeFeaturePolicy.hasFrozenTheme(bad),field);
        }
        TopicRouteSession bad=new TopicRouteSession();org.springframework.beans.BeanUtils.copyProperties(stored,bad);
        bad.setId(null);assertThrows(ServiceException.class,()->ThemeFirstIdentity.isFirst(bad));
        bad.setId(500L);bad.setPlayInstanceId("theme-first:010:0");assertThrows(ServiceException.class,()->ThemeFirstIdentity.isFirst(bad));
        bad.setPlayInstanceId("theme-first:10:0");bad.setConfigSnapshotJson("{}");assertThrows(ServiceException.class,()->ThemeRuntimeFeaturePolicy.hasFrozenTheme(bad));
        bad.setConfigSnapshotJson(null);assertThrows(ServiceException.class,()->ThemeRuntimeFeaturePolicy.hasFrozenTheme(bad));
        assertThrows(ServiceException.class,()->ThemeFirstIdentity.stampNew(stored));
    }
    @Test void tokenGenerationUsesFullLongRangeWithoutTruncation() {
        String token=ThemeFirstIdentity.token(Long.MAX_VALUE,Long.MAX_VALUE);
        assertEquals("theme-first:9223372036854775807:9223372036854775807",token);assertTrue(token.length()<=64);
        assertEquals("theme-first:1:0",ThemeFirstIdentity.token(1L,0L));
        assertThrows(ServiceException.class,()->ThemeFirstIdentity.token(0L,0L));
        assertThrows(ServiceException.class,()->ThemeFirstIdentity.token(1L,-1L));
    }
    @Test void existingLegacyDefaultThemeIsNotRewritten() throws Exception {
        Fixture f=new Fixture(true);f.start();TopicRouteSession stored=f.first.get();
        stored.setPlayInstanceId("default");
        com.fasterxml.jackson.databind.node.ObjectNode snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(stored.getConfigSnapshotJson());
        snapshot.remove(ThemeFirstIdentity.ORIGIN_KEY);stored.setConfigSnapshotJson(snapshot.toString());
        String original=stored.getConfigSnapshotJson();f.start();
        assertEquals("default",stored.getPlayInstanceId());assertEquals(original,stored.getConfigSnapshotJson());verify(f.sessions,times(1)).insertSession(any());
    }
    @Test void frozenFirstRemainsReadableAfterPublicationVersionIsRemoved() {
        Fixture f=new Fixture(true);Long id=f.start().getSessionId();f.topic.setPublishedConfigVersion(null);
        assertEquals(id,f.start().getSessionId());verify(f.sessions,times(1)).insertSession(any());
    }

}
