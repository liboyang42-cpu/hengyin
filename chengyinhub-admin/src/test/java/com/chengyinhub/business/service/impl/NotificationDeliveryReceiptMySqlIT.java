package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.NotificationDeliveryReceiptMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class NotificationDeliveryReceiptMySqlIT {
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("notify_delivery");
    private static JdbcTemplate jdbc;
    private static NotificationDeliveryReceiptService service;
    private static DataSourceTransactionManager manager;

    @BeforeAll
    static void open() throws Exception {
        assertTrue(MYSQL.adminUrl().startsWith("jdbc:mysql://127.0.0.1:"));
        assertTrue(MYSQL.databaseName().startsWith("chengyin_b1_"), "requires dedicated task database prefix");
        try (Connection connection = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = connection.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(connection.getMetaData().getDatabaseProductVersion()));
            statement.execute("DROP DATABASE IF EXISTS `" + MYSQL.databaseName() + "`");
            statement.execute("CREATE DATABASE `" + MYSQL.databaseName() + "` CHARACTER SET utf8mb4");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.databaseUrl(), MYSQL.user(), MYSQL.password());
        jdbc = new JdbcTemplate(dataSource);
        String schema = readRepo("sql/base_schema_20260818.sql");
        for (String table : new String[]{"im_conversation", "im_conversation_member", "im_message", "official_broadcast_log"}) {
            java.util.regex.Matcher ddl = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\n\\) ENGINE[^;]*;", Pattern.DOTALL).matcher(schema);
            assertTrue(ddl.find(), table);
            jdbc.execute(ddl.group());
        }
        jdbc.update("INSERT INTO im_conversation(id,type,biz_key) VALUES (1,2,'sys_42')");
        jdbc.update("INSERT INTO im_conversation_member(conversation_id,member_id) VALUES (1,42)");
        jdbc.update("INSERT INTO im_message(id,conversation_id,sender_id,receiver_id,content) VALUES (1,1,0,42,'fixture legacy message')");
        jdbc.execute(readRepo("chengyinhub-admin/sql/migration_im_send_idempotency_20260913.sql"));
        assertNull(jdbc.queryForObject("SELECT client_message_id FROM im_message WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT request_hash FROM im_message WHERE id=1", String.class));
        assertEquals("fixture legacy message", jdbc.queryForObject("SELECT content FROM im_message WHERE id=1", String.class));
        jdbc.execute(readRepo("sql/migration_20261012_bottoma_D_notification_delivery_receipt.sql"));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/NotificationDeliveryReceiptMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        ImServiceImpl im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(im, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(im, "messageMapper", session.getMapper(ImMessageMapper.class));
        manager = new DataSourceTransactionManager(dataSource);
        service = new NotificationDeliveryReceiptService(session.getMapper(NotificationDeliveryReceiptMapper.class), im, manager);
    }

    @AfterAll
    static void close() throws Exception {
        if (jdbc == null) return;
        try (Connection connection = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + MYSQL.databaseName() + "`");
        }
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fixture_receipt_failure");
        jdbc.execute("DROP TRIGGER IF EXISTS fixture_message_failure");
        for (String table : new String[]{"notification_delivery_receipt", "im_message", "im_conversation_member", "im_conversation", "official_broadcast_log"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void legacyReachedSubscriptionLosesUnprovedAcceptanceWithoutReopeningOriginalIntent() throws Exception {
        jdbc.update("INSERT INTO official_broadcast_log(broadcast_id,member_id,channel,reached,create_time) "
                + "VALUES(10,42,'subscribe',1,NOW())");
        jdbc.execute(readRepo("sql/migration_20261012_bottoma_D_notification_legacy_receipts.sql"));
        assertEquals("CHANNEL_ACCEPTED", status(), "old reached mapping reproduces the false certainty");
        Long id = jdbc.queryForObject("SELECT id FROM notification_delivery_receipt", Long.class);
        String repair = readRepo("sql/migration_20261012_bottoma_D_legacy_acceptance_evidence_repair.sql");
        jdbc.execute(repair);
        assertEquals("UNKNOWN", status());
        assertEquals("LEGACY_UNVERIFIED", jdbc.queryForObject("SELECT provider_code FROM notification_delivery_receipt", String.class));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
        jdbc.execute(repair);
        jdbc.execute(readRepo("sql/migration_20261012_bottoma_D_notification_legacy_receipts.sql"));
        assertEquals("UNKNOWN", status());
        assertEquals(id, jdbc.queryForObject("SELECT id FROM notification_delivery_receipt", Long.class));
        assertEquals(1, count("notification_delivery_receipt"));
    }

    @Test
    void legacyRepairPreservesExplicitModernAcceptanceAndInAppFact() throws Exception {
        jdbc.update("INSERT INTO official_broadcast_log(broadcast_id,member_id,channel,reached,create_time) "
                + "VALUES(10,42,'subscribe',1,NOW()),(11,43,'inapp',1,NOW())");
        assertTrue(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false));
        assertTrue(service.complete("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", "CHANNEL_ACCEPTED", "0"));
        jdbc.execute(readRepo("sql/migration_20261012_bottoma_D_notification_legacy_receipts.sql"));
        jdbc.execute(readRepo("sql/migration_20261012_bottoma_D_legacy_acceptance_evidence_repair.sql"));
        assertEquals("CHANNEL_ACCEPTED", status());
        assertEquals("0", jdbc.queryForObject("SELECT provider_code FROM notification_delivery_receipt WHERE event_id=10", String.class));
        assertEquals("IN_APP_STORED", jdbc.queryForObject("SELECT status FROM notification_delivery_receipt WHERE event_id=11", String.class));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
        assertEquals(2, count("notification_delivery_receipt"));
    }

    @Test
    void concurrentWorkersHaveOneExternalClaimWinner() throws Exception {
        assertEquals(1, concurrent(() -> service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false)));
        assertEquals(1, count("notification_delivery_receipt"));
        assertEquals("PROCESSING", status());
    }

    @Test
    void independentClaimAndCompletionSurviveOuterTransactionRollback() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(tx -> {
            assertTrue(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false));
            assertTrue(service.complete("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", "CHANNEL_ACCEPTED", "0"));
            throw new IllegalStateException("fixture outer rollback");
        }));
        assertTrue(service.confirmed("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE"));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
    }

    @Test
    void interruptedClaimAndUnknownResultCannotBeBlindlyClaimedAgain() {
        assertTrue(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
        assertTrue(service.complete("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", "UNKNOWN", null));
        assertFalse(service.confirmed("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE"));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
        assertEquals("UNKNOWN", status());
    }

    @Test
    void failedRetryRequiresExplicitAllowanceAndTerminalReceiptCannotBeOverwritten() {
        assertTrue(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false));
        assertTrue(service.complete("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", "FAILED", "fixture-rejected"));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false));
        assertTrue(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
        assertTrue(service.complete("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", "CHANNEL_ACCEPTED", "0"));
        assertFalse(service.complete("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", "FAILED", "late-result"));
        assertEquals("CHANNEL_ACCEPTED", status());
    }

    @Test
    void actualInAppMessageAndReceiptCommitOnceWithUnreadCount() {
        assertTrue(store());
        assertFalse(store());
        assertTrue(service.confirmed("OFFICIAL_BROADCAST", 10L, 42L, "IN_APP"));
        assertEquals(1, count("im_message"));
        assertEquals(1, count("im_conversation"));
        assertEquals(1, count("im_conversation_member"));
        assertEquals(1, jdbc.queryForObject("SELECT unread_count FROM im_conversation_member WHERE member_id=42", Integer.class));
        assertEquals("fixture message", jdbc.queryForObject("SELECT content FROM im_message WHERE receiver_id=42", String.class));
    }

    @Test
    void concurrentInAppWorkersStoreOneMessageAndOneUnread() throws Exception {
        assertEquals(1, concurrent(this::store));
        assertEquals(1, count("im_message"));
        assertEquals(1, jdbc.queryForObject("SELECT unread_count FROM im_conversation_member WHERE member_id=42", Integer.class));
    }

    @Test
    void receiptWriteFailureRollsBackActualMessageAndAllItsConsumerState() {
        jdbc.execute("CREATE TRIGGER fixture_receipt_failure BEFORE UPDATE ON notification_delivery_receipt FOR EACH ROW "
                + "BEGIN IF NEW.status='IN_APP_STORED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture receipt failure'; END IF; END");
        assertThrows(DataAccessException.class, this::store);
        assertEmptyInAppState();
    }

    @Test
    void messageWriteFailureRollsBackConversationMembershipAndReceipt() {
        jdbc.execute("CREATE TRIGGER fixture_message_failure BEFORE INSERT ON im_message FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture message failure'");
        assertThrows(DataAccessException.class, this::store);
        assertEmptyInAppState();
    }

    @Test
    void externalResultPersistenceFailureRetainsProcessingAndDoesNotPermitResend() {
        assertTrue(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", false));
        jdbc.execute("CREATE TRIGGER fixture_receipt_failure BEFORE UPDATE ON notification_delivery_receipt FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture result write failure'");
        assertThrows(DataAccessException.class, () -> service.complete("OFFICIAL_BROADCAST", 10L, 42L,
                "WECHAT_SUBSCRIBE", "CHANNEL_ACCEPTED", "0"));
        jdbc.execute("DROP TRIGGER fixture_receipt_failure");
        assertEquals("PROCESSING", status());
        assertFalse(service.confirmed("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE"));
        assertFalse(service.claim("OFFICIAL_BROADCAST", 10L, 42L, "WECHAT_SUBSCRIBE", true));
    }

    private boolean store() {
        return service.storeInApp("OFFICIAL_BROADCAST", 10L, 42L, 3, "fixture message", "{}");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM notification_delivery_receipt WHERE event_type='OFFICIAL_BROADCAST' "
                + "AND event_id=10 AND recipient_id=42 AND channel='WECHAT_SUBSCRIBE'", String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertEmptyInAppState() {
        for (String table : new String[]{"notification_delivery_receipt", "im_message", "im_conversation", "im_conversation_member"}) {
            assertEquals(0, count(table), table);
        }
    }

    private int concurrent(Callable<Boolean> operation) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Boolean> task = () -> { start.await(5, TimeUnit.SECONDS); return operation.call(); };
        try {
            Future<Boolean> first = workers.submit(task);
            Future<Boolean> second = workers.submit(task);
            return (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static String readRepo(String path) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(path))) root = root.getParent();
        if (root == null) throw new IllegalStateException("fixture repository file absent: " + path);
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
    }
}
