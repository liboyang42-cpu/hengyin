package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.chengyinhub.business.citygame.CityGameSupport.flag;
import static com.chengyinhub.business.citygame.CityGameSupport.requireMember;

/**
 * Team participation commands (CREATE / JOIN / LOCK / CONFIRM / EXIT / RESUME). Every command is
 * idempotent by (member, requestId): the first terminal outcome is stored and replayed; a requestId
 * reused with a different body is a 409. Round 1 opens when every present member has confirmed.
 */
@Service
public class CityGameParticipationService {
    static final String DOMAIN = "team-participation";

    private final CityGameMapper mapper;

    public CityGameParticipationService(CityGameMapper mapper) { this.mapper = mapper; }

    /** Result with the HTTP status the controller must answer with (200 / 400 / 409). */
    public static final class Outcome {
        public final int status;
        public final String errorCode;
        public final Map<String, Object> data;

        Outcome(int status, String errorCode, Map<String, Object> data) {
            this.status = status;
            this.errorCode = errorCode;
            this.data = data;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Outcome command(Long memberId, Map<String, Object> body) {
        requireMember(memberId);
        String requestId = string(body, "requestId");
        String kind = string(body, "kind");
        if (!CityGameRules.id(requestId)) throw new CityGameException(400, "requestId", "requestId invalid");
        String hash = CityGameRules.requestHash(body);
        String regionId = string(body, "regionId");
        Long releaseId = longValue(body, "releaseId");
        Long expectedVersion = longValue(body, "expectedVersion");
        String contentHash = string(body, "contentHash");
        String teamId = string(body, "teamId");
        String inviteCode = string(body, "inviteCode");
        boolean shape = kind != null && List.of("CREATE", "JOIN", "LOCK", "CONFIRM", "EXIT", "RESUME").contains(kind)
                && CityGameRules.id(regionId) && releaseId != null && releaseId > 0 && expectedVersion != null && expectedVersion >= 0
                && CityGameRules.hash(contentHash)
                && ("CREATE".equals(kind) ? teamId == null : CityGameRules.uuid(teamId))
                && ("JOIN".equals(kind) ? CityGameRules.uuid(inviteCode) : inviteCode == null);
        if (!shape) throw new CityGameException(400, "CITY_COMMAND_INVALID", "参与指令无效");

        // Lock the board row first (a locking read, so the receipt read below sees every commit made
        // before the lock was granted); a concurrent duplicate then waits and replays.
        Map<String, Object> board = mapper.selectBoardByRelease(releaseId);
        Map<String, Object> previous = mapper.selectRequest(memberId, DOMAIN, requestId);
        if (previous != null) return replay(previous, hash);
        if (board == null || !regionId.equals(board.get("regionId")) || !contentHash.equals(board.get("rulesHash"))) {
            return finish(memberId, requestId, hash, kind, board, 409, "CITY_RELEASE_CHANGED", null, 0);
        }
        // Lifecycle rule: team commands are actions and need an OPEN board; a FROZEN board is settled and read-only.
        String lifecycle = (String) board.get("lifecycle");
        if (!"OPEN".equals(lifecycle)) {
            return finish(memberId, requestId, hash, kind, board, 409, "CITY_BOARD_NOT_OPEN", null, 0);
        }
        String boardId = (String) board.get("boardId");
        Map<String, Object> mine = mapper.selectParticipationForUpdate(boardId, memberId);

        switch (kind) {
            case "CREATE": {
                if (mine != null) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ALREADY_IN_TEAM", (String) mine.get("teamId"), 0);
                if (expectedVersion != 0) return finish(memberId, requestId, hash, kind, board, 409, "VERSION_CONFLICT", null, 0);
                String newTeam = UUID.randomUUID().toString();
                Map<String, Object> team = new LinkedHashMap<>();
                team.put("teamId", newTeam);
                team.put("boardId", boardId);
                team.put("captainMemberId", memberId);
                team.put("inviteCode", UUID.randomUUID().toString());
                mapper.insertTeam(team);
                insertMember(boardId, newTeam, memberId);
                return finish(memberId, requestId, hash, kind, board, 200, null, newTeam, 1);
            }
            case "JOIN": {
                Map<String, Object> team = mapper.selectTeamByInvite(inviteCode);
                if (team == null || !teamId.equals(team.get("teamId")) || !boardId.equals(team.get("boardId"))) {
                    return finish(memberId, requestId, hash, kind, board, 409, "CITY_INVITE_INVALID", null, 0);
                }
                if (mine != null) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ALREADY_IN_TEAM", (String) mine.get("teamId"), 0);
                if (version(team) != expectedVersion) return finish(memberId, requestId, hash, kind, board, 409, "VERSION_CONFLICT", teamId, version(team));
                if (!"FORMING".equals(team.get("state"))) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ROSTER_LOCKED", teamId, version(team));
                if (mapper.selectTeamMembers(teamId).size() >= ((Number) board.get("maxMembers")).intValue()) {
                    return finish(memberId, requestId, hash, kind, board, 409, "CITY_TEAM_FULL", teamId, version(team));
                }
                insertMember(boardId, teamId, memberId);
                mapper.updateTeamState(teamId, "FORMING", null, null);
                return finish(memberId, requestId, hash, kind, board, 200, null, teamId, version(team) + 1);
            }
            default:
                break;
        }

        if (mine == null || !teamId.equals(mine.get("teamId"))) {
            return finish(memberId, requestId, hash, kind, board, 409, "CITY_NOT_IN_TEAM", null, 0);
        }
        Map<String, Object> team = mapper.selectTeamForUpdate(teamId);
        if (version(team) != expectedVersion) return finish(memberId, requestId, hash, kind, board, 409, "VERSION_CONFLICT", teamId, version(team));
        boolean captain = ((Number) team.get("captainMemberId")).longValue() == memberId;
        boolean forming = "FORMING".equals(team.get("state"));
        String participationId = (String) mine.get("participationId");
        List<Map<String, Object>> members = mapper.selectTeamMembers(teamId);

        switch (kind) {
            case "LOCK": {
                if (!captain) return finish(memberId, requestId, hash, kind, board, 409, "CITY_CAPTAIN_ONLY", teamId, version(team));
                if (!forming) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ROSTER_LOCKED", teamId, version(team));
                if (members.size() < ((Number) board.get("minMembers")).intValue()) {
                    return finish(memberId, requestId, hash, kind, board, 409, "CITY_TEAM_TOO_SMALL", teamId, version(team));
                }
                mapper.updateTeamState(teamId, "LOCKED", UUID.randomUUID().toString(), "AWAITING_CONFIRMATION");
                return finish(memberId, requestId, hash, kind, board, 200, null, teamId, version(team) + 1);
            }
            case "CONFIRM": {
                if (forming) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ROSTER_NOT_LOCKED", teamId, version(team));
                if (flag(mine.get("confirmed"))) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ALREADY_CONFIRMED", teamId, version(team));
                if (!flag(mine.get("present"))) return finish(memberId, requestId, hash, kind, board, 409, "CITY_NOT_PRESENT", teamId, version(team));
                mapper.updateParticipation(participationId, 1, 1);
                boolean everyone = members.stream().allMatch(r -> r.get("participationId").equals(participationId) || flag(r.get("confirmed")));
                String sessionState = everyone ? "READY" : "AWAITING_CONFIRMATION";
                mapper.updateTeamState(teamId, "LOCKED", (String) team.get("sessionId"), sessionState);
                if (everyone && mapper.selectLastRound(boardId, teamId) == null) {
                    mapper.insertRound(boardId, teamId, 1, ((Number) board.get("actionsPerRound")).intValue());
                }
                return finish(memberId, requestId, hash, kind, board, 200, null, teamId, version(team) + 1);
            }
            case "EXIT": {
                if (!flag(mine.get("present"))) return finish(memberId, requestId, hash, kind, board, 409, "CITY_NOT_PRESENT", teamId, version(team));
                if (forming) {
                    if (captain && members.size() > 1) return finish(memberId, requestId, hash, kind, board, 409, "CITY_CAPTAIN_HAS_MEMBERS", teamId, version(team));
                    mapper.deleteParticipation(participationId);
                } else {
                    // After lock the seat is kept for RESUME; replacement is forbidden.
                    mapper.updateParticipation(participationId, 0, flag(mine.get("confirmed")) ? 1 : 0);
                }
                mapper.updateTeamState(teamId, (String) team.get("state"), (String) team.get("sessionId"), (String) team.get("sessionState"));
                return finish(memberId, requestId, hash, kind, board, 200, null, teamId, version(team) + 1);
            }
            case "RESUME": {
                if (flag(mine.get("present"))) return finish(memberId, requestId, hash, kind, board, 409, "CITY_ALREADY_PRESENT", teamId, version(team));
                mapper.updateParticipation(participationId, 1, flag(mine.get("confirmed")) ? 1 : 0);
                mapper.updateTeamState(teamId, (String) team.get("state"), (String) team.get("sessionId"), (String) team.get("sessionState"));
                return finish(memberId, requestId, hash, kind, board, 200, null, teamId, version(team) + 1);
            }
            default:
                throw new CityGameException(400, "CITY_COMMAND_INVALID", "参与指令无效");
        }
    }

    /** GET /api/city-game/team-participation/request/{requestId}: the stored original outcome. */
    public Outcome receipt(Long memberId, String requestId) {
        requireMember(memberId);
        if (!CityGameRules.id(requestId)) throw new CityGameException(400, "requestId", "requestId invalid");
        Map<String, Object> row = mapper.selectRequest(memberId, DOMAIN, requestId);
        if (row == null) throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        return outcome(row);
    }

    private void insertMember(String boardId, String teamId, long memberId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("participationId", UUID.randomUUID().toString());
        row.put("boardId", boardId);
        row.put("teamId", teamId);
        row.put("memberId", memberId);
        mapper.insertParticipation(row);
    }

    private Outcome replay(Map<String, Object> previous, String hash) {
        if (!hash.equals(previous.get("requestHash"))) throw new CityGameException(409, "CITY_REQUEST_REUSED", "请求编号已用于其他指令");
        return outcome(previous);
    }

    private Outcome finish(long memberId, String requestId, String hash, String kind, Map<String, Object> board,
                           int status, String errorCode, String teamId, long version) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("memberId", memberId);
        row.put("domain", DOMAIN);
        row.put("requestId", requestId);
        row.put("boardId", board == null ? "-" : board.get("boardId"));
        row.put("action", kind);
        row.put("requestHash", hash);
        row.put("httpStatus", status);
        row.put("decision", status == 200 ? "APPLIED" : "REJECTED");
        row.put("currentVersion", version);
        row.put("reasonCode", errorCode);
        row.put("taskAttemptKept", 0);
        row.put("pointsCharged", 0);
        row.put("diceFace", null);
        mapper.insertRequest(row);
        Map<String, Object> data = data(requestId, kind, teamId == null ? null : teamId, version);
        return new Outcome(status, errorCode, data);
    }

    private Outcome outcome(Map<String, Object> row) {
        int status = ((Number) row.get("httpStatus")).intValue();
        return new Outcome(status, (String) row.get("reasonCode"),
                data((String) row.get("requestId"), (String) row.get("action"), null, ((Number) row.get("currentVersion")).longValue()));
    }

    private static Map<String, Object> data(String requestId, String kind, String teamId, long version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("requestStatus", "COMPLETE");
        data.put("kind", kind);
        if (teamId != null) data.put("teamId", teamId);
        data.put("teamVersion", version);
        return data;
    }

    private static long version(Map<String, Object> team) { return ((Number) team.get("version")).longValue(); }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Long longValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        return null;
    }
}
