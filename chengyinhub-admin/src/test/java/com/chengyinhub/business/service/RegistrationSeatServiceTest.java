package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy multi-quantity topic order; mocks prove caller decisions, not SQL rollback/concurrency. */
class RegistrationSeatServiceTest {
    private RegistrationSeatService service;
    private RegistrationSeatMapper seats;
    private CmsRegistrationMapper registrations;
    private CmsTopicMapper topics;
    private CmsActivityMapper activities;
    private ICmsRegistrationService orders;
    private IClubMemberService clubs;
    private BusinessAuditEventMapper audits;
    private CmsRegistration order;
    private CmsActivity activity;
    private com.chengyinhub.business.access.merchant.IMerchantAccessService merchants;
    private final List<RegistrationSeat> rows = new ArrayList<>();
    private final AtomicReference<CoopGroupMemberVerify> binding = new AtomicReference<>();

    @BeforeEach void setup() {
        rows.clear(); binding.set(null);
        service = new RegistrationSeatService(); seats = mock(RegistrationSeatMapper.class);
        registrations = mock(CmsRegistrationMapper.class); topics = mock(CmsTopicMapper.class);
        activities = mock(CmsActivityMapper.class); orders = mock(ICmsRegistrationService.class);
        clubs = mock(IClubMemberService.class); audits = mock(BusinessAuditEventMapper.class);
        merchants = mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        Object[][] fields = {{"seats", seats}, {"settlementReader", new RegistrationSeatSettlementReader(seats)}, {"registrations", registrations}, {"topics", topics},
                {"activities", activities}, {"registrationService", orders}, {"clubs", clubs}, {"audits", audits}, {"merchants", merchants}};
        for (Object[] field : fields) ReflectionTestUtils.setField(service, (String) field[0], field[1]);
        order = new CmsRegistration(); order.setId(31L); order.setOwnerType(1L); order.setOwnerId(41L);
        order.setMemberId(51L); order.setOrderNum(3L); order.setRegistrationStatus(2); order.setPaymentStatus(2); order.setVerificationStatus(0);
        when(orders.selectCmsRegistrationById(31L)).thenReturn(order);
        when(registrations.selectCmsRegistrationByIdForUpdate(31L)).thenReturn(order);
        when(seats.lockRegistration(31L)).thenReturn(31L);
        CmsTopic topic = new CmsTopic(); topic.setId(41L); topic.setIsSettlement(0);
        when(topics.selectCmsTopicByIdForUpdate(41L)).thenReturn(topic);
        activity = new CmsActivity(); activity.setId(61L); activity.setTopicId(41L); activity.setClubId(71L); activity.setDelFlag(0);
        when(activities.selectCmsActivityByIdForUpdate(61L)).thenReturn(activity);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        when(seats.lockClubOwner(71L)).thenReturn(81L);
        when(clubs.isTopicOwner(41L, 71L, 81L)).thenReturn(true);
        when(seats.selectByRegistrationForUpdate(31L)).thenReturn(rows);
        when(seats.selectGroupForUpdate(31L)).thenAnswer(call -> binding.get());
        doAnswer(call -> {
            RegistrationSeat seat = call.getArgument(0); seat.setId(100L + seat.getSeatNo()); rows.add(seat); return 1;
        }).when(seats).insertSeat(any());
        when(seats.markVerified(any())).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
        when(orders.verifyAndSettleForGroupActivity(order, 81L, 61L)).thenAnswer(call -> {
            order.setVerificationStatus(1);
            CoopGroupMemberVerify group = new CoopGroupMemberVerify(); group.setRegistrationId(31L); group.setTopicId(41L); group.setActivityId(61L);
            group.setVerifierMemberId(81L); group.setVerifyTime(new Date()); binding.set(group); return true;
        });
    }

