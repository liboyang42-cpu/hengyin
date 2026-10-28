package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ExploreBundleUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * F3 套票 Phase 1 产品规则(DOC2 D-11),纯函数,无 IO。版本号随订单快照冻结(policy_version)。
 *
 * <ul>
 *   <li>主动退套票:完全未用 → 原路全额;部分使用且剩余价值占比 &gt; 20% → 可退平台余额;
 *       ≤ 20% → 不走常规退款(商家违约走售后)。</li>
 *   <li>预约:开始前 ≥ 12h 免费取消;&lt; 12h 或爽约只消耗该单位、不另收现金;
 *       每会员每自然月一次主动豁免。罚没不记真实核销,不产生商家分成。</li>
 * </ul>
 */
public final class FreeExploreBundlePolicy {
    private FreeExploreBundlePolicy() {}

    public static final String POLICY_VERSION = "F3P1-20261022";
    public static final long FREE_CANCEL_HOURS = 12L;
    public static final BigDecimal PARTIAL_REFUND_MIN_RATIO = new BigDecimal("0.20");
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final String ROUTE_ORIGINAL_METHOD = "ORIGINAL_METHOD";
    public static final String ROUTE_PLATFORM_BALANCE = "PLATFORM_BALANCE";
    public static final String ROUTE_NOT_REFUNDABLE = "NOT_REFUNDABLE";

    /** 均分实付到各单位(分),余数落在最后一个单位,保证 Σ = 实付。 */
    public static List<BigDecimal> splitValue(BigDecimal paidAmount, int units) {
        if (paidAmount == null || paidAmount.signum() < 0 || units <= 0) {
            throw new IllegalArgumentException("套票金额或单位数无效");
        }
        BigDecimal paid = paidAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal each = paid.divide(BigDecimal.valueOf(units), 2, RoundingMode.DOWN);
        List<BigDecimal> values = new ArrayList<>(units);
        for (int i = 0; i < units - 1; i++) values.add(each);
        values.add(paid.subtract(each.multiply(BigDecimal.valueOf(units - 1L))));
        return values;
    }

    /** 开始前 ≥ 12h(含恰好 12h)免费取消。 */
    public static boolean freeCancel(Date startAt, Date now) {
        return startAt.getTime() - now.getTime() >= FREE_CANCEL_HOURS * 3600_000L;
    }

    /** 豁免按 Asia/Shanghai 自然月计。 */
    public static String waiverMonth(Date now) {
        return DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZONE).format(Instant.ofEpochMilli(now.getTime()));
    }

    /** 单位是否仍是「未履约」价值:可用 / 已约未到 / 暂停待持有人决定。 */
    public static boolean unfulfilled(String status) {
        return "AVAILABLE".equals(status) || "RESERVED".equals(status) || "HELD".equals(status);
    }

    /** 主动退套票报价(只读;不执行任何资金动作)。 */
    public static RefundQuote refundQuote(BigDecimal paidAmount, List<ExploreBundleUnit> units) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        boolean anyConsumed = false;
        boolean anySettled = false;
        for (ExploreBundleUnit unit : units) {
            BigDecimal value = unit.getUnitValue() == null ? BigDecimal.ZERO : unit.getUnitValue();
            total = total.add(value);
            if (unfulfilled(unit.getStatus())) {
                remaining = remaining.add(value);
            } else if ("REDEEMED".equals(unit.getStatus()) || "FORFEITED".equals(unit.getStatus())) {
                anyConsumed = true;
            } else {
                anySettled = true;   // REFUND_PENDING / REFUNDED:已进入退款处置,不再重复报价
            }
        }
        RefundQuote quote = new RefundQuote();
        quote.totalValue = total;
        quote.remainingValue = remaining;
        quote.remainingRatio = total.signum() == 0 ? BigDecimal.ZERO
                : remaining.divide(total, 4, RoundingMode.HALF_UP);
        if (anySettled || units.isEmpty()) {
            quote.route = ROUTE_NOT_REFUNDABLE;
            quote.amount = BigDecimal.ZERO;
            quote.reason = units.isEmpty() ? "套票无可退单位" : "套票已有单位进入退款处置";
        } else if (!anyConsumed) {
            quote.route = ROUTE_ORIGINAL_METHOD;
            quote.amount = paidAmount == null ? total : paidAmount.setScale(2, RoundingMode.HALF_UP);
            quote.reason = "完全未使用,原路全额退回";
        } else if (quote.remainingRatio.compareTo(PARTIAL_REFUND_MIN_RATIO) > 0) {
            quote.route = ROUTE_PLATFORM_BALANCE;
            quote.amount = remaining;
            quote.reason = "部分使用且剩余价值占比大于20%,剩余价值可退平台余额";
        } else {
            quote.route = ROUTE_NOT_REFUNDABLE;
            quote.amount = BigDecimal.ZERO;
            quote.reason = "剩余价值占比不超过20%,不走常规退款;商家违约请走售后";
            quote.afterSalesForMerchantBreach = true;
        }
        quote.policyVersion = POLICY_VERSION;
        return quote;
    }

    public static final class RefundQuote {
        private String route;
        private BigDecimal amount;
        private BigDecimal totalValue;
        private BigDecimal remainingValue;
        private BigDecimal remainingRatio;
        private boolean afterSalesForMerchantBreach;
        private String reason;
        private String policyVersion;

        public String getRoute() { return route; }
        public BigDecimal getAmount() { return amount; }
        public BigDecimal getTotalValue() { return totalValue; }
        public BigDecimal getRemainingValue() { return remainingValue; }
        public BigDecimal getRemainingRatio() { return remainingRatio; }
        public boolean isAfterSalesForMerchantBreach() { return afterSalesForMerchantBreach; }
        public String getReason() { return reason; }
        public String getPolicyVersion() { return policyVersion; }
    }
}
