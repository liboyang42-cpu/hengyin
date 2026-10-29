package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.RegistrationTicketApplication;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual HTTP adapter methods and original HMAC utility; permissions/SQL are separate service tests. */
class RegistrationSeatEndpointTest {
    private static final String SECRET = "seat-fixture-signing-value";
    private ApiVerifyController issuer;
    private ApiRegistrationController scanner;
    private com.chengyinhub.web.service.registration.RegistrationRedemptionApplication scannerApplication;
    private RegistrationSeatService seats;
    private ICmsRegistrationService orders;
    private CmsRegistration order;
    private com.chengyinhub.business.domain.CmsActivity activity;
    private ICmsActivityService activities;
    private IPerkLedgerService perkLedger;
    private QrcodeServiceImpl qrcode;
    private IPointsAwardService points;
    private IGrowthService growth;
    private com.chengyinhub.business.access.merchant.IMerchantAccessService merchants;
    @BeforeEach void setup() {
        issuer = spy(new ApiVerifyController()); doReturn(51L).when(issuer).getAppUserId();
        scanner = spy(new ApiRegistrationController()); doReturn(81L).when(scanner).getAppUserId();
        scannerApplication = spy(new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication());
        ReflectionTestUtils.setField(scanner, "registrationRedemptionApplication", scannerApplication);
        seats = mock(RegistrationSeatService.class); orders = mock(ICmsRegistrationService.class);
        perkLedger = mock(IPerkLedgerService.class); qrcode = mock(QrcodeServiceImpl.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "registrationSeatService", seats);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "registrationService", orders);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "dynSecret", SECRET);
        activities = mock(ICmsActivityService.class);
        wireIssuer(activities);
        activity = new com.chengyinhub.business.domain.CmsActivity(); activity.setId(61L); activity.setTopicId(41L); activity.setMemberId(81L);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "cmsActivityService", activities);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "activityService", activities);
        ReflectionTestUtils.setField(scannerApplication, "topicService", mock(com.chengyinhub.business.service.ICmsTopicService.class));
        points = mock(IPointsAwardService.class); growth = mock(IGrowthService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "pointsAwardService", points);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "growthService", growth);
        merchants = mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), "merchantAccessService", merchants);
        order = new CmsRegistration(); order.setId(31L); order.setOwnerType(1L); order.setOwnerId(41L);
        order.setMemberId(51L); order.setOrderNum(3L); order.setRegistrationStatus(2); order.setPaymentStatus(2);
        when(orders.selectCmsRegistrationById(31L)).thenReturn(order);
        RegistrationSeat seat = new RegistrationSeat(); seat.setId(102L); seat.setRegistrationId(31L); seat.setSeatNo(2); seat.setStatus(0);
        when(seats.prepareOwnedSeat(31L, 51L, 2)).thenReturn(seat);
    }
    private void wireIssuer(ICmsActivityService activityOwner) {
        ReflectionTestUtils.setField(issuer, "registrationTicketApplication", new RegistrationTicketApplication(
                orders, seats, activityOwner, perkLedger, qrcode, SECRET, mock(com.chengyinhub.web.service.verify.RegistrationDynCodeLedger.class)));
    }
    @Test void originalDynamicCodeFormatBindsTheExplicitSeatToTheOriginalRegistration() {
        AjaxResult response = issuer.issueDynCode(31L, 2);
        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        VerifyDynCodeUtil.Result parsed = VerifyDynCodeUtil.verify((String) data.get("code"), System.currentTimeMillis(), SECRET);
        assertTrue(parsed.valid); assertEquals(31L, parsed.regId); assertEquals("topic_seat_2", parsed.type);
        assertEquals(102L, data.get("seatId")); assertEquals(2, data.get("seatNo")); assertEquals(0, data.get("seatStatus"));
        verify(seats).prepareOwnedSeat(31L, 51L, 2); verify(seats, never()).redeemGroupSeat(any(), any(), any(), any());
    }
    @Test void signatureTamperingAndUnrelatedQrTypesNeverReachSeatVerification() {
        String code = VerifyDynCodeUtil.issue(31L, "topic_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        assertNotEquals(200, scanner.scanGroupMemberTicket(code.replace("topic_seat_2", "topic_seat_3"), 61L).get("code"));
        String other = VerifyDynCodeUtil.issue(31L, "activity", System.currentTimeMillis(), 300000, 2, SECRET);
        assertNotEquals(200, scanner.scanGroupMemberTicket(other, 61L).get("code"));
        verifyNoInteractions(seats);
    }
    @Test void groupScannerPassesOnlyServerVerifiedSeatAndTheAuthenticatedActor() {
        String code = VerifyDynCodeUtil.issue(31L, "topic_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        RegistrationSeatService.Receipt receipt = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, new Date(), false);
        when(seats.redeemGroupSeat(31L, 2, 61L, 81L)).thenReturn(receipt);
        assertSame(receipt, scanner.scanGroupMemberTicket(code, 61L).get("data"));
        verify(seats).redeemGroupSeat(31L, 2, 61L, 81L);
        verify(scannerApplication, never()).scanQrCode(any(), anyString(), anyString(), anyLong());
    }
    @Test void missingExplicitSeatCannotFallBackToAnOrderWideCode() {
        AjaxResult response = issuer.issueDynCode(31L);
        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(true, data.get("requiresSeatSelection")); assertEquals(3L, data.get("seatCount"));
        assertFalse(data.containsKey("code")); assertFalse(data.containsKey("qrcodeUrl"));
        verifyNoInteractions(seats);
    }
    @Test void foreignBuyerIsRefusedBeforeSeatIdentityCreation() {
        doReturn(99L).when(issuer).getAppUserId();
        assertNotEquals(200, issuer.issueDynCode(31L, 2).get("code"));
        verifyNoInteractions(seats);
    }
    @Test void activityIssueBindsItsOriginalActivityTypeAndSeat() {
        order.setOwnerType(2L); order.setOwnerId(61L);
        var activity = new com.chengyinhub.business.domain.CmsActivity(); activity.setId(61L);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        wireIssuer(activities);
        Map<?, ?> data = (Map<?, ?>) issuer.issueDynCode(31L, 2).get("data");
        var parsed = VerifyDynCodeUtil.verify((String) data.get("code"), System.currentTimeMillis(), SECRET);
        assertTrue(parsed.valid); assertEquals("activity_seat_2", parsed.type); assertEquals(31L, parsed.regId);
    }
    @Test void dynamicActivitySeatRetainsTheOriginalActivityScanPath() {
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        AjaxResult originalPath = AjaxResult.error("original activity guard");
        doReturn(originalPath).when(scannerApplication).scanQrCode(new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor(81L, false), "activity", code);
        assertSame(originalPath, scanner.scanDynamicCode(code));
        verify(scannerApplication).scanQrCode(new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor(81L, false), "activity", code); verifyNoInteractions(seats);
    }
    @Test void groupScannerCannotRedirectAnActivitySeatIntoAnotherGroup() {
        order.setOwnerType(2L); order.setOwnerId(61L);
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        assertEquals("活动席位不属于当前带队场次", scanner.scanGroupMemberTicket(code, 62L).get("msg"));
        verify(scannerApplication, never()).scanQrCode(any(), anyString(), anyString(), anyLong()); verifyNoInteractions(seats);
    }
    @Test void receiptLookupUnknownNeverCallsAnExecutionEntry() {
        assertEquals(520, issuer.seatReceipt(31L, 2, 61L).get("code"));
        verify(seats).seatReceipt(31L, 2, 61L, 51L);
        verify(seats, never()).redeemActivitySeat(any(), any(), any());
        verify(seats, never()).redeemGroupSeat(any(), any(), any(), any());
    }

    @Test void actualActivityEntryAppliesOriginalOwnerGuardBeforeTheSeatService() {
        order.setOwnerType(2L); order.setOwnerId(61L);
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        var receipt = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, new Date(), false);
        when(seats.redeemActivitySeat(31L, 2, 81L)).thenReturn(receipt);
        assertSame(receipt, scanner.scanDynamicCode(code).get("data"));
        verify(seats).redeemActivitySeat(31L, 2, 81L);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any());
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }
    @Test void originalClubTimeWindowRejectsBeforeAnySeatEffect() {
        order.setOwnerType(2L); order.setOwnerId(61L); activity.setClubId(71L);
        activity.setStartDate(new Date(System.currentTimeMillis() + 2L * 60 * 60 * 1000));
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        assertEquals("俱乐部场次开始前60分钟内才可核销", scanner.scanDynamicCode(code).get("msg"));
        // Authorized original-terminal lookup is read-only; a missing receipt still meets the old window guard.
        verify(seats).verifierSeatReceipt(31L, 2, null, 81L, true);
        verifyNoMoreInteractions(seats); // No prepare/redeem/other seat mutation may follow the rejection.
        verifyNoInteractions(points, growth);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any());
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }
    @Test void foreignMerchantCannotBypassOriginalActivityOwnerScopeWithAValidSeatCode() {
        order.setOwnerType(2L); order.setOwnerId(61L); activity.setMemberId(999L);
        var foreign = new com.chengyinhub.business.access.merchant.MerchantAccessContext(81L, 91L, 88L, "MERCHANT_CHECKIN");
        when(merchants.require(81L, com.chengyinhub.business.access.merchant.MerchantPermission.VERIFY)).thenReturn(foreign);
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        assertEquals("该店无权核销本场玩家票；俱乐部带队请扫描领队团码", scanner.scanDynamicCode(code).get("msg"));
        verifyNoInteractions(seats);
    }
    @Test void onlyFirstRegistrationVerificationKeepsOriginalPointsButEveryReplayCanDeliverQueuedGrowth() {
        order.setOwnerType(2L); order.setOwnerId(61L);
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        Date originalTime = new Date();
        var first = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, originalTime, false, true);
        var replay = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, originalTime, true);
        when(seats.redeemActivitySeat(31L, 2, 81L)).thenReturn(first, replay);
        assertEquals(200, scanner.scanDynamicCode(code).get("code"));
        assertEquals(200, scanner.scanDynamicCode(code).get("code"));
        verify(points, times(1)).award(51L, 11L, 31L, "到店核销");
        verify(growth, times(2)).deliverStoreVerification(31L);
    }

    @Test void activitySeatDatabaseFailureKeepsUnknownAndNeverLeaksSqlOrCreatesAnotherIntent() {
        order.setOwnerType(2L); order.setOwnerId(61L);
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        when(seats.redeemActivitySeat(31L, 2, 81L)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("SELECT private_seat WHERE secret=value"));
        AjaxResult response = scanner.scanDynamicCode(code);
        assertEquals(520, response.get("code"));
        assertEquals("原席位核销结果待确认，请按原席位查询，勿改选其他席位重发", response.get("msg"));
        assertFalse(response.toString().contains("private_seat")); assertFalse(response.containsKey("intentNotExecuted"));
        verify(seats, times(1)).redeemActivitySeat(31L, 2, 81L);
    }

    @Test void originalWholeActivityCodeCannotDefaultToAllPurchasedSeats() {
        order.setOwnerType(2L); order.setOwnerId(61L);
        String code = VerifyDynCodeUtil.issue(31L, "activity", System.currentTimeMillis(), 300000, 1, SECRET);
        assertEquals("多人订单请逐席位出示核销码，整单码不能代替逐人核销", scanner.scanDynamicCode(code).get("msg"));
        verifyNoInteractions(seats);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any());
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }

    @Test void responseLostReplayKeepsTheOriginalSeatFactAndExplicitReplayMessage() {
        String code = VerifyDynCodeUtil.issue(31L, "topic_seat_2", System.currentTimeMillis(), 300000, 1, SECRET);
        Date originalTime = new Date(1700000000000L);
        RegistrationSeatService.Receipt first = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, originalTime, false);
        RegistrationSeatService.Receipt replay = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, originalTime, true);
        when(seats.redeemGroupSeat(31L, 2, 61L, 81L)).thenReturn(first, replay);
        assertEquals("核销成功", scanner.scanGroupMemberTicket(code, 61L).get("msg"));
        AjaxResult recovered = scanner.scanGroupMemberTicket(code, 61L);
        assertEquals("该席位已核销，请勿重复提供权益", recovered.get("msg")); assertSame(replay, recovered.get("data"));
        assertEquals(first.seatId(), replay.seatId()); assertEquals(originalTime, replay.verifiedAt());
    }
}
