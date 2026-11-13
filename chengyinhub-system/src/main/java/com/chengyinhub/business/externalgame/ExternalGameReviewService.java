package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * External game (H5 package) submission and review loop.
 * <pre>
 *   submit ──► PENDING ──approve──► APPROVED   (catalog shows the newest APPROVED version of a game)
 *                 │  ├──reject(reason)──► REJECTED ──resubmit (higher version)──► new PENDING
 *                 │  ├──cancel (author)──► CANCELLED
 *                 │  └──escalate (admin, reason) / review timeout (SYSTEM)──► ESCALATED
 *   ESCALATED ──approve / reject / cancel──► APPROVED / REJECTED / CANCELLED
 * </pre>
 * APPROVED, REJECTED and CANCELLED are terminal. Every transition writes one external_game_review_event row in the same
 * transaction (actor AUTHOR / ADMIN / SYSTEM). A game id belongs to the author who first submitted it; at most one open
 * (PENDING / ESCALATED) submission per game; versions strictly increase. No money: monetization is NONE or rejected.
 */
@Service
public class ExternalGameReviewService {
    public static final String PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED", ESCALATED = "ESCALATED", CANCELLED = "CANCELLED";
    public static final String TIMEOUT_REASON = "REVIEW_TIMEOUT";
    static final Set<String> STATUSES = Set.of(PENDING, APPROVED, REJECTED, ESCALATED, CANCELLED);
    static final Set<String> REPORT_REASONS = Set.of("INAPPROPRIATE_CONTENT", "MALICIOUS_CODE", "PAYMENT_OR_SCAM", "COPYRIGHT", "BROKEN", "OTHER");
    static final int MAX_LIMIT = 50, MAX_REASON = 500;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("Asia/Shanghai"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBMISSION_COLUMNS = "s.id, s.game_id, s.author_member_id, s.version, s.name, s.creator_label, s.summary,"
            + " s.content_rating, s.entry, s.permissions_json, s.allowed_domains_json, s.package_sha256, s.package_bytes, s.file_count,"
            + " s.monetization, s.signature, s.signature_algorithm, s.signing_key_id, s.status, s.reject_reason, s.escalation_reason,"
            + " s.escalation_source, s.previous_submission_id, s.reviewer_id, s.submitted_time, s.reviewed_time, s.escalated_time, s.row_version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExternalGameSigner signer;
    private final LongSupplier clock;
    private final int timeoutDays;
    private final int linkTtlSeconds;

    @Autowired
    public ExternalGameReviewService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ExternalGameSigner signer,
                                     @Value("${chengyin.external-game.review-timeout-days:3}") int timeoutDays,
                                     @Value("${chengyin.external-game.download-link-ttl-seconds:600}") int linkTtlSeconds) {
        this(jdbc, transactions, signer, timeoutDays, linkTtlSeconds, System::currentTimeMillis);
    }

    ExternalGameReviewService(JdbcTemplate jdbc, PlatformTransactionManager transactions, ExternalGameSigner signer,
                              int timeoutDays, int linkTtlSeconds, LongSupplier clock) {
        if (timeoutDays < 1 || timeoutDays > 60) throw new IllegalArgumentException("chengyin.external-game.review-timeout-days must be 1..60");
        if (linkTtlSeconds < 30 || linkTtlSeconds > 86_400) throw new IllegalArgumentException("chengyin.external-game.download-link-ttl-seconds must be 30..86400");
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.signer = signer;
        this.timeoutDays = timeoutDays;
        this.linkTtlSeconds = linkTtlSeconds;
        this.clock = clock;
    }

    public int timeoutDays() { return timeoutDays; }

    // ================= author =================

    /** New submission (or a resubmission when {@code previousId} names the author's REJECTED / CANCELLED one). */
    public Map<String, Object> submit(Long viewer, String requestId, ExternalGamePackageValidator.Validated pkg, Long previousId) {
        long me = requireMember(viewer);
        String request = optionalRequestId(requestId);
        if (!signer.configured()) throw new AppGapException(503, "SIGNING_UNCONFIGURED", "外部游戏签名密钥未配置，暂不接受提交");
        ExternalGamePackageValidator.Manifest m = pkg.manifest();
        String requestHash = sha256Hex(String.join("|", m.id(), m.version(), m.sha256(),
                new String(ExternalGamePackageValidator.canonicalSigningPayload(m), StandardCharsets.UTF_8),
                previousId == null ? "-" : previousId.toString()));
        if (request != null) {
            Map<String, Object> replay = replay(me, request, requestHash);
            if (replay != null) return replay;
        }
        String signature = signer.signHex(ExternalGamePackageValidator.canonicalSigningPayload(m));
        byte[] archive = archiveJson(m, pkg, signature);
        try {
            long id = tx.execute(status -> insertSubmission(me, request, requestHash, pkg, previousId, signature, archive));
            return authorView(loadSubmission(id));
        } catch (DuplicateKeyException race) {
            if (request != null) {
                Map<String, Object> replay = replay(me, request, requestHash);
                if (replay != null) return replay;
            }
            throw new AppGapException(409, "SUBMISSION_CONFLICT", "该游戏已有进行中的提交或该版本已存在");
        }
    }

