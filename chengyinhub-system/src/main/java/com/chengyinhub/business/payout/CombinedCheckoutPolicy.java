package com.chengyinhub.business.payout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * l-payout3：一笔主题门票的钱属于多个收款方（发起方 + 带队俱乐部 + 核销商家）时，下单时怎么拆。纯函数，不读库、不调微信。
 *
 * <p>微信电商收付通不支持在多个二级商户之间直接分账（业主 2026-11-11 确认），所以多方收款的单改用「合单支付」：
 * <ul>
 *   <li><b>卖家子单</b>（发起方已进件的二级商户号）：下单时已经确定属于卖家的部分 = 实付 − 平台佣金 − 预留；</li>
 *   <li><b>平台自营子单</b>（平台自己的二级商户号）：平台佣金（主题锁价时冻结的费率）+ 预留。预留是结算后才知道归属的份额
 *       （带队费 / 核销费都按履约核销结算）的上限：按下单时已生效合作单的合同条款逐单取最大可能值相加。
 *       结算后由平台作为卖家按供应商结算付给对方，不做「代收再分发」。</li>
 * </ul>
 * 只有一个收款方（主题没有生效合作单）时返回 {@link Path#SINGLE}，调用方继续 l-payout2 的单商户路径。
 * 任何不确定都回落：开关关 / 卖家未进件 → SINGLE（l-payout2 再决定平台或卖家）；费率或合同条款缺失、
 * 卖家部分为 0 → PLATFORM（整单走现有平台商户号，即现有行为）。
 */
public final class CombinedCheckoutPolicy {

    public enum Path { SINGLE, COMBINED, PLATFORM }

    public enum Role { SELLER, PLATFORM_HELD }

    /** 下单时已生效的一张合作单的合同条款。shareMode：1 = 按销售额百分比（shareRate 为百分数），2 = 按每座固定费。 */
    public record Contract(Integer shareMode, BigDecimal shareRate, BigDecimal fixedFee) { }

    public record SubOrder(Role role, String subMchid, long amountFen, boolean profitSharing) { }

    public record Decision(Path path, String reason, List<SubOrder> subOrders, long commissionFen, long reserveFen) {
        static Decision of(Path path, String reason) { return new Decision(path, reason, List.of(), 0, 0); }
        public boolean combined() { return path == Path.COMBINED; }
    }

    /** 微信合单一次最多 50 笔子单（合单下单-JSAPI 文档）。本设计固定 2 笔。 */
    public static final int MAX_SUB_ORDERS = 50;

    private CombinedCheckoutPolicy() { }

    /**
     * @param seller       l-payout2 的卖家判定（必须已是二级商户）
     * @param totalFen     实付（分）
     * @param platformRate 主题锁价时冻结的平台费率（0..1），与结算 {@code settlementPlatformRate} 同源
     * @param contracts    下单时已生效的合作单；空 = 单一收款方
     * @param seats        本单座位数（每座固定费按它算）
     */
    public static Decision decide(SellerOfRecordPolicy.Decision seller, long totalFen, BigDecimal platformRate,
                                  List<Contract> contracts, Long seats, PayoutSwitches switches) {
        if (switches == null || !switches.combinedEnabled()) return Decision.of(Path.SINGLE, "SWITCH_OFF");
        if (seller == null || !seller.subMerchant() || seller.subMchid() == null) return Decision.of(Path.SINGLE, "SELLER_NOT_SUB_MERCHANT");
        if (contracts == null || contracts.isEmpty()) return Decision.of(Path.SINGLE, "SINGLE_PAYEE");
        if (totalFen <= 0) return Decision.of(Path.PLATFORM, "AMOUNT_INVALID");
        String platformSub = switches.platformSellerSubMchid();
        if (platformSub.equals(seller.subMchid())) return Decision.of(Path.PLATFORM, "SELLER_IS_PLATFORM_SELF");
        if (platformRate == null || platformRate.signum() < 0 || platformRate.compareTo(BigDecimal.ONE) > 0)
            return Decision.of(Path.PLATFORM, "PLATFORM_RATE_INVALID");
        long commission = BigDecimal.valueOf(totalFen).multiply(platformRate).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long reserve = 0;
        for (Contract c : contracts) {
            long max = maxShareFen(c, totalFen, seats);
            if (max < 0) return Decision.of(Path.PLATFORM, "CONTRACT_TERMS_UNKNOWN");
            reserve = Math.min(totalFen, reserve + max);
        }
        long held = Math.min(totalFen, commission + reserve);
        long sellerFen = totalFen - held;
        if (held == 0) return Decision.of(Path.SINGLE, "NOTHING_HELD");
        if (sellerFen == 0) return Decision.of(Path.PLATFORM, "NOTHING_CERTAIN_FOR_SELLER");
        // 卖家子单 profit_sharing=true：结算时若合作单在下单后才生效、预留不够，可从卖家子单向平台分账补差（受比例上限约束）；
        // 不需要补差时完结分账解冻。平台自营子单不需要分账。
        List<SubOrder> subs = List.of(
                new SubOrder(Role.SELLER, seller.subMchid(), sellerFen, true),
                new SubOrder(Role.PLATFORM_HELD, platformSub, held, false));
        return new Decision(Path.COMBINED, "MULTI_PAYEE", subs, commission, held - commission);
    }

    /** 一张合作单对这一单的最大可能份额（分）；条款不完整返回 -1。 */
    static long maxShareFen(Contract c, long totalFen, Long seats) {
        if (c == null || c.shareMode() == null) return -1;
        if (c.shareMode() == 1) {
            if (c.shareRate() == null || c.shareRate().signum() < 0) return -1;
            return Math.min(totalFen, BigDecimal.valueOf(totalFen).multiply(c.shareRate()).movePointLeft(2)
                    .setScale(0, RoundingMode.CEILING).longValueExact());
        }
        if (c.shareMode() == 2) {
            if (c.fixedFee() == null || c.fixedFee().signum() < 0 || seats == null || seats <= 0) return -1;
            BigDecimal fen = c.fixedFee().movePointRight(2).multiply(BigDecimal.valueOf(seats));
            return fen.compareTo(BigDecimal.valueOf(totalFen)) >= 0 ? totalFen : fen.setScale(0, RoundingMode.CEILING).longValueExact();
        }
        return -1;
    }

    /** 子单商户订单号：由合单号和角色确定性生成，≤32 位，只含字母数字。 */
    public static String subOutTradeNo(String combineOutTradeNo, Role role) {
        return "CYC" + sha(combineOutTradeNo + "|" + role.name()).substring(0, 29);
    }

    /** 子单退款单号：由业务退款单号和角色确定性生成，≤64 位。 */
    public static String subOutRefundNo(String outRefundNo, Role role) {
        return "CYCR" + sha(outRefundNo + "|" + role.name()).substring(0, 28);
    }

    /**
     * 一笔业务退款在各子单上的拆分：按子单金额比例，最大余数法凑整，且每个子单不超过其剩余可退。
     *
     * @param amounts       各子单实付（顺序即角色顺序）
     * @param refundedSoFar 各子单已退（不含本次）
     * @return 各子单本次退款额；无法拆分（超出剩余可退）返回 null
     */
    public static long[] splitRefund(long[] amounts, long[] refundedSoFar, long refundFen) {
        long total = 0, remaining = 0;
        for (int i = 0; i < amounts.length; i++) {
            total += amounts[i];
            remaining += amounts[i] - refundedSoFar[i];
        }
        if (refundFen <= 0 || refundFen > remaining || total <= 0) return null;
        long[] out = new long[amounts.length];
        long[] rem = new long[amounts.length];
        long assigned = 0;
        for (int i = 0; i < amounts.length; i++) {
            long cap = amounts[i] - refundedSoFar[i];
            long base = Math.min(cap, Math.multiplyExact(amounts[i], refundFen) / total);
            out[i] = base;
            rem[i] = Math.multiplyExact(amounts[i], refundFen) % total;
            assigned += base;
        }
        long left = refundFen - assigned;
        while (left > 0) {
            int best = -1;
            for (int i = 0; i < amounts.length; i++) {
                if (out[i] >= amounts[i] - refundedSoFar[i]) continue;
                if (best < 0 || rem[i] > rem[best]) best = i;
            }
            if (best < 0) return null;
            out[best]++;
            rem[best] = -1;
            left--;
        }
        return out;
    }

    static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
