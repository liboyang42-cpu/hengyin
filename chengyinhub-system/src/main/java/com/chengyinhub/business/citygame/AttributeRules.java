package com.chengyinhub.business.citygame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes v2 (CITY-MONOPOLY-SPEC §3): six permanent attributes, AXP sources with a daily soft cap, the L1–50 curve
 * need(L) = round5(12 × L^1.25 × 1.075^max(0, L−25)), titles, archetypes, backfill and the theme-attribute imprint map.
 * Pure functions; {@link MemberAttributeService} persists.
 */
public final class AttributeRules {
    private AttributeRules() {}

    private static final String P = "attributes.v2.";
    public static final List<String> KEYS = List.of("explore", "stamina", "insight", "charm", "luck", "lore");

    /** Every AXP source event: attribute, config key of its amount. */
    public enum Source {
        NEW_TILE("explore", "sources.explore.new_tile"),
        NEW_POI("explore", "sources.explore.new_poi"),
        NEW_DISTRICT("explore", "sources.explore.new_district"),
        ROAM_2KM("explore", "sources.explore.roam_2km"),
        ROAM_KM("stamina", "sources.stamina.per_km"),
        STEPS_1000("stamina", "sources.stamina.per_1000_steps"),
        SESSION_30MIN("stamina", "sources.stamina.session_30min"),
        QUIZ_FIRST_TRY("insight", "sources.insight.quiz_first_try"),
        PUZZLE_NO_HINT("insight", "sources.insight.puzzle_no_hint"),
        CHECK_DC13_SUCCESS("insight", "sources.insight.check_dc13_success"),
        PHOTO_APPROVED("insight", "sources.insight.photo_approved"),
        CHALLENGE_TASK_PERFECT("insight", "sources.insight.challenge_task_perfect"),
        GROUP_PLAY_2PLUS("charm", "sources.charm.group_play_2plus"),
        CLUB_EVENT("charm", "sources.charm.club_event"),
        STAMP_EXCHANGE("charm", "sources.charm.stamp_exchange"),
        PHOTO_LIKE("charm", "sources.charm.photo_like"),
        INVITE_REACHES_LV3("charm", "sources.charm.invite_reaches_lv3"),
        NAT20("luck", "sources.luck.nat20"),
        DROP("luck", "sources.luck.drop"),
        CHANCE_TASK("luck", "sources.luck.chance_task"),
        ACTIVE_DAY("luck", "sources.luck.active_day"),
        DISTRICT_3_CHECKINS("lore", "sources.lore.district_3_checkins"),
        NEW_CATEGORY_30D("lore", "sources.lore.new_category_30d"),
        CITY_QUIZ("lore", "sources.lore.city_quiz"),
        FIRST_CLAIM_IN_SEASON("lore", "sources.lore.first_claim_in_season"),
        VISITOR_YIELD("lore", "sources.lore.visitor_yield"),
        OWNER_MAINTAIN("lore", "sources.lore.owner_maintain"),
        UPGRADE("lore", "sources.lore.upgrade");

        public final String attr;
        public final String configKey;

        Source(String attr, String configKey) {
            this.attr = attr;
            this.configKey = configKey;
        }
    }

    /**
     * Raw AXP for a source. {@code units} multiplies per-unit sources (km, 1000 steps, likes); quiz uses the recent
     * accuracy factor (<50% ×0.5, 50–80% ×1.0, >80% ×1.2); steps and likes respect their own daily maxima via
     * {@code alreadyToday} (units already granted today for that source).
     */
    public static long rawAxp(CityMonopolyConfig c, Source s, double units, Double recentAccuracy, long alreadyToday) {
        double base = c.num(P + s.configKey);
        double u = Math.max(0, units <= 0 ? 1 : units);
        if (s == Source.STEPS_1000) {
            double max = c.num(P + "sources.stamina.steps_daily_max");
            u = Math.max(0, Math.min(u, max - alreadyToday));
        }
        if (s == Source.PHOTO_LIKE) {
            double max = c.num(P + "sources.charm.photo_like_daily_max");
            u = Math.max(0, Math.min(u, max - alreadyToday));
        }
        double v = base * u;
        if (s == Source.QUIZ_FIRST_TRY && recentAccuracy != null) {
            double f = recentAccuracy < 0.5 ? c.num(P + "sources.insight.accuracy_factor.lt50")
                    : recentAccuracy <= 0.8 ? c.num(P + "sources.insight.accuracy_factor.50to80")
                    : c.num(P + "sources.insight.accuracy_factor.gt80");
            v *= f;
        }
        return Math.max(0, Math.round(v));
    }

