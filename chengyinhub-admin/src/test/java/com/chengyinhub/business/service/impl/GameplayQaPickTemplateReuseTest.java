package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reuses real template normalization, publication compilation, advanced SUBMIT_QA and personal task proof.
 * Only persistence/current paid-registration reads are doubles; no copied answer/receipt algorithm.
 * Not an HTTP, SQL, review-approval or transaction-rollback acceptance test.
 */
class GameplayQaPickTemplateReuseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long PLAYER = 7L, NOW = 1_775_000_000_000L;

    @Test void twoThemesUseTheSamePickJudgeButKeepQuestionsStateAndCompletionProofSeparate() throws Exception {
        Fixture f = new Fixture();
        Theme a = f.publish(10L, 11L, 501L, "桥梁档案", "桥梁建成年份？", "a");
        Theme b = f.publish(20L, 21L, 502L, "车站档案", "车站建成年份？", "b");
        GameRuntimeSession first = f.start(a), second = f.start(b);

        assertEquals("桥梁建成年份？", JSON.readTree(first.getConfigSnapshotJson()).path("qa").path("title").asText());
        assertEquals("车站建成年份？", JSON.readTree(second.getConfigSnapshotJson()).path("qa").path("title").asText());
        assertFalse(f.route.verifyGuidedRouteAnswer(a.frozen(), a.node(), answer("b")));
        assertTrue(f.route.verifyGuidedRouteAnswer(a.frozen(), a.node(), answer("a")));
        assertFalse(f.route.verifyGuidedRouteAnswer(b.frozen(), b.node(), answer("a")));
        assertTrue(f.route.verifyGuidedRouteAnswer(b.frozen(), b.node(), answer("b")));

        f.submit(first, "wrong-a", "b");
        assertFalse(f.state(first).path("qa").path("passed").asBoolean());
        assertFalse(f.proved(a)); assertFalse(f.proved(b));
        assertEquals(0, f.routeState(a).path("completedNodeIds").size());
        assertEquals(0, f.routeState(b).path("completedNodeIds").size());
        assertTrue(f.state(second).path("qa").isMissingNode());

        f.submit(first, "correct-a", "a");
        assertTrue(f.state(first).path("qa").path("passed").asBoolean());
        assertTrue(f.proved(a)); assertFalse(f.proved(b));
        f.resolve(a, "resolve-a");
        assertEquals(1, f.routeState(a).path("completedNodeIds").size());
        assertEquals(a.node(), f.routeState(a).path("completedNodeIds").path(0).asLong());
        assertEquals(0, f.routeState(b).path("completedNodeIds").size());

        f.submit(second, "correct-b", "b");
        assertTrue(f.proved(b));
        f.resolve(b, "resolve-b");
        assertEquals(1, f.routeState(b).path("completedNodeIds").size());
        assertEquals(b.node(), f.routeState(b).path("completedNodeIds").path(0).asLong());
        assertEquals(9, f.routeState(a).path("variables").path("sys.hp").asInt());
        assertEquals(9, f.routeState(b).path("variables").path("sys.hp").asInt());
    }

    @Test void frozenTemplateOriginalReceiptAndInstanceGuardsSurviveReuseAcrossThemes() throws Exception {
        Fixture f = new Fixture();
        Theme a = f.publish(10L, 11L, 501L, "桥梁档案", "旧桥题目", "a");
        Theme b = f.publish(20L, 21L, 502L, "车站档案", "旧站题目", "b");
        // Editing source data after publication does not replace the configuration of either run.
        f.templates.get(a.template()).setAdvancedConfigJson(pick("作者后改的桥题目", "b"));
        f.templates.get(b.template()).setAdvancedConfigJson(pick("作者后改的站题目", "a"));
        GameRuntimeSession first = f.start(a), second = f.start(b);
        assertEquals("旧桥题目", JSON.readTree(first.getConfigSnapshotJson()).path("qa").path("title").asText());
        assertEquals("旧站题目", JSON.readTree(second.getConfigSnapshotJson()).path("qa").path("title").asText());
        String bBefore = b.session().getStateJson();

        assertThrows(ServiceException.class, () -> f.route.recordNodeResult(PLAYER, b.topic(), 0L, b.node(),
                first.getPlayInstanceId(), "borrow-other-run-proof", true, false, 0, Collections.emptyList()));
        assertEquals(bBefore, b.session().getStateJson());
        assertFalse(f.proved(b));

        int originalVersion = first.getVersion();
        Map<String,Object> receipt = f.advanced.act(PLAYER, first.getId(), originalVersion,
                "same-client-key", "SUBMIT_QA", Map.of("optionId", "a"));
        String aAfter = a.session().getStateJson(), childAfter = first.getStateJson();
        int attempts = f.attempts.size(), routeReceipts = f.routeReceipts.size();
        assertEquals(JSON.readTree(JSON.writeValueAsString(receipt)),
                JSON.readTree(JSON.writeValueAsString(f.advanced.act(PLAYER, first.getId(), originalVersion,
                        "same-client-key", "SUBMIT_QA", Map.of("optionId", "a")))));
        assertEquals(attempts, f.attempts.size()); assertEquals(routeReceipts, f.routeReceipts.size());
        assertEquals(aAfter, a.session().getStateJson()); assertEquals(childAfter, first.getStateJson());
        assertThrows(ServiceException.class, () -> f.advanced.act(PLAYER, first.getId(), originalVersion,
                "same-client-key", "SUBMIT_QA", Map.of("optionId", "b")));
        assertThrows(IllegalStateException.class, () -> f.advanced.act(8L, first.getId(), originalVersion,
                "foreign-actor", "SUBMIT_QA", Map.of("optionId", "a")));
        assertThrows(IllegalArgumentException.class, () -> f.advanced.start(PLAYER, 0L, a.topic(), b.node(), null));
        assertEquals(aAfter, a.session().getStateJson()); assertEquals(bBefore, b.session().getStateJson());
        assertEquals(childAfter, first.getStateJson());

        // The same client key is a different command in B; it must judge B's original answer, not replay A.
        f.advanced.act(PLAYER, second.getId(), second.getVersion(),
                "same-client-key", "SUBMIT_QA", Map.of("optionId", "b"));
        assertTrue(f.proved(a)); assertTrue(f.proved(b));
        assertEquals(2, f.attempts.size());
        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(first.getPlayInstanceId(), second.getPlayInstanceId());
        assertTrue(f.attempts.values().stream().allMatch(row -> row.getRequestFingerprint().matches("[0-9a-f]{64}")));
    }

    @Test void exportedPickCapabilityUsesExistingRuleCompilerAndKeepsFreeRetryAndClientProofGuards() throws Exception {
        JsonNode row = null;
        for (JsonNode candidate : GameplaySupportMatrix.view().path("types"))
            if ("qaPick".equals(candidate.path("key").asText())) row = candidate;
        assertNotNull(row); assertEquals("qa", row.path("section").asText());
        assertEquals("PICK", row.path("mode").asText()); assertTrue(row.path("completionProof").asBoolean());
        Fixture f = new Fixture();
        Theme theme = f.publish(10L, 11L, 501L, "同一组件", "选择正确年份", "a");
        JsonNode block = theme.frozen().path("nodeConfigs").path(Long.toString(theme.node()));
        assertEquals("PICK", block.path("qa").path("mode").asText());
        assertEquals(0, block.path("qa").path("maxTries").asInt());
        JsonNode game = theme.frozen().path("themeConfig").path("gameplayRuntime");
        assertEquals("tpl_node_11", game.path("quests").path(0).path("facilityKey").asText());
        assertEquals(theme.node(), game.path("facilities").path(0).path("operationNodeId").asLong());
        assertEquals(1, game.path("facilities").path(0).path("hpCost").asInt());

        ObjectNode chargedRetry = block.deepCopy();
        ((ObjectNode)chargedRetry.path("qa")).put("maxTries", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplaySupportMatrix.validateNode(
                theme.frozen().path("themeConfig"), theme.node(), "同一组件", chargedRetry, 3));
        ObjectNode forged = answer("b"); forged.with("qa").put("passed", true);
        assertThrows(IllegalArgumentException.class, () -> f.route.verifyGuidedRouteAnswer(
                theme.frozen(), theme.node(), forged));
        assertFalse(f.route.verifyGuidedRouteAnswer(theme.frozen(), theme.node(), answer("b")));
        assertEquals(0, f.routeState(theme).path("completedNodeIds").size());
    }

    private static ObjectNode answer(String option) {
        ObjectNode answers = JSON.createObjectNode(); answers.putObject("qa").put("optionId", option); return answers;
    }

    private static String pick(String title, String correct) {
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1);
        ObjectNode qa = config.putObject("qa").put("enabled", true).put("mode", "PICK")
                .put("title", title).put("maxTries", 0).put("xp", 0);
        qa.putArray("options").addObject().put("id", "a").put("label", "1908").put("correct", "a".equals(correct));
        qa.withArray("options").addObject().put("id", "b").put("label", "1926").put("correct", "b".equals(correct));
        config.putObject("interactionRules").put("hpCost", 1).put("limit", 1);
        return new AdvancedGameConfigValidator().validateAndNormalize(config.toString());
    }

    private record Theme(long topic, long node, long template, JsonNode frozen, TopicRouteSession session) { }

    /** Shared stores deliberately hold both themes; IDs are distinct in the same runtime, not separate mock universes. */
    private static final class Fixture {
        final Map<Long,CmsTopic> topics = new LinkedHashMap<>();
        final Map<Long,CmsTopicNode> nodes = new LinkedHashMap<>();
        final Map<Long,CmsMemberTemplate> templates = new LinkedHashMap<>();
        final Map<Long,TopicRouteSession> routeSessions = new LinkedHashMap<>();
        final Map<Long,GameRuntimeSession> games = new LinkedHashMap<>();
        final Map<String,TopicRouteAction> routeReceipts = new LinkedHashMap<>();
        final Map<String,PlayAttemptLog> attempts = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(1000);
        final TopicRouteRuntimeServiceImpl route;
        final AdvancedGameRuntimeServiceImpl advanced = new AdvancedGameRuntimeServiceImpl();
        final org.springframework.transaction.support.TransactionTemplate publication;

        Fixture() throws Exception {
            javax.sql.DataSource data = mock(javax.sql.DataSource.class);
            when(data.getConnection()).thenAnswer(call -> {
                java.sql.Connection connection = mock(java.sql.Connection.class);
                when(connection.getAutoCommit()).thenReturn(true);
                return connection;
            });
            publication = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
            TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
            CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
            CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
            CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
            CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
            ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
            IShopDayProofService arrival = mock(IShopDayProofService.class);
            CurrentRegistrationStubs.currentFromProbe(registrations);
            when(topicMapper.selectCmsTopicById(anyLong())).thenAnswer(call -> topics.get(call.getArgument(0)));
            when(topicMapper.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(call -> topics.get(call.getArgument(0)));
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
            when(chapters.selectByTopicForUpdate(anyLong())).thenReturn(Collections.emptyList());
            when(nodeMapper.selectCmsTopicNodeById(anyLong())).thenAnswer(call -> nodes.get(call.getArgument(0)));
            when(nodeMapper.selectCmsTopicNodeList(any())).thenAnswer(call -> {
                CmsTopicNode probe = call.getArgument(0);
                return new ArrayList<>(nodes.values().stream().filter(node -> node.getTopicId().equals(probe.getTopicId())).toList());
            });
            when(nodeMapper.selectByTopicForUpdate(anyLong())).thenAnswer(call ->
                    new ArrayList<>(nodes.values().stream().filter(node -> node.getTopicId().equals(call.getArgument(0))).toList()));
            when(templateMapper.selectCmsMemberTemplateById(anyLong())).thenAnswer(call -> templates.get(call.getArgument(0)));
            when(templateMapper.selectCmsMemberTemplateByIds(any())).thenAnswer(call -> {
                List<Long> selected = call.getArgument(0);
                return new ArrayList<>(selected.stream().map(templates::get).filter(Objects::nonNull).toList());
            });
            when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(any())).thenAnswer(call -> {
                List<Long> selected = call.getArgument(0);
                return new ArrayList<>(selected.stream().map(templates::get).filter(Objects::nonNull).toList());
            });
            when(keys.lockTopicForStateKeyWrite(anyLong())).thenAnswer(call -> {
                CmsTopic original = topics.get(call.getArgument(0)); return original == null ? null : original.getId();
            });
            when(keys.selectByTopicId(anyLong())).thenAnswer(call -> ThemeStateKeyDeclarationSnapshot.includingRuleAttributes(
                    call.getArgument(0),Collections.emptyList(),JourneyRules.parse(topics.get(call.getArgument(0)).getJourneyRulesJson())));
            when(keys.selectByTopicIdForUpdate(anyLong())).thenAnswer(call -> keys.selectByTopicId(call.getArgument(0)));
            when(arrival.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
            when(routes.selectByOwner(eq(0L), anyLong(), eq(PLAYER), anyString())).thenAnswer(call -> routeSessions.get(call.getArgument(1)));
            when(routes.selectCurrentByOwner(eq(0L), anyLong(), eq(PLAYER))).thenAnswer(call -> routeSessions.get(call.getArgument(1)));
            when(routes.selectAccessibleById(anyLong(), eq(PLAYER))).thenAnswer(call ->
                    routeSessions.values().stream().filter(row -> row.getId().equals(call.getArgument(0))).findFirst().orElse(null));
            when(routes.insertSession(any())).thenAnswer(call -> {
                TopicRouteSession row = call.getArgument(0); row.setId(ids.incrementAndGet()); routeSessions.put(row.getTopicId(), row); return 1;
            });
            when(routes.insertAction(any())).thenAnswer(call -> {
                TopicRouteAction row = call.getArgument(0); row.setId(ids.incrementAndGet());
                routeReceipts.put(routeKey(row.getSessionId(), row.getMemberId(), row.getActionId()), row); return 1;
            });
            when(routes.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(call ->
                    routeReceipts.get(routeKey(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
            when(routes.updateActionResponse(anyLong(), anyString())).thenAnswer(call -> {
                routeReceipts.values().stream().filter(row -> row.getId().equals(call.getArgument(0)))
                        .forEach(row -> row.setResponseJson(call.getArgument(1))); return 1;
            });
            when(routes.updateStateCas(any())).thenReturn(1);
            route = new TopicRouteRuntimeServiceImpl(routes, topicMapper, nodeMapper, templateMapper,
                    mock(PlayerNodeProgressMapper.class), null, registrations, null, arrival, () -> 1L, () -> 10,
                    Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), RouteReachabilityProvider.UNKNOWN);
            ReflectionTestUtils.setField(route,"cmsTopicChapterMapper",chapters);
            ReflectionTestUtils.setField(route,"themeStateKeyMapper",keys);
            GameRuntimeSessionMapper sessions = mock(GameRuntimeSessionMapper.class);
            PlayAttemptLogMapper logs = mock(PlayAttemptLogMapper.class);
            when(sessions.selectAccessibleById(anyLong(), eq(PLAYER))).thenAnswer(call -> games.get(call.getArgument(0)));
            when(sessions.selectPlayerSessionsByTopic(anyLong(), anyLong(), anyLong())).thenAnswer(call ->
                    new ArrayList<>(games.values().stream().filter(row -> row.getActivityId().equals(call.getArgument(0))
                            && row.getTopicId().equals(call.getArgument(1)) && row.getOwnerId().equals(call.getArgument(2))).toList()));
            when(sessions.selectOwnerByInstance(anyLong(), anyLong(), anyLong(), anyInt(), anyLong(), anyString()))
                    .thenAnswer(call -> games.values().stream().filter(row ->
                            row.getActivityId().equals(call.getArgument(0)) && row.getTopicId().equals(call.getArgument(1))
                            && row.getNodeId().equals(call.getArgument(2)) && row.getOwnerType().equals(call.getArgument(3))
                            && row.getOwnerId().equals(call.getArgument(4)) && row.getPlayInstanceId().equals(call.getArgument(5)))
                            .findFirst().orElse(null));
            when(sessions.insert(any())).thenAnswer(call -> {
                GameRuntimeSession row = call.getArgument(0); row.setId(ids.incrementAndGet()); games.put(row.getId(), row); return 1;
            });
            when(sessions.updateStateCas(any())).thenReturn(1);
            when(logs.selectByIdempotencyKey(anyString())).thenAnswer(call -> attempts.get(call.getArgument(0)));
            when(logs.insertPlayAttemptLog(any())).thenAnswer(call -> {
                PlayAttemptLog row = call.getArgument(0); row.setId(ids.incrementAndGet()); attempts.put(row.getIdempotencyKey(), row); return 1;
            });
            AdvancedActionTestFixtures.transactions(advanced);
            ReflectionTestUtils.setField(advanced, "sessionMapper", sessions);
            ReflectionTestUtils.setField(advanced, "nodeMapper", nodeMapper);
            ReflectionTestUtils.setField(advanced, "templateMapper", templateMapper);
            ReflectionTestUtils.setField(advanced, "activityMapper", activities);
            ReflectionTestUtils.setField(advanced, "registrationMapper", registrations);
            ReflectionTestUtils.setField(advanced, "teamMapper", mock(PlayTeamMapper.class));
            ReflectionTestUtils.setField(advanced, "attemptMapper", logs);
            ReflectionTestUtils.setField(advanced, "topicRouteRuntimeService", route);
            ReflectionTestUtils.setField(advanced, "clock", (AdvancedGameClock) () -> new Date(NOW));
        }

        Theme publish(long topicId, long nodeId, long templateId, String name, String question, String correct) throws Exception {
            CmsTopic topic = new CmsTopic(); topic.setId(topicId); topic.setName(name); topic.setRouteMode("LINEAR");
            topic.setPublishedConfigVersion(1L); topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
            ObjectNode theme = JSON.createObjectNode().put("schemaVersion", 1).put("capacity", 0);
            ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
            game.putObject("survival").put("zeroHpPolicy", "BLOCK_HP_COST").put("actionLimitPolicy", "BLOCK_OVERFLOW");
            game.putArray("acts").addObject().put("actKey", "a").putArray("questKeys").add("q");
            ((ObjectNode) game.path("acts").get(0)).putObject("completionRule").put("type","ALL");
            game.putArray("quests").addObject().put("questKey", "q").put("actKey", "a").put("nodeId", nodeId)
                    .put("locationKey", "gate").put("settlementSlot", "q");
            topic.setThemeRuntimeConfigJson(theme.toString()); topics.put(topicId, topic);
            CmsTopicNode node = new CmsTopicNode(); node.setId(nodeId); node.setTopicId(topicId);
            node.setStatus(1L); node.setMerchantMemberId(30L); node.setTemplateId(templateId); nodes.put(nodeId, node);
            CmsMemberTemplate template = new CmsMemberTemplate(); template.setId(templateId); template.setMemberId(PLAYER);
            template.setValidationMethod(3); template.setAdvancedConfigJson(pick(question, correct)); templates.put(templateId, template);
            publication.executeWithoutResult(status -> {
                topic.setThemeRuntimeConfigJson(route.compileTemplateRules(topic));
                topic.setPublishedThemeRuntimeConfigJson(topic.getThemeRuntimeConfigJson());
                topic.setPublishedRouteGraphJson(route.freezePublication(topic, null));
            });
            route.state(PLAYER, topicId, 0L);
            TopicRouteSession session = routeSessions.get(topicId);
            route.recordPlayerArrival(PLAYER, topicId, 0L, nodeId, "arrival-" + topicId, session.getVersion());
            route.gameplayAction(PLAYER, session.getId(), session.getVersion(), "start-" + topicId, "QUEST_START", "q", null);
            return new Theme(topicId, nodeId, templateId, JSON.readTree(session.getConfigSnapshotJson()), session);
        }

        GameRuntimeSession start(Theme theme) {
            Map<String,Object> view = advanced.start(PLAYER, 0L, theme.topic(), theme.node(), null);
            return games.get(((Number)view.get("sessionId")).longValue());
        }
        Map<String,Object> submit(GameRuntimeSession session, String key, String option) {
            return advanced.act(PLAYER, session.getId(), session.getVersion(), key, "SUBMIT_QA", Map.of("optionId", option));
        }
        JsonNode state(GameRuntimeSession session) throws Exception { return JSON.readTree(session.getStateJson()); }
        JsonNode routeState(Theme theme) throws Exception { return JSON.readTree(theme.session().getStateJson()); }
        boolean proved(Theme theme) throws Exception {
            return routeState(theme).path("themeRuntime").path("verifiedNodes").path(Long.toString(theme.node())).asLong() > 0;
        }
        void resolve(Theme theme, String key) {
            route.gameplayAction(PLAYER, theme.session().getId(), theme.session().getVersion(), key, "QUEST_RESOLVE", "q", null);
        }
        private static String routeKey(Long session, Long member, String action) { return session + ":" + member + ":" + action; }
    }
}
