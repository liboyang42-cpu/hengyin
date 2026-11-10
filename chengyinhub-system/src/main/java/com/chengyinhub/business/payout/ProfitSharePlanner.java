package com.chengyinhub.business.payout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 由内部账本（coop_settlement_original_component：每笔付款在每条结算行上的份额）推出一笔支付单的微信分账计划。
 * 纯函数：份额只读账本，不重算；任何对不上都转人工（MANUAL），不发起部分分账。
 */
public final class ProfitSharePlanner {

    public enum ReceiverKind { PLATFORM, SUB_MERCHANT, RETAINED }

    /** 账本里一笔付款在一条结算行上的份额（元，最多两位小数）。receiverKind=ACCOUNTING 表示平台。 */
    public record Component(long settlementId, String lineType, String receiverKind, Long receiverMemberId, BigDecimal amountYuan) { }

    /** 以二级商户为卖家的支付单。sellerMemberIds：代表卖家本身的会员（商家主账号/俱乐部主理人），其份额留在卖家。 */
    public record Order(String outTradeNo, String transactionId, String subMchid, long totalFen, Set<Long> sellerMemberIds) { }

    public record Line(long settlementId, String lineType, ReceiverKind kind, String receiverMchid, Long receiverMemberId, long amountFen) { }

    /** 发给微信的接收方（同一商户号合并）。 */
    public record Receiver(String mchid, long amountFen) { }

    public record Plan(String state, String manualReason, List<Line> lines, List<Receiver> receivers, long sharedFen, String planHash) {
        public boolean ready() { return "READY".equals(state); }
        public boolean nothingToShare() { return "NOTHING".equals(state); }
    }

    private ProfitSharePlanner() { }

