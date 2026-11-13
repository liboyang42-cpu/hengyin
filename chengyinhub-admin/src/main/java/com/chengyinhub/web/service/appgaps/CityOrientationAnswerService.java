package com.chengyinhub.web.service.appgaps;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.appgaps.AppGapSupport;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayBasicCommandApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/play/city-orientation/answer and GET /receipt: an original-request receipt around the existing
 * answer path (PlayBasicCommandApplication.answer) for one run session the caller owns. The receipt row and the
 * answer commit in one transaction, so a stored receipt means the answer was applied or refused exactly once.
 * The requestId doubles as the route action id, so the play engine itself also refuses a second application.
 */
@Service
public class CityOrientationAnswerService {
    static final String CONTRACT = "CITY_ORIENTATION_ANSWER_V1";
    private static final Set<String> KEYS = Set.of("sessionId", "nodeId", "requestId", "expectedVersion", "answer");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TopicRouteSessionMapper sessions;
    private final PlayBasicCommandApplication play;

    public CityOrientationAnswerService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                        TopicRouteSessionMapper sessions, PlayBasicCommandApplication play) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.sessions = sessions;
        this.play = play;
    }

    /** Same digest the app computes: sha256("session:node:expectedVersion:utf8Length:answer"). */
    static String payloadHash(long sessionId, long nodeId, long expectedVersion, String answer) {
        String raw = sessionId + ":" + nodeId + ":" + expectedVersion + ":" + answer.getBytes(StandardCharsets.UTF_8).length + ":" + answer;
        return AppGapSupport.sha256(raw);
    }

    public Map<String, Object> answer(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        Long sessionId = AppGapSupport.integer(body, "sessionId"), nodeId = AppGapSupport.integer(body, "nodeId");
        Long expected = AppGapSupport.integer(body, "expectedVersion");
        String requestId = AppGapSupport.str(body, "requestId"), answer = AppGapSupport.str(body, "answer");
        if (!AppGapSupport.exactKeys(body, KEYS) || sessionId == null || sessionId <= 0 || nodeId == null || nodeId <= 0
                || expected == null || expected < 0 || !AppGapSupport.anyUuid(requestId) || answer == null
                || answer.trim().isEmpty() || answer.length() > 300) {
            throw new AppGapException(400, "CITY_ORIENTATION_INVALID", "作答请求无效");
        }
        String hash = payloadHash(sessionId, nodeId, expected, answer);
        Map<String, Object> stored = stored(memberId, requestId);
        if (stored != null) return replay(stored, hash);
        try {
            return tx.execute(status -> apply(memberId, sessionId, nodeId, expected, requestId, answer, hash));
        } catch (DuplicateKeyException concurrent) {
            Map<String, Object> winner = stored(memberId, requestId);
            if (winner == null) throw concurrent;
            return replay(winner, hash);
        }
    }

    private Map<String, Object> apply(long memberId, long sessionId, long nodeId, long expected, String requestId, String answer, String hash) {
        TopicRouteSession session = sessions.selectAccessibleById(sessionId, memberId);
        if (session == null) throw new AppGapException(404, "CITY_ORIENTATION_SESSION_NOT_FOUND", "没有这条运行记录");
        Map<String, Object> response;
        if (session.getVersion() == null || session.getVersion().longValue() != expected) {
            response = new LinkedHashMap<>();
            response.put("code", 409);
            response.put("msg", "运行版本已变化，请刷新后重试");
            response.put("data", null);
        } else {
            AjaxResult result = play.answer(memberId, String.valueOf(session.getActivityId()), String.valueOf(nodeId), answer,
                    String.valueOf(session.getTopicId()), requestId, (int) expected, sessionId, null, null);
            response = JSON.parseObject(JSON.toJSONString(result));
        }
        TopicRouteSession after = sessions.selectAccessibleById(sessionId, memberId);
        boolean applied = Integer.valueOf(200).equals(response.get("code"));
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("contract", CONTRACT);
        receipt.put("sessionId", sessionId);
        receipt.put("nodeId", nodeId);
        receipt.put("requestId", requestId);
        receipt.put("payloadHash", hash);
        receipt.put("configVersion", after == null || after.getConfigVersion() == null ? null : after.getConfigVersion());
        receipt.put("revision", after == null || after.getVersion() == null ? expected : after.getVersion().longValue());
        receipt.put("outcome", applied ? "APPLIED" : "FAILED");
        receipt.put("response", response);
        jdbc.update("INSERT INTO city_orientation_answer(member_id, request_id, session_id, node_id, payload_hash, receipt_json) VALUES (?,?,?,?,?,?)",
                memberId, requestId, sessionId, nodeId, hash, JSON.toJSONString(receipt));
        return receipt;
    }

    private static Map<String, Object> replay(Map<String, Object> stored, String hash) {
        if (!hash.equals(stored.get("payloadHash"))) throw new AppGapException(409, "CITY_ORIENTATION_REQUEST_REUSED", "请求编号已用于其他作答");
        return stored;
    }

    private Map<String, Object> stored(long memberId, String requestId) {
        List<String> rows = jdbc.queryForList("SELECT receipt_json FROM city_orientation_answer WHERE member_id = ? AND request_id = ?",
                String.class, memberId, requestId);
        return rows.isEmpty() ? null : JSON.parseObject(rows.get(0));
    }

    /** GET receipt: the stored reply, or PENDING when this member never stored one for that request. */
    public Map<String, Object> receipt(Long memberId, String sessionId, String nodeId, String requestId) {
        AppGapSupport.requireMember(memberId);
        if (sessionId == null || !sessionId.matches("[1-9][0-9]{0,15}") || nodeId == null || !nodeId.matches("[1-9][0-9]{0,15}")
                || !AppGapSupport.anyUuid(requestId)) {
            throw new AppGapException(400, "CITY_ORIENTATION_INVALID", "回执请求无效");
        }
        Map<String, Object> stored = stored(memberId, requestId);
        if (stored != null) {
            if (!String.valueOf(stored.get("sessionId")).equals(sessionId) || !String.valueOf(stored.get("nodeId")).equals(nodeId)) {
                throw new AppGapException(409, "CITY_ORIENTATION_REQUEST_REUSED", "请求编号属于其他作答");
            }
            return stored;
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("contract", CONTRACT);
        pending.put("sessionId", Long.parseLong(sessionId));
        pending.put("nodeId", Long.parseLong(nodeId));
        pending.put("requestId", requestId);
        pending.put("outcome", "PENDING");
        return pending;
    }
}
