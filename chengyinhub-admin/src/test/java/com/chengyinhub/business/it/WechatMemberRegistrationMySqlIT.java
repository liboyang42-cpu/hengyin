package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.impl.UmsMemberPointsDetailServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Requires an existing empty, task-owned schema selected by MysqlIntegrationTestConfig. */
class WechatMemberRegistrationMySqlIT {
    @Test void realMysqlUniquenessAtomicityAndBindingRaces() throws Exception {
        assumeTrue("true".equals(System.getenv("CHENGYIN_WECHAT_IDENTITY_MYSQL_IT")),
                "Explicit task-owned empty MySQL schema required");
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("wechat_identity");
        DriverManagerDataSource ds = new DriverManagerDataSource(config.databaseUrl(), config.user(), config.password());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertEquals(config.databaseName(), jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(config.databaseName().contains("_wechat_identity"));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(jdbc.queryForObject("SELECT VERSION()", String.class)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class),
                "Refuse to mutate a non-empty schema");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            jdbc.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_type BIGINT,role VARCHAR(32),"
                    + "phone VARCHAR(32),password VARCHAR(255),level_id BIGINT,name VARCHAR(64),wechat VARCHAR(64),"
                    + "status BIGINT,invite_user_id VARCHAR(64),login_ip VARCHAR(64),login_time DATETIME,nickname VARCHAR(64),"
                    + "avatar VARCHAR(255),sex INT,wx_open_id VARCHAR(100),app_open_id VARCHAR(128),union_id VARCHAR(128),"
                    + "apple_id VARCHAR(128),area_id BIGINT,birthday DATETIME,mark VARCHAR(255),tag_ids VARCHAR(255),"
                    + "case_pics VARCHAR(255),case_introducton VARCHAR(255),website VARCHAR(255),work_address VARCHAR(255),"
                    + "introduction VARCHAR(255),balance DECIMAL(18,2),point BIGINT,del_flag INT,create_by VARCHAR(64),"
                    + "create_time DATETIME,update_by VARCHAR(64),update_time DATETIME) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            jdbc.execute("CREATE TABLE ums_member_points_detail(id BIGINT PRIMARY KEY AUTO_INCREMENT,member_id BIGINT,"
                    + "current_points BIGINT,after_points BIGINT,change_points BIGINT,change_reason VARCHAR(64),"
                    + "event_type BIGINT,event_id BIGINT,reward_business_key VARCHAR(128),change_type BIGINT,del_flag INT,"
                    + "create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME) ENGINE=InnoDB");

            Path migration = Paths.get("sql/migration_wechat_identity_unique_20261013.sql");
            if (!migration.toFile().exists()) migration = Paths.get("chengyinhub-admin").resolve(migration);
            assertTrue(migration.toFile().isFile(), "Versioned migration source must be available");
            FileSystemResource script = new FileSystemResource(migration);
            jdbc.update("INSERT INTO ums_member(wx_open_id) VALUES('duplicate'),('duplicate')");
            try (Connection connection = ds.getConnection()) {
                assertThrows(RuntimeException.class, () -> ScriptUtils.executeSqlScript(connection, script));
            }
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name='uk_ums_member_wx_open_id_nonblank'", Integer.class));
            jdbc.update("DELETE FROM ums_member");
            try (Connection connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, script);
                ScriptUtils.executeSqlScript(connection, script);
            }

            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
            factory.setMapperLocations(new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                    new ClassPathResource("mapper/business/UmsMemberPointsDetailMapper.xml"));
            SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
            UmsMemberMapper mapper = sessions.getMapper(UmsMemberMapper.class);
            UmsMemberPointsDetailServiceImpl gifts = new UmsMemberPointsDetailServiceImpl();
            ReflectionTestUtils.setField(gifts, "umsMemberPointsDetailMapper", sessions.getMapper(UmsMemberPointsDetailMapper.class));
            UmsMemberServiceImpl service = new UmsMemberServiceImpl();
            ReflectionTestUtils.setField(service, "umsMemberMapper", mapper);
            ReflectionTestUtils.setField(service, "pointsDetailService", gifts);
            ReflectionTestUtils.setField(service, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
            ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(ds));
            ReflectionTestUtils.setField(service, "appSessionRegistryService", mock(IAppSessionRegistryService.class));
            ReflectionTestUtils.setField(service, "defaultAvatarUrl", "");

