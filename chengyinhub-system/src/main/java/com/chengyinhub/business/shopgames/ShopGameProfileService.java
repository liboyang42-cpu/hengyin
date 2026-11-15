package com.chengyinhub.business.shopgames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.merchantplans.MerchantPlan;
import com.chengyinhub.business.merchantplans.MerchantPlanService;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * S5 shop games: each shop's game profile.
 *
 * <p>Resolution, in order: an operator's list (curated in the admin console) wins; otherwise the merchant's choice,
 * kept to games of the shop's pool and to the plan allowance ({@link ShopGameCatalog#allowance}, Free = one);
 * otherwise the pool's default game. Games an operator switched off are dropped everywhere. Each game is played
 * through one of the merchant's own node templates; a game with no matching template is listed as not ready.
 *
 * <p>Missing tables read as "no choice, no override, nothing switched off", so every shop keeps a default profile
 * before the migration runs. Nothing here reads plans' prices, sponsorship or paid placement.
 */
@Service
public class ShopGameProfileService {

    public static final String SOURCE_OPERATOR = "OPERATOR";
    public static final String SOURCE_MERCHANT = "MERCHANT";
    public static final String SOURCE_DEFAULT = "DEFAULT";
    public static final int REASON_MAX = 255;
    private static final Logger log = LoggerFactory.getLogger(ShopGameProfileService.class);
    static final ObjectMapper JSON = new ObjectMapper();

