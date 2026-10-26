package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the existing action ledger only; never invokes runtime state, expiry or minting. */
@Service
public class AdvancedActionReceiptReader {
    private final GameRuntimeSessionMapper sessions;
    private final PlayAttemptLogMapper attempts;
    private final ObjectMapper json;

    public AdvancedActionReceiptReader(GameRuntimeSessionMapper sessions, PlayAttemptLogMapper attempts, ObjectMapper json) {
        this.sessions = sessions;
        this.attempts = attempts;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> read(Long memberId, Long sessionId, String requestId) {
        if (memberId == null || memberId <= 0 || sessionId == null || sessionId <= 0
                || requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new ServiceException("原请求参数有误", 400);
        }
        if (sessions.selectAccessibleById(sessionId, memberId) == null) {
            throw new ServiceException("玩法会话不存在或无权访问", 404);
        }
        // Historical writers used three namespaces. Read them without re-evaluating current config.
        JsonNode found = null;
        for (String key : List.of(requestId, themeKey(sessionId, requestId), themeKey(sessionId, memberId + ":" + requestId))) {
            PlayAttemptLog row = attempts.selectByIdempotencyKey(key);
            if (row == null || !memberId.equals(row.getMemberId()) || !Integer.valueOf(8).equals(row.getAttemptType())) continue;
            JsonNode view;
            try { view = json.readTree(row.getResultJson()); }
            catch (java.io.IOException e) { throw new ServiceException("原请求回执不可用", 409); }
            if (!sessionId.toString().equals(view.path("sessionId").asText())
                    && !sessionId.toString().equals(view.path("retrySourceSessionId").asText())) continue;
            if (found != null && !found.equals(view)) throw new ServiceException("原请求回执存在冲突", 409);
            found = view;
        }
        if (found == null) throw new ServiceException("尚未查到原请求回执，结果仍待确认", 404);
        return Map.of("sessionId", sessionId.toString(), "idempotencyKey", requestId,
                "view", CheckReceiptProjection.response(found));
    }

    private static String themeKey(Long sessionId, String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requestId.getBytes(StandardCharsets.UTF_8));
            return "theme:" + sessionId + ":" + java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
