package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayJourneyReadMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R8-CASEBOOK 只读读侧:注册键白名单、三态关系、日志还原(hp/luck 相邻视图相减、重放不重复计)、
 * 背包状态与既有券 / 奖牌 / 进度记录一致;以及「只能读自己的局」。
 */
class PlayJourneyReadServiceImplTest {

    private static final long MEMBER = 7L, OTHER = 8L, TOPIC = 10L, SESSION = 99L;
    private static final long N1 = 101L, N2 = 102L, N3 = 103L, N4 = 104L, N5 = 105L;

    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final PlayerNodeProgressMapper progressMapper = mock(PlayerNodeProgressMapper.class);
    private final SmsCouponHistoryMapper couponHistoryMapper = mock(SmsCouponHistoryMapper.class);
    private final PlayJourneyReadMapper readMapper = mock(PlayJourneyReadMapper.class);
    private final NpcProfileService npcProfileService = mock(NpcProfileService.class);
    private final INodeMerchantOverrideService overrideService = mock(INodeMerchantOverrideService.class);
    private final ThemeStateKeyService themeStateKeyService = mock(ThemeStateKeyService.class);
    private final ISmsCouponService couponService = mock(ISmsCouponService.class);
    private final IXpBudgetService xpBudgetService = mock(IXpBudgetService.class);

    private PlayJourneyReadServiceImpl service;
    private CmsTopic topic;
    private TopicRouteSession session;

    @BeforeEach
    void setUp() {
        service = new PlayJourneyReadServiceImpl(topicMapper, nodeMapper, templateMapper, routeMapper,
                progressMapper, couponHistoryMapper, readMapper, npcProfileService,
                overrideService, themeStateKeyService, couponService, xpBudgetService);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);

