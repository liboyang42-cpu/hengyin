package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicPublicationService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real facade -> publication owner -> content/structure/reference/gate algorithms.
 * Real runtime/compiler with mapper/quota doubles models call ordering and frozen-route content only;
 * thread flags and controlled rows do not establish DB rollback, auth or live Spring propagation. */
class TopicPublicationDiagnosticsTest {

    // Unit call context only: mapper doubles are not MySQL/Spring rollback evidence.
    private boolean publicationWasActive, publicationWasReadOnly;
    @org.junit.jupiter.api.BeforeEach
    void enterPublicationWriteContext() {
        publicationWasActive = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        publicationWasReadOnly = org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    @org.junit.jupiter.api.AfterEach
    void restorePublicationWriteContext() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(publicationWasActive);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(publicationWasReadOnly);
    }

    private static final long TOPIC = 7L;
    private static final String GRAPH = """
            {"schemaVersion":1,"startNodeId":1,"terminalNodeIds":[2,3],"variables":{"score":0},"edges":[
              {"id":"left","fromNodeId":1,"toNodeId":2,"trigger":{"type":"CHOICE","outcomeCode":"COMPLETED"},"conditions":[{"op":"GTE","var":"score","value":0}]},
              {"id":"right","fromNodeId":1,"toNodeId":3,"trigger":{"type":"CHOICE","outcomeCode":"COMPLETED"},"conditions":[{"op":"GTE","var":"score","value":0}]}],
             "fallbacks":[{"fromNodeId":1,"toNodeId":3}]}
            """;
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
    private final ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
    private com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl runtime;
    private final ThemeStateKeyMapper keyRows = mock(ThemeStateKeyMapper.class);
    private final IQuotaService quota = mock(IQuotaService.class);
    private final IAuditTaskService audit = mock(IAuditTaskService.class);
    private final IUmsCreativeSquareService square = mock(IUmsCreativeSquareService.class);
    private final TopicSelfHostActivityService activity = mock(TopicSelfHostActivityService.class);
    private final Map<Long,CmsMemberTemplate> bindings = new LinkedHashMap<>();
    private final List<CmsTopicNode> nodeRows = new ArrayList<>();
    private CmsTopic topic;
    private CmsTopicServiceImpl facade;

