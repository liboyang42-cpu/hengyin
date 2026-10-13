package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.domain.vo.MemberGrowthCenterVO;
import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.domain.vo.MemberLeaderboardVO;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.MemberLeaderboardMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.impl.GrowthServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGrowthControllerTest {

    private MemberGrowthMapper memberGrowthMapper;
    private GrowthMissionMapper growthMissionMapper;
    private GrowthMissionProgressMapper progressMapper;
    private PlayerBadgeMapper playerBadgeMapper;
    private IUmsMemberService umsMemberService;
    private MemberLeaderboardMapper leaderboardMapper;
    private GrowthServiceImpl service;

    @BeforeEach
    void setUp() {
        memberGrowthMapper = mock(MemberGrowthMapper.class);
        growthMissionMapper = mock(GrowthMissionMapper.class);
        progressMapper = mock(GrowthMissionProgressMapper.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        leaderboardMapper = mock(MemberLeaderboardMapper.class);
        service = new GrowthServiceImpl();
        ReflectionTestUtils.setField(service, "memberGrowthMapper", memberGrowthMapper);
        ReflectionTestUtils.setField(service, "growthMissionMapper", growthMissionMapper);
        ReflectionTestUtils.setField(service, "growthMissionProgressMapper", progressMapper);
        ReflectionTestUtils.setField(service, "playerBadgeMapper", playerBadgeMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "memberLeaderboardMapper", leaderboardMapper);
    }

    @Test
    void getCenterDoesNotCreateAccountWhenMissing() {
        when(memberGrowthMapper.selectByMemberId(1001L)).thenReturn(null);
        when(growthMissionMapper.selectActiveMissionList()).thenReturn(Collections.emptyList());
        when(progressMapper.selectMemberProgress(1001L)).thenReturn(Collections.emptyList());
        when(playerBadgeMapper.selectListByMember(1001L)).thenReturn(Collections.emptyList());
        when(leaderboardMapper.selectPointTotalMyRank(1001L)).thenReturn(myRank(7, 30, 8370L));
        UmsMember m = new UmsMember();
        m.setPoint(8370L);
        when(umsMemberService.selectUmsMemberById(1001L)).thenReturn(m);

        MemberGrowthCenterVO result = service.getCenter(1001L);

        // 只读：返回 Lv.1 默认账户，但不得写库（避免被动展示页给全体用户建空账户）。
        assertEquals(Long.valueOf(1001L), result.getGrowth().getMemberId());
        assertEquals(Integer.valueOf(1), result.getGrowth().getLevelNo());
        // 积分走统一账本 UmsMember.point（并池）。
        assertEquals(Long.valueOf(8370L), result.getPoints());
        assertEquals(Integer.valueOf(7), result.getRank());
        verify(memberGrowthMapper, never()).insertMemberGrowth(any(MemberGrowth.class));
    }

    @Test
    void applyEventEnsuresAccountBeforeReward() {
        when(growthMissionMapper.selectActiveByEvent("content_view")).thenReturn(Collections.singletonList(mission("VIEW_TOPIC", "content_view", 10)));
        when(progressMapper.insertMissionProgress(any(GrowthMissionProgress.class))).thenReturn(1);

        service.applyEvent(1001L, "content_view", "topic", 5L);

        // 首次发奖时才建号，且发奖累加照常执行。
        verify(memberGrowthMapper).insertMemberGrowth(any(MemberGrowth.class));
        verify(memberGrowthMapper).updateMemberGrowthReward(any(MemberGrowth.class));
    }

    @Test
    void completeMissionIsIdempotentForSameMissionBizAndDate() {
        when(growthMissionMapper.selectActiveByCode("VIEW_TOPIC")).thenReturn(mission("VIEW_TOPIC", "content_view", 10));
        when(progressMapper.insertMissionProgress(any(GrowthMissionProgress.class))).thenReturn(0);

        GrowthMissionCompleteDTO request = new GrowthMissionCompleteDTO();
        request.setMissionCode("VIEW_TOPIC");
        request.setBizType("topic");
        request.setBizId(10L);

        int accepted = service.completeMission(1001L, request);

        assertEquals(0, accepted);
        verify(memberGrowthMapper, never()).updateMemberGrowthReward(any(MemberGrowth.class));
    }

    @Test
    void completeMissionIgnoresClientBizToPreventRewardFarming() {
        when(growthMissionMapper.selectActiveByCode("DAILY_CHECKIN")).thenReturn(mission("DAILY_CHECKIN", "daily", 10));
        when(progressMapper.insertMissionProgress(any(GrowthMissionProgress.class))).thenReturn(1);

        GrowthMissionCompleteDTO request = new GrowthMissionCompleteDTO();
        request.setMissionCode("DAILY_CHECKIN");
        request.setBizType("forged");
        request.setBizId(999L);

        service.completeMission(1001L, request);

        org.mockito.ArgumentCaptor<GrowthMissionProgress> captor = org.mockito.ArgumentCaptor.forClass(GrowthMissionProgress.class);
        verify(progressMapper).insertMissionProgress(captor.capture());
        assertEquals("", captor.getValue().getBizType());
        assertEquals(Long.valueOf(0L), captor.getValue().getBizId());
    }

    @Test
    void completeMissionRejectsStoreVerificationWithoutServerFact() {
        when(growthMissionMapper.selectActiveByCode("RENAMED_STORE_TASK"))
                .thenReturn(mission("RENAMED_STORE_TASK", "store_verify", 7));
        GrowthMissionCompleteDTO request = new GrowthMissionCompleteDTO();
        request.setMissionCode("RENAMED_STORE_TASK");

        ServiceException rejection = assertThrows(ServiceException.class,
                () -> service.completeMission(1001L, request));

        assertEquals("该任务需完成到店核验后由系统发放", rejection.getMessage());
        verify(progressMapper, never()).insertMissionProgress(any(GrowthMissionProgress.class));
    }

    @Test
    void applyEventAppliesActiveMissionReward() {
        when(growthMissionMapper.selectActiveByEvent("content_like")).thenReturn(Collections.singletonList(mission("LIKE_TOPIC", "content_like", 20)));
        when(progressMapper.insertMissionProgress(any(GrowthMissionProgress.class))).thenReturn(1);

        int accepted = service.applyEvent(1001L, "content_like", "topic", 9L);

        assertEquals(1, accepted);
        verify(memberGrowthMapper).updateMemberGrowthReward(any(MemberGrowth.class));
    }

    @Test
    void applyStoreVerificationRequiresPersistedMatchingRegistration() {
        when(growthMissionMapper.selectActiveByEvent("store_verify"))
                .thenReturn(Collections.singletonList(mission("STORE_TASK", "store_verify", 7)));
        when(growthMissionMapper.selectVerifiedStoreMember(501L)).thenReturn(null);

        int accepted = service.applyEvent(1001L, "store_verify", "registration", 501L);

        assertEquals(0, accepted);
        verify(progressMapper, never()).insertMissionProgress(any(GrowthMissionProgress.class));
        verify(memberGrowthMapper, never()).updateMemberGrowthReward(any(MemberGrowth.class));
    }

    @Test
    void saveMissionUsesLegacyDailyLimitDefaultWhenTheAdminConfigDoesNotExposeIt() {
        GrowthMission mission = mission("VIEW_TOPIC", "content_view", 10);
        mission.setDailyLimit(null);
        when(growthMissionMapper.insertGrowthMission(mission)).thenReturn(1);

        int saved = service.saveMission(mission);

        assertEquals(1, saved);
        assertEquals(Integer.valueOf(1), mission.getDailyLimit());
        verify(growthMissionMapper).insertGrowthMission(mission);
    }

    @SuppressWarnings("unchecked")
    @Test
    void controllerReturnsGrowthCenterForCurrentMember() {
        IGrowthService mockService = mock(IGrowthService.class);
        ApiGrowthController controller = new TestableApiGrowthController(1001L);
        ReflectionTestUtils.setField(controller, "growthService", mockService);
        MemberGrowthCenterVO center = new MemberGrowthCenterVO();
        center.setGrowth(growth(1001L, 2, 120));
        when(mockService.getCenter(eq(1001L))).thenReturn(center);

        AjaxResult result = controller.center();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        MemberGrowthCenterVO data = (MemberGrowthCenterVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(Integer.valueOf(2), data.getGrowth().getLevelNo());
    }

    @Test
    void controllerRejectsMissionCompletionWithoutLogin() {
        ApiGrowthController controller = new TestableApiGrowthController(null);

        AjaxResult result = controller.complete(new GrowthMissionCompleteDTO());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void getLeaderboardDefaultsToPointTotalAndFillsRanks() {
        List<LeaderboardItemVO> top = Arrays.asList(item(11L, 900L), item(22L, 800L), item(33L, 700L));
        when(leaderboardMapper.selectPointTotalTop(50)).thenReturn(top);
        when(leaderboardMapper.selectPointTotalMyRank(1001L)).thenReturn(myRank(3, 10, 700L));
        UmsMember m = new UmsMember();
        m.setNickname("我");
        m.setAvatar("a.png");
        when(umsMemberService.selectUmsMemberById(1001L)).thenReturn(m);

        MemberLeaderboardVO vo = service.getLeaderboard(1001L, new LeaderboardQueryDTO());

        assertEquals("point", vo.getMetric());
        assertEquals("total", vo.getPeriod());
        // 名次按返回顺序回填
        assertEquals(Integer.valueOf(1), vo.getList().get(0).getRank());
        assertEquals(Integer.valueOf(3), vo.getList().get(2).getRank());
        // 我的名次组装 + 分桶(3/10=0.3 -> TOP40%)
        assertEquals(Long.valueOf(1001L), vo.getMe().getMemberId());
        assertEquals("我", vo.getMe().getNickname());
        assertEquals(Integer.valueOf(3), vo.getMe().getRank());
        assertEquals(Long.valueOf(700L), vo.getMe().getScore());
        assertEquals("TOP40%", vo.getMe().getRankPercentage());
    }

    @Test
    void getLeaderboardRoutesToExpWeek() {
        when(leaderboardMapper.selectExpWeekTop(any(), eq(50))).thenReturn(Collections.emptyList());
        when(leaderboardMapper.selectExpWeekMyRank(eq(1001L), any())).thenReturn(myRank(1, 5, 50L));
        LeaderboardQueryDTO q = new LeaderboardQueryDTO();
        q.setMetric("exp");
        q.setPeriod("week");

        MemberLeaderboardVO vo = service.getLeaderboard(1001L, q);

        assertEquals("exp", vo.getMetric());
        assertEquals("week", vo.getPeriod());
        verify(leaderboardMapper).selectExpWeekTop(any(), eq(50));
        verify(leaderboardMapper).selectExpWeekMyRank(eq(1001L), any());
        verify(leaderboardMapper, never()).selectPointTotalTop(anyInt());
    }

    @Test
    void getLeaderboardClampsLimitToHundred() {
        when(leaderboardMapper.selectPointTotalTop(anyInt())).thenReturn(Collections.emptyList());
        when(leaderboardMapper.selectPointTotalMyRank(1001L)).thenReturn(myRank(1, 1, 0L));
        LeaderboardQueryDTO q = new LeaderboardQueryDTO();
        q.setLimit(500);

        service.getLeaderboard(1001L, q);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(leaderboardMapper).selectPointTotalTop(cap.capture());
        assertEquals(Integer.valueOf(100), cap.getValue());
    }

    @Test
    void getLeaderboardMyRankFallsBackWhenNoData() {
        when(leaderboardMapper.selectExpTotalTop(50)).thenReturn(Collections.emptyList());
        when(leaderboardMapper.selectExpTotalMyRank(1001L)).thenReturn(null);
        LeaderboardQueryDTO q = new LeaderboardQueryDTO();
        q.setMetric("exp");

        MemberLeaderboardVO vo = service.getLeaderboard(1001L, q);

        assertNull(vo.getMe().getRank());
        assertEquals(Long.valueOf(0L), vo.getMe().getScore());
        assertEquals("TOP100%", vo.getMe().getRankPercentage());
    }

    @Test
    void getLeaderboardUsesStableSequentialRanksForTiedScores() {
        List<LeaderboardItemVO> top = Arrays.asList(item(11L, 900L), item(22L, 900L), item(33L, 700L));
        when(leaderboardMapper.selectPointTotalTop(50)).thenReturn(top);
        when(leaderboardMapper.selectPointTotalMyRank(1001L)).thenReturn(myRank(3, 10, 700L));

        MemberLeaderboardVO vo = service.getLeaderboard(1001L, new LeaderboardQueryDTO());

        assertEquals(Integer.valueOf(1), vo.getList().get(0).getRank());
        assertEquals(Integer.valueOf(2), vo.getList().get(1).getRank());
        assertEquals(Integer.valueOf(3), vo.getList().get(2).getRank());
    }

    @Test
    void getLeaderboardDoesNotInventFirstPlaceForEmptyCohort() {
        when(leaderboardMapper.selectExpWeekTop(any(), eq(50))).thenReturn(Collections.emptyList());
        when(leaderboardMapper.selectExpWeekMyRank(eq(1001L), any())).thenReturn(myRank(1, 0, 0L));
        LeaderboardQueryDTO q = new LeaderboardQueryDTO();
        q.setMetric("exp");
        q.setPeriod("week");

        MemberLeaderboardVO vo = service.getLeaderboard(1001L, q);

        assertNull(vo.getMe().getRank());
    }

    private static LeaderboardItemVO item(Long memberId, Long score) {
        LeaderboardItemVO v = new LeaderboardItemVO();
        v.setMemberId(memberId);
        v.setScore(score);
        return v;
    }

    private static Map<String, Object> myRank(int rank, int total, long score) {
        Map<String, Object> m = new HashMap<>();
        m.put("userRank", rank);
        m.put("totalUsers", total);
        m.put("score", score);
        return m;
    }

    private static MemberGrowth growth(Long memberId, Integer level, Integer exp) {
        MemberGrowth row = new MemberGrowth();
        row.setMemberId(memberId);
        row.setLevelNo(level);
        row.setExpValue(exp);
        return row;
    }

    private static GrowthMission mission(String code, String event, Integer exp) {
        GrowthMission mission = new GrowthMission();
        mission.setMissionCode(code);
        mission.setMissionName(code);
        mission.setTriggerEvent(event);
        mission.setExpReward(exp);
        mission.setStatus("0");
        return mission;
    }

    private static class TestableApiGrowthController extends ApiGrowthController {
        private final Long appUserId;

        private TestableApiGrowthController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            if (appUserId == null) {
                throw new RuntimeException("not login");
            }
            return appUserId;
        }
    }
}