    @Test void issuingOneSeatCreatesNoFulfillmentAndRefreshKeepsItsIdentity() {
        RegistrationSeat seat = service.prepareOwnedSeat(31L, 51L, 2);
        assertSame(seat, service.prepareOwnedSeat(31L, 51L, 2));
        assertEquals(102L, seat.getId()); assertEquals(0, seat.getStatus()); assertNull(seat.getVerifiedAt());
        assertEquals(1, rows.size()); assertNull(binding.get());
        verify(orders, never()).verifyAndSettleForGroupActivity(any(), any(), any());
        verifyNoInteractions(audits); verify(seats, never()).markVerified(any());
    }
    @Test void missingOrOutOfRangeSeatCannotStandForTheWholeOrder() {
        for (Integer seat : new Integer[]{null, 0, -1, 4}) {
            assertThrowsExactly(ServiceException.class, () -> service.prepareOwnedSeat(31L, 51L, seat));
        }
        assertTrue(rows.isEmpty()); verify(seats, never()).insertSeat(any());
    }
    @Test void anotherBuyerCannotIssueTheOriginalOwnersSeat() {
        ServiceException denied = assertThrowsExactly(ServiceException.class, () -> service.prepareOwnedSeat(31L, 99L, 1));
        assertEquals(Integer.valueOf(403), denied.getCode()); verifyNoInteractions(seats, audits);
    }
    @Test void eachExplicitSeatCountsOnceWhileOneScanNeverVerifiesThree() {
        service.prepareOwnedSeat(31L, 51L, 1); service.prepareOwnedSeat(31L, 51L, 2);
        RegistrationSeatService.Receipt first = service.redeemGroupSeat(31L, 1, 61L, 81L);
        assertFalse(first.replayed()); assertEquals(101L, first.seatId());
        assertEquals(1, rows.stream().filter(row -> row.getStatus() == 1).count());
        RegistrationSeatService.Receipt replay = service.redeemGroupSeat(31L, 1, 61L, 81L);
        assertTrue(replay.replayed()); assertEquals(first.seatId(), replay.seatId()); assertEquals(first.verifiedAt(), replay.verifiedAt());
        RegistrationSeatService.Receipt second = service.redeemGroupSeat(31L, 2, 61L, 81L);
        assertFalse(second.replayed()); assertNotEquals(first.seatId(), second.seatId());
        assertEquals(2, rows.stream().filter(row -> row.getStatus() == 1).count());
        assertEquals(3L, order.getOrderNum());
        verify(orders).verifyAndSettleForGroupActivity(order, 81L, 61L);
        verify(seats, times(2)).markVerified(any()); verify(audits, times(2)).insertBusinessAuditEvent(any());
    }
    @Test void originalReceiptPrecedesLaterPaymentStateButAnotherSeatCannotExecute() {
        service.prepareOwnedSeat(31L, 51L, 1); service.prepareOwnedSeat(31L, 51L, 2);
        RegistrationSeatService.Receipt applied = service.redeemGroupSeat(31L, 1, 61L, 81L);
        order.setPaymentStatus(4); order.setRegistrationStatus(3);
        RegistrationSeatService.Receipt replay = service.redeemGroupSeat(31L, 1, 61L, 81L);
        assertTrue(replay.replayed()); assertEquals(applied.seatId(), replay.seatId()); assertEquals(applied.verifiedAt(), replay.verifiedAt());
        assertEquals(Integer.valueOf(403), assertThrowsExactly(ServiceException.class,
                () -> service.redeemGroupSeat(31L, 2, 61L, 81L)).getCode());
        verify(seats, times(1)).markVerified(any()); verify(audits, times(1)).insertBusinessAuditEvent(any());
    }

