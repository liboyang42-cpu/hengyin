package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recover the original typed quest result only; no current state, action execution or legacy proof upgrade. */
@Service
public class ThemeQuestResultService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TopicRouteSessionMapper routeMapper;

    public ThemeQuestResultService(TopicRouteSessionMapper routeMapper) {
        this.routeMapper = routeMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> query(Long memberId, Long topicId, Long activityId, Long sessionId,
                                     Integer expectedVersion, String actionId, String kind, JsonNode payload) {
        ThemeQuestActionContract.context(memberId, topicId, activityId, sessionId, expectedVersion, actionId);
        Map<String, Object> arguments = ThemeQuestActionContract.arguments(kind, payload);
        TopicRouteSession session = routeMapper.selectAccessibleById(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId()) || !sessionId.equals(session.getId())
                || !topicId.equals(session.getTopicId()) || !activityId.equals(session.getActivityId())) throw scope();
        String operation = kind, contentId = (String) arguments.get("contentId"), choiceKey = (String) arguments.get("choiceKey");
        JsonNode discardRewards = (JsonNode) arguments.get("discardRewards"), weRun = null;
        String previewVersion = (String) arguments.get("previewVersion");
        Integer costOption = (Integer) arguments.get("costOption");
        // Original gameplay action identity, evaluated with weRun absent for all three quest operations.
        String key="gameplay:"+actionId,receiptId="gameplay-receipt:"+shortHash(key+":"+operation+":"+contentId+":"+choiceKey+":"+discardRewards+":"+previewVersion+(costOption==null?"":":"+costOption)+(weRun==null?"":":"+shortHash(weRun.toString())));
        TopicRouteAction action = routeMapper.selectActionByAction(sessionId, memberId, key);
        if (action == null) return unknown();
        if (!memberId.equals(action.getMemberId()) || !sessionId.equals(action.getSessionId())) throw scope();
        if (!key.equals(action.getActionId()) || !operation.equals(action.getOutcomeCode())
                || !Objects.equals(receiptId, action.getReceiptId()) || action.getResponseJson() == null
                || action.getResponseJson().isBlank()) return unknown();
        try {
            JsonNode original = ThemeQuestActionContract.readStored(action.getResponseJson());
            if (original == null || !original.isObject() || !equalLong(original.get("sessionId"), sessionId)
                    || !equalLong(original.get("topicId"), topicId) || !equalLong(original.get("activityId"), activityId)
                    || !original.path("version").isIntegralNumber() || !original.path("version").canConvertToInt()
                    || original.path("version").intValue() != expectedVersion + 1 || !original.path("status").isTextual()
                    || !Set.of("ACTIVE", "COMPLETED", "ENDED").contains(original.path("status").asText())
                    || !original.path("contents").isArray() || !original.path("pending").isObject()
                    || !original.path("settlement").isObject() || original.path("settlement").isEmpty()) return unknown();
            String proof = ThemeQuestActionContract.fingerprint(session.getMemberId(), session.getTopicId(), session.getActivityId(),
                    session.getId(), expectedVersion, actionId, kind, payload);
            if (!original.path(ThemeQuestActionContract.PROOF_FIELD).isTextual()
                    || !proof.equals(original.path(ThemeQuestActionContract.PROOF_FIELD).asText())) return unknown();
            ((ObjectNode) original).remove(ThemeQuestActionContract.PROOF_FIELD);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "FOUND");
            result.put("matchLevel", "TYPED_QUEST_REQUEST_V1");
            result.put("receipt", JSON.convertValue(original, new TypeReference<Map<String, Object>>() { }));
            return result;
        } catch (Exception corrupt) {
            return unknown();
        }
    }

    private static boolean equalLong(JsonNode value, long expected) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == expected;
    }

    private static Map<String, Object> unknown() { return Map.of("status", "UNKNOWN"); }

    private static ServiceException scope() { return new ServiceException("本局不存在或无权查看", 403); }

    /** Stable digest copied from the original gameplay action identity: first 16 SHA-256 bytes, hex encoded. */
    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", hash[i] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("选择动作摘要算法不可用", impossible);
        }
    }
}
