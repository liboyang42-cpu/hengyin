package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营权委托(带队):带领场领队 = 承接俱乐部主理人(act.clubId 的 Club.memberId),而非活动所有者(发起商家)。
 * ClubLeadState.leaderMemberId 决定 发车/放行/广播/结算/核销 的授权,故领队解析必须对。
 */
class ClubLeadLeaderResolutionTest {

    private ApiClubLeadController controller;
    private ClubLeadMapper clubLeadMapper;
    private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    private ICmsActivityService cmsActivityService;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        controller = new ApiClubLeadController();
        clubLeadMapper = mock(ClubLeadMapper.class);
        clubRoleAssignmentMapper = mock(ClubRoleAssignmentMapper.class);
        cmsActivityService = mock(ICmsActivityService.class);
        clubService = mock(IClubService.class);
        when(cmsActivityService.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);
        when(cmsActivityService.updateActivityOpsIfUnsold(any(CmsActivity.class))).thenReturn(1);
        ReflectionTestUtils.setField(controller, "clubLeadMapper", clubLeadMapper);
        ReflectionTestUtils.setField(controller, "clubRoleAssignmentMapper", clubRoleAssignmentMapper);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        // 领队解析口径已抽到 ClubEventLeadResolver(与 /api/play/nodes 共用同一份);
        // 这里用同样两个 mock 现搭一个真实例,判定内容与原私有方法完全一致。
        ReflectionTestUtils.setField(controller, "clubEventLeadResolver",
                new com.chengyinhub.business.access.club.ClubEventLeadResolver(
                        clubRoleAssignmentMapper, clubService));
    }

    private ClubLeadState ensure(Long actId) {
        return ReflectionTestUtils.invokeMethod(controller, "ensureState", actId);
    }

    @Test
    void leaderIsClubOwner_notActivityOwner() {
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(200L); act.setMemberId(999L); // 活动所有者=发起商家 999
        when(cmsActivityService.selectCmsActivityById(100L)).thenReturn(act);
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(null); // 懒建
        Club club = new Club(); club.setId(200L); club.setMemberId(777L); // 承接俱乐部主理人 777
        when(clubService.selectClubById(200L)).thenReturn(club);

        ClubLeadState st = ensure(100L);

        assertEquals(Long.valueOf(777L), st.getLeaderMemberId(),
                "带领场领队=承接俱乐部主理人,而非活动所有者(发起商家)");
    }

    @Test
    void clubMissing_fallsBackToActivityOwner() {
        CmsActivity act = new CmsActivity();
        act.setId(101L); act.setClubId(201L); act.setMemberId(999L);
        when(cmsActivityService.selectCmsActivityById(101L)).thenReturn(act);
        when(clubLeadMapper.selectByActivity(101L)).thenReturn(null);
        when(clubService.selectClubById(201L)).thenReturn(null); // 俱乐部查不到

        ClubLeadState st = ensure(101L);

        assertEquals(Long.valueOf(999L), st.getLeaderMemberId(), "俱乐部缺失时兜底为活动所有者");
    }

    private ApiClubLeadController spyAs(long uid) {
        ApiClubLeadController c = org.mockito.Mockito.spy(new ApiClubLeadController());
        org.mockito.Mockito.doReturn(uid).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(c, "wxContentSecurityService",
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(c, "clubLeadMapper", clubLeadMapper);
        ReflectionTestUtils.setField(c, "clubRoleAssignmentMapper", clubRoleAssignmentMapper);
        ReflectionTestUtils.setField(c, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(c, "clubService", clubService);
        ReflectionTestUtils.setField(c, "clubEventLeadResolver",
                new com.chengyinhub.business.access.club.ClubEventLeadResolver(
                        clubRoleAssignmentMapper, clubService));
        return c;
    }

    private void stubLeadActivity(long actId, long clubId, long owner, long leaderOwner) {
        CmsActivity act = new CmsActivity();
        act.setId(actId); act.setClubId(clubId); act.setMemberId(owner);
        when(cmsActivityService.selectCmsActivityById(actId)).thenReturn(act);
        when(clubLeadMapper.selectByActivity(actId)).thenReturn(null);
        Club club = new Club(); club.setId(clubId); club.setMemberId(leaderOwner);
        when(clubService.selectClubById(clubId)).thenReturn(club);
    }

    @Test
    void editOps_leaderEditsWhitelistedFields() {
        ApiClubLeadController leader = spyAs(9L); // 承接主理人=领队=9
        stubLeadActivity(100L, 200L, 999L, 9L);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("activityId", 100L); body.put("address", "新集合点·地铁A口"); body.put("dayNote", "雨天改室内");

        leader.editOps(body);

        org.mockito.ArgumentCaptor<CmsActivity> cap = org.mockito.ArgumentCaptor.forClass(CmsActivity.class);
        org.mockito.Mockito.verify(cmsActivityService).updateActivityOpsIfUnsold(cap.capture());
        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never()).updateCmsActivity(any(CmsActivity.class));
        assertEquals("新集合点·地铁A口", cap.getValue().getAddress(), "承接方可改集合地点");
        assertEquals("雨天改室内", cap.getValue().getDayNote(), "承接方可改当日备注");
    }

    @Test
    void editOps_nonLeaderRejected_noUpdate() {
        ApiClubLeadController stranger = spyAs(7L); // 非领队(领队=9)
        stubLeadActivity(100L, 200L, 999L, 9L);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("activityId", 100L); body.put("address", "越权改");

        stranger.editOps(body);

        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never())
                .updateCmsActivity(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never())
                .updateActivityOpsIfUnsold(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeEventLeadRepairsExistingOwnerStateAndCanOperate() {
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(200L); act.setMemberId(999L);
        when(cmsActivityService.selectCmsActivityById(100L)).thenReturn(act);
        Club club = new Club(); club.setId(200L); club.setMemberId(9L);
        when(clubService.selectClubById(200L)).thenReturn(club);

        ClubLeadState stale = new ClubLeadState();
        stale.setId(1L); stale.setActivityId(100L); stale.setLeaderMemberId(9L); stale.setStatus(1);
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(stale);
        ClubRoleAssignment lead = new ClubRoleAssignment();
        lead.setClubId(200L); lead.setTargetMemberId(7L); lead.setRoleCode(ClubRoleCode.EVENT_LEAD);
        lead.setScopeType(ClubRoleAssignment.SCOPE_EVENT); lead.setScopeId(100L);
        when(clubRoleAssignmentMapper.selectActiveByClubAndScope(eq(200L), eq(100L), any(Date.class)))
                .thenReturn(Collections.singletonList(lead));

        ApiClubLeadController assignedLead = spyAs(7L);
        Map<String, Object> body = new HashMap<>();
        body.put("activityId", 100L);
        body.put("dayNote", "按现场安排集合");

        assertEquals(200, assignedLead.editOps(body).get("code"));
        org.mockito.ArgumentCaptor<ClubLeadState> stateUpdate =
                org.mockito.ArgumentCaptor.forClass(ClubLeadState.class);
        verify(clubLeadMapper).updateClubLeadState(stateUpdate.capture());
        assertEquals(Long.valueOf(7L), stateUpdate.getValue().getLeaderMemberId());
        verify(cmsActivityService).updateCmsActivity(any(CmsActivity.class));
    }

    @Test
    void eventCheckinAssignmentDoesNotBecomeLead() {
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(200L); act.setMemberId(999L);
        when(cmsActivityService.selectCmsActivityById(100L)).thenReturn(act);
        Club club = new Club(); club.setId(200L); club.setMemberId(9L);
        when(clubService.selectClubById(200L)).thenReturn(club);
        ClubLeadState state = new ClubLeadState();
        state.setId(1L); state.setActivityId(100L); state.setLeaderMemberId(9L); state.setStatus(1);
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(state);
        ClubRoleAssignment checkin = new ClubRoleAssignment();
        checkin.setTargetMemberId(7L); checkin.setRoleCode(ClubRoleCode.EVENT_CHECKIN);
        when(clubRoleAssignmentMapper.selectActiveByClubAndScope(eq(200L), eq(100L), any(Date.class)))
                .thenReturn(Collections.singletonList(checkin));

        Map<String, Object> body = new HashMap<>();
        body.put("activityId", 100L); body.put("dayNote", "越权修改");

        assertEquals(500, spyAs(7L).editOps(body).get("code"));
        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never())
                .updateCmsActivity(any(CmsActivity.class));
    }

    @Test
    void noActiveEventLeadRepairsExistingStateBackToClubOwner() {
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(200L); act.setMemberId(999L);
        when(cmsActivityService.selectCmsActivityById(100L)).thenReturn(act);
        Club club = new Club(); club.setId(200L); club.setMemberId(9L);
        when(clubService.selectClubById(200L)).thenReturn(club);
        ClubLeadState stale = new ClubLeadState();
        stale.setId(1L); stale.setActivityId(100L); stale.setLeaderMemberId(7L); stale.setStatus(1);
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(stale);
        when(clubRoleAssignmentMapper.selectActiveByClubAndScope(eq(200L), eq(100L), any(Date.class)))
                .thenReturn(Collections.emptyList());

        ClubLeadState repaired = ensure(100L);

        assertEquals(Long.valueOf(9L), repaired.getLeaderMemberId());
        org.mockito.ArgumentCaptor<ClubLeadState> update =
                org.mockito.ArgumentCaptor.forClass(ClubLeadState.class);
        verify(clubLeadMapper).updateClubLeadState(update.capture());
        assertEquals(Long.valueOf(9L), update.getValue().getLeaderMemberId());
    }

    @Test
    void nonLeadActivity_returnsNull() {
        CmsActivity act = new CmsActivity();
        act.setId(102L); act.setClubId(0L); // 非带领场
        when(cmsActivityService.selectCmsActivityById(102L)).thenReturn(act);

        assertNull(ensure(102L), "非带领场(无 clubId)返回 null");
    }

    /**
     * CR-63(2026-09-15 裁决 15):已售出(有已支付报名)的场次锁集合时间与地点;
     * 当日备注属文字,仍可改。服务端为准 —— 前端置灰只是体验。
     * P1-2(审查D):判据含待支付/支付中,所以这里桩 countOpenSaleRegistrations。
     */
    @Test
    void editOps_soldActivityLocksTimeAndPlace_butAllowsDayNote() {
        ApiClubLeadController leader = spyAs(9L); // 承接主理人=领队=9
        stubLeadActivity(100L, 200L, 999L, 9L);
        when(cmsActivityService.countOpenSaleRegistrations(100L)).thenReturn(1);

        java.util.Map<String, Object> place = new java.util.HashMap<>();
        place.put("activityId", 100L);
        place.put("address", "越权改集合点");
        com.chengyinhub.common.core.domain.AjaxResult rejected = leader.editOps(place);
        assertEquals(500, ((Integer) rejected.get("code")).intValue(), "实际:" + rejected.get("msg"));
        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never())
                .updateCmsActivity(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never())
                .updateActivityOpsIfUnsold(org.mockito.ArgumentMatchers.any());

        java.util.Map<String, Object> noteOnly = new java.util.HashMap<>();
        noteOnly.put("activityId", 100L);
        noteOnly.put("dayNote", "雨天改室内");
        com.chengyinhub.common.core.domain.AjaxResult ok = leader.editOps(noteOnly);
        assertEquals(200, ((Integer) ok.get("code")).intValue(), "实际:" + ok.get("msg"));
        org.mockito.ArgumentCaptor<CmsActivity> cap = org.mockito.ArgumentCaptor.forClass(CmsActivity.class);
        org.mockito.Mockito.verify(cmsActivityService).updateCmsActivity(cap.capture());
        assertEquals("雨天改室内", cap.getValue().getDayNote(), "当日备注是文字,裁决不锁");
    }
    /**
     * F0122:改集合时间与首单并发——先查未售，但条件更新命中 0 行(期间已售)时不得报成功，
     * 重读后明确报「刚刚售出」;0 行且未售也不报成功。
     */
    @Test
    void editOps_timeChangeLosingRaceToFirstSaleIsNotReportedAsSuccess() {
        ApiClubLeadController leader = spyAs(9L);
        stubLeadActivity(100L, 200L, 999L, 9L);
        when(cmsActivityService.countOpenSaleRegistrations(100L)).thenReturn(0, 1);
        when(cmsActivityService.updateActivityOpsIfUnsold(any(CmsActivity.class))).thenReturn(0);
        Map<String, Object> body = new HashMap<>();
        body.put("activityId", 100L); body.put("startDate", "2026-10-20 09:00:00");

        com.chengyinhub.common.core.domain.AjaxResult raced = leader.editOps(body);
        assertEquals(500, ((Integer) raced.get("code")).intValue());
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(raced.get("msg")).contains("刚刚售出"), "实际:" + raced.get("msg"));
        org.mockito.Mockito.verify(cmsActivityService, org.mockito.Mockito.never()).updateCmsActivity(any(CmsActivity.class));

        when(cmsActivityService.countOpenSaleRegistrations(100L)).thenReturn(0);
        com.chengyinhub.common.core.domain.AjaxResult unknown = leader.editOps(body);
        assertEquals(500, ((Integer) unknown.get("code")).intValue(), "0 行且未售也不报成功");
    }

    /** F0122:成功时回读新时间，并如实标注通知未推送(不把留痕称为送达)。 */
    @Test
    void editOps_successReadsBackNewTimeAndReportsNotificationNotSent() {
        ApiClubLeadController leader = spyAs(9L);
        stubLeadActivity(100L, 200L, 999L, 9L);
        CmsActivity original = cmsActivityService.selectCmsActivityById(100L);
        Date moved = com.chengyinhub.common.utils.DateUtils.parseDate("2026-10-20 09:00:00");
        CmsActivity reread = new CmsActivity(); reread.setId(100L); reread.setClubId(200L); reread.setMemberId(999L); reread.setStartDate(moved);
        boolean[] updated = {false};
        when(cmsActivityService.updateActivityOpsIfUnsold(any(CmsActivity.class))).thenAnswer(call -> { updated[0] = true; return 1; });
        when(cmsActivityService.selectCmsActivityById(100L)).thenAnswer(call -> updated[0] ? reread : original);
        Map<String, Object> body = new HashMap<>();
        body.put("activityId", 100L); body.put("startDate", "2026-10-20 09:00:00");

        com.chengyinhub.common.core.domain.AjaxResult ok = leader.editOps(body);
        assertEquals(200, ((Integer) ok.get("code")).intValue(), "实际:" + ok.get("msg"));
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) ok.get("data");
        assertEquals(moved, data.get("startDate"));
        assertEquals("NOT_SENT", data.get("notification"));
    }

    @Test
    void playerLeadProjectionUsesAuthoritativeRouteSessionWithoutChangingLeader() {
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(200L); act.setMemberId(999L);
        when(cmsActivityService.selectCmsActivityById(100L)).thenReturn(act);
        Club club = new Club(); club.setId(200L); club.setMemberId(777L);
        when(clubService.selectClubById(200L)).thenReturn(club);
        ClubLeadState stale = new ClubLeadState();
        stale.setId(1L); stale.setStatus(1); stale.setLeaderMemberId(777L); stale.setCurrentChapterId(10L);
        when(clubLeadMapper.selectByActivity(100L)).thenReturn(stale);
        com.chengyinhub.business.service.IGameSessionRuntimeService runtime = mock(
                com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(controller, "gameSessionRuntimeService", runtime);
        when(runtime.routeSessionStatus(100L)).thenReturn("RUNNING");
        ClubLeadState view = ensure(100L);
        assertEquals(2, view.getStatus());
        assertEquals(777L, view.getLeaderMemberId());
        assertNull(view.getCurrentChapterId());
        when(runtime.routeSessionStatus(100L)).thenReturn("FINISHED");
        assertEquals(3, ensure(100L).getStatus());
    }

}
