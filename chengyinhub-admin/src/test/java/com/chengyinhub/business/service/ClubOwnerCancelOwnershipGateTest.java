package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.impl.ClubTopicSettingServiceImpl;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S14-4(5-07):转让主理人后,新主理人取消旧场次报「无权操作该活动」。
 *
 * <p>根因:cms_activity.member_id 在系列创建时就写死了创建人(旧主理人),
 * {@code cancelActivity} 的归属校验只认这一列;而 event-ops 取消时传下去的是
 * <b>俱乐部当前主理人</b>(ClubEventOpsServiceImpl.cancelOccurrence 的 lockedOwner)。
 * 转让后两者必然不等 ⇒ 新主理人取消不了旧场次。
 *
 * <p>治:归属判定补上「活动挂在俱乐部名下且操作人是该俱乐部当前主理人」这一支,
 * 与 cancelPaidRegistrationByOwner 的俱乐部主理人档同口径。
 */
class ClubOwnerCancelOwnershipGateTest {

    private static final Long ACTIVITY_ID = 200L;
    private static final Long CLUB_ID = 9L;
    private static final Long PREVIOUS_OWNER = 77L;
    private static final Long CURRENT_OWNER = 88L;
    private static final Long UNRELATED = 12345L;
    private static final Long TOPIC_ID = 501L;
    private static final Long TICKET_ID = 300L;

    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private ClubMapper clubMapper;