    public static double softCap(CityMonopolyConfig c, String attr) {
        return c.num(P + "daily_soft_cap." + attr);
    }

    /** AXP_eff(day) = min(x, S) + 0.25 × min(max(x − S, 0), S); the hard daily ceiling is 1.25 S. */
    public static double dailyEffective(CityMonopolyConfig c, String attr, double rawToday) {
        double s = softCap(c, attr);
        double x = Math.max(0, rawToday);
        return Math.min(x, s) + c.num(P + "over_soft_rate") * Math.min(Math.max(x - s, 0), s);
    }

    /** Effective AXP actually granted for {@code raw} more today, given the raw already recorded today. */
    public static long effectiveGrant(CityMonopolyConfig c, String attr, long rawBefore, long raw) {
        long before = (long) Math.floor(dailyEffective(c, attr, rawBefore));
        long after = (long) Math.floor(dailyEffective(c, attr, rawBefore + Math.max(0, raw)));
        return Math.max(0, after - before);
    }

    /** AXP needed to go from L to L+1. */
    public static long need(CityMonopolyConfig c, int level) {
        if (level < 1 || level >= maxLevel(c)) return 0;
        double v = c.num(P + "curve.a") * Math.pow(level, c.num(P + "curve.p"))
                * Math.pow(c.num(P + "curve.tail"), Math.max(0, level - c.num(P + "curve.knee")));
        double r = c.num(P + "curve.round_to");
        return Math.round(v / r) * Math.round(r);
    }

    /** Cumulative AXP to reach level L (cum(1) = 0). */
    public static long cumulative(CityMonopolyConfig c, int level) {
        long sum = 0;
        for (int k = 1; k < Math.min(level, maxLevel(c)); k++) sum += need(c, k);
        if (level > maxLevel(c)) return cumulative(c, maxLevel(c));
        return sum;
    }

    public static int maxLevel(CityMonopolyConfig c) { return c.integer(P + "curve.max_level"); }

    public static int levelFor(CityMonopolyConfig c, long axp) {
        int level = 1;
        long cum = 0;
        while (level < maxLevel(c)) {
            long n = need(c, level);
            if (axp < cum + n) break;
            cum += n;
            level++;
        }
        return level;
    }

    /** Level that drives effects: luck is capped at Lv30 (anything above only feeds the 锦鲤 display). */
    public static int effectiveLevel(CityMonopolyConfig c, String attr, int level) {
        if ("luck".equals(attr)) return Math.min(level, c.integer(P + "luck_max_effective_level"));
        return level;
    }

    // ------------------------------------------------------------------ titles

    private static final Map<String, String[]> TITLES = Map.of(
            "explore", new String[]{"迷雾行者", "街巷猎人", "城市制图师", "雾之尽头"},
            "stamina", new String[]{"健步者", "城市长跑者", "铁脚板", "不夜行者"},
            "insight", new String[]{"细节控", "侦探", "真相猎手", "城市解谜王"},
            "charm", new String[]{"好搭子", "局长", "人气王", "城市名片"},
            "luck", new String[]{"小确幸", "锦鲤", "天选"},
            "lore", new String[]{"街坊", "老城通", "城市百科", "活地图"});

    public record Title(String code, String attr, int level, String name) {}

