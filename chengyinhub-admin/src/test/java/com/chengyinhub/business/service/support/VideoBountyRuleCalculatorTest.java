package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.support.VideoBountyRuleCalculator.Allocation;
import com.chengyinhub.business.service.support.VideoBountyRuleCalculator.Contribution;
import com.chengyinhub.business.service.support.VideoBountyRuleCalculator.GroupCalculation;
import com.chengyinhub.business.service.support.VideoBountyRuleCalculator.Rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应计口径的纯计算契约。
 *
 * <p>这些断言守的是<b>钱</b>:跨平台合计封顶、舍入不超发、窗口外不计。
 * 用参考值构造(€2/1000、€500 封顶、7 天)只为可读,不代表城瘾默认费率。
 */
class VideoBountyRuleCalculatorTest {

    private static Rule rule() {
        return new Rule(new BigDecimal("2.00"), new BigDecimal("500.00"), 7, "EUR");
    }

    @Test
    void singleSubmissionUnderCapPaysProportionalAmount() {
        GroupCalculation result = VideoBountyRuleCalculator.compute(rule(),
                List.of(new Contribution(11L, 12_500L)));

        Allocation only = result.forSubmission(11L);
        // 12_500 views × €2 / 1000 = €25.00
        assertEquals(new BigDecimal("25.00"), only.getGrossAmount());
        assertEquals(new BigDecimal("25.00"), only.getPayableAmount());
        assertEquals(new BigDecimal("25.00"), result.getPayableTotal());
    }

    @Test
    void crossPlatformGroupIsCappedOnceNotPerPost() {
        // 同一原视频发在三个平台,各自 200k 播放:合计 600k × €2/1000 = €1200 → 封顶 €500。
        List<Contribution> group = Arrays.asList(
                new Contribution(1L, 200_000L),
                new Contribution(2L, 200_000L),
                new Contribution(3L, 200_000L));

        GroupCalculation result = VideoBountyRuleCalculator.compute(rule(), group);

        assertEquals(600_000L, result.getGroupViews());
        assertEquals(new BigDecimal("1200.00"), result.getGroupGross());
        // 封顶是硬上限;按占比截断到分,可能留下不到一分的尾差,但绝不越过 500。
        assertTrue(result.getPayableTotal().compareTo(new BigDecimal("500.00")) <= 0,
                "组内合计越过封顶就是超发");
        assertTrue(result.getPayableTotal().compareTo(new BigDecimal("499.00")) > 0,
                "封顶后的合计应接近封顶,而不是只付一条");
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        for (Allocation allocation : result.getAllocations()) sum = sum.add(allocation.getPayableAmount());
        assertEquals(0, sum.compareTo(result.getPayableTotal()),
                "各条分摊之和必须等于组内合计");
        // 不分组的话三条各付 400 = 1200;分组合计显著更低,证明"跨平台合计封顶"真的生效。
        assertTrue(result.getPayableTotal().compareTo(new BigDecimal("1200.00")) < 0);
    }

    @Test
    void cappedGroupAllocationNeverExceedsCapEvenWithRounding() {
        // 三条播放量除不尽,按比例分摊必然产生小数:合计仍不得越过封顶。
        List<Contribution> group = Arrays.asList(
                new Contribution(1L, 333_333L),
                new Contribution(2L, 333_333L),
                new Contribution(3L, 333_334L));

        GroupCalculation result = VideoBountyRuleCalculator.compute(rule(), group);

        assertTrue(result.getPayableTotal().compareTo(new BigDecimal("500.00")) <= 0,
                "payable_total 越过封顶就是超发");
        BigDecimal sum = BigDecimal.ZERO;
        for (Allocation allocation : result.getAllocations()) {
            assertTrue(allocation.getPayableAmount().compareTo(allocation.getGrossAmount()) <= 0);
            sum = sum.add(allocation.getPayableAmount());
        }
        assertEquals(0, sum.compareTo(result.getPayableTotal()));
    }

    @Test
    void zeroViewsPayNothing() {
        GroupCalculation result = VideoBountyRuleCalculator.compute(rule(),
                List.of(new Contribution(7L, 0L)));

        assertEquals(new BigDecimal("0.00"), result.forSubmission(7L).getPayableAmount());
        assertEquals(new BigDecimal("0.00"), result.getPayableTotal());
    }

    @Test
    void emptyGroupIsZeroAndNoAllocations() {
        GroupCalculation result = VideoBountyRuleCalculator.compute(rule(), List.of());

        assertEquals(0L, result.getGroupViews());
        assertEquals(new BigDecimal("0.00"), result.getPayableTotal());
        assertTrue(result.getAllocations().isEmpty());
    }

    @Test
    void evidenceMustSitEntirelyInsideWindow() {
        Date publish = new Date(1_700_000_000_000L);
        Date windowEnd = VideoBountyRuleCalculator.windowEnd(publish, 7);
        long day = 24L * 60 * 60 * 1000;

        assertTrue(VideoBountyRuleCalculator.fullyInsideWindow(
                new Date(publish.getTime()), new Date(publish.getTime() + day), publish, windowEnd));
        // 跨过窗口终点:只给区间总量,无法拆分 ⇒ 不计,而不是按重叠估算。
        assertFalse(VideoBountyRuleCalculator.fullyInsideWindow(
                new Date(windowEnd.getTime() - day), new Date(windowEnd.getTime() + day), publish, windowEnd));
        // 早于发布时刻同样不计。
        assertFalse(VideoBountyRuleCalculator.fullyInsideWindow(
                new Date(publish.getTime() - day), new Date(publish.getTime() + day), publish, windowEnd));
    }

    @Test
    void windowEndIsExactlyConfiguredDays() {
        Date publish = new Date(0L);
        assertEquals(7L * 24 * 60 * 60 * 1000,
                VideoBountyRuleCalculator.windowEnd(publish, 7).getTime());
    }

    @Test
    void negativeRateIsRejectedAtConstruction() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Rule(new BigDecimal("-1"), new BigDecimal("500.00"), 7, "EUR"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Rule(new BigDecimal("2.00"), new BigDecimal("500.00"), 0, "EUR"));
    }
}
