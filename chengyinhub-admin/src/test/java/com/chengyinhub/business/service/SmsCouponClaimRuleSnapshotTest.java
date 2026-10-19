package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Date;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F0572:领取在券行锁内判可发、冻结规则;核销结算读领取时冻结的面额(旧券读旧规则)。
 */
class SmsCouponClaimRuleSnapshotTest {
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

    private static SmsCoupon coupon(int status, String amount, int version) {
        SmsCoupon row = new SmsCoupon();
        row.setId(30L);
        row.setMemberId(9001L);
        row.setScopeType(1);
        row.setMerchantId(11L);
        row.setStatus(status);
        row.setPublishCount(100L);
        row.setAmount(new BigDecimal(amount));
        row.setMinPoint(new BigDecimal("50.00"));
        row.setStartTime(new Date(1_000L));
        row.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        row.setRuleVersion(version);
        return row;
    }

    @Test
    void stopIssueCommittedBeforeClaimLockBlocksTheClaim() {
        // 调用方手里是停发前读到的券(进行中);锁内当前读已是停发
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(4, "20.00", 2));

        assertNull(service.grantCouponWithReceipt(5001L, coupon(1, "20.00", 1)));

        verify(coupons, never()).tryIncrementReceiveCount(any());
        verify(coupons, never()).incrementReceiveCount(any());
        verify(histories, never()).insertSmsCouponHistory(any());
    }

    @Test
    void claimFreezesTheLockedRuleNotTheCallersStaleCopy() {
        when(coupons.selectCouponRuleStateForUpdate(30L)).thenReturn(coupon(1, "30.00", 5));
        when(coupons.tryIncrementReceiveCount(30L)).thenReturn(1);
        when(histories.insertSmsCouponHistory(any())).thenAnswer(i -> {
            ((SmsCouponHistory) i.getArgument(0)).setId(8801L);
            return 1;
        });

        assertNotNull(service.grantCouponWithReceipt(5001L, coupon(1, "20.00", 4)));

        ArgumentCaptor<SmsCouponHistory> granted = ArgumentCaptor.forClass(SmsCouponHistory.class);
        verify(histories).insertSmsCouponHistory(granted.capture());
        assertEquals(Integer.valueOf(5), granted.getValue().getRuleVersion());
        assertEquals(new BigDecimal("30.00"), granted.getValue().getRuleAmount());
        assertEquals(new BigDecimal("50.00"), granted.getValue().getRuleMinPoint());
    }

    @Test
    void redemptionSettlesAtClaimTimeAmountAfterFaceValueChange() {
        SmsCouponHistory history = new SmsCouponHistory();
        history.setId(301L);
        history.setCouponId(30L);
        history.setMemberId(5001L);
        history.setUseStatus(0);
        history.setDelFlag(0);
        history.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        when(histories.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon(1, "99.00", 6)); // 后来改成 99
        when(histories.claimUnusedCoupon(eq(301L), any())).thenReturn(1);
        when(histories.selectClaimRuleAmount(301L)).thenReturn(new BigDecimal("20.00")); // 领取时 20
        when(coupons.incrementUseCount(30L)).thenReturn(1);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);

        assertEquals(ISmsCouponHistoryService.RedemptionResult.REDEEMED,
                service.redeemCoupon(301L, 9001L, 11L, 7002L, "MERCHANT_OWNER"));

        ArgumentCaptor<SmsCoupon> settled = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(settlements).recordCouponVerification(eq(history), settled.capture());
        assertEquals(new BigDecimal("20.00"), settled.getValue().getAmount());
        assertEquals(Long.valueOf(9001L), settled.getValue().getMemberId());
    }
}
