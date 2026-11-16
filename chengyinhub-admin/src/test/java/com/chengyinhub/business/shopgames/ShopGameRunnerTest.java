package com.chengyinhub.business.shopgames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import com.chengyinhub.business.service.impl.TemplatePlayRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * W7 x-shop-game-runner: a shop play's own runtime session, outside any journey, on the real section engine and the
 * migration's tables (H2, MySQL mode). Covers start, judged actions, replay, the tries cap, presence at the shop,
 * the switch, ownership, unsupported templates, and that journey and shop sessions never see each other.
 */
class ShopGameRunnerTest {

    private static final long MEMBER = 5L;
    private static final long OTHER = 6L;
    private static final long SHOP = 7L;
    private static final long POI = 900L;
    private static final long TEMPLATE = 13L;
    private static final long BRANCH_TEMPLATE = 14L;
    private static final double SHOP_LAT = 30.27;
    private static final double SHOP_LNG = 120.15;
    private static final String TRUE_FALSE = "{\"schemaVersion\":1,\"trueFalse\":{\"enabled\":true,\"title\":\"The cafe opened in 1990\","
            + "\"answer\":false,\"xp\":12}}";
    private static final String BRANCH = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"a\",\"steps\":["
            + "{\"id\":\"a\",\"text\":\"start\",\"options\":[{\"id\":\"o\",\"label\":\"go\",\"nextStepId\":\"b\"}]},"
            + "{\"id\":\"b\",\"text\":\"end\",\"terminal\":true,\"outcomeCode\":\"DONE\"}]}}";

    private JdbcTemplate jdbc;
    private IPointsAwardService points;
    private final Map<String, String> config = new HashMap<>();
    private ShopGamePlayService plays;
    private ShopGameSessionService sessions;
    private AdvancedGameRuntimeServiceImpl runtime;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:sgr_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        ds.setUsername("sa");
        String sql = Files.readString(Path.of("sql/migration_20261117_shop_game_runner.sql"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS[\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(sql);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            int tables = 0;
            while (m.find()) {
                s.execute(m.group().replaceAll("\\) ENGINE=InnoDB[^;]*;", ")")
                        .replaceAll("\\bJSON\\b", "VARCHAR(60000)").replaceAll("\\bMEDIUMTEXT\\b", "VARCHAR(60000)"));
                tables++;
            }
            assertEquals(2, tables);
            s.execute("create table cy_shop_game_play (id bigint auto_increment primary key, member_id bigint, merchant_id bigint,"
                    + " poi_id bigint, game_key varchar(32), template_id bigint, source varchar(32), status varchar(16),"
                    + " rounds_won int, points int, play_day varchar(10), issued_at timestamp(3), expires_at timestamp(3),"
                    + " finished_at timestamp(3))");
            s.execute("create table cms_member_template (id bigint primary key, advanced_config_json varchar(60000), del_flag tinyint)");
            s.execute("create table roam_poi (id bigint primary key, merchant_id bigint, lat double, lng double, radius_m int)");
            s.execute("create table mms_merchant (id bigint primary key, location_lat double, location_lng double)");
            s.execute("create table game_runtime_session (id bigint auto_increment primary key, activity_id bigint, topic_id bigint,"
                    + " node_id bigint, template_id bigint, owner_type tinyint, owner_id bigint, status tinyint, started_at timestamp(3))");
        }
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into cms_member_template values (?, ?, 0)", TEMPLATE, TRUE_FALSE);
        jdbc.update("insert into cms_member_template values (?, ?, 0)", BRANCH_TEMPLATE, BRANCH);
        jdbc.update("insert into roam_poi values (?, ?, ?, ?, 60)", POI, SHOP, SHOP_LAT, SHOP_LNG);

        runtime = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(runtime, "clock", (AdvancedGameClock) Date::new);
        ReflectionTestUtils.setField(runtime, "random", (AdvancedGameRandom) bound -> 0);

        points = mock(IPointsAwardService.class);
        when(points.award(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(10);
        config.put(ShopGamePlayService.KEY_PLAY_ENABLED, "true");
        plays = new ShopGamePlayService(jdbc, mock(ShopGameProfileService.class), null, points, Clock.systemUTC())
                .withConfig(config::get, runtime);
        sessions = new ShopGameSessionService(jdbc, plays, runtime);
    }

    private long issue(long member, long template) {
        Instant now = Instant.now();
        jdbc.update("insert into cy_shop_game_play (member_id, merchant_id, poi_id, game_key, template_id, source, status, rounds_won,"
                + " points, play_day, issued_at, expires_at) values (?, ?, ?, 'trueFalse', ?, 'PLACE_PAGE', 'ISSUED', null, 0, '2026-11-17', ?, ?)",
                member, SHOP, POI, template, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(1800)));
        return jdbc.queryForObject("select max(id) from cy_shop_game_play", Long.class);
    }

    private Map<String, Object> act(Map<String, Object> view, String key, String action, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", view.get("sessionId"));
        body.put("version", view.get("version"));
        body.put("idempotencyKey", key);
        body.put("action", action);
        body.put("payload", payload);
        return sessions.act(MEMBER, body);
    }

    private Map<String, Object> finishAt(long playId, double lat, double lng) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playId", playId);
        body.put("lat", lat);
        body.put("lng", lng);
        body.put("accuracy", 10);
        return plays.finish(MEMBER, body);
    }

    private static int code(Runnable call) {
        MerchantGapException e = assertThrows(MerchantGapException.class, call::run);
        return e.getStatus();
    }

    private static String errorCode(Runnable call) {
        return assertThrows(MerchantGapException.class, call::run).getErrorCode();
    }

    @Test
    void aWinAtTheShopIsJudgedFromTheShopSessionAndPaysPoints() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        assertEquals("RUNNING", view.get("status"));
        assertEquals(playId, view.get("playId"));
        assertEquals("TEMPLATE", view.get("scope"));
        assertNull(view.get("topicId"));
        assertNull(view.get("nodeId"));
        assertFalse(String.valueOf(view.get("config")).contains("answer=false"), "the answer never reaches the client");

        Map<String, Object> resumed = sessions.start(MEMBER, playId);
        assertEquals(view.get("sessionId"), resumed.get("sessionId"), "a second start resumes the same session");

        assertEquals("PLAY_NOT_FINISHED", errorCode(() -> finishAt(playId, SHOP_LAT, SHOP_LNG)));

        Map<String, Object> after = act(view, "k1", "SUBMIT_TRUE_FALSE", Map.of("answer", false));
        assertEquals("COMPLETED", after.get("status"));
        assertEquals(12, after.get("score"));

        Map<String, Object> result = finishAt(playId, SHOP_LAT, SHOP_LNG);
        assertEquals("WON", result.get("status"));
        assertEquals(10, result.get("points"));
        assertNull(result.get("rewardCeilingReason"));
        verify(points).award(eq(MEMBER), eq(ShopGameCatalog.POINTS_EVENT_TYPE), eq(playId), anyString());
    }

