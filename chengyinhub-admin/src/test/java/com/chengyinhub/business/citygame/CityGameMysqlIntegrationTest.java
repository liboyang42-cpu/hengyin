package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CITY game on real MySQL 8 with the real migration and MyBatis XML: team formation, server dice,
 * occupy / toll / challenge with points, round settlement, idempotent receipts, concurrency and fairness.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_CITY_GAME_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CityGameMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("city_game_it");
    private static final String BOARD = "board-sh-1";
    private static final String REGION = "shanghai";
    private static final String HASH = "a".repeat(64);
    private static final long A1 = 7001, A2 = 7002, B1 = 7101, OUTSIDER = 7999;

    private DataSource dataSource;
    private CityGameMapper mapper;
    private final AtomicLong clock = new AtomicLong();
    private final Deque<Integer> tickets = new ArrayDeque<>();
    private CityGameReadService reads;
    private CityGameParticipationService participation;
    private CityGameActionService actions;
    private CityFairnessService fairness;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        Configuration configuration = new Configuration(new Environment("city-game-it", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(CityGameMapper.class);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CityGameMapper.xml")) {
            assertNotNull(in, "CityGameMapper.xml on classpath");
            new XMLMapperBuilder(in, configuration, "mapper/business/CityGameMapper.xml", configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(CityGameMapper.class);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        reads = new CityGameReadService(mapper, clock::get);
        participation = transactional(new CityGameParticipationService(mapper), tx);
        actions = transactional(new CityGameActionService(mapper, n -> {
            Integer t = tickets.poll();
            return t == null ? 0 : t;
        }, clock::get), tx);
        fairness = new CityFairnessService(mapper, clock::get);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager tx) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS city_game_board", "DROP TABLE IF EXISTS city_game_point", "DROP TABLE IF EXISTS city_game_property",
                "DROP TABLE IF EXISTS city_game_service", "DROP TABLE IF EXISTS city_game_team", "DROP TABLE IF EXISTS city_game_participation",
                "DROP TABLE IF EXISTS city_game_round", "DROP TABLE IF EXISTS city_game_roll", "DROP TABLE IF EXISTS city_game_task_attempt",
                "DROP TABLE IF EXISTS city_game_quote", "DROP TABLE IF EXISTS city_game_request", "DROP TABLE IF EXISTS city_game_event",
                "DROP TABLE IF EXISTS city_game_outing", "DROP TABLE IF EXISTS ums_member", "DROP TABLE IF EXISTS ums_member_points_detail");
        String baseline = Files.readString(Paths.get("../sql/base_schema_20260818.sql"));
        execute(table(baseline, "ums_member"), table(baseline, "ums_member_points_detail"));
        // The real, unmodified migration file.
        for (String statement : Files.readString(Paths.get("sql/migration_20261113_gaps_a_city_game.sql")).split(";\\s*\\n")) {
            String sql = statement.replaceAll("(?m)^--.*$", "").trim();
            if (!sql.isEmpty()) execute(sql);
        }
        execute("INSERT INTO ums_member(id, role, status, del_flag, point) VALUES (7001,'player',1,0,100),(7002,'player',1,0,0),"
                + "(7101,'player',1,0,50),(7999,'player',1,0,10)");
        execute("INSERT INTO city_game_board(board_id, game_id, region_id, season_id, rules_release_id, release_id, rules_hash, rule_version,"
                + " lifecycle, title, revision, board_version, event_sequence, dice_id, dice_faces, dice_weights, actions_per_round, max_rounds,"
                + " acquire_points, challenge_points, holding_yield, goal_score, holding_limit, protection_seconds, quote_valid_seconds, term_seconds,"
                + " min_members, max_members, rules_id, rules_text, challenge_rule_id, protection_text, safety_statement, valid_from,"
                + " fairness_metric_version, fairness_horizon_seconds, fairness_delta_seconds, map_datum, map_south, map_west, map_north, map_east)"
                + " VALUES ('" + BOARD + "','city-game','" + REGION + "','s2026w','rel-1',11,'" + HASH + "','v1','OPEN','外滩城市局',3,3,0,"
                + " 'd6','[1,2,3]','[1,1,1]',4,2,30,20,2,1,2,600,300,86400,1,4,'rules-1','规则正文','ch-1','保护期说明','安全声明',"
                + " '2026-11-01 00:00:00','fm-1',0,86400,'GCJ02',31.2,121.4,31.3,121.5)");
        execute("INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, domain, collected_at) VALUES"
                + " ('" + BOARD + "','pt-0','广场',31.21,121.41,'POI','2026-11-01'),('" + BOARD + "','pt-1','咖啡馆',31.22,121.42,'MERCHANT','2026-11-01'),"
                + " ('" + BOARD + "','pt-2','书店',31.23,121.43,'MERCHANT','2026-11-01'),('" + BOARD + "','pt-3','面馆',31.24,121.44,'MERCHANT','2026-11-01')");
        execute("INSERT INTO city_game_property(board_id, property_id, point_id, track_index, role) VALUES"
                + " ('" + BOARD + "','goal-0','pt-0',0,'NEUTRAL'),('" + BOARD + "','prop-1','pt-1',1,'OPERABLE'),"
                + " ('" + BOARD + "','prop-2','pt-2',2,'OPERABLE'),('" + BOARD + "','prop-3','pt-3',3,'OPERABLE')");
        execute("INSERT INTO city_game_service(board_id, service_id, property_id, title, member_points_fee) VALUES"
                + " ('" + BOARD + "','svc-1','prop-1','手冲体验',10),('" + BOARD + "','svc-2','prop-2','未公布',NULL)");
        clock.set(java.sql.Timestamp.valueOf("2026-11-13 10:00:00").getTime());
        tickets.clear();
    }

    private static String table(String baseline, String name) {
        Matcher m = Pattern.compile("CREATE TABLE `" + name + "` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(baseline);
        assertTrue(m.find(), "baseline has " + name);
        return m.group();
    }

    // ---- helpers ----

    private CityGameParticipationService.Outcome command(long member, String kind, String requestId, long version, String teamId, String invite) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("requestId", requestId);
        body.put("regionId", REGION);
        body.put("releaseId", 11L);
        body.put("contentHash", HASH);
        body.put("expectedVersion", version);
        if (teamId != null) body.put("teamId", teamId);
        if (invite != null) body.put("inviteCode", invite);
        return participation.command(member, body);
    }

    private String teamOf(long member) { return (String) mapper.selectParticipation(BOARD, member).get("teamId"); }
    private long teamVersion(String team) { return ((Number) mapper.selectTeam(team).get("version")).longValue(); }

    /** Team A = {A1, A2}, team B = {B1}; both locked and confirmed. */
    private void formTeams() {
        assertEquals(200, command(A1, "CREATE", "a-create", 0, null, null).status);
        String a = teamOf(A1);
        String invite = (String) mapper.selectTeam(a).get("inviteCode");
        assertEquals(200, command(A2, "JOIN", "a2-join", teamVersion(a), a, invite).status);
        assertEquals(409, command(A2, "LOCK", "a2-lock", teamVersion(a), a, null).status, "only the captain locks");
        assertEquals(200, command(A1, "LOCK", "a-lock", teamVersion(a), a, null).status);
        assertEquals(200, command(A1, "CONFIRM", "a-confirm", teamVersion(a), a, null).status);
        assertNull(mapper.selectOpenRound(BOARD, a), "round waits for every member");
        assertEquals(200, command(A2, "CONFIRM", "a2-confirm", teamVersion(a), a, null).status);
        assertNotNull(mapper.selectOpenRound(BOARD, a));
        assertEquals(200, command(B1, "CREATE", "b-create", 0, null, null).status);
        String b = teamOf(B1);
        assertEquals(200, command(B1, "LOCK", "b-lock", teamVersion(b), b, null).status);
        assertEquals(200, command(B1, "CONFIRM", "b-confirm", teamVersion(b), b, null).status);
    }

    private Map<String, Object> snapshot(long member) { return actions.snapshot(member, BOARD); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> property(Map<String, Object> snapshot, String id) {
        for (Map<String, Object> p : (List<Map<String, Object>>) snapshot.get("properties")) if (id.equals(p.get("propertyId"))) return p;
        throw new AssertionError(id);
    }

    private Map<String, Object> act(long member, String action, String target, String requestId, Map<String, Object> quote) {
        Map<String, Object> snap = snapshot(member);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("boardId", BOARD);
        body.put("participationId", mapper.selectTeam(teamOf(member)).get("sessionId"));
        body.put("targetId", target);
        body.put("roundSeq", snap.get("roundSeq"));
        String propertyId = target.startsWith("svc-") ? (String) mapper.selectService(BOARD, target).get("propertyId") : target;
        body.put("expectedVersion", "ROLL_ROUND".equals(action) ? snap.get("boardVersion") : property(snap, propertyId).get("stateVersion"));
        body.put("requestId", requestId);
        if (quote != null) {
            body.put("quoteId", quote.get("quoteId"));
            body.put("quoteVersion", quote.get("quoteVersion"));
            body.put("payerMemberId", quote.get("payerMemberId"));
        }
        return actions.submit(member, body);
    }

    private Map<String, Object> quote(long member, String action, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("boardId", BOARD);
        body.put("targetId", target);
        return actions.quote(member, body);
    }

    private long points(long member) { return mapper.selectMemberPoints(member); }

    private long count(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---- scenarios ----

    @Test
    void fullLoopRollOccupyTollChallengeAndSettle() throws Exception {
        formTeams();
        String a = teamOf(A1), b = teamOf(B1);

        Map<String, Object> current = reads.current(A1, REGION);
        assertEquals("JOINED", current.get("participationStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> board = (Map<String, Object>) current.get("board");
        Map<String, Object> read = reads.participation(A1, REGION, BOARD, "s2026w", "rel-1", "3");
        @SuppressWarnings("unchecked")
        Map<String, Object> part = (Map<String, Object>) read.get("participation");
        assertEquals(mapper.selectTeam(a).get("sessionId"), part.get("participationId"));
        assertEquals(teamVersion(a), part.get("membershipVersion"));
        assertEquals(board.get("revision"), 3L);
        assertThrows(CityGameException.class, () -> reads.points(A1, REGION, BOARD, "s2026w", "rel-1", "4"), "stale revision restarts");

        Map<String, Object> snap = snapshot(A1);
        assertEquals("CITY_GAME_SNAPSHOT_V1", snap.get("contract"));
        assertEquals(1L, snap.get("roundSeq"));
        assertEquals(100L, snap.get("memberPoints"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) snap.get("rules");
        assertEquals(4, rules.get("actionsRemaining"));

        // Server die: ticket 0 → face 1 → track 1 (prop-1). A replay never re-rolls.
        tickets.add(0);
        Map<String, Object> roll = act(A1, "ROLL_ROUND", BOARD, "a-roll-1", null);
        assertEquals("APPLIED", roll.get("decision"));
        assertEquals(1, roll.get("diceFace"));
        assertTrue((Long) roll.get("currentVersion") > 3L);
        assertEquals("prop-1", mapper.selectTeam(a).get("landedPropertyId"));
        assertEquals(roll, actions.receipt(A1, "a-roll-1"));
        assertEquals(1, count("SELECT COUNT(*) FROM city_game_roll"));

        // Occupy without the task first is refused; with the task and a quote it charges the payer only.
        Map<String, Object> early = quote(A1, "ACQUIRE_PROPERTY", "prop-1");
        assertEquals(30L, early.get("memberPointsCost"));
        assertEquals(String.valueOf(A1), early.get("payerMemberId"));
        assertEquals(List.of("7001", "7002"), early.get("beneficiaryMemberIds"));
        Map<String, Object> refused = act(A1, "ACQUIRE_PROPERTY", "prop-1", "a-acq-early", early);
        assertEquals("REJECTED", refused.get("decision"));
        assertEquals("TASK_REQUIRED", refused.get("reasonCode"));
        assertEquals(100L, points(A1));

        Map<String, Object> task = act(A1, "ACCEPT_TASK", "prop-1", "a-task-1", null);
        assertEquals("APPLIED", task.get("decision"));
        assertEquals(true, task.get("taskAttemptKept"));
        Map<String, Object> q = quote(A1, "ACQUIRE_PROPERTY", "prop-1");
        Map<String, Object> acquired = act(A1, "ACQUIRE_PROPERTY", "prop-1", "a-acq-1", q);
        assertEquals("APPLIED", acquired.get("decision"), String.valueOf(acquired));
        assertEquals(70L, points(A1));
        assertEquals(0L, points(A2), "teammates are never charged");
        assertEquals(1, count("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id = 7001 AND change_points = 30"));
        Map<String, Object> held = property(snapshot(A1), "prop-1");
        assertEquals("PROTECTED", held.get("status"));
        assertEquals(true, held.get("heldByMyTeam"));
        assertEquals(false, property(snapshot(B1), "prop-1").get("heldByMyTeam"));
        // The used quote cannot be spent twice.
        Map<String, Object> reused = act(A1, "ACQUIRE_PROPERTY", "prop-1", "a-acq-again", q);
        assertNotEquals("APPLIED", reused.get("decision"));

        // 4th action spends the budget: round 1 settles (1 holding × yield 2) and round 2 opens.
        tickets.add(1);
        assertEquals("APPLIED", act(A1, "ROLL_ROUND", BOARD, "a-roll-2", null).get("decision"));
        assertEquals(1, count("SELECT COUNT(*) FROM city_game_round WHERE status = 'SETTLED' AND settled_score = 2"));
        assertEquals(2L, snapshot(A1).get("roundSeq"));
        assertEquals(2L, ((Number) mapper.selectTeam(a).get("score")).longValue());

        // Team B lands on prop-1 and pays the toll; the fee scores for team A.
        tickets.add(0);
        assertEquals("APPLIED", act(B1, "ROLL_ROUND", BOARD, "b-roll-1", null).get("decision"));
        Map<String, Object> toll = act(B1, "ACCEPT_SERVICE_VISIT", "svc-1", "b-toll-1", quote(B1, "ACCEPT_SERVICE_VISIT", "svc-1"));
        assertEquals("APPLIED", toll.get("decision"), String.valueOf(toll));
        assertEquals(40L, points(B1));
        assertEquals(12L, ((Number) mapper.selectTeam(a).get("score")).longValue());
        CityGameException unpublished = assertThrows(CityGameException.class, () -> quote(B1, "ACCEPT_SERVICE_VISIT", "svc-2"));
        assertEquals("CITY_PRICE_UNPUBLISHED", unpublished.getErrorCode());

        // Challenge is refused while protected, then a server dice duel (3 vs 1) transfers the property.
        assertEquals("APPLIED", act(B1, "ACCEPT_TASK", "prop-1", "b-task-1", null).get("decision"));
        Map<String, Object> protectedChallenge = act(B1, "START_CHALLENGE", "prop-1", "b-ch-0", quote(B1, "START_CHALLENGE", "prop-1"));
        assertEquals("NOT_CHALLENGEABLE", protectedChallenge.get("reasonCode"));
        clock.addAndGet(601_000);
        tickets.add(2);
        tickets.add(0);
        Map<String, Object> challenge = act(B1, "START_CHALLENGE", "prop-1", "b-ch-1", quote(B1, "START_CHALLENGE", "prop-1"));
        assertEquals("APPLIED", challenge.get("decision"), String.valueOf(challenge));
        assertEquals(b, mapper.selectPropertyForUpdate(BOARD, "prop-1").get("holderTeamId"));
        assertEquals(20L, points(B1));
        // Team A keeps its finished task attempt although the property changed hands.
        assertEquals(true, property(snapshot(A1), "prop-1").get("taskAttemptKept"));
        assertEquals(false, property(snapshot(A1), "prop-1").get("heldByMyTeam"));

        // Zero-point teammate: free choices stay, paid ones refuse without charging.
        Map<String, Object> broke = act(A2, "ACQUIRE_PROPERTY", "prop-1", "a2-acq", quote(A2, "ACQUIRE_PROPERTY", "prop-1"));
        assertEquals("REJECTED", broke.get("decision"));
        assertEquals(0L, points(A2));

        // Stale version, outsider and reused request id.
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("action", "ROLL_ROUND"); stale.put("boardId", BOARD); stale.put("participationId", mapper.selectTeam(a).get("sessionId"));
        stale.put("targetId", BOARD); stale.put("roundSeq", 2L); stale.put("expectedVersion", 3L); stale.put("requestId", "a-stale");
        Map<String, Object> conflict = actions.submit(A1, stale);
        assertEquals("VERSION_CONFLICT", conflict.get("decision"));
        assertNotEquals(3L, conflict.get("currentVersion"));
        stale.put("expectedVersion", 4L);
        assertEquals("CITY_REQUEST_REUSED", assertThrows(CityGameException.class, () -> actions.submit(A1, stale)).getErrorCode());
        Map<String, Object> outsider = new LinkedHashMap<>(stale);
        outsider.put("requestId", "x-1");
        assertEquals("NOT_JOINED", actions.submit(OUTSIDER, outsider).get("reasonCode"));

        // Ordered deltas from sequence 1 without gaps.
        List<Map<String, Object>> deltas = actions.deltas(A1, BOARD, "s2026w", 0);
        for (int i = 0; i < deltas.size(); i++) assertEquals((long) i + 1, deltas.get(i).get("sequence"));
        assertTrue(deltas.stream().anyMatch(d -> "ROUND_SETTLED".equals(d.get("type"))));

        // Fairness: horizon 0 makes every first outing mature; only the caller's own class is returned.
        Map<String, String> fq = new HashMap<>();
        fq.put("boardId", BOARD); fq.put("regionId", REGION); fq.put("seasonId", "s2026w"); fq.put("rulesVersion", "v1");
        fq.put("queryId", "q-1"); fq.put("asOf", String.valueOf(clock.get()));
        Map<String, Object> report = fairness.report(A1, fq);
        assertEquals("COUNTED", report.get("maturity"));
        assertEquals(2, report.get("denominator"), "members with an outing: A1 and B1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) report.get("outingClasses");
        assertEquals(1, classes.size());
        assertEquals(A1, classes.get(0).get("actorId"));
        Map<String, Object> outing = fairness.outing(B1, fq);
        assertEquals("MATURE", outing.get("maturity"));
        fq.put("rulesVersion", "v2");
        assertEquals(403, assertThrows(CityGameException.class, () -> fairness.report(A1, fq)).getStatus());
    }

    @Test
    void concurrentDuplicateRollIsAnsweredOnceAndReplayed() throws Exception {
        formTeams();
        String session = (String) mapper.selectTeam(teamOf(A1)).get("sessionId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "ROLL_ROUND"); body.put("boardId", BOARD); body.put("participationId", session);
        body.put("targetId", BOARD); body.put("roundSeq", 1L); body.put("expectedVersion", 3L); body.put("requestId", "dup-roll");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit((Callable<Map<String, Object>>) () -> actions.submit(A1, new LinkedHashMap<>(body))));
            Map<String, Object> first = futures.get(0).get();
            for (Future<Map<String, Object>> f : futures) assertEquals(first, f.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM city_game_roll"));
        assertEquals(1, count("SELECT COUNT(*) FROM city_game_request WHERE request_id = 'dup-roll'"));
        assertEquals(3, ((Number) mapper.selectOpenRound(BOARD, teamOf(A1)).get("actionsRemaining")).intValue());
    }

    @Test
    void participationCommandsAreIdempotentAndGuardTheRoster() throws Exception {
        CityGameParticipationService.Outcome created = command(A1, "CREATE", "c-1", 0, null, null);
        assertEquals(200, created.status);
        CityGameParticipationService.Outcome again = command(A1, "CREATE", "c-1", 0, null, null);
        assertEquals(200, again.status);
        assertEquals(1, count("SELECT COUNT(*) FROM city_game_team"));
        assertEquals(409, command(A1, "CREATE", "c-2", 0, null, null).status);
        String team = teamOf(A1);
        Map<String, Object> state = reads.teamState(A1, REGION);
        assertEquals("PENDING", state.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) state.get("team");
        assertNotNull(t.get("inviteCode"));
        Map<String, Object> preview = reads.invite(A2, REGION, (String) t.get("inviteCode"));
        assertEquals(team, preview.get("teamId"));
        assertEquals(1, preview.get("memberCount"));
        assertEquals(404, assertThrows(CityGameException.class,
                () -> reads.invite(A2, REGION, "00000000-0000-4000-8000-000000000000")).getStatus());
        assertEquals(409, command(A2, "JOIN", "j-bad", teamVersion(team), team, "00000000-0000-4000-8000-000000000000").status);
        CityGameParticipationService.Outcome stale = command(A1, "LOCK", "l-stale", 0, team, null);
        assertEquals("VERSION_CONFLICT", stale.errorCode);
        assertEquals(200, command(A1, "LOCK", "l-1", teamVersion(team), team, null).status);
        String invite = (String) mapper.selectTeam(team).get("inviteCode");
        assertEquals("CITY_ROSTER_LOCKED", command(A2, "JOIN", "j-late", teamVersion(team), team, invite).errorCode);
        // Receipt by original requestId, private to its member.
        assertEquals(409, participation.receipt(A1, "l-stale").status);
        assertEquals(404, assertThrows(CityGameException.class, () -> participation.receipt(A2, "l-1")).getStatus());
        // Exit after lock keeps the seat; resume restores presence.
        assertEquals(200, command(A1, "EXIT", "e-1", teamVersion(team), team, null).status);
        assertEquals("PENDING", reads.teamState(A1, REGION).get("status"));
        assertEquals(200, command(A1, "RESUME", "r-1", teamVersion(team), team, null).status);
        assertEquals(200, command(A1, "CONFIRM", "f-1", teamVersion(team), team, null).status);
        assertEquals("JOINED", reads.teamState(A1, REGION).get("status"));
        Map<String, String> rq = new HashMap<>();
        execute("UPDATE city_game_board SET lifecycle = 'FROZEN'");
        Map<String, Object> current = reads.current(A1, REGION);
        @SuppressWarnings("unchecked")
        Map<String, Object> board = (Map<String, Object>) current.get("board");
        rq.put("gameId", "city-game"); rq.put("regionId", REGION); rq.put("boardId", BOARD); rq.put("seasonId", "s2026w");
        rq.put("rulesReleaseId", "rel-1"); rq.put("rulesHash", HASH); rq.put("revision", String.valueOf(board.get("revision")));
        rq.put("membership", "JOINED"); rq.put("participationId", (String) mapper.selectTeam(team).get("sessionId"));
        rq.put("membershipVersion", String.valueOf(teamVersion(team)));
        Map<String, Object> rules = reads.playerRules(A1, rq);
        assertEquals("CITY_PLAYER_RULES_V1", rules.get("contract"));
        assertEquals(14, ((List<?>) rules.get("sections")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> eligibility = (Map<String, Object>) rules.get("eligibility");
        assertEquals("MEMBERSHIP_CONFIRMED", eligibility.get("status"));
        // Frozen board: bounded snapshot answers, actions are blocked.
        Map<String, String> sq = new HashMap<>();
        sq.put("regionId", REGION); sq.put("boardId", BOARD); sq.put("seasonId", "s2026w"); sq.put("rulesReleaseId", "rel-1");
        sq.put("revision", "3"); sq.put("south", "31.2"); sq.put("west", "121.4"); sq.put("north", "31.3"); sq.put("east", "121.5");
        sq.put("datum", "GCJ02"); sq.put("zoom", "14"); sq.put("layers", "MERCHANT,POI"); sq.put("limit", "50");
        Map<String, Object> snap = reads.stateSnapshot(A1, sq);
        assertEquals(4, ((List<?>) snap.get("points")).size());
        assertEquals(false, snap.get("grantsAction"));
        sq.put("limit", "2");
        assertEquals("CITY_VIEWPORT_TOO_DENSE", assertThrows(CityGameException.class, () -> reads.stateSnapshot(A1, sq)).getErrorCode());
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) s.execute(statement);
        }
    }

    private DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
