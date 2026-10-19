package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.constant.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.TopicRouteCompletionCoordinator;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy;
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
class ThemeFirstPublicProgressBoundaryTest {
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
        final List<CmsTopicNode> live=new ArrayList<>();
        final ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();
        final TopicRouteRuntimeServiceImpl runtime;
        Fixture(boolean theme) {
            topic.setId(10L);topic.setDelFlag(0);topic.setRouteMode("LINEAR");topic.setPublishedConfigVersion(1L);
            topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":6}}");
            if(theme)topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":3,\"replayEnabled\":true,\"items\":[]}");
            when(topics.selectCmsTopicById(10L)).thenReturn(topic);
            for(long id=11;id<=12;id++){CmsTopicNode n=new CmsTopicNode();n.setId(id);n.setTopicId(10L);n.setName("node"+id);n.setStatus(1L);n.setSortId(id);live.add(n);}
            when(nodes.selectCmsTopicNodeList(any())).thenAnswer(call->{CmsTopicNode p=call.getArgument(0);assertEquals(Long.valueOf(10),p.getTopicId());assertEquals(Long.valueOf(1),p.getStatus());return live;});
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
            when(registrations.selectCmsRegistrationList(any())).thenAnswer(call->{CmsRegistration p=call.getArgument(0);
                return Long.valueOf(7).equals(p.getMemberId()) && Long.valueOf(1).equals(p.getOwnerType()) && Long.valueOf(10).equals(p.getOwnerId()) && Integer.valueOf(2).equals(p.getRegistrationStatus())
                        ? Collections.singletonList(new CmsRegistration()) : Collections.emptyList();});
            when(sessions.selectCurrentByOwner(0L,10L,7L)).thenAnswer(call->current.get());
            when(sessions.selectCurrentByOwnerForUpdate(0L,10L,7L)).thenAnswer(call->current.get());
            when(sessions.selectByOwner(0L,10L,7L,"default")).thenAnswer(call->first.get());
            when(sessions.selectByOwnerForUpdate(0L,10L,7L,"default")).thenAnswer(call->first.get());
            when(sessions.selectAccessibleById(anyLong(),eq(7L))).thenAnswer(call->{long id=call.getArgument(0);TopicRouteSession row=first.get();return row!=null && row.getId()==id?row:null;});
            when(sessions.insertSession(any())).thenAnswer(call->store(call.getArgument(0)));
            when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);
                assertEquals(Long.valueOf(7),p.getMemberId());assertEquals(Long.valueOf(10),p.getTopicId());assertEquals(Long.valueOf(0),p.getActivityId());assertEquals("default",p.getPlayInstanceId());
                if(theme)assertNotNull(first.get(),"New theme must persist its own session before any current-progress read");
                PlayerNodeProgress old=new PlayerNodeProgress();old.setId(501L);old.setPicUrl("old-expired-photo");old.setCreateTime(new Date(1));old.setCheckinTime(new Date(1));old.setMemberId(7L);old.setTopicId(10L);old.setActivityId(0L);old.setNodeId(11L);old.setStatus(1);old.setPlayInstanceId("default");return Collections.singletonList(old);});
            runtime=new TopicRouteRuntimeServiceImpl(sessions,topics,nodes,templates,progress,activities,registrations,null,null,()->123L,()->10,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
            UmsMemberMapper firstMembers=mock(UmsMemberMapper.class);when(firstMembers.lockMemberById(7L)).thenReturn(7L);ReflectionTestUtils.setField(runtime,"originalAwardMemberMapper",firstMembers);
            when(sessions.selectCurrentCandidatesByOwners(anyList())).thenAnswer(call->current.get()==null?Collections.emptyList():Collections.singletonList(current.get()));
            ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);ReflectionTestUtils.setField(runtime,"cmsTopicChapterMapper",chapters);
        }
        int store(TopicRouteSession s){s.setId(ids.incrementAndGet());if(!first.compareAndSet(null,s))throw new DuplicateKeyException("synthetic same owner/default unique key");current.set(s);return 1;}
        TopicRouteState start(){return runtime.state(7L,10L,0L);}
    }

    @Test void advancedColdStartProxyCreatesFirstInRcAndExistingRrStillRejects() {
        Fixture f=new Fixture(true);CmsTopicNode node=f.live.get(0);node.setTemplateId(15L);when(f.nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(15L);template.setAdvancedConfigJson("{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"A\",\"weight\":1}]}}");when(f.templates.selectCmsMemberTemplateById(15L)).thenReturn(template);
        LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);when(reader.findLegacy(15L)).thenReturn(template);ReflectionTestUtils.setField(f.runtime,"legacyTemplateBodyReader",reader);
        AdvancedGameRuntimeServiceImpl target=new AdvancedGameRuntimeServiceImpl();GameRuntimeSessionMapper games=mock(GameRuntimeSessionMapper.class);
        ReflectionTestUtils.setField(target,"nodeMapper",f.nodes);ReflectionTestUtils.setField(target,"templateMapper",f.templates);ReflectionTestUtils.setField(target,"sessionMapper",games);ReflectionTestUtils.setField(target,"topicRouteRuntimeService",f.runtime);ReflectionTestUtils.setField(target,"registrationMapper",f.registrations);
        when(games.insert(any())).thenAnswer(call->{GameRuntimeSession row=call.getArgument(0);assertEquals("theme-first:10:0",row.getPlayInstanceId());row.setId(500L);return 1;});
        when(f.sessions.insertAction(any())).thenAnswer(call->{TopicRouteAction row=call.getArgument(0);row.setId(600L);return 1;});
        org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:first_game_proxy_"+UUID.randomUUID(),"sa","");
        org.springframework.jdbc.datasource.DataSourceTransactionManager manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        org.springframework.aop.framework.ProxyFactory factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setInterfaces(IAdvancedGameRuntimeService.class);factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));IAdvancedGameRuntimeService proxy=(IAdvancedGameRuntimeService)factory.getProxy();
        assertThrows(ServiceException.class,()->proxy.start(7L,0L,10L,11L,null));verify(f.sessions,never()).insertSession(any());
        f.topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");node.setChapterId(90L);
        CmsTopicChapter opening=new CmsTopicChapter();opening.setId(90L);opening.setTopicId(10L);opening.setName("Opening");opening.setBlocksJson("[{\"type\":\"node\",\"nodeId\":11}]");when(f.chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(opening));
        org.springframework.transaction.support.TransactionTemplate rr=new org.springframework.transaction.support.TransactionTemplate(manager);rr.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->rr.execute(status->proxy.start(7L,0L,10L,11L,null)));verify(f.sessions,never()).insertSession(any());verify(games,never()).insert(any());
        Map<String,Object> first=proxy.start(7L,0L,10L,11L,null);assertEquals(500L,first.get("sessionId"));assertEquals("theme-first:10:0",first.get("playInstanceId"));verify(f.sessions,times(1)).insertSession(any());verify(games,times(1)).insert(any());
    }

    @Test void invalidUploadWithOldDefaultCompletionIsRejected() throws Exception { runPhoto(false,true); }
    @Test void validNewUploadCompletesFirstWithoutRepeatingOldScopeAwards() throws Exception { runPhoto(true,true); }
    @Test void entirelyNewPlayerKeepsOriginalFirstNodeAwards() throws Exception { runPhoto(true,false); }
    private void runPhoto(boolean validUpload,boolean history) throws Exception {
        Fixture f=new Fixture(true);f.topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        CmsTopicNode node=f.live.get(0);node.setTemplateId(15L);when(f.nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(15L);template.setValidationMethod(ValidationMethod.PHOTO);
        LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);when(reader.findLegacy(15L)).thenReturn(template);ReflectionTestUtils.setField(f.runtime,"legacyTemplateBodyReader",reader);
        doAnswer(call->{TopicRouteAction a=call.getArgument(0);a.setId(991L);return 1;}).when(f.sessions).insertAction(any());
        when(f.sessions.updateStateCas(any())).thenReturn(1);when(f.sessions.updateActionResponse(anyLong(),anyString())).thenReturn(1);
        AtomicReference<PlayerNodeProgress> currentProgress=new AtomicReference<>();
        when(f.progress.selectPlayerNodeProgressListForInstance(any(),anyString())).thenAnswer(call->{
            String instance=call.getArgument(1);
            if("theme-first:10:0".equals(instance))return currentProgress.get()==null?Collections.emptyList():Collections.singletonList(currentProgress.get());
            return "default".equals(instance) && history?f.progress.selectPlayerNodeProgressList(call.getArgument(0)):Collections.emptyList();
        });
        when(f.progress.selectOriginalNodeAwardEvidence(7L,0L,10L,11L)).thenReturn(new LinkedHashMap<String,Object>(){{put("legacyCompleted",history);put("dropConsumed",false);put("couponConsumed",false);}});
        when(f.progress.selectOriginalInstanceAwardSessions(7L,0L,11L)).thenReturn(Collections.emptyList());
        when(f.sessions.selectCurrentCandidatesByOwners(anyList())).thenAnswer(call->f.first.get()==null?Collections.emptyList():Collections.singletonList(f.first.get()));
        when(f.sessions.selectByOwner(eq(0L),eq(10L),eq(7L),anyString())).thenAnswer(call->{TopicRouteSession row=f.first.get();return row!=null&&row.getPlayInstanceId().equals(call.getArgument(3))?row:null;});
        UmsMemberMapper members=mock(UmsMemberMapper.class);when(members.lockMemberById(7L)).thenReturn(7L);ReflectionTestUtils.setField(f.runtime,"originalAwardMemberMapper",members);
        when(f.progress.insertPlayerNodeProgress(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);assertEquals("theme-first:10:0",p.getPlayInstanceId());p.setId(900L);if(!currentProgress.compareAndSet(null,p))throw new DuplicateKeyException("same current FIRST");return 1;});
        PlayerNodeProgressServiceImpl progressService=new PlayerNodeProgressServiceImpl();ReflectionTestUtils.setField(progressService,"playerNodeProgressMapper",f.progress);ReflectionTestUtils.setField(progressService,"routeRuntimeService",f.runtime);
        AdvancedGameRuntimeServiceImpl advanced=new AdvancedGameRuntimeServiceImpl();ReflectionTestUtils.setField(advanced,"nodeMapper",f.nodes);ReflectionTestUtils.setField(advanced,"templateMapper",f.templates);ReflectionTestUtils.setField(advanced,"sessionMapper",mock(GameRuntimeSessionMapper.class));ReflectionTestUtils.setField(advanced,"topicRouteRuntimeService",f.runtime);
        ApiPlayProgressController controller=spy(new ApiPlayProgressController());doReturn(7L).when(controller).getAppUserId();
        ICmsTopicNodeService nodes=mock(ICmsTopicNodeService.class);when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);when(nodes.selectCmsTopicNodeList(any())).thenReturn(f.live);
        ICmsTopicService topics=mock(ICmsTopicService.class);when(topics.selectCmsTopicById(10L)).thenReturn(f.topic);
        ICmsMemberTemplateService templates=mock(ICmsMemberTemplateService.class);when(templates.selectCmsMemberTemplateById(15L)).thenReturn(template);
        ICmsRegistrationService registrations=mock(ICmsRegistrationService.class);CmsRegistration paid=new CmsRegistration();paid.setMemberId(7L);paid.setOwnerType(1L);paid.setOwnerId(10L);paid.setRegistrationStatus(2);when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(paid));
        ReflectionTestUtils.setField(controller,"cmsTopicNodeService",nodes);ReflectionTestUtils.setField(controller,"cmsTopicService",topics);ReflectionTestUtils.setField(controller,"cmsMemberTemplateService",templates);ReflectionTestUtils.setField(controller,"cmsRegistrationService",registrations);
        ReflectionTestUtils.setField(controller,"playerNodeProgressService",progressService);ReflectionTestUtils.setField(controller,"topicRouteRuntimeService",f.runtime);ReflectionTestUtils.setField(controller,"topicRouteCompletionCoordinator",new TopicRouteCompletionCoordinator(f.runtime));ReflectionTestUtils.setField(controller,"advancedGameRuntimeService",advanced);
        IPointsAwardService points=mock(IPointsAwardService.class);ITopicCompletionAwardService awards=mock(ITopicCompletionAwardService.class);
        ReflectionTestUtils.setField(controller,"pointsAwardService",points);ReflectionTestUtils.setField(controller,"topicCompletionAwardService",awards);ReflectionTestUtils.setField(controller,"playAttemptLogMapper",mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(controller,"nodeMerchantOverrideService",new NodeMerchantOverrideServiceImpl(mock(ICmsRegistrationMerchantService.class)));
        IGrowthService growth=mock(IGrowthService.class);IPlayerProgressionService progression=mock(IPlayerProgressionService.class);IXpBudgetService xp=mock(IXpBudgetService.class);
        when(progression.onNodeDone(7L)).thenReturn(Collections.emptyList());when(xp.resolveNodeXp(any())).thenReturn(12);
        ReflectionTestUtils.setField(controller,"growthService",growth);ReflectionTestUtils.setField(controller,"playerProgressionService",progression);ReflectionTestUtils.setField(controller,"xpBudgetService",xp);ReflectionTestUtils.setField(controller,"rewardDropService",mock(IRewardDropService.class));
        AtomicBoolean verifierCalled=new AtomicBoolean(false);ReflectionTestUtils.setField(controller,"photoUploadFactVerifier",(ApiPlayProgressController.PlayPhotoUploadFactVerifier)(url,member)->{verifierCalled.set(true);if(!validUpload)throw new IllegalArgumentException("no current uploaded photo fact");});
        TopicRouteState initial=f.start();assertEquals("theme-first:10:0",f.first.get().getPlayInstanceId());assertEquals(0,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").size());
        org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:public_photo_"+UUID.randomUUID(),"sa","");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        AjaxResult response=tx.execute(status->controller.photo("0","11","new-uploaded-photo","10","new-route-action",initial.getVersion()));
        assertTrue(verifierCalled.get(),"An old completed photo must never bypass this upload fact");
        if(!validUpload) {
            assertEquals(500,response.get("code"));verify(f.progress,never()).insertPlayerNodeProgress(any());verify(f.sessions,never()).updateStateCas(any());
            assertEquals(0,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").size());verifyNoInteractions(points,growth,progression);return;
        }
        assertEquals(200,response.get("code"),String.valueOf(response.get("msg")));
        Map<?,?> body=(Map<?,?>)response.get("data");assertEquals(Boolean.TRUE,body.get("firstTime"));
        TopicRouteState route=(TopicRouteState)body.get("routeState");assertEquals(TopicRouteState.COMPLETED,route.getNodeStates().get(11L));assertEquals(Long.valueOf(12L),route.getCurrentNodeId());
        assertEquals(900L,currentProgress.get().getId());assertEquals("theme-first:10:0",currentProgress.get().getPlayInstanceId());
        verify(f.progress,never()).selectPlayerNodeProgressListForInstance(any(),eq("default"));
        if(history){assertEquals(0,body.get("xp"));verifyNoInteractions(points,growth,progression);}
        else {assertEquals(12,body.get("xp"));verify(points,times(1)).award(7L,12L,11L,"节点打卡");verify(growth,times(1)).addExp(7L,12,"节点探索");verify(progression,times(1)).onNodeDone(7L);}
    }
    @Test void oldDefaultPreferenceReceiptCannotSatisfyNewFirst() throws Exception {
        Fixture f=new Fixture(true);f.topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        CmsTopicNode node=f.live.get(0);node.setTemplateId(15L);when(f.nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(15L);template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);template.setPreferenceJson("{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}},\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,\"purpose\":\"Reuse\",\"recipientLabel\":\"Next\",\"revocable\":true}}");
        LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);when(reader.findLegacy(15L)).thenReturn(template);ReflectionTestUtils.setField(f.runtime,"legacyTemplateBodyReader",reader);
        doAnswer(call->{TopicRouteAction a=call.getArgument(0);a.setId(991L);return 1;}).when(f.sessions).insertAction(any());
        when(f.sessions.updateStateCas(any())).thenReturn(1);when(f.sessions.updateActionResponse(anyLong(),anyString())).thenReturn(1);
        AtomicReference<PlayerNodeProgress> currentProgress=new AtomicReference<>();
        when(f.progress.selectPlayerNodeProgressListForInstance(any(),anyString())).thenAnswer(call->{
            String instance=call.getArgument(1);
            if("theme-first:10:0".equals(instance))return currentProgress.get()==null?Collections.emptyList():Collections.singletonList(currentProgress.get());
            return "default".equals(instance) ?f.progress.selectPlayerNodeProgressList(call.getArgument(0)):Collections.emptyList();
        });
        when(f.progress.selectOriginalNodeAwardEvidence(7L,0L,10L,11L)).thenReturn(new LinkedHashMap<String,Object>(){{put("legacyCompleted",true);put("dropConsumed",false);put("couponConsumed",false);}});
        when(f.progress.selectOriginalInstanceAwardSessions(7L,0L,11L)).thenReturn(Collections.emptyList());
        when(f.sessions.selectCurrentCandidatesByOwners(anyList())).thenAnswer(call->f.first.get()==null?Collections.emptyList():Collections.singletonList(f.first.get()));
        when(f.sessions.selectByOwner(eq(0L),eq(10L),eq(7L),anyString())).thenAnswer(call->{TopicRouteSession row=f.first.get();return row!=null&&row.getPlayInstanceId().equals(call.getArgument(3))?row:null;});
        UmsMemberMapper members=mock(UmsMemberMapper.class);when(members.lockMemberById(7L)).thenReturn(7L);ReflectionTestUtils.setField(f.runtime,"originalAwardMemberMapper",members);
        when(f.progress.insertPlayerNodeProgress(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);assertEquals("theme-first:10:0",p.getPlayInstanceId());p.setId(900L);if(!currentProgress.compareAndSet(null,p))throw new DuplicateKeyException("same current FIRST");return 1;});
        PlayerNodeProgressServiceImpl progressService=new PlayerNodeProgressServiceImpl();ReflectionTestUtils.setField(progressService,"playerNodeProgressMapper",f.progress);ReflectionTestUtils.setField(progressService,"routeRuntimeService",f.runtime);
        AdvancedGameRuntimeServiceImpl advanced=new AdvancedGameRuntimeServiceImpl();ReflectionTestUtils.setField(advanced,"nodeMapper",f.nodes);ReflectionTestUtils.setField(advanced,"templateMapper",f.templates);ReflectionTestUtils.setField(advanced,"sessionMapper",mock(GameRuntimeSessionMapper.class));ReflectionTestUtils.setField(advanced,"topicRouteRuntimeService",f.runtime);
        ApiPlayProgressController controller=spy(new ApiPlayProgressController());doReturn(7L).when(controller).getAppUserId();
        ICmsTopicNodeService nodes=mock(ICmsTopicNodeService.class);when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);when(nodes.selectCmsTopicNodeList(any())).thenReturn(f.live);
        ICmsTopicService topics=mock(ICmsTopicService.class);when(topics.selectCmsTopicById(10L)).thenReturn(f.topic);
        ICmsMemberTemplateService templates=mock(ICmsMemberTemplateService.class);when(templates.selectCmsMemberTemplateById(15L)).thenReturn(template);
        ICmsRegistrationService registrations=mock(ICmsRegistrationService.class);CmsRegistration paid=new CmsRegistration();paid.setMemberId(7L);paid.setOwnerType(1L);paid.setOwnerId(10L);paid.setRegistrationStatus(2);when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(paid));
        ReflectionTestUtils.setField(controller,"cmsTopicNodeService",nodes);ReflectionTestUtils.setField(controller,"cmsTopicService",topics);ReflectionTestUtils.setField(controller,"cmsMemberTemplateService",templates);ReflectionTestUtils.setField(controller,"cmsRegistrationService",registrations);
        ReflectionTestUtils.setField(controller,"playerNodeProgressService",progressService);ReflectionTestUtils.setField(controller,"topicRouteRuntimeService",f.runtime);ReflectionTestUtils.setField(controller,"topicRouteCompletionCoordinator",new TopicRouteCompletionCoordinator(f.runtime));ReflectionTestUtils.setField(controller,"advancedGameRuntimeService",advanced);
        IPointsAwardService points=mock(IPointsAwardService.class);ITopicCompletionAwardService awards=mock(ITopicCompletionAwardService.class);
        ReflectionTestUtils.setField(controller,"pointsAwardService",points);ReflectionTestUtils.setField(controller,"topicCompletionAwardService",awards);ReflectionTestUtils.setField(controller,"playAttemptLogMapper",mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(controller,"nodeMerchantOverrideService",new NodeMerchantOverrideServiceImpl(mock(ICmsRegistrationMerchantService.class)));
        IGrowthService growth=mock(IGrowthService.class);IPlayerProgressionService progression=mock(IPlayerProgressionService.class);IXpBudgetService xp=mock(IXpBudgetService.class);
        when(progression.onNodeDone(7L)).thenReturn(Collections.emptyList());when(xp.resolveNodeXp(any())).thenReturn(12);
        ReflectionTestUtils.setField(controller,"growthService",growth);ReflectionTestUtils.setField(controller,"playerProgressionService",progression);ReflectionTestUtils.setField(controller,"xpBudgetService",xp);ReflectionTestUtils.setField(controller,"rewardDropService",mock(IRewardDropService.class));
        com.chengyinhub.web.service.PreferencePlayService preference=new com.chengyinhub.web.service.PreferencePlayService();
        PlayAttemptLogMapper attempts=mock(PlayAttemptLogMapper.class);PlayAttemptLog legacy=new PlayAttemptLog();
        legacy.setResultJson("{\"evaluation\":{\"resultCode\":\"compact\"},\"progress\":{\"completed\":true,\"xp\":12},\"availableTagValues\":[\"compact\"]}");
        when(attempts.selectByIdempotencyKey("preference:7:10:0:11")).thenReturn(legacy);
        IPlayerTopicTagService tags=mock(IPlayerTopicTagService.class);when(tags.pendingForNode(7L,10L,11L)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(preference,"routeRuntimeService",f.runtime);when(attempts.insertPlayAttemptLog(any())).thenReturn(1);ReflectionTestUtils.setField(preference,"attemptMapper",attempts);ReflectionTestUtils.setField(preference,"tagService",tags);ReflectionTestUtils.setField(controller,"preferencePlayService",preference);
        TopicRouteState initial=f.start();assertEquals("theme-first:10:0",f.first.get().getPlayInstanceId());assertEquals(0,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").size());
        org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:public_photo_"+UUID.randomUUID(),"sa","");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        com.chengyinhub.business.domain.dto.PreferenceSubmitRequest request=new com.chengyinhub.business.domain.dto.PreferenceSubmitRequest();request.setActivityId("0");request.setTopicId("10");request.setChoices(Collections.singletonMap("q","A"));request.setRouteActionId("new-route-action");request.setExpectedRouteVersion(initial.getVersion());
        AjaxResult response=tx.execute(status->controller.submitPreference(11L,request));
        verify(attempts,never()).selectByIdempotencyKey("preference:7:10:0:11");
        assertNotNull(currentProgress.get(),"New FIRST must persist its own completion, rather than return a legacy preference receipt");
        assertEquals(200,response.get("code"),String.valueOf(response.get("msg")));
        Map<?,?> preferenceBody=(Map<?,?>)response.get("data");Map<?,?> body=(Map<?,?>)preferenceBody.get("progress");assertEquals(Boolean.TRUE,body.get("firstTime"));
        TopicRouteState route=(TopicRouteState)body.get("routeState");assertEquals(TopicRouteState.COMPLETED,route.getNodeStates().get(11L));assertEquals(Long.valueOf(12L),route.getCurrentNodeId());
        assertEquals(900L,currentProgress.get().getId());assertEquals("theme-first:10:0",currentProgress.get().getPlayInstanceId());
        verify(f.progress,never()).selectPlayerNodeProgressListForInstance(any(),eq("default"));
        assertEquals(0,body.get("xp"));verifyNoInteractions(points,growth,progression);
        assertFalse(preferenceBody.containsKey("_serverPreferenceScope"));
    }

    @Test void preferenceMustPrepareAdvancedGameBeforeMemberBarrier() throws Exception { runPreferenceAdvancedBoundary(false); }
    @Test void existingLegacyDefaultPreferenceReceiptDoesNotReopenAdvancedGame() throws Exception { runPreferenceAdvancedBoundary(true); }
    private void runPreferenceAdvancedBoundary(boolean legacyReceiptOnly) throws Exception {
        List<String> lockEvents=new ArrayList<>();Fixture f=new Fixture(true);f.topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        CmsTopicNode node=f.live.get(0);node.setTemplateId(15L);when(f.nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(15L);template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);template.setPreferenceJson("{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}},\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,\"purpose\":\"Reuse\",\"recipientLabel\":\"Next\",\"revocable\":true}}");
        LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);when(reader.findLegacy(15L)).thenReturn(template);ReflectionTestUtils.setField(f.runtime,"legacyTemplateBodyReader",reader);
        doAnswer(call->{TopicRouteAction a=call.getArgument(0);a.setId(991L);return 1;}).when(f.sessions).insertAction(any());
        when(f.sessions.updateStateCas(any())).thenReturn(1);when(f.sessions.updateActionResponse(anyLong(),anyString())).thenReturn(1);
        AtomicReference<PlayerNodeProgress> currentProgress=new AtomicReference<>();
        when(f.progress.selectPlayerNodeProgressListForInstance(any(),anyString())).thenAnswer(call->{
            String instance=call.getArgument(1);
            if("theme-first:10:0".equals(instance))return currentProgress.get()==null?Collections.emptyList():Collections.singletonList(currentProgress.get());
            return "default".equals(instance) ?f.progress.selectPlayerNodeProgressList(call.getArgument(0)):Collections.emptyList();
        });
        when(f.progress.selectOriginalNodeAwardEvidence(7L,0L,10L,11L)).thenReturn(new LinkedHashMap<String,Object>(){{put("legacyCompleted",true);put("dropConsumed",false);put("couponConsumed",false);}});
        when(f.progress.selectOriginalInstanceAwardSessions(7L,0L,11L)).thenReturn(Collections.emptyList());
        when(f.sessions.selectCurrentCandidatesByOwners(anyList())).thenAnswer(call->f.first.get()==null?Collections.emptyList():Collections.singletonList(f.first.get()));
        when(f.sessions.selectByOwner(eq(0L),eq(10L),eq(7L),anyString())).thenAnswer(call->{TopicRouteSession row=f.first.get();return row!=null&&row.getPlayInstanceId().equals(call.getArgument(3))?row:null;});
        UmsMemberMapper members=mock(UmsMemberMapper.class);when(members.lockMemberById(7L)).thenAnswer(call->{lockEvents.add("member");return 7L;});ReflectionTestUtils.setField(f.runtime,"originalAwardMemberMapper",members);
        when(f.progress.insertPlayerNodeProgress(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);assertEquals("theme-first:10:0",p.getPlayInstanceId());p.setId(900L);if(!currentProgress.compareAndSet(null,p))throw new DuplicateKeyException("same current FIRST");return 1;});
        PlayerNodeProgressServiceImpl progressService=new PlayerNodeProgressServiceImpl();ReflectionTestUtils.setField(progressService,"playerNodeProgressMapper",f.progress);ReflectionTestUtils.setField(progressService,"routeRuntimeService",f.runtime);
        AdvancedGameRuntimeServiceImpl advanced=new AdvancedGameRuntimeServiceImpl();ReflectionTestUtils.setField(advanced,"nodeMapper",f.nodes);ReflectionTestUtils.setField(advanced,"templateMapper",f.templates);ReflectionTestUtils.setField(advanced,"sessionMapper",mock(GameRuntimeSessionMapper.class));ReflectionTestUtils.setField(advanced,"topicRouteRuntimeService",f.runtime);
        GameRuntimeSessionMapper games=mock(GameRuntimeSessionMapper.class);
        GameRuntimeSession game=new GameRuntimeSession();game.setId(600L);game.setActivityId(0L);game.setTopicId(10L);game.setNodeId(11L);game.setTemplateId(15L);game.setOwnerType(1);game.setOwnerId(7L);game.setPlayInstanceId("theme-first:10:0");game.setStatus(GameRuntimeSession.STATUS_RUNNING);game.setVersion(0);
        game.setConfigSnapshotJson("{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"A\",\"weight\":1}]}}");game.setStateJson("{\"draws\":[{\"id\":\"a\"}]}");
        when(games.selectPlayerByOwnerForInstance(0L,10L,11L,7L,"theme-first:10:0")).thenReturn(game);
        when(games.updateStateCas(any())).thenAnswer(call->{lockEvents.add("game");return 1;});
        ReflectionTestUtils.setField(advanced,"sessionMapper",games);

        ApiPlayProgressController controller=spy(new ApiPlayProgressController());doReturn(7L).when(controller).getAppUserId();
        ICmsTopicNodeService nodes=mock(ICmsTopicNodeService.class);when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);when(nodes.selectCmsTopicNodeList(any())).thenReturn(f.live);
        ICmsTopicService topics=mock(ICmsTopicService.class);when(topics.selectCmsTopicById(10L)).thenReturn(f.topic);
        ICmsMemberTemplateService templates=mock(ICmsMemberTemplateService.class);when(templates.selectCmsMemberTemplateById(15L)).thenReturn(template);
        ICmsRegistrationService registrations=mock(ICmsRegistrationService.class);CmsRegistration paid=new CmsRegistration();paid.setMemberId(7L);paid.setOwnerType(1L);paid.setOwnerId(10L);paid.setRegistrationStatus(2);when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(paid));
        ReflectionTestUtils.setField(controller,"cmsTopicNodeService",nodes);ReflectionTestUtils.setField(controller,"cmsTopicService",topics);ReflectionTestUtils.setField(controller,"cmsMemberTemplateService",templates);ReflectionTestUtils.setField(controller,"cmsRegistrationService",registrations);
        ReflectionTestUtils.setField(controller,"playerNodeProgressService",progressService);ReflectionTestUtils.setField(controller,"topicRouteRuntimeService",f.runtime);ReflectionTestUtils.setField(controller,"topicRouteCompletionCoordinator",new TopicRouteCompletionCoordinator(f.runtime));ReflectionTestUtils.setField(controller,"advancedGameRuntimeService",advanced);
        IPointsAwardService points=mock(IPointsAwardService.class);ITopicCompletionAwardService awards=mock(ITopicCompletionAwardService.class);
        ReflectionTestUtils.setField(controller,"pointsAwardService",points);ReflectionTestUtils.setField(controller,"topicCompletionAwardService",awards);ReflectionTestUtils.setField(controller,"playAttemptLogMapper",mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(controller,"nodeMerchantOverrideService",new NodeMerchantOverrideServiceImpl(mock(ICmsRegistrationMerchantService.class)));
        IGrowthService growth=mock(IGrowthService.class);IPlayerProgressionService progression=mock(IPlayerProgressionService.class);IXpBudgetService xp=mock(IXpBudgetService.class);
        when(progression.onNodeDone(7L)).thenReturn(Collections.emptyList());when(xp.resolveNodeXp(any())).thenReturn(12);
        ReflectionTestUtils.setField(controller,"growthService",growth);ReflectionTestUtils.setField(controller,"playerProgressionService",progression);ReflectionTestUtils.setField(controller,"xpBudgetService",xp);ReflectionTestUtils.setField(controller,"rewardDropService",mock(IRewardDropService.class));
        com.chengyinhub.web.service.PreferencePlayService preference=new com.chengyinhub.web.service.PreferencePlayService();
        PlayAttemptLogMapper attempts=mock(PlayAttemptLogMapper.class);PlayAttemptLog legacy=new PlayAttemptLog();
        legacy.setResultJson("{\"evaluation\":{\"resultCode\":\"compact\"},\"progress\":{\"completed\":true,\"xp\":12},\"availableTagValues\":[\"compact\"]}");
        legacy.setMemberId(7L);legacy.setTopicId(10L);legacy.setActivityId(0L);legacy.setNodeId(11L);legacy.setAttemptType(6);legacy.setResult(1);legacy.setIdempotencyKey("preference:7:10:0:11");
        when(attempts.selectByIdempotencyKey("preference:7:10:0:11")).thenReturn(legacy);
        IPlayerTopicTagService tags=mock(IPlayerTopicTagService.class);when(tags.pendingForNode(7L,10L,11L)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(preference,"routeRuntimeService",f.runtime);when(attempts.insertPlayAttemptLog(any())).thenReturn(1);ReflectionTestUtils.setField(preference,"attemptMapper",attempts);ReflectionTestUtils.setField(preference,"tagService",tags);ReflectionTestUtils.setField(controller,"preferencePlayService",preference);
        TopicRouteState initial=f.start();assertEquals("theme-first:10:0",f.first.get().getPlayInstanceId());assertEquals(0,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").size());
        if (legacyReceiptOnly) {
            // Fixture for an already-existing legitimate legacy theme session, never a production retag.
            f.first.get().setPlayInstanceId("default");
            com.fasterxml.jackson.databind.node.ObjectNode snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(f.first.get().getConfigSnapshotJson());snapshot.remove("_serverThemeOrigin");f.first.get().setConfigSnapshotJson(snapshot.toString());
            game.setPlayInstanceId("default");game.setStateJson("{}");
            when(games.selectPlayerByOwnerForInstance(0L,10L,11L,7L,"default")).thenReturn(game);
        }
        org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:public_photo_"+UUID.randomUUID(),"sa","");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        com.chengyinhub.business.domain.dto.PreferenceSubmitRequest request=new com.chengyinhub.business.domain.dto.PreferenceSubmitRequest();request.setActivityId("0");request.setTopicId("10");request.setChoices(Collections.singletonMap("q","A"));request.setRouteActionId("new-route-action");request.setExpectedRouteVersion(initial.getVersion());
        lockEvents.clear();AjaxResult response=tx.execute(status->controller.submitPreference(11L,request));
        if (legacyReceiptOnly) {
            assertEquals(200,response.get("code"));Map<?,?> receipt=(Map<?,?>)response.get("data");
            assertEquals(Boolean.FALSE,receipt.get("firstTime"));assertEquals(12,((Map<?,?>)receipt.get("progress")).get("xp"));
            assertNull(currentProgress.get());assertEquals(GameRuntimeSession.STATUS_RUNNING,game.getStatus());
            assertFalse(lockEvents.contains("game") || lockEvents.contains("member"),"Existing receipt must not prepare or re-award: "+lockEvents);
            verify(games,never()).updateStateCas(any());verify(attempts,never()).insertPlayAttemptLog(any());verifyNoInteractions(points,growth,progression);return;
        }
        verify(attempts,never()).selectByIdempotencyKey("preference:7:10:0:11");
        assertNotNull(currentProgress.get(),"New FIRST must persist its own completion, rather than return a legacy preference receipt");
        assertEquals(200,response.get("code"),String.valueOf(response.get("msg")));
        Map<?,?> preferenceBody=(Map<?,?>)response.get("data");Map<?,?> body=(Map<?,?>)preferenceBody.get("progress");assertEquals(Boolean.TRUE,body.get("firstTime"));
        TopicRouteState route=(TopicRouteState)body.get("routeState");assertEquals(TopicRouteState.COMPLETED,route.getNodeStates().get(11L));assertEquals(Long.valueOf(12L),route.getCurrentNodeId());
        assertEquals(900L,currentProgress.get().getId());assertEquals("theme-first:10:0",currentProgress.get().getPlayInstanceId());
        verify(f.progress,never()).selectPlayerNodeProgressListForInstance(any(),eq("default"));
        assertEquals(0,body.get("xp"));verifyNoInteractions(points,growth,progression);
        assertFalse(preferenceBody.containsKey("_serverPreferenceScope"));
        System.out.println("INDEPENDENT_PUBLIC_PREFERENCE_LOCK_ORDER="+lockEvents);
        assertTrue(lockEvents.contains("game"), "Must exercise a real completeGate game CAS");
        assertTrue(lockEvents.indexOf("game") < lockEvents.indexOf("member"), "Game write barrier must precede member: " + lockEvents);
    }

    @Test void tiebreakMustBeAvailableBeforeAdvancedGameIsReady() throws Exception { runTiebreak(false, false); }
    @Test void tiebreakMustNotCompleteAnOtherwiseReadyAdvancedGame() throws Exception { runTiebreak(true, false); }
    @Test void resolvedTiebreakStillRequiresAnActuallyReadyAdvancedGame() throws Exception { runTiebreak(false, true); }
    @Test void resolvedTiebreakCompletesOnlyAfterGameBeforeMemberPreparation() throws Exception { runTiebreak(true, true); }
    private void runTiebreak(boolean gameReady, boolean resolved) throws Exception {
        List<String> lockEvents=new ArrayList<>();Fixture f=new Fixture(true);f.topic.setProductType(PlayMode.LINEAR_ORIENTEERING);
        CmsTopicNode node=f.live.get(0);node.setTemplateId(15L);when(f.nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(15L);template.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);template.setPreferenceJson("{\"steps\":[{\"key\":\"q\",\"type\":\"single\",\"title\":\"Choose\",\"options\":[{\"key\":\"A\",\"text\":\"Small\",\"scores\":{\"compact\":2}},{\"key\":\"B\",\"text\":\"Big\",\"scores\":{\"compact\":1}}]}],\"dimensions\":[\"compact\"],\"results\":{\"compact\":{\"title\":\"Compact\",\"body\":\"because {{choices}}\",\"nextStep\":\"Clear\",\"nextStepDays\":7}},\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,\"purpose\":\"Reuse\",\"recipientLabel\":\"Next\",\"revocable\":true}}");
        com.fasterxml.jackson.databind.node.ObjectNode pref=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(template.getPreferenceJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode)pref.get("dimensions")).add("other");
        for(com.fasterxml.jackson.databind.JsonNode option:pref.path("steps").get(0).path("options")){com.fasterxml.jackson.databind.node.ObjectNode scores=(com.fasterxml.jackson.databind.node.ObjectNode)option.get("scores");scores.put("compact",1);scores.put("other",1);}
        ((com.fasterxml.jackson.databind.node.ObjectNode)pref.get("results")).set("other",pref.path("results").get("compact").deepCopy());
        pref.set("tiebreak",JSON.readTree("{\"key\":\"tie\",\"type\":\"single\",\"title\":\"Choose a priority\",\"options\":[{\"key\":\"C\",\"text\":\"Compact\",\"scores\":{\"compact\":1}},{\"key\":\"D\",\"text\":\"Other\",\"scores\":{\"other\":1}}]}"));
        template.setPreferenceJson(pref.toString());assertTrue(new PreferenceResultEvaluator().evaluate(template.getPreferenceJson(),Collections.singletonMap("q","A")).isNeedsTiebreak());
        LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);when(reader.findLegacy(15L)).thenReturn(template);ReflectionTestUtils.setField(f.runtime,"legacyTemplateBodyReader",reader);
        doAnswer(call->{TopicRouteAction a=call.getArgument(0);a.setId(991L);return 1;}).when(f.sessions).insertAction(any());
        when(f.sessions.updateStateCas(any())).thenReturn(1);when(f.sessions.updateActionResponse(anyLong(),anyString())).thenReturn(1);
        AtomicReference<PlayerNodeProgress> currentProgress=new AtomicReference<>();
        when(f.progress.selectPlayerNodeProgressListForInstance(any(),anyString())).thenAnswer(call->{
            String instance=call.getArgument(1);
            if("theme-first:10:0".equals(instance))return currentProgress.get()==null?Collections.emptyList():Collections.singletonList(currentProgress.get());
            return "default".equals(instance) ?f.progress.selectPlayerNodeProgressList(call.getArgument(0)):Collections.emptyList();
        });
        when(f.progress.selectOriginalNodeAwardEvidence(7L,0L,10L,11L)).thenReturn(new LinkedHashMap<String,Object>(){{put("legacyCompleted",true);put("dropConsumed",false);put("couponConsumed",false);}});
        when(f.progress.selectOriginalInstanceAwardSessions(7L,0L,11L)).thenReturn(Collections.emptyList());
        when(f.sessions.selectCurrentCandidatesByOwners(anyList())).thenAnswer(call->f.first.get()==null?Collections.emptyList():Collections.singletonList(f.first.get()));
        when(f.sessions.selectByOwner(eq(0L),eq(10L),eq(7L),anyString())).thenAnswer(call->{TopicRouteSession row=f.first.get();return row!=null&&row.getPlayInstanceId().equals(call.getArgument(3))?row:null;});
        UmsMemberMapper members=mock(UmsMemberMapper.class);when(members.lockMemberById(7L)).thenAnswer(call->{lockEvents.add("member");return 7L;});ReflectionTestUtils.setField(f.runtime,"originalAwardMemberMapper",members);
        when(f.progress.insertPlayerNodeProgress(any())).thenAnswer(call->{PlayerNodeProgress p=call.getArgument(0);assertEquals("theme-first:10:0",p.getPlayInstanceId());p.setId(900L);if(!currentProgress.compareAndSet(null,p))throw new DuplicateKeyException("same current FIRST");return 1;});
        PlayerNodeProgressServiceImpl progressService=new PlayerNodeProgressServiceImpl();ReflectionTestUtils.setField(progressService,"playerNodeProgressMapper",f.progress);ReflectionTestUtils.setField(progressService,"routeRuntimeService",f.runtime);
        AdvancedGameRuntimeServiceImpl advanced=new AdvancedGameRuntimeServiceImpl();ReflectionTestUtils.setField(advanced,"nodeMapper",f.nodes);ReflectionTestUtils.setField(advanced,"templateMapper",f.templates);ReflectionTestUtils.setField(advanced,"sessionMapper",mock(GameRuntimeSessionMapper.class));ReflectionTestUtils.setField(advanced,"topicRouteRuntimeService",f.runtime);
        GameRuntimeSessionMapper games=mock(GameRuntimeSessionMapper.class);
        GameRuntimeSession game=new GameRuntimeSession();game.setId(600L);game.setActivityId(0L);game.setTopicId(10L);game.setNodeId(11L);game.setTemplateId(15L);game.setOwnerType(1);game.setOwnerId(7L);game.setPlayInstanceId("theme-first:10:0");game.setStatus(GameRuntimeSession.STATUS_RUNNING);game.setVersion(0);
        game.setConfigSnapshotJson("{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"label\":\"A\",\"weight\":1}]}}");game.setStateJson("{\"draws\":[{\"id\":\"a\"}]}");
        if(!gameReady)game.setStateJson("{\"draws\":[]}");
        when(games.selectPlayerByOwnerForInstance(0L,10L,11L,7L,"theme-first:10:0")).thenReturn(game);
        when(games.updateStateCas(any())).thenAnswer(call->{lockEvents.add("game");return 1;});
        ReflectionTestUtils.setField(advanced,"sessionMapper",games);

        ApiPlayProgressController controller=spy(new ApiPlayProgressController());doReturn(7L).when(controller).getAppUserId();
        ICmsTopicNodeService nodes=mock(ICmsTopicNodeService.class);when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);when(nodes.selectCmsTopicNodeList(any())).thenReturn(f.live);
        ICmsTopicService topics=mock(ICmsTopicService.class);when(topics.selectCmsTopicById(10L)).thenReturn(f.topic);
        ICmsMemberTemplateService templates=mock(ICmsMemberTemplateService.class);when(templates.selectCmsMemberTemplateById(15L)).thenReturn(template);
        ICmsRegistrationService registrations=mock(ICmsRegistrationService.class);CmsRegistration paid=new CmsRegistration();paid.setMemberId(7L);paid.setOwnerType(1L);paid.setOwnerId(10L);paid.setRegistrationStatus(2);when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(paid));
        ReflectionTestUtils.setField(controller,"cmsTopicNodeService",nodes);ReflectionTestUtils.setField(controller,"cmsTopicService",topics);ReflectionTestUtils.setField(controller,"cmsMemberTemplateService",templates);ReflectionTestUtils.setField(controller,"cmsRegistrationService",registrations);
        ReflectionTestUtils.setField(controller,"playerNodeProgressService",progressService);ReflectionTestUtils.setField(controller,"topicRouteRuntimeService",f.runtime);ReflectionTestUtils.setField(controller,"topicRouteCompletionCoordinator",new TopicRouteCompletionCoordinator(f.runtime));ReflectionTestUtils.setField(controller,"advancedGameRuntimeService",advanced);
        IPointsAwardService points=mock(IPointsAwardService.class);ITopicCompletionAwardService awards=mock(ITopicCompletionAwardService.class);
        ReflectionTestUtils.setField(controller,"pointsAwardService",points);ReflectionTestUtils.setField(controller,"topicCompletionAwardService",awards);ReflectionTestUtils.setField(controller,"playAttemptLogMapper",mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(controller,"nodeMerchantOverrideService",new NodeMerchantOverrideServiceImpl(mock(ICmsRegistrationMerchantService.class)));
        IGrowthService growth=mock(IGrowthService.class);IPlayerProgressionService progression=mock(IPlayerProgressionService.class);IXpBudgetService xp=mock(IXpBudgetService.class);
        when(progression.onNodeDone(7L)).thenReturn(Collections.emptyList());when(xp.resolveNodeXp(any())).thenReturn(12);
        ReflectionTestUtils.setField(controller,"growthService",growth);ReflectionTestUtils.setField(controller,"playerProgressionService",progression);ReflectionTestUtils.setField(controller,"xpBudgetService",xp);ReflectionTestUtils.setField(controller,"rewardDropService",mock(IRewardDropService.class));
        com.chengyinhub.web.service.PreferencePlayService preference=new com.chengyinhub.web.service.PreferencePlayService();
        PlayAttemptLogMapper attempts=mock(PlayAttemptLogMapper.class);PlayAttemptLog legacy=new PlayAttemptLog();
        legacy.setResultJson("{\"evaluation\":{\"resultCode\":\"compact\"},\"progress\":{\"completed\":true,\"xp\":12},\"availableTagValues\":[\"compact\"]}");
        when(attempts.selectByIdempotencyKey("preference:7:10:0:11")).thenReturn(legacy);
        IPlayerTopicTagService tags=mock(IPlayerTopicTagService.class);when(tags.pendingForNode(7L,10L,11L)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(preference,"routeRuntimeService",f.runtime);when(attempts.insertPlayAttemptLog(any())).thenReturn(1);ReflectionTestUtils.setField(preference,"attemptMapper",attempts);ReflectionTestUtils.setField(preference,"tagService",tags);ReflectionTestUtils.setField(controller,"preferencePlayService",preference);
        TopicRouteState initial=f.start();assertEquals("theme-first:10:0",f.first.get().getPlayInstanceId());assertEquals(0,JSON.readTree(f.first.get().getStateJson()).path("completedNodeIds").size());
        org.apache.ibatis.datasource.pooled.PooledDataSource ds=new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver","jdbc:h2:mem:public_photo_"+UUID.randomUUID(),"sa","");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        com.chengyinhub.business.domain.dto.PreferenceSubmitRequest request=new com.chengyinhub.business.domain.dto.PreferenceSubmitRequest();request.setActivityId("0");request.setTopicId("10");request.setChoices(Collections.singletonMap("q","A"));request.setRouteActionId("new-route-action");request.setExpectedRouteVersion(initial.getVersion());
        if (resolved) { Map<String,String> choices=new LinkedHashMap<>();choices.put("q","A");choices.put("tie","C");request.setChoices(choices); }
        lockEvents.clear();
        if (resolved && !gameReady) {
            assertThrows(ServiceException.class,()->tx.execute(status->controller.submitPreference(11L,request)));
            assertNull(currentProgress.get());assertEquals(GameRuntimeSession.STATUS_RUNNING,game.getStatus());
            verify(games,never()).updateStateCas(any());verify(attempts,never()).insertPlayAttemptLog(any());verifyNoInteractions(points,growth,progression);return;
        }
        AjaxResult response=tx.execute(status->controller.submitPreference(11L,request));
        if (resolved) {
            assertEquals(200,response.get("code"));Map<?,?> complete=(Map<?,?>)response.get("data");assertFalse(complete.containsKey("needsTiebreak"));
            assertEquals(Boolean.TRUE,complete.get("firstTime"));assertNotNull(currentProgress.get());assertEquals(GameRuntimeSession.STATUS_COMPLETED,game.getStatus());
            assertTrue(lockEvents.indexOf("game")>=0 && lockEvents.indexOf("game")<lockEvents.indexOf("member"),"Resolved tie retains game-before-member: "+lockEvents);
            verify(attempts,times(1)).insertPlayAttemptLog(any());verifyNoInteractions(points,growth,progression);return;
        }
        assertEquals(200,response.get("code"));Map<?,?> body=(Map<?,?>)response.get("data");
        assertEquals(Boolean.TRUE,body.get("needsTiebreak"));assertNotNull(body.get("tiebreak"));
        assertNull(currentProgress.get());assertEquals(GameRuntimeSession.STATUS_RUNNING,game.getStatus(),"The preference is still incomplete, so tie evaluation must not complete the game");
        verify(games,never()).updateStateCas(any());verify(attempts,never()).insertPlayAttemptLog(any());verifyNoInteractions(points,growth,progression);
        System.out.println("INDEPENDENT_PREFERENCE_TIEBREAK_READ_ONLY gameReady="+gameReady+" events="+lockEvents);
    }
}
