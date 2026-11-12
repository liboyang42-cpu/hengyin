package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分配算法契约：三分之和恒等于净额；折扣只能出现一次；推荐佣金只从声明的一侧出。
 *
 * <p>全部在整数分上验证 —— 不依赖数据库，也不代表结算/打款已验证。
 */
class RevenueSplitCalculatorTest {

    private static RevenueSplitCalculator.Input input(long paidFen) {
        return new RevenueSplitCalculator.Input().paidFen(paidFen).currency("CNY");
    }

    @Test
    void splitIsConservedAndLeavesNoResidual() {
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(10000L)
                .authorBaseRate(new BigDecimal("0.50"))
                .platformBaseRate(new BigDecimal("0.50")));

        assertEquals(10000L, out.getNetFen());
        assertEquals(5000L, out.getAuthorFen());
        assertEquals(0L, out.getReferrerFen());
        assertEquals(5000L, out.getPlatformFen());
        assertTrue(out.isConserved());
    }

    @Test
    void oddCentLandsOnPlatformSoSumNeverDrifts() {
        // 1 分钱、50/50：作者向下取整 0，平台取残差 1 —— 合计仍等于净额。
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(1L)
                .authorBaseRate(new BigDecimal("0.50"))
                .platformBaseRate(new BigDecimal("0.50")));

        assertEquals(1L, out.getNetFen());
        assertEquals(0L, out.getAuthorFen());
        assertEquals(1L, out.getPlatformFen());
        assertTrue(out.isConserved());
    }

    @Test
    void threeCentSplitAcrossThreeLayersHasNoLostCent() {
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(3L)
                .authorBaseRate(new BigDecimal("0.5"))
                .platformBaseRate(new BigDecimal("0.5"))
                .referralRate(new BigDecimal("0.2"))
                .referralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM));

        assertEquals(3L, out.getNetFen());
        assertEquals(out.getNetFen(),
                out.getAuthorFen() + out.getReferrerFen() + out.getPlatformFen());
        assertTrue(out.getPlatformFen() >= 0);
    }

    @Test
    void discountIsNotACalculatorInputSoItCannotBeDeductedTwice() {
        // 起点就是折后实付：原价 100 打 8 折后 80 作为 paid，净额必须是 80，不能再扣 20。
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(8000L)
                .authorBaseRate(new BigDecimal("0.50"))
                .platformBaseRate(new BigDecimal("0.50")));

        assertEquals(8000L, out.getNetFen());
        assertEquals(4000L, out.getAuthorFen());
        assertEquals(4000L, out.getPlatformFen());
    }

    @Test
    void taxFeeAndChannelCommissionAreDeductedOncePerContractLine() {
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(10000L)
                .taxFen(600L)
                .paymentFeeFen(60L)
                .channelCommissionFen(1000L)
                .authorBaseRate(new BigDecimal("0.50"))
                .platformBaseRate(new BigDecimal("0.50")));

        assertEquals(8340L, out.getNetFen());
        assertEquals(4170L, out.getAuthorFen());
        assertEquals(4170L, out.getPlatformFen());
        assertTrue(out.isConserved());
    }

    @Test
    void deductionsExceedingPaidIsRejectedInsteadOfProducingNegativeNet() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(1000L)
                        .taxFen(1100L)
                        .authorBaseRate(new BigDecimal("0.50"))
                        .platformBaseRate(new BigDecimal("0.50"))));
        assertEquals("NET_NEGATIVE", error.getReasonCode());
    }

    @Test
    void referralFundedByPlatformComesOutOfPlatformShareNotOnTop() {
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(10000L)
                .authorBaseRate(new BigDecimal("0.50"))
                .platformBaseRate(new BigDecimal("0.50"))
                .referralRate(new BigDecimal("0.20"))
                .referralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM));

        assertEquals(5000L, out.getAuthorFen());
        assertEquals(2000L, out.getReferrerFen());
        // 平台 5000 − 推荐 2000 = 3000：推荐从平台侧出，作者不受影响，
        // 也不能把"剩余 80%"当成平台最终收入。
        assertEquals(3000L, out.getPlatformFen());
        assertTrue(out.isConserved());
    }

    @Test
    void referralFundedByAuthorComesOutOfAuthorShare() {
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(10000L)
                .authorBaseRate(new BigDecimal("0.50"))
                .platformBaseRate(new BigDecimal("0.50"))
                .referralRate(new BigDecimal("0.20"))
                .referralFundingSide(MarketingRevenueRule.FUNDING_AUTHOR));

        assertEquals(3000L, out.getAuthorFen());
        assertEquals(2000L, out.getReferrerFen());
        assertEquals(5000L, out.getPlatformFen());
        assertTrue(out.isConserved());
    }

    @Test
    void referralCannotExceedItsFundingSide() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(10000L)
                        .authorBaseRate(new BigDecimal("0.50"))
                        .platformBaseRate(new BigDecimal("0.50"))
                        .referralRate(new BigDecimal("0.60"))
                        .referralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM)));
        assertEquals("REFERRAL_EXCEEDS_FUNDING", error.getReasonCode());
    }

    @Test
    void referralRateWithoutFundingSideIsRejected() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(10000L)
                        .authorBaseRate(new BigDecimal("0.50"))
                        .platformBaseRate(new BigDecimal("0.50"))
                        .referralRate(new BigDecimal("0.20"))));
        assertEquals("REFERRAL_WITHOUT_FUNDING", error.getReasonCode());
    }

    @Test
    void fundingSideWithoutRateIsRejected() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(10000L)
                        .authorBaseRate(new BigDecimal("0.50"))
                        .platformBaseRate(new BigDecimal("0.50"))
                        .referralFundingSide(MarketingRevenueRule.FUNDING_PLATFORM)));
        assertEquals("REFERRAL_RATE_REQUIRED", error.getReasonCode());
    }

    @Test
    void baseRatesMustSumToOneSoNothingIsSilentlyUnallocated() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(10000L)
                        .authorBaseRate(new BigDecimal("0.50"))
                        .platformBaseRate(new BigDecimal("0.40"))));
        assertEquals("RATE_NOT_CONSERVED", error.getReasonCode());
    }

    @Test
    void currencyMustBeIsoCode() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(new RevenueSplitCalculator.Input()
                        .paidFen(1000L)
                        .authorBaseRate(new BigDecimal("0.5"))
                        .platformBaseRate(new BigDecimal("0.5"))
                        .currency("rmb")));
        assertEquals("CURRENCY_INVALID", error.getReasonCode());
    }

    @Test
    void zeroPaidIsRejected() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(0L)
                        .authorBaseRate(new BigDecimal("0.5"))
                        .platformBaseRate(new BigDecimal("0.5"))));
        assertEquals("PAID_INVALID", error.getReasonCode());
    }

    @Test
    void negativeDeductionIsRejected() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueSplitCalculator.split(input(1000L)
                        .taxFen(-1L)
                        .authorBaseRate(new BigDecimal("0.5"))
                        .platformBaseRate(new BigDecimal("0.5"))));
        assertEquals("TAX_INVALID", error.getReasonCode());
    }

    @Test
    void fullAuthorShareLeavesPlatformZeroButStillConserved() {
        RevenueSplitCalculator.Output out = RevenueSplitCalculator.split(input(5000L)
                .authorBaseRate(BigDecimal.ONE)
                .platformBaseRate(BigDecimal.ZERO));

        assertEquals(5000L, out.getAuthorFen());
        assertEquals(0L, out.getPlatformFen());
        assertTrue(out.isConserved());
    }
}
