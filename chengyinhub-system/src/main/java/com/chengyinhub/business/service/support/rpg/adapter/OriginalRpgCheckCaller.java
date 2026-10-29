package com.chengyinhub.business.service.support.rpg.adapter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Separates the original POST execute result from the GET receipt shell.
 * A successful execute body has runId, attemptId, checkId and stateVersion.
 * Stage version stays with the original service. This caller does not compare stateVersion.
 * requestId and status belong only to {@code rpgV2CheckReceipt}.
 * This class does not write a resource ledger and does not invent those fields.
 */
public final class OriginalRpgCheckCaller {
    public interface Port {
        JsonNode check(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId,
                Long runId, String requestId, Integer expectedVersion, String operation,
                boolean useInspiration, String attemptId);

        JsonNode receipt(Long memberId, Long runId, Long nodeId, String checkId, String requestId);
    }

    private final Port port;

    public OriginalRpgCheckCaller(Port port) {
        if (port == null) {
            throw new IllegalArgumentException("rpgV2Check");
        }
        this.port = port;
    }

    public static boolean explicitV2(Long runId, String requestId, Integer expectedVersion,
                                     String attemptId, Boolean useInspiration) {
        return runId != null || (requestId != null && !requestId.trim().isEmpty())
                || expectedVersion != null || (attemptId != null && !attemptId.trim().isEmpty())
                || useInspiration != null;
    }

    /** An explicit v2 call that returns null must not fall back to the legacy roll. */
    public static JsonNode acceptOriginalOrLegacy(JsonNode original, boolean explicitV2, JsonNode legacy) {
        if (original != null && !original.isNull()) {
            return original;
        }
        if (explicitV2) {
            return null;
        }
        return legacy;
    }

    public JsonNode check(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId,
            Long runId, String requestId, Integer expectedVersion, String operation,
            boolean useInspiration, String attemptId) {
        if (!identity(memberId, runId, nodeId, checkId, requestId) || expectedVersion == null
                || !operationAllowed(operation)) {
            return null;
        }
        JsonNode body = port.check(memberId, topicId, activityId, nodeId, checkId, runId, requestId,
                expectedVersion, operation, useInspiration, attemptId);
        if (!executeResult(body, memberId, runId, checkId, expectedVersion)) {
            return null;
        }
        return body;
    }

    public JsonNode receipt(Long memberId, Long runId, Long nodeId, String checkId, String requestId) {
        if (!identity(memberId, runId, nodeId, checkId, requestId)) {
            return null;
        }
        JsonNode body = port.receipt(memberId, runId, nodeId, checkId, requestId);
        if (!receiptShell(body, runId, requestId)) {
            return null;
        }
        return body;
    }

    private static boolean identity(Long memberId, Long runId, Long nodeId, String checkId, String requestId) {
        return memberId != null && memberId.longValue() > 0L
                && runId != null && runId.longValue() > 0L
                && nodeId != null && nodeId.longValue() > 0L
                && checkId != null && !checkId.trim().isEmpty()
                && requestId != null && !requestId.trim().isEmpty();
    }

    private static boolean operationAllowed(String operation) {
        return "ROLL_CHECK".equals(operation) || "REROLL_CHECK".equals(operation)
                || "SUBMIT_CHECK".equals(operation) || "RETRY_CHECK".equals(operation);
    }

    static boolean executeResult(JsonNode body, Long memberId, Long runId, String checkId, Integer expectedVersion) {
        if (body == null || body.isNull() || !body.isObject() || body.has("status") || body.has("requestId")) {
            return false;
        }
        if (body.has("memberId") && body.path("memberId").asLong() != memberId.longValue()) {
            return false;
        }
        if (expectedVersion == null || !body.path("stateVersion").isIntegralNumber()) {
            return false;
        }
        return body.path("runId").isNumber() && body.path("runId").longValue() == runId.longValue()
                && checkId.equals(body.path("checkId").asText(""))
                && body.path("attemptId").isTextual() && !body.path("attemptId").asText().isEmpty();
    }

    static boolean receiptShell(JsonNode body, Long runId, String requestId) {
        if (body == null || body.isNull() || !body.isObject()) {
            return false;
        }
        if (!body.path("runId").isNumber() || body.path("runId").longValue() != runId.longValue()
                || !requestId.equals(body.path("requestId").asText())) {
            return false;
        }
        String status = body.path("status").asText("");
        if ("UNKNOWN".equals(status)) {
            return !body.has("receipt");
        }
        return "COMMITTED".equals(status) && body.path("receipt").isObject();
    }
}
