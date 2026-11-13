package com.chengyinhub.business.citygame;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import com.chengyinhub.business.citygame.CityR491Store.Outcome;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /api/city-game/arrival-evidence/** (CITY_ARRIVAL_EVIDENCE_V1). Arrival is attested by the store, never by GPS:
 * the member asks the server for a challenge at a merchant point (ISSUE, shown once as a display code), marks it
 * presented on site (SUBMIT), and store staff with check-in permission enter the same code to CONFIRM (or REJECT
 * with a reason). A confirmed challenge becomes one evidence record. Evidence has no automatic effect; an onsite
 * task may consume it once (SINGLE_TASK_INSTANCE).
 */
@Service
public class CityArrivalService {
    static final String DOMAIN = "arrival-evidence";
    static final String CONTRACT = "CITY_ARRIVAL_EVIDENCE_V1";
    static final int CHALLENGE_TTL = 600, EVIDENCE_TTL = 7200, MAX_CHALLENGES = 3;
    static final Set<String> STAFF = Set.of("MERCHANT_OWNER", "MERCHANT_MANAGER", "MERCHANT_CHECKIN");

    private final CityR491Store store;
    private final IMerchantAccessService merchants;

    public CityArrivalService(CityR491Store store, IMerchantAccessService merchants) {
        this.store = store;
        this.merchants = merchants;
    }

