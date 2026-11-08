package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单一分配算法：先把折后实付扣成净额，再按「作者基础 + 平台基础 = 1」二分，最后把推荐佣金
 * 从规则声明的出资侧扣出。平台额取净额残差，保证三分之和恒等于净额。
 *
 * <p>计算在<b>整数分</b>上完成：作者/推荐按 {@link RevenueMoney} 分摊、平台取残差，
 * 三分之和精确等于净额，不会出现"末位一分"漂移。
 *
 * <p>明确不做的事：
 * <ul>
 *   <li>不接收折扣参数 —— 起点必须是折后实付，结构上杜绝「折扣扣两次」。</li>
 *   <li>不套用 50/75/20 等竞品比例；费率全部来自调用方传入的已发布规则。</li>
 *   <li>不产生任何钱包/余额/支付动作，只算数。</li>
 * </ul>
 */
public final class RevenueSplitCalculator {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private RevenueSplitCalculator() {
    }

    public static final class Input {
        private Long paidFen;
        private Long taxFen;
        private Long paymentFeeFen;
        private Long channelCommissionFen;
        private BigDecimal authorBaseRate;
        private BigDecimal platformBaseRate;
        private BigDecimal referralRate;
        private Integer referralFundingSide;
        private String currency;

        public Input paidFen(Long v) { this.paidFen = v; return this; }
        public Input taxFen(Long v) { this.taxFen = v; return this; }
        public Input paymentFeeFen(Long v) { this.paymentFeeFen = v; return this; }
        public Input channelCommissionFen(Long v) { this.channelCommissionFen = v; return this; }
        public Input authorBaseRate(BigDecimal v) { this.authorBaseRate = v; return this; }
        public Input platformBaseRate(BigDecimal v) { this.platformBaseRate = v; return this; }
        public Input referralRate(BigDecimal v) { this.referralRate = v; return this; }
        public Input referralFundingSide(Integer v) { this.referralFundingSide = v; return this; }
        public Input currency(String v) { this.currency = v; return this; }
    }

    public static final class Output {
        private final long netFen;
        private final long authorFen;
        private final long referrerFen;
        private final long platformFen;
        private final BigDecimal authorBaseRate;
        private final BigDecimal platformBaseRate;
        private final BigDecimal referralRate;
        private final Integer referralFundingSide;
        private final String currency;

        private Output(long netFen, long authorFen, long referrerFen, long platformFen,
                       BigDecimal authorBaseRate, BigDecimal platformBaseRate,
                       BigDecimal referralRate, Integer referralFundingSide, String currency) {
            this.netFen = netFen;
            this.authorFen = authorFen;
            this.referrerFen = referrerFen;
            this.platformFen = platformFen;
            this.authorBaseRate = authorBaseRate;
            this.platformBaseRate = platformBaseRate;
            this.referralRate = referralRate;
            this.referralFundingSide = referralFundingSide;
            this.currency = currency;
        }

        public long getNetFen() { return netFen; }
        public long getAuthorFen() { return authorFen; }
        public long getReferrerFen() { return referrerFen; }
        public long getPlatformFen() { return platformFen; }
        public BigDecimal getAuthorBaseRate() { return authorBaseRate; }
        public BigDecimal getPlatformBaseRate() { return platformBaseRate; }
        public BigDecimal getReferralRate() { return referralRate; }
        public Integer getReferralFundingSide() { return referralFundingSide; }
        public String getCurrency() { return currency; }

        public BigDecimal getNetAmount() { return RevenueMoney.toAmount(netFen); }
        public BigDecimal getAuthorAmount() { return RevenueMoney.toAmount(authorFen); }
        public BigDecimal getReferrerAmount() { return RevenueMoney.toAmount(referrerFen); }
        public BigDecimal getPlatformAmount() { return RevenueMoney.toAmount(platformFen); }

        public long getTotalAllocatedFen() { return authorFen + referrerFen + platformFen; }

        public boolean isConserved() { return getTotalAllocatedFen() == netFen; }
    }