    @Test
    void aRemoteWinFinishesButPaysNoPoints() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        act(view, "k1", "SUBMIT_TRUE_FALSE", Map.of("answer", false));

        Map<String, Object> result = finishAt(playId, SHOP_LAT + 0.05, SHOP_LNG);
        assertEquals("WON", result.get("status"), "progress is free");
        assertEquals(0, result.get("points"), "prizes need presence");
        assertEquals("REMOTE", result.get("rewardCeilingReason"));
        verify(points, never()).award(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void unlimitedTriesAreCappedSoGuessingUntilRightLoses() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        for (int i = 1; i <= TemplatePlayRules.TRIES_CAP; i++) {
            view = act(view, "w" + i, "SUBMIT_TRUE_FALSE", Map.of("answer", true));
        }
        assertEquals("FAILED", view.get("status"));
        assertEquals(409, code(() -> act(sessionsState(), "late", "SUBMIT_TRUE_FALSE", Map.of("answer", false))));

        Map<String, Object> result = finishAt(playId, SHOP_LAT, SHOP_LNG);
        assertEquals("LOST", result.get("status"));
        assertEquals(0, result.get("points"));
    }

    private Map<String, Object> sessionsState() {
        Long id = jdbc.queryForObject("select max(id) from cy_shop_game_session", Long.class);
        return sessions.state(MEMBER, id);
    }

