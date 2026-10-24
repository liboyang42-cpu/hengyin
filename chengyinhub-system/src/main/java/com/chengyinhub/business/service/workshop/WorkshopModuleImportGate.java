package com.chengyinhub.business.service.workshop;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * C10 exclusive import / validate / preview gate.
 *
 * <p>Old qmod workshop candidate binaries and H5/SDK original texts are not on
 * this remote ({@link #QMOD_ORIGINAL_PRESENT} is false). Missing originals are
 * {@code WAITING_SOURCE}, not a rewrite and not a fake success.
 *
 * <p>Does not execute JS, does not write Runtime, and does not own the public
 * author-publish registry.
 */
public final class WorkshopModuleImportGate {

    public static final boolean QMOD_ORIGINAL_PRESENT = false;
    public static final boolean H5_SDK_ORIGINAL_PRESENT = false;

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "answer", "answers", "spoilerAnswer",
            "hiddenCoordinate", "hiddenCoordinates",
            "hiddenLat", "hiddenLng", "hiddenLatitude", "hiddenLongitude");

    public enum Outcome {
        PREVIEW,
        REJECTED,
        WAITING_SOURCE,
        UNKNOWN
    }

    /**
     * Public catalog facts only. Answers and hidden coordinates must not ship.
     *
     * <p>{@code originalRequestId} stays {@code null} when the caller omitted it;
     * it is never coerced to an empty string.
     */
    public record Manifest(
            String originalRequestId,
            String moduleId,
            String version,
            String contentHash,
            int schemaVersion,
            String format,
            Map<String, String> catalog) {

        public Manifest {
            catalog = catalog == null ? Map.of() : Map.copyOf(catalog);
        }
    }

    public record Decision(
            Outcome outcome,
            String originalRequestId,
            boolean executedJs,
            boolean wroteRuntime,
            String reason) {

        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }

        public boolean grantsImport() {
            return outcome == Outcome.PREVIEW;
        }
    }

    private final WorkshopOriginalRequestBinder binder;

    public WorkshopModuleImportGate() {
        this(new WorkshopOriginalRequestBinder());
    }

    public WorkshopModuleImportGate(WorkshopOriginalRequestBinder binder) {
        this.binder = binder == null ? new WorkshopOriginalRequestBinder() : binder;
    }

    public WorkshopOriginalRequestBinder binder() {
        return binder;
    }

    public Decision importPreview(Manifest manifest) {
        Map<String, Object> result = binder.oneShot(
                manifest == null ? null : manifest.originalRequestId(),
                "importPreview",
                () -> liveImport(manifest));
        return toDecision(result);
    }

    public Decision recover(String originalRequestId) {
        return toDecision(binder.recover(originalRequestId));
    }

    private Map<String, Object> liveImport(Manifest manifest) {
        if (manifest == null) {
            return WorkshopOriginalRequestBinder.unknown(null, "importPreview", "manifest missing");
        }
        try {
            inspect(manifest.catalog());
        } catch (IllegalArgumentException ex) {
            return rejected(manifest.originalRequestId(), ex.getMessage());
        }
        if (manifest.originalRequestId() == null || manifest.originalRequestId().isBlank()) {
            return WorkshopOriginalRequestBinder.unknown(null, "importPreview", "originalRequestId missing");
        }
        if (!QMOD_ORIGINAL_PRESENT || looksLikeUnsignedQmod(manifest.format())) {
            return waiting(manifest.originalRequestId(), "qmod.original");
        }
        if (manifest.schemaVersion() != 1) {
            return rejected(manifest.originalRequestId(), "schemaVersion must be 1");
        }
        if (!idOk(manifest.moduleId()) || !idOk(manifest.version())) {
            return rejected(manifest.originalRequestId(), "moduleId or version invalid");
        }
        if (manifest.contentHash() == null || !HASH.matcher(manifest.contentHash()).matches()) {
            return rejected(manifest.originalRequestId(), "contentHash must be sha256 hex");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("originalRequestId", manifest.originalRequestId());
        ok.put("status", WorkshopOriginalRequestBinder.APPLIED);
        ok.put("outcome", Outcome.PREVIEW.name());
        ok.put("executedJs", Boolean.FALSE);
        ok.put("wroteRuntime", Boolean.FALSE);
        ok.put("previewOnly", Boolean.TRUE);
        ok.put("reason", "metadata preview");
        return ok;
    }

    /** Reject answers / hidden coordinates before any bytes are retained. */
    public static void inspect(Map<String, String> catalog) {
        if (catalog == null) {
            return;
        }
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new IllegalArgumentException("answers or hidden coordinates forbidden");
            }
            String value = entry.getValue() == null ? "" : entry.getValue().toLowerCase(Locale.ROOT);
            if (value.contains("hidden-coord") || value.contains("\"answer\"")) {
                throw new IllegalArgumentException("answers or hidden coordinates forbidden");
            }
        }
    }

    static boolean looksLikeUnsignedQmod(String format) {
        if (format == null || format.isBlank()) {
            return true;
        }
        String lower = format.toLowerCase(Locale.ROOT);
        return lower.contains("qmod") || lower.contains("module-pack");
    }

    private static boolean idOk(String value) {
        return value != null && !value.isBlank() && value.length() <= 64
                && value.equals(value.trim());
    }

    private static Map<String, Object> waiting(String requestId, String capability) {
        Map<String, Object> out = WorkshopOriginalRequestBinder.unknown(requestId, "importPreview",
                WorkshopOriginalRequestBinder.WAITING_SOURCE + ":" + capability);
        out.put("status", WorkshopOriginalRequestBinder.WAITING_SOURCE);
        out.put("outcome", Outcome.WAITING_SOURCE.name());
        out.put("executedJs", Boolean.FALSE);
        out.put("wroteRuntime", Boolean.FALSE);
        return out;
    }

    private static Map<String, Object> rejected(String requestId, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", WorkshopOriginalRequestBinder.REJECTED);
        out.put("outcome", Outcome.REJECTED.name());
        out.put("executedJs", Boolean.FALSE);
        out.put("wroteRuntime", Boolean.FALSE);
        out.put("reason", reason);
        out.put("secondEffect", Boolean.FALSE);
        return out;
    }

    private static Decision toDecision(Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        Outcome outcome = Outcome.UNKNOWN;
        if (WorkshopOriginalRequestBinder.WAITING_SOURCE.equals(status)
                || Outcome.WAITING_SOURCE.name().equals(String.valueOf(result.get("outcome")))) {
            outcome = Outcome.WAITING_SOURCE;
        } else if (WorkshopOriginalRequestBinder.REJECTED.equals(status)
                || Outcome.REJECTED.name().equals(String.valueOf(result.get("outcome")))) {
            outcome = Outcome.REJECTED;
        } else if (WorkshopOriginalRequestBinder.APPLIED.equals(status)
                || WorkshopOriginalRequestBinder.REPLAY.equals(status)
                || Outcome.PREVIEW.name().equals(String.valueOf(result.get("outcome")))) {
            outcome = Outcome.PREVIEW;
        } else if (WorkshopOriginalRequestBinder.UNKNOWN.equals(status)) {
            outcome = Outcome.UNKNOWN;
        }
        Object requestId = result.get("originalRequestId");
        return new Decision(
                outcome,
                requestId instanceof String ? (String) requestId : null,
                Boolean.TRUE.equals(result.get("executedJs")),
                Boolean.TRUE.equals(result.get("wroteRuntime")),
                result.get("reason") == null ? status : String.valueOf(result.get("reason")));
    }
}
