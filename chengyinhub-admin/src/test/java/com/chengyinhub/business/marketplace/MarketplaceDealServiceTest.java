package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.MarketplaceMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceDealServiceTest {
    private MarketplaceMapper mapper;
    private ISmsCouponHistoryService history;
    private MarketplaceDealService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MarketplaceMapper.class);
        history = mock(ISmsCouponHistoryService.class);
        service = new MarketplaceDealService(mapper, history);
    }

    @Test
    void boxMustBeValidAndCitySized() {
        assertThrows(IllegalArgumentException.class, () -> service.dealsInBox(null, 31.3, 121.4, 121.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.dealsInBox(31.3, 31.2, 121.4, 121.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.dealsInBox(Double.NaN, 31.2, 121.4, 121.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.dealsInBox(-91.0, 31.2, 121.4, 121.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.dealsInBox(30.0, 31.0, 121.4, 121.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.dealsInBox(31.2, 31.3, 120.0, 121.5, 1L));
        verify(mapper, never()).selectDealsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyInt());
        // A client box clamped to ±0.25° around a center must pass despite rounding.
        double lat = 44.123456789, lng = 125.987654321;
        service.dealsInBox(lat - 0.25, lat + 0.25, lng - 0.25, lng + 0.25, 1L);
    }

    @Test
    void dealsNormaliseClaimedToBooleanAndCapRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>(); a.put("couponId", 1L); a.put("claimed", 1L); rows.add(a);
        Map<String, Object> b = new LinkedHashMap<>(); b.put("couponId", 2L); b.put("claimed", 0); rows.add(b);
        when(mapper.selectDealsInBox(31.2, 31.3, 121.4, 121.5, 7L, MarketplaceDealService.MAX_ROWS)).thenReturn(rows);
        List<Map<String, Object>> out = service.dealsInBox(31.2, 31.3, 121.4, 121.5, 7L);
        assertEquals(Boolean.TRUE, out.get(0).get("claimed"));
        assertEquals(Boolean.FALSE, out.get(1).get("claimed"));
    }

    @Test
    void claimRefusesWhenNotAnEnabledDeal() {
        when(mapper.selectEnabledDealCouponForUpdate(5L)).thenReturn(null);
        DealClaimException e = assertThrows(DealClaimException.class, () -> service.claim(7L, 5L));
        assertEquals(DealClaimException.Reason.UNAVAILABLE, e.getReason());
        verify(mapper, never()).insertClaimIgnore(anyLong(), anyLong());
        verify(history, never()).grantCouponWithReceipt(anyLong(), any());
    }

    @Test
    void claimNeedsSignIn() {
        assertEquals(DealClaimException.Reason.SIGN_IN,
                assertThrows(DealClaimException.class, () -> service.claim(0L, 5L)).getReason());
        assertEquals(DealClaimException.Reason.SIGN_IN,
                assertThrows(DealClaimException.class, () -> service.claim(null, 5L)).getReason());
    }

    @Test
    void secondClaimReturnsTheFirstWithoutIssuingAgain() {
        when(mapper.selectEnabledDealCouponForUpdate(5L)).thenReturn(coupon(5L));
        when(mapper.insertClaimIgnore(5L, 7L)).thenReturn(0);
        when(mapper.selectClaimHistoryId(5L, 7L)).thenReturn(88L);
        Map<String, Object> receipt = service.claim(7L, 5L);
        assertEquals(88L, receipt.get("historyId"));
        assertEquals(Boolean.TRUE, receipt.get("alreadyClaimed"));
        verify(history, never()).grantCouponWithReceipt(anyLong(), any());
    }

    @Test
    void soldOutThrowsSoTheClaimRowRollsBack() {
        SmsCoupon coupon = coupon(5L);
        when(mapper.selectEnabledDealCouponForUpdate(5L)).thenReturn(coupon);
        when(mapper.insertClaimIgnore(5L, 7L)).thenReturn(1);
        when(history.grantCouponWithReceipt(7L, coupon)).thenReturn(null);
        DealClaimException e = assertThrows(DealClaimException.class, () -> service.claim(7L, 5L));
        assertEquals(DealClaimException.Reason.SOLD_OUT, e.getReason());
        verify(mapper, never()).markClaimDelivered(anyLong(), anyLong(), anyLong());
        // RuntimeException + @Transactional(rollbackFor = Exception.class): the inserted claim row is rolled back.
        assertTrue(RuntimeException.class.isAssignableFrom(DealClaimException.class));
    }

    @Test
    void firstClaimIssuesThroughTheAtomicStockPathAndRecordsTheHistory() {
        SmsCoupon coupon = coupon(5L);
        when(mapper.selectEnabledDealCouponForUpdate(5L)).thenReturn(coupon);
        when(mapper.insertClaimIgnore(5L, 7L)).thenReturn(1);
        when(history.grantCouponWithReceipt(7L, coupon)).thenReturn(99L);
        when(mapper.markClaimDelivered(5L, 7L, 99L)).thenReturn(1);
        Map<String, Object> receipt = service.claim(7L, 5L);
        assertEquals(5L, receipt.get("couponId"));
        assertEquals(99L, receipt.get("historyId"));
        assertFalse((Boolean) receipt.get("alreadyClaimed"));
        verify(mapper).markClaimDelivered(eq(5L), eq(7L), eq(99L));
    }

    @Test
    void claimTransactionRollsBackOnAnyException() throws Exception {
        org.springframework.transaction.annotation.Transactional tx = MarketplaceDealService.class
                .getMethod("claim", Long.class, Long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertTrue(java.util.Arrays.asList(tx.rollbackFor()).contains(Exception.class));
    }

    private static SmsCoupon coupon(Long id) {
        SmsCoupon c = new SmsCoupon();
        c.setId(id);
        c.setStatus(1);
        return c;
    }
}
