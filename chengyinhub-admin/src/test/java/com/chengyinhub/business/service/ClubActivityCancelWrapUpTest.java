package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/**
 * [6-17] /api/activity/cancel 与 /event-ops/cancel 同一套收尾。
 *
 * <p>原先「我的项目 → 取消活动」只调退款核心:属于俱乐部系列场次的活动不终止候补、
 * 不发站内取消通知、不回收领队。这里钉死两条路的后半段完全同源,且三条边界不被破坏:
 * <ul>
 *   <li>非系列(无 series)活动保持原行为,只退款;</li>
 *   <li>操作人不是当前俱乐部管理角色时,不得取消或退款;</li>
 *   <li>场次已被 event-ops 收尾过时,不重复发通知、不重复终止候补。</li>
 * </ul>
 */
class ClubActivityCancelWrapUpTest {

    private static final Long ACTOR = 41L;
    private static final Long OWNER = 42L;
    private static final Long TOPIC = 31L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 71L;
    private static final String REASON = "天气原因取消";

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
        com.chengyinhub.business.mapper.CmsActivityMapper activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(ACTIVITY); activity.setClubId(CLUB); activity.setTopicId(TOPIC); activity.setMemberId(OWNER);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic(); topic.setId(TOPIC);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(activity);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cancellationActivityMapper", activities);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cancellationTopicMapper", topics);
    }

    private void stubSeries(ClubEventOccurrence occurrence) {
        ClubEventSeries series = new ClubEventSeries();
        series.setId(61L);
        series.setClubId(CLUB);
        series.setTopicId(TOPIC);
        series.setStatus(ClubEventSeries.STATUS_ACTIVE);
        series.setVersion(0);
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(series);
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB)).thenReturn(occurrence);
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(accessService.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, OWNER, ACTIVITY,
                        Collections.singleton(ClubRoleCode.CLUB_OWNER)));
    }

    private ClubEventOccurrence occurrence(String status) {
        ClubEventOccurrence row = new ClubEventOccurrence();
        row.setId(81L);
        row.setSeriesId(61L);
        row.setActivityId(ACTIVITY);
        row.setStatus(status);
        row.setVersion(0);
        return row;
    }

    private ClubWaitlistEntry waitlist(Long id, Long memberId, Long ticketId, String state) {
        ClubWaitlistEntry row = new ClubWaitlistEntry();
        row.setId(id);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setTicketId(ticketId);
        row.setMemberId(memberId);
        row.setState(state);
        row.setVersion(0);
        return row;
    }

    private void stubCampaignInserts() {
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
    }

    @Test
    void seriesOccurrence_getsTheSameWrapUpAsEventOpsCancel() {
        stubSeries(occurrence(ClubEventOccurrence.STATUS_ACTIVE));
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Arrays.asList(101L, 102L));
        when(mapper.selectWaitlistRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(201L));
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(2, 1, 1));
        when(mapper.cancelOccurrenceCas(81L, 0)).thenReturn(1);
        when(mapper.selectActiveWaitlistsForUpdate(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(
                waitlist(91L, 201L, 301L, ClubWaitlistEntry.OFFERED)));
        when(mapper.cancelActiveWaitlistCas(anyLong(), eq(0), anyString(), anyLong(),
                eq(ACTIVITY), anyLong())).thenReturn(1);
        when(mapper.restoreWaitlistHold(301L, ACTIVITY)).thenReturn(1);
        stubCampaignInserts();

        ActivityCancelResult result = service.cancelActivityByOwnerWithClubWrapUp(ACTOR, ACTIVITY, REASON);

        assertEquals(Integer.valueOf(2), result.getRefundedOrders());
        // 收尾顺序:受众快照 → 领队回收 → 退款核心 → 场次 CAS → 候补终止 → 通知
        org.mockito.InOrder lifecycle = inOrder(mapper, teamGroupService, roleService);
        lifecycle.verify(mapper).selectRegisteredRecipients(CLUB, ACTIVITY);
        lifecycle.verify(mapper).selectWaitlistRecipients(CLUB, ACTIVITY);
        lifecycle.verify(roleService).revokeEventLeadForCancelledOccurrence(
                ACTOR, CLUB, ACTIVITY, "api-activity-cancel-role-" + ACTIVITY);
        lifecycle.verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments);
        lifecycle.verify(mapper).cancelOccurrenceCas(81L, 0);
        verify(mapper).cancelActiveWaitlistCas(91L, 0, ClubWaitlistEntry.OFFERED, 201L, ACTIVITY, 301L);
        verify(mapper).restoreWaitlistHold(301L, ACTIVITY);

        ArgumentCaptor<ClubEventCampaign> campaigns = ArgumentCaptor.forClass(ClubEventCampaign.class);
        verify(mapper, times(2)).insertCampaign(campaigns.capture());
        assertEquals(Arrays.asList("REGISTERED", "WAITLIST"), Arrays.asList(
                campaigns.getAllValues().get(0).getAudienceType(),
                campaigns.getAllValues().get(1).getAudienceType()));
        assertTrue(campaigns.getAllValues().get(0).getContent().contains(REASON));
        assertEquals(Integer.valueOf(2), campaigns.getAllValues().get(0).getTotalCount());
        assertEquals(Integer.valueOf(1), campaigns.getAllValues().get(1).getTotalCount());
    }

    @Test
    void standaloneActivity_keepsOldBehaviour_refundOnly() {
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(null);
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, ACTOR, ACTOR, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(0, 0, 0));

        ActivityCancelResult result = service.cancelActivityByOwnerWithClubWrapUp(ACTOR, ACTIVITY, REASON);

        assertEquals(Integer.valueOf(0), result.getRefundedOrders());
        verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, ACTOR, ACTOR, REASON, false, payments);
        verify(roleService, never()).revokeEventLeadForCancelledOccurrence(anyLong(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).cancelOccurrenceCas(anyLong(), any());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
        verify(mapper, never()).selectRegisteredRecipients(anyLong(), anyLong());
    }

    @Test
    void formerOwnerAfterTransfer_cannotCancelOrRefundOccurrence() {
        stubSeries(occurrence(ClubEventOccurrence.STATUS_ACTIVE));
        when(accessService.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenThrow(new ServiceException("俱乐部身份无效或权限不足", 403));

        ServiceException denied = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> service.cancelActivityByOwnerWithClubWrapUp(ACTOR, ACTIVITY, REASON));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), eq(ACTOR), eq(ACTOR), anyString(), eq(false), any(RegistrationCancellationPayments.class));
        verify(roleService, never()).revokeEventLeadForCancelledOccurrence(anyLong(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
    }

    @Test
    void authorizedTopicCancellationAfterTransfer_usesCurrentOwnerAndCompletesWrapUp() {
        stubSeries(occurrence(ClubEventOccurrence.STATUS_ACTIVE));
        when(accessService.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenThrow(new ServiceException("旧主理人已失权", 403));
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, OWNER, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(1, 0, 0));
        when(mapper.cancelOccurrenceCas(81L, 0)).thenReturn(1);
        stubCampaignInserts();

        ActivityCancelResult result = service.cancelActivityForAuthorizedTopicCancellation(
                ACTOR, TOPIC, CLUB, ACTIVITY, REASON);

        assertEquals(Integer.valueOf(1), result.getRefundedOrders());
        verify(mapper, times(2)).selectClubOwnerForUpdate(CLUB);
        verify(roleService).revokeEventLeadForCancelledOccurrence(
                OWNER, CLUB, ACTIVITY, "topic-cancel-role-" + ACTIVITY);
        verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, OWNER, REASON, false, payments);
        verify(mapper).cancelOccurrenceCas(81L, 0);
    }

    @Test
    void authorizedTopicCancellationWithoutSeries_usesCurrentClubOwnerForRefund() {
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(null);
        when(mapper.selectActivityTopicForUpdate(ACTIVITY, CLUB)).thenReturn(TOPIC);
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, OWNER, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(1, 0, 0));

        ActivityCancelResult result = service.cancelActivityForAuthorizedTopicCancellation(
                ACTOR, TOPIC, CLUB, ACTIVITY, REASON);

        assertEquals(Integer.valueOf(1), result.getRefundedOrders());
        verify(mapper, times(2)).selectClubOwnerForUpdate(CLUB);
        verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, OWNER, REASON, false, payments);
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), eq(ACTOR), eq(ACTOR), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }

    @Test
    void authorizedTopicCancellationRejectsMismatchedTopicBeforeRefund() {
        stubSeries(occurrence(ClubEventOccurrence.STATUS_ACTIVE));

        ServiceException denied = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> service.cancelActivityForAuthorizedTopicCancellation(
                        ACTOR, TOPIC + 1, CLUB, ACTIVITY, REASON));

        assertEquals(Integer.valueOf(409), denied.getCode());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), anyLong(), anyLong(), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }

    @Test
    void alreadyWrappedOccurrence_doesNotNotifyOrTerminateAgain() {
        stubSeries(occurrence(ClubEventOccurrence.STATUS_CANCELLED));
        when(teamGroupService.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments))
                .thenReturn(new ActivityCancelResult(1, 0, 0));

        ActivityCancelResult result = service.cancelActivityByOwnerWithClubWrapUp(ACTOR, ACTIVITY, REASON);

        assertEquals(Integer.valueOf(1), result.getRefundedOrders());
        verify(mapper, never()).cancelOccurrenceCas(anyLong(), any());
        verify(mapper, never()).insertCampaign(any(ClubEventCampaign.class));
        verify(teamGroupService).cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, REASON, false, payments);
    }

    @Test
    void emptyReasonRejectedBeforeAnyMutation() {
        ServiceException bad = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> service.cancelActivityByOwnerWithClubWrapUp(ACTOR, ACTIVITY, "  "));

        assertEquals(Integer.valueOf(400), bad.getCode());
        verify(teamGroupService, never()).cancelActivityWithObservedPayments(anyLong(), eq(ACTOR), eq(ACTOR), anyString(), eq(false), any(RegistrationCancellationPayments.class));
    }
}
