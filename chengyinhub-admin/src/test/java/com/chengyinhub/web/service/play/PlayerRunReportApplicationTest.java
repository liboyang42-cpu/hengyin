package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.PlayerRunReportVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.business.mapper.PlayerRunSessionMapper;
import com.chengyinhub.business.mapper.ScopedLeaderboardMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayJourneyReadService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P7 战报只读投影：跨人越权、进行中/结束/辅助完成、无权威分数、无名次与只读事实。
 */
class PlayerRunReportApplicationTest {

    private static final Long VIEWER = 11L;
    private static final Long TOPIC = 7L;

    private PlayerRunReportApplication application;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private ICmsTopicNodeService nodeService;
    private IPlayerNodeProgressService progressService;
    private TopicRouteSessionMapper routeMapper;
    private PlayerRunSessionMapper runSessionMapper;
    private ScopedLeaderboardMapper leaderboardMapper;
    private PlayCompletionFacts completionFacts;
    private PlayNodeContext nodeContext;
    private ICmsMemberTemplateService templateService;
    private IPlayJourneyReadService journeyReadService;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        routeMapper = mock(TopicRouteSessionMapper.class);
        runSessionMapper = mock(PlayerRunSessionMapper.class);
        leaderboardMapper = mock(ScopedLeaderboardMapper.class);
        completionFacts = mock(PlayCompletionFacts.class);
        nodeContext = mock(PlayNodeContext.class);
        templateService = mock(ICmsMemberTemplateService.class);
        journeyReadService = mock(IPlayJourneyReadService.class);

