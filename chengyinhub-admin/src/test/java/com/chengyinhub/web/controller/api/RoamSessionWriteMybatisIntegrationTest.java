package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Controller + MyBatis XML + MySQL transactions; readback uses separate connections. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamSessionWriteMybatisIntegrationTest {
    private String database;
    private JdbcTemplate admin, db;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager transactions;
    private RoamMapper mapper;
    private IGrowthService growth;
    private com.chengyinhub.business.service.IRewardDropService drops;
    private com.chengyinhub.business.service.ISmsCouponHistoryService coupons;
    private ApiRoamController owner, stranger;

    @BeforeAll void database() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("f13");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                || config.adminUrl().startsWith("jdbc:mysql://localhost:"), "localhost scratch only");
        database = "chengyin_f13_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/RoamMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        db.execute("CREATE TABLE roam_session(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, started_at DATETIME,"
                + "finished_at DATETIME, distance_m INT, tiles_revealed INT, pois_found INT, xp_awarded INT, status INT,"
                + "recall_sent_at DATETIME, recall_attempts INT, recall_last_attempt_at DATETIME, create_time DATETIME) ENGINE=InnoDB");
        db.execute(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "sql/migration_roam_session_recovery_20260913.sql")), java.nio.charset.StandardCharsets.UTF_8));
        db.execute("CREATE TABLE roam_fog_tile(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, session_id BIGINT,"
                + "geohash7 VARCHAR(12), revealed_at DATETIME, UNIQUE KEY(member_id,geohash7)) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_poi_found(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, poi_id BIGINT,"
                + "session_id BIGINT, found_at DATETIME, UNIQUE KEY(member_id,poi_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_shop_visit(id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, session_id BIGINT,"
                + "source_type INT, source_id BIGINT, shop_name VARCHAR(100), visited_at DATETIME,"
                + "UNIQUE KEY(session_id,source_type,source_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE roam_poi(id BIGINT PRIMARY KEY, name VARCHAR(100), type INT, lat DECIMAL(12,7), lng DECIMAL(12,7),"
                + "radius_m INT, address VARCHAR(100), description TEXT, xp INT, coupon_template_id BIGINT, city_code VARCHAR(30),"
                + "status INT, audit_status INT, application_type VARCHAR(30), applicant_merchant_id BIGINT, audit_reason TEXT,"
                + "create_time DATETIME, merchant_id BIGINT, template_id BIGINT, node_level INT, tags TEXT, cover_img TEXT, sort INT) ENGINE=InnoDB");
        db.update("INSERT INTO roam_poi(id,name,type,lat,lng,radius_m,xp,status) VALUES(1,'test poi',1,31,121,100,20,1)");
    }

    @AfterAll void cleanup() { if (database != null && admin != null) admin.execute("DROP DATABASE " + database); }

    @BeforeEach void setup() {
        db.update("DELETE FROM roam_fog_tile");
        db.update("DELETE FROM roam_poi_found");
        db.update("DELETE FROM roam_shop_visit");
        db.update("DELETE FROM roam_session");
        db.update("INSERT INTO roam_session(id,member_id,status,started_at) VALUES(9,77,0,now())");
        mapper = mock(RoamMapper.class, org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(RoamMapper.class)));
        growth = mock(IGrowthService.class);
        drops = mock(com.chengyinhub.business.service.IRewardDropService.class);
        com.chengyinhub.business.service.ISmsCouponHistoryService couponTarget = mock(com.chengyinhub.business.service.ISmsCouponHistoryService.class);
        when(couponTarget.grantCouponById(77L, 123L)).thenThrow(new IllegalStateException("injected optional coupon failure"));
        org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource couponTx = new org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource();
        couponTx.addTransactionalMethod("grantCouponById", new org.springframework.transaction.interceptor.DefaultTransactionAttribute());
        ProxyFactory couponProxy = new ProxyFactory(couponTarget);
        couponProxy.addAdvice(new TransactionInterceptor(transactions, couponTx));
        coupons = (com.chengyinhub.business.service.ISmsCouponHistoryService) couponProxy.getProxy();
        db.update("UPDATE roam_poi SET type=1 WHERE id=1");
        owner = controller(77L);
        stranger = controller(88L);
    }

    private ApiRoamController controller(Long memberId) {
        ApiRoamController target = new ApiRoamController() { @Override Long uid() { return memberId; } };
        ReflectionTestUtils.setField(target, "roamMapper", mapper);
        ReflectionTestUtils.setField(target, "roamRecoveryService",
                new com.chengyinhub.business.service.support.RoamRecoveryService(mapper));
        com.chengyinhub.business.service.impl.RoamSessionWriteService writer = new com.chengyinhub.business.service.impl.RoamSessionWriteService();
        ReflectionTestUtils.setField(writer, "roamMapper", mapper);
        ProxyFactory writerProxy = new ProxyFactory(writer);
        writerProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(target, "roamSessionWriteService", writerProxy.getProxy());
        ReflectionTestUtils.setField(target, "growthService", growth);
        ReflectionTestUtils.setField(target, "rewardDropService", drops);
        ReflectionTestUtils.setField(target, "smsCouponHistoryService", coupons);
        ReflectionTestUtils.setField(target, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        ReflectionTestUtils.setField(target, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(target, "npcFeatureFlags", mock(NpcFeatureFlags.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ApiRoamController) proxy.getProxy();
    }

    @Test void optionalCouponFailureDoesNotUndoVerifiedPoi() {
        db.update("UPDATE roam_poi SET type=2 WHERE id=1");
        java.util.Map<String, Object> drop = new java.util.HashMap<>();
        drop.put("dropType", 3);
        drop.put("couponTemplateId", 123L);
        when(drops.rollDrop(77L, 0L, 1L, 9L,
                com.chengyinhub.business.service.IRewardDropService.SCENE_ROAM_MERCHANT_POI, 20L)).thenReturn(drop);
        success(assertDoesNotThrow(() -> owner.discoverPoi("9", 1L, 31D, 121D)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_poi_found WHERE session_id=9", Integer.class));
    }

    enum Action { TILE, POI, SHOP }

    private AjaxResult write(Action action, ApiRoamController controller, String sessionId) {
        switch (action) {
            case TILE: return controller.reveal(sessionId, "wtw3sjq");
            case POI: return controller.discoverPoi(sessionId, 1L, 31D, 121D);
            case SHOP: return controller.shopVisit(sessionId, 1, 1L, 31D, 121D);
            default: throw new AssertionError(action);
        }
    }

    private int rows(Action action) {
        String table = action == Action.TILE ? "roam_fog_tile" : action == Action.POI ? "roam_poi_found" : "roam_shop_visit";
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @ParameterizedTest @EnumSource(Action.class)
    void activeOwnerCanWriteAndRetryIsIdempotent(Action action) {
        success(write(action, owner, "9"));
        success(write(action, owner, "9"));
        assertEquals(1, rows(action));
        success(owner.finish("9", "", 12));
        assertEquals(action == Action.TILE ? 1 : 0, db.queryForObject("SELECT tiles_revealed FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.POI ? 1 : 0, db.queryForObject("SELECT pois_found FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.TILE ? 1 : action == Action.POI ? 20 : 0,
                db.queryForObject("SELECT xp_awarded FROM roam_session WHERE id=9", Integer.class));
        rejected(owner.finish("9", "", 12));
        if (action == Action.SHOP) verifyNoInteractions(growth);
        else verify(growth, times(1)).addExp(77L, action == Action.TILE ? 1 : 20, "城市漫游");
    }

    @ParameterizedTest @EnumSource(Action.class)
    void rejectsOtherOwnerMissingAndEndedWithoutRows(Action action) {
        rejected(write(action, stranger, "9"));
        rejected(write(action, owner, "999"));
        assertEquals(0, rows(action));
        success(owner.finish("9", "", 0));
        rejected(write(action, owner, "9"));
        assertEquals(0, rows(action));
    }

    @Test void concurrentBootstrapUsesOneOwnerKeyAndKeepsLegacyAndOtherOwnersIndependent() throws Exception {
        String key = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> attempt = () -> {
                start.await();
                return ((java.util.Map<?, ?>) owner.reveal("0", "wtw3sjq", key).get("data")).get("sessionId");
            };
            Future<Object> first = pool.submit(attempt), second = pool.submit(attempt);
            start.countDown();
            Object id = first.get(10, TimeUnit.SECONDS);
            assertEquals(id, second.get(10, TimeUnit.SECONDS));
            assertTrue(id instanceof String);
            success(stranger.reveal("0", "wtw3sjq", key));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM roam_session WHERE client_session_key=?", Integer.class, key));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM roam_fog_tile WHERE member_id=77", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void lookupValidatesIdentityWithoutWritingAndKeepsLargeIdsExact() {
        db.update("INSERT INTO roam_session(id,member_id,status,client_session_key) VALUES(9007199254740993,77,0,?)",
                "cccccccccccccccccccccccccccccccc");
        java.util.Map<?, ?> fact = (java.util.Map<?, ?>) owner.session("9007199254740993", null).get("data");
        assertEquals("9007199254740993", fact.get("sessionId"));
        assertEquals("ACTIVE", fact.get("state"));
        assertEquals("NOT_FOUND", ((java.util.Map<?, ?>) stranger.session("9007199254740993", null).get("data")).get("state"));
        for (String bad : new String[]{"", "0", "-1", "1e3", "9223372036854775808"})
            assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session(bad, null));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session(null, null));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session("9", "cccccccccccccccccccccccccccccccc"));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> owner.session("999", "cccccccccccccccccccccccccccccccc"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM roam_fog_tile", Integer.class));
        verifyNoInteractions(growth);
    }

    @Test void snapshotFailureRollsBackSessionAndGrowthTogether() {
        db.execute("CREATE TABLE IF NOT EXISTS recovery_growth(member_id BIGINT, xp INT) ENGINE=InnoDB");
        db.update("DELETE FROM recovery_growth");
        doAnswer(call -> { db.update("INSERT INTO recovery_growth VALUES(?,?)", new Object[] { call.getArgument(0), call.getArgument(1) }); return null; })
                .when(growth).addExp(anyLong(), anyInt(), anyString());
        success(owner.reveal("9", "wtw3sjq"));
        doThrow(new IllegalStateException("injected snapshot failure")).when(mapper).saveFinishResult(anyLong(), anyLong(), anyString());
        assertThrows(IllegalStateException.class, () -> owner.finish("9", "", 12));
        assertEquals(0, db.queryForObject("SELECT status FROM roam_session WHERE id=9", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM recovery_growth", Integer.class));
        assertNull(db.queryForObject("SELECT finish_result_json FROM roam_session WHERE id=9", String.class));
    }

    @Test void finishSnapshotCanBeReadExactlyWithoutAwardingAgain() {
        success(owner.reveal("9", "wtw3sjq"));
        AjaxResult finished = owner.finish("9", "", 12);
        success(finished);
        java.util.Map<?, ?> restored = (java.util.Map<?, ?>) owner.session("9", null).get("data");
        assertEquals(Boolean.TRUE, restored.get("resultComplete"));
        assertEquals(com.alibaba.fastjson2.JSON.toJSONString(finished.get("data")),
                com.alibaba.fastjson2.JSON.toJSONString(restored.get("result")));
        rejected(owner.finish("9", "", 12));
        verify(growth, times(1)).addExp(77L, 1, "城市漫游");
    }

    @Test void exactLookupReadsFinishedFactsWithoutExecutingFinish() throws Exception {
        org.springframework.test.web.servlet.MockMvc http =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(owner).build();
        db.update("UPDATE roam_session SET status=1,xp_awarded=7 WHERE id=9");
        String response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/roam/session").param("sessionId", "9"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        assertEquals("FINISHED", json.at("/data/state").asText());
        assertTrue(json.at("/data/sessionId").isTextual());
        assertEquals(7, json.at("/data/result/totalXp").asInt());
        assertFalse(json.at("/data/resultComplete").asBoolean());
        verifyNoInteractions(growth);
    }

    @Test void sameClientKeyRecoversTheExactBootstrapSession() throws Exception {
        org.springframework.test.web.servlet.MockMvc http =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(owner).build();
        String key = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String first = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/roam/reveal").param("sessionId", "0").param("tiles", "wtw3sjq")
                .param("clientSessionKey", key)).andReturn().getResponse().getContentAsString();
        String retry = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/roam/reveal").param("sessionId", "0").param("tiles", "wtw3sjq")
                .param("clientSessionKey", key)).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(200, json.readTree(first).get("code").asInt(), first);
        assertEquals(json.readTree(first).at("/data/sessionId"), json.readTree(retry).at("/data/sessionId"),
                "lost bootstrap response must not create another session");
        assertEquals(2, db.queryForObject("SELECT count(*) FROM roam_session", Integer.class),
                "one pre-existing legacy session plus exactly one keyed session");
    }

    @Test void lazySessionUsesAuthenticatedOwnerAndRemembersTilesAcrossSessions() {
        AjaxResult result = owner.reveal(null, "wtw3sjq");
        success(result);
        Long id = ((Number) ((java.util.Map<?, ?>) result.get("data")).get("sessionId")).longValue();
        assertEquals(77L, db.queryForObject("SELECT member_id FROM roam_session WHERE id=?", Long.class, id));
        success(owner.finish(String.valueOf(id), "", 12));
        success(owner.reveal(null, "wtw3sjq"));
        assertEquals(1, tiles());
        assertEquals(1, sql.getMapper(RoamMapper.class).countTilesEverByMember(77L));
        assertEquals(1, sql.getMapper(RoamMapper.class).selectTilePageByMember(77L, 0L, 10).size());
    }

    @ParameterizedTest @EnumSource(Action.class)
    void writeWinningLockIsIncludedInFinalSettlement(Action action) throws Exception {
        race(action, false);
        assertEquals(1, rows(action));
        assertEquals(action == Action.TILE ? 1 : 0, db.queryForObject("SELECT tiles_revealed FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.POI ? 1 : 0, db.queryForObject("SELECT pois_found FROM roam_session WHERE id=9", Integer.class));
        assertEquals(action == Action.TILE ? 1 : action == Action.POI ? 20 : 0,
                db.queryForObject("SELECT xp_awarded FROM roam_session WHERE id=9", Integer.class));
        assertEquals(1, db.queryForObject("SELECT status FROM roam_session WHERE id=9", Integer.class));
    }

    @ParameterizedTest @EnumSource(Action.class)
    void finishWinningLockRejectsLateWrite(Action action) throws Exception {
        race(action, true);
        assertEquals(0, rows(action));
        assertEquals(0, db.queryForObject("SELECT tiles_revealed+pois_found+xp_awarded FROM roam_session WHERE id=9", Integer.class));
    }

    private void race(Action action, boolean finishWins) throws Exception {
        CountDownLatch locked = new CountDownLatch(1), contenderEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            boolean first = Thread.currentThread().getName().equals("f13-first");
            if (!first) contenderEntered.countDown();
            RoamSession session = sql.getMapper(RoamMapper.class).selectOwnedSessionForUpdate(9L, 77L);
            if (first) {
                locked.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return session;
        }).when(mapper).selectOwnedSessionForUpdate(9L, 77L);
        ExecutorService first = Executors.newSingleThreadExecutor(r -> new Thread(r, "f13-first"));
        ExecutorService second = Executors.newSingleThreadExecutor(r -> new Thread(r, "f13-second"));
        try {
            Future<AjaxResult> winner = first.submit(() -> finishWins ? owner.finish("9", "", 12) : write(action, owner, "9"));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<AjaxResult> contender = second.submit(() -> finishWins ? write(action, owner, "9") : owner.finish("9", "", 12));
            assertTrue(contenderEntered.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> contender.get(100, TimeUnit.MILLISECONDS), "same session writer must wait on InnoDB row lock");
            release.countDown();
            success(winner.get(10, TimeUnit.SECONDS));
            AjaxResult result = contender.get(10, TimeUnit.SECONDS);
            if (finishWins) rejected(result); else success(result);
        } finally { release.countDown(); first.shutdownNow(); second.shutdownNow(); }
    }

    private int tiles() { return db.queryForObject("SELECT count(*) FROM roam_fog_tile", Integer.class); }
    private void success(AjaxResult result) { assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString()); }
    private void rejected(AjaxResult result) { assertNotEquals(200, result.get(AjaxResult.CODE_TAG), result.toString()); }

    @Test void strangerCannotInsertTileIntoOwnersSession() {
        rejected(stranger.reveal("9", "wtw3sjq"));
        assertEquals(0, tiles());
    }

    @Test void finishedSessionRejectsNewTiles() {
        success(owner.finish("9", "", 0));
        rejected(owner.reveal("9", "wtw3sjq"));
        assertEquals(0, tiles());
        assertEquals(0, db.queryForObject("SELECT tiles_revealed FROM roam_session WHERE id=9", Integer.class));
    }
}
