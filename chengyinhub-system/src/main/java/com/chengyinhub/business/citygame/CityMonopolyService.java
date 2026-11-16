package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/**
 * City Monopoly runtime (CITY-MONOPOLY-SPEC §2, owner ruling 2026-11-15: every amount is 积分).
 *
 * <p>Ownership lives in {@code roam_territory} ({@code rules_kind='MONOPOLY'}), merged with the CITY board by the
 * SYS-citygame change. Points move only through the existing {@code ums_member.point} +
 * {@code ums_member_points_detail} statements of {@link CityGameMapper}; {@code city_points_flow} records every
 * movement idempotently (UK member/kind/ref). Rent is written PENDING and only credited by {@link #settle}.
 * Every write checks ownership in its WHERE clause or via a locked read of the owner column.
 */
@Service
public class CityMonopolyService {
    static final String MONOPOLY = "MONOPOLY";
    private static final long QUOTE_TTL_MS = 300_000L;

    private final JdbcTemplate jdbc;
    private final CityGameMapper mapper;
    private final CityMonopolyConfigService configs;
    private final MemberAttributeService attributes;
    private final LongSupplier clock;
    private final IntUnaryOperator dice;

    private IGrowthService growth;
    private IMerchantAccessService merchants;
    private CityAttestVerifier attest;
    private CityTaskVerifier tasks;
    private RoamTerritoryService territories;

    @Autowired
    public CityMonopolyService(JdbcTemplate jdbc, CityGameMapper mapper, CityMonopolyConfigService configs,
                               MemberAttributeService attributes) {
        this(jdbc, mapper, configs, attributes, System::currentTimeMillis, secureDice());
    }

