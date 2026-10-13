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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-1:软删(撤销)的领取记录核销必须给可解释拒绝(券不存在),不能落系统异常;
 * 且零副作用(CAS 不命中,审计/结算/计数都不发生)。
 */
class SmsCouponRedemptionDeletedTest {

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
    void deletedHistoryIsRejectedAsNotFoundWithoutSideEffects() {
        SmsCouponHistory deleted = new SmsCouponHistory();
        deleted.setId(301L);
        deleted.setCouponId(30L);
        deleted.setUseStatus(0);
        deleted.setDelFlag(1);
        deleted.setStartTime(new Date(System.currentTimeMillis() - 86_400_000L));
        deleted.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(deleted);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());
        when(histories.claimUnusedCoupon(any(), any())).thenReturn(0);
        when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(deleted);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_FOUND,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"),
                "软删券必须明确拒绝,不能抛系统异常");

        verify(settlements, never()).recordCouponVerification(any(), any());
        verify(coupons, never()).incrementUseCount(any());
        verify(audits, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void schemaDeletedHistoryIsRejectedEvenWhenExpiryOrUseStatusWouldOtherwiseWin() {
        for (int status : new int[]{0, 1, 2}) {
            SmsCouponHistory row = new SmsCouponHistory();
            row.setId(301L); row.setCouponId(30L); row.setUseStatus(status); row.setDelFlag(2);
            row.setEndTime(new Date(System.currentTimeMillis() - 1000));
            when(histories.selectSmsCouponHistoryById(301L)).thenReturn(row);
            when(histories.selectSmsCouponHistoryByIdForUpdate(301L)).thenReturn(row);
            when(coupons.selectSmsCouponById(30L)).thenReturn(coupon());
            assertEquals(ISmsCouponHistoryService.RedemptionResult.NOT_FOUND,
                    service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_CHECKIN"));
        }
        verify(histories, never()).claimUnusedCoupon(any(), any());
        verify(settlements, never()).recordCouponVerification(any(), any());
    }

    private SmsCoupon coupon() {
        SmsCoupon row = new SmsCoupon();
        row.setId(30L);
        row.setMemberId(9001L);
        row.setScopeType(1);
        row.setMerchantId(11L);
        return row;
    }
}
