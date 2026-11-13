package com.chengyinhub.business.citygame;

import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import com.chengyinhub.business.mapper.CityGameMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.chengyinhub.business.citygame.CityGameSupport.*;

/**
 * CITY read surfaces: the player read (current / participation / points), the bounded current
 * snapshot, the published player rules and the team-participation state. Reads never grant an action
 * and never name or locate another team.
 */
@Service
public class CityGameReadService {
    static final String READ_CONTRACT = "CITY_PLAYER_READ_V1";
    private static final BigDecimal MAX_SPAN = new BigDecimal("0.5");
    private static final String COORDINATE = "-?(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,12})?";

    private final CityGameMapper mapper;
    private final LongSupplier clock;

    public CityGameReadService(CityGameMapper mapper) { this(mapper, System::currentTimeMillis); }

    CityGameReadService(CityGameMapper mapper, LongSupplier clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---- player read: /api/city-game/{current,participation,points} ----

    public Map<String, Object> current(Long memberId, String regionId) {
        requireMember(memberId);
        requireId(regionId, "regionId");
        Map<String, Object> out = envelope();
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        if (board == null) {
            out.put("status", "NO_CURRENT_BOARD");
            out.put("regionId", regionId);
            return out;
        }
        Membership membership = membership(mapper, (String) board.get("boardId"), memberId);
        out.put("status", "AVAILABLE");
        out.put("regionId", regionId);
        out.put("board", wireBoard(board));
        out.put("participationStatus", membership.wireStatus());
        out.put("pointsStatus", "AVAILABLE");
        return out;
    }

    public Map<String, Object> participation(Long memberId, String regionId, String boardId, String seasonId, String rulesReleaseId, String revision) {
        Map<String, Object> board = boundBoard(memberId, regionId, boardId, seasonId, rulesReleaseId, revision);
        Membership membership = membership(mapper, boardId, memberId);
        Map<String, Object> out = available(board);
        out.put("participationStatus", membership.wireStatus());
        out.put("participation", membership.wireParticipation());
        return out;
    }

    public Map<String, Object> points(Long memberId, String regionId, String boardId, String seasonId, String rulesReleaseId, String revision) {
        Map<String, Object> board = boundBoard(memberId, regionId, boardId, seasonId, rulesReleaseId, revision);
        Membership membership = membership(mapper, boardId, memberId);
        Map<String, String> ownership = ownership(boardId, membership);
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectPoints(boardId)) {
            if (points.size() == 200) break;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("pointId", row.get("pointId"));
            point.put("title", row.get("title"));
            point.put("latitude", ((BigDecimal) row.get("latitude")).doubleValue());
            point.put("longitude", ((BigDecimal) row.get("longitude")).doubleValue());
            point.put("mine", "OUR_TEAM".equals(ownership.get(row.get("pointId"))));
            points.add(point);
        }
        Map<String, Object> out = available(board);
        out.put("points", points);
        out.put("complete", true);
        return out;
    }

    private Map<String, Object> boundBoard(Long memberId, String regionId, String boardId, String seasonId, String rulesReleaseId, String revision) {
        requireMember(memberId);
        requireId(regionId, "regionId");
        requireId(boardId, "boardId");
        requireId(seasonId, "seasonId");
        requireId(rulesReleaseId, "rulesReleaseId");
        long rev = requireVersion(revision, "revision");
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        requireSameBoard(board, boardId, seasonId, rulesReleaseId, rev);
        return board;
    }

