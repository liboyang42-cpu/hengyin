package com.chengyinhub.web.controller.api;

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
    private RegistrationSeatService seats;
    private ICmsRegistrationService orders;
    private CmsRegistration order;
    @BeforeEach void setup() {
        issuer = spy(new ApiVerifyController()); doReturn(51L).when(issuer).getAppUserId();
        scanner = spy(new ApiRegistrationController()); doReturn(81L).when(scanner).getAppUserId();
        seats = mock(RegistrationSeatService.class); orders = mock(ICmsRegistrationService.class);
        ReflectionTestUtils.setField(issuer, "registrationService", orders);
        ReflectionTestUtils.setField(issuer, "registrationSeatService", seats);
        ReflectionTestUtils.setField(issuer, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(issuer, "qrcodeService", mock(QrcodeServiceImpl.class));
        ReflectionTestUtils.setField(issuer, "dynSecret", SECRET);
        ReflectionTestUtils.setField(scanner, "registrationSeatService", seats);
        ReflectionTestUtils.setField(scanner, "dynSecret", SECRET);
        order = new CmsRegistration(); order.setId(31L); order.setOwnerType(1L); order.setOwnerId(41L);
        order.setMemberId(51L); order.setOrderNum(3L); order.setRegistrationStatus(2); order.setPaymentStatus(2);
        when(orders.selectCmsRegistrationById(31L)).thenReturn(order);
        RegistrationSeat seat = new RegistrationSeat(); seat.setId(102L); seat.setRegistrationId(31L); seat.setSeatNo(2); seat.setStatus(0);
        when(seats.prepareOwnedSeat(31L, 51L, 2)).thenReturn(seat);
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
        verify(scanner, never()).scanQrCode(anyString(), anyString(), anyLong());
    }
    @Test void missingExplicitSeatCannotFallBackToAnOrderWideCode() {
        ServiceException refusal = new ServiceException("必须明确原订单中的合法席位，不能用整单码代替多人核销", 403);
        when(seats.prepareOwnedSeat(31L, 51L, null)).thenThrow(refusal);
        assertSame(refusal, assertThrowsExactly(ServiceException.class, () -> issuer.issueDynCode(31L)));
        verify(seats).prepareOwnedSeat(31L, 51L, null);
    }
    @Test void foreignBuyerIsRefusedBeforeSeatIdentityCreation() {
        doReturn(99L).when(issuer).getAppUserId();
        assertNotEquals(200, issuer.issueDynCode(31L, 2).get("code"));
        verifyNoInteractions(seats);
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
