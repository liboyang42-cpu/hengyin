package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessServiceImpl;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-SCAN-GROUP-CODE 验收:团码签发/兑换的身份口径。
 *
 * <ul>
 *   <li>没有本场 EVENT_CHECKIN 能力的普通玩家(含普通俱乐部成员、他场核销员)签不出团码,也不触发出图;</li>
 *   <li>领队对同一场次多次签发,码各不相同,但兑换出的场次/主题/出码人身份完全一致;
 *       获授权核销员签发的码兑换出的出码人就是该核销员本人。</li>
 * </ul>
 *
 * <p>团码是无状态签名(不落库),"同一份签发"指的是同一组 (topicId, activityId, issuer) 身份,
 * 而非同一条持久化记录。资格判定走真实 {@link ClubAccessServiceImpl}。
 */
class GroupCodeIssuanceAcceptanceTest {

    private static final Long TOPIC = 300L;
    private static final Long ACTIVITY = 301L;
    private static final Long OTHER_ACTIVITY = 302L;
    private static final Long CLUB = 610L;
    private static final Long LEADER = 42L;
    private static final Long CHECKIN_STAFF = 43L;
    private static final Long PLAIN_PLAYER = 44L;
    private static final Long OTHER_SESSION_STAFF = 45L;
    private static final Long MERCHANT = 77L;
    private static final Long COOP_ORDER = 8001L;
    private static final String SECRET = "group-code-acceptance-secret";

