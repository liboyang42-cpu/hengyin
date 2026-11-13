package com.chengyinhub.web.service.appgaps;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.appgaps.AppGapSupport;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiTopicController;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /api/topic/edit-requests/{submit,read,resume-post-processing}: a durable request wrapper around the four
 * original project writers. Submit decodes the exact payload bytes the app hashed, runs the same writer the
 * original path would (same validation, same author checks inside it), and stores the receipt in the same
 * transaction, so COMMITTED always means the original write committed and a retry of the same request replays
 * the receipt instead of writing twice. No post-processing is tracked here (postProcessing: UNAVAILABLE).
 */
@Service
public class TopicEditRequestService {
    static final String PROTOCOL = "original-project-edit-request-v1";
    static final Set<String> PATHS = Set.of("api/topic/create", "api/topic/update", "api/topic/v2/create", "api/topic/v2/update");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ApiTopicController topics;
    private final Validator validator;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TopicEditRequestService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ApiTopicController topics, Validator validator) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.topics = topics;
        this.validator = validator;
    }

    public Map<String, Object> submit(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        String requestId = AppGapSupport.str(body, "requestId"), path = AppGapSupport.str(body, "originalPath"), sha = AppGapSupport.str(body, "payloadSHA256");
        Object chunks = body == null ? null : body.get("payloadBase64Chunks");
        if (!AppGapSupport.exactKeys(body, Set.of("protocolVersion", "requestId", "originalPath", "payloadSHA256", "payloadBase64Chunks"))
                || !PROTOCOL.equals(body.get("protocolVersion")) || !AppGapSupport.lowerUuid(requestId) || !PATHS.contains(path) || !AppGapSupport.hash(sha)
                || !(chunks instanceof List) || ((List<?>) chunks).isEmpty() || ((List<?>) chunks).size() > 86) {
            throw new AppGapException(400, "EDIT_REQUEST_INVALID", "编辑请求无效");
        }
        StringBuilder joined = new StringBuilder();
        for (Object chunk : (List<?>) chunks) {
            if (!(chunk instanceof String) || ((String) chunk).isEmpty() || ((String) chunk).length() > 32_768) throw new AppGapException(400, "EDIT_REQUEST_INVALID", "编辑请求无效");
            joined.append((String) chunk);
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(joined.toString());
        } catch (IllegalArgumentException e) {
            throw new AppGapException(400, "EDIT_REQUEST_INVALID", "编辑请求无效");
        }
        if (payload.length == 0 || payload.length > 2_097_152 || !sha.equals(AppGapSupport.sha256(payload))) {
            throw new AppGapException(400, "EDIT_REQUEST_DIGEST_MISMATCH", "编辑内容校验失败");
        }
        Map<String, Object> stored = stored(memberId, requestId);
        if (stored != null) return replay(stored, path, sha);
        AjaxResult[] outcome = new AjaxResult[1];
        try {
            tx.executeWithoutResult(status -> {
                outcome[0] = invoke(path, payload);
                if (Integer.valueOf(200).equals(outcome[0].get("code"))) {
                    record(memberId, requestId, path, sha, "COMMITTED", outcome[0].get("data"), null);
                } else {
                    // A refused original write leaves nothing behind; only the refusal is recorded below.
                    status.setRollbackOnly();
                }
            });
            if (!Integer.valueOf(200).equals(outcome[0].get("code"))) {
                String message = String.valueOf(outcome[0].get("msg"));
                record(memberId, requestId, path, sha, "REJECTED", null, message.substring(0, Math.min(255, message.length())));
            }
        } catch (DuplicateKeyException concurrent) {
            Map<String, Object> winner = stored(memberId, requestId);
            if (winner == null) throw concurrent;
            return replay(winner, path, sha);
        }
        return stored(memberId, requestId);
    }

    private AjaxResult invoke(String path, byte[] payload) {
        Object dto;
        try {
            Class<?> type = path.endsWith("create") ? TopicCreateDTO.class : TopicUpdateDTO.class;
            dto = mapper.readValue(payload, type);
        } catch (java.io.IOException e) {
            return AjaxResult.error("编辑内容格式无效");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        if (!violations.isEmpty()) return AjaxResult.error(violations.iterator().next().getMessage());
        switch (path) {
            case "api/topic/create": return topics.createTopic((TopicCreateDTO) dto);
            case "api/topic/v2/create": return topics.createTopicV2((TopicCreateDTO) dto);
            case "api/topic/update": return topics.updateTopic((TopicUpdateDTO) dto);
            default: return topics.updateTopicV2((TopicUpdateDTO) dto);
        }
    }

    private Map<String, Object> record(long memberId, String requestId, String path, String sha, String state, Object result, String message) {
        jdbc.update("INSERT INTO topic_edit_request(member_id, request_id, original_path, payload_sha256, state, original_result, message) VALUES (?,?,?,?,?,?,?)",
                memberId, requestId, path, sha, state, result == null ? null : JSON.toJSONString(result), message);
        return Map.of();
    }

    public Map<String, Object> read(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        String requestId = AppGapSupport.str(body, "requestId"), path = AppGapSupport.str(body, "originalPath"), sha = AppGapSupport.str(body, "payloadSHA256");
        if (!AppGapSupport.exactKeys(body, Set.of("protocolVersion", "requestId", "originalPath", "payloadSHA256", "scope"))
                || !PROTOCOL.equals(body.get("protocolVersion")) || !AppGapSupport.lowerUuid(requestId) || !PATHS.contains(path) || !AppGapSupport.hash(sha)) {
            throw new AppGapException(400, "EDIT_REQUEST_INVALID", "编辑请求无效");
        }
        Map<String, Object> stored = stored(memberId, requestId);
        if (stored == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protocolVersion", PROTOCOL);
            out.put("requestId", requestId);
            out.put("originalPath", path);
            out.put("payloadSHA256", sha);
            out.put("state", "NOT_FOUND");
            return out;
        }
        return replay(stored, path, sha);
    }

    private Map<String, Object> stored(long memberId, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM topic_edit_request WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL);
        out.put("requestId", row.get("request_id"));
        out.put("originalPath", row.get("original_path"));
        out.put("payloadSHA256", row.get("payload_sha256"));
        out.put("state", row.get("state"));
        out.put("postProcessing", "UNAVAILABLE");
        out.put("originalResult", row.get("original_result") == null ? null : JSON.parse(String.valueOf(row.get("original_result"))));
        out.put("message", row.get("message"));
        return out;
    }

    private static Map<String, Object> replay(Map<String, Object> stored, String path, String sha) {
        if (!path.equals(stored.get("originalPath")) || !sha.equals(stored.get("payloadSHA256"))) {
            throw new AppGapException(409, "EDIT_REQUEST_REUSED", "请求编号已用于其他编辑");
        }
        return stored;
    }
}
