package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
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
        String migration = new String(Files.readAllBytes(Paths.get("sql/migration_club_role_event_ops_20260823.sql")), StandardCharsets.UTF_8);
        for (String table : Arrays.asList("club_event_series", "club_event_occurrence", "club_event_waitlist")) createTable(migration, table);
        Configuration configuration = new Configuration(new Environment("e01", new SpringManagedTransactionFactory(), dataSource()));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(ClubEventOpsMapper.class);
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
        for (String table : Arrays.asList("club_event_waitlist", "cms_registration", "club_event_occurrence", "club_event_series", "cms_activity", "club")) jdbc.execute("delete from " + table);
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (21,42,'E01 synthetic',1,0)");
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,topic_id,club_id) values (71,42,'E01 synthetic',1,1,0,?,31,21)", moment(time));
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time) values (61,21,31,'CUSTOM_DATES','Asia/Shanghai',42,1,20,'ACTIVE',0,42,'e01',?,now())",
                String.join("", Collections.nCopies(64, "a")));
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
        // Authorization is a fixed owner fixture, not an RBAC integration assertion.
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(eq(OWNER), eq(CLUB), isNull(), anyString())).thenReturn(
                new ClubAccessContext(OWNER, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        sessions = mock(IClubSessionService.class);
        roles = mock(IClubRoleAssignmentService.class);
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                sessions, mapper, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), roles);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubEventOpsService.class);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource()),
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
