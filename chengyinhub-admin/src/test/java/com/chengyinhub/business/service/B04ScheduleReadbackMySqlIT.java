package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessServiceImpl;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in authorized scratch MySQL only; no Cloud execution. Persistence and service transaction are real. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B04ScheduleReadbackMySqlIT {
    private static final long OWNER = 42L, CLUB = 21L, TOPIC = 31L, SERIES = 61L, ACTIVITY = 71L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate jdbc;
    private ClubEventOpsMapper mapper;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager transactions;
    private IClubAccessService access;
    private IClubEventOpsService service;
    private IClubSessionService sessions;
    private IClubRoleAssignmentService roles;
    private LocalDate day;
    private boolean created;

    @BeforeAll
    void createScratch() throws Exception {
        for (String key : Arrays.asList("CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX",
                "CHENGYIN_TEST_MYSQL_USER")) {
            assertNotNull(System.getenv(key), "Explicit authorized test setting required: " + key);
            assertFalse(System.getenv(key).trim().isEmpty(), key);
        }
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"), "Explicit test password required (may be empty)");
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e01_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        JdbcTemplate admin = independentAdmin();
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)),
                "Oracle MySQL 8+ required; enabled suite must fail on missing prerequisites");
        // No DROP-before-CREATE: a collision must fail instead of destroying a preexisting schema.
        admin.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
        created = true;
        jdbc = independentJdbc();
        String base;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("it-schema/run41-prod-ddl.sql")) {
            assertNotNull(input);
            try (Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                base = scanner.next();
            }
        }
        for (String table : Arrays.asList("club", "cms_activity", "cms_registration")) createTable(base, table);
        com.chengyinhub.business.it.P0AcceptanceSchema.baseTables(jdbc, "ums_member", "club_member");
        String migration = new String(Files.readAllBytes(Paths.get("sql/migration_club_role_event_ops_20260823.sql")), StandardCharsets.UTF_8);
        for (String table : Arrays.asList("club_event_series", "club_event_occurrence", "club_event_waitlist")) createTable(migration, table);
        createTable(Files.readString(Paths.get("sql/migration_club_role_assignment_20260823.sql"),
                StandardCharsets.UTF_8), "club_role_assignment");
        Configuration configuration = new Configuration(new Environment("e01", new SpringManagedTransactionFactory(), dataSource()));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String name : Arrays.asList("ClubEventOps", "Club", "ClubMember", "ClubRoleAssignment", "CmsActivity", "UmsMember")) {
            String resource = "mapper/business/" + name + "Mapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        mapper = sql.getMapper(ClubEventOpsMapper.class);
        transactions = new DataSourceTransactionManager(dataSource());
        // Keep Spring's production-default preparation. Connector/J propagates setReadOnly
        // to MySQL; a second executeUpdate("SET TRANSACTION READ ONLY") is not needed.
        wire();
    }

    private void createTable(String source, String table) {
        Matcher start = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`? \\(").matcher(source);
        assertTrue(start.find(), "Missing source DDL: " + table);
        Matcher end = Pattern.compile(";\\s*\\n").matcher(source);
        assertTrue(end.find(start.start()), "Missing line-end DDL terminator: " + table);
        jdbc.execute(source.substring(start.start(), end.start()));
    }

    @AfterAll
    void dropScratchAndReadBack() {
        if (!created) return;
        JdbcTemplate admin = independentAdmin();
        admin.execute("DROP DATABASE `" + mysql.databaseName() + "`");
        assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                Integer.class, mysql.databaseName()));
    }

    @BeforeEach
    void reset() {
        day = LocalDate.now(SHANGHAI).plusDays(14);
        seed("00:30");
        clearInvocations(sessions, roles);
    }

    @Test
    void assemblyTimesSurviveRealMapperReadbackAcrossJvmZones() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : Arrays.asList("UTC", "America/Los_Angeles", "Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (String time : Arrays.asList("00:30", "16:45")) {
                    seed(time); // JDBC writes and mapper reads use the same current JVM zone.
                    assertEquals(time, service.getSeries(OWNER, CLUB, SERIES).getStartTime(), zone);
                    assertEquals(Collections.singletonList(day.toString()), service.getSeries(OWNER, CLUB, SERIES).getFutureDates());
                    assertEquals(time, service.listSeries(OWNER, CLUB).get(0).getStartTime(), zone);
                    assertEquals(moment(time).getTime(), independentJdbc().queryForObject(
                            "select occurrence_at from club_event_occurrence where id=81", Date.class).getTime());
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void mixedAndEmptyFutureRowsReturnNullInsteadOfInventingDefault() {
        Date second = Date.from(day.plusDays(7).atTime(16, 45).atZone(SHANGHAI).toInstant());
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,topic_id,club_id) values (72,42,'mixed',1,1,0,?,31,21)", second);
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,occurrence_at,default_lead_member_id,status,version,create_time) values (82,61,72,?,42,'ACTIVE',0,now())", second);
        assertNull(service.getSeries(OWNER, CLUB, SERIES).getStartTime());
        assertNull(service.listSeries(OWNER, CLUB).get(0).getStartTime());
        jdbc.update("update club_event_occurrence set status='CANCELLED'");
        assertNull(service.getSeries(OWNER, CLUB, SERIES).getStartTime());
        assertEquals(Collections.emptyList(), service.getSeries(OWNER, CLUB, SERIES).getFutureDates());
        assertNull(service.listSeries(OWNER, CLUB).get(0).getStartTime());
    }

    @Test
    void everyProtectedFactClosesReadbackAndRejectsRemovalWithoutCommittedWrites() {
        for (String fact : Arrays.asList("REG_1", "REG_2", "REG_3", "REG_4", "WAITING", "OFFERED", "CLAIMED", "SETTLED")) {
            seed("00:30");
            protect(fact);
            assertEquals(1, mapper.countProtectedActivityHistory(ACTIVITY), fact);
            Map<String, Object> row = service.listSeriesOccurrences(OWNER, CLUB, SERIES).get(0);
            assertEquals(fact.equals("REG_2") ? 1 : 0, row.get("signupCount"), fact);
            assertEquals(false, row.get("editable"), fact);
            ServiceException denied = assertThrows(ServiceException.class,
                    () -> service.updateFuture(OWNER, "e01", request(day.plusDays(7))), fact);
            assertEquals(Integer.valueOf(409), denied.getCode(), fact);
            JdbcTemplate committed = independentJdbc();
            assertEquals("ACTIVE", committed.queryForObject("select status from club_event_occurrence where id=81", String.class), fact);
            assertEquals(1, committed.queryForObject("select publish_status from cms_activity where id=71", Integer.class), fact);
            assertNull(committed.queryForObject("select cancel_time from cms_activity where id=71", Date.class), fact);
            assertEquals(0, committed.queryForObject("select version from club_event_series where id=61", Integer.class), fact);
            assertEquals(1, committed.queryForObject("select count(*) from club_event_occurrence", Integer.class), fact);
        }
        verifyNoInteractions(sessions, roles);
    }

    @Test
    void registrationAndSettlementCommittedAfterPrecheckAreRejectedByWriteCas() {
        for (String fact : Arrays.asList("REG_3", "SETTLED")) {
            seed("00:30");
            assertEquals(0, mapper.countProtectedActivityHistory(ACTIVITY));
            protect(fact); // independent autocommit JDBC commit after the earlier read.
            assertEquals(0, mapper.cancelFutureActivityCas(ACTIVITY, new Date(), "e01"), fact);
            assertEquals(1, independentJdbc().queryForObject("select publish_status from cms_activity where id=71", Integer.class));
        }
    }

    @Test
    void unchangedFutureDatesAdvanceVersionOnceWithoutRegeneratingOccurrence() {
        assertEquals(true, service.listSeriesOccurrences(OWNER, CLUB, SERIES).get(0).get("editable"));
        service.updateFuture(OWNER, "e01", request(day));
        assertEquals(1, independentJdbc().queryForObject("select version from club_event_series where id=61", Integer.class));
        assertThrows(ServiceException.class, () -> service.updateFuture(OWNER, "e01", request(day)));
        assertEquals(1, independentJdbc().queryForObject("select count(*) from club_event_occurrence", Integer.class));
        assertEquals("00:30", service.getSeries(OWNER, CLUB, SERIES).getStartTime());
        verifyNoInteractions(sessions, roles);
    }

    @Test
    void ownerReadsAllThreeSeriesViewsThroughRealLockedAuthorizationWithoutWrites() {
        Map<String, List<Map<String, Object>>> before = persistedRows();
        assertEquals(Long.valueOf(SERIES), service.listSeries(OWNER, CLUB).get(0).getId());
        assertEquals("00:30", service.getSeries(OWNER, CLUB, SERIES).getStartTime());
        Map<String, Object> occurrence = service.listSeriesOccurrences(OWNER, CLUB, SERIES).get(0);
        assertEquals(ACTIVITY, ((Number) occurrence.get("activityId")).longValue());
        assertEquals(true, occurrence.get("editable"));
        assertEquals(before, persistedRows());
        verifyNoInteractions(sessions, roles);
    }

    @Test
    void nonOwnerMemberAndForeignScopesAreDeniedBeforeAnyPersistedChange() {
        // 43 is an active merchant without membership; 44 is an ordinary active member.
        for (long actor : Arrays.asList(43L, 44L)) assertReadDeniedWithoutWrites(actor, CLUB, SERIES, 403);
        assertReadDeniedWithoutWrites(OWNER, 22L, SERIES, 403);
        Map<String, List<Map<String, Object>>> before = persistedRows();
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.getSeries(OWNER, CLUB, 62L)).getCode());
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.listSeriesOccurrences(OWNER, CLUB, 62L)).getCode());
        assertEquals(before, persistedRows());

        // An EVENT-only role is not a CLUB management grant even for the original activity.
        jdbc.update("insert into club_role_assignment(club_id,target_member_id,role_code,scope_type,scope_id,"
                + "status,assigned_by_member_id,assigned_at,version,create_time)"
                + " values (21,44,'EVENT_LEAD','EVENT',71,'ACTIVE',42,now(),0,now())");
        assertReadDeniedWithoutWrites(44L, CLUB, SERIES, 403);
        jdbc.update("update ums_member set status=2 where id=42");
        assertReadDeniedWithoutWrites(OWNER, CLUB, SERIES, 403);
        jdbc.update("update ums_member set status=1 where id=42");
        jdbc.update("update club set member_id=45 where id=21");
        assertReadDeniedWithoutWrites(OWNER, CLUB, SERIES, 403);
        before = persistedRows();
        assertEquals(Long.valueOf(SERIES), service.listSeries(45L, CLUB).get(0).getId());
        assertEquals(Long.valueOf(SERIES), service.getSeries(45L, CLUB, SERIES).getId());
        assertEquals(1, service.listSeriesOccurrences(45L, CLUB, SERIES).size());
        assertEquals(before, persistedRows());
        verifyNoInteractions(sessions, roles);
    }

    @Test
    void realAuthorizationLockFailsInEnforcedMysqlReadOnlyTransaction() {
        Map<String, List<Map<String, Object>>> before = persistedRows();
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        org.springframework.dao.DataAccessException denied = assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> readOnly.execute(status -> {
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    // Use the SAME transaction-bound connection, not the independent readback connection.
                    assertEquals(Integer.valueOf(1), new JdbcTemplate(dataSource()).queryForObject(
                            "select @@session.transaction_read_only", Integer.class));
                    return access.require(OWNER, CLUB, null, ClubPermission.ACTIVITY_MANAGE);
                }));
        assertTrue(Arrays.stream(denied.getStackTrace()).anyMatch(frame ->
                ClubAccessServiceImpl.class.getName().equals(frame.getClassName())
                        && "resolve".equals(frame.getMethodName())), "The real access service must be entered");
        assertTrue(Arrays.stream(denied.getStackTrace()).anyMatch(frame ->
                "lockActiveMemberById".equals(frame.getMethodName())), "The original locking Mapper must be entered");
        java.sql.SQLException mysqlFailure = assertInstanceOf(java.sql.SQLException.class, denied.getMostSpecificCause());
        assertEquals(1792, mysqlFailure.getErrorCode(), "The real principal FOR UPDATE must hit MySQL's read-only guard");
        assertEquals("25006", mysqlFailure.getSQLState());
        assertEquals(before, persistedRows());
    }

    @Test
    void seriesReadsRetainThePrincipalLockUntilTheirWritableOuterTransactionCompletes() {
        Map<String, List<Map<String, Object>>> before = persistedRows();
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertEquals(Long.valueOf(SERIES), service.listSeries(OWNER, CLUB).get(0).getId());
            assertEquals(Long.valueOf(SERIES), service.getSeries(OWNER, CLUB, SERIES).getId());
            assertEquals(1, service.listSeriesOccurrences(OWNER, CLUB, SERIES).size());
            org.springframework.dao.DataAccessException blocked = assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> independentJdbc().queryForList("select id from ums_member where id=42 for update nowait"));
            java.sql.SQLException mysqlFailure = assertInstanceOf(java.sql.SQLException.class, blocked.getMostSpecificCause());
            assertEquals(3572, mysqlFailure.getErrorCode(), "An independent connection must not acquire the still-held principal lock");
        });
        assertEquals(1, independentJdbc().queryForList("select id from ums_member where id=42 for update nowait").size());
        assertEquals(before, persistedRows());
        verifyNoInteractions(sessions, roles);
    }

    private void assertReadDeniedWithoutWrites(long actor, long club, long series, int code) {
        Map<String, List<Map<String, Object>>> before = persistedRows();
        assertEquals(Integer.valueOf(code), assertThrows(ServiceException.class,
                () -> service.listSeries(actor, club)).getCode());
        assertEquals(Integer.valueOf(code), assertThrows(ServiceException.class,
                () -> service.getSeries(actor, club, series)).getCode());
        assertEquals(Integer.valueOf(code), assertThrows(ServiceException.class,
                () -> service.listSeriesOccurrences(actor, club, series)).getCode());
        assertEquals(before, persistedRows());
    }

    private Map<String, List<Map<String, Object>>> persistedRows() {
        JdbcTemplate committed = independentJdbc();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : Arrays.asList("ums_member", "club", "club_member", "club_role_assignment",
                "cms_activity", "cms_registration", "club_event_series", "club_event_occurrence", "club_event_waitlist")) {
            rows.put(table, committed.queryForList("select * from " + table + " order by id"));
        }
        return rows;
    }

    private void protect(String fact) {
        if (fact.startsWith("REG_")) {
            independentJdbc().update("insert into cms_registration(registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,registration_status,pay_expire_time) values ('E01',2,71,91,1,99,1,1,1,?,now())", Integer.parseInt(fact.substring(4)));
        } else if (fact.equals("SETTLED")) {
            independentJdbc().update("update cms_activity set is_settlement=1 where id=71");
        } else {
            independentJdbc().update("insert into club_event_waitlist(club_id,activity_id,ticket_id,member_id,state,joined_at,offered_at,offer_expires_at,offer_token_hash,version) values (21,71,91,99,?,now(),now(),date_add(now(),interval 1 hour),?,0)",
                    fact, String.join("", Collections.nCopies(64, "a")));
        }
    }

    private void seed(String time) {
        for (String table : Arrays.asList("club_role_assignment", "club_member", "club_event_waitlist", "cms_registration", "club_event_occurrence", "club_event_series", "cms_activity", "club", "ums_member")) jdbc.execute("delete from " + table);
        jdbc.update("insert into ums_member(id,role,status,del_flag) values (42,'club',1,0),(43,'merchant',1,0),(44,'player',1,0),(45,'club',1,0)");
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (21,42,'E01 synthetic',1,0)");
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (22,45,'E01 foreign',1,0)");
        jdbc.update("insert into club_member(id,club_id,member_id,role,status,join_time,del_flag) values (1,21,44,0,1,now(),0)");
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,topic_id,club_id) values (71,42,'E01 synthetic',1,1,0,?,31,21)", moment(time));
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time) values (61,21,31,'CUSTOM_DATES','Asia/Shanghai',42,1,20,'ACTIVE',0,42,'e01',?,now())",
                String.join("", Collections.nCopies(64, "a")));
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time) values (62,22,32,'CUSTOM_DATES','Asia/Shanghai',45,1,20,'ACTIVE',0,45,'e01-foreign',?,now())",
                String.join("", Collections.nCopies(64, "b")));
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,occurrence_at,default_lead_member_id,status,version,create_time) values (81,61,71,?,42,'ACTIVE',0,now())", moment(time));
    }

    private Date moment(String time) {
        return Date.from(day.atTime(java.time.LocalTime.parse(time)).atZone(SHANGHAI).toInstant());
    }

    private ClubEventSeriesRequest request(LocalDate desired) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setSeriesId(SERIES);
        request.setExpectedVersion(0);
        request.setRecurrenceType("CUSTOM_DATES");
        request.setCustomDates(Collections.singletonList(desired.toString()));
        request.setStartTime("00:30");
        return request;
    }

    private void wire() {
        ClubAccessServiceImpl realAccess = new ClubAccessServiceImpl();
        ReflectionTestUtils.setField(realAccess, "clubMapper", sql.getMapper(ClubMapper.class));
        ReflectionTestUtils.setField(realAccess, "clubMemberMapper", sql.getMapper(ClubMemberMapper.class));
        ReflectionTestUtils.setField(realAccess, "clubRoleAssignmentMapper", sql.getMapper(ClubRoleAssignmentMapper.class));
        ReflectionTestUtils.setField(realAccess, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(realAccess, "umsMemberMapper", sql.getMapper(UmsMemberMapper.class));
        access = realAccess;
        sessions = mock(IClubSessionService.class);
        roles = mock(IClubRoleAssignmentService.class);
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                sessions, mapper, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), roles);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubEventOpsService.class);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        service = (IClubEventOpsService) factory.getProxy();
    }

    // Reuse the SAME DataSource identity for MyBatis and transaction binding.
    private DriverManagerDataSource sharedDataSource;
    private DriverManagerDataSource dataSource() {
        if (sharedDataSource == null) sharedDataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        return sharedDataSource;
    }
    private JdbcTemplate independentJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password()));
    }
    private JdbcTemplate independentAdmin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
    }
}
