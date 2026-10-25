package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real publication compiler -> stored run JSON -> real Encounter. Mapper/SDK/storage are not emulated acceptance. */
class PublishedNodeContentConsumptionTest {
    private final ObjectMapper json = new ObjectMapper();
    private CmsTopic topic;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private TopicRoutePublicationCompiler compiler;
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);

    @BeforeEach void fixture() {
        topic = new CmsTopic(); topic.setId(73L); topic.setMemberId(5L); topic.setRouteMode("LINEAR");
        topic.setPublishedConfigVersion(1L); topic.setJourneyRulesJson("{\"schemaVersion\":1}");
        node = new CmsTopicNode(); node.setId(101L); node.setTopicId(73L); node.setChapterId(201L);
        node.setTemplateId(501L); node.setStatus(1L); node.setName("原节点"); node.setHookText("原钩子");
        template = new CmsMemberTemplate(); template.setId(501L); template.setValidationMethod(1);
        template.setTitle("原游戏"); template.setStoryText("原故事"); template.setQuestionName("原题干");
        template.setQuestionAnswer("secret-answer"); template.setAnswerReveal("secret-reveal");
        template.setHint1("secret-unused-hint"); template.setAudioUrl("/original/game.mp3");
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"one\",\"label\":\"一\",\"weight\":1}]}}");
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(List.of(template));
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(201L); chapter.setTopicId(73L);
        chapter.setAudioUrl("/original/chapter.mp3");
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(chapter));
        ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
        when(keys.selectByTopicId(73L)).thenReturn(List.of());
        compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, keys,
                Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC), json,
                new TopicRouteConfigValidator(), mock(CmsTopicMapper.class));
    }

    private PlayEncounterServiceImpl encounter(String snapshot) {
        PlayEncounterServiceImpl encounter = new PlayEncounterServiceImpl();
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        when(nodes.selectCmsTopicNodeById(101L)).thenReturn(node);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        ICmsMemberTemplateService drafts = mock(ICmsMemberTemplateService.class);
        when(drafts.selectCmsMemberTemplateById(any())).thenThrow(new AssertionError("published content cannot read author templates"));
        TopicRouteSession session = new TopicRouteSession(); session.setId(9001L); session.setMemberId(7L);
        session.setTopicId(73L); session.setActivityId(0L); session.setVersion(1); session.setConfigSnapshotJson(snapshot);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        when(routes.selectCurrentByOwner(0L, 73L, 7L)).thenReturn(session);
        IShopDayProofService proof = mock(IShopDayProofService.class);
        when(proof.hasArrived(7L, 0L, 101L)).thenReturn(true);
        ICmsTopicChapterService liveChapters = mock(ICmsTopicChapterService.class);
        when(liveChapters.selectCmsTopicChapterById(any())).thenThrow(new AssertionError("published audio cannot read author chapters"));
        ReflectionTestUtils.setField(encounter, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(encounter, "cmsMemberTemplateService", drafts);
        ReflectionTestUtils.setField(encounter, "topicRouteSessionMapper", routes);
        ReflectionTestUtils.setField(encounter, "shopDayProofService", proof);
        ReflectionTestUtils.setField(encounter, "npcProfileService", mock(NpcProfileService.class));
        ReflectionTestUtils.setField(encounter, "playerNodeProgressService", mock(IPlayerNodeProgressService.class));
        ReflectionTestUtils.setField(encounter, "playTeamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(encounter, "cmsTopicChapterService", liveChapters);
        return encounter;
    }

    private TopicRouteRuntimeServiceImpl contentRuntime(TopicRouteSessionMapper routes, CmsTopicMapper topics) {
        return new TopicRouteRuntimeServiceImpl(routes, topics, mock(CmsTopicNodeMapper.class), templates,
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class), null,
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class), null, mock(IShopDayProofService.class),
                () -> 1L, () -> 10, Clock.systemUTC(), com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
    }

    /** Calls the real original decision helper; this is not a transaction/storage acceptance fixture. */
    private boolean journeyConfigured(TopicRouteRuntimeServiceImpl runtime) throws Exception {
        Class<?> type=Class.forName(TopicRouteRuntimeServiceImpl.class.getName()+"$Scope");
        var constructor=type.getDeclaredConstructor(Long.class,Long.class,CmsTopic.class,List.class);
        constructor.setAccessible(true);
        Object scope=constructor.newInstance(7L,0L,topic,List.of());
        return ReflectionTestUtils.invokeMethod(runtime,"journeyConfigured",scope,compiler.probe(List.of(node)));
    }

    @Test void originalFrozenOpeningCheckRequiresAndEffectsStillNeedTheOriginalRun() throws Exception {
        String original=compiler.freezePublication(topic,null);
        var routes=mock(TopicRouteSessionMapper.class);
        var runtime=contentRuntime(routes,mock(CmsTopicMapper.class));
        for (String block:List.of("{\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}",
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.a\",\"value\":1}]}","{\"check\":{\"enabled\":true}}")) {
            var frozen=(ObjectNode)json.readTree(original);
            ((ObjectNode)frozen.path("nodeConfigs")).set("101",json.readTree(block));
            topic.setPublishedRouteGraphJson(frozen.toString());
            assertTrue(journeyConfigured(runtime),block);
        }
        var opening=(ObjectNode)json.readTree(original);
        opening.put("journeyStory","{\"schemaVersion\":1,\"openingChapterId\":201}");
        topic.setPublishedRouteGraphJson(opening.toString()); assertTrue(journeyConfigured(runtime));
        verify(routes,never()).insertSession(any()); verify(routes,never()).updateStateCas(any());
    }

    @Test void existingRunAndOldV1RetainPriorityWhileDamagedNewContentCannotUseLiveFallback() throws Exception {
        var routes=mock(TopicRouteSessionMapper.class);
        var runtime=contentRuntime(routes,mock(CmsTopicMapper.class));
        var old=json.createObjectNode().put("runtimeSnapshotVersion",1);
        topic.setPublishedRouteGraphJson(old.toString()); assertTrue(journeyConfigured(runtime));
        var frozen=(ObjectNode)json.readTree(compiler.freezePublication(topic,null));
        frozen.remove("nodeConfigs"); topic.setPublishedRouteGraphJson(frozen.toString());
        assertThrows(IllegalStateException.class,()->journeyConfigured(runtime));
        var existing=new TopicRouteSession(); existing.setId(81L); existing.setMemberId(7L); existing.setTopicId(73L); existing.setActivityId(0L);
        when(routes.selectCurrentByOwner(0L,73L,7L)).thenReturn(existing);
        assertTrue(journeyConfigured(runtime));
        verify(routes,never()).insertSession(any()); verify(routes,never()).updateStateCas(any());
    }

    @Test void realPublicationStillPresentsOriginalContentAfterTemplateUnbindDeleteAndRepublish() throws Exception {
        String original = compiler.freezePublication(topic, null);
        JsonNode frozen = json.readTree(original);
        assertEquals(1, frozen.path("nodeAnswers").path("101").path("contentSnapshotVersion").asInt());
        assertEquals(501L, NodeJourneySnapshot.answer(original, 101L).path("templateId").asLong());
        topic.setPublishedRouteGraphJson(original);
        node.setTemplateId(null); node.setName("later node name"); node.setHookText("later hook");
        template.setTitle("later game"); template.setQuestionName("later question");
        when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(List.of());

        Map<String, Object> view = encounter(original).resolve(7L, 73L, 0L, 101L);

        assertEquals("原游戏", ((Map<?, ?>) view.get("presentation")).get("title"));
        assertEquals("原故事", ((Map<?, ?>) view.get("presentation")).get("storyText"));
        assertEquals("原钩子", ((Map<?, ?>) view.get("presentation")).get("hook"));
        assertTrue(((List<?>) view.get("preload")).contains("/original/chapter.mp3"));
        assertFalse(json.writeValueAsString(view).contains("secret-"));
        assertEquals(frozen.path("nodeAnswers"), json.readTree(compiler.freezePublishedJourney(topic)).path("nodeAnswers"));
        JsonNode next = json.readTree(compiler.freezePublication(topic, null));
        assertFalse(next.path("nodeAnswers").path("101").hasNonNull("templateId"));
        assertEquals("原游戏", NodeJourneySnapshot.answer(original, 101L).path("templatePresentation").path("title").asText());
    }

    @Test void oldPublishedV1IsNotBackfilledWithANewContentIdentity() throws Exception {
        ObjectNode old = (ObjectNode) json.readTree(compiler.freezePublication(topic, null));
        ObjectNode answer = (ObjectNode) old.path("nodeAnswers").path("101");
        answer.remove(List.of("contentSnapshotVersion", "templateId", "templatePresentation", "nodePresentation", "chapterAudioUrl"));
        old.remove("stateKeySnapshotVersion"); topic.setPublishedRouteGraphJson(old.toString());
        template.setTitle("future content"); node.setTemplateId(null);
        JsonNode resumed = json.readTree(compiler.freezePublishedJourney(topic));
        assertFalse(resumed.path("nodeAnswers").path("101").has("contentSnapshotVersion"));
        assertEquals(answer, resumed.path("nodeAnswers").path("101"));
    }

    @Test void originalNonGenericRunReadsItsSavedConfigAndIdentityWithoutRequiringLiveThemeOrTemplate() throws Exception {
        String original = compiler.freezePublication(topic, null);
        assertFalse(json.readTree(original).path("themeConfig").isObject());
        TopicRouteSession session = new TopicRouteSession(); session.setId(9001L); session.setMemberId(7L);
        session.setTopicId(73L); session.setActivityId(0L); session.setConfigSnapshotJson(original);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[],\"completedNodeIds\":[]}");
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        when(routes.selectCurrentByOwner(0L, 73L, 7L)).thenReturn(session);
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(routes, mock(CmsTopicMapper.class),
                mock(CmsTopicNodeMapper.class), templates, mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class), null,
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class), null, mock(IShopDayProofService.class),
                () -> 1L, () -> 10, Clock.systemUTC(), com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
        clearInvocations(templates);
        node.setTemplateId(null); template.setAdvancedConfigJson(null);
        JsonNode config = runtime.nodeRuntimeConfig(7L, 73L, 101L, 0L);
        assertNotNull(config); assertTrue(config.path("random").path("enabled").asBoolean());
        ((ObjectNode) config.path("random")).put("enabled", false);
        assertTrue(runtime.nodeRuntimeConfig(7L, 73L, 101L, 0L).path("random").path("enabled").asBoolean());
        assertEquals(501L, runtime.nodeAnswers(7L, 73L, 0L, 101L).path("templateId").asLong());
        assertEquals(original, session.getConfigSnapshotJson()); verifyNoInteractions(templates);
        verify(routes, never()).insertSession(any()); verify(routes, never()).updateStateCas(any());
    }

    @Test void noRunReadsOnlyItsAdmittedOrdinaryPublicationWithoutCreatingRuntimeOrInventingTemplate() throws Exception {
        topic.setPublishedRouteGraphJson(compiler.freezePublication(topic, null));
        CmsTopicMapper topics = mock(CmsTopicMapper.class); when(topics.selectCmsTopicById(73L)).thenReturn(topic);
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        TopicRouteRuntimeServiceImpl runtime = contentRuntime(routes, topics);
        clearInvocations(templates); node.setTemplateId(null); template.setQuestionName("later author question");
        JsonNode first = runtime.nodeAnswers(7L, 73L, 0L, 101L);
        assertEquals(501L, first.path("templateId").asLong());
        assertEquals("原题干", first.path("templatePresentation").path("questionName").asText());
        assertTrue(runtime.nodeRuntimeConfig(7L, 73L, 101L, 0L).path("random").path("enabled").asBoolean());
        assertFalse(runtime.queryNodeContentSnapshot(7L, 73L, 0L, null).has("contentRouteSessionId"));
        // A later approved no-template publication is legal for a genuinely absent run, never a fake positive ID.
        topic.setPublishedRouteGraphJson(compiler.freezePublication(topic, null));
        clearInvocations(templates);
        JsonNode plain = runtime.nodeAnswers(7L, 73L, 0L, 101L);
        assertEquals(1, plain.path("contentSnapshotVersion").asInt());
        assertFalse(plain.hasNonNull("templateId")); assertNull(NodeJourneySnapshot.contentTemplate(plain));
        assertEquals(501L, first.path("templateId").asLong());
        verify(routes, never()).insertSession(any()); verify(routes, never()).updateStateCas(any());
        verifyNoInteractions(templates);
    }

    @Test void existingOldRunNeverBorrowsTheNewPublicationToFillHistoricalIdentity() throws Exception {
        topic.setPublishedRouteGraphJson(compiler.freezePublication(topic, null));
        CmsTopicMapper topics = mock(CmsTopicMapper.class); when(topics.selectCmsTopicById(73L)).thenReturn(topic);
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        TopicRouteSession old = new TopicRouteSession(); old.setId(8L); old.setMemberId(7L); old.setTopicId(73L); old.setActivityId(0L);
        old.setConfigSnapshotJson("{\"nodeAnswers\":{\"101\":{\"validationMethod\":1,\"questionAnswer\":\"old-answer\"}}}");
        when(routes.selectCurrentByOwner(0L, 73L, 7L)).thenReturn(old);
        TopicRouteRuntimeServiceImpl runtime = contentRuntime(routes, topics);
        clearInvocations(templates, topics);
        JsonNode answer = runtime.nodeAnswers(7L, 73L, 0L, 101L);
        assertEquals("old-answer", answer.path("questionAnswer").asText());
        assertFalse(answer.has("templateId")); assertFalse(answer.has("contentSnapshotVersion"));
        assertNull(runtime.nodeRuntimeConfig(7L, 73L, 101L, 0L));
        verifyNoInteractions(topics, templates);
        verify(routes, never()).insertSession(any()); verify(routes, never()).updateStateCas(any());
    }

    @Test void ordinaryContentPublicationDoesNotEnableAJourneyOrNewAttributesWhenEntryReadsState() throws Exception {
        topic.setPublishedRouteGraphJson(compiler.freezePublication(topic, null));
        assertEquals(1, json.readTree(topic.getPublishedRouteGraphJson()).path("nodeAnswers").path("101").path("contentSnapshotVersion").asInt());
        CmsTopicMapper topics = mock(CmsTopicMapper.class); when(topics.selectCmsTopicById(73L)).thenReturn(topic);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class); when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        com.chengyinhub.business.mapper.CmsRegistrationMapper registrations = mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(registrations);
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(routes, topics, nodes, templates,
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class), null, registrations, null,
                mock(IShopDayProofService.class), () -> 1L, () -> 10, Clock.systemUTC(),
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.later\",\"label\":\"后来属性\"}]}");
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"effects\":[{\"op\":\"SET\",\"var\":\"counter.later\",\"value\":1}]}");
        clearInvocations(templates);

        com.chengyinhub.business.domain.route.TopicRouteState view = runtime.state(7L, 73L, 0L);

        assertNull(view.getSessionId()); assertEquals("LINEAR", view.getRouteMode()); assertEquals(0, view.getVersion());
        verify(routes, never()).insertSession(any()); verify(routes, never()).updateStateCas(any());
        verifyNoInteractions(templates);
    }

    @Test void existingDefaultMinigameOverridesLaterPublicationAndLegacyAbsenceNeverFabricatesIdentity() throws Exception {
        String original=compiler.freezePublication(topic,null);
        var originalRoot=json.readTree(original);
        var row=new com.chengyinhub.business.domain.GameRuntimeSession();
        row.setId(61L); row.setActivityId(0L); row.setTopicId(73L); row.setNodeId(101L); row.setTemplateId(501L);
        row.setOwnerType(1); row.setOwnerId(7L); row.setPlayInstanceId("default");
        row.setConfigSnapshotJson(originalRoot.path("nodeConfigs").path("101").toString());
        var state=json.createObjectNode(); state.set("originalNodeContent",originalRoot.path("nodeAnswers").path("101"));
        row.setStateJson(state.toString());
        var games=mock(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class);
        when(games.selectDefaultMemberSessionsByTopic(0L,73L,7L)).thenReturn(List.of(row));
        var advanced=new AdvancedGameRuntimeServiceImpl(); ReflectionTestUtils.setField(advanced,"sessionMapper",games);
        var routes=mock(TopicRouteSessionMapper.class); var topics=mock(CmsTopicMapper.class);
        template.setTitle("future title"); template.setQuestionAnswer("future-answer");
        topic.setPublishedRouteGraphJson(compiler.freezePublication(topic,null));
        when(topics.selectCmsTopicById(73L)).thenReturn(topic);
        var runtime=contentRuntime(routes,topics); ReflectionTestUtils.setField(runtime,"advancedGameRuntimeService",advanced);
        clearInvocations(templates);

        JsonNode bulk=runtime.queryNodeContentSnapshot(7L,73L,0L,null);
        assertEquals("原游戏",NodeJourneySnapshot.answerFromSnapshot(bulk,101L).path("templatePresentation").path("title").asText());
        assertEquals("secret-answer",runtime.nodeAnswers(7L,73L,0L,101L).path("questionAnswer").asText());
        assertEquals(json.readTree(row.getConfigSnapshotJson()),runtime.nodeRuntimeConfig(7L,73L,101L,0L));
        row.setStateJson("{}");
        assertNull(runtime.nodeAnswers(7L,73L,0L,101L));
        assertTrue(NodeJourneySnapshot.nodeContentSource(runtime.queryNodeContentSnapshot(7L,73L,0L,null),101L)
                .path("legacyNodeContent").asBoolean());
        verify(routes,never()).insertSession(any()); verify(routes,never()).updateStateCas(any());
        verify(games,never()).insert(any()); verify(games,never()).updateStateCas(any()); verifyNoInteractions(templates);
    }
}