        application = new PlayerRunReportApplication();
        ReflectionTestUtils.setField(application, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(application, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(application, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(application, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(application, "topicRouteSessionMapper", routeMapper);
        ReflectionTestUtils.setField(application, "playerRunSessionMapper", runSessionMapper);
        ReflectionTestUtils.setField(application, "scopedLeaderboardMapper", leaderboardMapper);
        ReflectionTestUtils.setField(application, "playCompletionFacts", completionFacts);
        ReflectionTestUtils.setField(application, "playNodeContext", nodeContext);
        ReflectionTestUtils.setField(application, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(application, "playJourneyReadService", journeyReadService);

        CmsTopic topic = topic(TOPIC);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(completionFacts.topicHistoryBlocked(any())).thenReturn(false);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(List.of(node(1L), node(2L)));
        when(nodeContext.resolveMode(anyLong(), anyLong())).thenReturn(PlayMode.LINEAR_ORIENTEERING);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setValidationMethod(1);
        when(templateService.selectCmsMemberTemplateById(anyLong())).thenReturn(template);
        when(journeyReadService.backpack(anyLong(), anyLong(), any())).thenReturn(Map.of("nodes", List.of()));
    }

    @Test
    void deniesMemberWithNoParticipationFacts() {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of());
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(null);
        when(completionFacts.hasSelfPlayRegistration(VIEWER, TOPIC)).thenReturn(false);

        AjaxResult result = application.report(VIEWER, null, TOPIC);

        assertFalse(result.isSuccess(), "无参与事实不得读他人局");
        verify(leaderboardMapper, never()).selectScopedMemberRow(any(), any(), any(), any(), any(), anyLong());
        verify(runSessionMapper, never()).selectScope(anyLong(), anyLong(), any());
    }

    @Test
    void completedScoredRunIsLegitimateWithNetScoreAndRank() {
        PlayerNodeProgress first = progress(1L, 100, "SOLVED");
        first.setPlayInstanceId("run-a");
        PlayerNodeProgress second = progress(2L, 70, "SOLVED");
        second.setPlayInstanceId("run-a");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(first, second));
        TopicRouteSession session = new TopicRouteSession();
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setId(77L);
        session.setPlayInstanceId("run-a");
        session.setConfigSnapshotJson("{\"legacyRun\":true}");
        session.setCompletedAt(new Date(1_700_000_000_000L));
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(session);
        when(completionFacts.isTopicCompleted(VIEWER, 0L, TOPIC, 2, 2)).thenReturn(true);
        Map<String, Object> elapsed = new LinkedHashMap<>();
        elapsed.put("runState", "PAUSED");
        elapsed.put("elapsedSeconds", 123);
        when(runSessionMapper.selectScope(VIEWER, 0L, TOPIC)).thenReturn(elapsed);
        ScopedLeaderboardEntryVO me = new ScopedLeaderboardEntryVO();
        me.setScore(2L);
        when(leaderboardMapper.selectScopedMemberRow("quest", String.valueOf(TOPIC), TOPIC, null, null, VIEWER))
                .thenReturn(me);
        when(leaderboardMapper.selectScopedMemberRank("quest", String.valueOf(TOPIC), TOPIC, null, null, VIEWER))
                .thenReturn(3);

        AjaxResult result = application.report(VIEWER, null, TOPIC);

        assertTrue(result.isSuccess());
        PlayerRunReportVO vo = (PlayerRunReportVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(PlayerRunReportVO.RUN_COMPLETED, vo.getRunState());
        assertEquals(PlayerRunReportVO.COMPLETION_LEGITIMATE, vo.getCompletionKind());
        assertTrue(vo.getLegitimate());
        assertEquals(PlayerRunReportVO.SCOPE_RUN, vo.getScopeKind());
        assertEquals(Long.valueOf(77L), vo.getRunId());
        assertEquals("run-a", vo.getPlayInstanceId());
        assertEquals("SELF_PLAY", vo.getRunKind());
        assertEquals(2, vo.getCompletedNodes().intValue());
        assertEquals(2, vo.getTotalNodes().intValue());
        assertEquals(170, vo.getScore().getScore().intValue(), "100 + 70 的真实持久化分数");
        assertEquals(30, vo.getScore().getPenalties().intValue(), "100-100 与 100-70 的降档合计");
        assertEquals(PlayerRunReportVO.SCORE_SCORED, vo.getScore().getState());
        assertEquals(123, vo.getElapsedSeconds().intValue());
        assertEquals("PLAYER_RUN_SESSION_PAUSED", vo.getElapsedSource());
        assertEquals(Integer.valueOf(3), vo.getRank().getRank());
        assertEquals("COMPLETED_NODES", vo.getRank().getScoreBasis());
        assertEquals("COMPLETED_NODES_COUNT_NOT_NET_SCORE", vo.getRank().getBasisNote());
        assertEquals("quest", vo.getRank().getScope());
        assertEquals(TOPIC, vo.getRank().getScopeId());
        assertEquals("all", vo.getRank().getPeriod());
        assertTrue(vo.getReadOnly());
        verify(progressService, never()).insertPlayerNodeProgress(any());
        verify(routeMapper, never()).insertSession(any());
        verify(runSessionMapper, never()).upsertPaused(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(runSessionMapper, never()).markEnded(anyLong(), anyLong(), any(), anyLong());
        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).selectPlayerNodeProgressList(probe.capture());
        assertEquals("run-a", probe.getValue().getPlayInstanceId(), "读取绑定到本局既有 play_instance_id");
    }

    @Test
    void twoRunsOnSameTopicDoNotMerge() {
        PlayerNodeProgress current = progress(1L, 100, "SOLVED");
        current.setPlayInstanceId("run-a");
        PlayerNodeProgress previous = progress(2L, 40, "SOLVED");
        previous.setPlayInstanceId("run-b");
        // 读侧即使误返回旧局行，本局战报也只认当前 play_instance_id。
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(current, previous));
        TopicRouteSession session = new TopicRouteSession();
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setPlayInstanceId("run-a");
        session.setConfigSnapshotJson("{\"legacyRun\":true}");
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(session);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(1, vo.getCompletedNodes().intValue(), "旧局完成节点不得并入本局");
        assertEquals(1, vo.getNodes().size());
        assertEquals(Long.valueOf(1L), vo.getNodes().get(0).getNodeId());
        assertEquals(100, vo.getScore().getScore().intValue());
        assertEquals("run-a", vo.getPlayInstanceId());
    }

    @Test
    void frozenPublicationNameSurvivesLaterPublisherEdits() {
        PlayerNodeProgress row = progress(1L, 100, "SOLVED");
        row.setPlayInstanceId("run-a");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(row));
        TopicRouteSession session = new TopicRouteSession();
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setPlayInstanceId("run-a");
        session.setConfigSnapshotJson("{\"routeStations\":[{\"nodeId\":1,\"nodeName\":\"冻结站\"}],"
                + "\"nodeAnswers\":{\"1\":{\"contentSnapshotVersion\":1,"
                + "\"nodePresentation\":{\"id\":1,\"topicId\":7,\"name\":\"冻结站\"}}}}");
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(session);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals("冻结站", vo.getNodes().get(0).getName(),
                "作者改稿后，本局战报仍显示开局冻结的节点名");
    }

    @Test
    void inProgressUnscoredRunReturnsNotScoredAndNoRank() {
        PlayerNodeProgress revealed = progress(1L, null, "REVEALED");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(revealed));
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(null);
        when(completionFacts.isTopicCompleted(anyLong(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(false);
        when(runSessionMapper.selectScope(VIEWER, 0L, TOPIC)).thenReturn(null);
        when(leaderboardMapper.selectScopedMemberRow(any(), any(), any(), any(), any(), anyLong())).thenReturn(null);

        AjaxResult result = application.report(VIEWER, null, TOPIC);

        PlayerRunReportVO vo = (PlayerRunReportVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(PlayerRunReportVO.RUN_IN_PROGRESS, vo.getRunState());
        assertEquals(PlayerRunReportVO.COMPLETION_IN_PROGRESS, vo.getCompletionKind());
        assertEquals(PlayerRunReportVO.SCORE_NOT_SCORED, vo.getScore().getState());
        assertNull(vo.getScore().getScore(), "没有权威基准不得默认 300");
        assertNull(vo.getScore().getPenalties());
        assertEquals("NO_AUTHORITATIVE_BASE_SCORE", vo.getScore().getReason());
        assertNull(vo.getRank().getRank());
        assertFalse(vo.getRank().getQualified());
        assertNull(vo.getElapsedSeconds());
        assertNull(vo.getElapsedSource());
    }

    @Test
    void endedRunIsNotLegitimateCompletion() {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(1L, 100, "SOLVED")));
        TopicRouteSession ended = new TopicRouteSession();
        ended.setStatus(TopicRouteSession.STATUS_ENDED);
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(ended);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(PlayerRunReportVO.RUN_ENDED, vo.getRunState());
        assertEquals(PlayerRunReportVO.COMPLETION_ENDED, vo.getCompletionKind());
        assertFalse(vo.getLegitimate());
        assertNull(vo.getCompletedAt());
    }

    @Test
    void skippedCompletionIsAssistedNotLegitimate() {
        PlayerNodeProgress skipped = progress(1L, 0, "SKIPPED");
        skipped.setPlayInstanceId("run-a");
        PlayerNodeProgress solved = progress(2L, 100, "SOLVED");
        solved.setPlayInstanceId("run-a");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(skipped, solved));
        TopicRouteSession session = session(41L, "run-a", TopicRouteSession.STATUS_COMPLETED, "{\"legacyRun\":true}");
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(session);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(PlayerRunReportVO.RUN_COMPLETED, vo.getRunState());
        assertEquals(PlayerRunReportVO.COMPLETION_ASSISTED, vo.getCompletionKind());
        assertFalse(vo.getLegitimate());
        assertEquals(100, vo.getScore().getScore().intValue(), "跳过记 0，不伪造答对分");
        assertEquals(100, vo.getScore().getPenalties().intValue(), "跳过扣满本题基准");
    }

    @Test
    void frozenAnswerWithoutTemplateNeverBorrowsLivePenalty() {
        PlayerNodeProgress solved = progress(1L, 70, "SOLVED");
        solved.setPlayInstanceId("run-a");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(solved));
        // 现代快照：nodeAnswers 存在，但该节点冻结答案没有 templateId。
        TopicRouteSession session = session(41L, "run-a", TopicRouteSession.STATUS_COMPLETED,
                "{\"nodeAnswers\":{\"1\":{\"contentSnapshotVersion\":1}}}");
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(session);
        com.fasterxml.jackson.databind.node.ObjectNode answer =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("contentSnapshotVersion", 1);
        answer.putObject("nodePresentation").put("id", 1L).put("topicId", TOPIC);
        when(nodeContext.frozenAnswer(VIEWER, TOPIC, 0L, 1L)).thenReturn(answer);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC).get(AjaxResult.DATA_TAG);

