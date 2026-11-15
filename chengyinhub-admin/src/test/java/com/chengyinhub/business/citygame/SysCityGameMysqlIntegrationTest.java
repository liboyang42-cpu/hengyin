package com.chengyinhub.business.citygame;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full MySQL 8 integration test for SYS-citygame:
 * admin board authoring (draft -> preview -> publish -> freeze), unified dice roll path + audit,
 * merchant-ops approval (runtimeApplied=true), fairness metrics, and merged CITY+Roam territory claim,
 * protection window, check-in points revenue, anti-cheat block, and leaderboard.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_CITY_GAME_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysCityGameMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("sys_city_game_it");
    private static final long P1 = 8001, P2 = 8002, P3 = 8003, STAFF = 8101, MANAGER = 8201, MERCHANT = 601L;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private CityGameMapper mapper;
    private final AtomicLong clock = new AtomicLong();
    private final Deque<Integer> tickets = new ArrayDeque<>();

    private CityGameParticipationService participation;
    private CityGameActionService actions;
    private CityActionBudgetService budget;
    private CityArrivalService arrival;
    private CityMerchantValueService merchantValue;
    private CityFairnessService fairness;
    private RoamTerritoryService territories;
    private CityBoardAdminService boardAdmin;

    private final IMerchantAccessService merchants = new IMerchantAccessService() {
        public MerchantAccessContext resolve(Long member) { return require(member, null); }
        public MerchantAccessContext require(Long member, String permission) {
            if (member == STAFF) return new MerchantAccessContext(STAFF, MERCHANT, 7300L, "MERCHANT_CHECKIN");
            if (member == MANAGER) return new MerchantAccessContext(MANAGER, MERCHANT, MANAGER, "MERCHANT_OWNER");
            throw new ServiceException("无商家权限", 403);
        }
    };

    @BeforeAll
    void init() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        Configuration cfg = new Configuration(new Environment("sys-city-it", new SpringManagedTransactionFactory(), dataSource));
        cfg.addMapper(CityGameMapper.class);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CityGameMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/CityGameMapper.xml", cfg.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(CityGameMapper.class);
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        CityGameReadService reads = new CityGameReadService(mapper, clock::get);
        participation = transactional(new CityGameParticipationService(mapper), tx);
        actions = transactional(new CityGameActionService(mapper, n -> {
            Integer t = tickets.poll();
            return t == null ? 0 : t;
        }, clock::get), tx);
        CityR491Store store = new CityR491Store(mapper, jdbc, tx, clock::get);
        budget = new CityActionBudgetService(store, actions);
        territories = transactional(new RoamTerritoryService(jdbc, mapper, null, clock::get), tx);
        arrival = new CityArrivalService(store, merchants);
        arrival.setTerritoryService(territories);
        merchantValue = new CityMerchantValueService(store, merchants);
        fairness = new CityFairnessService(mapper, clock::get);
        boardAdmin = transactional(new CityBoardAdminService(jdbc, mapper, fairness, territories, clock::get), tx);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager tx) {
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) pf.getProxy();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) {
                s.execute("DROP TABLE `" + t.values().iterator().next() + "`");
            }
        }
        String baseline = Files.readString(Paths.get("../sql/base_schema_20260818.sql"));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(table(baseline, "ums_member"));
            s.execute(table(baseline, "ums_member_points_detail"));
            s.execute(table(baseline, "roam_poi"));
            s.execute(table(baseline, "sys_menu"));
            s.execute(table(baseline, "mms_merchant"));
        }
        CityGameMysqlIntegrationTest.migrate(dataSource, "sql/migration_20261113_gaps_a_city_game.sql");
        CityGameMysqlIntegrationTest.migrate(dataSource, "sql/migration_20261114_gaps_a_city_r491.sql");
        CityGameMysqlIntegrationTest.migrate(dataSource, "sql/migration_20261123_sys_citygame.sql");

        jdbc.update("INSERT INTO ums_member(id, role, nickname, status, del_flag, point) VALUES "
                + "(8001,'player','Alice',1,0,100),(8002,'player','Bob',1,0,100),(8003,'player','Carol',1,0,100),"
                + "(8101,'merchant','Staff',1,0,0),(8201,'merchant','Manager',1,0,0)");
        jdbc.update("INSERT INTO roam_poi(id, name, type, lat, lng, radius_m, status, city_code) VALUES "
                + "(9501,'外滩零号广场',2,31.230400,121.473700,80,1,'310000'),"
                + "(9502,'苏州河咖啡屋',2,31.235000,121.475000,80,1,'310000')");
        clock.set(java.sql.Timestamp.valueOf("2026-11-15 10:00:00").getTime());
        tickets.clear();
    }

    @Test
    void fullBoardAuthoringUnifiedDiceMerchantOpsAndMergedTerritoryFlow() {
        // 1. Dice probability preview + admin draft -> publish
        Map<String, Object> preview = boardAdmin.previewDice(Map.of("diceFaces", "[1,2,3]", "diceWeights", "[1,2,5]"));
        assertEquals(8, preview.get("totalWeight"));

        String boardId = "board-sys-it";
        String regionId = "sh-sys-it";
        String seasonId = "season-2026-w1";
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("boardId", boardId);
        draftBody.put("regionId", regionId);
        draftBody.put("seasonId", seasonId);
        draftBody.put("title", "黄浦江实景大富翁");
        draftBody.put("diceFaces", "[1,2,3]");
        draftBody.put("diceWeights", "[1,2,5]");
        draftBody.put("actionsPerRound", 5);
        draftBody.put("maxRounds", 2);
        draftBody.put("taskCost", 0);
        draftBody.put("serviceCost", 0);
        draftBody.put("challengeCost", 0);
        draftBody.put("protectionSeconds", 3600);
        draftBody.put("fairnessHorizonSeconds", 0);
        draftBody.put("fairnessDeltaSeconds", 86400);
        draftBody.put("spaces", List.of(
                Map.of("pointId", "pt-0", "propertyId", "prop-0", "title", "起点广场", "latitude", 31.2300, "longitude", 121.4730, "role", "NEUTRAL", "tollPoints", 0),
                Map.of("pointId", "pt-1", "propertyId", "prop-1", "title", "外滩零号广场", "latitude", 31.2304, "longitude", 121.4737, "poiId", 9501L, "merchantId", MERCHANT, "role", "OPERABLE", "tollPoints", 5),
                Map.of("pointId", "pt-2", "propertyId", "prop-2", "title", "苏州河咖啡屋", "latitude", 31.2350, "longitude", 121.4750, "poiId", 9502L, "role", "OPERABLE", "tollPoints", 8)
        ));
        draftBody.put("tasks", List.of(
                Map.of("taskId", "t-1", "access", "REMOTE_NEUTRAL", "title", "打卡题", "question", "选一",
                        "options", List.of(Map.of("id", "A", "label", "对"), Map.of("id", "B", "label", "错")),
                        "answerOptionId", "A", "maxAttempts", 2)
        ));

        Map<String, Object> draft = boardAdmin.saveDraft(1L, draftBody);
        assertEquals("DRAFT", draft.get("draftStatus"));
        Map<String, Object> published = boardAdmin.publishBoard(1L, boardId);
        assertEquals("OPEN", published.get("lifecycle"));
        assertNotNull(published.get("rulesHash"));
        long releaseId = ((Number) published.get("releaseId")).longValue();
        String rulesHash = (String) published.get("rulesHash");

        // 2. Team confirmation + unified dice rolls from all 3 entrypoints
        CityGameParticipationService.Outcome created = participation.command(P1, Map.of(
                "kind", "CREATE", "requestId", "c-sys-1", "regionId", regionId,
                "releaseId", releaseId, "contentHash", rulesHash, "expectedVersion", 0L));
        assertEquals(200, created.status);
        String teamId = (String) mapper.selectParticipation(boardId, P1).get("teamId");
        long tVer = ((Number) mapper.selectTeam(teamId).get("version")).longValue();
        assertEquals(200, participation.command(P1, Map.of(
                "kind", "LOCK", "requestId", "l-sys-1", "regionId", regionId,
                "releaseId", releaseId, "contentHash", rulesHash, "expectedVersion", tVer, "teamId", teamId)).status);
        tVer = ((Number) mapper.selectTeam(teamId).get("version")).longValue();
        assertEquals(200, participation.command(P1, Map.of(
                "kind", "CONFIRM", "requestId", "f-sys-1", "regionId", regionId,
                "releaseId", releaseId, "contentHash", rulesHash, "expectedVersion", tVer, "teamId", teamId)).status);
        String sessionId = (String) mapper.selectTeam(teamId).get("sessionId");

        // Path A: /game/action ROLL_ROUND
        tickets.add(0); // face=1 -> pos 0 -> 1
        Map<String, Object> snap1 = actions.snapshot(P1, boardId);
        @SuppressWarnings("unchecked")
        long boardVer = ((Number) snap1.get("boardVersion")).longValue();
        Map<String, Object> actRes = actions.submit(P1, Map.of(
                "action", "ROLL_ROUND", "boardId", boardId, "participationId", sessionId,
                "targetId", boardId, "requestId", "r-act-1", "roundSeq", 1L, "expectedVersion", boardVer));
        assertEquals("APPLIED", actRes.get("decision"));
        assertEquals(1, ((Number) mapper.selectTeam(teamId).get("trackPosition")).intValue());

        // Path B: /action-budget/command ROLL
        tickets.add(1); // totalWeight=8, ticket 1 -> faceIndex=1 -> face=2 -> pos 1 -> 0 (mod 3)
        tVer = ((Number) mapper.selectTeam(teamId).get("version")).longValue();
        Object bOut = budget.command(P1, Map.of(
                "kind", "ROLL", "requestId", "r-bud-1", "regionId", regionId, "teamId", teamId,
                "contentHash", rulesHash, "expectedTeamVersion", tVer, "releaseId", releaseId, "expectedVersion", 1L));
        assertEquals(200, CityR491Outcomes.view(bOut).status);
        assertEquals(0, ((Number) mapper.selectTeam(teamId).get("trackPosition")).intValue());

        // Path C: unified /api/city-game/dice/roll + replay + /dice/audit
        tickets.add(0); // ticket 0 -> face=1 -> pos 0 -> 1
        Map<String, Object> uRoll = actions.unifiedDiceRoll(P1, Map.of("requestId", "r-uni-1", "boardId", boardId));
        assertEquals(false, uRoll.get("idempotentReplay"));
        assertEquals(1, ((Number) uRoll.get("newPosition")).intValue());
        Map<String, Object> uReplay = actions.unifiedDiceRoll(P1, Map.of("requestId", "r-uni-1", "boardId", boardId));
        assertEquals(true, uReplay.get("idempotentReplay"));

        Map<String, Object> audit = actions.diceAudit(P1, boardId, null, 20);
        assertEquals(3, ((List<?>) audit.get("rolls")).size());

        // 3. Merchant-ops review -> runtimeApplied=true
        Map<String, Object> avail = merchantValue.availability(MANAGER, regionId);
        merchantValue.intent(MANAGER, Map.of(
                "requestId", UUID.randomUUID().toString().toUpperCase(),
                "expectedVersion", 0L, "regionId", regionId, "kind", "PAUSE_MERCHANT",
                "reason", "设备维护", "approvalHash", avail.get("approvalHash")));
        Map<String, Object> reviewed = boardAdmin.reviewMerchantOp(1L, Map.of(
                "merchantId", MERCHANT, "regionId", regionId, "decision", "APPROVE", "reason", "核准暂停"));
        assertEquals("APPLIED", reviewed.get("status"));
        assertEquals(true, reviewed.get("runtimeApplied"));
        assertEquals(true, merchantValue.availability(MANAGER, regionId).get("runtimeApplied"));

        // Restore merchant so pt-1 is active again for arrival test
        Map<String, Object> pausedSnap = merchantValue.availability(MANAGER, regionId);
        merchantValue.intent(MANAGER, Map.of(
                "requestId", UUID.randomUUID().toString().toUpperCase(),
                "expectedVersion", pausedSnap.get("version"), "regionId", regionId, "kind", "RESTORE",
                "reason", "恢复营业", "approvalHash", pausedSnap.get("approvalHash")));
        boardAdmin.reviewMerchantOp(1L, Map.of(
                "merchantId", MERCHANT, "regionId", regionId, "decision", "APPROVE", "reason", "核准恢复"));

        // 4. Merged Territory claim via geotagged photo + GPS check-in -> updates city_game_property
        Map<String, Object> claimRes = territories.claim(P1, Map.of(
                "requestId", "claim-1", "poiId", 9501L, "lat", 31.2304, "lng", 121.4737,
                "accuracyM", 12.0, "photoUrl", "https://example.com/photo-claim.jpg"));
        String territoryId = (String) claimRes.get("territoryId");
        assertEquals(boardId, claimRes.get("boardId"));
        assertEquals("prop-1", claimRes.get("propertyId"));
        assertEquals("POINTS", claimRes.get("revenueMode"));
        assertEquals("PROTECTED", mapper.selectPropertyForUpdate(boardId, "prop-1").get("status"));

        // Protection window blocks P2 takeover
        assertEquals("TERRITORY_PROTECTED", assertThrows(CityGameException.class, () -> territories.claim(P2, Map.of(
                "requestId", "claim-steal", "poiId", 9501L, "lat", 31.2304, "lng", 121.4737,
                "accuracyM", 12.0, "photoUrl", "https://example.com/photo2.jpg"))).getErrorCode());

        // Geofence blocks far-away claim on 9502
        assertEquals("OUT_OF_GEOFENCE", assertThrows(CityGameException.class, () -> territories.claim(P2, Map.of(
                "requestId", "claim-far", "poiId", 9502L, "lat", 31.2400, "lng", 121.4900,
                "accuracyM", 12.0, "photoUrl", "https://example.com/photo-far.jpg"))).getErrorCode());

        // P2 valid check-in on 9501 -> earns owner P1 POINTS (never money)
        Map<String, Object> visitRes = territories.checkin(P2, Map.of(
                "requestId", "visit-p2-1", "territoryId", territoryId, "lat", 31.23041, "lng", 121.47371, "accuracyM", 10.0));
        assertEquals(5L, ((Number) visitRes.get("ownerRevenuePoints")).longValue());
        assertEquals("POINTS", visitRes.get("revenueMode"));
        assertEquals(105L, mapper.selectMemberPoints(P1));
        assertEquals(95L, mapper.selectMemberPoints(P2));

        // Anti-cheat blocks P2 teleport 2 seconds later to 9502 (> 500m & > 45 m/s)
        clock.addAndGet(2_000L);
        assertEquals("TELEPORT_SPEED", assertThrows(CityGameException.class, () -> territories.claim(P2, Map.of(
                "requestId", "claim-tp", "poiId", 9502L, "lat", 31.2350, "lng", 121.4750,
                "accuracyM", 12.0, "photoUrl", "https://example.com/photo-tp.jpg"))).getErrorCode());
        assertTrue(territories.adminAuditList("BLOCKED", 10).size() >= 2);

        // P3 checks in externally (simulating /api/roam/checkin) -> P1 earns another 5 POINTS
        Map<String, Object> ext = territories.rewardExternalCheckin(P3, 9501L, boardId, "pt-1", 31.2304, 121.4737, "roam-ext-1", "ROAM_CHECKIN");
        assertEquals(true, ext.get("credited"));
        assertEquals(110L, mapper.selectMemberPoints(P1));

        // Season leaderboard shows P1 with 2 check-ins and 10 revenue points
        Map<String, Object> lb = territories.leaderboard(boardId, regionId, seasonId, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> leaders = (List<Map<String, Object>>) lb.get("leaders");
        assertEquals(P1, ((Number) leaders.get(0).get("ownerMemberId")).longValue());
        assertEquals(10L, ((Number) leaders.get(0).get("totalRevenuePoints")).longValue());

        // 5. Fairness report after arrival evidence has non-null merchantVerifications & merchantVerificationMillis
        String chReq = UUID.randomUUID().toString().toUpperCase();
        tVer = ((Number) mapper.selectTeam(teamId).get("version")).longValue();
        CityR491Outcomes.View issued = CityR491Outcomes.view(arrival.command(P1, Map.of(
                "kind", "ISSUE", "requestId", chReq, "regionId", regionId, "teamId", teamId,
                "pointId", "pt-1", "releaseId", releaseId, "contentHash", rulesHash,
                "expectedTeamVersion", tVer, "expectedVersion", 0L)));
        assertEquals(200, issued.status);
        String code = (String) issued.data.get("displayCode");
        @SuppressWarnings("unchecked")
        String cid = (String) ((Map<String, Object>) issued.data.get("challenge")).get("challengeId");
        arrival.command(P1, Map.of("kind", "SUBMIT", "requestId", UUID.randomUUID().toString().toUpperCase(),
                "challengeId", cid, "expectedVersion", 1L, "displayCode", code));
        clock.addAndGet(1500L);
        arrival.command(STAFF, Map.of("kind", "CONFIRM", "requestId", UUID.randomUUID().toString().toUpperCase(),
                "challengeId", cid, "expectedVersion", 2L, "displayCode", code, "reason", "核验通过"));

        Map<String, Object> adminFair = boardAdmin.adminFairnessReport(boardId, regionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) adminFair.get("facts");
        assertEquals(1L, ((Number) facts.get("merchantVerifications")).longValue());
        assertTrue(((Number) facts.get("merchantVerificationMillis")).longValue() >= 0L);

        // 6. Freeze board -> draft edits rejected
        Map<String, Object> frozen = boardAdmin.freezeBoard(1L, boardId);
        assertEquals("FROZEN", frozen.get("lifecycle"));
        assertEquals("BOARD_FROZEN", assertThrows(CityGameException.class, () -> boardAdmin.saveDraft(1L, draftBody)).getErrorCode());
    }

    private static String table(String baseline, String name) {
        Matcher m = Pattern.compile("CREATE TABLE `" + name + "` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(baseline);
        assertTrue(m.find(), "baseline has " + name);
        return m.group();
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
