package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动排行榜:rank 按列表序 1..n;我在榜内取榜内行,榜外回退只数 status=1 的完成行(rank 留空)。
 */
class ApiPlayLeaderboardTest {

    private static final Long ME = 88L;
    private static final Long ACT = 500L;

    private ApiPlayProgressController controller;
    private IPlayerNodeProgressService progressService;
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiPlayProgressController());
        doReturn(ME).when(controller).getAppUserId();
        progressService = mock(IPlayerNodeProgressService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACT);
        activity.setTopicId(300L);
        when(activityService.selectCmsActivityById(ACT)).thenReturn(activity);
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(new CmsRegistration()));
    }

    private static LeaderboardItemVO row(Long memberId, long score) {
        LeaderboardItemVO r = new LeaderboardItemVO();
        r.setMemberId(memberId);
        r.setScore(score);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callOk(String activityId, String topicId) {
        AjaxResult res = controller.leaderboard(activityId, topicId);
        assertEquals(200, res.get(AjaxResult.CODE_TAG));
        return (Map<String, Object>) res.get(AjaxResult.DATA_TAG);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ranksAssignedInOrder_andMeFoundInList() {
        when(progressService.selectActivityLeaderboard(eq(ACT), isNull(), anyInt()))
                .thenReturn(Arrays.asList(row(7L, 4L), row(ME, 3L)));

        Map<String, Object> data = callOk("500", null);
        List<LeaderboardItemVO> list = (List<LeaderboardItemVO>) data.get("list");
        assertEquals(Integer.valueOf(1), list.get(0).getRank());
        assertEquals(Integer.valueOf(2), list.get(1).getRank());
        LeaderboardItemVO me = (LeaderboardItemVO) data.get("me");
        assertEquals(ME, me.getMemberId());
        assertEquals(Integer.valueOf(2), me.getRank(), "榜内的我直接用榜内名次");
    }

    @Test
    void offListMe_countsOnlyCompletedRows() {
        when(progressService.selectActivityLeaderboard(eq(ACT), isNull(), anyInt()))
                .thenReturn(Arrays.asList(row(7L, 4L)));
        PlayerNodeProgress done = new PlayerNodeProgress();
        done.setStatus(1);
        PlayerNodeProgress arrivedOnly = new PlayerNodeProgress();
        arrivedOnly.setStatus(0); // mode2 进店未完成,不许计入
        when(progressService.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Arrays.asList(done, arrivedOnly));

        Map<String, Object> data = callOk("500", null);
        LeaderboardItemVO me = (LeaderboardItemVO) data.get("me");
        assertEquals(Long.valueOf(1L), me.getScore(), "只数 status=1 完成行");
        assertNull(me.getRank(), "榜外不猜名次");
    }

    @Test
    void missingBothIds_rejected() {
        AjaxResult res = controller.leaderboard(null, null);
        assertNotNull(res);
        assertEquals(500, res.get(AjaxResult.CODE_TAG));
    }

    @Test
    void activityLeaderboard_requiresRegistration() {
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.emptyList());

        AjaxResult res = controller.leaderboard("500", null);

        assertEquals(500, res.get(AjaxResult.CODE_TAG));
        assertEquals("请先报名并完成支付", res.get(AjaxResult.MSG_TAG));
    }

    @Test
    void missingActivity_rejectedBeforeReadingLeaderboard() {
        when(activityService.selectCmsActivityById(ACT)).thenReturn(null);

        AjaxResult res = controller.leaderboard("500", null);

        assertEquals(500, res.get(AjaxResult.CODE_TAG));
        assertEquals("活动不存在", res.get(AjaxResult.MSG_TAG));
    }

    @Test
    void selfPlayLeaderboard_requiresValidPass() {
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.emptyList());

        AjaxResult res = controller.leaderboard(null, "300");

        assertEquals(402, res.get(AjaxResult.CODE_TAG));
    }

    @Test
    void selfPlayOffListMeOnlyCountsActivityZeroRows() {
        when(progressService.selectActivityLeaderboard(isNull(), eq(300L), anyInt()))
                .thenReturn(Collections.singletonList(row(7L, 4L)));
        when(progressService.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.emptyList());

        callOk(null, "300");

        ArgumentCaptor<PlayerNodeProgress> captor = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).selectPlayerNodeProgressList(captor.capture());
        assertEquals(ME, captor.getValue().getMemberId());
        assertEquals(300L, captor.getValue().getTopicId());
        assertEquals(0L, captor.getValue().getActivityId(),
                "自玩榜外回读必须与榜单一样限定 activity_id=0");
    }
    // ---------- D-13(N-02):分支主题按「到达结局 → 用时」排,线性不变 ----------

    private void topicWithGraph(String graphJson) {
        com.chengyinhub.business.service.ICmsTopicService topics =
                mock(com.chengyinhub.business.service.ICmsTopicService.class);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(300L);
        topic.setPublishedRouteGraphJson(graphJson);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topics);
    }

    private static LeaderboardItemVO ended(Long memberId, long elapsed, long score) {
        LeaderboardItemVO r = row(memberId, score);
        r.setReachedEnding(true);
        r.setElapsedSeconds(elapsed);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void branchingTopicRanksByEndingThenElapsedAndTiesShareRank() {
        topicWithGraph("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\"}");
        LeaderboardItemVO unfinished = row(9L, 6L);
        unfinished.setReachedEnding(false);
        when(progressService.selectRouteLeaderboard(ACT, 300L, 50))
                .thenReturn(Arrays.asList(ended(7L, 600L, 1L), ended(ME, 600L, 3L), unfinished));

        Map<String, Object> data = callOk("500", null);

        List<LeaderboardItemVO> list = (List<LeaderboardItemVO>) data.get("list");
        assertEquals(Arrays.asList(1, 1, 3), Arrays.asList(list.get(0).getRank(), list.get(1).getRank(),
                list.get(2).getRank()), "同用时并列,未到结局者即便节点更多也在后");
        assertEquals(ME, ((LeaderboardItemVO) data.get("me")).getMemberId());
        assertEquals(Integer.valueOf(1), ((LeaderboardItemVO) data.get("me")).getRank());
        assertEquals("ROUTE", data.get("rankMode"));
        verify(progressService, org.mockito.Mockito.never()).selectActivityLeaderboard(any(), any(), anyInt());
    }

    @Test
    void branchingTopicOffListMeReadsOwnLatestRunWithoutGuessingRank() {
        topicWithGraph("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\"}");
        when(progressService.selectRouteLeaderboard(0L, 300L, 50))
                .thenReturn(Collections.singletonList(ended(7L, 600L, 1L)));
        LeaderboardItemVO mine = ended(ME, 900L, 2L);
        mine.setRank(5);
        when(progressService.selectRouteLeaderboardRow(0L, 300L, ME)).thenReturn(mine);
        // 自玩榜需要有效通行证
        CmsRegistration pass = new CmsRegistration();
        pass.setId(1L);
        pass.setOwnerType(1L);
        pass.setOwnerId(300L);
        pass.setRegistrationStatus(2);
        pass.setPurchaseKind(com.chengyinhub.common.constant.PurchaseKind.SELF_PLAY_PASS);
        pass.setExpiresAt(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(pass));

        AjaxResult res = controller.leaderboard(null, "300");
        assertEquals(200, res.get(AjaxResult.CODE_TAG), String.valueOf(res.get(AjaxResult.MSG_TAG)));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get(AjaxResult.DATA_TAG);

        LeaderboardItemVO me = (LeaderboardItemVO) data.get("me");
        assertEquals(Long.valueOf(900L), me.getElapsedSeconds());
        assertNull(me.getRank(), "榜外不猜名次");
        verify(progressService, org.mockito.Mockito.never()).selectPlayerNodeProgressList(any());
    }

    @Test
    void linearTopicKeepsNodeCountBoard() {
        topicWithGraph("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\"}");
        when(progressService.selectActivityLeaderboard(eq(ACT), isNull(), anyInt()))
                .thenReturn(Arrays.asList(row(7L, 4L), row(ME, 3L)));

        Map<String, Object> data = callOk("500", null);

        assertEquals("LINEAR", data.get("rankMode"));
        verify(progressService, org.mockito.Mockito.never()).selectRouteLeaderboard(any(), any(), anyInt());
    }

    @Test
    void hiddenRouteLeaderboardDoesNotReturnPlayerRankings() {
        com.chengyinhub.business.service.IGameSessionRuntimeService runtime = mock(
                com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "gameSessionRuntimeService", runtime);
        when(runtime.isRouteLeaderboardHidden(ACT)).thenReturn(true);
        AjaxResult result = controller.leaderboard(String.valueOf(ACT), null);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("主理人暂未开放本场排行榜", result.get(AjaxResult.MSG_TAG));
        org.mockito.Mockito.verify(progressService, org.mockito.Mockito.never())
                .selectActivityLeaderboard(any(), any(), anyInt());
    }

}
