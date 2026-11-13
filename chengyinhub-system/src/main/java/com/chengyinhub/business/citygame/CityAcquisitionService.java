package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import com.chengyinhub.business.citygame.CityR491Store.Outcome;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /api/city-game/acquisition/** (CITY_ACQUISITION_V1): self-funded occupation of an unowned point by the member who
 * passed that point's onsite task with store-attested arrival. QUOTE prices it from the published board
 * (acquire_points, acquisition_cost, holding_limit, term_seconds) without effects; CONFIRM re-checks every quoted
 * term, charges the payer's member points and the team's action budget, makes the team the holder of the point's
 * property on the board engine (one ownership record for every route) and records a title. Titles never transfer
 * and carry no service fees in v1.
 */
@Service
public class CityAcquisitionService {
    static final String DOMAIN = "acquisition";
    static final String CONTRACT = "CITY_ACQUISITION_V1";
    static final String MODE = "TASK_ACTOR_SELF_PAY_FOR_ORIGINAL_TEAM_UNOWNED_ONLY";

    private final CityR491Store store;
    private final CityGameActionService engine;
    private final CityActionBudgetService budget;

    public CityAcquisitionService(CityR491Store store, CityGameActionService engine, CityActionBudgetService budget) {
        this.store = store;
        this.engine = engine;
        this.budget = budget;
    }

    private static String boardId(Map<String, Object> board) { return (String) board.get("boardId"); }

