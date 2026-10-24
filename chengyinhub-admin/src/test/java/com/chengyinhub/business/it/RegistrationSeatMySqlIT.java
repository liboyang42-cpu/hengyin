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
    private static SqlSessionTemplate sql;
    private static final java.util.concurrent.atomic.AtomicReference<Thread> seatThread = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger seedReads = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger seedPayment = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger lockedPayment = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile CountDownLatch seedRead;
    private static volatile CountDownLatch registrationLockAttempt;

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
            ItSchema.applyMigrations(connection, "sql/migration_20261024_registration_verified_seats.sql",
                    "sql/migration_20261024_registration_seat_activity_scope.sql",
                    "sql/migration_20260930_registration_first_successful_scan.sql",
                    "sql/migration_event_cancel_manual_refund_case_20260826.sql",
                    "sql/migration_manual_refund_case_topic_20260918.sql",
                    "sql/migration_partial_chapter_aftercare_20261002.sql",
                    "sql/migration_business_audit_event_20260823.sql");
            String governance = Files.readString(Path.of("sql", "migration_approval_governance_20260828.sql"));
            var actorDdl = Pattern.compile("(?s)ALTER TABLE business_audit_event\\s+ADD COLUMN actor_type.*?;").matcher(governance);
            assertTrue(actorDdl.find()); statement.execute(actorDdl.group()); assertFalse(actorDdl.find());
        }
        Configuration config = new Configuration(new Environment("c-seats", new SpringManagedTransactionFactory(), pool));
        config.getTypeAliasRegistry().registerAlias("CmsRegistration", com.chengyinhub.business.domain.CmsRegistration.class);
        config.addInterceptor(new SeatReadInterleaving());
        for (String mapper : List.of("RegistrationSeatMapper", "CmsRegistrationMapper", "BusinessAuditEventMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream input = RegistrationSeatMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(pool); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject()); seats = sql.getMapper(RegistrationSeatMapper.class);
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
        for (String table : List.of("business_audit_event", "activity_cancel_manual_refund_case", "cms_registration_seat", "coop_group_member_verify", "cms_registration", "club", "cms_activity")) jdbc.update("DELETE FROM " + table);
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
            var settlementFacts = new com.chengyinhub.business.service.RegistrationSeatSettlementReader(seats)
                    .verifiedSeatsForGroup(41L, 61L, future());
            assertEquals(actual.stream().map(RegistrationSeat::getId).toList(),
                    settlementFacts.stream().map(RegistrationSeat::getId).toList());
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
            assertEquals(Integer.valueOf(409), assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> new com.chengyinhub.business.service.RegistrationSeatSettlementReader(seats)
                            .verifiedSeatsForGroup(41L, 61L, future())).getCode());
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
            var settlementFacts = new com.chengyinhub.business.service.RegistrationSeatSettlementReader(seats)
                    .verifiedSeatsForGroup(41L, 61L, future());
            assertEquals(List.of(row.getId()), settlementFacts.stream().map(RegistrationSeat::getId).toList());
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

    @Test void realServiceRejectsSecondSeatAfterRefundCommitsAheadOfItsRegistrationLock() throws Exception {
        // A committed first seat, its original group binding and aggregate used flag.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            seats.lockRegistration(31L); bindGroup();
            RegistrationSeat first = pending(1); seats.insertSeat(first); verify(first); seats.markVerified(first);
            seats.insertSeat(pending(2));
            jdbc.update("UPDATE cms_registration SET verification_status=1,verification_time=NOW() WHERE id=31");
            jdbc.update("INSERT INTO business_audit_event (domain_type,domain_id,owner_member_id,actor_member_id,actor_role_code,"
                    + "action,target_type,target_id,request_id,create_time) VALUES ('REGISTRATION',31,81,81,'CLUB_OWNER',"
                    + "'REGISTRATION_SEAT_REDEEM','REGISTRATION_SEAT',?,'first-seat-fixture',NOW())", first.getId());
        });
        int originalAudits = jdbc.queryForObject("SELECT COUNT(*) FROM business_audit_event", Integer.class);
        var service = realSeatService();
        TransactionTemplate rr = new TransactionTemplate(transactions);
        rr.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        CountDownLatch refundLocked = new CountDownLatch(1), allowRefundCommit = new CountDownLatch(1);
        seedRead = new CountDownLatch(1); registrationLockAttempt = new CountDownLatch(1);
        seedReads.set(0); seedPayment.set(0); lockedPayment.set(0);
        java.util.concurrent.atomic.AtomicLong refundConnection = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondConnection = new java.util.concurrent.atomic.AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> refund = workers.submit(() -> rr.executeWithoutResult(status -> {
                refundConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                seats.lockRegistration(31L);
                jdbc.update("UPDATE cms_registration SET payment_status=4,registration_status=3 WHERE id=31");
                refundLocked.countDown();
                try { assertTrue(allowRefundCommit.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }));
            assertTrue(refundLocked.await(5, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() -> { return rr.execute(status -> {
                secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                seatThread.set(Thread.currentThread());
                try { return service.redeemGroupSeat(31L, 2, 61L, 81L); }
                finally { seatThread.set(null); }
            }); });
            assertTrue(seedRead.await(5, TimeUnit.SECONDS), "the actual service seed SELECT must establish the old RR view");
            assertEquals(2, seedPayment.get());
            assertTrue(registrationLockAttempt.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS), "second seat must wait behind the actual refund row lock");
            allowRefundCommit.countDown(); refund.get(5, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            var error = assertInstanceOf(com.chengyinhub.common.exception.ServiceException.class, refused.getCause());
            assertEquals(Integer.valueOf(403), error.getCode()); assertEquals("订单未完成支付或已退款，无法核销", error.getMessage());
            assertEquals(4, lockedPayment.get(), "full ForUpdate must return the committed refund, not the stale paid seed");
            assertEquals(1, seedReads.get(), "no ordinary reread after the ID lock");
            assertNotEquals(refundConnection.get(), secondConnection.get());
            assertEquals(4, jdbc.queryForObject("SELECT payment_status FROM cms_registration WHERE id=31", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration_seat WHERE status=1", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT status FROM cms_registration_seat WHERE registration_id=31 AND seat_no=2", Integer.class));
            assertEquals(originalAudits, jdbc.queryForObject("SELECT COUNT(*) FROM business_audit_event", Integer.class));
        } finally {
            allowRefundCommit.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            seatThread.set(null); seedRead = null; registrationLockAttempt = null;
        }
    }

    private static com.chengyinhub.business.service.RegistrationSeatService realSeatService() {
        var service = new com.chengyinhub.business.service.RegistrationSeatService();
        var originalOrders = new com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl();
        var registrationMapper = sql.getMapper(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(originalOrders, "cmsRegistrationMapper", registrationMapper);
        var topicMapper = org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var topic = new com.chengyinhub.business.domain.CmsTopic(); topic.setId(41L); topic.setIsSettlement(0);
        org.mockito.Mockito.when(topicMapper.selectCmsTopicByIdForUpdate(41L)).thenReturn(topic);
        var activityMapper = org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        var activity = new com.chengyinhub.business.domain.CmsActivity(); activity.setId(61L); activity.setTopicId(41L); activity.setClubId(71L); activity.setDelFlag(0);
        org.mockito.Mockito.when(activityMapper.selectCmsActivityByIdForUpdate(61L)).thenReturn(activity);
        var clubs = org.mockito.Mockito.mock(com.chengyinhub.business.service.IClubMemberService.class);
        org.mockito.Mockito.when(clubs.isTopicOwner(41L, 71L, 81L)).thenReturn(true);
        Object[][] fields = {{"seats", seats},
                {"settlementReader", new com.chengyinhub.business.service.RegistrationSeatSettlementReader(seats)},
                {"registrations", registrationMapper}, {"registrationService", originalOrders},
                {"topics", topicMapper}, {"activities", activityMapper}, {"clubs", clubs},
                {"audits", sql.getMapper(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class)}};
        for (Object[] field : fields) org.springframework.test.util.ReflectionTestUtils.setField(service, (String) field[0], field[1]);
        var proxy = new org.springframework.aop.framework.ProxyFactory(service); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.chengyinhub.business.service.RegistrationSeatService) proxy.getProxy();
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type = org.apache.ibatis.executor.Executor.class,
            method = "query", args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class,
                org.apache.ibatis.session.RowBounds.class, org.apache.ibatis.session.ResultHandler.class}))
    public static class SeatReadInterleaving implements org.apache.ibatis.plugin.Interceptor {
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            if (Thread.currentThread() != seatThread.get()) return call.proceed();
            assertEquals(Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ),
                    org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            String statement = ((org.apache.ibatis.mapping.MappedStatement) call.getArgs()[0]).getId();
            if (statement.endsWith("RegistrationSeatMapper.lockRegistration")) registrationLockAttempt.countDown();
            Object result = call.proceed();
            if (statement.endsWith("CmsRegistrationMapper.selectCmsRegistrationById")) {
                var row = (com.chengyinhub.business.domain.CmsRegistration) ((List<?>) result).get(0);
                seedPayment.set(row.getPaymentStatus()); seedReads.incrementAndGet(); seedRead.countDown();
            } else if (statement.endsWith("CmsRegistrationMapper.selectCmsRegistrationByIdForUpdate")) {
                var row = (com.chengyinhub.business.domain.CmsRegistration) ((List<?>) result).get(0);
                lockedPayment.set(row.getPaymentStatus());
            }
            return result;
        }
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
