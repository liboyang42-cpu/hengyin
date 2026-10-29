package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubAccessServiceImpl;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.domain.vo.ClubEventCancellationStateVO;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubOccurrenceCancellationServiceTest {
    private static final Long ACTOR = 41L;
    private static final Long OWNER = 42L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 71L;
    private static final String REASON = "场地临时关闭";

    private IClubAccessService accessService;
    private ClubEventOpsMapper mapper;
    private IClubRoleAssignmentService roleService;
    private ITeamGroupService teamGroupService;
    private ClubEventOpsServiceImpl service;
    private RegistrationCancellationPayments payments;

    @BeforeEach
    void setUp() {
        accessService = mock(IClubAccessService.class);
        mapper = mock(ClubEventOpsMapper.class);
        roleService = mock(IClubRoleAssignmentService.class);
        teamGroupService = mock(ITeamGroupService.class);
        service = new ClubEventOpsServiceImpl(accessService, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), mapper, mock(ClubMemberMapper.class),
                mock(ClubLeadMapper.class), roleService, teamGroupService);
        CancellationPhaseTestSupport.wireTransactions(service,CancellationPhaseTestSupport.memoryTransactions());
        payments = CancellationPhaseTestSupport.stubEmptyObservation(teamGroupService,ACTIVITY);
        CmsActivityMapper phaseActivities = mock(CmsActivityMapper.class);
        com.chengyinhub.business.mapper.CmsTopicMapper phaseTopics =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsActivity phaseActivity = new com.chengyinhub.business.domain.CmsActivity();
        phaseActivity.setId(ACTIVITY); phaseActivity.setMemberId(OWNER);
        phaseActivity.setClubId(CLUB); phaseActivity.setTopicId(31L);
        com.chengyinhub.business.domain.CmsTopic phaseTopic = new com.chengyinhub.business.domain.CmsTopic();
        phaseTopic.setId(31L);
        when(phaseActivities.selectCmsActivityById(ACTIVITY)).thenReturn(phaseActivity);
        when(phaseActivities.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(phaseActivity);
        when(phaseTopics.selectCmsTopicByIdForUpdate(31L)).thenReturn(phaseTopic);
        ReflectionTestUtils.setField(service,"cancellationActivityMapper",phaseActivities);
        ReflectionTestUtils.setField(service,"cancellationTopicMapper",phaseTopics);
        when(accessService.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_CO_OWNER));
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(series());
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB))
                .thenReturn(occurrence(ClubEventOccurrence.STATUS_ACTIVE));
        when(mapper.selectOccurrenceCancellationState(ACTIVITY, CLUB)).thenReturn(cancelledState());
    }

    @Test
    void coOwnerCancelsExactOccurrenceWithRefundAndTwoImmutableAudienceSnapshots() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Arrays.asList(101L, 102L));
        when(mapper.selectWaitlistRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(201L));
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(2, 1, 1));
        when(mapper.cancelOccurrenceCas(81L, 0)).thenReturn(1);
        ClubWaitlistEntry waiting = waitlist(91L, 201L, 301L, ClubWaitlistEntry.WAITING, null);
        ClubWaitlistEntry offered = waitlist(92L, 202L, 302L, ClubWaitlistEntry.OFFERED, null);
        ClubWaitlistEntry claimed = waitlist(93L, 203L, 303L, ClubWaitlistEntry.CLAIMED, 401L);
        when(mapper.selectActiveWaitlistsForUpdate(CLUB, ACTIVITY))
                .thenReturn(Arrays.asList(waiting, offered, claimed));
        when(mapper.cancelActiveWaitlistCas(anyLong(), eq(0), anyString(), anyLong(),
                eq(ACTIVITY), anyLong())).thenReturn(1);
        when(mapper.restoreWaitlistHold(302L, ACTIVITY)).thenReturn(1);
        AtomicLong ids = new AtomicLong(900L);
        when(mapper.insertCampaign(any(ClubEventCampaign.class))).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertDelivery(any(ClubEventDelivery.class))).thenAnswer(call -> {
            call.getArgument(0, ClubEventDelivery.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertOutbox(anyLong(), any(Date.class))).thenReturn(1);

        ClubEventCancellationResult result = service.cancelOccurrence(
                ACTOR, "co-owner", request("cancel-1", REASON));

        assertEquals(ACTIVITY, result.getActivityId());
        assertEquals(ClubEventOccurrence.STATUS_CANCELLED, result.getOccurrenceStatus());
        assertEquals("ACCEPTED_WITH_MANUAL_REVIEW", result.getRefundStatus());
        assertEquals(Integer.valueOf(2), result.getRefundedOrders());
        assertEquals(Integer.valueOf(1), result.getManualOrders());
        assertEquals(Integer.valueOf(1), result.getClosedPendingOrders());
        assertEquals(Integer.valueOf(2), result.getRegisteredRecipientCount());
        assertEquals(Integer.valueOf(1), result.getWaitlistRecipientCount());
        assertEquals("QUEUED", result.getRegisteredNotificationStatus());
        assertEquals("QUEUED", result.getWaitlistNotificationStatus());

        ArgumentCaptor<ClubEventCampaign> campaigns = ArgumentCaptor.forClass(ClubEventCampaign.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertCampaign(campaigns.capture());
        assertEquals(Arrays.asList("REGISTERED", "WAITLIST"), Arrays.asList(
                campaigns.getAllValues().get(0).getAudienceType(),
                campaigns.getAllValues().get(1).getAudienceType()));
        assertEquals("活动已取消：" + REASON + "。报名与款项处理请查看订单状态。",
                campaigns.getAllValues().get(0).getContent());
        assertEquals(3, campaigns.getAllValues().stream()
                .mapToInt(item -> item.getTotalCount() == null ? -1 : item.getTotalCount()).sum());
        verify(mapper, org.mockito.Mockito.times(3)).insertDelivery(any(ClubEventDelivery.class));
        verify(mapper, org.mockito.Mockito.times(3)).insertOutbox(anyLong(), any(Date.class));
        verify(mapper).restoreWaitlistHold(302L, ACTIVITY);
        verify(mapper, never()).restoreWaitlistHold(303L, ACTIVITY);

        org.mockito.InOrder lifecycle = inOrder(mapper, teamGroupService, roleService);
        lifecycle.verify(mapper).selectRegisteredRecipients(CLUB, ACTIVITY);
        lifecycle.verify(mapper).selectWaitlistRecipients(CLUB, ACTIVITY);
        lifecycle.verify(roleService).revokeEventLeadForCancelledOccurrence(
                ACTOR, CLUB, ACTIVITY, "event-cancel-role-cancel-1");
        lifecycle.verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments);
        lifecycle.verify(mapper).cancelOccurrenceCas(81L, 0);
    }

    /**
     * ★ F-36:open-session 开出来的独立场次没有 series/occurrence,原先第一步就 409。
     * 归属改由 cms_activity 真源判(与 F-34 的状态查询同一口径);退款仍走
     * teamGroupService(按 activityId,与 series 无关),它自己持有「已取消不得重复退」
     * 那道资损闸。无系列=无候补,没有东西要终止。
     */
    @Test
    void standaloneOpenSessionWithoutSeriesCancelsWithRefundAndNotifications() {
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(null);
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB)).thenReturn(null);
        when(mapper.selectActivityTopicForUpdate(ACTIVITY, CLUB)).thenReturn(990028L);
        when(mapper.selectOccurrenceCancellationState(ACTIVITY, CLUB))
                .thenReturn(activeState(), activeState(), cancelledState());
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(101L));
        when(mapper.selectWaitlistRecipients(CLUB, ACTIVITY)).thenReturn(Collections.<Long>emptyList());
        when(mapper.selectActiveWaitlistsForUpdate(CLUB, ACTIVITY))
                .thenReturn(Collections.<ClubWaitlistEntry>emptyList());
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(1, 0, 0));
        AtomicLong ids = new AtomicLong(900L);
        when(mapper.insertCampaign(any(ClubEventCampaign.class))).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertDelivery(any(ClubEventDelivery.class))).thenAnswer(call -> {
            call.getArgument(0, ClubEventDelivery.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertOutbox(anyLong(), any(Date.class))).thenReturn(1);

        ClubEventCancellationResult result = service.cancelOccurrence(
                ACTOR, "co-owner", request("cancel-standalone", REASON));

        assertEquals(ACTIVITY, result.getActivityId());
        assertEquals("REFUND_REQUESTED", result.getRefundStatus());
        assertEquals(Integer.valueOf(1), result.getRefundedOrders());
        assertEquals(Integer.valueOf(1), result.getRegisteredRecipientCount());
        assertEquals(Integer.valueOf(0), result.getWaitlistRecipientCount());
        verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments);
        // 没有 occurrence 行可 CAS —— 真正落「已取消」的是退款服务写的 cms_activity.cancel_time
        verify(mapper, never()).cancelOccurrenceCas(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** ★ 负控:归属闸不准跟着放宽 —— 活动不属于本俱乐部时,退款链一步都不许走。 */
    @Test
    void standaloneActivityOfAnotherClubIsStillRejectedBeforeAnyRefund() {
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(null);
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB)).thenReturn(null);
        when(mapper.selectActivityTopicForUpdate(ACTIVITY, CLUB)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.cancelOccurrence(ACTOR, "co-owner", request("cancel-foreign", REASON)));

        assertEquals("场次不存在或不属于当前俱乐部", error.getMessage());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void eventOnlyRoleCannotCancelEvenIfAnAdapterAccidentallyReturnsItsContext() {
        when(accessService.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.EVENT_LEAD));

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelOccurrence(ACTOR, "event-lead", request("cancel-event", REASON)));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(mapper, never()).selectSeriesByActivityAnyStatusForUpdate(anyLong());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }

    @Test
    void coOwnerRevokedBeforeClubLockCannotCancelWithStaleFirstAccessRead() {
        when(accessService.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_CO_OWNER), access(ClubRoleCode.CLUB_MEMBER));

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.cancelOccurrence(ACTOR, "revoked-co-owner",
                        request("cancel-revoked", REASON)));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accessService, times(2)).require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE);
        verify(roleService, never()).revokeEventLeadForCancelledOccurrence(anyLong(), anyLong(), anyLong(), anyString());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
        verify(mapper, never()).cancelOccurrenceCas(anyLong(), any());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void disabledOwnerOldTokenCannotReachEventCancellationRefundMutation() {
        ClubMapper clubs = mock(ClubMapper.class);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        UmsMemberMapper accounts = mock(UmsMemberMapper.class);
        ClubAccessServiceImpl currentAccountAccess = new ClubAccessServiceImpl();
        ReflectionTestUtils.setField(currentAccountAccess, "clubMapper", clubs);
        ReflectionTestUtils.setField(currentAccountAccess, "clubMemberMapper", mock(ClubMemberMapper.class));
        ReflectionTestUtils.setField(currentAccountAccess, "clubRoleAssignmentMapper",
                mock(ClubRoleAssignmentMapper.class));
        ReflectionTestUtils.setField(currentAccountAccess, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(currentAccountAccess, "umsMemberMapper", accounts);

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(ACTOR);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        com.chengyinhub.business.domain.CmsActivity activity =
                new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(ACTIVITY);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(accounts.lockActiveMemberById(ACTOR)).thenReturn(null);

        ClubEventOpsServiceImpl guardedService = new ClubEventOpsServiceImpl(
                currentAccountAccess, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), mapper, mock(ClubMemberMapper.class),
                mock(ClubLeadMapper.class), roleService, teamGroupService);
        CancellationPhaseTestSupport.wireTransactions(guardedService,CancellationPhaseTestSupport.memoryTransactions());

        ServiceException denied = assertThrows(ServiceException.class,
                () -> guardedService.cancelOccurrence(
                        ACTOR, "disabled-owner", request("cancel-disabled", REASON)));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accounts).lockActiveMemberById(ACTOR);
        verify(mapper, never()).selectSeriesByActivityAnyStatusForUpdate(anyLong());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameRequestReplaysDurableReceiptsWithoutRefundingOrCancellingTwice(boolean legacy) {
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB))
                .thenReturn(occurrence(ClubEventOccurrence.STATUS_CANCELLED));
        when(mapper.selectCampaignByRequest(eq(CLUB), eq(ACTOR), anyString())).thenAnswer(call -> {
            String requestId = call.getArgument(2, String.class);
            return requestId.startsWith("cancel-r-")
                    ? campaign(901L, "REGISTERED", requestId, 2,
                    "活动已取消：" + REASON + (legacy
                            ? "。退款任务已提交处理，请以订单退款状态为准。"
                            : "。报名与款项处理请查看订单状态。"))
                    : campaign(902L, "WAITLIST", requestId, 1,
                    "活动已取消：" + REASON + "。候补已终止，预留名额已释放，无需继续等待。" );
        });

        ClubEventCancellationResult replay = service.cancelOccurrence(
                ACTOR, "co-owner", request("cancel-replay", REASON));

        assertEquals("ALREADY_ACCEPTED", replay.getRefundStatus());
        assertEquals(null, replay.getRefundedOrders());
        assertEquals(Long.valueOf(901L), replay.getRegisteredCampaignId());
        assertEquals(Long.valueOf(902L), replay.getWaitlistCampaignId());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
        verify(roleService, never()).revokeEventLeadForCancelledOccurrence(anyLong(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingReasonUnderSameRequestIdIsRejectedAndNeverRepeatsSideEffects(boolean legacy) {
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB))
                .thenReturn(occurrence(ClubEventOccurrence.STATUS_CANCELLED));
        when(mapper.selectCampaignByRequest(eq(CLUB), eq(ACTOR), anyString())).thenAnswer(call -> {
            String requestId = call.getArgument(2, String.class);
            return requestId.startsWith("cancel-r-")
                    ? campaign(901L, "REGISTERED", requestId, 2,
                    "活动已取消：旧原因" + (legacy
                            ? "。退款任务已提交处理，请以订单退款状态为准。"
                            : "。报名与款项处理请查看订单状态。"))
                    : campaign(902L, "WAITLIST", requestId, 1,
                    "活动已取消：旧原因。候补已终止，预留名额已释放，无需继续等待。" );
        });

        assertThrows(ServiceException.class,
                () -> service.cancelOccurrence(ACTOR, "co-owner", request("cancel-replay", REASON)));

        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }

    @Test
    void cancelledOccurrenceWithUnknownRequestFailsClosedWithoutRepeatingExternalEffects() {
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB))
                .thenReturn(occurrence(ClubEventOccurrence.STATUS_CANCELLED));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.cancelOccurrence(ACTOR, "co-owner", request("brand-new-request", REASON)));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
        verify(roleService, never()).revokeEventLeadForCancelledOccurrence(anyLong(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void independentStatusReadbackConfirmsOccurrenceAndRealActivityCancellation() {
        ClubEventCancellationStateVO state = new ClubEventCancellationStateVO();
        state.setClubId(CLUB);
        state.setActivityId(ACTIVITY);
        state.setOccurrenceStatus(ClubEventOccurrence.STATUS_CANCELLED);
        state.setActivityCancelled(true);
        state.setPublishStatus(0L);
        state.setCancelReason(REASON);
        when(mapper.selectOccurrenceCancellationState(ACTIVITY, CLUB)).thenReturn(state);

        ClubEventCancellationStateVO readback = service.getCancellationState(ACTOR, CLUB, ACTIVITY);

        assertEquals(ClubEventOccurrence.STATUS_CANCELLED, readback.getOccurrenceStatus());
        assertEquals(true, readback.isActivityCancelled());
        assertEquals(Long.valueOf(0L), readback.getPublishStatus());
        verify(accessService).require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE);
    }

    private ClubAccessContext access(String role) {
        return new ClubAccessContext(ACTOR, CLUB, OWNER, ACTIVITY, Collections.singleton(role));
    }

    private ClubEventSeries series() {
        ClubEventSeries row = new ClubEventSeries();
        row.setId(61L);
        row.setClubId(CLUB);
        row.setTopicId(31L);
        row.setDefaultLeadMemberId(51L);
        row.setStatus(ClubEventSeries.STATUS_ACTIVE);
        row.setVersion(0);
        return row;
    }

    private ClubEventOccurrence occurrence(String status) {
        ClubEventOccurrence row = new ClubEventOccurrence();
        row.setId(81L);
        row.setSeriesId(61L);
        row.setActivityId(ACTIVITY);
        row.setOccurrenceAt(new Date());
        row.setDefaultLeadMemberId(51L);
        row.setStatus(status);
        row.setVersion(0);
        return row;
    }

    private ClubWaitlistEntry waitlist(Long id, Long memberId, Long ticketId,
                                        String state, Long registrationId) {
        ClubWaitlistEntry row = new ClubWaitlistEntry();
        row.setId(id);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setTicketId(ticketId);
        row.setMemberId(memberId);
        row.setState(state);
        row.setRegistrationId(registrationId);
        row.setVersion(0);
        return row;
    }

    /** 取消前的独立场次:cms_activity 还没 cancel_time,状态查询给 ACTIVE。 */
    private ClubEventCancellationStateVO activeState() {
        ClubEventCancellationStateVO state = new ClubEventCancellationStateVO();
        state.setClubId(CLUB);
        state.setActivityId(ACTIVITY);
        state.setOccurrenceStatus(ClubEventOccurrence.STATUS_ACTIVE);
        state.setActivityCancelled(false);
        state.setPublishStatus(1L);
        return state;
    }

    private ClubEventCancellationStateVO cancelledState() {
        ClubEventCancellationStateVO state = new ClubEventCancellationStateVO();
        state.setClubId(CLUB);
        state.setActivityId(ACTIVITY);
        state.setOccurrenceStatus(ClubEventOccurrence.STATUS_CANCELLED);
        state.setActivityCancelled(true);
        state.setPublishStatus(0L);
        state.setCancelReason(REASON);
        return state;
    }

    private ClubEventCampaign campaign(Long id, String audience, String requestId,
                                        int total, String content) {
        ClubEventCampaign row = new ClubEventCampaign();
        row.setId(id);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setAudienceType(audience);
        row.setChannel("IN_APP");
        row.setTitle("场次取消通知");
        row.setContent(content);
        row.setStatus(ClubEventCampaign.PROCESSING);
        row.setActorMemberId(ACTOR);
        row.setRequestId(requestId);
        row.setTotalCount(total);
        row.setSuccessCount(0);
        row.setFailedCount(0);
        return row;
    }

    private ClubEventCancellationRequest request(String requestId, String reason) {
        ClubEventCancellationRequest request = new ClubEventCancellationRequest();
        request.setClubId(CLUB);
        request.setActivityId(ACTIVITY);
        request.setReason(reason);
        request.setRequestId(requestId);
        return request;
    }

    /** S531-2:系列关闭(CLOSED)后,已排场次仍须能从 event-ops 取消:退款 + 场次 CAS + 候补终止 + 通知照常收尾。 */
    @Test
    void scheduledOccurrenceOfClosedSeriesCanStillBeCancelled() {
        ClubEventSeries closed = series();
        closed.setStatus(ClubEventSeries.STATUS_CLOSED);
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(closed);
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(101L));
        when(mapper.selectWaitlistRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(201L));
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(1, 0, 0));
        when(mapper.cancelOccurrenceCas(81L, 0)).thenReturn(1);
        ClubWaitlistEntry offered = waitlist(92L, 201L, 302L, ClubWaitlistEntry.OFFERED, null);
        when(mapper.selectActiveWaitlistsForUpdate(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(offered));
        when(mapper.cancelActiveWaitlistCas(anyLong(), eq(0), anyString(), anyLong(),
                eq(ACTIVITY), anyLong())).thenReturn(1);
        when(mapper.restoreWaitlistHold(302L, ACTIVITY)).thenReturn(1);
        AtomicLong ids = new AtomicLong(900L);
        when(mapper.insertCampaign(any(ClubEventCampaign.class))).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertDelivery(any(ClubEventDelivery.class))).thenAnswer(call -> {
            call.getArgument(0, ClubEventDelivery.class).setId(ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertOutbox(anyLong(), any(Date.class))).thenReturn(1);

        ClubEventCancellationResult result = service.cancelOccurrence(
                ACTOR, "co-owner", request("cancel-closed", REASON));

        assertEquals(ClubEventOccurrence.STATUS_CANCELLED, result.getOccurrenceStatus());
        assertEquals("REFUND_REQUESTED", result.getRefundStatus());
        verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments);
        verify(mapper).cancelOccurrenceCas(81L, 0);
        verify(mapper).restoreWaitlistHold(302L, ACTIVITY);
        verify(mapper, times(2)).insertCampaign(any(ClubEventCampaign.class));
    }

    /** S531-2 负控:放开的只有 CLOSED;其他非 ACTIVE 状态的系列仍然 409,退款链一步不走。 */
    @Test
    void occurrenceOfUnknownSeriesStatusIsStillRejectedBeforeRefund() {
        ClubEventSeries odd = series();
        odd.setStatus("ARCHIVED");
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(odd);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.cancelOccurrence(ACTOR, "co-owner", request("cancel-odd", REASON)));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }
}
