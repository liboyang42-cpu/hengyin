package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * G3 external-games operations: operator takedown and restore, report resolution, operator node binding, and the
 * public availability read the app uses to explain a closed game honestly.
 *
 * <p>Takedown delists by clearing {@code external_game.approved_submission_id}; every existing read (catalogue,
 * signed download, node binding view, {@code sessions/start}) already refuses a game without an approved version.
 * Sessions still STARTED for the game are expired so no result can be counted after the takedown. The re-approval
 * guard in {@link ExternalGameReviewService} keeps a new approval from silently re-listing a taken-down game.
 *
 * <p>Every write appends a row to {@code external_game_moderation_event} with the operator's sys_user id, in the same
 * transaction as the change.
 */
@Service
public class ExternalGameOpsService {
    static final Set<String> REASON_CODES = ExternalGameReviewService.REPORT_REASONS;
    static final Set<String> RESOLUTIONS = Set.of("ACTIONED", "DISMISSED");
    static final int MAX_TEXT = 500;
    private static final String GAME_ID = "[a-z0-9][a-z0-9._-]{2,63}";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LongSupplier clock;

    @Autowired
    public ExternalGameOpsService(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, System::currentTimeMillis);
    }

    ExternalGameOpsService(JdbcTemplate jdbc, PlatformTransactionManager transactions, LongSupplier clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    // ================= takedown =================

    public Map<String, Object> takedown(Long adminId, String gameId, Map<String, Object> body) {
        long admin = requireAdmin(adminId);
        String gid = requireGameId(gameId);
        Map<String, Object> p = body == null ? Map.of() : body;
        String reasonCode = enumOf(p.get("reasonCode"), REASON_CODES, "reasonCode");
        String reason = requiredText(p.get("reason"), "reason");
        boolean resolveOpen = !Boolean.FALSE.equals(p.get("resolveOpenReports"));
        return tx.execute(status -> {
            Map<String, Object> game = lockGame(gid);
            List<Map<String, Object>> current = jdbc.queryForList(
                    "SELECT status FROM external_game_takedown WHERE game_id = ? FOR UPDATE", gid);
            if (!current.isEmpty() && "ACTIVE".equals(current.get(0).get("status"))) {
                throw AppGapException.conflict("ALREADY_TAKEN_DOWN", "该游戏已下架");
            }
            Long submissionId = longOrNull(game.get("approved_submission_id"));
            Timestamp now = now();
            if (current.isEmpty()) {
                jdbc.update("INSERT INTO external_game_takedown(game_id, status, submission_id, reason_code, reason, taken_down_by,"
                        + " taken_down_time, update_time) VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?)",
                        gid, submissionId, reasonCode, reason, admin, now, now);
            } else {
                jdbc.update("UPDATE external_game_takedown SET status = 'ACTIVE', submission_id = ?, reason_code = ?, reason = ?,"
                        + " taken_down_by = ?, taken_down_time = ?, lifted_by = NULL, lifted_time = NULL, lift_reason = NULL,"
                        + " update_time = ? WHERE game_id = ?", submissionId, reasonCode, reason, admin, now, now, gid);
            }
            jdbc.update("UPDATE external_game SET approved_submission_id = NULL, update_time = ? WHERE game_id = ?", now, gid);
            int expired = jdbc.update("UPDATE external_game_play_session SET status = 'EXPIRED' WHERE game_id = ? AND status = 'STARTED'", gid);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reasonCode", reasonCode);
            detail.put("submissionId", submissionId);
            detail.put("expiredSessions", expired);
            audit(gid, "TAKEDOWN", "GAME", gid, admin, reason, detail, now);
            int resolved = 0;
            if (resolveOpen) {
                List<Map<String, Object>> open = jdbc.queryForList(
                        "SELECT id FROM external_game_report WHERE game_id = ? AND status = 'OPEN' FOR UPDATE", gid);
                for (Map<String, Object> r : open) {
                    resolveLocked(admin, ((Number) r.get("id")).longValue(), gid, "ACTIONED", "下架：" + reason, now);
                    resolved++;
                }
            }
            Map<String, Object> out = takedownView(gid);
            out.put("expiredSessions", expired);
            out.put("resolvedReports", resolved);
            return out;
        });
    }

    public Map<String, Object> restore(Long adminId, String gameId, Map<String, Object> body) {
        long admin = requireAdmin(adminId);
        String gid = requireGameId(gameId);
        String reason = requiredText(body == null ? null : body.get("reason"), "reason");
        return tx.execute(status -> {
            lockGame(gid);
            List<Map<String, Object>> current = jdbc.queryForList(
                    "SELECT status FROM external_game_takedown WHERE game_id = ? FOR UPDATE", gid);
            if (current.isEmpty() || !"ACTIVE".equals(current.get(0).get("status"))) {
                throw AppGapException.conflict("NOT_TAKEN_DOWN", "该游戏未处于下架状态");
            }
            // The newest approved version is the one to put back (a version approved during the takedown counts).
            Long latest = jdbc.queryForObject(
                    "SELECT MAX(id) FROM external_game_submission WHERE game_id = ? AND status = 'APPROVED'", Long.class, gid);
            if (latest == null) {
                throw AppGapException.conflict("NOTHING_TO_RESTORE", "该游戏没有审核通过的版本，无法恢复上架");
            }
            Timestamp now = now();
            jdbc.update("UPDATE external_game SET approved_submission_id = ?, update_time = ? WHERE game_id = ?", latest, now, gid);
            jdbc.update("UPDATE external_game_takedown SET status = 'LIFTED', lifted_by = ?, lifted_time = ?, lift_reason = ?,"
                    + " update_time = ? WHERE game_id = ?", admin, now, reason, now, gid);
            audit(gid, "RESTORE", "GAME", gid, admin, reason, Map.of("submissionId", latest), now);
            return takedownView(gid);
        });
    }

    /** True when an operator has taken the game down. Fails open (false) only while the table does not exist yet. */
    public boolean activeTakedown(String gameId) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM external_game_takedown WHERE game_id = ? AND status = 'ACTIVE'", Integer.class, gameId);
            return n != null && n > 0;
        } catch (DataAccessException missingTableBeforeMigration) {
            return false;
        }
    }

    // ================= reports =================

    public Map<String, Object> resolveReport(Long adminId, long reportId, Map<String, Object> body) {
        long admin = requireAdmin(adminId);
        if (reportId <= 0) throw AppGapException.badRequest("reportId");
        Map<String, Object> p = body == null ? Map.of() : body;
        String resolution = enumOf(p.get("resolution"), RESOLUTIONS, "resolution");
        String note = optionalText(p.get("note"), "note");
        if ("DISMISSED".equals(resolution) && note == null) throw AppGapException.badRequest("note");
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, game_id, status FROM external_game_report WHERE id = ? FOR UPDATE", reportId);
            if (rows.isEmpty()) throw AppGapException.notFound("REPORT_NOT_FOUND");
            if (!"OPEN".equals(rows.get(0).get("status"))) {
                throw AppGapException.conflict("REPORT_ALREADY_RESOLVED", "该举报已处理");
            }
            resolveLocked(admin, reportId, (String) rows.get(0).get("game_id"), resolution, note, now());
            return reportResolutionView(reportId);
        });
    }

    private void resolveLocked(long admin, long reportId, String gameId, String resolution, String note, Timestamp now) {
        int n = jdbc.update("UPDATE external_game_report SET status = 'RESOLVED' WHERE id = ? AND status = 'OPEN'", reportId);
        if (n != 1) throw AppGapException.conflict("REPORT_ALREADY_RESOLVED", "该举报已处理");
        jdbc.update("INSERT INTO external_game_report_resolution(report_id, resolution, note, resolver_id, resolved_time)"
                + " VALUES (?,?,?,?,?)", reportId, resolution, note, admin, now);
        audit(gameId, "ACTIONED".equals(resolution) ? "REPORT_ACTIONED" : "REPORT_DISMISSED", "REPORT",
                String.valueOf(reportId), admin, note, null, now);
    }

    /**
     * Adds the resolution and the game's takedown state to the rows of the existing report list
     * ({@link ExternalGameReviewService#adminReports}).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enrichReports(Map<String, Object> page) {
        Object rows = page == null ? null : page.get("rows");
        if (!(rows instanceof List<?> list)) return page;
        Map<String, Boolean> takedownByGame = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = (Map<String, Object>) raw;
            Object id = row.get("reportId");
            if (id instanceof Number num) {
                List<Map<String, Object>> res = safeList(
                        "SELECT resolution, note, resolver_id, resolved_time FROM external_game_report_resolution WHERE report_id = ?",
                        num.longValue());
                if (!res.isEmpty()) {
                    row.put("resolution", res.get(0).get("resolution"));
                    row.put("resolutionNote", res.get(0).get("note"));
                    row.put("resolverId", longOrNull(res.get(0).get("resolver_id")));
                    row.put("resolvedAt", iso(res.get(0).get("resolved_time")));
                }
            }
            Object g = row.get("gameId");
            if (g instanceof String gid) {
                row.put("takenDown", takedownByGame.computeIfAbsent(gid, this::activeTakedown));
            }
        }
        return page;
    }

    // ================= operator node binding =================

    public Map<String, Object> adminBind(Long adminId, long nodeId, Map<String, Object> body) {
        long admin = requireAdmin(adminId);
        if (nodeId <= 0) throw AppGapException.badRequest("nodeId");
        Map<String, Object> p = body == null ? Map.of() : body;
        String gid = requireGameId(p.get("gameId") instanceof String s ? s : null);
        int minScore = intIn(p.get("minScore"), 0, 0, 1_000_000, "minScore");
        boolean requirePass = !Boolean.FALSE.equals(p.get("requirePass"));
        String reason = optionalText(p.get("reason"), "reason");
        List<Map<String, Object>> nodes = jdbc.queryForList("SELECT id, topic_id, name FROM cms_topic_node WHERE id = ?", nodeId);
        if (nodes.isEmpty() || nodes.get(0).get("topic_id") == null) throw AppGapException.notFound("NODE_NOT_FOUND");
        long topicId = ((Number) nodes.get(0).get("topic_id")).longValue();
        return tx.execute(status -> {
            Map<String, Object> game = lockGame(gid);
            if (game.get("approved_submission_id") == null) {
                throw AppGapException.conflict(activeTakedown(gid) ? "GAME_TAKEN_DOWN" : "GAME_NOT_APPROVED",
                        "只能绑定已上架的外部游戏");
            }
            long owner = ((Number) game.get("owner_member_id")).longValue();
            Timestamp now = now();
            int updated = jdbc.update("UPDATE external_game_node_binding SET topic_id = ?, game_id = ?, owner_member_id = ?, min_score = ?,"
                    + " require_pass = ?, enabled = 1, update_time = ? WHERE node_id = ?",
                    topicId, gid, owner, minScore, requirePass ? 1 : 0, now, nodeId);
            if (updated == 0) {
                jdbc.update("INSERT INTO external_game_node_binding(node_id, topic_id, game_id, owner_member_id, min_score, require_pass,"
                        + " enabled, create_time, update_time) VALUES (?,?,?,?,?,?,1,?,?)",
                        nodeId, topicId, gid, owner, minScore, requirePass ? 1 : 0, now, now);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("topicId", topicId);
            detail.put("minScore", minScore);
            detail.put("requirePass", requirePass);
            audit(gid, "NODE_BIND", "NODE", String.valueOf(nodeId), admin, reason, detail, now);
            return bindingRow(nodeId);
        });
    }

    public Map<String, Object> adminUnbind(Long adminId, long nodeId, Map<String, Object> body) {
        long admin = requireAdmin(adminId);
        if (nodeId <= 0) throw AppGapException.badRequest("nodeId");
        String reason = optionalText(body == null ? null : body.get("reason"), "reason");
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT game_id, enabled FROM external_game_node_binding WHERE node_id = ? FOR UPDATE", nodeId);
            if (rows.isEmpty() || toInt(rows.get(0).get("enabled")) != 1) {
                throw AppGapException.conflict("NODE_NOT_BOUND", "该节点没有绑定外部游戏");
            }
            Timestamp now = now();
            jdbc.update("UPDATE external_game_node_binding SET enabled = 0, update_time = ? WHERE node_id = ?", now, nodeId);
            audit((String) rows.get(0).get("game_id"), "NODE_UNBIND", "NODE", String.valueOf(nodeId), admin, reason, null, now);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("nodeId", nodeId);
            out.put("bound", false);
            return out;
        });
    }

    public Map<String, Object> listBindings(String gameId, Long topicId, Integer pageNum, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE b.enabled = 1");
        if (gameId != null && !gameId.isBlank()) { where.append(" AND b.game_id = ?"); args.add(requireGameId(gameId.trim())); }
        if (topicId != null && topicId > 0) { where.append(" AND b.topic_id = ?"); args.add(topicId); }
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM external_game_node_binding b" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(BINDING_SELECT + where + " ORDER BY b.update_time DESC, b.node_id DESC LIMIT ? OFFSET ?",
                args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(bindingView(r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("rows", items);
        return out;
    }

    public Map<String, Object> moderationEvents(String gameId, Integer pageNum, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (gameId != null && !gameId.isBlank()) { where = " WHERE game_id = ?"; args.add(requireGameId(gameId.trim())); }
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM external_game_moderation_event" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM external_game_moderation_event" + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("eventId", ((Number) r.get("id")).longValue());
            v.put("gameId", r.get("game_id"));
            v.put("action", r.get("action"));
            v.put("targetType", r.get("target_type"));
            v.put("targetId", r.get("target_id"));
            v.put("actorId", longOrNull(r.get("actor_id")));
            v.put("reason", r.get("reason"));
            v.put("detail", r.get("detail_json"));
            v.put("createdAt", iso(r.get("create_time")));
            items.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("rows", items);
        return out;
    }

    // ================= public availability =================

    /**
     * What the app needs to explain a closed game: LISTED, TAKEN_DOWN (operator; the internal reason is never
     * shown), NOT_LISTED (no approved version) or UNKNOWN_GAME. Never throws for a well-formed id.
     */
    public Map<String, Object> availability(String gameId) {
        String gid = requireGameId(gameId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gid);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT approved_submission_id FROM external_game WHERE game_id = ?", gid);
        String state;
        if (rows.isEmpty()) state = "UNKNOWN_GAME";
        else if (rows.get(0).get("approved_submission_id") != null) state = "LISTED";
        else if (activeTakedown(gid)) state = "TAKEN_DOWN";
        else state = "NOT_LISTED";
        out.put("state", state);
        out.put("available", "LISTED".equals(state));
        return out;
    }

    // ================= views and helpers =================

    private static final String BINDING_SELECT = "SELECT b.node_id, b.topic_id, b.game_id, b.owner_member_id, b.min_score,"
            + " b.require_pass, b.update_time, n.name AS node_name, s.name AS game_name, s.version,"
            + " g.approved_submission_id FROM external_game_node_binding b"
            + " LEFT JOIN cms_topic_node n ON n.id = b.node_id"
            + " LEFT JOIN external_game g ON g.game_id = b.game_id"
            + " LEFT JOIN external_game_submission s ON s.id = g.approved_submission_id";

    private Map<String, Object> bindingRow(long nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(BINDING_SELECT + " WHERE b.node_id = ?", nodeId);
        if (rows.isEmpty()) throw AppGapException.notFound("NODE_NOT_BOUND");
        return bindingView(rows.get(0));
    }

    private Map<String, Object> bindingView(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("nodeId", ((Number) r.get("node_id")).longValue());
        v.put("topicId", ((Number) r.get("topic_id")).longValue());
        v.put("nodeName", r.get("node_name"));
        v.put("gameId", r.get("game_id"));
        v.put("gameName", r.get("game_name") == null ? r.get("game_id") : r.get("game_name"));
        v.put("version", r.get("version"));
        v.put("ownerMemberId", longOrNull(r.get("owner_member_id")));
        v.put("minScore", toInt(r.get("min_score")));
        v.put("requirePass", toInt(r.get("require_pass")) == 1);
        v.put("listed", r.get("approved_submission_id") != null);
        v.put("updatedAt", iso(r.get("update_time")));
        return v;
    }

    private Map<String, Object> takedownView(String gid) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM external_game_takedown WHERE game_id = ?", gid);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("gameId", gid);
        v.put("status", r.get("status"));
        v.put("submissionId", longOrNull(r.get("submission_id")));
        v.put("reasonCode", r.get("reason_code"));
        v.put("reason", r.get("reason"));
        v.put("takenDownBy", longOrNull(r.get("taken_down_by")));
        v.put("takenDownAt", iso(r.get("taken_down_time")));
        v.put("liftedBy", longOrNull(r.get("lifted_by")));
        v.put("liftedAt", iso(r.get("lifted_time")));
        v.put("liftReason", r.get("lift_reason"));
        return v;
    }

    private Map<String, Object> reportResolutionView(long reportId) {
        Map<String, Object> r = jdbc.queryForMap("SELECT r.id, r.game_id, r.status, x.resolution, x.note, x.resolver_id, x.resolved_time"
                + " FROM external_game_report r JOIN external_game_report_resolution x ON x.report_id = r.id WHERE r.id = ?", reportId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("reportId", reportId);
        v.put("gameId", r.get("game_id"));
        v.put("status", r.get("status"));
        v.put("resolution", r.get("resolution"));
        v.put("resolutionNote", r.get("note"));
        v.put("resolverId", longOrNull(r.get("resolver_id")));
        v.put("resolvedAt", iso(r.get("resolved_time")));
        return v;
    }

    private Map<String, Object> lockGame(String gid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT game_id, owner_member_id, approved_submission_id FROM external_game WHERE game_id = ? FOR UPDATE", gid);
        if (rows.isEmpty()) throw AppGapException.notFound("GAME_NOT_FOUND");
        return rows.get(0);
    }

    private void audit(String gameId, String action, String targetType, String targetId, long actor, String reason,
                       Map<String, Object> detail, Timestamp at) {
        String json = null;
        if (detail != null && !detail.isEmpty()) {
            try {
                json = JSON.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                json = null;
            }
        }
        jdbc.update("INSERT INTO external_game_moderation_event(game_id, action, target_type, target_id, actor_id, reason,"
                + " detail_json, create_time) VALUES (?,?,?,?,?,?,?,?)", gameId, action, targetType, targetId, actor, reason, json, at);
    }

    private List<Map<String, Object>> safeList(String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args);
        } catch (DataAccessException missingTableBeforeMigration) {
            return List.of();
        }
    }

    private Timestamp now() { return new Timestamp(clock.getAsLong()); }

    private static long requireAdmin(Long adminId) {
        if (adminId == null || adminId <= 0) throw AppGapException.unauthorized();
        return adminId;
    }

    private static String requireGameId(String gameId) {
        if (gameId == null || !gameId.matches(GAME_ID)) throw AppGapException.badRequest("gameId");
        return gameId;
    }

    private static String enumOf(Object raw, Set<String> allowed, String field) {
        if (raw instanceof String s && allowed.contains(s.trim())) return s.trim();
        throw AppGapException.badRequest(field);
    }

    private static String requiredText(Object raw, String field) {
        String t = optionalText(raw, field);
        if (t == null) throw AppGapException.badRequest(field);
        return t;
    }

    private static String optionalText(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) throw AppGapException.badRequest(field);
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.codePointCount(0, t.length()) > MAX_TEXT) throw AppGapException.badRequest(field);
        return t;
    }

    private static int intIn(Object raw, int def, int min, int max, String field) {
        if (raw == null) return def;
        long v;
        if (raw instanceof Number n) v = n.longValue();
        else if (raw instanceof String s && s.trim().matches("-?[0-9]{1,9}")) v = Long.parseLong(s.trim());
        else throw AppGapException.badRequest(field);
        if (v < min || v > max) throw AppGapException.badRequest(field);
        return (int) v;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        return o == null ? 0 : Integer.parseInt(String.valueOf(o));
    }

    private static Long longOrNull(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static String iso(Object o) {
        if (o instanceof Timestamp t) return ISO.format(t.toInstant());
        if (o instanceof java.util.Date d) return ISO.format(Instant.ofEpochMilli(d.getTime()));
        if (o instanceof java.time.LocalDateTime l) return ISO.format(l.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        return o == null ? null : String.valueOf(o);
    }
}
