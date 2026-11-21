package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.mapper.CityGameMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin board authoring, publishing, freeze, merchant-ops approval, fairness reporting,
 * and roll audit service (replaces SQL-seed-only setup).
 */
@Service
public class CityBoardAdminService {
    private final JdbcTemplate jdbc;
    private final CityGameMapper mapper;
    private final CityFairnessService fairness;
    private final RoamTerritoryService territories;
    private final java.util.function.LongSupplier clock;

    @Autowired
    public CityBoardAdminService(JdbcTemplate jdbc, CityGameMapper mapper,
                                 CityFairnessService fairness, RoamTerritoryService territories) {
        this(jdbc, mapper, fairness, territories, System::currentTimeMillis);
    }

    CityBoardAdminService(JdbcTemplate jdbc, CityGameMapper mapper,
                          CityFairnessService fairness, RoamTerritoryService territories,
                          java.util.function.LongSupplier clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.fairness = fairness;
        this.territories = territories;
        this.clock = clock;
    }

    /** List all board drafts plus any SQL-seeded boards without a draft row yet. */
    public List<Map<String, Object>> listBoards(String regionId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.board_id AS boardId, COALESCE(d.draft_id, b.board_id) AS draftId, "
                        + "COALESCE(b.region_id, d.region_id) AS regionId, COALESCE(b.season_id, d.season_id) AS seasonId, "
                        + "COALESCE(b.title, d.title) AS title, COALESCE(b.lifecycle, d.draft_status) AS lifecycle, "
                        + "COALESCE(d.draft_status, b.lifecycle) AS draftStatus, b.release_id AS releaseId, "
                        + "COALESCE(b.rules_hash, d.published_rules_hash) AS rulesHash, "
                        + "b.rule_version AS ruleVersion, b.board_version AS boardVersion, b.revision, "
                        + "b.actions_per_round AS actionsPerRound, b.max_rounds AS maxRounds, "
                        + "b.dice_faces AS diceFaces, b.dice_weights AS diceWeights, "
                        + "d.version AS draftVersion, d.published_at AS publishedAt, d.frozen_at AS frozenAt "
                        + "FROM city_game_board b LEFT JOIN city_game_board_draft d ON d.board_id = b.board_id "
                        + "UNION "
                        + "SELECT d.board_id AS boardId, d.draft_id AS draftId, d.region_id AS regionId, d.season_id AS seasonId, "
                        + "d.title, COALESCE(b.lifecycle, d.draft_status) AS lifecycle, d.draft_status AS draftStatus, "
                        + "COALESCE(b.release_id, d.published_release_id) AS releaseId, "
                        + "COALESCE(b.rules_hash, d.published_rules_hash) AS rulesHash, d.rule_version AS ruleVersion, "
                        + "COALESCE(b.board_version, 1) AS boardVersion, COALESCE(b.revision, 1) AS revision, "
                        + "b.actions_per_round AS actionsPerRound, b.max_rounds AS maxRounds, "
                        + "b.dice_faces AS diceFaces, b.dice_weights AS diceWeights, "
                        + "d.version AS draftVersion, d.published_at AS publishedAt, d.frozen_at AS frozenAt "
                        + "FROM city_game_board_draft d LEFT JOIN city_game_board b ON b.board_id = d.board_id "
                        + "WHERE b.board_id IS NULL");
        List<Map<String, Object>> all = jdbc.queryForList(sql.toString());
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : all) {
            if (regionId != null && !regionId.isEmpty() && !regionId.equals(row.get("regionId"))) continue;
            if (status != null && !status.isEmpty() && !status.equals(row.get("lifecycle")) && !status.equals(row.get("draftStatus"))) continue;
            filtered.add(row);
        }
        return filtered;
    }

    /** Return editable full board configuration (draft payload if present, else live DB tables). */
    public Map<String, Object> getBoardDetail(String boardId) {
        CityGameSupport.requireId(boardId, "boardId");
        List<Map<String, Object>> drafts = jdbc.queryForList(
                "SELECT * FROM city_game_board_draft WHERE board_id = ? LIMIT 1", boardId);
        Map<String, Object> live = selectBoardRow(boardId);
        if (drafts.isEmpty() && live == null) {
            throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        }
        Map<String, Object> payload;
        String draftStatus;
        long draftVersion = 1L;
        if (!drafts.isEmpty()) {
            Map<String, Object> d = drafts.get(0);
            payload = new LinkedHashMap<>(JSON.parseObject(String.valueOf(d.get("payload_json"))));
            draftStatus = live != null ? String.valueOf(live.get("lifecycle")) : String.valueOf(d.get("draft_status"));
            draftVersion = ((Number) d.get("version")).longValue();
        } else {
            payload = reconstructFromLive(boardId, live);
            draftStatus = String.valueOf(live.get("lifecycle"));
        }
        payload.put("boardId", boardId);
        payload.put("draftStatus", draftStatus);
        payload.put("draftVersion", draftVersion);
        if (live != null) {
            payload.put("lifecycle", live.get("lifecycle"));
            payload.put("releaseId", live.get("releaseId"));
            payload.put("rulesHash", live.get("rulesHash"));
            payload.put("boardVersion", live.get("boardVersion"));
            payload.put("eventSequence", live.get("eventSequence"));
        }
        return payload;
    }

    /** Fail-closed dice weighted table validation + normalized probability preview via CityGameRules.dice. */
    public Map<String, Object> previewDice(Map<String, Object> body) {
        if (body == null) throw new CityGameException(400, "DICE_INVALID", "骰子配置不能为空");
        String facesJson = toJsonArray(body.get("diceFaces"), body.get("faces"), "[1,2,3,4,5,6]");
        String weightsJson = toJsonArray(body.get("diceWeights"), body.get("weights"), "[1,1,1,1,1,1]");
        CityGameRules.Dice dice = parseDiceOrThrow(facesJson, weightsJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diceFaces", facesJson);
        out.put("diceWeights", weightsJson);
        out.put("totalWeight", dice.totalWeight());
        out.put("faceCount", dice.faceCount());
        out.put("probabilities", dice.probabilityPreview());
        return out;
    }

    /** Create or update an editable board draft (tracks, tiles, properties, tolls, challenges, rounds, dice). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveDraft(Long adminUserId, Map<String, Object> body) {
        if (body == null) throw new CityGameException(400, "BOARD_DRAFT_INVALID", "棋盘草稿不能为空");
        String boardId = str(body, "boardId");
        if (boardId == null || boardId.trim().isEmpty()) {
            boardId = "board-" + UUID.randomUUID().toString().substring(0, 8);
        }
        CityGameSupport.requireId(boardId, "boardId");
        Map<String, Object> live = selectBoardRow(boardId);
        if (live != null && "FROZEN".equals(live.get("lifecycle"))) {
            throw new CityGameException(409, "BOARD_FROZEN", "已冻结的棋盘不可修改");
        }
        Map<String, Object> normalized = normalizeAndValidatePayload(boardId, body);
        String payloadJson = JSON.toJSONString(normalized);
        String rulesHash = sha256(payloadJson);

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT draft_id, draft_status, version FROM city_game_board_draft WHERE board_id = ? FOR UPDATE", boardId);
        long nextVer;
        if (existing.isEmpty()) {
            String draftId = "draft-" + boardId;
            jdbc.update("INSERT INTO city_game_board_draft(draft_id, board_id, game_id, region_id, season_id, rules_release_id, "
                            + "rule_version, title, draft_status, payload_json, published_rules_hash, version, created_by, updated_by) "
                            + "VALUES (?,?,?,?,?,?,?,?, 'DRAFT', ?, ?, 1, ?, ?)",
                    draftId, boardId, normalized.get("gameId"), normalized.get("regionId"), normalized.get("seasonId"),
                    normalized.get("rulesReleaseId"), normalized.get("ruleVersion"), normalized.get("title"),
                    payloadJson, rulesHash, adminUserId, adminUserId);
            nextVer = 1L;
        } else {
            Map<String, Object> row = existing.get(0);
            if ("FROZEN".equals(row.get("draft_status"))) {
                throw new CityGameException(409, "BOARD_FROZEN", "已冻结的棋盘不可修改");
            }
            nextVer = ((Number) row.get("version")).longValue() + 1L;
            jdbc.update("UPDATE city_game_board_draft SET region_id = ?, season_id = ?, rules_release_id = ?, rule_version = ?, "
                            + "title = ?, payload_json = ?, updated_by = ?, version = ? WHERE board_id = ?",
                    normalized.get("regionId"), normalized.get("seasonId"), normalized.get("rulesReleaseId"),
                    normalized.get("ruleVersion"), normalized.get("title"), payloadJson, adminUserId, nextVer, boardId);
        }
        mapper.insertBoardAudit(boardId, "DRAFT_SAVE", adminUserId, "ADMIN", rulesHash,
                JSON.toJSONString(Map.of("version", nextVer, "regionId", normalized.get("regionId"))));
        Map<String, Object> out = getBoardDetail(boardId);
        out.put("rulesHash", rulesHash);
        out.put("draftVersion", nextVer);
        return out;
    }

    /**
     * Publish draft to live city_game_* tables with SHA-256 rulesHash, unique release_id,
     * and BOARD_PUBLISHED event + audit.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publishBoard(Long adminUserId, String boardId) {
        CityGameSupport.requireId(boardId, "boardId");
        Map<String, Object> detail = getBoardDetail(boardId);
        Map<String, Object> norm = normalizeAndValidatePayload(boardId, detail);
        Map<String, Object> live = selectBoardRow(boardId);
        if (live != null && "FROZEN".equals(live.get("lifecycle"))) {
            throw new CityGameException(409, "BOARD_FROZEN", "已冻结棋盘不能重新发布");
        }

        String canonicalJson = JSON.toJSONString(norm);
        String rulesHash = sha256(canonicalJson);
        long releaseId = live != null ? ((Number) live.get("releaseId")).longValue() : nextReleaseId();
        Timestamp validFrom = parseTimestamp(norm.get("validFromMillis"), new Timestamp(clock.getAsLong()));
        Timestamp validUntil = norm.get("validUntilMillis") == null ? null
                : parseTimestamp(norm.get("validUntilMillis"), null);

        if (live == null) {
            jdbc.update("INSERT INTO city_game_board(board_id, game_id, region_id, season_id, rules_release_id, release_id, "
                            + "rules_hash, rule_version, lifecycle, title, revision, board_version, event_sequence, "
                            + "dice_id, dice_faces, dice_weights, actions_per_round, max_rounds, roll_cost, acquisition_cost, "
                            + "task_cost, service_cost, challenge_cost, acquire_points, challenge_points, holding_yield, goal_score, "
                            + "holding_limit, protection_seconds, quote_valid_seconds, term_seconds, min_members, max_members, "
                            + "rules_id, rules_text, challenge_rule_id, protection_text, safety_statement, valid_from, valid_until, "
                            + "fairness_metric_version, fairness_horizon_seconds, fairness_delta_seconds, "
                            + "map_datum, map_south, map_west, map_north, map_east, map_zoom, map_min_zoom, map_max_zoom) "
                            + "VALUES (?,?,?,?,?,?,?,?, 'OPEN', ?, 1, 1, 1, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'GCJ02', ?,?,?,?,?,?,?)",
                    boardId, norm.get("gameId"), norm.get("regionId"), norm.get("seasonId"), norm.get("rulesReleaseId"),
                    releaseId, rulesHash, norm.get("ruleVersion"), norm.get("title"),
                    norm.get("diceId"), norm.get("diceFaces"), norm.get("diceWeights"),
                    norm.get("actionsPerRound"), norm.get("maxRounds"), norm.get("rollCost"), norm.get("acquisitionCost"),
                    norm.get("taskCost"), norm.get("serviceCost"), norm.get("challengeCost"),
                    norm.get("acquirePoints"), norm.get("challengePoints"), norm.get("holdingYield"), norm.get("goalScore"),
                    norm.get("holdingLimit"), norm.get("protectionSeconds"), norm.get("quoteValidSeconds"), norm.get("termSeconds"),
                    norm.get("minMembers"), norm.get("maxMembers"),
                    norm.get("rulesId"), norm.get("rulesText"), norm.get("challengeRuleId"),
                    norm.get("protectionText"), norm.get("safetyStatement"), validFrom, validUntil,
                    norm.get("fairnessMetricVersion"), norm.get("fairnessHorizonSeconds"), norm.get("fairnessDeltaSeconds"),
                    norm.get("mapSouth"), norm.get("mapWest"), norm.get("mapNorth"), norm.get("mapEast"),
                    norm.get("mapZoom"), norm.get("mapMinZoom"), norm.get("mapMaxZoom"));
        } else {
            jdbc.update("UPDATE city_game_board SET season_id = ?, rules_release_id = ?, rules_hash = ?, rule_version = ?, "
                            + "lifecycle = 'OPEN', title = ?, revision = revision + 1, board_version = board_version + 1, "
                            + "event_sequence = event_sequence + 1, dice_id = ?, dice_faces = ?, dice_weights = ?, "
                            + "actions_per_round = ?, max_rounds = ?, roll_cost = ?, acquisition_cost = ?, "
                            + "task_cost = ?, service_cost = ?, challenge_cost = ?, acquire_points = ?, challenge_points = ?, "
                            + "holding_yield = ?, goal_score = ?, holding_limit = ?, protection_seconds = ?, quote_valid_seconds = ?, "
                            + "term_seconds = ?, min_members = ?, max_members = ?, rules_id = ?, rules_text = ?, "
                            + "challenge_rule_id = ?, protection_text = ?, safety_statement = ?, valid_from = ?, valid_until = ?, "
                            + "fairness_metric_version = ?, fairness_horizon_seconds = ?, fairness_delta_seconds = ?, "
                            + "map_south = ?, map_west = ?, map_north = ?, map_east = ?, map_zoom = ?, map_min_zoom = ?, map_max_zoom = ? "
                            + "WHERE board_id = ?",
                    norm.get("seasonId"), norm.get("rulesReleaseId"), rulesHash, norm.get("ruleVersion"), norm.get("title"),
                    norm.get("diceId"), norm.get("diceFaces"), norm.get("diceWeights"),
                    norm.get("actionsPerRound"), norm.get("maxRounds"), norm.get("rollCost"), norm.get("acquisitionCost"),
                    norm.get("taskCost"), norm.get("serviceCost"), norm.get("challengeCost"),
                    norm.get("acquirePoints"), norm.get("challengePoints"), norm.get("holdingYield"), norm.get("goalScore"),
                    norm.get("holdingLimit"), norm.get("protectionSeconds"), norm.get("quoteValidSeconds"), norm.get("termSeconds"),
                    norm.get("minMembers"), norm.get("maxMembers"), norm.get("rulesId"), norm.get("rulesText"),
                    norm.get("challengeRuleId"), norm.get("protectionText"), norm.get("safetyStatement"), validFrom, validUntil,
                    norm.get("fairnessMetricVersion"), norm.get("fairnessHorizonSeconds"), norm.get("fairnessDeltaSeconds"),
                    norm.get("mapSouth"), norm.get("mapWest"), norm.get("mapNorth"), norm.get("mapEast"),
                    norm.get("mapZoom"), norm.get("mapMinZoom"), norm.get("mapMaxZoom"), boardId);
        }

        // Sync points, track properties, tolls/services, and tasks
        syncPointsAndTrack(boardId, norm, validFrom);

        jdbc.update("INSERT INTO city_game_board_draft(draft_id, board_id, game_id, region_id, season_id, rules_release_id, "
                        + "rule_version, title, draft_status, payload_json, published_release_id, published_rules_hash, version, created_by, updated_by, published_at) "
                        + "VALUES (?,?,?,?,?,?,?,?, 'PUBLISHED', ?, ?, ?, 1, ?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE draft_status = 'PUBLISHED', payload_json = VALUES(payload_json), "
                        + "published_release_id = VALUES(published_release_id), published_rules_hash = VALUES(published_rules_hash), "
                        + "published_at = NOW(), updated_by = VALUES(updated_by), version = version + 1",
                "draft-" + boardId, boardId, norm.get("gameId"), norm.get("regionId"), norm.get("seasonId"),
                norm.get("rulesReleaseId"), norm.get("ruleVersion"), norm.get("title"), canonicalJson, releaseId, rulesHash,
                adminUserId, adminUserId);

        Map<String, Object> fresh = selectBoardRow(boardId);
        mapper.insertEvent(boardId, ((Number) fresh.get("eventSequence")).longValue(), "BOARD_PUBLISHED",
                null, null, ((Number) fresh.get("boardVersion")).longValue());
        mapper.insertBoardAudit(boardId, "BOARD_PUBLISHED", adminUserId, "ADMIN", rulesHash,
                JSON.toJSONString(Map.of("releaseId", releaseId, "spaceCount", ((List<?>) norm.get("spaces")).size())));

        Map<String, Object> out = getBoardDetail(boardId);
        out.put("rulesHash", rulesHash);
        out.put("releaseId", releaseId);
        out.put("lifecycle", "OPEN");
        return out;
    }

    /** Freeze an OPEN board (read-only settled season). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> freezeBoard(Long adminUserId, String boardId) {
        CityGameSupport.requireId(boardId, "boardId");
        Map<String, Object> board = selectBoardRow(boardId);
        if (board == null) throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        if (mapper.freezeBoard(boardId) == 1) {
            Map<String, Object> fresh = selectBoardRow(boardId);
            mapper.insertEvent(boardId, ((Number) fresh.get("eventSequence")).longValue(), "BOARD_FROZEN",
                    null, null, ((Number) fresh.get("boardVersion")).longValue());
        }
        jdbc.update("UPDATE city_game_board_draft SET draft_status = 'FROZEN', frozen_at = NOW(), updated_by = ? WHERE board_id = ?",
                adminUserId, boardId);
        mapper.insertBoardAudit(boardId, "BOARD_FROZEN", adminUserId, "ADMIN", (String) board.get("rulesHash"),
                JSON.toJSONString(Map.of("frozenBy", adminUserId == null ? 0L : adminUserId)));
        return getBoardDetail(boardId);
    }

    // =================================================================== CITY-19 tier & district authoring

    private CityMonopolyConfigService monopolyConfigs;

    @Autowired(required = false)
    public void setMonopolyConfigs(CityMonopolyConfigService monopolyConfigs) { this.monopolyConfigs = monopolyConfigs; }

    private CityMonopolyConfig monopolyConfig(String boardId) {
        return monopolyConfigs == null ? CityMonopolyConfig.defaults() : monopolyConfigs.forBoard(boardId);
    }

    static int requireTier(Object v) {
        int t = v instanceof Number n ? n.intValue() : -1;
        if (!(v instanceof Number) && v != null) {
            try { t = Integer.parseInt(String.valueOf(v).trim().replaceFirst("^[Tt]", "")); } catch (NumberFormatException e) { t = -1; }
        }
        if (t < 1 || t > 4) throw new CityGameException(400, "TIER_INVALID", "地点等级只能是 T1 街角、T2 小店、T3 名店或 T4 地标");
        return t;
    }

    static String requireDistrictName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 64) {
            throw new CityGameException(400, "DISTRICT_NAME_INVALID", "街区名称必填，最多 64 个字");
        }
        return name.trim();
    }

    static String requireColorToken(String token) {
        if (token == null || token.isBlank()) return "district.c1";
        if (!token.trim().matches("district\\.c([1-9]|10)")) {
            throw new CityGameException(400, "DISTRICT_COLOR_INVALID", "街区颜色只能是 district.c1 到 district.c10");
        }
        return token.trim();
    }

    Map<String, Object> boardRowForAuthoring(String boardId) { return selectBoardRow(boardId); }

    private void requireEditableBoard(String boardId) {
        Map<String, Object> live = boardRowForAuthoring(boardId);
        if (live == null) throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        if ("FROZEN".equals(live.get("lifecycle"))) throw new CityGameException(409, "BOARD_FROZEN", "已冻结的棋盘不可修改");
    }

    /** Sets a place's tier (T1 街角 .. T4 地标). New claims pay and earn by it; a place already held keeps its bought tier. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updatePointTier(Long adminUserId, String boardId, String pointId, Object tierValue) {
        CityGameSupport.requireId(boardId, "boardId");
        CityGameSupport.requireId(pointId, "pointId");
        int tier = requireTier(tierValue);
        requireEditableBoard(boardId);
        if (jdbc.update("UPDATE city_game_point SET tier = ? WHERE board_id = ? AND point_id = ? AND del_flag = 0", tier, boardId, pointId) != 1) {
            throw new CityGameException(404, "POINT_NOT_FOUND", "地点不存在");
        }
        patchDraftSpace(boardId, pointId, "tier", tier);
        mapper.insertBoardAudit(boardId, "POINT_TIER", adminUserId, "ADMIN", null, JSON.toJSONString(Map.of("pointId", pointId, "tier", tier)));
        return layout(boardId);
    }

    /** Creates or renames a district (colour group). The board may hold at most city.monopoly.board.districts.max. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upsertDistrict(Long adminUserId, String boardId, String districtId, String name, String colorToken) {
        CityGameSupport.requireId(boardId, "boardId");
        CityGameSupport.requireId(districtId, "districtId");
        String cleanName = requireDistrictName(name);
        String color = requireColorToken(colorToken);
        requireEditableBoard(boardId);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT district_id FROM city_district WHERE board_id = ? FOR UPDATE", boardId);
        boolean known = existing.stream().anyMatch(r -> districtId.equals(r.get("district_id")));
        int max = monopolyConfig(boardId).integer("city.monopoly.board.districts.max");
        if (!known && existing.size() >= max) {
            throw new CityGameException(409, "DISTRICTS_OUT_OF_RANGE", "一张棋盘最多 " + max + " 个街区");
        }
        jdbc.update("INSERT INTO city_district(board_id, district_id, name, color_token) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), color_token = VALUES(color_token)", boardId, districtId, cleanName, color);
        patchDraftDistrict(boardId, districtId, cleanName, color);
        mapper.insertBoardAudit(boardId, known ? "DISTRICT_UPDATE" : "DISTRICT_CREATE", adminUserId, "ADMIN", null,
                JSON.toJSONString(Map.of("districtId", districtId, "name", cleanName, "colorToken", color)));
        recountOwnable(boardId);
        return layout(boardId);
    }

    /** Puts a place in a district (null = no district). A district holds at most city.monopoly.board.properties_per_district.max. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> assignDistrict(Long adminUserId, String boardId, String pointId, String districtId) {
        CityGameSupport.requireId(boardId, "boardId");
        CityGameSupport.requireId(pointId, "pointId");
        String target = districtId == null || districtId.isBlank() ? null : districtId.trim();
        requireEditableBoard(boardId);
        if (target != null) {
            CityGameSupport.requireId(target, "districtId");
            if (jdbc.queryForList("SELECT district_id FROM city_district WHERE board_id = ? AND district_id = ? FOR UPDATE", boardId, target).isEmpty()) {
                throw new CityGameException(404, "DISTRICT_NOT_FOUND", "街区不存在");
            }
            Integer inside = jdbc.queryForObject("SELECT COUNT(*) FROM city_game_point WHERE board_id = ? AND district_id = ? AND point_id <> ? AND del_flag = 0",
                    Integer.class, boardId, target, pointId);
            int max = monopolyConfig(boardId).integer("city.monopoly.board.properties_per_district.max");
            if (inside != null && inside >= max) {
                throw new CityGameException(409, "DISTRICT_FULL", "一个街区最多 " + max + " 个地点");
            }
        }
        if (jdbc.update("UPDATE city_game_point SET district_id = ? WHERE board_id = ? AND point_id = ? AND del_flag = 0", target, boardId, pointId) != 1) {
            throw new CityGameException(404, "POINT_NOT_FOUND", "地点不存在");
        }
        // Held City Monopoly places follow their board place, so set bonus and district challenges see the new group.
        jdbc.update("UPDATE roam_territory SET district_id = ?, version = version + 1 WHERE board_id = ? AND point_id = ? AND status = 'ACTIVE' "
                + "AND rules_kind = 'MONOPOLY'", target, boardId, pointId);
        patchDraftSpace(boardId, pointId, "districtId", target);
        recountOwnable(boardId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pointId", pointId);
        detail.put("districtId", target);
        mapper.insertBoardAudit(boardId, "POINT_DISTRICT", adminUserId, "ADMIN", null, JSON.toJSONString(detail));
        return layout(boardId);
    }

    /** Recounts each district's ownable places (role OPERABLE; neutral places are not ownable). */
    void recountOwnable(String boardId) {
        jdbc.update("UPDATE city_district d SET ownable_count = (SELECT COUNT(*) FROM city_game_point p JOIN city_game_property pr "
                + "ON pr.board_id = p.board_id AND pr.point_id = p.point_id WHERE p.board_id = d.board_id AND p.district_id = d.district_id "
                + "AND p.del_flag = 0 AND pr.role = 'OPERABLE') WHERE d.board_id = ?", boardId);
    }

    /** Tier and district layout of a board with what is still outside the approved ranges. */
    public Map<String, Object> layout(String boardId) {
        CityGameSupport.requireId(boardId, "boardId");
        List<Map<String, Object>> districts = jdbc.queryForList("SELECT district_id AS districtId, name, color_token AS colorToken, "
                + "ownable_count AS ownableCount FROM city_district WHERE board_id = ? ORDER BY district_id", boardId);
        List<Map<String, Object>> points = jdbc.queryForList("SELECT p.point_id AS pointId, p.title, COALESCE(p.tier, 1) AS tier, "
                + "p.district_id AS districtId, COALESCE(pr.role, 'OPERABLE') AS role FROM city_game_point p LEFT JOIN city_game_property pr "
                + "ON pr.board_id = p.board_id AND pr.point_id = p.point_id WHERE p.board_id = ? AND p.del_flag = 0 "
                + "ORDER BY pr.track_index, p.point_id", boardId);
        Map<String, Integer> perDistrict = new LinkedHashMap<>();
        for (Map<String, Object> d : districts) perDistrict.put(String.valueOf(d.get("districtId")), 0);
        int neutral = 0, unassigned = 0;
        for (Map<String, Object> p : points) {
            Object d = p.get("districtId");
            if ("NEUTRAL".equals(p.get("role"))) neutral++;
            else if (d == null) unassigned++;
            if (d != null) perDistrict.merge(String.valueOf(d), 1, Integer::sum);
        }
        CityMonopolyConfig c = monopolyConfig(boardId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardId", boardId);
        out.put("districts", districts);
        out.put("points", points);
        out.put("issues", layoutIssues(c, perDistrict, points.size(), neutral, unassigned));
        Map<String, Object> ranges = new LinkedHashMap<>();
        ranges.put("districtsMin", c.integer("city.monopoly.board.districts.min"));
        ranges.put("districtsMax", c.integer("city.monopoly.board.districts.max"));
        ranges.put("placesPerDistrictMin", c.integer("city.monopoly.board.properties_per_district.min"));
        ranges.put("placesPerDistrictMax", c.integer("city.monopoly.board.properties_per_district.max"));
        ranges.put("neutralShareMin", c.num("city.monopoly.board.neutral_share.min"));
        ranges.put("neutralShareMax", c.num("city.monopoly.board.neutral_share.max"));
        out.put("ranges", ranges);
        return out;
    }

    /** Stable issue codes for the console; empty when the board is inside every approved range. */
    static List<String> layoutIssues(CityMonopolyConfig c, Map<String, Integer> perDistrict, int totalPlaces, int neutralPlaces, int unassigned) {
        List<String> issues = new ArrayList<>();
        int n = perDistrict.size();
        if (n < c.integer("city.monopoly.board.districts.min")) issues.add("DISTRICTS_TOO_FEW");
        if (n > c.integer("city.monopoly.board.districts.max")) issues.add("DISTRICTS_TOO_MANY");
        for (Map.Entry<String, Integer> e : perDistrict.entrySet()) {
            if (e.getValue() < c.integer("city.monopoly.board.properties_per_district.min")) issues.add("DISTRICT_TOO_SMALL:" + e.getKey());
            if (e.getValue() > c.integer("city.monopoly.board.properties_per_district.max")) issues.add("DISTRICT_TOO_LARGE:" + e.getKey());
        }
        double share = totalPlaces == 0 ? 0 : (double) neutralPlaces / totalPlaces;
        if (share < c.num("city.monopoly.board.neutral_share.min") - 1e-9 || share > c.num("city.monopoly.board.neutral_share.max") + 1e-9) {
            issues.add("NEUTRAL_SHARE_OUT_OF_RANGE");
        }
        if (unassigned > 0) issues.add("PLACES_WITHOUT_DISTRICT");
        return issues;
    }

    @SuppressWarnings("unchecked")
    private void patchDraftSpace(String boardId, String pointId, String key, Object value) {
        List<Map<String, Object>> drafts = jdbc.queryForList("SELECT payload_json FROM city_game_board_draft WHERE board_id = ? FOR UPDATE", boardId);
        if (drafts.isEmpty() || drafts.get(0).get("payload_json") == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(JSON.parseObject(String.valueOf(drafts.get(0).get("payload_json"))));
        if (!(payload.get("spaces") instanceof List<?> spaces)) return;
        boolean changed = false;
        for (Object o : spaces) {
            if (o instanceof Map<?, ?> m && pointId.equals(m.get("pointId"))) {
                ((Map<String, Object>) m).put(key, value);
                changed = true;
            }
        }
        if (changed) jdbc.update("UPDATE city_game_board_draft SET payload_json = ? WHERE board_id = ?", JSON.toJSONString(payload), boardId);
    }

    @SuppressWarnings("unchecked")
    private void patchDraftDistrict(String boardId, String districtId, String name, String colorToken) {
        List<Map<String, Object>> drafts = jdbc.queryForList("SELECT payload_json FROM city_game_board_draft WHERE board_id = ? FOR UPDATE", boardId);
        if (drafts.isEmpty() || drafts.get(0).get("payload_json") == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(JSON.parseObject(String.valueOf(drafts.get(0).get("payload_json"))));
        List<Object> districts = payload.get("districts") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("districtId", districtId);
        entry.put("name", name);
        entry.put("colorToken", colorToken);
        boolean replaced = false;
        for (int i = 0; i < districts.size(); i++) {
            if (districts.get(i) instanceof Map<?, ?> m && districtId.equals(m.get("districtId"))) {
                districts.set(i, entry);
                replaced = true;
            }
        }
        if (!replaced) districts.add(entry);
        payload.put("districts", districts);
        jdbc.update("UPDATE city_game_board_draft SET payload_json = ? WHERE board_id = ?", JSON.toJSONString(payload), boardId);
    }

    /** Admin board audit trail: board events + unified dice roll audit + admin/merchant actions. */
    public Map<String, Object> boardAudit(String boardId, Integer limit) {
        CityGameSupport.requireId(boardId, "boardId");
        int capped = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> rollAudit = mapper.selectBoardRollAudit(boardId, capped);
        List<Map<String, Object>> adminAudit = jdbc.queryForList(
                "SELECT id, board_id AS boardId, action, actor_id AS actorId, actor_type AS actorType, "
                        + "rules_hash AS rulesHash, detail_json AS detailJson, create_time AS createTime "
                        + "FROM city_game_board_audit WHERE board_id = ? ORDER BY id DESC LIMIT ?",
                boardId, capped);
        List<Map<String, Object>> events = mapper.selectEvents(boardId, 0L, capped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardId", boardId);
        out.put("rollAudit", rollAudit);
        out.put("adminAudit", adminAudit);
        out.put("boardEvents", events);
        return out;
    }

    /** List merchant pause/restore requests awaiting admin review or already reviewed. */
    public List<Map<String, Object>> merchantOpsQueue(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.merchant_id AS merchantId, COALESCE(m.name, CONCAT('Merchant#', o.merchant_id)) AS merchantName, "
                        + "o.region_id AS regionId, o.version, o.status, o.effective_state AS effectiveState, "
                        + "o.kind, o.request_id AS requestId, o.reason, o.approval_hash AS approvalHash, o.update_time AS updateTime "
                        + "FROM city_merchant_operation o LEFT JOIN mms_merchant m ON m.id = o.merchant_id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" AND o.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY o.update_time DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /** Approve or reject a merchant pause/restore intent and apply runtime state to city_game_point/property. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reviewMerchantOp(Long adminUserId, Map<String, Object> body) {
        if (body == null) throw new CityGameException(400, "REVIEW_INVALID", "审核请求无效");
        Long merchantId = num(body, "merchantId");
        String regionId = str(body, "regionId");
        String decision = str(body, "decision");
        String reason = str(body, "reason");
        if (merchantId == null || !CityGameRules.id(regionId) || !List.of("APPROVE", "REJECT").contains(decision)) {
            throw new CityGameException(400, "REVIEW_INVALID", "merchantId / regionId / decision 无效");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM city_merchant_operation WHERE merchant_id = ? AND region_id = ? FOR UPDATE",
                merchantId, regionId);
        if (rows.isEmpty()) throw new CityGameException(404, "MERCHANT_OP_NOT_FOUND", "门店运营申请不存在");
        Map<String, Object> row = rows.get(0);
        if (!"IN_REVIEW".equals(row.get("status"))) {
            throw new CityGameException(409, "INVALID_STATE", "仅审核中的申请可以审批");
        }
        String kind = str(row, "kind");
        String note = reason != null && !reason.trim().isEmpty() ? reason.trim() : (String) row.get("reason");
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        String boardId = board == null ? regionId : (String) board.get("boardId");

        if ("REJECT".equals(decision)) {
            jdbc.update("UPDATE city_merchant_operation SET status = 'REJECTED', version = version + 1, reason = ? "
                            + "WHERE merchant_id = ? AND region_id = ?",
                    note, merchantId, regionId);
            mapper.insertBoardAudit(boardId, "MERCHANT_OP_REJECTED", adminUserId, "ADMIN", null,
                    JSON.toJSONString(Map.of("merchantId", merchantId, "regionId", regionId, "kind", kind == null ? "" : kind, "reason", note)));
        } else {
            boolean pause = "PAUSE_MERCHANT".equals(kind);
            String effectiveState = pause ? "PAUSED" : "RESTORED";
            String availability = pause ? "PAUSED" : "AVAILABLE";
            jdbc.update("UPDATE city_merchant_operation SET status = 'APPLIED', effective_state = ?, version = version + 1, reason = ? "
                            + "WHERE merchant_id = ? AND region_id = ?",
                    effectiveState, note, merchantId, regionId);
            if (board != null) {
                List<String> affectedPoints = jdbc.queryForList(
                        "SELECT point_id FROM city_game_point WHERE board_id = ? AND merchant_id = ? AND del_flag = 0",
                        String.class, boardId, merchantId);
                for (String ptId : affectedPoints) {
                    jdbc.update("UPDATE city_game_point SET availability = ?, merchant_version = merchant_version + 1, platform_version = platform_version + 1 "
                                    + "WHERE board_id = ? AND point_id = ?",
                            availability, boardId, ptId);
                    if (pause) {
                        jdbc.update("UPDATE city_game_property SET status = 'PAUSED', state_version = state_version + 1 "
                                        + "WHERE board_id = ? AND point_id = ? AND status IN ('AVAILABLE', 'HELD', 'PROTECTED')",
                                boardId, ptId);
                    } else {
                        jdbc.update("UPDATE city_game_property SET status = 'AVAILABLE', state_version = state_version + 1 "
                                        + "WHERE board_id = ? AND point_id = ? AND status = 'PAUSED'",
                                boardId, ptId);
                    }
                    mapper.insertPointEvent(boardId, ptId, "MERCHANT", null);
                }
            }
            mapper.insertBoardAudit(boardId, "MERCHANT_OP_APPROVED", adminUserId, "ADMIN", null,
                    JSON.toJSONString(Map.of("merchantId", merchantId, "regionId", regionId, "kind", kind == null ? "" : kind, "effectiveState", effectiveState)));
        }
        Map<String, Object> fresh = jdbc.queryForMap(
                "SELECT merchant_id AS merchantId, region_id AS regionId, version, status, effective_state AS effectiveState, "
                        + "kind, reason FROM city_merchant_operation WHERE merchant_id = ? AND region_id = ?",
                merchantId, regionId);
        fresh.put("runtimeApplied", "APPLIED".equals(fresh.get("status")));
        return fresh;
    }

    /** Admin fairness + telemetry report without requiring an active player participation row. */
    public Map<String, Object> adminFairnessReport(String boardId, String regionId) {
        Map<String, Object> board = CityGameRules.id(boardId) ? selectBoardRow(boardId)
                : (CityGameRules.id(regionId) ? mapper.selectCurrentBoard(regionId) : null);
        if (board == null) throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        String resolvedBoardId = (String) board.get("boardId");
        Map<String, Object> facts = mapper.selectBoardFacts(resolvedBoardId, new Date(clock.getAsLong()));
        List<Map<String, Object>> rollAudit = mapper.selectBoardRollAudit(resolvedBoardId, 30);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardId", resolvedBoardId);
        out.put("regionId", board.get("regionId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("lifecycle", board.get("lifecycle"));
        out.put("formulaId", CityFairnessService.FORMULA);
        out.put("facts", facts);
        out.put("coverageGaps", List.of("challengeCompensationExits", "gpsFalseRejections", "safetyTickets"));
        out.put("rollAudit", rollAudit);
        return out;
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private void syncPointsAndTrack(String boardId, Map<String, Object> norm, Timestamp validFrom) {
        List<Map<String, Object>> spaces = (List<Map<String, Object>>) norm.get("spaces");
        // CITY-19: a republish keeps each place's authored tier and district unless the payload sets them.
        Map<String, Map<String, Object>> previous = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT point_id, tier, district_id FROM city_game_point WHERE board_id = ?", boardId)) {
            previous.put(String.valueOf(r.get("point_id")), r);
        }
        if (norm.get("districts") instanceof List<?> ds) {
            for (Object o : ds) {
                Map<String, Object> d = (Map<String, Object>) o;
                jdbc.update("INSERT INTO city_district(board_id, district_id, name, color_token) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), color_token = VALUES(color_token)",
                        boardId, d.get("districtId"), d.get("name"), d.get("colorToken"));
            }
        }
        // Clear old task defs & services cleanly before rewriting current board config
        jdbc.update("DELETE FROM city_game_service WHERE board_id = ?", boardId);
        jdbc.update("DELETE FROM city_task_definition WHERE board_id = ?", boardId);
        jdbc.update("DELETE FROM city_game_property WHERE board_id = ?", boardId);
        jdbc.update("DELETE FROM city_game_point WHERE board_id = ?", boardId);

        int idx = 0;
        for (Map<String, Object> sp : spaces) {
            String pointId = str(sp, "pointId");
            String propertyId = str(sp, "propertyId");
            String title = str(sp, "title");
            double lat = dbl(sp.get("latitude"));
            double lng = dbl(sp.get("longitude"));
            Long placeId = num(sp, "poiId") != null ? num(sp, "poiId") : num(sp, "placeId");
            Long merchantId = num(sp, "merchantId");
            String domain = str(sp, "domain");
            if (domain == null) domain = merchantId != null ? "MERCHANT" : "POI";
            String role = str(sp, "role");
            if (!"NEUTRAL".equals(role) && !"OPERABLE".equals(role)) role = "OPERABLE";
            int trackIndex = sp.get("trackIndex") instanceof Number ? ((Number) sp.get("trackIndex")).intValue() : idx;

            Map<String, Object> before = previous.getOrDefault(pointId, Map.of());
            Object tier = sp.get("tier") != null ? sp.get("tier") : before.get("tier");
            Object district = sp.get("districtId") != null ? sp.get("districtId") : before.get("district_id");
            jdbc.update("INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, domain, collected_at, availability, merchant_id, place_id, slot_version, del_flag, tier, district_id) "
                            + "VALUES (?,?,?,?,?,?,?, 'AVAILABLE', ?, ?, 1, 0, ?, ?)",
                    boardId, pointId, title, lat, lng, domain, validFrom, merchantId, placeId, tier, district);
            jdbc.update("INSERT INTO city_game_property(board_id, property_id, point_id, track_index, role, status, state_version) "
                            + "VALUES (?,?,?,?,?, 'AVAILABLE', 1)",
                    boardId, propertyId, pointId, trackIndex, role);

            Long toll = num(sp, "tollPoints") != null ? num(sp, "tollPoints") : num(sp, "memberPointsFee");
            if ("OPERABLE".equals(role) && toll != null && toll > 0) {
                String serviceId = str(sp, "serviceId");
                if (serviceId == null || serviceId.isEmpty()) serviceId = "svc-" + propertyId;
                jdbc.update("INSERT INTO city_game_service(board_id, service_id, property_id, title, member_points_fee, status) VALUES (?,?,?,?,?,'PUBLISHED')",
                        boardId, serviceId, propertyId, title + " 过路服务", toll);
            }
            idx++;
        }
        recountOwnable(boardId);

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) norm.get("tasks");
        int sortOrder = 1;
        for (Map<String, Object> t : tasks) {
            String taskId = str(t, "taskId");
            String access = str(t, "access");
            if (!"REMOTE_NEUTRAL".equals(access) && !"ONSITE_ZERO_EFFECT".equals(access)) access = "REMOTE_NEUTRAL";
            String pointId = str(t, "pointId");
            String title = str(t, "title");
            String question = str(t, "question");
            List<Map<String, Object>> opts = (List<Map<String, Object>>) t.get("options");
            String optionsJson = JSON.toJSONString(opts);
            String answerId = str(t, "answerOptionId");
            int maxAttempts = t.get("maxAttempts") instanceof Number ? ((Number) t.get("maxAttempts")).intValue() : 2;
            jdbc.update("INSERT INTO city_task_definition(board_id, task_id, access, point_id, title, question, options_json, answer_option_id, max_attempts, sort_order, status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,'PUBLISHED')",
                    boardId, taskId, access, pointId, title, question, optionsJson, answerId, maxAttempts, sortOrder++);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeAndValidatePayload(String boardId, Map<String, Object> raw) {
        String regionId = str(raw, "regionId");
        String seasonId = str(raw, "seasonId");
        String title = str(raw, "title");
        if (!CityGameRules.id(regionId) || !CityGameRules.id(seasonId) || title == null || title.trim().isEmpty()) {
            throw new CityGameException(400, "BOARD_DRAFT_INVALID", "regionId、seasonId 和 title 必填");
        }
        String diceFaces = toJsonArray(raw.get("diceFaces"), raw.get("faces"), "[1,2,3,4,5,6]");
        String diceWeights = toJsonArray(raw.get("diceWeights"), raw.get("weights"), "[1,1,1,1,1,1]");
        parseDiceOrThrow(diceFaces, diceWeights);

        List<Map<String, Object>> rawSpaces = (List<Map<String, Object>>) raw.get("spaces");
        if (rawSpaces == null || rawSpaces.isEmpty()) {
            throw new CityGameException(400, "BOARD_DRAFT_INVALID", "棋盘至少需要 1 个地块格子");
        }
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        Set<String> seenPoints = new LinkedHashSet<>();
        Set<String> seenProps = new LinkedHashSet<>();
        List<Map<String, Object>> spaces = new ArrayList<>();
        for (int i = 0; i < rawSpaces.size(); i++) {
            Map<String, Object> sp = rawSpaces.get(i);
            String pointId = nonEmpty(str(sp, "pointId"), "pt-" + (i + 1));
            String propertyId = nonEmpty(str(sp, "propertyId"), "prop-" + (i + 1));
            String spTitle = nonEmpty(str(sp, "title"), "地块#" + (i + 1));
            Double lat = dbl(sp.get("latitude"));
            Double lng = dbl(sp.get("longitude"));
            if (!RoamTerritoryService.validCoord(lat, lng)) {
                throw new CityGameException(400, "BOARD_DRAFT_INVALID", "地块坐标无效: " + pointId);
            }
            if (!seenPoints.add(pointId) || !seenProps.add(propertyId)) {
                throw new CityGameException(400, "BOARD_DRAFT_INVALID", "pointId/propertyId 不能重复");
            }
            minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
            minLng = Math.min(minLng, lng); maxLng = Math.max(maxLng, lng);

            Map<String, Object> cleanSp = new LinkedHashMap<>();
            cleanSp.put("pointId", pointId);
            cleanSp.put("propertyId", propertyId);
            cleanSp.put("trackIndex", i);
            cleanSp.put("title", spTitle);
            cleanSp.put("latitude", lat);
            cleanSp.put("longitude", lng);
            cleanSp.put("poiId", num(sp, "poiId") != null ? num(sp, "poiId") : num(sp, "placeId"));
            cleanSp.put("merchantId", num(sp, "merchantId"));
            cleanSp.put("domain", nonEmpty(str(sp, "domain"), num(sp, "merchantId") != null ? "MERCHANT" : "POI"));
            cleanSp.put("role", "NEUTRAL".equals(str(sp, "role")) ? "NEUTRAL" : "OPERABLE");
            cleanSp.put("tollPoints", num(sp, "tollPoints") != null ? Math.max(0L, num(sp, "tollPoints")) : 5L);
            cleanSp.put("serviceId", nonEmpty(str(sp, "serviceId"), "svc-" + propertyId));
            // CITY-19: authored tier (T1..T4) and district; absent = keep what the live board has.
            if (sp.get("tier") != null) cleanSp.put("tier", requireTier(sp.get("tier")));
            String districtId = str(sp, "districtId");
            if (districtId != null) {
                CityGameSupport.requireId(districtId, "districtId");
                cleanSp.put("districtId", districtId);
            }
            spaces.add(cleanSp);
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        if (raw.get("tasks") instanceof List) {
            int tIdx = 1;
            for (Map<String, Object> t : (List<Map<String, Object>>) raw.get("tasks")) {
                Map<String, Object> nt = new LinkedHashMap<>();
                nt.put("taskId", nonEmpty(str(t, "taskId"), "task-" + tIdx++));
                nt.put("access", nonEmpty(str(t, "access"), "REMOTE_NEUTRAL"));
                nt.put("pointId", str(t, "pointId"));
                nt.put("title", nonEmpty(str(t, "title"), "探索挑战"));
                nt.put("question", nonEmpty(str(t, "question"), "确认完成本据点探索？"));
                List<Map<String, Object>> opts = t.get("options") instanceof List ? (List<Map<String, Object>>) t.get("options")
                        : List.of(Map.of("id", "A", "label", "确认"), Map.of("id", "B", "label", "跳过"));
                nt.put("options", opts);
                nt.put("answerOptionId", nonEmpty(str(t, "answerOptionId"), String.valueOf(opts.get(0).get("id"))));
                nt.put("maxAttempts", intVal(t.get("maxAttempts"), 2));
                tasks.add(nt);
            }
        }

        int minMembers = Math.max(1, intVal(raw.get("minMembers"), 1));
        int maxMembers = Math.max(minMembers, intVal(raw.get("maxMembers"), 4));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardId", boardId);
        out.put("gameId", nonEmpty(str(raw, "gameId"), "city-game"));
        out.put("regionId", regionId);
        out.put("seasonId", seasonId);
        out.put("rulesReleaseId", nonEmpty(str(raw, "rulesReleaseId"), "rel-" + boardId));
        out.put("ruleVersion", nonEmpty(str(raw, "ruleVersion"), "v1"));
        out.put("title", title.trim());
        out.put("diceId", nonEmpty(str(raw, "diceId"), "dice-" + boardId));
        out.put("diceFaces", diceFaces);
        out.put("diceWeights", diceWeights);
        out.put("actionsPerRound", Math.max(1, intVal(raw.get("actionsPerRound"), 3)));
        out.put("maxRounds", Math.max(1, intVal(raw.get("maxRounds"), 5)));
        out.put("rollCost", Math.max(1, intVal(raw.get("rollCost"), 1)));
        out.put("acquisitionCost", Math.max(1, intVal(raw.get("acquisitionCost"), 1)));
        out.put("taskCost", Math.max(0, intVal(raw.get("taskCost"), 0)));
        out.put("serviceCost", Math.max(0, intVal(raw.get("serviceCost"), 0)));
        out.put("challengeCost", Math.max(0, intVal(raw.get("challengeCost"), 0)));
        out.put("acquirePoints", Math.max(0L, longVal(raw.get("acquirePoints"), 10L)));
        out.put("challengePoints", Math.max(0L, longVal(raw.get("challengePoints"), 15L)));
        out.put("holdingYield", Math.max(0, intVal(raw.get("holdingYield"), 2)));
        out.put("goalScore", Math.max(0, intVal(raw.get("goalScore"), 3)));
        out.put("holdingLimit", raw.get("holdingLimit") instanceof Number ? ((Number) raw.get("holdingLimit")).intValue() : null);
        out.put("protectionSeconds", Math.max(0, intVal(raw.get("protectionSeconds"), 3600)));
        out.put("quoteValidSeconds", Math.max(30, intVal(raw.get("quoteValidSeconds"), 120)));
        out.put("termSeconds", Math.max(60, intVal(raw.get("termSeconds"), 86400)));
        out.put("minMembers", minMembers);
        out.put("maxMembers", maxMembers);
        out.put("rulesId", nonEmpty(str(raw, "rulesId"), "rules-v1"));
        out.put("rulesText", nonEmpty(str(raw, "rulesText"), "统一骰子驱动城市棋盘移动与实景拍照占领，仅消耗和奖励积分。"));
        out.put("challengeRuleId", nonEmpty(str(raw, "challengeRuleId"), "prot-v1"));
        out.put("protectionText", nonEmpty(str(raw, "protectionText"), "占领后进入保护窗口，期内不可抢占。"));
        out.put("safetyStatement", nonEmpty(str(raw, "safetyStatement"), "请在安全合规区域参与实地漫游打卡。"));
        out.put("validFromMillis", raw.get("validFromMillis"));
        out.put("validUntilMillis", raw.get("validUntilMillis"));
        out.put("fairnessMetricVersion", nonEmpty(str(raw, "fairnessMetricVersion"), "v1"));
        out.put("fairnessHorizonSeconds", raw.get("fairnessHorizonSeconds") instanceof Number ? ((Number) raw.get("fairnessHorizonSeconds")).intValue() : 86400);
        out.put("fairnessDeltaSeconds", raw.get("fairnessDeltaSeconds") instanceof Number ? ((Number) raw.get("fairnessDeltaSeconds")).intValue() : 604800);
        out.put("mapSouth", dblOr(raw.get("mapSouth"), minLat - 0.01));
        out.put("mapWest", dblOr(raw.get("mapWest"), minLng - 0.01));
        out.put("mapNorth", dblOr(raw.get("mapNorth"), maxLat + 0.01));
        out.put("mapEast", dblOr(raw.get("mapEast"), maxLng + 0.01));
        out.put("mapZoom", intVal(raw.get("mapZoom"), 14));
        out.put("mapMinZoom", intVal(raw.get("mapMinZoom"), 10));
        out.put("mapMaxZoom", intVal(raw.get("mapMaxZoom"), 18));
        out.put("spaces", spaces);
        out.put("tasks", tasks);
        List<Map<String, Object>> districts = normalizeDistricts(raw.get("districts"));
        if (!districts.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            for (Map<String, Object> d : districts) ids.add((String) d.get("districtId"));
            for (Map<String, Object> sp : spaces) {
                if (sp.get("districtId") != null && !ids.contains(sp.get("districtId"))) {
                    throw new CityGameException(400, "DISTRICT_NOT_FOUND", "地块所属街区不存在: " + sp.get("pointId"));
                }
            }
            out.put("districts", districts);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeDistricts(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> d = (Map<String, Object>) o;
            String id = str(d, "districtId");
            CityGameSupport.requireId(id, "districtId");
            if (!seen.add(id)) throw new CityGameException(400, "BOARD_DRAFT_INVALID", "街区编号不能重复");
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("districtId", id);
            clean.put("name", requireDistrictName(str(d, "name")));
            clean.put("colorToken", requireColorToken(str(d, "colorToken")));
            out.add(clean);
        }
        return out;
    }

    private static CityGameRules.Dice parseDiceOrThrow(String facesJson, String weightsJson) {
        try {
            return CityGameRules.dice(facesJson, weightsJson);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new CityGameException(400, "DICE_INVALID", "骰子配置无效: " + e.getMessage());
        }
    }

    private Map<String, Object> reconstructFromLive(String boardId, Map<String, Object> live) {
        Map<String, Object> out = new LinkedHashMap<>(live);
        List<Map<String, Object>> props = jdbc.queryForList(
                "SELECT pt.point_id AS pointId, pr.property_id AS propertyId, pr.track_index AS trackIndex, "
                        + "pt.title, pt.latitude, pt.longitude, pt.place_id AS poiId, pt.merchant_id AS merchantId, "
                        + "pt.domain, pr.role, pr.status, COALESCE(s.member_points_fee, 5) AS tollPoints, s.service_id AS serviceId, "
                        + "pt.tier, pt.district_id AS districtId "
                        + "FROM city_game_point pt "
                        + "LEFT JOIN city_game_property pr ON pr.board_id = pt.board_id AND pr.point_id = pt.point_id "
                        + "LEFT JOIN city_game_service s ON s.board_id = pr.board_id AND s.property_id = pr.property_id "
                        + "WHERE pt.board_id = ? AND pt.del_flag = 0 ORDER BY pr.track_index ASC, pt.point_id ASC",
                boardId);
        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT task_id AS taskId, access, point_id AS pointId, title, question, options_json AS optionsJson, "
                        + "answer_option_id AS answerOptionId, max_attempts AS maxAttempts FROM city_task_definition WHERE board_id = ? ORDER BY sort_order ASC",
                boardId);
        for (Map<String, Object> t : tasks) {
            if (t.get("optionsJson") != null) {
                t.put("options", JSON.parseArray(String.valueOf(t.get("optionsJson"))));
            }
        }
        out.put("spaces", props);
        out.put("tasks", tasks);
        return out;
    }

    private Map<String, Object> selectBoardRow(String boardId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT board_id AS boardId, game_id AS gameId, region_id AS regionId, season_id AS seasonId, "
                        + "rules_release_id AS rulesReleaseId, release_id AS releaseId, rules_hash AS rulesHash, rule_version AS ruleVersion, "
                        + "lifecycle, title, revision, board_version AS boardVersion, event_sequence AS eventSequence, "
                        + "dice_id AS diceId, dice_faces AS diceFaces, dice_weights AS diceWeights, "
                        + "actions_per_round AS actionsPerRound, max_rounds AS maxRounds, roll_cost AS rollCost, acquisition_cost AS acquisitionCost, "
                        + "task_cost AS taskCost, service_cost AS serviceCost, challenge_cost AS challengeCost, "
                        + "acquire_points AS acquirePoints, challenge_points AS challengePoints, holding_yield AS holdingYield, goal_score AS goalScore, "
                        + "holding_limit AS holdingLimit, protection_seconds AS protectionSeconds, quote_valid_seconds AS quoteValidSeconds, "
                        + "term_seconds AS termSeconds, min_members AS minMembers, max_members AS maxMembers, "
                        + "rules_id AS rulesId, rules_text AS rulesText, challenge_rule_id AS challengeRuleId, protection_text AS protectionText, "
                        + "safety_statement AS safetyStatement, valid_from AS validFrom, valid_until AS validUntil, "
                        + "fairness_metric_version AS fairnessMetricVersion, fairness_horizon_seconds AS fairnessHorizonSeconds, "
                        + "fairness_delta_seconds AS fairnessDeltaSeconds, "
                        + "map_datum AS mapDatum, map_south AS mapSouth, map_west AS mapWest, map_north AS mapNorth, map_east AS mapEast, "
                        + "map_zoom AS mapZoom, map_min_zoom AS mapMinZoom, map_max_zoom AS mapMaxZoom "
                        + "FROM city_game_board WHERE board_id = ? LIMIT 1",
                boardId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long nextReleaseId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(release_id), 1000) FROM city_game_board", Long.class);
        return (max == null ? 1000L : max) + 1L;
    }

    private static String toJsonArray(Object primary, Object secondary, String fallback) {
        Object pick = primary != null ? primary : secondary;
        if (pick == null) return fallback;
        if (pick instanceof String) return (String) pick;
        return JSON.toJSONString(pick);
    }

    private static String sha256(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp parseTimestamp(Object v, Timestamp fallback) {
        if (v instanceof Number) return new Timestamp(((Number) v).longValue());
        return fallback;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static String nonEmpty(String v, String fallback) {
        return v != null && !v.trim().isEmpty() ? v.trim() : fallback;
    }

    private static Long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static int intVal(Object v, int fallback) {
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    private static long longVal(Object v, long fallback) {
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    private static Double dbl(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static double dblOr(Object v, double fallback) {
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }
}
