package com.chengyinhub.business.service.workshop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C10 exclusive version freeze. Same originalRequestId republish is idempotent.
 * Mutating a frozen contentHash without a new version fails.
 *
 * <p>Does not own the public author-publish / install registry (dot hunk).
 */
public final class WorkshopPackageVersionPublish {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    public enum Outcome {
        FROZEN,
        REPLAY,
        REJECTED,
        UNKNOWN
    }

    public record FreezeFact(
            String originalRequestId,
            String moduleId,
            String versionLabel,
            String contentHash,
            String termsHash) {
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            String frozenContentHash,
            String frozenTermsHash,
            boolean wrotePublicRegistry,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }

        public boolean grantsPublish() {
            return outcome == Outcome.FROZEN || outcome == Outcome.REPLAY;
        }
    }

    private final WorkshopOriginalRequestBinder binder;
    private FreezeFact frozen;

    public WorkshopPackageVersionPublish() {
        this(new WorkshopOriginalRequestBinder());
    }

    public WorkshopPackageVersionPublish(WorkshopOriginalRequestBinder binder) {
        this.binder = binder == null ? new WorkshopOriginalRequestBinder() : binder;
    }

    public FreezeFact frozen() {
        return frozen;
    }

    public Decision freeze(FreezeFact fact) {
        Map<String, Object> result = binder.oneShot(
                fact == null ? null : fact.originalRequestId(),
                "versionPublish",
                () -> liveFreeze(fact));
        return toDecision(result);
    }

    public Decision recover(String originalRequestId) {
        return toDecision(binder.recover(originalRequestId));
    }

    private Map<String, Object> liveFreeze(FreezeFact fact) {
        if (fact == null) {
            return WorkshopOriginalRequestBinder.unknown(null, "versionPublish", "freeze fact missing");
        }
        if (fact.originalRequestId() == null || fact.originalRequestId().isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "versionPublish", "originalRequestId missing");
        }
        if (fact.moduleId() == null || fact.moduleId().isBlank()
                || fact.versionLabel() == null || fact.versionLabel().isBlank()) {
            return rejected(fact.originalRequestId(), "moduleId or versionLabel missing");
        }
        if (fact.contentHash() == null || !HASH.matcher(fact.contentHash()).matches()
                || fact.termsHash() == null || !HASH.matcher(fact.termsHash()).matches()) {
            return rejected(fact.originalRequestId(), "contentHash and termsHash must be sha256 hex");
        }
        if (frozen != null
                && frozen.moduleId().equals(fact.moduleId())
                && frozen.versionLabel().equals(fact.versionLabel())
                && !frozen.contentHash().equals(fact.contentHash())) {
            return rejected(fact.originalRequestId(), "frozen hash cannot mutate without new version");
        }
        frozen = fact;
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("originalRequestId", fact.originalRequestId());
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        ok.put("outcome", Outcome.FROZEN.name());
        ok.put("frozenContentHash", fact.contentHash());
        ok.put("frozenTermsHash", fact.termsHash());
        ok.put("wrotePublicRegistry", Boolean.FALSE);
        ok.put("reason", "version frozen");
        return ok;
    }

    private static Map<String, Object> rejected(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", WorkshopOriginalRequestBinder.REJECTED);
        out.put("outcome", Outcome.REJECTED.name());
        out.put("wrotePublicRegistry", Boolean.FALSE);
        out.put("reason", reason);
        out.put("secondEffect", Boolean.FALSE);
        return out;
    }

    private static Decision toDecision(Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        Outcome outcome = Outcome.UNKNOWN;
        if (WorkshopOriginalRequestBinder.REPLAY.equals(status)) {
            outcome = Outcome.REPLAY;
        } else if (WorkshopOriginalRequestBinder.REJECTED.equals(status)) {
            outcome = Outcome.REJECTED;
        } else if (WorkshopOriginalRequestBinder.APPLIED.equals(status)) {
            outcome = Outcome.FROZEN;
        } else if (WorkshopOriginalRequestBinder.UNKNOWN.equals(status)) {
            outcome = Outcome.UNKNOWN;
        }
        Object requestId = result.get("originalRequestId");
        Object content = result.get("frozenContentHash");
        Object terms = result.get("frozenTermsHash");
        return new Decision(
                outcome,
                requestId instanceof String ? (String) requestId : null,
                content instanceof String ? (String) content : null,
                terms instanceof String ? (String) terms : null,
                Boolean.TRUE.equals(result.get("wrotePublicRegistry")),
                result.get("reason") == null ? status : String.valueOf(result.get("reason")));
    }
}
