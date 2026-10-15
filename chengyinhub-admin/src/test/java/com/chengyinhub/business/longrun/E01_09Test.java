package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.ClubWaitlistClaim;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.IClubWaitlistOrderBridge;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.transaction.support.TransactionTemplate;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** NOT_RUN. Real MySQL claim/bind/terminal lifecycle; payment/closure facts are synthetic fixtures. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E01_09Test {
    private static final Long CLUB = 21L, ACTIVITY = 71L, TICKET = 91L, FIRST_MEMBER = 101L;
    private MysqlIntegrationTestConfig mysql;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ClubEventOpsMapper realMapper;
    private IClubWaitlistService service;
    private IClubWaitlistOrderBridge bridge;
    private TransactionTemplate orderTransaction;
    private ClubWaitlistTokenCodec codec;
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
        mysql = MysqlIntegrationTestConfig.fromEnvironment("e01bind_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
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
        for (String table : Arrays.asList("club_event_series", "club_event_occurrence", "club_event_waitlist")) createTable(migration, table);
        Configuration config = new Configuration(new Environment("e01-bind-terminal", new SpringManagedTransactionFactory(), dataSource));
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
        OmsTicketMapper realTickets = sql.getMapper(OmsTicketMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        orderTransaction = new TransactionTemplate(manager);
        codec = new ClubWaitlistTokenCodec("e01-09-synthetic-token-secret");
        ClubWaitlistServiceImpl target = new ClubWaitlistServiceImpl(realMapper, realTickets,
                mock(IClubParticipationGuard.class), codec, manager);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubWaitlistService.class, IClubWaitlistOrderBridge.class);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        Object proxy = factory.getProxy();
        service = (IClubWaitlistService) proxy;
        bridge = (IClubWaitlistOrderBridge) proxy;
    }

    @BeforeEach
    void seed() {
        now = new Date(System.currentTimeMillis() / 1000L * 1000L);
        for (String table : Arrays.asList("club_event_waitlist", "club_event_occurrence", "club_event_series", "cms_registration", "oms_ticket", "cms_activity", "cms_topic", "club_member", "club")) jdbc.execute("delete from " + table);
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (21,42,'E01 binding synthetic',1,0)");
        jdbc.update("insert into club_member(club_id,member_id,status,del_flag) values (21,101,1,0),(21,102,1,0),(21,103,1,0)");
        jdbc.update("insert into cms_topic(id,member_id,name,club_id,del_flag) values (31,42,'E01 binding synthetic',21,0)");
        Date future = new Date(now.getTime() + TimeUnit.DAYS.toMillis(2));
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,end_date,topic_id,club_id) values (71,42,'E01 binding synthetic',1,1,0,?,?,31,21)", future, future);
        jdbc.update("insert into oms_ticket(id,member_id,owner_type,owner_id,name,total_inventory,remaining_inventory,del_flag,signup_deadline) values (91,42,2,71,'E01 binding',1,1,0,?)", future);
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time) values (61,21,31,'ONCE','Asia/Shanghai',42,1,20,'ACTIVE',0,42,'e01-bind',?,now())",
                String.join("", Collections.nCopies(64, "a")));
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,occurrence_at,default_lead_member_id,status,version,create_time) values (71,61,71,?,42,'ACTIVE',0,now())", future);
        // Equal join times deliberately require the production id tie-break to choose 82 before 83.
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,version) values (81,21,71,91,101,'WAITING',?,0),(82,21,71,91,102,'WAITING',?,0),(83,21,71,91,103,'WAITING',?,0)",
                new Date(now.getTime() - 2000), new Date(now.getTime() - 1000), new Date(now.getTime() - 1000));
    }

    @Test
    void oldClaimCannotRebindAfterConversionOrTouchNewlyJoinedEntry() {
        assertTrue(service.promoteNext(ACTIVITY, TICKET, now));
        ClubWaitlistClaim claim = claimAndBind(201L);
        assertEquals("CLAIMED", state(81L));
        assertEquals(201L, registration(81L));
        int boundVersion = version(81L);
        assertThrows(ServiceException.class, () -> bridge.bindRegistration(claim, 201L));
        assertThrows(ServiceException.class, () -> bridge.bindRegistration(claim, 202L));
        assertEquals(201L, registration(81L));
        assertEquals(boundVersion, version(81L));
        independent().update("update cms_registration set payment_status=2,registration_status=2 where id=201");

        bridge.convertedByRegistration(201L);
        assertEquals("CONVERTED", state(81L));
        assertEquals(boundVersion + 1, version(81L));
        bridge.convertedByRegistration(201L);
        assertEquals(0, service.maintain(now));
        assertEquals(boundVersion + 1, version(81L));
        assertEquals(0, stock());

        // Synthetic historical terminal facts with the ticket currently full; no refund path or policy is exercised.
        independent().update("update cms_registration set payment_status=4,registration_status=3 where id=201");
        ClubWaitlistEntry rejoined = service.join(FIRST_MEMBER, ACTIVITY, TICKET);
        assertNotEquals(Long.valueOf(81L), rejoined.getId());
        ServiceException conflict = assertThrows(ServiceException.class, () -> bridge.bindRegistration(claim, 202L));
        assertEquals(Integer.valueOf(409), conflict.getCode());
        assertEquals(201L, registration(81L));
        assertEquals("CONVERTED", state(81L));
        assertEquals("WAITING", state(rejoined.getId()));
        assertNull(independent().queryForObject("select registration_id from club_event_waitlist where id=?", Long.class, rejoined.getId()));
        assertEquals(0, stock());
    }

    @Test
    void terminalClaimExpiryDoesNotRestoreTwiceAndLateConversionCannotReviveIt() {
        assertTrue(service.promoteNext(ACTIVITY, TICKET, now));
        ClubWaitlistClaim claim = claimAndBind(201L);
        assertEquals(0, stock());
        // Model the canonical order-close transaction's committed status and one inventory restore.
        orderTransaction.execute(status -> {
            assertEquals(1, jdbc.update("update cms_registration set payment_status=3,registration_status=4 where id=201"));
            assertEquals(1, jdbc.update("update oms_ticket set remaining_inventory=1 where id=91 and remaining_inventory=0"));
            return null;
        });

        assertEquals(1, service.maintain(now));
        assertEquals("EXPIRED", state(81L));
        assertEquals(201L, registration(81L));
        int expiredVersion = version(81L);
        assertEquals("OFFERED", state(82L));
        assertEquals("WAITING", state(83L));
        assertEquals(0, stock(), "The already restored slot must be offered only once to the next FIFO member");
        assertEquals(0, service.maintain(now));
        bridge.convertedByRegistration(201L);
        bridge.convertedByRegistration(201L);
        assertEquals("EXPIRED", state(81L));
        assertEquals(expiredVersion, version(81L));
        assertThrows(ServiceException.class, () -> bridge.bindRegistration(claim, 202L));
        assertEquals(201L, registration(81L));
        assertEquals(1, independent().queryForObject("select count(*) from club_event_waitlist where state='OFFERED'", Integer.class));
        assertEquals(0, stock());
        assertEquals(0, independent().queryForObject("select count(*) from oms_ticket where remaining_inventory<0 or remaining_inventory>total_inventory", Integer.class));
    }

    private ClubWaitlistClaim claimAndBind(Long registrationId) {
        ClubWaitlistEntry offered = realMapper.selectWaitlistById(81L);
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY);
        request.setTicketId(TICKET);
        request.setWaitlistOfferId(81L);
        request.setWaitlistOfferToken(codec.token(offered));
        // Match the real outer registration transaction: claim -> row insert -> bind commit together.
        return orderTransaction.execute(status -> {
            ClubWaitlistClaim claim = bridge.claimForOrder(FIRST_MEMBER, request, now);
            assertNotNull(claim);
            jdbc.update("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time) values (?, ?,2,71,91,1,101,1,1,1,0,1,?)",
                    registrationId, "E01-BIND-" + registrationId, claim.getExpiresAt());
            bridge.bindRegistration(claim, registrationId);
            return claim;
        });
    }

    private String state(Long id) {
        return independent().queryForObject("select state from club_event_waitlist where id=?", String.class, id);
    }
    private long registration(Long id) {
        return independent().queryForObject("select registration_id from club_event_waitlist where id=?", Long.class, id);
    }
    private int version(Long id) {
        return independent().queryForObject("select version from club_event_waitlist where id=?", Integer.class, id);
    }
    private int stock() {
        return independent().queryForObject("select remaining_inventory from oms_ticket where id=91", Integer.class);
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