    private long insertSubmission(long me, String request, String requestHash, ExternalGamePackageValidator.Validated pkg,
                                  Long previousId, String signature, byte[] archive) {
        ExternalGamePackageValidator.Manifest m = pkg.manifest();
        Timestamp now = now();
        List<Map<String, Object>> game = jdbc.queryForList("SELECT owner_member_id FROM external_game WHERE game_id = ? FOR UPDATE", m.id());
        if (game.isEmpty()) {
            jdbc.update("INSERT INTO external_game(game_id, owner_member_id, create_time, update_time) VALUES (?,?,?,?)", m.id(), me, now, now);
        } else if (longOf(game.get(0).get("owner_member_id")) != me) {
            throw new AppGapException(409, "GAME_ID_TAKEN", "该游戏标识已属于其他作者", Map.of("gameId", m.id()));
        }
        if (previousId != null) {
            List<Map<String, Object>> prev = jdbc.queryForList("SELECT game_id, status FROM external_game_submission WHERE id = ? AND author_member_id = ? FOR UPDATE", previousId, me);
            if (prev.isEmpty()) throw AppGapException.notFound("SUBMISSION_NOT_FOUND");
            String prevStatus = (String) prev.get(0).get("status");
            if (!REJECTED.equals(prevStatus) && !CANCELLED.equals(prevStatus)) {
                throw new AppGapException(409, "RESUBMIT_NOT_ALLOWED", "只有被驳回或已撤回的提交可以重新提交", Map.of("status", prevStatus));
            }
            if (!m.id().equals(prev.get(0).get("game_id"))) throw new AppGapException(400, "GAME_ID_MISMATCH", "重新提交必须是同一个游戏标识");
        }
        List<Map<String, Object>> open = jdbc.queryForList("SELECT id, status FROM external_game_submission WHERE game_id = ? AND status IN ('PENDING','ESCALATED')", m.id());
        if (!open.isEmpty()) {
            throw new AppGapException(409, "SUBMISSION_ALREADY_OPEN", "该游戏已有待审核的提交，请先等待结果或撤回",
                    Map.of("submissionId", longOf(open.get(0).get("id")), "status", open.get(0).get("status")));
        }
        for (Map<String, Object> v : jdbc.queryForList("SELECT version FROM external_game_submission WHERE game_id = ?", m.id())) {
            String existing = (String) v.get("version");
            if (ExternalGamePackageValidator.compareVersions(m.version(), existing) <= 0) {
                throw new AppGapException(409, "VERSION_NOT_INCREASED", "版本号必须高于该游戏已提交过的所有版本", Map.of("version", m.version(), "existingVersion", existing));
            }
        }
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> {
            java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO external_game_submission(game_id, author_member_id, version, name,"
                    + " creator_label, summary, content_rating, entry, permissions_json, allowed_domains_json, package_sha256, package_bytes,"
                    + " file_count, monetization, signature, signature_algorithm, signing_key_id, status, previous_submission_id, request_id,"
                    + " request_hash, submitted_time, update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'NONE',?,?,?,'PENDING',?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, m.id()); ps.setLong(i++, me); ps.setString(i++, m.version()); ps.setString(i++, m.name());
            ps.setString(i++, m.author()); ps.setString(i++, m.summary()); ps.setString(i++, m.contentRating()); ps.setString(i++, m.entry());
            ps.setString(i++, json(m.permissions())); ps.setString(i++, json(m.allowedDomains())); ps.setString(i++, m.sha256());
            ps.setLong(i++, pkg.totalBytes()); ps.setInt(i++, pkg.files().size()); ps.setString(i++, signature);
            ps.setString(i++, ExternalGameSigner.ALGORITHM); ps.setString(i++, signer.keyId());
            if (previousId == null) ps.setNull(i++, java.sql.Types.BIGINT); else ps.setLong(i++, previousId);
            ps.setString(i++, request); ps.setString(i++, requestHash); ps.setTimestamp(i++, now); ps.setTimestamp(i, now);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("INSERT INTO external_game_package(submission_id, archive_json) VALUES (?,?)", id, archive);
        event(id, null, PENDING, "AUTHOR", me, previousId == null ? "SUBMITTED" : "RESUBMITTED_AFTER #" + previousId, now);
        return id;
    }

    private Map<String, Object> replay(long me, String request, String requestHash) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, request_hash FROM external_game_submission WHERE author_member_id = ? AND request_id = ?", me, request);
        if (rows.isEmpty()) return null;
        if (!requestHash.equals(rows.get(0).get("request_hash"))) throw new AppGapException(409, "REQUEST_ID_REUSED", "同一 requestId 已用于不同的提交");
        Map<String, Object> view = authorView(loadSubmission(longOf(rows.get(0).get("id"))));
        view.put("replayed", true);
        return view;
    }

    public Map<String, Object> mine(Long viewer, String status, String cursor, String limit) {
        long me = requireMember(viewer);
        String filter = optionalStatus(status);
        long before = cursor(cursor);
        int size = limit(limit);
        List<Object> args = new ArrayList<>(List.of(me));
        StringBuilder sql = new StringBuilder("SELECT " + SUBMISSION_COLUMNS + " FROM external_game_submission s WHERE s.author_member_id = ?");
        if (filter != null) { sql.append(" AND s.status = ?"); args.add(filter); }
        if (before > 0) { sql.append(" AND s.id < ?"); args.add(before); }
        sql.append(" ORDER BY s.id DESC LIMIT ?");
        args.add(size + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(size, rows.size()))) items.add(authorView(r));
        return page(items, rows.size() > size, "submissionId");
    }

