package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.support.RoamLocationRetention;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Controller/transaction proxy, production XML and DDL, isolated MySQL 8.
 * No server, H2, mock mutex/unique key, database assumptions or skipped failures.
 * The race requires performance_schema.data_lock_waits/threads read access on CI loopback.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CityNodeCompletionIdempotencyMySqlIT {
    private static final long MEMBER = 9001L, POI = 42L;
    private JdbcTemplate db, admin;
    private String database;
    private ApiCityNodeController controller;
    private PiiCryptoService previousCrypto;
    private boolean cryptoReplaced;
    private final SqlProbe probe = new SqlProbe();

    @BeforeAll
    void setup() throws Exception {
        assertTrue(Set.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()), "CI loopback only");
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        String suite = "city_complete_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        database = MybatisItDatabase.databaseName(suite);
        assertTrue(database.matches("chengyin_mybatis_city_complete_[a-z0-9_]+") && database.length() <= 64);
        String adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        assertEquals(database, db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(db.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/base_schema_20260818.sql"))) root = root.getParent();
        assertNotNull(root, "public baseline schema missing");
        String baseline = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        // Exact retained production DDL, no reduced hand-written mutex or unique key.
        for (String table : List.of("ums_member", "roam_poi", "roam_poi_reward_log", "cms_member_template",
                "mms_merchant", "ums_comment", "growth_badge", "player_badge")) {
            Matcher ddl = Pattern.compile("(?s)CREATE TABLE `" + table + "` [(].*?[)] ENGINE=[^;]+;").matcher(baseline);
            assertTrue(ddl.find(), table);
            db.execute(ddl.group());
        }
        // Only the existing guard/reward section is needed; other retention tables are out of scope.
        String retention = Files.readString(root.resolve("chengyinhub-admin/sql/migration_20261012_roam_claim_fingerprints.sql"));
        int guardStart = retention.indexOf("CREATE TABLE IF NOT EXISTS roam_claim_key_guard");
        int guardEnd = retention.indexOf("SET @fog_tile_fp_exists");
        int rewardStart = retention.indexOf("SET @reward_log_fp_exists");
        assertTrue(guardStart >= 0 && guardEnd > guardStart && rewardStart > guardEnd);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource((retention.substring(guardStart, guardEnd)
                    + retention.substring(rewardStart)).getBytes(StandardCharsets.UTF_8)));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(
                    "chengyinhub-admin/sql/migration_20261024_official_roam_challenges.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(
                    "chengyinhub-admin/sql/migration_20261025_official_poi_quotes.sql")));
        }
        assertEquals("poi_id,member_id", db.queryForObject("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                + "FROM information_schema.statistics WHERE table_schema=? AND table_name='roam_poi_reward_log' "
                + "AND index_name='uk_poi_member' AND non_unique=0", String.class, database));
        assertEquals("InnoDB", db.queryForObject("SELECT engine FROM information_schema.tables "
                + "WHERE table_schema=? AND table_name='ums_member'", String.class, database));
        previousCrypto = PiiCryptoService.getInstance();
        new PiiCryptoService(Base64.getEncoder().encodeToString(new byte[32]), "city-completion-fixture-only").register();
        cryptoReplaced = true;

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/RoamMapper.xml"),
                new ClassPathResource("mapper/business/GrowthBadgeMapper.xml"),
                new ClassPathResource("mapper/business/PlayerBadgeMapper.xml"));
        factory.setPlugins(probe);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        ApiCityNodeController target = new ApiCityNodeController() {
            @Override public Long getAppUserId() { return MEMBER; }
        };
        ReflectionTestUtils.setField(target, "roamMapper", sql.getMapper(RoamMapper.class));
        ReflectionTestUtils.setField(target, "growthBadges", sql.getMapper(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(target, "playerBadges", sql.getMapper(PlayerBadgeMapper.class));
        // GPS template (vm=5), photo_required=0: no external photo provider or template service is invoked.
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        controller = (ApiCityNodeController) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        for (String table : List.of("player_badge", "growth_badge", "roam_poi_reward_log", "roam_poi",
                "cms_member_template", "ums_member", "roam_claim_key_guard")) db.update("DELETE FROM " + table);
        db.update("INSERT INTO ums_member(id) VALUES(?)", MEMBER);
        db.update("INSERT INTO cms_member_template(id,member_id,title,validation_method,coupon_id) VALUES(88,?,'fixture',5,NULL)", MEMBER);
        db.update("INSERT INTO roam_poi(id,name,type,lat,lng,radius_m,status,application_type,audit_status,template_id,photo_required,badge_code) "
                + "VALUES(?,'fixture',1,31.2,121.4,80,1,3,1,88,0,'ROAM_POI_42')", POI);
        db.update("INSERT INTO growth_badge(badge_code,badge_name,status) VALUES('ROAM_POI_42','fixture badge','0')");
        db.update("INSERT INTO roam_claim_key_guard(id,key_fingerprint,create_time) VALUES(1,?,NOW())", RoamLocationRetention.keyFingerprint());
        probe.writes.clear();
    }

    @AfterAll
    void cleanup() {
        try {
            if (admin != null && database != null) {
                assertTrue(database.matches("chengyin_mybatis_city_complete_[a-z0-9_]+") && database.length() <= 64);
                admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
                assertEquals(0L, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name=?", Long.class, database));
            }
        } finally {
            if (cryptoReplaced) ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previousCrypto);
        }
    }

    @Test
    void concurrentTransactionsWaitOnRealMemberLockAndAwardOnlyOnce() throws Exception {
        probe.race = true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> first = workers.submit(() -> raceComplete(1));
            assertTrue(probe.held.await(10, TimeUnit.SECONDS), "first transaction must acquire actual member lock");
            Future<AjaxResult> second = workers.submit(() -> raceComplete(2));
            assertTrue(probe.attempted.await(10, TimeUnit.SECONDS), "second transaction must attempt same lock");
            assertNotEquals(probe.connections.get(1), probe.connections.get(2), "distinct database transactions");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                Long n = db.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        + "JOIN performance_schema.threads r ON r.thread_id=w.requesting_thread_id "
                        + "JOIN performance_schema.threads b ON b.thread_id=w.blocking_thread_id "
                        + "JOIN performance_schema.data_locks l ON l.engine_lock_id=w.requesting_engine_lock_id "
                        + "AND l.engine=w.engine WHERE r.processlist_id=? AND b.processlist_id=? "
                        + "AND l.object_schema=? AND l.object_name='ums_member'", Long.class,
                        probe.connections.get(2), probe.connections.get(1), database);
                if (n != null && n > 0) { waiting = true; break; }
                Thread.sleep(20);
            }
            assertTrue(waiting, "must observe real InnoDB member-row lock wait, not scheduler serialization");
            probe.release.countDown();
            AjaxResult a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
            assertEquals(200, a.get("code"));
            assertEquals(200, b.get("code"));
            assertEquals(true, data(a).get("completed"));
            assertEquals(true, data(b).get("alreadyClaimed"));
            assertEquals(1L, count("roam_poi_reward_log"));
            assertEquals(1L, count("player_badge"));
            assertEquals(List.of(1, 0), probe.writes.stream().filter(w -> w.statement.endsWith(".insertRewardClaim"))
                    .map(Write::rows).toList());
            assertEquals(1L, probe.writes.stream().filter(w -> w.statement.endsWith(".insertPlayerBadge")).count());
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM player_badge WHERE member_id=? "
                    + "AND badge_code='ROAM_POI_42' AND source_type=6 AND source_id=? AND del_flag=0", Long.class, MEMBER, POI));
            assertThrows(DuplicateKeyException.class, () -> db.update("INSERT INTO roam_poi_reward_log(poi_id,member_id) VALUES(?,?)", POI, MEMBER));
        } finally {
            probe.release.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "workers must finish before schema cleanup");
            probe.race = false;
        }
    }

    @Test
    void repeatReturnsOriginalPendingCouponEvenWhenTemplateCouponChanges() {
        db.update("UPDATE cms_member_template SET coupon_id=501 WHERE id=88");
        AjaxResult first = complete(31.2, 121.4);
        assertEquals(200, first.get("code"));
        assertEquals(true, data(first).get("needRedeem"));
        assertEquals(501L, ((Number) data(first).get("couponId")).longValue());
        db.update("UPDATE cms_member_template SET coupon_id=999 WHERE id=88");
        AjaxResult repeat = complete(31.2, 121.4);
        assertEquals(200, repeat.get("code"));
        assertEquals(true, data(repeat).get("alreadyClaimed"));
        assertEquals(true, data(repeat).get("needRedeem"));
        assertEquals(501L, ((Number) data(repeat).get("couponId")).longValue());
        assertEquals(1L, count("roam_poi_reward_log"));
        assertEquals(0L, count("player_badge"));
        assertEquals(0, db.queryForObject("SELECT status FROM roam_poi_reward_log", Integer.class));
        assertNull(db.queryForObject("SELECT coupon_history_id FROM roam_poi_reward_log", Long.class));
    }

    @Test
    void currentValidationStillRejectsBeforeWritesEvenAfterAnEarlierCompletion() {
        db.update("UPDATE cms_member_template SET coupon_id=501 WHERE id=88");
        assertEquals(200, complete(31.2, 121.4).get("code"));
        List<Map<String, Object>> original = db.queryForList("SELECT * FROM roam_poi_reward_log");
        probe.writes.clear();
        assertEquals(500, complete(32.2, 121.4).get("code"));
        assertEquals(500, complete(null, 121.4).get("code"));
        db.update("UPDATE roam_poi SET audit_status=2 WHERE id=?", POI);
        assertEquals(500, complete(31.2, 121.4).get("code"));
        assertTrue(probe.writes.isEmpty(), "current validation must reject before any Mapper mutation");
        assertEquals(original, db.queryForList("SELECT * FROM roam_poi_reward_log"));
        assertEquals(1L, count("roam_poi_reward_log"));
        assertEquals(0L, count("player_badge"));
    }

    private AjaxResult raceComplete(int slot) {
        probe.slot.set(slot);
        try { return complete(31.2, 121.4); }
        finally { probe.slot.remove(); }
    }
    private AjaxResult complete(Double lat, Double lng) { return controller.complete(POI, lat, lng, "", "", ""); }
    private long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> data(AjaxResult result) { return (Map<String, Object>) result.get("data"); }
    private record Write(String statement, int rows) { }

    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
    })
    public static class SqlProbe implements Interceptor {
        final ThreadLocal<Integer> slot = new ThreadLocal<>();
        final Map<Integer, Long> connections = new ConcurrentHashMap<>();
        final List<Write> writes = new CopyOnWriteArrayList<>();
        final CountDownLatch held = new CountDownLatch(1), attempted = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean race;
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            boolean memberLock = statement.getId().equals(RoamMapper.class.getName() + ".lockClaimOwner");
            Integer index = slot.get();
            if (memberLock) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "Controller annotation must be proxied");
                Connection connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
                assertFalse(connection.getAutoCommit(), "FOR UPDATE must execute within a real transaction");
                if (race && index != null) {
                    try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery("SELECT CONNECTION_ID()")) {
                        assertTrue(row.next());
                        connections.put(index, row.getLong(1));
                    }
                    if (index == 2) attempted.countDown();
                }
            }
            Object result = invocation.proceed(); // actual lock/SQL; never stub the result
            if (invocation.getMethod().getName().equals("update")) writes.add(new Write(statement.getId(), (Integer) result));
            if (memberLock) {
                assertEquals(List.of(MEMBER), result, "the seeded member row must really be locked");
                if (race && Integer.valueOf(1).equals(index)) {
                    held.countDown();
                    assertTrue(release.await(20, TimeUnit.SECONDS), "bounded hold for observed MySQL lock wait");
                }
            }
            return result;
        }
    }
}
