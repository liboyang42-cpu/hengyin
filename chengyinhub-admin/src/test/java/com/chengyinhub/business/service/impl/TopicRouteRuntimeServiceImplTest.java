package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicRouteRuntimeServiceImplTest {
    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
    private final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final PlayerActivityRoleMapper roleMapper = mock(PlayerActivityRoleMapper.class);
    private final IPlayEncounterService encounterService = mock(IPlayEncounterService.class);
    private final CmsTopicChapterMapper cmsTopicChapterMapper = mock(CmsTopicChapterMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession[] stored;
    private TopicRouteAction[] storedAction;

    @BeforeEach
    void setUp() {
        runtime = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper, templateMapper,
                progressMapper, activityMapper, registrationMapper, roleMapper, () -> 12345L,
                Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime, "playEncounterService", encounterService);
        when(encounterService.resolve(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", cmsTopicChapterMapper);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setRouteMode("BRANCH_GRAPH"); topic.setConfigVersion(3L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        topic.setPublishedConfigVersion(3L);
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);
        CmsTopicNode one = node(1L, 1L); CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        CmsRegistration pass = new CmsRegistration(); pass.setExpiresAt(null);
        pass.setId(9000001L);pass.setMemberId(7L);pass.setOwnerType(1L);pass.setOwnerId(10L);pass.setRegistrationStatus(2);pass.setPaymentStatus(0);pass.setPurchaseKind(2);
        // 按查询人/归属返回其本人的成功报名(与 member/owner 过滤的真实查询一致)：7 号自玩仍是原 pass，
        // 其他成员或活动场次各有自己的同口径免费成功报名；锁读按原ID回同一行。用例内显式 stub 仍优先。
        when(activityMapper.selectCmsActivityByIdForUpdate(anyLong()))
                .thenAnswer(call -> activityMapper.selectCmsActivityById(call.<Long>getArgument(0)));
        java.util.Map<Long, CmsRegistration> issued = new java.util.HashMap<>();
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(call -> issued.get(call.<Long>getArgument(0)));
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(9000001L)).thenReturn(pass);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            if (Long.valueOf(7L).equals(probe.getMemberId()) && Long.valueOf(1L).equals(probe.getOwnerType())
                    && Long.valueOf(10L).equals(probe.getOwnerId())) return Collections.singletonList(pass);
            CmsRegistration own = new CmsRegistration();
            own.setId(9_100_000L + probe.getMemberId() * 1_000L + probe.getOwnerType() * 100L + probe.getOwnerId());
            own.setMemberId(probe.getMemberId()); own.setOwnerType(probe.getOwnerType()); own.setOwnerId(probe.getOwnerId());
            own.setRegistrationStatus(2); own.setPaymentStatus(0);
            own.setPurchaseKind(Long.valueOf(2L).equals(probe.getOwnerType()) ? 1 : 2);
            issued.put(own.getId(), own);
            return Collections.singletonList(own);
        });
        stored = new TopicRouteSession[1];
        storedAction = new TopicRouteAction[1];
        when(routeMapper.selectByOwner(0L, 10L, 7L, "default")).thenAnswer(inv -> stored[0]);
        when(routeMapper.insertSession(any(TopicRouteSession.class))).thenAnswer(inv -> {
            TopicRouteSession value = inv.getArgument(0); value.setId(99L); stored[0] = value; return 1;
        });
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction value = inv.getArgument(0); value.setId(501L); storedAction[0] = value; return 1;
        });
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> storedAction[0] != null
                && inv.getArgument(0).equals(storedAction[0].getSessionId())
                && inv.getArgument(1).equals(storedAction[0].getMemberId())
                && inv.getArgument(2).equals(storedAction[0].getActionId()) ? storedAction[0] : null);
        when(routeMapper.selectActionByReceipt(anyLong(), anyString())).thenAnswer(inv -> storedAction[0] != null
                && inv.getArgument(1).equals(storedAction[0].getReceiptId()) ? storedAction[0] : null);
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenAnswer(inv -> {
            if (storedAction[0] != null) storedAction[0].setResponseJson(inv.getArgument(1));
            return 1;
        });
    }

    @Test
    void publishedNestedAttributeDefinitionIsCopiedForNewRunWithoutReadingDraftMetadata() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
        com.fasterxml.jackson.databind.node.ObjectNode published = (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree(runtime.freezePublication(topic, topic.getRouteGraphJson()));
        JsonNode definition = json.readTree("{\"schemaVersion\":1,\"configVersion\":\"3\",\"registryVersion\":\"9007199254740993\",\"attributes\":[]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) published.path("journeyRules")).set("attributeDefinition", definition);
        topic.setPublishedConfigVersion(3L);
        topic.setPublishedRouteGraphJson(published.toString());
        topic.setJourneyRulesJson(null);
        JsonNode frozen = json.readTree(runtime.freezePublishedJourney(topic));
        assertEquals(definition, frozen.path("attributeDefinition"));
        assertEquals(definition, frozen.path("journeyRules").path("attributeDefinition"));
        assertFalse(json.readTree(topic.getPublishedRouteGraphJson()).has("attributeDefinition"));
    }

    @Test
    void fullPublishedSnapshotKeepsRulesAttributesAndNamesWhenDraftChanges() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"hpName\":\"体力\"}");
        topic.setPublishedRouteGraphJson(runtime.freezePublication(topic, topic.getRouteGraphJson()));
        topic.setJourneyRulesJson(null); topic.setThemeRuntimeConfigJson(null); topic.setPublishedThemeRuntimeConfigJson(null);
        JsonNode frozen = json.readTree(runtime.freezePublishedJourney(topic));
        assertEquals(1, frozen.path("runtimeSnapshotVersion").asInt());
        assertEquals(1, frozen.path("journeyRules").path("statLinks").size());
        assertEquals("体力", frozen.path("themeConfig").path("hpName").asText());
        runtime.state(7L, 10L, null);
        assertEquals(8, json.readTree(stored[0].getStateJson()).path("variables").path("counter.temperature").asInt());
        assertEquals(10, json.readTree(stored[0].getStateJson()).path("variables").path("sys.hp").asInt());
    }

    @Test
    void linkedTrustedEffectUsesOneReceiptAndRefreshDoesNotApplyItAgain() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0}");
        runtime.state(7L, 10L, null);
        when(routeMapper.updateStateCas(any())).thenReturn(1);
        TopicRouteEngine.State state = json.readValue(stored[0].getStateJson(), TopicRouteEngine.State.class);
        state.getVariables().put("counter.temperature", 6);
        Map<String, Object> receipt = new LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(runtime, "commitJourneyAction", stored[0], 1L, "effect-once", "CHOICE", receipt, state);
        assertEquals(6, ((Number)receipt.get("hp")).intValue());
        assertEquals(6, json.readTree(stored[0].getStateJson()).path("variables").path("sys.hp").asInt());
        runtime.state(7L, 10L, null);
        assertEquals(6, json.readTree(stored[0].getStateJson()).path("variables").path("sys.hp").asInt());
        assertEquals(1, json.readTree(stored[0].getStateJson()).path("themeRuntime").path("statLinks").path("events").size());
    }

    @Test void emptyPlayerAndTeamCannotPublishWithoutActualTasks() {
        CmsTopic topic=topicMapper.selectCmsTopicById(10L);topic.setRouteMode("LINEAR");
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\",\"survival\":{\"zeroHpPolicy\":\"BLOCK_PAID\"}}}");assertThrows(IllegalArgumentException.class,()->runtime.freezePublication(topic,null));
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"TEAM_INDEPENDENT\"}}");assertThrows(IllegalArgumentException.class,()->runtime.freezePublication(topic,null));
    }

    @Test void publishedLinearFirstRunUsesFrozenAttributesAfterDraftRulesRemoved() throws Exception {
        useLinearJourneyTopic();CmsTopic topic=topicMapper.selectCmsTopicById(10L);topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
        topic.setPublishedRouteGraphJson(runtime.freezePublication(topic,null));topic.setJourneyRulesJson(null);topic.setRouteMode("BRANCH_GRAPH");
        TopicRouteState view=runtime.state(7L,10L,null);assertEquals(TopicRouteState.LINEAR,view.getRouteMode());assertEquals(1L,view.getCurrentNodeId());
        assertEquals(8,json.readTree(stored[0].getStateJson()).path("variables").path("counter.temperature").asInt());
    }

    @Test void publishOnlyConnectedQuestSubsetAndFreezeItsWholeConfig() throws Exception {
        CmsTopic topic=topicMapper.selectCmsTopicById(10L);topic.setRouteMode("LINEAR");topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());topic.setThemeRuntimeConfigJson(GenericRouteRuntimeServiceTest.config());
        CmsTopicNode one=node(11L,1L),two=node(12L,2L);one.setMerchantMemberId(310L);two.setMerchantMemberId(310L);one.setTemplateId(101L);two.setTemplateId(102L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(one,two));
        String qa="{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"prompt\":\"回答\",\"answerText\":\"正确\",\"maxTries\":0}}";
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Arrays.asList(template(101L,qa),template(102L,qa)));
        JsonNode frozen=json.readTree(runtime.freezePublication(topic,null));
        assertEquals(2,frozen.path("themeConfig").path("gameplayRuntime").path("quests").size());assertEquals(2,frozen.path("routeStations").size());
        topic.setThemeRuntimeConfigJson(GenericRouteRuntimeServiceTest.config().replace("TEAM_INDEPENDENT","WORLD_SHARED"));assertNotNull(runtime.freezePublication(topic,null));
        topic.setThemeRuntimeConfigJson(GenericRouteRuntimeServiceTest.config());one.setMerchantMemberId(null);assertNotNull(runtime.freezePublication(topic,null));
    }

    @Test
    void personalR5ScanPublishesButScanAloneIsNotGameplayCompletion() throws Exception {
        CmsTopic topic = personalR5Topic(4, true);
        JsonNode frozen = json.readTree(runtime.freezePublication(topic, null));
        JsonNode config = frozen.path("nodeConfigs").path("1");
        assertNull(com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(config, 4));
        assertThrows(IllegalArgumentException.class, () -> runtime.verifyGuidedRouteAnswer(frozen, 1L, json.createObjectNode()));
        com.fasterxml.jackson.databind.node.ObjectNode answers = json.createObjectNode();
        answers.putObject("qa").put("input", "1930");
        assertTrue(runtime.verifyGuidedRouteAnswer(frozen, 1L, answers));
        assertEquals(1, frozen.path("themeConfig").path("gameplayRuntime").path("quests").size());
    }

    @Test
    void personalR5GpsPublishesWhileDirectAndUnverifiedArrivalStillFail() throws Exception {
        CmsTopic topic = personalR5Topic(5, true);
        JsonNode frozen = json.readTree(runtime.freezePublication(topic, null));
        assertNull(com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(frozen.path("nodeConfigs").path("1"), 5));
        topic = personalR5Topic(0, true); final CmsTopic direct = topic;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(direct, null)).getMessage().contains("个人任务"));
        topic = personalR5Topic(4, false); final CmsTopic noCode = topic;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(noCode, null)).getMessage().contains("真实节点码"));
        topic = personalR5Topic(5, false); final CmsTopic noCoordinates = topic;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(noCoordinates, null)).getMessage().contains("有效站点坐标"));
    }

    @Test
    void legacyBasicParticipationCompletionKindsStayUnchanged() {
        JsonNode empty = json.createObjectNode();
        assertEquals("SCAN", com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(empty, 4));
        assertEquals("GPS", com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(empty, 5));
        assertEquals("ANSWER", com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(empty, 1));
        assertEquals("ANSWER", com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(empty, 3));
        assertNull(com.chengyinhub.business.service.support.GameplaySupportMatrix.basicCompletionKind(empty, 0));
    }

    private CmsTopic personalR5Topic(int method, boolean verified) {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L); topic.setRouteMode("LINEAR");
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true}");
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\",\"survival\":{\"zeroHpPolicy\":\"BLOCK_HP_COST\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\"}}}");
        CmsTopicNode node = node(1L, 1L); node.setTemplateId(101L);
        if (verified) { node.setCheckinCode("r5-personal-node"); node.setLongitude("121.4"); node.setLatitude("31.2"); }
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        CmsMemberTemplate template = template(101L, "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"年份？\",\"answerText\":\"1930\",\"maxTries\":0},\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\"正确\"},\"fail\":{\"reply\":\"错误\"}}}");
        template.setValidationMethod(method);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        return topic;
    }

    @Test
    void r5QaAndCheckRecoveryNodesAreFrozenWithoutOrdinaryQuests() throws Exception {
        for (String gameplay : Arrays.asList("qa", "check")) {
            CmsTopic topic = r5RecoveryTopic(gameplay);
            ObjectNode author = (ObjectNode) json.readTree(topic.getThemeRuntimeConfigJson());
            ObjectNode survival = author.with("gameplayRuntime").with("survival");
            JsonNode challenge = survival.remove("zeroChallenge"); survival.put("zeroHpPolicy", "BLOCK_HP_COST");
            topic.setThemeRuntimeConfigJson(author.toString());
            author = (ObjectNode) json.readTree(runtime.compileTemplateRules(topic));
            assertEquals(3, author.path("gameplayRuntime").path("quests").size());
            survival = author.with("gameplayRuntime").with("survival");
            survival.put("zeroHpPolicy", "CHALLENGE").set("zeroChallenge", challenge);
            Map<Long,Long> rebuilt = new LinkedHashMap<>();
            for (CmsTopicNode node : nodeMapper.selectCmsTopicNodeList(new CmsTopicNode())) {
                rebuilt.put(node.getId(), node.getId()+10); node.setId(node.getId()+10);
            }
            topic.setThemeRuntimeConfigJson(com.chengyinhub.business.service.support.ThemeRuntimeConfig
                    .remapForNodeSave(author.toString(), rebuilt, Collections.emptyMap(), Collections.emptyMap()));
            topic.setThemeRuntimeConfigJson(runtime.compileTemplateRules(topic));
            assertEquals(topic.getThemeRuntimeConfigJson(), runtime.compileTemplateRules(topic));
            JsonNode frozen = json.readTree(runtime.freezePublication(topic, null));
            JsonNode game = frozen.path("themeConfig").path("gameplayRuntime");
            assertEquals(1, game.path("quests").size());
            assertEquals(11, game.path("quests").get(0).path("nodeId").asLong());
            assertEquals(json.readTree("[\"node_1\"]"), game.path("acts").get(0).path("questKeys"));
            assertEquals(12, game.path("survival").path("zeroChallenge").path("challengeNodeId").asLong());
            assertEquals(json.readTree("[13]"), game.path("survival").path("zeroChallenge").path("alternativeNodeIds"));
            for (String id : Arrays.asList("12", "13")) {
                JsonNode block = frozen.path("nodeConfigs").path(id);
                assertEquals("恢复成功", block.path("outcomeRules").path("pass").path("reply").asText());
                assertEquals("再试一次", block.path("outcomeRules").path("fail").path("reply").asText());
                assertEquals("feedback", block.path("outcomeRules").path("pass").path("nextStepId").asText());
                assertEquals("feedback", block.path("branch").path("startStepId").asText());
            }
        }
    }

    @Test
    void r5RecoveryStillRejectsInteractionToolsAndExplicitSettlementQuest() throws Exception {
        CmsTopic topic = r5RecoveryTopic("qa");
        CmsMemberTemplate recovery = templateMapper.selectCmsMemberTemplateByIds(Collections.emptyList()).get(1);
        ObjectNode config = (ObjectNode) json.readTree(recovery.getAdvancedConfigJson());
        config.putObject("interactionTools").put("allowTrade", true);
        recovery.setAdvancedConfigJson(config.toString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(topic, null))
                .getMessage().contains("归零挑战不能配置普通结算工具"));

        config.remove("interactionTools"); recovery.setAdvancedConfigJson(config.toString());
        ObjectNode compiled = (ObjectNode) json.readTree(runtime.compileTemplateRules(topic));
        compiled.with("gameplayRuntime").withArray("quests").addObject().put("questKey", "node_2")
                .put("nodeId", 2).put("actKey", "route_nodes").put("locationKey", "node_2").put("settlementSlot", "node_2").put("hpCost", 1);
        ((ObjectNode) compiled.with("gameplayRuntime").withArray("acts").get(0)).withArray("questKeys").add("node_2");
        topic.setThemeRuntimeConfigJson(compiled.toString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(topic, null))
                .getMessage().contains("归零挑战不能配置普通结算工具"));
    }

    @Test
    void legacyChallengeDoesNotAcquireV2QuestExclusion() throws Exception {
        CmsTopic topic = r5RecoveryTopic("qa");
        ObjectNode author = (ObjectNode) json.readTree(topic.getThemeRuntimeConfigJson());
        author.with("gameplayRuntime").with("survival").set("zeroChallenge",
                json.readTree("{\"challengeNodeId\":2,\"alternativeNodeIds\":[3],\"restoreTo\":4,\"limit\":2}"));
        topic.setThemeRuntimeConfigJson(author.toString());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.compileTemplateRules(topic))
                .getMessage().contains("归零挑战不能配置普通结算工具"));
        assertFalse(json.readTree(topic.getThemeRuntimeConfigJson()).path("gameplayRuntime")
                .path("survival").path("zeroChallenge").has("version"));
        ObjectNode game = author.with("gameplayRuntime");
        game.putArray("acts").addObject().put("actKey", "route_nodes").putObject("completionRule").put("type", "ALL");
        ((ObjectNode) game.withArray("acts").get(0)).putArray("questKeys").add("node_1");
        game.putArray("quests").addObject().put("questKey", "node_1").put("nodeId", 1)
                .put("actKey", "route_nodes").put("locationKey", "node_1").put("settlementSlot", "node_1");
        for (CmsMemberTemplate recovery : templateMapper.selectCmsMemberTemplateByIds(Collections.emptyList()).subList(1, 3)) {
            ObjectNode config = (ObjectNode) json.readTree(recovery.getAdvancedConfigJson());
            config.remove(Arrays.asList("outcomeRules", "branch"));
            recovery.setAdvancedConfigJson(config.toString());
        }
        topic.setThemeRuntimeConfigJson(author.toString());
        JsonNode frozen = json.readTree(runtime.freezePublication(topic, null));
        assertEquals(game.path("survival").path("zeroChallenge"), frozen.path("themeConfig")
                .path("gameplayRuntime").path("survival").path("zeroChallenge"));
    }

    @Test
    void recoveryNodesDoNotReplaceTheRequiredOrdinaryTask() throws Exception {
        CmsTopic topic = r5RecoveryTopic("qa");
        List<CmsTopicNode> nodes = nodeMapper.selectCmsTopicNodeList(new CmsTopicNode());
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(nodes.subList(1, 3));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.freezePublication(topic, null))
                .getMessage().contains("须配置实际幕和任务"));
    }

    private CmsTopic r5RecoveryTopic(String gameplay) throws Exception {
        CmsTopic topic = personalR5Topic(4, true);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":true}");
        ObjectNode author = (ObjectNode) json.readTree(topic.getThemeRuntimeConfigJson());
        ObjectNode survival = author.with("gameplayRuntime").with("survival").put("zeroHpPolicy", "CHALLENGE");
        survival.putObject("zeroChallenge").put("version", 2).put("challengeNodeId", 2).put("gameplayKey", gameplay)
                .put("requiredCompletions", 2).put("withinSeconds", 60).put("restoreTo", 4).put("limit", 2)
                .put("thenPolicy", "THEME_FAIL").putArray("alternativeNodeIds").add(3);
        topic.setThemeRuntimeConfigJson(author.toString());
        CmsTopicNode ordinary = node(1L, 1L); ordinary.setTemplateId(101L); ordinary.setCheckinCode("r5-personal-node");
        CmsTopicNode primary = node(2L, 2L); primary.setTemplateId(102L);
        CmsTopicNode alternative = node(3L, 3L); alternative.setTemplateId(103L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(ordinary, primary, alternative));
        ObjectNode block = json.createObjectNode().put("schemaVersion", 1);
        if ("qa".equals(gameplay)) block.set("qa", json.readTree("{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"口令？\",\"answerText\":\"1930\",\"maxTries\":0}"));
        else block.putObject("check").put("enabled", true).put("checkId", "recover").put("tier", "easy");
        ObjectNode outcomes = block.putObject("outcomeRules").put("version", 1);
        outcomes.putObject("pass").put("reply", "恢复成功").put("nextStepId", "feedback");
        outcomes.putObject("fail").put("reply", "再试一次");
        block.putObject("branch").put("enabled", true).put("startStepId", "feedback").putArray("steps").addObject()
                .put("id", "feedback").put("title", "反馈").put("body", "恢复反馈").put("terminal", true).putArray("options");
        CmsMemberTemplate ordinaryTemplate = templateMapper.selectCmsMemberTemplateByIds(Collections.emptyList()).get(0);
        CmsMemberTemplate recovery = template(102L, block.toString()); recovery.setValidationMethod(0);
        CmsMemberTemplate backup = template(103L, block.toString()); backup.setValidationMethod(0);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Arrays.asList(ordinaryTemplate, recovery, backup));
        return topic;
    }

    @Test
    void a01EndingBodyIsFrozenSeparatelyFromPlayableChapters() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"done\",\"title\":\"结束\",\"fallback\":true,\"chapterId\":90}]}}");
        CmsTopicChapter ending = new CmsTopicChapter();
        ending.setId(90L); ending.setTopicId(10L); ending.setName("原结局");
        ending.setAudioUrl("/fixture-ending.mp3"); ending.setAtmospherePreset("night");
        ending.setBlocksJson("[{\"type\":\"text\",\"content\":\"原正文\"}]");
        CmsTopicChapter foreign = new CmsTopicChapter();
        foreign.setId(90L); foreign.setTopicId(11L); foreign.setName("异主题");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Arrays.asList(ending, foreign));

        runtime.state(7L, 10L, 0L);
        JsonNode frozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertEquals("原结局", frozen.path("endingChapters").path("90").path("name").asText());
        assertEquals("/fixture-ending.mp3", frozen.path("endingChapters").path("90").path("audioUrl").asText());
        assertTrue(frozen.path("endingChapters").path("90").path("blocks").toString().contains("原正文"));
        assertEquals(0, frozen.path("thoughtChapters").size(), "结局不进入可推进章节");
        ending.setName("改后结局"); ending.setBlocksJson("[]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.emptyList());
        runtime.state(7L, 10L, 0L);
        JsonNode restored = json.readTree(stored[0].getConfigSnapshotJson());
        assertEquals(frozen.path("endingChapters"), restored.path("endingChapters"), "续局不重冻正文");
    }

    @Test
    void a01NewSnapshotMarksEvenEmptyEndingBodiesAsFrozen() throws Exception {
        runtime.state(7L, 10L, 0L);
        JsonNode frozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertTrue(frozen.path("endingChapters").isObject());
        assertEquals(0, frozen.path("endingChapters").size());
    }

    @Test
    void branchRouteDoesNotAcquireLinearReceptionOrder() {
        com.chengyinhub.business.service.IGameSessionRuntimeService sessions =
                mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(runtime, "gameSessionRuntimeService", sessions);
        assertTrue(runtime.canPlay(7L, 10L, 0L, 1L));
        verify(sessions, never()).assertRouteReception(any(), any());
    }

    @Test
    void linearRouteChecksReceptionBeforeCreatingJourneyState() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setRouteGraphJson(null);
        topic.setPublishedRouteGraphJson(null);
        CmsActivity activity = new CmsActivity();
        activity.setId(9L); activity.setTopicId(10L);
        activity.setStatus(1L); activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(9L)).thenReturn(activity);
        com.chengyinhub.business.service.IGameSessionRuntimeService sessions =
                mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(runtime, "gameSessionRuntimeService", sessions);
        org.mockito.Mockito.doThrow(new IllegalStateException("请先由商家扫描带队主理人的团码完成上一站接待"))
                .when(sessions).assertRouteReception(9L, 1L);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.canPlay(7L, 10L, 9L, 1L));
        assertTrue(error.getMessage().contains("团码"));
        verify(routeMapper, never()).insertSession(any());
    }

    @Test
    void legacyRequestCannotReadLatestTagsWhenTwoActivitySessionsExist() {
        TopicRouteSession first = new TopicRouteSession();
        first.setId(901L); first.setMemberId(7L); first.setTopicId(10L); first.setActivityId(11L);
        first.setStateJson("{\"tags\":[\"tag.first\"]}");
        TopicRouteSession second = new TopicRouteSession();
        second.setId(902L); second.setMemberId(7L); second.setTopicId(10L); second.setActivityId(12L);
        second.setStateJson("{\"tags\":[\"tag.second\"]}");
        when(routeMapper.selectMemberTopicSessions(7L, 10L)).thenReturn(Arrays.asList(first, second));
        assertThrows(IllegalArgumentException.class, () -> runtime.tags(7L, 10L));
    }

    @Test
    void explicitActivityReadsOnlyItsOwnTagsAndNeverFallsBack() {
        TopicRouteSession first = new TopicRouteSession();
        first.setId(901L); first.setMemberId(7L); first.setTopicId(10L); first.setActivityId(11L);
        first.setStateJson("{\"tags\":[\"tag.first\"]}");
        TopicRouteSession second = new TopicRouteSession();
        second.setId(902L); second.setMemberId(7L); second.setTopicId(10L); second.setActivityId(12L);
        second.setStateJson("{\"tags\":[\"tag.second\"]}");
        when(routeMapper.selectByOwner(11L, 10L, 7L, "default")).thenReturn(first);
        when(routeMapper.selectByOwner(12L, 10L, 7L, "default")).thenReturn(second);
        assertEquals(Collections.singleton("tag.first"), runtime.tags(7L, 10L, 11L));
        assertEquals(Collections.singleton("tag.second"), runtime.tags(7L, 10L, 12L));
        assertTrue(runtime.tags(7L, 10L, 13L).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runtime.tags(7L, 10L, -1L));
        verify(routeMapper, never()).selectMemberTopicSessions(anyLong(), anyLong());
    }

    // 2026-10-14 用户已定：自玩通行证在本局内有效。普通线性路线不建局，以本人默认实例下的节点进度判定已开局。
    private PlayerNodeProgress expiredPassOnLinearRoute() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR"); topic.setPublishedRouteGraphJson(null);
        registrationMapper.selectCmsRegistrationByIdForUpdate(9000001L)
                .setExpiresAt(new java.util.Date(System.currentTimeMillis() - 60_000L));
        PlayerNodeProgress fact = new PlayerNodeProgress(); fact.setNodeId(1L); fact.setStatus(1);
        return fact;
    }

    @Test
    void expiredSelfPlayPassStillFinishesLinearRouteAlreadyStarted() {
        PlayerNodeProgress fact = expiredPassOnLinearRoute();
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(fact));
        assertTrue(runtime.hasRunningSession(7L, 10L, 0L));
        assertNotNull(runtime.state(7L, 10L, 0L), "已开局：中途到期仍可读本局状态");
        assertTrue(runtime.canPlay(7L, 10L, 0L, 2L), "开局时有效、已记过进度：中途到期仍可打完");
    }

    @Test
    void expiredSelfPlayPassCannotStartLinearRoute() {
        expiredPassOnLinearRoute();
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.emptyList());
        assertFalse(runtime.hasRunningSession(7L, 10L, 0L));
        assertThrows(IllegalStateException.class, () -> runtime.state(7L, 10L, 0L));
        assertThrows(IllegalStateException.class, () -> runtime.canPlay(7L, 10L, 0L, 1L));
    }

    @Test
    void expiredSelfPlayPassDoesNotReopenAFinishedRoute() {
        PlayerNodeProgress fact = expiredPassOnLinearRoute();
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(fact));
        TopicRouteSession finished = new TopicRouteSession(); finished.setId(98L); finished.setMemberId(7L);
        finished.setTopicId(10L); finished.setActivityId(0L); finished.setPlayInstanceId("default");
        finished.setStatus(TopicRouteSession.STATUS_COMPLETED); stored[0] = finished;
        assertFalse(runtime.hasRunningSession(7L, 10L, 0L), "已结束的局的旧进度不算在玩");
        assertThrows(IllegalStateException.class, () -> runtime.canPlay(7L, 10L, 0L, 2L));
    }

    @Test
    void textOpeningIsFrozenAndMustBeCompletedBeforeFirstStation() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedRouteGraphJson(null);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        CmsTopicChapter opening = new CmsTopicChapter();
        opening.setId(90L); opening.setTopicId(10L); opening.setName("序章");
        opening.setBlocksJson("[{\"type\":\"text\",\"id\":\"wake\",\"content\":\"医院醒来\"}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        JsonNode first = json.valueToTree(runtime.state(7L, 10L, 0L));
        assertEquals(99L, first.path("sessionId").asLong());
        assertFalse(first.path("opening").path("complete").asBoolean(true));
        assertFalse(runtime.canPlay(7L, 10L, 0L, 1L));
        assertFalse(runtime.isCompleted(7L, 10L, 0L, 2, 2));
        opening.setBlocksJson("[{\"type\":\"text\",\"content\":\"改稿不能覆盖旧局\"}]");
        assertTrue(json.valueToTree(runtime.state(7L, 10L, 0L)).path("opening").toString().contains("医院醒来"));
        assertThrows(ServiceException.class, () -> runtime.completeOpening(7L, 10L, 0L, 90L, 8));
        assertThrows(IllegalArgumentException.class, () -> runtime.completeOpening(7L, 10L, 0L, 91L, 0));

        TopicRouteState done = runtime.completeOpening(7L, 10L, 0L, 90L, 0);
        assertTrue(json.valueToTree(done).path("opening").path("complete").asBoolean());
        assertEquals(1, done.getVersion());
        assertTrue(runtime.canPlay(7L, 10L, 0L, 1L));
        runtime.completeOpening(7L, 10L, 0L, 90L, 0);
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
    }

    @Test
    void openingGameMustSettleBeforeDepartureAndDoesNotRevealFollowingTextEarly() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedRouteGraphJson(null);
        topic.setCompleteRuleJson("{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":1}}");
        nodeMapper.selectCmsTopicNodeById(1L).setTemplateId(901L);
        CmsMemberTemplate photo = new CmsMemberTemplate(); photo.setId(901L);
        photo.setValidationMethod(2); photo.setSensorType("filter_shot");
        when(templateMapper.selectCmsMemberTemplateById(901L)).thenReturn(photo);
        when(templateMapper.selectCmsMemberTemplateByIds(any(List.class))).thenReturn(Collections.singletonList(photo));
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        CmsTopicChapter opening = new CmsTopicChapter();
        opening.setId(90L); opening.setTopicId(10L);
        opening.setBlocksJson("[{\"type\":\"text\",\"content\":\"建档之前\"},"
                + "{\"type\":\"node\",\"nodeId\":1},{\"type\":\"text\",\"content\":\"建档之后\"}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        TopicRouteState first = runtime.state(7L, 10L, 0L);
        assertFalse(json.valueToTree(first).path("opening").toString().contains("建档之后"));
        assertTrue(runtime.canPlay(7L, 10L, 0L, 1L));
        assertFalse(runtime.canPlay(7L, 10L, 0L, 2L));
        PlayerNodeProgress fact = new PlayerNodeProgress(); fact.setNodeId(1L); fact.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(fact));
        assertThrows(IllegalStateException.class, () -> runtime.completeOpening(7L, 10L, 0L, 90L, 0),
                "完成事实尚未权威结算时不能靠出发跳过效果");
        TopicRouteState settled = runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(1L, "COMPLETED", "opening-game"));
        assertTrue(json.valueToTree(settled).path("opening").toString().contains("建档之后"));
        assertNull(settled.getCurrentNodeId(), "出发之前不能推荐第一站");
        assertFalse(runtime.canPlay(7L, 10L, 0L, 2L));
        fact.setNodeId(2L);
        assertThrows(IllegalStateException.class, () -> runtime.advance(7L, 10L, 0L, 1,
                new NodeOutcome(2L, "COMPLETED", "skip-opening")));
        fact.setNodeId(1L);
        topic.setCompleteRuleJson(null);
        TopicRouteState departed = runtime.completeOpening(7L, 10L, 0L, 90L, 1);
        assertEquals(Boolean.TRUE, departed.getOpening().get("themeComplete"), "出发按本局冻结的 N/M 通关规则结算");
        assertEquals("XP_BADGE_ONLY", departed.getOpening().get("rewardPolicy"), "延迟发奖不能放宽滤镜拍照的奖励范围");
        assertEquals(2L, departed.getCurrentNodeId());
        assertTrue(runtime.canPlay(7L, 10L, 0L, 2L));
    }

    @Test
    void openingGameBeforeBranchStartSettlesWithoutReplacingTheRoute() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setPublishedRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":2,\"terminalNodeIds\":[1],\"edges\":["
                + "{\"id\":\"return\",\"fromNodeId\":2,\"toNodeId\":1,\"allowLoop\":true,"
                + "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        CmsTopicChapter opening = new CmsTopicChapter(); opening.setId(90L);
        opening.setBlocksJson("[{\"type\":\"node\",\"nodeId\":1}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        assertEquals(1L, runtime.state(7L, 10L, 0L).getCurrentNodeId());
        assertTrue(runtime.canPlay(7L, 10L, 0L, 1L));
        ServiceException stale = assertThrows(ServiceException.class, () -> runtime.advance(7L, 10L, 0L, 8,
                new NodeOutcome(1L, "COMPLETED", "stale-opening")));
        assertEquals("BRANCH_GRAPH", json.valueToTree(stale.getData()).path("routeState").path("routeMode").asText());
        PlayerNodeProgress fact = new PlayerNodeProgress(); fact.setNodeId(1L); fact.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(fact));
        TopicRouteState settled = runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(1L, "COMPLETED", "opening-outside-graph"));
        assertEquals("BRANCH_GRAPH", settled.getRouteMode());
        assertEquals(2L, json.readTree(stored[0].getStateJson()).path("currentNodeId").asLong());
        TopicRouteState departed = runtime.completeOpening(7L, 10L, 0L, 90L, 1);
        assertEquals(2L, departed.getCurrentNodeId());
        assertEquals(TopicRouteState.PLAYABLE, departed.getNodeStates().get(2L));
    }

    @Test
    void openingDepartureCasFailureCannotBeReportedAsSuccess() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        CmsTopicChapter opening = new CmsTopicChapter(); opening.setId(90L);
        opening.setBlocksJson("[{\"type\":\"text\",\"content\":\"开始\"}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        runtime.state(7L, 10L, 0L);
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(0);
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> runtime.completeOpening(7L, 10L, 0L, 90L, 0));
        assertEquals(409, conflict.getCode());
    }

    @Test
    void openingAccessMustFailClosedWhenSessionCannotBeRead() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        CmsTopicChapter opening = new CmsTopicChapter(); opening.setId(90L);
        opening.setBlocksJson("[{\"type\":\"text\",\"content\":\"开始\"}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        // 建局写入对本次读不可见(并发/回滚):查不到本局 != 这个主题没有开局,不能当成放行理由
        when(routeMapper.insertSession(any(TopicRouteSession.class))).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> runtime.requireOpeningAccess(7L, 10L, 0L, 1L));
    }

    @Test
    void openingAccessRejectsNodelessStartInsteadOfCrashing() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        CmsTopicChapter opening = new CmsTopicChapter(); opening.setId(90L);
        opening.setBlocksJson("[{\"type\":\"text\",\"content\":\"开始\"}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        runtime.state(7L, 10L, 0L);

        // 不带节点上下文的玩法(自玩局 nodeId 为空)在开局未完成时必须被拒,而不是崩在 NPE 上
        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> runtime.requireOpeningAccess(7L, 10L, 0L, null));
        assertEquals("请先完成开局流程", denied.getMessage());
    }

    @Test
    void playerViewCarriesThoughtProgressSoTheMenuCanShowIt() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        TopicRouteState view = runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3040);

        JsonNode thoughts = json.valueToTree(view).path("thoughts");
        assertEquals(1, thoughts.size(), "玩家侧要看得见念头,不然「走够了就想通」全程没有反馈");
        assertEquals("afternoon", thoughts.get(0).path("key").asText());
        assertEquals("那个下午", thoughts.get(0).path("name").asText());
        assertEquals(40, thoughts.get(0).path("progress").asInt());
        assertEquals(120, thoughts.get(0).path("need").asInt());
        assertFalse(thoughts.get(0).path("done").asBoolean(true));
    }

    @Test
    void thoughtsNotYetObtainedStayHiddenFromThePlayer() throws Exception {
        thoughtTopic();
        TopicRouteState view = runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3000);

        assertEquals(0, json.valueToTree(view).path("thoughts").size(),
                "还没得到的念头不能提前露出来 —— 那是剧透");
    }

    @Test
    void openingCompletionGrantsTheThemeAwardFromServiceNotController() throws Exception {
        ITopicCompletionAwardService awards = mock(ITopicCompletionAwardService.class);
        ReflectionTestUtils.setField(runtime, "topicCompletionAwardService", awards);
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedRouteGraphJson(null);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        // 主题只有站点 1,而它就摆在序章里:走完开局玩法就等于整部主题通关。
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node(1L, 1L)));
        CmsTopicChapter opening = new CmsTopicChapter();
        opening.setId(90L); opening.setTopicId(10L);
        opening.setBlocksJson("[{\"type\":\"text\",\"id\":\"wake\",\"content\":\"医院醒来\"},"
                + "{\"type\":\"node\",\"id\":\"n1\",\"nodeId\":1}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        com.fasterxml.jackson.databind.node.ObjectNode state =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(stored[0].getStateJson());
        state.putArray("completedNodeIds").add(1L);
        state.put("status", TopicRouteState.COMPLETED);
        stored[0].setStateJson(state.toString());

        TopicRouteState done = runtime.completeOpening(7L, 10L, 0L, 90L, stored[0].getVersion());

        assertTrue(json.valueToTree(done).path("opening").path("themeComplete").asBoolean(),
                "序章里的玩法就是主题唯一站点,完成即整部通关");
        verify(awards).grantOnce(7L, 10L, 0L, null);
    }

    /** #478-3:序章玩法靠设备自报完成 ⇒ 出发时发的整部通关奖同样封顶为探索值与徽章,不发通关积分与券。 */
    @Test
    void openingThemeAwardIsCappedWhenACompletedNodeReliedOnSelfReport() throws Exception {
        ITopicCompletionAwardService awards = mock(ITopicCompletionAwardService.class);
        ReflectionTestUtils.setField(runtime, "topicCompletionAwardService", awards);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        when(advanced.rewardCappedToXpBadge(7L, 0L, 10L, 1L)).thenReturn(true);
        when(advanced.anyNodeRewardCappedToXpBadge(anyLong(), anyLong(), anyLong(), any())).thenCallRealMethod();
        ReflectionTestUtils.setField(runtime, "advancedGameRuntimeService", advanced);
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedRouteGraphJson(null);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":90}");
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node(1L, 1L)));
        CmsTopicChapter opening = new CmsTopicChapter();
        opening.setId(90L); opening.setTopicId(10L);
        opening.setBlocksJson("[{\"type\":\"node\",\"id\":\"n1\",\"nodeId\":1}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(opening));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        com.fasterxml.jackson.databind.node.ObjectNode state =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(stored[0].getStateJson());
        state.putArray("completedNodeIds").add(1L);
        state.put("status", TopicRouteState.COMPLETED);
        stored[0].setStateJson(state.toString());

        TopicRouteState done = runtime.completeOpening(7L, 10L, 0L, 90L, stored[0].getVersion());

        assertTrue(json.valueToTree(done).path("opening").path("themeComplete").asBoolean());
        verify(awards).grantOnce(7L, 10L, 0L, null, com.chengyinhub.common.constant.RewardPolicy.XP_BADGE_ONLY);
        verify(awards, never()).grantOnce(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void genericAttributesInitializeOnceAndOnlyVisibleFrozenAttributesAreReturned() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"luck\":{\"init\":2},\"attributes\":["
                + "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":8,\"visible\":true},"
                + "{\"key\":\"counter.secret\",\"label\":\"隐藏状态\",\"initial\":6,\"min\":0,\"max\":10,\"visible\":false}]}");
        TopicRouteState first = runtime.state(7L, 10L, null);
        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(2, state.path("variables").path("counter.focus").asInt());
        assertEquals(6, state.path("variables").path("counter.secret").asInt());
        assertEquals(2, state.path("variables").path("sys.luck").asInt());
        JsonNode view = json.valueToTree(first);
        assertEquals(1, view.path("attributes").size());
        assertEquals("专注", view.path("attributes").get(0).path("label").asText());
        assertFalse(view.toString().contains("隐藏状态"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("variables")).put("counter.focus", 5);
        stored[0].setStateJson(state.toString());
        topic.setJourneyRulesJson(topic.getJourneyRulesJson().replace("专注", "改名").replace("\"initial\":2", "\"initial\":7"));
        JsonNode resumed = json.valueToTree(runtime.state(7L, 10L, null));
        assertEquals(5, resumed.path("attributes").get(0).path("value").asInt());
        assertEquals("专注", resumed.path("attributes").get(0).path("label").asText());
        verify(routeMapper, times(1)).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void unpublishedRouteCannotCreateANewBranchSession() {
        topicMapper.selectCmsTopicById(10L).setPublishedRouteGraphJson(null);
        topicMapper.selectCmsTopicById(10L).setPublishedConfigVersion(0L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.state(7L, 10L, 0L));

        assertEquals("路线尚未发布", error.getMessage());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void newSessionUsesPublishedSnapshotInsteadOfNewerDraft() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":2,\"terminalNodeIds\":[1]," +
                "\"edges\":[{\"id\":\"draft-only\",\"fromNodeId\":2,\"toNodeId\":1," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        topic.setConfigVersion(9L);

        TopicRouteState state = runtime.state(7L, 10L, 0L);

        assertEquals(1L, state.getCurrentNodeId());
        assertEquals(Long.valueOf(3L), stored[0].getConfigVersion());
        JsonNode frozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertEquals(json.readTree(topic.getPublishedRouteGraphJson()).path("edges"), frozen.path("edges"),
                "发布快照的路线图部分必须原样冻结");
        assertTrue(frozen.has("nodeJourney"), "建局要把节点旅程段冻结进 nodeJourney");
        assertTrue(frozen.has("journeyRules"), "建局要冻结主题数值规则");
    }

    @Test
    void existingBranchSessionKeepsItsFrozenSnapshotAfterANewerLinearPublish() {
        TopicRouteState opened = runtime.state(7L, 10L, 0L);
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setRouteGraphJson(null);
        topic.setPublishedRouteGraphJson(null);
        topic.setPublishedConfigVersion(4L);

        TopicRouteState resumed = runtime.state(7L, 10L, 0L);

        assertEquals(TopicRouteState.BRANCH_GRAPH, resumed.getRouteMode());
        assertEquals(opened.getSessionId(), resumed.getSessionId());
        assertEquals(1L, resumed.getCurrentNodeId());
    }

    @Test
    void reviewedLinearTopicWithPublishedVersionCanOpenANewRouteState() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedRouteGraphJson(null);
        topic.setPublishedConfigVersion(1L);

        TopicRouteState state = runtime.state(7L, 10L, 0L);

        assertEquals(TopicRouteState.LINEAR, state.getRouteMode());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void branchTopicWithoutPublishedSnapshotCannotCreateSession() {
        topicMapper.selectCmsTopicById(10L).setPublishedRouteGraphJson(null);
        topicMapper.selectCmsTopicById(10L).setPublishedConfigVersion(0L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.state(7L, 10L, 0L));

        assertEquals("路线尚未发布", error.getMessage());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void cancelledActivityCannotOpenARouteSession() {
        CmsActivity activity = playableActivity();
        activity.setCancelTime(new java.util.Date());
        useActivityScope(activity);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.state(7L, 10L, 44L));

        assertEquals("活动已取消", error.getMessage());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void hiddenActivityCannotOpenARouteSession() {
        CmsActivity activity = playableActivity();
        activity.setStatus(0L);
        useActivityScope(activity);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.state(7L, 10L, 44L));

        assertEquals("活动当前不可用", error.getMessage());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void nonActiveDeletionFlagCannotOpenARouteSession() {
        CmsActivity activity = playableActivity();
        activity.setDelFlag(1);
        useActivityScope(activity);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.state(7L, 10L, 44L));

        assertEquals("活动当前不可用", error.getMessage());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void paidPlayerCanOpenRouteAfterActivityLeavesThePublicCatalog() {
        CmsActivity activity = playableActivity();
        activity.setPublishStatus(0L);
        useActivityScope(activity);

        TopicRouteState state = runtime.state(7L, 10L, 44L);

        assertEquals(TopicRouteState.BRANCH_GRAPH, state.getRouteMode());
        assertEquals(1L, state.getCurrentNodeId());
        verify(routeMapper).insertSession(any(TopicRouteSession.class));
    }

    @Test
    void startsFromFrozenSnapshotAndAdvancesWithCasAndReceipt() {
        TopicRouteState initial = runtime.state(7L, 10L, 0L);
        assertEquals(99L, initial.getSessionId());
        assertEquals(1L, initial.getCurrentNodeId());
        assertEquals(0, initial.getVersion());

        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0, "client-action-1", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));
        assertEquals(2L, advanced.getCurrentNodeId());
        assertEquals(1, advanced.getVersion());
        assertEquals("COMPLETED", advanced.getNodeStates().get(1L));
        verify(routeMapper).insertAction(any(TopicRouteAction.class));
        verify(routeMapper).updateStateCas(any(TopicRouteSession.class));
        assertEquals("client-action-1", storedAction[0].getActionId());
        assertEquals(true, storedAction[0].getReceiptId().startsWith("v0:"));

        TopicRouteState replay = runtime.advance(7L, 10L, 0L, 0, "client-action-1", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "different-server-receipt"));
        assertEquals(1, replay.getVersion());
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
        assertEquals(true, runtime.canPlay(7L, 10L, 0L, 1L, "client-action-1"),
                "同 actionId 重试必须先命中幂等回执，再绕过 COMPLETED 写入拒绝");
        when(nodeMapper.selectCmsTopicNodeById(3L)).thenReturn(node(3L, 3L));
        assertEquals(false, runtime.canPlay(7L, 10L, 0L, 3L, "client-action-1"),
                "同 actionId 不得绕过其他节点的可玩权限");
    }

    /**
     * R22-A7:分支推进回执顶层补 hp / luck 当前值(与线性局、ask / call / 检定同口径)——
     * 站末恢复与阶段休整只在推进里发生,客户端不必再拉一次 vitals;重放回执同样带。
     *
     * <p>负控:advance 去掉 putVitals,本用例立刻红。
     */
    // ==================== 思维阁:到站同步步数 ====================
    // 累积源是真实步数(用户拍板),但 wx.getWeRunData 只给**今日累计**且授权要挂用户手势,
    // 所以做不到原型那种实时累加:到站时同步一次,按「今日步数 - 上次读数」给增量。
    // service 只收已解密的步数 —— 解密是 IO 适配,累积才是要测的逻辑。

    private void thoughtTopic() {
        thoughtTopic("[{\"type\":\"thought\",\"thoughtKey\":\"afternoon\"},{\"type\":\"node\",\"nodeId\":1}]", 1L);
    }

    private void thoughtTopic(String blocks, Long chapterId) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(chapterId); chapter.setTopicId(10L); chapter.setSortId(chapterId); chapter.setRequired(1);
        chapter.setBlocksJson(blocks);
        nodeMapper.selectCmsTopicNodeById(1L).setChapterId(1L);
        nodeMapper.selectCmsTopicNodeById(2L).setChapterId(2L);
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"luck\":{\"enabled\":true,\"max\":3,\"init\":1},"
                        + "\"thoughts\":[{\"key\":\"afternoon\",\"name\":\"那个下午\",\"need\":120}]}");
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        runtime.state(7L, 10L, 0L);
    }

    private JsonNode stateJson() throws Exception {
        return json.readTree(stored[0].getStateJson());
    }

    @Test
    void firstThoughtSyncOnlyRecordsBaseline() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);

        assertEquals(0, stateJson().path("variables").path("thought.afternoon").asInt(-1),
                "第一次同步只知道今天走了多少,不知道这一局走了多少 —— 只能记基线");
        assertEquals(3000, stateJson().path("variables").path("sys.steps.last").asInt());
    }

    @Test
    void thoughtAccumulatesDeltaAndRaisesLuckCeilingOnceSettled() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        TopicRouteState after = runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3150);

        assertEquals(120, stateJson().path("variables").path("thought.afternoon").asInt(),
                "150 步只记到阈值为止,不超记");
        assertTrue(stateJson().path("tags").toString().contains("thought.afternoon.done"));
        assertEquals(4, stateJson().path("variables").path("sys.luck.max").asInt(),
                "想通了 ⇒ 幸运上限 3 抬到 4");
        assertNotNull(after);
    }

    @Test
    void repeatedSyncOfSameStepCountDoesNotDoubleCount() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3050);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3050);

        assertEquals(50, stateJson().path("variables").path("thought.afternoon").asInt(),
                "同一个读数同步两次不能算两次");
    }

    @Test
    void outOfOrderStepReadingsCannotDoubleCountAndCompletedSessionCannotChange() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3050);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3050);
        assertEquals(50, stateJson().path("variables").path("thought.afternoon").asInt());
        stored[0].setStatus(TopicRouteSession.STATUS_COMPLETED);
        String before = stored[0].getStateJson();
        assertThrows(IllegalStateException.class, () -> runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3100));
        assertEquals(before, stored[0].getStateJson());
    }

    @Test
    void crossDayStepResetDoesNotSubtractProgress() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), 8000);
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) stateJson();
        com.fasterxml.jackson.databind.node.ObjectNode variables = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("variables");
        variables.put("sys.steps.day", variables.get("sys.steps.day").asLong() - 1);
        stored[0].setStateJson(json.writeValueAsString(root));
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 120);

        assertEquals(120, stateJson().path("variables").path("thought.afternoon").asInt(),
                "已有伏笔跨日计入当天完整读数，不倒扣、不虚构前日尾段");
        assertEquals(120, stateJson().path("variables").path("sys.steps.last").asInt(),
                "基线要跟着新的一天走,否则之后一整天都累积不上");
    }

    // ==================== 伏笔:由选项/条件得到与想通(2026-09-22 用户裁决) ====================
    // 「伏笔不能只是微信步数,回答出一些特定选项和条件的时候才有」:选项效果贴的标签
    // (ADD_TAG tag.xxx)落进本局后,when 满足即得到、doneWhen 满足即想通。

    private void conditionThoughtTopic() {
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"luck\":{\"enabled\":true,\"max\":3,\"init\":1},"
                        + "\"thoughts\":[{\"key\":\"window\",\"name\":\"那扇窗\","
                        + "\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.saw_window\"}],"
                        + "\"doneWhen\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.opened_window\"}]}]}");
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        runtime.state(7L, 10L, 0L);
    }

    private void addTag(String tag) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) stateJson();
        com.fasterxml.jackson.databind.node.ArrayNode tags = root.has("tags") && root.get("tags").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) root.get("tags") : root.putArray("tags");
        tags.add(tag);
        stored[0].setStateJson(json.writeValueAsString(root));
    }

    @Test
    void chosenOptionTagObtainsThoughtWithoutReadingItsBlock() throws Exception {
        conditionThoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), -1);
        assertTrue(stateJson().path("variables").path("thought.window").isMissingNode(), "没选到那个选项 ⇒ 还没有");

        addTag("tag.saw_window");
        TopicRouteState view = runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), -1);
        assertEquals(0, stateJson().path("variables").path("thought.window").asInt(-1), "选到了 ⇒ 得到");
        assertEquals("window", String.valueOf(view.getThoughts().get(0).get("key")));
        assertTrue(stateJson().path("variables").path("sys.steps.last").isMissingNode(),
                "没带微信运动读数(-1)⇒ 不碰步数基线");
    }

    @Test
    void chosenOptionTagSettlesThoughtAndRaisesLuckCeiling() throws Exception {
        conditionThoughtTopic();
        addTag("tag.saw_window");
        addTag("tag.opened_window");
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), -1);

        assertTrue(stateJson().path("tags").toString().contains("thought.window.done"), "条件满足 ⇒ 想通");
        assertEquals(4, stateJson().path("variables").path("sys.luck.max").asInt(), "想通照样幸运上限 +1");
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), -1);
        assertEquals(4, stateJson().path("variables").path("sys.luck.max").asInt(), "已想通的不重复加");
    }

    @Test
    void doneConditionDoesNotSettleAThoughtNotYetObtained() throws Exception {
        conditionThoughtTopic();
        addTag("tag.opened_window");
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), -1);
        assertFalse(stateJson().path("tags").toString().contains("thought.window.done"),
                "还没得到就想通 = 伏笔没埋就应验");
    }

    @Test
    void legacySnapshotCannotClaimAThoughtWithoutFrozenBlockEvidence() throws Exception {
        thoughtTopic();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree(stored[0].getConfigSnapshotJson());
        snapshot.remove("thoughtChapters");
        stored[0].setConfigSnapshotJson(snapshot.toString());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1));
    }

    @Test
    void thoughtAfterUnfinishedNodeAndFutureChapterCannotBeClaimed() {
        thoughtTopic("[{\"type\":\"node\",\"nodeId\":1},{\"type\":\"thought\",\"thoughtKey\":\"afternoon\"}]", 1L);
        assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1));
        stored[0] = null;
        thoughtTopic("[{\"type\":\"thought\",\"thoughtKey\":\"afternoon\"},{\"type\":\"node\",\"nodeId\":2}]", 2L);
        assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1));
    }

    @Test
    void thoughtConditionUsesFrozenBlockAndCurrentTags() throws Exception {
        thoughtTopic("[{\"type\":\"thought\",\"thoughtKey\":\"afternoon\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.met\"}}]", 1L);
        CmsTopicChapter live = cmsTopicChapterMapper.selectCmsTopicChapterList(new CmsTopicChapter()).get(0);
        live.setBlocksJson("[{\"type\":\"thought\",\"thoughtKey\":\"afternoon\"}]");
        assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1));
        TopicRouteEngine.State state = json.readValue(stored[0].getStateJson(), TopicRouteEngine.State.class);
        state.getTags().add("tag.met");
        stored[0].setStateJson(json.writeValueAsString(state));
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1);
        assertEquals(0, stateJson().path("variables").path("thought.afternoon").asInt(-1));
    }

    @Test
    void claimWorksWithoutWeRunReading() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.singletonList("afternoon"), -1);
        assertEquals(0, stateJson().path("variables").path("thought.afternoon").asInt(-1),
                "没授权微信运动也得到了念头(读到伏笔块就算)");
        assertTrue(stateJson().path("variables").path("sys.steps.last").isMissingNode());
    }

    @Test
    void playerViewCountsConfiguredThoughtsSoTheClientKnowsToAskForWeRun() throws Exception {
        thoughtTopic();
        TopicRouteState view = runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), -1);
        assertEquals(1, view.getThoughtCount(), "一个都没得到也要让客户端知道这局有伏笔 —— 开局才会请求授权");
    }

    @Test
    void unclaimedThoughtDoesNotAccumulate() throws Exception {
        thoughtTopic();
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 3000);
        runtime.syncThoughts(7L, 10L, 0L, Collections.<String>emptyList(), 9000);

        assertTrue(stateJson().path("variables").path("thought.afternoon").isMissingNode(),
                "还没在故事里得到这个念头,就不该替玩家开始累积");
    }

    /**
     * 思维阁(2026-09-22 用户拍板):想通一个念头把幸运上限 +1,**运行时上限可以超过配置值**。
     * 配置管起点、念头管成长 —— 否则 luck.max 已经配满的主题里思维阁等于没有。
     *
     * 负控:把 luckMaxOf 换回 rules.getLuck().getMax(),本用例立刻红(封顶回 3)。
     */
    @Test
    void thoughtRaisedLuckCeilingBeatsConfiguredMax() {
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},"
                        + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":3}}");
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(one, node(2L, 2L)));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        CmsMemberTemplate effects = template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(effects));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(effects);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        // 想通了一个念头 ⇒ 运行时上限抬到 4(配置写的是 3)
        stored[0].setStateJson(stored[0].getStateJson()
                .replace("\"sys.luck\":3", "\"sys.luck\":3,\"sys.luck.max\":4"));

        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0, "ceiling-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(Integer.valueOf(4), advanced.getLuck(),
                "念头抬过上限之后,幸运要能涨到 4;被配置的 3 封顶就是思维阁白做了");
    }

    @Test
    void branchAdvanceReceiptCarriesCurrentHpAndLuck() {
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},"
                        + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":2}}");
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(one, node(2L, 2L)));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        CmsMemberTemplate effects = template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(effects));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(effects);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0, "vitals-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(Integer.valueOf(5), advanced.getHp());
        assertEquals(Integer.valueOf(3), advanced.getLuck(), "选择效果加 1 点幸运后的当前值");

        TopicRouteState replayed = runtime.advance(7L, 10L, 0L, 0, "vitals-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(Integer.valueOf(5), replayed.getHp(), "重放回执同样带当前值");
        assertEquals(Integer.valueOf(3), replayed.getLuck());
    }

    @Test
    void staleVersionConflictCarriesConflictCodeAndPersistsNoAction() {
        runtime.state(7L, 10L, 0L);

        ServiceException error = assertThrows(ServiceException.class, () -> runtime.advance(7L, 10L, 0L, 9,
                new NodeOutcome(1L, "COMPLETED", "action-stale")));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        assertEquals("路线状态已更新，请刷新后重试", error.getMessage());
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
    }

    @Test
    void advanceReceiptCarriesNewVersionAndRefreshedEncounter() {
        runtime.state(7L, 10L, 0L);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", 2L);
        encounter.put("stateVersion", 1);
        when(encounterService.resolve(7L, 10L, 0L, 2L)).thenReturn(encounter);

        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0, "world-1", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(1, advanced.getVersion(), "回包自带新 version");
        assertNotNull(advanced.getEncounter(), "回包自带下一拍 Encounter");
        assertEquals(2L, advanced.getEncounter().get("nodeId"));
        assertTrue(storedAction[0].getResponseJson().contains("\"encounter\""), "世界随回执落库,重放同一份");
    }

    @Test
    void staleVersionConflictCarriesCurrentWorldAndEncounter() {
        runtime.state(7L, 10L, 0L);
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", 1L);
        encounter.put("stateVersion", 0);
        when(encounterService.resolve(7L, 10L, 0L, 1L)).thenReturn(encounter);

        ServiceException error = assertThrows(ServiceException.class, () -> runtime.advance(7L, 10L, 0L, 9,
                new NodeOutcome(1L, "COMPLETED", "action-stale")));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        Map<?, ?> data = (Map<?, ?>) error.getData();
        assertNotNull(data, "旧 version 被拒必须随包带当前状态");
        TopicRouteState current = (TopicRouteState) data.get("routeState");
        assertNotNull(current);
        assertEquals(0, current.getVersion(), "当前本局 version");
        assertNotNull(current.getEncounter());
        assertEquals(1L, current.getEncounter().get("nodeId"));
    }

    @Test
    void missingExpectedVersionIsPlainMissingParamNotConflict() {
        runtime.state(7L, 10L, 0L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runtime.advance(7L, 10L, 0L, null,
                        new NodeOutcome(1L, "COMPLETED", "player-node-progress:701")));

        assertEquals("expectedVersion 不能为空", error.getMessage());
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
    }

    @Test
    void sameActionIdWithDifferentPayloadReplaysWithoutConflictCode() {
        runtime.state(7L, 10L, 0L);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        TopicRouteState first = runtime.advance(7L, 10L, 0L, 0, "client-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));
        TopicRouteState replayed = runtime.advance(7L, 10L, 0L, 1, "client-action", "CHOICE",
                new NodeOutcome(2L, "COMPLETED", "player-node-progress:999"));

        assertEquals(first.getVersion(), replayed.getVersion());
        assertEquals(first.getCurrentNodeId(), replayed.getCurrentNodeId());
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
        verify(routeMapper, times(1)).insertAction(any(TopicRouteAction.class));
    }

    @Test
    void lostCasRaceConflictCarriesConflictCode() {
        runtime.state(7L, 10L, 0L);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class, () -> runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701")));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
    }

    @Test
    void nonConflictRouteErrorStaysPlainIllegalStateExceptionWithoutCode() {
        runtime.state(7L, 10L, 0L);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(3L, "COMPLETED", "player-node-progress:701")));

        assertEquals("节点当前不可用", error.getMessage());
    }

    @Test
    void unavailableTargetIsFilteredFromFrozenGraphAndUsesFallback() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[{\"id\":\"closed\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}");
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        CmsTopicNode one = node(1L, 1L); CmsTopicNode two = node(2L, 2L); CmsTopicNode three = node(3L, 3L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(one, two, three), Arrays.asList(one, three));
        runtime.state(7L, 10L, 0L);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(1L, "COMPLETED", "fallback-action"));
        assertEquals(3L, advanced.getCurrentNodeId());
        assertEquals("FALLBACK", advanced.getDecisionLog().get(0).get("reason"));
    }

    @Test
    void anySuccessfulRegistrationCanAuthorizeARestrictedCandidate() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[{\"id\":\"restricted\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]," +
                "\"nodeRequirements\":[{\"nodeId\":2,\"allowedTicketIds\":[7]}]}" );
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        CmsTopicNode one = node(1L, 1L); CmsTopicNode two = node(2L, 2L); CmsTopicNode three = node(3L, 3L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two, three));
        when(nodeMapper.selectCmsTopicNodeById(3L)).thenReturn(three);
        CmsRegistration denied = registration(8L, 1); CmsRegistration allowed = registration(7L, 1);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Arrays.asList(denied, allowed));
        runtime = policyRuntime();
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(1L, "COMPLETED", "multi-registration"));

        assertEquals(2L, advanced.getCurrentNodeId());
        assertEquals("MATCHED", advanced.getDecisionLog().get(0).get("reason"));
    }

    @Test
    void expiredSelfPlayRegistrationDoesNotAuthorizeARestrictedCandidate() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[{\"id\":\"restricted\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]," +
                "\"nodeRequirements\":[{\"nodeId\":2,\"allowedTicketIds\":[7]}]}" );
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, 1L), node(2L, 2L), node(3L, 3L)));
        CmsRegistration expiredAllowed = registration(7L, 1);
        expiredAllowed.setExpiresAt(new java.util.Date(0L));
        CmsRegistration currentDenied = registration(8L, 1);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Arrays.asList(expiredAllowed, currentDenied));
        runtime = policyRuntime();
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0,
                new NodeOutcome(1L, "COMPLETED", "expired-registration"));

        assertEquals(3L, advanced.getCurrentNodeId());
        assertEquals("FALLBACK", advanced.getDecisionLog().get(0).get("reason"));
    }

    @Test
    void roleRestrictedCandidateFallsBackUsingTheStoredActivityRole() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"edges\":[{\"id\":\"navigator-only\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]," +
                "\"nodeRequirements\":[{\"nodeId\":2,\"requiredRoleCodes\":[\"NAVIGATOR\"]}]}" );
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        CmsActivity activity = playableActivity();
        when(activityMapper.selectCmsActivityById(44L)).thenReturn(activity);
        when(routeMapper.selectByOwner(44L, 10L, 7L, "default")).thenAnswer(inv -> stored[0]);
        CmsTopicNode one = node(1L, 1L); CmsTopicNode two = node(2L, 2L); CmsTopicNode three = node(3L, 3L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two, three));
        // registration() 内含锁读 stub，不能嵌在 when(...).thenReturn 里；本例是活动 44 的场次报名。
        CmsRegistration activityTicket = registration(7L, 1);
        activityTicket.setOwnerType(2L); activityTicket.setOwnerId(44L);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(activityTicket));
        PlayerActivityRole role = new PlayerActivityRole(); role.setRoleCode("OBSERVER");
        when(roleMapper.selectByActivityAndMember(44L, 7L)).thenReturn(role);
        runtime = policyRuntime();
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 44L);
        TopicRouteState advanced = runtime.advance(7L, 10L, 44L, 0,
                new NodeOutcome(1L, "COMPLETED", "role-fallback"));

        assertEquals(3L, advanced.getCurrentNodeId());
        assertEquals("FALLBACK", advanced.getDecisionLog().get(0).get("reason"));
        verify(roleMapper).selectByActivityAndMember(44L, 7L);
    }

    @Test
    void stableProgressReceiptCanAdvanceTheSameNodeAgainOnAnAuthorizedLoopVisit() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[3]," +
                "\"edges\":[{\"id\":\"to-two\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"allowLoop\":true,\"maxVisits\":3," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}," +
                "{\"id\":\"back-one\",\"fromNodeId\":2,\"toNodeId\":1," +
                "\"allowLoop\":true,\"maxVisits\":3," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}," +
                "{\"id\":\"finish\",\"fromNodeId\":2,\"toNodeId\":3," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"FINISH\"}}]," +
                "\"fallbacks\":[{\"fromNodeId\":2,\"toNodeId\":3}]}");
        topic.setPublishedRouteGraphJson(topic.getRouteGraphJson());
        CmsTopicNode three = node(3L, 3L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, 1L), node(2L, 2L), three));
        when(nodeMapper.selectCmsTopicNodeById(3L)).thenReturn(three);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        List<TopicRouteAction> actions = new ArrayList<TopicRouteAction>();
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction value = inv.getArgument(0); value.setId(600L + actions.size());
            actions.add(value); storedAction[0] = value; return 1;
        });
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> actions.stream()
                .filter(a -> inv.getArgument(0).equals(a.getSessionId())
                        && inv.getArgument(1).equals(a.getMemberId())
                        && inv.getArgument(2).equals(a.getActionId())).findFirst().orElse(null));
        when(routeMapper.selectActionByReceipt(anyLong(), anyString())).thenAnswer(inv -> actions.stream()
                .filter(a -> inv.getArgument(0).equals(a.getSessionId())
                        && inv.getArgument(1).equals(a.getReceiptId())).findFirst().orElse(null));
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenAnswer(inv -> {
            actions.stream().filter(a -> inv.getArgument(0).equals(a.getId())).findFirst().get()
                    .setResponseJson(inv.getArgument(1));
            return 1;
        });

        runtime.state(7L, 10L, 0L);
        runtime.advance(7L, 10L, 0L, 0, "visit-one-1", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));
        runtime.advance(7L, 10L, 0L, 1, "visit-two-1", "CHOICE",
                new NodeOutcome(2L, "COMPLETED", "player-node-progress:702"));
        TopicRouteState secondVisit = runtime.advance(7L, 10L, 0L, 2, "visit-one-2", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(3, secondVisit.getVersion(), "同节点的下一次权威访问不能被旧 progress receipt 误判重放");
        assertEquals(3, actions.size());
    }

    @Test
    void stateFailsClosedWhenCurrentNodeWasTakenOfflineAfterSessionStarted() {
        CmsTopicNode one = node(1L, 1L); CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(one, two), Arrays.asList(one, two), Collections.singletonList(two));

        runtime.state(7L, 10L, 0L);
        TopicRouteState unavailable = runtime.state(7L, 10L, 0L);

        assertEquals("UNAVAILABLE", unavailable.getStatus());
        assertEquals(1L, unavailable.getCurrentNodeId());
    }

    @Test
    void sessionStatusesBatchKeepsActivityScopeAndCompletionStatus() {
        TopicRouteSession scope = new TopicRouteSession();
        scope.setMemberId(7L); scope.setTopicId(10L); scope.setActivityId(44L);
        TopicRouteSession session = new TopicRouteSession();
        session.setMemberId(7L); session.setTopicId(10L); session.setActivityId(44L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        when(routeMapper.selectByOwners(Collections.singletonList(scope)))
                .thenReturn(Collections.singletonList(session));

        Map<String, Integer> statuses = runtime.sessionStatuses(Collections.singletonList(scope));

        assertEquals(Integer.valueOf(TopicRouteSession.STATUS_COMPLETED),
                statuses.get(ITopicRouteRuntimeService.sessionKey(7L, 10L, 44L)));
        assertEquals(1, statuses.size());
    }

    /**
     * 节点玩法模板的 effects 要在推进时写进本局状态,并且和 engine.advance 一起走同一次 CAS;
     * 同一 routeActionId 重放走幂等回执,不二次写入。
     */
    @Test
    void completedNodeEffectsLandInSessionStateAndReplayDoesNotApplyThemTwice() throws Exception {
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        CmsMemberTemplate effectsTemplate = template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1},"
                        + "{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(effectsTemplate));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(effectsTemplate);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0, "effects-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(1, state.path("variables").path("counter.visits").asInt());
        assertTrue(state.path("tags").toString().contains("tag.hurried"));
        assertEquals(1, advanced.getVersion());
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));

        runtime.advance(7L, 10L, 0L, 1, "effects-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        JsonNode replayed = json.readTree(stored[0].getStateJson());
        assertEquals(1, replayed.path("variables").path("counter.visits").asInt(),
                "同 actionId 重放不得再次加计数");
        assertEquals(1, replayed.path("tags").size());
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
    }

    /**
     * requires 不满足:advance 当场拒绝,且在插 action 行之前 —— 不留任何可重放的回执,
     * state 与 version 一动不动。
     */
    @Test
    void unmetRequiresRejectsAdvanceBeforeActionRowAndKeepsStateAndVersion() throws Exception {
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        CmsMemberTemplate blockedTemplate = template(101L,
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
                        + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(blockedTemplate));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(blockedTemplate);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        String stateBefore = stored[0].getStateJson();
        int versionBefore = stored[0].getVersion();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.advance(7L, 10L, 0L, 0, "blocked-action", "CHOICE",
                        new NodeOutcome(1L, "COMPLETED", "player-node-progress:701")));

        assertEquals("本站前置条件未满足", error.getMessage());
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
        assertEquals(stateBefore, stored[0].getStateJson());
        assertEquals(versionBefore, stored[0].getVersion());
        assertFalse(json.readTree(stored[0].getStateJson()).path("variables").has("counter.visits"),
                "被拒绝的那次不得写入 effects");
    }

    /** requires 不满足时 canPlay 直接 false —— 玩家到点也开不了这一站。 */
    @Test
    void canPlayReturnsFalseWhenNodeRequiresNotMet() {
        CmsTopicNode one = node(1L, 1L);
        CmsTopicNode two = node(2L, 2L); two.setTemplateId(202L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        CmsMemberTemplate requiresTemplate = template(202L,
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(requiresTemplate));
        when(templateMapper.selectCmsMemberTemplateById(202L)).thenReturn(requiresTemplate);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        runtime.advance(7L, 10L, 0L, 0, "to-two", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertFalse(runtime.canPlay(7L, 10L, 0L, 2L));
    }

    /** requires 满足后同一站可以玩:正向对照,证明上一条不是 canPlay 恒 false。 */
    @Test
    void canPlayReturnsTrueWhenNodeRequiresHolds() {
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L); two.setTemplateId(202L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        CmsMemberTemplate effectsTemplate = template(101L,
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}]}");
        CmsMemberTemplate requiresTemplate = template(202L,
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Arrays.asList(effectsTemplate, requiresTemplate));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(effectsTemplate);
        when(templateMapper.selectCmsMemberTemplateById(202L)).thenReturn(requiresTemplate);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        runtime.advance(7L, 10L, 0L, 0, "to-two-ok", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertTrue(runtime.canPlay(7L, 10L, 0L, 2L));
    }

    /**
     * B8 不可变快照:开局冻结 requires/effects/check 与主题 journeyRules 后,
     * 创作者改模板与主题规则不改进行中的局;同一主题新开的局按新配置冻结。
     */
    @Test
    void frozenNodeJourneyKeepsOpenSessionStableWhileNewSessionUsesEditedConfig() throws Exception {
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L); two.setTemplateId(202L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        CmsMemberTemplate effects = template(101L,
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}],"
                        + "\"brief\":{\"question\":\"模板里的委托\"},\"route\":{\"walk\":\"模板里的六分钟\"}}");
        CmsMemberTemplate requires = template(202L,
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Arrays.asList(effects, requires));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenAnswer(inv -> effects);
        when(templateMapper.selectCmsMemberTemplateById(202L)).thenAnswer(inv -> requires);
        CmsTopicChapter chapterOne = new CmsTopicChapter();
        chapterOne.setTopicId(10L);
        chapterOne.setBlocksJson(storyBlocks("故事流的委托", "故事流的六分钟"));
        CmsTopicChapter chapterTwo = new CmsTopicChapter();
        chapterTwo.setTopicId(10L);
        chapterTwo.setBlocksJson("[{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\","
                + "\"content\":\"二站开场\",\"nodeId\":2}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Arrays.asList(chapterOne, chapterTwo));
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true}");

        runtime.state(7L, 10L, 0L);

        JsonNode frozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertTrue(frozen.path("nodeJourney").path("1").has("effects"), "节点 1 的 effects 冻结");
        assertEquals("故事流的委托", frozen.path("nodeJourney").path("1").path("brief").path("question").asText(),
                "叙事段按站点从故事流取,模板里的残留 brief 不生效");
        assertEquals("故事流的六分钟", frozen.path("nodeJourney").path("1").path("route").path("walk").asText());
        assertEquals("二站开场", frozen.path("nodeJourney").path("2").path("enter").path("opener").asText(),
                "第二站从自己的章节块取叙事,不串第一站的台词");
        assertFalse(frozen.path("nodeJourney").path("2").has("brief"), "第二站没有 brief 就不写");
        assertTrue(frozen.path("nodeJourney").path("2").has("requires"), "节点 2 的 requires 冻结");
        assertTrue(frozen.path("journeyRules").path("checkEnabled").asBoolean(), "主题数值规则冻结");

        // 开局后创作者改配置:效果改值、requires 换成永不满足、故事流叙事改写、主题关掉检定
        effects.setAdvancedConfigJson(
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":9}]}");
        requires.setAdvancedConfigJson(
                "{\"requires\":[{\"var\":\"clue.never_seen\",\"op\":\"GTE\",\"value\":99}]}");
        chapterOne.setBlocksJson(storyBlocks("改后的故事流委托", "改后的六分钟"));
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":false}");

        runtime.advance(7L, 10L, 0L, 0, "frozen-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(1, state.path("variables").path("clue.ledger_page").asInt(),
                "进行中的局用建局冻结的 effects(value=1),不是改后的 9");
        assertTrue(runtime.canPlay(7L, 10L, 0L, 2L),
                "进行中的局用建局冻结的 requires(clue.ledger_page>=1),不受改后条件影响");

        TopicRouteState reopened = runtime.state(8L, 10L, 0L);

        assertEquals(99L, reopened.getSessionId(), "新会员新开一局");
        JsonNode newFrozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertEquals(9, newFrozen.path("nodeJourney").path("1").path("effects").get(0).path("value").asInt(),
                "新开的局用改后的新配置冻结");
        assertEquals("改后的故事流委托",
                newFrozen.path("nodeJourney").path("1").path("brief").path("question").asText(),
                "新开的局冻结改后的故事流叙事");
        assertFalse(newFrozen.path("journeyRules").path("checkEnabled").asBoolean(),
                "新开的局冻结改后的主题规则");
    }

    /** 故事流章节 blocks:一条 brief 与一条 route,都挂在节点 1 上。 */
    private static String storyBlocks(String brief, String walk) {
        return "[{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"question\",\"content\":\"" + brief
                + "\",\"nodeId\":1},{\"type\":\"text\",\"beat\":\"route\",\"field\":\"walk\",\"content\":\""
                + walk + "\",\"nodeId\":1}]";
    }

    /**
     * R11-A:建局把主题剧情 journey_story_json 冻结进快照(与 journeyRules 同级的新键),
     * 新局用改后的剧情冻结;老快照没有该键时由读侧回退实时(见 JourneyRuntimeActionsTest)。
     */
    @Test
    void freezeJourneyAlsoFreezesThemeStoryForNewSessions() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
        topic.setJourneyStoryJson(
                "{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"喂\",\"brief\":\"旧电话\","
                        + "\"options\":[{\"id\":\"a\",\"line\":\"接\",\"reply\":\"回 A\"},"
                        + "{\"id\":\"b\",\"line\":\"不接\",\"reply\":\"回 B\"}]}}");

        runtime.state(7L, 10L, 0L);

        JsonNode frozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertEquals(topic.getJourneyStoryJson(), frozen.path("journeyStory").asText(),
                "主题剧情冻结进快照");

        topic.setJourneyStoryJson(
                "{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"喂\",\"brief\":\"新电话\","
                        + "\"options\":[{\"id\":\"a\",\"line\":\"接\",\"reply\":\"回 A\"},"
                        + "{\"id\":\"b\",\"line\":\"不接\",\"reply\":\"回 B\"}]}}");
        runtime.state(8L, 10L, 0L);

        JsonNode newFrozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertTrue(newFrozen.path("journeyStory").asText().contains("新电话"), "新开的局冻结改后的剧情");
    }

    /** 本次改动前建的老局快照没有 nodeJourney:回退实时模板配置,行为不变。 */
    @Test
    void legacySnapshotWithoutNodeJourneyFallsBackToLiveTemplate() throws Exception {
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        CmsMemberTemplate liveTemplate = template(101L,
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.frozen\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(liveTemplate));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(liveTemplate);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        stored[0].setConfigSnapshotJson(
                topicMapper.selectCmsTopicById(10L).getPublishedRouteGraphJson());
        liveTemplate.setAdvancedConfigJson(
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.live\",\"value\":2}]}");

        runtime.advance(7L, 10L, 0L, 0, "legacy-snapshot", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(2, state.path("variables").path("clue.live").asInt(), "老快照回退读实时模板");
        assertFalse(state.path("variables").has("clue.frozen"),
                "老快照绝不读建局时那份模板");
    }

    /**
     * 老线性主题(没有 journeyRules、节点块也没配 requires/effects/check)一字不变:
     * 不建局、不写 action,state/advance 都还是原来的线性读模型。
     */
    @Test
    void legacyLinearTopicStaysStatelessWithoutJourneyConfig() {
        useLinearJourneyTopic();
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, 1L), node(2L, 2L)));

        TopicRouteState opened = runtime.state(7L, 10L, 0L);
        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, null, "legacy-action", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(TopicRouteState.LINEAR, opened.getRouteMode());
        assertEquals(TopicRouteState.LINEAR, advanced.getRouteMode());
        assertEquals(1L, opened.getCurrentNodeId());
        assertNull(opened.getSessionId());
        assertEquals(Integer.valueOf(0), opened.getVersion());
        verify(routeMapper, never()).insertSession(any(TopicRouteSession.class));
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    /**
     * 带 effects 的线性主题建局:快照没有边,节点完成时 effects 写进 state_json,
     * 与分支同一次 CAS、同一 routeActionId 幂等。
     */
    @Test
    void linearJourneyTopicOpensSessionAndWritesNodeEffectsWithCas() throws Exception {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        String effectsConfig = "{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1},"
                + "{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}]}";
        CmsMemberTemplate effectsTemplate = template(101L, effectsConfig);
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(effectsTemplate));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(effectsTemplate);
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        TopicRouteState opened = runtime.state(7L, 10L, 0L);

        assertEquals(TopicRouteState.LINEAR, opened.getRouteMode());
        assertEquals(99L, opened.getSessionId());
        assertEquals(0, opened.getVersion());
        JsonNode linearFrozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertFalse(linearFrozen.has("edges"), "线性局快照没有边,推进不走 engine.advance");
        assertTrue(linearFrozen.path("nodeJourney").path("1").has("effects"),
                "线性局同样把节点旅程段冻结进快照");
        assertTrue(linearFrozen.has("journeyRules"), "线性局同样冻结主题数值规则");

        // 开局后创作者改模板:进行中的局仍用建局冻结的 effects
        effectsTemplate.setAdvancedConfigJson(
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.edited\",\"value\":9}]}");

        TopicRouteState advanced = runtime.advance(7L, 10L, 0L, 0, "linear-effects", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertEquals(TopicRouteState.LINEAR, advanced.getRouteMode());
        assertEquals(1, advanced.getVersion());
        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(1, state.path("variables").path("counter.visits").asInt());
        assertTrue(state.path("tags").toString().contains("tag.hurried"));
        assertTrue(state.path("completedNodeIds").toString().contains("1"));
        assertFalse(state.path("variables").has("clue.edited"), "改后的 effects 不进进行中的局");
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));

        runtime.advance(7L, 10L, 0L, 1, "linear-effects", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));
        assertEquals(1, json.readTree(stored[0].getStateJson()).path("variables").path("counter.visits").asInt(),
                "同 actionId 重放不得再次加计数");
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
    }

    /**
     * R19-INT 收口 a:只配 roleViews 的线性主题也必须建局 —— 双人视角分半要用本局 activityId
     * 定队伍,不建局时 /api/play/encounter 直接报「这一局还没开始」,本人那一半永远拿不到。
     * 冻结快照里 roleViews 必须在,到店后才读得到这一半。
     */
    @Test
    void linearTopicWithOnlyRoleViewsOpensSessionForDuoView() throws Exception {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(1L)).thenReturn(one);
        String roleViewsConfig = "{\"schemaVersion\":1,\"roleViews\":{\"enabled\":true,"
                + "\"roles\":[{\"id\":\"A\",\"label\":\"看实物\"},{\"id\":\"B\",\"label\":\"查档案\"}],"
                + "\"views\":[{\"roleId\":\"A\",\"title\":\"A 的实物清单\"},"
                + "{\"roleId\":\"B\",\"title\":\"B 的档案摘录\"}]}}";
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template(101L, roleViewsConfig)));

        TopicRouteState opened = runtime.state(7L, 10L, 0L);

        assertEquals(TopicRouteState.LINEAR, opened.getRouteMode());
        assertEquals(99L, opened.getSessionId(), "只配 roleViews 的线性主题必须建局,否则 Encounter 报没开始");
        verify(routeMapper, times(1)).insertSession(any(TopicRouteSession.class));
        JsonNode frozen = json.readTree(stored[0].getConfigSnapshotJson());
        assertTrue(frozen.path("nodeJourney").path("1").path("roleViews").path("enabled").asBoolean(false),
                "建局把 roleViews 冻进本局,分半读的是冻结配置");
    }

    /**
     * 热路径模板查询:无本局的线性旅程主题,建局判定与建局冻结共用同一次模板查询 ——
     * 同一次 state 调用只查一次;已有本局时再 state / canPlay 一次都不查。
     */
    @Test
    void journeyTemplateLookupRunsAtMostOncePerRequest() {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}")));

        TopicRouteState opened = runtime.state(7L, 10L, 0L);

        assertEquals(99L, opened.getSessionId());
        verify(templateMapper, times(1)).selectCmsMemberTemplateByIds(any());

        clearInvocations(templateMapper);
        runtime.state(7L, 10L, 0L);
        runtime.canPlay(7L, 10L, 0L, 2L);

        verify(templateMapper, never()).selectCmsMemberTemplateByIds(any());
    }

    /** canPlay 自己开局的调用链同样只查一次模板:判定与冻结不得各查一次。 */
    @Test
    void journeyTemplateLookupRunsOnceWhenCanPlayOpensTheSession() {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(one, node(2L, 2L)));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}")));

        runtime.canPlay(7L, 10L, 0L, 1L);

        verify(templateMapper, times(1)).selectCmsMemberTemplateByIds(any());
    }

    /** 已有本局的线性主题:创作者清空模板旅程段后,本局仍按快照走,不退回无局线性读模型。 */
    @Test
    void linearSessionSurvivesTemplateJourneyRemovalAfterOpen() {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(one, node(2L, 2L)));
        CmsMemberTemplate template = template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}");
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenAnswer(inv -> template);

        TopicRouteState opened = runtime.state(7L, 10L, 0L);
        assertEquals(99L, opened.getSessionId());

        template.setAdvancedConfigJson("{}");

        TopicRouteState resumed = runtime.state(7L, 10L, 0L);

        assertEquals(99L, resumed.getSessionId(), "已有本局仍读快照,不退回无局线性读模型");
        assertEquals(opened.getVersion(), resumed.getVersion());
    }

    /** 线性主题 requires 不满足:advance 当场拒绝,不留 action,不写版本。 */
    @Test
    void linearUnmetRequiresRejectsAdvanceBeforeAnyWrite() {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L); two.setTemplateId(202L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Arrays.asList(
                template(101L, "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}]}"),
                template(202L, "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}")));
        when(templateMapper.selectCmsMemberTemplateById(202L)).thenReturn(template(202L,
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}"));
        PlayerNodeProgress first = new PlayerNodeProgress(); first.setNodeId(1L); first.setStatus(1);
        PlayerNodeProgress second = new PlayerNodeProgress(); second.setNodeId(2L); second.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Arrays.asList(first, second));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        String stateBefore = stored[0].getStateJson();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.advance(7L, 10L, 0L, 0, "linear-blocked", "CHOICE",
                        new NodeOutcome(2L, "COMPLETED", "player-node-progress:702")));

        assertEquals("本站前置条件未满足", error.getMessage());
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
        assertEquals(stateBefore, stored[0].getStateJson());
    }

    /** requires 满足后线性主题的下一站可以玩:同一局状态里 effects 落地的正向对照。 */
    @Test
    void linearCanPlayUnlocksAfterEffectsLandInSessionState() {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        CmsTopicNode two = node(2L, 2L); two.setTemplateId(202L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, two));
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(two);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Arrays.asList(
                template(101L, "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}]}"),
                template(202L, "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}")));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template(101L,
                "{\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1}]}"));
        when(templateMapper.selectCmsMemberTemplateById(202L)).thenReturn(template(202L,
                "{\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}"));
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        assertFalse(runtime.canPlay(7L, 10L, 0L, 2L), "requires 未满足前不给玩");

        runtime.advance(7L, 10L, 0L, 0, "land-effects", "CHOICE",
                new NodeOutcome(1L, "COMPLETED", "player-node-progress:701"));

        assertTrue(runtime.canPlay(7L, 10L, 0L, 2L), "effects 落地后同一站可以玩");
    }

    /** 线性局同样走 version CAS:期望版本过期直接 409,零写入。 */
    @Test
    void linearAdvanceConflictsOnStaleVersionWithoutWritingAction() {
        useLinearJourneyTopic();
        CmsTopicNode one = node(1L, 1L); one.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(one, node(2L, 2L)));
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}")));
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);

        runtime.state(7L, 10L, 0L);
        ServiceException error = assertThrows(ServiceException.class, () -> runtime.advance(7L, 10L, 0L, 9,
                "linear-stale", "CHOICE", new NodeOutcome(1L, "COMPLETED", "player-node-progress:701")));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
    }

    /** checkEnabled 的主题建局时写入服务端保留变量 sys.hp / sys.luck = init。 */
    @Test
    void checkEnabledJourneySeedsSysHpAndLuckAtSessionOpen() throws Exception {
        useLinearJourneyTopic();
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":7},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":2}}");

        TopicRouteState opened = runtime.state(7L, 10L, 0L);

        assertEquals(99L, opened.getSessionId());
        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(7, state.path("variables").path("sys.hp").asInt());
        assertEquals(2, state.path("variables").path("sys.luck").asInt());
    }

    /** hp/luck 各自 enabled=false 时那一项不写,不是恒有 sys.*。 */
    @Test
    void disabledHpOrLuckIsNotSeededAtSessionOpen() throws Exception {
        useLinearJourneyTopic();
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":false,\"max\":10,\"init\":7},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":2}}");

        runtime.state(7L, 10L, 0L);

        JsonNode state = json.readTree(stored[0].getStateJson());
        assertFalse(state.path("variables").has("sys.hp"));
        assertEquals(2, state.path("variables").path("sys.luck").asInt());
    }

    /** 分支主题也吃同一套开局初始化,不是只有线性局写 sys.*。 */
    @Test
    void checkEnabledBranchTopicAlsoSeedsSysVitals() throws Exception {
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":6},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":1}}");

        runtime.state(7L, 10L, 0L);

        JsonNode state = json.readTree(stored[0].getStateJson());
        assertEquals(6, state.path("variables").path("sys.hp").asInt());
        assertEquals(1, state.path("variables").path("sys.luck").asInt());
    }

    /** 线性局用既有完成事实播种 completedNodeIds,建局前已通关的玩家不会从通关读侧消失。 */
    @Test
    void linearJourneySessionSeedsCompletedNodeIdsFromProgressFacts() throws Exception {
        useLinearJourneyTopic();
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, 1L), node(2L, 2L)));
        topicMapper.selectCmsTopicById(10L).setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true}");
        PlayerNodeProgress completed = new PlayerNodeProgress(); completed.setNodeId(1L); completed.setStatus(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(completed));

        runtime.state(7L, 10L, 0L);

        JsonNode state = json.readTree(stored[0].getStateJson());
        assertTrue(state.path("completedNodeIds").toString().contains("1"));
        assertEquals(2L, stored[0].getCurrentNodeId());
        assertEquals(Integer.valueOf(TopicRouteSession.STATUS_RUNNING), stored[0].getStatus());
    }

    private void useLinearJourneyTopic() {
        CmsTopic topic = topicMapper.selectCmsTopicById(10L);
        topic.setRouteMode("LINEAR");
        topic.setRouteGraphJson(null);
        topic.setPublishedRouteGraphJson(null);
        topic.setPublishedConfigVersion(1L);
    }

    private static CmsMemberTemplate template(Long id, String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(id);
        template.setAdvancedConfigJson(advancedConfigJson);
        return template;
    }

    private static CmsMemberTemplate template(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setAdvancedConfigJson(advancedConfigJson);
        return template;
    }

    private CmsTopicNode node(Long id, Long sort) {
        CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(10L); node.setSortId(sort); node.setStatus(1L);
        return node;
    }

    private CmsRegistration registration(Long ticketId, Integer purchaseKind) {
        CmsRegistration registration = new CmsRegistration(); registration.setTicketId(ticketId);
        registration.setPurchaseKind(purchaseKind); registration.setId(ticketId);
        registration.setMemberId(7L);registration.setOwnerType(1L);registration.setOwnerId(10L);
        registration.setPaymentStatus(0);registration.setRegistrationStatus(2);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(ticketId)).thenReturn(registration);
        return registration;
    }

    private CmsActivity playableActivity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(44L); activity.setTopicId(10L); activity.setStatus(1L);
        activity.setPublishStatus(1L); activity.setDelFlag(0);
        return activity;
    }

    private void useActivityScope(CmsActivity activity) {
        when(activityMapper.selectCmsActivityById(44L)).thenReturn(activity);
        when(routeMapper.selectByOwner(44L, 10L, 7L, "default")).thenAnswer(inv -> stored[0]);
    }

    private TopicRouteRuntimeServiceImpl policyRuntime() {
        TopicRouteRuntimeServiceImpl policy = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper,
                templateMapper, progressMapper, activityMapper, registrationMapper, roleMapper, () -> 12345L,
                Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "cmsTopicChapterMapper",
                cmsTopicChapterMapper);
        return policy;
    }

    @Test void contentReadKeepsLegacyRunPartialAndNeverReadsNewPublication() {
        TopicRouteSession original = new TopicRouteSession();
        original.setId(71L); original.setMemberId(7L); original.setTopicId(10L);
        original.setActivityId(20L); original.setConfigSnapshotJson("{}");
        when(routeMapper.selectCurrentByOwner(20L, 10L, 7L)).thenReturn(original);
        clearInvocations(topicMapper, routeMapper);
        JsonNode result = runtime.queryNodeContentSnapshot(7L, 10L, 20L, 1L);
        assertEquals(71L, result.path("contentRouteSessionId").asLong());
        assertFalse(result.has("nodeAnswers"));
        verify(topicMapper, never()).selectCmsTopicById(anyLong());
        verify(routeMapper, never()).updateStateCas(any());
    }

    @Test void contentReadUsesSharedOriginalBeforePersonalOrPublication() {
        com.chengyinhub.business.service.IGameSessionRuntimeService shared =
                mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(runtime, "gameSessionRuntimeService", shared);
        ObjectNode snapshot = json.createObjectNode().put("gameSessionId", 91L).put("authorityEpoch", 3);
        when(shared.querySharedRouteSnapshot(7L, 10L, 20L, 1L)).thenReturn(snapshot);
        clearInvocations(routeMapper, topicMapper);
        assertEquals(snapshot, runtime.queryNodeContentSnapshot(7L, 10L, 20L, 1L));
        verify(routeMapper, never()).selectCurrentByOwner(anyLong(), anyLong(), anyLong());
        verify(topicMapper, never()).selectCmsTopicById(anyLong());
    }

    @Test void contentPreviewWithoutRunRetainsOriginalAdvancedGames() {
        when(routeMapper.selectCurrentByOwner(20L, 10L, 7L)).thenReturn(null);
        when(routeMapper.selectByOwner(20L, 10L, 7L, "default")).thenReturn(null);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService games =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        ReflectionTestUtils.setField(runtime, "advancedGameRuntimeService", games);
        ObjectNode original = json.createObjectNode();
        original.putObject("1").put("legacyNodeContent", true);
        when(games.queryDefaultNodeContents(7L, 20L, 10L)).thenReturn(original);
        JsonNode result = runtime.queryNodeContentSnapshot(7L, 10L, 20L, 1L);
        assertEquals(original, result.path("originalNodeGames"));
        verify(routeMapper, never()).updateStateCas(any());
    }

}