    public Map<String, Object> mineDetail(Long viewer, String submissionId) {
        long me = requireMember(viewer);
        Map<String, Object> row = loadSubmission(requiredId(submissionId, "submissionId"));
        if (row == null || longOf(row.get("author_member_id")) != me) throw AppGapException.notFound("SUBMISSION_NOT_FOUND");
        Map<String, Object> view = authorView(row);
        view.put("timeline", events(longOf(row.get("id")), false));
        return view;
    }

    public Map<String, Object> cancel(Long viewer, String submissionId, Map<String, Object> body) {
        long me = requireMember(viewer);
        long id = requiredId(submissionId, "submissionId");
        String reason = text(body == null ? null : body.get("reason"), MAX_REASON, false, "reason");
        Map<String, Object> row = loadSubmission(id);
        if (row == null || longOf(row.get("author_member_id")) != me) throw AppGapException.notFound("SUBMISSION_NOT_FOUND");
        if (CANCELLED.equals(row.get("status"))) return authorView(row); // replay-safe
        transition(id, Set.of(PENDING, ESCALATED), CANCELLED, "AUTHOR", me, reason == null ? "CANCELLED_BY_AUTHOR" : reason, null);
        return authorView(loadSubmission(id));
    }

    // ================= admin =================

    /** Open review queue: ESCALATED first (oldest first), then PENDING. Runs the timeout sweep first. */
    public Map<String, Object> adminQueue(String status, Integer pageNum, Integer pageSize) {
        sweepTimeouts();
        String filter = optionalStatus(status);
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        int pageNo = pageNum == null ? 1 : Math.max(1, pageNum);
        String where = filter == null ? "s.status IN ('PENDING','ESCALATED')" : "s.status = ?";
        Object[] args = filter == null ? new Object[0] : new Object[]{filter};
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM external_game_submission s WHERE " + where, Long.class, args);
        List<Object> pageArgs = new ArrayList<>(List.of(args));
        pageArgs.add(size); pageArgs.add((pageNo - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SUBMISSION_COLUMNS + ", (SELECT COUNT(1) FROM external_game_report r"
                + " WHERE r.game_id = s.game_id AND r.status = 'OPEN') AS open_reports FROM external_game_submission s WHERE " + where
                + " ORDER BY (s.status = 'ESCALATED') DESC, s.submitted_time ASC, s.id ASC LIMIT ? OFFSET ?", pageArgs.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> v = adminView(r);
            v.put("openReports", longOf(r.get("open_reports")));
            items.add(v);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT status, COUNT(1) AS n FROM external_game_submission GROUP BY status")) {
            counts.put((String) c.get("status"), longOf(c.get("n")));
        }
        for (String s : List.of(PENDING, ESCALATED, APPROVED, REJECTED, CANCELLED)) counts.putIfAbsent(s, 0L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("rows", items);
        out.put("counts", counts);
        out.put("reviewTimeoutDays", timeoutDays);
        return out;
    }

    public Map<String, Object> adminDetail(String submissionId) {
        Map<String, Object> row = loadSubmission(requiredId(submissionId, "submissionId"));
        if (row == null) throw AppGapException.notFound("SUBMISSION_NOT_FOUND");
        Map<String, Object> view = adminView(row);
        view.put("files", fileList(longOf(row.get("id"))));
        view.put("timeline", events(longOf(row.get("id")), true));
        view.put("reports", reportsFor((String) row.get("game_id"), 20));
        return view;
    }

    /** The stored package (server-signed manifest + files) for the reviewer to inspect. */
    public byte[] adminPackage(String submissionId) {
        long id = requiredId(submissionId, "submissionId");
        List<byte[]> rows = jdbc.query("SELECT archive_json FROM external_game_package WHERE submission_id = ?", (rs, n) -> rs.getBytes(1), id);
        if (rows.isEmpty()) throw AppGapException.notFound("SUBMISSION_NOT_FOUND");
        return rows.get(0);
    }

    public Map<String, Object> approve(Long admin, String submissionId, Map<String, Object> body) {
        long reviewer = requireAdmin(admin);
        long id = requiredId(submissionId, "submissionId");
        String note = text(body == null ? null : body.get("note"), MAX_REASON, false, "note");
        transition(id, Set.of(PENDING, ESCALATED), APPROVED, "ADMIN", reviewer, note, null);
        return adminView(loadSubmission(id));
    }

    public Map<String, Object> reject(Long admin, String submissionId, Map<String, Object> body) {
        long reviewer = requireAdmin(admin);
        long id = requiredId(submissionId, "submissionId");
        String reason = text(body == null ? null : body.get("reason"), MAX_REASON, true, "reason");
        transition(id, Set.of(PENDING, ESCALATED), REJECTED, "ADMIN", reviewer, reason, null);
        return adminView(loadSubmission(id));
    }

    public Map<String, Object> escalate(Long admin, String submissionId, Map<String, Object> body) {
        long reviewer = requireAdmin(admin);
        long id = requiredId(submissionId, "submissionId");
        String reason = text(body == null ? null : body.get("reason"), MAX_REASON, true, "reason");
        transition(id, Set.of(PENDING), ESCALATED, "ADMIN", reviewer, reason, "REVIEWER");
        return adminView(loadSubmission(id));
    }

    /** PENDING longer than the review timeout → ESCALATED (actor SYSTEM, reason REVIEW_TIMEOUT). Idempotent. */
    public int sweepTimeouts() {
        Timestamp threshold = new Timestamp(clock.getAsLong() - timeoutDays * 86_400_000L);
        List<Long> overdue = jdbc.queryForList("SELECT id FROM external_game_submission WHERE status = 'PENDING' AND submitted_time <= ?"
                + " ORDER BY id LIMIT 200", Long.class, threshold);
        int moved = 0;
        for (Long id : overdue) {
            try {
                transition(id, Set.of(PENDING), ESCALATED, "SYSTEM", null, TIMEOUT_REASON, "TIMEOUT");
                moved++;
            } catch (AppGapException raced) {
                // reviewed or cancelled meanwhile
            }
        }
        return moved;
    }

    public Map<String, Object> adminReports(String gameId, String status, Integer pageNum, Integer pageSize) {
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        int pageNo = pageNum == null ? 1 : Math.max(1, pageNum);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (gameId != null && !gameId.isEmpty()) { where.append(" AND game_id = ?"); args.add(gameId); }
        if (status != null && !status.isEmpty()) {
            if (!status.equals("OPEN") && !status.equals("RESOLVED")) throw AppGapException.badRequest("status");
            where.append(" AND status = ?"); args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM external_game_report" + where, Long.class, args.toArray());
        args.add(size); args.add((pageNo - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM external_game_report" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(reportView(r, true));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("rows", items);
        return out;
    }

    // ================= public catalog =================

    public Map<String, Object> catalog(String cursor, String limit) {
        long before = cursor(cursor);
        int size = limit(limit);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + SUBMISSION_COLUMNS + " FROM external_game g JOIN external_game_submission s"
                + " ON s.id = g.approved_submission_id AND s.status = 'APPROVED' WHERE 1 = 1");
        if (before > 0) { sql.append(" AND s.id < ?"); args.add(before); }
        sql.append(" ORDER BY s.id DESC LIMIT ?");
        args.add(size + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(size, rows.size()))) items.add(catalogView(r));
        return page(items, rows.size() > size, "submissionId");
    }

    public Map<String, Object> catalogDetail(String gameId) {
        Map<String, Object> row = approvedRow(gameId);
        Map<String, Object> view = catalogView(row);
        if (!signer.configured()) throw new AppGapException(503, "SIGNING_UNCONFIGURED", "外部游戏签名密钥未配置");
        long exp = clock.getAsLong() / 1000 + linkTtlSeconds;
        long sid = longOf(row.get("id"));
        String token = signer.linkToken(linkPayload(gameId, sid, (String) row.get("package_sha256"), exp));
        view.put("downloadPath", "/api/external-game/catalog/" + gameId + "/package?submissionId=" + sid + "&exp=" + exp + "&sig=" + token);
        view.put("downloadExpiresAt", ISO.format(Instant.ofEpochSecond(exp)));
        return view;
    }

    /** Signed, expiring download of the approved package: the app's archive JSON with the server-signed manifest. */
    public byte[] catalogPackage(String gameId, String submissionId, String exp, String sig) {
        long sid = requiredId(submissionId, "submissionId");
        if (exp == null || !exp.matches("[1-9][0-9]{0,11}")) throw new AppGapException(403, "DOWNLOAD_LINK_INVALID", "下载链接无效");
        if (gameId == null || !gameId.matches("[a-z0-9][a-z0-9._-]{2,63}")) throw new AppGapException(403, "DOWNLOAD_LINK_INVALID", "下载链接无效");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT package_sha256 FROM external_game_submission WHERE id = ? AND game_id = ?", sid, gameId);
        if (rows.isEmpty() || !signer.verifyLinkToken(linkPayload(gameId, sid, (String) rows.get(0).get("package_sha256"), Long.parseLong(exp)), sig)) {
            throw new AppGapException(403, "DOWNLOAD_LINK_INVALID", "下载链接无效");
        }
        if (Long.parseLong(exp) < clock.getAsLong() / 1000) throw new AppGapException(410, "DOWNLOAD_LINK_EXPIRED", "下载链接已过期，请重新获取详情");
        Map<String, Object> approved = approvedRow(gameId);
        if (longOf(approved.get("id")) != sid) throw new AppGapException(410, "PACKAGE_SUPERSEDED", "该版本已不是当前上架版本");
        return adminPackage(String.valueOf(sid));
    }

    public Map<String, Object> signingKey() { return signer.publicKeyView(); }

    // ================= reports =================

    /** Player report of an installed external game. Accepted for packages the catalog never saw (side-loaded). */
    public Map<String, Object> report(Long viewer, Map<String, Object> body) {
        long me = requireMember(viewer);
        if (body == null) throw AppGapException.badRequest("body");
        for (String k : body.keySet()) {
            if (!Set.of("requestId", "gameId", "version", "sha256", "reasonCode", "detail", "blocked").contains(k)) throw AppGapException.badRequest(k);
        }
        String request = requiredRequestId(body.get("requestId"));
        String gameId = body.get("gameId") instanceof String g && g.matches("[a-z0-9][a-z0-9._-]{2,63}") ? g : null;
        if (gameId == null) throw AppGapException.badRequest("gameId");
        String version = body.get("version") == null ? null : body.get("version") instanceof String v && v.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}") ? v : bad("version");
        String sha = body.get("sha256") == null ? null : body.get("sha256") instanceof String h && h.matches("[0-9a-f]{64}") ? h : bad("sha256");
        String reason = body.get("reasonCode") instanceof String r && REPORT_REASONS.contains(r) ? r : bad("reasonCode");
        String detail = text(body.get("detail"), MAX_REASON, "OTHER".equals(reason), "detail");
        boolean blocked = Boolean.TRUE.equals(body.get("blocked"));
        String hash = sha256Hex(String.join("|", gameId, String.valueOf(version), String.valueOf(sha), reason, String.valueOf(detail), String.valueOf(blocked)));
        List<Map<String, Object>> prior = jdbc.queryForList("SELECT * FROM external_game_report WHERE reporter_member_id = ? AND request_id = ?", me, request);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.get(0).get("request_hash"))) throw new AppGapException(409, "REQUEST_ID_REUSED", "同一 requestId 已用于不同的举报");
            return reportView(prior.get(0), false);
        }
        Long submission = null;
        List<Map<String, Object>> match = version == null
                ? jdbc.queryForList("SELECT approved_submission_id AS id FROM external_game WHERE game_id = ? AND approved_submission_id IS NOT NULL", gameId)
                : jdbc.queryForList("SELECT id FROM external_game_submission WHERE game_id = ? AND version = ?", gameId, version);
        if (!match.isEmpty()) submission = longOf(match.get(0).get("id"));
        try {
            jdbc.update("INSERT INTO external_game_report(game_id, submission_id, version, package_sha256, reporter_member_id, reason_code, detail,"
                    + " blocked, status, request_id, request_hash, create_time) VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?,?)",
                    gameId, submission, version, sha, me, reason, detail, blocked ? 1 : 0, request, hash, now());
        } catch (DuplicateKeyException dup) {
            List<Map<String, Object>> again = jdbc.queryForList("SELECT * FROM external_game_report WHERE reporter_member_id = ? AND request_id = ?", me, request);
            if (!again.isEmpty()) {
                if (!hash.equals(again.get(0).get("request_hash"))) throw new AppGapException(409, "REQUEST_ID_REUSED", "同一 requestId 已用于不同的举报");
                return reportView(again.get(0), false);
            }
            List<Map<String, Object>> open = jdbc.queryForList("SELECT * FROM external_game_report WHERE reporter_member_id = ? AND game_id = ? AND status = 'OPEN'", me, gameId);
            Map<String, Object> view = open.isEmpty() ? Map.of() : reportView(open.get(0), false);
            throw new AppGapException(409, "REPORT_ALREADY_OPEN", "你对这个游戏的举报还在处理中", view);
        }
        return reportView(jdbc.queryForList("SELECT * FROM external_game_report WHERE reporter_member_id = ? AND request_id = ?", me, request).get(0), false);
    }

    // ================= state machine core =================

    private void transition(long id, Set<String> from, String to, String actorType, Long actorId, String reason, String escalationSource) {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, game_id FROM external_game_submission WHERE id = ? FOR UPDATE", id);
            if (rows.isEmpty()) throw AppGapException.notFound("SUBMISSION_NOT_FOUND");
            String current = (String) rows.get(0).get("status");
            if (!from.contains(current)) {
                throw new AppGapException(409, "INVALID_TRANSITION", "当前状态不允许该操作", Map.of("status", current, "target", to));
            }
            Timestamp now = now();
            int n = switch (to) {
                case APPROVED -> jdbc.update("UPDATE external_game_submission SET status = 'APPROVED', reviewer_id = ?, reviewed_time = ?, update_time = ?,"
                        + " row_version = row_version + 1 WHERE id = ? AND status = ?", actorId, now, now, id, current);
                case REJECTED -> jdbc.update("UPDATE external_game_submission SET status = 'REJECTED', reject_reason = ?, reviewer_id = ?, reviewed_time = ?,"
                        + " update_time = ?, row_version = row_version + 1 WHERE id = ? AND status = ?", reason, actorId, now, now, id, current);
                case ESCALATED -> jdbc.update("UPDATE external_game_submission SET status = 'ESCALATED', escalation_reason = ?, escalation_source = ?,"
                        + " escalated_time = ?, update_time = ?, row_version = row_version + 1 WHERE id = ? AND status = ?", reason, escalationSource, now, now, id, current);
                case CANCELLED -> jdbc.update("UPDATE external_game_submission SET status = 'CANCELLED', update_time = ?, row_version = row_version + 1"
                        + " WHERE id = ? AND status = ?", now, id, current);
                default -> throw new IllegalArgumentException(to);
            };
            if (n != 1) throw new AppGapException(409, "INVALID_TRANSITION", "当前状态不允许该操作", Map.of("status", current, "target", to));
            if (APPROVED.equals(to)) {
                jdbc.update("UPDATE external_game SET approved_submission_id = ?, approved_time = ?, update_time = ? WHERE game_id = ?",
                        id, now, now, rows.get(0).get("game_id"));
            }
            event(id, current, to, actorType, actorId, reason, now);
        });
    }

    private void event(long submissionId, String from, String to, String actorType, Long actorId, String reason, Timestamp at) {
        jdbc.update("INSERT INTO external_game_review_event(submission_id, from_status, to_status, actor_type, actor_id, reason, create_time)"
                + " VALUES (?,?,?,?,?,?,?)", submissionId, from, to, actorType, actorId, reason, at);
    }

    // ================= views =================

    private Map<String, Object> loadSubmission(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SUBMISSION_COLUMNS + " FROM external_game_submission s WHERE s.id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> approvedRow(String gameId) {
        if (gameId == null || !gameId.matches("[a-z0-9][a-z0-9._-]{2,63}")) throw AppGapException.notFound("GAME_NOT_FOUND");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SUBMISSION_COLUMNS + " FROM external_game g JOIN external_game_submission s"
                + " ON s.id = g.approved_submission_id AND s.status = 'APPROVED' WHERE g.game_id = ?", gameId);
        if (rows.isEmpty()) throw AppGapException.notFound("GAME_NOT_FOUND");
        return rows.get(0);
    }

    private Map<String, Object> base(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("submissionId", longOf(r.get("id")));
        v.put("gameId", r.get("game_id"));
        v.put("version", r.get("version"));
        v.put("name", r.get("name"));
        v.put("author", r.get("creator_label"));
        v.put("summary", r.get("summary"));
        v.put("contentRating", r.get("content_rating"));
        v.put("entry", r.get("entry"));
        v.put("permissions", list(r.get("permissions_json")));
        v.put("allowedDomains", list(r.get("allowed_domains_json")));
        v.put("sha256", r.get("package_sha256"));
        v.put("packageBytes", longOf(r.get("package_bytes")));
        v.put("fileCount", longOf(r.get("file_count")));
        v.put("monetization", r.get("monetization"));
        v.put("signature", r.get("signature"));
        v.put("signatureAlgorithm", r.get("signature_algorithm"));
        v.put("signingKeyId", r.get("signing_key_id"));
        return v;
    }

    private Map<String, Object> authorView(Map<String, Object> r) {
        Map<String, Object> v = base(r);
        String status = (String) r.get("status");
        v.put("status", status);
        v.put("rejectReason", r.get("reject_reason"));
        v.put("escalated", r.get("escalated_time") != null);
        v.put("previousSubmissionId", longOrNull(r.get("previous_submission_id")));
        v.put("submittedAt", iso(r.get("submitted_time")));
        v.put("reviewedAt", iso(r.get("reviewed_time")));
        v.put("reviewDueAt", reviewDue(r));
        v.put("canCancel", PENDING.equals(status) || ESCALATED.equals(status));
        v.put("canResubmit", REJECTED.equals(status) || CANCELLED.equals(status));
        return v;
    }

    private Map<String, Object> adminView(Map<String, Object> r) {
        Map<String, Object> v = authorView(r);
        v.put("authorMemberId", longOf(r.get("author_member_id")));
        v.put("escalationReason", r.get("escalation_reason"));
        v.put("escalationSource", r.get("escalation_source"));
        v.put("escalatedAt", iso(r.get("escalated_time")));
        v.put("reviewerId", longOrNull(r.get("reviewer_id")));
        v.put("rowVersion", longOf(r.get("row_version")));
        v.remove("canCancel");
        v.remove("canResubmit");
        return v;
    }

    private Map<String, Object> catalogView(Map<String, Object> r) {
        Map<String, Object> v = base(r);
        v.put("approvedAt", iso(r.get("reviewed_time")));
        return v;
    }

    private String reviewDue(Map<String, Object> r) {
        Instant submitted = instant(r.get("submitted_time"));
        if (!PENDING.equals(r.get("status")) || submitted == null) return null;
        return ISO.format(submitted.plusSeconds(timeoutDays * 86_400L));
    }

    private List<Map<String, Object>> events(long submissionId, boolean withActorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : jdbc.queryForList("SELECT * FROM external_game_review_event WHERE submission_id = ? ORDER BY id", submissionId)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("eventId", longOf(e.get("id")));
            v.put("fromStatus", e.get("from_status"));
            v.put("toStatus", e.get("to_status"));
            v.put("actorType", e.get("actor_type"));
            if (withActorId) v.put("actorId", longOrNull(e.get("actor_id")));
            v.put("reason", e.get("reason"));
            v.put("at", iso(e.get("create_time")));
            out.add(v);
        }
        return out;
    }

    private List<Map<String, Object>> fileList(long submissionId) {
        try {
            com.fasterxml.jackson.databind.JsonNode files = JSON.readTree(adminPackage(String.valueOf(submissionId))).get("files");
            List<Map<String, Object>> out = new ArrayList<>();
            files.fields().forEachRemaining(e -> out.add(Map.of("path", e.getKey(), "bytes", Base64.getDecoder().decode(e.getValue().asText()).length)));
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> reportsFor(String gameId, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM external_game_report WHERE game_id = ? ORDER BY id DESC LIMIT ?", gameId, max)) out.add(reportView(r, true));
        return out;
    }

    private Map<String, Object> reportView(Map<String, Object> r, boolean admin) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("reportId", longOf(r.get("id")));
        v.put("gameId", r.get("game_id"));
        v.put("submissionId", longOrNull(r.get("submission_id")));
        v.put("version", r.get("version"));
        v.put("sha256", r.get("package_sha256"));
        v.put("reasonCode", r.get("reason_code"));
        v.put("detail", r.get("detail"));
        v.put("blocked", r.get("blocked") != null && ((Number) toNumber(r.get("blocked"))).intValue() == 1);
        v.put("status", r.get("status"));
        v.put("knownToCatalog", r.get("submission_id") != null);
        if (admin) v.put("reporterMemberId", longOf(r.get("reporter_member_id")));
        v.put("requestId", r.get("request_id"));
        v.put("createdAt", iso(r.get("create_time")));
        return v;
    }

    private static Object toNumber(Object o) { return o instanceof Boolean b ? (b ? 1 : 0) : o; }

    private static byte[] archiveJson(ExternalGamePackageValidator.Manifest m, ExternalGamePackageValidator.Validated pkg, String signature) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode manifest = root.putObject("manifest");
        manifest.put("id", m.id());
        manifest.put("name", m.name());
        manifest.put("version", m.version());
        manifest.put("entry", m.entry());
        ArrayNode perms = manifest.putArray("permissions");
        m.permissions().forEach(perms::add);
        manifest.put("contentRating", m.contentRating());
        ArrayNode domains = manifest.putArray("allowedDomains");
        m.allowedDomains().forEach(domains::add);
        manifest.put("sha256", m.sha256());
        manifest.put("signature", signature);
        manifest.put("signatureAlgorithm", ExternalGameSigner.ALGORITHM);
        if (m.author() != null) manifest.put("author", m.author());
        if (m.summary() != null) manifest.put("summary", m.summary());
        manifest.put("monetization", ExternalGamePackageValidator.MONETIZATION_NONE);
        ObjectNode files = root.putObject("files");
        pkg.files().forEach((path, bytes) -> files.put(path, Base64.getEncoder().encodeToString(bytes)));
        try {
            return JSON.writeValueAsBytes(root);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String linkPayload(String gameId, long submissionId, String sha256, long exp) {
        return "dl-v1|" + gameId + "|" + submissionId + "|" + sha256 + "|" + exp;
    }

    // ================= small helpers =================

    private Timestamp now() { return new Timestamp(clock.getAsLong()); }

    private static String json(List<String> values) {
        try { return JSON.writeValueAsString(values); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private static List<String> list(Object raw) {
        if (raw == null) return List.of();
        try { return List.of(JSON.readValue(raw.toString(), String[].class)); } catch (java.io.IOException e) { return List.of(); }
    }

    private static String sha256Hex(String s) {
        return HexFormat.of().formatHex(ExternalGamePackageValidator.sha256().digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> T bad(String field) { throw AppGapException.badRequest(field); }

    private static long requireMember(Long viewer) {
        if (viewer == null || viewer <= 0) throw AppGapException.unauthorized();
        return viewer;
    }

    private static long requireAdmin(Long admin) {
        if (admin == null || admin <= 0) throw AppGapException.unauthorized();
        return admin;
    }

    private static String optionalStatus(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (!STATUSES.contains(raw)) throw AppGapException.badRequest("status");
        return raw;
    }

    private static String optionalRequestId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return requiredRequestId(raw);
    }

    private static String requiredRequestId(Object raw) {
        if (!(raw instanceof String s) || !s.matches("[A-Za-z0-9._:-]{8,64}")) throw AppGapException.badRequest("requestId");
        return s;
    }

    private static long requiredId(Object raw, String field) {
        if (raw instanceof Number n && n.longValue() > 0) return n.longValue();
        if (raw instanceof String s && s.matches("[1-9][0-9]{0,18}")) return Long.parseLong(s);
        throw AppGapException.badRequest(field);
    }

    private static String text(Object raw, int max, boolean required, String field) {
        if (raw == null) { if (required) throw AppGapException.badRequest(field); return null; }
        if (!(raw instanceof String s)) throw AppGapException.badRequest(field);
        String t = s.strip();
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

    private static Map<String, Object> page(List<Map<String, Object>> items, boolean more, String idKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("hasMore", more);
        out.put("nextCursor", more && !items.isEmpty() ? String.valueOf(items.get(items.size() - 1).get(idKey)) : null);
        return out;
    }

    private static long longOf(Object v) { return v == null ? 0L : ((Number) toNumber(v)).longValue(); }

    private static Long longOrNull(Object v) { return v == null ? null : longOf(v); }

    private static Instant instant(Object v) {
        return v instanceof java.util.Date d ? d.toInstant() : v instanceof java.time.LocalDateTime l ? l.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    private static String iso(Object v) {
        if (v == null) return null;
        Instant at = instant(v);
        return at == null ? String.valueOf(v) : ISO.format(at);
    }
}