    private ClubMemberMapper clubMembers;
    private ClubRoleAssignmentMapper assignments;
    private CoopGroupVerifyMapper verifies;
    private IQrcodeService qrcode;
    private GroupCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        ClubMapper clubs = mock(ClubMapper.class);
        clubMembers = mock(ClubMemberMapper.class);
        assignments = mock(ClubRoleAssignmentMapper.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        UmsMemberMapper accounts = mock(UmsMemberMapper.class);
        CoopOrderMapper orders = mock(CoopOrderMapper.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        verifies = mock(CoopGroupVerifyMapper.class);
        qrcode = mock(IQrcodeService.class);
        when(qrcode.generateInlineQrcode(anyString(), anyInt(), anyInt())).thenReturn("https://oss.test/qr/group.png");

        ClubAccessServiceImpl access = new ClubAccessServiceImpl();
        ReflectionTestUtils.setField(access, "clubMapper", clubs);
        ReflectionTestUtils.setField(access, "clubMemberMapper", clubMembers);
        ReflectionTestUtils.setField(access, "clubRoleAssignmentMapper", assignments);
        ReflectionTestUtils.setField(access, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(access, "umsMemberMapper", accounts);
        when(accounts.selectRolePermissionSubject(anyLong())).thenAnswer(call -> {
            UmsMember m = new UmsMember();
            m.setId(call.getArgument(0)); m.setRole("club"); m.setStatus(1L); m.setDelFlag(0); return m;
        });
        when(accounts.lockActiveMemberById(anyLong())).thenAnswer(call -> call.getArgument(0));

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(LEADER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        CmsActivity activity = activeActivity();
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(orders.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                order("club", CLUB, null), order("merchant", MERCHANT, COOP_ORDER)));

        when(clubMembers.selectByClubAndMember(CLUB, CHECKIN_STAFF)).thenReturn(member(CHECKIN_STAFF, 0));
        when(assignments.selectActiveForAccess(eq(CLUB), eq(CHECKIN_STAFF), eq(ACTIVITY), any(Date.class)))
                .thenReturn(Collections.singletonList(eventCheckin(CHECKIN_STAFF, ACTIVITY)));
        when(clubMembers.selectByClubAndMember(CLUB, PLAIN_PLAYER)).thenReturn(member(PLAIN_PLAYER, 0));

        service = new GroupCodeServiceImpl(activityService, access, orders, verifies, qrcode, SECRET);
        BusinessAuditEventMapper audit = mock(BusinessAuditEventMapper.class);
        when(audit.insertBusinessAuditEvent(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audit);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic foreign = new CmsTopic();
        foreign.setId(TOPIC);
        foreign.setClubId(CLUB + 9999L);   // 承接场景:主题不属于本俱乐部,资格只靠 club 合作单
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(foreign);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
    }

    @Test
    void plainClubPlayerWithoutEventCheckinIsRefusedIssuance() {
        AjaxResult result = service.issue(ACTIVITY, PLAIN_PLAYER);

        assertEquals(500, result.get("code"));
        assertEquals("您没有该场次的团码核销权限", result.get("msg"));
        assertNull(result.get("data"), "被拒时不得下发任何可出示的码");
        verify(qrcode, never()).generateInlineQrcode(anyString(), anyInt(), anyInt());
    }

    @Test
    void nonMemberPlayerIsRefusedIssuance() {
        when(clubMembers.selectByClubAndMember(CLUB, 46L)).thenReturn(null);

        AjaxResult result = service.issue(ACTIVITY, 46L);

        assertEquals(500, result.get("code"));
        assertNull(result.get("data"));
        verify(qrcode, never()).generateInlineQrcode(anyString(), anyInt(), anyInt());
    }

    @Test
    void checkinStaffOfAnotherSessionIsRefusedIssuanceForThisSession() {
        when(clubMembers.selectByClubAndMember(CLUB, OTHER_SESSION_STAFF)).thenReturn(member(OTHER_SESSION_STAFF, 0));
        // 即便查询泄漏出他场 assignment,也不能放大到本场
        when(assignments.selectActiveForAccess(eq(CLUB), eq(OTHER_SESSION_STAFF), eq(ACTIVITY), any(Date.class)))
                .thenReturn(Collections.singletonList(eventCheckin(OTHER_SESSION_STAFF, OTHER_ACTIVITY)));

        AjaxResult result = service.issue(ACTIVITY, OTHER_SESSION_STAFF);

        assertEquals(500, result.get("code"));
        assertNull(result.get("data"));
        verify(qrcode, never()).generateInlineQrcode(anyString(), anyInt(), anyInt());
    }

    @Test
    void twoLeaderIssuancesForSameSessionRedeemToTheSameIdentity() {
        Map<?, ?> first = data(service.issue(ACTIVITY, LEADER));
        Map<?, ?> second = data(service.issue(ACTIVITY, LEADER));
        assertNotEquals(first.get("code"), second.get("code"), "每次签发都是新码(防截图复用)");
        for (Map<?, ?> issued : Arrays.asList(first, second)) {
            assertEquals(ACTIVITY, issued.get("activityId"));
            assertEquals(TOPIC, issued.get("topicId"));
            assertEquals(CLUB, issued.get("clubId"));
        }

        when(verifies.insertIgnoreCoopGroupVerify(any())).thenReturn(1, 0);
        Map<?, ?> r1 = data(service.redeem((String) first.get("code"), MERCHANT));
        Map<?, ?> r2 = data(service.redeem((String) second.get("code"), MERCHANT));

        for (Map<?, ?> redeemed : Arrays.asList(r1, r2)) {
            assertEquals(ACTIVITY, redeemed.get("activityId"));
            assertEquals(TOPIC, redeemed.get("topicId"));
            assertEquals(LEADER, redeemed.get("leaderMemberId"));
            assertEquals(COOP_ORDER, redeemed.get("coopOrderId"));
        }
        assertEquals(false, r1.get("alreadyVerified"));
        assertEquals(true, r2.get("alreadyVerified"), "同场第二张码只命中同一条接待台账");
        ArgumentCaptor<CoopGroupVerify> rows = ArgumentCaptor.forClass(CoopGroupVerify.class);
        verify(verifies, times(2)).insertIgnoreCoopGroupVerify(rows.capture());
        List<CoopGroupVerify> captured = rows.getAllValues();
        for (CoopGroupVerify row : captured) {
            assertEquals(ACTIVITY, row.getActivityId());
            assertEquals(TOPIC, row.getTopicId());
            assertEquals(LEADER, row.getLeaderMemberId());
            assertEquals(MERCHANT, row.getMerchantMemberId());
            assertEquals(COOP_ORDER, row.getCoopOrderId());
        }
    }

    @Test
    void checkinStaffIssuanceRedeemsToSameSessionWithStaffAsIssuer() {
        Map<?, ?> leaderCode = data(service.issue(ACTIVITY, LEADER));
        Map<?, ?> staffCode = data(service.issue(ACTIVITY, CHECKIN_STAFF));
        when(verifies.insertIgnoreCoopGroupVerify(any())).thenReturn(1, 0);

        Map<?, ?> byLeader = data(service.redeem((String) leaderCode.get("code"), MERCHANT));
        Map<?, ?> byStaff = data(service.redeem((String) staffCode.get("code"), MERCHANT));

        assertEquals(byLeader.get("activityId"), byStaff.get("activityId"));
        assertEquals(byLeader.get("topicId"), byStaff.get("topicId"));
        assertEquals(LEADER, byLeader.get("leaderMemberId"));
        assertEquals(CHECKIN_STAFF, byStaff.get("leaderMemberId"), "出码人身份来自签名,不可被替换成领队或扫码人");
    }

    private static Map<?, ?> data(AjaxResult result) {
        assertEquals(200, result.get("code"), String.valueOf(result.get("msg")));
        return (Map<?, ?>) result.get("data");
    }

    private static CmsActivity activeActivity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        return activity;
    }

    private static CoopOrder order(String toType, Long toId, Long id) {
        CoopOrder o = new CoopOrder();
        o.setId(id);
        o.setTopicId(TOPIC);
        o.setToType(toType);
        o.setToId(toId);
        return o;
    }

    private static ClubMember member(Long memberId, int role) {
        ClubMember cm = new ClubMember();
        cm.setId(memberId + 1000L);
        cm.setClubId(CLUB);
        cm.setMemberId(memberId);
        cm.setRole(role);
        cm.setStatus(1);
        cm.setDelFlag(0);
        return cm;
    }

    private static ClubRoleAssignment eventCheckin(Long memberId, Long activityId) {
        ClubRoleAssignment a = new ClubRoleAssignment();
        a.setId(990L + memberId);
        a.setClubId(CLUB);
        a.setTargetMemberId(memberId);
        a.setRoleCode(ClubRoleCode.EVENT_CHECKIN);
        a.setScopeType(ClubRoleAssignment.SCOPE_EVENT);
        a.setScopeId(activityId);
        a.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        return a;
    }
}