            // Force both transactions to observe absence before either INSERT.
            UmsMemberMapper racingMapper = spy(mapper);
            CountDownLatch observed = new CountDownLatch(2);
            AtomicInteger reads = new AtomicInteger();
            doAnswer(invocation -> {
                List<UmsMember> result = mapper.selectUmsMemberList(invocation.getArgument(0));
                if (reads.incrementAndGet() <= 2) {
                    observed.countDown();
                    assertTrue(observed.await(10, TimeUnit.SECONDS));
                }
                return result;
            }).when(racingMapper).selectUmsMemberList(any());
            ReflectionTestUtils.setField(service, "umsMemberMapper", racingMapper);
            Future<UmsMember> one = pool.submit(() -> service.getOrCreateWechatMember("concurrent"));
            Future<UmsMember> two = pool.submit(() -> service.getOrCreateWechatMember("concurrent"));
            Long winner = one.get(15, TimeUnit.SECONDS).getId();
            assertEquals(winner, two.get(15, TimeUnit.SECONDS).getId());
            assertEquals(winner, service.getOrCreateWechatMember("concurrent").getId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member WHERE wx_open_id='concurrent'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=?", Integer.class, winner));
            ReflectionTestUtils.setField(service, "umsMemberMapper", mapper);

            // Fail after both real INSERTs to prove neither partial write survives.
            UmsMemberPointsDetailServiceImpl failingGift = spy(gifts);
            doAnswer(invocation -> {
                gifts.insertUmsMemberPointsDetail(invocation.getArgument(0));
                throw new ServiceException("fixture gift failure");
            }).when(failingGift).insertUmsMemberPointsDetail(any());
            ReflectionTestUtils.setField(service, "pointsDetailService", failingGift);
            assertThrows(ServiceException.class, () -> service.getOrCreateWechatMember("gift-failure"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member WHERE wx_open_id='gift-failure'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class));
            ReflectionTestUtils.setField(service, "pointsDetailService", gifts);

            // Phone, native App and Apple may all remain unbound to Mini Program WeChat.
            for (int index = 0; index < 4; index++) {
                UmsMember member = new UmsMember();
                member.setWxOpenId(index % 2 == 0 ? "" : null);
                member.setPhone("phone-" + index);
                if (index == 1) member.setAppOpenId("app-only");
                if (index == 2) member.setAppleId("apple-only");
                service.insertUmsMember(member);
            }
            List<Long> blanks = jdbc.queryForList("SELECT id FROM ums_member WHERE wx_open_id IS NULL OR wx_open_id='' ORDER BY id", Long.class);
            assertEquals(4, blanks.size());
            CountDownLatch bindStart = new CountDownLatch(1);
            Future<Boolean> bindOne = pool.submit(() -> bind(mapper, blanks.get(0), "binding-race", bindStart));
            Future<Boolean> bindTwo = pool.submit(() -> bind(mapper, blanks.get(1), "binding-race", bindStart));
            bindStart.countDown();
            assertNotEquals(bindOne.get(10, TimeUnit.SECONDS), bindTwo.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member WHERE wx_open_id='binding-race'", Integer.class));

            // A phone binding wins after code-login's absent read; recovery keeps that exact owner.
            CountDownLatch absent = new CountDownLatch(1), bound = new CountDownLatch(1);
            UmsMemberMapper bindingRaceMapper = spy(mapper);
            doAnswer(invocation -> {
                List<UmsMember> result = mapper.selectUmsMemberList(invocation.getArgument(0));
                if (result.isEmpty()) {
                    absent.countDown();
                    assertTrue(bound.await(10, TimeUnit.SECONDS));
                }
                return result;
            }).when(bindingRaceMapper).selectUmsMemberList(any());
            ReflectionTestUtils.setField(service, "umsMemberMapper", bindingRaceMapper);
            Future<UmsMember> code = pool.submit(() -> service.getOrCreateWechatMember("code-binding-race"));
            assertTrue(absent.await(10, TimeUnit.SECONDS));
            assertEquals(1, mapper.bindWxOpenIdIfBlank(blanks.get(2), "code-binding-race"));
            bound.countDown();
            assertEquals(blanks.get(2), code.get(15, TimeUnit.SECONDS).getId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class));
            ReflectionTestUtils.setField(service, "umsMemberMapper", mapper);

            UmsMember collision = new UmsMember();
            collision.setWxOpenId("concurrent");
            assertEquals("微信身份已绑定其他账号", assertThrows(ServiceException.class,
                    () -> service.insertUmsMember(collision)).getMessage());
            collision.setId(blanks.get(3));
            assertEquals("微信身份已绑定其他账号", assertThrows(ServiceException.class,
                    () -> service.updateUmsMember(collision)).getMessage());
            assertNull(jdbc.queryForObject("SELECT wx_open_id FROM ums_member WHERE id=?", String.class, blanks.get(3)));

            // A same-name index on another expression must fail before and after reruns.
            jdbc.execute("ALTER TABLE ums_member DROP INDEX uk_ums_member_wx_open_id_nonblank");
            jdbc.execute("CREATE UNIQUE INDEX uk_ums_member_wx_open_id_nonblank ON ums_member ((NULLIF(app_open_id,'')))");
            try (Connection connection = ds.getConnection()) {
                assertThrows(RuntimeException.class, () -> ScriptUtils.executeSqlScript(connection, script));
            }
            String backslashLiteral = jdbc.queryForObject(
                    "SELECT CONCAT(CHAR(39),CHAR(92),CHAR(92),CHAR(39))", String.class);
            for (String literal : new String[]{"'x'", backslashLiteral}) {
                jdbc.execute("ALTER TABLE ums_member DROP INDEX uk_ums_member_wx_open_id_nonblank");
                jdbc.execute("CREATE UNIQUE INDEX uk_ums_member_wx_open_id_nonblank ON ums_member ((NULLIF(wx_open_id,"
                        + literal + ")))");
                String rawExpression = jdbc.queryForObject(
                        "SELECT expression FROM information_schema.statistics WHERE table_schema=DATABASE() "
                                + "AND table_name='ums_member' AND index_name='uk_ums_member_wx_open_id_nonblank'", String.class);
                try (Connection connection = ds.getConnection()) {
                    assertThrows(RuntimeException.class, () -> ScriptUtils.executeSqlScript(connection, script));
                }
                assertEquals(rawExpression, jdbc.queryForObject(
                        "SELECT expression FROM information_schema.statistics WHERE table_schema=DATABASE() "
                                + "AND table_name='ums_member' AND index_name='uk_ums_member_wx_open_id_nonblank'", String.class));
                String source = new String(java.nio.file.Files.readAllBytes(migration), java.nio.charset.StandardCharsets.UTF_8);
                String finalAssertion = source.substring(source.lastIndexOf("-- runner: assert=scalar-true")
                        + "-- runner: assert=scalar-true".length()).trim();
                assertEquals(0, jdbc.queryForObject(finalAssertion, Integer.class));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            // The initial empty-schema guard makes these exact fixture tables task-owned.
            jdbc.execute("DROP TABLE IF EXISTS ums_member_points_detail");
            jdbc.execute("DROP TABLE IF EXISTS ums_member");
        }
    }

    private boolean bind(UmsMemberMapper mapper, Long id, String openid, CountDownLatch start) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try { return mapper.bindWxOpenIdIfBlank(id, openid) == 1; }
        catch (DuplicateKeyException expected) { return false; }
    }
}
