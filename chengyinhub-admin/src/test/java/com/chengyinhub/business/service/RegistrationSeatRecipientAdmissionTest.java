package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.domain.SeatRecipientFact;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.support.CurrentPlayRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 原准入点的受让人合并：已领取才可玩，原已付行/金额/退款归属不动；
 * 待领取、退款、礼品扣住都不能借道。
 */
class RegistrationSeatRecipientAdmissionTest {

    private CmsActivityMapper activities;
    private CmsRegistrationMapper registrations;
    private IMarketingGiftPlayEligibility gift;
    private IRegistrationSeatRecipientEligibility seats;
    private CmsRegistration order;
    private CmsActivity activity;

    @BeforeEach
    void setup() {
        activities = mock(CmsActivityMapper.class);
        registrations = mock(CmsRegistrationMapper.class);
        gift = mock(IMarketingGiftPlayEligibility.class);
        seats = mock(IRegistrationSeatRecipientEligibility.class);
        order = new CmsRegistration();
        order.setId(31L); order.setOwnerType(2L); order.setOwnerId(61L); order.setMemberId(51L);
        order.setOrderNum(3L); order.setRegistrationStatus(2); order.setPaymentStatus(2);
        order.setPurchaseKind(1); order.setPayableAmount(new java.math.BigDecimal("30.00"));
        activity = new CmsActivity();
        activity.setId(61L); activity.setTopicId(41L); activity.setDelFlag(0);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86_400_000L));

        when(activities.selectCmsActivityByIdForUpdate(61L)).thenReturn(activity);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            return Long.valueOf(51L).equals(probe.getMemberId()) ? List.of(order) : List.of();
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(31L)).thenReturn(order);
        when(registrations.selectCmsRegistrationById(31L)).thenReturn(order);
        when(gift.playFacts(anyLong(), anyLong(), anyLong())).thenReturn(List.of());
        when(seats.claimedUnderRegistrationLock(any(), eq(99L), eq(order))).thenReturn(true);
    }

    @Test
    void aClaimedIncludedSeatAdmitsTheRecipientOnTheOriginalPaidRow() {
        when(seats.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of(fact(31L, 2, 99L)));

        CmsRegistration admitted = CurrentPlayRegistration.require(activities, registrations,
                99L, 2L, 61L, 31L, new Date(), false, gift, seats);

        assertSame(order, admitted);
        assertEquals(Long.valueOf(51L), admitted.getMemberId(), "财务归属仍是购票人");
        assertEquals(Integer.valueOf(2), admitted.getPaymentStatus(), "不改原支付事实");
        assertEquals(Long.valueOf(3L), admitted.getOrderNum(), "不改冻结席位数量");
        assertEquals(new java.math.BigDecimal("30.00"), admitted.getPayableAmount());
        verify(registrations, never()).selectCmsRegistrationByIdForUpdate(99L);
    }

    @Test
    void pendingInvitationAloneNeverBecomesAdmission() {
        when(seats.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of());

        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> CurrentPlayRegistration.require(activities, registrations,
                        99L, 2L, 61L, 31L, new Date(), false, gift, seats));
        assertTrue(denied.getMessage().contains("报名"));
    }

    @Test
    void releaseWinningTheRegistrationLockInvalidatesAnEarlierClaimedSnapshot() {
        when(seats.playFacts(99L, 2L, 61L)).thenReturn(List.of(fact(31L, 2, 99L)));
        when(seats.claimedUnderRegistrationLock(any(), eq(99L), eq(order))).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> CurrentPlayRegistration.require(activities, registrations,
                        99L, 2L, 61L, 31L, new Date(), false, gift, seats));
        org.mockito.InOrder locks = inOrder(activities, registrations, seats);
        locks.verify(activities).selectCmsActivityByIdForUpdate(61L);
        locks.verify(seats).playFacts(99L, 2L, 61L);
        locks.verify(registrations).selectCmsRegistrationByIdForUpdate(31L);
        locks.verify(seats).claimedUnderRegistrationLock(any(), eq(99L), eq(order));
    }

    @Test
    void currentLockingInvitationReadRejectsReleasedAndChangedBuyerFacts() {
        com.chengyinhub.business.mapper.RegistrationSeatInvitationMapper mapper =
                mock(com.chengyinhub.business.mapper.RegistrationSeatInvitationMapper.class);
        com.chengyinhub.business.service.impl.RegistrationSeatRecipientEligibility eligibility =
                new com.chengyinhub.business.service.impl.RegistrationSeatRecipientEligibility(mapper);
        com.chengyinhub.business.domain.RegistrationSeatInvitation row =
                new com.chengyinhub.business.domain.RegistrationSeatInvitation();
        row.setId(900L); row.setRegistrationId(31L); row.setSeatNo(2);
        row.setOwnerMemberId(51L); row.setOwnerType(2L); row.setOwnerId(61L);
        row.setInviteeMemberId(99L); row.setStatus(1);
        when(mapper.selectByIdForUpdate(900L)).thenReturn(row);
        SeatRecipientFact snapshot = fact(31L, 2, 99L);
        assertTrue(eligibility.claimedUnderRegistrationLock(snapshot, 99L, order));
        row.setStatus(4);
        assertFalse(eligibility.claimedUnderRegistrationLock(snapshot, 99L, order));
        row.setStatus(1);
        order.setMemberId(52L);
        assertFalse(eligibility.claimedUnderRegistrationLock(snapshot, 99L, order));
        order.setMemberId(51L);
        row.setSeatNo(4);
        assertFalse(eligibility.claimedUnderRegistrationLock(snapshot, 99L, order));
        verify(mapper, times(4)).selectByIdForUpdate(900L);
    }

    @Test
    void registrationOwnerDriftAndCancellationStillDenyAClaimedSeat() {
        when(seats.playFacts(99L, 2L, 61L)).thenReturn(List.of(fact(31L, 2, 99L)));
        order.setOwnerId(62L);
        assertThrows(IllegalStateException.class, () -> CurrentPlayRegistration.require(activities,
                registrations, 99L, 2L, 61L, 31L, new Date(), false, gift, seats));
        order.setOwnerId(61L);
        order.setRegistrationStatus(3);
        assertThrows(IllegalStateException.class, () -> CurrentPlayRegistration.require(activities,
                registrations, 99L, 2L, 61L, 31L, new Date(), false, gift, seats));
    }

    @Test
    void refundedOriginalRowStopsTheClaimedSeat() {
        order.setPaymentStatus(4);
        when(seats.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of(fact(31L, 2, 99L)));

        assertThrows(IllegalStateException.class,
                () -> CurrentPlayRegistration.require(activities, registrations,
                        99L, 2L, 61L, 31L, new Date(), false, gift, seats));
    }

    @Test
    void pendingGiftOnTheSameRowStillBlocksTheSeatRecipient() {
        GiftPlayFact giftFact = new GiftPlayFact();
        giftFact.setRegistrationId(31L);
        giftFact.setStatus(GiftPlayFact.STATUS_PENDING);
        when(gift.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of(giftFact));
        when(seats.playFacts(eq(99L), eq(2L), eq(61L))).thenReturn(List.of(fact(31L, 2, 99L)));

        assertThrows(IllegalStateException.class,
                () -> CurrentPlayRegistration.require(activities, registrations,
                        99L, 2L, 61L, 31L, new Date(), false, gift, seats));
    }

    @Test
    void giftClaimedByAnotherMemberStillWithholdsTheOriginalPaidRow() {
        GiftPlayFact giftFact = new GiftPlayFact();
        giftFact.setRegistrationId(31L);
        giftFact.setStatus(GiftPlayFact.STATUS_CLAIMED);
        giftFact.setClaimedMemberId(100L);
        when(gift.playFacts(99L, 2L, 61L)).thenReturn(List.of(giftFact));
        when(seats.playFacts(99L, 2L, 61L)).thenReturn(List.of(fact(31L, 2, 99L)));
        assertThrows(IllegalStateException.class, () -> CurrentPlayRegistration.require(
                activities, registrations, 99L, 2L, 61L, 31L, new Date(), false, gift, seats));
    }

    @Test
    void claimedRecipientDoesNotNeedItsOwnBuyerRegistrationForRouteScope() {
        com.chengyinhub.business.mapper.TopicRouteSessionMapper routes =
                mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class);
        com.chengyinhub.business.mapper.CmsTopicMapper topics =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(41L); topic.setPublishedConfigVersion(1L);
        activity.setStatus(1L);
        when(topics.selectCmsTopicById(41L)).thenReturn(topic);
        when(seats.playFacts(99L, 2L, 61L)).thenReturn(List.of(fact(31L, 2, 99L)));
        com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl route =
                new com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl(
                        routes, topics, mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                        mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class), activities, registrations);
        org.springframework.test.util.ReflectionTestUtils.setField(route, "seatRecipientEligibility", seats);
        org.springframework.test.util.ReflectionTestUtils.setField(route, "giftPlayEligibility", gift);
        assertNotNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                route, "currentScope", 99L, 41L, 61L, true));
        assertNotNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                route, "currentScope", 99L, 41L, 61L, false));
    }

    @Test
    void orientRecipientAdmissionLocksOriginalOrderAndRechecksClaimBeforeSubmission() {
        com.chengyinhub.business.service.impl.OrientTaskServiceImpl orient =
                new com.chengyinhub.business.service.impl.OrientTaskServiceImpl();
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(41L);
        topic.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        com.chengyinhub.business.domain.CmsTopicNode node = new com.chengyinhub.business.domain.CmsTopicNode();
        node.setId(71L); node.setTopicId(41L);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(topicService.selectCmsTopicById(41L)).thenReturn(topic);
        when(nodeService.selectCmsTopicNodeById(71L)).thenReturn(node);
        when(activityService.selectCmsActivityById(61L)).thenReturn(activity);
        when(seats.playFacts(99L, 2L, 61L)).thenReturn(List.of(fact(31L, 2, 99L)));
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "topicService", topicService);
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "topicNodeService", nodeService);
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "activityService", activityService);
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "playTeamMapper",
                mock(com.chengyinhub.business.mapper.PlayTeamMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "currentPlayActivityMapper", activities);
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "currentPlayRegistrationMapper", registrations);
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "giftPlayEligibility", gift);
        org.springframework.test.util.ReflectionTestUtils.setField(orient, "seatRecipientEligibility", seats);
        assertNotNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                orient, "requireParticipantScope", 99L, 41L, 61L, "default", 71L));
        verify(registrations).selectCmsRegistrationByIdForUpdate(31L);
        verify(seats).claimedUnderRegistrationLock(any(), eq(99L), eq(order));
        when(seats.claimedUnderRegistrationLock(any(), eq(99L), eq(order))).thenReturn(false);
        assertThrows(com.chengyinhub.business.service.support.OrientRejection.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        orient, "requireParticipantScope", 99L, 41L, 61L, "default", 71L));
    }

    @Test
    void buyerAdmissionIsUnchangedWithoutAnySeatInvitation() {
        CmsRegistration admitted = CurrentPlayRegistration.require(activities, registrations,
                51L, 2L, 61L, 31L, new Date(), false, gift, seats);
        assertSame(order, admitted);
        verifyNoInteractions(seats);
    }

    private SeatRecipientFact fact(Long registrationId, Integer seatNo, Long invitee) {
        SeatRecipientFact fact = new SeatRecipientFact();
        fact.setInvitationId(900L);
        fact.setRegistrationId(registrationId);
        fact.setSeatNo(seatNo);
        fact.setInviteeMemberId(invitee);
        return fact;
    }
}
