package com.chengyinhub.business.service.fulfillment;

/**
 * C07 exclusive adapter: transfer of an already-sold entitlement.
 *
 * <p>Requires three originals that are <b>not</b> on this remote tree:
 * sold-policy, designated recipient authorization, irrevocable-effect.
 * Missing originals are {@link #WAITING_SOURCE}; this class does not invent
 * them.
 *
 * <p>D4 17 SMS / phone identity ({@code SmsVerificationService}, login bind)
 * is a different domain. A verified SMS code is not sold-policy, not recipient
 * authorization, and not an original receipt chain.
 */
public final class TransferSoldPolicyGate {

    public static final String WAITING_SOURCE = "WAITING_SOURCE";

    /**
     * Remote originals for transfer-sold policy were not pushed
     * ({@code e16c2c5} / D4 28/36/37/38 candidates are not this gate).
     */
    public static final boolean REMOTE_SOLD_POLICY_ORIGINAL_PRESENT = false;

    public enum Outcome {
        ALLOW,
        DENY,
        WAITING_SOURCE
    }

    /**
     * @param smsIdentityVerified D4 17 phone-identity bit; must be passed so it
     *                            can be ignored as sold-policy
     */
    public record TransferFact(
            String originalRequestId,
            String soldPolicySourceHash,
            Long designatedRecipientMemberId,
            boolean irrevocableEffectRecorded,
            boolean smsIdentityVerified) {

        public TransferFact {
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
            soldPolicySourceHash = soldPolicySourceHash == null ? "" : soldPolicySourceHash;
        }
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            boolean usedSmsIdentityAsPolicy,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.WAITING_SOURCE : outcome;
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
            reason = reason == null ? "" : reason;
        }

        public boolean grantsTransfer() {
            return outcome == Outcome.ALLOW;
        }
    }

    /**
     * Evaluate transfer of a sold entitlement. SMS identity never satisfies
     * policy even when {@code smsIdentityVerified} is true.
     */
    public Decision evaluate(TransferFact fact) {
        if (fact == null) {
            return waiting("", false, "transfer fact missing");
        }
        String requestId = fact.originalRequestId();
        if (requestId.isBlank()) {
            return deny("", false, "originalRequestId missing");
        }
        if (fact.smsIdentityVerified()) {
            // Explicitly consume the bit so callers cannot skip it; it still
            // does not authorize transfer.
            if (!REMOTE_SOLD_POLICY_ORIGINAL_PRESENT || fact.soldPolicySourceHash().isBlank()) {
                return waiting(requestId, false,
                        "SMS identity is not transfer-sold policy; originals WAITING_SOURCE");
            }
        }
        if (!REMOTE_SOLD_POLICY_ORIGINAL_PRESENT || fact.soldPolicySourceHash().isBlank()) {
            return waiting(requestId, false, WAITING_SOURCE + ": sold-policy original not on remote");
        }
        if (fact.designatedRecipientMemberId() == null || fact.designatedRecipientMemberId() <= 0L) {
            return deny(requestId, false, "designated recipient missing");
        }
        if (!fact.irrevocableEffectRecorded()) {
            return deny(requestId, false, "irrevocable-effect original missing");
        }
        return new Decision(Outcome.ALLOW, requestId, false, "sold-policy + recipient + irrevocable");
    }

    public Decision requery(TransferFact fact) {
        return evaluate(fact);
    }

    private static Decision waiting(String requestId, boolean usedSms, String reason) {
        return new Decision(Outcome.WAITING_SOURCE, requestId, usedSms, reason);
    }

    private static Decision deny(String requestId, boolean usedSms, String reason) {
        return new Decision(Outcome.DENY, requestId, usedSms, reason);
    }
}
