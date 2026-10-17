package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R8-STORY 读接口求值:stage-end 全部完成才可取、changed 按条件;ending 完成才可取、
 * 多结局取第一个命中 / fallback、尾声三态、found/total。条件一律走 TopicRouteEngine.conditionsPass。
 */
class PlayJourneyStoryServiceImplTest {

    private static final Long MEMBER = 55L;
    private static final Long TOPIC = 700L;
    private static final Long CHAPTER = 11L;
    private static final Long NODE_DONE = 501L;
    private static final Long NODE_TODO = 502L;

    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":7,\"opener\":\"开场\"},"
            + "\"stages\":[{\"chapterId\":11,\"question\":\"问?\",\"end\":{\"changed\":["
            + "{\"when\":null,\"text\":\"无论如何\"},"
            + "{\"when\":{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1},\"text\":\"拿到残页\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"text\":\"匆匆离开\"}],\"open\":\"本章完\"}}],"
            + "\"ending\":{\"endings\":["
            + "{\"code\":\"correct\",\"title\":\"真相\",\"summary\":\"你找到了真相\",\"when\":["
            + "{\"op\":\"HAS_TAG\",\"value\":\"tag.brave\"}]},"
            + "{\"code\":\"quiet\",\"title\":\"沉默\",\"summary\":\"你选择沉默\",\"when\":["
            + "{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"}]},"
            + "{\"code\":\"default\",\"title\":\"余波\",\"summary\":\"故事就这样结束\",\"fallback\":true}],"
            + "\"epilogues\":["
            + "{\"npcId\":7,\"relationKey\":\"relation.arong\",\"ally\":\"阿戎与你并肩\",\"neutral\":\"阿戎点头致意\",\"guarded\":\"阿戎转身离开\"},"
            + "{\"npcId\":8,\"relationKey\":\"relation.xiaoman\",\"ally\":\"小满同行\",\"neutral\":\"小满驻足\",\"guarded\":\"小满侧身\"},"
            + "{\"npcId\":9,\"relationKey\":\"relation.laoli\",\"ally\":\"老李信任\",\"neutral\":\"老李沉默\",\"guarded\":\"老李防备\"}],"
            + "\"exhibits\":[{\"when\":null,\"text\":\"旧照片\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.brave\"},\"text\":\"锦旗\"}],"
            + "\"badges\":[{\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.brave\"}],\"title\":\"勇者\"},"
            + "{\"title\":\"过客\",\"fallback\":true}],"
            + "\"optionalClues\":[\"clue.voice\",\"clue.photo\"]}}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private TopicRouteSessionMapper sessionMapper;
    private ThemeStateKeyMapper keyMapper;
    private NpcProfileService npcProfileService;
    private IPlayEncounterService encounterService;
    private PlayJourneyStoryServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        sessionMapper = mock(TopicRouteSessionMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);
        // Resolve the exact current scope from each test's actual, mutable session fixture.
        when(sessionMapper.selectCurrentByOwner(0L, TOPIC, MEMBER)).thenAnswer(inv -> {
            List<TopicRouteSession> rows = sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC);
            TopicRouteSession current = rows != null && rows.size() == 1 ? rows.get(0) : null;
            return current != null && Long.valueOf(MEMBER).equals(current.getMemberId())
                    && Long.valueOf(TOPIC).equals(current.getTopicId())
                    && Long.valueOf(0L).equals(current.getActivityId()) ? current : null;
        });

        npcProfileService = mock(NpcProfileService.class);
        encounterService = mock(IPlayEncounterService.class);

        service = new PlayJourneyStoryServiceImpl(topicMapper, chapterMapper, nodeMapper, sessionMapper,
                new ThemeStateKeyService(keyMapper), npcProfileService);
        ReflectionTestUtils.setField(service, "playEncounterService", encounterService);
        when(encounterService.resolve(any(), any(), any(), any())).thenReturn(null);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setDelFlag(0);
        topic.setJourneyStoryJson(STORY);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);

        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(
                node(NODE_DONE), node(NODE_TODO), node(503L)));
        when(npcProfileService.resolveForNodeOrMerchant(eq(NODE_DONE), nullable(Long.class)))
                .thenReturn(profile(7L, "阿戎"));
        when(npcProfileService.resolveForNodeOrMerchant(eq(NODE_TODO), nullable(Long.class)))
                .thenReturn(profile(8L, "小满"));
        when(npcProfileService.resolveForNodeOrMerchant(eq(503L), nullable(Long.class)))
                .thenReturn(profile(9L, "老李"));
    }

    // ---------- stage-end ----------

    @Test
    void stageEndRejectsUntilEveryChapterNodeCompleted() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(TopicRouteSession.STATUS_RUNNING, state(null, null, longSet(NODE_DONE)))));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.stageEnd(MEMBER, TOPIC, CHAPTER));

        assertTrue(error.getMessage().contains("还有站点未完成"), error.getMessage());
    }

    @Test
    void stageEndReturnsChangedByConditionAfterAllNodesDone() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_RUNNING,
                state(vars("clue.page", 1, "sys.hp", 7, "sys.luck", 1), stringSet("tag.hurried"),
                        longSet(NODE_DONE, NODE_TODO, 503L)))));

        Map<String, Object> view = service.stageEnd(MEMBER, TOPIC, CHAPTER);

        assertEquals(Arrays.asList("无论如何", "拿到残页", "匆匆离开"), view.get("changed"));
        assertEquals("本章完", view.get("open"));
        assertEquals(7, view.get("hp"));
        assertEquals(1, view.get("luck"));
    }

    @Test
    void stageEndReceiptCarriesStateVersionAndRefreshedEncounter() throws Exception {
        TopicRouteSession session = session(TopicRouteSession.STATUS_RUNNING,
                state(vars("clue.page", 1, "sys.hp", 7, "sys.luck", 1), null,
                        longSet(NODE_DONE, NODE_TODO, 503L)));
        session.setCurrentNodeId(NODE_TODO);
        session.setVersion(4);
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session));
        Map<String, Object> encounter = new LinkedHashMap<String, Object>();
        encounter.put("nodeId", NODE_TODO);
        encounter.put("stateVersion", 4);
        when(encounterService.resolve(MEMBER, TOPIC, 0L, NODE_TODO)).thenReturn(encounter);

        Map<String, Object> view = service.stageEnd(MEMBER, TOPIC, CHAPTER);

        assertEquals(4, view.get("stateVersion"), "stage-end 回包自带本局 version");
        assertNotNull(view.get("encounter"), "stage-end 回包自带这一站刷新后的 Encounter");
    }

    @Test
    void stageEndLogsWarnWhenEncounterUnavailable() throws Exception {
        TopicRouteSession session = session(TopicRouteSession.STATUS_RUNNING,
                state(null, null, longSet(NODE_DONE, NODE_TODO, 503L)));
        session.setCurrentNodeId(NODE_TODO);
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session));
        when(encounterService.resolve(MEMBER, TOPIC, 0L, NODE_TODO))
                .thenThrow(new ServiceException("这一站没配玩法"));

        Logger logger = (Logger) LoggerFactory.getLogger(PlayJourneyStoryServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, Object> view = service.stageEnd(MEMBER, TOPIC, CHAPTER);

            assertNull(view.get("encounter"), "方向不变:缺省不追加");
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                            && String.valueOf(e.getFormattedMessage()).contains("Encounter")),
                    "缺 Encounter 必须留 WARN:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void stageEndOnlyDeliversConditionalTextsThatPass() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_RUNNING,
                state(null, null, longSet(NODE_DONE, NODE_TODO, 503L)))));

        Map<String, Object> view = service.stageEnd(MEMBER, TOPIC, CHAPTER);

        assertEquals(Collections.singletonList("无论如何"), view.get("changed"));
    }

    @Test
    void stageEndHpLuckFallBackToJourneyRulesInit() throws Exception {
        CmsTopic topic = topicMapper.selectCmsTopicById(TOPIC);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"max\":6},\"luck\":{\"max\":2}}");
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_RUNNING,
                state(null, null, longSet(NODE_DONE, NODE_TODO, 503L)))));

        Map<String, Object> view = service.stageEnd(MEMBER, TOPIC, CHAPTER);

        assertEquals(6, view.get("hp"));
        assertEquals(2, view.get("luck"));
    }

    /**
     * R22-C3:已注册但 label 为空的 tag 不再丢弃,回落键名去掉前缀的可读名(与案件册同一份解析),
     * 绝不把带前缀的完整键名当展示名下发给前端。
     *
     * <p>负控:把 displayLabel 的回落改回完整键名(tag.unlabeled),本用例断言立刻红。
     */
    @Test
    void stageEndCostsFallBackToReadableKeySuffixWhenLabelBlank() throws Exception {
        when(keyMapper.selectByTopicId(TOPIC)).thenReturn(Arrays.asList(
                key("tag.hurried", "赶时间"), key("tag.unlabeled", null), key("clue.page", "残页")));
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_RUNNING,
                state(null, new LinkedHashSet<String>(Arrays.asList("tag.hurried", "tag.unlabeled", "clue.page")),
                        longSet(NODE_DONE, NODE_TODO, 503L)))));

        Map<String, Object> view = service.stageEnd(MEMBER, TOPIC, CHAPTER);

        assertEquals(2, ((List<?>) view.get("costs")).size(), "label 为空不再丢弃");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) view.get("costs")).get(0);
        assertEquals("tag.hurried", first.get("tag"));
        assertEquals("赶时间", first.get("label"));
        Map<?, ?> second = (Map<?, ?>) ((List<?>) view.get("costs")).get(1);
        assertEquals("tag.unlabeled", second.get("tag"));
        assertEquals("unlabeled", second.get("label"), "回落键后缀,不带 tag. 前缀");
    }

    @Test
    void stageEndRejectsChapterWithoutStageConfig() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_RUNNING, state(null, null, longSet()))));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.stageEnd(MEMBER, TOPIC, 99L));

        assertTrue(error.getMessage().contains("本章节未配置阶段结算"), error.getMessage());
    }

    @Test
    void stageEndRejectsBeforeAnyRunStarted() {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(null));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.stageEnd(MEMBER, TOPIC, CHAPTER));

        assertTrue(error.getMessage().contains("本局尚未开始"), error.getMessage());
    }

    @Test
    void stageEndRejectsChapterThatDoesNotBelongToTopic() {
        CmsTopicChapter foreign = new CmsTopicChapter();
        foreign.setId(CHAPTER);
        foreign.setTopicId(999L);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(foreign);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.stageEnd(MEMBER, TOPIC, CHAPTER));

        assertTrue(error.getMessage().contains("章节不存在"), error.getMessage());
    }

    @Test
    void stageEndRejectsTopicWithoutStory() {
        topicMapper.selectCmsTopicById(TOPIC).setJourneyStoryJson(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.stageEnd(MEMBER, TOPIC, CHAPTER));

        assertTrue(error.getMessage().contains("未配置剧情"), error.getMessage());
    }

    /**
     * R11-A:stage-end / ending 读建局冻结的主题剧情,开局后改 journey_story_json 不穿透进行中的局。
     *
     * <p>负控:requireStory 改回直接读 topic.getJourneyStoryJson(),本用例立刻红
     * (会读到「改后的文案」与「改后结局」)。
     */
    @Test
    void stageEndAndEndingReadFrozenStoryAfterTopicStoryEdit() throws Exception {
        String edited = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":["
                + "{\"text\":\"改后的文案\"}],\"open\":\"改后本章完\"}}],"
                + "\"ending\":{\"endings\":[{\"code\":\"edited\",\"title\":\"改后结局\",\"fallback\":true}]}}";
        TopicRouteSession run = session(TopicRouteSession.STATUS_RUNNING,
                state(vars("clue.page", 1), null, longSet(NODE_DONE, NODE_TODO, 503L)));
        run.setConfigSnapshotJson("{\"journeyStory\":" + objectMapper.writeValueAsString(STORY) + "}");
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(run));
        topicMapper.selectCmsTopicById(TOPIC).setJourneyStoryJson(edited);

        Map<String, Object> stage = service.stageEnd(MEMBER, TOPIC, CHAPTER);

        assertEquals(Arrays.asList("无论如何", "拿到残页"), stage.get("changed"), "changed 读冻结剧情");
        assertEquals("本章完", stage.get("open"));

        run.setStatus(TopicRouteSession.STATUS_COMPLETED);
        Map<String, Object> end = service.ending(MEMBER, TOPIC);

        assertEquals("default", end.get("code"), "结局读冻结剧情");
        assertEquals("余波", end.get("title"));
    }

    // ---------- ending ----------

    @Test
    void endingRejectsUnfinishedRun() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_RUNNING, state(null, null, longSet()))));

        ServiceException error = assertThrows(ServiceException.class, () -> service.ending(MEMBER, TOPIC));

        assertTrue(error.getMessage().contains("本局尚未完成"), error.getMessage());
    }

    @Test
    void endingPicksFirstMatchingInConfiguredOrder() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED,
                state(null, new LinkedHashSet<String>(Arrays.asList("tag.brave", "tag.hurried")), longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        assertEquals("correct", view.get("code"), "两条都命中时取配置在前的那个");
        assertEquals("真相", view.get("title"));
        assertEquals("你找到了真相", view.get("summary"));
    }

    /**
     * 结局按「想通了哪个念头」分支 —— 思维阁到结局这条链路的端到端。
     *
     * <p>键名不是随便写的:TopicRouteRuntimeServiceImpl.syncThoughts 想通时写的就是
     * {@code thought.<key>.done}(见 TopicRouteRuntimeServiceImplTest:551 钉住的那条)。校验层放行
     * 只说明配得进去,这里才证明运行时真读得到。
     *
     * <p>负控:第二段把 tag 换成没有 .done 的 {@code thought.afternoon},必须落回兜底 ——
     * 否则「只要沾 thought 就算数」的实现也能让第一段绿。
     */
    /** 结局章(2026-09-24):命中的结局指向一章 ⇒ 把那一章按本局标签投影后带回;别的主题的章不带。 */
    @Test
    void endingCarriesEndingChapterBlocksProjectedForThisRun() throws Exception {
        String story = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"ending1\",\"title\":\"窗外\",\"chapterId\":88,\"fallback\":true}]}}";
        topicMapper.selectCmsTopicById(TOPIC).setJourneyStoryJson(story);
        com.chengyinhub.business.domain.CmsTopicChapter chapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        chapter.setId(88L);
        chapter.setTopicId(TOPIC);
        chapter.setName("窗外");
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"你推开了窗。\"},"
                + "{\"type\":\"text\",\"content\":\"画家才看得到这一句。\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.m_painter\"}}]");
        when(chapterMapper.selectCmsTopicChapterById(88L)).thenReturn(chapter);
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(null, new LinkedHashSet<String>(), longSet()))));

        Map<?, ?> view = (Map<?, ?>) service.ending(MEMBER, TOPIC).get("chapter");
        assertEquals(88L, view.get("id"));
        String blocks = String.valueOf(view.get("blocks"));
        assertTrue(blocks.contains("你推开了窗。"), blocks);
        assertFalse(blocks.contains("画家才看得到这一句"), "没有这个标签的玩家不下发那一句");

        chapter.setTopicId(TOPIC + 1);
        assertEquals(null, service.ending(MEMBER, TOPIC).get("chapter"), "别的主题的章不带");
    }

    @Test
    void endingBranchesOnThoughtDone() throws Exception {
        String story = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"awake\",\"title\":\"想通了\",\"summary\":\"那个下午终于讲清楚了\","
                + "\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"thought.afternoon.done\"}]},"
                + "{\"code\":\"default\",\"title\":\"余波\",\"fallback\":true}]}}";
        topicMapper.selectCmsTopicById(TOPIC).setJourneyStoryJson(story);

        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED,
                state(null, new LinkedHashSet<String>(
                        Arrays.asList("thought.afternoon.done")), longSet()))));
        Map<String, Object> awake = service.ending(MEMBER, TOPIC);
        assertEquals("awake", awake.get("code"), "想通了就该走这条结局");
        assertEquals("那个下午终于讲清楚了", awake.get("summary"));

        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED,
                state(null, new LinkedHashSet<String>(
                        Arrays.asList("thought.afternoon")), longSet()))));
        assertEquals("default", service.ending(MEMBER, TOPIC).get("code"),
                "念头只是得到了、还没想通 ⇒ 不该命中");
    }

    @Test
    void endingFallsBackWhenNothingMatches() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(null, null, longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        assertEquals("default", view.get("code"));
        assertEquals("余波", view.get("title"));
    }

    @Test
    void endingEpiloguesChooseLineByRelationTriState() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED,
                state(vars("relation.arong", 2, "relation.laoli", -3, "relation.xiaoman", 0), null, longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        List<?> epilogues = (List<?>) view.get("epilogues");
        Map<?, ?> ally = (Map<?, ?>) epilogues.get(0);
        Map<?, ?> neutral = (Map<?, ?>) epilogues.get(1);
        Map<?, ?> guarded = (Map<?, ?>) epilogues.get(2);
        assertEquals("ally", ally.get("state"));
        assertEquals("阿戎与你并肩", ally.get("line"));
        assertEquals("阿戎", ally.get("name"));
        assertEquals("neutral", neutral.get("state"));
        assertEquals("小满驻足", neutral.get("line"));
        assertEquals("guarded", guarded.get("state"));
        assertEquals("老李防备", guarded.get("line"));
    }

    @Test
    void endingEpilogueMissingRelationReadsNeutral() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(null, null, longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        Map<?, ?> first = (Map<?, ?>) ((List<?>) view.get("epilogues")).get(0);
        assertEquals("neutral", first.get("state"));
        assertEquals("阿戎点头致意", first.get("line"));
    }

    @Test
    void endingEpilogueIgnoresImplicitRelationByNpcId() throws Exception {
        // 旧隐含键 relation.{npcId} 不再参与:配了它也要按 relationKey 缺失算 neutral
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED,
                state(vars("relation.7", 9, "relation.8", 9, "relation.9", 9), null, longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        List<?> epilogues = (List<?>) view.get("epilogues");
        assertEquals("neutral", ((Map<?, ?>) epilogues.get(0)).get("state"));
        assertEquals("阿戎点头致意", ((Map<?, ?>) epilogues.get(0)).get("line"));
    }

    @Test
    void endingExhibitsAllPassingAndBadgeFirstMatchElseFallback() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED,
                state(null, stringSet("tag.brave"), longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        assertEquals(Arrays.asList("旧照片", "锦旗"), view.get("exhibits"));
        assertEquals("勇者", ((Map<?, ?>) view.get("badge")).get("title"));

        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(null, null, longSet()))));
        Map<String, Object> fallbackView = service.ending(MEMBER, TOPIC);
        assertEquals(Arrays.asList("旧照片"), fallbackView.get("exhibits"));
        assertEquals("过客", ((Map<?, ?>) fallbackView.get("badge")).get("title"));
    }

    @Test
    void endingFoundCountsHeldOptionalClues() throws Exception {
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(vars("clue.voice", 1), null, longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        assertEquals(1, view.get("found"));
        assertEquals(2, view.get("total"));

        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(vars("clue.voice", 1, "clue.photo", 3), null, longSet()))));
        Map<String, Object> fullView = service.ending(MEMBER, TOPIC);
        assertEquals(2, fullView.get("found"));
    }

    @Test
    void endingWithoutEndingConfigFailsWithBusinessError() {
        topicMapper.selectCmsTopicById(TOPIC)
                .setJourneyStoryJson("{\"schemaVersion\":1,\"call\":{\"npcId\":7}}");

        ServiceException error = assertThrows(ServiceException.class, () -> service.ending(MEMBER, TOPIC));

        assertTrue(error.getMessage().contains("未配置结局"), error.getMessage());
    }

    @Test
    void endingCostsFollowSameRegisteredLabelRule() throws Exception {
        when(keyMapper.selectByTopicId(TOPIC)).thenReturn(Collections.singletonList(key("tag.hurried", "赶时间")));
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(session(
                TopicRouteSession.STATUS_COMPLETED, state(null, stringSet("tag.hurried"), longSet()))));

        Map<String, Object> view = service.ending(MEMBER, TOPIC);

        assertEquals(1, ((List<?>) view.get("costs")).size());
    }

    @Test
    void endingHidesFrozenPrivateTags() throws Exception {
        when(keyMapper.selectByTopicId(TOPIC)).thenReturn(Arrays.asList(
                key("tag.hurried", "赶时间"), key("tag.secret", "秘密状态")));
        TopicRouteSession run = session(TopicRouteSession.STATUS_COMPLETED,
                state(null, stringSet("tag.hurried", "tag.secret"), longSet()));
        run.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                + "{\"key\":\"tag.secret\",\"label\":\"隐藏属性\",\"visible\":false}]}}");
        when(sessionMapper.selectMemberTopicSessions(MEMBER, TOPIC)).thenReturn(java.util.Collections.singletonList(run));
        assertEquals(1, ((List<?>) service.ending(MEMBER, TOPIC).get("costs")).size());
    }

    // ---------- 夹具 ----------

    private static CmsTopicNode node(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC);
        node.setStatus(1L);
        return node;
    }

    private static NpcProfileVO profile(Long id, String name) {
        NpcProfileVO vo = new NpcProfileVO();
        vo.setProfileId(id);
        vo.setName(name);
        return vo;
    }

    private static ThemeStateKey key(String stateKey, String label) {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey(stateKey);
        key.setLabel(label);
        return key;
    }

    private static Set<Long> longSet(Long... values) {
        return new LinkedHashSet<Long>(Arrays.asList(values));
    }

    private static Set<String> stringSet(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static Map<String, Number> vars(Object... pairs) {
        Map<String, Number> map = new LinkedHashMap<String, Number>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Number) pairs[i + 1]);
        }
        return map;
    }

    private TopicRouteSession session(int status, String stateJson) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(1L);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(0L);
        session.setStatus(status);
        session.setStateJson(stateJson);
        return session;
    }

    private String state(Map<String, Number> variables, Set<String> tags, Set<Long> completed) throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.setStatus("ACTIVE");
        if (variables != null) {
            state.getVariables().putAll(variables);
        }
        if (tags != null) {
            state.getTags().addAll(tags);
        }
        if (completed != null) {
            state.getCompletedNodeIds().addAll(completed);
        }
        return objectMapper.writeValueAsString(state);
    }

    @org.junit.jupiter.api.Test
    void 档案_回收建档答案照片与留言_选择题回写成选项文案() {
        com.chengyinhub.business.mapper.GameRuntimeSessionMapper games = mock(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class);
        ReflectionTestUtils.setField(service, "gameSessionMapper", games);
        com.chengyinhub.business.domain.GameRuntimeSession profile = new com.chengyinhub.business.domain.GameRuntimeSession();
        profile.setConfigSnapshotJson("{\"profile\":{\"questions\":[{\"key\":\"name\",\"label\":\"你叫什么\",\"kind\":\"text\"},"
                + "{\"key\":\"job\",\"label\":\"职业\",\"kind\":\"pick\",\"options\":[{\"key\":\"F\",\"label\":\"会计\"}]}]}}");
        profile.setStateJson("{\"profile\":{\"done\":true,\"answers\":{\"name\":\"阿猫\",\"job\":\"F\"}}}");
        com.chengyinhub.business.domain.GameRuntimeSession photo = new com.chengyinhub.business.domain.GameRuntimeSession();
        photo.setConfigSnapshotJson("{\"photoCheck\":{\"title\":\"拍下招牌\"}}");
        photo.setStateJson("{\"photoCheck\":{\"lastUrl\":\"https://x/a.jpg\"}}");
        com.chengyinhub.business.domain.GameRuntimeSession note = new com.chengyinhub.business.domain.GameRuntimeSession();
        note.setConfigSnapshotJson("{\"note\":{\"title\":\"留句话\"}}");
        note.setStateJson("{\"note\":{\"text\":\"天是灰的\",\"done\":true}}");
        when(games.selectPlayerSessionsByTopic(0L, TOPIC, 7L)).thenReturn(Arrays.asList(profile, photo, note));
        com.chengyinhub.business.domain.TopicRouteSession run = new com.chengyinhub.business.domain.TopicRouteSession();

        Map<String, Object> archive = service.archive(7L, TOPIC, run);

        assertEquals("[{label=你叫什么, value=阿猫}, {label=职业, value=会计}]", String.valueOf(archive.get("identity")));
        assertEquals("[{url=https://x/a.jpg, title=拍下招牌}]", String.valueOf(archive.get("photos")));
        assertEquals("[{title=留句话, text=天是灰的}]", String.valueOf(archive.get("notes")));
    }
}