    @Test void alreadyVerifiedOrderWithoutSeatEvidenceIsNeverExpanded() {
        order.setVerificationStatus(1);
        ServiceException refusal = assertThrowsExactly(ServiceException.class, () -> service.prepareOwnedSeat(31L, 51L, 1));
        assertEquals(Integer.valueOf(409), refusal.getCode()); assertTrue(rows.isEmpty());
        verify(seats, never()).insertSeat(any()); verifyNoInteractions(audits);
    }
    @Test void foreignGroupAndTransferredClubOwnerCannotConsumeAnotherSeat() {
        service.prepareOwnedSeat(31L, 51L, 1); service.prepareOwnedSeat(31L, 51L, 2);
        service.redeemGroupSeat(31L, 1, 61L, 81L);
        CmsActivity other = new CmsActivity(); other.setId(62L); other.setTopicId(41L); other.setClubId(71L); other.setDelFlag(0);
        when(activities.selectCmsActivityByIdForUpdate(62L)).thenReturn(other);
        assertEquals(Integer.valueOf(409), assertThrowsExactly(ServiceException.class,
                () -> service.redeemGroupSeat(31L, 2, 62L, 81L)).getCode());
        doReturn(82L).when(seats).lockClubOwner(71L);
        assertEquals(Integer.valueOf(403), assertThrowsExactly(ServiceException.class,
                () -> service.redeemGroupSeat(31L, 2, 61L, 81L)).getCode());
        assertEquals(0, rows.get(1).getStatus()); verify(seats, times(1)).markVerified(any());
    }
    @Test void refundFreezeRejectsBeforeTheOriginalAggregateAndSeatMutation() {
        service.prepareOwnedSeat(31L, 51L, 1);
        when(registrations.hasActiveManualRefundCaseForUpdate(31L)).thenReturn(true);
        assertEquals(Integer.valueOf(409), assertThrowsExactly(ServiceException.class,
                () -> service.redeemGroupSeat(31L, 1, 61L, 81L)).getCode());
        verify(seats, never()).markVerified(any()); verifyNoInteractions(audits);
        verify(orders, never()).verifyAndSettleForGroupActivity(any(), any(), any());
    }
    @Test void originalAggregateFailureNeverProducesASeatFactOrAudit() {
        service.prepareOwnedSeat(31L, 51L, 1);
        doThrow(new IllegalStateException("original ledger unavailable")).when(orders)
                .verifyAndSettleForGroupActivity(order, 81L, 61L);
        assertThrowsExactly(IllegalStateException.class, () -> service.redeemGroupSeat(31L, 1, 61L, 81L));
        verify(seats, never()).markVerified(any()); verifyNoInteractions(audits);
        assertEquals(0, rows.get(0).getStatus());
    }
    @Test void factReaderRefusesUnprovenLegacyInsteadOfReportingZeroOrPurchasedQuantity() {
        Date cutoff = new Date();
        when(seats.selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, cutoff)).thenReturn(List.of(31L));
        assertEquals(Integer.valueOf(409), assertThrowsExactly(ServiceException.class,
                () -> service.verifiedSeatsForGroup(41L, 61L, cutoff)).getCode());
        verify(seats, never()).selectVerifiedForGroupForUpdate(any(), any(), any());
    }

    @Test void activitySeatsKeepTheOriginalPayeeAndAccrueTheWholeOrderOnlyOnce() {
        activityOrder();
        service.prepareOwnedSeat(31L, 51L, 1); service.prepareOwnedSeat(31L, 51L, 2);
        var first = service.redeemActivitySeat(31L, 1, 81L);
        var replay = service.redeemActivitySeat(31L, 1, 81L);
        var second = service.redeemActivitySeat(31L, 2, 81L);
        assertEquals(61L, first.activityId()); assertEquals(41L, first.topicId());
        assertEquals(first.seatId(), replay.seatId()); assertTrue(replay.replayed());
        assertTrue(first.registrationFirstTime()); assertFalse(replay.registrationFirstTime()); assertFalse(second.registrationFirstTime());
        assertNotEquals(first.seatId(), second.seatId());
        assertEquals(2, rows.stream().filter(row -> row.getStatus() == 1).count());
        verify(orders).verifyAndSettle(order, 81L, 81L, null, 81L, "ACTIVITY_OWNER");
        verify(orders, never()).verifyAndSettleForGroupActivity(any(), any(), any());
        verify(seats, never()).selectGroupForUpdate(any());
    }
    @Test void activityDelegateMustStillResolveToTheOriginalMerchantOwner() {
        activityOrder();
        service.prepareOwnedSeat(31L, 51L, 1);
        var access = new com.chengyinhub.business.access.merchant.MerchantAccessContext(82L, 91L, 999L, "MERCHANT_CHECKIN");
        when(merchants.require(82L, com.chengyinhub.business.access.merchant.MerchantPermission.VERIFY)).thenReturn(access);
        assertEquals(Integer.valueOf(403), assertThrowsExactly(ServiceException.class,
                () -> service.redeemActivitySeat(31L, 1, 82L)).getCode());
        verify(seats, never()).markVerified(any()); verifyNoInteractions(audits);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }
    @Test void originalActivityDelegateRoleAndPayeeArePassedWithoutChangingAmounts() {
        activityOrder();
        service.prepareOwnedSeat(31L, 51L, 1);
        var access = new com.chengyinhub.business.access.merchant.MerchantAccessContext(82L, 91L, 81L, "MERCHANT_CHECKIN");
        when(merchants.require(82L, com.chengyinhub.business.access.merchant.MerchantPermission.VERIFY)).thenReturn(access);
        when(orders.verifyAndSettle(order, 81L, 81L, null, 82L, "MERCHANT_CHECKIN")).thenAnswer(call -> {
            order.setVerificationStatus(1); return true;
        });
        service.redeemActivitySeat(31L, 1, 82L);
        verify(orders).verifyAndSettle(order, 81L, 81L, null, 82L, "MERCHANT_CHECKIN");
        var audit = org.mockito.ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(audit.capture());
        assertEquals(81L, audit.getValue().getOwnerMemberId()); assertEquals(82L, audit.getValue().getActorMemberId());
        assertEquals("MERCHANT_CHECKIN", audit.getValue().getActorRoleCode());
    }
    @Test void standaloneActivityKeepsItsNullTopicInsteadOfInventingATopicId() {
        activityOrder(); activity.setTopicId(null);
        service.prepareOwnedSeat(31L, 51L, 1);
        var receipt = service.redeemActivitySeat(31L, 1, 81L);
        assertNull(receipt.topicId()); assertEquals(61L, receipt.activityId());
    }
    @Test void uncertainSeatLookupNeverExecutesAndTheBuyerCanReadItsCommittedFact() {
        activityOrder(); service.prepareOwnedSeat(31L, 51L, 1);
        assertNull(service.seatReceipt(31L, 1, null, 81L));
        assertNull(service.seatReceipt(31L, 1, null, 51L));
        verify(seats, never()).markVerified(any()); verifyNoInteractions(audits);
        var original = service.redeemActivitySeat(31L, 1, 81L);
        order.setPaymentStatus(4); order.setRegistrationStatus(3); activity.setCancelTime(new Date());
        var replay = service.seatReceipt(31L, 1, null, 51L);
        var verifierReplay = service.seatReceipt(31L, 1, null, 81L);
        assertEquals(original.seatId(), replay.seatId()); assertEquals(original.verifiedAt(), replay.verifiedAt());
        assertEquals(original.seatId(), verifierReplay.seatId());
        verify(seats, times(1)).markVerified(any()); verify(audits, times(1)).insertBusinessAuditEvent(any());
    }
    @Test void lockedActivityWindowStillRefusesEarlyClubSeatWithoutCallingTheOriginalLedger() {
        activityOrder(); service.prepareOwnedSeat(31L, 51L, 1);
        activity.setStartDate(new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000L));
        assertEquals("俱乐部场次开始前60分钟内才可核销", assertThrowsExactly(ServiceException.class,
                () -> service.redeemActivitySeat(31L, 1, 81L)).getMessage());
        verify(seats, never()).markVerified(any()); verifyNoInteractions(audits);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }

    @Test void lockedCompleteRowRejectsARefundEvenWhenTheOrdinarySeedRemainsPaid() {
        service.prepareOwnedSeat(31L, 51L, 1); service.prepareOwnedSeat(31L, 51L, 2);
        service.redeemGroupSeat(31L, 1, 61L, 81L);
        CmsRegistration refunded = new CmsRegistration(); refunded.setId(31L); refunded.setMemberId(51L);
        refunded.setOwnerType(1L); refunded.setOwnerId(41L); refunded.setOrderNum(3L);
        refunded.setVerificationStatus(1); refunded.setRegistrationStatus(3); refunded.setPaymentStatus(4);
        when(registrations.selectCmsRegistrationByIdForUpdate(31L)).thenReturn(refunded);
        assertEquals(Integer.valueOf(403), assertThrowsExactly(ServiceException.class,
                () -> service.redeemGroupSeat(31L, 2, 61L, 81L)).getCode());
        assertEquals(2, order.getPaymentStatus(), "the ordinary seed deliberately remains stale");
        assertEquals(0, rows.get(1).getStatus()); verify(seats, times(1)).markVerified(any());
        verify(audits, times(1)).insertBusinessAuditEvent(any());
    }

    @Test void signedActivitySeatRemainsInTheExistingHmacTypeField() {
        assertEquals("activity_seat_3", RegistrationSeatService.codeType(2L, 3));
        assertEquals(3, RegistrationSeatService.seatNumber("activity_seat_3"));
        assertNull(RegistrationSeatService.seatNumber("activity_seat_03"));
    }
    private void activityOrder() {
        order.setOwnerType(2L); order.setOwnerId(61L); activity.setMemberId(81L);
        when(orders.verifyAndSettle(order, 81L, 81L, null, 81L, "ACTIVITY_OWNER")).thenAnswer(call -> {
            order.setVerificationStatus(1); return true;
        });
    }

    @Test void signedTypeCarriesExactlyOnePositiveSeatAndDoesNotAcceptOtherQrFamilies() {
        assertEquals("topic_seat_3", RegistrationSeatService.codeType(3));
        assertEquals(3, RegistrationSeatService.seatNumber("topic_seat_3"));
        for (String type : new String[]{null, "topic", "activity", "topic_seat_0", "topic_seat_01", "topic_seat_-1", "topic_seat_2147483648"}) {
            assertNull(RegistrationSeatService.seatNumber(type));
        }
    }
}
