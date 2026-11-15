package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Attributes v2 rules (CITY-MONOPOLY-SPEC §3) against the approved defaults. */
class AttributeRulesTest {
    private static final CityMonopolyConfig C = CityMonopolyConfig.defaults();

    @Test
    void sixPermanentAttributes() {
        assertEquals(List.of("explore", "stamina", "insight", "charm", "luck", "lore"), AttributeRules.KEYS);
        assertEquals(AttributeRules.KEYS, C.strings("attributes.v2.keys"));
        for (AttributeRules.Source s : AttributeRules.Source.values()) assertTrue(AttributeRules.KEYS.contains(s.attr), s.name());
    }

    @Test
    void curveMatchesTheSpecTableForEveryLevel() {
        long[] need = {0, 10, 30, 45, 70, 90, 115, 135, 160, 185, 215, 240, 270, 295, 325, 355, 385, 415, 445, 475, 510, 540, 570, 605, 635,
                670, 755, 855, 960, 1080, 1210, 1355, 1515, 1695, 1890, 2105, 2345, 2610, 2900, 3220, 3570, 3960, 4385, 4855, 5375, 5940, 6565, 7250, 8000, 8825};
        long[] cum = {0, 0, 10, 40, 85, 155, 245, 360, 495, 655, 840, 1055, 1295, 1565, 1860, 2185, 2540, 2925, 3340, 3785, 4260, 4770, 5310,
                5880, 6485, 7120, 7790, 8545, 9400, 10360, 11440, 12650, 14005, 15520, 17215, 19105, 21210, 23555, 26165, 29065, 32285, 35855,
                39815, 44200, 49055, 54430, 60370, 66935, 74185, 82185, 91010};
        for (int l = 1; l <= 49; l++) assertEquals(need[l], AttributeRules.need(C, l), "need(" + l + ")");
        assertEquals(0, AttributeRules.need(C, 50), "Lv50 is max");
        for (int l = 1; l <= 50; l++) assertEquals(cum[l], AttributeRules.cumulative(C, l), "cum(" + l + ")");
    }

    @Test
    void curveFormulaIsTwelveLToThe125TimesTailAfter25() {
        for (int l = 1; l < 50; l++) {
            double v = 12 * Math.pow(l, 1.25) * Math.pow(1.075, Math.max(0, l - 25));
            assertEquals(Math.round(v / 5) * 5, AttributeRules.need(C, l));
        }
    }

    @Test
    void levelForAxpBoundaries() {
        assertEquals(1, AttributeRules.levelFor(C, 0));
        assertEquals(1, AttributeRules.levelFor(C, 9));
        assertEquals(2, AttributeRules.levelFor(C, 10));
        assertEquals(15, AttributeRules.levelFor(C, 2185));
        assertEquals(14, AttributeRules.levelFor(C, 2184));
        assertEquals(50, AttributeRules.levelFor(C, 91010));
        assertEquals(50, AttributeRules.levelFor(C, 10_000_000));
    }

    @Test
    void dailySoftCapQuarterRateAndHardCeiling() {
        assertEquals(200, AttributeRules.dailyEffective(C, "explore", 200));
        assertEquals(212.5, AttributeRules.dailyEffective(C, "explore", 250));
        assertEquals(250, AttributeRules.dailyEffective(C, "explore", 10_000), "hard ceiling 1.25 S");
        assertEquals(50, AttributeRules.dailyEffective(C, "luck", 1000));
        assertEquals(150, AttributeRules.softCap(C, "lore"));
        assertEquals(120, AttributeRules.softCap(C, "charm"));
        assertEquals(10, AttributeRules.effectiveGrant(C, "charm", 0, 10));
        assertEquals(5, AttributeRules.effectiveGrant(C, "charm", 120, 20), "over the soft cap only 25% counts");
        assertEquals(0, AttributeRules.effectiveGrant(C, "charm", 240, 20), "beyond 2 S nothing counts");
        long total = 0, raw = 0;
        for (int i = 0; i < 300; i++) { total += AttributeRules.effectiveGrant(C, "stamina", raw, 3); raw += 3; }
        assertEquals(187, total, "1.25 × 150 = 187.5 → 187 effective");
    }