    @Test
    void giveUpEndsTheSessionAsLost() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        assertEquals("FAILED", act(view, "g", TemplatePlayRules.GIVE_UP, Map.of()).get("status"));
        assertEquals("LOST", finishAt(playId, SHOP_LAT, SHOP_LNG).get("status"));
    }

    @Test
    void theSameKeyReplaysAndADifferentRequestUnderItIsRefused() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        Map<String, Object> first = act(view, "k1", "SUBMIT_TRUE_FALSE", Map.of("answer", true));
        Map<String, Object> again = act(view, "k1", "SUBMIT_TRUE_FALSE", Map.of("answer", true));
        assertEquals(first.get("version"), again.get("version"));
        assertEquals(first.get("status"), again.get("status"));
        assertEquals("IDEMPOTENCY_CONFLICT", errorCode(() -> act(view, "k1", "SUBMIT_TRUE_FALSE", Map.of("answer", false))));
        assertEquals("VERSION_CONFLICT", errorCode(() -> act(view, "k2", "SUBMIT_TRUE_FALSE", Map.of("answer", false))));
    }

    @Test
    void journeyAndShopSessionsNeverSeeEachOther() {
        // A journey session of the same member on the same template, with a known id.
        jdbc.update("insert into game_runtime_session (id, activity_id, topic_id, node_id, template_id, owner_type, owner_id, status,"
                + " started_at) values (1, 0, 19, 29, ?, 1, ?, 1, ?)", TEMPLATE, MEMBER, Timestamp.from(Instant.now()));
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        assertEquals(1, jdbc.queryForObject("select count(*) from game_runtime_session", Integer.class),
                "a shop session writes nothing to the journey table");
        // The journey session's id is not a shop session the player can read or act on.
        jdbc.update("delete from cy_shop_game_session_action");
        long shopId = ((Number) view.get("sessionId")).longValue();
        long journeyOnlyId = shopId + 100;
        jdbc.update("insert into game_runtime_session (id, activity_id, topic_id, node_id, template_id, owner_type, owner_id, status,"
                + " started_at) values (?, 0, 19, 30, ?, 1, ?, 1, ?)", journeyOnlyId, TEMPLATE, MEMBER, Timestamp.from(Instant.now()));
        assertEquals(404, code(() -> sessions.state(MEMBER, journeyOnlyId)));
        // A completed journey session sent as sessionId does not judge a shop play that has its own session.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playId", playId);
        body.put("sessionId", 1L);
        body.put("lat", SHOP_LAT);
        body.put("lng", SHOP_LNG);
        assertEquals("PLAY_NOT_FINISHED", errorCode(() -> plays.finish(MEMBER, body)));
        // And the shop session is never found by the journey key (activity, topic, node).
        assertEquals(0, jdbc.queryForObject("select count(*) from game_runtime_session where template_id = ? and node_id is null",
                Integer.class, TEMPLATE));
    }

    @Test
    void onlyTheOwnerCanStartReadOrPlay() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        assertEquals(404, code(() -> sessions.start(OTHER, playId)));
        assertEquals(404, code(() -> sessions.state(OTHER, ((Number) view.get("sessionId")).longValue())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", view.get("sessionId"));
        body.put("version", 0);
        body.put("idempotencyKey", "x");
        body.put("action", "SUBMIT_TRUE_FALSE");
        body.put("payload", Map.of("answer", false));
        assertEquals(404, code(() -> sessions.act(OTHER, body)));
    }

    @Test
    void theSwitchKeepsSessionsClosed() {
        long playId = issue(MEMBER, TEMPLATE);
        config.put(ShopGamePlayService.KEY_PLAY_ENABLED, "false");
        assertEquals("SHOP_GAME_NOT_OPEN", errorCode(() -> sessions.start(MEMBER, playId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from cy_shop_game_session", Integer.class));
    }

    @Test
    void aTemplateThatNeedsAJourneyIsNotPlayableHereAndStartsNothing() {
        long playId = issue(MEMBER, BRANCH_TEMPLATE);
        assertEquals("SHOP_GAME_NOT_PLAYABLE", errorCode(() -> sessions.start(MEMBER, playId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from cy_shop_game_session", Integer.class));
        assertEquals("ISSUED", jdbc.queryForObject("select status from cy_shop_game_play where id = ?", String.class, playId),
                "the play stays open");
    }

    @Test
    void aFinishedPlayCannotStartANewSession() {
        long playId = issue(MEMBER, TEMPLATE);
        jdbc.update("update cy_shop_game_play set status = 'EXPIRED' where id = ?", playId);
        assertEquals("PLAY_ALREADY_FINISHED", errorCode(() -> sessions.start(MEMBER, playId)));
    }

    @Test
    void journeyOnlyActionsAreRefused() {
        long playId = issue(MEMBER, TEMPLATE);
        Map<String, Object> view = sessions.start(MEMBER, playId);
        assertEquals("INVALID_ACTION", errorCode(() -> act(view, "c", "CHOOSE", Map.of("optionId", "o"))));
        assertEquals("INVALID_ACTION", errorCode(() -> act(view, "r", "RETRY", Map.of())));
    }

    @Test
    void rulesRefuseJourneySectionsAndCapTries() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertTrue(TemplatePlayRules.unsupported(json.readTree(TRUE_FALSE)).isEmpty());
        assertTrue(TemplatePlayRules.unsupported(json.readTree(BRANCH)).contains("branch"));
        assertTrue(TemplatePlayRules.unsupported(json.readTree("{\"multiplayer\":{\"enabled\":true},\"qa\":{\"enabled\":true}}"))
                .contains("multiplayer"));
        assertTrue(TemplatePlayRules.unsupported(json.readTree("{\"outcomeRules\":{},\"qa\":{\"enabled\":true}}")).contains("outcomeRules"));
        assertTrue(TemplatePlayRules.unsupported(json.readTree("{\"timer\":{\"enabled\":true}}")).contains("noGame"));
        ObjectNode capped = TemplatePlayRules.capTries((ObjectNode) json.readTree(
                "{\"qa\":{\"enabled\":true,\"maxTries\":0},\"sort\":{\"enabled\":true},\"trueFalse\":{\"enabled\":true,\"maxTries\":2}}"));
        assertEquals(TemplatePlayRules.TRIES_CAP, capped.path("qa").path("maxTries").asInt());
        assertEquals(TemplatePlayRules.TRIES_CAP, capped.path("sort").path("maxAttempts").asInt());
        assertEquals(2, capped.path("trueFalse").path("maxTries").asInt(), "a stricter author limit stays");
        assertNotNull(List.of());
    }
}
