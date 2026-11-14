package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.service.support.CanonicalDigest;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * U08-03 creator maintenance workbench (owner doc: 问题 → 原内容 → 修复草稿 → 复测 → 新版本 → 关闭).
 * <ul>
 *   <li>Feedback read: the U08-02 play_run_feedback rows on topics the caller authors ({@code cms_topic.member_id}).
 *       The reporting player is never returned (no member id, no run id), only the problem and its frozen version.</li>
 *   <li>A ticket links feedback of one topic and records the published version at open time (the original content).</li>
 *   <li>Fix draft, new version and retest are derived from existing facts, not stored: the topic's draft
 *       {@code config_version}, its {@code published_config_version}, and the author's own completed run on a
 *       version newer than the baseline. A new version never changes old runs; they keep their frozen snapshot.</li>
 *   <li>Close needs fix evidence (FIXED: the published version moved past the baseline) or an explicit reason
 *       (NO_CHANGE). Tickets are never deleted, including when the author gives up authorship.</li>
 * </ul>
 * No money: nothing here reads or writes income, settlement or payout.
 */
@Service
public class CreatorMaintenanceService {
    static final Set<String> OPEN_KEYS = Set.of("requestId", "topicId", "title", "note", "feedbackIds");
    static final Set<String> CLOSE_KEYS = Set.of("requestId", "expectedVersion", "resolution", "reason");
    static final int MAX_LIMIT = 50, MAX_FEEDBACK = 50, MAX_TITLE = 80, MAX_TEXT = 500;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CreatorMaintenanceService(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, System::currentTimeMillis);
    }

    CreatorMaintenanceService(JdbcTemplate jdbc, PlatformTransactionManager transactions, LongSupplier clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    // ---- feedback read ----

    public Map<String, Object> feedback(Long viewer, String topicId, String cursor, String limit) {
        long me = requireMember(viewer);
        Long topic = optionalId(topicId, "topicId");
        if (topic != null) requireAuthor(me, topic);
        long before = cursor(cursor);
        int size = limit(limit);
        List<Object> args = new ArrayList<>(List.of(me));
        StringBuilder sql = new StringBuilder("SELECT f.id, f.topic_id AS topicId, t.name AS topicName, f.node_id AS nodeId, n.name AS nodeName,"
                + " f.category, f.content, f.config_version AS configVersion, f.content_revision_id AS contentRevisionId, f.create_time AS createTime,"
                + " l.ticket_id AS ticketId FROM play_run_feedback f JOIN cms_topic t ON t.id = f.topic_id"
                + " LEFT JOIN cms_topic_node n ON n.id = f.node_id LEFT JOIN creator_maintenance_ticket_feedback l ON l.feedback_id = f.id WHERE t.member_id = ? AND t.del_flag <> 2");
        if (topic != null) { sql.append(" AND f.topic_id = ?"); args.add(topic); }
        if (before > 0) { sql.append(" AND f.id < ?"); args.add(before); }
        sql.append(" ORDER BY f.id DESC LIMIT ?");
        args.add(size + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(size, rows.size()))) items.add(feedbackItem(r));
        return page(items, rows.size() > size, "feedbackId");
    }

    private static Map<String, Object> feedbackItem(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feedbackId", longOf(r.get("id")));
        out.put("topicId", longOf(r.get("topicId")));
        out.put("topicName", r.get("topicName"));
        out.put("nodeId", longOrNull(r.get("nodeId")));
        out.put("nodeName", r.get("nodeName"));
        out.put("category", r.get("category"));
        out.put("content", r.get("content"));
        out.put("configVersion", longOrNull(r.get("configVersion")));
        out.put("contentRevisionId", longOrNull(r.get("contentRevisionId")));
        out.put("createdAt", iso(r.get("createTime")));
        out.put("ticketId", longOrNull(r.get("ticketId")));
        return out;
    }

    // ---- tickets ----

    public Map<String, Object> open(Long viewer, Map<String, Object> body) {
        long me = requireMember(viewer);
        if (body == null || !OPEN_KEYS.containsAll(body.keySet()) || !body.keySet().containsAll(Set.of("requestId", "topicId", "title", "feedbackIds")))
            throw AppGapException.badRequest("body");
        String requestId = requestId(body.get("requestId"));
        long topic = requiredId(body.get("topicId"), "topicId");
        String title = text(body.get("title"), MAX_TITLE, true, "title");
        String note = text(body.get("note"), MAX_TEXT, false, "note");
        List<Long> feedbackIds = ids(body.get("feedbackIds"));
        String hash = CanonicalDigest.sha256Hex(topic + "\n" + title + "\n" + (note == null ? "" : note) + "\n" + feedbackIds);
        Map<String, Object> existing = ticketByRequest(me, requestId);
        if (existing != null) return replayOpen(me, existing, hash);
        try {
            Long id = tx.execute(status -> {
                Map<String, Object> t = requireAuthor(me, topic);
                long published = longOf(t.get("publishedConfigVersion"));
                if (published <= 0) throw new AppGapException(409, "TOPIC_NOT_PUBLISHED", "主题还没有发布版本");
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT f.id, f.topic_id AS topicId, l.ticket_id AS ticketId FROM play_run_feedback f"
                        + " LEFT JOIN creator_maintenance_ticket_feedback l ON l.feedback_id = f.id WHERE f.id IN (" + placeholders(feedbackIds.size()) + ") FOR UPDATE",
                        feedbackIds.toArray());
                if (rows.size() != feedbackIds.size()) throw new AppGapException(404, "FEEDBACK_NOT_FOUND", "反馈不存在");
                for (Map<String, Object> r : rows) {
                    if (longOf(r.get("topicId")) != topic) throw new AppGapException(400, "FEEDBACK_TOPIC_MISMATCH", "反馈不属于这个主题");
                    if (r.get("ticketId") != null)
                        throw new AppGapException(409, "FEEDBACK_ALREADY_LINKED", "反馈已在其他维护单里", Map.of("ticketId", longOf(r.get("ticketId"))));
                }
                Long revision = jdbc.queryForList("SELECT id FROM cms_topic_content_revision WHERE topic_id = ? ORDER BY revision_no DESC LIMIT 1",
                        Long.class, topic).stream().findFirst().orElse(null);
                Timestamp now = new Timestamp(clock.getAsLong());
                jdbc.update("INSERT INTO creator_maintenance_ticket(topic_id, author_member_id, title, note, baseline_config_version, baseline_revision_id,"
                                + " status, version, request_id, request_hash, create_time, update_time) VALUES (?,?,?,?,?,?,'OPEN',1,?,?,?,?)",
                        topic, me, title, note, published, revision, requestId, hash, now, now);
                Long ticket = jdbc.queryForObject("SELECT id FROM creator_maintenance_ticket WHERE author_member_id = ? AND request_id = ?", Long.class, me, requestId);
                for (Long f : feedbackIds) jdbc.update("INSERT INTO creator_maintenance_ticket_feedback(ticket_id, feedback_id) VALUES (?, ?)", ticket, f);
                return ticket;
            });
            return view(me, id);
        } catch (DuplicateKeyException race) {
            Map<String, Object> raced = ticketByRequest(me, requestId);
            if (raced != null) return replayOpen(me, raced, hash);
            throw new AppGapException(409, "FEEDBACK_ALREADY_LINKED", "反馈已在其他维护单里");
        }
    }

    /** Recovery read for an open request whose outcome the client did not see. */
    public Map<String, Object> openRequest(Long viewer, String requestId) {
        long me = requireMember(viewer);
        Map<String, Object> existing = ticketByRequest(me, requestId(requestId));
        if (existing == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestId", requestId);
            out.put("state", "NOT_FOUND");
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("state", "APPLIED");
        out.put("ticket", view(me, longOf(existing.get("id"))));
        return out;
    }

    public Map<String, Object> tickets(Long viewer, String topicId, String status, String cursor, String limit) {
        long me = requireMember(viewer);
        Long topic = optionalId(topicId, "topicId");
        if (status != null && !status.isEmpty() && !Set.of("OPEN", "CLOSED").contains(status)) throw AppGapException.badRequest("status");
        long before = cursor(cursor);
        int size = limit(limit);
        List<Object> args = new ArrayList<>(List.of(me));
        StringBuilder sql = new StringBuilder("SELECT id FROM creator_maintenance_ticket WHERE author_member_id = ?");
        if (topic != null) { sql.append(" AND topic_id = ?"); args.add(topic); }
        if (status != null && !status.isEmpty()) { sql.append(" AND status = ?"); args.add(status); }
        if (before > 0) { sql.append(" AND id < ?"); args.add(before); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1);
        List<Long> ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids.subList(0, Math.min(size, ids.size()))) items.add(view(me, id));
        return page(items, ids.size() > size, "ticketId");
    }

    public Map<String, Object> ticket(Long viewer, String ticketId) {
        long me = requireMember(viewer);
        return view(me, requiredId(ticketId, "ticketId"));
    }

    public Map<String, Object> close(Long viewer, String ticketId, Map<String, Object> body) {
        long me = requireMember(viewer);
        long id = requiredId(ticketId, "ticketId");
        if (body == null || !CLOSE_KEYS.containsAll(body.keySet()) || !body.keySet().containsAll(Set.of("requestId", "expectedVersion", "resolution")))
            throw AppGapException.badRequest("body");
        String requestId = requestId(body.get("requestId"));
        long expected = requiredNonNegative(body.get("expectedVersion"), "expectedVersion");
        String resolution = body.get("resolution") instanceof String s ? s : "";
        if (!Set.of("FIXED", "NO_CHANGE").contains(resolution)) throw AppGapException.badRequest("resolution");
        String reason = text(body.get("reason"), MAX_TEXT, false, "reason");
        if ("NO_CHANGE".equals(resolution) && (reason == null || reason.codePointCount(0, reason.length()) < 2))
            throw new AppGapException(400, "CLOSE_REASON_REQUIRED", "不修改就关闭需要写明原因");
        String hash = CanonicalDigest.sha256Hex(id + "\n" + expected + "\n" + resolution + "\n" + (reason == null ? "" : reason));
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT t.id, t.topic_id AS topicId, t.author_member_id AS author, t.status, t.version,"
                    + " t.baseline_config_version AS baseline, t.close_request_id AS closeRequestId, t.close_request_hash AS closeRequestHash"
                    + " FROM creator_maintenance_ticket t WHERE t.id = ? FOR UPDATE", id);
            if (rows.isEmpty() || longOf(rows.get(0).get("author")) != me) throw AppGapException.notFound("TICKET_NOT_FOUND");
            Map<String, Object> t = rows.get(0);
            if ("CLOSED".equals(t.get("status"))) {
                if (requestId.equals(t.get("closeRequestId")) && hash.equals(t.get("closeRequestHash"))) return; // replay
                if (requestId.equals(t.get("closeRequestId"))) throw AppGapException.conflict("REQUEST_ID_CONFLICT", "该请求编号已用于其他内容");
                throw AppGapException.conflict("TICKET_CLOSED", "维护单已关闭");
            }
            if (longOf(t.get("version")) != expected) throw AppGapException.conflict("TICKET_VERSION_CONFLICT", "维护单已变化，请刷新");
            Long fixed = null;
            if ("FIXED".equals(resolution)) {
                Long published = jdbc.queryForList("SELECT published_config_version FROM cms_topic WHERE id = ?", Long.class, longOf(t.get("topicId")))
                        .stream().findFirst().orElse(null);
                if (published == null || published <= longOf(t.get("baseline")))
                    throw new AppGapException(409, "FIX_NOT_PUBLISHED", "修复还没有发布成新版本");
                fixed = published;
            }
            Timestamp now = new Timestamp(clock.getAsLong());
            int changed = jdbc.update("UPDATE creator_maintenance_ticket SET status = 'CLOSED', resolution = ?, fixed_config_version = ?, close_reason = ?,"
                            + " close_request_id = ?, close_request_hash = ?, version = version + 1, update_time = ?, closed_time = ? WHERE id = ? AND version = ?",
                    resolution, fixed, reason, requestId, hash, now, now, id, expected);
            if (changed != 1) throw AppGapException.conflict("TICKET_VERSION_CONFLICT", "维护单已变化，请刷新");
        });
        return view(me, id);
    }

    // ---- projection ----

    Map<String, Object> view(long me, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT t.*, c.name AS topicName, c.config_version AS draftVersion,"
                + " c.published_config_version AS publishedVersion FROM creator_maintenance_ticket t LEFT JOIN cms_topic c ON c.id = t.topic_id"
                + " WHERE t.id = ? AND t.author_member_id = ?", id, me);
        if (rows.isEmpty()) throw AppGapException.notFound("TICKET_NOT_FOUND");
        Map<String, Object> t = rows.get(0);
        long baseline = longOf(t.get("baseline_config_version"));
        Long draft = longOrNull(t.get("draftVersion")), published = longOrNull(t.get("publishedVersion"));
        boolean drafted = draft != null && draft > baseline;
        boolean fixPublished = published != null && published > baseline;
        boolean retested = fixPublished && jdbc.queryForObject("SELECT COUNT(*) FROM topic_route_session WHERE topic_id = ? AND member_id = ?"
                + " AND config_version > ? AND completed_at IS NOT NULL", Long.class, t.get("topic_id"), me, baseline) > 0;
        boolean closed = "CLOSED".equals(t.get("status"));
        String stage = closed ? "CLOSED" : retested ? "RETESTED" : fixPublished ? "FIX_PUBLISHED" : drafted ? "FIX_DRAFTED" : "REPORTED";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", id);
        out.put("topicId", longOf(t.get("topic_id")));
        out.put("topicName", t.get("topicName"));
        out.put("title", t.get("title"));
        out.put("note", t.get("note"));
        out.put("status", t.get("status"));
        out.put("stage", stage);
        out.put("version", longOf(t.get("version")));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("configVersion", baseline);
        base.put("contentRevisionId", longOrNull(t.get("baseline_revision_id")));
        out.put("baseline", base);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("draftConfigVersion", draft);
        current.put("publishedConfigVersion", published);
        out.put("current", current);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fixDrafted", drafted);
        evidence.put("fixPublished", fixPublished);
        evidence.put("retestedByAuthor", retested);
        out.put("evidence", evidence);
        out.put("canCloseFixed", !closed && fixPublished);
        out.put("resolution", t.get("resolution"));
        out.put("fixedConfigVersion", longOrNull(t.get("fixed_config_version")));
        out.put("closeReason", t.get("close_reason"));
        out.put("createdAt", iso(t.get("create_time")));
        out.put("closedAt", iso(t.get("closed_time")));
        List<Map<String, Object>> feedback = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT f.id, f.topic_id AS topicId, NULL AS topicName, f.node_id AS nodeId, n.name AS nodeName,"
                + " f.category, f.content, f.config_version AS configVersion, f.content_revision_id AS contentRevisionId, f.create_time AS createTime,"
                + " l.ticket_id AS ticketId FROM creator_maintenance_ticket_feedback l JOIN play_run_feedback f ON f.id = l.feedback_id"
                + " LEFT JOIN cms_topic_node n ON n.id = f.node_id WHERE l.ticket_id = ? ORDER BY f.id", id)) {
            Map<String, Object> item = feedbackItem(r);
            item.remove("topicName");
            item.remove("ticketId");
            feedback.add(item);
        }
        out.put("feedback", feedback);
        return out;
    }

    // ---- helpers ----

    private Map<String, Object> replayOpen(long me, Map<String, Object> existing, String hash) {
        if (!hash.equals(existing.get("requestHash"))) throw AppGapException.conflict("REQUEST_ID_CONFLICT", "该请求编号已用于其他内容");
        return view(me, longOf(existing.get("id")));
    }

    private Map<String, Object> ticketByRequest(long me, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, request_hash AS requestHash FROM creator_maintenance_ticket"
                + " WHERE author_member_id = ? AND request_id = ?", me, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Author = cms_topic.member_id of a non-deleted topic (the existing topic edit rule). Others get 404, not 403. */
    private Map<String, Object> requireAuthor(long me, long topic) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, member_id AS memberId, published_config_version AS publishedConfigVersion"
                + " FROM cms_topic WHERE id = ? AND del_flag <> 2", topic);
        if (rows.isEmpty() || longOf(rows.get(0).get("memberId")) != me) throw AppGapException.notFound("TOPIC_NOT_FOUND");
        return rows.get(0);
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, boolean more, String idKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("hasMore", more);
        out.put("nextCursor", more && !items.isEmpty() ? String.valueOf(items.get(items.size() - 1).get(idKey)) : null);
        return out;
    }

    private static long requireMember(Long viewer) {
        if (viewer == null || viewer <= 0) throw AppGapException.unauthorized();
        return viewer;
    }

    private static String requestId(Object raw) {
        if (!(raw instanceof String s) || !s.matches("[A-Za-z0-9._:-]{8,64}")) throw AppGapException.badRequest("requestId");
        return s;
    }

    private static long requiredId(Object raw, String field) {
        Long v = optionalId(raw, field);
        if (v == null) throw AppGapException.badRequest(field);
        return v;
    }

    private static Long optionalId(Object raw, String field) {
        if (raw == null || "".equals(raw)) return null;
        if (raw instanceof Number n && n.longValue() > 0 && n.doubleValue() == n.longValue()) return n.longValue();
        if (raw instanceof String s && s.matches("[1-9][0-9]{0,18}")) return Long.parseLong(s);
        throw AppGapException.badRequest(field);
    }

    private static long requiredNonNegative(Object raw, String field) {
        if (raw instanceof Number n && n.longValue() >= 0 && n.doubleValue() == n.longValue()) return n.longValue();
        throw AppGapException.badRequest(field);
    }

    private static List<Long> ids(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_FEEDBACK) throw AppGapException.badRequest("feedbackIds");
        Set<Long> out = new LinkedHashSet<>();
        for (Object o : list) if (!out.add(requiredId(o, "feedbackIds"))) throw AppGapException.badRequest("feedbackIds");
        List<Long> sorted = new ArrayList<>(out);
        sorted.sort(null);
        return sorted;
    }

    private static String text(Object raw, int max, boolean required, String field) {
        if (raw == null) { if (required) throw AppGapException.badRequest(field); return null; }
        if (!(raw instanceof String s)) throw AppGapException.badRequest(field);
        String t = s.trim();
        if (t.isEmpty()) { if (required) throw AppGapException.badRequest(field); return null; }
        if (t.codePointCount(0, t.length()) > max) throw AppGapException.badRequest(field);
        return t;
    }

    private static long cursor(String raw) {
        if (raw == null || raw.isEmpty() || "0".equals(raw)) return 0;
        if (!raw.matches("[1-9][0-9]{0,18}")) throw AppGapException.badRequest("cursor");
        return Long.parseLong(raw);
    }

    private static int limit(String raw) {
        if (raw == null || raw.isEmpty()) return 20;
        if (!raw.matches("[1-9][0-9]?")) throw AppGapException.badRequest("limit");
        int v = Integer.parseInt(raw);
        if (v > MAX_LIMIT) throw AppGapException.badRequest("limit");
        return v;
    }

    private static String placeholders(int n) { return String.join(",", java.util.Collections.nCopies(n, "?")); }

    private static long longOf(Object v) { return v == null ? 0L : ((Number) v).longValue(); }

    private static Long longOrNull(Object v) { return v == null ? null : ((Number) v).longValue(); }

    private static String iso(Object v) {
        if (v == null) return null;
        Instant at = v instanceof java.util.Date d ? d.toInstant() : v instanceof java.time.LocalDateTime l ? l.atZone(ZoneId.systemDefault()).toInstant() : null;
        return at == null ? String.valueOf(v) : ISO.format(at);
    }
}
