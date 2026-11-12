package com.chengyinhub.business.service.workshop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C10 exclusive H5 session / domain / SDK capability gate.
 *
 * <p>Missing original H5 container / SDK / module-pack texts are
 * {@link #H5_SDK_ORIGINAL_PRESENT false} → {@code WAITING_SOURCE}. Arbitrary
 * H5 URLs are denied. Version incompatibility rejects. This does not open a
 * WebView and does not invent SDK methods.
 */
public final class H5SessionCapabilityGate {

    public static final boolean H5_SDK_ORIGINAL_PRESENT = WorkshopModuleImportGate.H5_SDK_ORIGINAL_PRESENT;

    public enum Outcome {
        ALLOW,
        REJECTED,
        WAITING_SOURCE,
        UNKNOWN
    }

    public record SessionFact(
            String originalRequestId,
            String requestedUrl,
            String frozenSdkVersion,
            String clientSdkVersion,
            List<String> frozenDomainAllowlist,
            Set<String> frozenCapabilities,
            Set<String> requestedCapabilities) {

        public SessionFact {
            frozenDomainAllowlist = frozenDomainAllowlist == null ? List.of() : List.copyOf(frozenDomainAllowlist);
            frozenCapabilities = frozenCapabilities == null ? Set.of() : Set.copyOf(frozenCapabilities);
            requestedCapabilities = requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities);
        }
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            boolean openedArbitraryH5,
            boolean inventedSdkMethod,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }

        public boolean grantsSession() {
            return outcome == Outcome.ALLOW;
        }
    }

    private final WorkshopOriginalRequestBinder binder;

    public H5SessionCapabilityGate() {
        this(new WorkshopOriginalRequestBinder());
    }

    public H5SessionCapabilityGate(WorkshopOriginalRequestBinder binder) {
        this.binder = binder == null ? new WorkshopOriginalRequestBinder() : binder;
    }

    public Decision evaluate(SessionFact fact) {
        Map<String, Object> result = binder.oneShot(
                fact == null ? null : fact.originalRequestId(),
                "h5Session",
                () -> live(fact));
        return toDecision(result);
    }

    public Decision recover(String originalRequestId) {
        return toDecision(binder.recover(originalRequestId));
    }

    private Map<String, Object> live(SessionFact fact) {
        if (fact == null) {
            return WorkshopOriginalRequestBinder.unknown(null, "h5Session", "session fact missing");
        }
        if (fact.originalRequestId() == null || fact.originalRequestId().isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "h5Session", "originalRequestId missing");
        }
        if (!H5_SDK_ORIGINAL_PRESENT) {
            Map<String, Object> waiting = WorkshopOriginalRequestBinder.unknown(
                    fact.originalRequestId(), "h5Session",
                    WorkshopOriginalRequestBinder.WAITING_SOURCE + ":h5.sdk.original");
            waiting.put("status", WorkshopOriginalRequestBinder.WAITING_SOURCE);
            waiting.put("outcome", Outcome.WAITING_SOURCE.name());
            waiting.put("openedArbitraryH5", Boolean.FALSE);
            waiting.put("inventedSdkMethod", Boolean.FALSE);
            return waiting;
        }
        if (fact.requestedUrl() == null || fact.requestedUrl().isBlank()) {
            return rejected(fact.originalRequestId(), "url missing");
        }
        if (!urlOnAllowlist(fact.requestedUrl(), fact.frozenDomainAllowlist())) {
            return rejected(fact.originalRequestId(), "arbitrary H5 denied");
        }
        if (fact.frozenSdkVersion() == null || fact.clientSdkVersion() == null
                || !fact.frozenSdkVersion().equals(fact.clientSdkVersion())) {
            return rejected(fact.originalRequestId(), "sdk version incompatible");
        }
        if (!fact.frozenCapabilities().containsAll(fact.requestedCapabilities())) {
            return rejected(fact.originalRequestId(), "capability not in frozen set");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("originalRequestId", fact.originalRequestId());
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        ok.put("outcome", Outcome.ALLOW.name());
        ok.put("openedArbitraryH5", Boolean.FALSE);
        ok.put("inventedSdkMethod", Boolean.FALSE);
        ok.put("reason", "frozen domain and capability allowlist");
        return ok;
    }

    static boolean urlOnAllowlist(String url, List<String> allowlist) {
        if (url == null || allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("https://"))) {
            return false;
        }
        for (String allowed : allowlist) {
            if (allowed != null && !allowed.isBlank() && lower.startsWith(allowed.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> rejected(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", WorkshopOriginalRequestBinder.REJECTED);
        out.put("outcome", Outcome.REJECTED.name());
        out.put("openedArbitraryH5", Boolean.FALSE);
        out.put("inventedSdkMethod", Boolean.FALSE);
        out.put("reason", reason);
        out.put("secondEffect", Boolean.FALSE);
        return out;
    }

    private static Decision toDecision(Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        Outcome outcome = Outcome.UNKNOWN;
        Object named = result.get("outcome");
        if (named instanceof String) {
            try {
                outcome = Outcome.valueOf((String) named);
            } catch (IllegalArgumentException ignored) {
                outcome = Outcome.UNKNOWN;
            }
        } else if (WorkshopOriginalRequestBinder.WAITING_SOURCE.equals(status)) {
            outcome = Outcome.WAITING_SOURCE;
        } else if (WorkshopOriginalRequestBinder.REJECTED.equals(status)) {
            outcome = Outcome.REJECTED;
        } else if (WorkshopOriginalRequestBinder.UNKNOWN.equals(status)) {
            outcome = Outcome.UNKNOWN;
        } else if (WorkshopOriginalRequestBinder.REPLAY.equals(status)) {
            outcome = Outcome.ALLOW;
        }
        Object requestId = result.get("originalRequestId");
        return new Decision(
                outcome,
                requestId instanceof String ? (String) requestId : null,
                Boolean.TRUE.equals(result.get("openedArbitraryH5")),
                Boolean.TRUE.equals(result.get("inventedSdkMethod")),
                result.get("reason") == null ? status : String.valueOf(result.get("reason")));
    }
}
