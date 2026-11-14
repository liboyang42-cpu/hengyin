package com.chengyinhub.business.begapmisc.home;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Private player home ({@code GET/PUT/DELETE /api/native/home}, app {@code Core/PrivateHomeService} and
 * {@code PrivateHomeContracts}). Owner = the authenticated account only; no body field selects it. The point is
 * WGS84 with at most six decimals, the label at most 40 UTF-16 units without control or surrogate characters.
 *
 * <p>Writes carry {@code requestId} and {@code expectedVersion}. Decisions: {@code SAVED} / {@code DELETED} (version + 1),
 * {@code VERSION_CONFLICT} (the current version, never the expected one), {@code HOME_CHANGE_COOLDOWN} (a new point within
 * the change cooldown of the last save; version unchanged). Every decision is stored per (owner, requestId): the same id
 * with the same body replays the stored receipt, with another body it is 409 {@code REQUEST_CONFLICT}. The home point is
 * never logged and never leaves this owner-scoped table; the map, NPC and merchant code never read it.
 */
@Service
public class NativeHomeService {
    static final String REQUEST_ID = "[A-Za-z0-9._:-]{1,64}";
    static final Set<String> DELETE_KEYS = Set.of("requestId", "expectedVersion");
    static final Set<String> PUT_KEYS = Set.of("requestId", "expectedVersion", "label", "latitude", "longitude", "datum");
    private final JdbcTemplate jdbc;
    private final NativeHomePolicy policy;
    Clock clock = Clock.systemUTC();

