package com.chengyinhub.business.appgaps;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * /api/location-share/personal/**: foreground-only live location sharing between two members who follow
 * each other, under one published, versioned policy. Privacy and retention rules (owner decision for v1):
 * <ul>
 *   <li>the owner is always the logged-in member; a grant names exactly one recipient;</li>
 *   <li>only the latest observation of an ACTIVE grant is stored; it is deleted when the grant ends;</li>
 *   <li>a COARSE grant stores the snapped grid cell, never the exact fix;</li>
 *   <li>grants end on expiry, on owner revoke, or when the follow relationship is gone.</li>
 * </ul>
 */
@Service
public class LocationShareService {
    enum Disclosure { audience, precision, duration, foregroundOnly, stopAnytime, alreadySeenCannotBeRecalled }

    private static final Set<String> CREATE_KEYS = Set.of("requestKey", "recipientId", "policyId", "policyVersion", "policyDigest",
            "modeChoiceId", "durationChoiceId", "foregroundOnly", "acknowledgedDisclosures");
    private static final Set<String> PUBLISH_KEYS = Set.of("sequence", "observedAtMs", "datum", "latitude", "longitude", "foreground");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LocationShareService(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, System::currentTimeMillis);
    }

    LocationShareService(JdbcTemplate jdbc, PlatformTransactionManager transactions, LongSupplier clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    // ---- policy ----

    Map<String, Object> policy() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM location_share_policy WHERE status = 'PUBLISHED' ORDER BY version DESC LIMIT 1");
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policyId", row.get("policy_id"));
        out.put("version", AppGapSupport.num(row.get("version")));
        out.put("approvalRef", row.get("approval_ref"));
        out.put("foregroundOnly", true);
        out.put("requiresRecipientAcceptance", false);
        out.put("modeChoices", JSON.parseArray(String.valueOf(row.get("mode_choices"))));
        out.put("durationChoices", JSON.parseArray(String.valueOf(row.get("duration_choices"))));
        out.put("refreshAfterMs", AppGapSupport.num(row.get("refresh_after_ms")));
        out.put("staleAfterMs", AppGapSupport.num(row.get("stale_after_ms")));
        out.put("displayLeaseMs", AppGapSupport.num(row.get("display_lease_ms")));
        out.put("ownerLeaseMs", AppGapSupport.num(row.get("owner_lease_ms")));
        // The digest covers every field above, so a client consent always names one exact policy text.
        out.put("digest", AppGapSupport.sha256(AppGapSupport.canonical(out)));
        return out;
    }

    private Map<String, Object> requirePolicy() {
        Map<String, Object> policy = policy();
        if (policy == null) throw new AppGapException(409, "POLICY_NOT_PUBLISHED", "位置共享暂未开放");
        return policy;
    }

    boolean mutualFollow(long a, long b) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM ums_member_follow WHERE member_id = ? AND follow_member_id = ? LIMIT 1) x"
                        + " JOIN (SELECT 1 FROM ums_member_follow WHERE member_id = ? AND follow_member_id = ? LIMIT 1) y"
                        + " JOIN ums_member m ON m.id = ? AND m.del_flag = 0",
                Integer.class, a, b, b, a, b);
        return count != null && count > 0;
    }

    // ---- reads ----

    public Map<String, Object> context(Long owner, String recipientParam) {
        AppGapSupport.requireMember(owner);
        long recipient = positive(recipientParam, "recipientId");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerId", owner);
        out.put("recipientId", recipient);
        Map<String, Object> policy = policy();
        String reason = null;
        if (recipient == owner) reason = "SELF";
        else if (policy == null) reason = "POLICY_NOT_PUBLISHED";
        else if (!mutualFollow(owner, recipient)) reason = "NOT_MUTUAL_FOLLOW";
        out.put("eligible", reason == null);
        out.put("unavailableReason", reason);
        out.put("policy", reason == null ? policy : null);
        return out;
    }

    public Map<String, Object> requestStatus(Long owner, String requestKey) {
        AppGapSupport.requireMember(owner);
        if (!AppGapSupport.anyUuid(requestKey)) throw AppGapException.badRequest("requestKey");
        expireDue();
        Map<String, Object> grant = grantByKey(owner, requestKey);
        return receipt(requestKey, grant);
    }

    public Map<String, Object> owned(Long owner) {
        AppGapSupport.requireMember(owner);
        expireDue();
        List<Map<String, Object>> grants = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM location_share_grant WHERE owner_id = ? ORDER BY created_at_ms DESC LIMIT 50", owner)) {
            grants.add(wireGrant(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerId", owner);
        out.put("serverNowMs", clock.getAsLong());
        out.put("grants", grants);
        return out;
    }

    public Map<String, Object> received(Long recipient) {
        AppGapSupport.requireMember(recipient);
        Map<String, Object> policy = requirePolicy();
        expireDue();
        long now = clock.getAsLong();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM location_share_grant WHERE recipient_id = ? AND status = 'ACTIVE' ORDER BY created_at_ms", recipient)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("grant", wireGrant(row));
            entry.put("observation", observation(row, now, AppGapSupport.num(policy.get("staleAfterMs"))));
            entries.add(entry);
        }
        Long cursor = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM location_share_event WHERE recipient_id = ?", Long.class, recipient);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipientId", recipient);
        out.put("serverNowMs", now);
        out.put("cursor", cursor);
        out.put("displayLeaseUntilMs", now + AppGapSupport.num(policy.get("displayLeaseMs")));
        out.put("refreshAfterMs", AppGapSupport.num(policy.get("refreshAfterMs")));
        out.put("staleAfterMs", AppGapSupport.num(policy.get("staleAfterMs")));
        out.put("policyId", policy.get("policyId"));
        out.put("policyVersion", policy.get("version"));
        out.put("policyDigest", policy.get("digest"));
        out.put("entries", entries);
        return out;
    }

    public Map<String, Object> events(Long recipient, String cursorParam) {
        AppGapSupport.requireMember(recipient);
        if (cursorParam == null || !cursorParam.matches("0|[1-9][0-9]{0,17}")) throw AppGapException.badRequest("cursor");
        Map<String, Object> policy = requirePolicy();
        expireDue();
        long cursor = Long.parseLong(cursorParam);
        Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM location_share_event WHERE recipient_id = ?", Long.class, recipient);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipientId", recipient);
        out.put("serverNowMs", clock.getAsLong());
        out.put("refreshAfterMs", AppGapSupport.num(policy.get("refreshAfterMs")));
        List<Map<String, Object>> events = new ArrayList<>();
        // A cursor from the future (another account, a reset store) cannot be continued.
        boolean reset = cursor > latest;
        long last = cursor;
        if (!reset) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, grant_id, version, watermark, kind FROM location_share_event WHERE recipient_id = ? AND id > ? ORDER BY id LIMIT 200",
                    recipient, cursor)) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("grantId", row.get("grant_id"));
                event.put("version", AppGapSupport.num(row.get("version")));
                event.put("watermark", AppGapSupport.num(row.get("watermark")));
                event.put("kind", row.get("kind"));
                events.add(event);
                last = AppGapSupport.num(row.get("id"));
            }
        }
        out.put("cursor", reset ? latest : last);
        out.put("resetRequired", reset);
        out.put("events", events);
        return out;
    }

    // ---- writes ----

    public Map<String, Object> create(Long owner, Map<String, Object> body) {
        AppGapSupport.requireMember(owner);
        String requestKey = AppGapSupport.str(body, "requestKey");
        if (!AppGapSupport.exactKeys(body, CREATE_KEYS) || !AppGapSupport.anyUuid(requestKey)) throw new AppGapException(400, "INVALID_REQUEST", "共享请求无效");
        String hash = AppGapSupport.sha256(AppGapSupport.canonical(body));
        Map<String, Object> existing = grantByKey(owner, requestKey);
        if (existing != null) return replay(requestKey, existing, hash);
        Long recipient = AppGapSupport.integer(body, "recipientId"), policyVersion = AppGapSupport.integer(body, "policyVersion");
        Object foreground = body.get("foregroundOnly");
        if (recipient == null || recipient <= 0 || policyVersion == null || !Boolean.TRUE.equals(foreground)) {
            throw new AppGapException(400, "INVALID_REQUEST", "共享请求无效");
        }
        if (recipient.equals(owner)) throw new AppGapException(409, "SELF", "不能与自己共享");
        Map<String, Object> policy = requirePolicy();
        if (!policy.get("policyId").equals(AppGapSupport.str(body, "policyId")) || !policyVersion.equals(policy.get("version"))
                || !policy.get("digest").equals(AppGapSupport.str(body, "policyDigest"))) {
            throw new AppGapException(409, "POLICY_CHANGED", "共享规则已更新，请重新确认");
        }
        Object acknowledged = body.get("acknowledgedDisclosures");
        EnumSet<Disclosure> seen = EnumSet.noneOf(Disclosure.class);
        if (acknowledged instanceof List) {
            for (Object value : (List<?>) acknowledged) {
                try { seen.add(Disclosure.valueOf(String.valueOf(value))); } catch (IllegalArgumentException ignored) { }
            }
        }
        if (!seen.equals(EnumSet.allOf(Disclosure.class))) throw new AppGapException(409, "INCOMPLETE_CONSENT", "请确认全部说明");
        JSONObject mode = choice((JSONArray) policy.get("modeChoices"), AppGapSupport.str(body, "modeChoiceId"));
        JSONObject duration = choice((JSONArray) policy.get("durationChoices"), AppGapSupport.str(body, "durationChoiceId"));
        if (mode == null || duration == null) throw new AppGapException(409, "CHOICE_UNAVAILABLE", "所选共享方式不可用");
        if (!mutualFollow(owner, recipient)) throw new AppGapException(409, "NOT_MUTUAL_FOLLOW", "只能与互相关注的人共享");
        long now = clock.getAsLong();
        String grantId = UUID.randomUUID().toString();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO location_share_grant(grant_id, owner_id, request_key, request_hash, recipient_id, policy_id, policy_version,"
                                + " policy_digest, mode_choice_id, mode, duration_choice_id, created_at_ms, expires_at_ms, owner_lease_until_ms)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        grantId, owner, requestKey, hash, recipient, policy.get("policyId"), policy.get("version"), policy.get("digest"),
                        mode.getString("id"), mode.getString("mode"), duration.getString("id"), now, now + 1000L * duration.getLongValue("seconds"),
                        now + AppGapSupport.num(policy.get("ownerLeaseMs")));
                event(recipient, grantId, 1, 1, "UPDATED");
            });
        } catch (DuplicateKeyException e) {
            Map<String, Object> raced = grantByKey(owner, requestKey);
            if (raced != null) return replay(requestKey, raced, hash);
            throw new AppGapException(409, "GRANT_ALREADY_ACTIVE", "已在与对方共享位置");
        }
        return receipt(requestKey, grantByKey(owner, requestKey));
    }

    public Map<String, Object> revoke(Long owner, String requestKey) {
        AppGapSupport.requireMember(owner);
        if (!AppGapSupport.anyUuid(requestKey)) throw AppGapException.badRequest("requestKey");
        expireDue();
        Map<String, Object> grant = grantByKey(owner, requestKey);
        if (grant == null) return receipt(requestKey, null);
        if ("ACTIVE".equals(grant.get("status"))) end(grant, "REVOKED", "OWNER_REVOKED");
        return receipt(requestKey, grantByKey(owner, requestKey));
    }

    public Map<String, Object> publish(Long owner, String grantId, Map<String, Object> body) {
        AppGapSupport.requireMember(owner);
        if (!AppGapSupport.anyUuid(grantId) || !AppGapSupport.exactKeys(body, PUBLISH_KEYS)) throw new AppGapException(400, "INVALID_REQUEST", "位置上报无效");
        Long sequence = AppGapSupport.integer(body, "sequence"), observedAt = AppGapSupport.integer(body, "observedAtMs");
        Object lat = body.get("latitude"), lng = body.get("longitude");
        if (sequence == null || sequence <= 0 || observedAt == null || observedAt <= 0 || !"WGS84".equals(body.get("datum"))
                || !Boolean.TRUE.equals(body.get("foreground")) || !(lat instanceof Number) || !(lng instanceof Number)) {
            throw new AppGapException(400, "INVALID_REQUEST", "位置上报无效");
        }
        double latitude = ((Number) lat).doubleValue(), longitude = ((Number) lng).doubleValue();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new AppGapException(400, "INVALID_REQUEST", "位置上报无效");
        }
        Map<String, Object> policy = requirePolicy();
        expireDue();
        List<Map<String, Object>> current = jdbc.queryForList("SELECT * FROM location_share_grant WHERE grant_id = ? AND owner_id = ?", grantId, owner);
        if (!current.isEmpty() && "ACTIVE".equals(current.get(0).get("status"))
                && !mutualFollow(owner, AppGapSupport.num(current.get(0).get("recipient_id")))) {
            // The follow relationship is gone: the grant stops and its stored location is deleted.
            end(current.get(0), "STOPPED", "RELATIONSHIP_ENDED");
            throw new AppGapException(409, "GRANT_NOT_ACTIVE", "共享已结束");
        }
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM location_share_grant WHERE grant_id = ? FOR UPDATE", grantId);
            if (rows.isEmpty() || AppGapSupport.num(rows.get(0).get("owner_id")) != owner) throw new AppGapException(404, "GRANT_NOT_FOUND", "共享不存在");
            Map<String, Object> grant = rows.get(0);
            if (!"ACTIVE".equals(grant.get("status"))) throw new AppGapException(409, "GRANT_NOT_ACTIVE", "共享已结束");
            long now = clock.getAsLong();
            if (observedAt < AppGapSupport.num(grant.get("created_at_ms")) || observedAt > now + 5_000) {
                throw new AppGapException(409, "OBSERVATION_TIME_INVALID", "定位时间无效");
            }
            long last = AppGapSupport.num(grant.get("last_sequence"));
            if (sequence <= last) throw new AppGapException(409, "SEQUENCE_STALE", "定位序号已过期");
            BigDecimal[] stored = snap(grant, (JSONArray) policy.get("modeChoices"), latitude, longitude);
            long watermark = AppGapSupport.num(grant.get("watermark")) + 1;
            long version = AppGapSupport.num(grant.get("version"));
            long lease = now + AppGapSupport.num(policy.get("ownerLeaseMs"));
            jdbc.update("REPLACE INTO location_share_observation(grant_id, sequence, observed_at_ms, latitude, longitude) VALUES (?,?,?,?,?)",
                    grantId, sequence, observedAt, stored[0], stored[1]);
            jdbc.update("UPDATE location_share_grant SET last_sequence = ?, watermark = ?, owner_lease_until_ms = ? WHERE grant_id = ?",
                    sequence, watermark, lease, grantId);
            event(AppGapSupport.num(grant.get("recipient_id")), grantId, version, watermark, "UPDATED");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("grantId", grantId);
            out.put("sequence", sequence);
            out.put("version", version);
            out.put("watermark", watermark);
            out.put("observedAtMs", observedAt);
            out.put("ownerLeaseUntilMs", lease);
            return out;
        });
    }

    // ---- helpers ----

    /** Coarse grants keep only the south-west corner of the published grid cell. */
    static BigDecimal[] snap(Map<String, Object> grant, JSONArray modes, double latitude, double longitude) {
        BigDecimal lat = BigDecimal.valueOf(latitude), lng = BigDecimal.valueOf(longitude);
        if ("COARSE".equals(grant.get("mode"))) {
            JSONObject mode = choice(modes, (String) grant.get("mode_choice_id"));
            if (mode == null) throw new AppGapException(409, "CHOICE_UNAVAILABLE", "共享方式已下线");
            BigDecimal latStep = mode.getBigDecimal("latitudeStep"), lngStep = mode.getBigDecimal("longitudeStep");
            lat = lat.divide(latStep, 0, RoundingMode.FLOOR).multiply(latStep);
            lng = lng.divide(lngStep, 0, RoundingMode.FLOOR).multiply(lngStep);
        }
        return new BigDecimal[]{lat.setScale(8, RoundingMode.HALF_EVEN), lng.setScale(8, RoundingMode.HALF_EVEN)};
    }

    private static JSONObject choice(JSONArray choices, String id) {
        if (choices == null || id == null) return null;
        for (int i = 0; i < choices.size(); i++) {
            JSONObject c = choices.getJSONObject(i);
            if (id.equals(c.getString("id"))) return c;
        }
        return null;
    }

    private Map<String, Object> observation(Map<String, Object> grant, long now, long staleAfter) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM location_share_observation WHERE grant_id = ?", grant.get("grant_id"));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long observed = AppGapSupport.num(row.get("observed_at_ms"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grantId", grant.get("grant_id"));
        out.put("ownerId", AppGapSupport.num(grant.get("owner_id")));
        out.put("recipientId", AppGapSupport.num(grant.get("recipient_id")));
        out.put("sequence", AppGapSupport.num(row.get("sequence")));
        out.put("observedAtMs", observed);
        out.put("datum", "WGS84");
        out.put("mode", grant.get("mode"));
        out.put("latitude", ((BigDecimal) row.get("latitude")).doubleValue());
        out.put("longitude", ((BigDecimal) row.get("longitude")).doubleValue());
        out.put("stale", now - observed > staleAfter || now > AppGapSupport.num(grant.get("owner_lease_until_ms")));
        out.put("version", AppGapSupport.num(grant.get("version")));
        out.put("watermark", AppGapSupport.num(grant.get("watermark")));
        return out;
    }

    private Map<String, Object> grantByKey(long owner, String requestKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM location_share_grant WHERE owner_id = ? AND request_key = ?", owner, requestKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> replay(String requestKey, Map<String, Object> grant, String hash) {
        if (!hash.equals(grant.get("request_hash"))) throw new AppGapException(409, "REQUEST_KEY_REUSED", "请求编号已用于其他共享");
        return receipt(requestKey, grant);
    }

    private Map<String, Object> receipt(String requestKey, Map<String, Object> grant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestKey", requestKey);
        out.put("known", grant != null);
        out.put("state", grant == null ? "NOT_FOUND" : grant.get("status"));
        out.put("requestHash", grant == null ? null : grant.get("request_hash"));
        out.put("grant", grant == null ? null : wireGrant(grant));
        out.put("serverNowMs", clock.getAsLong());
        out.put("watermark", grant == null ? 0 : AppGapSupport.num(grant.get("watermark")));
        return out;
    }

    private static Map<String, Object> wireGrant(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grantId", row.get("grant_id"));
        out.put("requestKey", row.get("request_key"));
        out.put("ownerId", AppGapSupport.num(row.get("owner_id")));
        out.put("recipientId", AppGapSupport.num(row.get("recipient_id")));
        out.put("policyId", row.get("policy_id"));
        out.put("policyVersion", AppGapSupport.num(row.get("policy_version")));
        out.put("policyDigest", row.get("policy_digest"));
        out.put("modeChoiceId", row.get("mode_choice_id"));
        out.put("mode", row.get("mode"));
        out.put("durationChoiceId", row.get("duration_choice_id"));
        out.put("createdAtMs", AppGapSupport.num(row.get("created_at_ms")));
        out.put("expiresAtMs", AppGapSupport.num(row.get("expires_at_ms")));
        out.put("ownerLeaseUntilMs", AppGapSupport.num(row.get("owner_lease_until_ms")));
        out.put("version", AppGapSupport.num(row.get("version")));
        out.put("watermark", AppGapSupport.num(row.get("watermark")));
        out.put("status", row.get("status"));
        out.put("reason", row.get("reason"));
        return out;
    }

    /** Ends a grant, deletes its stored observation and tells the recipient. */
    private void end(Map<String, Object> grant, String status, String reason) {
        String grantId = (String) grant.get("grant_id");
        long version = AppGapSupport.num(grant.get("version")) + 1, watermark = AppGapSupport.num(grant.get("watermark")) + 1;
        int changed = jdbc.update("UPDATE location_share_grant SET status = ?, reason = ?, version = ?, watermark = ? WHERE grant_id = ? AND status = 'ACTIVE'",
                status, reason, version, watermark, grantId);
        if (changed == 1) {
            jdbc.update("DELETE FROM location_share_observation WHERE grant_id = ?", grantId);
            event(AppGapSupport.num(grant.get("recipient_id")), grantId, version, watermark, "INVALIDATED");
        }
    }

    void expireDue() {
        long now = clock.getAsLong();
        for (Map<String, Object> grant : jdbc.queryForList(
                "SELECT * FROM location_share_grant WHERE status = 'ACTIVE' AND expires_at_ms <= ? LIMIT 200", now)) {
            end(grant, "EXPIRED", "DURATION_ENDED");
        }
    }

    private void event(long recipient, String grantId, long version, long watermark, String kind) {
        jdbc.update("INSERT INTO location_share_event(recipient_id, grant_id, version, watermark, kind) VALUES (?,?,?,?,?)",
                recipient, grantId, version, watermark, kind);
    }

    private static long positive(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) throw AppGapException.badRequest(field);
        return Long.parseLong(value);
    }
}
