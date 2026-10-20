package com.chengyinhub.business.longrun;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.vo.ClubEventSeriesView;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Opt-in real MySQL snapshot regression source; never execute in Cloud. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E01_10Test {
    private static final Long OWNER = 42L, CLUB = 21L, SERIES = 61L;
    private MysqlIntegrationTestConfig mysql;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private IClubEventOpsService service;
    private LocalDate day;
    private String pauseAfter;
    private Runnable writer;
    private boolean interleaved;
    private boolean created;

    @BeforeAll
    void createScratch() throws Exception {
        for (String key : Arrays.asList("CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "CHENGYIN_TEST_MYSQL_USER")) {
            assertNotNull(System.getenv(key), "Explicit authorized test setting required: " + key);
            assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e01snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
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
        for (String table : Arrays.asList("cms_activity", "cms_registration")) createTable(base, table);
        String migration = new String(Files.readAllBytes(Paths.get("sql/migration_club_role_event_ops_20260823.sql")), StandardCharsets.UTF_8);
        for (String table : Arrays.asList("club_event_series", "club_event_occurrence", "club_event_waitlist")) createTable(migration, table);
        Configuration config = new Configuration(new Environment("e01-snapshot", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        ClubEventOpsMapper real = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ClubEventOpsMapper.class);
        ClubEventOpsMapper interleavingMapper = (ClubEventOpsMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ClubEventOpsMapper.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getTargetException();
                    }
                    if (!interleaved && method.getName().equals(pauseAfter)) {
                        // The actual production query has completed. The independent writer commits before the next query.
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                        assertEquals(Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ),
                                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                        interleaved = true;
                        writer.run();
                    }
                    return result;
                });
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(eq(OWNER), eq(CLUB), isNull(), anyString())).thenReturn(
                new ClubAccessContext(OWNER, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), interleavingMapper, mock(ClubMemberMapper.class),
                mock(ClubLeadMapper.class), mock(IClubRoleAssignmentService.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubEventOpsService.class);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (IClubEventOpsService) factory.getProxy();
    }

    @BeforeEach
    void seed() {
        pauseAfter = null;
        writer = null;
        interleaved = false;
        day = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(14);
        for (String table : Arrays.asList("club_event_waitlist", "cms_registration", "club_event_occurrence", "club_event_series", "cms_activity")) jdbc.execute("delete from " + table);
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,topic_id,club_id) values (71,42,'E01 snapshot synthetic',1,1,0,?,31,21)", moment(0, 30));
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time) values (61,21,31,'CUSTOM_DATES','Asia/Shanghai',42,1,20,'ACTIVE',0,42,'e01-snap',?,now())",
                String.join("", Collections.nCopies(64, "a")));
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,occurrence_at,default_lead_member_id,status,version,create_time) values (81,61,71,?,42,'ACTIVE',0,now())", moment(0, 30));
    }

    @Test
    void detailKeepsParentVersionAndChildrenAtFirstSnapshotThenRefreshes() {
        pauseAfter = "selectActiveSeriesByIdAndClub";
        writer = this::commitReschedule;

        ClubEventSeriesView old = service.getSeries(OWNER, CLUB, SERIES);

        assertTrue(interleaved);
        assertEquals(Integer.valueOf(0), old.getVersion());
        assertEquals("00:30", old.getStartTime());
        assertEquals(Collections.singletonList(day.toString()), old.getFutureDates());
        assertEquals(1, independent().queryForObject("select version from club_event_series where id=61", Integer.class));
        ClubEventSeriesView fresh = service.getSeries(OWNER, CLUB, SERIES);
        assertEquals(Integer.valueOf(1), fresh.getVersion());
        assertEquals("16:45", fresh.getStartTime());
        assertEquals(Collections.singletonList(day.plusDays(1).toString()), fresh.getFutureDates());
    }

    @Test
    void listKeepsSeriesAndTimeAtFirstSnapshotThenRefreshes() {
        pauseAfter = "selectActiveSeriesByClub";
        writer = this::commitReschedule;

        ClubEventSeriesView old = service.listSeries(OWNER, CLUB).get(0);

        assertTrue(interleaved);
        assertEquals(Integer.valueOf(0), old.getVersion());
        assertEquals("00:30", old.getStartTime());
        ClubEventSeriesView fresh = service.listSeries(OWNER, CLUB).get(0);
        assertEquals(Integer.valueOf(1), fresh.getVersion());
        assertEquals("16:45", fresh.getStartTime());
    }

    @Test
    void occurrenceCountsAndProtectedHistoryUseOneSnapshotThenRefresh() {
        pauseAfter = "selectSeriesOccurrenceRows";
        writer = () -> independent().update("insert into cms_registration(registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,registration_status,pay_expire_time) values ('E01-SNAPSHOT',2,71,91,1,99,1,1,1,3,now())");

        Map<String, Object> old = service.listSeriesOccurrences(OWNER, CLUB, SERIES).get(0);

        assertTrue(interleaved);
        assertEquals(0, old.get("signupCount"));
        assertEquals(0, old.get("refundedCount"));
        assertEquals(true, old.get("editable"), "The first snapshot predates the committed registration history");
        assertNull(old.get("lockReason"));
        assertEquals(1, independent().queryForObject("select count(*) from cms_registration", Integer.class));
        Map<String, Object> fresh = service.listSeriesOccurrences(OWNER, CLUB, SERIES).get(0);
        assertEquals(1, fresh.get("refundedCount"));
        assertEquals(false, fresh.get("editable"));
        assertEquals("已有报名、候补或结算事实", fresh.get("lockReason"));
    }

    private void commitReschedule() {
        // Independent JDBC transaction: no reader DataSource/transaction binding is reused.
        try (Connection connection = java.sql.DriverManager.getConnection(mysql.databaseUrl(), mysql.user(), mysql.password())) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("update club_event_series set version=1 where id=61")) {
                    assertEquals(1, statement.executeUpdate());
                }
                Timestamp updated = Timestamp.from(day.plusDays(1).atTime(16, 45)
                        .atZone(ZoneId.of("Asia/Shanghai")).toInstant());
                try (PreparedStatement statement = connection.prepareStatement("update club_event_occurrence set occurrence_at=?,version=1 where id=81")) {
                    statement.setTimestamp(1, updated);
                    assertEquals(1, statement.executeUpdate());
                }
                try (PreparedStatement statement = connection.prepareStatement("update cms_activity set start_date=? where id=71")) {
                    statement.setTimestamp(1, updated);
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | AssertionError failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Independent schedule commit failed", failure);
        }
    }

    private Date moment(int hour, int minute) {
        return Date.from(day.atTime(hour, minute).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
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
}
