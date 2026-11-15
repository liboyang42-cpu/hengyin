package com.chengyinhub.business.shopgames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantplans.MerchantPlan;
import com.chengyinhub.business.merchantplans.MerchantPlanService;
import com.chengyinhub.business.service.IPointsAwardService;

/** S5 shop games: category defaults, fairness, plan allowance, check-in launch, governed results, City rules untouched. */
class ShopGamesTest {

    // ------------------------------------------------------------------ fake database

    /** Answers by SQL fragment; records every update. */
    static class FakeJdbc extends JdbcTemplate {
        final Map<String, Function<Object[], List<Map<String, Object>>>> rows = new LinkedHashMap<>();
        final Map<String, Function<Object[], List<?>>> scalars = new LinkedHashMap<>();
        final List<String> updates = new ArrayList<>();
        final List<Object[]> updateArgs = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            for (Map.Entry<String, Function<Object[], List<Map<String, Object>>>> e : rows.entrySet()) {
                if (sql.contains(e.getKey())) return e.getValue().apply(args);
            }
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            for (Map.Entry<String, Function<Object[], List<?>>> e : scalars.entrySet()) {
                if (sql.contains(e.getKey())) return (List<T>) e.getValue().apply(args);
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            updateArgs.add(args);
            return 1;
        }
    }

    private static final long SHOP = 7L;
    private static final long OWNER = 70L;
    private static final long POI = 900L;
    private static final long MEMBER = 5L;

    private static MerchantPlanService plan(int extraNodeGames) {
        MerchantPlanService plans = mock(MerchantPlanService.class);
        when(plans.feature(anyLong(), eq(MerchantPlan.F_EXTRA_NODE_GAMES))).thenReturn(extraNodeGames);
        return plans;
    }