    /**
     * @param memberSubMchid 会员 → 其已完成进件的二级商户号（商家主账号或俱乐部主理人）；没有则 empty。
     */
    public static Plan plan(Order order, Collection<Component> components, String platformMchid, int maxSharePercent,
                            Function<Long, Optional<String>> memberSubMchid) {
        if (order == null || order.totalFen() <= 0 || order.subMchid() == null || order.subMchid().isBlank()
                || order.transactionId() == null || order.transactionId().isBlank())
            return manual("ORDER_FACTS_MISSING", List.of());
        if (components == null || components.isEmpty()) return manual("NO_LEDGER_COMPONENTS", List.of());
        List<Line> lines = new ArrayList<>();
        long sum = 0;
        for (Component c : components) {
            long fen;
            try {
                fen = toFen(c.amountYuan());
            } catch (ArithmeticException | NullPointerException bad) {
                return manual("LEDGER_AMOUNT_INVALID", lines);
            }
            if (fen < 0) return manual("LEDGER_NEGATIVE_COMPONENT", lines);
            sum += fen;
            if (fen == 0) continue;
            if ("ACCOUNTING".equals(c.receiverKind())) {
                if (platformMchid == null || platformMchid.isBlank()) return manual("PLATFORM_RECEIVER_MISSING", lines);
                if (platformMchid.equals(order.subMchid())) return manual("PLATFORM_IS_SELLER", lines);
                lines.add(new Line(c.settlementId(), c.lineType(), ReceiverKind.PLATFORM, platformMchid, null, fen));
                continue;
            }
            if (!"MEMBER".equals(c.receiverKind()) || c.receiverMemberId() == null)
                return manual("LEDGER_RECEIVER_UNKNOWN", lines);
            if (order.sellerMemberIds() != null && order.sellerMemberIds().contains(c.receiverMemberId())) {
                lines.add(new Line(c.settlementId(), c.lineType(), ReceiverKind.RETAINED, order.subMchid(), c.receiverMemberId(), fen));
                continue;
            }
            Optional<String> mchid = memberSubMchid == null ? Optional.empty() : memberSubMchid.apply(c.receiverMemberId());
            if (mchid == null || mchid.isEmpty() || mchid.get().isBlank()) return manual("RECEIVER_NOT_ONBOARDED", lines);
            if (mchid.get().equals(order.subMchid())) {
                lines.add(new Line(c.settlementId(), c.lineType(), ReceiverKind.RETAINED, order.subMchid(), c.receiverMemberId(), fen));
            } else {
                lines.add(new Line(c.settlementId(), c.lineType(), ReceiverKind.SUB_MERCHANT, mchid.get(), c.receiverMemberId(), fen));
            }
        }
        // 账本对这笔付款的份额之和必须正好等于实付；对不上说明账本不完整或已调整，不能按局部分账。
        if (sum != order.totalFen()) return manual("LEDGER_CONSERVATION", lines);
        Map<String, Long> merged = new LinkedHashMap<>();
        long shared = 0;
        for (Line l : lines) {
            if (l.kind() == ReceiverKind.RETAINED) continue;
            merged.merge(l.receiverMchid(), l.amountFen(), Long::sum);
            shared += l.amountFen();
        }
        // 微信电商收付通分账比例上限（开通时约定，默认 30%）。超过时不能请求，转人工。
        if (shared * 100 > (long) maxSharePercent * order.totalFen()) return manual("SHARE_RATIO_EXCEEDED", lines);
        List<Receiver> receivers = new ArrayList<>();
        merged.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> receivers.add(new Receiver(e.getKey(), e.getValue())));
        lines.sort(Comparator.comparingLong(Line::settlementId).thenComparing(l -> l.kind().name()));
        String hash = hash(order, lines);
        return new Plan(shared == 0 ? "NOTHING" : "READY", null, List.copyOf(lines), List.copyOf(receivers), shared, hash);
    }

    /**
     * 退款前的分账回退：按退款占实付的比例从每个接收方回退，最大余数法凑整，总额 = round(已分 × 退款 / 实付)，
     * 全额退款时全部回退。已回退的不再重复回退。
     *
     * @param sharedByMchid 接收方 → [已分, 已回退]
     */
    public static Map<String, Long> returnsForRefund(Map<String, long[]> sharedByMchid, long totalFen, long refundFen,
                                                     long refundedBeforeFen) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (sharedByMchid == null || sharedByMchid.isEmpty() || totalFen <= 0 || refundFen <= 0) return out;
        if (refundedBeforeFen < 0 || refundedBeforeFen + refundFen > totalFen)
            throw new IllegalArgumentException("退款累计超过实付");
        long cumulative = refundedBeforeFen + refundFen;
        List<String> keys = new ArrayList<>(sharedByMchid.keySet());
        keys.sort(Comparator.naturalOrder());
        long sharedTotal = 0;
        for (String k : keys) sharedTotal += sharedByMchid.get(k)[0];
        // 截至本次退款累计应回退的总额，再减去已回退得出本次额度。
        long targetTotal = cumulative == totalFen ? sharedTotal : divRoundHalfUp(sharedTotal * cumulative, totalFen);
        long[] target = new long[keys.size()];
        long[] remainder = new long[keys.size()];
        long floorSum = 0;
        for (int i = 0; i < keys.size(); i++) {
            long shared = sharedByMchid.get(keys.get(i))[0];
            target[i] = sharedTotal == 0 ? 0 : (shared * targetTotal) / sharedTotal;
            remainder[i] = sharedTotal == 0 ? 0 : (shared * targetTotal) % sharedTotal;
            floorSum += target[i];
        }
        long left = targetTotal - floorSum;
        while (left > 0) {
            int best = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (target[i] >= sharedByMchid.get(keys.get(i))[0]) continue;
                if (best < 0 || remainder[i] > remainder[best]) best = i;
            }
            if (best < 0) break;
            target[best]++; remainder[best] = -1; left--;
        }
        for (int i = 0; i < keys.size(); i++) {
            long due = target[i] - sharedByMchid.get(keys.get(i))[1];
            if (due > 0) out.put(keys.get(i), due);
        }
        return out;
    }

    static long toFen(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
    }

    private static long divRoundHalfUp(long a, long b) { return (a * 2 + b) / (b * 2); }

    private static Plan manual(String reason, List<Line> lines) {
        return new Plan("MANUAL", reason, List.copyOf(lines), List.of(), 0, null);
    }

    private static String hash(Order order, List<Line> lines) {
        StringBuilder sb = new StringBuilder(order.outTradeNo()).append('|').append(order.transactionId()).append('|')
                .append(order.subMchid()).append('|').append(order.totalFen());
        for (Line l : lines) sb.append('|').append(l.settlementId()).append(':').append(l.kind()).append(':')
                .append(l.receiverMchid()).append(':').append(l.amountFen());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
