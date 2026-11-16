package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The approved defaults are the single source of truth: every key is seeded into sys_config by the migration with the
 * same value; admin overrides are validated (fail closed to the default); currency stays 积分.
 */
class CityMonopolyConfigDefaultsTest {
    private static final String MIGRATION = "sql/migration_20261124_city_numbers_attr.sql";

    /** Keys added after the first seed (W7 x-city-monopoly) are seeded by their own migration. */
    private static final String LATER_SEEDS = "sql/migration_20261127_city_monopoly_claim_flow.sql";
    /** W7 x-city-economy: city.monopoly.abuse.clawback_lookback_d (CITY-15). */
    private static final String ECONOMY_SEEDS = "sql/migration_20261128_city_economy.sql";

    private static Path migration() {
        return path(MIGRATION);
    }

    private static Path path(String rel) {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("chengyinhub-admin", rel);
        return p;
    }

    @Test
    void migrationSeedsEveryDefaultKeyWithTheSameValue() throws Exception {
        String sql = Files.readString(migration(), StandardCharsets.UTF_8) + "\n" + Files.readString(path(LATER_SEEDS), StandardCharsets.UTF_8)
                + "\n" + Files.readString(path(ECONOMY_SEEDS), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("SELECT '(?:[^']|'')*', '([^']+)', '((?:[^']|'')*)', 'Y'").matcher(sql);
        Map<String, String> seeded = new LinkedHashMap<>();
        while (m.find()) seeded.put(m.group(1), m.group(2).replace("''", "'"));
        assertEquals(CityMonopolyConfig.defaultValues(), seeded);
        for (String k : seeded.keySet()) {
            assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = '" + k + "')"), "idempotent seed " + k);
            assertTrue(k.length() <= 100);
        }
        for (String v : seeded.values()) assertTrue(v.length() <= 500);
    }

    @Test
    void migrationIsAdditiveAndDocumentsRollback() throws Exception {
        String sql = Files.readString(migration(), StandardCharsets.UTF_8);
        String executable = sql.replaceAll("(?m)^--.*$", "");
        assertFalse(executable.toUpperCase().matches("(?s).*\\bDROP\\s+(TABLE|COLUMN|INDEX)\\b.*"), "no destructive DDL outside the rollback comment");
        assertFalse(executable.toUpperCase().contains("TRUNCATE"));
        assertFalse(executable.matches("(?is).*(ALTER\\s+TABLE|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+`?ums_member(_points_detail)?\\b.*"),
                "points tables untouched");
        assertFalse(executable.toLowerCase().contains("city_coin"), "one currency: no coin tables");
        assertTrue(sql.contains("-- Rollback instructions"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS member_archetype"));
    }

    @Test
    void approvedSpecNumbersAreTheDefaults() {
        CityMonopolyConfig c = CityMonopolyConfig.defaults();
        assertEquals("POINTS", c.str("city.monopoly.currency"), "owner ruling 2026-11-15: one currency 积分");
        assertEquals(40, c.integer("city.monopoly.claim.radius_m"));
        assertEquals(25, c.integer("city.monopoly.claim.max_h_accuracy_m"));
        assertEquals(60, c.integer("city.monopoly.claim.dwell.min_span_s"));
        assertTrue(c.flag("city.monopoly.claim.photo.in_app_camera_only"));
        assertTrue(c.flag("city.monopoly.claim.require_app_attest"));
        assertEquals(24, c.integer("city.monopoly.protection.after_claim_h"));
        assertEquals(4.0, c.num("city.monopoly.yield.multiplier_cap"));
        assertEquals(200, c.integer("city.monopoly.yield.owner_daily.soft_threshold"));
        assertEquals(7, c.integer("city.monopoly.decay.weathered_after_d"));
        assertEquals(21, c.integer("city.monopoly.decay.release_at_d"));
        assertEquals(28, c.integer("city.monopoly.season.length_d"));
        assertEquals(300, c.integer("city.monopoly.season.stipend"), "300 城币 → 300 积分");
        assertEquals(2, c.integer("city.monopoly.yield.visitor_reward"));
        assertEquals(60, c.integer("city.monopoly.protection.shield.cost"));
        assertEquals(1.075, c.num("attributes.v2.curve.tail"));
    }

    @Test
    void overridesAreValidatedAndFailClosed() {
        CityMonopolyConfig c = CityMonopolyConfig.withOverrides(Map.of(
                "city.monopoly.claim.radius_m", "35",
                "city.monopoly.yield.multiplier_cap", "9",
                "city.monopoly.yield.event_multiplier", "5",
                "city.monopoly.currency", "CITY_COIN",
                "city.monopoly.claim.require_app_attest", "maybe",
                "city.monopoly.yield.time_bands", "garbage",
                "city.monopoly.season.rewards.solo", "not json",
                "unknown.key", "1"));
        assertEquals(35, c.integer("city.monopoly.claim.radius_m"), "valid override applies");
        assertEquals(4.0, c.num("city.monopoly.yield.multiplier_cap"), "cap cannot be raised past 4.0");
        assertEquals(1.0, c.num("city.monopoly.yield.event_multiplier"), "event multiplier ≤ 2.0");
        assertEquals("POINTS", c.str("city.monopoly.currency"), "no second currency");
        assertTrue(c.flag("city.monopoly.claim.require_app_attest"));
        assertEquals(5, c.timeBands().size());
        assertFalse(c.asMap().containsKey("unknown.key"));
        Map<String, String> invalid = CityMonopolyConfig.invalidOverrides(Map.of("unknown.key", "1", "city.monopoly.claim.radius_m", "-3",
                "city.monopoly.season.rewards.solo", "not json"));
        assertEquals("UNKNOWN_KEY", invalid.get("unknown.key"));
        assertEquals("INVALID_VALUE", invalid.get("city.monopoly.claim.radius_m"));
        assertEquals("INVALID_VALUE", invalid.get("city.monopoly.season.rewards.solo"));
    }

    @Test
    void rulesHashChangesWithAnyValue() {
        assertEquals(CityMonopolyConfig.defaults().rulesHash(), CityMonopolyConfig.withOverrides(Map.of()).rulesHash());
        assertNotEquals(CityMonopolyConfig.defaults().rulesHash(),
                CityMonopolyConfig.withOverrides(Map.of("city.monopoly.tiers.T1.claim_cost", "61")).rulesHash());
        assertEquals(64, CityMonopolyConfig.defaults().rulesHash().length());
    }

    @Test
    void fixedConfigServiceServesTheGivenConfig() {
        CityMonopolyConfig c = CityMonopolyConfig.withOverrides(Map.of("city.monopoly.enabled", "false"));
        CityMonopolyConfigService s = CityMonopolyConfigService.fixed(c);
        assertSame(c, s.global());
        assertSame(c, s.forBoard(null));
    }
}
