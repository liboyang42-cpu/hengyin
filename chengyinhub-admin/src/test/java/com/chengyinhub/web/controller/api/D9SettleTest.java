package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeadCheckin;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * D-9 队长全团结算(/api/club/lead/settle)逻辑级验证。
 *
 * 决策锁定:授权=仅队长(=活动创建者)本人;结转名单=已付款(status=2)∩ 集合签到到场,
 * 无人签到则回落全部已付。核销三步(条件置位+入账+台账)已下沉进 ICmsRegistrationService.verifyAndSettle
 * 的事务里,本类的 mock 按其真实契约建模(已核销 ⇒ CAS 返 0 ⇒ false ⇒ 跳过)。
 * 无法本地启后端,沿用 ClubLeadFlowTest 的 spy+ReflectionTestUtils+内存 holder 手法。
 */
class D9SettleTest {

    private ClubLeadMapper clubLeadMapper;
    private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    private IClubService clubService;
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private ISettlementEntryService settlementEntryService;
    private IPointsAwardService pointsAwardService;
    private ICmsRegistrationScanService registrationScanService;
    private IGrowthService growthService;

    private final ClubLeadState[] stateHolder = new ClubLeadState[1];
    private final List<CmsRegistration> paid = new ArrayList<>();
    private final List<ClubLeadCheckin> checkins = new ArrayList<>();
    private final List<Long> settledRegIds = new ArrayList<>();   // recordPaidRegistration 被调的 regId

