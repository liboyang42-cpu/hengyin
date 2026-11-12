package com.chengyinhub.business.service.support;

import com.chengyinhub.business.exception.MarketingRevenueException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 金额精确口径：整数分 + 最大余数法分摊。
 *
 * <p>为什么不用 BigDecimal 逐笔乘比例：分笔部分退款时每笔各自 {@code HALF_UP} 取整，
 * 尾差会累积丢失，最后全额退完仍剩几分钱冲不掉。这里把所有金额换成整数分，
 * 按<b>累计</b>退款额计算每个原分录的目标冲销额，事件增量 = 目标 − 已冲销，
 * 于是三次 1 分退款恰好把三个 1 分分录冲完，无残差。
 */
public final class RevenueMoney {

    private RevenueMoney() {
    }

    /** 严格转分：超过 2 位小数直接拒绝，不静默四舍五入。 */
    public static long toFen(BigDecimal amount, String reasonCode, String label) {
        if (amount == null) {
            throw MarketingRevenueException.badRequest(reasonCode, label + "不能为空");
        }
        if (amount.signum() < 0) {
            throw MarketingRevenueException.badRequest(reasonCode, label + "不能为负");
        }
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException fractionalCent) {
            throw MarketingRevenueException.badRequest("AMOUNT_FRACTIONAL_CENT", label + "最多 2 位小数");
        }
    }

    public static long toFenStrict(Long fen, String reasonCode, String label) {
        if (fen == null) {
            throw MarketingRevenueException.badRequest(reasonCode, label + "不能为空");
        }
        if (fen < 0) {
            throw MarketingRevenueException.badRequest(reasonCode, label + "不能为负");
        }
        return fen;
    }

    public static BigDecimal toAmount(long fen) {
        return BigDecimal.valueOf(fen, 2).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * 把 {@code totalFen} 按 {@code weightsFen} 的权重分摊到各份，整数分且总和恰等于 totalFen。
     *
     * <p>最大余数法：先向下取整，再把剩余的分按余数从大到小逐份 +1；余数相同按序号升序，
     * 保证同一输入永远得到同一结果（可重放、可对账）。任何一份都不会超过自己的权重。
     *
     * @param totalFen   要分摊的总分，必须 0 ≤ totalFen ≤ ΣweightsFen
     * @param weightsFen 各份权重(通常就是各原分录金额)
     * @return 与 weightsFen 等长的分摊结果，Σ == totalFen
     */
    public static long[] allocate(long totalFen, long[] weightsFen) {
        if (weightsFen == null || weightsFen.length == 0) {
            throw new IllegalArgumentException("allocate requires at least one line");
        }
        long weightSum = 0;
        for (long weight : weightsFen) {
            if (weight < 0) {
                throw new IllegalArgumentException("negative line weight");
            }
            weightSum = Math.addExact(weightSum, weight);
        }
        if (totalFen < 0 || totalFen > weightSum) {
            throw new IllegalArgumentException("total outside line range: total=" + totalFen + " sum=" + weightSum);
        }
        long[] target = new long[weightsFen.length];
        if (totalFen == 0 || weightSum == 0) {
            return target;
        }
        if (weightSum == totalFen) {
            System.arraycopy(weightsFen, 0, target, 0, weightsFen.length);
            return target;
        }
        BigInteger sum = BigInteger.valueOf(weightSum);
        BigInteger total = BigInteger.valueOf(totalFen);
        long[] remainder = new long[weightsFen.length];
        long assigned = 0;
        for (int i = 0; i < weightsFen.length; i++) {
            BigInteger[] quotientAndRemainder =
                    BigInteger.valueOf(weightsFen[i]).multiply(total).divideAndRemainder(sum);
            target[i] = quotientAndRemainder[0].longValueExact();
            // 余数/除数 < 1，可安全降到 long 作排序键（除数规模远小于 long 上限）。
            remainder[i] = quotientAndRemainder[1].compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                    ? Long.MAX_VALUE / 2 : quotientAndRemainder[1].longValue();
            assigned += target[i];
        }
        long remaining = totalFen - assigned;
        if (remaining <= 0) {
            return target;
        }
        // 余数从大到小；余数相同按序号升序 —— 稳定且可重放。
        Integer[] order = new Integer[weightsFen.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.<Integer>comparingLong(i -> -remainder[i]).thenComparingInt(i -> i));
        for (Integer index : order) {
            if (remaining == 0) {
                break;
            }
            if (target[index] < weightsFen[index]) {
                target[index]++;
                remaining--;
            }
        }
        // 防御：向下取整最多丢 n-1 分，一轮必然补齐；补不齐说明实现被改坏，宁可失败。
        if (remaining != 0) {
            throw new IllegalStateException("largest-remainder allocation incomplete: remaining=" + remaining);
        }
        return target;
    }
}
