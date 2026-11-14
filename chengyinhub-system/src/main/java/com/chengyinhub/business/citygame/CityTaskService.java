package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
 * Zero-effect CITY tasks: /neutral-tasks (CITY_NEUTRAL_TASK_V1, remote, team-scoped) and /onsite-tasks
 * (CITY_ONSITE_TASK_V1, at a merchant point, member-scoped, needs one unused store-attested arrival evidence that
 * must still be valid on accept and on every answer), plus the caller's own onsite history (/task-history).
 * Tasks are objective pick-one questions graded by the server; a task never moves points, budget or ownership.
 * A passed onsite task is what the self-funded acquisition quotes against.
 */
@Service
public class CityTaskService {
    public static final String NEUTRAL = "neutral-tasks", ONSITE = "onsite-tasks";
    static final String VERIFIER = "NATIVE_OBJECTIVE_QA_PICK_V1";

    private final CityR491Store store;
    private final CityArrivalService arrival;

    public CityTaskService(CityR491Store store, CityArrivalService arrival) {
        this.store = store;
        this.arrival = arrival;
    }

    private static boolean onsite(String domain) { return ONSITE.equals(domain); }

    List<Map<String, Object>> definitions(String boardId, String access) {
        return store.jdbc.queryForList("SELECT * FROM city_task_definition WHERE board_id = ? AND access = ? AND status = 'PUBLISHED'"
                + " ORDER BY sort_order, task_id LIMIT 50", boardId, access);
    }

