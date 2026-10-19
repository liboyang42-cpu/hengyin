package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R14-PLAY 运行期缺口 1 / 4:选择结果当场写本局状态、大成功大失败额外效果。
 *
 * <p>正控:分支选项 / 问答选项 / 通过类玩法的 effects 立即落进 topic_route_session.state_json,
 * 分支同时写 sys.outcome.{nodeId}=选项序号;自然 20 追加 critEffects、自然 1 追加 fumbleEffects。
 * 负控:重放不重复写;非旅程主题零变化;非 20 点不执行 critEffects、非 1 点不执行 fumbleEffects。
 */
class JourneyChoiceEffectsTest {

    private static final long MEMBER = 7L;
    private static final long TOPIC = 10L;
    private static final long NODE_ID = 5009L;

    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
    private final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final PlayerActivityRoleMapper roleMapper = mock(PlayerActivityRoleMapper.class);
    private final IShopDayProofService shopDayProofService = mock(IShopDayProofService.class);
    private final ObjectMapper json = new ObjectMapper();

    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession[] stored;
    private List<TopicRouteAction> actions;
    private CmsTopicNode node;

    @BeforeEach
    void setUp() {
        stored = new TopicRouteSession[1];
        actions = new ArrayList<>();
        runtime = runtime(() -> 10);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setPublishedConfigVersion(1L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":0},"
                + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
                + "\"hard\":{\"dc\":16,\"damage\":3}}}");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC);
        node.setStatus(1L);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenAnswer(inv -> java.util.Collections.singletonList(stored[0]));
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction value = inv.getArgument(0);
            value.setId(600L + actions.size());
            actions.add(value);
            return 1;
        });
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> actions.stream()
                .filter(a -> inv.getArgument(0).equals(a.getSessionId())
                        && inv.getArgument(1).equals(a.getMemberId())
                        && inv.getArgument(2).equals(a.getActionId()))
                .findFirst().orElse(null));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.emptyList());
        when(shopDayProofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
        CmsRegistration pass = new CmsRegistration();
        pass.setId(9_000_001L); // 真实报名行必有主键；C包当前资格按原ID锁读
        pass.setExpiresAt(null);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.delegateLocks(activityMapper, registrationMapper);
    }

    // ==================== 1. 选择结果当场写状态 ====================

    @Test
    void branchChoiceWritesOptionEffectsAndOutcomeOrdinal() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "ask_linzhou", "to_exhibit");

        JsonNode state = state();
        assertEquals(2, state.path("variables").path("counter.disposition").asInt(), "选项 effects 当场写入");
        assertEquals(2, state.path("variables").path("sys.outcome." + NODE_ID).asInt(),
                "分支结果写选项序号(1 起)");
        assertEquals(1, actions.size());
        assertEquals("choice:" + NODE_ID + ":ask_linzhou:to_exhibit", actions.get(0).getActionId());
        assertEquals("CHOICE", actions.get(0).getOutcomeCode());
    }

    /** 负控:把 recordChoice 的写入路径关掉(不 applyJourneyEffects / 不写 sys.outcome),本用例红。 */
    @Test
    void branchChoiceReplayDoesNotWriteTwice() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "ask_linzhou", "to_correct");
        String afterFirst = stored[0].getStateJson();
        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "ask_linzhou", "to_correct");

        assertEquals(afterFirst, stored[0].getStateJson(), "同 actionId 重放不改状态");
        assertEquals(1, actions.size(), "只登记一次动作");
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
    }

    @Test
    void qaPickOptionEffectsWrittenForChosenOptionOnly() throws Exception {
        storeSession(qaBlock(), null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "qa", "wrong");

        JsonNode state = state();
        assertEquals(-1, state.path("variables").path("counter.attitude").asInt(),
                "选了哪个选项就写哪个选项的 effects");
        assertFalse(state.path("variables").has("counter.right"), "没选的选项效果不写");
        assertFalse(state.path("variables").has("sys.outcome." + NODE_ID), "问答不写 sys.outcome");
        assertEquals("choice:" + NODE_ID + ":qa:wrong", actions.get(0).getActionId());
    }

    /** 没配 effects 的选项不产生动作行、不顶版本(没配 = no-op)。 */
    @Test
    void qaOptionWithoutEffectsWritesNothing() throws Exception {
        storeSession("{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"题\",\"options\":["
                + "{\"id\":\"plain\",\"label\":\"无效果\",\"correct\":true}]}}", null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "qa", "plain");

        assertTrue(actions.isEmpty());
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    /**
     * 步骤 / 选项 id 各可到 64 字,拼出来的契约 id 可能超 action_id 的 96 位 ——
     * 超长时用稳定摘要收尾,长度守住,重放仍是同一个 id。
     */
    @Test
    void longStepAndOptionIdsGetAStableShortActionId() throws Exception {
        String step = repeat('s', 64);
        String option = repeat('o', 64);
        storeSession(branchBlockWithLongIds(step, option), null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, step, option);
        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, step, option);

        assertEquals(1, actions.size(), "同 id 重放不重复登记");
        String actionId = actions.get(0).getActionId();
        assertTrue(actionId.length() <= 96, actionId);
        assertTrue(actionId.startsWith("choice:" + NODE_ID + ":"), actionId);
        assertFalse(actionId.contains(step), "超长 id 不再原样进 action_id:" + actionId);
        assertEquals(1, state().path("variables").path("sys.outcome." + NODE_ID).asInt());
    }

    @Test
    void kitPassEffectsWrittenWithPassActionId() throws Exception {
        storeSession(kitBlock(), null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "sort", null);

        assertEquals(1, state().path("variables").path("clue.notes_restored").asInt());
        assertEquals("choice:" + NODE_ID + ":sort:pass", actions.get(0).getActionId());
        assertFalse(state().path("variables").has("sys.outcome." + NODE_ID));
    }

    /** 负控:非旅程主题(没有本局)时 recordChoice 必须完全 no-op。 */
    @Test
    void nonJourneyTopicChoiceChangesNothing() {
        stored[0] = null;

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "sort", null);

        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    @Test
    void unknownKitAndUnknownOptionChangeNothing() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "scan", null);
        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "ask_linzhou", "no_such_option");

        assertTrue(actions.isEmpty());
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    /** 老局快照没有 choiceEffects 冻结投影 = 这一局没有当场写状态的配置,不回退实时配置。 */
    @Test
    void oldSnapshotWithoutChoiceProjectionDoesNotWrite() throws Exception {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(99L);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStateJson("{\"status\":\"ACTIVE\"}");
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":" + NODE_ID
                + ",\"nodeJourney\":{\"" + NODE_ID + "\":{\"requires\":[]}}}");
        stored[0] = session;

        runtime.recordChoice(MEMBER, TOPIC, NODE_ID, "ask_linzhou", "to_correct");

        assertTrue(actions.isEmpty());
    }

    // ==================== 2. 判定型成败写 sys.passed ====================

    @Test
    void recordPassedTrueWritesSysPassedOne() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-1", true);

        assertEquals(1, state().path("variables").path("sys.passed." + NODE_ID).asInt());
        assertEquals(1, actions.size());
        assertEquals("passed:act-1", actions.get(0).getActionId());
    }

    @Test
    void recordPassedFalseWritesSysPassedZero() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-2", false);

        // 缺键时 asInt() 也是 0:先断言键存在,否则「根本没写」也能绿
        assertTrue(state().path("variables").has("sys.passed." + NODE_ID));
        assertEquals(0, state().path("variables").path("sys.passed." + NODE_ID).asInt());
    }

    /** 负控:同一 actionId 重放不二次写(状态、动作行、版本都不动)。 */
    @Test
    void recordPassedReplayDoesNotWriteTwice() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-3", true);
        String afterFirst = stored[0].getStateJson();
        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-3", false);

        assertEquals(afterFirst, stored[0].getStateJson(), "同 actionId 重放不改状态");
        assertEquals(1, actions.size(), "只登记一次动作");
        verify(routeMapper, times(1)).updateStateCas(any(TopicRouteSession.class));
    }

    /** 同一节点重复作答(新 actionId):后写覆盖先写,最后一次为准。 */
    @Test
    void recordPassedLaterAttemptOverwrites() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-a", true);
        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-b", false);

        assertEquals(0, state().path("variables").path("sys.passed." + NODE_ID).asInt());
    }

    /** 负控:非旅程主题(没有本局)时 recordPassed 完全 no-op。 */
    @Test
    void nonJourneyTopicPassedChangesNothing() {
        stored[0] = null;

        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-1", true);

        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    /**
     * 负控(阶段 1 加闸):主题没开检定时 recordPassed 一个字都不写。
     * 摘掉那一闸 → 这类主题每次判定型提交都多一行 action、多一轮 CAS → 本条必红。
     */
    @Test
    void checkDisabledTopicPassedChangesNothing() throws Exception {
        storeSession(branchBlock(), null);
        CmsTopic noCheck = new CmsTopic();
        noCheck.setId(TOPIC);
        noCheck.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":false}");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(noCheck);

        runtime.recordPassed(MEMBER, TOPIC, NODE_ID, "act-nocheck", true);

        assertFalse(state().path("variables").has("sys.passed." + NODE_ID));
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    /** 负控(跨主题):外来 nodeId(属别的主题)不得写进当前局的 sys.passed。 */
    @Test
    void foreignTopicNodePassedChangesNothing() throws Exception {
        storeSession(branchBlock(), null);
        CmsTopicNode foreign = new CmsTopicNode();
        foreign.setId(8888L);
        foreign.setTopicId(999L);                 // 不属于 TOPIC
        when(nodeMapper.selectCmsTopicNodeById(8888L)).thenReturn(foreign);

        runtime.recordPassed(MEMBER, TOPIC, 8888L, "act-foreign", true);

        assertFalse(state().path("variables").has("sys.passed.8888"));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    // ==================== 3b. 随机类结果写 sys.outcome ====================

    @Test
    void recordOutcomeWritesSysOutcome() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordOutcome(MEMBER, TOPIC, NODE_ID, "coin-1", 2);

        assertEquals(2, state().path("variables").path("sys.outcome." + NODE_ID).asInt());
        assertEquals("outcome:coin-1", actions.get(0).getActionId());
    }

    /** 负控:同 actionId 重放不二次写;与 recordPassed 共用写入路径,闸与幂等一处维护。 */
    @Test
    void recordOutcomeReplayDoesNotWriteTwice() throws Exception {
        storeSession(branchBlock(), null);

        runtime.recordOutcome(MEMBER, TOPIC, NODE_ID, "dice-1", 3);
        String afterFirst = stored[0].getStateJson();
        runtime.recordOutcome(MEMBER, TOPIC, NODE_ID, "dice-1", 5);

        assertEquals(afterFirst, stored[0].getStateJson());
        assertEquals(1, actions.size());
    }

    /** 负控:主题没开检定 = 链路 D 没有消费方,随机结果同样一个字都不写。 */
    @Test
    void checkDisabledTopicOutcomeChangesNothing() throws Exception {
        storeSession(branchBlock(), null);
        CmsTopic noCheck = new CmsTopic();
        noCheck.setId(TOPIC);
        noCheck.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":false}");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(noCheck);

        runtime.recordOutcome(MEMBER, TOPIC, NODE_ID, "coin-x", 1);

        assertFalse(state().path("variables").has("sys.outcome." + NODE_ID));
        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
    }

    // ==================== 4. 大成功 / 大失败 ====================

    @Test
    void critEffectsApplyOnlyOnNaturalTwenty() throws Exception {
        storeSession(checkBlock(), null);
        runtime = runtime(() -> 20);

        runtime.roll(MEMBER, TOPIC, NODE_ID, "c1");
        runtime.settle(MEMBER, TOPIC, NODE_ID, "c1");

        JsonNode state = state();
        assertEquals(1, state.path("variables").path("counter.base").asInt());
        assertEquals(1, state.path("variables").path("counter.crit").asInt(), "nat 20 追加 critEffects");
        assertFalse(state.path("variables").has("counter.fumble"));
    }

    /** 负控:critEffects 若在非 20 点也执行,本用例红。 */
    @Test
    void critEffectsDoNotApplyOnOrdinarySuccess() throws Exception {
        storeSession(checkBlock(), null);
        runtime = runtime(() -> 19);

        runtime.roll(MEMBER, TOPIC, NODE_ID, "c1");
        runtime.settle(MEMBER, TOPIC, NODE_ID, "c1");

        JsonNode state = state();
        assertEquals(1, state.path("variables").path("counter.base").asInt(), "19 点成功,successEffects 照常");
        assertFalse(state.path("variables").has("counter.crit"), "非自然 20 不执行 critEffects");
    }

    @Test
    void fumbleEffectsApplyOnlyOnNaturalOne() throws Exception {
        storeSession(checkBlock(), null);
        runtime = runtime(() -> 1);

        runtime.roll(MEMBER, TOPIC, NODE_ID, "c1");
        runtime.settle(MEMBER, TOPIC, NODE_ID, "c1");

        JsonNode state = state();
        assertEquals(1, state.path("variables").path("counter.failed").asInt());
        assertEquals(1, state.path("variables").path("counter.fumble").asInt(), "nat 1 追加 fumbleEffects");
    }

    /** 负控:fumbleEffects 若在非 1 点的失败上也执行,本用例红。 */
    @Test
    void fumbleEffectsDoNotApplyOnOrdinaryFailure() throws Exception {
        storeSession(checkBlock(), null);
        runtime = runtime(() -> 2);

        runtime.roll(MEMBER, TOPIC, NODE_ID, "c1");
        runtime.settle(MEMBER, TOPIC, NODE_ID, "c1");

        JsonNode state = state();
        assertEquals(1, state.path("variables").path("counter.failed").asInt(), "2 点失败,failEffects 照常");
        assertFalse(state.path("variables").has("counter.fumble"), "非自然 1 不执行 fumbleEffects");
    }

    // ==================== helpers ====================

    private TopicRouteRuntimeServiceImpl runtime(java.util.function.IntSupplier d20) {
        return com.chengyinhub.business.service.support.SyntheticLegacyRuntimeReader.attach(new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper, templateMapper,
                progressMapper, activityMapper, registrationMapper, roleMapper, shopDayProofService,
                () -> 12345L, d20, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN), templateMapper);
    }

    private JsonNode state() throws Exception {
        return json.readTree(stored[0].getStateJson());
    }

    private void storeSession(String blockJson, String stateJson) throws Exception {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(99L);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(0L);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setVersion(0);
        session.setStateJson(stateJson == null
                ? "{\"status\":\"ACTIVE\",\"variables\":{}}" : stateJson);
        session.setConfigSnapshotJson(snapshotFor(blockJson));
        stored[0] = session;
    }

    /** 用真实的建局冻结投影把块配置冻进快照 —— 与生产链路同一个函数。 */
    private String snapshotFor(String blockJson) throws Exception {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("startNodeId", NODE_ID);
        ObjectNode block = snapshot.putObject(NodeJourneySnapshot.NODE_JOURNEY_KEY)
                .putObject(String.valueOf(NODE_ID));
        NodeJourneySnapshot.copyJourneySegments(json.readTree(blockJson), block);
        return json.writeValueAsString(snapshot);
    }

    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String branchBlockWithLongIds(String stepId, String optionId) {
        return "{\"branch\":{\"enabled\":true,\"startStepId\":\"" + stepId + "\",\"steps\":["
                + "{\"id\":\"" + stepId + "\",\"options\":[{\"id\":\"" + optionId + "\",\"label\":\"选项\","
                + "\"nextStepId\":\"done\",\"effects\":[{\"op\":\"SET\",\"var\":\"clue.picked\",\"value\":1}]}]},"
                + "{\"id\":\"done\",\"terminal\":true,\"outcomeCode\":\"done\",\"options\":[]}]}}";
    }

    private static String branchBlock() {
        return "{\"branch\":{\"enabled\":true,\"startStepId\":\"ask_linzhou\",\"steps\":["
                + "{\"id\":\"ask_linzhou\",\"title\":\"写进公开记录前\",\"options\":["
                + "{\"id\":\"to_correct\",\"label\":\"更正\",\"nextStepId\":\"correct\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":1}]},"
                + "{\"id\":\"to_exhibit\",\"label\":\"展示\",\"nextStepId\":\"exhibit\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":2}]},"
                + "{\"id\":\"to_custody\",\"label\":\"保管\",\"nextStepId\":\"custody\","
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.disposition\",\"value\":3}]}]},"
                + "{\"id\":\"correct\",\"terminal\":true,\"outcomeCode\":\"correct\",\"options\":[]},"
                + "{\"id\":\"exhibit\",\"terminal\":true,\"outcomeCode\":\"exhibit\",\"options\":[]},"
                + "{\"id\":\"custody\",\"terminal\":true,\"outcomeCode\":\"custody\",\"options\":[]}]}}";
    }

    private static String qaBlock() {
        return "{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"题\",\"options\":["
                + "{\"id\":\"right\",\"label\":\"对\",\"correct\":true,"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.right\",\"value\":1}]},"
                + "{\"id\":\"wrong\",\"label\":\"错\",\"correct\":false,"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"counter.attitude\",\"value\":-1}]}],\"xp\":5}}";
    }

    private static String kitBlock() {
        return "{\"sort\":{\"enabled\":true,\"prompt\":\"排序\",\"items\":["
                + "{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"}],"
                + "\"answerOrder\":[\"a\",\"b\"],"
                + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.notes_restored\",\"value\":1}]}}";
    }

    private static String checkBlock() {
        return "{\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"easy\","
                + "\"successEffects\":[{\"op\":\"SET\",\"var\":\"counter.base\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"SET\",\"var\":\"counter.failed\",\"value\":1}],"
                + "\"critEffects\":[{\"op\":\"SET\",\"var\":\"counter.crit\",\"value\":1}],"
                + "\"fumbleEffects\":[{\"op\":\"SET\",\"var\":\"counter.fumble\",\"value\":1}]}}";
    }
}