    static boolean upperUuid(String value) {
        return value != null && value.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}");
    }

    /** Published per-point rule the app checks field by field. */
    static Map<String, Object> policy(Map<String, Object> point) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("domain", "POI");
        ref.put("source", "roam_poi");
        ref.put("id", String.valueOf(((Number) point.get("place_id")).longValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pointId", point.get("point_id"));
        out.put("method", "SERVER_CHALLENGE_MEMBER_SUBMIT_STAFF_CONFIRM");
        out.put("assurance", "MERCHANT_ATTESTED");
        out.put("assuranceReason", "门店店员现场核对到店码，由商家账号确认");
        out.put("evidenceScope", "MEMBER_POINT_TEAM_ROUND");
        out.put("effectsPolicy", "EVIDENCE_ONLY_NO_AUTOMATIC_EFFECTS");
        out.put("renewalPolicy", "NO_REISSUE_AFTER_EVIDENCE");
        out.put("placeRef", ref);
        out.put("challengeTtlSeconds", CHALLENGE_TTL);
        out.put("evidenceTtlSeconds", EVIDENCE_TTL);
        out.put("maxChallenges", MAX_CHALLENGES);
        return out;
    }

    List<Map<String, Object>> merchantPoints(String boardId) {
        return store.jdbc.queryForList("SELECT point_id, title, availability, merchant_id, place_id FROM city_game_point"
                + " WHERE board_id = ? AND del_flag = 0 AND merchant_id IS NOT NULL AND place_id IS NOT NULL ORDER BY point_id", boardId);
    }

    Map<String, Object> point(String boardId, String pointId) {
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT point_id, title, availability, merchant_id, place_id FROM city_game_point"
                + " WHERE board_id = ? AND point_id = ? AND del_flag = 0 AND merchant_id IS NOT NULL AND place_id IS NOT NULL", boardId, pointId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> wireChallenge(Map<String, Object> row, Map<String, Object> board, Map<String, Object> point) {
        long now = store.now();
        String state = (String) row.get("state");
        if (("ISSUED".equals(state) || "SUBMITTED".equals(state)) && CityGameSupport.millis(row.get("expires_at")) <= now) state = "EXPIRED";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("challengeId", row.get("challenge_id"));
        out.put("requestId", row.get("request_id"));
        out.put("pointId", row.get("point_id"));
        out.put("teamId", row.get("team_id"));
        out.put("sessionId", row.get("session_id"));
        out.put("contentHash", board.get("rulesHash"));
        out.put("state", state);
        out.put("expiresAt", CityGameSupport.iso(row.get("expires_at")));
        out.put("regionId", board.get("regionId"));
        out.put("boardId", board.get("boardId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("roundId", board.get("seasonId"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("policyHash", CityR491Store.policyHash(policy(point)));
        out.put("pointTitle", point.get("title"));
        out.put("memberId", ((Number) row.get("member_id")).longValue());
        out.put("merchantId", ((Number) row.get("merchant_id")).longValue());
        out.put("placeId", ((Number) row.get("place_id")).longValue());
        out.put("releaseId", ((Number) board.get("releaseId")).longValue());
        out.put("version", ((Number) row.get("version")).longValue());
        out.put("ordinal", ((Number) row.get("ordinal")).intValue());
        return out;
    }

    Map<String, Object> wireEvidence(Map<String, Object> row, Map<String, Object> board, Map<String, Object> point) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceId", row.get("evidence_id"));
        out.put("challengeId", row.get("challenge_id"));
        out.put("requestId", row.get("request_id"));
        out.put("teamId", row.get("team_id"));
        out.put("sessionId", row.get("session_id"));
        out.put("pointId", row.get("point_id"));
        out.put("contentHash", board.get("rulesHash"));
        out.put("assurance", "MERCHANT_ATTESTED");
        out.put("reason", row.get("reason"));
        out.put("reviewerRole", row.get("reviewer_role"));
        out.put("createdAt", CityGameSupport.iso(row.get("create_time")));
        out.put("expiresAt", CityGameSupport.iso(row.get("expires_at")));
        out.put("timeStatus", CityGameSupport.millis(row.get("expires_at")) > store.now() ? "WITHIN_WINDOW" : "EXPIRED");
        out.put("currentTaskEligibility", "NOT_EVALUATED");
        out.put("effectsStatus", "NOT_EXECUTED");
        out.put("regionId", board.get("regionId"));
        out.put("boardId", board.get("boardId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("roundId", board.get("seasonId"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("policyHash", CityR491Store.policyHash(policy(point)));
        out.put("memberId", ((Number) row.get("member_id")).longValue());
        out.put("merchantId", ((Number) row.get("merchant_id")).longValue());
        out.put("placeId", ((Number) row.get("place_id")).longValue());
        out.put("releaseId", ((Number) board.get("releaseId")).longValue());
        out.put("reviewerMemberId", ((Number) row.get("reviewer_member_id")).longValue());
        return out;
    }

    // ---- player ----

    public Map<String, Object> state(Long memberId, String regionId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        Membership m = store.membership(board, memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        if (m.participation == null || !m.present()) {
            out.put("status", "JOIN_REQUIRED");
            out.put("writeApproved", false);
            out.put("binding", null);
            out.put("points", new ArrayList<>());
            return out;
        }
        String boardId = (String) board.get("boardId");
        List<Map<String, Object>> points = merchantPoints(boardId);
        String status = points.isEmpty() ? "NOT_CONFIGURED" : !m.joined() ? "TEAM_NOT_READY" : "READY";
        boolean write = "READY".equals(status) && CityR491Store.open(board);
        out.put("status", status);
        out.put("scope", "CITY");
        out.put("teamId", m.teamId());
        out.put("sessionId", m.sessionId());
        out.put("effectsStatus", "NOT_EXECUTED");
        out.put("taskEligibility", "NOT_EVALUATED");
        out.put("teamVersion", m.teamVersion());
        out.put("binding", CityR491Store.binding(board));
        out.put("writeApproved", write);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> point : points) {
            String pointId = (String) point.get("point_id");
            List<Map<String, Object>> challenges = store.jdbc.queryForList(
                    "SELECT * FROM city_arrival_challenge WHERE board_id = ? AND point_id = ? AND member_id = ? ORDER BY ordinal DESC", boardId, pointId, memberId);
            List<Map<String, Object>> evidence = store.jdbc.queryForList(
                    "SELECT * FROM city_arrival_evidence WHERE board_id = ? AND point_id = ? AND member_id = ? ORDER BY create_time DESC LIMIT 1", boardId, pointId, memberId);
            Map<String, Object> latest = challenges.isEmpty() ? null : wireChallenge(challenges.get(0), board, point);
            boolean live = latest != null && ("ISSUED".equals(latest.get("state")) || "SUBMITTED".equals(latest.get("state")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pointId", pointId);
            row.put("title", point.get("title"));
            row.put("availability", point.get("availability"));
            row.put("merchantId", ((Number) point.get("merchant_id")).longValue());
            row.put("placeId", ((Number) point.get("place_id")).longValue());
            row.put("rules", policy(point));
            row.put("attemptVersion", (long) challenges.size());
            row.put("canIssue", write && "AVAILABLE".equals(point.get("availability")) && evidence.isEmpty() && !live && challenges.size() < MAX_CHALLENGES);
            row.put("challenge", latest);
            row.put("evidence", evidence.isEmpty() ? null : wireEvidence(evidence.get(0), board, point));
            rows.add(row);
        }
        out.put("points", rows);
        return out;
    }

    // ---- merchant ----

    MerchantAccessContext staff(long memberId) {
        MerchantAccessContext access;
        try {
            access = merchants.require(memberId, MerchantPermission.VERIFY);
        } catch (ServiceException e) {
            throw new CityGameException(403, "NO_STORE_PERMISSION", "没有门店核验权限");
        }
        if (access == null || !STAFF.contains(access.getRoleCode())) throw new CityGameException(403, "NO_STORE_PERMISSION", "没有门店核验权限");
        return access;
    }

    public Map<String, Object> merchant(Long memberId) {
        CityGameSupport.requireMember(memberId);
        MerchantAccessContext access = staff(memberId);
        long now = store.now();
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> row : store.jdbc.queryForList(
                "SELECT c.*, b.lifecycle FROM city_arrival_challenge c JOIN city_game_board b ON b.board_id = c.board_id"
                        + " WHERE c.merchant_id = ? AND c.state = 'SUBMITTED' AND c.expires_at > ? AND c.member_id <> ? ORDER BY c.create_time LIMIT 100",
                access.getMerchantId(), new Date(now), memberId)) {
            Map<String, Object> board = store.mapper.selectBoard((String) row.get("board_id"));
            Map<String, Object> point = point((String) row.get("board_id"), (String) row.get("point_id"));
            if (board == null || point == null) continue;
            pending.add(wireChallenge(row, board, point));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("role", access.getRoleCode());
        out.put("merchantId", access.getMerchantId());
        out.put("ownerMemberId", access.getOwnerMemberId());
        out.put("writeApproved", true);
        out.put("pending", pending);
        return out;
    }

    // ---- commands ----

    public Object command(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String requestId = CityR491Store.str(body, "requestId"), kind = CityR491Store.str(body, "kind");
        Long expected = CityR491Store.num(body, "expectedVersion");
        if (body == null || !upperUuid(requestId) || expected == null || expected < 0 || kind == null) throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
        String hash = CityR491Store.requestHash(body);
        switch (kind) {
            case "ISSUE":
                if (!body.keySet().equals(Set.of("requestId", "kind", "regionId", "teamId", "pointId", "releaseId", "contentHash", "expectedTeamVersion", "expectedVersion"))) {
                    throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
                }
                return store.once(memberId, DOMAIN, requestId, hash, () -> issue(memberId, body, requestId, expected));
            case "SUBMIT":
                if (!body.keySet().equals(Set.of("requestId", "kind", "challengeId", "expectedVersion", "displayCode"))) {
                    throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
                }
                return store.once(memberId, DOMAIN, requestId, hash, () -> submit(memberId, body, requestId, expected));
            case "CONFIRM":
            case "REJECT":
                if (!body.keySet().equals(Set.of("requestId", "kind", "challengeId", "expectedVersion", "displayCode", "reason"))) {
                    throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
                }
                MerchantAccessContext access = staff(memberId);
                return store.once(memberId, DOMAIN, requestId, hash, () -> review(memberId, access, body, requestId, expected, "CONFIRM".equals(kind)));
            default:
                throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
        }
    }

    private Outcome issue(long memberId, Map<String, Object> body, String requestId, long expected) {
        String regionId = CityR491Store.str(body, "regionId"), teamId = CityR491Store.str(body, "teamId"), pointId = CityR491Store.str(body, "pointId");
        Long releaseId = CityR491Store.num(body, "releaseId"), teamVersion = CityR491Store.num(body, "expectedTeamVersion");
        if (!CityGameRules.uuid(teamId) || !CityGameRules.id(pointId) || teamVersion == null) throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
        Map<String, Object> board = store.board(regionId);
        if (!CityR491Store.sameRelease(board, releaseId, CityR491Store.str(body, "contentHash"))) return CityR491Store.refused(409, "CITY_RELEASE_CHANGED", requestId);
        if (!CityR491Store.open(board)) return CityR491Store.refused(409, "BOARD_NOT_OPEN", requestId);
        Membership m = store.membership(board, memberId);
        if (!m.joined() || !teamId.equals(m.teamId())) return CityR491Store.refused(409, "NOT_JOINED", requestId);
        if (m.teamVersion() != teamVersion) return CityR491Store.refused(409, "TEAM_VERSION_CHANGED", requestId);
        String boardId = (String) board.get("boardId");
        Map<String, Object> point = point(boardId, pointId);
        if (point == null) return CityR491Store.refused(404, "POINT_NOT_FOUND", requestId);
        if (!"AVAILABLE".equals(point.get("availability"))) return CityR491Store.refused(409, "POINT_UNAVAILABLE", requestId);
        // Serialise issuance for this member and point.
        store.jdbc.queryForList("SELECT point_id FROM city_game_point WHERE board_id = ? AND point_id = ? FOR UPDATE", boardId, pointId);
        List<Map<String, Object>> challenges = store.jdbc.queryForList(
                "SELECT * FROM city_arrival_challenge WHERE board_id = ? AND point_id = ? AND member_id = ? ORDER BY ordinal DESC", boardId, pointId, memberId);
        if (challenges.size() != expected) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
        if (challenges.size() >= MAX_CHALLENGES) return CityR491Store.refused(409, "CHALLENGE_LIMIT", requestId);
        Integer evidence = store.jdbc.queryForObject("SELECT COUNT(*) FROM city_arrival_evidence WHERE board_id = ? AND point_id = ? AND member_id = ?",
                Integer.class, boardId, pointId, memberId);
        if (evidence != null && evidence > 0) return CityR491Store.refused(409, "EVIDENCE_ALREADY_RECORDED", requestId);
        if (!challenges.isEmpty() && "ISSUED,SUBMITTED".contains((String) challenges.get(0).get("state"))
                && CityGameSupport.millis(challenges.get(0).get("expires_at")) > store.now()) {
            return CityR491Store.refused(409, "CHALLENGE_ACTIVE", requestId);
        }
        String challengeId = UUID.randomUUID().toString();
        String code = com.chengyinhub.business.appgaps.AppGapSupport.randomHex(16).toUpperCase();
        long now = store.now();
        store.jdbc.update("INSERT INTO city_arrival_challenge(challenge_id, request_id, board_id, point_id, member_id, team_id, session_id, merchant_id, place_id,"
                        + " ordinal, display_code, state, version, expires_at, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,'ISSUED',1,?,?)",
                challengeId, requestId, boardId, pointId, memberId, teamId, m.sessionId(), point.get("merchant_id"), point.get("place_id"),
                challenges.size() + 1, code, new Date(now + CHALLENGE_TTL * 1000L), new Date(now));
        Map<String, Object> data = receiptBody(requestId);
        data.put("challenge", wireChallenge(row(challengeId), board, point));
        data.put("displayCode", code);
        return CityR491Store.ok(data);
    }

    private Map<String, Object> row(String challengeId) {
        return store.jdbc.queryForMap("SELECT * FROM city_arrival_challenge WHERE challenge_id = ?", challengeId);
    }

    private Outcome submit(long memberId, Map<String, Object> body, String requestId, long expected) {
        String challengeId = CityR491Store.str(body, "challengeId"), code = CityR491Store.str(body, "displayCode");
        if (!CityGameRules.uuid(challengeId) || code == null || !code.matches("[A-F0-9]{32}")) throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "到店指令无效");
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_arrival_challenge WHERE challenge_id = ? FOR UPDATE", challengeId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("member_id")).longValue() != memberId) return CityR491Store.refused(404, "CHALLENGE_NOT_FOUND", requestId);
        Map<String, Object> c = rows.get(0);
        Map<String, Object> board = store.mapper.selectBoard((String) c.get("board_id"));
        Map<String, Object> point = point((String) c.get("board_id"), (String) c.get("point_id"));
        if (!CityR491Store.open(board)) return CityR491Store.refused(409, "BOARD_NOT_OPEN", requestId);
        if (((Number) c.get("version")).longValue() != expected) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
        if (CityGameSupport.millis(c.get("expires_at")) <= store.now()) return CityR491Store.refused(409, "CHALLENGE_EXPIRED", requestId);
        if (!"ISSUED".equals(c.get("state"))) return CityR491Store.refused(409, "INVALID_STATE", requestId);
        if (!code.equals(c.get("display_code"))) return CityR491Store.refused(409, "CODE_MISMATCH", requestId);
        store.jdbc.update("UPDATE city_arrival_challenge SET state = 'SUBMITTED', version = version + 1 WHERE challenge_id = ?", challengeId);
        Map<String, Object> data = receiptBody(requestId);
        data.put("challenge", wireChallenge(row(challengeId), board, point));
        return CityR491Store.ok(data);
    }

    private Outcome review(long memberId, MerchantAccessContext access, Map<String, Object> body, String requestId, long expected, boolean confirm) {
        String challengeId = CityR491Store.str(body, "challengeId"), code = CityR491Store.str(body, "displayCode"), reason = CityR491Store.str(body, "reason");
        if (!CityGameRules.uuid(challengeId) || code == null || (confirm ? !code.matches("[A-F0-9]{32}") : !code.isEmpty())
                || reason == null || reason.trim().isEmpty() || reason.length() > 500) {
            throw new CityGameException(400, "CITY_ARRIVAL_INVALID", "核验指令无效");
        }
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_arrival_challenge WHERE challenge_id = ? FOR UPDATE", challengeId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("merchant_id")).longValue() != access.getMerchantId()) {
            return CityR491Store.refused(404, "CHALLENGE_NOT_FOUND", requestId);
        }
        Map<String, Object> c = rows.get(0);
        if (((Number) c.get("member_id")).longValue() == memberId) return CityR491Store.refused(403, "SELF_REVIEW_FORBIDDEN", requestId);
        Map<String, Object> board = store.mapper.selectBoard((String) c.get("board_id"));
        Map<String, Object> point = point((String) c.get("board_id"), (String) c.get("point_id"));
        if (((Number) c.get("version")).longValue() != expected) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
        if (CityGameSupport.millis(c.get("expires_at")) <= store.now()) return CityR491Store.refused(409, "CHALLENGE_EXPIRED", requestId);
        if (!"SUBMITTED".equals(c.get("state"))) return CityR491Store.refused(409, "INVALID_STATE", requestId);
        if (confirm && !code.equals(c.get("display_code"))) return CityR491Store.refused(409, "CODE_MISMATCH", requestId);
        store.jdbc.update("UPDATE city_arrival_challenge SET state = ?, version = version + 1 WHERE challenge_id = ?", confirm ? "CONFIRMED" : "REJECTED", challengeId);
        Map<String, Object> data = receiptBody(requestId);
        data.put("challenge", wireChallenge(row(challengeId), board, point));
        if (confirm) {
            String evidenceId = UUID.randomUUID().toString();
            long now = store.now();
            store.jdbc.update("INSERT INTO city_arrival_evidence(evidence_id, challenge_id, request_id, board_id, point_id, member_id, team_id, session_id, merchant_id,"
                            + " place_id, reviewer_member_id, reviewer_role, reason, create_time, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    evidenceId, challengeId, c.get("request_id"), c.get("board_id"), c.get("point_id"), c.get("member_id"), c.get("team_id"),
                    c.get("session_id"), c.get("merchant_id"), c.get("place_id"), memberId, access.getRoleCode(), reason.trim(),
                    new Date(now), new Date(now + EVIDENCE_TTL * 1000L));
            data.put("evidence", wireEvidence(store.jdbc.queryForMap("SELECT * FROM city_arrival_evidence WHERE evidence_id = ?", evidenceId), board, point));
        }
        return CityR491Store.ok(data);
    }

    static Map<String, Object> receiptBody(String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("requestStatus", "COMPLETE");
        return data;
    }

    public Object receipt(Long memberId, String requestId) {
        CityGameSupport.requireMember(memberId);
        CityGameSupport.requireId(requestId, "requestId");
        return store.receipt(memberId, DOMAIN, requestId);
    }

    /** GET /evidence/{id}: the member it is about, or staff of the attesting merchant. */
    public Map<String, Object> evidence(Long memberId, String evidenceId) {
        CityGameSupport.requireMember(memberId);
        if (!CityGameRules.uuid(evidenceId)) throw new CityGameException(404, "EVIDENCE_NOT_FOUND", "到店凭证不存在");
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_arrival_evidence WHERE evidence_id = ?", evidenceId);
        if (rows.isEmpty()) throw new CityGameException(404, "EVIDENCE_NOT_FOUND", "到店凭证不存在");
        Map<String, Object> e = rows.get(0);
        if (((Number) e.get("member_id")).longValue() != memberId) {
            MerchantAccessContext access;
            try {
                access = merchants.require(memberId, MerchantPermission.VERIFY);
            } catch (ServiceException denied) {
                access = null;
            }
            if (access == null || access.getMerchantId() == null || access.getMerchantId() != ((Number) e.get("merchant_id")).longValue()) {
                throw new CityGameException(404, "EVIDENCE_NOT_FOUND", "到店凭证不存在");
            }
        }
        Map<String, Object> board = store.mapper.selectBoard((String) e.get("board_id"));
        Map<String, Object> point = point((String) e.get("board_id"), (String) e.get("point_id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("currentTaskEligibility", "NOT_EVALUATED");
        out.put("effectsStatus", "NOT_EXECUTED");
        out.put("evidence", wireEvidence(e, board, point));
        return out;
    }
}
