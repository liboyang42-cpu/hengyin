package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.PlayerNodeProgress;
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
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R9-ACTIONS 旅程运行期动作:答错扣血 / 站末恢复 / 阶段休整 / 问问题 / 回应开场通话。
 * 全部走 topic_route_session.state_json 与 topic_route_action 幂等,不新建表。
 */
class JourneyRuntimeActionsTest {
    private static final long MEMBER = 7L;
    private static final long TOPIC = 10L;

    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
    private final CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final PlayerActivityRoleMapper roleMapper = mock(PlayerActivityRoleMapper.class);
    private final CmsTopicChapterMapper cmsTopicChapterMapper = mock(CmsTopicChapterMapper.class);
    private final IShopDayProofService shopDayProofService = mock(IShopDayProofService.class);
    private final IPlayEncounterService encounterService = mock(IPlayEncounterService.class);
    private final ObjectMapper json = new ObjectMapper();

    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession[] stored;
    private List<TopicRouteAction> actions;
    private List<CmsTopicNode> liveNodes;

    @BeforeEach
    void setUp() {
        runtime = new TopicRouteRuntimeServiceImpl(routeMapper, topicMapper, nodeMapper, templateMapper,
                progressMapper, activityMapper, registrationMapper, roleMapper, shopDayProofService,
                () -> 12345L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime, "playEncounterService", encounterService);
        when(encounterService.resolve(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", cmsTopicChapterMapper);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setRouteMode("LINEAR");
        topic.setPublishedConfigVersion(1L);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsRegistration pass = new CmsRegistration();
        pass.setExpiresAt(null);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));
        when(routeMapper.selectByOwner(0L, TOPIC, MEMBER, "default")).thenAnswer(inv -> stored[0]);
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenAnswer(inv -> java.util.Collections.singletonList(stored[0]));
        when(routeMapper.insertSession(any(TopicRouteSession.class))).thenAnswer(inv -> {
            TopicRouteSession value = inv.getArgument(0);
            value.setId(99L);
            stored[0] = value;
            return 1;
        });
        stored = new TopicRouteSession[1];
        actions = new ArrayList<>();
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
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.emptyList());
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenAnswer(inv -> liveNodes);
        when(shopDayProofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
    }

    // ==================== 1. 答错扣血 ====================

