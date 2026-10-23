package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O-06 库存守恒 · 后台手工补录领取记录(insertSmsCouponHistory):
 * 必须先走条件占库存、0 行拒写记录;不得再走「先插、再无条件 +1」。
 */
class SmsCouponManualInsertStockTest {
    private SmsCouponHistoryMapper histories;
    private SmsCouponMapper coupons;
    private SmsCouponHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        histories = mock(SmsCouponHistoryMapper.class);
        coupons = mock(SmsCouponMapper.class);
        service = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", histories);
        ReflectionTestUtils.setField(service, "smsCouponMapper", coupons);
    }

    @Test
    void exhaustedStockRejectsWithoutWritingHistory() {
        when(coupons.tryIncrementReceiveCountWithinStock(30L)).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.insertSmsCouponHistory(history(30L)));

        verify(histories, never()).insertSmsCouponHistory(any());
        verify(coupons, never()).incrementReceiveCount(any());
    }

    @Test
    void stockIsClaimedBeforeHistoryIsWritten() {
        when(coupons.tryIncrementReceiveCountWithinStock(30L)).thenReturn(1);
        when(histories.insertSmsCouponHistory(any())).thenReturn(1);

        assertEquals(1, service.insertSmsCouponHistory(history(30L)));

        InOrder order = inOrder(coupons, histories);
        order.verify(coupons).tryIncrementReceiveCountWithinStock(30L);
        order.verify(histories).insertSmsCouponHistory(any());
        verify(coupons, never()).incrementReceiveCount(any());
    }

    @Test
    void historyWriteFailureAfterClaimThrowsSoTransactionRollsBackStock() {
        when(coupons.tryIncrementReceiveCountWithinStock(30L)).thenReturn(1);
        when(histories.insertSmsCouponHistory(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.insertSmsCouponHistory(history(30L)));
    }

    @Test
    void recordWithoutCouponTouchesNoStock() {
        when(histories.insertSmsCouponHistory(any())).thenReturn(1);

        assertEquals(1, service.insertSmsCouponHistory(history(null)));

        verify(coupons, never()).tryIncrementReceiveCountWithinStock(any());
        verify(coupons, never()).incrementReceiveCount(any());
    }

    private static SmsCouponHistory history(Long couponId) {
        SmsCouponHistory h = new SmsCouponHistory();
        h.setCouponId(couponId);
        h.setMemberId(5001L);
        return h;
    }
}
