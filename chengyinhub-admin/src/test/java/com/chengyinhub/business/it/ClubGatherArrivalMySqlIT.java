package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.ClubEventLeadResolver;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.support.ClubGatherArrivalPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiClubLeadController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.*;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真MySQL/真实入口事务；固定principal仅用于SQL层测试，不代表HTTP鉴权或设备定位验收。 */
class ClubGatherArrivalMySqlIT {
    private static PooledDataSource ds;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactions;
    private ApiClubLeadController controller;
    private CmsRegistrationServiceImpl registrations;

    @BeforeAll static void schema() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("club_gather_arrival"), MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection connection = ds.getConnection()) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
            ItSchema.applyMigrations(connection, "sql/migration_20260930_registration_first_successful_scan.sql", "sql/migration_club_lead.sql");
        }
        Configuration config = new Configuration(new Environment("gather", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : Arrays.asList("CmsActivityMapper", "CmsRegistrationMapper", "ClubLeadMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream input = ClubGatherArrivalMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject()); transactions = new DataSourceTransactionManager(ds);
    }

    @BeforeEach void seed() throws Exception {
        long now = System.currentTimeMillis();
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : Arrays.asList("club_lead_checkin", "club_lead_state", "cms_registration", "cms_activity")) statement.executeUpdate("DELETE FROM " + table);
            executeTimes(connection, "INSERT INTO cms_activity(id,member_id,club_id,del_flag,start_date,end_date,latitude,longitude) VALUES(100,9,5,0,?,?,'31.23','121.47')", now + TimeUnit.MINUTES.toMillis(30), now + TimeUnit.HOURS.toMillis(2));
            statement.executeUpdate("INSERT INTO club_lead_state(activity_id,leader_member_id,status,create_time) VALUES(100,9,2,NOW())");
            executeTimes(connection, "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,member_id,ticket_id,ticket_price,original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time) VALUES(1,'gather-real',2,100,7,1,0,0,0,0,2,2,?)", now + TimeUnit.HOURS.toMillis(1));
        }
        CmsActivityServiceImpl activities = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(activities, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
        registrations = spy(new CmsRegistrationServiceImpl());
        ReflectionTestUtils.setField(registrations, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ClubEventLeadResolver resolver = mock(ClubEventLeadResolver.class); when(resolver.resolveLeaderMemberId(any())).thenReturn(9L);
        ApiClubLeadController target = new ApiClubLeadController() { @Override public Long getAppUserId() { return 7L; } };
        ReflectionTestUtils.setField(target, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "cmsActivityService", activities);
        ReflectionTestUtils.setField(target, "cmsRegistrationService", registrations);
        ReflectionTestUtils.setField(target, "clubLeadMapper", sql.getMapper(ClubLeadMapper.class));
        ReflectionTestUtils.setField(target, "clubEventLeadResolver", resolver);
        ReflectionTestUtils.setField(target, "clubGatherArrivalPolicy", new ClubGatherArrivalPolicy());
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        controller = (ApiClubLeadController) proxy.getProxy();
    }

    @AfterAll static void close() { if (ds != null) ds.forceCloseAll(); }

    @Test void cancellationCommitsFirstThenBlockedArrivalCannotWriteAStaleFact() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), arrivalStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancellation = pool.submit(() -> cancel(locked, release));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<AjaxResult> arrival = pool.submit(() -> { arrivalStarted.countDown(); return controller.arrive("100", 31.23D, 121.47D); });
            assertTrue(arrivalStarted.await(10, TimeUnit.SECONDS)); assertThrows(TimeoutException.class, () -> arrival.get(200, TimeUnit.MILLISECONDS));
            assertEquals(0, checkins()); release.countDown(); cancellation.get(10, TimeUnit.SECONDS);
            assertNotEquals(200, arrival.get(10, TimeUnit.SECONDS).get("code")); assertEquals(0, checkins()); assertTrue(cancelled());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void arrivalHoldsTheActivityLockThroughItsFactThenCancellationWaitsForCommit() throws Exception {
        CountDownLatch beforeInsert = new CountDownLatch(1), releaseArrival = new CountDownLatch(1), cancellationStarted = new CountDownLatch(1);
        doAnswer(call -> {
            List<CmsRegistration> actual = sql.getMapper(CmsRegistrationMapper.class).selectCmsRegistrationList(call.getArgument(0));
            beforeInsert.countDown(); await(releaseArrival); return actual;
        }).when(registrations).selectCmsRegistrationList(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> arrival = pool.submit(() -> controller.arrive("100", 31.23D, 121.47D));
            assertTrue(beforeInsert.await(10, TimeUnit.SECONDS));
            Future<?> cancellation = pool.submit(() -> { cancellationStarted.countDown(); cancel(null, null); });
            assertTrue(cancellationStarted.await(10, TimeUnit.SECONDS)); assertThrows(TimeoutException.class, () -> cancellation.get(200, TimeUnit.MILLISECONDS));
            assertEquals(0, checkins()); assertFalse(cancelled()); releaseArrival.countDown();
            assertEquals(200, arrival.get(10, TimeUnit.SECONDS).get("code")); cancellation.get(10, TimeUnit.SECONDS);
            assertEquals(1, checkins()); assertTrue(cancelled());
        } finally { releaseArrival.countDown(); pool.shutdownNow(); }
    }

    @Test void actualInvalidTimePointPositionAndUnpaidScopeLeaveNoAttendance() throws Exception {
        execute("UPDATE cms_activity SET start_date=?", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(90));
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); assertEquals(0, checkins());
        execute("UPDATE cms_activity SET start_date=?,latitude=NULL", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30));
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); assertEquals(0, checkins());
        execute("UPDATE cms_activity SET latitude='31.23'");
        assertNotEquals(200, controller.arrive("100", Double.NaN, 121.47D).get("code")); assertEquals(0, checkins());
        assertNotEquals(200, controller.arrive("100", 32D, 121.47D).get("code")); assertEquals(0, checkins());
        execute("UPDATE cms_registration SET payment_status=0");
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); assertEquals(0, checkins());
    }

    @Test void duplicateConcurrentArrivalHasOneActualFactAndDoesNotRedeemTheOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<AjaxResult> action = () -> { await(start); return controller.arrive("100", 31.23D, 121.47D); };
            Future<AjaxResult> first = pool.submit(action), second = pool.submit(action); start.countDown();
            assertEquals(200, first.get(10, TimeUnit.SECONDS).get("code")); assertEquals(200, second.get(10, TimeUnit.SECONDS).get("code"));
            assertEquals(1, checkins());
            try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT payment_status,registration_status,verification_time,first_successful_scan_at FROM cms_registration WHERE id=1")) {
                assertTrue(row.next()); assertEquals(2, row.getInt(1)); assertEquals(2, row.getInt(2)); assertNull(row.getTimestamp(3)); assertNull(row.getTimestamp(4));
            }
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private void cancel(CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(transactions).execute(status -> {
            CmsActivityMapper mapper = sql.getMapper(CmsActivityMapper.class); CmsActivity row = mapper.selectCmsActivityByIdForUpdate(100L); assertNotNull(row);
            CmsActivity update = new CmsActivity(); update.setId(100L); update.setCancelTime(new Date()); assertEquals(1, mapper.updateCmsActivity(update));
            if (locked != null) locked.countDown(); if (release != null) await(release); return null;
        });
    }

    private int checkins() throws Exception {
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT count(*) FROM club_lead_checkin WHERE activity_id=100 AND member_id=7")) { assertTrue(row.next()); return row.getInt(1); }
    }

    private boolean cancelled() throws Exception {
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT cancel_time FROM cms_activity WHERE id=100")) { assertTrue(row.next()); return row.getTimestamp(1) != null; }
    }

    private void execute(String query, long... times) throws Exception { try (Connection connection = ds.getConnection()) { executeTimes(connection, query, times); } }
    private void executeTimes(Connection connection, String query, long... times) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            // DATETIME values must use the same zone as MybatisItDatabase's JDBC reader.
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
            for (int i = 0; i < times.length; i++) statement.setTimestamp(i + 1, new Timestamp(times[i]), calendar);
            statement.executeUpdate();
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture barrier timed out"); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
    }
}