    static Map<String, Object> policy(Map<String, Object> board) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", MODE);
        out.put("releasePolicy", "CURRENT_FROZEN_RELEASE_ONLY");
        out.put("termPolicy", "RETAIN_TITLE_PENDING_REVIEW");
        out.put("pausePolicy", "FREEZE_TERM_REVIEW_BEFORE_RESUME");
        out.put("rewardPolicy", "NONE");
        out.put("pointCost", board.get("acquirePoints"));
        out.put("actionCost", board.get("acquisitionCost"));
        out.put("maxHoldingsPerTeam", board.get("holdingLimit"));
        out.put("termSeconds", board.get("termSeconds"));
        return out;
    }

    /** The property standing for a point on the board engine, if any. */
    Map<String, Object> property(String boardId, String pointId, boolean lock) {
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT property_id FROM city_game_property WHERE board_id = ? AND point_id = ? AND role = 'OPERABLE' LIMIT 1",
                boardId, pointId);
        if (rows.isEmpty()) return null;
        String propertyId = (String) rows.get(0).get("property_id");
        return lock ? store.mapper.selectPropertyForUpdate(boardId, propertyId)
                : store.mapper.selectProperties(boardId).stream().filter(p -> propertyId.equals(p.get("propertyId"))).findFirst().orElse(null);
    }

    private String eligibility(Map<String, Object> board, Membership m, Map<String, Object> inst, Map<String, Object> evidence, long memberId) {
        if (!CityR491Store.open(board)) return "BOARD_NOT_OPEN";
        if (board.get("holdingLimit") == null) return "NOT_CONFIGURED";
        if (!"PASSED".equals(inst.get("status"))) return "TASK_NOT_PASSED";
        if (evidence == null || CityGameSupport.millis(evidence.get("expires_at")) <= store.now()) return "EVIDENCE_EXPIRED";
        String pointId = (String) evidence.get("point_id");
        Map<String, Object> property = property(boardId(board), pointId, false);
        if (property == null) return "POINT_NOT_ACQUIRABLE";
        if (property.get("holderTeamId") != null) return "POINT_HELD";
        if (!"AVAILABLE".equals(CityGameActionService.effectiveStatus(property, store.now()))) return "POINT_UNAVAILABLE";
        if (store.mapper.countHeld(boardId(board), m.teamId()) >= ((Number) board.get("holdingLimit")).intValue()) return "HOLDING_LIMIT";
        Long points = store.mapper.selectMemberPoints(memberId);
        if (points == null || points < ((Number) board.get("acquirePoints")).longValue()) return "INSUFFICIENT_POINTS";
        Map<String, Object> round = store.mapper.selectOpenRound(boardId(board), m.teamId());
        if (round == null || ((Number) round.get("actionsRemaining")).intValue() < ((Number) board.get("acquisitionCost")).intValue()) return "ACTION_BUDGET_EXHAUSTED";
        return "QUOTE_AVAILABLE";
    }

    private List<Map<String, Object>> passedInstances(String boardId, long memberId) {
        return store.jdbc.queryForList("SELECT i.*, e.point_id AS evidence_point FROM city_task_instance i LEFT JOIN city_arrival_evidence e ON e.evidence_id = i.evidence_id"
                + " WHERE i.board_id = ? AND i.member_id = ? AND i.access = 'ONSITE_ZERO_EFFECT' AND i.status = 'PASSED' ORDER BY i.accepted_at DESC LIMIT 50", boardId, memberId);
    }

    private Map<String, Object> evidenceOf(Map<String, Object> inst) {
        if (inst.get("evidence_id") == null) return null;
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_arrival_evidence WHERE evidence_id = ?", inst.get("evidence_id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> state(Long memberId, String regionId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = engine.freezeIfDue(store.board(regionId));
        Membership m = store.membership(board, memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("regionId", regionId);
        out.put("memberId", memberId);
        out.put("ownTitles", titles(board, memberId, true));
        if (!m.joined()) {
            out.put("status", "JOIN_REQUIRED");
            out.put("writeApproved", false);
            out.put("binding", null);
            out.put("teamId", null);
            out.put("candidates", new ArrayList<>());
            out.put("ownership", new ArrayList<>());
            return out;
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> titled = new java.util.HashSet<>(store.jdbc.queryForList("SELECT point_id FROM city_acquisition_title WHERE board_id = ?", String.class, boardId(board)));
        for (Map<String, Object> inst : passedInstances(boardId(board), memberId)) {
            Map<String, Object> evidence = evidenceOf(inst);
            if (evidence == null || titled.contains(evidence.get("point_id"))) continue;
            String eligibility = eligibility(board, m, inst, evidence, memberId);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("instanceId", inst.get("instance_id"));
            c.put("taskId", inst.get("task_id"));
            c.put("pointId", evidence.get("point_id"));
            c.put("eligibility", eligibility);
            boolean quotable = "QUOTE_AVAILABLE".equals(eligibility);
            c.put("pointCost", quotable ? ((Number) board.get("acquirePoints")).intValue() : null);
            c.put("actionCost", quotable ? ((Number) board.get("acquisitionCost")).intValue() : null);
            c.put("maxHoldingsPerTeam", quotable ? ((Number) board.get("holdingLimit")).intValue() : null);
            c.put("termSeconds", quotable ? ((Number) board.get("termSeconds")).intValue() : null);
            c.put("evidenceAcceptance", quotable ? "MERCHANT_ATTESTED" : null);
            candidates.add(c);
        }
        out.put("status", "AVAILABLE");
        out.put("writeApproved", CityR491Store.open(board));
        out.put("binding", CityR491Store.binding(board));
        out.put("teamId", m.teamId());
        out.put("candidates", candidates);
        out.put("ownership", titles(board, memberId, false));
        return out;
    }

    /** personal = the caller's own titles with payment detail; otherwise every title on the board without it. */
    List<Map<String, Object>> titles(Map<String, Object> board, long memberId, boolean personal) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = personal ? "SELECT title_json FROM city_acquisition_title WHERE board_id = ? AND payer_member_id = ? ORDER BY create_time"
                : "SELECT title_json FROM city_acquisition_title WHERE board_id = ? ORDER BY point_id";
        List<String> rows = personal ? store.jdbc.queryForList(sql, String.class, boardId(board), memberId) : store.jdbc.queryForList(sql, String.class, boardId(board));
        for (String json : rows) {
            Map<String, Object> title = JSON.parseObject(json);
            if (!personal) {
                for (String key : new String[]{"payerMemberId", "pointsBefore", "pointsAfter", "budgetVersion", "requestId", "quoteId", "sessionId", "roundId",
                        "taskResultId", "evidenceId", "pointsBusinessKey", "policyHash", "pointCost", "actionCost", "actionsBefore", "actionsAfter", "termSeconds"}) {
                    title.put(key, null);
                }
            }
            out.add(title);
        }
        return out;
    }

    public Map<String, Object> titlesRead(Long memberId, String regionId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("regionId", regionId);
        out.put("memberId", memberId);
        out.put("titles", titles(board, memberId, true));
        return out;
    }

    public Map<String, Object> quoteRead(Long memberId, String quoteId) {
        CityGameSupport.requireMember(memberId);
        if (!CityGameRules.uuid(quoteId)) throw new CityGameException(404, "QUOTE_NOT_FOUND", "报价不存在");
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_acquisition_quote WHERE quote_id = ? AND member_id = ?", quoteId, memberId);
        if (rows.isEmpty()) throw new CityGameException(404, "QUOTE_NOT_FOUND", "报价不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("quote", live(rows.get(0)));
        return out;
    }

    private Map<String, Object> live(Map<String, Object> row) {
        Map<String, Object> quote = JSON.parseObject(String.valueOf(row.get("quote_json")));
        boolean expired = CityGameSupport.millis(row.get("expires_at")) <= store.now() || ((Number) row.get("consumed")).intValue() == 1;
        quote.put("expired", expired);
        Map<String, Object> board = store.mapper.selectBoard((String) row.get("board_id"));
        quote.put("writeApproved", !expired && board != null && CityR491Store.open(board));
        return quote;
    }

    // ---- commands ----

    public Object command(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String requestId = CityR491Store.str(body, "requestId"), kind = CityR491Store.str(body, "kind");
        if (body == null || !CityArrivalService.upperUuid(requestId) || !CityGameRules.id(CityR491Store.str(body, "regionId"))) {
            throw new CityGameException(400, "CITY_ACQUISITION_INVALID", "占领指令无效");
        }
        if ("QUOTE".equals(kind) && body.keySet().equals(Set.of("requestId", "kind", "regionId", "instanceId")) && CityGameRules.uuid(CityR491Store.str(body, "instanceId"))) {
            return store.once(memberId, DOMAIN, requestId, CityR491Store.requestHash(body), () -> quote(memberId, body, requestId));
        }
        if ("CONFIRM".equals(kind) && body.keySet().equals(Set.of("requestId", "kind", "regionId", "quoteId", "quoteHash", "confirmed"))
                && Boolean.TRUE.equals(body.get("confirmed")) && CityGameRules.uuid(CityR491Store.str(body, "quoteId")) && CityGameRules.hash(CityR491Store.str(body, "quoteHash"))) {
            return store.once(memberId, DOMAIN, requestId, CityR491Store.requestHash(body), () -> confirm(memberId, body, requestId));
        }
        throw new CityGameException(400, "CITY_ACQUISITION_INVALID", "占领指令无效");
    }

    private Outcome quote(long memberId, Map<String, Object> body, String requestId) {
        Map<String, Object> board = engine.freezeIfDue(store.board(CityR491Store.str(body, "regionId")));
        Membership m = store.membership(board, memberId);
        if (!m.joined()) return CityR491Store.refused(409, "NOT_JOINED", requestId);
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_task_instance WHERE instance_id = ? AND member_id = ? AND board_id = ?"
                + " AND access = 'ONSITE_ZERO_EFFECT'", body.get("instanceId"), memberId, boardId(board));
        if (rows.isEmpty()) return CityR491Store.refused(404, "TASK_INSTANCE_NOT_FOUND", requestId);
        Map<String, Object> inst = rows.get(0);
        if (!m.teamId().equals(inst.get("team_id"))) return CityR491Store.refused(409, "NOT_ORIGINAL_TEAM", requestId);
        Map<String, Object> evidence = evidenceOf(inst);
        String eligibility = eligibility(board, m, inst, evidence, memberId);
        if (!"QUOTE_AVAILABLE".equals(eligibility)) return CityR491Store.refused(409, eligibility, requestId);
        String pointId = (String) evidence.get("point_id");
        Integer titled = store.jdbc.queryForObject("SELECT COUNT(*) FROM city_acquisition_title WHERE board_id = ? AND point_id = ?", Integer.class, boardId(board), pointId);
        if (titled != null && titled > 0) return CityR491Store.refused(409, "POINT_HELD", requestId);
        Map<String, Object> point = store.jdbc.queryForMap("SELECT * FROM city_game_point WHERE board_id = ? AND point_id = ?", boardId(board), pointId);
        Map<String, Object> property = property(boardId(board), pointId, false);
        Map<String, Object> round = store.mapper.selectOpenRound(boardId(board), m.teamId());
        int total = ((Number) round.get("actionsTotal")).intValue(), remaining = ((Number) round.get("actionsRemaining")).intValue();
        int[] c = budget.counters(board, m.teamId(), ((Number) round.get("roundSeq")).longValue(), total, remaining);
        Map<String, Object> result = JSON.parseObject(String.valueOf(inst.get("result_json")));
        long now = store.now();
        long expires = now + 1000L * ((Number) board.get("quoteValidSeconds")).intValue();
        String quoteId = UUID.randomUUID().toString();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("quoteId", quoteId);
        q.put("confirmRequestId", UUID.randomUUID().toString().toUpperCase());
        q.put("beneficiaryTeamId", m.teamId());
        q.put("sessionId", m.sessionId());
        q.put("regionId", board.get("regionId"));
        q.put("boardId", board.get("boardId"));
        q.put("seasonId", board.get("seasonId"));
        q.put("roundId", board.get("seasonId"));
        q.put("pointId", pointId);
        q.put("contentHash", board.get("rulesHash"));
        q.put("policyHash", CityR491Store.policyHash(policy(board)));
        q.put("taskInstanceId", inst.get("instance_id"));
        q.put("taskResultId", result.get("resultId"));
        q.put("evidenceId", evidence.get("evidence_id"));
        q.put("evidenceAssurance", "MERCHANT_ATTESTED");
        q.put("evidenceAcceptanceReason", "到店任务完成且到店凭证由门店确认");
        q.put("createdAt", CityGameSupport.iso(new Date(now)));
        q.put("expiresAt", CityGameSupport.iso(new Date(expires)));
        q.put("mode", MODE);
        q.put("releasePolicy", "CURRENT_FROZEN_RELEASE_ONLY");
        q.put("termPolicy", "RETAIN_TITLE_PENDING_REVIEW");
        q.put("pausePolicy", "FREEZE_TERM_REVIEW_BEFORE_RESUME");
        q.put("rewardPolicy", "NONE");
        q.put("payerMemberId", memberId);
        q.put("releaseId", ((Number) board.get("releaseId")).longValue());
        q.put("merchantId", ((Number) point.get("merchant_id")).longValue());
        q.put("placeId", ((Number) point.get("place_id")).longValue());
        q.put("teamVersion", m.teamVersion());
        q.put("budgetVersion", (long) (c[2] + c[3]));
        q.put("pointVersion", ((Number) property.get("stateVersion")).longValue());
        q.put("merchantVersion", ((Number) point.get("merchant_version")).longValue());
        q.put("platformVersion", ((Number) point.get("platform_version")).longValue());
        q.put("pointsBalanceAtQuote", store.mapper.selectMemberPoints(memberId));
        q.put("pointCost", ((Number) board.get("acquirePoints")).intValue());
        q.put("actionCost", ((Number) board.get("acquisitionCost")).intValue());
        q.put("maxHoldingsPerTeam", ((Number) board.get("holdingLimit")).intValue());
        q.put("termSeconds", ((Number) board.get("termSeconds")).intValue());
        q.put("actionsRemainingAtQuote", remaining);
        String quoteHash = CityGameRules.sha256(com.chengyinhub.business.appgaps.AppGapSupport.canonical(q));
        q.put("quoteHash", quoteHash);
        store.jdbc.update("INSERT INTO city_acquisition_quote(quote_id, confirm_request_id, request_id, member_id, board_id, quote_json, quote_hash, expires_at)"
                + " VALUES (?,?,?,?,?,?,?,?)", quoteId, q.get("confirmRequestId"), requestId, memberId, boardId(board), JSON.toJSONString(q), quoteHash, new Date(expires));
        Map<String, Object> data = CityArrivalService.receiptBody(requestId);
        data.put("contract", CONTRACT);
        data.put("effectsStatus", "NOT_EXECUTED");
        Map<String, Object> wire = new LinkedHashMap<>(q);
        wire.put("writeApproved", true);
        wire.put("expired", false);
        data.put("quote", wire);
        return CityR491Store.ok(data);
    }

    private Outcome confirm(long memberId, Map<String, Object> body, String requestId) {
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_acquisition_quote WHERE quote_id = ? AND member_id = ? FOR UPDATE",
                body.get("quoteId"), memberId);
        if (rows.isEmpty()) return CityR491Store.refused(404, "QUOTE_NOT_FOUND", requestId);
        Map<String, Object> row = rows.get(0);
        Map<String, Object> q = JSON.parseObject(String.valueOf(row.get("quote_json")));
        if (!requestId.equals(row.get("confirm_request_id")) || !body.get("quoteHash").equals(row.get("quote_hash"))) {
            return CityR491Store.refused(409, "QUOTE_MISMATCH", requestId);
        }
        if (((Number) row.get("consumed")).intValue() == 1) return CityR491Store.refused(409, "QUOTE_USED", requestId);
        if (CityGameSupport.millis(row.get("expires_at")) <= store.now()) return CityR491Store.refused(409, "QUOTE_EXPIRED", requestId);
        String boardId = (String) row.get("board_id");
        Map<String, Object> board = engine.freezeIfDue(store.mapper.selectBoardForUpdate(boardId));
        if (!CityR491Store.open(board)) return CityR491Store.refused(409, "BOARD_NOT_OPEN", requestId);
        if (!board.get("rulesHash").equals(q.get("contentHash")) || !CityR491Store.policyHash(policy(board)).equals(q.get("policyHash"))) {
            return CityR491Store.refused(409, "QUOTE_CHANGED", requestId);
        }
        Membership m = store.membership(board, memberId);
        String teamId = (String) q.get("beneficiaryTeamId");
        if (!m.joined() || !teamId.equals(m.teamId())) return CityR491Store.refused(409, "NOT_ORIGINAL_TEAM", requestId);
        Map<String, Object> team = store.mapper.selectTeamForUpdate(teamId);
        if (((Number) team.get("version")).longValue() != ((Number) q.get("teamVersion")).longValue()) return CityR491Store.refused(409, "QUOTE_CHANGED", requestId);
        String pointId = (String) q.get("pointId");
        Map<String, Object> property = property(boardId, pointId, true);
        long now = store.now();
        if (property == null || property.get("holderTeamId") != null || !"AVAILABLE".equals(CityGameActionService.effectiveStatus(property, now))) {
            return CityR491Store.refused(409, "POINT_HELD", requestId);
        }
        if (((Number) property.get("stateVersion")).longValue() != ((Number) q.get("pointVersion")).longValue()) return CityR491Store.refused(409, "QUOTE_CHANGED", requestId);
        if (store.mapper.countHeld(boardId, teamId) >= ((Number) board.get("holdingLimit")).intValue()) return CityR491Store.refused(409, "HOLDING_LIMIT", requestId);
        List<Map<String, Object>> evidence = store.jdbc.queryForList("SELECT expires_at FROM city_arrival_evidence WHERE evidence_id = ?", q.get("evidenceId"));
        if (evidence.isEmpty() || CityGameSupport.millis(evidence.get(0).get("expires_at")) <= now) return CityR491Store.refused(409, "EVIDENCE_EXPIRED", requestId);
        Map<String, Object> round = store.mapper.selectOpenRound(boardId, teamId);
        int actionCost = ((Number) q.get("actionCost")).intValue();
        if (round == null || ((Number) round.get("actionsRemaining")).intValue() < actionCost) return CityR491Store.refused(409, "ACTION_BUDGET_EXHAUSTED", requestId);
        long seq = ((Number) round.get("roundSeq")).longValue();
        int total = ((Number) round.get("actionsTotal")).intValue(), before = ((Number) round.get("actionsRemaining")).intValue();
        int[] c = budget.counters(board, teamId, seq, total, before);
        long pointCost = ((Number) q.get("pointCost")).longValue();
        Long pointsBefore = store.mapper.selectMemberPoints(memberId);
        if (pointsBefore == null || pointsBefore < pointCost || store.mapper.deductMemberPoints(memberId, pointCost) != 1) {
            return CityR491Store.refused(409, "INSUFFICIENT_POINTS", requestId);
        }
        String titleId = UUID.randomUUID().toString();
        String businessKey = "CITY:ACQUIRE:" + titleId;
        store.mapper.insertPointsDetail(memberId, pointsBefore, pointCost, "城市棋盘:自费占领", businessKey);
        store.jdbc.update("UPDATE city_acquisition_quote SET consumed = 1 WHERE quote_id = ?", q.get("quoteId"));
        Date protectedUntil = new Date(now + 1000L * ((Number) board.get("protectionSeconds")).intValue());
        boolean protectedNow = protectedUntil.getTime() > now;
        engine.write(boardId, property, (String) property.get("propertyId"), protectedNow ? "PROTECTED" : "HELD", teamId, new Date(now),
                protectedNow ? protectedUntil : null, null);
        engine.recordEvent(board, "PROPERTY_ACQUIRED", (String) property.get("propertyId"), teamId);
        engine.spend(board, teamId, seq, actionCost);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("titleId", titleId);
        t.put("regionId", board.get("regionId"));
        t.put("boardId", boardId);
        t.put("seasonId", board.get("seasonId"));
        t.put("pointId", pointId);
        t.put("ownerTeamId", teamId);
        t.put("contentHash", board.get("rulesHash"));
        t.put("acquiredAt", CityGameSupport.iso(new Date(now)));
        t.put("status", "ACTIVE_TERM");
        t.put("releaseId", ((Number) board.get("releaseId")).longValue());
        t.put("pointVersion", ((Number) property.get("stateVersion")).longValue() + 1);
        t.put("titleRetained", true);
        t.put("transferAllowed", false);
        t.put("serviceFeesAvailable", false);
        t.put("payerMemberId", memberId);
        t.put("pointsBefore", pointsBefore);
        t.put("pointsAfter", pointsBefore - pointCost);
        t.put("budgetVersion", (long) (c[2] + c[3] + 1));
        t.put("requestId", requestId);
        t.put("quoteId", q.get("quoteId"));
        t.put("sessionId", q.get("sessionId"));
        t.put("roundId", q.get("roundId"));
        t.put("taskResultId", q.get("taskResultId"));
        t.put("evidenceId", q.get("evidenceId"));
        t.put("pointsBusinessKey", businessKey);
        t.put("policyHash", q.get("policyHash"));
        t.put("pointCost", (int) pointCost);
        t.put("actionCost", actionCost);
        t.put("actionsBefore", before);
        t.put("actionsAfter", before - actionCost);
        t.put("termSeconds", q.get("termSeconds"));
        store.jdbc.update("INSERT INTO city_acquisition_title(title_id, board_id, point_id, owner_team_id, payer_member_id, title_json) VALUES (?,?,?,?,?,?)",
                titleId, boardId, pointId, teamId, memberId, JSON.toJSONString(t));
        Map<String, Object> data = CityArrivalService.receiptBody(requestId);
        data.put("contract", CONTRACT);
        data.put("effectsStatus", "COMMITTED");
        data.put("title", t);
        return CityR491Store.ok(data);
    }

    public Object receipt(Long memberId, String requestId) {
        CityGameSupport.requireMember(memberId);
        if (!CityArrivalService.upperUuid(requestId)) throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        return store.receipt(memberId, DOMAIN, requestId);
    }
}
