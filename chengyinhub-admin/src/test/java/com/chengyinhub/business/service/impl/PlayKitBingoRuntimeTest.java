package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayBingoCard;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayBingoCardMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.business.service.support.BingoCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code playKit.bingo} 段的下发契约 —— 主题级九宫格在玩家那一屏到底收不收得到。
 *
 * <p>修之前的状态:商家能配(主题的 complete_rule_json 里那段 bingo)、服务端能记账
 * (节点完成时 ApiPlayProgressController#refreshBingo 刷 filled_mask),但
 * {@code playKitRuntime()} 压根不发这一段 —— 玩家侧 cy-playkit-bingo 永远收不到数据,
 * 整个玩法断在运行期。
 *
 * <p>★ 铁律(最后三条用例盯住):bingo 是**主题级进度呈现**,它既不判定通关,
 * 也不能把任何节点判成通关;它读挂了也不许拖累整个玩法视图。
 *
 * <p>JSON 形状与前端钉死,键名一字不差:
 * {@code title / labels / cellSpecs[{t,how}] / filledPositions / lineReward / fullReward}。
 */
class PlayKitBingoRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;
    private static final Date NOW = new Date(1_775_000_000_000L);

    /** 主题的九宫格配置:开了、配了两格的名字、给了两档奖的文案。第 4 格位序是行优先的中间那格。 */
    private static final String BINGO_RULE = "{\"bingo\":{\"enabled\":true,\"title\":\"这周的九宫格\","
            + "\"lineReward\":\"第二杯半价\",\"fullReward\":\"一杯手冲免单\",\"cells\":["
            + "{\"label\":\"老张咖啡\",\"feedbackText\":\"到店送你一张挂耳\",\"couponId\":88},"
            + "{\"label\":\"\"},{\"label\":\"\"},{\"label\":\"\"},"
            + "{\"label\":\"巷口书店\"},"
            + "{\"label\":\"\"},{\"label\":\"\"},{\"label\":\"\"},{\"label\":\"\"}]}}";

    /** 扫码:这一局没扫完就不该通得了关 —— 拿它当 readyForBase 的探针。 */
    private static final String SCAN_CONFIG = "{\"schemaVersion\":1,\"scan\":{\"enabled\":true,"
            + "\"kind\":\"TEXT\",\"reply\":\"欢迎来到这家店\",\"xp\":10}}";

    private GameRuntimeSessionMapper sessionMapper;
    private PlayBingoCardMapper cardMapper;
    private CmsTopicMapper topicMapper;
    private AdvancedGameRuntimeServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        cardMapper = mock(PlayBingoCardMapper.class);
        topicMapper = mock(CmsTopicMapper.class);

        PlayBingoServiceImpl bingo = new PlayBingoServiceImpl();
        ReflectionTestUtils.setField(bingo, "cardMapper", cardMapper);
        ReflectionTestUtils.setField(bingo, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(bingo, "progressMapper", mock(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(bingo, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(bingo, "couponHistoryService", mock(ISmsCouponHistoryService.class));

        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(SCAN_CONFIG);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);

        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", mock(PlayTeamMapper.class));
        ReflectionTestUtils.setField(service, "attemptMapper", mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "playBingoService", bingo);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> NOW);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        // 默认这一局:扫码还没做,所以 readyForBase 恒为 false —— 用它验九宫格不许掺和判定
        session(SCAN_CONFIG, "{}");
    }

    // ==================== 形状 ====================

    @Test
    void 主题开了九宫格_playKit就带bingo段且形状与前端钉死() {
        givenTopicRule(BINGO_RULE);
        givenCard(card("[]", 0b000000111));

        Map<String, Object> bingo = kitSection(state(), "bingo");

        assertEquals(new LinkedHashSet<>(Arrays.asList("title", "labels", "cellSpecs",
                "filledPositions", "lineReward", "fullReward")), bingo.keySet(),
                "键名与前端钉死,多一个少一个都会让那一屏拿不到东西:" + bingo.keySet());
        assertEquals("这周的九宫格", bingo.get("title"));
        assertEquals("第二杯半价", bingo.get("lineReward"));
        assertEquals("一杯手冲免单", bingo.get("fullReward"));
        assertEquals(Arrays.asList(0, 1, 2), bingo.get("filledPositions"),
                "位序行优先 0..8,与 BingoCard / cy-playkit-bingo 同口径");
    }

    @Test
    void 九格名称走商家配的_没配的那几格回落平台内置文案() {
        givenTopicRule(BINGO_RULE);
        givenCard(card(null, 0));

        List<?> labels = (List<?>) kitSection(state(), "bingo").get("labels");
        assertEquals(9, labels.size(), "少一格,玩家看见的就是一个残缺的棋盘");
        assertEquals("老张咖啡", labels.get(0));
        assertEquals("巷口书店", labels.get(4));
        // 第 1、5 格商家没起名 → 用平台内置的那句,不能留空格子
        assertEquals("完成 3 个点", labels.get(1));
        assertEquals("连续两天来", labels.get(5));
    }

    @Test
    void 每格都带how_那是点亮条件_商家改名也换不掉() {
        givenTopicRule(BINGO_RULE);
        givenCard(card(null, 0));

        List<Map<String, Object>> specs = (List<Map<String, Object>>)
                kitSection(state(), "bingo").get("cellSpecs");
        assertEquals(9, specs.size());
        assertEquals(new LinkedHashSet<>(Arrays.asList("t", "how")), specs.get(0).keySet());
        assertEquals("老张咖啡", specs.get(0).get("t"));
        assertEquals("完成第 1 个点", specs.get(0).get("how"));
        assertEquals("巷口书店", specs.get(4).get("t"));
        assertEquals("两天各来一次", specs.get(4).get("how"));
    }

    @Test
    void 格子以开卡时冻结的快照为准_商家后来改的名不覆盖已经开出去的卡() {
        givenTopicRule(BINGO_RULE);
        // 卡上第 4 格冻的是旧名,主题配置现在写的是「巷口书店」
        givenCard(card("[{\"pos\":0,\"code\":\"NODE_1\",\"target\":1,\"label\":\"老张咖啡\"},"
                + "{\"pos\":4,\"code\":\"DAYS_2\",\"target\":2,\"label\":\"以前那家书店\"}]", 0));

        List<?> labels = (List<?>) kitSection(state(), "bingo").get("labels");
        assertEquals("以前那家书店", labels.get(4), "玩到一半换格子意思,玩家只会觉得系统在耍他");
        assertEquals("老张咖啡", labels.get(0));
    }

    @Test
    void 负控_还没开卡也要回九格_不能因为没记录就把这一屏吞掉() {
        givenTopicRule(BINGO_RULE);
        when(cardMapper.selectCard(MEMBER_ID, TOPIC_ID)).thenReturn(null);

        Map<String, Object> bingo = kitSection(state(), "bingo");
        assertEquals(9, ((List<?>) bingo.get("labels")).size());
        assertEquals(Collections.emptyList(), bingo.get("filledPositions"));
    }

    @Test
    void 负控_快照列读坏了不许炸_九格照样出() {
        givenTopicRule(BINGO_RULE);
        givenCard(card("{这不是合法 JSON", 0b1));

        Map<String, Object> bingo = kitSection(state(), "bingo");
        assertEquals(9, ((List<?>) bingo.get("labels")).size());
        assertEquals(Arrays.asList(0), bingo.get("filledPositions"));
    }

    // ==================== 只有 filled_mask 能点亮 ====================

    @Test
    void 负控_点亮只看filledMask_rewardMask里的奖位一概不算() {
        givenTopicRule(BINGO_RULE);
        // 奖位全亮(第一档 + 连线档 + 九格每格的奖位),格子却只填了两格
        int rewardMask = BingoCard.REWARD_FIRST | BingoCard.REWARD_LINE;
        for (int position = 0; position < BingoCard.SIZE; position++) {
            rewardMask |= BingoCard.cellRewardBit(position);
        }
        PlayBingoCard card = card(null, 0b000010001);
        card.setRewardMask(rewardMask);
        givenCard(card);

        Map<String, Object> bingo = kitSection(state(), "bingo");
        assertEquals(Arrays.asList(0, 4), bingo.get("filledPositions"),
                "reward_mask 里每一位都只是「这一档奖记过账」,不是格子亮了");
        assertFalse(String.valueOf(bingo).contains(String.valueOf(rewardMask)),
                "奖位掩码不该整块下发:" + bingo);
    }

    // ==================== 没开就没有这一段 ====================

    @Test
    void 主题没开九宫格_playKit里就不该有bingo段() {
        givenTopicRule("{\"nodeCompletion\":{\"mode\":\"ALL\"}}");
        givenCard(card(null, 0b111));

        Map<String, Object> playKit = playKit(state());
        assertNotNull(playKit.get("scan"), "扫码段还在,playKit 不该整块没了");
        assertNull(playKit.get("bingo"));
    }

    @Test
    void 主题配置读不动_按没开处理_不许拖累玩法视图() {
        givenTopicRule("坏掉的 JSON");
        givenCard(card(null, 0));

        Map<String, Object> playKit = playKit(state());
        assertNull(playKit.get("bingo"));
        assertNotNull(playKit.get("scan"), "扫码段不受九宫格配置读不动的影响");
    }

    // ==================== ★ 铁律:不掺和通关判定 ====================

    @Test
    void 铁律_九格全亮也不把节点判成通关() {
        givenTopicRule(BINGO_RULE);
        givenCard(card(null, BingoCard.FULL_MASK));   // 九格全亮、三档奖也都记过

        Map<String, Object> view = state();
        assertNotNull(kitSection(view, "bingo"), "这一段本身要发得出去");
        assertEquals(Boolean.FALSE, view.get("readyForBase"),
                "九格是主题级进度呈现,亮满不等于这一站做完了");
    }

    @Test
    void 铁律_一格没亮也不许把已经做完的节点判成没通关() {
        givenTopicRule(BINGO_RULE);
        givenCard(card(null, 0));
        session(SCAN_CONFIG, "{\"scan\":{\"scanned\":true}}");

        Map<String, Object> view = state();
        assertEquals(Boolean.TRUE, view.get("readyForBase"));
        assertNotNull(kitSection(view, "bingo"));
    }

    @Test
    void 铁律_九宫格整段读挂了玩法视图照常返回() {
        givenTopicRule(BINGO_RULE);
        when(cardMapper.selectCard(MEMBER_ID, TOPIC_ID)).thenThrow(new RuntimeException("库连接断了"));

        Map<String, Object> view = state();
        Map<String, Object> playKit = playKit(view);
        assertNull(playKit.get("bingo"), "读挂了就是不下发这一段,不能把整屏玩法带走");
        assertNotNull(playKit.get("scan"));
        assertEquals(Boolean.FALSE, view.get("readyForBase"));
    }

    // ==================== 脚手架 ====================

    private void givenTopicRule(String completeRuleJson) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setCompleteRuleJson(completeRuleJson);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
    }

    private void givenCard(PlayBingoCard card) {
        when(cardMapper.selectCard(MEMBER_ID, TOPIC_ID)).thenReturn(card);
    }

    private PlayBingoCard card(String cellsJson, int filledMask) {
        PlayBingoCard card = new PlayBingoCard();
        card.setMemberId(MEMBER_ID);
        card.setTopicId(TOPIC_ID);
        card.setCellsJson(cellsJson);
        card.setFilledMask(filledMask);
        card.setRewardMask(BingoCard.REWARD_FIRST);
        return card;
    }

    private GameRuntimeSession session(String config, String state) {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID);
        session.setActivityId(0L);
        session.setTopicId(TOPIC_ID);
        session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(config);
        session.setStateJson(state);
        session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(1);
        session.setScore(0);
        session.setStartedAt(NOW);
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        return session;
    }

    private Map<String, Object> state() {
        return service.state(MEMBER_ID, SESSION_ID);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> playKit(Map<String, Object> view) {
        Map<String, Object> playKit = (Map<String, Object>) view.get("playKit");
        assertNotNull(playKit, "playKit 整块都没有 —— 前端那一屏永远不会出现");
        return playKit;
    }

    private Map<String, Object> kitSection(Map<String, Object> view, String name) {
        Object section = playKit(view).get(name);
        assertTrue(section instanceof Map, name + " 没进 playKit:" + playKit(view).keySet());
        return (Map<String, Object>) section;
    }
}