        session = new TopicRouteSession();
        session.setId(SESSION);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(0L);
        givenState("{}", "[]");
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session));
        when(routeMapper.selectMemberTopicSessions(OTHER, TOPIC)).thenReturn(java.util.Collections.singletonList(null));
        // Resolve the exact current scope from each test's actual, mutable session fixture.
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, MEMBER)).thenAnswer(inv -> {
            List<TopicRouteSession> rows = routeMapper.selectMemberTopicSessions(MEMBER, TOPIC);
            TopicRouteSession current = rows != null && rows.size() == 1 ? rows.get(0) : null;
            return current != null && Long.valueOf(MEMBER).equals(current.getMemberId())
                    && Long.valueOf(TOPIC).equals(current.getTopicId())
                    && Long.valueOf(0L).equals(current.getActivityId()) ? current : null;
        });


        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(new ArrayList<CmsTopicNode>());
        when(nodeMapper.selectCmsTopicNodeById(anyLong())).thenReturn(null);
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(new ArrayList<ThemeStateKey>());
        when(readMapper.selectActionsBySession(SESSION)).thenReturn(new ArrayList<TopicRouteAction>());
        when(readMapper.selectJourneyStoryJson(TOPIC)).thenReturn(null);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(new ArrayList<PlayerNodeProgress>());
        when(couponHistoryMapper.selectSmsCouponHistoryList(any(SmsCouponHistory.class)))
                .thenReturn(new ArrayList<SmsCouponHistory>());
        when(overrideService.forNode(anyLong())).thenReturn(NodeMerchantOverride.empty());
        when(xpBudgetService.resolveNodeXp(any(CmsTopicNode.class))).thenReturn(0);
    }

    @Test
    void casebookHidesAttributesUsingFrozenRules() {
        givenState("{\"clue.secret\":1,\"relation.secret\":1}", "[\"tag.secret\",\"tag.public\"]");
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(Arrays.asList(
                key("clue.secret", "flag", "秘密线索", null),
                key("relation.secret", "relation", "秘密关系", null),
                key("tag.secret", "tag", "秘密状态", null),
                key("tag.public", "tag", "公开状态", null)));
        session.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"clue.secret\",\"label\":\"隐藏属性\",\"visible\":false},"
                + "{\"key\":\"relation.secret\",\"label\":\"隐藏属性\",\"visible\":false},"
                + "{\"key\":\"tag.secret\",\"label\":\"隐藏属性\",\"visible\":false}]}}");
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[]}");
        Map<String, Object> view = service.casebook(MEMBER, TOPIC);
        assertTrue(((List<?>) view.get("facts")).isEmpty());
        assertTrue(((List<?>) view.get("relations")).isEmpty());
        assertEquals(1, ((List<?>) view.get("costs")).size());
    }

    @Test
    void casebookUsesCompleteOriginalDefinitionsWithoutReadingChangedRegistry() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var snapshot = json.createObjectNode();
        var rules = com.chengyinhub.business.domain.JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true}");
        snapshot.set("journeyRules", json.valueToTree(rules)); snapshot.put("journeyStory", "");
        List<ThemeStateKey> declarations = Arrays.asList(
                key("clue.original", "flag", "原线索", null), key("tag.original", "tag", "原代价", null));
        for (ThemeStateKey declaration : declarations) declaration.setTopicId(TOPIC);
        com.chengyinhub.business.service.support.JourneyInitialState.freezeKeys(snapshot, declarations, rules, TOPIC);
        assertEquals(1, snapshot.path("stateKeySnapshotVersion").asInt());
        session.setConfigSnapshotJson(snapshot.toString());
        givenState("{\"clue.original\":1}", "[\"tag.original\"]");
        when(themeStateKeyService.listByTopic(TOPIC)).thenThrow(new AssertionError("original casebook must not reread author declarations"));

        Map<String, Object> book = service.casebook(MEMBER, TOPIC);

        String response = json.writeValueAsString(book);
        assertTrue(response.contains("原线索")); assertTrue(response.contains("原代价"));
        assertEquals(1, ((List<?>) book.get("facts")).size());
        assertEquals(1, ((List<?>) book.get("costs")).size());
        assertEquals(snapshot.toString(), session.getConfigSnapshotJson());
    }

    @Test
    void oldCasebookCannotRevealRelationsAddedAfterStart() {
        session.setConfigSnapshotJson("{\"stateKeys\":[],\"journeyRules\":{\"schemaVersion\":1,"
                + "\"stateEnabled\":true,\"attributes\":[]}}");
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(Collections.singletonList(
                key("relation.new_secret", "relation", "未来的秘密人物", null)));
        assertTrue(((List<?>) service.casebook(MEMBER, TOPIC).get("relations")).isEmpty());
    }

    // ==================== 案件册:白名单与三态 ====================

    @SuppressWarnings("unchecked")
    @Test
    void casebookKeepsOnlyRegisteredAndHeldFactsAndCosts() {
        givenState("{\"clue.ledger_page\":1,\"clue.not_registered\":1,\"clue.zero\":0}",
                "[\"tag.hurried\",\"tag.not_registered\"]");
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(Arrays.asList(
                key("clue.ledger_page", "flag", "账本页", null),
                key("clue.zero", "flag", "只有零", null),
                key("tag.hurried", "tag", "匆忙", null),
                key("tag.injured", "tag", "受伤", null)));

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        List<Map<String, Object>> facts = (List<Map<String, Object>>) data.get("facts");
        assertEquals(1, facts.size(), "只有已注册且值>=1的clue才算事实");
        assertEquals("clue.ledger_page", facts.get(0).get("key"));
        assertEquals("账本页", facts.get(0).get("label"));

        List<Map<String, Object>> costs = (List<Map<String, Object>>) data.get("costs");
        assertEquals(1, costs.size(), "未注册的tag不出现");
        assertEquals("tag.hurried", costs.get(0).get("tag"));
        assertEquals("匆忙", costs.get(0).get("label"));

        assertEquals(0, data.get("found"));
        assertEquals(0, data.get("total"), "journey_story_json未配置时0/0");
    }

    /**
     * R22-C3:已注册但 label 为空的键回落键名去掉前缀的可读名,与阶段结算同一份解析;
     * 任何响应都不下发带前缀的完整键名(键名是服务端口径)。
     *
     * <p>负控:把 displayLabel 的回落改回 stateKey(clue.unlabeled),本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void blankRegisteredLabelsFallBackToReadableKeySuffix() {
        givenState("{\"clue.unlabeled\":1}", "[\"tag.hurried\",\"tag.unlabeled\"]");
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(Arrays.asList(
                key("clue.unlabeled", "flag", null, null),
                key("tag.hurried", "tag", "匆忙", null),
                key("tag.unlabeled", "tag", "   ", null)));

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        List<Map<String, Object>> facts = (List<Map<String, Object>>) data.get("facts");
        assertEquals("unlabeled", facts.get(0).get("label"), "空 label 回落键后缀,不带 clue. 前缀");
        List<Map<String, Object>> costs = (List<Map<String, Object>>) data.get("costs");
        assertEquals("匆忙", costs.get(0).get("label"));
        assertEquals("unlabeled", costs.get(1).get("label"), "空 label 回落键后缀,不带 tag. 前缀");
    }

    @SuppressWarnings("unchecked")
    @Test
    void relationsFollowEpilogueMappingAndIgnoreNameMatching() {
        givenState("{\"relation.arong\":2,\"relation.lin\":0,\"relation.zhao\":-2}", "[]");
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(Arrays.asList(
                key("relation.arong", "relation", "阿蓉", null),
                key("relation.lin", "relation", "阿林", null),
                key("relation.zhao", "relation", "老赵", null),
                key("relation.absent", "relation", "没出现过的人", null),
                key("relation.dupe", "relation", "阿蓉", null)));
        when(readMapper.selectJourneyStoryJson(TOPIC)).thenReturn(
                "{\"schemaVersion\":1,\"ending\":{\"epilogues\":["
                        + "{\"npcId\":5,\"relationKey\":\"relation.arong\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"},"
                        + "{\"npcId\":6,\"relationKey\":\"relation.lin\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"},"
                        + "{\"npcId\":999,\"relationKey\":\"relation.zhao\",\"ally\":\"a\",\"neutral\":\"b\",\"guarded\":\"c\"}]}}");
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(new ArrayList<CmsTopicNode>(Arrays.asList(
                        node(N1, 1L, null, null), node(N2, 2L, null, null))));
        when(npcProfileService.resolveForNodeOrMerchant(N1, null)).thenReturn(npc(5L, "阿蓉"));
        when(npcProfileService.resolveForNodeOrMerchant(N2, null)).thenReturn(npc(6L, "阿林"));

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        List<Map<String, Object>> relations = (List<Map<String, Object>>) data.get("relations");
        assertEquals(5, relations.size(), "注册过的关系都要出现(缺值=neutral)");
        assertEquals("ally", relations.get(0).get("state"));
        assertEquals(5L, relations.get(0).get("npcId"), "npcId 只认 epilogues 的 relationKey→npcId 映射");
        assertEquals("阿蓉", relations.get(0).get("name"));
        assertEquals("neutral", relations.get(1).get("state"));
        assertEquals(6L, relations.get(1).get("npcId"));
        assertEquals("阿林", relations.get(1).get("name"));
        assertEquals("guarded", relations.get(2).get("state"));
        assertEquals(999L, relations.get(2).get("npcId"), "映射到的 NPC 解析不到名字时回落 label");
        assertEquals("老赵", relations.get(2).get("name"));
        assertEquals("neutral", relations.get(3).get("state"), "缺值算 neutral");
        assertNull(relations.get(3).get("npcId"), "没配 epilogues 的键 npcId=null");
        assertEquals("没出现过的人", relations.get(3).get("name"), "配不到NPC回落label");
        assertNull(relations.get(4).get("npcId"), "label 与 NPC 同名也不再模糊匹配");
        assertEquals("阿蓉", relations.get(4).get("name"), "名字直接取注册 label");
    }

    @Test
    void threeStateThresholdsAreAllyZeroGuarded() {
        assertEquals("ally", PlayJourneyReadServiceImpl.threeState(1));
        assertEquals("ally", PlayJourneyReadServiceImpl.threeState(99));
        assertEquals("neutral", PlayJourneyReadServiceImpl.threeState(0));
        assertEquals("guarded", PlayJourneyReadServiceImpl.threeState(-1));
        assertEquals("guarded", PlayJourneyReadServiceImpl.threeState(-99));
    }

    // ==================== 案件册:日志还原 ====================

    @SuppressWarnings("unchecked")
    @Test
    void logRestoresVitalsFromAdjacentSnapshotsAndIgnoresReplayedAction() {
        topic.setJourneyRulesJson(rules(10, 3));
        List<TopicRouteAction> actions = new ArrayList<TopicRouteAction>();
        actions.add(action(1L, "act-node", "CHOICE", N1, "{}", 1_000L));
        actions.add(action(2L, "check:101:ask:roll", "CHECK_ROLL", N1,
                receiptJson(10, 3, null, null), 2_000L));
        actions.add(action(3L, "check:101:ask:reroll", "CHECK_REROLL", N1,
                receiptJson(10, 2, null, null), 3_000L));
        // 重放退化:同 actionId 的第二行(库侧有唯一键,读侧仍要自己去重)
        actions.add(action(4L, "check:101:ask:reroll", "CHECK_REROLL", N1,
                receiptJson(10, 1, null, null), 4_000L));
        actions.add(action(5L, "check:101:ask:settle", "CHECK_SETTLE", N1,
                receiptJson(8, 2, Boolean.FALSE, "他合上了账本"), 5_000L));
        actions.add(action(6L, "rest:101:6", "CHECK_REST", N1,
                receiptJson(13, 2, null, null), 6_000L));
        when(readMapper.selectActionsBySession(SESSION)).thenReturn(actions);

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        List<Map<String, Object>> log = (List<Map<String, Object>>) data.get("log");
        assertEquals(4, log.size(), "roll 不进日志;重放行不得多出一条");
        assertEquals("node", log.get(0).get("kind"));
        assertEquals(0, log.get(0).get("hpDelta"));
        assertEquals("reroll", log.get(1).get("kind"), "重放行(第二行)必须被去重");
        assertEquals(-1, log.get(1).get("luckDelta"), "重掷扣1点幸运");
        assertEquals(0, log.get(1).get("hpDelta"));
        assertEquals("check", log.get(2).get("kind"));
        assertEquals(-2, log.get(2).get("hpDelta"), "扣血 = 相邻两次视图 10->8");
        assertEquals(0, log.get(2).get("luckDelta"), "重放行不得把幸运再扣一次");
        assertTrue(String.valueOf(log.get(2).get("text")).contains("失败"));
        assertEquals("rest", log.get(3).get("kind"));
        assertEquals(5, log.get(3).get("hpDelta"), "休整回血 8->13");
        assertEquals(Long.valueOf(1_000L), log.get(0).get("at"));
        assertEquals(Long.valueOf(6_000L), log.get(3).get("at"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void logSeedsFirstSnapshotFromJourneyRulesInit() {
        topic.setJourneyRulesJson(rules(10, 3));
        when(readMapper.selectActionsBySession(SESSION)).thenReturn(new ArrayList<TopicRouteAction>(
                Collections.singletonList(action(1L, "check:101:ask:settle", "CHECK_SETTLE", N1,
                        receiptJson(8, 2, Boolean.FALSE, null), 1_000L))));

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        List<Map<String, Object>> log = (List<Map<String, Object>>) data.get("log");
        assertEquals(1, log.size());
        assertEquals(-2, log.get(0).get("hpDelta"), "首个视图与规则初值相减");
        assertEquals(-1, log.get(0).get("luckDelta"), "3->2");
    }

    // ==================== 案件册:归属与可选发现 ====================

    @SuppressWarnings("unchecked")
    @Test
    void logClassifiesInternalActionsByActionIdPrefix() {
        topic.setJourneyRulesJson(rules(10, 3));
        List<TopicRouteAction> actions = new ArrayList<TopicRouteAction>();
        actions.add(action(1L, "act-node", "CHOICE", N1, "{}", 1_000L));
        actions.add(action(2L, "mistake:101:1", "MISTAKE", N1,
                "{\"nodeId\":101,\"mistakes\":1,\"damage\":0,\"hp\":10}", 2_000L));
        actions.add(action(3L, "mistake:101:2", "MISTAKE", N1,
                "{\"nodeId\":101,\"mistakes\":2,\"damage\":1,\"hp\":9}", 3_000L));
        actions.add(action(4L, "ask:101:night", "ASK", N1, "{\"q\":\"问\",\"a\":\"答\"}", 4_000L));
        actions.add(action(5L, "call", "CALL", N1, "{\"optionId\":\"a\"}", 5_000L));
        actions.add(action(6L, "stage-rest:11", "STAGE_REST", N1,
                "{\"chapterId\":11,\"hp\":13,\"luck\":2}", 6_000L));
        when(readMapper.selectActionsBySession(SESSION)).thenReturn(actions);

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        List<Map<String, Object>> log = (List<Map<String, Object>>) data.get("log");
        assertEquals(3, log.size(), "首错免罚与 ask/call 不进日志:" + log);
        assertEquals("node", log.get(0).get("kind"));
        assertEquals("mistake", log.get(1).get("kind"));
        assertEquals("答错 · 生命 -1", log.get(1).get("text"));
        assertEquals(-1, log.get(1).get("hpDelta"));
        assertEquals(0, log.get(1).get("luckDelta"));
        assertEquals("rest", log.get(2).get("kind"));
        assertEquals("阶段休整", log.get(2).get("text"));
        assertEquals(4, log.get(2).get("hpDelta"), "9->13");
        assertEquals(-1, log.get(2).get("luckDelta"), "3->2");
        for (Map<String, Object> event : log) {
            assertFalse(String.valueOf(event.get("text")).contains("sys."), "保留变量不得进文案");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void logSkipsStageRestWithoutAttributeChange() {
        topic.setJourneyRulesJson(rules(10, 3));
        when(readMapper.selectActionsBySession(SESSION)).thenReturn(new ArrayList<TopicRouteAction>(
                Collections.singletonList(action(1L, "stage-rest:11", "STAGE_REST", N1,
                        "{\"chapterId\":11,\"hp\":10,\"luck\":3}", 1_000L))));

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        assertTrue(((List<?>) data.get("log")).isEmpty(), "没动属性就不占一行");
    }

    @SuppressWarnings("unchecked")
    @Test
    void casebookNeverLeaksReservedSysVariables() throws Exception {
        givenState("{\"sys.hp\":7,\"sys.luck\":1,\"sys.exhausted\":1,\"sys.damage.101\":3,"
                + "\"clue.page\":1,\"relation.arong\":2}", "[\"tag.hurried\"]");
        when(themeStateKeyService.listByTopic(TOPIC)).thenReturn(Arrays.asList(
                key("clue.page", "flag", "残页", null),
                key("relation.arong", "relation", "阿蓉", null),
                key("tag.hurried", "tag", "匆忙", null)));
        when(readMapper.selectJourneyStoryJson(TOPIC)).thenReturn(
                "{\"schemaVersion\":1,\"ending\":{\"optionalClues\":[\"clue.page\"],\"epilogues\":[]}}");

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        String serialized = new ObjectMapper().writeValueAsString(data);
        assertFalse(serialized.contains("sys."), "服务端保留变量(sys.*)不得下发:" + serialized);
        assertEquals(1, data.get("found"));
        assertEquals(1, data.get("total"));
    }

    @Test
    void casebookRejectsForeignOrMissingRun() {
        assertThrows(ServiceException.class, () -> service.casebook(OTHER, TOPIC), "没有自己的局就不给读");

        TopicRouteSession foreign = new TopicRouteSession();
        foreign.setId(SESSION);
        foreign.setMemberId(OTHER);
        foreign.setTopicId(TOPIC);
        foreign.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[]}");
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(foreign));
        assertThrows(ServiceException.class, () -> service.casebook(MEMBER, TOPIC), "mapper 口径被改也不能吐别人的局");
    }

    @Test
    void casebookFoundCountsHeldOptionalClues() {
        givenState("{\"clue.voice\":1,\"clue.photo_story\":0}", "[]");
        when(readMapper.selectJourneyStoryJson(TOPIC)).thenReturn(
                "{\"schemaVersion\":1,\"ending\":{\"optionalClues\":"
                        + "[\"clue.voice\",\"clue.photo_story\",\"clue.never_seen\"]}}");

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        assertEquals(1, data.get("found"));
        assertEquals(3, data.get("total"));
    }

    /**
     * R11-A:found / total 读建局冻结的主题剧情;开局后改列不穿透进行中的局。
     *
     * <p>负控:optionalClueProgress 改回直接读 readMapper.selectJourneyStoryJson(topicId),
     * 本用例立刻红(total 会变成 3)。
     */
    @Test
    void casebookReadsFrozenJourneyStoryAfterTopicEdit() throws Exception {
        givenState("{\"clue.voice\":1}", "[]");
        String frozenStory = "{\"schemaVersion\":1,\"ending\":{\"optionalClues\":"
                + "[\"clue.voice\",\"clue.photo_story\"]}}";
        session.setConfigSnapshotJson("{\"journeyStory\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(frozenStory) + "}");
        when(readMapper.selectJourneyStoryJson(TOPIC)).thenReturn(
                "{\"schemaVersion\":1,\"ending\":{\"optionalClues\":"
                        + "[\"clue.voice\",\"clue.photo_story\",\"clue.after_edit\"]}}");

        Map<String, Object> data = service.casebook(MEMBER, TOPIC);

        assertEquals(1, data.get("found"));
        assertEquals(2, data.get("total"), "total 读冻结剧情,不是改后的 3");
    }

    @Test
    void casebookPropagatesJourneyStoryReadFailure() {
        when(readMapper.selectJourneyStoryJson(TOPIC))
                .thenThrow(new BadSqlGrammarException("select", "journey_story_json", new SQLException("unknown column")));

        assertThrows(BadSqlGrammarException.class, () -> service.casebook(MEMBER, TOPIC),
                "真实库错误不能被当成未配置吞掉");
    }

    // ==================== 背包 ====================

    @SuppressWarnings("unchecked")
    @Test
    void backpackStatusMatchesExistingRecords() {
        CmsMemberTemplate tpl7 = new CmsMemberTemplate();
        tpl7.setId(7L);
        tpl7.setCouponId(7L);
        CmsMemberTemplate tpl8 = new CmsMemberTemplate();
        tpl8.setId(8L);
        tpl8.setCouponId(8L);
        tpl8.setMedalName("金印");
        // R22-A1:交付文案的真源是本局冻结的故事流叙事,不再是模板 advancedConfigJson
        session.setConfigSnapshotJson("{\"nodeJourney\":{\"102\":{\"deliver\":{\"reward\":\"阿蓉的印章\"}}}}");
        when(templateMapper.selectCmsMemberTemplateById(7L)).thenReturn(tpl7);
        when(templateMapper.selectCmsMemberTemplateById(8L)).thenReturn(tpl8);
        when(couponService.selectSmsCouponById(7L)).thenReturn(null);

        List<CmsTopicNode> nodes = Arrays.asList(
                node(N1, 1L, 7L, null), node(N2, 2L, 8L, null), node(N3, 3L, null, null),
                node(N4, 4L, 7L, null), node(N5, 5L, 8L, null));
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(new ArrayList<CmsTopicNode>(nodes));

        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class))).thenReturn(Arrays.asList(
                progress(N1, new Date(1_500L)), progress(N4, new Date(1_600L)), progress(N5, new Date(1_700L))));
        SmsCouponHistory granted = new SmsCouponHistory();
        granted.setCouponId(7L);
        when(couponHistoryMapper.selectSmsCouponHistoryList(any(SmsCouponHistory.class)))
                .thenReturn(new ArrayList<SmsCouponHistory>(Collections.singletonList(granted)));

        Map<String, Object> data = service.backpack(MEMBER, TOPIC);

        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(5, items.size());
        assertEquals("claimed", items.get(0).get("status"), "券已发放=claimed");
        assertEquals("pending", items.get(1).get("status"), "配了奖励但没完成=pending");
        assertFalse(items.get(1).containsKey("claimableAt"), "没完成不知道何时可领");
        assertEquals("none", items.get(2).get("status"), "没配奖励=none");
        assertNull(items.get(2).get("reward"));
        assertEquals("claimed", items.get(3).get("status"), "完成且券到手=claimed");
        assertEquals("pending", items.get(4).get("status"), "完成但券没到手=pending");
        assertEquals(Long.valueOf(1_700L), items.get(4).get("claimableAt"), "可领时刻=完成时刻");
        assertTrue(String.valueOf(items.get(1).get("reward")).contains("阿蓉的印章"),
                "交付文案读本局冻结故事流");
        assertTrue(String.valueOf(items.get(0).get("reward")).contains("到店券"), "没配交付文案时按券拼");
    }

    /**
     * R22-SEC S19:未上架主题且本人无局时,背包不得被按 id 读出(奖励与领取事实是游玩数据)。
     *
     * <p>负控:去掉 requireReadableBackpack,本用例立刻红。
     */
    @Test
    void backpackRequiresOnShelfTopicOrOwnSession() {
        topic.setStatus(0);
        topic.setUserStatus(0);
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(null));

        ServiceException error = assertThrows(ServiceException.class, () -> service.backpack(MEMBER, TOPIC));

        assertTrue(error.getMessage().contains("不存在"), error.getMessage());
    }

    /** 在架主题没有本局也照常可读:背包是货架的一部分。 */
    @Test
    void backpackReadableForOnShelfTopicWithoutSession() {
        topic.setStatus(1);
        topic.setUserStatus(1);
        when(routeMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(null));

        Map<String, Object> data = service.backpack(MEMBER, TOPIC);

        assertNotNull(data.get("nodes"));
    }

    /**
     * R22-A1:模板里的老叙事段已废弃 —— 本局冻结的故事流没有 deliver 时不再回退读模板,
     * 奖励文案回落券 / 勋章拼接,不把模板里的旧文案当成真源下发。
     *
     * <p>负控:deliverText 改回读 template.getAdvancedConfigJson(),本用例立刻红。
     */
    @SuppressWarnings("unchecked")
    @Test
    void backpackIgnoresTemplateDeliverWhenFrozenStoryFlowHasNone() {
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(8L);
        tpl.setCouponId(8L);
        tpl.setMedalName("金印");
        tpl.setAdvancedConfigJson("{\"deliver\":{\"reward\":\"模板的旧交付\"}}");
        when(templateMapper.selectCmsMemberTemplateById(8L)).thenReturn(tpl);
        session.setConfigSnapshotJson("{\"nodeJourney\":{\"102\":{\"deliver\":null}}}");
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(new ArrayList<CmsTopicNode>(Collections.singletonList(node(N2, 1L, 8L, null))));

        Map<String, Object> data = service.backpack(MEMBER, TOPIC);

        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("nodes");
        assertEquals("pending", items.get(0).get("status"), "勋章配置仍在,不算 none");
        assertFalse(String.valueOf(items.get(0).get("reward")).contains("模板的旧交付"),
                "冻结故事流没有 deliver 就不回退模板");
        assertTrue(String.valueOf(items.get(0).get("reward")).contains("勋章:金印"));
    }

    // ==================== 构造 ====================

    private void givenState(String variables, String tags) {
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":" + variables
                + ",\"tags\":" + tags + ",\"completedNodeIds\":[]}");
    }

    private static ThemeStateKey key(String stateKey, String type, String label, Integer initial) {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey(stateKey);
        key.setKeyType(type);
        key.setLabel(label);
        key.setInitialValue(initial);
        return key;
    }

    private static CmsTopicNode node(Long id, Long sortId, Long templateId, Long merchantMemberId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC);
        node.setStatus(1L);
        node.setSortId(sortId);
        node.setTemplateId(templateId);
        node.setMerchantMemberId(merchantMemberId);
        node.setName("节点" + id);
        return node;
    }

    private static PlayerNodeProgress progress(Long nodeId, Date at) {
        PlayerNodeProgress row = new PlayerNodeProgress();
        row.setNodeId(nodeId);
        row.setTopicId(TOPIC);
        row.setStatus(1);
        row.setCheckinTime(at);
        return row;
    }

    private static NpcProfileVO npc(Long id, String name) {
        NpcProfileVO vo = new NpcProfileVO();
        vo.setProfileId(id);
        vo.setName(name);
        return vo;
    }

    private static TopicRouteAction action(Long id, String actionId, String outcomeCode, Long nodeId,
                                           String responseJson, long createdAt) {
        TopicRouteAction action = new TopicRouteAction();
        action.setId(id);
        action.setSessionId(SESSION);
        action.setMemberId(MEMBER);
        action.setNodeId(nodeId);
        action.setActionId(actionId);
        action.setOutcomeCode(outcomeCode);
        action.setResponseJson(responseJson);
        action.setCreateTime(new Date(createdAt));
        return action;
    }

    private static String receiptJson(Integer hp, Integer luck, Boolean success, String text) {
        return "{\"hp\":" + hp + ",\"luck\":" + luck + ",\"success\":" + success
                + ",\"text\":" + (text == null ? "null" : "\"" + text + "\"") + "}";
    }

    private static String rules(int hpInit, int luckInit) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,"
                + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":" + hpInit + "},"
                + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":" + luckInit + "},"
                + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
                + "\"hard\":{\"dc\":16,\"damage\":3}},"
                + "\"recovery\":{\"exhaustRestHp\":5},"
                + "\"fumbleExtraDamage\":1,\"modCap\":6}";
    }
}