    private Map<String, Object> envelope() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", READ_CONTRACT);
        out.put("scope", "CITY");
        return out;
    }

    private Map<String, Object> available(Map<String, Object> board) {
        Map<String, Object> out = envelope();
        out.put("status", "AVAILABLE");
        out.put("regionId", board.get("regionId"));
        out.put("board", wireBoard(board));
        return out;
    }

    /** pointId → OUR_TEAM / OTHER_TEAM / UNOWNED for points that carry a property. */
    private Map<String, String> ownership(String boardId, Membership membership) {
        Map<String, String> out = new HashMap<>();
        String mine = membership.joined() ? membership.teamId() : null;
        for (Map<String, Object> property : mapper.selectProperties(boardId)) {
            String holder = (String) property.get("holderTeamId");
            String value = holder == null ? "UNOWNED" : holder.equals(mine) ? "OUR_TEAM" : "OTHER_TEAM";
            out.merge((String) property.get("pointId"), value, (a, b) -> "OUR_TEAM".equals(a) || "OUR_TEAM".equals(b) ? "OUR_TEAM" : a);
        }
        return out;
    }

    // ---- bounded current snapshot: /api/city-game/state/snapshot ----

    public Map<String, Object> stateSnapshot(Long memberId, Map<String, String> q) {
        requireMember(memberId);
        String regionId = requireId(q.get("regionId"), "regionId");
        String boardId = requireId(q.get("boardId"), "boardId");
        String seasonId = requireId(q.get("seasonId"), "seasonId");
        String rulesReleaseId = requireId(q.get("rulesReleaseId"), "rulesReleaseId");
        long revision = requireVersion(q.get("revision"), "revision");
        BigDecimal south = coordinate(q.get("south"), "south"), west = coordinate(q.get("west"), "west");
        BigDecimal north = coordinate(q.get("north"), "north"), east = coordinate(q.get("east"), "east");
        if (!bounded(south, west, north, east)) throw new CityGameException(400, "viewport", "viewport invalid");
        String datum = q.get("datum");
        if (!"WGS84".equals(datum) && !"GCJ02".equals(datum)) throw new CityGameException(400, "datum", "datum invalid");
        int zoom = smallInt(q.get("zoom"), 0, 22, "zoom");
        int limit = smallInt(q.get("limit"), 1, 50, "limit");
        String rawLayers = q.get("layers");
        List<String> layers = rawLayers == null ? List.of() : Arrays.asList(rawLayers.split(",", -1));
        if (layers.isEmpty() || !layers.stream().allMatch(l -> l.equals("MERCHANT") || l.equals("POI") || l.equals("PLATFORM_VIRTUAL"))
                || layers.stream().distinct().count() != layers.size() || !layers.equals(layers.stream().sorted().collect(java.util.stream.Collectors.toList()))) {
            throw new CityGameException(400, "layers", "layers invalid");
        }

        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        requireSameBoard(board, boardId, seasonId, rulesReleaseId, revision);
        if (!"FROZEN".equals(board.get("lifecycle"))) throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "城市棋盘未处于冻结发布");
        if (!datum.equals(board.get("mapDatum"))) throw new CityGameException(422, "CITY_DATUM_UNAVAILABLE", "坐标系不可用");
        int minZoom = ((Number) board.get("mapMinZoom")).intValue(), maxZoom = ((Number) board.get("mapMaxZoom")).intValue();
        if (zoom < minZoom || zoom > maxZoom) throw new CityGameException(400, "zoom", "zoom out of range");

        Membership membership = membership(mapper, boardId, memberId);
        Map<String, String> ownership = ownership(boardId, membership);
        long now = clock.getAsLong();
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectPoints(boardId)) {
            BigDecimal lat = (BigDecimal) row.get("latitude"), lng = (BigDecimal) row.get("longitude");
            if (!layers.contains((String) row.get("domain")) || lat.compareTo(south) < 0 || lat.compareTo(north) > 0
                    || lng.compareTo(west) < 0 || lng.compareTo(east) > 0) continue;
            if (millis(row.get("collectedAt")) > now) continue;
            if (points.size() == limit) throw new CityGameException(422, "CITY_VIEWPORT_TOO_DENSE", "视野内点位过多，请放大地图");
            String owner = ownership.getOrDefault((String) row.get("pointId"), "NOT_EVALUATED");
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("pointId", row.get("pointId"));
            point.put("title", row.get("title"));
            point.put("latitude", lat.stripTrailingZeros());
            point.put("longitude", lng.stripTrailingZeros());
            point.put("domain", row.get("domain"));
            point.put("datum", datum);
            point.put("collectedAt", iso(row.get("collectedAt")));
            point.put("coordinateAssurance", "PLATFORM_REVIEWED_METADATA");
            point.put("availability", row.get("availability"));
            point.put("merchantVersion", ((Number) row.get("merchantVersion")).longValue());
            point.put("platformVersion", ((Number) row.get("platformVersion")).longValue());
            point.put("mine", "OUR_TEAM".equals(owner));
            point.put("ownership", owner);
            point.put("grantsNavigation", false);
            point.put("grantsAction", false);
            points.add(point);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "CITY_CURRENT_SNAPSHOT_V1");
        out.put("scope", "CITY");
        out.put("mode", "CURRENT_SNAPSHOT");
        out.put("deltaStatus", "DELTAS_UNAVAILABLE");
        out.put("deltaReason", "AUTHORIZATION_LIFETIME_UNAVAILABLE");
        out.put("board", wireBoard(board));
        out.put("boardVersion", revision);
        out.put("membership", membership.wireStatus());
        out.put("participation", membership.wireParticipation());
        out.put("points", points);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contract", "CITY_BOUNDED_MAP_V1");
        map.put("datum", datum);
        map.put("initialViewport", bounds(board.get("mapSouth"), board.get("mapWest"), board.get("mapNorth"), board.get("mapEast")));
        map.put("initialZoom", ((Number) board.get("mapZoom")).intValue());
        map.put("maxViewportSpanDegrees", MAX_SPAN);
        map.put("minZoom", minZoom);
        map.put("maxZoom", maxZoom);
        map.put("pageSize", 50);
        map.put("maxPages", 4);
        out.put("mapRead", map);
        out.put("viewport", bounds(south, west, north, east));
        out.put("datum", datum);
        out.put("zoom", zoom);
        out.put("layers", layers);
        out.put("complete", true);
        out.put("capturedAtMillis", now);
        out.put("validUntilMillis", now + 60_000L);
        out.put("grantsNavigation", false);
        out.put("grantsAction", false);
        return out;
    }

    private static Map<String, Object> bounds(Object south, Object west, Object north, Object east) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("south", ((BigDecimal) south).stripTrailingZeros());
        out.put("west", ((BigDecimal) west).stripTrailingZeros());
        out.put("north", ((BigDecimal) north).stripTrailingZeros());
        out.put("east", ((BigDecimal) east).stripTrailingZeros());
        return out;
    }

    static boolean bounded(BigDecimal s, BigDecimal w, BigDecimal n, BigDecimal e) {
        return s.compareTo(n) < 0 && w.compareTo(e) < 0 && s.compareTo(new BigDecimal(-85)) >= 0 && n.compareTo(new BigDecimal(85)) <= 0
                && w.compareTo(new BigDecimal(-180)) >= 0 && e.compareTo(new BigDecimal(180)) <= 0
                && n.subtract(s).compareTo(MAX_SPAN) <= 0 && e.subtract(w).compareTo(MAX_SPAN) <= 0;
    }

    private static BigDecimal coordinate(String value, String field) {
        if (value == null || !value.matches(COORDINATE)) throw new CityGameException(400, field, field + " invalid");
        return new BigDecimal(value);
    }

    private static int smallInt(String value, int min, int max, String field) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,2}")) throw new CityGameException(400, field, field + " invalid");
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) throw new CityGameException(400, field, field + " invalid");
        return parsed;
    }

    // ---- published player rules: /api/city-game/player-rules ----

    public Map<String, Object> playerRules(Long memberId, Map<String, String> q) {
        requireMember(memberId);
        String regionId = requireId(q.get("regionId"), "regionId");
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        if (board == null) throw new CityGameException(404, "CITY_RULES_NOT_PUBLISHED", "规则未发布");
        requireSameBoard(board, q.get("boardId"), q.get("seasonId"), q.get("rulesReleaseId"), requireVersion(q.get("revision"), "revision"));
        if (!board.get("gameId").equals(q.get("gameId")) || !board.get("rulesHash").equals(q.get("rulesHash"))) {
            throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "城市棋盘已更新");
        }
        if (!"FROZEN".equals(board.get("lifecycle"))) throw new CityGameException(404, "CITY_RULES_NOT_PUBLISHED", "规则未发布");
        Membership membership = membership(mapper, (String) board.get("boardId"), memberId);
        String claimed = q.get("membership");
        if (claimed == null || !claimed.equals(membership.wireStatus())) throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "参与状态已变化");
        if (membership.joined() && (!membership.sessionId().equals(q.get("participationId"))
                || !String.valueOf(membership.teamVersion()).equals(q.get("membershipVersion")))) {
            throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "参与状态已变化");
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(section("rules", "PUBLISHED", "rulesId", board.get("rulesId"), "text", board.get("rulesText")));
        sections.add(section("protection", "PUBLISHED", "challengeId", board.get("challengeRuleId"), "text", board.get("protectionText")));
        sections.add(section("safety", "PUBLISHED", "statement", board.get("safetyStatement")));
        sections.add(section("dice", "PUBLISHED", "diceId", board.get("diceId"), "faces", board.get("diceFaces"), "weights", board.get("diceWeights")));
        sections.add(section("pointCost", "PUBLISHED", "unit", "POINT", "amount", board.get("acquirePoints")));
        sections.add(section("serviceFee", "PUBLISHED", "amountMinor", "0", "currency", "CNY"));
        sections.add(section("participation", "PUBLISHED", "mode", "CITY_TEAM", "minMembers", board.get("minMembers"),
                "maxMembers", board.get("maxMembers"), "joinPolicy", "INVITE_CODE", "rosterPolicy", "CAPTAIN_LOCK_THEN_EACH_MEMBER_CONFIRM",
                "replacementPolicy", "FORBIDDEN_AFTER_LOCK", "reentryPolicy", "RESUME_ORIGINAL_SESSION", "roundPolicy", "FIXED_PUBLISHED_ROUND",
                "roundId", board.get("seasonId")));
        sections.add(section("actionBudget", "PUBLISHED", "scope", "TEAM_ROUND", "unit", "ACTION", "totalUnits", board.get("actionsPerRound"),
                "rollCost", board.get("rollCost"), "acquisitionCost", board.get("acquisitionCost"), "refillPolicy", "NONE_WITHIN_ROUND",
                "chargeTiming", "COMMITTED_DICE_RESULT", "actionKind", "DICE_ROLL_AND_ACQUIRE",
                "resultPolicy", "DICE_RECORD_ONLY_ACQUISITION_EXPLICIT_CONFIRM"));
        List<Object> acquisition = new ArrayList<>(List.of("mode", "TASK_ACTOR_SELF_PAY_FOR_ORIGINAL_TEAM_UNOWNED_ONLY",
                "evidenceAcceptance", "MERCHANT_ATTESTED", "evidencePolicy", "ORIGINAL_TASK_EVIDENCE_VALID_AT_CONFIRM",
                "evidenceAcceptanceReason", "到店任务完成后由平台记录",
                "releasePolicy", "CURRENT_FROZEN_RELEASE_ONLY", "termSeconds", board.get("termSeconds"),
                "quoteValidSeconds", board.get("quoteValidSeconds"), "termPolicy", "RETAIN_TITLE_PENDING_REVIEW",
                "pausePolicy", "FREEZE_TERM_REVIEW_BEFORE_RESUME"));
        if (board.get("holdingLimit") != null) { acquisition.add("maxHoldingsPerTeam"); acquisition.add(board.get("holdingLimit")); }
        sections.add(section("acquisition", "PUBLISHED", acquisition.toArray()));
        sections.add(section("season", "PUBLISHED", "seasonId", board.get("seasonId")));
        sections.add(section("effectiveInterval", "NOT_PUBLISHED"));
        sections.add(section("settlement", "INTERNAL_BUDGET_ONLY"));
        sections.add(section("taskDetails", "CONTEXTUAL_READ_ONLY"));
        sections.add(section("locationDetails", "CONTEXTUAL_READ_ONLY"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "CITY_PLAYER_RULES_V1");
        out.put("scope", "CITY");
        out.put("status", "AVAILABLE");
        out.put("board", wireBoard(board));
        out.put("releaseId", ((Number) board.get("releaseId")).longValue());
        out.put("sections", sections);
        out.put("eligibility", eligibility(membership));
        out.put("grantsParticipation", false);
        out.put("grantsAction", false);
        return out;
    }

    private Map<String, Object> eligibility(Membership m) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (m.participation == null) {
            out.put("status", "NO_QUALIFICATION");
            return out;
        }
        List<Map<String, Object>> members = mapper.selectTeamMembers(m.teamId());
        boolean ready = "READY".equals(m.team.get("sessionState"));
        String status = !m.present() ? "ABSENT" : m.sessionId() == null ? "FORMING" : !m.confirmed() ? "CONFIRMATION_REQUIRED"
                : ready ? "MEMBERSHIP_CONFIRMED" : "TEAM_WAITING";
        out.put("status", status);
        out.put("teamId", m.teamId());
        out.put("sessionId", m.sessionId());
        out.put("teamVersion", m.teamVersion());
        out.put("sessionVersion", m.sessionId() == null ? null : ((Number) m.team.get("sessionVersion")).longValue());
        out.put("present", m.present());
        out.put("confirmed", m.confirmed());
        out.put("memberCount", members.size());
        out.put("presentCount", members.stream().filter(r -> flag(r.get("present"))).count());
        out.put("confirmedCount", members.stream().filter(r -> flag(r.get("confirmed"))).count());
        return out;
    }

    private static Map<String, Object> section(String id, String status, Object... pairs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", pairs[i]);
            row.put("value", String.valueOf(pairs[i + 1]));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        out.put("rows", rows);
        return out;
    }

    /** The frozen release a team binds to (shared by team state and invite preview). */
    private static Map<String, Object> binding(Map<String, Object> board) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("mode", "CITY_TEAM");
        rules.put("minMembers", board.get("minMembers"));
        rules.put("maxMembers", board.get("maxMembers"));
        rules.put("joinPolicy", "INVITE_CODE");
        rules.put("rosterPolicy", "CAPTAIN_LOCK_THEN_EACH_MEMBER_CONFIRM");
        rules.put("replacementPolicy", "FORBIDDEN_AFTER_LOCK");
        rules.put("reentryPolicy", "RESUME_ORIGINAL_SESSION");
        rules.put("roundPolicy", "FIXED_PUBLISHED_ROUND");
        rules.put("roundId", board.get("seasonId"));
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("releaseId", ((Number) board.get("releaseId")).longValue());
        binding.put("regionId", board.get("regionId"));
        binding.put("boardId", board.get("boardId"));
        binding.put("seasonId", board.get("seasonId"));
        binding.put("rulesReleaseId", board.get("rulesReleaseId"));
        binding.put("contentHash", board.get("rulesHash"));
        binding.put("revision", ((Number) board.get("revision")).longValue());
        binding.put("title", board.get("title"));
        binding.put("rules", rules);

        return binding;
    }

    /** GET /api/city-game/team-participation/invite: preview the team behind an invite code. */
    public Map<String, Object> invite(Long memberId, String regionId, String code) {
        requireMember(memberId);
        requireId(regionId, "regionId");
        if (!CityGameRules.uuid(code)) throw new CityGameException(400, "code", "code invalid");
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        Map<String, Object> team = board == null ? null : mapper.selectTeamByCode(code);
        if (team == null || !board.get("boardId").equals(team.get("boardId")) || !"FORMING".equals(team.get("state"))) {
            throw new CityGameException(404, "CITY_INVITE_INVALID", "邀请码无效或队伍已锁定");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("binding", binding(board));
        out.put("teamId", team.get("teamId"));
        out.put("version", ((Number) team.get("version")).longValue());
        out.put("memberCount", mapper.selectTeamMembers((String) team.get("teamId")).size());
        return out;
    }

    // ---- team participation state: /api/city-game/team-participation/state ----

    public Map<String, Object> teamState(Long memberId, String regionId) {
        requireMember(memberId);
        requireId(regionId, "regionId");
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        if (board == null) throw new CityGameException(404, "CITY_NO_CURRENT_BOARD", "当前区域没有城市棋盘");
        Membership m = membership(mapper, (String) board.get("boardId"), memberId);
        Map<String, Object> binding = binding(board);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "CITY_TEAM_PARTICIPATION_V1");
        out.put("scope", "CITY");
        out.put("binding", binding);
        out.put("writeApproved", "OPEN".equals(board.get("lifecycle")) || "FROZEN".equals(board.get("lifecycle")));
        // This surface reports membership only; points, tasks and budget are read from the game snapshot.
        out.put("pointsStatus", "UNAVAILABLE");
        out.put("taskExecutionStatus", "UNAVAILABLE");
        out.put("actionBudgetStatus", "UNAVAILABLE");
        if (m.participation == null) {
            out.put("status", "NOT_JOINED");
            out.put("team", null);
            return out;
        }
        List<Map<String, Object>> members = mapper.selectTeamMembers(m.teamId());
        boolean captain = ((Number) m.team.get("captainMemberId")).longValue() == memberId;
        boolean forming = "FORMING".equals(m.team.get("state"));
        Map<String, Object> team = new LinkedHashMap<>();
        team.put("teamId", m.teamId());
        team.put("version", m.teamVersion());
        team.put("state", m.team.get("state"));
        team.put("captain", captain);
        team.put("memberCount", members.size());
        team.put("presentCount", members.stream().filter(r -> flag(r.get("present"))).count());
        team.put("confirmedCount", members.stream().filter(r -> flag(r.get("confirmed"))).count());
        team.put("present", m.present());
        team.put("confirmed", m.confirmed());
        team.put("inviteCode", captain && forming ? m.team.get("inviteCode") : null);
        if (m.sessionId() != null) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", m.sessionId());
            session.put("version", ((Number) m.team.get("sessionVersion")).longValue());
            session.put("state", m.team.get("sessionState"));
            team.put("session", session);
        } else {
            team.put("session", null);
        }
        out.put("status", m.present() && m.confirmed() ? "JOINED" : "PENDING");
        out.put("team", team);
        return out;
    }
}
