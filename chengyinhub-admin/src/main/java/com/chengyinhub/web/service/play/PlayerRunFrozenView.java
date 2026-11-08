package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本局冻结快照的只读视图（P7/P8）。只读 {@code topic_route_session.config_snapshot_json}，
 * 不读当前发布的节点表来补历史；缺键即 UNKNOWN，不回落实时内容。
 */
final class PlayerRunFrozenView {

    static final String NODE_ANSWERS = "nodeAnswers";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlayerRunFrozenView() {
    }

    static JsonNode snapshot(TopicRouteSession session) {
        if (session == null || session.getConfigSnapshotJson() == null
                || session.getConfigSnapshotJson().isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = JSON.readTree(session.getConfigSnapshotJson());
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception unusableSnapshot) {
            return null;
        }
    }

    /** nodeId -> 冻结站点；没有 routeStations 时返回空表（老局）。 */
    static Map<Long, JsonNode> stations(JsonNode snapshot) {
        Map<Long, JsonNode> byId = new LinkedHashMap<>();
        if (snapshot == null) return byId;
        for (JsonNode station : snapshot.path("routeStations")) {
            if (station == null || !station.path("nodeId").canConvertToLong()) continue;
            byId.put(station.path("nodeId").asLong(), station);
        }
        return byId;
    }

    /** 本局冻结节点数：优先 routeStations；没有时数 nodeAnswers 的键；都没有返回 0。 */
    static int nodeCount(JsonNode snapshot) {
        int stationCount = stations(snapshot).size();
        if (stationCount > 0) return stationCount;
        if (snapshot == null) return 0;
        JsonNode answers = snapshot.path(NODE_ANSWERS);
        if (!answers.isObject()) return 0;
        int count = 0;
        java.util.Iterator<String> names = answers.fieldNames();
        while (names.hasNext()) {
            names.next();
            count++;
        }
        return count;
    }

    /** 站点名优先取发布冻结的 routeStations.nodeName，其次 nodeAnswers.nodePresentation.name。 */
    static String nodeName(JsonNode snapshot, JsonNode station, Long nodeId) {
        if (station != null && station.path("nodeName").isTextual()) return station.path("nodeName").asText();
        JsonNode presentation = presentation(snapshot, nodeId);
        return presentation != null && presentation.path("name").isTextual() ? presentation.path("name").asText() : null;
    }

    /** 碎片正文只取本局冻结的 nodePresentation.fragmentText；缺键返回 null，不读当前节点。 */
    static String fragmentText(JsonNode snapshot, Long nodeId) {
        JsonNode presentation = presentation(snapshot, nodeId);
        return presentation != null && presentation.path("fragmentText").isTextual()
                ? presentation.path("fragmentText").asText() : null;
    }

    private static JsonNode presentation(JsonNode snapshot, Long nodeId) {
        if (snapshot == null || nodeId == null) return null;
        JsonNode answer = snapshot.path(NODE_ANSWERS).path(String.valueOf(nodeId));
        return answer.isObject() ? answer.path("nodePresentation") : null;
    }
}
