package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real service methods with mocked read/write boundaries; no database or Spring transaction claim. */
class ThemeRuntimeDefaultOffTest {
    private final ObjectMapper json = new ObjectMapper();
    private TopicRouteSessionMapper sessions;
    private CmsTopicMapper topics;
    private CmsTopicChapterMapper chapters;
    private ThemeStateKeyMapper keys;
    private TopicRouteRuntimeServiceImpl runtime;

    @BeforeEach void setUp() {
        sessions = mock(TopicRouteSessionMapper.class);
        topics = mock(CmsTopicMapper.class);
        chapters = mock(CmsTopicChapterMapper.class);
        keys = mock(ThemeStateKeyMapper.class);
        runtime = new TopicRouteRuntimeServiceImpl(sessions,topics,null,null,null,null);
        ReflectionTestUtils.setField(runtime,"cmsTopicChapterMapper",chapters);
        ReflectionTestUtils.setField(runtime,"themeStateKeyMapper",keys);
    }
    private void enable() {
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        ReflectionTestUtils.setField(policy,"enabled",true);
        ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);
    }
    private TopicRouteSession session(String raw) {
        TopicRouteSession s = new TopicRouteSession(); s.setId(31L);s.setMemberId(7L);s.setTopicId(10L);
        s.setActivityId(0L);s.setPlayInstanceId("default");s.setConfigSnapshotJson(raw);s.setVersion(0);s.setStatus(0);
        s.setStateJson("{\"status\":\"ACTIVE\"}");
        when(sessions.selectByOwner(0L,10L,7L,"default")).thenReturn(s);
        when(sessions.selectCurrentByOwner(0L,10L,7L)).thenReturn(s);
        when(sessions.selectCurrentByOwnerForUpdate(0L,10L,7L)).thenReturn(s);
        when(sessions.selectByOwnerForUpdate(0L,10L,7L,"default")).thenReturn(s);
        return s;
    }
    @Test void allNewThemeEndpointsRejectBeforeAnySourceOrWrite() {
        assertAll(
            () -> assertThrows(ServiceException.class, () -> runtime.themeView(7L,10L,0L,null)),
            () -> assertThrows(ServiceException.class, () -> runtime.themeAction(7L,31L,0,"a","USE","food")),
            () -> assertThrows(ServiceException.class, () -> runtime.replayTheme(7L,31L)),
            () -> assertThrows(ServiceException.class, () -> runtime.replayThemeTeam(7L,33L,5L,10L,31L,null,"a")),
            () -> assertThrows(ServiceException.class, () -> runtime.themeHistory(7L,10L)),
            () -> assertThrows(ServiceException.class, () -> runtime.themeCard(7L,31L)));
        verifyNoInteractions(sessions,topics,chapters,keys);
    }
    @Test void publishedThemeCannotCreateRouteWhenDisabled() {
        CmsTopic t = new CmsTopic();t.setId(10L);t.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        when(topics.selectCmsTopicById(10L)).thenReturn(t);
        assertThrows(ServiceException.class, () -> runtime.state(7L,10L,0L));
        verifyNoInteractions(sessions,chapters,keys);
    }
    @Test void frozenThemeCannotFallBackAfterPublishedConfigIsCleared() {
        session("{\"themeConfig\":{}}");
        assertAll(
            () -> assertThrows(ServiceException.class, () -> runtime.nodeRuntimeConfig(7L,10L,1L,0L)),
            () -> assertThrows(ServiceException.class, () -> runtime.playInstance(7L,10L,0L)),
            () -> assertThrows(ServiceException.class, () -> runtime.assertCurrentPlayInstance(7L,10L,0L,"default")),
            () -> assertThrows(ServiceException.class, () -> runtime.requireOpeningAccessReadOnly(7L,10L,0L,1L)),
            () -> assertThrows(ServiceException.class, () -> runtime.recordMistake(7L,10L,0L,1L)));
        verify(sessions,never()).insertSession(any());verify(sessions,never()).updateStateCas(any());
        verify(sessions,never()).insertAction(any());verifyNoInteractions(chapters,keys);
    }
    @Test void oldNoThemeInstanceRemainsDefaultWithoutAnyMutation() {
        TopicRouteSession s = session("{\"journeyRules\":{}}");
        String before=s.getConfigSnapshotJson();assertEquals("default",runtime.playInstance(7L,10L,0L));
        assertNull(runtime.nodeRuntimeConfig(7L,10L,1L,0L));assertFalse(runtime.assertCurrentPlayInstance(7L,10L,0L,"default"));
        assertEquals(before,s.getConfigSnapshotJson());verify(sessions,never()).updateStateCas(any());verify(sessions,never()).insertAction(any());
    }
    @Test void configuredUnstartedThemeCannotOpenLegacyAdvancedGame() {
        enable();CmsTopic t=new CmsTopic();t.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        when(topics.selectCmsTopicById(10L)).thenReturn(t);
        assertThrows(ServiceException.class,()->runtime.nodeRuntimeConfig(7L,10L,1L,0L));
        assertThrows(ServiceException.class,()->runtime.assertCurrentPlayInstance(7L,10L,0L,"default"));
        verify(sessions,never()).insertSession(any());
    }
    @Test void corruptOrMissingFrozenNodeNeverReadsLiveTemplate() {
        enable();
        for(String raw:Arrays.asList("{\"themeConfig\":{}}","{\"themeConfig\":{},\"nodeConfigs\":{\"1\":null}}","{\"themeConfig\":{},\"nodeConfigs\":{\"1\":[]}}")) {
            session(raw);assertThrows(IllegalStateException.class,()->runtime.nodeRuntimeConfig(7L,10L,1L,0L));
        }
        verifyNoInteractions(topics,chapters,keys);
    }
    @Test void frozenConfigReadReturnsDefensiveCopy() throws Exception {
        enable();TopicRouteSession s=session("{\"themeConfig\":{},\"nodeConfigs\":{\"1\":{\"schemaVersion\":1}}}");
        ObjectNode returned=(ObjectNode)runtime.nodeRuntimeConfig(7L,10L,1L,0L);returned.put("injected",true);
        assertFalse(runtime.nodeRuntimeConfig(7L,10L,1L,0L).has("injected"));assertFalse(s.getConfigSnapshotJson().contains("injected"));
    }
    @Test void broadcastDispatchAndEnqueueAreIndependentlyDefaultOff() {
        ThemeBroadcastMapper mapper=mock(ThemeBroadcastMapper.class);IImService im=mock(IImService.class);
        ThemeBroadcastService service=new ThemeBroadcastService(mapper,im);
        TopicRouteEngine.State state=new TopicRouteEngine.State();state.getThemeRuntime().putArray("events").addObject().put("id","hello");
        TopicRouteSession s=session("{\"themeConfig\":{}}");
        assertThrows(ServiceException.class,()->service.enqueue(s,state));
        assertThrows(ServiceException.class,()->service.deliver(31L,7L));verifyNoInteractions(mapper,im);
        service.enqueue(s,new TopicRouteEngine.State());verifyNoInteractions(mapper,im);
    }
    @Test void progressInsertionAndPhotoApprovalRejectFrozenThemeBeforeMapperWrite() {
        PlayerNodeProgressMapper mapper=mock(PlayerNodeProgressMapper.class);
        PlayerNodeProgressServiceImpl service=new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(service,"playerNodeProgressMapper",mapper);ReflectionTestUtils.setField(service,"routeSessions",sessions);
        session("{\"themeConfig\":{}}");PlayerNodeProgress row=new PlayerNodeProgress();row.setId(99L);row.setMemberId(7L);row.setTopicId(10L);row.setActivityId(0L);row.setPlayInstanceId("default");
        when(mapper.selectPlayerNodeProgressById(99L)).thenReturn(row);
        assertThrows(ServiceException.class,()->service.insertPlayerNodeProgress(row));
        assertThrows(ServiceException.class,()->service.markPhotoMachineApproved(99L));
        verify(mapper,never()).insertPlayerNodeProgress(any());verify(mapper,never()).markPhotoMachineApproved(anyLong());
    }
    @Test void disabledThemeSnapshotFreezeRejectsBeforeReadingChapterSources() {
        CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(runtime,"freezeJourney","{}",topic,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap()));
        verifyNoInteractions(chapters,keys);
    }
    @Test void invalidSnapshotRejectsBeforeAnyThemeOrChapterWork() {
        CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(runtime,"freezeJourney","{",topic,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap()));
        verifyNoInteractions(chapters,keys);
    }

    private CmsTopicChapter chapter(long id, long sort, String name, String blocks) {
        CmsTopicChapter c=new CmsTopicChapter();c.setId(id);c.setTopicId(10L);c.setSortId(sort);c.setName(name);c.setRequired(1);c.setBlocksJson(blocks);return c;
    }
    @Test void sceneFreezePreservesThreeReadsAnswersOriginalTemplateAndOpeningSource() throws Exception {
        enable();CmsTopic topic=new CmsTopic();topic.setId(10L);
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"currentScene\":\"s\",\"scenes\":[{\"id\":\"s\",\"name\":\"Scene\",\"nodeConfigs\":{\"1\":{\"schemaVersion\":1}},\"journeyStory\":{\"schemaVersion\":1,\"openingChapterId\":11}}]}");
        CmsTopicNode node=new CmsTopicNode();node.setId(1L);node.setTopicId(10L);node.setChapterId(12L);node.setTemplateId(101L);
        CmsMemberTemplate template=new CmsMemberTemplate();template.setId(101L);template.setQuestionAnswer("frozen-answer");
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.old\"}]}");
        CmsTopicChapter narrative=chapter(12L,2L,"narrative","[{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"question\",\"content\":\"frozen narrative\",\"nodeId\":1}]");
        CmsTopicChapter second=chapter(11L,1L,"second observation","[{\"type\":\"text\",\"content\":\"frozen role text\",\"roleKey\":\"detective\"}]");
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(narrative),Arrays.asList(second,chapter(12L,2L,"main","[]")),Collections.singletonList(chapter(11L,1L,"third observation","[]")));
        String frozen=ReflectionTestUtils.invokeMethod(runtime,"freezeJourney","{\"schemaVersion\":1}",topic,Collections.singletonList(node),Collections.singletonMap(101L,template.getAdvancedConfigJson()),Collections.singletonMap(101L,template));
        JsonNode snapshot=json.readTree(frozen);
        assertEquals("third observation",snapshot.path("openingChapter").path("name").asText());
        assertTrue(snapshot.path("journeyStory").asText().contains("11"));
        assertEquals(101L,snapshot.path("nodeTemplateIds").path("1").asLong());
        assertEquals("frozen-answer",snapshot.path("nodeAnswers").path("1").path("questionAnswer").asText());
        assertFalse(snapshot.path("nodeJourney").path("1").has("requires"));
        assertTrue(snapshot.path("nodeJourney").path("1").toString().contains("frozen narrative"));
        assertTrue(snapshot.path("themeStoryChapters").path("11").toString().contains("detective"));
        assertNull(topic.getJourneyStoryJson());
        template.setQuestionAnswer("live change");second.setBlocksJson("[]");topic.setPublishedThemeRuntimeConfigJson(null);
        assertEquals(frozen,snapshot.toString());
        org.mockito.InOrder order=inOrder(chapters,keys);order.verify(chapters).selectCmsTopicChapterList(any());order.verify(keys).selectByTopicId(10L);order.verify(chapters,times(2)).selectCmsTopicChapterList(any());order.verifyNoMoreInteractions();
    }
    private TopicRouteSession endingSession() throws Exception {
        ObjectNode root=json.createObjectNode();root.putObject("themeConfig");root.putObject("journeyRules").put("schemaVersion",1).put("checkEnabled",true);root.putArray("stateKeys");
        root.put("journeyStory","{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"done\",\"title\":\"Frozen title\",\"fallback\":true,\"chapterId\":13}],\"epilogues\":[{\"npcId\":77,\"relationKey\":\"relation.friend\",\"ally\":\"Frozen ally\",\"neutral\":\"Frozen line\",\"guarded\":\"Frozen guarded\"}]}}");
        root.putObject("themeNpcNames").put("77","Frozen NPC");
        ObjectNode chapter=root.putObject("themeStoryChapters").putObject("13");chapter.put("id",13);chapter.put("name","Frozen chapter");chapter.putArray("blocks").addObject().put("type","text").put("content","Frozen content");
        return session(root.toString());
    }
    @Test void endingUsesFrozenRulesNpcAndChapterWithoutLiveReads() throws Exception {
        TopicRouteSession run=endingSession();CmsTopicNodeMapper nodes=mock(CmsTopicNodeMapper.class);
        ThemeStateKeyService registry=mock(ThemeStateKeyService.class);NpcProfileService npcs=mock(NpcProfileService.class);
        PlayJourneyStoryServiceImpl story=new PlayJourneyStoryServiceImpl(topics,chapters,nodes,sessions,registry,npcs);
        ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(story,"themeRuntimePolicy",policy);
        TopicRouteEngine.State state=new TopicRouteEngine.State();state.getTags().add("tag.frozen");
        Map<String,Object> result=story.freezeEnding(run,state);
        assertEquals("Frozen title",result.get("title"));assertEquals("Frozen chapter",((Map<?,?>)result.get("chapter")).get("name"));
        assertEquals("Frozen NPC",((Map<?,?>)((List<?>)result.get("epilogues")).get(0)).get("name"));
        verifyNoInteractions(topics,chapters,nodes,registry,npcs);
    }
    @Test void missingFrozenEndingChapterFailsInsteadOfLoadingLiveChapter() throws Exception {
        TopicRouteSession run=endingSession();ObjectNode root=(ObjectNode)json.readTree(run.getConfigSnapshotJson());root.remove("themeStoryChapters");run.setConfigSnapshotJson(root.toString());
        PlayJourneyStoryServiceImpl story=new PlayJourneyStoryServiceImpl(topics,chapters,null,sessions,null,null);
        ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(story,"themeRuntimePolicy",policy);
        assertThrows(ServiceException.class,()->story.freezeEnding(run,new TopicRouteEngine.State()));verifyNoInteractions(topics,chapters);
    }
    @Test void themeCompletionOverridesFreeExploreReceiptOnlyForFrozenTheme() {
        com.chengyinhub.web.controller.api.ApiPlayProgressController controller=new com.chengyinhub.web.controller.api.ApiPlayProgressController();
        com.chengyinhub.business.service.IPerkLedgerService perks=mock(com.chengyinhub.business.service.IPerkLedgerService.class);
        ReflectionTestUtils.setField(controller,"perkLedgerService",perks);
        CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        CmsRegistration ticket=new CmsRegistration();ticket.setId(77L);
        when(perks.isExploreCompleted(77L,10L)).thenReturn(true);
        for(Integer status:Arrays.asList(null,TopicRouteSession.STATUS_RUNNING,TopicRouteSession.STATUS_ENDED)) {
            com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact fact=
                new com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact(status,"default",true);
            assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(controller,"isCompletedCurrentFact",topic,fact,ticket,10L,2,2));
        }
        com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact completed=
            new com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact(TopicRouteSession.STATUS_COMPLETED,"replay:1",true);
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(controller,"isCompletedCurrentFact",topic,completed,ticket,10L,2,0));
        verifyNoInteractions(perks);
        com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact legacy=
            new com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact(TopicRouteSession.STATUS_RUNNING,"default",false);
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(controller,"isCompletedCurrentFact",topic,legacy,ticket,10L,2,0));
        when(perks.isExploreCompleted(77L,10L)).thenReturn(false);
        assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(controller,"isCompletedCurrentFact",topic,legacy,ticket,10L,2,2));
    }


    private TopicRouteSession historySession(long id,String snapshot) {
        TopicRouteSession s=new TopicRouteSession();s.setId(id);s.setMemberId(7L);s.setTopicId(10L);
        s.setActivityId(0L);s.setPlayInstanceId("run:"+id);s.setVersion(0);s.setStatus(0);
        s.setConfigSnapshotJson(snapshot);s.setStateJson("{\"status\":\"ACTIVE\"}");return s;
    }
    private TopicRouteSession themeHistorySession(long id) {
        return historySession(id,"{\"themeConfig\":{},\"journeyRules\":{\"schemaVersion\":1}}");
    }
    @Test void historyScansPastFullLegacyPageAndUsesTheReturnedThemeCursor() {
        enable();List<TopicRouteSession> legacy=new ArrayList<>();
        for(long id=300;id>200;id--)legacy.add(historySession(id,"{}"));
        when(sessions.selectHistoryPage(7L,10L,null,100)).thenReturn(legacy);
        when(sessions.selectHistoryPage(7L,10L,201L,100)).thenReturn(Arrays.asList(
            themeHistorySession(200),historySession(199,"{}"),themeHistorySession(198),themeHistorySession(197)));
        List<Map<String,Object>> page=runtime.themeHistory(7L,10L,null,2);
        assertEquals(2,page.size());assertEquals(200L,page.get(0).get("sessionId"));assertEquals(198L,page.get(1).get("sessionId"));
        when(sessions.selectHistoryPage(7L,10L,198L,100)).thenReturn(Collections.singletonList(themeHistorySession(197)));
        List<Map<String,Object>> next=runtime.themeHistory(7L,10L,198L,2);
        assertEquals(1,next.size());assertEquals(197L,next.get(0).get("sessionId"));
        verify(sessions,times(3)).selectHistoryPage(eq(7L),eq(10L),nullable(Long.class),eq(100));
        verify(sessions,never()).insertSession(any());verify(sessions,never()).updateStateCas(any());
        verifyNoInteractions(topics,chapters,keys);
    }
    @Test void historyStopsAtRequestedThemeCountWithoutAnExtraRead() {
        enable();when(sessions.selectHistoryPage(7L,10L,null,100)).thenReturn(Arrays.asList(themeHistorySession(50),themeHistorySession(49)));
        List<Map<String,Object>> page=runtime.themeHistory(7L,10L,null,1);
        assertEquals(1,page.size());assertEquals(50L,page.get(0).get("sessionId"));
        verify(sessions).selectHistoryPage(7L,10L,null,100);verifyNoMoreInteractions(sessions);
    }
    @Test void historySkipsEmptyLegacySnapshotsWithoutReadingLiveConfiguration() {
        enable();when(sessions.selectHistoryPage(7L,10L,null,100)).thenReturn(Arrays.asList(
            historySession(53,null),historySession(52," "),historySession(51,"{}"),themeHistorySession(50)));
        List<Map<String,Object>> page=runtime.themeHistory(7L,10L,null,100);
        assertEquals(1,page.size());assertEquals(50L,page.get(0).get("sessionId"));verifyNoInteractions(topics,chapters,keys);
    }
    @Test void historyRejectsCorruptSnapshotsInsteadOfSkippingThem() {
        enable();for(String raw:Arrays.asList("{bad","{\"themeConfig\":null}","{} {}")) {
            when(sessions.selectHistoryPage(7L,10L,null,100)).thenReturn(Collections.singletonList(historySession(50,raw)));
            assertThrows(ServiceException.class,()->runtime.themeHistory(7L,10L,null,1));
        }
        verify(sessions,never()).updateStateCas(any());verifyNoInteractions(topics,chapters,keys);
    }
    @Test void historyRejectsRowsFromAnotherMemberOrTopic() {
        enable();for(int mismatch=0;mismatch<2;mismatch++) {
            TopicRouteSession foreign=themeHistorySession(50);if(mismatch==0)foreign.setMemberId(8L);else foreign.setTopicId(11L);
            when(sessions.selectHistoryPage(7L,10L,null,100)).thenReturn(Collections.singletonList(foreign));
            assertThrows(ServiceException.class,()->runtime.themeHistory(7L,10L,null,1));
        }
    }
    @Test void historyRejectsNonDecreasingCursorAndDuplicateRows() {
        enable();when(sessions.selectHistoryPage(7L,10L,50L,100)).thenReturn(Collections.singletonList(themeHistorySession(50)));
        assertThrows(IllegalStateException.class,()->runtime.themeHistory(7L,10L,50L,1));
        when(sessions.selectHistoryPage(7L,10L,null,100)).thenReturn(Arrays.asList(historySession(50,"{}"),historySession(50,"{}")));
        assertThrows(IllegalStateException.class,()->runtime.themeHistory(7L,10L,null,1));
    }
    /** Receipt tests execute the actual END path first, rather than inventing a stored response. */
    private static final class ActionReceiptFixture {
        final ObjectMapper json=new ObjectMapper();
        final TopicRouteSessionMapper sessions=mock(TopicRouteSessionMapper.class);
        final CmsTopicMapper topics=mock(CmsTopicMapper.class);
        final CmsActivityMapper activities=mock(CmsActivityMapper.class);
        final CmsRegistrationMapper registrations=mock(CmsRegistrationMapper.class);
        final TopicRouteRuntimeServiceImpl runtime=new TopicRouteRuntimeServiceImpl(sessions,topics,null,null,activities,registrations);
        final CmsActivity activity=new CmsActivity();
        final TopicRouteSession session=new TopicRouteSession();
        final Map<String,TopicRouteAction> receipts=new LinkedHashMap<>();
        ActionReceiptFixture() throws Exception {
            ThemeRuntimeFeaturePolicy policy=new ThemeRuntimeFeaturePolicy();ReflectionTestUtils.setField(policy,"enabled",true);ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);
            CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setDelFlag(0);topic.setPublishedConfigVersion(1L);topic.setRouteMode("LINEAR");
            when(topics.selectCmsTopicById(10L)).thenReturn(topic);
            activity.setId(5L);activity.setTopicId(10L);activity.setStatus(1L);activity.setDelFlag(0);when(activities.selectCmsActivityById(5L)).thenReturn(activity);
            when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(new CmsRegistration()));
            ObjectNode config=json.createObjectNode();config.put("schemaVersion",1);config.put("capacity",0);config.putArray("items");
            ObjectNode snapshot=json.createObjectNode();snapshot.set("themeConfig",config);snapshot.putObject("journeyRules").put("schemaVersion",1);
            TopicRouteEngine.State state=new TopicRouteEngine.State();state.setStatus("ACTIVE");state.setCurrentNodeId(11L);state.setRecommendedNodeId(11L);new ThemeRuntimeEngine().initialize(config,state);
            session.setId(31L);session.setMemberId(7L);session.setTopicId(10L);session.setActivityId(5L);session.setPlayInstanceId("default");session.setVersion(0);session.setStatus(0);session.setConfigSnapshotJson(json.writeValueAsString(snapshot));session.setStateJson(json.writeValueAsString(state));
            when(sessions.selectAccessibleById(31L,7L)).thenReturn(session);when(sessions.selectAccessibleByIdForUpdate(31L,7L)).thenReturn(session);
            when(sessions.selectCurrentByOwnerForUpdate(5L,10L,7L)).thenReturn(session);when(sessions.selectByOwnerForUpdate(5L,10L,7L,"default")).thenReturn(session);
            when(sessions.selectActionByAction(eq(31L),eq(7L),anyString())).thenAnswer(i->receipts.get(i.getArgument(2)));
            when(sessions.insertAction(any())).thenAnswer(i->{TopicRouteAction a=i.getArgument(0);a.setId(901L);receipts.put(a.getActionId(),a);return 1;});
            when(sessions.updateActionResponse(eq(901L),anyString())).thenAnswer(i->{receipts.values().iterator().next().setResponseJson(i.getArgument(1));return 1;});
            when(sessions.updateStateCas(any())).thenReturn(1);
        }
        Map<String,Object> end(String key){return runtime.themeAction(7L,31L,session.getVersion(),key,"END",null);}
        JsonNode normalized(Object value)throws Exception{return json.readTree(json.writeValueAsString(value));}
        void reject(boolean terminalEnd,boolean cancelled)throws Exception {
            Map<String,Object> first=end("first-end");assertEquals("ENDED",first.get("status"));assertEquals(1,receipts.size());
            String key=terminalEnd?"another-end":"first-end";
            assertEquals(normalized(first),normalized(end(key))); // Establish the exact formerly-permitted early branch.
            if(cancelled)activity.setCancelTime(new Date());else when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
            clearInvocations(sessions);
            IllegalStateException ex=assertThrows(IllegalStateException.class,()->end(key));
            assertEquals(cancelled?"活动已取消":"请先报名并完成支付",ex.getMessage());
            verify(sessions,never()).selectActionByAction(anyLong(),anyLong(),anyString());verify(sessions,never()).insertAction(any());verify(sessions,never()).updateActionResponse(anyLong(),anyString());verify(sessions,never()).updateStateCas(any());
            assertEquals(1,receipts.size());assertEquals(Integer.valueOf(1),session.getVersion());
        }
    }
    @Test void actionReceiptRejectsRevokedRegistration()throws Exception {new ActionReceiptFixture().reject(false,false);}
    @Test void actionReceiptRejectsCancelledActivity()throws Exception {new ActionReceiptFixture().reject(false,true);}
    @Test void terminalEndRejectsRevokedRegistration()throws Exception {new ActionReceiptFixture().reject(true,false);}
    @Test void terminalEndRejectsCancelledActivity()throws Exception {new ActionReceiptFixture().reject(true,true);}
    @Test void normalActionReceiptAndTerminalEndRemainIdempotent()throws Exception {
        ActionReceiptFixture f=new ActionReceiptFixture();Map<String,Object> first=f.end("first-end");
        assertEquals(f.normalized(first),f.normalized(f.runtime.themeAction(7L,31L,0,"first-end","END",null)));
        assertEquals(f.normalized(first),f.normalized(f.end("terminal-end")));
        verify(f.sessions,times(1)).insertAction(any());verify(f.sessions,times(1)).updateStateCas(any());verify(f.sessions,times(1)).updateActionResponse(anyLong(),anyString());
    }
    @Test void normalActionReceiptStillRejectsChangedPayload()throws Exception {
        ActionReceiptFixture f=new ActionReceiptFixture();f.end("first-end");
        ServiceException changedOperation=assertThrows(ServiceException.class,()->f.runtime.themeAction(7L,31L,0,"first-end","RESCUE",null));
        ServiceException changedContent=assertThrows(ServiceException.class,()->f.runtime.themeAction(7L,31L,0,"first-end","END","different"));
        assertEquals(Integer.valueOf(409),changedOperation.getCode());assertEquals(Integer.valueOf(409),changedContent.getCode());verify(f.sessions,times(1)).insertAction(any());verify(f.sessions,times(1)).updateStateCas(any());
    }

}
