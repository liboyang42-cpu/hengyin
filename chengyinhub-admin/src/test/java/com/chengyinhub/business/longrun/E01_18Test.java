package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubWaitlistService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** NOT_RUN. New OFFERED activity preflight, existing hold release, and real activity lock interleavings. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E01_18Test {
    private static final Long CLUB = 21L, ACTIVITY = 71L, TICKET = 91L, FIRST_MEMBER = 101L;
    private MysqlIntegrationTestConfig mysql;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ClubEventOpsMapper realMapper;
    private OmsTicketMapper realTickets;
    private IClubWaitlistService service;
    private ClubWaitlistTokenCodec codec;
    private final ThreadLocal<String> role = new ThreadLocal<>();
    private final AtomicInteger restores = new AtomicInteger();
    private final AtomicInteger deductions = new AtomicInteger();
    private volatile Gate gate;
    private boolean created;
    private Date now;

    @BeforeAll
    void createScratch() throws Exception {
        for (String key : Arrays.asList("CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "CHENGYIN_TEST_MYSQL_USER")) {
            assertNotNull(System.getenv(key), "Explicit authorized test setting required: " + key);
            assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e01open_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        JdbcTemplate admin = admin();
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
        created = true;
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        String base;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("it-schema/run41-prod-ddl.sql")) {
            assertNotNull(input);
            try (Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                base = scanner.next();
            }
        }
        for (String table : Arrays.asList("club", "club_member", "cms_topic", "cms_activity", "cms_registration", "oms_ticket")) createTable(base, table);
        String migration = new String(Files.readAllBytes(Paths.get("sql/migration_club_role_event_ops_20260823.sql")), StandardCharsets.UTF_8);
        // master 候补晋升/过期同事务写站内通知三表（见 ClubEventOpsMapper.xml insertCampaign/Delivery/Outbox）。
        for (String table : Arrays.asList("club_event_series", "club_event_occurrence", "club_event_waitlist",
                "club_event_notification_campaign", "club_event_notification_delivery", "club_event_notification_outbox")) createTable(migration, table);
        // This suite requires PROCESS permission so blocked SQL is observed, not inferred from thread timing.
        independent().queryForObject("select count(*) from information_schema.innodb_trx", Integer.class);
        Configuration config = new Configuration(new Environment("e01-offer-activity", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String name : Arrays.asList("ClubEventOpsMapper", "OmsTicketMapper")) {
            String resource = "mapper/business/" + name + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        realMapper = sql.getMapper(ClubEventOpsMapper.class);
        realTickets = sql.getMapper(OmsTicketMapper.class);
        ClubEventOpsMapper probed = (ClubEventOpsMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ClubEventOpsMapper.class}, (proxy, method, args) -> {
                    Gate current = gate;
                    boolean policyLock = method.getName().equals("selectSeriesPolicyByActivityForUpdate");
                    boolean offerPreflight = method.getName().equals("selectPromotableActivityForUpdate");
                    if (policyLock && current != null && "second".equals(role.get())) {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        jdbc.execute("set session innodb_lock_wait_timeout=10");
                        current.secondConnection.set(jdbc.queryForObject("select connection_id()", Long.class));
                        current.secondAttempt.countDown();
                    }
                    Object value;
                    try {
                        value = method.invoke(realMapper, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getTargetException();
                    }
                    if (offerPreflight && current != null && "first".equals(role.get())) {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        assertNotNull(value, "The first operation must hold the real current activity preflight lock");
                        current.firstLocked.countDown();
                        assertTrue(current.releaseFirst.await(30, TimeUnit.SECONDS), "First-operation gate timed out");
                    }
                    return value;
                });
        OmsTicketMapper countedTickets = (OmsTicketMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OmsTicketMapper.class}, (proxy, method, args) -> {
                    Object value;
                    try {
                        value = method.invoke(realTickets, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getTargetException();
                    }
                    if (value instanceof Number && ((Number) value).intValue() == 1) {
                        if (method.getName().equals("restoreInventory")) restores.incrementAndGet();
                        if (method.getName().equals("deductInventory")) deductions.incrementAndGet();
                    }
                    return value;
                });
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        codec = new ClubWaitlistTokenCodec("e01-18-synthetic-token-secret");
        // This collaborator is not called by cancel/promote; membership and policy decisions use real SQL.
        ClubWaitlistServiceImpl target = new ClubWaitlistServiceImpl(probed, countedTickets,
                mock(IClubParticipationGuard.class), codec, manager);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubWaitlistService.class);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (IClubWaitlistService) factory.getProxy();
    }

    @BeforeEach
    void seed() {
        gate = null;
        restores.set(0);
        deductions.set(0);
        now = new Date(System.currentTimeMillis() / 1000L * 1000L);
        for (String table : Arrays.asList("club_event_waitlist", "club_event_occurrence", "club_event_series", "cms_registration", "oms_ticket", "cms_activity", "cms_topic", "club_member", "club")) jdbc.execute("delete from " + table);
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (21,42,'E01 race synthetic',1,0)");
        jdbc.update("insert into club_member(club_id,member_id,status,del_flag) values (21,101,1,0),(21,102,1,0),(21,103,1,0)");
        jdbc.update("insert into cms_topic(id,member_id,name,club_id,del_flag) values (31,42,'E01 race synthetic',21,0)");
        Date future = new Date(now.getTime() + TimeUnit.DAYS.toMillis(2));
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,end_date,topic_id,club_id) values (71,42,'E01 race synthetic',1,1,0,?,?,31,21)", future, future);
        jdbc.update("insert into oms_ticket(id,member_id,owner_type,owner_id,name,total_inventory,remaining_inventory,del_flag,signup_deadline) values (91,42,2,71,'E01 race',1,1,0,?)", future);
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time) values (61,21,31,'ONCE','Asia/Shanghai',42,1,20,'ACTIVE',0,42,'e01-race',?,now())",
                String.join("", Collections.nCopies(64, "a")));
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,occurrence_at,default_lead_member_id,status,version,create_time) values (71,61,71,?,42,'ACTIVE',0,now())", future);
        // Equal join times deliberately require the production id tie-break to choose 82 before 83.
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,version) values (81,21,71,91,101,'WAITING',?,0),(82,21,71,91,102,'WAITING',?,0),(83,21,71,91,103,'WAITING',?,0)",
                new Date(now.getTime() - 2000), new Date(now.getTime() - 1000), new Date(now.getTime() - 1000));
    }

    @Test
    void newOfferRejectsUnavailableActivityUsingExactExistingMaintenancePredicate() {
        for (String change : Arrays.asList("publish_status=0", "status=0", "end_date=null",
                "end_date=now()", "end_date=date_sub(now(),interval 1 hour)")) {
            seed();
            jdbc.update("update cms_activity set " + change + " where id=71");
            assertFalse(service.promoteNext(ACTIVITY, TICKET, new Date()), change);
            assertNoFreshOffer(1);
            assertEquals("WAITING", independent().queryForObject("select state from club_event_waitlist where id=81", String.class));
            assertEquals(0, deductions.get());
        }
        seed();
        assertNull(realMapper.selectPromotableActivityForUpdate(ACTIVITY, 999L, now));
        assertNull(realMapper.selectPromotableActivityForUpdate(999L, CLUB, now));
        assertEquals(ACTIVITY, realMapper.selectPromotableActivityForUpdate(ACTIVITY, CLUB, now));
        assertTrue(service.promoteNext(ACTIVITY, TICKET, now), "Open activity still admits the first FIFO member");
        assertEquals("OFFERED", independent().queryForObject("select state from club_event_waitlist where id=81", String.class));
        assertEquals(0, stock());
    }

    @Test
    void cancellingOldHoldAfterUnpublishCommitsRestoreWithoutNewOffer() {
        assertTrue(service.promoteNext(ACTIVITY, TICKET, now));
        jdbc.update("update cms_activity set publish_status=0 where id=71");
        assertTrue(service.cancel(FIRST_MEMBER, ACTIVITY, TICKET, now));
        assertEquals("CANCELLED", independent().queryForObject("select state from club_event_waitlist where id=81", String.class));
        assertNoFreshOffer(1);
        assertEquals(1, restores.get());
        assertFalse(service.cancel(FIRST_MEMBER, ACTIVITY, TICKET, now));
        assertEquals(1, restores.get());
        assertEquals(1, stock());
    }

    @Test
    void expiringOldHoldAfterActivityEndCommitsRestoreWithoutNewOffer() {
        assertTrue(service.promoteNext(ACTIVITY, TICKET, now));
        Date deadline = realMapper.selectWaitlistById(81L).getOfferExpiresAt();
        jdbc.update("update cms_activity set end_date=? where id=71", new Date(now.getTime() - 1000));
        assertTrue(service.expireOffered(81L, deadline));
        assertEquals("EXPIRED", independent().queryForObject("select state from club_event_waitlist where id=81", String.class));
        assertNoFreshOffer(1);
        assertEquals(1, restores.get());
        assertFalse(service.expireOffered(81L, deadline));
        assertEquals(1, restores.get());
    }

    @Test
    void admittedOfferCommitsBeforeBlockedUnpublishAndLaterReleaseDoesNotReoffer() throws Exception {
        boolean[] results = race(() -> service.promoteNext(ACTIVITY, TICKET, now), this::unpublishWithObservedConnection);
        assertTrue(results[0]);
        assertTrue(results[1]);
        assertEquals(0, independent().queryForObject("select publish_status from cms_activity where id=71", Integer.class));
        assertEquals("OFFERED", independent().queryForObject("select state from club_event_waitlist where id=81", String.class),
                "Admission precedes unpublish; do not retroactively revoke an existing hold");
        assertEquals(0, stock());
        Date deadline = realMapper.selectWaitlistById(81L).getOfferExpiresAt();
        assertTrue(service.expireOffered(81L, deadline));
        assertNoFreshOffer(1);
        assertEquals(1, restores.get());
    }

    @Test
    void unpublishHoldingActivityLockCommitsBeforeBlockedPromotionWhichThenReturnsFalse() throws Exception {
        Gate current = new Gate();
        gate = current;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (java.sql.Connection publisher = java.sql.DriverManager.getConnection(mysql.databaseUrl(), mysql.user(), mysql.password())) {
            publisher.setAutoCommit(false);
            try (java.sql.Statement statement = publisher.createStatement()) {
                assertEquals(1, statement.executeUpdate("update cms_activity set publish_status=0 where id=71"));
            }
            Future<Boolean> promotion = pool.submit(() -> asRole("second", () -> service.promoteNext(ACTIVITY, TICKET, now)));
            assertTrue(current.secondAttempt.await(5, TimeUnit.SECONDS));
            awaitRealLockWait(current.secondConnection.get());
            assertThrows(TimeoutException.class, () -> promotion.get(200, TimeUnit.MILLISECONDS));
            publisher.commit();
            assertFalse(promotion.get(10, TimeUnit.SECONDS));
            assertNoFreshOffer(1);
            assertEquals(0, restores.get());
            assertEquals(0, deductions.get());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
            gate = null;
        }
    }

    private Boolean unpublishWithObservedConnection() throws Exception {
        try (java.sql.Connection publisher = java.sql.DriverManager.getConnection(mysql.databaseUrl(), mysql.user(), mysql.password());
             java.sql.Statement statement = publisher.createStatement()) {
            statement.execute("set session innodb_lock_wait_timeout=10");
            try (java.sql.ResultSet result = statement.executeQuery("select connection_id()")) {
                assertTrue(result.next());
                gate.secondConnection.set(result.getLong(1));
            }
            gate.secondAttempt.countDown();
            return statement.executeUpdate("update cms_activity set publish_status=0 where id=71") == 1;
        }
    }

    private int stock() {
        return independent().queryForObject("select remaining_inventory from oms_ticket where id=91", Integer.class);
    }

    private void assertNoFreshOffer(int expectedStock) {
        JdbcTemplate committed = independent();
        assertEquals(0, committed.queryForObject("select count(*) from club_event_waitlist where state='OFFERED'", Integer.class));
        assertEquals("WAITING", committed.queryForObject("select state from club_event_waitlist where id=82", String.class));
        assertEquals("WAITING", committed.queryForObject("select state from club_event_waitlist where id=83", String.class));
        assertEquals(expectedStock, stock());
        assertEquals(0, committed.queryForObject("select count(*) from oms_ticket where remaining_inventory<0 or remaining_inventory>total_inventory", Integer.class));
    }

    private boolean[] race(Callable<Boolean> firstOperation, Callable<Boolean> secondOperation) throws Exception {
        Gate current = new Gate();
        gate = current;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> asRole("first", firstOperation));
            assertTrue(current.firstLocked.await(5, TimeUnit.SECONDS), "First operation did not acquire the activity preflight lock");
            Future<Boolean> second = pool.submit(() -> asRole("second", secondOperation));
            assertTrue(current.secondAttempt.await(5, TimeUnit.SECONDS), "Second operation did not reach SQL lock attempt");
            awaitRealLockWait(current.secondConnection.get());
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS), "Blocked SQL must not complete before first commit");
            current.releaseFirst.countDown();
            return new boolean[]{first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)};
        } finally {
            current.releaseFirst.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "Race workers must stop before scratch cleanup");
            gate = null;
        }
    }

    private boolean asRole(String name, Callable<Boolean> work) throws Exception {
        role.set(name);
        try {
            return work.call();
        } finally {
            role.remove();
        }
    }

    private void awaitRealLockWait(long connectionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = independent().queryForObject("select count(*) from information_schema.innodb_trx where trx_mysql_thread_id=? and trx_state='LOCK WAIT'", Integer.class, connectionId);
            if (waiting != null && waiting == 1) return;
            Thread.sleep(20L);
        }
        fail("Second connection never entered an observed InnoDB LOCK WAIT");
    }

    private void createTable(String source, String table) {
        Matcher start = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`? \\(").matcher(source);
        assertTrue(start.find(), table);
        Matcher end = Pattern.compile(";\\s*\\n").matcher(source);
        assertTrue(end.find(start.start()), table);
        jdbc.execute(source.substring(start.start(), end.start()));
    }
    private JdbcTemplate independent() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password()));
    }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }
    @AfterAll
    void dropScratch() {
        if (!created) return;
        JdbcTemplate admin = admin();
        admin.execute("DROP DATABASE `" + mysql.databaseName() + "`");
        assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
    }
    private static final class Gate {
        private final CountDownLatch firstLocked = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondAttempt = new CountDownLatch(1);
        private final AtomicLong secondConnection = new AtomicLong();
    }
}