    @Test
    void axpSourcesAndAmounts() {
        assertEquals(1, AttributeRules.rawAxp(C, AttributeRules.Source.NEW_TILE, 1, null, 0));
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.NEW_POI, 1, null, 0));
        assertEquals(30, AttributeRules.rawAxp(C, AttributeRules.Source.NEW_DISTRICT, 1, null, 0));
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.ROAM_2KM, 1, null, 0));
        assertEquals(35, AttributeRules.rawAxp(C, AttributeRules.Source.ROAM_KM, 3.5, null, 0), "10 per km");
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.SESSION_30MIN, 1, null, 0));
        assertEquals(20, AttributeRules.rawAxp(C, AttributeRules.Source.STEPS_1000, 25, null, 0), "steps max 20/day");
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.STEPS_1000, 25, null, 15));
        assertEquals(3, AttributeRules.rawAxp(C, AttributeRules.Source.QUIZ_FIRST_TRY, 1, 0.4, 0), "5 × 0.5 → 3 (half up)");
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.QUIZ_FIRST_TRY, 1, 0.7, 0));
        assertEquals(6, AttributeRules.rawAxp(C, AttributeRules.Source.QUIZ_FIRST_TRY, 1, 0.9, 0));
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.PUZZLE_NO_HINT, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.CHECK_DC13_SUCCESS, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.PHOTO_APPROVED, 1, null, 0));
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.CHALLENGE_TASK_PERFECT, 1, null, 0));
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.GROUP_PLAY_2PLUS, 1, null, 0));
        assertEquals(20, AttributeRules.rawAxp(C, AttributeRules.Source.CLUB_EVENT, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.STAMP_EXCHANGE, 1, null, 0));
        assertEquals(2, AttributeRules.rawAxp(C, AttributeRules.Source.PHOTO_LIKE, 5, null, 8), "likes max 10/day");
        assertEquals(30, AttributeRules.rawAxp(C, AttributeRules.Source.INVITE_REACHES_LV3, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.NAT20, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.DROP, 1, null, 0));
        assertEquals(3, AttributeRules.rawAxp(C, AttributeRules.Source.CHANCE_TASK, 1, null, 0));
        assertEquals(2, AttributeRules.rawAxp(C, AttributeRules.Source.ACTIVE_DAY, 1, null, 0));
        assertEquals(20, AttributeRules.rawAxp(C, AttributeRules.Source.DISTRICT_3_CHECKINS, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.NEW_CATEGORY_30D, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.CITY_QUIZ, 1, null, 0));
        assertEquals(10, AttributeRules.rawAxp(C, AttributeRules.Source.FIRST_CLAIM_IN_SEASON, 1, null, 0));
        assertEquals(3, AttributeRules.rawAxp(C, AttributeRules.Source.VISITOR_YIELD, 1, null, 0));
        assertEquals(2, AttributeRules.rawAxp(C, AttributeRules.Source.OWNER_MAINTAIN, 1, null, 0));
        assertEquals(5, AttributeRules.rawAxp(C, AttributeRules.Source.UPGRADE, 1, null, 0));
    }

    @Test
    void luckEffectsCapAtLevel30() {
        assertEquals(30, AttributeRules.effectiveLevel(C, "luck", 42));
        assertEquals(42, AttributeRules.effectiveLevel(C, "explore", 42));
    }

    @Test
    void titlesAtTenTwentyFiveFortyFiftyAndLuckTenTwentyThirty() {
        assertEquals(List.of(), AttributeRules.titlesUpTo(C, "explore", 9));
        List<AttributeRules.Title> explore = AttributeRules.titlesUpTo(C, "explore", 50);
        assertEquals(List.of("迷雾行者", "街巷猎人", "城市制图师", "雾之尽头"), explore.stream().map(AttributeRules.Title::name).toList());
        assertEquals("ATTR_EXPLORE_25", explore.get(1).code());
        assertEquals(List.of("小确幸", "锦鲤", "天选"), AttributeRules.titlesUpTo(C, "luck", 50).stream().map(AttributeRules.Title::name).toList());
        assertEquals("ATTR_LUCK_20", AttributeRules.titlesUpTo(C, "luck", 20).get(1).code());
        assertEquals("城市解谜王", AttributeRules.titlesUpTo(C, "insight", 50).get(3).name());
        assertEquals("局长", AttributeRules.titlesUpTo(C, "charm", 25).get(1).name());
        assertEquals("活地图", AttributeRules.titlesUpTo(C, "lore", 50).get(3).name());
        assertEquals("铁脚板", AttributeRules.titlesUpTo(C, "stamina", 40).get(2).name());
    }

    private static Map<String, Integer> lv(int explore, int stamina, int insight, int charm, int luck, int lore) {
        Map<String, Integer> m = new HashMap<>();
        m.put("explore", explore); m.put("stamina", stamina); m.put("insight", insight);
        m.put("charm", charm); m.put("luck", luck); m.put("lore", lore);
        return m;
    }

    @Test
    void archetypesFromTopTwoAndPolymath() {
        assertEquals("远征漫游者", AttributeRules.archetype(C, lv(30, 20, 5, 5, 5, 5)).name());
        assertEquals("ROVER", AttributeRules.archetype(C, lv(30, 20, 5, 5, 5, 5)).code());
        assertEquals("漫游者", AttributeRules.archetype(C, lv(30, 5, 20, 5, 5, 5)).name());
        assertEquals("街巷行脚客", AttributeRules.archetype(C, lv(5, 30, 5, 5, 5, 20)).name());
        assertEquals("老城侦探", AttributeRules.archetype(C, lv(5, 5, 30, 5, 5, 20)).name());
        assertEquals("探店召集人", AttributeRules.archetype(C, lv(20, 5, 5, 30, 5, 5)).name());
        assertEquals("锦鲤", AttributeRules.archetype(C, lv(5, 5, 5, 5, 30, 5)).name());
        assertEquals("街坊掌柜", AttributeRules.archetype(C, lv(5, 5, 5, 20, 5, 30)).name());
        assertEquals("LOCAL", AttributeRules.archetype(C, lv(5, 5, 5, 20, 5, 30)).code());
        assertEquals("博物家", AttributeRules.archetype(C, lv(20, 18, 17, 16, 19, 20)).name());
        assertEquals("POLYMATH", AttributeRules.archetype(C, lv(20, 18, 17, 16, 19, 20)).code());
        assertNotEquals("POLYMATH", AttributeRules.archetype(C, lv(1, 1, 1, 1, 1, 1)).code(), "fresh accounts are not polymaths");
    }

    @Test
    void imprintIsOffByDefaultAndMapsThemePresets() {
        Map<String, Integer> high = lv(40, 40, 40, 40, 40, 40);
        assertFalse(C.flag("attributes.v2.imprint.default_enabled"));
        assertEquals(2, AttributeRules.imprintInitial(C, null, "observe", 2, 5, high), "default OFF → unchanged");
        assertEquals(2, AttributeRules.imprintInitial(C, false, "observe", 2, 5, high));
        assertEquals("insight", AttributeRules.imprintTarget(C, "observe"));
        assertEquals("insight", AttributeRules.imprintTarget(C, "insight"));
        assertEquals("explore", AttributeRules.imprintTarget(C, "nerve"));
        assertEquals("stamina", AttributeRules.imprintTarget(C, "body"));
        assertEquals("stamina", AttributeRules.imprintTarget(C, "agility"));
        assertEquals("charm", AttributeRules.imprintTarget(C, "charm"));
        assertEquals("luck", AttributeRules.imprintTarget(C, "fortune"));
        assertNull(AttributeRules.imprintTarget(C, "custom"), "custom is not mapped");
        assertEquals(4, AttributeRules.imprintInitial(C, true, "observe", 2, 5, high), "Lv40 → +2");
        assertEquals(3, AttributeRules.imprintInitial(C, true, "observe", 2, 5, lv(1, 1, 20, 1, 1, 1)), "Lv20 → +1");
        assertEquals(2, AttributeRules.imprintInitial(C, true, "observe", 2, 5, lv(1, 1, 19, 1, 1, 1)));
        assertEquals(2, AttributeRules.imprintInitial(C, true, "fortune", 1, 3, lv(1, 1, 1, 1, 50, 1)), "luck effective Lv30 → +1");
        assertEquals(3, AttributeRules.imprintInitial(C, true, "fortune", 3, 3, high), "clamped to preset max");
        assertEquals(0, AttributeRules.imprintInitial(C, true, "custom", 0, 5, high));
        CityMonopolyConfig on = CityMonopolyConfig.withOverrides(Map.of("attributes.v2.imprint.default_enabled", "true"));
        assertEquals(8, AttributeRules.imprintInitial(on, null, "nerve", 6, 10, high), "global default can be switched on");
    }

    @Test
    void backfillCapsAtLevel15AndInsightCharmAtLevel10() {
        Map<String, Long> big = AttributeRules.backfill(C, new AttributeRules.BackfillFacts(5000, 500, 900, 1000, 200, 500));
        assertEquals(2185L, big.get("explore"));
        assertEquals(2185L, big.get("stamina"));
        assertEquals(840L, big.get("insight"));
        assertEquals(840L, big.get("charm"));
        assertEquals(0L, big.get("luck"));
        assertEquals(2185L, big.get("lore"));
        Map<String, Long> small = AttributeRules.backfill(C, new AttributeRules.BackfillFacts(40, 3, 2.5, 4, 1, 6));
        assertEquals(70L, small.get("explore"));
        assertEquals(25L, small.get("stamina"));
        assertEquals(20L, small.get("insight"));
        assertEquals(20L, small.get("charm"));
        assertEquals(30L, small.get("lore"));
    }

    @Test
    void nextUnlockHints() {
        assertEquals(15, AttributeRules.nextUnlock("insight", 1));
        assertEquals(35, AttributeRules.nextUnlock("insight", 20));
        assertNull(AttributeRules.nextUnlock("charm", 30));
        assertEquals(10, AttributeRules.nextUnlock("luck", 1));
    }
}
