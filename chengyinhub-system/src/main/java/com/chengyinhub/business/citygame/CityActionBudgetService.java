package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import com.chengyinhub.business.citygame.CityR491Store.Outcome;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /api/city-game/action-budget/{state,command,request/{id}} (contract CITY_ACTION_BUDGET_V2). The budget is the
 * team round of the one board engine: rolls cost roll_cost, acquisitions cost acquisition_cost, nothing else spends
 * units on an r491 board (task, service and challenge costs are 0; a board pricing them otherwise answers
 * NOT_CONFIGURED). version = rolls + acquisitions in the round. An r491 ROLL is recorded only: the die is rolled by
 * the server from the published weighted table, the units are spent, and nothing moves, changes hands or scores.
 */
@Service
public class CityActionBudgetService {
    static final String DOMAIN = "action-budget";
    static final String CONTRACT = "CITY_ACTION_BUDGET_V2";
    static final String VERIFIER = "SERVER_WEIGHTED_SECURE_RANDOM_V1";
    private static final Set<String> KEYS = Set.of("kind", "requestId", "regionId", "teamId", "contentHash", "expectedTeamVersion", "releaseId", "expectedVersion");

    private final CityR491Store store;
    private final CityGameActionService engine;

    public CityActionBudgetService(CityR491Store store, CityGameActionService engine) {
        this.store = store;
        this.engine = engine;
    }

    static boolean configured(Map<String, Object> board) {
        return ((Number) board.get("taskCost")).intValue() == 0 && ((Number) board.get("serviceCost")).intValue() == 0
                && ((Number) board.get("challengeCost")).intValue() == 0;
    }

    /** Budget counters of one round: [total, used, rolls, acquisitions]. */
    int[] counters(Map<String, Object> board, String teamId, long seq, int total, int remaining) {
        int rollCost = ((Number) board.get("rollCost")).intValue(), acqCost = ((Number) board.get("acquisitionCost")).intValue();
        int rolls = store.mapper.selectRoundRolls((String) board.get("boardId"), teamId, seq).size();
        int used = total - remaining;
        int acquisitions = Math.max(0, (used - rolls * rollCost) / acqCost);
        return new int[]{total, used, rolls, acquisitions};
    }

