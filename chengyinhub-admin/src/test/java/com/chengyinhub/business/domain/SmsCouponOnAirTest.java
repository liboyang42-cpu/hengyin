package com.chengyinhub.business.domain;

import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CU-M-51 / CU-M-86(2026-09-23 用户裁决 A)· 「在投放」的判据。
 *
 * 商家侧两处聚合(营销首页 couponStats、商家首页 coupon-summary)原来只剔 del_flag=2,
 * 把「已停发 / 未开始 / 已结束」都算进了卡面那句「N 张在投放」。判据收到这里一处,
 * 两处调用点一起改,语义与 SmsCoupon.status 的字段注释对齐:
 * 1 进行中才算在投;0 未开始 / 2 已结束 / 3 手动失效 / 4 已停发都不算。
 */
class SmsCouponOnAirTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static Date at(long offsetMs) {
        return new Date(System.currentTimeMillis() + offsetMs);
    }

    private static SmsCoupon coupon(Integer status, Date start, Date end) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setStatus(status);
        coupon.setStartTime(start);
        coupon.setEndTime(end);
        coupon.setDelFlag(0);
        return coupon;
    }

    @Test
    void onlyRunningCouponInsideItsPeriodIsOnAir() {
        Date now = new Date();
        assertTrue(coupon(1, at(-DAY), at(DAY)).onAir(now), "进行中且在有效期内 = 在投放");
        assertFalse(coupon(4, at(-DAY), at(DAY)).onAir(now), "已停发不再是「在投放」");
        assertFalse(coupon(0, at(DAY), at(2 * DAY)).onAir(now), "还没开始不算在投放");
        assertFalse(coupon(2, at(-2 * DAY), at(-DAY)).onAir(now), "已结束不算在投放");
        assertFalse(coupon(3, at(-DAY), at(DAY)).onAir(now), "平台手动失效不算在投放");
        assertFalse(coupon(null, at(-DAY), at(DAY)).onAir(now), "状态未知失败关闭");
    }

    @Test
    void periodBoundariesAreHalfOpen() {
        Date start = at(-DAY);
        Date end = at(DAY);
        SmsCoupon running = coupon(1, start, end);
        assertTrue(running.onAir(start), "生效瞬间就在投放");
        assertFalse(running.onAir(end), "结束瞬间已不在投放(与券列表页同口径)");
        assertFalse(running.onAir(new Date(end.getTime() + 1)), "过期即不在投放");
    }

    @Test
    void missingBoundsDoNotFakeAnExpiredCoupon() {
        Date now = new Date();
        assertTrue(coupon(1, null, null).onAir(now), "没填起止时间的进行中券不因此被判死");
        assertTrue(coupon(1, at(-DAY), null).onAir(now), "只填开始时间:未到期即在投放");
        assertTrue(coupon(1, null, at(DAY)).onAir(now));
    }

    @Test
    void nullNowFallsBackToCurrentTime() {
        SmsCoupon running = coupon(1, at(-DAY), at(DAY));
        assertTrue(running.onAir(null), "调用方没传时刻时按当前时间判");
        Calendar twoDaysAgo = Calendar.getInstance();
        twoDaysAgo.add(Calendar.DAY_OF_MONTH, -2);
        assertFalse(running.onAir(twoDaysAgo.getTime()), "显式传时刻就按那一刻判:那时它还没开始");
        assertFalse(coupon(4, at(-DAY), at(DAY)).onAir(null));
    }
}
