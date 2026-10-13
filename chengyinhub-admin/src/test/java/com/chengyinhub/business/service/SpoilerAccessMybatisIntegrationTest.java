package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.SpoilerProtectionMapper;
import com.chengyinhub.business.service.impl.SpoilerProtectionServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL, original DDL and mapper; every test owns a new scratch schema. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class SpoilerAccessMybatisIntegrationTest {
    private MysqlIntegrationTestConfig config;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        config = MysqlIntegrationTestConfig.fromEnvironment(
                "spoiler_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try (Connection connection = DriverManager.getConnection(config.adminUrl(), config.user(), config.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + config.databaseName() + "` CHARACTER SET utf8mb4");
        }
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", config.databaseUrl(),
                        config.user(), config.password())));
        for (String resource : new String[]{"mapper/business/SpoilerProtectionMapper.xml",
                "mapper/business/CmsRegistrationMapper.xml"}) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        execute(ddl("chengyinhub-admin/sql/migration_spoiler_protection_20260828.sql", "member_spoiler_preference"));
        execute(ddl("chengyinhub-admin/sql/migration_spoiler_protection_20260828.sql", "member_spoiler_reveal"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (config != null) {
            try (Connection connection = DriverManager.getConnection(config.adminUrl(), config.user(), config.password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + config.databaseName() + "`");
            }
        }
    }

    @Test
    void changingExistingPreferenceReportsSuccessAndPersists() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            SpoilerProtectionServiceImpl service = service(session);
            assertTrue(service.updatePreference(9L, false));
            assertEquals("0", scalar("select reveal_all from member_spoiler_preference where member_id=9"));
            assertTrue(service.updatePreference(9L, true), "changed upsert must report success");
            assertEquals("1", scalar("select reveal_all from member_spoiler_preference where member_id=9"));
        }
    }

    @Test
    void preferenceFirstInsertBothDirectionsAndSameValueWorkForBothDriverCounts() throws Exception {
        for (boolean affectedRows : new boolean[]{false, true}) {
            ((UnpooledDataSource) factory.getConfiguration().getEnvironment().getDataSource())
                    .setUrl(config.databaseUrl() + "&useAffectedRows=" + affectedRows);
            long member = affectedRows ? 10L : 11L;
            try (SqlSession session = factory.openSession(true)) {
                try (Statement statement = session.getConnection().createStatement()) {
                    statement.execute("SET timestamp=1800000000");
                }
                SpoilerProtectionServiceImpl service = service(session);
                com.chengyinhub.web.controller.api.ApiSpoilerController controller =
                        new com.chengyinhub.web.controller.api.ApiSpoilerController() {
                            @Override public Long getAppUserId() { return member; }
                        };
                ReflectionTestUtils.setField(controller, "spoilerProtectionService", service);
                for (boolean value : new boolean[]{false, true, true, false, false}) {
                    assertEquals(200, controller.preference(java.util.Collections.singletonMap("revealAll", value)).get("code"));
                    assertEquals(value ? "1" : "0", scalar(
                            "select reveal_all from member_spoiler_preference where member_id=" + member));
                    assertEquals(value, service.snapshot(member, "NODE").isRevealAll());
                }
            }
        }
        assertEquals("2", scalar("select count(*) from member_spoiler_preference"));
    }

    @Test
    void databaseFailurePropagatesWithoutChangingSavedPreference() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            SpoilerProtectionServiceImpl service = service(session);
            assertTrue(service.updatePreference(9L, false));
            execute("CREATE TRIGGER reject_preference BEFORE UPDATE ON member_spoiler_preference "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test write rejected'");
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,
                    () -> service.updatePreference(9L, true));
            assertEquals("0", scalar("select reveal_all from member_spoiler_preference where member_id=9"));
            assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> service.updatePreference(null, true));
            assertEquals("1", scalar("select count(*) from member_spoiler_preference"));
        }
    }

    @Test
    void storyAccessUsesAuthoritativeRegistrationDdlWithoutSoftDeleteColumn() throws Exception {
        createStoryTables();
        assertEquals("0", scalar("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='cms_registration' and column_name='del_flag'"));
        assertFalse(access(7L, 9L));
        insertRegistration();
        assertTrue(access(7L, 9L));
    }

    @Test
    void storyAccessKeepsMemberTopicPaymentAndCancellationBoundaries() throws Exception {
        createStoryTables();
        insertRegistration();
        assertTrue(access(7L, 9L), "paid zero-cash ticket remains eligible");
        assertFalse(access(7L, 10L));
        assertFalse(access(8L, 9L));
        assertFalse(access(7L, null));
        assertFalse(access(null, 9L));
        for (int payment : new int[]{0, 1, 3, 4}) {
            execute("update cms_registration set payment_status=" + payment);
            assertFalse(access(7L, 9L), "payment status " + payment);
        }
        execute("update cms_registration set payment_status=2");
        for (int registration : new int[]{1, 3, 4}) {
            execute("update cms_registration set registration_status=" + registration);
            assertFalse(access(7L, 9L), "registration status " + registration);
        }
        execute("update cms_registration set registration_status=2,verification_status=1");
        assertTrue(access(7L, 9L), "verification does not invalidate a valid story ticket");
        execute("update cms_registration set registration_status=3");
        assertFalse(access(7L, 9L), "verified but cancelled ticket is not eligible");
        execute("update cms_registration set registration_status=2");
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(1, session.getMapper(CmsRegistrationMapper.class).deleteCmsRegistrationById(21L));
        }
        assertEquals("0", scalar("select count(*) from cms_registration"));
        assertFalse(access(7L, 9L));
    }

    @Test
    void storyAccessKeepsOwnerMappingAndSelfPlayExpiryRules() throws Exception {
        createStoryTables();
        insertRegistration();
        execute("update cms_registration set owner_id=0,topic_id=7,purchase_kind=2");
        assertTrue(access(7L, 9L), "legacy null expiry remains supported");
        execute("update cms_registration set expires_at=date_add(now(),interval 1 day)");
        assertTrue(access(7L, 9L));
        execute("update cms_registration set expires_at=date_sub(now(),interval 1 day)");
        assertFalse(access(7L, 9L));
        execute("update cms_registration set purchase_kind=null");
        assertTrue(access(7L, 9L), "legacy ticket rule is unchanged");
        execute("update cms_registration set owner_type=2,owner_id=11");
        assertFalse(access(7L, 9L), "an activity ticket requires the activity relation");
        execute("insert into cms_activity(id,topic_id) values(11,8)");
        assertFalse(access(7L, 9L), "registration.topic_id cannot override activity ownership");
        assertTrue(access(8L, 9L));
        execute("update cms_activity set topic_id=7");
        assertTrue(access(7L, 9L));
        execute("update cms_registration set owner_type=3");
        assertFalse(access(7L, 9L));
    }

    @Test
    void exploreTicketRequiresOwnTopicEntitlementBeforeAndAfterVerification() throws Exception {
        createStoryTables();
        insertRegistration();
        execute("update cms_registration set purchase_kind=3");
        assertFalse(access(7L, 9L));
        execute("insert into registration_entitlement(registration_id,chapter_id,topic_id,status) values(22,1,7,0)");
        assertFalse(access(7L, 9L), "another registration entitlement grants nothing");
        execute("update registration_entitlement set registration_id=21,topic_id=8");
        assertFalse(access(7L, 9L), "another topic entitlement grants nothing");
        execute("update registration_entitlement set topic_id=7");
        assertTrue(access(7L, 9L), "unredeemed valid entitlement unlocks story");
        execute("update registration_entitlement set status=1");
        execute("update cms_registration set verification_status=1");
        assertTrue(access(7L, 9L), "redeemed entitlement still unlocks story");
        execute("update registration_entitlement set status=2");
        assertFalse(access(7L, 9L), "invalid entitlement locks story again");
        execute("update registration_entitlement set status=0");
        execute("update cms_registration set payment_status=4");
        assertFalse(access(7L, 9L), "entitlement cannot override refunded payment");
        execute("update cms_registration set payment_status=2,registration_status=3");
        assertFalse(access(7L, 9L), "entitlement cannot override cancellation");
    }

    private void createStoryTables() throws Exception {
        execute(ddl("sql/base_schema_20260818.sql", "cms_registration"));
        execute(ddl("sql/base_schema_20260818.sql", "cms_activity"));
        execute(ddl("sql/base_schema_20260818.sql", "registration_entitlement"));
    }

    private void insertRegistration() throws Exception {
        execute("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,"
                + "member_id,original_amount,total_amount,payable_amount,pay_expire_time,payment_status,"
                + "registration_status,purchase_kind) values(21,'story-21',1,7,1,0,9,0,0,0,now(),2,2,1)");
    }

    private boolean access(Long topic, Long member) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(CmsRegistrationMapper.class).hasActiveTopicStoryAccess(topic, member);
        }
    }

    private SpoilerProtectionServiceImpl service(SqlSession session) {
        SpoilerProtectionServiceImpl service = new SpoilerProtectionServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", session.getMapper(SpoilerProtectionMapper.class));
        return service;
    }

    private String ddl(String relative, String table) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/base_schema_20260818.sql"))) root = root.getParent();
        assertNotNull(root, "repository root required");
        String source = new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("CREATE TABLE `?" + table + "`? \\(.*?^\\).*?;\\s*$", Pattern.DOTALL | Pattern.MULTILINE).matcher(source);
        assertTrue(matcher.find(), "authoritative DDL missing: " + table);
        return matcher.group();
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.databaseUrl(), config.user(), config.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.databaseUrl(), config.user(), config.password());
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
