package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.business.service.support.ExploreAccountTicketGate;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 一账号一票的真实 InnoDB 当前读/主题行锁门禁；类名 *IT，必须由 CI 显式点名。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopDayOneTicketMySqlIT {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("shop_day_one_ticket_it");
    private static final long TOPIC = 83L;
    private static final long MEMBER = 901L;

    private DataSource dataSource;
    private SqlSessionFactory factory;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            // ★ 2026-08-19:先 DROP 再 CREATE,不接受"环境里恰好剩下一个同名库"。
            // 原来是 CREATE DATABASE IF NOT EXISTS ⇒ 本类默默继承上一次(可能是崩在半路的那次)留下的表,
            // 于是 @BeforeEach 建表撞 "Table 'xxx' already exists" —— #741 的资金闸就是这么连红的:
            // 本地怎么跑都绿,只有 runner 上那个库有残留。库名本来就一类一个(见 MysqlIntegrationTestConfig),
            // 这里补的是另一半:**每次开跑都从零建**,隔离才是完整的。
            // 同一条规矩在 business/it/ExploreItDatabase 已经这么写了,这里对齐。
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            statement.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        factory = factory(dataSource);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS product_role", "DROP TABLE IF EXISTS cms_registration", "DROP TABLE IF EXISTS cms_activity",
                "DROP TABLE IF EXISTS topic_sale_snapshot",
                "DROP TABLE IF EXISTS cms_topic",
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, product_type INT, lifecycle INT NOT NULL DEFAULT 2,"
                        + " del_flag INT NOT NULL DEFAULT 0) ENGINE=InnoDB",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT NOT NULL,"
                        + " KEY idx_activity_topic(topic_id)) ENGINE=InnoDB",
                "CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type BIGINT NOT NULL,"
                        + " owner_id BIGINT NOT NULL, member_id BIGINT NOT NULL, ticket_id BIGINT,"
                        + " purchase_kind INT, expires_at DATETIME(3),"
                        + " registration_status INT NOT NULL, payment_status INT NOT NULL, pay_expire_time DATETIME(3),"
                        + " KEY idx_registration_member(member_id), KEY idx_registration_owner(owner_type,owner_id)) ENGINE=InnoDB",
                "CREATE TABLE topic_sale_snapshot (id BIGINT PRIMARY KEY AUTO_INCREMENT, topic_id BIGINT NOT NULL,"
                        + " schema_version VARCHAR(32) NOT NULL, parameter_version BIGINT NOT NULL,"
                        + " source_review_task_id BIGINT NOT NULL, canonical_json LONGTEXT NOT NULL,"
                        + " frozen_at DATETIME NOT NULL, UNIQUE KEY uk_snapshot_topic(topic_id)) ENGINE=InnoDB",
                "INSERT INTO cms_topic(id,product_type,lifecycle) VALUES (83,2,2)",
                "INSERT INTO cms_activity(id,topic_id) VALUES (501,83),(502,83)");
        applyProductRoleMigrations();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO topic_sale_snapshot(topic_id,schema_version,parameter_version,"
                             + "source_review_task_id,canonical_json,frozen_at) VALUES (?,?,?,?,?,NOW())")) {
            statement.setLong(1, TOPIC);
            statement.setString(2, com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1.SCHEMA_VERSION);
            statement.setLong(3, 1L);
            statement.setLong(4, 701L);
            statement.setString(5, com.chengyinhub.business.service.support.TopicSaleCanonicalCodec.write(
                    TopicSaleCanonicalTestFixture.canonical()));
            statement.executeUpdate();
        }
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentDoubleOrderAcrossShellsCreatesExactlyOneTicket() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> attempt(7101L, 501L, ready, start));
            Future<Boolean> second = pool.submit(() -> attempt(7102L, 502L, ready, start));
            ready.await();
            start.countDown();

            assertTrue(first.get() ^ second.get(), "并发双下单必须且只能一笔成功");
            assertEquals(1, number("SELECT COUNT(*) FROM cms_registration"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void existingTicketInAnotherActivityShellIsVisibleToCurrentRead() throws Exception {
        execute("INSERT INTO cms_registration(id,owner_type,owner_id,member_id,ticket_id,"
                + "registration_status,payment_status,pay_expire_time)"
                + " VALUES (7001,2,501,901,61,2,1,NULL)");

        assertFalse(attempt(7102L, 502L, new CountDownLatch(0), new CountDownLatch(0)));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration"));
    }

    @Test
    void canonicalSnapshotPersistsWholeNumberRetailValueAsPlainString() {
        try (SqlSession session = factory.openSession(true)) {
            com.chengyinhub.business.domain.TopicSaleSnapshot snapshot =
                    session.getMapper(TopicSaleSnapshotMapper.class).selectSnapshotByTopicId(TOPIC);
            String retailValue = com.chengyinhub.business.service.support.TopicSaleCanonicalCodec
                    .read(snapshot.getSchemaVersion(), snapshot.getCanonicalJson())
                    .getMicroExperienceCatalog().path("perks").get(0).path("retailValue").asText();
            assertEquals("20", retailValue, "持久化回读不得变成 2E+1");
        }
    }

    @Test
    void sellingRejectsOrganizerMutationAndFrozenActorDoesNotFollowLiveRole() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ProductRoleMapper mapper = session.getMapper(ProductRoleMapper.class);
            ITopicOperationsScopeAuthorizer authorizer = mock(ITopicOperationsScopeAuthorizer.class);
            when(authorizer.canViewTopic(TOPIC)).thenReturn(true);
            ProductRoleServiceImpl service = new ProductRoleServiceImpl();
            ReflectionTestUtils.setField(service, "productRoleMapper", mapper);
            ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", authorizer);

            assertTrue(service.declareRequiredOrganizerSlot(
                    TOPIC, 9001L));
            ProductRole live = mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.ORGANIZER).get(0);
            assertEquals(RoleEntityType.PLATFORM, live.getEntityType());
            assertEquals(Long.valueOf(9001L), live.getDeclaredBy());

            com.fasterxml.jackson.databind.node.ObjectNode frozen = TopicSaleCanonicalTestFixture.json();
            com.fasterxml.jackson.databind.node.ObjectNode actor = (com.fasterxml.jackson.databind.node.ObjectNode)
                    frozen.path("clubWorkPackage").path("organizerActor");
            actor.put("roleId", live.getId()).put("actorType", live.getEntityType()).putNull("actorId");
            actor.put("declaredBy", live.getDeclaredBy()).put("declaredAt", live.getCreatedAt().getTime());
            try (PreparedStatement update = session.getConnection().prepareStatement(
                    "UPDATE topic_sale_snapshot SET canonical_json=? WHERE topic_id=?")) {
                update.setString(1, TopicSaleCanonicalCodec.write(
                        new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(frozen, TopicSaleCanonicalV1.class)));
                update.setLong(2, TOPIC);
                assertEquals(1, update.executeUpdate());
            }
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE cms_topic SET lifecycle=3 WHERE id=" + TOPIC);
            }

            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    service.declareRequiredOrganizerSlot(TOPIC, 9002L));
            assertEquals("SALE_ORGANIZER_IMMUTABLE", error.getMessage());
            ProductRole after = mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.ORGANIZER).get(0);
            assertEquals(live.getId(), after.getId(), "开售后 live ORGANIZER 不得被替换");

            com.chengyinhub.business.domain.TopicSaleSnapshot readback =
                    session.getMapper(TopicSaleSnapshotMapper.class).selectSnapshotByTopicId(TOPIC);
            com.fasterxml.jackson.databind.JsonNode frozenActor = TopicSaleCanonicalCodec
                    .read(readback.getSchemaVersion(), readback.getCanonicalJson())
                    .getClubWorkPackage().path("organizerActor");
            assertEquals(live.getId().longValue(), frozenActor.path("roleId").asLong());
            assertEquals("PLATFORM", frozenActor.path("actorType").asText());
            assertEquals(9001L, frozenActor.path("declaredBy").asLong());
        }
    }

    @Test
    void secondActivePlatformOrganizerHitsSingletonAndCannotTamperOriginal() throws Exception {
        execute("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                + " VALUES (83,'ORGANIZER','PLATFORM',NULL,1,9001)");
        String original = text("SELECT CONCAT(id,'|',entity_type,'|',IFNULL(entity_id,'NULL'),'|',declared_by,'|',singleton_key)"
                + " FROM product_role WHERE topic_id=83 AND slot='ORGANIZER' AND status=1");

        java.sql.SQLException duplicate = assertThrows(java.sql.SQLException.class, () ->
                execute("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status,declared_by)"
                        + " VALUES (83,'ORGANIZER','PLATFORM',NULL,1,9002)"));

        assertTrue(allMessages(duplicate).contains("uk_product_role_singleton"),
                "第二个生效 ORGANIZER 必须由 singleton 唯一键拒绝:" + allMessages(duplicate));
        assertEquals(1, number("SELECT COUNT(*) FROM product_role"
                + " WHERE topic_id=83 AND slot='ORGANIZER' AND status=1"));
        assertEquals(0, number("SELECT COUNT(*) FROM product_role WHERE declared_by=9002"),
                "失败写入不得留下半行");
        assertEquals(original, text("SELECT CONCAT(id,'|',entity_type,'|',IFNULL(entity_id,'NULL'),'|',declared_by,'|',singleton_key)"
                + " FROM product_role WHERE topic_id=83 AND slot='ORGANIZER' AND status=1"),
                "唯一冲突后原 ORGANIZER 的主体、声明人和 singleton_key 不得被篡改");
    }

    private boolean attempt(long registrationId, long activityId, CountDownLatch ready,
                            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try (SqlSession session = factory.openSession(false)) {
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC);
            topic.setProductType(ProductType.FREE_EXPLORE);
            try {
                ExploreAccountTicketGate.assertAvailable(topic, MEMBER,
                        session.getMapper(TopicSaleSnapshotMapper.class),
                        session.getMapper(CmsRegistrationMapper.class));
            } catch (IllegalStateException e) {
                if (!"SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED".equals(e.getMessage())) throw e;
                session.rollback();
                return false;
            }
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO cms_registration(id,owner_type,owner_id,member_id,ticket_id,"
                        + "registration_status,payment_status,pay_expire_time) VALUES ("
                        + registrationId + ",2," + activityId + "," + MEMBER + ",61,1,0,DATE_ADD(NOW(),INTERVAL 30 MINUTE))");
            }
            session.commit();
            return true;
        }
    }

    private SqlSessionFactory factory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("one-ticket-it",
                new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(CmsRegistrationMapper.class);
        configuration.addMapper(TopicSaleSnapshotMapper.class);
        configuration.addMapper(ProductRoleMapper.class);
        parseMapper(configuration, "mapper/business/CmsRegistrationMapper.xml");
        parseMapper(configuration, "mapper/business/TopicSaleSnapshotMapper.xml");
        parseMapper(configuration, "mapper/business/ProductRoleMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void parseMapper(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("找不到 MyBatis XML:" + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private int number(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String text(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private String allMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private void applyProductRoleMigrations() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(
                    "chengyinhub-admin/sql/migration_product_role_20260716.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(
                    "chengyinhub-admin/sql/migration_20260815_shop_day_organizer_actor.sql")));
        }
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
