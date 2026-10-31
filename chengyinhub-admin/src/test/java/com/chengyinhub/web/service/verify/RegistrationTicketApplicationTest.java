package com.chengyinhub.web.service.verify;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.web.controller.api.ApiVerifyController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Direct application owner and thin HTTP adapter contracts; not SQL/transaction execution evidence. */
class RegistrationTicketApplicationTest {
    private static final String SECRET = "ticket-application-fixture-signing-value";
    private ICmsRegistrationService orders;
    private RegistrationSeatService seats;
    private ICmsActivityService activities;
    private IPerkLedgerService perks;
    private QrcodeServiceImpl qr;
    private RegistrationTicketApplication application;
    private CmsRegistration order;
    @BeforeEach void setup() {
        orders = mock(ICmsRegistrationService.class); seats = mock(RegistrationSeatService.class);
        activities = mock(ICmsActivityService.class); perks = mock(IPerkLedgerService.class); qr = mock(QrcodeServiceImpl.class);
        application = new RegistrationTicketApplication(orders, seats, activities, perks, qr, SECRET, mock(RegistrationDynCodeLedger.class));
        order = new CmsRegistration(); order.setId(31L); order.setMemberId(51L); order.setOwnerType(2L); order.setOwnerId(61L);
        order.setPaymentStatus(2); order.setRegistrationStatus(2); order.setOrderNum(3L); order.setPurchaseKind(1);
        when(orders.selectCmsRegistrationById(31L)).thenReturn(order);
        CmsActivity activity = new CmsActivity(); activity.setId(61L); activity.setDelFlag(0);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        RegistrationSeat seat = new RegistrationSeat(); seat.setId(102L); seat.setRegistrationId(31L); seat.setSeatNo(2); seat.setStatus(0);
        when(seats.prepareOwnedSeat(31L, 51L, 2)).thenReturn(seat);
        when(qr.generateInlineQrcode(anyString(), eq(300), eq(300))).thenReturn("fixture-inline-png");
    }
    @Test void actorAdmissionRemainsFirstAndDoesNotTouchAnyDependency() {
        for (Long actor : new Long[]{null, 0L, -1L}) {
            AjaxResult response = application.issueDynCode(actor, 31L, 2);
            assertEquals(500, response.get("code")); assertEquals("请先登录", response.get("msg"));
        }
        verifyNoInteractions(orders, seats, activities, perks, qr);
    }
    @Test void originalOwnershipAndPaidStatusChecksStillPrecedeSeatPreparation() {
        assertEquals("无权操作该报名单", application.issueDynCode(99L, 31L, 2).get("msg"));
        order.setPaymentStatus(4);
        assertEquals("订单未完成支付或已结束,无法生成核销码", application.issueDynCode(51L, 31L, 2).get("msg"));
        verifyNoInteractions(seats, activities, perks, qr);
    }
    @Test void missingSeatStillReturnsOnlyChoiceMetadataWithoutPreparingOrSigningASeat() {
        AjaxResult response = application.issueDynCode(51L, 31L, null);
        assertEquals(200, response.get("code")); Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(true, data.get("requiresSeatSelection")); assertEquals(31L, data.get("registrationId")); assertEquals(3L, data.get("seatCount"));
        assertFalse(data.containsKey("code")); assertFalse(data.containsKey("qrcodeUrl"));
        verifyNoInteractions(seats, activities, perks, qr);
    }
    @Test void directOwnerKeepsPreparationActivityAndLocalPngOrderWithTheOriginalSignedIdentityAndTtl() {
        long before = System.currentTimeMillis();
        AjaxResult response = application.issueDynCode(51L, 31L, 2);
        long after = System.currentTimeMillis();
        Map<?, ?> data = (Map<?, ?>) response.get("data"); assertEquals(200, response.get("code"));
        var parsed = VerifyDynCodeUtil.verify((String) data.get("code"), after, SECRET);
        assertTrue(parsed.valid); assertEquals(31L, parsed.regId); assertEquals("activity_seat_2", parsed.type);
        assertEquals(300000L, data.get("ttlMs")); assertEquals(102L, data.get("seatId")); assertEquals(2, data.get("seatNo"));
        assertEquals(0, data.get("seatStatus")); assertEquals("fixture-inline-png", data.get("qrcodeUrl"));
        long expires = (Long) data.get("expiresAt"); assertTrue(expires >= before + 300000L && expires <= after + 300000L);
        InOrder calls = inOrder(orders, seats, activities, qr);
        calls.verify(orders).selectCmsRegistrationById(31L); calls.verify(seats).prepareOwnedSeat(31L, 51L, 2);
        calls.verify(activities).selectCmsActivityById(61L); calls.verify(qr).generateInlineQrcode((String) data.get("code"), 300, 300);
        verifyNoInteractions(perks); verify(qr, never()).generateQrcode(anyString(), anyInt(), anyInt());
    }
    @Test void preparationRefusalStillPropagatesWithoutSigningOrChangingTheErrorProtocol() {
        when(seats.prepareOwnedSeat(31L, 51L, 2)).thenThrow(new ServiceException("原订单状态已变化", 409));
        ServiceException refusal = assertThrowsExactly(ServiceException.class, () -> application.issueDynCode(51L, 31L, 2));
        assertEquals(Integer.valueOf(409), refusal.getCode()); assertEquals("原订单状态已变化", refusal.getMessage());
        verifyNoInteractions(activities, perks, qr);
    }
    @Test void localPngFailureKeepsTheOriginalSignedSuccessWithNullPngRatherThanInventingANewFallback() {
        when(qr.generateInlineQrcode(anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("fixture renderer unavailable"));
        AjaxResult response = application.issueDynCode(51L, 31L, 2); Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(200, response.get("code")); assertNull(data.get("qrcodeUrl"));
        assertTrue(VerifyDynCodeUtil.verify((String) data.get("code"), System.currentTimeMillis(), SECRET).valid);
    }
    @Test void receiptProjectionKeepsUnknownAndReturnsTheSameTerminalObjectWithoutAnExecutionEntry() {
        AjaxResult unknown = application.seatReceipt(51L, 31L, 2, 61L);
        assertEquals(520, unknown.get("code")); assertEquals("原席位核销结果待确认，请勿改选其他席位重发", unknown.get("msg"));
        var receipt = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L, new Date(1700000000000L), true);
        when(seats.seatReceipt(31L, 2, 61L, 51L)).thenReturn(receipt);
        assertSame(receipt, application.seatReceipt(51L, 31L, 2, 61L).get("data"));
        verify(seats, times(2)).seatReceipt(31L, 2, 61L, 51L);
        verify(seats, never()).prepareOwnedSeat(any(), any(), any()); verify(seats, never()).redeemActivitySeat(any(), any(), any());
        verify(seats, never()).redeemGroupSeat(any(), any(), any(), any()); verifyNoInteractions(orders, activities, perks, qr);
    }
    @Test void httpAndCompatibilityEntrancesPassOnlyTheRealActorAndReturnTheUnchangedApplicationResult() {
        RegistrationTicketApplication owner = mock(RegistrationTicketApplication.class);
        ApiVerifyController controller = spy(new ApiVerifyController()); doReturn(77L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "registrationTicketApplication", owner);
        AjaxResult issued = AjaxResult.success("original issue", Map.of("seatNo", 2));
        AjaxResult compat = AjaxResult.success("original selection"); AjaxResult pending = new AjaxResult(520, "original unknown");
        when(owner.issueDynCode(77L, 31L, 2)).thenReturn(issued); when(owner.issueDynCode(77L, 31L, null)).thenReturn(compat);
        when(owner.seatReceipt(77L, 31L, 2, 61L)).thenReturn(pending);
        assertSame(issued, controller.issueDynCode(31L, 2)); assertSame(compat, controller.issueDynCode(31L));
        assertSame(pending, controller.seatReceipt(31L, 2, 61L));
        verify(owner).issueDynCode(77L, 31L, 2); verify(owner).issueDynCode(77L, 31L, null); verify(owner).seatReceipt(77L, 31L, 2, 61L);
    }
    @Test void failedHttpActorAcquisitionStillPassesZeroToTheSameAdmissionOwner() {
        RegistrationTicketApplication owner = mock(RegistrationTicketApplication.class);
        ApiVerifyController controller = spy(new ApiVerifyController()); doThrow(new IllegalStateException("fixture no login")).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "registrationTicketApplication", owner);
        AjaxResult denied = AjaxResult.error("请先登录"); when(owner.issueDynCode(0L, 31L, 2)).thenReturn(denied);
        assertSame(denied, controller.issueDynCode(31L, 2)); verify(owner).issueDynCode(0L, 31L, 2);
    }
}
