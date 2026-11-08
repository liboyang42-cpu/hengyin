package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.RegistrationSeatInvitation;
import com.chengyinhub.business.domain.RegistrationSeatInvitationRequestRecord;
import com.chengyinhub.business.domain.SeatRecipientFact;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationMapper;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationRequestMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内含席位邀请的真实 MySQL 唯一键/CAS 证明。只跑自有 fixture 库；不触发资金/HTTP。
 *
 * <p>证明点：同原单同席同时最多一个 pending/claimed（唯一键）、终态行保留历史、
 * 并发最后一位只成功一次、领取 CAS 只产生一个受让人、过期只释放未领取行、
 * 受让人事实在原行退款/跨范围时立即消失。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@Timeout(40)
class RegistrationSeatInvitationMySqlIT {

    private static PooledDataSource pool;
    private static String ownedSchema;
    private static boolean ownedSchemaCreated;
    private static JdbcTemplate jdbc;
    private static RegistrationSeatInvitationMapper invitations;
    private static RegistrationSeatInvitationRequestMapper requestRecords;
    private static DataSourceTransactionManager transactions;

    @BeforeAll
    static void createOwnedSchema() throws Exception {
        for (String suffix : List.of("HOST", "PORT", "USER", "PASSWORD")) {
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_" + suffix),
                    "Explicit isolated MySQL variable required: " + suffix);
        }
        ownedSchema = "chengyin_mybatis_c_seat_invitation_"
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + ownedSchema + "` CHARACTER SET utf8mb4");
            ownedSchemaCreated = true;
        }
        String url = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/" + ownedSchema + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        pool = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cms_registration ("
                    + "id BIGINT NOT NULL PRIMARY KEY, owner_type BIGINT NOT NULL, owner_id BIGINT NOT NULL,"
                    + "registration_status INT NOT NULL, payment_status INT NOT NULL,"
                    + "member_id BIGINT NOT NULL, order_num BIGINT NOT NULL) ENGINE=InnoDB");
            statement.execute("CREATE TABLE cms_activity ("
                    + "id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NULL, topic_id BIGINT NULL,"
                    + "del_flag INT NULL, cancel_time DATETIME(3) NULL, end_date DATETIME(3) NULL) ENGINE=InnoDB");
            statement.execute("CREATE TABLE cms_topic (id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            statement.execute("CREATE TABLE topic_route_session (id BIGINT PRIMARY KEY, member_id BIGINT,"
                    + "topic_id BIGINT, activity_id BIGINT, status INT) ENGINE=InnoDB");
            statement.execute("CREATE TABLE player_node_progress (id BIGINT PRIMARY KEY, member_id BIGINT,"
                    + "topic_id BIGINT, activity_id BIGINT) ENGINE=InnoDB");
            statement.execute("CREATE TABLE orient_task_attempt (id BIGINT PRIMARY KEY, member_id BIGINT,"
                    + "topic_id BIGINT, activity_id BIGINT) ENGINE=InnoDB");
            statement.execute("CREATE TABLE orient_task_completion (id BIGINT PRIMARY KEY, member_id BIGINT,"
                    + "topic_id BIGINT, activity_id BIGINT) ENGINE=InnoDB");
            statement.execute("CREATE TABLE game_runtime_session (id BIGINT PRIMARY KEY, owner_type INT,"
                    + "owner_id BIGINT, topic_id BIGINT, activity_id BIGINT, status INT) ENGINE=InnoDB");
            statement.execute("CREATE TABLE game_session (id BIGINT PRIMARY KEY, activity_id BIGINT,"
                    + "status VARCHAR(20), started_at DATETIME(3)) ENGINE=InnoDB");
            ItSchema.applyMigrations(connection, "sql/migration_20261108_registration_seat_invitation.sql");
        }
        Configuration config = new Configuration(new Environment("c-seat-invitation",
                new SpringManagedTransactionFactory(), pool));
        for (String mapper : List.of("RegistrationSeatInvitationMapper",
                "RegistrationSeatInvitationRequestMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream input = RegistrationSeatInvitationMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(pool); factory.setConfiguration(config);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        invitations = sql.getMapper(RegistrationSeatInvitationMapper.class);
        requestRecords = sql.getMapper(RegistrationSeatInvitationRequestMapper.class);
        jdbc = new JdbcTemplate(pool);
        transactions = new DataSourceTransactionManager(pool);
    }

    @AfterAll
    static void cleanOwnedSchema() throws Exception {
        if (pool != null) pool.forceCloseAll();
        if (!ownedSchemaCreated) return;
        assertTrue(ownedSchema.matches("chengyin_mybatis_c_seat_invitation_[0-9a-f]{16}"));
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + ownedSchema + "`");
        }
        // DROP 的独立新连接回读；不能用已关闭的池或原执行连接自证。
        try (Connection connection = adminConnection();
             java.sql.PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
            query.setString(1, ownedSchema);
            try (java.sql.ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "owned schema remains after DROP");
            }
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + MybatisItDatabase.host() + ":"
                        + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM cms_registration_seat_invitation_request");
        jdbc.update("DELETE FROM cms_registration_seat_invitation");
        jdbc.update("DELETE FROM player_node_progress");
        jdbc.update("DELETE FROM orient_task_attempt");
        jdbc.update("DELETE FROM orient_task_completion");
        jdbc.update("DELETE FROM topic_route_session");
        jdbc.update("DELETE FROM game_runtime_session");
        jdbc.update("DELETE FROM game_session");
        jdbc.update("DELETE FROM cms_activity");
        jdbc.update("DELETE FROM cms_registration");
        jdbc.update("DELETE FROM cms_topic");
        jdbc.update("INSERT INTO cms_topic (id) VALUES (41)");
        jdbc.update("INSERT INTO cms_activity (id,member_id,topic_id,del_flag) VALUES (61,81,41,0)");
        jdbc.update("INSERT INTO cms_registration (id,owner_type,owner_id,registration_status,payment_status,member_id,order_num)"
                + " VALUES (31,2,61,2,2,51,4)");
    }

    @Test
    void oneActiveInvitationPerSeatWhileTerminalHistoryRemains() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Long first = tx.execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 2, 10);
            assertEquals(1, invitations.insert(row));
            return row.getId();
        });
        assertNotNull(first);

        assertThrows(DataAccessException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> invitations.insert(pending(31L, 2, 11))));

        jdbc.update("UPDATE cms_registration_seat_invitation SET status=2 WHERE id=?", first);
        Long second = new TransactionTemplate(transactions).execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 2, 11);
            assertEquals(1, invitations.insert(row));
            return row.getId();
        });
        assertNotNull(second);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cms_registration_seat_invitation WHERE registration_id=31 AND seat_no=2",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cms_registration_seat_invitation WHERE registration_id=31 AND seat_no=2 AND status IN (0,1)",
                Integer.class));
    }

    @Test
    void concurrentLastIncludedSeatSucceedsExactlyOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> work = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(
                            status -> invitations.insert(pending(31L, 2, 15)));
                    return Boolean.TRUE;
                } catch (DataAccessException duplicate) {
                    return Boolean.FALSE;
                }
            };
            Future<Boolean> left = workers.submit(work);
            Future<Boolean> right = workers.submit(work);
            start.countDown();
            boolean a = left.get(10, TimeUnit.SECONDS);
            boolean b = right.get(10, TimeUnit.SECONDS);
            assertTrue(a ^ b, "exactly one concurrent insert may win the last seat");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cms_registration_seat_invitation WHERE registration_id=31 AND status IN (0,1)",
                    Integer.class));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void claimCasBindsExactlyOneRecipient() throws Exception {
        Long id = new TransactionTemplate(transactions).execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 2, 10);
            assertEquals(1, invitations.insert(row));
            return row.getId();
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> claim = (Callable<Integer>) () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return new TransactionTemplate(transactions).execute(status -> {
                    RegistrationSeatInvitation row = invitations.selectByIdForUpdate(id);
                    return invitations.claim(row.getId(), 99L, new Date());
                });
            };
            Future<Integer> left = workers.submit(claim);
            Future<Integer> right = workers.submit(claim);
            start.countDown();
            int total = left.get(10, TimeUnit.SECONDS) + right.get(10, TimeUnit.SECONDS);
            assertEquals(1, total);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cms_registration_seat_invitation WHERE id=? AND status=1 AND invitee_member_id=99",
                    Integer.class, id));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiryReleasesOnlyUnclaimedReservations() {
        Long pendingExpired = new TransactionTemplate(transactions).execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 2, 10);
            row.setExpiresAt(new Date(System.currentTimeMillis() - 60_000L));
            assertEquals(1, invitations.insert(row));
            return row.getId();
        });
        Long pendingFuture = new TransactionTemplate(transactions).execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 3, 11);
            assertEquals(1, invitations.insert(row));
            return row.getId();
        });
        Long claimed = new TransactionTemplate(transactions).execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 4, 12);
            assertEquals(1, invitations.insert(row));
            assertEquals(1, invitations.claim(row.getId(), 99L, new Date()));
            return row.getId();
        });

        new TransactionTemplate(transactions).executeWithoutResult(
                status -> invitations.expirePending(31L, new Date()));

        assertEquals(3, statusOf(pendingExpired));
        assertEquals(0, statusOf(pendingFuture));
        assertEquals(1, statusOf(claimed));
    }

    @Test
    void originalUseFactsIncludeCompletedAdvancedAndSharedRunsWithoutBorrowingAnotherScope() {
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
        jdbc.update("INSERT INTO topic_route_session VALUES (1,99,41,61,0)");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
        jdbc.update("UPDATE topic_route_session SET status=1");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L),
                "completed route still consumes the seat");
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(100L, 41L, 61L));
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(99L, 41L, 62L));
        jdbc.update("DELETE FROM topic_route_session");

        for (String table : List.of("player_node_progress", "orient_task_attempt", "orient_task_completion")) {
            jdbc.update("INSERT INTO " + table + " VALUES (1,99,41,61)");
            assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L), table);
            assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(100L, 41L, 61L), table);
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO player_node_progress VALUES (1,99,41,NULL)");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 0L));
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
        jdbc.update("DELETE FROM player_node_progress");

        jdbc.update("INSERT INTO game_runtime_session VALUES (1,1,99,41,61,0)");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
        jdbc.update("UPDATE game_runtime_session SET status=1");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L),
                "completed advanced game still consumes the seat");
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(100L, 41L, 61L));
        jdbc.update("UPDATE game_runtime_session SET owner_type=2, owner_id=501, status=2");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L),
                "team history cannot prove an individual seat unused");
        jdbc.update("DELETE FROM game_runtime_session");

        jdbc.update("INSERT INTO game_session VALUES (1,61,'READY',NULL)");
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
        jdbc.update("UPDATE game_session SET status='FINISHED', started_at=NOW(3)");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(99L, 41L, 62L));
        jdbc.update("UPDATE game_session SET status='READY'");
        assertEquals(Boolean.TRUE, invitations.hasOriginalUseFacts(99L, 41L, 61L),
                "a reset status cannot erase a persisted start");
        jdbc.update("DELETE FROM game_session");
        assertEquals(Boolean.FALSE, invitations.hasOriginalUseFacts(99L, 41L, 61L));
    }

    @Test
    void recipientFactsDisappearWhenOriginalIsRefundedOrOutOfScope() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            RegistrationSeatInvitation row = pending(31L, 2, 10);
            assertEquals(1, invitations.insert(row));
            assertEquals(1, invitations.claim(row.getId(), 99L, new Date()));
        });
        assertEquals(1, invitations.selectClaimedFactsByRecipient(99L, 2L, 61L).size());

        jdbc.update("UPDATE cms_registration SET payment_status=4 WHERE id=31");
        assertTrue(invitations.selectClaimedFactsByRecipient(99L, 2L, 61L).isEmpty());

        jdbc.update("UPDATE cms_registration SET payment_status=2, owner_id=62 WHERE id=31");
        assertTrue(invitations.selectClaimedFactsByRecipient(99L, 2L, 61L).isEmpty());

        jdbc.update("UPDATE cms_registration SET owner_id=61 WHERE id=31");
        List<SeatRecipientFact> facts = invitations.selectClaimedFactsByRecipient(99L, 2L, 61L);
        assertEquals(1, facts.size());
        assertEquals(Integer.valueOf(2), facts.get(0).getSeatNo());
        assertTrue(invitations.selectClaimedFactsByRecipient(100L, 2L, 61L).isEmpty());
        jdbc.update("UPDATE cms_registration SET member_id=52 WHERE id=31");
        assertTrue(invitations.selectClaimedFactsByRecipient(99L, 2L, 61L).isEmpty());
        jdbc.update("UPDATE cms_registration SET member_id=51, order_num=1 WHERE id=31");
        assertTrue(invitations.selectClaimedFactsByRecipient(99L, 2L, 61L).isEmpty());
    }

    @Test
    void requestLedgerIsUniquePerRequestIdAndSurvivesReplayLookup() {
        Long invitationId = new TransactionTemplate(transactions).execute(status -> {
            RegistrationSeatInvitation row = pending(31L, 2, 10);
            assertEquals(1, invitations.insert(row));
            return row.getId();
        });
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(1, requestRecords.insert(request("r-1", invitationId, "fp-1")));
        });
        assertNotNull(new TransactionTemplate(transactions).execute(
                status -> requestRecords.selectByRequestIdForUpdate(51L, "r-1")));
        assertThrows(DataAccessException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> requestRecords.insert(request("r-1", invitationId, "fp-2"))));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            RegistrationSeatInvitationRequestRecord otherActor = request("r-1", invitationId, "fp-2");
            otherActor.setMemberId(99L);
            assertEquals(1, requestRecords.insert(otherActor));
        });
        assertEquals("INVITE", requestRecords.selectByRequestId(99L, "r-1").getOperation());
        assertNotEquals(requestRecords.selectByRequestId(51L, "r-1").getRequestFingerprint(),
                requestRecords.selectByRequestId(99L, "r-1").getRequestFingerprint());
    }

    private int statusOf(Long id) {
        return jdbc.queryForObject("SELECT status FROM cms_registration_seat_invitation WHERE id=?",
                Integer.class, id);
    }

    private RegistrationSeatInvitation pending(Long registrationId, Integer seatNo, int generation) {
        Date now = new Date();
        RegistrationSeatInvitation row = new RegistrationSeatInvitation();
        row.setRegistrationId(registrationId);
        row.setSeatNo(seatNo);
        row.setGeneration(generation);
        row.setOwnerMemberId(51L);
        row.setOwnerType(2L);
        row.setOwnerId(61L);
        row.setTopicId(41L);
        row.setStatus(RegistrationSeatInvitation.STATUS_PENDING);
        row.setTokenNonce("0123456789abcdef");
        row.setTokenHash(String.format("%064x", generation + 100L));
        row.setExpiresAt(new Date(now.getTime() + 3_600_000L));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setVersion(0);
        return row;
    }

    private RegistrationSeatInvitationRequestRecord request(String requestId, Long invitationId, String fingerprint) {
        RegistrationSeatInvitationRequestRecord record = new RegistrationSeatInvitationRequestRecord();
        record.setRequestId(requestId);
        record.setMemberId(51L);
        record.setOperation(RegistrationSeatInvitationRequestRecord.OP_INVITE);
        record.setRegistrationId(31L);
        record.setInvitationId(invitationId);
        record.setRequestFingerprint(String.format("%064x", Math.abs(fingerprint.hashCode())));
        record.setResultStatus(0);
        record.setCreateTime(new Date());
        return record;
    }
}