    public NativeHomeService(JdbcTemplate jdbc, NativeHomePolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(long memberId) {
        requireEnabled();
        return view(home(memberId, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> set(long memberId, String raw) {
        requireEnabled();
        Map<String, Object> body = HomeWire.object(raw, PUT_KEYS, Set.of(), "HOME_REQUEST_INVALID");
        String requestId = HomeWire.text(body, "requestId", REQUEST_ID, "HOME_REQUEST_INVALID");
        long expected = HomeWire.version(body, "expectedVersion", "HOME_REQUEST_INVALID");
        Object label = body.get("label");
        if (!(label instanceof String) || !HomeWire.validLabel((String) label) || !"WGS84".equals(body.get("datum"))) {
            throw new HomeRejection(400, "HOME_REQUEST_INVALID");
        }
        BigDecimal latitude = HomeWire.coordinate(body, "latitude", 90, "HOME_REQUEST_INVALID");
        BigDecimal longitude = HomeWire.coordinate(body, "longitude", 180, "HOME_REQUEST_INVALID");
        String digest = HomeWire.digest(body);
        Map<String, Object> home = home(memberId, true);
        Map<String, Object> replay = replay(memberId, requestId, "PUT", digest);
        if (replay != null) return replay;
        long now = clock.millis();
        long version = ((Number) home.get("version")).longValue();
        Object lastSet = home.get("last_set_at");
        String decision;
        if (version != expected) decision = "VERSION_CONFLICT";
        else if (lastSet != null && now < ((Number) lastSet).longValue() + policy.changeCooldownSeconds * 1000) decision = "HOME_CHANGE_COOLDOWN";
        else decision = "SAVED";
        if ("SAVED".equals(decision)) {
            version = Math.addExact(version, 1);
            jdbc.update("UPDATE native_private_home SET version = ?, status = 'ACTIVE', label = ?, latitude = ?, longitude = ?,"
                    + " datum = 'WGS84', changed_at = ?, effective_at = ?, last_set_at = ?, updated_at = ? WHERE member_id = ?",
                    version, label, latitude, longitude, now, now + policy.activationDelaySeconds * 1000, now, now, memberId);
        }
        return record(memberId, requestId, "PUT", digest, decision, version, now);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(long memberId, String raw) {
        requireEnabled();
        Map<String, Object> body = HomeWire.object(raw, DELETE_KEYS, Set.of(), "HOME_REQUEST_INVALID");
        String requestId = HomeWire.text(body, "requestId", REQUEST_ID, "HOME_REQUEST_INVALID");
        long expected = HomeWire.version(body, "expectedVersion", "HOME_REQUEST_INVALID");
        String digest = HomeWire.digest(body);
        Map<String, Object> home = home(memberId, true);
        Map<String, Object> replay = replay(memberId, requestId, "DELETE", digest);
        if (replay != null) return replay;
        long now = clock.millis();
        long version = ((Number) home.get("version")).longValue();
        String decision = version != expected ? "VERSION_CONFLICT" : "DELETED";
        if ("DELETED".equals(decision)) {
            version = Math.addExact(version, 1);
            // Deleting clears every stored coordinate; the separate last_set_at cooldown fence stays.
            jdbc.update("UPDATE native_private_home SET version = ?, status = 'DELETED', label = NULL, latitude = NULL,"
                    + " longitude = NULL, datum = NULL, changed_at = NULL, effective_at = NULL, updated_at = ? WHERE member_id = ?",
                    version, now, memberId);
        }
        return record(memberId, requestId, "DELETE", digest, decision, version, now);
    }

    /** The owner's home row; {@code lock} creates the empty row and locks it (one writer per owner). */
    Map<String, Object> home(long memberId, boolean lock) {
        if (lock) {
            jdbc.update("INSERT IGNORE INTO native_private_home (member_id, version, status, updated_at) VALUES (?, 0, 'DELETED', ?)",
                    memberId, clock.millis());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, status, label, latitude, longitude, datum,"
                + " changed_at, effective_at, last_set_at FROM native_private_home WHERE member_id = ?" + (lock ? " FOR UPDATE" : ""),
                memberId);
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("version", 0L);
            empty.put("status", "DELETED");
            return empty;
        }
        return rows.get(0);
    }

    static Map<String, Object> view(Map<String, Object> home) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", ((Number) home.get("version")).longValue());
        boolean active = "ACTIVE".equals(home.get("status"));
        view.put("status", active ? "ACTIVE" : "DELETED");
        view.put("changedAt", active ? home.get("changed_at") : null);
        view.put("effectiveAt", active ? home.get("effective_at") : null);
        view.put("label", active ? home.get("label") : null);
        view.put("latitude", active ? plain(home.get("latitude")) : null);
        view.put("longitude", active ? plain(home.get("longitude")) : null);
        view.put("datum", active ? home.get("datum") : null);
        return view;
    }

    private static BigDecimal plain(Object value) {
        BigDecimal n = (BigDecimal) value;
        n = n.signum() == 0 ? BigDecimal.ZERO : n.stripTrailingZeros();
        return n.scale() < 0 ? n.setScale(0) : n;
    }

    private Map<String, Object> replay(long memberId, String requestId, String operation, String digest) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT operation, payload_digest, decision, result_version"
                + " FROM native_private_home_mutation WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (!operation.equals(row.get("operation")) || !digest.equals(row.get("payload_digest"))) {
            throw new HomeRejection(409, "HOME_REQUEST_CONFLICT");
        }
        return receipt(requestId, (String) row.get("decision"), ((Number) row.get("result_version")).longValue());
    }

    private Map<String, Object> record(long memberId, String requestId, String operation, String digest, String decision,
                                       long version, long now) {
        jdbc.update("INSERT INTO native_private_home_mutation (member_id, request_id, operation, payload_digest, decision,"
                + " result_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", memberId, requestId, operation, digest, decision, version, now);
        return receipt(requestId, decision, version);
    }

    private static Map<String, Object> receipt(String requestId, String decision, long version) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("requestId", requestId);
        receipt.put("decision", decision);
        receipt.put("version", version);
        return receipt;
    }

    private void requireEnabled() {
        if (!policy.homeEnabled()) throw new HomeRejection(403, "PRIVATE_HOME_NOT_APPROVED");
    }
}