    CityMonopolyService(JdbcTemplate jdbc, CityGameMapper mapper, CityMonopolyConfigService configs,
                        MemberAttributeService attributes, LongSupplier clock, IntUnaryOperator dice) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.configs = configs;
        this.attributes = attributes;
        this.clock = clock;
        this.dice = dice;
    }

    private static IntUnaryOperator secureDice() {
        SecureRandom random = new SecureRandom();
        return faces -> random.nextInt(faces) + 1;
    }

    @Autowired(required = false) public void setGrowth(IGrowthService growth) { this.growth = growth; }
    @Autowired(required = false) public void setMerchants(IMerchantAccessService merchants) { this.merchants = merchants; }
    @Autowired(required = false) public void setAttest(CityAttestVerifier attest) { this.attest = attest; }
    @Autowired(required = false) public void setTasks(CityTaskVerifier tasks) { this.tasks = tasks; }
    void setTerritories(RoamTerritoryService territories) { this.territories = territories; }

    public boolean enabled(String boardId) {
        return configs.forBoard(boardId).flag("city.monopoly.enabled");
    }

    // =================================================================== time & season helpers

    private long now() { return clock.getAsLong(); }

    private ZonedDateTime zdt(CityMonopolyConfig c, long millis) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), CityMonopolyRules.zone(c));
    }

    private LocalDate day(CityMonopolyConfig c, long millis) { return zdt(c, millis).toLocalDate(); }

    CityMonopolyRules.Season season(CityMonopolyConfig c, long millis) {
        ZonedDateTime epoch = LocalDate.parse(c.str("city.monopoly.season.epoch"))
                .atTime(LocalTime.parse(c.str("city.monopoly.season.start").split(" ")[1])).atZone(CityMonopolyRules.zone(c));
        return CityMonopolyRules.seasonAt(c, epoch, zdt(c, millis));
    }

    private boolean finale(CityMonopolyConfig c, long millis) {
        CityMonopolyRules.Season s = season(c, millis);
        return CityMonopolyRules.phase(c, s, zdt(c, millis)) == CityMonopolyRules.Phase.FINALE;
    }

    private void requireOpenSeason(CityMonopolyConfig c, long millis) {
        CityMonopolyRules.Season s = season(c, millis);
        CityMonopolyRules.Phase p = CityMonopolyRules.phase(c, s, zdt(c, millis));
        if (p == CityMonopolyRules.Phase.BREAK || p == CityMonopolyRules.Phase.FROZEN) {
            throw new CityGameException(409, "SEASON_FROZEN", "赛季结算中，暂不可操作");
        }
        jdbc.update("INSERT IGNORE INTO city_season(season_id, region_id, starts_at, ends_at, phase, status, config_hash, carry_rate, carry_cap) "
                        + "VALUES (?,'default',?,?,?,'OPEN',?,?,?)",
                s.seasonId(), Timestamp.from(s.startsAt().toInstant()), Timestamp.from(s.endsAt().toInstant()), p.name(),
                c.rulesHash(), c.num("city.monopoly.season.reset.carry_rate"), c.integer("city.monopoly.season.reset.carry_cap"));
    }

    private void requireEnabled(CityMonopolyConfig c) {
        if (!c.flag("city.monopoly.enabled")) throw new CityGameException(409, "MONOPOLY_DISABLED", "城市大富翁未开放");
    }

    // =================================================================== member facts

    record MemberFacts(long accountAgeDays, int level, boolean phoneBound, String unionId) {}

    MemberFacts memberFacts(long memberId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.create_time, m.phone, m.union_id, g.level_no FROM ums_member m LEFT JOIN member_growth g ON g.member_id = m.id "
                        + "WHERE m.id = ? AND m.del_flag = 0", memberId);
        if (rows.isEmpty()) return new MemberFacts(0, 1, false, null);
        Map<String, Object> r = rows.get(0);
        long created = r.get("create_time") == null ? now() : CityGameSupport.millis(r.get("create_time"));
        long age = Math.max(0, (now() - created) / 86_400_000L);
        int level = r.get("level_no") instanceof Number ? ((Number) r.get("level_no")).intValue() : 1;
        String phone = r.get("phone") == null ? "" : String.valueOf(r.get("phone")).trim();
        return new MemberFacts(age, level, !phone.isEmpty(), r.get("union_id") == null ? null : String.valueOf(r.get("union_id")));
    }

    private boolean banned(long memberId, long now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE member_id = ? AND action IN ('BAN','SEASON_BAN') "
                + "AND status <> 'DISMISSED' AND window_to > ?", Integer.class, memberId, new Timestamp(now));
        return n != null && n > 0;
    }

    private String teamOf(String boardId, long memberId) {
        if (boardId == null || mapper == null) return null;
        Map<String, Object> p = mapper.selectParticipation(boardId, memberId);
        return p == null ? null : (String) p.get("teamId");
    }

    // =================================================================== points (one currency: 积分)

    private long balance(long memberId) { return mapper.selectMemberPoints(memberId); }

    private boolean flow(long memberId, String seasonId, long delta, String state, String kind, String refType, String refId, String bizKey) {
        int n = jdbc.update("INSERT IGNORE INTO city_points_flow(member_id, season_id, delta, state, kind, ref_type, ref_id, points_business_key, settled_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                memberId, seasonId, delta, state, kind, refType, refId, bizKey, "AVAILABLE".equals(state) ? new Timestamp(now()) : null);
        return n == 1;
    }

    private void seasonStat(long memberId, String seasonId, String column, double delta) {
        if (!Set.of("earned", "spent", "debt", "confirmed_rent", "first_claims", "takeovers", "defenses", "holding_score").contains(column)) {
            throw new IllegalArgumentException(column);
        }
        jdbc.update("INSERT INTO city_member_season(member_id, season_id, " + column + ") VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE " + column + " = " + column + " + VALUES(" + column + "), version = version + 1", memberId, seasonId, delta);
    }

    /** Debits 积分 now (all-or-nothing). Returns false when the balance is short. */
    private boolean debit(long memberId, String seasonId, long amount, String kind, String refType, String refId, String reason) {
        if (amount <= 0) return true;
        String key = "CITYM:" + kind + ":" + refType + ":" + refId;
        if (!flow(memberId, seasonId, -amount, "AVAILABLE", kind, refType, refId, key)) return true; // replay
        long before = balance(memberId);
        if (before < amount || mapper.deductMemberPoints(memberId, amount) != 1) {
            jdbc.update("DELETE FROM city_points_flow WHERE member_id = ? AND kind = ? AND ref_type = ? AND ref_id = ?", memberId, kind, refType, refId);
            return false;
        }
        mapper.insertPointsDetail(memberId, before, amount, reason, key);
        seasonStat(memberId, seasonId, "spent", amount);
        return true;
    }

    /** Credits 积分 now, first paying down season debt (unpaid upkeep / clawback). */
    private long creditNow(long memberId, String seasonId, long amount, String kind, String refType, String refId, String reason) {
        if (amount <= 0) return 0;
        String key = "CITYM:" + kind + ":" + refType + ":" + refId;
        if (!flow(memberId, seasonId, amount, "AVAILABLE", kind, refType, refId, key)) return 0;
        return applyCredit(memberId, seasonId, amount, reason, key);
    }

    private long applyCredit(long memberId, String seasonId, long amount, String reason, String key) {
        List<Map<String, Object>> debtRows = jdbc.queryForList(
                "SELECT debt FROM city_member_season WHERE member_id = ? AND season_id = ? FOR UPDATE", memberId, seasonId);
        long debt = debtRows.isEmpty() ? 0 : ((Number) debtRows.get(0).get("debt")).longValue();
        long offset = Math.min(debt, amount);
        if (offset > 0) seasonStat(memberId, seasonId, "debt", -offset);
        long credit = amount - offset;
        if (credit > 0) {
            long before = balance(memberId);
            mapper.creditMemberPoints(memberId, credit);
            mapper.insertPointsEarnDetail(memberId, before, credit, reason, key);
        }
        seasonStat(memberId, seasonId, "earned", amount);
        return credit;
    }

    /** Takes what the balance allows; the rest becomes debt repaid from later income. */
    private long debitOrDebt(long memberId, String seasonId, long amount, String kind, String refType, String refId, String reason) {
        if (amount <= 0) return 0;
        String key = "CITYM:" + kind + ":" + refType + ":" + refId;
        if (!flow(memberId, seasonId, -amount, "AVAILABLE", kind, refType, refId, key)) return 0;
        long before = balance(memberId);
        long take = Math.min(before, amount);
        if (take > 0 && mapper.deductMemberPoints(memberId, take) == 1) {
            mapper.insertPointsDetail(memberId, before, take, reason, key);
        } else {
            take = 0;
        }
        if (amount - take > 0) seasonStat(memberId, seasonId, "debt", amount - take);
        seasonStat(memberId, seasonId, "spent", amount);
        return take;
    }

    private void xp(long memberId, int amount, String reason) {
        if (growth != null && amount > 0) growth.addExp(memberId, amount, reason);
    }

    private void axp(long memberId, AttributeRules.Source s, String sourceId) {
        if (attributes != null) attributes.grant(memberId, s, sourceId);
    }

    private int attrLevel(long memberId, String attr) {
        return attributes == null ? 1 : attributes.level(memberId, attr);
    }

    // =================================================================== receipts & quotes

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> replay(long memberId, String requestId, String action, Map<String, Object> body) {
        if (!CityGameRules.id(requestId)) throw new CityGameException(400, "REQUEST_INVALID", "requestId 无效");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, body_hash, response_json FROM city_monopoly_receipt WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        if (!action.equals(r.get("action")) || !sha256(JSON.toJSONString(body)).equals(r.get("body_hash"))) {
            throw new CityGameException(409, "REQUEST_CONFLICT", "同一 requestId 的请求内容不一致");
        }
        Map<String, Object> out = new LinkedHashMap<>(JSON.parseObject(String.valueOf(r.get("response_json"))));
        out.put("idempotentReplay", true);
        return out;
    }

    private Map<String, Object> receipt(long memberId, String requestId, String action, Map<String, Object> body, Map<String, Object> response) {
        response.put("requestId", requestId);
        response.put("idempotentReplay", false);
        jdbc.update("INSERT INTO city_monopoly_receipt(member_id, request_id, action, body_hash, response_json) VALUES (?,?,?,?,?)",
                memberId, requestId, action, sha256(JSON.toJSONString(body)), JSON.toJSONString(response));
        return response;
    }

    public Map<String, Object> receiptOf(long memberId, String requestId) {
        CityGameSupport.requireMember(memberId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT response_json FROM city_monopoly_receipt WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) throw new CityGameException(404, "RECEIPT_NOT_FOUND", "没有该请求的回执");
        return JSON.parseObject(String.valueOf(rows.get(0).get("response_json")));
    }

    private Map<String, Object> quote(long memberId, String kind, String targetKey, long cost, Map<String, Object> payload, CityMonopolyConfig c) {
        String quoteId = UUID.randomUUID().toString();
        long expires = now() + QUOTE_TTL_MS;
        String hash = sha256(quoteId + "|" + memberId + "|" + kind + "|" + targetKey + "|" + cost + "|" + c.rulesHash());
        jdbc.update("INSERT INTO city_monopoly_quote(quote_id, member_id, kind, target_key, cost, payload_json, rules_hash, quote_hash, expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", quoteId, memberId, kind, targetKey, cost, JSON.toJSONString(payload), c.rulesHash(), hash, new Timestamp(expires));
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("quoteId", quoteId);
        out.put("quoteHash", hash);
        out.put("cost", cost);
        out.put("currency", "POINTS");
        out.put("rulesHash", c.rulesHash());
        out.put("expiresAt", expires);
        return out;
    }

    private Map<String, Object> consumeQuote(long memberId, String kind, Map<String, Object> body) {
        String quoteId = str(body, "quoteId");
        String quoteHash = str(body, "quoteHash");
        if (quoteId == null || quoteHash == null) throw new CityGameException(400, "QUOTE_REQUIRED", "缺少报价");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM city_monopoly_quote WHERE quote_id = ? AND member_id = ? AND kind = ? FOR UPDATE", quoteId, memberId, kind);
        if (rows.isEmpty()) throw new CityGameException(404, "QUOTE_NOT_FOUND", "报价不存在");
        Map<String, Object> q = rows.get(0);
        if (!quoteHash.equals(q.get("quote_hash"))) throw new CityGameException(409, "QUOTE_MISMATCH", "报价校验失败");
        if (q.get("used_at") != null) throw new CityGameException(409, "QUOTE_USED", "报价已使用");
        if (CityGameSupport.millis(q.get("expires_at")) < now()) throw new CityGameException(409, "QUOTE_EXPIRED", "报价已过期，请重新报价");
        jdbc.update("UPDATE city_monopoly_quote SET used_at = ? WHERE quote_id = ? AND member_id = ? AND used_at IS NULL", new Timestamp(now()), quoteId, memberId);
        Map<String, Object> out = new LinkedHashMap<>(JSON.parseObject(String.valueOf(q.get("payload_json"))));
        out.put("_cost", ((Number) q.get("cost")).longValue());
        out.put("_targetKey", q.get("target_key"));
        out.put("_quoteHash", quoteHash);
        out.put("_rulesHash", q.get("rules_hash"));
        return out;
    }

    /** The quote is only honoured under the exact rules (board-effective config) it was priced with. */
    private static void requireSameRules(Map<String, Object> q, CityMonopolyConfig c) {
        if (!c.rulesHash().equals(q.get("_rulesHash"))) throw new CityGameException(409, "RULES_CHANGED", "规则已更新，请重新报价");
    }

    // =================================================================== targets

    /** A claimable map space: roam_poi and/or CITY board point. */
    record Target(String key, Long poiId, String boardId, String pointId, String propertyId, String title,
                  double lat, double lng, int tier, String districtId, Long merchantId, String cityCode) {}

    Target resolveTarget(Map<String, Object> body) {
        Long poiId = num(body, "poiId");
        String boardId = str(body, "boardId");
        String pointId = str(body, "pointId");
        if (boardId != null && pointId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT p.board_id, p.point_id, p.title, p.latitude, p.longitude, p.tier, p.district_id, p.merchant_id, p.place_id, "
                            + "(SELECT property_id FROM city_game_property pr WHERE pr.board_id = p.board_id AND pr.point_id = p.point_id LIMIT 1) AS property_id "
                            + "FROM city_game_point p WHERE p.board_id = ? AND p.point_id = ? AND p.del_flag = 0", boardId, pointId);
            if (rows.isEmpty()) throw new CityGameException(404, "SPACE_NOT_FOUND", "地点不存在");
            Map<String, Object> r = rows.get(0);
            Long place = r.get("place_id") instanceof Number ? ((Number) r.get("place_id")).longValue() : poiId;
            return new Target("board:" + boardId + ":" + pointId, place, boardId, pointId, (String) r.get("property_id"),
                    (String) r.get("title"), dbl(r.get("latitude")), dbl(r.get("longitude")),
                    CityMonopolyRules.normalizeTier(r.get("tier") instanceof Number ? ((Number) r.get("tier")).intValue() : null),
                    (String) r.get("district_id"), r.get("merchant_id") instanceof Number ? ((Number) r.get("merchant_id")).longValue() : null, null);
        }
        if (poiId != null && poiId > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, name, lat, lng, merchant_id, city_code FROM roam_poi WHERE id = ? AND status = 1", poiId);
            if (rows.isEmpty()) throw new CityGameException(404, "POI_NOT_FOUND", "地点不存在或已下线");
            Map<String, Object> r = rows.get(0);
            List<Map<String, Object>> linked = jdbc.queryForList(
                    "SELECT p.board_id, p.point_id, p.tier, p.district_id FROM city_game_point p JOIN city_game_board b ON b.board_id = p.board_id "
                            + "WHERE p.place_id = ? AND p.del_flag = 0 AND b.lifecycle = 'OPEN' ORDER BY b.board_id LIMIT 1", poiId);
            if (!linked.isEmpty()) {
                Map<String, Object> l = new HashMap<>(body);
                l.put("boardId", linked.get(0).get("board_id"));
                l.put("pointId", linked.get(0).get("point_id"));
                return resolveTarget(l);
            }
            return new Target("poi:" + poiId, poiId, null, null, null, (String) r.get("name"), dbl(r.get("lat")), dbl(r.get("lng")),
                    1, null, r.get("merchant_id") instanceof Number ? ((Number) r.get("merchant_id")).longValue() : null, (String) r.get("city_code"));
        }
        throw new CityGameException(400, "TARGET_REQUIRED", "请指定地点");
    }

    private Map<String, Object> activeTerritory(Target t, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = t.boardId() != null && t.pointId() != null
                ? jdbc.queryForList("SELECT * FROM roam_territory WHERE board_id = ? AND point_id = ? AND status = 'ACTIVE' LIMIT 1" + suffix, t.boardId(), t.pointId())
                : jdbc.queryForList("SELECT * FROM roam_territory WHERE poi_id = ? AND status = 'ACTIVE' LIMIT 1" + suffix, t.poiId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> territoryForUpdate(String territoryId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM roam_territory WHERE territory_id = ? FOR UPDATE", territoryId);
        if (rows.isEmpty()) throw new CityGameException(404, "TERRITORY_NOT_FOUND", "领地不存在");
        return rows.get(0);
    }

    private boolean staffOf(long memberId, Long merchantId) {
        if (merchants == null || merchantId == null) return false;
        try {
            MerchantAccessContext ctx = merchants.resolve(memberId);
            return ctx != null && merchantId.equals(ctx.getMerchantId());
        } catch (RuntimeException failClosed) {
            return true;
        }
    }

    private Long merchantOf(Map<String, Object> territory) {
        if (territory.get("board_id") != null && territory.get("point_id") != null) {
            List<Map<String, Object>> r = jdbc.queryForList("SELECT merchant_id FROM city_game_point WHERE board_id = ? AND point_id = ?",
                    territory.get("board_id"), territory.get("point_id"));
            if (!r.isEmpty() && r.get(0).get("merchant_id") instanceof Number) return ((Number) r.get(0).get("merchant_id")).longValue();
        }
        if (territory.get("poi_id") instanceof Number) {
            List<Map<String, Object>> r = jdbc.queryForList("SELECT merchant_id FROM roam_poi WHERE id = ?", territory.get("poi_id"));
            if (!r.isEmpty() && r.get(0).get("merchant_id") instanceof Number) return ((Number) r.get(0).get("merchant_id")).longValue();
        }
        return null;
    }

    private boolean districtBlocked(String boardId, String districtId) {
        if (boardId == null || districtId == null) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM city_district WHERE board_id = ? AND district_id = ? AND safety_blocked = 1",
                Integer.class, boardId, districtId);
        return n != null && n > 0;
    }

    // =================================================================== evidence

    @SuppressWarnings("unchecked")
    static CityMonopolyRules.Evidence parseEvidence(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("evidence");
        if (!(raw instanceof Map)) throw new CityGameException(400, "EVIDENCE_REQUIRED", "缺少定位证据");
        Map<String, Object> e = (Map<String, Object>) raw;
        Double lat = dbl(e.get("lat"));
        Double lng = dbl(e.get("lng"));
        if (lat == null || lng == null || !RoamTerritoryService.validCoord(lat, lng)) {
            throw new CityGameException(400, "INVALID_COORDINATES", "无效的坐标");
        }
        List<CityMonopolyRules.Sample> samples = new ArrayList<>();
        if (e.get("samples") instanceof List) {
            for (Object o : (List<Object>) e.get("samples")) {
                if (!(o instanceof Map) || samples.size() >= 64) continue;
                Map<String, Object> s = (Map<String, Object>) o;
                Long t = s.get("t") instanceof Number ? ((Number) s.get("t")).longValue() : null;
                Double sl = dbl(s.get("lat"));
                Double sg = dbl(s.get("lng"));
                if (t != null && sl != null && sg != null && RoamTerritoryService.validCoord(sl, sg)) {
                    samples.add(new CityMonopolyRules.Sample(t, sl, sg, dbl(s.get("hAcc"))));
                }
            }
        }
        Long phash = null;
        Object ph = e.get("phash");
        if (ph instanceof Number) phash = ((Number) ph).longValue();
        else if (ph instanceof String && !((String) ph).isEmpty()) {
            try { phash = Long.parseUnsignedLong((String) ph, 16); } catch (NumberFormatException ignored) { phash = null; }
        }
        return new CityMonopolyRules.Evidence(lat, lng, dbl(e.get("hAcc")), samples, Boolean.TRUE.equals(e.get("simulated")),
                false, Boolean.TRUE.equals(e.get("merchantQr")) || e.get("merchantQr") instanceof String,
                Boolean.TRUE.equals(e.get("inAppCamera")),
                e.get("capturedAt") instanceof Number ? ((Number) e.get("capturedAt")).longValue() : null,
                dbl(e.get("captureLat")), dbl(e.get("captureLng")), phash, dbl(e.get("recaptureScore")));
    }

    private CityMonopolyRules.Evidence withAttest(CityMonopolyRules.Evidence ev, boolean ok) {
        return new CityMonopolyRules.Evidence(ev.lat(), ev.lng(), ev.hAcc(), ev.samples(), ev.simulated(), ok, ev.merchantQr(),
                ev.inAppCamera(), ev.capturedAtMillis(), ev.captureLat(), ev.captureLng(), ev.phash(), ev.recaptureScore());
    }

    @SuppressWarnings("unchecked")
    private boolean verifyAttest(long memberId, String kind, String requestId, String quoteHash, Map<String, Object> body) {
        if (attest == null) return false;
        Map<String, Object> e = body.get("evidence") instanceof Map ? (Map<String, Object>) body.get("evidence") : Map.of();
        String assertion = str(e, "attestAssertion");
        String keyId = str(e, "deviceKeyId");
        if (assertion == null || keyId == null) return false;
        try {
            return attest.verify(memberId, keyId, CityAttestVerifier.canonicalPayload(kind, memberId, requestId, quoteHash, keyId), assertion);
        } catch (RuntimeException e2) {
            return false;
        }
    }

    private long recordEvidence(String boardId, String targetKey, String territoryId, long memberId, String kind,
                                CityMonopolyRules.Evidence ev, CityMonopolyRules.ClaimVerdict v, String verdict, String photo) {
        jdbc.update("INSERT INTO city_claim_evidence(board_id, target_key, territory_id, member_id, kind, photo_asset_id, phash, captured_at, lat, lng, h_acc, "
                        + "sample_count, dwell_s, max_speed_mps, simulated, attest_ok, merchant_qr, risk_score, verdict, reasons) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                boardId, targetKey, territoryId, memberId, kind, photo, ev.phash(),
                ev.capturedAtMillis() == null ? null : new Timestamp(ev.capturedAtMillis()), ev.lat(), ev.lng(), ev.hAcc(),
                ev.samples().size(), v.dwellSeconds(), Math.min(9999, v.maxSpeedMps()), ev.simulated() ? 1 : 0, ev.attestVerified() ? 1 : 0,
                ev.merchantQr() ? 1 : 0, Math.min(1.0, v.riskScore()), verdict, String.join(",", v.reasons()));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (v.auditFlags().contains("SPOOF")) spoofFlag(memberId, id);
        if (v.auditFlags().contains("TELEPORT_AUDIT")) signal(memberId, "TELEPORT", v.maxSpeedMps(), null, Map.of("evidenceId", id), "FLAG");
        return id == null ? 0 : id;
    }

    private void signal(long memberId, String kind, double score, Long until, Map<String, Object> detail, String action) {
        jdbc.update("INSERT INTO city_abuse_signal(member_id, kind, score, window_from, window_to, detail_json, status, action) VALUES (?,?,?,?,?,?, 'OPEN', ?)",
                memberId, kind, score, new Timestamp(now()), until == null ? null : new Timestamp(until), JSON.toJSONString(detail), action);
    }

    /** Spoof flag; ≥3 within 30 days → 7-day CITY ban; a repeat ban → season ban with the season score cleared. */
    private void spoofFlag(long memberId, Long evidenceId) {
        CityMonopolyConfig c = configs.global();
        long now = now();
        signal(memberId, "SPOOF", 1, null, Map.of("evidenceId", evidenceId == null ? 0 : evidenceId), "FLAG");
        long window = Math.round(c.num("city.monopoly.abuse.spoof.flags_window_d") * 86_400_000L);
        Integer flags = jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE member_id = ? AND kind = 'SPOOF' AND action = 'FLAG' "
                + "AND status <> 'DISMISSED' AND created_at >= ?", Integer.class, memberId, new Timestamp(now - window));
        if (flags == null || flags < c.num("city.monopoly.abuse.spoof.flags_for_ban") || banned(memberId, now)) return;
        Integer priorBans = jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE member_id = ? AND action = 'BAN' AND status <> 'DISMISSED'",
                Integer.class, memberId);
        CityMonopolyRules.Season s = season(c, now);
        if (priorBans != null && priorBans > 0) {
            signal(memberId, "SPOOF", flags, s.endsAt().toInstant().toEpochMilli(), Map.of("seasonId", s.seasonId()), "SEASON_BAN");
            jdbc.update("INSERT INTO city_member_season(member_id, season_id, score_cleared) VALUES (?,?,1) ON DUPLICATE KEY UPDATE score_cleared = 1",
                    memberId, s.seasonId());
        } else {
            signal(memberId, "SPOOF", flags, now + Math.round(c.num("city.monopoly.abuse.spoof.ban_d") * 86_400_000L), Map.of(), "BAN");
        }
    }

    private List<Long> hashes(String sql, Object... args) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args)) if (r.get("phash") instanceof Number) out.add(((Number) r.get("phash")).longValue());
        return out;
    }

    // =================================================================== claim

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claimQuote(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        Target t = resolveTarget(body == null ? Map.of() : body);
        CityMonopolyConfig c = configs.forBoard(t.boardId());
        requireEnabled(c);
        requireOpenSeason(c, now());
        Map<String, Object> held = activeTerritory(t, false);
        if (held != null) {
            boolean mine = ((Number) held.get("owner_member_id")).longValue() == memberId;
            throw new CityGameException(409, mine ? "ALREADY_HELD" : "CHALLENGE_REQUIRED", mine ? "你已持有该地点" : "该地点已有主人，请发起挑战");
        }
        long cost = CityMonopolyRules.claimCost(c, t.tier());
        Map<String, Object> payload = targetPayload(t);
        payload.put("protectionHours", CityMonopolyRules.protectionHours(c, CityMonopolyRules.ProtectionEvent.CLAIM, finale(c, now())));
        payload.put("balance", balance(memberId));
        return quote(memberId, "CLAIM", t.key(), cost, payload, c);
    }

    private Map<String, Object> targetPayload(Target t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("targetKey", t.key());
        if (t.poiId() != null) p.put("poiId", t.poiId());
        if (t.boardId() != null) { p.put("boardId", t.boardId()); p.put("pointId", t.pointId()); }
        if (t.propertyId() != null) p.put("propertyId", t.propertyId());
        p.put("tier", t.tier());
        if (t.districtId() != null) p.put("districtId", t.districtId());
        p.put("title", t.title());
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claimCommand(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        if (body == null) throw new CityGameException(400, "REQUEST_INVALID", "请求体不能为空");
        String requestId = str(body, "requestId");
        Map<String, Object> replayed = replay(memberId, requestId, "CLAIM", body);
        if (replayed != null) return replayed;
        Map<String, Object> q = consumeQuote(memberId, "CLAIM", body);
        Target t = resolveTarget(q);
        CityMonopolyConfig c = configs.forBoard(t.boardId());
        requireEnabled(c);
        requireSameRules(q, c);
        long now = now();
        requireOpenSeason(c, now);
        String seasonId = season(c, now).seasonId();
        CityMonopolyRules.Evidence ev = withAttest(parseEvidence(body),
                verifyAttest(memberId, "CLAIM", requestId, (String) q.get("_quoteHash"), body));
        @SuppressWarnings("unchecked")
        Map<String, Object> evMap = (Map<String, Object>) body.get("evidence");
        String photo = str(evMap, "photoAssetId");

        MemberFacts facts = memberFacts(memberId);
        LocalDate today = day(c, now);
        Timestamp dayStart = Timestamp.valueOf(today.atStartOfDay());
        Integer claimsToday = jdbc.queryForObject("SELECT COUNT(*) FROM city_claim_evidence WHERE member_id = ? AND kind = 'CLAIM' AND verdict IN ('PASS','REVIEW') AND created_at >= ?",
                Integer.class, memberId, dayStart);
        Integer failedHere = jdbc.queryForObject("SELECT COUNT(*) FROM city_claim_evidence WHERE member_id = ? AND kind = 'CLAIM' AND verdict = 'REJECTED' AND target_key = ? AND created_at >= ?",
                Integer.class, memberId, t.key(), dayStart);
        long cooldown = Math.round(c.num("city.monopoly.claim.reclaim_after_loss_cooldown_h") * 3_600_000L);
        Integer lost = jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory WHERE owner_member_id = ? AND rules_kind = 'MONOPOLY' "
                        + "AND status <> 'ACTIVE' AND released_at >= ? AND (poi_id <=> ?) AND (point_id <=> ?)",
                Integer.class, memberId, new Timestamp(now - cooldown), t.poiId(), t.pointId());
        Integer holdings = jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory WHERE owner_member_id = ? AND rules_kind = 'MONOPOLY' AND status = 'ACTIVE'",
                Integer.class, memberId);
        Integer lit = jdbc.queryForObject("SELECT COUNT(*) FROM roam_fog_tile WHERE member_id = ? AND geohash7 = ?",
                Integer.class, memberId, RoamTerritoryService.encodeGeohash7(t.lat(), t.lng()));
        boolean litOrNow = (lit != null && lit > 0)
                || GeoDistanceUtils.haversineMeters(ev.lat(), ev.lng(), t.lat(), t.lng()) <= c.num("city.monopoly.claim.radius_m");
        List<Long> own = hashes("SELECT phash FROM city_claim_evidence WHERE member_id = ? AND target_key = ? AND phash IS NOT NULL", memberId, t.key());
        List<Long> others = hashes("SELECT phash FROM city_claim_evidence WHERE member_id <> ? AND target_key = ? AND phash IS NOT NULL", memberId, t.key());
        CityMonopolyRules.ClaimContext ctx = new CityMonopolyRules.ClaimContext(t.lat(), t.lng(), now, zdt(c, now).toLocalTime(), true,
                facts.accountAgeDays(), facts.level(), facts.phoneBound(), n(claimsToday), n(failedHere), n(lost) > 0, n(holdings),
                attrLevel(memberId, "explore"), staffOf(memberId, t.merchantId()), districtBlocked(t.boardId(), t.districtId()), litOrNow,
                own, others, banned(memberId, now), true);
        CityMonopolyRules.ClaimVerdict v = CityMonopolyRules.evaluateClaim(c, ev, ctx);
        List<String> reasons = new ArrayList<>(v.reasons());
        if (photo == null || photo.isBlank() || photo.length() > 500) reasons.add("PHOTO_REQUIRED");
        Map<String, Object> held = activeTerritory(t, true);
        if (held != null) reasons.add("ALREADY_HELD");
        long cost = ((Number) q.get("_cost")).longValue();
        if (reasons.isEmpty() && !debit(memberId, seasonId, cost, "CLAIM", "CLAIM", requestId, "城市大富翁占领消耗积分")) {
            reasons.add("INSUFFICIENT_POINTS");
        }
        if (!reasons.isEmpty()) {
            CityMonopolyRules.ClaimVerdict rejected = new CityMonopolyRules.ClaimVerdict(reasons, v.auditFlags(), v.distanceM(), v.dwellSeconds(), v.maxSpeedMps(), v.riskScore());
            recordEvidence(t.boardId(), t.key(), null, memberId, "CLAIM", ev, rejected, "REJECTED", photo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("outcome", "REJECTED");
            out.put("reasons", reasons);
            out.put("balance", balance(memberId));
            return receipt(memberId, requestId, "CLAIM", body, out);
        }
        boolean review = CityMonopolyRules.needsHumanReview(c, v.riskScore(), requestId);
        long evidenceId = recordEvidence(t.boardId(), t.key(), null, memberId, "CLAIM", ev, v, review ? "REVIEW" : "PASS", photo);
        long protH = CityMonopolyRules.protectionHours(c, CityMonopolyRules.ProtectionEvent.CLAIM, finale(c, now));
        String territoryId = insertTerritory(t, memberId, requestId, photo == null ? "" : photo, seasonId, 0, cost, now, protH, evidenceId);
        jdbc.update("UPDATE city_claim_evidence SET territory_id = ? WHERE id = ?", territoryId, evidenceId);
        if (territories != null && t.boardId() != null && t.propertyId() != null) {
            territories.syncBoardPropertyOnClaim(t.boardId(), t.propertyId(), t.pointId(), memberId, territoryId, photo,
                    new Timestamp(now + protH * 3_600_000L));
        }
        xp(memberId, c.integer("city.monopoly.xp.claim"), "城市大富翁占领");
        axp(memberId, AttributeRules.Source.FIRST_CLAIM_IN_SEASON, seasonId + ":" + t.key());
        seasonStat(memberId, seasonId, "first_claims", 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", "CLAIMED");
        out.put("reasons", List.of());
        out.put("humanReview", review);
        out.put("property", wire(c, jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", territoryId), memberId, true));
        out.put("balance", balance(memberId));
        return receipt(memberId, requestId, "CLAIM", body, out);
    }

    private String insertTerritory(Target t, long memberId, String requestId, String photo, String seasonId, int level,
                                   long invested, long now, long protH, Long evidenceId) {
        String territoryId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roam_territory(territory_id, geohash7, poi_id, board_id, season_id, property_id, point_id, title, city_code, "
                        + "latitude, longitude, radius_m, owner_member_id, claim_photo_url, claim_request_id, checkin_toll_points, owner_revenue_points, "
                        + "revenue_mode, checkin_count, revenue_total_points, status, version, claimed_at, protected_until, rules_kind, tier, upgrade_level, "
                        + "invested, district_id, last_owner_visit_at, decay_state, claim_evidence_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,'POINTS',0,0,'ACTIVE',1,?,?,'MONOPOLY',?,?,?,?,?,'OK',?)",
                territoryId, RoamTerritoryService.encodeGeohash7(t.lat(), t.lng()), t.poiId(), t.boardId(), seasonId, t.propertyId(), t.pointId(),
                t.title() == null ? "城市地产" : (t.title().length() > 160 ? t.title().substring(0, 160) : t.title()), t.cityCode(),
                t.lat(), t.lng(), Math.round(configs.global().num("city.monopoly.yield.visit_radius_m")), memberId, photo, requestId,
                new Timestamp(now), new Timestamp(now + protH * 3_600_000L), t.tier(), level, invested, t.districtId(), new Timestamp(now), evidenceId);
        return territoryId;
    }

    private Target targetOf(Map<String, Object> terr) {
        int tier = CityMonopolyRules.normalizeTier(terr.get("tier") instanceof Number ? ((Number) terr.get("tier")).intValue() : 1);
        return new Target(terr.get("board_id") != null && terr.get("point_id") != null
                ? "board:" + terr.get("board_id") + ":" + terr.get("point_id") : "poi:" + terr.get("poi_id"),
                terr.get("poi_id") instanceof Number ? ((Number) terr.get("poi_id")).longValue() : null,
                (String) terr.get("board_id"), (String) terr.get("point_id"), (String) terr.get("property_id"), (String) terr.get("title"),
                dbl(terr.get("latitude")), dbl(terr.get("longitude")), tier, (String) terr.get("district_id"), merchantOf(terr), (String) terr.get("city_code"));
    }

    /** Presence check shared by upgrade and challenge (GPS rules of §2.2, no photo, no account gates). */
    private CityMonopolyRules.ClaimVerdict presence(CityMonopolyConfig c, long memberId, Target t, CityMonopolyRules.Evidence ev, long now) {
        CityMonopolyRules.ClaimContext ctx = new CityMonopolyRules.ClaimContext(t.lat(), t.lng(), now, zdt(c, now).toLocalTime(), false,
                0, 0, true, 0, 0, false, 0, 0, false, districtBlocked(t.boardId(), t.districtId()), true, List.of(), List.of(),
                banned(memberId, now), false);
        return CityMonopolyRules.evaluateClaim(c, ev, ctx);
    }

    // =================================================================== upgrade

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upgradeQuote(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        Map<String, Object> terr = ownedTerritory(memberId, str(body, "territoryId"), false);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        requireEnabled(c);
        requireOpenSeason(c, now());
        int level = ((Number) terr.get("upgrade_level")).intValue();
        int tier = ((Number) terr.get("tier")).intValue();
        if (level >= CityMonopolyRules.MAX_LEVEL) throw new CityGameException(409, "MAX_LEVEL", "已是最高等级");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("territoryId", terr.get("territory_id"));
        payload.put("fromLevel", level);
        payload.put("toLevel", level + 1);
        payload.put("yieldMultiplier", CityMonopolyRules.levelYieldMultiplier(c, level + 1));
        return quote(memberId, "UPGRADE", (String) terr.get("territory_id"), CityMonopolyRules.upgradeCost(c, tier, level), payload, c);
    }

    private Map<String, Object> ownedTerritory(long memberId, String territoryId, boolean lock) {
        if (territoryId == null) throw new CityGameException(400, "TERRITORY_REQUIRED", "缺少 territoryId");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM roam_territory WHERE territory_id = ? AND owner_member_id = ? "
                + "AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'" + (lock ? " FOR UPDATE" : ""), territoryId, memberId);
        if (rows.isEmpty()) throw new CityGameException(404, "NOT_OWNER", "不是你持有的地产");
        return rows.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upgradeCommand(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        if (body == null) throw new CityGameException(400, "REQUEST_INVALID", "请求体不能为空");
        String requestId = str(body, "requestId");
        Map<String, Object> replayed = replay(memberId, requestId, "UPGRADE", body);
        if (replayed != null) return replayed;
        Map<String, Object> q = consumeQuote(memberId, "UPGRADE", body);
        Map<String, Object> terr = ownedTerritory(memberId, (String) q.get("territoryId"), true);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        requireEnabled(c);
        requireSameRules(q, c);
        long now = now();
        requireOpenSeason(c, now);
        String seasonId = season(c, now).seasonId();
        Target t = targetOf(terr);
        CityMonopolyRules.Evidence ev = withAttest(parseEvidence(body), verifyAttest(memberId, "UPGRADE", requestId, (String) q.get("_quoteHash"), body));
        CityMonopolyRules.ClaimVerdict v = presence(c, memberId, t, ev, now);
        List<String> reasons = new ArrayList<>(v.reasons());
        int level = ((Number) terr.get("upgrade_level")).intValue();
        if (level != ((Number) q.get("fromLevel")).intValue()) reasons.add("LEVEL_CHANGED");
        LocalDate today = day(c, now);
        if (terr.get("upgrades_day") != null && today.equals(((Date) terr.get("upgrades_day")).toLocalDate())
                && c.integer("city.monopoly.upgrade.per_property_per_day") <= 1) {
            reasons.add("PROPERTY_UPGRADED_TODAY");
        }
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM city_points_flow WHERE member_id = ? AND kind = 'UPGRADE' AND created_at >= ?",
                Integer.class, memberId, Timestamp.valueOf(today.atStartOfDay()));
        if (n(mine) >= c.integer("city.monopoly.upgrade.per_member_per_day")) reasons.add("DAILY_UPGRADE_LIMIT");
        long cost = ((Number) q.get("_cost")).longValue();
        if (reasons.isEmpty() && !debit(memberId, seasonId, cost, "UPGRADE", "UPGRADE", requestId, "城市大富翁升级消耗积分")) reasons.add("INSUFFICIENT_POINTS");
        recordEvidence(t.boardId(), t.key(), (String) terr.get("territory_id"), memberId, "UPGRADE", ev,
                new CityMonopolyRules.ClaimVerdict(reasons, v.auditFlags(), v.distanceM(), v.dwellSeconds(), v.maxSpeedMps(), v.riskScore()),
                reasons.isEmpty() ? "PASS" : "REJECTED", null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!reasons.isEmpty()) {
            out.put("outcome", "REJECTED");
            out.put("reasons", reasons);
            return receipt(memberId, requestId, "UPGRADE", body, out);
        }
        jdbc.update("UPDATE roam_territory SET upgrade_level = upgrade_level + 1, invested = invested + ?, upgrades_day = ?, last_owner_visit_at = ?, "
                        + "decay_state = 'OK', version = version + 1 WHERE territory_id = ? AND owner_member_id = ? AND upgrade_level = ?",
                cost, Date.valueOf(today), new Timestamp(now), terr.get("territory_id"), memberId, level);
        xp(memberId, c.integer("city.monopoly.xp.upgrade"), "城市大富翁升级");
        axp(memberId, AttributeRules.Source.UPGRADE, "upgrade:" + requestId);
        out.put("outcome", "UPGRADED");
        out.put("reasons", List.of());
        out.put("property", wire(c, jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", terr.get("territory_id")), memberId, true));
        out.put("balance", balance(memberId));
        return receipt(memberId, requestId, "UPGRADE", body, out);
    }

    // =================================================================== rent (visitor check-in)

    /**
     * Rent hook for a visitor check-in on a held territory (called inside the caller's transaction by
     * {@link RoamTerritoryService}). Writes a PENDING {@code city_yield_event} when eligible; never pays out directly.
     */
    public Map<String, Object> onVisit(long visitorId, Map<String, Object> terr, Double lat, Double lng, Double hAcc,
                                       String checkinRef, String deviceKey) {
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        Map<String, Object> out = new LinkedHashMap<>();
        String territoryId = (String) terr.get("territory_id");
        long owner = ((Number) terr.get("owner_member_id")).longValue();
        long now = now();
        LocalDate today = day(c, now);
        String seasonId = season(c, now).seasonId();
        out.put("propertyId", territoryId);
        out.put("currency", "POINTS");
        out.put("holderMasked", mask(owner));
        if (visitorId == owner) {
            jdbc.update("UPDATE roam_territory SET last_owner_visit_at = ?, decay_state = 'OK' WHERE territory_id = ? AND owner_member_id = ?",
                    new Timestamp(now), territoryId, owner);
            axp(owner, AttributeRules.Source.OWNER_MAINTAIN, "maintain:" + territoryId + ":" + today);
            out.put("yield", 0L);
            out.put("visitorPoints", 0L);
            out.put("ineligibleReason", "SELF_VISIT");
            return out;
        }
        double distance = lat == null || lng == null ? Double.MAX_VALUE
                : GeoDistanceUtils.haversineMeters(lat, lng, dbl(terr.get("latitude")), dbl(terr.get("longitude")));
        String boardId = (String) terr.get("board_id");
        String ownerTeam = teamOf(boardId, owner);
        boolean sameTeam = ownerTeam != null && ownerTeam.equals(teamOf(boardId, visitorId));
        MemberFacts facts = memberFacts(visitorId);
        String device = deviceKey != null && !deviceKey.isBlank() ? deviceKey.trim() : (facts.unionId() == null ? null : "wx:" + facts.unionId());
        Timestamp d14 = new Timestamp(now - 14L * 86_400_000L);
        Timestamp d7 = new Timestamp(now - 7L * 86_400_000L);
        Timestamp d30 = new Timestamp(now - 30L * 86_400_000L);
        Date day = Date.valueOf(today);
        int otherDevice = 0;
        boolean frozen = false;
        if (device != null) {
            otherDevice = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_device_account WHERE device_key = ? AND member_id <> ? AND last_yield_at >= ?",
                    Integer.class, device, visitorId, d30));
            jdbc.update("INSERT IGNORE INTO city_device_account(device_key, member_id) VALUES (?,?)", device, visitorId);
            int accounts = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_device_account WHERE device_key = ? AND first_seen_at >= ?", Integer.class, device, d30));
            if (accounts >= c.num("city.monopoly.abuse.device.freeze_at_accounts")) {
                frozen = true;
                freezeDevice(device, now);
            }
        }
        int pairProperty = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE visitor_member_id = ? AND territory_id = ? AND state <> 'VOID' AND created_at >= ?",
                Integer.class, visitorId, territoryId, new Timestamp(now - Math.round(c.num("city.monopoly.yield.pair_cap.per_property_days") * 86_400_000L))));
        int propertyToday = terr.get("daily_visits_day") != null && today.equals(((Date) terr.get("daily_visits_day")).toLocalDate())
                ? ((Number) terr.get("daily_visits")).intValue() : 0;
        CityMonopolyRules.VisitFacts f = new CityMonopolyRules.VisitFacts(false, sameTeam, staffOf(visitorId, merchantOf(terr)),
                distance, hAcc, CityMonopolyRules.timeMultiplier(c, zdt(c, now).toLocalTime()),
                districtBlocked(boardId, (String) terr.get("district_id")),
                facts.accountAgeDays(), facts.level(), facts.phoneBound(),
                n(jdbc.queryForObject("SELECT COUNT(DISTINCT holder_member_id) FROM city_yield_event WHERE visitor_member_id = ? AND state <> 'VOID' AND created_at >= ?", Integer.class, visitorId, d14)),
                n(jdbc.queryForObject("SELECT COUNT(*) FROM member_visited_poi WHERE member_id = ? AND first_visit_time >= ?", Integer.class, visitorId, d14)),
                otherDevice, frozen,
                n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE visitor_member_id = ? AND board_day = ? AND state <> 'VOID'", Integer.class, visitorId, day)),
                n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE visitor_member_id = ? AND territory_id = ? AND board_day = ?", Integer.class, visitorId, territoryId, day)),
                pairProperty,
                n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE visitor_member_id = ? AND holder_member_id = ? AND state <> 'VOID' AND created_at >= ?", Integer.class, visitorId, owner, d7)),
                propertyToday,
                n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE visitor_member_id = ? AND holder_member_id = ? AND state <> 'VOID' AND created_at >= ?", Integer.class, visitorId, owner, d14)),
                n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE visitor_member_id = ? AND holder_member_id = ? AND state <> 'VOID' AND created_at >= ?", Integer.class, owner, visitorId, d14)),
                banned(visitorId, now), banned(owner, now));
        List<String> reasons = CityMonopolyRules.yieldIneligibility(c, f);
        if (!reasons.isEmpty()) {
            out.put("yield", 0L);
            out.put("visitorPoints", 0L);
            out.put("ineligibleReason", reasons.get(0));
            out.put("reasons", reasons);
            return out;
        }
        String districtId = (String) terr.get("district_id");
        CityMonopolyRules.SetState set = CityMonopolyRules.SetState.NONE;
        boolean teamBonus = false;
        if (boardId != null && districtId != null) {
            List<Map<String, Object>> d = jdbc.queryForList("SELECT ownable_count FROM city_district WHERE board_id = ? AND district_id = ?", boardId, districtId);
            int ownable = d.isEmpty() ? 0 : ((Number) d.get(0).get("ownable_count")).intValue();
            int held = n(jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory WHERE board_id = ? AND district_id = ? AND owner_member_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'",
                    Integer.class, boardId, districtId, owner));
            set = CityMonopolyRules.setState(c, held, ownable);
            if (ownerTeam != null) {
                int teamHeld = n(jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory t JOIN city_game_participation p ON p.board_id = t.board_id AND p.member_id = t.owner_member_id "
                                + "WHERE t.board_id = ? AND t.district_id = ? AND p.team_id = ? AND t.status = 'ACTIVE' AND t.rules_kind = 'MONOPOLY'",
                        Integer.class, boardId, districtId, ownerTeam));
                teamBonus = teamHeld >= c.num("city.monopoly.yield.team_bonus.min_team_props_in_district");
            }
        }
        boolean penalty = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE member_id = ? AND kind = 'CONCENTRATION' AND status <> 'DISMISSED' AND window_to > ?",
                Integer.class, owner, new Timestamp(now))) > 0;
        int ownerCharm = attrLevel(owner, "charm");
        CityMonopolyRules.YieldQuote yq = CityMonopolyRules.computeYield(c, new CityMonopolyRules.YieldInput(
                ((Number) terr.get("tier")).intValue(), ((Number) terr.get("upgrade_level")).intValue(), set, zdt(c, now).toLocalTime(),
                CityMonopolyRules.Weather.CLEAR, c.num("city.monopoly.yield.event_multiplier"), c.num("city.monopoly.yield.city_launch_multiplier"),
                teamBonus, ownerCharm, "WEATHERED".equals(terr.get("decay_state")), penalty));
        Long rawToday = jdbc.queryForObject("SELECT COALESCE(SUM(raw),0) FROM city_yield_event WHERE holder_member_id = ? AND board_day = ? AND state <> 'VOID'",
                Long.class, owner, day);
        CityMonopolyRules.CapResult cap = CityMonopolyRules.applyOwnerDailyCap(c, rawToday == null ? 0 : rawToday, yq.raw(), ownerCharm);
        long visitorReward = yq.raw() > 0 ? Math.round(c.num("city.monopoly.yield.visitor_reward")) : 0;
        String capReason = cap.capReason() != null ? cap.capReason() : (yq.capped() ? "MULTIPLIER_CAP" : null);
        try {
            jdbc.update("INSERT INTO city_yield_event(board_id, season_id, territory_id, holder_member_id, visitor_member_id, checkin_ref, board_day, base, mult_json, raw, granted, "
                            + "visitor_reward, cap_reason, state, device_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)",
                    boardId, seasonId, territoryId, owner, visitorId, checkinRef, day, yq.base(), JSON.toJSONString(yq.multipliers()),
                    yq.raw(), cap.granted(), visitorReward, capReason, device);
        } catch (DuplicateKeyException sameDay) {
            out.put("yield", 0L);
            out.put("visitorPoints", 0L);
            out.put("ineligibleReason", "VISITOR_PROPERTY_DAILY_CAP");
            return out;
        }
        Long eventId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        flow(owner, seasonId, cap.granted(), "PENDING", "YIELD", "YIELD_EVENT", String.valueOf(eventId), null);
        flow(visitorId, seasonId, visitorReward, "PENDING", "VISIT", "YIELD_EVENT", String.valueOf(eventId), null);
        jdbc.update("UPDATE roam_territory SET daily_visits = IF(daily_visits_day = ?, daily_visits + 1, 1), daily_visits_day = ?, "
                        + "checkin_count = checkin_count + 1, revenue_total_points = revenue_total_points + ?, last_checkin_at = ? WHERE territory_id = ?",
                day, day, cap.granted(), new Timestamp(now), territoryId);
        if (device != null) jdbc.update("UPDATE city_device_account SET last_yield_at = ? WHERE device_key = ? AND member_id = ?", new Timestamp(now), device, visitorId);
        axp(visitorId, AttributeRules.Source.VISITOR_YIELD, "yield:" + eventId);
        out.put("yieldEventId", eventId);
        out.put("yield", cap.granted());
        out.put("raw", yq.raw());
        out.put("multipliers", yq.multipliers());
        out.put("capReason", capReason);
        out.put("visitorPoints", visitorReward);
        out.put("state", "PENDING");
        out.put("ineligibleReason", null);
        return out;
    }

    private void freezeDevice(String device, long now) {
        List<Long> members = jdbc.queryForList("SELECT member_id FROM city_device_account WHERE device_key = ?", Long.class, device);
        for (Long m : members) {
            jdbc.update("UPDATE city_yield_event SET risk_flags = 'DEVICE_FREEZE' WHERE state = 'PENDING' AND (visitor_member_id = ? OR holder_member_id = ?)", m, m);
        }
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE kind = 'DEVICE' AND status = 'OPEN' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.device')) = ?",
                Integer.class, device);
        if (n(open) == 0 && !members.isEmpty()) {
            signal(members.get(0), "DEVICE", members.size(), null, Map.of("device", device, "members", members), "FREEZE");
        }
    }

    static String mask(long memberId) {
        String s = String.valueOf(memberId);
        return s.length() <= 2 ? "玩家*" : "玩家" + s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }

    // =================================================================== nightly settle / upkeep / decay / abuse

    /** 03:00 risk batch: PENDING rent of earlier days becomes AVAILABLE 积分, or VOID when flagged. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settle() {
        CityMonopolyConfig c = configs.global();
        long now = now();
        Date today = Date.valueOf(day(c, now));
        int settled = 0, voided = 0;
        long paid = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM city_yield_event WHERE state = 'PENDING' AND board_day < ? ORDER BY id LIMIT 5000 FOR UPDATE", today);
        for (Map<String, Object> e : rows) {
            long id = ((Number) e.get("id")).longValue();
            long holder = ((Number) e.get("holder_member_id")).longValue();
            long visitor = ((Number) e.get("visitor_member_id")).longValue();
            String seasonId = (String) e.get("season_id");
            boolean flagged = e.get("risk_flags") != null || confirmedAbuse(holder) || confirmedAbuse(visitor) || banned(holder, now) || banned(visitor, now);
            if (flagged) {
                jdbc.update("UPDATE city_yield_event SET state = 'VOID', settled_at = ? WHERE id = ?", new Timestamp(now), id);
                jdbc.update("UPDATE city_points_flow SET state = 'VOID', settled_at = ? WHERE ref_type = 'YIELD_EVENT' AND ref_id = ? AND state = 'PENDING'",
                        new Timestamp(now), String.valueOf(id));
                voided++;
                continue;
            }
            long granted = ((Number) e.get("granted")).longValue();
            long reward = ((Number) e.get("visitor_reward")).longValue();
            paid += settleFlow(holder, seasonId, "YIELD", id, granted, "城市大富翁收租");
            paid += settleFlow(visitor, seasonId, "VISIT", id, reward, "城市大富翁访客奖励");
            seasonStat(holder, seasonId, "confirmed_rent", granted);
            jdbc.update("UPDATE city_yield_event SET state = 'AVAILABLE', settled_at = ? WHERE id = ?", new Timestamp(now), id);
            settled++;
        }
        return Map.of("settled", settled, "voided", voided, "creditedPoints", paid);
    }

    private long settleFlow(long memberId, String seasonId, String kind, long eventId, long amount, String reason) {
        if (amount <= 0) {
            jdbc.update("UPDATE city_points_flow SET state = 'AVAILABLE', settled_at = ? WHERE member_id = ? AND kind = ? AND ref_type = 'YIELD_EVENT' AND ref_id = ?",
                    new Timestamp(now()), memberId, kind, String.valueOf(eventId));
            return 0;
        }
        String key = "CITYM:" + kind + ":YIELD_EVENT:" + eventId;
        int n = jdbc.update("UPDATE city_points_flow SET state = 'AVAILABLE', settled_at = ?, points_business_key = ? "
                        + "WHERE member_id = ? AND kind = ? AND ref_type = 'YIELD_EVENT' AND ref_id = ? AND state = 'PENDING'",
                new Timestamp(now()), key, memberId, kind, String.valueOf(eventId));
        if (n != 1) return 0;
        return applyCredit(memberId, seasonId, amount, reason, key);
    }

    private boolean confirmedAbuse(long memberId) {
        return n(jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE member_id = ? AND status = 'CONFIRMED' AND kind IN ('SPOOF','DEVICE','RING','RECIPROCITY')",
                Integer.class, memberId)) > 0;
    }

    /** Daily upkeep (2%/day × invested, lore discount), holding-day score and decay. Idempotent per day. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> nightly() {
        CityMonopolyConfig c = configs.global();
        long now = now();
        LocalDate today = day(c, now);
        String seasonId = season(c, now).seasonId();
        Map<String, Double> weights = c.levels("city.monopoly.season.score.holding_day_weight");
        int upkept = 0, released = 0, dropped = 0;
        List<Map<String, Object>> reminders = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("SELECT * FROM roam_territory WHERE status = 'ACTIVE' AND rules_kind = 'MONOPOLY' FOR UPDATE")) {
            String id = (String) t.get("territory_id");
            long owner = ((Number) t.get("owner_member_id")).longValue();
            int lore = attrLevel(owner, "lore");
            long invested = ((Number) t.get("invested")).longValue();
            if (flow(owner, seasonId, 0, "AVAILABLE", "HOLD", "TERRITORY_DAY", id + ":" + today, null)) {
                seasonStat(owner, seasonId, "holding_score", weights.getOrDefault("T" + t.get("tier"), 1.0));
                debitOrDebt(owner, seasonId, CityMonopolyRules.upkeep(c, invested, lore), "UPKEEP", "TERRITORY_DAY", id + ":" + today, "城市大富翁维护费");
                upkept++;
            }
            long last = CityGameSupport.millis(t.get("last_owner_visit_at") != null ? t.get("last_owner_visit_at") : t.get("claimed_at"));
            long days = Math.max(0, (now - last) / 86_400_000L);
            CityMonopolyRules.Decay d = CityMonopolyRules.decay(c, days, lore);
            if (d.reminderDay()) reminders.add(Map.of("memberId", owner, "territoryId", id, "daysSinceVisit", days));
            if (d.state() == CityMonopolyRules.DecayState.RELEASED) {
                jdbc.update("UPDATE roam_territory SET status = 'RELEASED', released_at = ?, release_reason = 'DECAY', version = version + 1 WHERE territory_id = ? AND status = 'ACTIVE'",
                        new Timestamp(now), id);
                released++;
                continue;
            }
            int applied = ((Number) t.get("decay_levels_applied")).intValue();
            int level = ((Number) t.get("upgrade_level")).intValue();
            if (d.state() == CityMonopolyRules.DecayState.DECAYING && d.levelDrops() > applied) {
                int next = Math.max(0, level - (d.levelDrops() - applied));
                jdbc.update("UPDATE roam_territory SET upgrade_level = ?, invested = ?, decay_levels_applied = ?, decay_state = 'DECAYING', version = version + 1 WHERE territory_id = ?",
                        next, CityMonopolyRules.investedAt(c, ((Number) t.get("tier")).intValue(), next), d.levelDrops(), id);
                dropped++;
            } else if (!d.state().name().equals(t.get("decay_state"))) {
                jdbc.update("UPDATE roam_territory SET decay_state = ?, decay_levels_applied = IF(? = 'OK', 0, decay_levels_applied) WHERE territory_id = ?",
                        d.state().name(), d.state().name(), id);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", today.toString());
        out.put("upkept", upkept);
        out.put("levelDrops", dropped);
        out.put("released", released);
        out.put("reminders", reminders);
        out.put("abuse", detectAbuse());
        return out;
    }

    /** Concentration (7 d, top-3 > 60% or HHI > 0.35 → rent ×0.5 for 7 d) and reciprocal rings (≥4 members, ≥80% internal). */
    Map<String, Object> detectAbuse() {
        CityMonopolyConfig c = configs.global();
        long now = now();
        long window = Math.round(c.num("city.monopoly.abuse.concentration.window_d") * 86_400_000L);
        Timestamp from = new Timestamp(now - window);
        Map<Long, Map<Long, Long>> byHolder = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT holder_member_id h, visitor_member_id v, SUM(granted) g FROM city_yield_event "
                + "WHERE state <> 'VOID' AND created_at >= ? GROUP BY holder_member_id, visitor_member_id", from)) {
            byHolder.computeIfAbsent(((Number) r.get("h")).longValue(), k -> new HashMap<>()).put(((Number) r.get("v")).longValue(), ((Number) r.get("g")).longValue());
        }
        int concentrated = 0;
        for (Map.Entry<Long, Map<Long, Long>> e : byHolder.entrySet()) {
            if (!CityMonopolyRules.concentrated(c, e.getValue())) continue;
            boolean active = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_abuse_signal WHERE member_id = ? AND kind = 'CONCENTRATION' AND window_to > ?",
                    Integer.class, e.getKey(), new Timestamp(now))) > 0;
            if (!active) {
                signal(e.getKey(), "CONCENTRATION", 1, now + Math.round(c.num("city.monopoly.abuse.concentration.penalty_d") * 86_400_000L),
                        Map.of("visitors", e.getValue().size()), "PENALTY");
                concentrated++;
            }
        }
        List<Set<Long>> rings = rings(c, byHolder);
        for (Set<Long> ring : rings) {
            for (Long m : ring) {
                signal(m, "RING", ring.size(), null, Map.of("members", new ArrayList<>(ring)), "SUSPEND");
                jdbc.update("UPDATE city_yield_event SET risk_flags = 'RING' WHERE state = 'PENDING' AND (holder_member_id = ? OR visitor_member_id = ?)", m, m);
            }
        }
        return Map.of("concentration", concentrated, "rings", rings.size());
    }

    /**
     * Ring detection over the window's rent graph: communities are connected components of reciprocal edges (A→B and
     * B→A both present). A community with ≥ min_members whose members receive ≥ internal_share of their rent from
     * each other is suspended.
     */
    static List<Set<Long>> rings(CityMonopolyConfig c, Map<Long, Map<Long, Long>> byHolder) {
        Map<Long, Long> parent = new HashMap<>();
        java.util.function.LongUnaryOperator[] find = new java.util.function.LongUnaryOperator[1];
        find[0] = x -> {
            Long p = parent.getOrDefault(x, x);
            if (p == x) return x;
            long root = find[0].applyAsLong(p);
            parent.put(x, root);
            return root;
        };
        for (Map.Entry<Long, Map<Long, Long>> e : byHolder.entrySet()) {
            long b = e.getKey();
            for (long a : e.getValue().keySet()) {
                Map<Long, Long> back = byHolder.get(a);
                if (a != b && back != null && back.containsKey(b)) {
                    long ra = find[0].applyAsLong(a), rb = find[0].applyAsLong(b);
                    if (ra != rb) parent.put(ra, rb);
                }
            }
        }
        Map<Long, Set<Long>> groups = new HashMap<>();
        for (Long k : new ArrayList<>(parent.keySet())) groups.computeIfAbsent(find[0].applyAsLong(k), x -> new HashSet<>()).add(k);
        for (Set<Long> g : groups.values()) for (Long k : new ArrayList<>(g)) g.add(find[0].applyAsLong(k));
        List<Set<Long>> out = new ArrayList<>();
        for (Set<Long> g : new HashSet<>(groups.values())) {
            if (g.size() < c.num("city.monopoly.abuse.ring.min_members")) continue;
            long internal = 0, total = 0;
            for (Long m : g) {
                Map<Long, Long> in = byHolder.getOrDefault(m, Map.of());
                for (Map.Entry<Long, Long> v : in.entrySet()) {
                    total += v.getValue();
                    if (g.contains(v.getKey())) internal += v.getValue();
                }
            }
            if (total > 0 && (double) internal / total >= c.num("city.monopoly.abuse.ring.internal_share")) out.add(g);
        }
        return out;
    }

    /** Admin: confirm a signal → void pending rent and claw back this season's settled rent (debt when short). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reviewSignal(long signalId, boolean confirm, Long reviewer) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM city_abuse_signal WHERE id = ? FOR UPDATE", signalId);
        if (rows.isEmpty()) throw new CityGameException(404, "SIGNAL_NOT_FOUND", "信号不存在");
        long member = ((Number) rows.get(0).get("member_id")).longValue();
        jdbc.update("UPDATE city_abuse_signal SET status = ? WHERE id = ?", confirm ? "CONFIRMED" : "DISMISSED", signalId);
        long clawed = 0;
        if (confirm) {
            CityMonopolyConfig c = configs.global();
            String seasonId = season(c, now()).seasonId();
            jdbc.update("UPDATE city_yield_event SET risk_flags = 'CONFIRMED_ABUSE' WHERE state = 'PENDING' AND (holder_member_id = ? OR visitor_member_id = ?)", member, member);
            for (Map<String, Object> f : jdbc.queryForList("SELECT id, delta FROM city_points_flow WHERE member_id = ? AND season_id = ? AND state = 'AVAILABLE' "
                    + "AND kind IN ('YIELD','VISIT') AND delta > 0", member, seasonId)) {
                long amount = ((Number) f.get("delta")).longValue();
                clawed += debitOrDebt(member, seasonId, amount, "CLAWBACK", "FLOW", String.valueOf(f.get("id")), "城市大富翁作弊收益追回");
                jdbc.update("UPDATE city_points_flow SET state = 'VOID' WHERE id = ?", f.get("id"));
            }
        }
        return Map.of("signalId", signalId, "status", confirm ? "CONFIRMED" : "DISMISSED", "clawedBack", clawed, "reviewer", reviewer == null ? 0 : reviewer);
    }

    public List<Map<String, Object>> abuseQueue(String status, Integer limit) {
        int n = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return jdbc.queryForList("SELECT * FROM city_abuse_signal WHERE status = ? ORDER BY id DESC LIMIT ?", status == null ? "OPEN" : status, n);
    }

    // =================================================================== challenge

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> challengeQuote(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String territoryId = str(body, "territoryId");
        Map<String, Object> terr = monopolyTerritory(territoryId, false);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        requireEnabled(c);
        long now = now();
        requireOpenSeason(c, now);
        List<String> blocked = challengeBlocks(c, memberId, terr, now);
        if (!blocked.isEmpty()) throw new CityGameException(409, blocked.get(0), "当前不可挑战", Map.of("reasons", blocked));
        boolean revenge = revenge(c, memberId, terr, now);
        long fee = CityMonopolyRules.challengeFee(c, ((Number) terr.get("invested")).longValue(), finale(c, now), revenge);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("territoryId", territoryId);
        payload.put("fee", fee);
        payload.put("revengeDiscount", revenge);
        if (scouted(memberId, territoryId, now)) payload.put("dc", dc(c, terr, now));
        payload.put("task", tasks == null ? null : Map.of("taskRef", "challenge:" + territoryId + ":" + UUID.randomUUID()));
        return quote(memberId, "CHALLENGE", territoryId, fee, payload, c);
    }

    private Map<String, Object> monopolyTerritory(String territoryId, boolean lock) {
        if (territoryId == null) throw new CityGameException(400, "TERRITORY_REQUIRED", "缺少 territoryId");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM roam_territory WHERE territory_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'"
                + (lock ? " FOR UPDATE" : ""), territoryId);
        if (rows.isEmpty()) throw new CityGameException(404, "TERRITORY_NOT_FOUND", "地产不存在");
        return rows.get(0);
    }

    private List<String> challengeBlocks(CityMonopolyConfig c, long memberId, Map<String, Object> terr, long now) {
        List<String> r = new ArrayList<>();
        String id = (String) terr.get("territory_id");
        long owner = ((Number) terr.get("owner_member_id")).longValue();
        if (owner == memberId) r.add("OWN_PROPERTY");
        String board = (String) terr.get("board_id");
        String team = teamOf(board, owner);
        if (team != null && team.equals(teamOf(board, memberId))) r.add("SAME_TEAM");
        if (terr.get("protected_until") != null && CityGameSupport.millis(terr.get("protected_until")) > now) r.add("TERRITORY_PROTECTED");
        if (banned(memberId, now)) r.add("CITY_BANNED");
        Timestamp dayStart = Timestamp.valueOf(day(c, now).atStartOfDay());
        if (n(jdbc.queryForObject("SELECT COUNT(*) FROM city_challenge WHERE territory_id = ? AND created_at >= ?", Integer.class, id, dayStart))
                >= c.num("city.monopoly.challenge.per_property_per_day")) r.add("PROPERTY_CHALLENGE_LIMIT");
        if (n(jdbc.queryForObject("SELECT COUNT(*) FROM city_challenge WHERE challenger_id = ? AND created_at >= ?", Integer.class, memberId, dayStart))
                >= c.num("city.monopoly.challenge.per_member_per_day")) r.add("DAILY_CHALLENGE_LIMIT");
        long cd = Math.round(c.num("city.monopoly.challenge.on_loss.challenger_cooldown_h") * 3_600_000L);
        if (n(jdbc.queryForObject("SELECT COUNT(*) FROM city_challenge WHERE territory_id = ? AND challenger_id = ? AND outcome <> 'WIN' AND created_at >= ?",
                Integer.class, id, memberId, new Timestamp(now - cd))) > 0) r.add("CHALLENGER_COOLDOWN");
        return r;
    }

    private boolean revenge(CityMonopolyConfig c, long memberId, Map<String, Object> terr, long now) {
        long window = Math.round(c.num("city.monopoly.challenge.revenge_discount.window_h") * 3_600_000L);
        return n(jdbc.queryForObject("SELECT COUNT(*) FROM city_challenge WHERE defender_id = ? AND challenger_id = ? AND outcome = 'WIN' AND created_at >= ? "
                        + "AND territory_id IN (SELECT territory_id FROM roam_territory WHERE (poi_id <=> ?) AND (point_id <=> ?))",
                Integer.class, memberId, terr.get("owner_member_id"), new Timestamp(now - window), terr.get("poi_id"), terr.get("point_id"))) > 0;
    }

    private boolean scouted(long memberId, String territoryId, long now) {
        return n(jdbc.queryForObject("SELECT COUNT(*) FROM city_monopoly_quote WHERE member_id = ? AND kind = 'SCOUT' AND target_key = ? AND create_time >= ?",
                Integer.class, memberId, territoryId, new Timestamp(now - 86_400_000L))) > 0;
    }

    private int dc(CityMonopolyConfig c, Map<String, Object> terr, long now) {
        long owner = ((Number) terr.get("owner_member_id")).longValue();
        long recent = Math.round(c.num("city.monopoly.challenge.dc.owner_recent_visit_h") * 3_600_000L);
        boolean visited = terr.get("last_owner_visit_at") != null && now - CityGameSupport.millis(terr.get("last_owner_visit_at")) <= recent;
        String board = (String) terr.get("board_id");
        String team = teamOf(board, owner);
        if (!visited && team != null) {
            visited = n(jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory_checkin k JOIN city_game_participation p ON p.member_id = k.member_id AND p.board_id = ? "
                    + "WHERE k.territory_id = ? AND p.team_id = ? AND k.create_time >= ?", Integer.class, board, terr.get("territory_id"), team, new Timestamp(now - recent))) > 0;
        }
        CityMonopolyRules.SetState set = CityMonopolyRules.SetState.NONE;
        String district = (String) terr.get("district_id");
        if (board != null && district != null) {
            List<Map<String, Object>> d = jdbc.queryForList("SELECT ownable_count FROM city_district WHERE board_id = ? AND district_id = ?", board, district);
            int held = n(jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory WHERE board_id = ? AND district_id = ? AND owner_member_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'",
                    Integer.class, board, district, owner));
            set = CityMonopolyRules.setState(c, held, d.isEmpty() ? 0 : ((Number) d.get(0).get("ownable_count")).intValue());
        }
        return CityMonopolyRules.challengeDc(c, ((Number) terr.get("upgrade_level")).intValue(), visited, set, "WEATHERED".equals(terr.get("decay_state")));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> challengeCommand(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        if (body == null) throw new CityGameException(400, "REQUEST_INVALID", "请求体不能为空");
        String requestId = str(body, "requestId");
        Map<String, Object> replayed = replay(memberId, requestId, "CHALLENGE", body);
        if (replayed != null) return replayed;
        Map<String, Object> q = consumeQuote(memberId, "CHALLENGE", body);
        Map<String, Object> terr = monopolyTerritory((String) q.get("territoryId"), true);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        requireEnabled(c);
        requireSameRules(q, c);
        long now = now();
        requireOpenSeason(c, now);
        String seasonId = season(c, now).seasonId();
        String territoryId = (String) terr.get("territory_id");
        long defender = ((Number) terr.get("owner_member_id")).longValue();
        Target t = targetOf(terr);
        CityMonopolyRules.Evidence ev = withAttest(parseEvidence(body), verifyAttest(memberId, "CHALLENGE", requestId, (String) q.get("_quoteHash"), body));
        CityMonopolyRules.ClaimVerdict v = presence(c, memberId, t, ev, now);
        List<String> reasons = new ArrayList<>(v.reasons());
        reasons.addAll(challengeBlocks(c, memberId, terr, now));
        long fee = ((Number) q.get("_cost")).longValue();
        if (reasons.isEmpty() && !debit(memberId, seasonId, fee, "CHALLENGE", "CHALLENGE", requestId, "城市大富翁挑战费")) reasons.add("INSUFFICIENT_POINTS");
        recordEvidence(t.boardId(), t.key(), territoryId, memberId, "CHALLENGE", ev,
                new CityMonopolyRules.ClaimVerdict(reasons, v.auditFlags(), v.distanceM(), v.dwellSeconds(), v.maxSpeedMps(), v.riskScore()),
                reasons.isEmpty() ? "PASS" : "REJECTED", null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!reasons.isEmpty()) {
            out.put("outcome", "REJECTED");
            out.put("reasons", reasons);
            return receipt(memberId, requestId, "CHALLENGE", body, out);
        }
        CityMonopolyRules.TaskResult task = CityMonopolyRules.TaskResult.NONE;
        @SuppressWarnings("unchecked")
        Map<String, Object> issued = q.get("task") instanceof Map ? (Map<String, Object>) q.get("task") : null;
        if (tasks != null && issued != null) {
            task = tasks.verify(memberId, String.valueOf(issued.get("taskRef")), str(body, "taskSubmissionId"));
            if (task == null) task = CityMonopolyRules.TaskResult.FAIL;
        }
        int dc = dc(c, terr, now);
        jdbc.update("UPDATE roam_territory SET challenges_today = IF(challenges_day = ?, challenges_today + 1, 1), challenges_day = ? WHERE territory_id = ?",
                Date.valueOf(day(c, now)), Date.valueOf(day(c, now)), territoryId);
        if (task == CityMonopolyRules.TaskResult.FAIL) {
            CityMonopolyRules.FeeSplit split = CityMonopolyRules.feeSplit(c, fee, false, true);
            creditNow(memberId, seasonId, split.refund(), "REFUND", "CHALLENGE", requestId, "城市大富翁挑战任务失败退款");
            insertChallenge(requestId, terr, seasonId, memberId, defender, fee, dc, 0, false, false, Map.of(), 0, "TASK_FAILED", split);
            out.put("outcome", "TASK_FAILED");
            out.put("feeSplit", split);
            out.put("balance", balance(memberId));
            return receipt(memberId, requestId, "CHALLENGE", body, out);
        }
        Long meters = jdbc.queryForObject("SELECT COALESCE(SUM(distance_m),0) FROM roam_session WHERE member_id = ? AND started_at >= ?", Long.class,
                memberId, new Timestamp(now - Math.round(c.num("city.monopoly.challenge.mods.warmup_window_min") * 60_000L)));
        double kmLast = meters == null ? 0 : meters / 1000.0;
        CityMonopolyRules.Mods mods = CityMonopolyRules.challengeMods(c, new CityMonopolyRules.ModInput(task, attrLevel(memberId, "insight"),
                attrLevel(memberId, "stamina"), kmLast, assists(c, memberId, terr, now), attrLevel(memberId, "charm")));
        int roll = dice.applyAsInt(20);
        boolean win = CityMonopolyRules.challengerWins(c, roll, mods.total(), dc);
        boolean rerolled = false;
        if (!win && Boolean.TRUE.equals(body.get("rerollOnFail"))) {
            int used = n(jdbc.queryForObject("SELECT COALESCE(SUM(reroll_used),0) FROM city_challenge WHERE challenger_id = ? AND created_at >= ?",
                    Integer.class, memberId, Timestamp.valueOf(day(c, now).atStartOfDay())));
            if (used < CityMonopolyRules.rerollsPerDay(c, attrLevel(memberId, "luck"))) {
                roll = dice.applyAsInt(20);
                win = CityMonopolyRules.challengerWins(c, roll, mods.total(), dc);
                rerolled = true;
            }
        }
        CityMonopolyRules.FeeSplit split = CityMonopolyRules.feeSplit(c, fee, win, false);
        creditNow(defender, seasonId, split.defenderComp(), "COMP", "CHALLENGE", memberId + ":" + requestId, "城市大富翁挑战补偿");
        if (roll == 20) axp(memberId, AttributeRules.Source.NAT20, "challenge:" + requestId);
        if (task == CityMonopolyRules.TaskResult.PERFECT) axp(memberId, AttributeRules.Source.CHALLENGE_TASK_PERFECT, "challenge:" + requestId);
        Map<String, Object> property;
        if (win) {
            int level = CityMonopolyRules.levelAfterTakeover(c, ((Number) terr.get("upgrade_level")).intValue());
            jdbc.update("UPDATE roam_territory SET status = 'REVOKED', released_at = ?, release_reason = 'TAKEOVER', version = version + 1 WHERE territory_id = ? AND owner_member_id = ?",
                    new Timestamp(now), territoryId, defender);
            long protH = CityMonopolyRules.protectionHours(c, CityMonopolyRules.ProtectionEvent.TAKEOVER, false);
            String newId = insertTerritory(t, memberId, requestId, String.valueOf(terr.get("claim_photo_url")), seasonId, level,
                    CityMonopolyRules.investedAt(c, t.tier(), level), now, protH, null);
            xp(memberId, c.integer("city.monopoly.xp.takeover_win"), "城市大富翁抢夺成功");
            seasonStat(memberId, seasonId, "takeovers", 1);
            property = wire(c, jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", newId), memberId, true);
        } else {
            long until = Math.max(terr.get("protected_until") == null ? 0 : CityGameSupport.millis(terr.get("protected_until")),
                    now + Math.round(c.num("city.monopoly.challenge.on_loss.protection_h") * 3_600_000L));
            jdbc.update("UPDATE roam_territory SET protected_until = ?, version = version + 1 WHERE territory_id = ? AND owner_member_id = ?",
                    new Timestamp(until), territoryId, defender);
            int defensesToday = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_challenge WHERE defender_id = ? AND outcome = 'LOSS' AND created_at >= ?",
                    Integer.class, defender, Timestamp.valueOf(day(c, now).atStartOfDay())));
            if (defensesToday < c.num("city.monopoly.xp.defense_daily_max")) xp(defender, c.integer("city.monopoly.xp.defense_win"), "城市大富翁防守成功");
            seasonStat(defender, seasonId, "defenses", 1);
            property = wire(c, jdbc.queryForMap("SELECT * FROM roam_territory WHERE territory_id = ?", territoryId), memberId, false);
        }
        insertChallenge(requestId, terr, seasonId, memberId, defender, fee, dc, roll, roll == 20 || roll == 1, rerolled, mods.parts(),
                roll + mods.total(), win ? "WIN" : "LOSS", split);
        out.put("roll", roll);
        out.put("nat", natural(roll));
        out.put("rerolled", rerolled);
        out.put("mods", mods.parts());
        out.put("dc", dc);
        out.put("total", roll + mods.total());
        out.put("outcome", win ? "WIN" : "LOSS");
        out.put("feeSplit", split);
        out.put("property", property);
        out.put("balance", balance(memberId));
        return receipt(memberId, requestId, "CHALLENGE", body, out);
    }

    /** Natural 20 or natural 1, else null. Boxed on every branch: a mixed int/null ternary unboxes null and throws (B10). */
    static Integer natural(int roll) {
        if (roll == 20) return Integer.valueOf(20);
        if (roll == 1) return Integer.valueOf(1);
        return null;
    }

    private int assists(CityMonopolyConfig c, long memberId, Map<String, Object> terr, long now) {
        String board = (String) terr.get("board_id");
        String team = teamOf(board, memberId);
        if (team == null) return 0;
        double radius = c.num("city.monopoly.challenge.mods.assist_radius_m");
        int count = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT e.member_id, e.lat, e.lng FROM city_claim_evidence e JOIN city_game_participation p "
                + "ON p.member_id = e.member_id AND p.board_id = ? WHERE p.team_id = ? AND e.member_id <> ? AND e.created_at >= ? AND e.verdict <> 'REJECTED' "
                + "GROUP BY e.member_id, e.lat, e.lng", board, team, memberId, new Timestamp(now - 600_000L))) {
            Double la = dbl(r.get("lat")), lo = dbl(r.get("lng"));
            if (la != null && lo != null && GeoDistanceUtils.haversineMeters(la, lo, dbl(terr.get("latitude")), dbl(terr.get("longitude"))) <= radius) count++;
        }
        return count;
    }

    private void insertChallenge(String requestId, Map<String, Object> terr, String seasonId, long challenger, long defender, long fee, int dc,
                                 int roll, boolean nat, boolean rerolled, Map<String, Integer> mods, int total, String outcome, CityMonopolyRules.FeeSplit split) {
        jdbc.update("INSERT INTO city_challenge(request_id, board_id, season_id, territory_id, challenger_id, defender_id, fee, dc, roll, nat, reroll_used, mods_json, total, outcome, fee_split_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                requestId, terr.get("board_id"), seasonId, terr.get("territory_id"), challenger, defender, fee, dc, roll, nat ? 1 : 0, rerolled ? 1 : 0,
                JSON.toJSONString(mods), total, outcome, JSON.toJSONString(split));
    }

    // =================================================================== scout / shield / chance / stipend / shop

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> scout(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String requestId = str(body, "requestId");
        Map<String, Object> replayed = replay(memberId, requestId, "SCOUT", body);
        if (replayed != null) return replayed;
        Map<String, Object> terr = monopolyTerritory(str(body, "territoryId"), false);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        requireEnabled(c);
        long now = now();
        String seasonId = season(c, now).seasonId();
        long cost = attrLevel(memberId, "lore") >= 10 ? 0 : Math.round(c.num("city.monopoly.challenge.scout_cost"));
        if (!debit(memberId, seasonId, cost, "SCOUT", "SCOUT", requestId, "城市大富翁侦察")) {
            throw new CityGameException(409, "INSUFFICIENT_POINTS", "积分不足");
        }
        jdbc.update("INSERT INTO city_monopoly_quote(quote_id, member_id, kind, target_key, cost, payload_json, rules_hash, quote_hash, expires_at, used_at) "
                        + "VALUES (?,?,'SCOUT',?,?,NULL,?,?,?,?)", UUID.randomUUID().toString(), memberId, terr.get("territory_id"), cost,
                c.rulesHash(), sha256(requestId), new Timestamp(now + 86_400_000L), new Timestamp(now));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("territoryId", terr.get("territory_id"));
        out.put("dc", dc(c, terr, now));
        out.put("cost", cost);
        out.put("balance", balance(memberId));
        return receipt(memberId, requestId, "SCOUT", body, out);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> shield(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        String requestId = str(body, "requestId");
        Map<String, Object> replayed = replay(memberId, requestId, "SHIELD", body);
        if (replayed != null) return replayed;
        Map<String, Object> terr = ownedTerritory(memberId, str(body, "territoryId"), true);
        CityMonopolyConfig c = configs.forBoard((String) terr.get("board_id"));
        requireEnabled(c);
        long now = now();
        requireOpenSeason(c, now);
        Long until = CityMonopolyRules.shieldUntil(c, now,
                terr.get("protected_until") == null ? null : CityGameSupport.millis(terr.get("protected_until")),
                terr.get("last_shield_at") == null ? null : CityGameSupport.millis(terr.get("last_shield_at")));
        if (until == null) throw new CityGameException(409, "SHIELD_COOLDOWN", "护盾冷却中（每格 7 天 1 次）");
        if (!debit(memberId, season(c, now).seasonId(), Math.round(c.num("city.monopoly.protection.shield.cost")), "SHIELD", "SHIELD", requestId, "城市大富翁护盾")) {
            throw new CityGameException(409, "INSUFFICIENT_POINTS", "积分不足");
        }
        jdbc.update("UPDATE roam_territory SET protected_until = ?, shield_until = ?, last_shield_at = ?, version = version + 1 WHERE territory_id = ? AND owner_member_id = ?",
                new Timestamp(until), new Timestamp(until), new Timestamp(now), terr.get("territory_id"), memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("territoryId", terr.get("territory_id"));
        out.put("protectedUntil", until);
        out.put("balance", balance(memberId));
        return receipt(memberId, requestId, "SHIELD", body, out);
    }

    /** Chance space: server d6 picks the task type; the reward is fixed and paid only after server verification. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> chance(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        requireEnabled(c);
        long now = now();
        requireOpenSeason(c, now);
        String action = str(body, "action") == null ? "ROLL" : str(body, "action");
        String seasonId = season(c, now).seasonId();
        Timestamp dayStart = Timestamp.valueOf(day(c, now).atStartOfDay());
        int doneToday = n(jdbc.queryForObject("SELECT COUNT(*) FROM city_points_flow WHERE member_id = ? AND kind = 'CHANCE' AND created_at >= ?",
                Integer.class, memberId, dayStart));
        if (doneToday >= c.num("city.monopoly.chance.per_day")) throw new CityGameException(409, "CHANCE_DAILY_LIMIT", "今天的机会格任务已完成");
        if ("ROLL".equals(action)) {
            int d6 = dice.applyAsInt(6);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("d6", d6);
            payload.put("taskType", CityMonopolyRules.chanceTask(c, d6));
            payload.put("reward", Math.round(c.num("city.monopoly.chance.reward")));
            payload.put("taskRef", "chance:" + UUID.randomUUID());
            String poi = String.valueOf(num(body, "poiId"));
            return quote(memberId, "CHANCE", "poi:" + poi, Math.round(c.num("city.monopoly.chance.reward")), payload, c);
        }
        if (tasks == null) throw new CityGameException(409, "TASK_VERIFIER_UNAVAILABLE", "任务核验未开放");
        Map<String, Object> q = consumeQuote(memberId, "CHANCE", body);
        requireSameRules(q, c);
        CityMonopolyRules.TaskResult r = tasks.verify(memberId, String.valueOf(q.get("taskRef")), str(body, "taskSubmissionId"));
        if (r != CityMonopolyRules.TaskResult.PASS && r != CityMonopolyRules.TaskResult.PERFECT) {
            return Map.of("outcome", "TASK_FAILED", "reward", 0);
        }
        long reward = ((Number) q.get("_cost")).longValue();
        creditNow(memberId, seasonId, reward, "CHANCE", "CHANCE", String.valueOf(body.get("quoteId")), "城市大富翁机会格");
        axp(memberId, AttributeRules.Source.CHANCE_TASK, "chance:" + body.get("quoteId"));
        return Map.of("outcome", "COMPLETED", "reward", reward, "balance", balance(memberId));
    }

    /** Opening-days stipend (300 积分 once per season; same account gates as claiming). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> stipend(Long memberId) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        requireEnabled(c);
        long now = now();
        requireOpenSeason(c, now);
        CityMonopolyRules.Season s = season(c, now);
        if (CityMonopolyRules.phase(c, s, zdt(c, now)) != CityMonopolyRules.Phase.OPENING) {
            throw new CityGameException(409, "STIPEND_CLOSED", "启动金只在开城期（第 1–3 天）发放");
        }
        MemberFacts f = memberFacts(memberId);
        if (f.accountAgeDays() < c.num("city.monopoly.claim.min_account_age_days") || f.level() < c.num("city.monopoly.claim.min_account_level")
                || !f.phoneBound() || banned(memberId, now)) {
            throw new CityGameException(409, "STIPEND_INELIGIBLE", "账号未满足参与条件");
        }
        long amount = Math.round(c.num("city.monopoly.season.stipend"));
        long credited = creditNow(memberId, s.seasonId(), amount, "STIPEND", "SEASON", s.seasonId(), "城市大富翁开城启动金");
        jdbc.update("UPDATE city_member_season SET stipend_at = IFNULL(stipend_at, ?) WHERE member_id = ? AND season_id = ?", new Timestamp(now), memberId, s.seasonId());
        return Map.of("seasonId", s.seasonId(), "stipend", amount, "alreadyClaimed", credited == 0, "balance", balance(memberId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> purchase(Long memberId, Map<String, Object> body) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        requireEnabled(c);
        String requestId = str(body, "requestId");
        Map<String, Object> replayed = replay(memberId, requestId, "SHOP", body);
        if (replayed != null) return replayed;
        String item = str(body, "item");
        long price;
        if ("inscription".equals(item)) price = Math.round(c.num("city.monopoly.shop.inscription"));
        else if ("district_banner".equals(item)) price = Math.round(c.num("city.monopoly.shop.district_banner"));
        else if (item != null && item.matches("flag_skin_[1-3]")) price = Math.round(c.list("city.monopoly.shop.flag_skin").get(item.charAt(10) - '1'));
        else throw new CityGameException(400, "ITEM_INVALID", "商品不存在");
        String seasonId = season(c, now()).seasonId();
        if (!debit(memberId, seasonId, price, "SHOP", "ITEM", item + ":" + requestId, "城市大富翁外观")) {
            throw new CityGameException(409, "INSUFFICIENT_POINTS", "积分不足");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item);
        out.put("price", price);
        out.put("seasonId", seasonId);
        out.put("balance", balance(memberId));
        return receipt(memberId, requestId, "SHOP", body, out);
    }

    // =================================================================== reads

    Map<String, Object> wire(CityMonopolyConfig c, Map<String, Object> t, long viewer, boolean showDc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("propertyId", t.get("territory_id"));
        out.put("poiId", t.get("poi_id"));
        out.put("boardId", t.get("board_id"));
        out.put("pointId", t.get("point_id"));
        out.put("geohash7", t.get("geohash7"));
        out.put("title", t.get("title"));
        out.put("tier", t.get("tier"));
        out.put("districtId", t.get("district_id"));
        out.put("status", t.get("status"));
        out.put("lvl", t.get("upgrade_level"));
        out.put("invested", t.get("invested"));
        long owner = ((Number) t.get("owner_member_id")).longValue();
        out.put("holder", Map.of("maskedName", mask(owner), "isMe", owner == viewer));
        out.put("protectedUntil", t.get("protected_until") == null ? null : CityGameSupport.millis(t.get("protected_until")));
        out.put("decayState", t.get("decay_state"));
        out.put("lat", dbl(t.get("latitude")));
        out.put("lng", dbl(t.get("longitude")));
        if (showDc && "ACTIVE".equals(t.get("status")) && "MONOPOLY".equals(t.get("rules_kind"))) {
            out.put("baseYield", CityMonopolyRules.baseYield(c, ((Number) t.get("tier")).intValue()));
            out.put("levelMultiplier", CityMonopolyRules.levelYieldMultiplier(c, ((Number) t.get("upgrade_level")).intValue()));
        }
        return out;
    }

    /** Territory layer for a bbox; properties in tiles the viewer has not lit return only district and tier. */
    public Map<String, Object> map(Long memberId, Double minLat, Double minLng, Double maxLat, Double maxLng) {
        CityGameSupport.requireMember(memberId);
        if (minLat == null || minLng == null || maxLat == null || maxLng == null || maxLat - minLat > 0.5 || maxLng - minLng > 0.5) {
            throw new CityGameException(400, "BBOX_INVALID", "地图范围无效");
        }
        CityMonopolyConfig c = configs.global();
        Set<String> lit = new HashSet<>(jdbc.queryForList("SELECT geohash7 FROM roam_fog_tile WHERE member_id = ?", String.class, memberId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("SELECT * FROM roam_territory WHERE status = 'ACTIVE' AND rules_kind = 'MONOPOLY' "
                + "AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 500", minLat, maxLat, minLng, maxLng)) {
            boolean visible = lit.contains(String.valueOf(t.get("geohash7"))) || ((Number) t.get("owner_member_id")).longValue() == memberId;
            if (!visible) {
                Map<String, Object> fogged = new LinkedHashMap<>();
                fogged.put("districtId", t.get("district_id"));
                fogged.put("tier", t.get("tier"));
                fogged.put("geohash7", t.get("geohash7"));
                items.add(fogged);
            } else {
                items.add(wire(c, t, memberId, false));
            }
        }
        return Map.of("items", items, "currency", "POINTS");
    }

    public Map<String, Object> property(Long memberId, String territoryId) {
        CityGameSupport.requireMember(memberId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM roam_territory WHERE territory_id = ?", territoryId);
        if (rows.isEmpty()) throw new CityGameException(404, "TERRITORY_NOT_FOUND", "地产不存在");
        Map<String, Object> t = rows.get(0);
        CityMonopolyConfig c = configs.forBoard((String) t.get("board_id"));
        long now = now();
        Map<String, Object> out = wire(c, t, memberId, true);
        out.put("deedPhoto", t.get("claim_photo_url"));
        out.put("visitsThisWeek", n(jdbc.queryForObject("SELECT COUNT(*) FROM city_yield_event WHERE territory_id = ? AND created_at >= ?",
                Integer.class, territoryId, new Timestamp(now - 7L * 86_400_000L))));
        if ("ACTIVE".equals(t.get("status")) && scouted(memberId, territoryId, now)) out.put("dc", dc(c, t, now));
        return out;
    }

    public Map<String, Object> revenue(Long memberId, Long cursor, Integer limit) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        long now = now();
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        List<Object> args = new ArrayList<>(List.of(memberId));
        String sql = "SELECT id, territory_id, visitor_member_id, base, mult_json, raw, granted, cap_reason, state, board_day, created_at FROM city_yield_event WHERE holder_member_id = ?";
        if (cursor != null && cursor > 0) { sql += " AND id < ?"; args.add(cursor); }
        sql += " ORDER BY id DESC LIMIT ?";
        args.add(n);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.get("id"));
            e.put("visitorMasked", mask(((Number) r.get("visitor_member_id")).longValue()));
            e.put("propertyId", r.get("territory_id"));
            e.put("base", r.get("base"));
            e.put("multipliers", r.get("mult_json") == null ? Map.of() : JSON.parseObject(String.valueOf(r.get("mult_json"))));
            e.put("raw", r.get("raw"));
            e.put("granted", r.get("granted"));
            e.put("capReason", r.get("cap_reason"));
            e.put("state", r.get("state"));
            e.put("day", String.valueOf(r.get("board_day")));
            events.add(e);
        }
        Date today = Date.valueOf(day(c, now));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        out.put("todayPending", jdbc.queryForObject("SELECT COALESCE(SUM(granted),0) FROM city_yield_event WHERE holder_member_id = ? AND board_day = ? AND state = 'PENDING'", Long.class, memberId, today));
        out.put("todayAvailable", jdbc.queryForObject("SELECT COALESCE(SUM(granted),0) FROM city_yield_event WHERE holder_member_id = ? AND board_day = ? AND state = 'AVAILABLE'", Long.class, memberId, today));
        int charm = attrLevel(memberId, "charm");
        out.put("threshold", CityMonopolyRules.ownerDailyThreshold(c, charm));
        out.put("dailyMax", CityMonopolyRules.ownerDailyMax(c, charm));
        out.put("currency", "POINTS");
        return out;
    }

    /** 积分 summary for the HUD (a read model over ums_member.point; there is no separate wallet). */
    public Map<String, Object> points(Long memberId) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        String seasonId = season(c, now()).seasonId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", "POINTS");
        out.put("balance", balance(memberId));
        out.put("pending", jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM city_points_flow WHERE member_id = ? AND state = 'PENDING'", Long.class, memberId));
        List<Map<String, Object>> s = jdbc.queryForList("SELECT earned, spent, debt FROM city_member_season WHERE member_id = ? AND season_id = ?", memberId, seasonId);
        out.put("seasonId", seasonId);
        out.put("earnedSeason", s.isEmpty() ? 0 : s.get(0).get("earned"));
        out.put("spentSeason", s.isEmpty() ? 0 : s.get(0).get("spent"));
        out.put("debt", s.isEmpty() ? 0 : s.get(0).get("debt"));
        out.put("holdings", n(jdbc.queryForObject("SELECT COUNT(*) FROM roam_territory WHERE owner_member_id = ? AND status = 'ACTIVE' AND rules_kind = 'MONOPOLY'", Integer.class, memberId)));
        out.put("holdingLimit", CityMonopolyRules.holdingLimit(c, attrLevel(memberId, "explore")));
        return out;
    }

    public Map<String, Object> seasonCurrent(Long memberId) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        long now = now();
        CityMonopolyRules.Season s = season(c, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seasonId", s.seasonId());
        out.put("phase", CityMonopolyRules.phase(c, s, zdt(c, now)).name());
        out.put("day", CityMonopolyRules.seasonDay(s, zdt(c, now)));
        out.put("startsAt", s.startsAt().toInstant().toEpochMilli());
        out.put("freezesAt", s.freezesAt().toInstant().toEpochMilli());
        out.put("endsAt", s.endsAt().toInstant().toEpochMilli());
        out.put("countdownMs", Math.max(0, s.freezesAt().toInstant().toEpochMilli() - now));
        List<Map<String, Object>> solo = soloScores(c, s.seasonId());
        int rank = 0;
        for (int i = 0; i < solo.size(); i++) if (((Number) solo.get(i).get("subjectId")).longValue() == memberId) rank = i + 1;
        out.put("myScore", rank == 0 ? 0 : solo.get(rank - 1).get("score"));
        out.put("myRank", rank == 0 ? null : rank);
        out.put("participants", solo.size());
        return out;
    }

    List<Map<String, Object>> soloScores(CityMonopolyConfig c, String seasonId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT member_id, confirmed_rent, first_claims, takeovers, defenses, holding_score, score_cleared "
                + "FROM city_member_season WHERE season_id = ?", seasonId)) {
            double score = ((Number) r.get("score_cleared")).intValue() == 1 ? 0 : c.num("city.monopoly.season.score.per_first_claim") * ((Number) r.get("first_claims")).intValue()
                    + ((Number) r.get("confirmed_rent")).longValue()
                    + c.num("city.monopoly.season.score.per_takeover") * ((Number) r.get("takeovers")).intValue()
                    + c.num("city.monopoly.season.score.per_defense") * ((Number) r.get("defenses")).intValue()
                    + ((Number) r.get("holding_score")).doubleValue();
            if (score <= 0 && ((Number) r.get("first_claims")).intValue() == 0) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("subjectId", ((Number) r.get("member_id")).longValue());
            e.put("score", Math.round(score * 100) / 100.0);
            e.put("claims", ((Number) r.get("first_claims")).intValue());
            out.add(e);
        }
        out.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        return out;
    }

    List<Map<String, Object>> groupScores(CityMonopolyConfig c, String seasonId, String scope, List<Map<String, Object>> solo) {
        Map<Long, Double> byMember = new HashMap<>();
        for (Map<String, Object> s : solo) byMember.put(((Number) s.get("subjectId")).longValue(), ((Number) s.get("score")).doubleValue());
        Map<String, List<Double>> groups = new HashMap<>();
        Map<String, Integer> districts = new HashMap<>();
        if ("team".equals(scope)) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT DISTINCT team_id, member_id FROM city_game_participation WHERE team_id IS NOT NULL")) {
                Double v = byMember.get(((Number) r.get("member_id")).longValue());
                if (v != null) groups.computeIfAbsent(String.valueOf(r.get("team_id")), k -> new ArrayList<>()).add(v);
            }
        } else {
            for (Map<String, Object> r : jdbc.queryForList("SELECT club_id, member_id FROM club_member WHERE status = 1 AND del_flag = 0")) {
                Double v = byMember.get(((Number) r.get("member_id")).longValue());
                if (v != null) groups.computeIfAbsent(String.valueOf(r.get("club_id")), k -> new ArrayList<>()).add(v);
            }
            for (Map<String, Object> r : jdbc.queryForList("SELECT cm.club_id, COUNT(DISTINCT CONCAT(t.board_id, ':', t.district_id)) d FROM roam_territory t "
                    + "JOIN club_member cm ON cm.member_id = t.owner_member_id AND cm.status = 1 AND cm.del_flag = 0 "
                    + "WHERE t.status = 'ACTIVE' AND t.rules_kind = 'MONOPOLY' AND t.district_id IS NOT NULL GROUP BY cm.club_id")) {
                districts.put(String.valueOf(r.get("club_id")), ((Number) r.get("d")).intValue());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Double>> g : groups.entrySet()) {
            double score = "team".equals(scope) ? CityMonopolyRules.teamScore(c, g.getValue(), 0)
                    : CityMonopolyRules.clubScore(c, g.getValue(), districts.getOrDefault(g.getKey(), 0));
            out.add(new LinkedHashMap<>(Map.of("subjectId", g.getKey(), "score", Math.round(score * 100) / 100.0, "members", g.getValue().size())));
        }
        out.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        return out;
    }

    public Map<String, Object> leaderboard(Long memberId, String scope, String seasonId) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        String sid = seasonId == null || seasonId.isEmpty() ? season(c, now()).seasonId() : seasonId;
        String sc = scope == null ? "solo" : scope.toLowerCase();
        if (!Set.of("solo", "team", "club").contains(sc)) throw new CityGameException(400, "SCOPE_INVALID", "scope 只能是 solo/team/club");
        List<Map<String, Object>> solo = soloScores(c, sid);
        List<Map<String, Object>> rows = "solo".equals(sc) ? solo : groupScores(c, sid, sc, solo);
        String mine = null;
        if ("solo".equals(sc)) mine = String.valueOf(memberId);
        else if ("club".equals(sc)) {
            List<String> clubs = jdbc.queryForList("SELECT CAST(club_id AS CHAR) FROM club_member WHERE member_id = ? AND status = 1 AND del_flag = 0 LIMIT 1", String.class, memberId);
            mine = clubs.isEmpty() ? null : clubs.get(0);
        } else {
            List<String> teams = jdbc.queryForList("SELECT team_id FROM city_game_participation WHERE member_id = ? AND team_id IS NOT NULL ORDER BY participation_id DESC LIMIT 1", String.class, memberId);
            mine = teams.isEmpty() ? null : teams.get(0);
        }
        int myIndex = -1;
        for (int i = 0; i < rows.size(); i++) if (String.valueOf(rows.get(i).get("subjectId")).equals(mine)) myIndex = i;
        List<Map<String, Object>> window = new ArrayList<>();
        for (int i : CityMonopolyRules.leaderboardWindow(c, rows.size(), myIndex)) {
            Map<String, Object> e = new LinkedHashMap<>(rows.get(i));
            e.put("rank", i + 1);
            e.put("isMe", i == myIndex);
            if ("solo".equals(sc)) e.put("subjectId", mask(((Number) rows.get(i).get("subjectId")).longValue()));
            window.add(e);
        }
        return Map.of("seasonId", sid, "scope", sc, "entries", window, "total", rows.size(), "myRank", myIndex < 0 ? 0 : myIndex + 1);
    }

    /**
     * Season settlement after freeze: persists solo/team/club scores and ranks, grants XP/titles once, and returns all
     * MONOPOLY holdings of that season to AVAILABLE. Points are NOT burned (one-currency ruling; see delivery doc).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settleSeason(String seasonId, String city) {
        CityMonopolyConfig c = configs.global();
        long now = now();
        CityMonopolyRules.Season current = season(c, now);
        if (seasonId == null || (seasonId.equals(current.seasonId()) && now < current.freezesAt().toInstant().toEpochMilli())) {
            throw new CityGameException(409, "SEASON_NOT_FROZEN", "赛季尚未冻结");
        }
        List<Map<String, Object>> solo = soloScores(c, seasonId);
        int rewarded = 0;
        for (int i = 0; i < solo.size(); i++) {
            Map<String, Object> s = solo.get(i);
            long member = ((Number) s.get("subjectId")).longValue();
            jdbc.update("INSERT INTO city_season_score(season_id, scope, subject_id, score, rank_no, detail_json, rewarded_at) VALUES (?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE score = VALUES(score), rank_no = VALUES(rank_no)",
                    seasonId, "SOLO", String.valueOf(member), s.get("score"), i + 1, JSON.toJSONString(s), null);
            List<Map<String, Object>> st = jdbc.queryForList("SELECT rewarded_at FROM city_season_score WHERE season_id = ? AND scope = 'SOLO' AND subject_id = ? FOR UPDATE",
                    seasonId, String.valueOf(member));
            if (!st.isEmpty() && st.get(0).get("rewarded_at") == null) {
                Map<String, Object> reward = CityMonopolyRules.soloReward(c, i + 1, solo.size(), ((Number) s.get("claims")).intValue());
                if (reward != null) {
                    xp(member, ((Number) reward.getOrDefault("xp", 0)).intValue(), "城市大富翁赛季奖励");
                    if (reward.get("title") != null) {
                        String title = String.valueOf(reward.get("title")).replace("{city}", city == null ? "城市" : city)
                                .replace("{n}", seasonId.replaceAll("\\D", ""));
                        jdbc.update("INSERT IGNORE INTO member_title(member_id, title_code, title_name, category, source_id) VALUES (?,?,?,'SEASON',?)",
                                member, "SEASON_LORD_" + seasonId, title.length() > 64 ? title.substring(0, 64) : title, seasonId);
                    }
                }
                jdbc.update("UPDATE city_season_score SET rewarded_at = ? WHERE season_id = ? AND scope = 'SOLO' AND subject_id = ?", new Timestamp(now), seasonId, String.valueOf(member));
                rewarded++;
            }
        }
        for (String scope : List.of("team", "club")) {
            List<Map<String, Object>> g = groupScores(c, seasonId, scope, solo);
            for (int i = 0; i < g.size(); i++) {
                jdbc.update("INSERT INTO city_season_score(season_id, scope, subject_id, score, rank_no, detail_json) VALUES (?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE score = VALUES(score), rank_no = VALUES(rank_no)",
                        seasonId, scope.toUpperCase(), String.valueOf(g.get(i).get("subjectId")), g.get(i).get("score"), i + 1, JSON.toJSONString(g.get(i)));
            }
        }
        int reset = jdbc.update("UPDATE roam_territory SET status = 'RELEASED', released_at = ?, release_reason = 'SEASON_RESET', version = version + 1 "
                + "WHERE rules_kind = 'MONOPOLY' AND status = 'ACTIVE' AND season_id = ?", new Timestamp(now), seasonId);
        jdbc.update("UPDATE city_season SET status = 'SETTLED', settled_at = ? WHERE season_id = ?", new Timestamp(now), seasonId);
        return Map.of("seasonId", seasonId, "participants", solo.size(), "rewarded", rewarded, "propertiesReset", reset);
    }

    // =================================================================== utils

    private static int n(Integer v) { return v == null ? 0 : v; }

    static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    static Long num(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) { try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return null; } }
        return null;
    }

    static Double dbl(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        return null;
    }
}
