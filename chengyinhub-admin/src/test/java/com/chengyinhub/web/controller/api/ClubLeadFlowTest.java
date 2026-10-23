package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeadCheckin;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * P1 俱乐部带领 · 双账号全链路(队长9 / 成员7)逻辑级验证。
 *
 * 无法本地启后端(业务表无完整 DDL,仅 sys_* + 7 备份表),故用 spy 桩住 getAppUserId 切换身份,
 * 驱动 5 个真实 endpoint 的控制器代码路径:懒建→发车放行首章→成员签到→队长顺延章节→
 * 成员越权被拒→轮询聚合(集合 n/m + 成员完成数)。clubLeadMapper 用内存 holder 让写读连续。
 */
class ClubLeadFlowTest {

    private ApiClubLeadController leader;   // getAppUserId()=9
    private ApiClubLeadController member;   // getAppUserId()=7
    private ApiClubLeadController otherActivityParticipant; // getAppUserId()=8，仅参加别的场次
    private ClubLeadMapper clubLeadMapper;
    private com.chengyinhub.business.mapper.GameSessionRuntimeMapper commandReceiptMapper;
    private ClubEventOpsMapper clubEventOpsMapper;
    private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    private ICmsActivityService activityService;
    private com.chengyinhub.business.mapper.CmsActivityMapper activityMapper;
    private IClubService clubService;
    private ICmsTopicChapterService chapterService;
    private ICmsRegistrationService registrationService;
    private IPlayerNodeProgressService progressService;
    private IUmsMemberService memberService;
    private IGrowthService growthService;
    private IPointsAwardService pointsAwardService;

    private final ClubLeadState[] stateHolder = new ClubLeadState[1];      // 内存态
    private final List<ClubLeadCheckin> checkins = new ArrayList<>();

