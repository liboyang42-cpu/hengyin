package com.chengyinhub.common.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Bounded diagnostic IDs only. Never an authorization, claim, retry or execution source. */
public final class LogCorrelation {
    public static final String REQUEST_ID = "requestId";
    public static final String JOB_RUN_ID = "jobRunId";
    private static final Logger log = LoggerFactory.getLogger(LogCorrelation.class);
    private static final String[] KEYS = {REQUEST_ID, "commandId", "entityId", "receiptId",
            "jobId", JOB_RUN_ID, "event", "fence"};

    private LogCorrelation() { }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Invalid/control-bearing IDs are omitted, never truncated or echoed. No payload/header copying. */
    public static String safe(String key, String value) {
        if (value == null) return "-";
        String pattern;
        switch (key) {
            case REQUEST_ID: pattern = "[A-Za-z0-9_-]{8,64}"; break;
            case JOB_RUN_ID: pattern = "[0-9a-f]{32}"; break;
            case "jobId": case "fence": pattern = "[0-9]{1,20}"; break;
            case "event": pattern = "[a-z][a-z0-9_.-]{0,79}"; break;
            case "commandId": case "entityId": case "receiptId": pattern = "[A-Za-z0-9_-]{1,96}"; break;
            default: return "-";
        }
        return value.matches(pattern) ? value : "-";
    }

    public static String value(String key) {
        return safe(key, MDC.get(key));
    }

    public static Map<String, String> snapshot() {
        Map<String, String> values = new HashMap<>();
        for (String key : KEYS) {
            String value = value(key);
            if (!"-".equals(value)) values.put(key, value);
        }
        return Map.copyOf(values);
    }

    /** Replace while running, restore the exact calling thread state even on nested failure. */
    public static Scope open(Map<String, String> context) {
        Map<String, String> clean = new HashMap<>();
        for (String key : KEYS) {
            String value = safe(key, context.get(key));
            if (!"-".equals(value)) clean.put(key, value);
        }
        return new Scope(clean);
    }

    public static Scope with(Map<String, String> ids) {
        Map<String, String> context = new HashMap<>(snapshot());
        context.putAll(ids);
        return open(context);
    }

    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Map<String, String> captured = snapshot();
        return () -> {
            try (Scope ignored = open(captured)) {
                try { task.run(); }
                catch (RuntimeException | Error failure) {
                    // ScheduledFuture/afterExecute observes failure after this scope has closed.
                    // Record correlation here; never log the exception message/body/cause.
                    try (Scope failureContext = open(captured)) {
                        log.error("event=async.failure context={} errorType={}", captured, failure.getClass().getSimpleName());
                    }
                    throw failure;
                }
            }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous = MDC.getCopyOfContextMap();
        private boolean closed;

        private Scope(Map<String, String> context) {
            MDC.clear();
            if (!context.isEmpty()) MDC.setContextMap(context);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            MDC.clear();
            if (previous != null && !previous.isEmpty()) MDC.setContextMap(previous);
        }
    }
}