    public Map<String, Object> state(Long memberId, String regionId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        board = engine.freezeIfDue(board);
        Membership m = store.membership(board, memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("scope", "CITY");
        if (m.participation == null || !m.present()) {
            out.put("status", "JOIN_REQUIRED");
            out.put("writeApproved", false);
            out.put("binding", null);
            out.put("teamId", null);
            out.put("recentResults", new ArrayList<>());
            return out;
        }
        out.put("binding", CityR491Store.binding(board));
        out.put("teamId", m.teamId());
        out.put("teamVersion", m.teamVersion());
        out.put("movementStatus", "UNAVAILABLE");
        out.put("arrivalProof", "NOT_PROVIDED");
        out.put("ownershipEffect", "NONE");
        out.put("pointsEffect", "NONE");
        out.put("effectsStatus", "NOT_EXECUTED");
        out.put("recentResults", new ArrayList<>());
        if (!configured(board)) {
            out.put("status", "NOT_CONFIGURED");
            out.put("writeApproved", false);
            return out;
        }
        int total = ((Number) board.get("actionsPerRound")).intValue();
        int rollCost = ((Number) board.get("rollCost")).intValue();
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("scope", "TEAM_ROUND");
        rules.put("unit", "ACTION");
        rules.put("refillPolicy", "NONE_WITHIN_ROUND");
        rules.put("chargeTiming", "COMMITTED_ACTION_EFFECT");
        rules.put("actionKind", "DICE_ROLL_AND_ACQUIRE");
        rules.put("resultPolicy", "DICE_RECORD_ONLY_ACQUISITION_EXPLICIT_CONFIRM");
        rules.put("totalUnits", total);
        rules.put("rollCost", rollCost);
        rules.put("acquisitionCost", ((Number) board.get("acquisitionCost")).intValue());
        out.put("rules", rules);
        out.put("dice", dice(board));
        String boardId = (String) board.get("boardId");
        Map<String, Object> round = m.joined() ? store.mapper.selectOpenRound(boardId, m.teamId()) : null;
        long seq;
        int remaining;
        if (round != null) {
            seq = ((Number) round.get("roundSeq")).longValue();
            remaining = ((Number) round.get("actionsRemaining")).intValue();
        } else {
            Map<String, Object> last = m.joined() ? store.mapper.selectLastRound(boardId, m.teamId()) : null;
            seq = last == null ? 0 : ((Number) last.get("roundSeq")).longValue();
            remaining = last == null ? total : 0;
        }
        int[] c = seq == 0 ? new int[]{total, 0, 0, 0} : counters(board, m.teamId(), seq, total, remaining);
        String status;
        if (!m.joined() || seq == 0) status = "TEAM_NOT_READY";
        else status = remaining >= rollCost && round != null && CityR491Store.open(board) ? "AVAILABLE" : "EXHAUSTED";
        if ("TEAM_NOT_READY".equals(status)) c = new int[]{total, 0, 0, 0};
        int used = c[1];
        out.put("status", status);
        out.put("writeApproved", "AVAILABLE".equals(status));
        out.put("sessionId", m.sessionId());
        out.put("version", (long) (c[2] + c[3]));
        out.put("usedUnits", used);
        out.put("remainingUnits", total - used);
        out.put("rollCount", c[2]);
        if (!"TEAM_NOT_READY".equals(status)) out.put("recentResults", recent(board, m.teamId(), seq));
        return out;
    }

    static Map<String, Object> dice(Map<String, Object> board) {
        Map<String, Object> dice = new LinkedHashMap<>();
        dice.put("diceId", board.get("diceId"));
        dice.put("faces", JSON.parseArray((String) board.get("diceFaces"), Long.class));
        dice.put("weights", JSON.parseArray((String) board.get("diceWeights"), Integer.class));
        return dice;
    }

    private List<Map<String, Object>> recent(Map<String, Object> board, String teamId, long seq) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> roll : store.mapper.selectRoundRolls((String) board.get("boardId"), teamId, seq)) {
            if (!"BUDGET".equals(roll.get("purpose")) || roll.get("resultJson") == null) continue;
            out.add(JSON.parseObject(String.valueOf(roll.get("resultJson"))));
        }
        return out.size() > 20 ? out.subList(out.size() - 20, out.size()) : out;
    }

    public Object command(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String requestId = CityR491Store.str(body, "requestId"), regionId = CityR491Store.str(body, "regionId");
        String teamId = CityR491Store.str(body, "teamId"), contentHash = CityR491Store.str(body, "contentHash");
        Long teamVersion = CityR491Store.num(body, "expectedTeamVersion"), releaseId = CityR491Store.num(body, "releaseId");
        Long expected = CityR491Store.num(body, "expectedVersion");
        if (body == null || !body.keySet().equals(KEYS) || !"ROLL".equals(body.get("kind")) || !CityGameRules.id(requestId) || !CityGameRules.id(regionId)
                || !CityGameRules.uuid(teamId) || !CityGameRules.hash(contentHash) || teamVersion == null || teamVersion < 1 || releaseId == null
                || releaseId < 1 || expected == null || expected < 0) {
            throw new CityGameException(400, "CITY_BUDGET_INVALID", "预算指令无效");
        }
        return store.once(memberId, DOMAIN, requestId, CityR491Store.requestHash(body), () -> roll(memberId, body, requestId, regionId, teamId,
                contentHash, teamVersion, releaseId, expected));
    }

    private Outcome roll(long memberId, Map<String, Object> body, String requestId, String regionId, String teamId, String contentHash,
                         long teamVersion, long releaseId, long expected) {
        Map<String, Object> current = store.board(regionId);
        Map<String, Object> board = engine.freezeIfDue(store.mapper.selectBoardForUpdate((String) current.get("boardId")));
        if (!CityR491Store.sameRelease(board, releaseId, contentHash)) return CityR491Store.refused(409, "CITY_RELEASE_CHANGED", requestId);
        if (!CityR491Store.open(board)) return CityR491Store.refused(409, "BOARD_NOT_OPEN", requestId);
        if (!configured(board)) return CityR491Store.refused(409, "CITY_BUDGET_NOT_CONFIGURED", requestId);
        Membership m = store.membership(board, memberId);
        if (!m.joined() || !teamId.equals(m.teamId())) return CityR491Store.refused(409, "NOT_JOINED", requestId);
        Map<String, Object> team = store.mapper.selectTeamForUpdate(teamId);
        if (((Number) team.get("version")).longValue() != teamVersion) return CityR491Store.refused(409, "TEAM_VERSION_CHANGED", requestId);
        String boardId = (String) board.get("boardId");
        Map<String, Object> round = store.mapper.selectOpenRound(boardId, teamId);
        if (round == null) return CityR491Store.refused(409, "ACTION_BUDGET_EXHAUSTED", requestId);
        long seq = ((Number) round.get("roundSeq")).longValue();
        int total = ((Number) round.get("actionsTotal")).intValue(), remaining = ((Number) round.get("actionsRemaining")).intValue();
        int rollCost = ((Number) board.get("rollCost")).intValue();
        int[] c = counters(board, teamId, seq, total, remaining);
        if (c[2] + c[3] != expected) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
        if (remaining < rollCost) return CityR491Store.refused(409, "ACTION_BUDGET_EXHAUSTED", requestId);
        String resultId = UUID.randomUUID().toString();
        String diceId = (String) board.get("diceId");
        long now = store.now();
        int rollNumber = c[2] + 1;
        long budgetVersion = expected + 1;
        Map<String, Object>[] result = new Map[1];
        engine.budgetRoll(board, teamId, seq, memberId, requestId, r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resultId", resultId);
            row.put("requestId", requestId);
            row.put("diceId", diceId);
            row.put("createdAt", CityGameSupport.iso(new java.util.Date(now)));
            row.put("effectsStatus", "NOT_EXECUTED");
            row.put("verifier", VERIFIER);
            row.put("rollNumber", rollNumber);
            row.put("costUnits", rollCost);
            row.put("beforeUnits", r[2]);
            row.put("afterUnits", r[3]);
            row.put("faceIndex", r[0]);
            row.put("budgetVersion", budgetVersion);
            row.put("faceValue", (long) r[1]);
            result[0] = row;
            return JSON.toJSONString(row);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("requestStatus", "COMPLETE");
        data.put("contract", CONTRACT);
        data.put("scope", "CITY");
        data.put("teamId", teamId);
        data.put("sessionId", m.sessionId());
        data.put("binding", CityR491Store.binding(board));
        data.put("result", result[0]);
        return CityR491Store.ok(data);
    }

    public Object receipt(Long memberId, String requestId) {
        CityGameSupport.requireMember(memberId);
        CityGameSupport.requireId(requestId, "requestId");
        return store.receipt(memberId, DOMAIN, requestId);
    }

    static JSONObject unused() { return null; }
}
