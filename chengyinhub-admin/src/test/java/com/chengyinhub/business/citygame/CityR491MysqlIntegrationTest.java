package com.chengyinhub.business.citygame;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * r491 CITY surfaces on real MySQL 8 with both gaps A migrations and the real MyBatis XML: recorded-only budget
 * rolls, store-attested arrival → onsite task → self-funded acquisition (points, budget and ownership on the one
 * board), neutral tasks, history, merchant intents, value visits, the ordered map stream and the lifecycle rule.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_CITY_GAME_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CityR491MysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("city_r491_it");
    private static final String BOARD = "board-sh-1", REGION = "shanghai", HASH = "a".repeat(64);
    private static final long A1 = 7001, STAFF = 7201, MANAGER = 7202, MERCHANT = 501;

    private DataSource dataSource;
    private CityGameMapper mapper;
    private JdbcTemplate jdbc;
    private final AtomicLong clock = new AtomicLong();
    private final Deque<Integer> tickets = new ArrayDeque<>();
    private CityGameReadService reads;
    private CityGameParticipationService participation;
    private CityGameActionService engine;
    private CityActionBudgetService budget;
    private CityArrivalService arrival;
    private CityTaskService tasks;
    private CityAcquisitionService acquisition;
    private CityMerchantValueService merchantValue;
    private CityOrderedMapService orderedMap;

    private final IMerchantAccessService merchants = new IMerchantAccessService() {
        public MerchantAccessContext resolve(Long member) { return require(member, null); }
        public MerchantAccessContext require(Long member, String permission) {
            if (member == STAFF) return new MerchantAccessContext(STAFF, MERCHANT, 7300L, "MERCHANT_CHECKIN");
            if (member == MANAGER) return new MerchantAccessContext(MANAGER, MERCHANT, MANAGER, "MERCHANT_OWNER");
            throw new ServiceException("无商家权限", 403);
        }
    };

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        Configuration configuration = new Configuration(new Environment("city-r491-it", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(CityGameMapper.class);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CityGameMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/business/CityGameMapper.xml", configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(CityGameMapper.class);
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        reads = new CityGameReadService(mapper, clock::get);
        participation = transactional(new CityGameParticipationService(mapper), tx);
        engine = transactional(new CityGameActionService(mapper, n -> {
            Integer t = tickets.poll();
            return t == null ? 0 : t;
        }, clock::get), tx);
        CityR491Store store = new CityR491Store(mapper, jdbc, tx, clock::get);
        budget = new CityActionBudgetService(store, engine);
        arrival = new CityArrivalService(store, merchants);
        tasks = new CityTaskService(store, arrival);
        acquisition = new CityAcquisitionService(store, engine, budget);
        merchantValue = new CityMerchantValueService(store, merchants);
        orderedMap = new CityOrderedMapService(store, reads, tx);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager tx) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (Map<String, Object> t : new JdbcTemplate(dataSource).queryForList("SHOW TABLES")) s.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        }
        String baseline = Files.readString(Paths.get("../sql/base_schema_20260818.sql"));
        execute(table(baseline, "ums_member"), table(baseline, "ums_member_points_detail"), table(baseline, "roam_poi"));
        CityGameMysqlIntegrationTest.migrate(dataSource, "sql/migration_20261113_gaps_a_city_game.sql");
        CityGameMysqlIntegrationTest.migrate(dataSource, "sql/migration_20261114_gaps_a_city_r491.sql");
        execute("INSERT INTO ums_member(id, role, status, del_flag, point) VALUES (7001,'player',1,0,100),(7201,'merchant',1,0,0),(7202,'merchant',1,0,0)");
        execute("INSERT INTO city_game_board(board_id, game_id, region_id, season_id, rules_release_id, release_id, rules_hash, rule_version,"
                + " lifecycle, title, revision, board_version, event_sequence, dice_id, dice_faces, dice_weights, actions_per_round, max_rounds,"
                + " roll_cost, acquisition_cost, task_cost, service_cost, challenge_cost,"
                + " acquire_points, challenge_points, holding_yield, goal_score, holding_limit, protection_seconds, quote_valid_seconds, term_seconds,"
                + " min_members, max_members, rules_id, rules_text, challenge_rule_id, protection_text, safety_statement, valid_from,"
                + " map_datum, map_south, map_west, map_north, map_east)"
                + " VALUES ('" + BOARD + "','city-game','" + REGION + "','s2026w','rel-1',11,'" + HASH + "','v1','OPEN','外滩城市局',3,3,0,"
                + " 'd6','[1,2,2]','[1,1,1]',4,1,1,2,0,0,0,30,20,2,1,2,600,300,86400,1,4,'rules-1','规则正文','ch-1','保护期说明','安全声明',"
                + " '2026-11-01 00:00:00','GCJ02',31.2,121.4,31.3,121.5)");
        execute("INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, domain, collected_at, merchant_id, place_id) VALUES"
                + " ('" + BOARD + "','pt-0','广场',31.21,121.41,'POI','2026-11-01',NULL,9002),"
                + " ('" + BOARD + "','pt-1','咖啡馆',31.22,121.42,'MERCHANT','2026-11-01',501,9001),"
                + " ('" + BOARD + "','pt-2','书店',31.23,121.43,'MERCHANT','2026-11-01',NULL,NULL)");
        execute("INSERT INTO city_game_property(board_id, property_id, point_id, track_index, role) VALUES"
                + " ('" + BOARD + "','goal-0','pt-0',0,'NEUTRAL'),('" + BOARD + "','prop-1','pt-1',1,'OPERABLE'),('" + BOARD + "','prop-2','pt-2',2,'OPERABLE')");
        execute("INSERT INTO city_task_definition(board_id, task_id, access, point_id, title, question, options_json, answer_option_id, max_attempts) VALUES"
                + " ('" + BOARD + "','n-1','REMOTE_NEUTRAL',NULL,'外滩有几座钟楼','选一个','[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]','b',2),"
                + " ('" + BOARD + "','o-1','ONSITE_ZERO_EFFECT','pt-1','店里的招牌','选一个','[{\"id\":\"a\",\"label\":\"拿铁\"},{\"id\":\"b\",\"label\":\"手冲\"}]','b',3)");
        execute("INSERT INTO roam_poi(id, name, type, lat, lng, status, city_code) VALUES (9001,'咖啡馆',2,31.220000,121.420000,1,'310000')");
        clock.set(java.sql.Timestamp.valueOf("2026-11-13 10:00:00").getTime());
        tickets.clear();
        formTeam();
    }

    private String team;

    private void formTeam() {
        assertEquals(200, participate("CREATE", "c-1", 0, null).status);
        team = (String) mapper.selectParticipation(BOARD, A1).get("teamId");
        assertEquals(200, participate("LOCK", "l-1", version(), team).status);
        assertEquals(200, participate("CONFIRM", "f-1", version(), team).status);
        assertNotNull(mapper.selectOpenRound(BOARD, team));
    }

    private long version() { return ((Number) mapper.selectTeam(team).get("version")).longValue(); }

    private CityGameParticipationService.Outcome participate(String kind, String requestId, long version, String teamId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("requestId", requestId);
        body.put("regionId", REGION);
        body.put("releaseId", 11L);
        body.put("contentHash", HASH);
        body.put("expectedVersion", version);
        if (teamId != null) body.put("teamId", teamId);
        return participation.command(A1, body);
    }

    private static CityR491Outcomes.View view(Object outcome) { return CityR491Outcomes.view(outcome); }

    private static String upper() { return UUID.randomUUID().toString().toUpperCase(); }

    private Map<String, Object> roll(String requestId, long expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "ROLL");
        body.put("requestId", requestId);
        body.put("regionId", REGION);
        body.put("teamId", team);
        body.put("contentHash", HASH);
        body.put("expectedTeamVersion", version());
        body.put("releaseId", 11L);
        body.put("expectedVersion", expected);
        return body;
    }

    @Test
    void budgetRollIsRecordedOnlyReplaysAndSettlesTheRoundIntoAFrozenBoard() {
        Map<String, Object> state = budget.state(A1, REGION);
        assertEquals("AVAILABLE", state.get("status"));
        assertEquals(0L, state.get("version"));
        tickets.add(2);
        CityR491Outcomes.View first = view(budget.command(A1, roll("roll-1", 0)));
        assertEquals(200, first.status);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) first.data.get("result");
        assertEquals(2, ((Number) result.get("faceIndex")).intValue());
        assertEquals(2L, ((Number) result.get("faceValue")).longValue());
        assertEquals(4, ((Number) result.get("beforeUnits")).intValue());
        assertEquals(3, ((Number) result.get("afterUnits")).intValue());
        assertEquals("NOT_EXECUTED", result.get("effectsStatus"));
        assertEquals(0, ((Number) mapper.selectTeam(team).get("trackPosition")).intValue(), "a budget roll never moves the token");
        // Same request: the stored reply, not a second roll. Same id, other body: refused.
        CityR491Outcomes.View replay = view(budget.command(A1, roll("roll-1", 0)));
        assertEquals(first.data, replay.data);
        assertEquals(1, mapper.selectRoundRolls(BOARD, team, 1).size());
        assertEquals("CITY_REQUEST_REUSED", assertThrows(CityGameException.class, () -> budget.command(A1, roll("roll-1", 5))).getErrorCode());
        assertEquals(200, view(budget.receipt(A1, "roll-1")).status);
        // A stale budget version is a stored 409 receipt.
        CityR491Outcomes.View stale = view(budget.command(A1, roll("roll-stale", 0)));
        assertEquals(409, stale.status);
        assertEquals("VERSION_CONFLICT", stale.errorCode);
        Map<String, Object> after = budget.state(A1, REGION);
        assertEquals(1L, after.get("version"));
        assertEquals(1, after.get("usedUnits"));
        assertEquals(1, ((List<?>) after.get("recentResults")).size());
        // Spend the rest: max_rounds = 1, so the last settlement freezes the board.
        for (int i = 2; i <= 4; i++) assertEquals(200, view(budget.command(A1, roll("roll-" + i, i - 1))).status);
        assertEquals("FROZEN", mapper.selectBoard(BOARD).get("lifecycle"));
        assertEquals("EXHAUSTED", budget.state(A1, REGION).get("status"));
        CityR491Outcomes.View frozen = view(budget.command(A1, roll("roll-5", 4)));
        assertEquals("BOARD_NOT_OPEN", frozen.errorCode);
        // Reads stay available on the frozen board (one lifecycle for every route).
        assertEquals("AVAILABLE", reads.current(A1, REGION).get("status"));
    }

    @Test
    void storeAttestedArrivalUnlocksOnsiteTaskThenSelfFundedAcquisitionOnTheBoard() {
        Map<String, Object> state = arrival.state(A1, REGION);
        assertEquals("READY", state.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) state.get("points");
        assertEquals(1, points.size(), "only points with a merchant and a public POI");
        assertEquals(true, points.get(0).get("canIssue"));

        // Ordered map stream opened before the acquisition.
        Map<String, String> q = viewport();
        q.put("sync", "TRANSACTIONAL_EVENTS_V1");
        Map<String, Object> snap = orderedMap.snapshot(A1, q);
        assertEquals("SNAPSHOT", snap.get("kind"));
        String cursor = (String) snap.get("cursor");

        String issueId = upper();
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("requestId", issueId); issue.put("kind", "ISSUE"); issue.put("regionId", REGION); issue.put("teamId", team);
        issue.put("pointId", "pt-1"); issue.put("releaseId", 11L); issue.put("contentHash", HASH); issue.put("expectedTeamVersion", version());
        issue.put("expectedVersion", 0L);
        CityR491Outcomes.View issued = view(arrival.command(A1, issue));
        assertEquals(200, issued.status);
        String code = (String) issued.data.get("displayCode");
        @SuppressWarnings("unchecked")
        String challengeId = (String) ((Map<String, Object>) issued.data.get("challenge")).get("challengeId");
        assertEquals(code, view(arrival.receipt(A1, issueId)).data.get("displayCode"));

        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("requestId", upper()); submit.put("kind", "SUBMIT"); submit.put("challengeId", challengeId); submit.put("expectedVersion", 1L);
        submit.put("displayCode", code);
        assertEquals(200, view(arrival.command(A1, submit)).status);
        assertEquals(1, ((List<?>) arrival.merchant(STAFF).get("pending")).size());
        assertEquals(403, assertThrows(CityGameException.class, () -> arrival.merchant(A1)).getStatus());

        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("requestId", upper()); wrong.put("kind", "CONFIRM"); wrong.put("challengeId", challengeId); wrong.put("expectedVersion", 2L);
        wrong.put("displayCode", "0".repeat(32)); wrong.put("reason", "现场核对");
        assertEquals("CODE_MISMATCH", view(arrival.command(STAFF, wrong)).errorCode);
        Map<String, Object> confirm = new LinkedHashMap<>(wrong);
        confirm.put("requestId", upper()); confirm.put("displayCode", code);
        CityR491Outcomes.View confirmed = view(arrival.command(STAFF, confirm));
        assertEquals(200, confirmed.status);
        @SuppressWarnings("unchecked")
        String evidenceId = (String) ((Map<String, Object>) confirmed.data.get("evidence")).get("evidenceId");

        // Onsite task: accept with the evidence, one wrong answer, then the right one.
        Map<String, Object> onsite = tasks.state(CityTaskService.ONSITE, A1, REGION);
        assertEquals("AVAILABLE", onsite.get("status"));
        String accept = upper();
        CityR491Outcomes.View accepted = view(tasks.command(CityTaskService.ONSITE, A1, taskBody(accept, "ACCEPT", "o-1", evidenceId, 0, null, null)));
        assertEquals(200, accepted.status);
        String instance = (String) jdbc.queryForObject("SELECT instance_id FROM city_task_instance WHERE task_id = 'o-1'", String.class);
        assertEquals(200, view(tasks.command(CityTaskService.ONSITE, A1, taskBody(upper(), "SUBMIT", "o-1", evidenceId, 1, instance, "a"))).status);
        CityR491Outcomes.View passed = view(tasks.command(CityTaskService.ONSITE, A1, taskBody(upper(), "SUBMIT", "o-1", evidenceId, 2, instance, "b")));
        assertNotNull(passed.data.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> passResult = (Map<String, Object>) passed.data.get("result");
        assertEquals("PASSED", passResult.get("outcome"));
        Map<String, Object> history = tasks.history(A1, REGION, null);
        assertEquals(1, ((List<?>) history.get("items")).size());
        assertEquals(2, ((List<?>) tasks.historyDetail(A1, REGION, instance).get("attempts")).size());

        // Acquisition: quote has no effect; confirm charges points 100 -> 70, spends 2 units, makes the team hold pt-1.
        Map<String, Object> acq = acquisition.state(A1, REGION);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) acq.get("candidates");
        assertEquals("QUOTE_AVAILABLE", candidates.get(0).get("eligibility"));
        Map<String, Object> quoteBody = new LinkedHashMap<>();
        quoteBody.put("requestId", upper()); quoteBody.put("kind", "QUOTE"); quoteBody.put("regionId", REGION); quoteBody.put("instanceId", instance);
        CityR491Outcomes.View quoted = view(acquisition.command(A1, quoteBody));
        assertEquals(200, quoted.status);
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) quoted.data.get("quote");
        assertEquals(100L, mapper.selectMemberPoints(A1));
        Map<String, Object> confirmBody = new LinkedHashMap<>();
        confirmBody.put("requestId", quote.get("confirmRequestId")); confirmBody.put("kind", "CONFIRM"); confirmBody.put("regionId", REGION);
        confirmBody.put("quoteId", quote.get("quoteId")); confirmBody.put("quoteHash", quote.get("quoteHash")); confirmBody.put("confirmed", true);
        CityR491Outcomes.View bought = view(acquisition.command(A1, confirmBody));
        assertEquals(200, bought.status, String.valueOf(bought.errorCode));
        assertEquals(70L, mapper.selectMemberPoints(A1));
        assertEquals(team, mapper.selectPropertyForUpdate(BOARD, "prop-1").get("holderTeamId"));
        assertEquals(2, ((Number) mapper.selectOpenRound(BOARD, team).get("actionsRemaining")).intValue());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id = 7001", Long.class));
        assertEquals(1, ((List<?>) acquisition.titlesRead(A1, REGION).get("titles")).size());
        assertEquals(1L, budget.state(A1, REGION).get("version"), "one acquisition counts in the budget version");
        // A second confirm of the same quote replays; the points are charged once.
        assertEquals(bought.data, view(acquisition.command(A1, confirmBody)).data);
        assertEquals(70L, mapper.selectMemberPoints(A1));

        // The ordered stream now carries exactly one SLOT replacement for pt-1, owned by our team.
        Map<String, String> d = viewport();
        d.put("sync", "TRANSACTIONAL_EVENTS_V1");
        d.put("cursor", cursor);
        Map<String, Object> deltas = orderedMap.deltas(A1, d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batches = (List<Map<String, Object>>) deltas.get("batches");
        assertEquals(1, batches.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> event = ((List<Map<String, Object>>) batches.get(0).get("events")).get(0);
        assertEquals("SLOT", event.get("component"));
        @SuppressWarnings("unchecked")
        Map<String, Object> point = (Map<String, Object>) event.get("point");
        assertEquals("OUR_TEAM", point.get("ownership"));
        assertEquals(1L, deltas.get("sequence"));
    }

    @Test
    void neutralTasksMerchantIntentsValueVisitsAndWalkingTarget() {
        String accept = upper();
        Map<String, Object> body = taskBody(accept, "ACCEPT", "n-1", null, 0, null, null);
        CityR491Outcomes.View accepted = view(tasks.command(CityTaskService.NEUTRAL, A1, body));
        assertEquals(200, accepted.status);
        String instance = (String) jdbc.queryForObject("SELECT instance_id FROM city_task_instance WHERE task_id = 'n-1'", String.class);
        assertEquals(409, view(tasks.command(CityTaskService.NEUTRAL, A1, taskBody(upper(), "ACCEPT", "n-1", null, 0, null, null))).status);
        assertEquals(200, view(tasks.command(CityTaskService.NEUTRAL, A1, taskBody(upper(), "SUBMIT", "n-1", null, 1, instance, "a"))).status);
        CityR491Outcomes.View last = view(tasks.command(CityTaskService.NEUTRAL, A1, taskBody(upper(), "SUBMIT", "n-1", null, 2, instance, "a")));
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) last.data.get("task");
        assertEquals("EXHAUSTED", task.get("status"));
        assertEquals(100L, mapper.selectMemberPoints(A1), "tasks never move points");

        Map<String, Object> availability = merchantValue.availability(MANAGER, REGION);
        assertEquals("AVAILABLE", availability.get("availability"));
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("requestId", upper()); intent.put("expectedVersion", 0L); intent.put("regionId", REGION); intent.put("kind", "PAUSE_MERCHANT");
        intent.put("reason", "店内装修"); intent.put("approvalHash", availability.get("approvalHash"));
        CityR491Outcomes.View paused = view(merchantValue.intent(MANAGER, intent));
        assertEquals("IN_REVIEW", paused.data.get("status"));
        assertEquals(false, paused.data.get("runtimeApplied"));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT availability FROM city_game_point WHERE point_id = 'pt-1'", String.class), "review first, no runtime change");
        Map<String, Object> again = new LinkedHashMap<>(intent);
        again.put("requestId", upper()); again.put("expectedVersion", 1L);
        assertEquals("PENDING_REVIEW", view(merchantValue.intent(MANAGER, again)).errorCode);

        Map<String, Object> target = merchantValue.walkingTarget(A1, "9001");
        assertEquals("wgs84", target.get("datum"));
        assertTrue(((String) target.get("authorityRevision")).startsWith("city:9001:"));
        assertNotEquals(121.42, (Double) target.get("longitude"), 0.0000001, "GCJ-02 converted once to WGS84");
        assertEquals(404, assertThrows(CityGameException.class, () -> merchantValue.walkingTarget(A1, "9999")).getStatus());

        Map<String, String> q = viewport();
        q.remove("limit");
        q.put("layers", "MERCHANT,PLATFORM_VIRTUAL,POI");
        Map<String, Object> page = reads.pointsQuery(A1, q);
        assertEquals("CITY_BOUNDED_MAP_V1", page.get("mapContract"));
        assertEquals(true, page.get("complete"));
        Map<String, String> sq = new HashMap<>(q);
        sq.put("mapWatermark", (String) page.get("watermark"));
        sq.put("mapPage", "1");
        Map<String, Object> services = merchantValue.services(A1, sq, reads);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) services.get("services");
        assertEquals(1, rows.size());
        assertEquals(9002L, rows.get(0).get("poiId"));
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("requestId", "visit-1"); begin.put("page", sq); begin.put("pointId", "pt-0"); begin.put("poiId", 9002L);
        Map<String, Object> bound = merchantValue.begin(A1, begin, reads);
        assertEquals(bound, merchantValue.begin(A1, begin, reads), "same request replays");
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("visitId", bound.get("visitId")); feedback.put("kind", "SERVICE_RATING"); feedback.put("rating", 80L);
        Map<String, Object> visit = merchantValue.feedback(A1, feedback);
        assertEquals(true, visit.get("closed"));
        assertEquals("UNAVAILABLE", visit.get("netPoints"));
    }

    private Map<String, Object> taskBody(String requestId, String kind, String taskId, String evidenceId, long expected, String instanceId, String optionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("regionId", REGION);
        body.put("kind", kind);
        body.put("releaseId", 11L);
        body.put("contentHash", HASH);
        body.put("teamId", team);
        body.put("expectedTeamVersion", version());
        body.put("taskId", taskId);
        if (evidenceId != null) body.put("evidenceId", evidenceId);
        body.put("expectedVersion", expected);
        if (instanceId != null) {
            body.put("instanceId", instanceId);
            body.put("optionId", optionId);
        }
        return body;
    }

    private Map<String, String> viewport() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("regionId", REGION); q.put("boardId", BOARD); q.put("seasonId", "s2026w"); q.put("rulesReleaseId", "rel-1");
        q.put("revision", "3"); q.put("south", "31.2"); q.put("west", "121.4"); q.put("north", "31.3"); q.put("east", "121.5");
        q.put("datum", "GCJ02"); q.put("zoom", "14"); q.put("layers", "MERCHANT,POI"); q.put("limit", "50");
        return q;
    }

    private static String table(String baseline, String name) {
        Matcher m = Pattern.compile("CREATE TABLE `" + name + "` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(baseline);
        assertTrue(m.find(), "baseline has " + name);
        return m.group();
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