        assertEquals(70, vo.getScore().getScore().intValue());
        assertEquals(0, vo.getScore().getPenalties().intValue(),
                "冻结答案缺模板字段时不得用实时模板推算扣分");
    }

    @Test
    void activityScopeUsesActivityTopicAndRejectsMismatch() {
        CmsActivity activity = new CmsActivity();
        activity.setId(900L);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(900L)).thenReturn(activity);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(progress(1L, 100, "SOLVED")));
        when(routeMapper.selectCurrentByOwner(900L, TOPIC, VIEWER)).thenReturn(null);
        when(completionFacts.isRegistered(VIEWER, 900L)).thenReturn(true);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, 900L, TOPIC).get(AjaxResult.DATA_TAG);
        assertEquals(Long.valueOf(900L), vo.getActivityId());
        assertEquals(TOPIC, vo.getTopicId());
        assertNotNull(vo.getScore());

        AjaxResult mismatch = application.report(VIEWER, 900L, 55L);
        assertFalse(mismatch.isSuccess(), "主题与活动不匹配必须拒绝");
    }

    // ------------------------------------------------------------------ 点名历史局 (runId)

    @Test
    void namedHistoricalRunIsReopenableAfterAnotherRunStarted() {
        PlayerNodeProgress older = progress(1L, 100, "SOLVED");
        older.setPlayInstanceId("replay:77");
        PlayerNodeProgress newer = progress(2L, 40, "SOLVED");
        newer.setPlayInstanceId("default");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(older, newer));
        // 该作用域最新一局已经是另一局；不带 runId 时只会读到它。
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, VIEWER)).thenReturn(session(88L, "default",
                TopicRouteSession.STATUS_RUNNING, "{\"routeStations\":[{\"nodeId\":2,\"nodeName\":\"新局站\"}]}"));
        when(routeMapper.selectAccessibleById(77L, VIEWER)).thenReturn(session(77L, "replay:77",
                TopicRouteSession.STATUS_COMPLETED, "{\"routeStations\":[{\"nodeId\":1,\"nodeName\":\"旧局站\"}]}"));
        when(completionFacts.isTopicCompleted(VIEWER, 0L, TOPIC, 1, 1)).thenReturn(true);

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC, 77L)
                .get(AjaxResult.DATA_TAG);

        assertEquals(PlayerRunReportVO.SCOPE_RUN, vo.getScopeKind());
        assertEquals(PlayerRunReportVO.PROGRESS_RUN_INSTANCE, vo.getProgressScope());
        assertEquals(Long.valueOf(77L), vo.getRunId());
        assertEquals("replay:77", vo.getPlayInstanceId());
        assertEquals(PlayerRunReportVO.RUN_COMPLETED, vo.getRunState());
        assertEquals(1, vo.getCompletedNodes().intValue(), "只投影这一局的完成行");
        assertEquals(1, vo.getTotalNodes().intValue(), "分母取该局冻结节点集");
        assertEquals("旧局站", vo.getNodes().get(0).getName(), "名字取该局冻结快照，不读当前发布");
        assertEquals(100, vo.getScore().getScore().intValue());
        // player_run_session 只有主题作用域，历史局没有自己的耗时事实。
        assertNull(vo.getElapsedSeconds());
        assertEquals(PlayerRunReportVO.ELAPSED_OTHER_RUN, vo.getElapsedSource());
        verify(runSessionMapper, never()).selectScope(anyLong(), anyLong(), any());
        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).selectPlayerNodeProgressList(probe.capture());
        assertEquals("replay:77", probe.getValue().getPlayInstanceId());
    }

    @Test
    void namedRunOfAnotherMemberIsRejectedWithoutReading() {
        when(routeMapper.selectAccessibleById(999L, VIEWER)).thenReturn(null);

        AjaxResult result = application.report(VIEWER, null, TOPIC, 999L);

        assertFalse(result.isSuccess());
        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("RUN_NOT_FOUND", result.get("reasonCode"));
        verify(progressService, never()).selectPlayerNodeProgressList(any());
        verify(leaderboardMapper, never()).selectScopedMemberRow(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void namedRunOutsideTheRequestedScopeIsRejected() {
        TopicRouteSession foreign = session(55L, "default", TopicRouteSession.STATUS_COMPLETED, "{}");
        foreign.setTopicId(TOPIC + 1);
        when(routeMapper.selectAccessibleById(55L, VIEWER)).thenReturn(foreign);

        AjaxResult result = application.report(VIEWER, null, TOPIC, 55L);

        assertFalse(result.isSuccess());
        assertEquals("RUN_SCOPE_MISMATCH", result.get("reasonCode"));
        verify(progressService, never()).selectPlayerNodeProgressList(any());
    }

    @Test
    void namedRunWithoutPlayInstanceNeverBorrowsTopicHistoryRows() {
        when(routeMapper.selectAccessibleById(66L, VIEWER)).thenReturn(
                session(66L, "  ", TopicRouteSession.STATUS_RUNNING, null));

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC, 66L)
                .get(AjaxResult.DATA_TAG);

        assertEquals(PlayerRunReportVO.PROGRESS_UNATTRIBUTED, vo.getProgressScope());
        assertEquals(Long.valueOf(66L), vo.getRunId());
        assertNull(vo.getPlayInstanceId());
        assertTrue(vo.getNodes().isEmpty(), "无法归因的局不得混入主题历史行");
        assertEquals(0, vo.getTotalNodes().intValue(), "分母未知就用 0，不猜实时节点数");
        assertEquals(PlayerRunReportVO.SCORE_NOT_SCORED, vo.getScore().getState());
        assertEquals("RUN_INSTANCE_MISSING", vo.getScore().getReason());
        verify(progressService, never()).selectPlayerNodeProgressList(any());
    }

    @Test
    void missingModernNodePresentationNeverBorrowsLiveNodeName() {
        PlayerNodeProgress row = progress(1L, 70, "SOLVED");
        row.setPlayInstanceId("run-a");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(row));
        // 现代快照：nodeAnswers 存在但该节点没有 nodePresentation；当前节点表仍有名字"站1"。
        when(routeMapper.selectAccessibleById(41L, VIEWER)).thenReturn(session(41L, "run-a",
                TopicRouteSession.STATUS_COMPLETED, "{\"nodeAnswers\":{\"1\":{\"contentSnapshotVersion\":1}}}"));

        PlayerRunReportVO vo = (PlayerRunReportVO) application.report(VIEWER, null, TOPIC, 41L)
                .get(AjaxResult.DATA_TAG);

        assertNull(vo.getNodes().get(0).getName(), "快照缺名就是 UNKNOWN，不拿改稿后的实时节点名");
        assertEquals(70, vo.getScore().getScore().intValue(), "分数仍取本局持久化事实");
        assertEquals(0, vo.getScore().getPenalties().intValue(), "缺模板字段不推算扣分");
    }

    private static TopicRouteSession session(Long id, String instance, int status, String snapshot) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(id);
        session.setActivityId(0L);
        session.setTopicId(TOPIC);
        session.setPlayInstanceId(instance);
        session.setStatus(status);
        session.setConfigSnapshotJson(snapshot);
        session.setCompletedAt(new Date(1_700_000_500_000L));
        return session;
    }

    private static CmsTopic topic(Long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setStatus(1);
        topic.setDelFlag(0);
        return topic;
    }

    private static CmsTopicNode node(Long id) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(TOPIC);
        node.setName("站" + id);
        node.setTemplateId(100L + id);
        return node;
    }

    private static PlayerNodeProgress progress(Long nodeId, Integer score, String mode) {
        PlayerNodeProgress row = new PlayerNodeProgress();
        row.setMemberId(VIEWER);
        row.setActivityId(0L);
        row.setTopicId(TOPIC);
        row.setNodeId(nodeId);
        row.setStatus(1);
        row.setPuzzleScore(score);
        row.setCompletionMode(mode);
        row.setCheckinTime(new Date(1_700_000_000_000L + nodeId * 1000L));
        return row;
    }
}
