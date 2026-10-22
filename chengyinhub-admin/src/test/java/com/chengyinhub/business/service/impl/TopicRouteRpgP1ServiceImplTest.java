package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
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
import com.chengyinhub.business.service.support.rpg.TopicEnvironmentService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F6 RPG P1 运行期:魔鬼交易 / 动量 / 环境键 / 状态栏。
 * 夹具与 {@link TopicRouteCheckServiceImplTest} 同形(固定骰点、actionId 重放、CAS 计数),
 * 每个用例都对照「没开 rpg 的老主题」确认零行为变化。
 */
class TopicRouteRpgP1ServiceImplTest {
    private static final long MEMBER = 7L, TOPIC = 10L, NODE = 1L, SESSION = 99L;
    private static final String CHECK_ID = "ask_arong";
    /** 2026-10-22 14:00 北京时间(周四,下午,秋)。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);

    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final IShopDayProofService proofService = mock(IShopDayProofService.class);
    private final IPlayEncounterService encounterService = mock(IPlayEncounterService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayDeque<Integer> diceQueue = new ArrayDeque<Integer>();
    private final List<TopicRouteAction> actions = new ArrayList<TopicRouteAction>();
    private final List<String> weatherAsks = new ArrayList<String>();
    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession session;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        runtime = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper, templateMapper,
                mock(PlayerNodeProgressMapper.class), mock(CmsActivityMapper.class), registrationMapper,
                mock(PlayerActivityRoleMapper.class), proofService, () -> 12345L, () -> diceQueue.removeFirst(),
                Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", mock(ThemeStateKeyMapper.class));
        ReflectionTestUtils.setField(runtime, "playEncounterService", encounterService);
        ReflectionTestUtils.setField(runtime, "topicEnvironmentService", new TopicEnvironmentService(city -> {
            weatherAsks.add(city);
            return "rain";
        }, CLOCK));
        when(encounterService.resolve(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(null);
        topic = new CmsTopic();
        topic.setId(TOPIC); topic.setRouteMode("BRANCH_GRAPH"); topic.setPublishedConfigVersion(1L);
        topic.setAddressName("上海市徐汇区");
        topic.setPublishedRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2],\"edges\":[]}");
        topic.setJourneyRulesJson(rules(null));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE); node.setTopicId(TOPIC); node.setStatus(1L); node.setTemplateId(101L);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        template(check(null, null));
        CmsRegistration registration = new CmsRegistration();
        registration.setId(9000001L); registration.setMemberId(MEMBER); registration.setOwnerType(1L); registration.setOwnerId(TOPIC);
        registration.setPaymentStatus(2); registration.setRegistrationStatus(2); registration.setPurchaseKind(2);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(9000001L)).thenReturn(registration);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.singletonList(registration));
        when(proofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
        session = new TopicRouteSession();
        session.setId(SESSION); session.setActivityId(0L); session.setTopicId(TOPIC); session.setMemberId(MEMBER);
        session.setPlayInstanceId("default"); session.setConfigVersion(1L);
        session.setConfigSnapshotJson(topic.getPublishedRouteGraphJson());
        session.setStateJson("{\"status\":\"ACTIVE\",\"currentNodeId\":1,\"recommendedNodeId\":1,"
                + "\"variables\":{\"sys.hp\":10,\"sys.luck\":3,\"clue.ledger_page\":1},\"tags\":[]}");
        session.setStatus(TopicRouteSession.STATUS_RUNNING); session.setSeed(12345L); session.setVersion(0);
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenAnswer(inv -> Collections.singletonList(session));
        when(routeMapper.selectCurrentByOwner(anyLong(), org.mockito.ArgumentMatchers.eq(TOPIC), org.mockito.ArgumentMatchers.eq(MEMBER)))
                .thenAnswer(inv -> session);
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction action = inv.getArgument(0);
            action.setId(500L + actions.size());
            actions.add(action);
            return 1;
        });
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> actions.stream()
                .filter(a -> inv.getArgument(2).equals(a.getActionId())).findFirst().orElse(null));
        when(routeMapper.updateStateCas(any(TopicRouteSession.class))).thenReturn(1);
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenAnswer(inv -> {
            for (TopicRouteAction action : actions) {
                if (inv.getArgument(0).equals(action.getId())) { action.setResponseJson(inv.getArgument(1)); return 1; }
            }
            return 0;
        });
    }

    // ==================== 老主题零变化 ====================

    @Test
    void legacyTopicKeepsReceiptAndStateShapeAndRefusesP1Actions() throws Exception {
        diceQueue.add(9);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals(11, roll.getTotal().intValue());
        assertFalse(roll.getBreakdown().containsKey("bargain"));
        assertNull(roll.getBargain());
        assertNull(roll.getMomentum());
        PlayCheckReceipt settled = runtime.settle(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertNull(settled.getMomentum());
        JsonNode state = json.readTree(session.getStateJson());
        assertFalse(state.has("environment"), "老主题不写环境快照");
        assertFalse(state.path("variables").has("sys.momentum"));
        assertTrue(weatherAsks.isEmpty(), "没开环境键就不问天气源");
        ServiceException bargain = assertThrows(ServiceException.class,
                () -> runtime.bargain(MEMBER, TOPIC, 0L, NODE, CHECK_ID));
        assertEquals("RPG_BARGAIN_DISABLED", bargain.getDetailMessage());
        assertEquals(6, roll.getBreakdown().size(), "明细字段集合与 F2 一致:die/skill/situational/total/dc/natAuto");
    }

    // ==================== T09 魔鬼交易 ====================

    @Test
    void acceptedBargainAddsBonusOutsideModCapAndPaysCostExactlyOnce() throws Exception {
        topic.setJourneyRulesJson(rules("{\"bargain\":{\"enabled\":true}}"));
        template(check("medium", "{\"label\":\"惊动了巡夜人\",\"bonus\":2,"
                + "\"effects\":[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4}]}"));
        PlayCheckReceipt deal = runtime.bargain(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals(Boolean.TRUE, deal.getBargain().get("accepted"));
        assertEquals(1, json.readTree(session.getStateJson()).path("variables").path("counter.alarm").asInt());
        // 重放:同一回执,代价不二次付。
        runtime.bargain(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals(1, json.readTree(session.getStateJson()).path("variables").path("counter.alarm").asInt());
        diceQueue.add(9);   // 9 + 情境修正 2 + 交易 2 = 13 = medium dc
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals(13, roll.getTotal().intValue());
        assertTrue(roll.getSuccess());
        assertEquals(2, ((Number) roll.getBreakdown().get("bargain")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) roll.getBreakdown().get("situational")).get("applied")).intValue(),
                "交易加值不占 modCap(=2)");
    }

    @Test
    void bargainAfterRollIsRefused() {
        topic.setJourneyRulesJson(rules("{\"bargain\":{\"enabled\":true}}"));
        template(check("medium", "{\"label\":\"惊动了巡夜人\",\"bonus\":2,"
                + "\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.noticed\"}]}"));
        diceQueue.add(9);
        runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        ServiceException refused = assertThrows(ServiceException.class,
                () -> runtime.bargain(MEMBER, TOPIC, 0L, NODE, CHECK_ID));
        assertEquals("RPG_BARGAIN_AFTER_ROLL", refused.getDetailMessage());
    }

    /** RV4 S533-1:前置条件没满足时接受交易 —— 拒绝,且代价一分不扣、不 CAS、不登记动作。 */
    @Test
    void bargainWithUnmetRequiresIsRefusedBeforePayingCost() throws Exception {
        topic.setJourneyRulesJson(rules("{\"bargain\":{\"enabled\":true}}"));
        String withCheck = check("medium", "{\"label\":\"惊动了巡夜人\",\"bonus\":2,"
                + "\"effects\":[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4},"
                + "{\"op\":\"ADD_TAG\",\"value\":\"tag.noticed\"}]}");
        template("{\"requires\":[{\"var\":\"clue.missing\",\"op\":\"GTE\",\"value\":1}],"
                + withCheck.substring(1));
        String before = session.getStateJson();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> runtime.bargain(MEMBER, TOPIC, 0L, NODE, CHECK_ID));
        assertEquals("本站前置条件未满足", refused.getMessage());
        assertEquals(before, session.getStateJson(), "代价没有写进本局");
        assertTrue(actions.isEmpty(), "不登记交易动作");
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).updateStateCas(any(TopicRouteSession.class));
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).insertAction(any(TopicRouteAction.class));
    }

    /** RV4 S533-3:节点投影是否下发报价,读的是本局规则(与 bargain() 同源)。 */
    @Test
    void bargainOfferedFollowsSessionRules() {
        assertFalse(runtime.bargainOffered(MEMBER, TOPIC, 0L), "没开 rpg.bargain 不下发报价");
        topic.setJourneyRulesJson(rules("{\"bargain\":{\"enabled\":true}}"));
        assertTrue(runtime.bargainOffered(MEMBER, TOPIC, 0L));
        assertFalse(runtime.bargainOffered(MEMBER + 1, TOPIC, 0L), "不是本人的局不算");
    }

    @Test
    void bargainSwitchOnButCheckWithoutOfferIsRefused() {
        topic.setJourneyRulesJson(rules("{\"bargain\":{\"enabled\":true}}"));
        ServiceException refused = assertThrows(ServiceException.class,
                () -> runtime.bargain(MEMBER, TOPIC, 0L, NODE, CHECK_ID));
        assertEquals("RPG_BARGAIN_NOT_OFFERED", refused.getDetailMessage());
    }

    // ==================== T10 动量 ====================

    @Test
    void momentumClosesTheGapOnceThenSettleUsesThePushedReceipt() throws Exception {
        topic.setJourneyRulesJson(rules(MOMENTUM));
        template(check("medium", null));
        setVariable("sys.momentum", 2);
        diceQueue.add(10);  // 10 + 2 = 12 < 13
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertFalse(roll.getSuccess());
        PlayCheckReceipt push = runtime.spendMomentum(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertTrue(push.getSuccess());
        assertEquals(13, push.getTotal().intValue());
        assertEquals(1, ((Number) push.getMomentum().get("spent")).intValue());
        assertEquals(1, ((Number) push.getMomentum().get("value")).intValue());
        // 重放不二次扣
        runtime.spendMomentum(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals(1, json.readTree(session.getStateJson()).path("variables").path("sys.momentum").asInt());
        ServiceException reroll = assertThrows(ServiceException.class,
                () -> runtime.reroll(MEMBER, TOPIC, 0L, NODE, CHECK_ID));
        assertEquals("RPG_MOMENTUM_USED", reroll.getDetailMessage());
        PlayCheckReceipt settled = runtime.settle(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertTrue(settled.getSuccess());
        JsonNode variables = json.readTree(session.getStateJson()).path("variables");
        assertEquals(1, variables.path("sys.passed." + NODE).asInt());
        assertEquals(1, variables.path("counter.wins").asInt());
        assertEquals(10, variables.path("sys.hp").asInt(), "补成成功就不扣血");
    }

    @Test
    void failedSettleBuildsMomentumUpToMax() throws Exception {
        topic.setJourneyRulesJson(rules(MOMENTUM));
        template(check("medium", null));
        setVariable("sys.momentum", 4);
        diceQueue.add(5);
        runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        PlayCheckReceipt settled = runtime.settle(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertFalse(settled.getSuccess());
        assertEquals(1, ((Number) settled.getMomentum().get("gained")).intValue(), "gainOnFail=2 但封顶 max=5");
        assertEquals(5, json.readTree(session.getStateJson()).path("variables").path("sys.momentum").asInt());
    }

    @Test
    void momentumRefusesFumbleTooLargeGapAndShortage() {
        topic.setJourneyRulesJson(rules(MOMENTUM));
        template(check("medium", null));
        setVariable("sys.momentum", 1);
        diceQueue.add(1);
        runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals("RPG_MOMENTUM_FUMBLE", assertThrows(ServiceException.class,
                () -> runtime.spendMomentum(MEMBER, TOPIC, 0L, NODE, CHECK_ID)).getDetailMessage());
        actions.clear();
        diceQueue.add(5);   // 7 vs 13:差 6 > maxSpend 3
        runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals("RPG_MOMENTUM_GAP_TOO_LARGE", assertThrows(ServiceException.class,
                () -> runtime.spendMomentum(MEMBER, TOPIC, 0L, NODE, CHECK_ID)).getDetailMessage());
        actions.clear();
        diceQueue.add(9);   // 11 vs 13:差 2 > 现有 1
        runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertEquals("RPG_MOMENTUM_INSUFFICIENT", assertThrows(ServiceException.class,
                () -> runtime.spendMomentum(MEMBER, TOPIC, 0L, NODE, CHECK_ID)).getDetailMessage());
    }

    // ==================== T06/T07 环境键 ====================

    @Test
    void environmentSnapshotDrivesEnvInFromTopicCityOnly() throws Exception {
        topic.setJourneyRulesJson(rules("{\"environment\":{\"enabled\":true}}"));
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"medium\","
                + "\"advantageIf\":[{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\",\"storm\"]}]}}");
        diceQueue.add(4); diceQueue.add(15);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertTrue(roll.isAdvantage());
        assertEquals(15, roll.getKept().intValue());
        assertEquals(Collections.singletonList("上海市徐汇区"), weatherAsks, "只按主题城市问,一次缓存");
        JsonNode environment = json.readTree(session.getStateJson()).path("environment");
        assertEquals("rain", environment.path("weather").asText());
        assertEquals("afternoon", environment.path("daypart").asText());
        assertEquals("weekday", environment.path("weekpart").asText());
        assertEquals("autumn", environment.path("season").asText());
    }

    @Test
    void weatherProviderFailureFailsOpenAsUnknown() {
        ReflectionTestUtils.setField(runtime, "topicEnvironmentService", new TopicEnvironmentService(city -> {
            throw new IllegalStateException("provider down");
        }, CLOCK));
        topic.setJourneyRulesJson(rules("{\"environment\":{\"enabled\":true,\"keys\":[\"weather\"]}}"));
        template("{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"tier\":\"medium\","
                + "\"advantageIf\":[{\"op\":\"ENV_IN\",\"key\":\"weather\",\"values\":[\"rain\"]}]}}");
        diceQueue.add(12);
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, 0L, NODE, CHECK_ID);
        assertFalse(roll.isAdvantage(), "天气源挂了:不报错、不给优势,按 unknown 判");
    }

    // ==================== T13 状态栏 ====================

    @Test
    void statusBarShowsMomentumClocksAspectsAndEnvironment() throws Exception {
        topic.setJourneyRulesJson(rules("{\"environment\":{\"enabled\":true},"
                + "\"clocks\":[{\"var\":\"counter.alarm\",\"label\":\"警报\",\"segments\":4,\"kind\":\"danger\"},"
                + "{\"var\":\"counter.secret\",\"label\":\"暗线\",\"segments\":6,\"visible\":false}],"
                + "\"momentum\":{\"enabled\":true,\"max\":5,\"init\":2},"
                + "\"aspects\":[{\"key\":\"night_owl\",\"label\":\"夜猫子\",\"kind\":\"facet\"},"
                + "{\"key\":\"soaked\",\"label\":\"浑身湿透\"},{\"key\":\"lucky\",\"label\":\"走运\"}]}"));
        setVariable("counter.alarm", 3);
        setVariable("sys.momentum", 2);
        ObjectNode root = (ObjectNode) json.readTree(session.getStateJson());
        root.putArray("statusConditions").add("night_owl").add("soaked");
        session.setStateJson(json.writeValueAsString(root));
        Map<String, Object> status = runtime.rpgStatus(MEMBER, TOPIC, 0L);
        assertEquals(Boolean.TRUE, status.get("running"));
        JsonNode rpg = json.valueToTree(status.get("rpg"));
        assertEquals(2, rpg.path("momentum").path("value").asInt());
        assertEquals(1, rpg.path("clocks").size(), "visible=false 的钟不下发");
        assertEquals(3, rpg.path("clocks").get(0).path("value").asInt());
        assertEquals("night_owl", rpg.path("facets").get(0).path("key").asText());
        assertEquals(1, rpg.path("aspects").size());
        assertEquals("rain", rpg.path("environment").path("weather").asText());
        assertFalse(json.readTree(session.getStateJson()).has("environment"), "状态栏只读,环境只在副本上现算");
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertEquals(Boolean.FALSE, runtime.rpgStatus(MEMBER, TOPIC, 0L).get("running"));
    }

    // ==================== 夹具 ====================

    private static final String MOMENTUM = "{\"momentum\":{\"enabled\":true,\"max\":5,\"init\":2,\"gainOnFail\":2,"
            + "\"gainOnSuccess\":0,\"maxSpend\":3}}";

    private void setVariable(String key, int value) {
        try {
            ObjectNode root = (ObjectNode) json.readTree(session.getStateJson());
            ((ObjectNode) root.get("variables")).put(key, value);
            session.setStateJson(json.writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void template(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setAdvancedConfigJson(advancedConfigJson);
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
    }

    private static String check(String tier, String bargain) {
        return "{\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"skill\":\"察言\","
                + "\"tier\":\"" + (tier == null ? "easy" : tier) + "\","
                + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"value\":2,\"label\":\"手握签收联\"}],"
                + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"counter.wins\",\"value\":1}],"
                + "\"failEffects\":[{\"op\":\"INC\",\"var\":\"counter.fails\",\"value\":1}],"
                + "\"successText\":\"成了\",\"failText\":\"砸了\""
                + (bargain == null ? "" : ",\"bargain\":" + bargain) + "}}";
    }

    private static String rules(String rpg) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":10},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
                + "\"modCap\":2" + (rpg == null ? "" : ",\"rpg\":" + rpg) + "}";
    }
}
