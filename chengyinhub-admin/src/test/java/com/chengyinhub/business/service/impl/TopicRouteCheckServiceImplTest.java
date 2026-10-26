package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B5 检定/重掷/结算/力竭休整:固定骰点三条路径、优劣势、封顶、幂等与前置拒绝。
 * 骰点由构造注入的 IntSupplier 固定;所有幂等动作都走 topic_route_action 的 actionId 重放。
 */
class TopicRouteCheckServiceImplTest {
    private static final long MEMBER = 7L, TOPIC = 10L, NODE = 1L, SESSION = 99L;
    /** 第二站:验「力竭后还能不能在别的站掷骰」,同一站会先撞「该检定已结算」。 */
    private static final long NODE2 = 2L;
    private static final String CHECK_ID = "ask_arong";

    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
    private final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final PlayerActivityRoleMapper roleMapper = mock(PlayerActivityRoleMapper.class);
    private final IShopDayProofService proofService = mock(IShopDayProofService.class);
    private final ThemeStateKeyMapper themeStateKeyMapper = mock(ThemeStateKeyMapper.class);
    private final IPlayEncounterService encounterService = mock(IPlayEncounterService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayDeque<Integer> diceQueue = new ArrayDeque<Integer>();
    private final List<TopicRouteAction> actions = new ArrayList<TopicRouteAction>();
    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession session;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        runtime = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper, templateMapper,
                progressMapper, activityMapper, registrationMapper, roleMapper, proofService,
                () -> 12345L, () -> diceQueue.removeFirst(), Clock.systemUTC(),
                RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", themeStateKeyMapper);
        ReflectionTestUtils.setField(runtime, "playEncounterService", encounterService);
        when(encounterService.resolve(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(null);
        topic = new CmsTopic();
        topic.setId(TOPIC); topic.setRouteMode("BRANCH_GRAPH");
        topic.setPublishedConfigVersion(1L);
        topic.setPublishedRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"edges\":[]}");
        topic.setJourneyRulesJson(rules(10, 3, 1, 6, "tag.injured", true, true));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE); node.setTopicId(TOPIC); node.setStatus(1L); node.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        CmsTopicNode node2 = new CmsTopicNode();
        node2.setId(NODE2); node2.setTopicId(TOPIC); node2.setStatus(1L); node2.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeById(NODE2)).thenReturn(node2);
        template(check("easy", 2, "成了", "砸了", true));
        CmsRegistration registration = new CmsRegistration();
        registration.setId(9000001L);registration.setMemberId(MEMBER);registration.setOwnerType(1L);registration.setOwnerId(TOPIC);
        registration.setPaymentStatus(2);registration.setRegistrationStatus(2);registration.setPurchaseKind(2);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(9000001L)).thenReturn(registration);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(registration));
        when(proofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
        session = newSession();
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenAnswer(inv -> java.util.Collections.singletonList(session));
        // CORE OWN-C06-1x：检定按「当前局」定位（selectCurrentByOwner），只回该场次的本局。
        when(routeMapper.selectCurrentByOwner(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(TOPIC),
                org.mockito.ArgumentMatchers.eq(MEMBER))).thenAnswer(inv -> session != null
                && java.util.Objects.equals(inv.getArgument(0), session.getActivityId()) ? session : null);
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction action = inv.getArgument(0);
            action.setId(500L + actions.size());
            actions.add(action);
            return 1;
        });
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> actions.stream()
                .filter(a -> inv.getArgument(0).equals(a.getSessionId())
                        && inv.getArgument(1).equals(a.getMemberId())
                        && inv.getArgument(2).equals(a.getActionId())).findFirst().orElse(null));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenAnswer(inv -> {
            TopicRouteSession value = inv.getArgument(0);
            value.setVersion(value.getVersion() + 1);
            return 1;
        });
        // CORE OWN-C06-25：检定回执总以最终结算值回写原动作；夹具把回写落到已记录的动作上。
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenAnswer(inv -> {
            for (TopicRouteAction action : actions) {
                if (inv.getArgument(0).equals(action.getId())) { action.setResponseJson(inv.getArgument(1)); return 1; }
            }
            return 0;
        });
    }

    @Test
    void scoringBridgeLocksBeforeActionAndRunsOnlyAfterWinningCas() {
        var bridge = mock(com.chengyinhub.business.service.support.OrientRouteSettlement.class);
        ReflectionTestUtils.setField(runtime, "orientRouteSettlement", bridge);
        when(bridge.active(any())).thenReturn(true);
        when(bridge.lock(session)).thenReturn(session);
        diceQueue.add(12);
        runtime.roll(MEMBER, TOPIC, null, NODE, CHECK_ID);
        var order = org.mockito.Mockito.inOrder(bridge, routeMapper);
        order.verify(bridge).lock(session);
        order.verify(routeMapper).insertAction(any());
        order.verify(routeMapper).updateStateCas(session);
        order.verify(bridge).complete(org.mockito.ArgumentMatchers.eq(session), any());
        order.verify(routeMapper).updateActionResponse(anyLong(), anyString());
        org.mockito.Mockito.clearInvocations(bridge);
        runtime.roll(MEMBER, TOPIC, null, NODE, CHECK_ID);
        org.mockito.Mockito.verifyNoInteractions(bridge);
    }

    @Test
    void journeyCommitBridgeRunsAfterCasAndResponseFailurePropagates() {
        var bridge = mock(com.chengyinhub.business.service.support.OrientRouteSettlement.class);
        ReflectionTestUtils.setField(runtime, "orientRouteSettlement", bridge);
        when(bridge.active(any())).thenReturn(true); when(bridge.lock(session)).thenReturn(session);
        var state = ReflectionTestUtils.invokeMethod(runtime, "readEngineState", session.getStateJson());
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(runtime,
                "commitJourneyAction", session, NODE, "journey:bridge", "CHOICE", new LinkedHashMap<String,Object>(), state));
        var order = org.mockito.Mockito.inOrder(bridge, routeMapper);
        order.verify(bridge).lock(session);
        order.verify(routeMapper).insertAction(any());
        order.verify(routeMapper).updateStateCas(session);
        order.verify(bridge).complete(org.mockito.ArgumentMatchers.eq(session), any());
        order.verify(routeMapper).updateActionResponse(anyLong(), anyString());
    }

    @Test
    void losingCheckCasNeverReachesScoringBridge() {
        var bridge = mock(com.chengyinhub.business.service.support.OrientRouteSettlement.class);
        ReflectionTestUtils.setField(runtime, "orientRouteSettlement", bridge);
        when(bridge.active(any())).thenReturn(true); when(bridge.lock(session)).thenReturn(session);
        when(routeMapper.updateStateCas(any())).thenReturn(0);
        diceQueue.add(12);
        assertThrows(ServiceException.class, () -> runtime.roll(MEMBER, TOPIC, null, NODE, CHECK_ID));
        org.mockito.Mockito.verify(bridge, org.mockito.Mockito.never()).complete(any(), any());
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).updateActionResponse(anyLong(), anyString());
    }

    @Test
    void scoringFailurePropagatesBeforeOriginalCheckResponseIsFinalized() {
        var bridge = mock(com.chengyinhub.business.service.support.OrientRouteSettlement.class);
        ReflectionTestUtils.setField(runtime, "orientRouteSettlement", bridge);
        when(bridge.active(any())).thenReturn(true); when(bridge.lock(session)).thenReturn(session);
        org.mockito.Mockito.doThrow(new IllegalStateException("settlement unavailable")).when(bridge).complete(any(), any());
        diceQueue.add(12);
        assertThrows(IllegalStateException.class, () -> runtime.roll(MEMBER, TOPIC, null, NODE, CHECK_ID));
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).updateActionResponse(anyLong(), anyString());
        // Real transaction rollback is separately covered by the MySQL fixture; mocks do not prove rollback.
    }

    @Test
    void originalReceiptRemainsReadableAfterRunEndsWithoutAnyMutation() {
        diceQueue.add(12);
        runtime.roll(MEMBER, TOPIC, null, NODE, CHECK_ID);
        when(routeMapper.selectAccessibleById(SESSION, MEMBER)).thenReturn(session);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        String unchanged = session.getStateJson();
        int count = actions.size();
        org.mockito.Mockito.clearInvocations(routeMapper);
        PlayCheckReceipt result = runtime.checkReceipt(MEMBER, SESSION, NODE, CHECK_ID, "roll");
        assertEquals(String.valueOf(SESSION), result.getSessionId());
        assertEquals(String.valueOf(NODE), result.getNodeId());
        assertEquals("check:1:ask_arong:roll", result.getActionId());
        assertEquals(unchanged, session.getStateJson()); assertEquals(count, actions.size());
        org.mockito.Mockito.verify(routeMapper).selectAccessibleById(SESSION, MEMBER);
        org.mockito.Mockito.verify(routeMapper).selectActionByAction(SESSION, MEMBER, result.getActionId());
        org.mockito.Mockito.verifyNoMoreInteractions(routeMapper);
        assertThrows(ServiceException.class, () -> runtime.checkReceipt(8L, SESSION, NODE, CHECK_ID, "roll"));
        assertThrows(ServiceException.class, () -> runtime.checkReceipt(MEMBER, SESSION, NODE, CHECK_ID, "settle"));
    }

    @Test
    void explicitOriginalSessionCannotWriteIntoAnotherTopicOrActivity() {
        when(routeMapper.selectAccessibleById(SESSION, MEMBER)).thenReturn(session);
        assertThrows(IllegalArgumentException.class, () -> runtime.roll(MEMBER, TOPIC + 1, null, NODE, CHECK_ID, SESSION));
        assertThrows(IllegalArgumentException.class, () -> runtime.roll(MEMBER, TOPIC, 88L, NODE, CHECK_ID, SESSION));
        assertTrue(actions.isEmpty());
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).updateStateCas(any());
    }

    @Test
    void outcomeWritesOnlyTheValidatedActivitySessionAndReplayDoesNotRepeat() throws Exception {
        TopicRouteSession first = newSession();
        first.setId(801L); first.setActivityId(11L);
        TopicRouteSession second = newSession();
        second.setId(802L); second.setActivityId(12L);
        String untouched = second.getStateJson();
        when(routeMapper.selectByOwner(11L, TOPIC, MEMBER, "default")).thenReturn(first);
        when(routeMapper.selectByOwner(12L, TOPIC, MEMBER, "default")).thenReturn(second);
        runtime.recordOutcome(MEMBER, TOPIC, 11L, NODE, "dice-a", 6);
        runtime.recordOutcome(MEMBER, TOPIC, 11L, NODE, "dice-a", 2);
        assertEquals(6, json.readTree(first.getStateJson()).path("variables").path("sys.outcome." + NODE).asInt());
        assertEquals(untouched, second.getStateJson());
        assertEquals(1, actions.size());
        assertEquals(801L, actions.get(0).getSessionId());
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).selectMemberTopicSessions(MEMBER, TOPIC);
    }

    // ==================== 三条固定骰点路径 ====================

    @Test
    void fixedDiceSuccessAppliesSuccessEffectsAndOnlySettledReceiptCarriesText() {
        diceQueue.add(9);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(Integer.valueOf(11), roll.getTotal(), "9 + 2 = 11 >= dc 10");
        assertEquals(Integer.valueOf(10), roll.getDc(), "dc 取自 journeyRules.easy");
        assertEquals("easy", roll.getTier());
        assertEquals(Boolean.TRUE, roll.getSuccess());
        assertNull(roll.getNat());
        assertFalse(roll.isSettled());
        assertNull(roll.getText(), "roll 回执不带文案");
        assertEquals(1, roll.getMods().size());
        assertTrue(roll.getMods().get(0).isApplied());
        assertEquals("手握签收联 071", roll.getMods().get(0).getLabel());
        assertEquals(Integer.valueOf(10), roll.getHp());
        assertEquals(Integer.valueOf(3), roll.getLuck());
        assertFalse(roll.isExhausted());
        assertEquals(Integer.valueOf(1), roll.getStateVersion());
        assertFalse(state().path("variables").has("counter.wins"), "roll 不结算、不发效果");

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(settle.isSettled());
        assertEquals("成了", settle.getText());
        assertEquals(Boolean.TRUE, settle.getSuccess());
        assertEquals(1, state().path("variables").path("counter.wins").asInt());
        assertFalse(state().path("variables").has("counter.fails"));
        assertEquals(1, state().path("variables").path("sys.passed." + NODE).asInt(), "检定过了也落 sys.passed");
        assertEquals(0, state().path("tags").size());
        assertEquals(Integer.valueOf(2), settle.getStateVersion());
    }

    @Test
    void fixedDiceFailureDeductsTierDamageAndAddsFailCostTag() {
        template(check("medium", 0, "成了", "砸了", true));
        diceQueue.add(2);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(Boolean.FALSE, roll.getSuccess());
        assertEquals(Integer.valueOf(13), roll.getDc(), "dc 取自 journeyRules.medium");
        assertEquals(Integer.valueOf(2), roll.getTotal());

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(Boolean.FALSE, settle.getSuccess());
        assertEquals("砸了", settle.getText());
        assertEquals(Integer.valueOf(8), settle.getHp(), "10 - medium.damage 2");
        // 先断言键存在:缺键时 asInt() 也是 0,只比值会假绿
        assertTrue(state().path("variables").has("sys.passed." + NODE), "检定没过也要落 sys.passed");
        assertEquals(0, state().path("variables").path("sys.passed." + NODE).asInt(), "检定没过落 sys.passed=0");
        assertTrue(state().path("tags").toString().contains("tag.hurried"));
        assertEquals(1, state().path("variables").path("counter.fails").asInt());
        assertFalse(state().path("variables").has("counter.wins"));
        assertFalse(settle.isExhausted());
    }

    // ==================== R17-FIX:失败回执的代价标签 ====================

    /** 失败回执带注册表里该 tag 的 label;掷骰回执(未结算)不带。 */
    @Test
    void settleFailureReceiptCarriesRegisteredFailCostTagLabel() {
        template(check("medium", 0, "成了", "砸了", true));
        stubCostTagLabel("tag.hurried", "匆忙");
        diceQueue.add(2);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertNull(roll.getFailCostLabel(), "掷骰回执不带代价标签");

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(Boolean.FALSE, settle.getSuccess());
        assertEquals("匆忙", settle.getFailCostLabel());
    }

    /** 成功回执不带代价标签(成功没有代价)。 */
    @Test
    void settleSuccessReceiptHasNoFailCostLabel() {
        stubCostTagLabel("tag.hurried", "匆忙");
        diceQueue.add(9);

        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(Boolean.TRUE, settle.getSuccess());
        assertNull(settle.getFailCostLabel(), "成功回执不带代价标签");
    }

    /** 未注册的 tag 不抛错:label 回落空串,tag 仍照旧落进本局。 */
    @Test
    void settleFailureWithUnregisteredCostTagFallsBackToEmptyLabel() {
        template(check("medium", 0, "成了", "砸了", true));
        when(themeStateKeyMapper.selectByTopicId(TOPIC)).thenReturn(Collections.<ThemeStateKey>emptyList());
        diceQueue.add(2);

        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals("", settle.getFailCostLabel());
        assertTrue(state().path("tags").toString().contains("tag.hurried"));
    }

    @Test
    void fumbleAlwaysFailsEvenWhenTotalBeatsDcAndAddsExtraDamage() {
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":10},\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
                + "\"tiers\":{\"easy\":{\"dc\":5,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
                + "\"hard\":{\"dc\":16,\"damage\":3}},\"fumbleExtraDamage\":1,\"modCap\":6}");
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "\"value\":99,\"label\":\"硬塞\"}]}}");
        diceQueue.add(1);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(PlayCheckReceipt.NAT_FUMBLE, roll.getNat());
        assertEquals(Integer.valueOf(7), roll.getTotal(), "1 + modCap 6 = 7,已超过 dc 5");
        assertEquals(Boolean.FALSE, roll.getSuccess(), "nat 1 必失败,哪怕 total 已经过线");

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(Integer.valueOf(8), settle.getHp(), "easy.damage 1 + fumbleExtraDamage 1");
        assertFalse(settle.isExhausted());
    }

    @Test
    void criticalAlwaysSucceedsEvenWhenTotalMissesDc() {
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":10},\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
                + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
                + "\"hard\":{\"dc\":25,\"damage\":3}},\"fumbleExtraDamage\":1,\"modCap\":6}");
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"hard\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "\"value\":-99,\"label\":\"debuff\"}],"
                + "\"advantageIf\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}}");
        diceQueue.add(20); diceQueue.add(3);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(roll.isAdvantage());
        assertEquals(Arrays.asList(20, 3), roll.getDice());
        assertEquals(Integer.valueOf(20), roll.getKept(), "优势取高");
        assertEquals(PlayCheckReceipt.NAT_CRIT, roll.getNat());
        assertEquals(Boolean.TRUE, roll.getSuccess(), "nat 20 必成功,哪怕 total 已经被 debuff 打到过不了 dc");
    }

    // ==================== 链路 D:上一站的成败真的influence这一站 ====================

    /**
     * 阶段 1 规格点名的负控:sys.passed 不是"写进去就算完",要能被下一站引用并真的改变结果。
     * 去掉 recordPassed 的写入 → 这里 advantageIf 不成立 → 单骰 4 → 本条必红。
     */
    @Test
    void passedFactFromEarlierNodeGrantsAdvantageOnThisCheck() {
        CmsTopicNode earlier = new CmsTopicNode();
        earlier.setId(2L); earlier.setTopicId(TOPIC); earlier.setStatus(1L);
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(earlier);
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\","
                + "\"advantageIf\":[{\"var\":\"sys.passed.2\",\"op\":\"EQ\",\"value\":1}]}}");
        runtime.recordPassed(MEMBER, TOPIC, 2L, "qa-1", true);
        diceQueue.add(17); diceQueue.add(4);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(roll.isAdvantage(), "上一站答对应当让这一站拿到优势");
        assertEquals(Arrays.asList(17, 4), roll.getDice());
        assertEquals(Integer.valueOf(17), roll.getKept(), "优势取高");
    }

    /** 配对负控:没有那条成败事实时不得有优势 —— 防止上一条变成恒真断言。 */
    @Test
    void withoutPassedFactTheSameCheckHasNoAdvantage() {
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\","
                + "\"advantageIf\":[{\"var\":\"sys.passed.2\",\"op\":\"EQ\",\"value\":1}]}}");
        diceQueue.add(4);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertFalse(roll.isAdvantage());
        assertEquals(Collections.singletonList(4), roll.getDice(), "没有优势只掷一颗");
    }

    @Test
    void disadvantageKeepsTheLowDie() {
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\","
                + "\"disadvantageIf\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},"
                + "{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}}");
        diceQueue.add(17); diceQueue.add(4);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(roll.isDisadvantage());
        assertFalse(roll.isAdvantage());
        assertEquals(Arrays.asList(17, 4), roll.getDice());
        assertEquals(Integer.valueOf(4), roll.getKept(), "劣势取低");
        assertEquals(Boolean.FALSE, roll.getSuccess());
    }

    @Test
    void advantageAndDisadvantageCancelIntoASingleDie() {
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\","
                + "\"advantageIf\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
                + "\"disadvantageIf\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]}}");
        diceQueue.add(12);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertFalse(roll.isAdvantage());
        assertFalse(roll.isDisadvantage());
        assertEquals(Collections.singletonList(12), roll.getDice(), "抵消后只掷一颗");
        assertEquals(Integer.valueOf(12), roll.getKept());
        assertEquals(Boolean.TRUE, roll.getSuccess());
    }

    @Test
    void appliedModsAreCappedByModCap() {
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "\"value\":5,\"label\":\"甲\"},{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
                + "\"value\":5,\"label\":\"乙\"}]}}");
        diceQueue.add(5);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(2, roll.getMods().size());
        assertTrue(roll.getMods().get(0).isApplied());
        assertEquals(Integer.valueOf(11), roll.getTotal(), "5 + 封顶 6 = 11(未封顶是 15)");
        assertEquals(Boolean.TRUE, roll.getSuccess());
    }

    // ==================== 幂等 ====================

    @Test
    void rollIsIdempotentAndNeverRerollsTheDice() {
        diceQueue.add(9);
        PlayCheckReceipt first = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        PlayCheckReceipt second = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        PlayCheckReceipt third = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(first.getTotal(), second.getTotal());
        assertEquals(first.getDice(), third.getDice());
        assertEquals(1, actions.size(), "同 id 重放不再登记动作");
        assertTrue(diceQueue.isEmpty(), "重放不得消耗骰点;若重掷这里会先抛 NoSuchElementException");
        assertEquals(Integer.valueOf(1), third.getStateVersion(), "重放不改版本");
    }

    // ==================== M1 回执自带新世界 ====================

    @Test
    void checkReceiptsCarryNewVersionAndRefreshedEncounterAndPersistIt() throws Exception {
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenAnswer(inv -> {
            long actionId = ((Number) inv.getArgument(0)).longValue();
            for (TopicRouteAction action : actions) {
                if (action.getId() != null && action.getId().longValue() == actionId) {
                    action.setResponseJson(inv.getArgument(1));
                }
            }
            return 1;
        });
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", NODE);
        encounter.put("stateVersion", 1);
        when(encounterService.resolve(MEMBER, TOPIC, 0L, NODE)).thenReturn(encounter);
        diceQueue.add(9);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(Integer.valueOf(1), roll.getStateVersion(), "roll 回包自带新 version");
        assertNotNull(roll.getEncounter(), "roll 回包自带这一站刷新后的 Encounter");

        PlayCheckReceipt rollAgain = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(json.writeValueAsString(roll.getEncounter()),
                json.writeValueAsString(rollAgain.getEncounter()), "同动作重放返回同一份回执(含世界)");

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(Integer.valueOf(2), settle.getStateVersion(), "settle 回包自带新 version");
        assertNotNull(settle.getEncounter(), "settle 回包自带 Encounter");
        assertTrue(actions.get(1).getResponseJson().contains("\"encounter\""), "世界随回执落库,重放不丢");
    }

    @Test
    void restReceiptCarriesNewVersionAndRefreshedEncounter() {
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", NODE);
        encounter.put("stateVersion", 1);
        when(encounterService.resolve(MEMBER, TOPIC, 0L, NODE)).thenReturn(encounter);
        template(check("hard", 0, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(1, 3)));
        diceQueue.add(2);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        PlayCheckReceipt rest = runtime.rest(MEMBER, TOPIC, NODE);

        assertEquals(Integer.valueOf(3), rest.getStateVersion(), "rest 回包自带新 version");
        assertNotNull(rest.getEncounter(), "rest 回包自带 Encounter");
        assertEquals(Integer.valueOf(5), rest.getHp(), "休整回血不受回执化影响");
    }

    @Test
    void settleIsIdempotentAndDeductsHpOnlyOnce() {
        template(check("medium", 0, "成了", "砸了", true));
        diceQueue.add(2);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        PlayCheckReceipt first = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        PlayCheckReceipt second = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(first.getHp(), second.getHp());
        assertEquals(Integer.valueOf(8), second.getHp());
        assertEquals(1, state().path("variables").path("counter.fails").asInt(), "失败效果只应用一次");
        assertEquals(1, state().path("tags").size());
        assertEquals(2, actions.size(), "roll + settle 各一行");
    }

    @Test
    void rerollReplacesTheLastResultAndChargesLuckOnce() {
        template(check("easy", 0, "成了", "砸了", true));
        diceQueue.add(2);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(Boolean.FALSE, roll.getSuccess());
        diceQueue.add(18);

        PlayCheckReceipt rerolled = runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(rerolled.isRerolled());
        assertEquals(Boolean.TRUE, rerolled.getSuccess());
        assertEquals(Integer.valueOf(18), rerolled.getKept());
        assertEquals(Integer.valueOf(2), rerolled.getLuck(), "3 - 1");

        PlayCheckReceipt repeated = runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(rerolled.getDice(), repeated.getDice());
        assertEquals(Integer.valueOf(2), repeated.getLuck(), "重复调用不得二次扣幸运");
        assertEquals(2, actions.size(), "roll + reroll 各一行");

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(Boolean.TRUE, settle.getSuccess(), "结算以重掷结果为准");
        assertTrue(settle.isRerolled());
        assertEquals("成了", settle.getText());
    }

    @Test
    void rerollIsRejectedWhenLuckIsDisabledOrExhausted() {
        diceQueue.add(2);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        topic.setJourneyRulesJson(rules(10, 3, 1, 6, "tag.injured", true, false));
        IllegalStateException disabled = assertThrows(IllegalStateException.class,
                () -> runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID));
        assertEquals("本主题未启用幸运重掷", disabled.getMessage());

        topic.setJourneyRulesJson(rules(10, 0, 1, 6, "tag.injured", true, true));
        session.setStateJson(stateWith(vitals(10, 0)));
        IllegalStateException zero = assertThrows(IllegalStateException.class,
                () -> runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID));
        assertEquals("幸运值不足", zero.getMessage());
        assertEquals(1, actions.size(), "被拒的重掷不留动作");
    }

    @Test
    void rerollWithoutRollAndRerollAfterSettleAreRejected() {
        IllegalStateException beforeRoll = assertThrows(IllegalStateException.class,
                () -> runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID));
        assertEquals("请先掷骰", beforeRoll.getMessage());

        diceQueue.add(12);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        IllegalStateException rerollAfterSettle = assertThrows(IllegalStateException.class,
                () -> runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID));
        assertEquals("该检定已结算", rerollAfterSettle.getMessage());

        PlayCheckReceipt replay = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(roll.getDice(), replay.getDice(), "roll 重放原样返回上次结果,不重掷");
        assertFalse(replay.isSettled());
        assertEquals(2, actions.size(), "roll + settle 各一行;重放不再登记");
    }

    // ==================== 前置 ====================

    @Test
    void missingSessionIsRejected() {
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID));

        assertEquals("本局尚未开始", error.getMessage());
    }

    @Test
    void arrivalRequiresCheckAndJourneyRuleGatesAllReject() {
        when(proofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(false);
        assertEquals("请先扫码确认到店", assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID)).getMessage());
        when(proofService.hasArrived(MEMBER, 0L, NODE)).thenReturn(true);

        template(check("easy", 0, "成了", "砸了", false));
        assertEquals("该节点未启用检定", assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID)).getMessage());

        template(check("easy", 0, "成了", "砸了", true)
                .replace("\"checkId\":\"" + CHECK_ID + "\"", "\"checkId\":\"other_check\""));
        assertEquals("检定标识不匹配", assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID)).getMessage());

        topic.setJourneyRulesJson(rules(10, 3, 1, 6, "tag.injured", false, true));
        template(check("easy", 0, "成了", "砸了", true));
        assertEquals("本主题未启用检定", assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID)).getMessage());

        topic.setJourneyRulesJson(rules(10, 3, 1, 6, "tag.injured", true, true));
        template("{\"requires\":[{\"var\":\"clue.missing\",\"op\":\"GTE\",\"value\":1}],"
                + "\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"easy\"}}");
        assertEquals("本站前置条件未满足", assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID)).getMessage());
        assertEquals(0, actions.size(), "被拒的检定不留动作、不掷骰");
    }

    // ==================== 力竭与休整 ====================

    @Test
    void hpZeroMarksExhaustedAndRestRecoversOnceWithTag() {
        template(check("hard", 0, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(1, 3)));
        diceQueue.add(2);

        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(settle.isExhausted());
        assertEquals(Integer.valueOf(0), settle.getHp(), "hard 3 + fumble 1 打到 0 以下,下限 0");
        assertEquals(1, state().path("variables").path("sys.exhausted").asInt());

        PlayCheckReceipt rest = runtime.rest(MEMBER, TOPIC, NODE);

        assertFalse(rest.isExhausted());
        assertEquals(Integer.valueOf(5), rest.getHp(), "recovery.exhaustRestHp");
        assertFalse(state().path("variables").has("sys.exhausted"));
        assertTrue(state().path("tags").toString().contains("tag.injured"));

        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> runtime.rest(MEMBER, TOPIC, NODE));
        assertEquals("尚未力竭，无需休整", again.getMessage());
    }

    // ==================== 阶段 3:力竭要有后果 ====================

    /**
     * 力竭之前 HP 只是个数字:血扣到 0,游戏照玩、节点照推进、检定照掷,
     * sys.exhausted 除了回执一行字什么都不 gate。这条是那个后果本身。
     */
    @Test
    void exhaustedPlayerCannotRollUntilResting() {
        template(check("hard", 0, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(1, 3)));
        diceQueue.add(2);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        assertEquals(1, state().path("variables").path("sys.exhausted").asInt(), "前提:已力竭");

        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE2, CHECK_ID));

        assertTrue(blocked.getMessage().contains("休整"), "错误要指向休整,别让玩家猜为什么点不动");
    }

    /** 休整之后恢复正常 —— 否则「力竭」就成了单程票。 */
    @Test
    void restingLetsThePlayerRollAgain() {
        template(check("hard", 0, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(1, 3)));
        diceQueue.add(2);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        runtime.rest(MEMBER, TOPIC, NODE);

        diceQueue.add(15);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE2, CHECK_ID);

        assertEquals(Integer.valueOf(15), roll.getKept());
    }

    /**
     * 已经掷出、未结算的检定不受影响 —— 力竭拦的是「再开一次」,
     * 不是把玩家卡在一个掷了骰子却结算不了的中间态。
     */
    @Test
    void exhaustionDoesNotStrandAnAlreadyRolledCheck() {
        template(check("easy", 1, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(6, 3)));
        diceQueue.add(4);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        // 关键:力竭必须来自**别处**。若靠这一次 settle 自己扣到 0,闸在扣血前读 state、
        // 此时尚未力竭 —— 就算给 settle 也加上闸,这条测试照样绿,等于没守住任何东西。
        setVariable("sys.exhausted", 1);
        setVariable("sys.hp", 0);

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertTrue(settle.isSettled(), "已掷未结算的那次必须能收尾,不把玩家卡在中间态");
        assertEquals(Boolean.FALSE, settle.getSuccess());
    }

    /**
     * 边界:力竭**不**拦重掷。能走到 reroll 说明这一站已掷、未结算,重掷是把在途的检定做完,
     * 与 settle 同类 —— 而闸拦的是「再开一次」。在别处被打到力竭的玩家回来收尾这一站,
     * 不该被卡死在一个掷了骰子却收不了的中间态。
     *
     * <p>这条是写给将来的:谁要顺手给 reroll 也加个力竭闸,先看这里为什么没加。
     */
    @Test
    void exhaustionDoesNotBlockRerollingAnInFlightCheck() {
        template(check("easy", 1, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(6, 3)));
        diceQueue.add(4);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        // 掷完之后在别处被打到力竭(这里直接置位,等价于另一站判错扣到 0)。
        setVariable("sys.exhausted", 1);

        diceQueue.add(18);
        PlayCheckReceipt reroll = runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(Integer.valueOf(18), reroll.getKept(), "在途检定照常收尾");
        assertTrue(reroll.isRerolled());
    }

    @Test
    void restIsRejectedWhenNotExhausted() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.rest(MEMBER, TOPIC, NODE));

        assertEquals("尚未力竭，无需休整", error.getMessage());
        assertEquals(0, actions.size());
    }

    @Test
    void hpDisabledOnFailureDoesNotDeductNorExhaust() {
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":false,\"max\":10,\"init\":10},\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
                + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
                + "\"hard\":{\"dc\":16,\"damage\":3}},\"fumbleExtraDamage\":1,\"modCap\":6}");
        template(check("easy", 0, "成了", "砸了", true));
        session.setStateJson(stateWith(vitals(1, 3)));
        diceQueue.add(1);

        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals(Integer.valueOf(1), settle.getHp(), "hp.enabled=false 时不扣血");
        assertFalse(settle.isExhausted());
    }

    // ==================== 回执序列化扫描 ====================

    @Test
    void preSettlementReceiptsNeverCarrySettlementSecrets() throws Exception {
        diceQueue.add(2);
        runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);
        diceQueue.add(18);
        runtime.reroll(MEMBER, TOPIC, NODE, CHECK_ID);

        for (TopicRouteAction action : new ArrayList<TopicRouteAction>(actions)) {
            JsonNode receipt = json.readTree(action.getResponseJson());
            String raw = receipt.toString();
            assertFalse(raw.contains("successText"), raw);
            assertFalse(raw.contains("failText"), raw);
            assertFalse(raw.contains("successEffects"), raw);
            assertFalse(raw.contains("failEffects"), raw);
            assertFalse(raw.contains("成了"), raw);
            assertFalse(raw.contains("砸了"), raw);
            assertFalse(receipt.path("settled").asBoolean(true), raw);
            assertTrue(receipt.path("text").isNull(), raw);
        }

        runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);
        JsonNode settled = json.readTree(actions.get(2).getResponseJson());
        assertTrue(settled.path("settled").asBoolean());
        assertEquals("成了", settled.path("text").asText());
        assertEquals(1, settled.path("dice").size());
        assertFalse(settled.toString().contains("successEffects"));
        assertFalse(settled.toString().contains("failEffects"));
    }

    @Test
    void stateCasConflictIsReportedAsConflict() {
        diceQueue.add(9);
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        assertEquals("路线状态已更新，请刷新后重试", error.getMessage());
    }

    // ==================== B8 冻结:检定读建局快照 ====================

    /** 开局后改模板检定档位与主题数值规则:进行中的局仍按建局冻结值判定。 */
    @Test
    void frozenCheckAndJourneyRulesSurvivePostOpenEdits() {
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],"
                + "\"edges\":[],\"nodeJourney\":{\"1\":" + check("hard", 0, "旧成了", "旧砸了", true) + "},"
                + "\"journeyRules\":" + rules(10, 3, 1, 6, "tag.injured", true, true) + "}");
        // 开局后创作者改模板与主题规则:检定换 easy、主题停用检定、正文改新
        template(check("easy", 0, "新成了", "新砸了", true));
        topic.setJourneyRulesJson(rules(10, 3, 1, 6, "tag.injured", false, true));
        diceQueue.add(9);

        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals("hard", roll.getTier(), "档位读建局冻结快照,不读改后的 easy");
        assertEquals(Integer.valueOf(16), roll.getDc());
        assertEquals(Boolean.FALSE, roll.getSuccess(), "9 < 16,冻结档位说了算");

        PlayCheckReceipt settle = runtime.settle(MEMBER, TOPIC, NODE, CHECK_ID);

        assertEquals("旧砸了", settle.getText(), "结算正文也读冻结快照");
        assertEquals(Integer.valueOf(7), settle.getHp(), "hard.damage 3,不是改后档位的伤害");
    }

    // ==================== 测试脚手架 ====================

    /** 直接置本局某个状态变量(用于构造「在别处被打到力竭」这类前置)。 */
    private void setVariable(String key, int value) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(session.getStateJson());
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("variables")).put(key, value);
            session.setStateJson(json.writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TopicRouteSession newSession() {
        TopicRouteSession value = new TopicRouteSession();
        value.setId(SESSION); value.setActivityId(0L); value.setTopicId(TOPIC); value.setMemberId(MEMBER);
        value.setPlayInstanceId("default"); value.setConfigVersion(1L);
        value.setConfigSnapshotJson(topic.getPublishedRouteGraphJson());
        value.setStateJson(stateWith(vitals(10, 3)));
        value.setStatus(TopicRouteSession.STATUS_RUNNING); value.setSeed(12345L); value.setVersion(0);
        return value;
    }

    private Map<String, Object> vitals(int hp, int luck) {
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        variables.put("sys.hp", hp);
        variables.put("sys.luck", luck);
        variables.put("clue.ledger_page", 1);
        return variables;
    }

    private String stateWith(Map<String, Object> variables) {
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("status", "ACTIVE");
        state.put("currentNodeId", NODE);
        state.put("recommendedNodeId", NODE);
        state.put("variables", variables);
        state.put("tags", new ArrayList<String>());
        try {
            return json.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode state() {
        try {
            return json.readTree(session.getStateJson());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubCostTagLabel(String stateKey, String label) {
        ThemeStateKey row = new ThemeStateKey();
        row.setStateKey(stateKey);
        row.setLabel(label);
        when(themeStateKeyMapper.selectByTopicId(TOPIC)).thenReturn(Collections.singletonList(row));
    }

    private void template(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setAdvancedConfigJson(advancedConfigJson);
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
    }

    private static String check(String tier, int modValue, String successText, String failText,
                                boolean enabled) {
        return "{\"check\":{\"enabled\":" + enabled + ",\"checkId\":\"" + CHECK_ID + "\",\"skill\":\"察言\","
                + "\"tier\":\"" + tier + "\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"value\":"
                + modValue + ",\"label\":\"手握签收联 071\"}],"
                + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"counter.wins\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"INC\",\"var\":\"counter.fails\",\"value\":1}],"
                + "\"successText\":\"" + successText + "\",\"failText\":\"" + failText + "\","
                + "\"failCostTag\":\"tag.hurried\"}}";
    }

    private static String rules(int hpInit, int luckInit, int fumbleExtra, int modCap, String exhaustTag,
                                boolean checkEnabled, boolean luckEnabled) {
        return "{\"schemaVersion\":1,\"checkEnabled\":" + checkEnabled + ","
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":" + hpInit + "},"
                + "\"luck\":{\"enabled\":" + luckEnabled + ",\"max\":3,\"init\":" + luckInit + "},"
                + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
                + "\"hard\":{\"dc\":16,\"damage\":3}},"
                + "\"recovery\":{\"exhaustRestHp\":5,\"exhaustTag\":\"" + exhaustTag + "\"},"
                + "\"fumbleExtraDamage\":" + fumbleExtra + ",\"modCap\":" + modCap + "}";
    }
}