    private ApiClubLeadController newController(long uid) {
        ApiClubLeadController c = spy(new ApiClubLeadController());
        doReturn(uid).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "clubLeadMapper", clubLeadMapper);
        ReflectionTestUtils.setField(c, "commandReceiptMapper", commandReceiptMapper);
        ReflectionTestUtils.setField(c, "clubEventOpsMapper", clubEventOpsMapper);
        ReflectionTestUtils.setField(c, "clubRoleAssignmentMapper", clubRoleAssignmentMapper);
        ReflectionTestUtils.setField(c, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(c, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(c, "clubGatherArrivalPolicy", new com.chengyinhub.business.service.support.ClubGatherArrivalPolicy());
        ReflectionTestUtils.setField(c, "clubService", clubService);
        // 领队解析口径已抽到 ClubEventLeadResolver(与 /api/play/nodes 共用同一份);
        // 这里用同样两个 mock 现搭一个真实例,判定内容与原私有方法完全一致。
        ReflectionTestUtils.setField(c, "clubEventLeadResolver",
                new com.chengyinhub.business.access.club.ClubEventLeadResolver(
                        clubRoleAssignmentMapper, clubService));
        ReflectionTestUtils.setField(c, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(c, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(c, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(c, "umsMemberService", memberService);
        ReflectionTestUtils.setField(c, "wxContentSecurityService", mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(c, "growthService", growthService);
        ReflectionTestUtils.setField(c, "pointsAwardService", pointsAwardService);
        return c;
    }

    private CmsTopicChapter chapter(long id, long sort, String name) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id); c.setSortId(sort); c.setName(name);
        return c;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clubLeadMapper = mock(ClubLeadMapper.class);
        commandReceiptMapper = mock(com.chengyinhub.business.mapper.GameSessionRuntimeMapper.class);
        when(commandReceiptMapper.insertReceiptClaim(any())).thenReturn(1);
        when(commandReceiptMapper.completeReceipt(any())).thenReturn(1);
        when(clubLeadMapper.lockClubOwner(5L)).thenReturn(9L);
        when(clubLeadMapper.selectByActivityForUpdate(100L)).thenAnswer(inv -> stateHolder[0]);
        when(clubLeadMapper.unlockChapterCas(org.mockito.ArgumentMatchers.eq(100L), anyLong(), anyLong(), any(), anyLong())).thenAnswer(inv -> {
            if (!java.util.Objects.equals(stateHolder[0].getCurrentChapterId(), inv.getArgument(3))) return 0;
            stateHolder[0].setCurrentChapterId(inv.getArgument(4)); return 1;
        });
        clubEventOpsMapper = mock(ClubEventOpsMapper.class);
        clubRoleAssignmentMapper = mock(ClubRoleAssignmentMapper.class);
        activityService = mock(ICmsActivityService.class);
        activityMapper = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        clubService = mock(IClubService.class);
        chapterService = mock(ICmsTopicChapterService.class);
        registrationService = mock(ICmsRegistrationService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        memberService = mock(IUmsMemberService.class);
        growthService = mock(IGrowthService.class);
        pointsAwardService = mock(IPointsAwardService.class);

        // 带领场活动:club_id=5(带领场),队长=9,topic=1
        CmsActivity act = new CmsActivity();
        act.setId(100L); act.setClubId(5L); act.setMemberId(9L); act.setTopicId(1L);
        act.setDelFlag(0); act.setStartDate(new java.util.Date(System.currentTimeMillis() + 1800000L));
        act.setEndDate(new java.util.Date(System.currentTimeMillis() + 10800000L));
        act.setLatitude("31.23"); act.setLongitude("121.47");
        when(activityService.selectCmsActivityById(100L)).thenReturn(act);
        when(activityMapper.selectCmsActivityByIdForUpdate(100L)).thenReturn(act);
        // 运营权委托:带领场领队=承接俱乐部(5)主理人;本场主理人即队长9(保持"队长9"意图)
        Club club = new Club(); club.setId(5L); club.setMemberId(9L);
        when(clubService.selectClubById(5L)).thenReturn(club);

        // 章节两章(sort 1,2)
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(new ArrayList<>(Arrays.asList(
                chapter(10L, 1L, "第一章·集合"), chapter(20L, 2L, "第二章·街巷"))));
        when(chapterService.selectCmsTopicChapterById(10L)).thenReturn(chapter(10L, 1L, "第一章·集合"));
        when(chapterService.selectCmsTopicChapterById(20L)).thenReturn(chapter(20L, 2L, "第二章·街巷"));

        // clubLeadMapper 内存 holder:insert 建态,select 读态,update 合并变更字段
        when(clubLeadMapper.selectByActivity(100L)).thenAnswer(inv -> stateHolder[0]);
        doAnswer(inv -> {
            ClubLeadState s = inv.getArgument(0);
            s.setId(1L);
            stateHolder[0] = s;
            return 1;
        }).when(clubLeadMapper).insertClubLeadState(any());
        doAnswer(inv -> {
            ClubLeadState u = inv.getArgument(0);
            ClubLeadState cur = stateHolder[0];
            if (cur != null) {
                if (u.getStatus() != null) cur.setStatus(u.getStatus());
                if (u.getCurrentChapterId() != null) cur.setCurrentChapterId(u.getCurrentChapterId());
                if (u.getBroadcastText() != null) cur.setBroadcastText(u.getBroadcastText());
            }
            return 1;
        }).when(clubLeadMapper).updateClubLeadState(any());
        // 模拟 uk_arrive:同 (activity,member) 二次插入抛 DuplicateKeyException(驱动控制器 catch 幂等分支)
        doAnswer(inv -> {
            ClubLeadCheckin c = inv.getArgument(0);
            boolean dup = checkins.stream().anyMatch(x ->
                    x.getActivityId().equals(c.getActivityId()) && x.getMemberId().equals(c.getMemberId()));
            if (dup) throw new org.springframework.dao.DuplicateKeyException("uk_arrive");
            checkins.add(c);
            return 1;
        }).when(clubLeadMapper).insertCheckin(any());
        when(clubLeadMapper.selectCheckins(100L)).thenAnswer(inv -> checkins);

        // 报名:成员7 已购票(status=2)
        CmsRegistration reg = paidRegistration(100L, 7L);
        CmsRegistration otherActivityReg = paidRegistration(200L, 8L);
        List<CmsRegistration> registrations = Arrays.asList(reg, otherActivityReg);
        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(invocation -> {
            CmsRegistration query = invocation.getArgument(0);
            List<CmsRegistration> matches = new ArrayList<>();
            for (CmsRegistration candidate : registrations) {
                if (matches(query.getOwnerType(), candidate.getOwnerType())
                        && matches(query.getOwnerId(), candidate.getOwnerId())
                        && matches(query.getMemberId(), candidate.getMemberId())
                        && matches(query.getPaymentStatus(), candidate.getPaymentStatus())
                        && matches(query.getRegistrationStatus(), candidate.getRegistrationStatus())) {
                    matches.add(candidate);
                }
            }
            return matches;
        });
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(new ArrayList<>());
        when(memberService.selectUmsMemberById(anyLong())).thenReturn(new com.chengyinhub.business.domain.UmsMember());

        leader = newController(9L);
        member = newController(7L);
        otherActivityParticipant = newController(8L);
    }

    private CmsRegistration paidRegistration(Long activityId, Long memberId) {
        CmsRegistration registration = new CmsRegistration();
        registration.setOwnerType(2L);
        registration.setId(activityId * 100 + memberId);
        registration.setOwnerId(activityId);
        registration.setMemberId(memberId);
        registration.setPaymentStatus(2);
        registration.setRegistrationStatus(2);
        return registration;
    }

    private boolean matches(Object queryValue, Object actualValue) {
        return queryValue == null || queryValue.equals(actualValue);
    }

    @Test
    void twoAccountLeadFlow_start_arrive_unlock_reject_aggregate() {
        // ① 成员越权发车 → 拒(此时懒建 state,leader=9,成员7 非队长)
        AjaxResult r1 = member.start("100");
        assertEquals("只有队长可以开始行程", r1.get("msg"));
        assertEquals(1, stateHolder[0].getStatus().intValue(), "越权不改状态,仍集合中");

        // ② 队长发车 → 行进 + 放行首章(sort 最小=10)
        AjaxResult r2 = leader.start("100");
        assertEquals(2, stateHolder[0].getStatus().intValue());
        assertEquals(10L, stateHolder[0].getCurrentChapterId().longValue(), "发车放行第一章");

        // ③ 成员签到(幂等:重复不增)
        member.arrive("100", 31.23D, 121.47D);
        member.arrive("100", 31.23D, 121.47D);
        assertEquals(1, checkins.size(), "同成员重复签到内存只一条(真库靠 uk 幂等)");

        // ④ 队长顺延下一章(10→20)
        AjaxResult r4 = leader.unlockChapter("100", "chapter-two", 10L, 20L);
        assertEquals(20L, stateHolder[0].getCurrentChapterId().longValue(), "放行第二章");
        assertTrue(String.valueOf(r4.get("msg")).contains("第二章"));

        // ⑤ 已是最后一章 → 顺延到头返回提示,不越界
        AjaxResult r5 = leader.unlockChapter("100", "no-next-chapter", 20L, 30L);
        assertEquals("已是最后一章", r5.get("msg"));
        assertEquals(20L, stateHolder[0].getCurrentChapterId().longValue());

        // ⑥ 轮询聚合:集合 arrived=1(成员7)/ total 去重含队长9+成员7=2 / 当前章名
        AjaxResult prog = leader.teamProgress(100L);
        assertEquals(2, ((java.util.Map<?, ?>) prog.get("data")).get("status"));
        java.util.Map<?, ?> d = (java.util.Map<?, ?>) prog.get("data");
        assertEquals(1, d.get("arrived"));
        assertEquals(2, d.get("total"));
        assertEquals("第二章·街巷", d.get("chapterName"));
        assertEquals(Boolean.TRUE, d.get("isLeader"));
    }

    @Test
    void teamProgressDoneCountOnlyCountsFormalCompletion() {
        leader.start("100");
        PlayerNodeProgress finished = new PlayerNodeProgress();
        finished.setMemberId(7L);
        finished.setActivityId(100L);
        finished.setNodeId(1L);
        finished.setStatus(1);
        PlayerNodeProgress arrivedOnly = new PlayerNodeProgress();
        arrivedOnly.setMemberId(7L);
        arrivedOnly.setActivityId(100L);
        arrivedOnly.setNodeId(2L);
        arrivedOnly.setStatus(0);
        when(progressService.selectPlayerNodeProgressList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(finished, arrivedOnly)));

        AjaxResult prog = leader.teamProgress(100L);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> members = (List<java.util.Map<String, Object>>)
                ((java.util.Map<?, ?>) prog.get("data")).get("members");
        java.util.Map<String, Object> member7 = null;
        for (java.util.Map<String, Object> m : members) {
            if (Long.valueOf(7L).equals(m.get("memberId"))) member7 = m;
        }
        assertTrue(member7 != null, "成员7 必须出现在进度列表");
        assertEquals(1, member7.get("done"), "仅到店(status=0)不得计入成员完成数");
        assertEquals(Boolean.FALSE, member7.get("arrived"), "未签到仍是未签到(到达事实独立保留)");
    }

    @Test
    void teamProgressUsesLatestAttendanceCorrectionAndKeepsLeaderCheckin() {
        member.arrive("100", 31.23D, 121.47D);
        leader.arrive("100", 31.23D, 121.47D);
        Map<String, Object> corrected = new HashMap<>();
        corrected.put("memberId", 7L);
        corrected.put("baseArrivedAt", new java.util.Date());
        corrected.put("correctedArrived", 0);
        when(clubEventOpsMapper.selectPaidRoster(100L)).thenReturn(Collections.singletonList(corrected));

        Map<?, ?> retracted = (Map<?, ?>) member.teamProgress(100L).get("data");
        assertEquals(Boolean.FALSE, retracted.get("meArrived"));
        assertEquals(1, retracted.get("arrived"), "领队签到保留，成员撤销应即时扣除");
        assertEquals(2, retracted.get("total"));
        Map<?, ?> retractedMember = ((List<Map<?, ?>>) retracted.get("members")).stream()
                .filter(row -> Long.valueOf(7L).equals(row.get("memberId"))).findFirst().get();
        assertEquals(Boolean.FALSE, retractedMember.get("arrived"));

        corrected.put("correctedArrived", Boolean.TRUE);
        corrected.put("baseArrivedAt", null);
        Map<?, ?> supplemented = (Map<?, ?>) member.teamProgress(100L).get("data");
        assertEquals(Boolean.TRUE, supplemented.get("meArrived"));
        assertEquals(2, supplemented.get("arrived"), "补记到场应进入集合人数");
        assertEquals(2, checkins.size(), "更正读回不得重写原始签到");
    }

    @Test
    void memberCannotUnlockOrBroadcast() {
        leader.start("100"); // 建态并发车
        AjaxResult u = member.unlockChapter("100", "not-leader", 10L, 20L);
        assertEquals("只有当前队长可以放行章节", u.get("msg"));
        AjaxResult b = member.broadcast("100", "大家跟上");
        assertEquals("只有队长可以广播", b.get("msg"));
        // 队长广播成功落字段
        leader.broadcast("100", "前方路口左转");
        assertEquals("前方路口左转", stateHolder[0].getBroadcastText());
    }

    @Test
    void participantFromAnotherActivityCannotArriveOrReadThisTeam() {
        leader.start("100");

        AjaxResult arrive = otherActivityParticipant.arrive("100", 31.23D, 121.47D);
        AjaxResult progress = otherActivityParticipant.teamProgress(100L);

        assertEquals("仅本场已支付成员或领队可签到", arrive.get("msg"));
        assertEquals(0, checkins.size(), "越权签到不得写入 club_lead_checkin");
        assertEquals("无权查看本场队伍进度", progress.get("msg"));
        org.mockito.Mockito.verify(progressService, org.mockito.Mockito.never())
                .selectPlayerNodeProgressList(any());
        ArgumentCaptor<CmsRegistration> probes = ArgumentCaptor.forClass(CmsRegistration.class);
        org.mockito.Mockito.verify(registrationService, atLeast(2))
                .selectCmsRegistrationList(probes.capture());
        long exactScopeChecks = probes.getAllValues().stream()
                .filter(q -> Long.valueOf(8L).equals(q.getMemberId()))
                .filter(q -> Long.valueOf(2L).equals(q.getOwnerType()))
                .filter(q -> Long.valueOf(100L).equals(q.getOwnerId()))
                .filter(q -> Integer.valueOf(2).equals(q.getPaymentStatus()))
                .filter(q -> Integer.valueOf(2).equals(q.getRegistrationStatus()))
                .count();
        assertEquals(2L, exactScopeChecks, "到场与进度两个入口都必须按本场+本人+已支付精确查询");
    }

    @Test
    void settledTeamRetryReplaysGrowthFromPersistedVerification() {
        leader.start("100");
        stateHolder[0].setStatus(3);

        AjaxResult result = leader.settle("100");

        assertEquals("全团已结算", result.get("msg"));
        verify(growthService).deliverStoreVerification(10007L);
    }
}
