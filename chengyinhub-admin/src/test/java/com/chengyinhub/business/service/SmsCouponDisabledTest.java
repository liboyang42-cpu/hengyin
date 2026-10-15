package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-30:平台「手动失效」(sms_coupon.status=3)必须真的生效 —— 已领未用的券拒核销,发券链路拒发;
 * 两处都不得留下核销/结算/计数/审计/领取记录副作用。
 */
class SmsCouponDisabledTest {
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
    void manuallyDisabledCouponCannotBeRedeemedAndLeavesNoSideEffects() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(history(0));
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(3));

        assertEquals(ISmsCouponHistoryService.RedemptionResult.DISABLED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
        verify(coupons, never()).incrementUseCount(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void alreadyRedeemedCouponStaysAlreadyUsedAfterDisable() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(history(1));
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(3));

        assertEquals(ISmsCouponHistoryService.RedemptionResult.ALREADY_USED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));
    }

    @Test
    void expiredThenManuallyDisabledCouponStillReportsExpired() {
        // COUPON-RPT-17:过期兜底排在手动失效判定之前 —— 已过 endTime 的失效券回「已过期」,
        // 不回「已停用」(商家扫码时那句理由必须是真的),且不放松 C-30 的零副作用要求。
        SmsCouponHistory expired = history(0);
        expired.setEndTime(new Date(System.currentTimeMillis() - 60_000));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(expired);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(3));
        when(histories.expireUnusedCoupon(any(), any())).thenReturn(1);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.EXPIRED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));

        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
        verify(coupons, never()).incrementUseCount(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void activeCouponStillRedeems() {
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(history(0));
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(1));
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(1);
        when(coupons.incrementUseCount(30L)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));
    }

    @Test
    void manuallyDisabledCouponIsNotGrantedAndTouchesNoCountersOrHistory() {
        assertNull(service.grantCouponWithReceipt(5001L, coupon(3)));

        verify(coupons, never()).tryIncrementReceiveCount(any());
        verify(coupons, never()).tryReserveIssuableCoupon(any());
        verify(coupons, never()).incrementReceiveCount(any());
        verify(histories, never()).insertSmsCouponHistory(any());
    }

    @Test
    void activeCouponIsGrantedWithoutStaticQrOrObjectStorage() {
        when(coupons.tryReserveIssuableCoupon(30L)).thenReturn(1);
        when(histories.insertSmsCouponHistory(any())).thenAnswer(invocation -> {
            SmsCouponHistory granted = invocation.getArgument(0);
            assertNull(granted.getQrcodeUrl());
            assertNotNull(granted.getCouponCode());
            granted.setId(8801L);
            return 1;
        });

        assertNotNull(service.grantCouponWithReceipt(5001L, coupon(1)));
        verify(histories).insertSmsCouponHistory(any());
    }

    @Test
    void isCouponNotIssuableForManuallyInvalidatedAndMerchantStoppedOnly() {
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(3));
        when(coupons.selectSmsCouponById(31L)).thenReturn(coupon(1));
        when(coupons.selectSmsCouponById(32L)).thenReturn(coupon(4));
        org.junit.jupiter.api.Assertions.assertTrue(service.isCouponNotIssuable(30L), "平台手动失效(3)不可发");
        org.junit.jupiter.api.Assertions.assertTrue(service.isCouponNotIssuable(32L), "商家停发(4)不可发");
        org.junit.jupiter.api.Assertions.assertFalse(service.isCouponNotIssuable(31L));
        org.junit.jupiter.api.Assertions.assertFalse(service.isCouponNotIssuable(404L), "券不存在走原失败口径,不当失效跳过");
        org.junit.jupiter.api.Assertions.assertFalse(service.isCouponNotIssuable(null));
    }

    @Test
    void merchantStoppedCouponIsNotGrantedButStillRedeemable() {
        // 商家停发(4)=只停新增发放;已领的照常可用、可核销。
        assertNull(service.grantCouponWithReceipt(5001L, coupon(4)),
                "停发的券不得再新增发放");
        verify(coupons, never()).tryIncrementReceiveCount(any());
        verify(coupons, never()).tryReserveIssuableCoupon(any());
        verify(histories, never()).insertSmsCouponHistory(any());

        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(history(0));
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(4));
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(1);
        when(coupons.incrementUseCount(30L)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"),
                "商家停发不影响已领券核销");
    }

    private static SmsCouponHistory history(int useStatus) {
        SmsCouponHistory row = new SmsCouponHistory();
        row.setId(301L);
        row.setCouponId(30L);
        row.setUseStatus(useStatus);
        row.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        return row;
    }

    private static SmsCoupon coupon(int status) {
        SmsCoupon row = new SmsCoupon();
        row.setId(30L);
        row.setMemberId(9001L);
        row.setScopeType(1);
        row.setMerchantId(11L);
        row.setStatus(status);
        row.setPublishCount(100L);
        return row;
    }
}