    /** Titles unlocked at a level (ATTR_{KEY}_{LV}); luck uses 10/20/30. */
    public static List<Title> titlesUpTo(CityMonopolyConfig c, String attr, int level) {
        List<Double> levels = c.list(P + ("luck".equals(attr) ? "luck_title_levels" : "title_levels"));
        String[] names = TITLES.get(attr);
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < levels.size() && i < names.length; i++) {
            int lv = levels.get(i).intValue();
            if (level >= lv) out.add(new Title("ATTR_" + attr.toUpperCase() + "_" + lv, attr, lv, names[i]));
        }
        return out;
    }

    // ------------------------------------------------------------------ archetype

    private static final Map<String, String[]> ARCHETYPE = Map.of(
            "explore", new String[]{"ROVER", "漫游者"},
            "stamina", new String[]{"WALKER", "行脚客"},
            "insight", new String[]{"SLEUTH", "侦探"},
            "charm", new String[]{"CONVENER", "召集人"},
            "luck", new String[]{"KOI", "锦鲤"},
            "lore", new String[]{"LOCAL", "地头蛇"});

    private static final Map<String, String> MODIFIED = Map.of(
            "explore+stamina", "远征漫游者",
            "stamina+lore", "街巷行脚客",
            "insight+lore", "老城侦探",
            "charm+explore", "探店召集人",
            "lore+charm", "街坊掌柜");

    public record Archetype(String code, String name, String primary, String secondary) {}

    /** Display-only archetype from the top two levels; Polymath when (max − min) / max ≤ 0.2. Ties keep KEYS order. */
    public static Archetype archetype(CityMonopolyConfig c, Map<String, Integer> levels) {
        int max = 0, min = Integer.MAX_VALUE;
        for (String k : KEYS) {
            int v = levels.getOrDefault(k, 1);
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        if (max > 1 && (double) (max - min) / max <= c.num(P + "archetype.polymath_max_spread")) {
            return new Archetype("POLYMATH", "博物家", null, null);
        }
        List<String> order = new ArrayList<>(KEYS);
        order.sort((a, b) -> Integer.compare(levels.getOrDefault(b, 1), levels.getOrDefault(a, 1)));
        String primary = order.get(0);
        String secondary = order.get(1);
        String name = MODIFIED.getOrDefault(primary + "+" + secondary, ARCHETYPE.get(primary)[1]);
        return new Archetype(ARCHETYPE.get(primary)[0], name, primary, secondary);
    }

    // ------------------------------------------------------------------ imprint (mini-program theme attributes)

    /** Theme preset → v2 attribute, or null for unmapped presets (custom). */
    public static String imprintTarget(CityMonopolyConfig c, String preset) {
        if (preset == null) return null;
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : c.str(P + "imprint.map").split(",")) {
            String[] kv = pair.split(":");
            map.put(kv[0].trim(), kv[1].trim());
        }
        return map.get(preset);
    }

    /**
     * Initial value of a theme attribute at run start. Imprint is OFF unless the topic opts in ({@code personaImprint});
     * when on, +1 at v2 Lv20 and +2 at Lv40, clamped to the preset max (fortune ≤ 3, observe ≤ 5 ...).
     */
    public static int imprintInitial(CityMonopolyConfig c, Boolean topicPersonaImprint, String preset, int baseInitial,
                                     int presetMax, Map<String, Integer> levels) {
        boolean enabled = topicPersonaImprint != null ? topicPersonaImprint : c.flag(P + "imprint.default_enabled");
        if (!enabled) return baseInitial;
        String target = imprintTarget(c, preset);
        if (target == null) return baseInitial;
        int lv = effectiveLevel(c, target, levels.getOrDefault(target, 1));
        int bonus = 0;
        for (Map.Entry<String, Double> e : c.levels(P + "imprint.thresholds").entrySet()) {
            if (lv >= Integer.parseInt(e.getKey())) bonus = Math.max(bonus, e.getValue().intValue());
        }
        return Math.min(presetMax, baseInitial + bonus);
    }

    // ------------------------------------------------------------------ backfill

    public record BackfillFacts(long tiles, long pois, double km, long quizNodes, long clubEvents, long categories) {}

    /** Launch backfill AXP per attribute, capped at Lv15 (insight/charm Lv10); luck = 0. */
    public static Map<String, Long> backfill(CityMonopolyConfig c, BackfillFacts f) {
        long cap = cumulative(c, c.integer(P + "backfill.max_level"));
        long insightCap = cumulative(c, c.integer(P + "backfill.insight_max_level"));
        long charmCap = cumulative(c, c.integer(P + "backfill.charm_max_level"));
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("explore", Math.min(cap, f.tiles() * 1 + f.pois() * 10));
        out.put("stamina", Math.min(cap, Math.round(f.km() * 10)));
        out.put("insight", Math.min(insightCap, f.quizNodes() * 5));
        out.put("charm", Math.min(charmCap, f.clubEvents() * 20));
        out.put("luck", 0L);
        out.put("lore", Math.min(cap, f.categories() * 5));
        return Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------ next unlock hints (§3.4)

    private static final Map<String, int[]> UNLOCK_LEVELS = Map.of(
            "explore", new int[]{15, 25, 30, 45},
            "stamina", new int[]{20, 30},
            "insight", new int[]{15, 20, 35},
            "charm", new int[]{30},
            "luck", new int[]{10, 25},
            "lore", new int[]{10, 15, 20, 25, 40, 45});

    public static Integer nextUnlock(String attr, int level) {
        for (int lv : UNLOCK_LEVELS.getOrDefault(attr, new int[0])) if (lv > level) return lv;
        return null;
    }
}
