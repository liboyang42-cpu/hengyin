package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.vo.MyCouponUsabilityVO;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.service.ISmsCouponService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 已持有券的可用性投影:判定顺序必须与核销服务端闸口一致,否则玩家看到"可用"却被商家拒。
 *
 * <p>特别守住「发券闸 ≠ 核销闸」:商家停发(status=4)只停新增发放,已领的照常可核销;
 * 平台手动失效(status=3)才是已领也不可用。
 */
class CouponUsabilityServiceTest {

    private static final Long MEMBER_ID = 88L;
    private static final long HOUR = 3_600_000L;

    @Test
    void activeCouponWithinWindowIsUsable() {
        SmsCouponHistory row = history(1L, 10L, 0, new Date(System.currentTimeMillis() - HOUR),
                new Date(System.currentTimeMillis() + HOUR));
        MyCouponUsabilityVO vo = single(row, coupon(10L, 1, 30L));

        assertEquals(MyCouponUsabilityVO.USABLE, vo.getReason());
        assertTrue(vo.isUsable());
        assertEquals(30L, vo.getMerchantId(), "商家券要把核销商家回给玩家");
        assertEquals("满100可用,到店出示", vo.getDescription(), "持券人需要知道券条款,但公开奖励投影不下发门槛");
    }

    @Test
    void platformCouponHasNoMerchant() {
        SmsCouponHistory row = history(1L, 10L, 0, null, future());
        MyCouponUsabilityVO vo = single(row, coupon(10L, 0, null));
        assertNull(vo.getMerchantId());
        assertTrue(vo.isUsable());
    }

    @Test
    void usedCouponIsNotUsable() {
        MyCouponUsabilityVO vo = single(
                history(1L, 10L, 1, null, future()), coupon(10L, 1, 30L));
        assertEquals(MyCouponUsabilityVO.USED, vo.getReason());
        assertFalse(vo.isUsable());
    }

    @Test
    void expiredByHistoryWindowIsNotUsable() {
        MyCouponUsabilityVO vo = single(
                history(1L, 10L, 0, new Date(System.currentTimeMillis() - 2 * HOUR),
                        new Date(System.currentTimeMillis() - HOUR)),
                coupon(10L, 1, 30L));
        assertEquals(MyCouponUsabilityVO.EXPIRED, vo.getReason());
    }

    @Test
    void manuallyInvalidatedCouponIsNotUsable() {
        MyCouponUsabilityVO vo = single(
                history(1L, 10L, 0, null, future()), couponWithStatus(10L, 3));
        assertEquals(MyCouponUsabilityVO.DISABLED, vo.getReason());
    }

    @Test
    void stoppedIssuingCouponStaysUsable() {
        // 4=商家停发:只阻新增发放,已领的照常核销(核销闸只看 status=3)
        MyCouponUsabilityVO vo = single(
                history(1L, 10L, 0, null, future()), couponWithStatus(10L, 4));
        assertEquals(MyCouponUsabilityVO.USABLE, vo.getReason());
        assertTrue(vo.isUsable());
    }

    @Test
    void notYetStartedCouponIsNotUsable() {
        MyCouponUsabilityVO vo = single(
                history(1L, 10L, 0, new Date(System.currentTimeMillis() + HOUR), future()),
                coupon(10L, 1, 30L));
        assertEquals(MyCouponUsabilityVO.NOT_STARTED, vo.getReason());
    }

    @Test
    void missingCouponTemplateIsReportedRatherThanCrashing() {
        MyCouponUsabilityVO vo = single(history(1L, 10L, 0, null, future()), null);
        assertEquals(MyCouponUsabilityVO.COUPON_MISSING, vo.getReason());
        assertFalse(vo.isUsable());
        assertNull(vo.getName(), "券查不到时不要编造名称");
    }

    @Test
    void onlyCallersOwnRowsAreProjected() {
        SmsCouponHistoryMapper mapper = mock(SmsCouponHistoryMapper.class);
        SmsCouponHistory other = history(2L, 10L, 0, null, future());
        other.setMemberId(MEMBER_ID + 1);
        when(mapper.selectSmsCouponHistoryList(any())).thenReturn(new ArrayList<>(Arrays.asList(other)));
        CouponUsabilityService service = service(mapper, mock(ISmsCouponService.class));

        assertTrue(service.listMine(MEMBER_ID).isEmpty(), "上游查询条件被改坏也不能投影他人的券");
    }

    @Test
    void emptyWhenNoHeldCoupons() {
        SmsCouponHistoryMapper mapper = mock(SmsCouponHistoryMapper.class);
        when(mapper.selectSmsCouponHistoryList(any())).thenReturn(Collections.emptyList());
        assertTrue(service(mapper, mock(ISmsCouponService.class)).listMine(MEMBER_ID).isEmpty());
        assertTrue(service(mapper, mock(ISmsCouponService.class)).listMine(null).isEmpty());
    }

    // ---------- fixture ----------

    private MyCouponUsabilityVO single(SmsCouponHistory row, SmsCoupon coupon) {
        SmsCouponHistoryMapper mapper = mock(SmsCouponHistoryMapper.class);
        when(mapper.selectSmsCouponHistoryList(any())).thenReturn(new ArrayList<>(Arrays.asList(row)));
        ISmsCouponService coupons = mock(ISmsCouponService.class);
        if (coupon != null) {
            when(coupons.selectSmsCouponById(coupon.getId())).thenReturn(coupon);
        }
        List<MyCouponUsabilityVO> out = service(mapper, coupons).listMine(MEMBER_ID);
        assertEquals(1, out.size());
        return out.get(0);
    }

    private CouponUsabilityService service(SmsCouponHistoryMapper mapper, ISmsCouponService coupons) {
        CouponUsabilityService service = new CouponUsabilityService();
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", mapper);
        ReflectionTestUtils.setField(service, "smsCouponService", coupons);
        return service;
    }

    private SmsCouponHistory history(long id, long couponId, int useStatus, Date start, Date end) {
        SmsCouponHistory h = new SmsCouponHistory();
        h.setId(id);
        h.setCouponId(couponId);
        h.setMemberId(MEMBER_ID);
        h.setUseStatus(useStatus);
        h.setStartTime(start);
        h.setEndTime(end);
        h.setDelFlag(0);
        return h;
    }

    private SmsCoupon coupon(long id, int scopeType, Long merchantId) {
        SmsCoupon c = new SmsCoupon();
        c.setId(id);
        c.setName("到店券");
        c.setCouponType(1);
        c.setAmount(new BigDecimal("20.00"));
        c.setDescription("满100可用,到店出示");
        c.setScopeType(scopeType);
        c.setMerchantId(merchantId);
        c.setStatus(1);
        c.setDelFlag(0);
        return c;
    }

    private SmsCoupon couponWithStatus(long id, int status) {
        SmsCoupon c = coupon(id, 1, 30L);
        c.setStatus(status);
        return c;
    }

    private Date future() {
        return new Date(System.currentTimeMillis() + 24 * HOUR);
    }
}
