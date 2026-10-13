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
        ReflectionTestUtils.setField(controller, "playerNodeProgressService", progressService);
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);

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
    @Test
    void hiddenRouteLeaderboardDoesNotReturnPlayerRankings() {
        com.chengyinhub.business.service.IGameSessionRuntimeService runtime = mock(
                com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(controller, "gameSessionRuntimeService", runtime);
        when(runtime.isRouteLeaderboardHidden(ACT)).thenReturn(true);
        AjaxResult result = controller.leaderboard(String.valueOf(ACT), null);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("主理人暂未开放本场排行榜", result.get(AjaxResult.MSG_TAG));
        org.mockito.Mockito.verify(progressService, org.mockito.Mockito.never())
                .selectActivityLeaderboard(any(), any(), anyInt());
    }

}