    private static IMerchantAccessService access() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext ctx = mock(MerchantAccessContext.class);
        when(ctx.getMerchantId()).thenReturn(SHOP);
        when(ctx.getOwnerMemberId()).thenReturn(OWNER);
        when(access.require(anyLong(), anyString())).thenReturn(ctx);
        return access;
    }

    private static Map<String, Object> template(long id, Integer vm, String advanced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", "t" + id);
        m.put("validation_method", vm);
        m.put("advanced_config_json", advanced);
        return m;
    }

    /** A café (category 咖啡茶饮) at POI 900 with one quiz template (vm 3 = qaPick). */
    private static FakeJdbc cafe(String gamesJson, String operatorJson) {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.scalars.put("FROM roam_poi", a -> List.of(SHOP));
        jdbc.rows.put("FROM mms_merchant WHERE id = ?", a -> List.of(new LinkedHashMap<>(Map.of("category_id", 41))));
        jdbc.scalars.put("FROM sys_category", a -> List.of("咖啡茶饮"));
        jdbc.scalars.put("FROM mms_merchant WHERE id", a -> List.of("Bean Café"));
        jdbc.rows.put("FROM cy_shop_game_profile", a -> {
            if (gamesJson == null && operatorJson == null) return List.of();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind_override", null);
            r.put("games_json", gamesJson);
            r.put("operator_games_json", operatorJson);
            r.put("locked", 0);
            return List.of(r);
        });
        jdbc.rows.put("FROM cms_member_template", a -> List.of(
                template(12L, 3, null),
                template(13L, null, "{\"trueFalse\":{\"enabled\":true}}"),
                template(14L, 2, null)));
        return jdbc;
    }

    // ------------------------------------------------------------------ 1. defaults for every category

    @Test
    void everyMerchantIndustryGetsItsKindAndEveryKindHasADefault() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("咖啡茶饮", "CAFE");
        expected.put("茶馆", "CAFE");
        expected.put("书店", "BOOKSHOP");
        expected.put("手作工坊", "CRAFT");
        expected.put("古着中古", "RETAIL");
        expected.put("文创杂货", "RETAIL");
        expected.put("花店", "FLORIST");
        expected.put("烘焙甜品", "BAKERY");
        expected.put("酒馆小酌", "BAR");
        expected.put("宠物友好", "PET");
        expected.put("健身房", "GYM");
        expected.put("火锅餐厅", "RESTAURANT");
        expected.put("美术馆", "GALLERY");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertEquals(e.getValue(), ShopGameCatalog.kindForCategories(List.of(e.getKey())).code(), e.getKey());
        }
        assertEquals(ShopGameCatalog.OTHER, ShopGameCatalog.kindForCategories(List.of("洗衣店")).code());
        assertEquals(ShopGameCatalog.OTHER, ShopGameCatalog.kindForCategories(List.of()).code());
        assertEquals(ShopGameCatalog.OTHER, ShopGameCatalog.kindForCategories(null).code());
        // The primary category decides when a shop has several.
        assertEquals("BOOKSHOP", ShopGameCatalog.kindForCategories(List.of("书店", "咖啡茶饮")).code());
        for (ShopGameCatalog.Kind k : ShopGameCatalog.kinds().values()) {
            assertNotNull(ShopGameCatalog.game(k.defaultGame()), k.code());
            ShopGameProfileService.Resolved r = ShopGameProfileService.resolve(k, null, Set.of(), 1);
            assertEquals(List.of(k.defaultGame()), r.games().stream().map(ShopGameProfileService.Entry::key).toList(), k.code());
            assertEquals(ShopGameProfileService.SOURCE_DEFAULT, r.source());
        }
    }

    @Test
    void everyPoolIsComparableAndUsesExistingPlayKitGames() {
        assertEquals(List.of(), ShopGameCatalog.fairnessProblems());
        Set<String> matrixKeys = new java.util.HashSet<>();
        com.chengyinhub.business.service.support.GameplaySupportMatrix.view().path("types")
                .forEach(row -> matrixKeys.add(row.path("key").asText()));
        for (String key : ShopGameCatalog.games().keySet()) assertTrue(matrixKeys.contains(key), key + " is an existing game type");
        Map<String, Object> rules = ShopGameCatalog.standardRules();
        assertEquals(3, rules.get("rounds"));
        assertEquals(2, rules.get("passRounds"));
        assertEquals(31L, rules.get("pointsEventType"));
    }

    @Test
    void templatesAreRecognisedByTheirGame() {
        assertEquals("qaPick", ShopGameCatalog.detectGame(3, null));
        assertEquals("qaText", ShopGameCatalog.detectGame(1, null));
        assertEquals("hidden", ShopGameCatalog.detectGame(10, null));
        assertEquals(null, ShopGameCatalog.detectGame(4, null));
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("qaShot", ShopGameCatalog.detectGame(null, tree(om, "{\"qa\":{\"enabled\":true,\"mode\":\"SHOT\"}}")));
        assertEquals("steps", ShopGameCatalog.detectGame(null, tree(om, "{\"steps\":{\"enabled\":true}}")));
        assertEquals(null, ShopGameCatalog.detectGame(null, tree(om, "{\"steps\":{\"enabled\":false}}")));
    }

    private static com.fasterxml.jackson.databind.JsonNode tree(com.fasterxml.jackson.databind.ObjectMapper om, String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ 2. the plan allowance

    @Test
    void freeOffersOneGameAndPaidPlansOfferMoreOfThePoolOnly() {
        assertEquals(1, ShopGameCatalog.allowance(0));
        assertEquals(3, ShopGameCatalog.allowance(2));
        assertEquals(3, ShopGameCatalog.allowance(5));
        assertEquals(1, ShopGameCatalog.allowance(-4));
        FakeJdbc jdbc = cafe(null, null);
        assertEquals(1, new ShopGameProfileService(jdbc, access(), null, plan(0)).allowanceOf(SHOP));
        assertEquals(3, new ShopGameProfileService(jdbc, access(), null, plan(2)).allowanceOf(SHOP));
        MerchantPlanService broken = mock(MerchantPlanService.class);
        when(broken.feature(anyLong(), anyString())).thenThrow(new IllegalStateException("db down"));
        assertEquals(1, new ShopGameProfileService(jdbc, access(), null, broken).allowanceOf(SHOP));
    }

    @Test
    void storedChoiceIsKeptToThePoolAndTheAllowance() {
        ShopGameCatalog.Kind cafe = ShopGameCatalog.kind("CAFE");
        ShopGameProfileService.Stored stored = new ShopGameProfileService.Stored(null,
                List.of(new ShopGameProfileService.Entry("steps", null), new ShopGameProfileService.Entry("trueFalse", 13L),
                        new ShopGameProfileService.Entry("qaPick", 12L)), List.of(), false);
        ShopGameProfileService.Resolved free = ShopGameProfileService.resolve(cafe, stored, Set.of(), 1);
        assertEquals(List.of("trueFalse"), keys(free));
        assertEquals(ShopGameProfileService.SOURCE_MERCHANT, free.source());
        assertEquals(List.of("trueFalse", "qaPick"), keys(ShopGameProfileService.resolve(cafe, stored, Set.of(), 3)));
        // An operator switched trueFalse off: it drops out everywhere.
        assertEquals(List.of("qaPick"), keys(ShopGameProfileService.resolve(cafe, stored, Set.of("trueFalse"), 1)));
        // Every pool game off: the default falls back to the general pool.
        assertEquals(List.of("hidden"), keys(ShopGameProfileService.resolve(cafe, null,
                Set.of("qaPick", "imageGuess", "trueFalse"), 1)));
        // Operator override wins over the merchant and may use any game that is on.
        ShopGameProfileService.Stored op = new ShopGameProfileService.Stored(null, stored.merchantGames(),
                List.of(new ShopGameProfileService.Entry("jigsaw", 99L)), false);
        ShopGameProfileService.Resolved r = ShopGameProfileService.resolve(cafe, op, Set.of(), 1);
        assertEquals(List.of("jigsaw"), keys(r));
        assertEquals(ShopGameProfileService.SOURCE_OPERATOR, r.source());
    }

    private static List<String> keys(ShopGameProfileService.Resolved r) {
        return r.games().stream().map(ShopGameProfileService.Entry::key).toList();
    }

    @Test
    void merchantCanSaveWithinPoolAndAllowanceOnly() {
        FakeJdbc jdbc = cafe(null, null);
        ShopGameProfileService free = new ShopGameProfileService(jdbc, access(), null, plan(0));
        MerchantGapException over = assertThrows(MerchantGapException.class, () -> free.merchantSave(OWNER,
                Map.of("games", List.of(Map.of("key", "qaPick", "templateId", 12), Map.of("key", "trueFalse", "templateId", 13)))));
        assertEquals("SHOP_GAMES_OVER_ALLOWANCE", over.getErrorCode());
        assertEquals("SHOP_GAME_NOT_IN_POOL", assertThrows(MerchantGapException.class, () -> free.merchantSave(OWNER,
                Map.of("games", List.of(Map.of("key", "steps")))) ).getErrorCode());
        assertEquals("SHOP_GAME_TEMPLATE_MISMATCH", assertThrows(MerchantGapException.class, () -> free.merchantSave(OWNER,
                Map.of("games", List.of(Map.of("key", "qaPick", "templateId", 13)))) ).getErrorCode());
        assertTrue(jdbc.updates.isEmpty(), "nothing written on a refusal");

        ShopGameProfileService basic = new ShopGameProfileService(jdbc, access(), null, plan(2));
        basic.merchantSave(OWNER, Map.of("games", List.of(Map.of("key", "qaPick", "templateId", 12),
                Map.of("key", "trueFalse", "templateId", 13))));
        assertTrue(jdbc.updates.stream().anyMatch(s -> s.contains("cy_shop_game_profile")));
        assertTrue(jdbc.updates.stream().anyMatch(s -> s.contains("cy_shop_game_audit")));
    }

    @Test
    void merchantCannotChangeAnOperatorProfile() {
        FakeJdbc jdbc = cafe(null, "[{\"key\":\"jigsaw\",\"templateId\":null}]");
        ShopGameProfileService svc = new ShopGameProfileService(jdbc, access(), null, plan(5));
        assertEquals("SHOP_GAMES_LOCKED", assertThrows(MerchantGapException.class,
                () -> svc.merchantSave(OWNER, Map.of("games", List.of(Map.of("key", "qaPick"))))).getErrorCode());
        Map<String, Object> view = svc.merchantView(OWNER);
        assertEquals(false, view.get("editable"));
        assertEquals("OPERATOR", view.get("source"));
    }

    @Test
    void operatorWritesNeedAReason() {
        ShopGameProfileService svc = new ShopGameProfileService(cafe(null, null), access(), null, plan(0));
        assertThrows(IllegalArgumentException.class, () -> svc.adminOverride(SHOP, Map.of("locked", true), 1L, "ops", " "));
        assertThrows(IllegalArgumentException.class, () -> svc.adminCurate("qaPick", false, 1L, "ops", null));
        assertThrows(IllegalArgumentException.class, () -> svc.adminOverride(SHOP, Map.of("kindOverride", "SPACESHIP"), 1L, "ops", "x"));
    }

    // ------------------------------------------------------------------ 3. a check-in launches the profile's game

    private static ShopGamePlayService plays(FakeJdbc jdbc, ShopGameProfileService profiles, IPointsAwardService points, Instant now) {
        return new ShopGamePlayService(jdbc, profiles, null, points, Clock.fixed(now, ZoneId.of("Asia/Shanghai")));
    }

    private static Map<String, Object> playRow(long id, String key, Long tpl, String status, Instant expires) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("member_id", MEMBER);
        r.put("merchant_id", SHOP);
        r.put("poi_id", POI);
        r.put("game_key", key);
        r.put("template_id", tpl);
        r.put("source", "ROAM_CHECKIN");
        r.put("status", status);
        r.put("rounds_won", null);
        r.put("points", 0);
        r.put("play_day", "2026-11-15");
        r.put("issued_at", Timestamp.from(expires.minusSeconds(1800)));
        r.put("expires_at", Timestamp.from(expires));
        return r;
    }

    @Test
    void checkinLaunchesTheShopsDefaultGameWithItsTemplate() {
        Instant now = Instant.parse("2026-11-15T04:00:00Z");
        FakeJdbc jdbc = cafe(null, null);
        List<Map<String, Object>> plays = new ArrayList<>();
        jdbc.rows.put("FROM cy_shop_game_play WHERE member_id", a -> List.copyOf(plays));
        ShopGameProfileService profiles = new ShopGameProfileService(jdbc, access(), null, plan(0));
        ShopGamePlayService svc = new ShopGamePlayService(jdbc, profiles, null, null,
                Clock.fixed(now, ZoneId.of("Asia/Shanghai"))) {
            @Override
            Map<String, Object> issue(long memberId, long poiId, String source) {
                return super.issue(memberId, poiId, source);
            }
        };
        // The insert makes the row readable.
        FakeJdbc spy = jdbc;
        jdbc.rows.put("FROM cy_shop_game_play WHERE member_id", a -> spy.updates.stream().anyMatch(s -> s.contains("INSERT INTO cy_shop_game_play"))
                ? List.of(playRow(1L, "qaPick", 12L, "ISSUED", now.plusSeconds(1800))) : List.of());
        Map<String, Object> launch = svc.launchAfterCheckin(MEMBER, POI);
        assertEquals(true, launch.get("available"));
        @SuppressWarnings("unchecked")
        Map<String, Object> game = (Map<String, Object>) launch.get("game");
        assertEquals("qaPick", game.get("key"));
        assertEquals(12L, game.get("templateId"));
        int insert = indexOf(jdbc.updates, "INSERT INTO cy_shop_game_play");
        Object[] args = jdbc.updateArgs.get(insert);
        assertEquals("qaPick", args[3]);
        assertEquals(12L, args[4]);
        assertEquals("ROAM_CHECKIN", args[5]);
        assertEquals("2026-11-15", args[6]);
        // Launching again the same day returns the same play; nothing new is written.
        int writes = jdbc.updates.size();
        assertEquals(1L, svc.launchAfterCheckin(MEMBER, POI).get("playId"));
        assertEquals(writes, jdbc.updates.size());
    }

    @Test
    void aShopWithNoMatchingTemplateSaysNotReadyAndTheCheckinStillWorks() {
        FakeJdbc jdbc = cafe(null, null);
        jdbc.rows.put("FROM cms_member_template", a -> List.of(template(14L, 2, null)));
        ShopGamePlayService svc = plays(jdbc, new ShopGameProfileService(jdbc, access(), null, plan(0)), null, Instant.now());
        Map<String, Object> out = svc.launchAfterCheckin(MEMBER, POI);
        assertEquals(false, out.get("available"));
        assertEquals("NOT_READY", out.get("reason"));
        // A broken database answers available=false, never an exception into the check-in.
        FakeJdbc broken = new FakeJdbc() {
            @Override public <T> List<T> queryForList(String sql, Class<T> t, Object... a) { throw new IllegalStateException("x"); }
            @Override public List<Map<String, Object>> queryForList(String sql, Object... a) { throw new IllegalStateException("x"); }
        };
        assertEquals(false, plays(broken, new ShopGameProfileService(broken, access(), null, plan(0)), null, Instant.now())
                .launchAfterCheckin(MEMBER, POI).get("available"));
    }

    @Test
    void theGameIsPickedByRotationNotByThePlayer() {
        int a = ShopGamePlayService.pick(MEMBER, "2026-11-15", 3);
        assertEquals(a, ShopGamePlayService.pick(MEMBER, "2026-11-15", 3));
        assertTrue(a >= 0 && a < 3);
        assertEquals(-1, ShopGamePlayService.pick(MEMBER, "2026-11-15", 0));
        assertEquals(0, ShopGamePlayService.pick(MEMBER, "2026-11-15", 1));
    }

    @Test
    void roamCheckinControllerAttachesTheShopGameOnlyAfterTrustAndRadius() throws Exception {
        String src = Files.readString(Path.of(root(), "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamController.java"),
                StandardCharsets.UTF_8);
        int checkin = src.indexOf("public AjaxResult checkin(");
        int trust = src.indexOf("trusted.rejected()", checkin);
        int radius = src.indexOf("CHECKIN_LOOSE_RADIUS_M", checkin);
        int launch = src.indexOf("shopGamePlays.launchAfterCheckin(memberId, poiId)", checkin);
        assertTrue(checkin > 0 && trust > checkin && radius > trust && launch > radius);
    }

    // ------------------------------------------------------------------ 4. results through points governance

    @Test
    void aWinAwardsThroughTheGovernedAwardPathOnce() {
        Instant now = Instant.parse("2026-11-15T04:00:00Z");
        FakeJdbc jdbc = cafe(null, null);
        Map<String, Object>[] row = new Map[] { playRow(1L, "qaPick", 12L, "ISSUED", now.plusSeconds(600)) };
        jdbc.rows.put("FROM cy_shop_game_play WHERE id", a -> List.of(row[0]));
        IPointsAwardService points = mock(IPointsAwardService.class);
        when(points.award(MEMBER, 31L, 1L, "店铺小游戏")).thenReturn(10);
        ShopGamePlayService svc = plays(jdbc, new ShopGameProfileService(jdbc, access(), null, plan(0)), points, now);
        svc.finish(MEMBER, Map.of("playId", 1, "roundsWon", 2));
        verify(points).award(MEMBER, 31L, 1L, "店铺小游戏");
        int upd = indexOf(jdbc.updates, "UPDATE cy_shop_game_play SET status = ?");
        assertEquals("WON", jdbc.updateArgs.get(upd)[0]);
        assertEquals(10, jdbc.updateArgs.get(upd)[2]);
        // Finished: a second result changes nothing and awards nothing more.
        row[0] = new LinkedHashMap<>(row[0]);
        row[0].put("status", "WON");
        IPointsAwardService again = mock(IPointsAwardService.class);
        Map<String, Object> out = plays(jdbc, new ShopGameProfileService(jdbc, access(), null, plan(0)), again, now)
                .finish(MEMBER, Map.of("playId", 1, "roundsWon", 3));
        assertEquals(true, out.get("alreadyFinished"));
        verify(again, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void aLossOrAnExpiredPlayAwardsNothing() {
        Instant now = Instant.parse("2026-11-15T04:00:00Z");
        FakeJdbc jdbc = cafe(null, null);
        jdbc.rows.put("FROM cy_shop_game_play WHERE id", a -> List.of(playRow(1L, "qaPick", 12L, "ISSUED", now.plusSeconds(600))));
        IPointsAwardService points = mock(IPointsAwardService.class);
        plays(jdbc, new ShopGameProfileService(jdbc, access(), null, plan(0)), points, now).finish(MEMBER, Map.of("playId", 1, "roundsWon", 1));
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());

        FakeJdbc late = cafe(null, null);
        late.rows.put("FROM cy_shop_game_play WHERE id", a -> List.of(playRow(1L, "qaPick", 12L, "ISSUED", now.minusSeconds(1))));
        assertEquals("PLAY_EXPIRED", assertThrows(MerchantGapException.class, () -> plays(late,
                new ShopGameProfileService(late, access(), null, plan(0)), points, now)
                .finish(MEMBER, Map.of("playId", 1, "roundsWon", 3))).getErrorCode());
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        assertEquals("INVALID_REQUEST", assertThrows(MerchantGapException.class, () -> plays(late,
                new ShopGameProfileService(late, access(), null, plan(0)), points, now)
                .finish(MEMBER, Map.of("playId", 1, "roundsWon", 4))).getErrorCode());
    }

    @Test
    void shopGamePointsAreGovernedByTheMigration() throws Exception {
        String sql = Files.readString(Path.of(root(), "chengyinhub-admin/sql/migration_20261115_shop_games.sql"), StandardCharsets.UTF_8);
        assertTrue(sql.contains("INSERT INTO points_award_cap"));
        assertTrue(sql.contains("SELECT 31, '店铺小游戏', 'PLAY', 30"));
        assertTrue(sql.contains("UNIQUE KEY uk_shop_game_play_day (member_id, merchant_id, play_day)"));
        assertFalse(sql.contains("INSERT INTO sys_role_menu"), "permissions are granted to no role");
        assertFalse(sql.toLowerCase().contains("cy_money_switch"));
    }

    // ------------------------------------------------------------------ 5. fairness and City Monopoly untouched

    @Test
    void noPaidPlacementOrSponsorshipDecidesTheGame() throws Exception {
        for (String f : List.of("ShopGameCatalog.java", "ShopGameProfileService.java", "ShopGamePlayService.java", "CityShopLandingHook.java")) {
            String code = stripComments(Files.readString(shopgames().resolve(f), StandardCharsets.UTF_8)).toLowerCase();
            for (String word : List.of("sponsor", "promotion", "placement", "paid", "pricefen", "money")) {
                assertFalse(code.contains(word), f + " mentions " + word);
            }
        }
    }

    @Test
    void cityMonopolyRulesAreUntouched() throws Exception {
        Path city = Path.of(root(), "chengyinhub-system/src/main/java/com/chengyinhub/business/citygame");
        try (Stream<Path> files = Files.list(city)) {
            for (Path p : files.toList()) {
                String code = Files.readString(p, StandardCharsets.UTF_8);
                assertFalse(code.contains("shopgames"), p.getFileName() + " must not depend on shop games");
            }
        }
        ShopGameProfileService profiles = new ShopGameProfileService(cafe(null, null), access(), null, plan(0));
        Map<String, Object> hook = new CityShopLandingHook(profiles).onShopLanding(POI, null);
        assertEquals(false, hook.get("changesCityRules"));
        @SuppressWarnings("unchecked")
        Map<String, Object> launch = (Map<String, Object>) hook.get("launch");
        assertEquals("CITY_LANDING", launch.get("source"));
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) hook.get("profile");
        assertEquals(true, profile.get("available"));
        // The hook only reads: its sole dependency is the profile service.
        assertEquals(1, CityShopLandingHook.class.getConstructors()[0].getParameterCount());
    }

    // ------------------------------------------------------------------ helpers

    private static int indexOf(List<String> sqls, String fragment) {
        for (int i = 0; i < sqls.size(); i++) if (sqls.get(i).contains(fragment)) return i;
        throw new AssertionError("no statement with " + fragment + " in " + sqls);
    }

    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static Path shopgames() {
        return Path.of(root(), "chengyinhub-system/src/main/java/com/chengyinhub/business/shopgames");
    }

    private static String root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("chengyinhub-admin/sql"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("repository root not found");
        return p.toString();
    }
}