    @BeforeEach void fixture() {
        topic = new CmsTopic(); topic.setId(TOPIC); topic.setMemberId(9L); topic.setStatus(1);
        topic.setProductType(1); topic.setRouteMode("BRANCH_GRAPH"); topic.setRouteGraphJson(GRAPH);
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(11L); chapter.setTopicId(TOPIC);
        for (long id = 1; id <= 3; id++) {
            CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(TOPIC); node.setChapterId(11L);
            node.setStatus(1L); node.setName("同名地点"); nodeRows.add(node);
        }
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(keyRows.lockTopicForStateKeyWrite(TOPIC)).thenReturn(TOPIC);
        when(keyRows.selectByTopicIdForUpdate(TOPIC)).thenReturn(List.of());
        when(keys.listCurrentDeclarations(TOPIC)).thenReturn(List.of());
        when(chapters.selectByTopicForUpdate(TOPIC)).thenReturn(List.of(chapter));
        when(nodes.selectByTopicForUpdate(TOPIC)).thenReturn(nodeRows);
        when(templates.selectCmsMemberTemplateByIdsForUpdate(anyList())).thenAnswer(c -> {
            List<Long> ids = c.getArgument(0); List<CmsMemberTemplate> rows = new ArrayList<>();
            for (Long id : ids) if (bindings.containsKey(id)) rows.add(bindings.get(id)); return rows;
        });
        runtime = spy(new com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl(
                mock(TopicRouteSessionMapper.class), topics, nodes, templates,
                mock(PlayerNodeProgressMapper.class), mock(CmsActivityMapper.class),
                mock(CmsRegistrationMapper.class), mock(PlayerActivityRoleMapper.class),
                () -> 1L, java.time.Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN));
        ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", keyRows);
        when(topics.lockTopicContentBudget(TOPIC)).thenReturn(TOPIC);
        when(chapters.countContentByTopicForUpdate(TOPIC)).thenReturn(1L);
        when(nodes.countContentByTopicForUpdate(TOPIC)).thenReturn(3L);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(chapter));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(nodeRows);
        when(templates.selectCmsMemberTemplateById(anyLong())).thenAnswer(c -> bindings.get(c.getArgument(0)));
        when(templates.selectCmsMemberTemplateByIds(anyList())).thenAnswer(c -> {
            List<Long> ids = c.getArgument(0); List<CmsMemberTemplate> rows = new ArrayList<>();
            for (Long id : ids) if (bindings.containsKey(id)) rows.add(bindings.get(id)); return rows;
        });
        when(topics.publishRouteSnapshot(eq(TOPIC), any(), any())).thenReturn(1);
        TopicContentWriteGuard budget = new TopicContentWriteGuard(topics, chapters, templates, null, null);
        ReflectionTestUtils.setField(budget, "cmsTopicNodeMapper", nodes);
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        ReflectionTestUtils.setField(guard, "contentWriteGuard", budget);
        ReflectionTestUtils.setField(guard, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(guard, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(guard, "cmsMemberTemplateMapper", templates);
        ReflectionTestUtils.setField(guard, "themeStateKeyService", keys);
        TopicStateKeyRefService references = new TopicStateKeyRefService(nodes, templates, chapters);
        ReflectionTestUtils.setField(references, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(guard, "topicStateKeyRefService", references);
        ReflectionTestUtils.setField(guard, "npcProfileMapper", mock(NpcProfileMapper.class));
        ReflectionTestUtils.setField(guard, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(guard, "routeOutcomeReader", new TopicRouteOutcomeReader(nodes, templates));
        TopicPublicationService publication = new TopicPublicationService();
        ReflectionTestUtils.setField(publication, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(publication, "publicationContentGuard", guard);
        ReflectionTestUtils.setField(publication, "themeStateKeyService", keys);
        ReflectionTestUtils.setField(publication, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(publication, "quotaService", quota);
        ReflectionTestUtils.setField(publication, "auditTaskService", audit);
        ReflectionTestUtils.setField(publication, "umsCreativeSquareService", square);
        ReflectionTestUtils.setField(publication, "selfHostActivityService", activity);
        facade = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(facade, "topicPublicationService", publication);
    }

    private void template(long nodeId, long templateId, String raw) {
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(templateId); template.setAdvancedConfigJson(raw);
        bindings.put(templateId, template);
        nodeRows.stream().filter(n -> n.getId() == nodeId).findFirst().orElseThrow().setTemplateId(templateId);
    }
    private void noPublicationEffects() {
        verify(topics, never()).publishRouteSnapshot(any(), any(), any());
        verify(topics, never()).updateCmsTopic(any());
        verify(runtime, never()).freezePublication(any(), any());
        verify(runtime, never()).freezePublication(any(), any(), anyList());
        verify(keys, never()).register(anyLong(), anyList());
        verifyNoInteractions(quota, audit, square, activity);
    }
    private void location(Throwable error, String path) {
        assertTrue(error.getMessage().contains("topicId=7"), error.getMessage());
        assertTrue(error.getMessage().contains("path=" + path), error.getMessage());
    }

    @Test void malformedThemeConfigurationKeepsIllegalArgumentAndAddsOriginalField() {
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":99}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        location(error, "themeRuntimeConfigJson"); assertInstanceOf(IllegalArgumentException.class, error.getCause());
        noPublicationEffects();
    }
    @Test void missingTemplateItemReportsActualNodeAndTemplateDespiteDuplicateDisplayNames() {
        // The existing AdvancedGameConfigValidatorTest.itemConditionsPreserveValidQuantityBoundaries contract.
        String raw = "{\"schemaVersion\":1,\"requires\":[{\"op\":\"ITEM_HELD\",\"itemId\":\"supply\",\"quantity\":1}]}";
        assertNotNull(new AdvancedGameConfigValidator().validateAndNormalize(raw));
        template(2, 200, raw);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("条件引用的背包内容不存在"));
        location(error, "nodes[nodeId=2].template[templateId=200].advancedConfigJson");
        assertNotNull(error.getCause()); noPublicationEffects();
    }
    @Test void foreignJourneyChapterKeepsServiceExceptionAndExistingReferenceExplanation() {
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":999}");
        ServiceException error = assertThrows(ServiceException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("剧情配置引用无效"), error.getMessage());
        assertTrue(error.getMessage().contains("openingChapterId"), error.getMessage());
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("chapterId=999"), error.getMessage());
        location(error, "journeyStoryJson"); noPublicationEffects();
    }
    @Test void nestedSceneReferenceKeepsTheActualSceneIndexInsteadOfPretendingTopLevelStory() {
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"currentScene\":\"summer\",\"scenes\":[{\"id\":\"summer\",\"name\":\"夏天\","
                + "\"journeyStory\":{\"schemaVersion\":1,\"openingChapterId\":999}}]}");
        ServiceException error = assertThrows(ServiceException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("openingChapterId"), error.getMessage());
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("chapterId=999"), error.getMessage());
        location(error, "themeRuntimeConfigJson.scenes[0].journeyStory"); noPublicationEffects();
    }
    @Test void foreignCompletedNodeReportsTheOriginalTemplateAndRetainsScopeRejection() {
        template(2, 200, "{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":999}]}");
        ServiceException error = assertThrows(ServiceException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("NODE_COMPLETED"), error.getMessage());
        assertEquals("主题发布失败:块配置的 NODE_COMPLETED 引用无效 → [节点:同名地点 — NODE_COMPLETED 引用的节点不属于本主题:nodeId=[999]"
                + " [topicId=7, path=nodes[nodeId=2].template[templateId=200].advancedConfigJson]]", error.getMessage());
        location(error, "nodes[nodeId=2].template[templateId=200].advancedConfigJson"); noPublicationEffects();
    }
    @Test void malformedTemplateCaughtByTheEarlierStateKeyCollectorStillHasNodeLocation() {
        template(2, 200, "{");
        ServiceException error = assertThrows(ServiceException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("玩法配置无法解析"));
        location(error, "nodes[nodeId=2].template.advancedConfigJson"); noPublicationEffects();
    }
    @Test void undeclaredRouteVariableIsRejectedByTheExistingStructureValidatorBeforeSnapshot() {
        topic.setRouteGraphJson(GRAPH.replace("\"var\":\"score\"", "\"var\":\"foreign\""));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("使用未声明变量")); location(error, "routeGraphJson");
        assertNotNull(error.getCause()); noPublicationEffects();
    }
    @Test void contradictoryCombinationReachesRealDeterministicGateAndNeverPersists() {
        topic.setRouteGraphJson(GRAPH.replace("{\"op\":\"GTE\",\"var\":\"score\",\"value\":0}",
                "{\"op\":\"GT\",\"var\":\"score\",\"value\":5},{\"op\":\"LT\",\"var\":\"score\",\"value\":3}"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC));
        assertTrue(error.getMessage().contains("路线 edge left 没有确定性可达状态夹具"));
        assertTrue(error.getMessage().contains("routeGraphJson.edges[0]")); assertTrue(error.getMessage().contains("fromNodeId=1, toNodeId=2"));
        location(error, "routeGraphJson"); assertNotNull(error.getCause()); noPublicationEffects();
    }
    @Test void validCompositionPersistsTheSameNormalizedBytesThroughTheOriginalFacade() throws Exception {
        facade.publishApprovedRouteSnapshot(TOPIC);
        Map<Long,Set<String>> outcomes = new LinkedHashMap<>();
        for (long id = 1; id <= 3; id++) outcomes.put(id, Set.of("CHOICE:COMPLETED"));
        String expected = new TopicRouteConfigValidator().validateAndNormalize(GRAPH, Set.of(1L,2L,3L), outcomes);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(topics).publishRouteSnapshot(eq(TOPIC), snapshot.capture(), any());
        // Formal publication now stores the complete frozen content, with the same normalized route fields.
        com.fasterxml.jackson.databind.node.ObjectNode frozen =
                (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot.getValue());
        com.fasterxml.jackson.databind.node.ObjectNode route = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(expected).fieldNames()
                .forEachRemaining(field -> route.set(field, frozen.get(field)));
        assertEquals(expected, route.toString(), "diagnostics must not change valid published route JSON or order");
        assertEquals(1, frozen.path("runtimeSnapshotVersion").asInt());
        assertEquals(3, frozen.path("routeStations").size());
        verify(runtime).freezePublication(eq(topic), eq(expected), eq(List.of()));
        verifyNoInteractions(quota, audit, square, activity);
    }
    @Test void databaseFailureIsPropagatedWithoutBeingRelabeledAsAuthorConfiguration() {
        template(2, 200, "{\"schemaVersion\":1}");
        DataAccessResourceFailureException unavailable = new DataAccessResourceFailureException("controlled storage failure");
        doThrow(unavailable).when(templates).selectCmsMemberTemplateById(200L);
        doThrow(unavailable).when(templates).selectCmsMemberTemplateByIdsForUpdate(argThat(ids -> ids.contains(200L)));
        assertSame(unavailable, assertThrows(DataAccessResourceFailureException.class, () -> facade.publishApprovedRouteSnapshot(TOPIC)));
        noPublicationEffects();
    }
}