    /** One game a shop offers and the merchant template that plays it (null = not set up yet). */
    public record Entry(String key, Long templateId) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("templateId", templateId);
            return m;
        }
    }

    /** The stored row for a shop; every field may be empty. */
    public record Stored(String kindOverride, List<Entry> merchantGames, List<Entry> operatorGames, boolean locked) {
        static Stored empty() { return new Stored(null, List.of(), List.of(), false); }
    }

    /** A resolved profile: the shop kind, the games offered now and where the list came from. */
    public record Resolved(ShopGameCatalog.Kind kind, List<Entry> games, String source, int allowance, boolean locked) { }

    private final JdbcTemplate jdbc;
    private final IMerchantAccessService access;
    private final OpsConsoleAudit audit;
    @Autowired(required = false)
    private MerchantPlanService plans;

    public ShopGameProfileService(JdbcTemplate jdbc, IMerchantAccessService access, OpsConsoleAudit audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** For tests. */
    ShopGameProfileService(JdbcTemplate jdbc, IMerchantAccessService access, OpsConsoleAudit audit, MerchantPlanService plans) {
        this(jdbc, access, audit);
        this.plans = plans;
    }

    // ---------------------------------------------------------------- pure resolution

    /**
     * Resolves a shop's games. Pure: same inputs, same answer. {@code disabled} are the keys operators switched off.
     */
    public static Resolved resolve(ShopGameCatalog.Kind kind, Stored stored, Set<String> disabled, int allowance) {
        Stored s = stored == null ? Stored.empty() : stored;
        Set<String> off = disabled == null ? Set.of() : disabled;
        List<Entry> operator = keep(s.operatorGames(), key -> ShopGameCatalog.game(key) != null && !off.contains(key),
                ShopGameCatalog.POOL_SIZE);
        if (!operator.isEmpty()) return new Resolved(kind, operator, SOURCE_OPERATOR, allowance, s.locked());
        List<Entry> merchant = keep(s.merchantGames(), key -> kind.pool().contains(key) && !off.contains(key), allowance);
        if (!merchant.isEmpty()) return new Resolved(kind, merchant, SOURCE_MERCHANT, allowance, s.locked());
        for (String key : kind.pool()) {
            if (!off.contains(key)) return new Resolved(kind, List.of(new Entry(key, null)), SOURCE_DEFAULT, allowance, s.locked());
        }
        for (String key : ShopGameCatalog.kind(ShopGameCatalog.OTHER).pool()) {
            if (!off.contains(key)) return new Resolved(kind, List.of(new Entry(key, null)), SOURCE_DEFAULT, allowance, s.locked());
        }
        return new Resolved(kind, List.of(), SOURCE_DEFAULT, allowance, s.locked());
    }

    private static List<Entry> keep(List<Entry> in, java.util.function.Predicate<String> ok, int max) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (in == null) return out;
        for (Entry e : in) {
            if (e == null || e.key() == null || !ok.test(e.key()) || !seen.add(e.key())) continue;
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    // ---------------------------------------------------------------- reads

    /** The kind for a merchant: the operator's override, else from its categories (primary first), else OTHER. */
    public ShopGameCatalog.Kind kindOf(long merchantId, Stored stored) {
        if (stored != null && ShopGameCatalog.knownKind(stored.kindOverride())) return ShopGameCatalog.kind(stored.kindOverride());
        return ShopGameCatalog.kindForCategories(categoryNames(merchantId));
    }

    public Resolved resolved(long merchantId) {
        Stored stored = stored(merchantId);
        return resolve(kindOf(merchantId, stored), stored, disabledGames(), allowanceOf(merchantId));
    }

    public int allowanceOf(long merchantId) {
        int extra = 0;
        try {
            Object v = plans == null ? null : plans.feature(merchantId, MerchantPlan.F_EXTRA_NODE_GAMES);
            if (v instanceof Number n) extra = n.intValue();
        } catch (Exception e) {
            log.warn("shop {} plan unreadable; one game (Free)", merchantId);
        }
        return ShopGameCatalog.allowance(extra);
    }

    /** Public profile of a shop (player, City Monopoly). Templates are filled in where the merchant has one. */
    public Map<String, Object> profile(long merchantId) {
        Resolved r = resolved(merchantId);
        List<Entry> games = withTemplates(merchantId, r.games());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", merchantId);
        out.put("available", !games.isEmpty());
        out.put("kind", ShopGameCatalog.kindMap(r.kind()));
        out.put("source", r.source());
        out.put("allowance", r.allowance());
        out.put("games", gameMaps(games));
        out.put("rules", ShopGameCatalog.standardRules());
        return out;
    }

    /** Profile by map place: the shop behind a roam node, or {@code available=false, reason=NOT_A_SHOP}. */
    public Map<String, Object> profileForPoi(long poiId) {
        Long merchantId = merchantOfPoi(poiId);
        if (merchantId == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("poiId", poiId);
            out.put("available", false);
            out.put("reason", "NOT_A_SHOP");
            return out;
        }
        Map<String, Object> out = profile(merchantId);
        out.put("poiId", poiId);
        return out;
    }

    public Long merchantOfPoi(long poiId) {
        try {
            List<Long> ids = jdbc.queryForList("SELECT merchant_id FROM roam_poi WHERE id = ? AND merchant_id IS NOT NULL"
                    + " AND merchant_id > 0", Long.class, poiId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("roam_poi {} unreadable for shop games", poiId);
            return null;
        }
    }

    /** Fills a missing template with the merchant's newest live template that plays that game. */
    public List<Entry> withTemplates(long merchantId, List<Entry> games) {
        Map<String, Long> byGame = null;
        List<Entry> out = new ArrayList<>();
        for (Entry e : games) {
            if (e.templateId() != null) { out.add(e); continue; }
            if (byGame == null) {
                byGame = new HashMap<>();
                for (Map<String, Object> t : templates(merchantId)) {
                    Object key = t.get("game");
                    if (key != null) byGame.putIfAbsent(key.toString(), ((Number) t.get("id")).longValue());
                }
            }
            out.add(new Entry(e.key(), byGame.get(e.key())));
        }
        return out;
    }

    static List<Map<String, Object>> gameMaps(List<Entry> games) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : games) {
            Map<String, Object> m = ShopGameCatalog.game(e.key()).toMap();
            m.put("templateId", e.templateId());
            m.put("ready", e.templateId() != null);
            out.add(m);
        }
        return out;
    }

    /** The merchant's own live node templates, newest first, with the catalog game each one plays (or null). */
    public List<Map<String, Object>> templates(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT t.id, t.title, t.validation_method, t.advanced_config_json"
                    + " FROM cms_member_template t JOIN mms_merchant m ON m.member_id = t.member_id"
                    + " WHERE m.id = ? AND t.del_flag = 0 ORDER BY t.id DESC LIMIT 100", merchantId)) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", ((Number) r.get("id")).longValue());
                t.put("title", r.get("title"));
                Integer vm = r.get("validation_method") instanceof Number n ? n.intValue() : null;
                t.put("game", ShopGameCatalog.detectGame(vm, tree(r.get("advanced_config_json"))));
                out.add(t);
            }
        } catch (Exception e) {
            log.warn("templates of shop {} unreadable for shop games", merchantId);
        }
        return out;
    }

    private static JsonNode tree(Object raw) {
        if (raw == null) return null;
        try { return JSON.readTree(raw.toString()); } catch (Exception e) { return null; }
    }

    public List<String> categoryNames(long merchantId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT category_id, category_ids FROM mms_merchant WHERE id = ?", merchantId);
            if (rows.isEmpty()) return List.of();
            List<Long> ids = new ArrayList<>();
            Object primary = rows.get(0).get("category_id");
            if (primary instanceof Number n && n.longValue() > 0) ids.add(n.longValue());
            Object many = rows.get(0).get("category_ids");
            if (many != null) {
                for (String part : many.toString().split(",")) {
                    try {
                        long id = Long.parseLong(part.trim());
                        if (id > 0 && !ids.contains(id)) ids.add(id);
                    } catch (NumberFormatException ignore) { /* skip junk */ }
                }
            }
            List<String> names = new ArrayList<>();
            for (Long id : ids) {
                names.addAll(jdbc.queryForList("SELECT category_name FROM sys_category WHERE id = ? AND del_flag = 0",
                        String.class, id));
            }
            return names;
        } catch (Exception e) {
            log.warn("categories of shop {} unreadable; default kind", merchantId);
            return List.of();
        }
    }

    public Stored stored(long merchantId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT kind_override, games_json, operator_games_json, locked"
                    + " FROM cy_shop_game_profile WHERE merchant_id = ?", merchantId);
            if (rows.isEmpty()) return Stored.empty();
            Map<String, Object> r = rows.get(0);
            Object kind = r.get("kind_override");
            boolean locked = r.get("locked") instanceof Number n ? n.intValue() == 1 : Boolean.TRUE.equals(r.get("locked"));
            return new Stored(kind == null ? null : kind.toString(), entries(r.get("games_json")),
                    entries(r.get("operator_games_json")), locked);
        } catch (Exception e) {
            log.warn("shop game profile {} unreadable; default profile", merchantId);
            return Stored.empty();
        }
    }

    public Set<String> disabledGames() {
        try {
            return new LinkedHashSet<>(jdbc.queryForList("SELECT game_key FROM cy_shop_game_curation WHERE enabled = 0", String.class));
        } catch (Exception e) {
            log.warn("shop game curation unreadable; nothing switched off");
            return Set.of();
        }
    }

    static List<Entry> entries(Object raw) {
        if (raw == null || raw.toString().isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = JSON.readValue(raw.toString(), new TypeReference<List<Map<String, Object>>>() {});
            List<Entry> out = new ArrayList<>();
            for (Map<String, Object> m : list) {
                Object key = m.get("key");
                Object tpl = m.get("templateId");
                if (key == null) continue;
                out.add(new Entry(key.toString(), tpl instanceof Number n && n.longValue() > 0 ? n.longValue() : null));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    static String json(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : entries) list.add(e.toMap());
        try { return JSON.writeValueAsString(list); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- merchant (app, mini-program)

    /** The merchant's page: kind, pool, allowance, current games, own templates and whether they can change it. */
    public Map<String, Object> merchantView(Long principal) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.BASIC_READ);
        long merchantId = ctx.getMerchantId();
        Stored stored = stored(merchantId);
        Resolved r = resolve(kindOf(merchantId, stored), stored, disabledGames(), allowanceOf(merchantId));
        Map<String, Object> out = profile(merchantId);
        Set<String> off = disabledGames();
        List<Map<String, Object>> pool = new ArrayList<>();
        for (String key : r.kind().pool()) {
            Map<String, Object> g = ShopGameCatalog.game(key).toMap();
            g.put("enabled", !off.contains(key));
            pool.add(g);
        }
        out.put("pool", pool);
        out.put("templates", templates(merchantId));
        out.put("locked", r.locked() || SOURCE_OPERATOR.equals(r.source()));
        out.put("editable", !r.locked() && !SOURCE_OPERATOR.equals(r.source()));
        return out;
    }

    /**
     * The merchant picks games from the pool: {@code {"games":[{"key":"qaPick","templateId":12}]}}. At most the
     * allowance, each from the pool and switched on, no repeats, each template the shop's own and playing that game.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> merchantSave(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROJECT_MANAGE);
        long merchantId = ctx.getMerchantId();
        Stored before = stored(merchantId);
        if (before.locked() || !before.operatorGames().isEmpty()) {
            throw new MerchantGapException(409, "SHOP_GAMES_LOCKED", "平台已为这家店设好了小游戏,暂时不能修改");
        }
        ShopGameCatalog.Kind kind = kindOf(merchantId, before);
        int allowance = allowanceOf(merchantId);
        List<Entry> wanted = parseGames(body == null ? null : body.get("games"));
        if (wanted.isEmpty()) throw new MerchantGapException(400, "SHOP_GAMES_EMPTY", "请至少选一个小游戏");
        if (wanted.size() > allowance) {
            throw new MerchantGapException(400, "SHOP_GAMES_OVER_ALLOWANCE", "当前套餐最多可选 " + allowance + " 个小游戏");
        }
        Set<String> off = disabledGames();
        Map<Long, String> mine = new HashMap<>();
        for (Map<String, Object> t : templates(merchantId)) mine.put((Long) t.get("id"), (String) t.get("game"));
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : wanted) {
            if (!kind.pool().contains(e.key()) || off.contains(e.key())) {
                throw new MerchantGapException(400, "SHOP_GAME_NOT_IN_POOL", "这个小游戏不适合你的店铺类型");
            }
            if (!seen.add(e.key())) throw new MerchantGapException(400, "SHOP_GAME_REPEATED", "同一个小游戏只能选一次");
            if (e.templateId() != null && !e.key().equals(mine.get(e.templateId()))) {
                throw new MerchantGapException(400, "SHOP_GAME_TEMPLATE_MISMATCH", "所选内容和这个小游戏的玩法不一致");
            }
        }
        upsert(merchantId, before.kindOverride(), wanted, before.operatorGames(), before.locked(), "merchant:" + principal);
        writeAudit(merchantId, "merchant:" + principal, "MERCHANT_CHOICE", before, stored(merchantId), null);
        return merchantView(principal);
    }

    static List<Entry> parseGames(Object raw) {
        if (!(raw instanceof List<?> list)) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        if (list.size() > ShopGameCatalog.POOL_SIZE) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        List<Entry> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("key") instanceof String key) || ShopGameCatalog.game(key) == null) {
                throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            }
            Object tpl = m.get("templateId");
            Long templateId = null;
            if (tpl != null) {
                if (!(tpl instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() <= 0) {
                    throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
                }
                templateId = n.longValue();
            }
            out.add(new Entry(key, templateId));
        }
        return out;
    }

    // ---------------------------------------------------------------- operators (admin console)

    public Map<String, Object> catalogView() {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> off = disabledGames();
        List<Map<String, Object>> games = new ArrayList<>();
        for (ShopGameCatalog.Game g : ShopGameCatalog.games().values()) {
            Map<String, Object> m = g.toMap();
            m.put("enabled", !off.contains(g.key()));
            games.add(m);
        }
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (ShopGameCatalog.Kind k : ShopGameCatalog.kinds().values()) kinds.add(ShopGameCatalog.kindMap(k));
        out.put("games", games);
        out.put("kinds", kinds);
        out.put("rules", ShopGameCatalog.standardRules());
        out.put("fairnessProblems", ShopGameCatalog.fairnessProblems());
        return out;
    }

    /** Shops with a stored row (choice or override), newest change first; plus a single shop's full view. */
    public List<Map<String, Object>> adminList(Long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Long> ids;
        try {
            ids = merchantId != null && merchantId > 0 ? List.of(merchantId)
                    : jdbc.queryForList("SELECT merchant_id FROM cy_shop_game_profile ORDER BY update_time DESC LIMIT 200", Long.class);
        } catch (Exception e) {
            log.warn("shop game profiles unreadable");
            ids = merchantId != null && merchantId > 0 ? List.of(merchantId) : List.of();
        }
        for (Long id : ids) {
            Map<String, Object> m = profile(id);
            Stored s = stored(id);
            m.put("kindOverride", s.kindOverride());
            m.put("merchantGames", gameKeys(s.merchantGames()));
            m.put("operatorGames", gameKeys(s.operatorGames()));
            m.put("locked", s.locked());
            m.put("merchantName", merchantName(id));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> gameKeys(List<Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.toMap());
        return out;
    }

    private String merchantName(long id) {
        try {
            List<String> n = jdbc.queryForList("SELECT name FROM mms_merchant WHERE id = ?", String.class, id);
            return n.isEmpty() ? null : n.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Operator override for one shop: {@code kindOverride} (a kind code or empty to clear), {@code games} (any
     * switched-on catalog games, up to the pool size; empty clears the override) and {@code locked}. Reason required;
     * audited.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminOverride(Long merchantId, Map<String, Object> body, Long sysUserId, String operator, String reason) {
        String why = reason(reason, operator);
        if (merchantId == null || merchantId <= 0) throw new IllegalArgumentException("merchantId is required");
        if (merchantName(merchantId) == null) throw new IllegalArgumentException("shop not found");
        Stored before = stored(merchantId);
        String kind = before.kindOverride();
        if (body.containsKey("kindOverride")) {
            Object k = body.get("kindOverride");
            String code = k == null ? "" : k.toString().trim();
            if (!code.isEmpty() && !ShopGameCatalog.knownKind(code)) throw new IllegalArgumentException("unknown shop kind");
            kind = code.isEmpty() ? null : ShopGameCatalog.kind(code).code();
        }
        List<Entry> operatorGames = before.operatorGames();
        if (body.containsKey("games")) {
            Object g = body.get("games");
            operatorGames = g == null ? List.of() : parseOperatorGames(g);
            Set<String> off = disabledGames();
            for (Entry e : operatorGames) if (off.contains(e.key())) throw new IllegalArgumentException(e.key() + " is switched off");
        }
        boolean locked = before.locked();
        if (body.containsKey("locked")) {
            if (!(body.get("locked") instanceof Boolean b)) throw new IllegalArgumentException("locked must be true or false");
            locked = b;
        }
        upsert(merchantId, kind, before.merchantGames(), operatorGames, locked, operator);
        Stored after = stored(merchantId);
        writeAudit(merchantId, operator, "OPERATOR_OVERRIDE", before, after, why);
        if (audit != null) audit.record(sysUserId, "SHOP_GAMES_OVERRIDE", "SHOP_GAME_PROFILE", merchantId, before, after, why);
        return profile(merchantId);
    }

    static List<Entry> parseOperatorGames(Object raw) {
        try {
            List<Entry> out = parseGames(raw);
            Set<String> seen = new LinkedHashSet<>();
            for (Entry e : out) if (!seen.add(e.key())) throw new IllegalArgumentException("a game is repeated");
            return out;
        } catch (MerchantGapException e) {
            throw new IllegalArgumentException("games must be up to " + ShopGameCatalog.POOL_SIZE + " known games");
        }
    }

    /** Switch one catalog game on or off for every shop. Reason required; audited. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminCurate(String gameKey, Object enabledRaw, Long sysUserId, String operator, String reason) {
        String why = reason(reason, operator);
        if (ShopGameCatalog.game(gameKey) == null) throw new IllegalArgumentException("unknown game");
        if (!(enabledRaw instanceof Boolean enabled)) throw new IllegalArgumentException("enabled must be true or false");
        boolean before = !disabledGames().contains(gameKey);
        int n = jdbc.update("UPDATE cy_shop_game_curation SET enabled = ?, reason = ?, update_by = ?, update_time = CURRENT_TIMESTAMP"
                + " WHERE game_key = ?", enabled ? 1 : 0, why, operator, gameKey);
        if (n == 0) {
            jdbc.update("INSERT INTO cy_shop_game_curation (game_key, enabled, reason, update_by, update_time)"
                    + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)", gameKey, enabled ? 1 : 0, why, operator);
        }
        if (audit != null) audit.record(sysUserId, "SHOP_GAMES_CURATE", "SHOP_GAME", 0L,
                Map.of("key", gameKey, "enabled", before), Map.of("key", gameKey, "enabled", enabled), why);
        return catalogView();
    }

    private void upsert(long merchantId, String kind, List<Entry> merchant, List<Entry> operator, boolean locked, String by) {
        int n = jdbc.update("UPDATE cy_shop_game_profile SET kind_override = ?, games_json = ?, operator_games_json = ?, locked = ?,"
                + " update_by = ?, update_time = CURRENT_TIMESTAMP WHERE merchant_id = ?",
                kind, json(merchant), json(operator), locked ? 1 : 0, by, merchantId);
        if (n == 0) {
            jdbc.update("INSERT INTO cy_shop_game_profile (merchant_id, kind_override, games_json, operator_games_json, locked,"
                    + " update_by, update_time) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    merchantId, kind, json(merchant), json(operator), locked ? 1 : 0, by);
        }
    }

    private void writeAudit(long merchantId, String actor, String action, Stored before, Stored after, String reason) {
        jdbc.update("INSERT INTO cy_shop_game_audit (merchant_id, actor, action, before_json, after_json, reason, create_time)"
                + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)", merchantId, actor, action, storedJson(before),
                storedJson(after), reason);
    }

    private static String storedJson(Stored s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kindOverride", s.kindOverride());
        m.put("merchantGames", gameKeys(s.merchantGames()));
        m.put("operatorGames", gameKeys(s.operatorGames()));
        m.put("locked", s.locked());
        try { return JSON.writeValueAsString(m); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String reason(String reason, String operator) {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new IllegalArgumentException("reason is required");
        return why.length() > REASON_MAX ? why.substring(0, REASON_MAX) : why;
    }
}
