package com.chengyinhub.business.service.workshop;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C10 exclusive answer protection. Answers never ship in a client pack.
 * Comparison uses only the server-side frozen author version. Client clock
 * and JS reports cannot succeed.
 */
public final class H5AnswerProtection {

    public enum Outcome {
        SERVER_MATCH,
        REJECTED,
        UNKNOWN,
        WAITING_SOURCE
    }

    public record Attempt(
            String originalRequestId,
            Map<String, String> clientPack,
            String submittedAnswer,
            String frozenAuthorAnswer,
            String frozenAuthorVersion,
            String clientReportedVersion,
            boolean jsReportedClear,
            boolean usedClientClock) {

        public Attempt {
            clientPack = clientPack == null ? Map.of() : Map.copyOf(clientPack);
        }
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            boolean acceptedJsReport,
            boolean acceptedClientClock,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }
    }

    private final WorkshopOriginalRequestBinder binder;

    public H5AnswerProtection() {
        this(new WorkshopOriginalRequestBinder());
    }

    public H5AnswerProtection(WorkshopOriginalRequestBinder binder) {
        this.binder = binder == null ? new WorkshopOriginalRequestBinder() : binder;
    }

    public Decision evaluate(Attempt attempt) {
        Map<String, Object> result = binder.oneShot(
                attempt == null ? null : attempt.originalRequestId(),
                "answerProtect",
                () -> live(attempt));
        return toDecision(result);
    }

    public Decision recover(String originalRequestId) {
        return toDecision(binder.recover(originalRequestId));
    }

    private Map<String, Object> live(Attempt attempt) {
        if (attempt == null) {
            return WorkshopOriginalRequestBinder.unknown(null, "answerProtect", "attempt missing");
        }
        if (attempt.originalRequestId() == null || attempt.originalRequestId().isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "answerProtect", "originalRequestId missing");
        }
        try {
            WorkshopModuleImportGate.inspect(attempt.clientPack());
        } catch (IllegalArgumentException ex) {
            return rejected(attempt.originalRequestId(), ex.getMessage());
        }
        if (attempt.jsReportedClear()) {
            return rejected(attempt.originalRequestId(), "JS self-reported clear rejected");
        }
        if (attempt.usedClientClock()) {
            return rejected(attempt.originalRequestId(), "client clock cannot succeed");
        }
        if (attempt.frozenAuthorAnswer() == null || attempt.frozenAuthorVersion() == null) {
            if (!WorkshopModuleImportGate.H5_SDK_ORIGINAL_PRESENT) {
                Map<String, Object> waiting = WorkshopOriginalRequestBinder.unknown(
                        attempt.originalRequestId(), "answerProtect",
                        WorkshopOriginalRequestBinder.WAITING_SOURCE + ":h5.sdk.original");
                waiting.put("status", WorkshopOriginalRequestBinder.WAITING_SOURCE);
                waiting.put("outcome", Outcome.WAITING_SOURCE.name());
                waiting.put("acceptedJsReport", Boolean.FALSE);
                waiting.put("acceptedClientClock", Boolean.FALSE);
                return waiting;
            }
            return WorkshopOriginalRequestBinder.unknown(
                    attempt.originalRequestId(), "answerProtect", "frozen author answer missing");
        }
        if (attempt.clientReportedVersion() != null
                && !attempt.frozenAuthorVersion().equals(attempt.clientReportedVersion())) {
            return rejected(attempt.originalRequestId(), "client version is not frozen author version");
        }
        if (attempt.submittedAnswer() == null
                || !attempt.frozenAuthorAnswer().equals(attempt.submittedAnswer())) {
            return rejected(attempt.originalRequestId(), "answer does not match frozen author version");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("originalRequestId", attempt.originalRequestId());
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        ok.put("outcome", Outcome.SERVER_MATCH.name());
        ok.put("acceptedJsReport", Boolean.FALSE);
        ok.put("acceptedClientClock", Boolean.FALSE);
        ok.put("reason", "server frozen author version");
        return ok;
    }

    private static Map<String, Object> rejected(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", WorkshopOriginalRequestBinder.REJECTED);
        out.put("outcome", Outcome.REJECTED.name());
        out.put("acceptedJsReport", Boolean.FALSE);
        out.put("acceptedClientClock", Boolean.FALSE);
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
            outcome = Outcome.SERVER_MATCH;
        }
        Object requestId = result.get("originalRequestId");
        return new Decision(
                outcome,
                requestId instanceof String ? (String) requestId : null,
                Boolean.TRUE.equals(result.get("acceptedJsReport")),
                Boolean.TRUE.equals(result.get("acceptedClientClock")),
                result.get("reason") == null ? status : String.valueOf(result.get("reason")));
    }
}
