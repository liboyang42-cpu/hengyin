package com.chengyinhub.business.shopgames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * W7 x-shop-game-runner: the runtime session of an issued shop play, played outside any journey (the place page or a
 * City Monopoly landing).
 *
 * <ul>
 *   <li>One session per play ({@code cy_shop_game_session.play_id} unique), owned by the play's member, on the play's
 *       template. It starts only while the play is {@code ISSUED} and not expired, and it ends no later than the
 *       play expires.</li>
 *   <li>Sessions live in their own table, never in {@code game_runtime_session}: journey sessions are keyed by
 *       activity, topic and node, and neither kind can see the other.</li>
 *   <li>The advanced runtime's section engine judges every action
 *       ({@link IAdvancedGameRuntimeService#templateAct}); this class only stores, locks and replays.</li>
 *   <li>The result is still recorded by {@link ShopGamePlayService#finish}, which judges from this session.</li>
 * </ul>
 */
@Service
public class ShopGameSessionService {

    static final String SESSION_COLUMNS = "SELECT id, play_id, member_id, template_id, config_snapshot_json, state_json,"
            + " status, score, version, started_at, deadline_at, completed_at, create_time, update_time FROM cy_shop_game_session";
    private static final Logger log = LoggerFactory.getLogger(ShopGameSessionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ShopGamePlayService plays;
    @Autowired(required = false)
    private IAdvancedGameRuntimeService runtime;

    public ShopGameSessionService(JdbcTemplate jdbc, ShopGamePlayService plays) {
        this.jdbc = jdbc;
        this.plays = plays;
    }

    /** For tests. */
    ShopGameSessionService(JdbcTemplate jdbc, ShopGamePlayService plays, IAdvancedGameRuntimeService runtime) {
        this(jdbc, plays);
        this.runtime = runtime;
    }

    /** Starts the session of the player's own issued play, or resumes it. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long memberId, Long playId) {
        requireMember(memberId);
        if (playId == null || playId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        if (!plays.playOpen()) throw new MerchantGapException(409, "SHOP_GAME_NOT_OPEN", "店铺小游戏还没开放");
        requireRuntime();
        List<Map<String, Object>> rows = jdbc.queryForList(ShopGamePlayService.PLAY_COLUMNS
                + " WHERE id = ? AND member_id = ? FOR UPDATE", playId, memberId);
        if (rows.isEmpty()) throw new MerchantGapException(404, "PLAY_NOT_FOUND", "没有找到这局小游戏");
        Map<String, Object> play = rows.get(0);
        Map<String, Object> existing = sessionOfPlay(memberId, playId);
        if (existing != null) return view(expireIfDue(existing), memberId);
        if (!"ISSUED".equals(String.valueOf(play.get("status")))) {
            throw new MerchantGapException(409, "PLAY_ALREADY_FINISHED", "这局小游戏已经结束了");
        }
        Date playExpires = Date.from(ShopGamePlayService.instant(play.get("expires_at")));
        if (new Date().after(playExpires)) throw new MerchantGapException(409, "PLAY_EXPIRED", "这局小游戏已过期，明天再来");
        Object tpl = play.get("template_id");
        if (!(tpl instanceof Number t)) throw notPlayable();
        long templateId = t.longValue();
        List<String> configs = jdbc.queryForList("SELECT advanced_config_json FROM cms_member_template WHERE id = ? AND del_flag = 0",
                String.class, templateId);
        if (configs.isEmpty() || configs.get(0) == null || configs.get(0).isBlank()) throw notPlayable();
        GameRuntimeSession session;
        try {
            session = runtime.templateStart(memberId, templateId, configs.get(0));
        } catch (IllegalArgumentException | IllegalStateException unsupported) {
            log.info("shop game template {} cannot run outside a journey: {}", templateId, unsupported.getMessage());
            throw notPlayable();
        }
        // The session ends no later than the play: a finish after the play's expiry is answered EXPIRED anyway.
        if (session.getDeadlineAt() == null || session.getDeadlineAt().after(playExpires)) session.setDeadlineAt(playExpires);
        try {
            jdbc.update("INSERT INTO cy_shop_game_session (play_id, member_id, template_id, config_snapshot_json, state_json,"
                    + " status, score, version, started_at, deadline_at, completed_at, create_time, update_time)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, ?, ?)",
                    playId, memberId, templateId, session.getConfigSnapshotJson(), session.getStateJson(),
                    session.getStatus(), session.getScore(), ts(session.getStartedAt()), ts(session.getDeadlineAt()),
                    ts(session.getCreateTime()), ts(session.getUpdateTime()));
        } catch (DuplicateKeyException raced) {
            // uk play_id: a parallel start won; answer with that session.
        }
        Map<String, Object> stored = sessionOfPlay(memberId, playId);
        if (stored == null) throw new IllegalStateException("shop game session not readable after insert");
        return view(stored, memberId);
    }

    /** The player's own session, read-only (an overdue running session is closed as expired first). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> state(Long memberId, Long sessionId) {
        requireMember(memberId);
        requireRuntime();
        return view(expireIfDue(ownSession(memberId, sessionId, false)), memberId);
    }

    /**
     * One action, same body as the journey runtime: {@code {sessionId, version, idempotencyKey, action, payload}}.
     * The same key with the same request replays the stored answer; with a different request it is refused.
     */
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> act(Long memberId, Map<String, Object> body) {
        requireMember(memberId);
        if (body == null) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        requireRuntime();
        if (!plays.playOpen()) throw new MerchantGapException(409, "SHOP_GAME_NOT_OPEN", "店铺小游戏还没开放");
        Long sessionId = body.get("sessionId") instanceof Number n ? n.longValue() : null;
        Integer version = body.get("version") instanceof Number v && v.doubleValue() == Math.rint(v.doubleValue()) ? v.intValue() : null;
        String key = body.get("idempotencyKey") == null ? "" : body.get("idempotencyKey").toString().trim();
        String action = body.get("action") == null ? "" : body.get("action").toString().trim();
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (version == null || version < 0 || key.isEmpty() || key.length() > 64 || action.isEmpty() || action.length() > 40) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        Map<String, Object> row = ownSession(memberId, sessionId, true);
        String fingerprint = fingerprint(version, action, payload);
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT fingerprint, response_json FROM cy_shop_game_session_action"
                + " WHERE session_id = ? AND idempotency_key = ?", sessionId, key);
        if (!replay.isEmpty()) {
            if (!fingerprint.equals(String.valueOf(replay.get(0).get("fingerprint")))) {
                throw new MerchantGapException(409, "IDEMPOTENCY_CONFLICT", "这一步已经用别的内容提交过了，请刷新后再试");
            }
            return readMap(String.valueOf(replay.get(0).get("response_json")));
        }
        int current = ((Number) row.get("version")).intValue();
        if (current != version) {
            throw new MerchantGapException(409, "VERSION_CONFLICT", "状态已更新，请刷新后重试", view(row, memberId));
        }
        GameRuntimeSession session = toSession(row);
        try {
            session = runtime.templateAct(session, memberId, action, payload);
        } catch (IllegalArgumentException refused) {
            throw new MerchantGapException(400, "INVALID_ACTION", refused.getMessage());
        } catch (IllegalStateException closed) {
            throw new MerchantGapException(409, "SESSION_CLOSED", closed.getMessage(), view(row, memberId));
        }
        int n = jdbc.update("UPDATE cy_shop_game_session SET state_json = ?, status = ?, score = ?, completed_at = ?,"
                + " version = version + 1, update_time = ? WHERE id = ? AND version = ?",
                session.getStateJson(), session.getStatus(), session.getScore(), ts(session.getCompletedAt()),
                ts(session.getUpdateTime()), sessionId, version);
        if (n != 1) throw new MerchantGapException(409, "VERSION_CONFLICT", "状态已更新，请刷新后重试");
        session.setVersion(version + 1);
        Map<String, Object> out = withPlay(runtime.templateView(session, memberId), row);
        jdbc.update("INSERT INTO cy_shop_game_session_action (session_id, idempotency_key, fingerprint, response_json, create_time)"
                + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(3))", sessionId, key, fingerprint, writeJson(out));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> sessionOfPlay(long memberId, long playId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SESSION_COLUMNS + " WHERE play_id = ? AND member_id = ?", playId, memberId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> ownSession(Long memberId, Long sessionId, boolean lock) {
        if (sessionId == null || sessionId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        List<Map<String, Object>> rows = jdbc.queryForList(SESSION_COLUMNS + " WHERE id = ? AND member_id = ?"
                + (lock ? " FOR UPDATE" : ""), sessionId, memberId);
        if (rows.isEmpty()) throw new MerchantGapException(404, "SESSION_NOT_FOUND", "没有找到这局小游戏");
        return rows.get(0);
    }

    /** Closes a running session whose deadline passed (status 3). Returns the row as it now stands. */
    private Map<String, Object> expireIfDue(Map<String, Object> row) {
        if (!(row.get("status") instanceof Number s) || s.intValue() != GameRuntimeSession.STATUS_RUNNING) return row;
        Object deadline = row.get("deadline_at");
        if (deadline == null || !new Date().after(Date.from(ShopGamePlayService.instant(deadline)))) return row;
        jdbc.update("UPDATE cy_shop_game_session SET status = ?, completed_at = CURRENT_TIMESTAMP(3), version = version + 1,"
                + " update_time = CURRENT_TIMESTAMP(3) WHERE id = ? AND status = ?",
                GameRuntimeSession.STATUS_EXPIRED, row.get("id"), GameRuntimeSession.STATUS_RUNNING);
        Map<String, Object> after = new LinkedHashMap<>(row);
        after.put("status", GameRuntimeSession.STATUS_EXPIRED);
        after.put("version", ((Number) row.get("version")).intValue() + 1);
        after.put("completed_at", new Timestamp(System.currentTimeMillis()));
        return after;
    }

    private Map<String, Object> view(Map<String, Object> row, Long memberId) {
        return withPlay(runtime.templateView(toSession(row), memberId), row);
    }

    private static Map<String, Object> withPlay(Map<String, Object> view, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(view);
        out.put("playId", ((Number) row.get("play_id")).longValue());
        return out;
    }

    static GameRuntimeSession toSession(Map<String, Object> row) {
        GameRuntimeSession s = new GameRuntimeSession();
        s.setId(((Number) row.get("id")).longValue());
        s.setPlayInstanceId("shop:" + ((Number) row.get("play_id")).longValue());
        s.setTemplateId(((Number) row.get("template_id")).longValue());
        s.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        s.setOwnerId(((Number) row.get("member_id")).longValue());
        s.setConfigSnapshotJson(String.valueOf(row.get("config_snapshot_json")));
        s.setStateJson(String.valueOf(row.get("state_json")));
        s.setStatus(((Number) row.get("status")).intValue());
        s.setScore(row.get("score") instanceof Number n ? n.intValue() : 0);
        s.setCompletedUnits(0);
        s.setVersion(((Number) row.get("version")).intValue());
        s.setStartedAt(date(row.get("started_at")));
        s.setDeadlineAt(date(row.get("deadline_at")));
        s.setCompletedAt(date(row.get("completed_at")));
        s.setCreateTime(date(row.get("create_time")));
        s.setUpdateTime(date(row.get("update_time")));
        return s;
    }

    private static Date date(Object v) {
        return v == null ? null : Date.from(ShopGamePlayService.instant(v));
    }

    private static Timestamp ts(Date d) {
        return d == null ? null : new Timestamp(d.getTime());
    }

    private void requireRuntime() {
        if (runtime == null) throw new MerchantGapException(409, "SHOP_GAME_NOT_PLAYABLE", "这家店的小游戏还在准备，换个时间再来");
    }

    private static MerchantGapException notPlayable() {
        return new MerchantGapException(409, "SHOP_GAME_NOT_PLAYABLE", "这家店的小游戏还在准备，换个时间再来");
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
    }

    static String fingerprint(int version, String action, Map<String, Object> payload) {
        try {
            String canonical = version + "|" + action.toUpperCase(java.util.Locale.ROOT) + "|"
                    + JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("fingerprint unavailable", e);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("shop game view not writable", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("stored shop game answer unreadable", e);
        }
    }
}