    private ApiClubLeadController newController(long uid) {
        ApiClubLeadController c = spy(new ApiClubLeadController());
        doReturn(uid).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "clubLeadMapper", clubLeadMapper);
        ReflectionTestUtils.setField(c, "clubRoleAssignmentMapper", clubRoleAssignmentMapper);
        ReflectionTestUtils.setField(c, "clubService", clubService);
        // 领队解析口径已抽到 ClubEventLeadResolver(与 /api/play/nodes 共用);
        // 用同样两个 mock 现搭真实例,判定内容与原私有方法一致。
        ReflectionTestUtils.setField(c, "clubEventLeadResolver",
                new com.chengyinhub.business.access.club.ClubEventLeadResolver(
                        clubRoleAssignmentMapper, clubService));
        ReflectionTestUtils.setField(c, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(c, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(c, "settlementEntryService", settlementEntryService);
        ReflectionTestUtils.setField(c, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(c, "registrationScanService", registrationScanService);
        ReflectionTestUtils.setField(c, "growthService", growthService);
        return c;
    }

    private CmsRegistration reg(long id, long memberId, Integer verStatus) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(memberId);
        r.setOwnerType(2L);
        r.setOwnerId(100L);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(verStatus);
        return r;
    }

    private void arrive(long memberId) {
        ClubLeadCheckin c = new ClubLeadCheckin();
        c.setActivityId(100L);
        c.setMemberId(memberId);
        checkins.add(c);
    }

    private void state(int status) {
        ClubLeadState s = new ClubLeadState();
        s.setId(1L);
        s.setActivityId(100L);
        s.setLeaderMemberId(9L);
        s.setStatus(status);
        stateHolder[0] = s;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clubLeadMapper = mock(ClubLeadMapper.class);
        clubRoleAssignmentMapper = mock(ClubRoleAssignmentMapper.class);
        clubService = mock(IClubService.class);
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        settlementEntryService = mock(ISettlementEntryService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        registrationScanService = mock(ICmsRegistrationScanService.class);
        growthService = mock(IGrowthService.class);

        // 带领场活动:club_id=5,队长(创建者)=9
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(5L); act.setMemberId(9L); act.setTopicId(1L);
        when(activityService.selectCmsActivityById(100L)).thenReturn(act);
        Club club = new Club(); club.setId(5L); club.setMemberId(9L);
        when(clubService.selectClubById(5L)).thenReturn(club);

        when(clubLeadMapper.selectByActivity(100L)).thenAnswer(inv -> stateHolder[0]);
        doAnswer(inv -> {
            ClubLeadState u = inv.getArgument(0);
            ClubLeadState cur = stateHolder[0];
            if (cur != null && u.getStatus() != null) cur.setStatus(u.getStatus());
            return 1;
        }).when(clubLeadMapper).updateClubLeadState(any());
        when(clubLeadMapper.selectCheckins(100L)).thenAnswer(inv -> checkins);

        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(inv -> paid);

        // recordPaidRegistration:记录 regId,模拟幂等(已在名单里则返回 0 不重复)
        doAnswer(inv -> {
            CmsRegistration r = inv.getArgument(0);
            if (settledRegIds.contains(r.getId())) return 0;
            settledRegIds.add(r.getId());
            return 1;
        }).when(settlementEntryService).recordPaidRegistration(any(), anyLong());

        // [P0-21] 全团结算已改走 verifyAndSettle(条件置位+入账+台账同一事务)。
        // 本 mock **忠实建模真实契约**:CAS 对已核销的返 0 行 ⇒ 方法返 false ⇒ 调用方跳过。
        // 注意「跳过已核销」现在是**服务层的条件置位**在保证,不再是 controller 里的读判 —— 那个读判
        // 挡不住并发(队长点全团结算的同时商家扫单张码,两边都读到 0、都置 1、都进结算)。
        doAnswer(inv -> {
            CmsRegistration r = inv.getArgument(0);
            Long payee = inv.getArgument(2);
            if (r.getVerificationStatus() != null && r.getVerificationStatus() == 1) {
                return false;                       // CAS 0 行:已被(单张核销)核过
            }
            r.setVerificationStatus(1);
            settlementEntryService.recordPaidRegistration(r, payee);
            return true;
        }).when(registrationService).verifyAndSettle(any(), anyLong(), anyLong(), any());

        when(pointsAwardService.award(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(1);
        when(growthService.applyEvent(anyLong(), anyString(), anyString(), anyLong())).thenReturn(1);
        when(registrationScanService.selectCmsRegistrationScanList(any())).thenReturn(new ArrayList<CmsRegistrationScan>());
    }

    private int settledCount(AjaxResult r) {
        return (int) ((java.util.Map<?, ?>) r.get("data")).get("settled");
    }

    @Test
    void leaderSettle_roster_arrivedIntersectPaid() {
        // 已付:7,8,9;到场签到:7,9(8 买了票没到集合点)→ 只结算 7,9
        state(2); // 行进中
        paid.add(reg(701L, 7L, 0));
        paid.add(reg(801L, 8L, 0));
        paid.add(reg(901L, 9L, 0));
        arrive(7L); arrive(9L);

        AjaxResult r = newController(9L).settle("100");
        assertEquals(2, settledCount(r), "到场∩已付=7,9 共2人");
        assertTrue(settledRegIds.contains(701L) && settledRegIds.contains(901L));
        assertFalse(settledRegIds.contains(801L), "没到场的8不结算");
        assertEquals(3, stateHolder[0].getStatus().intValue(), "状态机→已结算(3)");
    }

    @Test
    void nonLeader_rejected() {
        state(2);
        paid.add(reg(701L, 7L, 0));
        arrive(7L);
        AjaxResult r = newController(7L).settle("100"); // 成员7 非队长
        assertEquals("只有队长可以结算全团", r.get("msg"));
        assertTrue(settledRegIds.isEmpty(), "越权不结算");
        assertEquals(2, stateHolder[0].getStatus().intValue(), "状态不变");
    }

    @Test
    void beforeStart_rejected() {
        state(1); // 集合中,未发车
        paid.add(reg(701L, 7L, 0));
        arrive(7L);
        AjaxResult r = newController(9L).settle("100");
        assertEquals("请先发车再结算", r.get("msg"));
        assertTrue(settledRegIds.isEmpty());
        assertEquals(1, stateHolder[0].getStatus().intValue());
    }

    @Test
    void reSettle_idempotent() {
        state(2);
        paid.add(reg(701L, 7L, 0));
        paid.add(reg(901L, 9L, 0));
        arrive(7L); arrive(9L);
        ApiClubLeadController leader = newController(9L);

        AjaxResult first = leader.settle("100");
        assertEquals(2, settledCount(first));
        assertEquals(3, stateHolder[0].getStatus().intValue());

        // 再次结算:已 status=3 → 直接返回,不重复入账
        AjaxResult second = leader.settle("100");
        assertEquals("全团已结算", second.get("msg"));
        assertEquals(2, settledRegIds.size(), "幂等:结算名单未变多");
    }

    @Test
    void noCheckins_fallbackAllPaid() {
        // 无人用集合签到 → 回落全部已付(7,8 都结算)
        state(2);
        paid.add(reg(701L, 7L, 0));
        paid.add(reg(801L, 8L, 0));
        AjaxResult r = newController(9L).settle("100");
        assertEquals(2, settledCount(r), "checkins 空回落全部已付");
        assertTrue(settledRegIds.contains(701L) && settledRegIds.contains(801L));
    }

    @Test
    void alreadyVerified_skipped() {
        // 7 已被单张核销(verificationStatus=1)→ 全团结算跳过它,只结未核的 9
        state(2);
        paid.add(reg(701L, 7L, 1));
        paid.add(reg(901L, 9L, 0));
        arrive(7L); arrive(9L);
        AjaxResult r = newController(9L).settle("100");
        assertEquals(1, settledCount(r), "已核的7跳过,只结9");
        assertFalse(settledRegIds.contains(701L));
        assertTrue(settledRegIds.contains(901L));
    }
}
