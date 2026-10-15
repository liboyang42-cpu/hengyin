package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CouponSourceGrantTest {
    private SmsCouponHistoryServiceImpl service;
    private CouponSourceGrantMapper receipts;
    private SmsCouponHistoryMapper histories;
    private SmsCouponMapper coupons;
    private RewardDropLogMapper drops;
    private CouponSourceGrant receipt;
    @BeforeEach
    void setup() {
        service = new SmsCouponHistoryServiceImpl();
        receipts = mock(CouponSourceGrantMapper.class); histories = mock(SmsCouponHistoryMapper.class);
        coupons = mock(SmsCouponMapper.class); drops = mock(RewardDropLogMapper.class);
        ReflectionTestUtils.setField(service, "couponSourceGrantMapper", receipts);
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", histories);
        ReflectionTestUtils.setField(service, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(service, "rewardDropLogMapper", drops);
        RewardDropLog drop = new RewardDropLog(); drop.setId(9L); drop.setMemberId(7L);
        drop.setDropType(3); drop.setParamJson("{\"couponTemplateId\":8}");
        when(drops.selectById(9L)).thenReturn(drop);
        receipt = new CouponSourceGrant(); receipt.setMemberId(7L); receipt.setCouponId(8L);
        when(receipts.selectForUpdate("reward_drop", 9L)).thenReturn(receipt);
    }
    @Test
    void replayReturnsOriginalHistoryWithoutAnyNewInventoryMutation() {
        receipt.setHistoryId(10L);
        assertEquals(10L, service.grantCouponBySource(7L, 8L, "reward_drop", 9L));
        verify(receipts, never()).selectCouponForUpdate(any());
        verify(histories, never()).insertSmsCouponHistory(any());
        verify(coupons, never()).incrementReceiveCount(any());
    }
    @Test
    void legacyDropWithoutIntentIsUnknownAndCannotBlindlyGrant() {
        when(receipts.selectForUpdate("reward_drop", 9L)).thenReturn(null);
        assertNull(service.grantCouponBySource(7L, 8L, "reward_drop", 9L));
        verify(histories, never()).insertSmsCouponHistory(any());
    }
    @Test
    void wrongOwnerAndDifferentOriginalCouponAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.grantCouponBySource(6L, 8L, "reward_drop", 9L));
        assertThrows(IllegalArgumentException.class, () -> service.grantCouponBySource(7L, 88L, "reward_drop", 9L));
        verify(receipts, never()).selectForUpdate(any(), any());
    }
    @Test
    void disabledTemplateLeavesPendingIntentAndNoCoupon() {
        SmsCoupon coupon = new SmsCoupon(); coupon.setId(8L); coupon.setStatus(3);
        when(receipts.selectCouponForUpdate(8L)).thenReturn(coupon);
        assertNull(service.grantCouponBySource(7L, 8L, "reward_drop", 9L));
        verify(receipts, never()).markDelivered(any(), any(), any());
        verify(histories, never()).insertSmsCouponHistory(any());
    }
    @Test
    void successfulGrantMustPersistItsOriginalHistoryReceipt() {
        SmsCoupon coupon = new SmsCoupon(); coupon.setId(8L); coupon.setPublishCount(2L);
        when(receipts.selectCouponForUpdate(8L)).thenReturn(coupon);
        when(coupons.tryIncrementReceiveCount(8L)).thenReturn(1);
        when(histories.insertSmsCouponHistory(any())).thenAnswer(i -> { ((SmsCouponHistory)i.getArgument(0)).setId(10L); return 1; });
        when(receipts.markDelivered("reward_drop", 9L, 10L)).thenReturn(1);
        assertEquals(10L, service.grantCouponBySource(7L, 8L, "reward_drop", 9L));
        verify(coupons).tryIncrementReceiveCount(8L);
        verify(receipts).markDelivered("reward_drop", 9L, 10L);
    }
    @Test
    void missingReceiptWriteThrowsForTransactionRollback() {
        SmsCoupon coupon = new SmsCoupon(); coupon.setId(8L); coupon.setPublishCount(2L);
        when(receipts.selectCouponForUpdate(8L)).thenReturn(coupon);
        when(coupons.tryIncrementReceiveCount(8L)).thenReturn(1);
        when(histories.insertSmsCouponHistory(any())).thenAnswer(i -> { ((SmsCouponHistory)i.getArgument(0)).setId(10L); return 1; });
        assertThrows(IllegalStateException.class, () -> service.grantCouponBySource(7L, 8L, "reward_drop", 9L));
    }
}
