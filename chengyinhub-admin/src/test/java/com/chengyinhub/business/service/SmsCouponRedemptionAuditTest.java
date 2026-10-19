package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsCouponRedemptionAuditTest {
    private SmsCouponHistoryMapper histories;
    private SmsCouponMapper coupons;
    private ISettlementEntryService settlements;
    private BusinessAuditEventMapper audits;
    private SmsCouponHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        histories = mock(SmsCouponHistoryMapper.class);
        coupons = mock(SmsCouponMapper.class);
        settlements = mock(ISettlementEntryService.class);
        audits = mock(BusinessAuditEventMapper.class);
        service = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", histories);
        ReflectionTestUtils.setField(service, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(service, "settlementEntryService", settlements);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", audits);
    }

    @Test
    void redeemedCouponKeepsOwnerForSettlementAndRecordsActualEmployeeActor() {
        SmsCouponHistory history = history();
        SmsCoupon coupon = coupon();
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(history);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon);
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(1);
        when(coupons.incrementUseCount(30L)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        ISmsCouponHistoryService.RedemptionResult result = service.redeemCoupon(
                301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN");

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED, result);
        verify(settlements).recordCouponVerification(history, coupon);
        ArgumentCaptor<BusinessAuditEvent> event = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(event.capture());
        assertEquals("MERCHANT", event.getValue().getDomainType());
        assertEquals(11L, event.getValue().getDomainId());
        assertEquals(9001L, event.getValue().getOwnerMemberId());
        assertEquals(7002L, event.getValue().getActorMemberId());
        assertEquals("MERCHANT_CHECKIN", event.getValue().getActorRoleCode());
        assertEquals("COUPON_REDEEM", event.getValue().getAction());
        assertEquals("SMS_COUPON_HISTORY", event.getValue().getTargetType());
        assertEquals(301L, event.getValue().getTargetId());
        assertEquals(30L, event.getValue().getRelationId());
        assertEquals("{\"useStatus\":0}", event.getValue().getBeforeJson());
        assertEquals("{\"useStatus\":1}", event.getValue().getAfterJson());
        assertTrue(event.getValue().getRequestId().matches("coupon-redeem:301:[0-9a-f]{32}"));
    }

    @Test
    void auditWriteFailureFailsTheTransactionalRedemption() {
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(history());
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(1);
        when(coupons.incrementUseCount(30L)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        assertEquals("优惠券核销审计写入失败", error.getMessage());
    }

    @Test
    void alreadyUsedReplayDoesNotCreateAnotherAuditEvent() {
        SmsCouponHistory history = history();
        history.setUseStatus(1);
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(history);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());

        assertEquals(ISmsCouponHistoryService.RedemptionResult.ALREADY_USED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void merchantScopeCannotRedeemLegacyClubCouponEvenWhenOwnerMatches() {
        SmsCoupon legacyClub = coupon();
        legacyClub.setScopeType(null);
        legacyClub.setMerchantId(null);
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(history());
        when(coupons.selectSmsCouponById(30L)).thenReturn(legacyClub);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.FORBIDDEN,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
    }

    @Test
    void legacyClubScopeCannotRedeemMerchantCouponEvenWhenOwnerMatches() {
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(history());
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());

        assertEquals(ISmsCouponHistoryService.RedemptionResult.FORBIDDEN,
                service.redeemCoupon(301L, 9001L, null, 9001L, "club"));

        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
    }

    @Test
    void employeeCannotRedeemCouponFromAnotherMerchantRowUnderSameOwner() {
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(history());
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());

        assertEquals(ISmsCouponHistoryService.RedemptionResult.FORBIDDEN,
                service.redeemCoupon(301L, 9001L, 12L, 7002L, "MERCHANT_CHECKIN"));

        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
    }

    @Test
    void merchantActorCannotClaimLegacyClubDomainByOmittingMerchantId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.redeemCoupon(301L, 9001L, null, 7002L, "MERCHANT_CHECKIN"));

        assertEquals("优惠券核销作用域与操作者角色不匹配", error.getMessage());
        verify(histories, never()).selectSmsCouponHistoryById(any());
        verify(histories, never()).selectSmsCouponHistoryByIdForUpdate(any());
    }

    @Test
    void clubActorCannotClaimMerchantDomainBySupplyingMerchantId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.redeemCoupon(301L, 9001L, 11L, 9001L, "club"));

        assertEquals("优惠券核销作用域与操作者角色不匹配", error.getMessage());
        verify(histories, never()).selectSmsCouponHistoryById(any());
        verify(histories, never()).selectSmsCouponHistoryByIdForUpdate(any());
    }

    private static SmsCouponHistory history() {
        SmsCouponHistory row = new SmsCouponHistory();
        row.setId(301L);
        row.setCouponId(30L);
        row.setUseStatus(0);
        return row;
    }

    private static SmsCoupon coupon() {
        SmsCoupon row = new SmsCoupon();
        row.setId(30L);
        row.setMemberId(9001L);
        row.setScopeType(1);
        row.setMerchantId(11L);
        return row;
    }
}
