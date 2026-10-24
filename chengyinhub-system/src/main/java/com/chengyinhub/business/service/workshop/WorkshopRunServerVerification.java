package com.chengyinhub.business.service.workshop;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C10 exclusive run verification. Pass/fail comes from a server receipt bound
 * to the original requestId. JS / H5 self-reported clears are rejected.
 *
 * <p>UNKNOWN keeps original intent, does not mint a new requestId, and does
 * not treat the current projection as success.
 */
public final class WorkshopRunServerVerification {

    public enum Outcome {
        PASSED,
        FAILED,
        REJECTED,
        UNKNOWN,
        WAITING_SOURCE
    }

    public record ServerReceipt(
            String originalRequestId,
            Boolean passed,
            String receiptHash) {
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            boolean acceptedJsSelfReport,
            boolean mintedNewRequestId,
            boolean projectionTakenAsSuccess,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }
    }

    private final WorkshopOriginalRequestBinder binder;

    public WorkshopRunServerVerification() {
        this(new WorkshopOriginalRequestBinder());
    }

    public WorkshopRunServerVerification(WorkshopOriginalRequestBinder binder) {
        this.binder = binder == null ? new WorkshopOriginalRequestBinder() : binder;
    }

    public Decision verifyServer(ServerReceipt receipt) {
        Map<String, Object> result = binder.oneShot(
                receipt == null ? null : receipt.originalRequestId(),
                "runVerify",
                () -> liveVerify(receipt, false));
        return toDecision(result);
    }

    /** Explicit rejection path: a client/JS self-report is never a pass. */
    public Decision rejectJsSelfReport(String originalRequestId, boolean jsReportedClear) {
        Map<String, Object> result = binder.oneShot(originalRequestId, "runVerifyJs", () -> {
            if (!jsReportedClear) {
                return WorkshopOriginalRequestBinder.unknown(originalRequestId, "runVerifyJs", "no js report");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("originalRequestId", originalRequestId);
            out.put("status", WorkshopOriginalRequestBinder.REJECTED);
            out.put("outcome", Outcome.REJECTED.name());
            out.put("acceptedJsSelfReport", Boolean.FALSE);
            out.put("mintedNewRequestId", Boolean.FALSE);
            out.put("projectionTakenAsSuccess", Boolean.FALSE);
            out.put("reason", "JS/H5 self-reported clear rejected");
            return out;
        });
        return toDecision(result);
    }

    public Decision recoverUnknown(String originalRequestId) {
        return toDecision(binder.markUnknown(originalRequestId, "server receipt UNKNOWN"));
    }

    public Decision recover(String originalRequestId) {
        return toDecision(binder.recover(originalRequestId));
    }

    /**
     * Lookup original intent. Missing / never-seen ids stay UNKNOWN with Java
     * {@code null} requestId when blank, and do not occupy the ledger.
     */
    public Decision recoverOriginal(String originalRequestId) {
        return recover(originalRequestId);
    }

    private Map<String, Object> liveVerify(ServerReceipt receipt, boolean jsSelfReport) {
        if (jsSelfReport) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("originalRequestId", receipt == null ? null : receipt.originalRequestId());
            out.put("status", WorkshopOriginalRequestBinder.REJECTED);
            out.put("outcome", Outcome.REJECTED.name());
            out.put("acceptedJsSelfReport", Boolean.FALSE);
            out.put("reason", "JS/H5 self-reported clear rejected");
            return out;
        }
        if (receipt == null) {
            return WorkshopOriginalRequestBinder.unknown(null, "runVerify", "server receipt missing");
        }
        if (receipt.originalRequestId() == null || receipt.originalRequestId().isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "runVerify", "originalRequestId missing");
        }
        if (receipt.passed() == null || receipt.receiptHash() == null || receipt.receiptHash().isBlank()) {
            Map<String, Object> unknown = WorkshopOriginalRequestBinder.unknown(
                    receipt.originalRequestId(), "runVerify", "server receipt UNKNOWN");
            unknown.put("outcome", Outcome.UNKNOWN.name());
            return unknown;
        }
        if (!WorkshopModuleImportGate.QMOD_ORIGINAL_PRESENT) {
            Map<String, Object> waiting = WorkshopOriginalRequestBinder.unknown(
                    receipt.originalRequestId(), "runVerify",
                    WorkshopOriginalRequestBinder.WAITING_SOURCE + ":qmod.original");
            waiting.put("status", WorkshopOriginalRequestBinder.WAITING_SOURCE);
            waiting.put("outcome", Outcome.WAITING_SOURCE.name());
            return waiting;
        }
        Outcome outcome = Boolean.TRUE.equals(receipt.passed()) ? Outcome.PASSED : Outcome.FAILED;
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("originalRequestId", receipt.originalRequestId());
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        ok.put("outcome", outcome.name());
        ok.put("acceptedJsSelfReport", Boolean.FALSE);
        ok.put("mintedNewRequestId", Boolean.FALSE);
        ok.put("projectionTakenAsSuccess", Boolean.FALSE);
        ok.put("receiptHash", receipt.receiptHash());
        ok.put("reason", outcome == Outcome.PASSED ? "server pass" : "server fail");
        return ok;
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
        }
        Object requestId = result.get("originalRequestId");
        return new Decision(
                outcome,
                requestId instanceof String ? (String) requestId : null,
                Boolean.TRUE.equals(result.get("acceptedJsSelfReport")),
                Boolean.TRUE.equals(result.get("mintedNewRequestId")),
                Boolean.TRUE.equals(result.get("projectionTakenAsSuccess")),
                result.get("reason") == null ? status : String.valueOf(result.get("reason")));
    }
}