    Map<String, Object> definition(Map<String, Object> row) {
        boolean site = "ONSITE_ZERO_EFFECT".equals(row.get("access"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", row.get("task_id"));
        if (site) out.put("pointId", row.get("point_id"));
        out.put("title", row.get("title"));
        out.put("type", "QA_PICK");
        out.put("access", row.get("access"));
        out.put("arrivalRequirement", site ? "MERCHANT_ATTESTED" : "NONE");
        out.put("scope", site ? "MEMBER_POINT_TEAM_ROUND" : "TEAM_ROUND");
        out.put("question", row.get("question"));
        if (site) {
            out.put("acceptanceReason", "到店凭证由门店店员确认，接取与每次作答时都须仍在有效期内");
            out.put("evidencePolicy", "SAME_EVIDENCE_VALID_ON_ACCEPT_AND_EACH_SUBMIT");
            out.put("evidenceUse", "SINGLE_TASK_INSTANCE");
            out.put("reviewerPolicy", "CURRENT_STORE_PERMISSION_AND_REVIEWER_VERIFY");
            out.put("effectsPolicy", "NO_AUTOMATIC_EFFECTS");
        }
        JSONArray options = JSON.parseArray(String.valueOf(row.get("options_json")));
        out.put("options", options);
        out.put("maxAttempts", ((Number) row.get("max_attempts")).intValue());
        out.put("pointCost", 0);
        out.put("pointReward", 0);
        out.put("actionCost", 0);
        return out;
    }

    static String taskHash(Map<String, Object> definition) { return CityR491Store.policyHash(definition); }

    private Map<String, Object> instance(String boardId, String taskId, String ownerKey) {
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_task_instance WHERE board_id = ? AND task_id = ? AND owner_key = ?",
                boardId, taskId, ownerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The member's evidence for a point that an onsite task can still use (confirmed, unexpired, unused). */
    Map<String, Object> usableEvidence(String boardId, String pointId, long memberId, String teamId) {
        List<Map<String, Object>> rows = store.jdbc.queryForList(
                "SELECT * FROM city_arrival_evidence WHERE board_id = ? AND point_id = ? AND member_id = ? AND team_id = ? AND used_by_instance IS NULL"
                        + " AND expires_at > ? ORDER BY create_time DESC LIMIT 1", boardId, pointId, memberId, teamId, new Date(store.now()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> wireTaskEvidence(Map<String, Object> e, Map<String, Object> board) {
        Map<String, Object> point = arrival.point(boardId(board), (String) e.get("point_id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceId", e.get("evidence_id"));
        out.put("pointId", e.get("point_id"));
        out.put("teamId", e.get("team_id"));
        out.put("sessionId", e.get("session_id"));
        out.put("contentHash", board.get("rulesHash"));
        out.put("policyHash", CityR491Store.policyHash(CityArrivalService.policy(point)));
        out.put("roundId", board.get("seasonId"));
        out.put("expiresAt", CityGameSupport.iso(e.get("expires_at")));
        out.put("assurance", "MERCHANT_ATTESTED");
        out.put("memberId", ((Number) e.get("member_id")).longValue());
        out.put("merchantId", ((Number) e.get("merchant_id")).longValue());
        out.put("placeId", ((Number) e.get("place_id")).longValue());
        out.put("releaseId", ((Number) board.get("releaseId")).longValue());
        return out;
    }

    private static String boardId(Map<String, Object> board) { return (String) board.get("boardId"); }

    // ---- state ----

    public Map<String, Object> state(String domain, Long memberId, String regionId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        Membership m = store.membership(board, memberId);
        boolean site = onsite(domain);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", site ? "CITY_ONSITE_TASK_V1" : "CITY_NEUTRAL_TASK_V1");
        out.put("scope", "CITY");
        if (m.participation == null || !m.present()) {
            out.put("status", "JOIN_REQUIRED");
            out.put("writeApproved", false);
            out.put("binding", null);
            out.put("teamId", null);
            out.put("sessionId", null);
            out.put("tasks", new ArrayList<>());
            return out;
        }
        List<Map<String, Object>> defs = definitions(boardId(board), site ? "ONSITE_ZERO_EFFECT" : "REMOTE_NEUTRAL");
        String status = site && defs.isEmpty() ? "NOT_CONFIGURED" : m.joined() ? "AVAILABLE" : "TEAM_NOT_READY";
        boolean write = "AVAILABLE".equals(status) && CityR491Store.open(board);
        out.put("status", status);
        out.put("writeApproved", write);
        out.put("binding", CityR491Store.binding(board));
        out.put("teamId", m.teamId());
        out.put("sessionId", "AVAILABLE".equals(status) ? m.sessionId() : null);
        out.put("teamVersion", m.teamVersion());
        if (site) out.put("memberId", memberId);
        out.put("arrivalProof", site ? "MERCHANT_ATTESTED_ONLY" : "NOT_PROVIDED");
        out.put("ownershipEffect", "NONE");
        out.put("pointsEffect", "NONE");
        if (site) out.put("effectsStatus", "NOT_EXECUTED");
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            String owner = site ? String.valueOf(memberId) : m.teamId();
            Map<String, Object> inst = instance(boardId(board), (String) def.get("task_id"), owner);
            tasks.add(wireTask(site, def, inst, board, m, memberId, write));
        }
        out.put("tasks", tasks);
        return out;
    }

    Map<String, Object> wireTask(boolean site, Map<String, Object> def, Map<String, Object> inst, Map<String, Object> board, Membership m,
                                 long memberId, boolean write) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> definition = definition(def);
        out.put("definition", definition);
        String status = inst == null ? "NOT_ACCEPTED" : (String) inst.get("status");
        out.put("status", status);
        out.put("version", inst == null ? 0L : ((Number) inst.get("version")).longValue());
        out.put("attempts", inst == null ? 0 : ((Number) inst.get("attempts")).intValue());
        out.put("instanceId", inst == null ? null : inst.get("instance_id"));
        out.put("acceptedAt", inst == null ? null : CityGameSupport.iso(inst.get("accepted_at")));
        Object result = inst == null || inst.get("result_json") == null ? null : JSON.parseObject(String.valueOf(inst.get("result_json")));
        if (site) {
            Map<String, Object> evidence = null;
            if (inst != null && inst.get("evidence_id") != null) {
                List<Map<String, Object>> bound = store.jdbc.queryForList("SELECT * FROM city_arrival_evidence WHERE evidence_id = ?", inst.get("evidence_id"));
                if (!bound.isEmpty()) evidence = bound.get(0);
            } else if (inst == null && m.joined()) {
                evidence = usableEvidence(boardId(board), (String) def.get("point_id"), memberId, m.teamId());
            }
            boolean evidenceValid = evidence != null && CityGameSupport.millis(evidence.get("expires_at")) > store.now();
            String eligibility = !m.joined() ? "TEAM_NOT_READY" : evidence == null ? "ARRIVAL_EVIDENCE_REQUIRED"
                    : !evidenceValid ? "ARRIVAL_EVIDENCE_EXPIRED" : "ELIGIBLE";
            out.put("eligibility", eligibility);
            out.put("canAct", write && "ELIGIBLE".equals(eligibility) && ("NOT_ACCEPTED".equals(status) || "ACTIVE".equals(status)));
            out.put("evidence", evidence == null ? null : wireTaskEvidence(evidence, board));
            out.put("evidenceId", inst == null ? null : inst.get("evidence_id"));
            out.put("acceptRequestId", inst == null ? null : inst.get("accept_request_id"));
            out.put("result", result);
        } else {
            out.put("result", result == null ? null : neutralResult((Map<String, Object>) result));
        }
        return out;
    }

    private static Map<String, Object> neutralResult(Map<String, Object> full) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultId", full.get("resultId"));
        out.put("outcome", full.get("outcome"));
        out.put("requestId", full.get("requestId"));
        out.put("createdAt", full.get("createdAt"));
        out.put("verifier", full.get("verifier"));
        out.put("attemptNumber", full.get("attemptNumber"));
        return out;
    }

    // ---- commands ----

    public Object command(String domain, Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        boolean site = onsite(domain);
        String requestId = CityR491Store.str(body, "requestId"), kind = CityR491Store.str(body, "kind");
        Set<String> keys = new java.util.HashSet<>(Set.of("requestId", "regionId", "kind", "releaseId", "contentHash", "teamId", "expectedTeamVersion", "taskId", "expectedVersion"));
        if (site) keys.add("evidenceId");
        if ("SUBMIT".equals(kind)) keys.addAll(Set.of("instanceId", "optionId"));
        if (body == null || !body.keySet().equals(keys) || !CityArrivalService.upperUuid(requestId) || !("ACCEPT".equals(kind) || "SUBMIT".equals(kind))) {
            throw new CityGameException(400, "CITY_TASK_INVALID", "任务指令无效");
        }
        return store.once(memberId, domain, requestId, CityR491Store.requestHash(body), () -> apply(site, domain, memberId, body, requestId, kind));
    }

    private Outcome apply(boolean site, String domain, long memberId, Map<String, Object> body, String requestId, String kind) {
        Map<String, Object> board = store.board(CityR491Store.str(body, "regionId"));
        String taskId = CityR491Store.str(body, "taskId"), teamId = CityR491Store.str(body, "teamId");
        Long teamVersion = CityR491Store.num(body, "expectedTeamVersion"), expected = CityR491Store.num(body, "expectedVersion");
        if (!CityGameRules.id(taskId) || !CityGameRules.uuid(teamId) || teamVersion == null || expected == null) throw new CityGameException(400, "CITY_TASK_INVALID", "任务指令无效");
        if (!CityR491Store.sameRelease(board, CityR491Store.num(body, "releaseId"), CityR491Store.str(body, "contentHash"))) {
            return CityR491Store.refused(409, "CITY_RELEASE_CHANGED", requestId);
        }
        if (!CityR491Store.open(board)) return CityR491Store.refused(409, "BOARD_NOT_OPEN", requestId);
        Membership m = store.membership(board, memberId);
        if (!m.joined() || !teamId.equals(m.teamId())) return CityR491Store.refused(409, "NOT_JOINED", requestId);
        if (m.teamVersion() != teamVersion) return CityR491Store.refused(409, "TEAM_VERSION_CHANGED", requestId);
        List<Map<String, Object>> defs = store.jdbc.queryForList("SELECT * FROM city_task_definition WHERE board_id = ? AND task_id = ? AND access = ? AND status = 'PUBLISHED'",
                boardId(board), taskId, site ? "ONSITE_ZERO_EFFECT" : "REMOTE_NEUTRAL");
        if (defs.isEmpty()) return CityR491Store.refused(404, "TASK_NOT_FOUND", requestId);
        Map<String, Object> def = defs.get(0);
        String owner = site ? String.valueOf(memberId) : teamId;
        // Serialise on the team row: neutral tasks are shared by the team.
        store.mapper.selectTeamForUpdate(teamId);
        Map<String, Object> inst = instance(boardId(board), taskId, owner);
        long now = store.now();
        Map<String, Object> evidence = null;
        if (site) {
            String evidenceId = CityR491Store.str(body, "evidenceId");
            if (!CityGameRules.uuid(evidenceId)) throw new CityGameException(400, "CITY_TASK_INVALID", "任务指令无效");
            List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_arrival_evidence WHERE evidence_id = ? FOR UPDATE", evidenceId);
            if (rows.isEmpty() || ((Number) rows.get(0).get("member_id")).longValue() != memberId || !def.get("point_id").equals(rows.get(0).get("point_id"))
                    || !teamId.equals(rows.get(0).get("team_id"))) {
                return CityR491Store.refused(409, "ARRIVAL_EVIDENCE_INVALID", requestId);
            }
            evidence = rows.get(0);
            if (CityGameSupport.millis(evidence.get("expires_at")) <= now) return CityR491Store.refused(409, "ARRIVAL_EVIDENCE_EXPIRED", requestId);
        }
        if ("ACCEPT".equals(kind)) {
            if (expected != 0 || inst != null) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
            if (site && evidence.get("used_by_instance") != null) return CityR491Store.refused(409, "ARRIVAL_EVIDENCE_USED", requestId);
            String instanceId = UUID.randomUUID().toString();
            store.jdbc.update("INSERT INTO city_task_instance(instance_id, board_id, task_id, access, owner_key, team_id, session_id, member_id, evidence_id,"
                            + " accept_request_id, accepted_at, attempts, status, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,'ACTIVE',1)",
                    instanceId, boardId(board), taskId, def.get("access"), owner, teamId, m.sessionId(), memberId,
                    site ? evidence.get("evidence_id") : null, requestId, new Date(now));
            if (site) store.jdbc.update("UPDATE city_arrival_evidence SET used_by_instance = ? WHERE evidence_id = ?", instanceId, evidence.get("evidence_id"));
            return reply(site, domain, def, instance(boardId(board), taskId, owner), board, m, memberId, requestId, null);
        }
        String instanceId = CityR491Store.str(body, "instanceId"), optionId = CityR491Store.str(body, "optionId");
        if (!CityGameRules.uuid(instanceId) || !CityGameRules.id(optionId)) throw new CityGameException(400, "CITY_TASK_INVALID", "任务指令无效");
        if (inst == null || !instanceId.equals(inst.get("instance_id"))) return CityR491Store.refused(404, "TASK_INSTANCE_NOT_FOUND", requestId);
        if (site && !evidence.get("evidence_id").equals(inst.get("evidence_id"))) return CityR491Store.refused(409, "ARRIVAL_EVIDENCE_INVALID", requestId);
        if (((Number) inst.get("version")).longValue() != expected) return CityR491Store.refused(409, "VERSION_CONFLICT", requestId);
        if (!"ACTIVE".equals(inst.get("status"))) return CityR491Store.refused(409, "TASK_CLOSED", requestId);
        JSONArray options = JSON.parseArray(String.valueOf(def.get("options_json")));
        boolean known = false;
        for (int i = 0; i < options.size(); i++) if (optionId.equals(options.getJSONObject(i).getString("id"))) known = true;
        if (!known) return CityR491Store.refused(400, "OPTION_UNKNOWN", requestId);
        int attempt = ((Number) inst.get("attempts")).intValue() + 1;
        int max = ((Number) def.get("max_attempts")).intValue();
        boolean passed = optionId.equals(def.get("answer_option_id"));
        String status = passed ? "PASSED" : attempt >= max ? "EXHAUSTED" : "ACTIVE";
        store.jdbc.update("INSERT INTO city_task_attempt(attempt_id, instance_id, request_id, option_id, attempt_number, passed, create_time, evidence_checked_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), instanceId, requestId, optionId, attempt, passed ? 1 : 0,
                new Date(now), site ? new Date(now) : null);
        String resultJson = null;
        if (!"ACTIVE".equals(status)) {
            Map<String, Object> result = result(site, def, inst, board, memberId, requestId, status, attempt, evidence, now);
            resultJson = JSON.toJSONString(result);
        }
        store.jdbc.update("UPDATE city_task_instance SET attempts = ?, status = ?, version = version + 1, result_json = ? WHERE instance_id = ?",
                attempt, status, resultJson, instanceId);
        return reply(site, domain, def, instance(boardId(board), taskId, owner), board, m, memberId, requestId, resultJson);
    }

    private Map<String, Object> result(boolean site, Map<String, Object> def, Map<String, Object> inst, Map<String, Object> board, long memberId,
                                       String requestId, String outcome, int attempt, Map<String, Object> evidence, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultId", UUID.randomUUID().toString());
        out.put("instanceId", inst.get("instance_id"));
        out.put("taskId", def.get("task_id"));
        out.put("requestId", requestId);
        out.put("acceptRequestId", inst.get("accept_request_id"));
        out.put("outcome", outcome);
        out.put("createdAt", CityGameSupport.iso(new Date(now)));
        out.put("verifier", VERIFIER);
        out.put("attemptNumber", attempt);
        if (site) {
            Map<String, Object> point = arrival.point(boardId(board), (String) def.get("point_id"));
            out.put("evidenceCheckedAt", CityGameSupport.iso(new Date(now)));
            out.put("evidenceExpiresAt", CityGameSupport.iso(evidence.get("expires_at")));
            out.put("evidenceId", evidence.get("evidence_id"));
            out.put("evidencePolicyHash", CityR491Store.policyHash(CityArrivalService.policy(point)));
            out.put("pointId", def.get("point_id"));
            out.put("teamId", inst.get("team_id"));
            out.put("sessionId", inst.get("session_id"));
            out.put("regionId", board.get("regionId"));
            out.put("boardId", board.get("boardId"));
            out.put("seasonId", board.get("seasonId"));
            out.put("roundId", board.get("seasonId"));
            out.put("rulesReleaseId", board.get("rulesReleaseId"));
            out.put("contentHash", board.get("rulesHash"));
            out.put("taskHash", taskHash(definition(def)));
            out.put("arrivalAssurance", "MERCHANT_ATTESTED");
            out.put("effectsStatus", "NOT_EXECUTED");
            out.put("memberId", memberId);
            out.put("merchantId", ((Number) evidence.get("merchant_id")).longValue());
            out.put("placeId", ((Number) evidence.get("place_id")).longValue());
            out.put("releaseId", ((Number) board.get("releaseId")).longValue());
        }
        return out;
    }

    private Outcome reply(boolean site, String domain, Map<String, Object> def, Map<String, Object> inst, Map<String, Object> board, Membership m,
                          long memberId, String requestId, String resultJson) {
        Map<String, Object> data = CityArrivalService.receiptBody(requestId);
        if (site) {
            data.put("result", resultJson == null ? null : JSON.parseObject(resultJson));
        } else {
            data.put("contract", "CITY_NEUTRAL_TASK_V1");
            data.put("scope", "CITY");
            data.put("task", wireTask(false, def, inst, board, m, memberId, true));
        }
        return CityR491Store.ok(data);
    }

    public Object receipt(String domain, Long memberId, String requestId) {
        CityGameSupport.requireMember(memberId);
        CityGameSupport.requireId(requestId, "requestId");
        return store.receipt(memberId, domain, requestId);
    }

    public Map<String, Object> onsiteResult(Long memberId, String resultId) {
        CityGameSupport.requireMember(memberId);
        if (!CityGameRules.uuid(resultId)) throw new CityGameException(404, "RESULT_NOT_FOUND", "结果不存在");
        for (Map<String, Object> row : store.jdbc.queryForList(
                "SELECT result_json FROM city_task_instance WHERE member_id = ? AND access = 'ONSITE_ZERO_EFFECT' AND result_json IS NOT NULL", memberId)) {
            Map<String, Object> result = JSON.parseObject(String.valueOf(row.get("result_json")));
            if (resultId.equals(result.get("resultId"))) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("contract", "CITY_ONSITE_TASK_V1");
                out.put("effectsStatus", "NOT_EXECUTED");
                out.put("result", result);
                return out;
            }
        }
        throw new CityGameException(404, "RESULT_NOT_FOUND", "结果不存在");
    }

    // ---- personal history ----

    private Map<String, Object> historyBase(long memberId, String regionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "CITY_PERSONAL_TASK_HISTORY_V1");
        out.put("scope", "OWN_ONSITE_TASKS_ONLY");
        out.put("regionId", regionId);
        out.put("readAt", CityGameSupport.iso(new Date(store.now())));
        out.put("currentQualification", "NOT_EVALUATED");
        out.put("memberId", memberId);
        out.put("readOnly", true);
        return out;
    }

    private Map<String, Object> historyItem(Map<String, Object> inst, Map<String, Object> board, Map<String, Object> def) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", inst.get("instance_id"));
        out.put("regionId", board.get("regionId"));
        out.put("boardId", board.get("boardId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("roundId", board.get("seasonId"));
        out.put("teamId", inst.get("team_id"));
        out.put("sessionId", inst.get("session_id"));
        out.put("taskId", inst.get("task_id"));
        out.put("pointId", def.get("point_id"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("contentHash", board.get("rulesHash"));
        out.put("taskHash", taskHash(definition(def)));
        out.put("status", inst.get("status"));
        out.put("acceptRequestId", inst.get("accept_request_id"));
        out.put("acceptedAt", CityGameSupport.iso(inst.get("accepted_at")));
        out.put("evidenceId", inst.get("evidence_id"));
        out.put("arrivalAssurance", "MERCHANT_ATTESTED");
        out.put("effectsStatus", "NOT_EXECUTED");
        out.put("memberId", ((Number) inst.get("member_id")).longValue());
        out.put("releaseId", ((Number) board.get("releaseId")).longValue());
        out.put("version", ((Number) inst.get("version")).longValue());
        out.put("attempts", ((Number) inst.get("attempts")).intValue());
        out.put("maxAttempts", ((Number) def.get("max_attempts")).intValue());
        out.put("title", def.get("title"));
        return out;
    }

    public Map<String, Object> history(Long memberId, String regionId, String before) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        if (before != null && !CityGameRules.uuid(before)) throw new CityGameException(400, "beforeInstanceId", "beforeInstanceId invalid");
        List<Map<String, Object>> rows = store.jdbc.queryForList(
                "SELECT i.*, d.point_id AS def_point FROM city_task_instance i JOIN city_task_definition d ON d.board_id = i.board_id AND d.task_id = i.task_id"
                        + " WHERE i.member_id = ? AND i.board_id = ? AND i.access = 'ONSITE_ZERO_EFFECT' ORDER BY i.accepted_at DESC, i.instance_id DESC",
                memberId, boardId(board));
        List<Map<String, Object>> items = new ArrayList<>();
        boolean skipping = before != null;
        for (Map<String, Object> inst : rows) {
            if (skipping) {
                if (before.equals(inst.get("instance_id"))) skipping = false;
                continue;
            }
            if (items.size() == 20) break;
            items.add(historyItem(inst, board, def(inst)));
        }
        if (skipping) throw new CityGameException(409, "CITY_HISTORY_CURSOR_STALE", "分页游标已失效");
        Map<String, Object> out = historyBase(memberId, regionId);
        out.put("pageSize", 20);
        out.put("items", items);
        boolean more = items.size() == 20 && rows.indexOf(rows.stream().filter(r -> r.get("instance_id").equals(items.get(19).get("instanceId"))).findFirst().orElse(null)) < rows.size() - 1;
        out.put("nextBeforeInstanceId", more ? items.get(19).get("instanceId") : null);
        return out;
    }

    private Map<String, Object> def(Map<String, Object> inst) {
        return store.jdbc.queryForMap("SELECT * FROM city_task_definition WHERE board_id = ? AND task_id = ?", inst.get("board_id"), inst.get("task_id"));
    }

    private Map<String, Object> ownInstance(long memberId, Map<String, Object> board, String instanceId) {
        if (!CityGameRules.uuid(instanceId)) throw new CityGameException(404, "TASK_INSTANCE_NOT_FOUND", "任务记录不存在");
        List<Map<String, Object>> rows = store.jdbc.queryForList("SELECT * FROM city_task_instance WHERE instance_id = ? AND member_id = ? AND board_id = ?"
                + " AND access = 'ONSITE_ZERO_EFFECT'", instanceId, memberId, boardId(board));
        if (rows.isEmpty()) throw new CityGameException(404, "TASK_INSTANCE_NOT_FOUND", "任务记录不存在");
        return rows.get(0);
    }

    public Map<String, Object> historyDetail(Long memberId, String regionId, String instanceId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        Map<String, Object> inst = ownInstance(memberId, board, instanceId);
        Map<String, Object> out = historyBase(memberId, regionId);
        out.put("instance", historyItem(inst, board, def(inst)));
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Map<String, Object> a : store.jdbc.queryForList("SELECT * FROM city_task_attempt WHERE instance_id = ? ORDER BY attempt_number", instanceId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attemptId", a.get("attempt_id"));
            row.put("requestId", a.get("request_id"));
            row.put("optionId", a.get("option_id"));
            row.put("createdAt", CityGameSupport.iso(a.get("create_time")));
            row.put("evidenceCheckedAt", CityGameSupport.iso(a.get("evidence_checked_at") == null ? a.get("create_time") : a.get("evidence_checked_at")));
            row.put("attemptNumber", ((Number) a.get("attempt_number")).intValue());
            row.put("passed", ((Number) a.get("passed")).intValue() == 1);
            attempts.add(row);
        }
        out.put("attempts", attempts);
        out.put("result", inst.get("result_json") == null ? null : JSON.parseObject(String.valueOf(inst.get("result_json"))));
        return out;
    }

    /** History receipt read: only requests that belong to this instance (its accept or one of its answers). */
    public Object historyReceipt(Long memberId, String regionId, String instanceId, String requestId) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> board = store.board(regionId);
        Map<String, Object> inst = ownInstance(memberId, board, instanceId);
        CityGameSupport.requireId(requestId, "requestId");
        Integer answers = store.jdbc.queryForObject("SELECT COUNT(*) FROM city_task_attempt WHERE instance_id = ? AND request_id = ?", Integer.class, instanceId, requestId);
        if (!requestId.equals(inst.get("accept_request_id")) && (answers == null || answers == 0)) {
            throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        }
        return store.receipt(memberId, ONSITE, requestId);
    }
}
