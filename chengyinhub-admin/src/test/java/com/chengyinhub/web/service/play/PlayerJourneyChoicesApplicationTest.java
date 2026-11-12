package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.vo.PlayerJourneyChoicesVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4 真实支持的选择：只按已发布 gameplayRuntime 投影，PLAYER 不展示共享世界；
 * TEAM 要求队伍但队伍模式关闭时明确不可用；能力声明未发布时为 null。
 */
class PlayerJourneyChoicesApplicationTest {

    private static final Long VIEWER = 11L;
    private static final Long TOPIC = 7L;
    private static final Long ACTIVITY = 900L;

    private PlayerJourneyChoicesApplication application;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private PlayCompletionFacts completionFacts;
    private IPlayTeamService teamService;
    private TopicRouteSessionMapper routeMapper;

    @BeforeEach
    void setUp() {
        routeMapper = mock(TopicRouteSessionMapper.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        completionFacts = mock(PlayCompletionFacts.class);
        teamService = mock(IPlayTeamService.class);
        application = new PlayerJourneyChoicesApplication();
        ReflectionTestUtils.setField(application, "topicRouteSessionMapper", routeMapper);
        ReflectionTestUtils.setField(application, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(application, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(application, "playCompletionFacts", completionFacts);
        ReflectionTestUtils.setField(application, "playTeamService", teamService);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(completionFacts.isRegistered(VIEWER, ACTIVITY)).thenReturn(true);
        PlayTeam team = new PlayTeam();
        team.setId(44L);
        team.setOwnerType(2);
        team.setOwnerId(ACTIVITY);
        team.setJoinedCount(3);
        team.setMaxMembers(6);
        when(teamService.my(VIEWER)).thenReturn(List.of(team));
    }

    @Test
    void playerRuntimeNeverAdvertisesSharedWorld() {
        CmsTopic topic = topic();
        topic.setPublishedThemeRuntimeConfigJson(
                "{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\"}}");
        topic.setPublishedRouteGraphJson(
                "{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\","
                        + "\"requiredCapabilities\":[{\"key\":\"answer.text\",\"minVersion\":2}]}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals("PLAYER", vo.getRuntimeScope());
        assertEquals("LINEAR", vo.getRouteMode());
        assertEquals(1, vo.getChoices().size());
        assertEquals(PlayerJourneyChoicesVO.Choice.INDEPENDENT, vo.getChoices().get(0).getKey());
        assertEquals("PER_MEMBER", vo.getChoices().get(0).getProgressScope());
        assertFalse(vo.getChoices().get(0).getRequiresTeam());
        assertTrue(vo.getChoices().get(0).getAvailable());
        assertNull(vo.getChoices().get(0).getTeamCapacity());
        assertNotNull(vo.getRequiredCapabilities());
        assertEquals("answer.text", vo.getRequiredCapabilities().get(0).getKey());
        assertEquals(Integer.valueOf(2), vo.getRequiredCapabilities().get(0).getMinVersion());
    }

    void teamSharedWorldAdvertisesOnlySharedScopeWithCompetitionAndCapacity() {
        CmsTopic topic = topic();
        topic.setTeamMode(1);
        topic.setPublishedThemeRuntimeConfigJson(
                "{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"WORLD_SHARED\","
                        + "\"acts\":[{\"actKey\":\"a1\",\"completionRule\":{\"type\":\"ALL\"},"
                        + "\"questKeys\":[\"q1\",\"q2\",\"q3\",\"q4\"]}],\"quests\":["
                        + "{\"questKey\":\"q1\",\"actKey\":\"a1\",\"nodeId\":1,\"exclusiveGroup\":\"g1\"},"
                        + "{\"questKey\":\"q2\",\"actKey\":\"a1\",\"nodeId\":2,\"exclusiveGroup\":\"g1\"},"
                        + "{\"questKey\":\"q3\",\"actKey\":\"a1\",\"nodeId\":3,\"exclusiveGroup\":\"g2\"},"
                        + "{\"questKey\":\"q4\",\"actKey\":\"a1\",\"nodeId\":4,\"extraSlot\":\"e1\",\"extraOnly\":true}]}}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals("TEAM", vo.getRuntimeScope());
        assertEquals("WORLD_SHARED", vo.getWorldMode());
        assertEquals(1, vo.getChoices().size());
        PlayerJourneyChoicesVO.Choice shared = vo.getChoices().get(0);
        assertEquals(PlayerJourneyChoicesVO.Choice.SHARED, shared.getKey());
        assertEquals("SHARED_WORLD", shared.getProgressScope());
        assertEquals("PER_MEMBER", shared.getHintScope(), "提示事实按会员与局记录，仍归本人");
        assertTrue(shared.getRequiresTeam());
        assertTrue(shared.getAvailable());
        assertTrue(shared.getCompetition());
        assertEquals(Integer.valueOf(2), shared.getExclusiveQuestSlots());
        assertEquals(Integer.valueOf(1), shared.getAdditionalQuestSlots());
        assertEquals(Long.valueOf(44L), shared.getTeamCapacity().getTeamId());
        assertEquals(Integer.valueOf(6), shared.getTeamCapacity().getMaxMembers());
        assertNull(vo.getRequiredCapabilities(), "未声明能力时为 null，不是空数组");
    }

    @Test
    void teamModeDisabledMarksSharedChoiceUnavailable() {
        CmsTopic topic = topic();
        topic.setTeamMode(0);
        topic.setPublishedThemeRuntimeConfigJson(
                "{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"TEAM_INDEPENDENT\"}}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(1, vo.getChoices().size());
        assertEquals(PlayerJourneyChoicesVO.Choice.SHARED, vo.getChoices().get(0).getKey());
        assertEquals("PER_TEAM", vo.getChoices().get(0).getProgressScope());
        assertFalse(vo.getChoices().get(0).getAvailable());
        assertEquals("TEAM_MODE_DISABLED", vo.getChoices().get(0).getUnavailableReason());
        assertFalse(vo.getChoices().get(0).getCompetition());
    }

    @Test
    void unpublishedTopicDeniedForNonParticipant() {
        CmsTopic topic = topic();
        topic.setStatus(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(completionFacts.isRegistered(VIEWER, ACTIVITY)).thenReturn(false);

        assertFalse(application.choices(VIEWER, ACTIVITY, TOPIC).isSuccess());
    }

    @Test
    void topicActivityMismatchRejected() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic());
        assertFalse(application.choices(VIEWER, ACTIVITY, 55L).isSuccess());
    }
    @Test
    void startedRunKeepsFrozenConfigEvenWhenPublishedChanged() {
        CmsTopic publishedTopic = topic();
        publishedTopic.setPublishedThemeRuntimeConfigJson(
                "{\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\"}}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(publishedTopic);
        TopicRouteSession running = new TopicRouteSession();
        running.setStatus(TopicRouteSession.STATUS_RUNNING);
        running.setConfigSnapshotJson(
                "{\"themeConfig\":{\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\","
                        + "\"worldMode\":\"TEAM_INDEPENDENT\"}}}");
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(running);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals("TEAM", vo.getRuntimeScope(), "进行中的局按冻结配置，不被新发布改写");
        assertEquals(PlayerJourneyChoicesVO.Choice.SHARED, vo.getChoices().get(0).getKey());
    }

    @Test
    void runningRunWithMissingFrozenSnapshotIsUnavailableNotPublishedFallback() {
        CmsTopic publishedTopic = topic();
        publishedTopic.setTeamMode(1);
        publishedTopic.setPublishedThemeRuntimeConfigJson(
                "{\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"WORLD_SHARED\"}}");
        publishedTopic.setPublishedRouteGraphJson(
                "{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\","
                        + "\"requiredCapabilities\":[{\"key\":\"answer.text\"}]}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(publishedTopic);
        TopicRouteSession running = new TopicRouteSession();
        running.setStatus(TopicRouteSession.STATUS_RUNNING);
        running.setConfigSnapshotJson(null);
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(running);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyChoicesVO.CONFIG_UNAVAILABLE, vo.getConfigState());
        assertEquals(PlayerJourneyChoicesVO.CONFIG_MISSING_RUN_SNAPSHOT, vo.getConfigUnavailableReason());
        assertNull(vo.getRuntimeScope(), "缺冻结配置就不可用，不拿当前发布的 TEAM 顶替");
        assertNull(vo.getWorldMode());
        assertNull(vo.getRouteMode());
        assertNull(vo.getRequiredCapabilities());
        assertTrue(vo.getChoices().isEmpty(), "不可用时不展示任何可进入的模式");
        assertTrue(vo.getTeamModeEnabled(), "team_mode 仍是当前列值");
        assertEquals(PlayerJourneyChoicesVO.TEAM_MODE_SOURCE_CURRENT_TOPIC, vo.getTeamModeSource());
    }

    @Test
    void runningRunWithUnusableFrozenSnapshotIsAlsoUnavailable() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic());
        TopicRouteSession running = new TopicRouteSession();
        running.setStatus(TopicRouteSession.STATUS_RUNNING);
        running.setConfigSnapshotJson("not-json");
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(running);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyChoicesVO.CONFIG_UNAVAILABLE, vo.getConfigState());
        assertTrue(vo.getChoices().isEmpty());
    }

    @Test
    void frozenRunRouteModeAndCapabilitiesComeFromTheChosenSnapshot() {
        CmsTopic publishedTopic = topic();
        publishedTopic.setTeamMode(1);
        publishedTopic.setPublishedThemeRuntimeConfigJson(
                "{\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\"}}");
        publishedTopic.setPublishedRouteGraphJson(
                "{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\","
                        + "\"requiredCapabilities\":[{\"key\":\"audio.tap\",\"minVersion\":9}]}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(publishedTopic);
        TopicRouteSession running = new TopicRouteSession();
        running.setStatus(TopicRouteSession.STATUS_RUNNING);
        running.setConfigSnapshotJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\","
                + "\"requiredCapabilities\":[{\"key\":\"answer.text\",\"minVersion\":2}],"
                + "\"themeConfig\":{\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\","
                + "\"worldMode\":\"TEAM_INDEPENDENT\"}}}");
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(running);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyChoicesVO.CONFIG_FROZEN_RUN, vo.getConfigState());
        assertEquals("TEAM", vo.getRuntimeScope());
        assertEquals("TEAM_INDEPENDENT", vo.getWorldMode());
        assertEquals("LINEAR", vo.getRouteMode(), "路线模式取本局冻结，不混新发布的 BRANCH_GRAPH");
        assertEquals(1, vo.getRequiredCapabilities().size());
        assertEquals("answer.text", vo.getRequiredCapabilities().get(0).getKey(),
                "端能力同源取冻结修订，不混新发布的 audio.tap");
        assertEquals(Integer.valueOf(2), vo.getRequiredCapabilities().get(0).getMinVersion());
        assertEquals(PlayerJourneyChoicesVO.TEAM_MODE_SOURCE_CURRENT_TOPIC, vo.getTeamModeSource());
    }

    @Test
    void withoutRunningRunChoicesUsePublishedRevisionAndSaySo() {
        CmsTopic publishedTopic = topic();
        publishedTopic.setPublishedThemeRuntimeConfigJson(
                "{\"schemaVersion\":1,\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\"}}");
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(publishedTopic);
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(null);

        PlayerJourneyChoicesVO vo = (PlayerJourneyChoicesVO) application
                .choices(VIEWER, ACTIVITY, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyChoicesVO.CONFIG_PUBLISHED, vo.getConfigState());
        assertEquals("PLAYER", vo.getRuntimeScope());
        assertEquals(PlayerJourneyChoicesVO.Choice.INDEPENDENT, vo.getChoices().get(0).getKey());
    }

    private static CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setStatus(1);
        topic.setDelFlag(0);
        return topic;
    }
}
