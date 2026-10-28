package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.RefundApplication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable internal facts for consumers sharing the registration -> refund-history lock order.
 * Construct from IRefundService.selectRegistrationHistoryForUpdate inside the caller's transaction,
 * after locking the original registration(s). This projection neither acquires locks nor proves
 * that an empty read means a previous external request was never accepted.
 * No invoice transition, recipient, tax information, user-entered reason or new funds policy.
 */
public record RegistrationRefundFacts(Long registrationId, List<Fact> refunds, boolean unknownHistory) {
    public RegistrationRefundFacts {
        if (registrationId == null || registrationId <= 0) {
            throw new IllegalArgumentException("原报名编号不合法");
        }
        refunds = List.copyOf(refunds);
    }

    /** Keeps provider payout state separate from refund approval; amounts stay exact and unaggregated. */
    public record Fact(Long refundId, Integer reviewStatus, Integer payoutStatus, BigDecimal refundAmount) {}

    public static RegistrationRefundFacts fromHistory(Long registrationId, List<RefundApplication> history) {
        if (registrationId == null || registrationId <= 0) {
            throw new IllegalArgumentException("原报名编号不合法");
        }
        List<Fact> facts = new ArrayList<>();
        boolean unknown = history == null;
        if (history != null) {
            for (RefundApplication row : history) {
                if (row == null) {
                    unknown = true;
                    continue;
                }
                if (!"registration".equals(row.getSourceType()) || !registrationId.equals(row.getSourceId())) {
                    throw new IllegalStateException("退款历史与原报名不一致");
                }
                // Deleted, unidentified or malformed legacy rows cannot establish eligibility.
                Integer status = row.getStatus();
                if (row.getId() == null || row.getId() <= 0 || !Integer.valueOf(0).equals(row.getDelFlag())
                        || status == null || status < 0 || status > 2
                        || row.getRefundAmount() == null || row.getRefundAmount().signum() <= 0) {
                    unknown = true;
                }
                facts.add(new Fact(row.getId(), status, row.getPayoutStatus(), row.getRefundAmount()));
            }
        }
        return new RegistrationRefundFacts(registrationId, facts, unknown);
    }

    /** A failed/manual/unknown payout does not undo an approved refund. */
    public boolean hasPendingOrApproved() {
        return refunds.stream().anyMatch(f -> Integer.valueOf(0).equals(f.reviewStatus())
                || Integer.valueOf(1).equals(f.reviewStatus()));
    }
}
