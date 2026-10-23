package com.chengyinhub.business.service.fulfillment;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.LedgerDiscrepancy;

/**
 * C07 exclusive adapter: reread the four ledgers for an original requestId.
 *
 * <p>Dimensions match P1-5 《四账映射与守恒规则》: 微信交易, 报名/订单,
 * 内部资金流水, 商家结算. Consumes already-loaded {@link LedgerDiscrepancy}
 * shaped facts. Does not call {@code FourLedgerReconcileServiceImpl} (no new
 * reconcile request, no auto-compensation). Missing bill / source hash is
 * UNKNOWN, same fail-closed as the writer's "账单尚未完整导入，待核实".
 *
 * <p>Amount tolerance copies the writer's {@code 0.01} constant; this adapter
 * must not reach into that class.
 */
public final class FourLedgerBillReread {

    /** Same cents tolerance as {@code FourLedgerReconcileServiceImpl.TOLERANCE}. */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    public enum Dimension {
        WECHAT_TRADE,
        REGISTRATION_ORDER,
        MEMBER_BALANCE,
        MERCHANT_PAYABLE
    }

    public enum Outcome {
        MATCHED,
        DIVERGED,
        UNKNOWN
    }

    public record BillFact(
            String originalRequestId,
            Dimension dimension,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            boolean billPresent,
            String sourceHash) {

        public BillFact {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            expectedAmount = expectedAmount == null ? BigDecimal.ZERO : expectedAmount;
            actualAmount = actualAmount == null ? BigDecimal.ZERO : actualAmount;
            sourceHash = sourceHash == null ? "" : sourceHash;
        }
    }

    public record View(
            String originalRequestId,
            Map<Dimension, Outcome> outcomes,
            boolean startedNewReconcile,
            boolean currentProjectionReplacesOriginal) {

        public View {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            outcomes = outcomes == null
                    ? Collections.unmodifiableMap(emptyUnknown())
                    : Collections.unmodifiableMap(new EnumMap<Dimension, Outcome>(outcomes));
        }

        public Outcome outcome(Dimension dimension) {
            Outcome value = outcomes.get(dimension);
            return value == null ? Outcome.UNKNOWN : value;
        }
    }

    /**
     * Reread bills already attributed to {@code originalRequestId}. Facts for a
     * different id are ignored (do not start a new request). Missing dimensions
     * stay UNKNOWN.
     */
    public View reread(String originalRequestId, List<BillFact> facts) {
        String requestId = FulfillmentEvidence.requireRequestId(originalRequestId);
        Map<Dimension, Outcome> outcomes = emptyUnknown();
        if (facts == null || facts.isEmpty()) {
            return new View(requestId, outcomes, false, false);
        }
        for (BillFact fact : facts) {
            if (fact == null || fact.dimension() == null || !requestId.equals(fact.originalRequestId())) {
                continue;
            }
            outcomes.put(fact.dimension(), outcomeOf(fact));
        }
        return new View(requestId, outcomes, false, false);
    }

    public View requery(String originalRequestId, List<BillFact> facts) {
        return reread(originalRequestId, facts);
    }

    /**
     * Current APPLIED+SUCCESS projection must not rewrite an UNKNOWN original bill.
     */
    public boolean originalUnknownNotReplacedByProjectionSuccess(View original, boolean projectionClaimsSuccess) {
        if (original == null) {
            return true;
        }
        if (!projectionClaimsSuccess) {
            return true;
        }
        for (Outcome outcome : original.outcomes.values()) {
            if (outcome == Outcome.UNKNOWN) {
                return true;
            }
        }
        return !original.currentProjectionReplacesOriginal;
    }

    private static Outcome outcomeOf(BillFact fact) {
        if (!fact.billPresent() || fact.sourceHash().isBlank()) {
            return Outcome.UNKNOWN;
        }
        BigDecimal delta = fact.actualAmount().subtract(fact.expectedAmount()).abs();
        if (delta.compareTo(TOLERANCE) > 0) {
            return Outcome.DIVERGED;
        }
        return Outcome.MATCHED;
    }

    private static Map<Dimension, Outcome> emptyUnknown() {
        Map<Dimension, Outcome> outcomes = new EnumMap<Dimension, Outcome>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            outcomes.put(dimension, Outcome.UNKNOWN);
        }
        return outcomes;
    }
}
