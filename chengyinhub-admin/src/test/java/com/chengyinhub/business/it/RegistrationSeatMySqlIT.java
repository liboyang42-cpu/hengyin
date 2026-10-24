package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL mapper/lock/transaction tests; not original A cash settlement or HTTP permission evidence. */
@Timeout(30)
class RegistrationSeatMySqlIT {
    private static PooledDataSource pool;
    private static String ownedSchema;
    private static JdbcTemplate jdbc;
    private static RegistrationSeatMapper seats;
    private static DataSourceTransactionManager transactions;

    @BeforeAll static void createOwnedSchema() throws Exception {
        for (String suffix : List.of("HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX")) {
            String value = System.getenv("CHENGYIN_MYBATIS_IT_" + suffix);
            assertNotNull(value, "Explicit isolated MySQL variable required: " + suffix);
            if (!suffix.equals("PASSWORD")) assertFalse(value.isBlank());
        }
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        int port = Integer.parseInt(MybatisItDatabase.port()); assertTrue(port > 0 && port <= 65535);
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("_[a-z0-9_]{1,20}"));
        String url = MybatisItDatabase.url("c_registration_seats");
        ownedSchema = MybatisItDatabase.databaseName("c_registration_seats");
        pool = new PooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        Path baseline = Path.of("..", "sql", "base_schema_20260818.sql");
        if (!Files.isRegularFile(baseline)) baseline = Path.of("sql", "base_schema_20260818.sql");
        String ddl = Files.readString(baseline);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("cms_registration", "coop_group_member_verify", "club", "cms_activity")) {
                var match = Pattern.compile("(?s)CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(ddl);
                assertTrue(match.find(), table); statement.execute(match.group()); assertFalse(match.find());
            }
            ItSchema.applyMigrations(connection, "sql/migration_20261024_registration_verified_seats.sql", "sql/migration_20261024_registration_seat_activity_scope.sql");
        }
        Configuration config = new Configuration(new Environment("c-seats", new SpringManagedTransactionFactory(), pool));
        String resource = "mapper/business/RegistrationSeatMapper.xml";
        try (InputStream input = RegistrationSeatMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(pool); factory.setConfiguration(config);
        seats = new SqlSessionTemplate(factory.getObject()).getMapper(RegistrationSeatMapper.class);
        jdbc = new JdbcTemplate(pool); transactions = new DataSourceTransactionManager(pool);
    }

    @AfterAll static void cleanOwnedSchema() throws Exception {
        if (pool != null) pool.forceCloseAll();
        if (ownedSchema == null) return;
        assertTrue(ownedSchema.matches("chengyin_mybatis_c_registration_seats_[a-z0-9_]{1,20}"));
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true", MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + ownedSchema + "`");
        }
    }

    @BeforeEach void seed() {
        for (String table : List.of("cms_registration_seat", "coop_group_member_verify", "cms_registration", "club", "cms_activity")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO cms_registration (id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                + "order_num,original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time)"
                + " VALUES (31,'SEAT-IT-31',1,41,91,10,51,3,30,30,30,2,2,'2099-01-01')");
        jdbc.update("INSERT INTO club (id,member_id,name,status,del_flag) VALUES (71,81,'seat-test',1,0)");
    }

    @Test void oneExplicitVerificationNeverExpandsPurchasedQuantityAndReplaysTheSameSeat() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            assertEquals(31L, seats.lockRegistration(31L));
            for (int number = 1; number <= 3; number++) assertEquals(1, seats.insertSeat(pending(number)));
            assertTrue(seats.selectVerifiedForGroupForUpdate(41L, 61L, future()).isEmpty());
            bindGroup();
            RegistrationSeat selected = seats.selectByRegistrationForUpdate(31L).get(0);
            verify(selected); assertEquals(1, seats.markVerified(selected));
            assertEquals(0, seats.markVerified(selected));
            List<RegistrationSeat> actual = seats.selectVerifiedForGroupForUpdate(41L, 61L, future());
            assertEquals(1, actual.size()); assertEquals(selected.getId(), actual.get(0).getId());
            assertEquals(1, actual.get(0).getSeatNo());
            assertTrue(seats.selectVerifiedForGroupForUpdate(41L, 62L, future()).isEmpty());
        });
        assertEquals(3L, jdbc.queryForObject("SELECT order_num FROM cms_registration WHERE id=31", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration_seat WHERE status=1", Integer.class));
    }

    @Test void concurrentDuplicateUsesRegistrationLockAndReturnsTheCommittedOriginalFact() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> { seats.lockRegistration(31L); seats.insertSeat(pending(1)); bindGroup(); });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), entered = new CountDownLatch(1);
        java.util.Set<Long> connections = ConcurrentHashMap.newKeySet();
        try {
            Future<Long> winner = workers.submit(() -> tx.execute(status -> {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                seats.lockRegistration(31L); RegistrationSeat row = seats.selectByRegistrationForUpdate(31L).get(0);
                verify(row); assertEquals(1, seats.markVerified(row)); held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return row.getId();
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Future<Long> retry = workers.submit(() -> tx.execute(status -> {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); entered.countDown();
                seats.lockRegistration(31L); RegistrationSeat row = seats.selectByRegistrationForUpdate(31L).get(0);
                assertEquals(1, row.getStatus()); return row.getId();
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> retry.get(200, TimeUnit.MILLISECONDS), "waiter must not bypass the held registration lock");
            release.countDown(); assertEquals(winner.get(5, TimeUnit.SECONDS), retry.get(5, TimeUnit.SECONDS));
            assertEquals(2, connections.size());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration_seat WHERE status=1", Integer.class));
        } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void ledgerAndSeatCasRollBackTogetherWhenTheTerminalAuditStageFails() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> { seats.lockRegistration(31L); seats.insertSeat(pending(1)); });
        assertThrowsExactly(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            seats.lockRegistration(31L); bindGroup();
            jdbc.update("UPDATE cms_registration SET verification_status=1 WHERE id=31");
            RegistrationSeat row = seats.selectByRegistrationForUpdate(31L).get(0); verify(row);
            assertEquals(1, seats.markVerified(row));
            throw new IllegalStateException("terminal audit stage failed");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT verification_status FROM cms_registration WHERE id=31", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM coop_group_member_verify", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM cms_registration_seat WHERE registration_id=31", Integer.class));
    }

    @Test void legacyWholeOrderAndMalformedFactNeverBecomeThreeVerifiedSeats() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            seats.lockRegistration(31L); bindGroup();
            assertEquals(List.of(31L), seats.selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, future()));
            assertTrue(seats.selectVerifiedForGroupForUpdate(41L, 61L, future()).isEmpty());
        });
        var rejected = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("INSERT INTO cms_registration_seat (registration_id,seat_no,status) VALUES (31,1,1)"));
        java.sql.SQLException sqlFailure = assertInstanceOf(java.sql.SQLException.class, rejected.getMostSpecificCause());
        assertEquals(3819, sqlFailure.getErrorCode(), "the actual MySQL CHECK must reject incomplete verification facts");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration_seat", Integer.class));
    }

    @Test void activityFactsUseTheirOriginalActivityAndNeverRequireAFabricatedGroupRow() {
        jdbc.update("INSERT INTO cms_activity (id,member_id,topic_id,del_flag) VALUES (61,81,41,0)");
        jdbc.update("UPDATE cms_registration SET owner_type=2,owner_id=61,verification_status=1,verification_time=NOW() WHERE id=31");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            seats.lockRegistration(31L);
            assertEquals(List.of(31L), seats.selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, future()));
            RegistrationSeat row = pending(2); assertEquals(1, seats.insertSeat(row));
            verify(row); assertEquals(1, seats.markVerified(row));
            assertTrue(seats.selectUnprovenMultiGroupOrdersForUpdate(41L, 61L, future()).isEmpty());
            List<RegistrationSeat> actual = seats.selectVerifiedForGroupForUpdate(41L, 61L, future());
            assertEquals(1, actual.size()); assertEquals(2, actual.get(0).getSeatNo());
            assertTrue(seats.selectVerifiedForGroupForUpdate(41L, 62L, future()).isEmpty());
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM coop_group_member_verify", Integer.class));
    }

    @Test void standaloneActivityPersistsNullTopicWithoutScopeIdSubstitution() {
        jdbc.update("INSERT INTO cms_activity (id,member_id,topic_id,del_flag) VALUES (61,81,NULL,0)");
        jdbc.update("UPDATE cms_registration SET owner_type=2,owner_id=61 WHERE id=31");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            seats.lockRegistration(31L); RegistrationSeat row = pending(1); seats.insertSeat(row);
            verify(row); row.setTopicId(null); assertEquals(1, seats.markVerified(row));
            assertNull(seats.selectByRegistrationForUpdate(31L).get(0).getTopicId());
            assertTrue(seats.selectVerifiedForGroupForUpdate(41L, 61L, future()).isEmpty());
        });
    }

    private static RegistrationSeat pending(int number) {
        RegistrationSeat row = new RegistrationSeat(); row.setRegistrationId(31L); row.setSeatNo(number); row.setStatus(0); return row;
    }
    private static void verify(RegistrationSeat row) {
        row.setTopicId(41L); row.setActivityId(61L); row.setVerifierMemberId(81L); row.setVerifiedAt(new Date());
    }
    private static void bindGroup() {
        jdbc.update("INSERT INTO coop_group_member_verify (topic_id,activity_id,registration_id,verifier_member_id,verify_time) VALUES (41,61,31,81,NOW())");
    }
    private static Date future() { return new Date(System.currentTimeMillis() + 1000); }
}
