package com.chengyinhub.business.payout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * l-payout3：主题结算冻结后，一笔合单（卖家子单 A + 平台自营子单 B）怎么对账。纯函数：份额只读账本，不重算。
 *
 * <p>账本里这笔付款的分项分三类：平台佣金 P（ACCOUNTING）、卖家本人的份额 S（发起方会员）、其他收款方 O（带队俱乐部 / 核销商家）。
 * 守恒：P + S + ΣO = A + B。
 * <ul>
 *   <li>B ≥ P + ΣO：平台自营子单够付。其他收款方按供应商结算由平台付（每人一条应付）；多出的 B − P − ΣO 是卖家的，
 *       同样按供应商结算付回卖家（SURPLUS）。卖家子单不分账，完结分账解冻给卖家。</li>
 *   <li>B &lt; P + ΣO（合作单在下单后才生效，预留不够）：差额 = A − S，从卖家子单分账给平台（电商平台向平台分账是允许的，
 *       受分账比例上限约束），其他收款方仍按供应商结算由平台付。超过比例上限转人工。</li>
 * </ul>
 * 任何对不上都转人工，不发起部分分账，也不记会员余额。
 */
public final class CombinedSettlementPlanner {

    public record Order(String businessOutTradeNo, String sellerTransactionId, long sellerFen, long heldFen, Long sellerMemberId) { }

    public record Payable(long settlementId, String lineType, long payeeMemberId, long amountFen) { }

    public record Plan(String state, String manualReason, long platformFen, long shortfallFen, long surplusFen,
                       List<Payable> payables, String planHash) {
        public boolean ready() { return "READY".equals(state); }
    }

    public static final String SURPLUS = "SURPLUS";

    private CombinedSettlementPlanner() { }

    public static Plan plan(Order order, Collection<ProfitSharePlanner.Component> components, int maxSharePercent) {
        if (order == null || order.sellerFen() <= 0 || order.heldFen() <= 0 || order.sellerTransactionId() == null
                || order.sellerTransactionId().isBlank()) return manual("ORDER_FACTS_MISSING");
        if (order.sellerMemberId() == null || order.sellerMemberId() <= 0) return manual("SELLER_MEMBER_UNKNOWN");
        if (components == null || components.isEmpty()) return manual("NO_LEDGER_COMPONENTS");
        long platform = 0, seller = 0, others = 0;
        long sellerSettlementId = -1;
        List<Payable> payables = new ArrayList<>();
        for (ProfitSharePlanner.Component c : components) {
            long fen;
            try {
                fen = ProfitSharePlanner.toFen(c.amountYuan());
            } catch (ArithmeticException | NullPointerException bad) {
                return manual("LEDGER_AMOUNT_INVALID");
            }
            if (fen < 0) return manual("LEDGER_NEGATIVE_COMPONENT");
            if ("ACCOUNTING".equals(c.receiverKind())) {
                platform += fen;
                continue;
            }
            if (!"MEMBER".equals(c.receiverKind()) || c.receiverMemberId() == null) return manual("LEDGER_RECEIVER_UNKNOWN");
            if (order.sellerMemberId().equals(c.receiverMemberId())) {
                seller += fen;
                if (fen > 0 && (sellerSettlementId < 0 || c.settlementId() < sellerSettlementId)) sellerSettlementId = c.settlementId();
                continue;
            }
            others += fen;
            if (fen > 0) payables.add(new Payable(c.settlementId(), c.lineType(), c.receiverMemberId(), fen));
        }
        long total = order.sellerFen() + order.heldFen();
        if (platform + seller + others != total) return manual("LEDGER_CONSERVATION");
        long needFromHeld = platform + others;
        long shortfall = 0, surplus = 0;
        if (order.heldFen() >= needFromHeld) {
            surplus = order.heldFen() - needFromHeld;
            if (surplus > 0) {
                if (sellerSettlementId < 0) return manual("SURPLUS_WITHOUT_SELLER_LINE");
                payables.add(new Payable(sellerSettlementId, SURPLUS, order.sellerMemberId(), surplus));
            }
        } else {
            shortfall = needFromHeld - order.heldFen(); // = sellerFen − seller
            if (shortfall * 100 > (long) maxSharePercent * order.sellerFen()) return manual("SHARE_RATIO_EXCEEDED");
        }
        payables.sort(Comparator.comparingLong(Payable::settlementId).thenComparingLong(Payable::payeeMemberId)
                .thenComparing(Payable::lineType));
        StringBuilder sb = new StringBuilder(order.businessOutTradeNo()).append('|').append(order.sellerTransactionId())
                .append('|').append(order.sellerFen()).append('|').append(order.heldFen()).append('|').append(platform)
                .append('|').append(shortfall).append('|').append(surplus);
        for (Payable p : payables) sb.append('|').append(p.settlementId()).append(':').append(p.lineType()).append(':')
                .append(p.payeeMemberId()).append(':').append(p.amountFen());
        return new Plan("READY", null, platform, shortfall, surplus, List.copyOf(payables), sha(sb.toString()));
    }

    private static Plan manual(String reason) {
        return new Plan("MANUAL", reason, 0, 0, 0, List.of(), null);
    }

    static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