    private TeamGroupServiceImpl service(CmsActivity activity, Club club) {
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service,"cancellationTransactions",CancellationPhaseTestSupport.memoryTransactions());
        ReflectionTestUtils.setField(service,"verifiedPaymentFacts",
                mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class));

        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        clubMapper = mock(ClubMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);

        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY_ID)).thenReturn(activity);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(activityMapper.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.<CmsRegistration>emptyList());
        when(registrationMapper.selectCancellationRowsForUpdate(2L,ACTIVITY_ID))
                .thenReturn(Collections.emptyList());
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(club);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID))
                .thenAnswer(call -> clubMapper.selectClubById(CLUB_ID));
        return service;
    }

    private CmsActivity clubActivity(Long creatorMemberId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setName("外滩夜行");
        activity.setMemberId(creatorMemberId);
        activity.setClubId(CLUB_ID);
        activity.setDelFlag(0);
        activity.setPublishStatus(1L);
        return activity;
    }

    private Club club(Long ownerMemberId) {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(ownerMemberId);
        club.setDelFlag(0);
        return club;
    }

    @Test
    void currentClubOwnerCanCancelActivityCreatedByPreviousOwner() {
        TeamGroupServiceImpl service = service(clubActivity(PREVIOUS_OWNER), club(CURRENT_OWNER));

        ActivityCancelResult result = service.cancelActivityByClubOperator(
                ACTIVITY_ID, CURRENT_OWNER, CURRENT_OWNER, "场地不可用");

        assertEquals(0, result.getRefundedOrders());
        ArgumentCaptor<CmsActivity> update = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(update.capture());
        assertEquals("场地不可用", update.getValue().getCancelReason(),
                "★转让后的旧场次必须能被当前主理人取消;还报「无权操作该活动」就是归属判据没跟上");
    }

    @Test
    void previousOwnerCannotCancelClubActivityAfterTransfer() {
        TeamGroupServiceImpl service = service(clubActivity(PREVIOUS_OWNER), club(CURRENT_OWNER));

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelActivityByOwner(ACTIVITY_ID, PREVIOUS_OWNER, "场地不可用"));

        assertEquals("无权操作该活动", denied.getMessage());
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    @Test
    void cancellationPreviewUsesCurrentClubOwnerAfterTransfer() {
        TeamGroupServiceImpl service = service(clubActivity(PREVIOUS_OWNER), club(CURRENT_OWNER));

        assertEquals(0, service.countPaidPlayersForActivityCancel(ACTIVITY_ID, CURRENT_OWNER));
        assertThrows(ServiceException.class,
                () -> service.countPaidPlayersForActivityCancel(ACTIVITY_ID, PREVIOUS_OWNER));
    }

    @Test
    void unrelatedMemberStillCannotCancel() {
        TeamGroupServiceImpl service = service(clubActivity(PREVIOUS_OWNER), club(CURRENT_OWNER));

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelActivityByClubOperator(ACTIVITY_ID, UNRELATED, UNRELATED, "场地不可用"));
        assertEquals("无权操作该活动", denied.getMessage());
        verify(activityMapper, never()).updateCmsActivity(any(CmsActivity.class));
    }

    @Test
    void merchantActivityWithoutClubStillOnlyCancellableByCreator() {
        // 商家活动(club_id 为空)的既有行为不变:只有创建者本人能取消。
        CmsActivity merchantActivity = clubActivity(PREVIOUS_OWNER);
        merchantActivity.setClubId(null);
        TeamGroupServiceImpl service = service(merchantActivity, club(CURRENT_OWNER));

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelActivityByOwner(ACTIVITY_ID, CURRENT_OWNER, "场地不可用"));
        assertEquals("无权操作该活动", denied.getMessage());

        ActivityCancelResult result = service.cancelActivityByOwner(ACTIVITY_ID, PREVIOUS_OWNER, "场地不可用");
        assertEquals(0, result.getRefundedOrders());
        verify(activityMapper).updateCmsActivity(any(CmsActivity.class));
    }

    // ---------- RV-p5 5-07 残留:结束主题的兜底取消也不能再传主题作者 ----------

    /**
     * 场景:A 写的主题,A 把俱乐部转让给 B;B 后来开了非系列场次(没有 occurrence 行),
     * B 结束主题时这一场走 endTopic 的兜底取消链路。
     *
     * <p>修复前兜底把**主题作者 A** 当 owner 传下去:活动创建人是 B,当前主理人也是 B,
     * A 两个都不是 ⇒「无权操作该活动」,这一场进 failedSessions、钱不退。
     */
    @Test
    void newClubOwnerEndsTopicAndCancelsRefundsHisNonSeriesSession() {
        CmsActivity session = clubActivity(CURRENT_OWNER);
        ClubTopicSettingServiceImpl topicSetting = topicSetting(topicEndTeamGroupWithRefund(session), session);

        Map<String, Object> out = topicSetting.endTopic(
                CURRENT_OWNER, "新任主理人", CLUB_ID, TOPIC_ID, null);

        assertEquals(Collections.emptyList(), out.get("failedSessions"),
                "★转让后新主理人开的场次结束主题时不能报「无权操作该活动」");
        assertEquals(1, out.get("cancelledSessions"));
        assertEquals(1, out.get("refundedOrders"), "这一场已付款的报名必须真的走了退款");
        ArgumentCaptor<CmsActivity> update = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(update.capture());
        assertNotNull(update.getValue().getCancelTime(), "活动必须被置为取消态");
    }

    private ClubTopicSettingServiceImpl topicSetting(TeamGroupServiceImpl teamGroup, CmsActivity session) {
        IClubAccessService accessService = mock(IClubAccessService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IClubEventOpsService eventOpsService = mock(IClubEventOpsService.class);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setClubId(CLUB_ID);
        topic.setMemberId(PREVIOUS_OWNER); // 主题是转让前的旧主理人写的
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(accessService.resolve(CURRENT_OWNER, CLUB_ID, null)).thenReturn(new ClubAccessContext(
                CURRENT_OWNER, CLUB_ID, CURRENT_OWNER, null,
                new LinkedHashSet<>(Collections.singletonList(ClubRoleCode.CLUB_OWNER))));
        when(activityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.singletonList(session));
        // 没有 occurrence 行 ⇒ event-ops 那条路走不通,逼进程走兜底取消
        when(eventOpsService.cancelOccurrence(anyLong(), any(), any(ClubEventCancellationRequest.class)))
                .thenThrow(new ServiceException("场次不存在或不属于当前俱乐部"));

        ClubTopicSettingServiceImpl topicSetting = new ClubTopicSettingServiceImpl();
        ReflectionTestUtils.setField(topicSetting, "clubAccessService", accessService);
        ReflectionTestUtils.setField(topicSetting, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(topicSetting, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(topicSetting, "clubEventOpsService", eventOpsService);
        ReflectionTestUtils.setField(topicSetting, "teamGroupService", teamGroup);
        return topicSetting;
    }

    /** 真 TeamGroupServiceImpl(只 mock 持久化),装一张已付款单以验证兜底链路真的退了钱。 */
    private TeamGroupServiceImpl topicEndTeamGroupWithRefund(CmsActivity session) {
        TeamGroupServiceImpl teamGroup = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(teamGroup,"cancellationTransactions",CancellationPhaseTestSupport.memoryTransactions());
        ReflectionTestUtils.setField(teamGroup,"verifiedPaymentFacts",
                mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class));
        com.chengyinhub.business.mapper.CmsTopicMapper phaseTopics =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        CmsTopic phaseTopic = new CmsTopic(); phaseTopic.setId(TOPIC_ID);
        when(phaseTopics.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(phaseTopic);
        ReflectionTestUtils.setField(teamGroup,"cmsTopicMapper",phaseTopics);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        clubMapper = mock(ClubMapper.class);
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY_ID)).thenReturn(session);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(session);
        when(activityMapper.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);

        CmsRegistration paid = paidRegistration();
        when(registrationMapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(invocation -> {
            CmsRegistration query = invocation.getArgument(0, CmsRegistration.class);
            return Integer.valueOf(2).equals(query.getPaymentStatus())
                    ? Collections.singletonList(paid) : Collections.<CmsRegistration>emptyList();
        });
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(paid.getId())).thenReturn(paid);
        when(registrationMapper.selectCancellationRowsForUpdate(2L,ACTIVITY_ID))
                .thenReturn(Collections.singletonList(paid));
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(club(CURRENT_OWNER));
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID))
                .thenAnswer(call -> clubMapper.selectClubById(CLUB_ID));

        IRefundService refundService = mock(IRefundService.class);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, RefundApplication.class).setId(999L);
            return 1;
        });
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        when(ticketMapper.restoreInventory(anyLong(), anyLong())).thenReturn(1);

        ReflectionTestUtils.setField(teamGroup, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(teamGroup, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(teamGroup, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(teamGroup, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(teamGroup, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(teamGroup, "refundService", refundService);
        ReflectionTestUtils.setField(teamGroup, "refundPayoutService", mock(IRefundPayoutService.class));
        ReflectionTestUtils.setField(teamGroup, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(teamGroup, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(teamGroup, "manualRefundCaseService",
                mock(IActivityCancelManualRefundCaseService.class));
        return teamGroup;
    }

    private CmsRegistration paidRegistration() {
        CmsRegistration r = new CmsRegistration();
        r.setId(900L);
        r.setRegistrationNo("R900");
        r.setMemberId(500L);
        r.setTicketId(TICKET_ID);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY_ID);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(0);
        r.setPointUsed(0L);
        r.setOrderNum(1L);
        r.setPayableAmount(new BigDecimal("68.00"));
        r.setWechatPaymentAmount(new BigDecimal("68.00"));
        return r;
    }
}
