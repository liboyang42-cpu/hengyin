package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Scope and lock-order wiring only. No provider or G reversal acceptance. */
class OuterCancellationTopicFenceTest {
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private CmsRegistrationMapper registrations;
    private ClubMapper clubs;
    private IRefundService refunds;
    private TeamGroupServiceImpl team;
    private CmsRegistration registration;
    private CmsActivity activity;
    private CmsTopic topic;
    private ClubEventOpsMapper ops;
    private ClubEventOpsServiceImpl events;
    private ITeamGroupService delegate;

    @BeforeEach void fixtures() {
        activities = mock(CmsActivityMapper.class); topics = mock(CmsTopicMapper.class);
        registrations = mock(CmsRegistrationMapper.class); clubs = mock(ClubMapper.class);
        refunds = mock(IRefundService.class);
        registration = new CmsRegistration(); registration.setId(1L); registration.setRegistrationNo("REG1");
        registration.setMemberId(11L); registration.setOwnerType(1L); registration.setOwnerId(7L);
        registration.setPaymentStatus(2); registration.setRegistrationStatus(2);
        registration.setPurchaseKind(1); registration.setVerificationStatus(1);
        activity = new CmsActivity(); activity.setId(5L); activity.setMemberId(70L); activity.setTopicId(7L); activity.setDelFlag(0);
        topic = new CmsTopic(); topic.setId(7L); topic.setMemberId(70L);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(registration);
        when(activities.selectCmsActivityById(5L)).thenReturn(activity);
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenReturn(activity);
        when(activities.updateCmsActivity(any())).thenReturn(1);
        when(topics.selectCmsTopicById(7L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        team = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(team, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(team, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(team, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(team, "clubMapper", clubs);
        ReflectionTestUtils.setField(team, "refundService", refunds);
        ops = mock(ClubEventOpsMapper.class); delegate = mock(ITeamGroupService.class);
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(70L, 9L, 5L, ClubPermission.ACTIVITY_MANAGE)).thenReturn(
                new ClubAccessContext(70L, 9L, 70L, 5L, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        events = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(), mock(IClubSessionService.class),
                ops, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), mock(IClubRoleAssignmentService.class), delegate);
        ReflectionTestUtils.setField(events, "cancellationActivityMapper", activities);
        ReflectionTestUtils.setField(events, "cancellationTopicMapper", topics);
    }

    @Test void selfRefundHoldsOriginalTopicAndRegistrationBeforeVerifiedEligibilityBranch() {
        assertThrows(RuntimeException.class, () -> team.cancelPaidRegistration(1L, 11L));
        var order = inOrder(topics, registrations);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        verifyNoInteractions(refunds);
    }

    @Test void selfRefundRejectsMemberChangedBeforeTheCurrentRowLockWithoutRefundWrites() {
        CmsRegistration changed = new CmsRegistration(); changed.setId(1L); changed.setRegistrationNo("REG1");
        changed.setMemberId(22L); changed.setOwnerType(1L); changed.setOwnerId(7L);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(changed);
        assertThrows(RuntimeException.class, () -> team.cancelPaidRegistration(1L, 11L));
        verifyNoInteractions(refunds);
        verify(registrations, never()).claimRegistrationRefunded(anyLong(), anyString(), any());
    }

    @Test void ownerRefundUsesCurrentTopicOwnerInsteadOfTheOlderOrdinaryProjection() {
        CmsTopic current = new CmsTopic(); current.setId(7L); current.setMemberId(80L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(current);
        RuntimeException denied = assertThrows(RuntimeException.class, () -> team.cancelPaidRegistrationByOwner(1L, 70L));
        assertTrue(denied.getMessage().contains("无权操作"));
        verify(topics, never()).selectCmsTopicById(anyLong()); verifyNoInteractions(refunds);
    }

    @Test void legitimateActivityOwnerCancellationStartsAtTopicAndKeepsOriginalEmptySalesResult() {
        assertEquals(0, team.cancelActivityByOwner(5L, 70L, "reason").getRefundedOrders());
        var order = inOrder(topics, activities);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        order.verify(activities).updateCmsActivity(any());
        verifyNoInteractions(refunds);
    }

    @Test void transferredClubOwnerIsReadUnderCurrentLockBeforeActivityCancellationWrite() {
        activity.setClubId(9L);
        Club old = new Club(); old.setId(9L); old.setMemberId(70L); old.setDelFlag(0);
        Club current = new Club(); current.setId(9L); current.setMemberId(80L); current.setDelFlag(0);
        when(clubs.selectClubById(9L)).thenReturn(old);
        when(clubs.selectClubByIdForUpdate(9L)).thenReturn(current);
        assertThrows(RuntimeException.class, () -> team.cancelActivityByOwner(5L, 70L, "reason"));
        verify(clubs).selectClubByIdForUpdate(9L); verify(clubs, never()).selectClubById(9L);
        verify(activities, never()).updateCmsActivity(any()); verifyNoInteractions(refunds);
    }

    @Test void standaloneWrapperTakesTopicBeforeSeriesThenDelegatesExactlyOnce() {
        when(delegate.cancelActivityByOwner(5L, 70L, "reason")).thenReturn(new ActivityCancelResult(0, 0, 0));
        assertEquals(0, events.cancelActivityByOwnerWithClubWrapUp(70L, 5L, "reason").getRefundedOrders());
        var order = inOrder(topics, ops, activities, delegate);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(ops).selectSeriesByActivityAnyStatusForUpdate(5L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        order.verify(delegate).cancelActivityByOwner(5L, 70L, "reason");
        verifyNoMoreInteractions(delegate);
    }

    @Test void eventOpsChangedParentFailsBeforeClubLockRolesOrRefundDispatch() {
        activity.setClubId(9L);
        CmsActivity changed = new CmsActivity(); changed.setId(5L); changed.setTopicId(8L); changed.setClubId(9L); changed.setMemberId(70L);
        when(activities.selectCmsActivityByIdForUpdate(5L)).thenReturn(changed);
        ClubEventSeries series = new ClubEventSeries(); series.setId(61L); series.setClubId(9L); series.setTopicId(7L);
        when(ops.selectSeriesByActivityAnyStatusForUpdate(5L)).thenReturn(series);
        ClubEventCancellationRequest request = new ClubEventCancellationRequest(); request.setClubId(9L); request.setActivityId(5L);
        request.setRequestId("cancel-1"); request.setReason("reason");
        assertThrows(RuntimeException.class, () -> events.cancelOccurrence(70L, "owner", request));
        var order = inOrder(topics, ops, activities);
        order.verify(topics).selectCmsTopicByIdForUpdate(7L);
        order.verify(ops).selectSeriesByActivityAnyStatusForUpdate(5L);
        order.verify(activities).selectCmsActivityByIdForUpdate(5L);
        verify(ops, never()).selectClubOwnerForUpdate(anyLong()); verifyNoInteractions(delegate);
    }

    @Test void authorizedTopicCannotAcquireOrCancelAnotherTopicsActivity() {
        activity.setClubId(9L);
        assertThrows(RuntimeException.class,
                () -> events.cancelActivityForAuthorizedTopicCancellation(70L, 8L, 9L, 5L, "reason"));
        verify(topics, never()).selectCmsTopicByIdForUpdate(anyLong());
        verifyNoInteractions(ops, delegate);
    }
}
