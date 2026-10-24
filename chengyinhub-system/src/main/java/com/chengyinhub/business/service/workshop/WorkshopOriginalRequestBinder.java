package com.chengyinhub.business.service.workshop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * C10 exclusive one-shot binder. Same original requestId never applies a second
 * effect; UNKNOWN occupies the id and must not mint a retry.
 *
 * <p>Not a second public install registry or payment ledger. Dot owns those
 * writers; this map is isolation-only until a hunk wires it.
 */
public final class WorkshopOriginalRequestBinder {

    public static final String APPLIED = "APPLIED";
    public static final String REPLAY = "REPLAY";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String WAITING_SOURCE = "WAITING_SOURCE";
    public static final String REJECTED = "REJECTED";
    public static final String PENDING = "PENDING";

    public static final class Entry {
        private final String requestId;
        private final String operation;
        private String status = PENDING;
        private Map<String, Object> result;

        Entry(String requestId, String operation) {
            this.requestId = requestId;
            this.operation = operation;
        }

        public String requestId() { return requestId; }
        public String operation() { return operation; }
        public String status() { return status; }
        public Map<String, Object> result() {
            return result == null ? null : Collections.unmodifiableMap(result);
        }
    }

    private final Map<String, Entry> byRequestId = new LinkedHashMap<>();

    public Entry existing(String requestId) {
        return requestId == null ? null : byRequestId.get(requestId);
    }

    /**
     * Bind then run {@code body} once. A second call with the same id returns
     * {@link #REPLAY} of the original result and does not invoke {@code body}.
     *
     * @return a mutable result map that always contains {@code originalRequestId}
     *         (null when the caller omitted it) and {@code status}
     */
    public Map<String, Object> oneShot(String requestId, String operation, Supplier<Map<String, Object>> body) {
        if (requestId == null || requestId.isBlank()) {
            return unknown(null, operation, "originalRequestId missing");
        }
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(body, "body");
        Entry existing = byRequestId.get(requestId);
        if (existing != null) {
            Map<String, Object> replay = copy(existing.result);
            replay.put("originalRequestId", existing.requestId);
            replay.put("status", existing.status == null ? REPLAY : replayStatus(existing.status));
            replay.put("replay", Boolean.TRUE);
            replay.put("secondEffect", Boolean.FALSE);
            return replay;
        }
        Entry entry = new Entry(requestId, operation);
        byRequestId.put(requestId, entry);
        Map<String, Object> live;
        try {
            live = body.get();
        } catch (RuntimeException ex) {
            entry.status = UNKNOWN;
            entry.result = unknown(requestId, operation, ex.getMessage());
            throw ex;
        }
        if (live == null) {
            entry.status = UNKNOWN;
            entry.result = unknown(requestId, operation, "body returned null");
            return copy(entry.result);
        }
        Map<String, Object> stored = copy(live);
        stored.put("originalRequestId", requestId);
        if (!stored.containsKey("status") || stored.get("status") == null) {
            stored.put("status", APPLIED);
        }
        stored.put("secondEffect", Boolean.FALSE);
        entry.status = String.valueOf(stored.get("status"));
        entry.result = stored;
        return copy(stored);
    }

    /**
     * Record UNKNOWN for an already-bound id. Does not clear original intent
     * and does not allocate a new requestId. A never-seen id must not occupy
     * the ledger, or a later unique writer becomes a replay of minted UNKNOWN.
     * APPLIED / WAITING_SOURCE / REJECTED original outcomes are not clobbered.
     */
    public Map<String, Object> markUnknown(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            return unknown(null, null, "originalRequestId missing");
        }
        Entry entry = byRequestId.get(requestId);
        if (entry == null) {
            return unknown(requestId, null, reason == null ? UNKNOWN : reason);
        }
        if (APPLIED.equals(entry.status) || WAITING_SOURCE.equals(entry.status) || REJECTED.equals(entry.status)) {
            Map<String, Object> kept = copy(entry.result);
            kept.put("originalRequestId", entry.requestId);
            kept.put("status", entry.status);
            kept.put("clearedOriginalIntent", Boolean.FALSE);
            kept.put("mintedNewRequestId", Boolean.FALSE);
            kept.put("projectionTakenAsSuccess", Boolean.FALSE);
            return kept;
        }
        Map<String, Object> stored = entry.result == null ? new LinkedHashMap<>() : copy(entry.result);
        stored.put("originalRequestId", requestId);
        stored.put("status", UNKNOWN);
        stored.put("reason", reason == null ? UNKNOWN : reason);
        stored.put("clearedOriginalIntent", Boolean.FALSE);
        stored.put("mintedNewRequestId", Boolean.FALSE);
        stored.put("projectionTakenAsSuccess", Boolean.FALSE);
        entry.status = UNKNOWN;
        entry.result = stored;
        return copy(stored);
    }

    public Map<String, Object> recover(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return unknown(null, null, "originalRequestId missing");
        }
        Entry entry = existing(requestId);
        if (entry == null) {
            return unknown(requestId, null, "no original receipt");
        }
        Map<String, Object> out = copy(entry.result);
        out.put("originalRequestId", entry.requestId);
        out.put("status", entry.status);
        out.put("clearedOriginalIntent", Boolean.FALSE);
        out.put("mintedNewRequestId", Boolean.FALSE);
        if (UNKNOWN.equals(entry.status)) {
            out.put("projectionTakenAsSuccess", Boolean.FALSE);
        }
        return out;
    }

    static Map<String, Object> unknown(String requestId, String operation, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("originalRequestId", requestId);
        out.put("status", UNKNOWN);
        if (operation != null) {
            out.put("operation", operation);
        }
        out.put("reason", reason);
        out.put("clearedOriginalIntent", Boolean.FALSE);
        out.put("mintedNewRequestId", Boolean.FALSE);
        out.put("projectionTakenAsSuccess", Boolean.FALSE);
        out.put("secondEffect", Boolean.FALSE);
        return out;
    }

    private static String replayStatus(String original) {
        if (UNKNOWN.equals(original) || WAITING_SOURCE.equals(original) || REJECTED.equals(original)) {
            return original;
        }
        return REPLAY;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