    public static Output split(Input input) {
        if (input == null) {
            throw MarketingRevenueException.badRequest("SPLIT_INPUT_REQUIRED", "缺少分配参数");
        }
        String currency = requireCurrency(input.currency);
        long paid = RevenueMoney.toFenStrict(input.paidFen, "PAID_REQUIRED", "折后实付");
        if (paid <= 0) {
            throw MarketingRevenueException.badRequest("PAID_INVALID", "折后实付必须大于 0");
        }
        long tax = RevenueMoney.toFenStrict(nz(input.taxFen), "TAX_INVALID", "税费");
        long paymentFee = RevenueMoney.toFenStrict(nz(input.paymentFeeFen), "PAYMENT_FEE_INVALID", "支付手续费");
        long channel = RevenueMoney.toFenStrict(nz(input.channelCommissionFen), "CHANNEL_COMMISSION_INVALID", "渠道佣金");
        long deductions = Math.addExact(Math.addExact(tax, paymentFee), channel);
        if (deductions > paid) {
            throw MarketingRevenueException.badRequest("NET_NEGATIVE",
                    "税费/手续费/渠道佣金合计超过折后实付，净额为负");
        }
        long net = paid - deductions;

        BigDecimal authorBase = rate(input.authorBaseRate, "AUTHOR_RATE_INVALID", "作者基础比例");
        BigDecimal platformBase = rate(input.platformBaseRate, "PLATFORM_RATE_INVALID", "平台基础比例");
        if (authorBase.add(platformBase).compareTo(ONE) != 0) {
            throw MarketingRevenueException.badRequest("RATE_NOT_CONSERVED",
                    "作者基础比例 + 平台基础比例必须等于 100%");
        }
        BigDecimal referralRate = rateOrZero(input.referralRate, "REFERRAL_RATE_INVALID", "推荐佣金比例");
        int fundingSide = input.referralFundingSide == null
                ? MarketingRevenueRule.FUNDING_NONE : input.referralFundingSide;
        requireFundingSide(fundingSide);
        if (fundingSide == MarketingRevenueRule.FUNDING_NONE && referralRate.signum() != 0) {
            throw MarketingRevenueException.badRequest("REFERRAL_WITHOUT_FUNDING",
                    "配置了推荐佣金比例但未声明出资侧");
        }
        if (fundingSide != MarketingRevenueRule.FUNDING_NONE && referralRate.signum() <= 0) {
            throw MarketingRevenueException.badRequest("REFERRAL_RATE_REQUIRED",
                    "声明了推荐出资侧但佣金比例必须大于 0");
        }
        BigDecimal fundingBase = fundingSide == MarketingRevenueRule.FUNDING_PLATFORM ? platformBase : authorBase;
        if (fundingSide != MarketingRevenueRule.FUNDING_NONE && referralRate.compareTo(fundingBase) > 0) {
            throw MarketingRevenueException.badRequest("REFERRAL_EXCEEDS_FUNDING",
                    "推荐佣金不能超过出资侧基础份额");
        }

        // 二分：作者取向下取整，平台取净额残差 —— 两份恒等于净额，不需要最大余数。
        long authorHalf = share(authorBase, net);
        long platformHalf = net - authorHalf;
        long referrerFen = 0;
        if (fundingSide != MarketingRevenueRule.FUNDING_NONE) {
            referrerFen = proportional(referralRate, net);
            if (referrerFen > (fundingSide == MarketingRevenueRule.FUNDING_PLATFORM ? platformHalf : authorHalf)) {
                throw MarketingRevenueException.badRequest("REFERRAL_EXCEEDS_FUNDING",
                        "推荐佣金超过出资侧可用份额");
            }
        }
        long authorFen = fundingSide == MarketingRevenueRule.FUNDING_AUTHOR
                ? authorHalf - referrerFen : authorHalf;
        long platformFen = net - authorFen - referrerFen;
        if (authorFen < 0 || platformFen < 0 || referrerFen < 0) {
            throw MarketingRevenueException.badRequest("SPLIT_NEGATIVE", "分配结果出现负数，请检查费率配置");
        }
        Output output = new Output(net, authorFen, referrerFen, platformFen,
                authorBase, platformBase, referralRate, fundingSide, currency);
        if (!output.isConserved()) {
            // 平台额已取残差，走到这里只可能是实现错误；宁可拒绝入账也不写不平的账。
            throw new IllegalStateException("分配不守恒 net=" + net + " total=" + output.getTotalAllocatedFen());
        }
        return output;
    }

    /**
     * 二分：把 net 按作者比例切成整数分，作者取向下取整，平台取残差。
     * 不用最大余数，因为只有两份且平台本就是残差，这样恒久等于 net。
     */
    private static long share(BigDecimal rateOfBase, long net) {
        return BigDecimal.valueOf(net).multiply(rateOfBase).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /** 单比例取整数分，向下取整（保守：绝不多给）。 */
    private static long proportional(BigDecimal rateValue, long baseFen) {
        return BigDecimal.valueOf(baseFen).multiply(rateValue).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    public static String requireCurrency(String currency) {
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw MarketingRevenueException.badRequest("CURRENCY_INVALID", "币种必须为 3 位大写 ISO-4217 代码");
        }
        return currency;
    }

    public static BigDecimal requireRate(BigDecimal rate, String reasonCode, String label) {
        return rate(rate, reasonCode, label);
    }

    private static BigDecimal rate(BigDecimal value, String reasonCode, String label) {
        if (value == null) {
            throw MarketingRevenueException.badRequest(reasonCode, label + "不能为空");
        }
        if (value.signum() < 0 || value.compareTo(ONE) > 0) {
            throw MarketingRevenueException.badRequest(reasonCode, label + "必须在 0 到 1 之间");
        }
        return value;
    }

    private static BigDecimal rateOrZero(BigDecimal value, String reasonCode, String label) {
        return value == null ? BigDecimal.ZERO : rate(value, reasonCode, label);
    }

    private static void requireFundingSide(int side) {
        if (side != MarketingRevenueRule.FUNDING_NONE
                && side != MarketingRevenueRule.FUNDING_PLATFORM
                && side != MarketingRevenueRule.FUNDING_AUTHOR) {
            throw MarketingRevenueException.badRequest("FUNDING_SIDE_INVALID", "推荐佣金出资侧不合法");
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
