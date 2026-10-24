package com.chengyinhub.business.service.support.rpg.adapter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 正常检定成长调用者。只把同一 member、run、node、check、request 交给原
 * {@code ITopicRouteRuntimeService.rpgV2Check} / {@code rpgV2CheckReceipt}。
 * 不写资源账，也不把进程内 adapter 当成原 writer。
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

    public JsonNode check(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId,
            Long runId, String requestId, Integer expectedVersion, String operation,
            boolean useInspiration, String attemptId) {
        if (!identity(memberId, runId, nodeId, checkId, requestId) || expectedVersion == null
                || !operationAllowed(operation)) {
            return null;
        }
        JsonNode body = port.check(memberId, topicId, activityId, nodeId, checkId, runId, requestId,
                expectedVersion, operation, useInspiration, attemptId);
        if (!sameRequest(body, runId, requestId)) {
            return null;
        }
        return body;
    }

    public JsonNode receipt(Long memberId, Long runId, Long nodeId, String checkId, String requestId) {
        if (!identity(memberId, runId, nodeId, checkId, requestId)) {
            return null;
        }
        JsonNode body = port.receipt(memberId, runId, nodeId, checkId, requestId);
        if (body == null || !sameRequest(body, runId, requestId)) {
            return null;
        }
        String status = body.path("status").asText("");
        if (!"COMMITTED".equals(status) && !"UNKNOWN".equals(status)) {
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

    private static boolean sameRequest(JsonNode body, Long runId, String requestId) {
        return body != null && !body.isNull()
                && body.path("runId").isNumber() && body.path("runId").longValue() == runId.longValue()
                && requestId.equals(body.path("requestId").asText());
    }
}