    @Test
    void firstMistakeIsWaivedAndSecondDeductsOneHp() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);

        JsonNode state = state();
        assertEquals(1, state.path("variables").path("sys.mistakes.1").asInt());
        assertEquals(5, state.path("variables").path("sys.hp").asInt(), "每个节点第一次判错免罚");
        assertEquals(0, state.path("variables").path("sys.damage.1").asInt(), "免罚那次不算扣过血");
        assertTrue(actionIds().contains("mistake:1:1"));

        runtime.recordMistake(MEMBER, TOPIC, 1L);

        state = state();
        assertEquals(2, state.path("variables").path("sys.mistakes.1").asInt());
        assertEquals(4, state.path("variables").path("sys.hp").asInt(), "第二次判错扣 1 血");
        assertEquals(1, state.path("variables").path("sys.damage.1").asInt());
        assertTrue(actionIds().contains("mistake:1:2"));
    }

    @Test
    void mistakesFloorAtZeroAndMarkExhausted() throws Exception {
        useJourneyTopic(rules(1, 10), node(1L, 1L, null));
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);

        JsonNode state = state();
        assertEquals(3, state.path("variables").path("sys.mistakes.1").asInt());
        assertEquals(0, state.path("variables").path("sys.hp").asInt(), "血量下限 0");
        assertEquals(1, state.path("variables").path("sys.exhausted").asInt(), "到 0 写力竭");
    }

    @Test
    void replayedMistakeActionDoesNotCountTwice() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        runtime.state(MEMBER, TOPIC, 0L);
        TopicRouteAction existing = new TopicRouteAction();
        existing.setId(1L);
        existing.setSessionId(99L);
        existing.setMemberId(MEMBER);
        existing.setNodeId(1L);
        existing.setActionId("mistake:1:1");
        existing.setReceiptId("mistake:1:1");
        existing.setOutcomeCode("MISTAKE");
        existing.setResponseJson("{\"mistakes\":1}");
        actions.add(existing);
        String stateBefore = stored[0].getStateJson();

        runtime.recordMistake(MEMBER, TOPIC, 1L);

        assertEquals(stateBefore, stored[0].getStateJson(), "同 actionId 重放不改状态");
        assertEquals(1, actions.size());
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    /**
     * R22-SEC S11:recordMistake 只认本主题节点(与 recordChoice 一致),外来 nodeId 零变化。
     *
     * <p>负控:去掉 node 归属校验,本用例立刻红(会往本局写 sys.mistakes.2)。
     */
    @Test
    void mistakeRejectsForeignNode() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        CmsTopicNode foreign = node(2L, 1L, null);
        foreign.setTopicId(999L);
        when(nodeMapper.selectCmsTopicNodeById(2L)).thenReturn(foreign);
        runtime.state(MEMBER, TOPIC, 0L);
        int actionsBefore = actions.size();
        String stateBefore = stored[0].getStateJson();

        runtime.recordMistake(MEMBER, TOPIC, 2L);

        assertEquals(actionsBefore, actions.size(), "外来节点不得登记判错动作");
        assertEquals(stateBefore, stored[0].getStateJson(), "外来节点不得改本局状态");
        assertFalse(state().path("variables").has("sys.mistakes.2"));
    }

    @Test
    void nonJourneyTopicMistakeChangesNothing() {
        topicMapper.selectCmsTopicById(TOPIC).setJourneyRulesJson(null);
        liveNodes = Arrays.asList(node(1L, 1L, null));

        runtime.recordMistake(MEMBER, TOPIC, 1L);

        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    @Test
    void hpDisabledTopicMistakeChangesNothing() {
        useJourneyTopic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":false,\"max\":10,\"init\":5}}", node(1L, 1L, null));
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);

        verify(routeMapper, never()).insertAction(any(TopicRouteAction.class));
        verify(routeMapper, never()).updateStateCas(any(TopicRouteSession.class));
    }

    // ==================== 2. 阶段 2:mistakeTier 并入主题难度表 ====================

    /**
     * 不配 mistakeTier 的老模板:扣血数字与并入前逐字相同(第二次扣 1,即 easy 默认档)。
     *
     * <p>这条守的是「零行为变化」承诺 —— 阶段 2 上线不许动存量主题的体感数字。
     */
    @Test
    void mistakeWithoutTierDeductsLegacyOne() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);

        assertEquals(4, state().path("variables").path("sys.hp").asInt(), "没配档位 = easy 默认扣 1");
        assertEquals("easy", mistakeReceipt(2).path("tier").asText(), "缺省回执口径是 easy");
    }

    /**
     * 玩法块配 mistakeTier=hard:在主题默认难度表下,判错按 hard 档 damage=3 扣。
     *
     * <p>负控:把档位读死成 easy,本用例立刻红(只扣 1)。
     */
    @Test
    void hardMistakeTierDeductsHardTierDamage() throws Exception {
        useJourneyTopic(rules(10, 10), node(1L, 1L, null));
        giveNodeGameBlock(1L, "{\"schemaVersion\":1,\"mistakeTier\":\"hard\"}");
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);   // 第一次仍免罚
        assertEquals(10, state().path("variables").path("sys.hp").asInt(), "首错免罚不受档位影响");

        runtime.recordMistake(MEMBER, TOPIC, 1L);   // 第二次起按 hard 扣

        assertEquals(7, state().path("variables").path("sys.hp").asInt(), "hard 档默认扣 3");
        assertEquals("hard", mistakeReceipt(2).path("tier").asText());
    }

    /**
     * 主题调难度表 → 判错伤害随之变:主题把 tiers.medium.damage 从 2 改成 5,
     * 同一份 mistakeTier=medium 的模板新局扣 5。这是「主题调一次,两条扣血路一起变」的正身。
     *
     * <p>负控:把 mistakeTier 读死成 easy,本用例必红(easy 档仍是 1)。
     */
    @Test
    void mistakeTierDamageFollowsThemeDifficultyTable() throws Exception {
        useJourneyTopic(rulesWithMediumDamage(10, 10, 5), node(1L, 1L, null));
        giveNodeGameBlock(1L, "{\"schemaVersion\":1,\"mistakeTier\":\"medium\"}");
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);

        assertEquals(5, state().path("variables").path("sys.hp").asInt(), "主题 medium.damage=5 → 扣 5");
    }

    /**
     * 建局冻结:判错伤害吃的是**建局时**的难度表与档位,中途改主题表不动这一局。
     *
     * <p>§3.8:冻结只对带 configSnapshotJson 的新局成立(老局回退实时规则),所以这里先把
     * 「本局确实带快照、快照里确实冻了 medium.damage=2 与 mistakeTier」钉死,再验扣血读数 ——
     * 否则这条断言可能因为压根没冻结而假绿。
     *
     * <p>负控:recordMistake 里规则或块配置改读实时(绕过 session),本用例红。
     */
    @Test
    void mistakeTierDamageIsFrozenAtSessionStart() throws Exception {
        useJourneyTopic(rulesWithMediumDamage(10, 10, 2), node(1L, 1L, null));
        giveNodeGameBlock(1L, "{\"schemaVersion\":1,\"mistakeTier\":\"medium\"}");
        runtime.state(MEMBER, TOPIC, 0L);

        JsonNode snapshot = json.readTree(stored[0].getConfigSnapshotJson());
        assertEquals(2, snapshot.path("journeyRules").path("tiers").path("medium").path("damage").asInt(),
                "§3.8 前提:本局带的是建局冻结的难度表");
        assertEquals("medium", snapshot.path("nodeJourney").path("1").path("mistakeTier").asText(),
                "§3.8 前提:mistakeTier 也随块配置一起冻结");

        // 建局之后**两样都改**:主题把 medium 伤害 2→5,模板把档位 medium→hard。
        // 两条冻结各管一半 —— 只改主题只能验到「规则冻结」,块配置改读实时照样绿(注释比断言强)。
        topicMapper.selectCmsTopicById(TOPIC)
                .setJourneyRulesJson(rulesWithMediumDamage(10, 10, 5));
        giveNodeGameBlock(1L, "{\"schemaVersion\":1,\"mistakeTier\":\"hard\"}");

        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);

        // 冻结的是 medium=2。读实时规则会变 5、读实时块配置会变 hard=3,两种都不是 8。
        assertEquals(8, state().path("variables").path("sys.hp").asInt(),
                "本局仍按建局冻结的 medium=2 扣;规则或块配置任一改读实时,这里都该红");
    }

    // ==================== 3. 站末恢复 ====================

    @Test
    void stationEndRecoveryHealsOnlyWhenThisNodeHurt() throws Exception {
        useJourneyTopic(stationRules(5, 10, 2), node(1L, 1L, null), node(2L, 2L, null), node(3L, 3L, null));
        runtime.state(MEMBER, TOPIC, 0L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        complete(1L);

        runtime.advance(MEMBER, TOPIC, 0L, 0, "adv-1", "CHOICE", outcome(1L));

        JsonNode state = state();
        assertEquals(6, state.path("variables").path("sys.hp").asInt(), "本节点扣过血:回 stationEndIfHurt");
        assertFalse(state.path("variables").has("sys.damage.1"), "恢复用掉即清标记,防 loop 误判");

        // 节点 2 全程没扣血:推进后血量不动
        complete(1L, 2L);
        runtime.advance(MEMBER, TOPIC, 0L, 1, "adv-2", "CHOICE", outcome(2L));

        assertEquals(6, state().path("variables").path("sys.hp").asInt(), "本节点没扣过血就不回血");
    }

    @Test
    void stationEndRecoveryCapsAtHpMax() throws Exception {
        useJourneyTopic(stationRules(5, 5, 2), node(1L, 1L, null), node(2L, 2L, null));
        runtime.state(MEMBER, TOPIC, 0L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        complete(1L);

        runtime.advance(MEMBER, TOPIC, 0L, 0, "adv-cap", "CHOICE", outcome(1L));

        assertEquals(5, state().path("variables").path("sys.hp").asInt(), "封顶 hp.max");
    }

    // ==================== 4. 阶段休整 ====================

    @Test
    void stageRestFloorsHpAndLuckWhenEnteringTheFirstChapter() throws Exception {
        useJourneyTopic(stageRules(2, 10, 6, 1), node(1L, 1L, 11L), node(2L, 2L, 12L));

        runtime.state(MEMBER, TOPIC, 0L);

        JsonNode state = state();
        assertEquals(6, state.path("variables").path("sys.hp").asInt(), "建局即进入首章:抬到 hp 下限");
        assertEquals(1, state.path("variables").path("sys.luck").asInt(), "抬到 luck 下限");
        assertTrue(actionIds().contains("stage-rest:11"));
    }

    @Test
    void stageRestFiresOnceWhenEnteringANewChapterAndNotAgainWithinIt() throws Exception {
        useJourneyTopic(stageRules(6, 10, 6, 1),
                node(1L, 1L, 11L), node(2L, 2L, 12L), node(3L, 3L, 12L));
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        assertEquals(5, state().path("variables").path("sys.hp").asInt());
        complete(1L);

        runtime.advance(MEMBER, TOPIC, 0L, 0, "stage-1", "CHOICE", outcome(1L));

        assertEquals(6, state().path("variables").path("sys.hp").asInt(), "进入新章节的第一个节点:抬下限");
        assertEquals(1, countActions("stage-rest:12"));

        runtime.recordMistake(MEMBER, TOPIC, 2L);
        runtime.recordMistake(MEMBER, TOPIC, 2L);
        assertEquals(5, state().path("variables").path("sys.hp").asInt());
        complete(1L, 2L);

        runtime.advance(MEMBER, TOPIC, 0L, 1, "stage-2", "CHOICE", outcome(2L));

        assertEquals(5, state().path("variables").path("sys.hp").asInt(), "同章节内推进不重复休整");
        assertEquals(1, countActions("stage-rest:12"), "一个章节只登记一次");
    }

    /**
     * R22-SEC S9:并发进入同一章节时 stage-rest 撞唯一键,兜底吞掉重复键(与 recordMistake 同口径),
     * 不把原始 DuplicateKeyException 抛给玩家。
     *
     * <p>负控:去掉 recordStageRestAction 的 DuplicateKeyException 兜底,本用例立刻红。
     */
    @Test
    void stageRestDuplicateKeyIsSwallowedWhenWinnerAlreadyRegistered() throws Exception {
        useJourneyTopic(stageRules(2, 10, 6, 1), node(1L, 1L, 11L));
        TopicRouteAction winner = new TopicRouteAction();
        winner.setSessionId(99L);
        winner.setMemberId(MEMBER);
        winner.setActionId("stage-rest:11");
        when(routeMapper.insertAction(any(TopicRouteAction.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_route_action"));
        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenReturn(winner);

        runtime.state(MEMBER, TOPIC, 0L);

        JsonNode state = state();
        assertEquals(6, state.path("variables").path("sys.hp").asInt(), "休整状态照常生效");
        verify(routeMapper, times(1)).insertAction(any(TopicRouteAction.class));
    }

    /**
     * R22-SEC S10:hp / luck 未启用时不往本局状态写 sys.hp / sys.luck(与 isEnabled 口径一致),
     * 否则 requires 里的 sys.* 会被初始值「证明」通过。
     *
     * <p>负控:applyVitals 改回无条件写,本用例立刻红。
     */
    @Test
    void rollDoesNotWriteVitalsWhenHpAndLuckDisabled() throws Exception {
        useJourneyTopic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":false,\"max\":10,\"init\":5},"
                + "\"luck\":{\"enabled\":false,\"max\":3,\"init\":2}}", node(1L, 1L, null));
        CmsMemberTemplate template = template(101L,
                "{\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"medium\"}}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.roll(MEMBER, TOPIC, 1L, "c1");

        JsonNode state = state();
        assertFalse(state.path("variables").has("sys.hp"), "未启用 hp 不得写 sys.hp");
        assertFalse(state.path("variables").has("sys.luck"), "未启用 luck 不得写 sys.luck");
    }

    /**
     * R22-SEC S10 对照组:启用后系统键照常落状态,防止「一律不写」的假修复。
     */
    @Test
    void rollWritesVitalsWhenEnabled() throws Exception {
        useJourneyTopic(hpLuckRules(5, 10, 2), node(1L, 1L, null));
        CmsMemberTemplate template = template(101L,
                "{\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"medium\"}}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.roll(MEMBER, TOPIC, 1L, "c1");

        JsonNode state = state();
        assertEquals(5, state.path("variables").path("sys.hp").asInt());
        assertEquals(2, state.path("variables").path("sys.luck").asInt());
    }

    // ==================== 2. 获得幸运(效果封顶 / 忽略) ====================
    @Test
    void sysLuckEffectCapsAtLuckMax() throws Exception {
        useJourneyTopic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":1}}", node(1L, 1L, null), node(2L, 2L, null));
        CmsMemberTemplate template = template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":3}]}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        runtime.state(MEMBER, TOPIC, 0L);
        complete(1L);

        runtime.advance(MEMBER, TOPIC, 0L, 0, "luck-cap", "CHOICE", outcome(1L));

        assertEquals(3, state().path("variables").path("sys.luck").asInt(), "幸运封顶 luck.max");
    }

    @Test
    void sysLuckEffectIsIgnoredWhenLuckDisabledAndOthersStillApply() throws Exception {
        useJourneyTopic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},"
                + "\"luck\":{\"enabled\":false,\"max\":3,\"init\":1}}", node(1L, 1L, null), node(2L, 2L, null));
        CmsMemberTemplate template = template(101L, "{\"effects\":["
                + "{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2},"
                + "{\"op\":\"INC\",\"var\":\"counter.visits\",\"value\":1}]}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        runtime.state(MEMBER, TOPIC, 0L);
        complete(1L);

        runtime.advance(MEMBER, TOPIC, 0L, 0, "luck-off", "CHOICE", outcome(1L));

        JsonNode state = state();
        assertFalse(state.path("variables").has("sys.luck"), "luck.enabled=false 时忽略该效果");
        assertEquals(1, state.path("variables").path("counter.visits").asInt(), "同批其它效果照常生效");
    }

    @Test
    void settleFailEffectsApplySysLuckWithCap() throws Exception {
        useJourneyTopic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":10},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":2}}", node(1L, 1L, null));
        CmsMemberTemplate template = template(101L,
                "{\"check\":{\"enabled\":true,\"checkId\":\"c1\",\"tier\":\"hard\","
                        + "\"failEffects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":2}]}}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        runtime.state(MEMBER, TOPIC, 0L);

        runtime.roll(MEMBER, TOPIC, 1L, "c1");
        runtime.settle(MEMBER, TOPIC, 1L, "c1");

        JsonNode state = state();
        assertEquals(3, state.path("variables").path("sys.luck").asInt(), "结算失败效果同样封顶");
        assertEquals(7, state.path("variables").path("sys.hp").asInt(), "hard 档失败扣 3 血");
    }

    // ==================== 5. 问问题 ====================

    @Test
    void askRequiresArrival() {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        when(shopDayProofService.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(false);
        runtime.state(MEMBER, TOPIC, 0L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.ask(MEMBER, TOPIC, 1L, "night"));

        assertEquals("请先扫码确认到店", error.getMessage());
    }

    @Test
    void askRejectsUnknownQuestion() {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        stubAskTemplate();
        runtime.state(MEMBER, TOPIC, 0L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.ask(MEMBER, TOPIC, 1L, "day"));

        assertEquals("这一站没有这个问题", error.getMessage());
    }

    @Test
    void askWritesAskedFlagAndReplaysSameReceipt() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        stubAskTemplate();
        runtime.state(MEMBER, TOPIC, 0L);

        Map<String, Object> first = runtime.ask(MEMBER, TOPIC, 1L, "night");
        Map<String, Object> replayed = runtime.ask(MEMBER, TOPIC, 1L, "night");

        assertEquals("钟楼", first.get("a"));
        assertEquals("今晚几点关", first.get("q"));
        assertEquals(json.writeValueAsString(first), json.writeValueAsString(replayed), "同 id 重放返回原回执");
        assertEquals(1, state().path("variables").path("sys.asked.1.night").asInt());
        assertEquals(1, actions.size(), "只登记一次动作");
    }

    // ==================== 6. 回应开场通话 ====================

    @Test
    void callWritesOptionIndexAndOnlyOnce() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        when(topicMapper.selectJourneyStoryJsonById(TOPIC)).thenReturn(storyJson());
        runtime.state(MEMBER, TOPIC, 0L);
        // 老局口径:把建局冻结的剧情键去掉,验证没有冻结键时回退实时列
        stored[0].setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":1}");

        Map<String, Object> first = runtime.call(MEMBER, TOPIC, "b");
        Map<String, Object> replayed = runtime.call(MEMBER, TOPIC, "a");

        assertEquals(2, ((Number) first.get("index")).intValue(), "sys.call 记选项序号");
        assertEquals("回 B", first.get("reply"));
        assertEquals("今晚这通电话", first.get("brief"));
        assertEquals(json.writeValueAsString(first), json.writeValueAsString(replayed),
                "只允许一次:重放返回原回执,不换选项");
        assertEquals(2, state().path("variables").path("sys.call").asInt());
        assertEquals(1, actions.size());
    }

    @Test
    void callRejectsUnknownOption() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        when(topicMapper.selectJourneyStoryJsonById(TOPIC)).thenReturn(storyJson());
        runtime.state(MEMBER, TOPIC, 0L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.call(MEMBER, TOPIC, "zzz"));

        assertEquals("开场通话没有这个选项", error.getMessage());
    }

    /**
     * R11-A:开场通话读建局冻结的主题剧情,开局后改 journey_story_json 不穿透进行中的局。
     *
     * <p>负控:journeyStory(session, topicId) 改回直接窄读实时列,本用例立刻红
     * (改后主题的选项 c 会被接受)。
     */
    @Test
    void callReadsFrozenStoryAndIgnoresEditedTopicStory() throws Exception {
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        topicMapper.selectCmsTopicById(TOPIC).setJourneyStoryJson(storyJson());
        runtime.state(MEMBER, TOPIC, 0L);

        // 开局后创作者改剧情:多了一个选项 c,开场白也换了
        topicMapper.selectCmsTopicById(TOPIC).setJourneyStoryJson(
                "{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"改后的喂\",\"brief\":\"改后的电话\","
                        + "\"options\":[{\"id\":\"a\",\"line\":\"接\",\"reply\":\"回 A\"},"
                        + "{\"id\":\"b\",\"line\":\"不接\",\"reply\":\"回 B\"},"
                        + "{\"id\":\"c\",\"line\":\"挂断\",\"reply\":\"回 C\"}]}}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runtime.call(MEMBER, TOPIC, "c"));
        assertEquals("开场通话没有这个选项", error.getMessage(), "改后的新选项不进进行中的局");

        Map<String, Object> first = runtime.call(MEMBER, TOPIC, "b");
        assertEquals("回 B", first.get("reply"), "冻结的选项照常可用");
        assertEquals("今晚这通电话", first.get("brief"), "brief 也读冻结值");
    }

    // ==================== 7. M1 回执自带新世界 ====================

    @Test
    void askReceiptCarriesNewVersionAndRefreshedEncounter() {
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        stubAskTemplate();
        runtime.state(MEMBER, TOPIC, 0L);
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", 1L);
        encounter.put("stateVersion", 1);
        when(encounterService.resolve(MEMBER, TOPIC, 0L, 1L)).thenReturn(encounter);

        Map<String, Object> receipt = runtime.ask(MEMBER, TOPIC, 1L, "night");

        assertEquals(1, ((Number) receipt.get("stateVersion")).intValue(), "ask 回包自带新 version");
        assertNotNull(receipt.get("encounter"), "ask 回包自带这一站刷新后的 Encounter");
        assertTrue(actionIds().contains("ask:1:night"));
    }

    @Test
    void callReceiptCarriesNewVersionAndRefreshedEncounter() {
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
        useJourneyTopic(rules(5, 10), node(1L, 1L, null));
        when(topicMapper.selectJourneyStoryJsonById(TOPIC)).thenReturn(storyJson());
        runtime.state(MEMBER, TOPIC, 0L);
        // 老局口径:把建局冻结的剧情键去掉,验证没有冻结键时回退实时列
        stored[0].setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":1}");
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", 1L);
        encounter.put("stateVersion", 1);
        when(encounterService.resolve(MEMBER, TOPIC, 0L, 1L)).thenReturn(encounter);

        Map<String, Object> receipt = runtime.call(MEMBER, TOPIC, "b");

        assertEquals(1, ((Number) receipt.get("stateVersion")).intValue(), "call 回包自带新 version");
        assertNotNull(receipt.get("encounter"), "call 回包自带这一站刷新后的 Encounter");
    }

    // ==================== 8. R20-INT 收口 b:hp / luck 随写接口回执下发 ====================

    /**
     * ask 回执顶层带当前 hp / luck(不是初始值):先判错两次把血从 5 扣到 4,回执必须反映当前值。
     *
     * <p>负控:去掉 ask 的 putVitals,本用例立刻红。
     */
    @Test
    void askReceiptCarriesCurrentHpAndLuck() {
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenReturn(1);
        useJourneyTopic(hpLuckRules(5, 10, 2), node(1L, 1L, null));
        stubAskTemplate();
        runtime.state(MEMBER, TOPIC, 0L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L); // 第一次免罚,第二次 -1

        Map<String, Object> receipt = runtime.ask(MEMBER, TOPIC, 1L, "night");

        assertEquals(4, ((Number) receipt.get("hp")).intValue(), "回执带当前 hp(不是初始值)");
        assertEquals(2, ((Number) receipt.get("luck")).intValue(), "回执带当前 luck");
    }

    /** call 回执顶层带当前 hp / luck;老局快照没有冻结规则时回退实时主题规则读数。 */
    @Test
    void callReceiptCarriesCurrentHpAndLuck() {
        useJourneyTopic(hpLuckRules(5, 10, 2), node(1L, 1L, null));
        when(topicMapper.selectJourneyStoryJsonById(TOPIC)).thenReturn(storyJson());
        runtime.state(MEMBER, TOPIC, 0L);
        stored[0].setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":1}");

        Map<String, Object> receipt = runtime.call(MEMBER, TOPIC, "b");

        assertEquals(5, ((Number) receipt.get("hp")).intValue());
        assertEquals(2, ((Number) receipt.get("luck")).intValue());
    }

    /**
     * vitals 读当前值(与检定回执同一份口径);没有本局返回 null,调用方缺省。
     *
     * <p>负控:vitals 改成永远返回规则 init,本用例 hp 断言立刻红。
     */
    @Test
    void vitalsReadsCurrentHpAndLuckAndNullWithoutSession() {
        useJourneyTopic(hpLuckRules(5, 10, 2), node(1L, 1L, null));
        runtime.state(MEMBER, TOPIC, 0L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);

        Map<String, Integer> vitals = runtime.vitals(MEMBER, TOPIC);

        assertEquals(Integer.valueOf(4), vitals.get("hp"), "当前值,不是初始值");
        assertEquals(Integer.valueOf(2), vitals.get("luck"));
        assertNull(runtime.vitals(MEMBER, 999L), "没有本局返回 null");
    }

    /**
     * R22-A7:推进回执(线性局)顶层补 hp / luck 当前值 —— 站末恢复与选择效果改过的值
     * 随回执带出,不必再拉 vitals;重放回执同值。
     *
     * <p>负控:advanceLinear 去掉 putVitals,本用例立刻红。
     */
    @Test
    void advanceReceiptCarriesCurrentHpAndLuck() {
        useJourneyTopic("{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":5},"
                + "\"luck\":{\"enabled\":true,\"max\":5,\"init\":3},"
                + "\"recovery\":{\"stationEndIfHurt\":2,\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0}}",
                node(1L, 1L, null), node(2L, 2L, null));
        CmsMemberTemplate effects = template(101L,
                "{\"effects\":[{\"op\":\"INC\",\"var\":\"sys.luck\",\"value\":1}]}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(effects));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(effects);
        // 回执落库:重放读回存下来的 responseJson(与生产 updateActionResponse 同行为)
        when(routeMapper.updateActionResponse(anyLong(), anyString())).thenAnswer(inv -> {
            for (TopicRouteAction action : actions) {
                if (inv.getArgument(0).equals(action.getId())) {
                    action.setResponseJson(inv.getArgument(1));
                    return 1;
                }
            }
            return 1;
        });
        runtime.state(MEMBER, TOPIC, 0L);
        runtime.recordMistake(MEMBER, TOPIC, 1L);
        runtime.recordMistake(MEMBER, TOPIC, 1L); // 5 到 4
        complete(1L);

        TopicRouteState receipt = runtime.advance(MEMBER, TOPIC, 0L, 0, "adv-vitals", "CHOICE", outcome(1L));

        assertEquals(Integer.valueOf(6), receipt.getHp(), "站末恢复 +2(封顶 10)");
        assertEquals(Integer.valueOf(4), receipt.getLuck(), "luck 初值 3,选择效果 +1");

        TopicRouteState replay = runtime.advance(MEMBER, TOPIC, 0L, 0, "adv-vitals", "CHOICE", outcome(1L));

        assertEquals(Integer.valueOf(6), replay.getHp(), "重放回执同值");
        assertEquals(Integer.valueOf(4), replay.getLuck());
    }

    // ==================== helpers ====================

    private JsonNode state() throws Exception {
        return json.readTree(stored[0].getStateJson());
    }

    private List<String> actionIds() {
        List<String> ids = new ArrayList<>();
        for (TopicRouteAction action : actions) ids.add(action.getActionId());
        return ids;
    }

    private int countActions(String actionId) {
        int count = 0;
        for (TopicRouteAction action : actions) if (actionId.equals(action.getActionId())) count++;
        return count;
    }

    private void useJourneyTopic(String rulesJson, CmsTopicNode... nodes) {
        topicMapper.selectCmsTopicById(TOPIC).setJourneyRulesJson(rulesJson);
        liveNodes = new ArrayList<>(Arrays.asList(nodes));
        for (CmsTopicNode node : liveNodes) {
            when(nodeMapper.selectCmsTopicNodeById(node.getId())).thenReturn(node);
        }
    }

    private void complete(Long... nodeIds) {
        List<PlayerNodeProgress> rows = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            PlayerNodeProgress row = new PlayerNodeProgress();
            row.setNodeId(nodeId);
            row.setStatus(1);
            rows.add(row);
        }
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class))).thenReturn(rows);
    }

    /** R18:问题叙事住在章节故事流的 enter 块,template 只留玩法段。 */
    private void stubAskTemplate() {
        CmsMemberTemplate template = template(101L, "{\"schemaVersion\":1,\"effects\":[]}");
        liveNodes.get(0).setTemplateId(101L);
        when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(template);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTopicId(TOPIC);
        chapter.setBlocksJson("["
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"q\",\"qid\":\"night\",\"content\":\"今晚几点关\",\"nodeId\":1},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"a\",\"qid\":\"night\",\"content\":\"钟楼\",\"nodeId\":1},"
                + "{\"type\":\"node\",\"nodeId\":1}]");
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));
    }

    private static NodeOutcome outcome(Long nodeId) {
        return new NodeOutcome(nodeId, "COMPLETED", "player-node-progress:" + nodeId);
    }

    private static CmsTopicNode node(Long id, Long sort, Long chapterId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC);
        node.setSortId(sort);
        node.setStatus(1L);
        node.setChapterId(chapterId);
        return node;
    }

    private static CmsMemberTemplate template(Long id, String config) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(id);
        template.setAdvancedConfigJson(config);
        return template;
    }

    /** 给节点挂一份玩法模板(recordMistake 从这块配置读 mistakeTier;建局冻结与回退实时两条路都走它)。 */
    private void giveNodeGameBlock(Long nodeId, String advancedConfigJson) {
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        CmsMemberTemplate block = template(201L, advancedConfigJson);
        node.setTemplateId(201L);
        when(templateMapper.selectCmsMemberTemplateById(201L)).thenReturn(block);
        when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(block));
    }

    /** 按序号取这一节点的判错回执(第 n 次判错,actionId = mistake:{nodeId}:{n})。 */
    private JsonNode mistakeReceipt(int ordinal) throws Exception {
        for (TopicRouteAction action : actions) {
            if (("mistake:1:" + ordinal).equals(action.getActionId())) {
                return json.readTree(action.getResponseJson());
            }
        }
        throw new AssertionError("没有第 " + ordinal + " 次判错回执");
    }

    private static String rules(int hpInit, int hpMax) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":" + hpMax + ",\"init\":" + hpInit + "}}";
    }

    /** 主题难度表:easy=1 / medium=传入 / hard=3,其余取默认。 */
    private static String rulesWithMediumDamage(int hpInit, int hpMax, int mediumDamage) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":" + hpMax + ",\"init\":" + hpInit + "},"
                + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},"
                + "\"medium\":{\"dc\":13,\"damage\":" + mediumDamage + "},"
                + "\"hard\":{\"dc\":16,\"damage\":3}}}";
    }

    /** R20-INT 收口 b:hp 与 luck 都启用,用来验回执带的是当前值。 */
    private static String hpLuckRules(int hpInit, int hpMax, int luckInit) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":" + hpMax + ",\"init\":" + hpInit + "},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":" + luckInit + "}}";
    }

    private static String stationRules(int hpInit, int hpMax, int stationEndIfHurt) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":" + hpMax + ",\"init\":" + hpInit + "},"
                + "\"recovery\":{\"stationEndIfHurt\":" + stationEndIfHurt
                + ",\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0}}";
    }

    private static String stageRules(int hpInit, int hpMax, int stageRestHpFloor, int stageRestLuckFloor) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":" + hpMax + ",\"init\":" + hpInit + "},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":0},"
                + "\"recovery\":{\"stationEndIfHurt\":0,\"stageRestHpFloor\":" + stageRestHpFloor
                + ",\"stageRestLuckFloor\":" + stageRestLuckFloor + "}}";
    }

    private static String storyJson() {
        return "{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"喂\",\"brief\":\"今晚这通电话\","
                + "\"options\":[{\"id\":\"a\",\"line\":\"接\",\"reply\":\"回 A\"},"
                + "{\"id\":\"b\",\"line\":\"不接\",\"reply\":\"回 B\"}]}}";
    }
}
