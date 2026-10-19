package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Server-owned first-theme identity. A token is a namespace, never an admission credential. */
public final class ThemeFirstIdentity {
    public static final String ORIGIN_KEY = "_serverThemeOrigin";
    private static final String PREFIX = "theme-first:";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ThemeFirstIdentity() { }

    public static String token(Long topicId, Long activityId) {
        if (topicId == null || topicId <= 0 || activityId == null || activityId < 0) throw invalid();
        return PREFIX + topicId + ":" + activityId;
    }

    /** Also catches malformed uses of the reserved namespace; callers must not fall back to default. */
    public static boolean usesNamespace(String instance) {
        return instance != null && instance.startsWith("theme-first");
    }

    /** Called only for a new, server-built session immediately before its one INSERT. */
    public static void stampNew(TopicRouteSession session) {
        if (session == null || session.getId() != null || session.getMemberId() == null
                || session.getMemberId() <= 0) throw invalid();
        String instance = token(session.getTopicId(), session.getActivityId());
        ObjectNode root = snapshot(session);
        if (!root.path("themeConfig").isObject()) throw invalid();
        ObjectNode origin = JSON.createObjectNode();
        origin.put("schemaVersion", 1);
        origin.put("kind", "FIRST");
        origin.put("memberId", session.getMemberId());
        origin.put("topicId", session.getTopicId());
        origin.put("activityId", session.getActivityId());
        origin.put("playInstanceId", instance);
        root.set(ORIGIN_KEY, origin); // Never inherit an author/client-supplied origin.
        session.setPlayInstanceId(instance);
        session.setConfigSnapshotJson(root.toString());
    }

    /** Requires the persisted owner row, not a client token or a live publication. */
    public static boolean isFirst(TopicRouteSession session) {
        if (session == null || !usesNamespace(session.getPlayInstanceId())) return false;
        requireFirst(session, snapshot(session));
        return true;
    }

    public static void requireFirst(TopicRouteSession session, JsonNode root) {
        if (session == null || session.getId() == null || session.getId() <= 0
                || session.getMemberId() == null || session.getMemberId() <= 0
                || !token(session.getTopicId(), session.getActivityId()).equals(session.getPlayInstanceId())
                || root == null || !root.isObject() || !root.path("themeConfig").isObject()) throw invalid();
        JsonNode origin = root.get(ORIGIN_KEY);
        if (origin == null || !origin.isObject() || origin.size() != 6
                || !longEquals(origin.get("schemaVersion"), 1L)
                || !origin.path("kind").isTextual() || !"FIRST".equals(origin.path("kind").textValue())
                || !longEquals(origin.get("memberId"), session.getMemberId())
                || !longEquals(origin.get("topicId"), session.getTopicId())
                || !longEquals(origin.get("activityId"), session.getActivityId())
                || !origin.path("playInstanceId").isTextual()
                || !session.getPlayInstanceId().equals(origin.path("playInstanceId").textValue())) throw invalid();
    }

    private static boolean longEquals(JsonNode value, Long expected) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && expected != null && value.longValue() == expected;
    }

    private static ObjectNode snapshot(TopicRouteSession session) {
        try {
            String bytes = session.getConfigSnapshotJson();
            JsonNode root = bytes == null ? null : JSON.readTree(bytes);
            if (root == null || !root.isObject()) throw invalid();
            return (ObjectNode) root;
        } catch (ServiceException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw invalid();
        }
    }

    private static ServiceException invalid() {
        return new ServiceException("本局首次身份不可读取，请重新进入", 409);
    }
}
