package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MySQL DATETIME(0), offer/claim/expiry CAS and ticket inventory; synthetic membership/policy only. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class ClubWaitlistTokenMybatisIntegrationTest {
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private boolean created;
    private ClubEventOpsMapper realMapper;
    private ClubEventSeries policy;
    private IClubWaitlistService service;
    private IClubWaitlistOrderBridge orderBridge;
    private ClubWaitlistTokenCodec codec;
    private long epoch;

    @BeforeEach
    void setup() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        // 2026-09-16: 原来这里覆写 HOST=127.0.0.1 / PORT=3317 —— CI 注入的连接被无视,
        // 拿 CI 账号去连本机 8.0.45 容器,Access denied。连接参数只认门禁注入的 CHENGYIN_TEST_MYSQL_*。
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "waitlist_token");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        mysql = MysqlIntegrationTestConfig.from(env, "it");
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,实际 " + version);
        admin.execute("CREATE DATABASE " + mysql.databaseName());
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(tableDdl("sql/migration_club_role_event_ops_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_event_series ("));
        jdbc.execute(tableDdl("sql/migration_club_role_event_ops_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_event_occurrence ("));
        jdbc.execute(tableDdl("sql/migration_club_role_event_ops_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_event_waitlist ("));
        jdbc.execute(tableDdl("sql/migration_club_role_event_ops_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_event_notification_campaign ("));
        jdbc.execute(tableDdl("sql/migration_club_role_event_ops_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_event_notification_delivery ("));
        jdbc.execute(tableDdl("sql/migration_club_role_event_ops_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_event_notification_outbox ("));
        jdbc.execute(tableDdl("../sql/base_schema_20260818.sql", "CREATE TABLE `club` ("));
        jdbc.execute(tableDdl("../sql/base_schema_20260818.sql", "CREATE TABLE `club_member` ("));
        jdbc.execute(tableDdl("sql/migration_club_role_governance_20260823.sql",
                "CREATE TABLE IF NOT EXISTS club_governance_ban ("));
        // The ticket projection joins both real tables to check self-hosted publisher ownership.
        jdbc.execute(tableDdl("../sql/base_schema_20260818.sql", "CREATE TABLE `cms_topic` ("));
        jdbc.execute(tableDdl("../sql/base_schema_20260818.sql", "CREATE TABLE `cms_activity` ("));
        jdbc.execute(tableDdl("../sql/base_schema_20260818.sql", "CREATE TABLE `oms_ticket` ("));
        // Existing production migration_20260817_tandianri_r6_product_contracts.sql:93, needed by current ticket mapper.
        jdbc.execute("ALTER TABLE oms_ticket ADD COLUMN source_ticket_id BIGINT NULL AFTER owner_id");
        // 2-26 migration_oms_ticket_sale_fields_20260918.sql: selectOmsTicketVo (used by
        // selectOmsTicketByIdForUpdate) now reads these 5 unconditionally. base_schema_20260818 predates them
        // ⇒ every waitlist case died with ERROR 1054 Unknown column 'sale_start_time' in 'field list'.
        // Types / nullability / COMMENT / AFTER positions copied verbatim from the migration.
        jdbc.execute("ALTER TABLE oms_ticket "
                + "ADD COLUMN sale_start_time DATETIME NULL "
                + "COMMENT '售票开始时间(发布页填写;仅落库回显,购票闸未接)' AFTER end_time, "
                + "ADD COLUMN sale_end_time DATETIME NULL "
                + "COMMENT '售票结束时间(发布页填写;仅落库回显,购票闸未接)' AFTER sale_start_time, "
                + "ADD COLUMN meeting_point_address VARCHAR(255) NULL "
                + "COMMENT '集合点地址(地图选点文本)' AFTER meeting_point, "
                + "ADD COLUMN refund_supported TINYINT(1) NULL "
                + "COMMENT '支持随时退款 0否1是(发布页填写;仅落库回显,退款规则未接)' AFTER refund_rule, "
                + "ADD COLUMN sync_with_theme TINYINT(1) NULL "
                + "COMMENT '售票时间与主题日期同步 0否1是(发布页填写;仅落库回显,同步逻辑未接)' AFTER refund_supported");
        assertEquals("datetime", jdbc.queryForObject("SELECT column_type FROM information_schema.columns"
                + " WHERE table_schema=DATABASE() AND table_name='club_event_waitlist' AND column_name='offer_expires_at'", String.class));
        System.out.println("WAITLIST_TOKEN_MYSQL=" + version + " SCRATCH=" + mysql.databaseName());
        Configuration config = new Configuration(new Environment("waitlist-token", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String name : new String[]{"ClubEventOpsMapper", "OmsTicketMapper"}) {
            String resource = "mapper/business/" + name + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        realMapper = sessions.getMapper(ClubEventOpsMapper.class);
        OmsTicketMapper ticketMapper = sessions.getMapper(OmsTicketMapper.class);
        policy = new ClubEventSeries();
        policy.setId(11L);
        policy.setClubId(7L);
        policy.setWaitlistEnabled(true);
        policy.setOfferMinutes(5);
        policy.setStatus(ClubEventSeries.STATUS_ACTIVE);
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class, invocation -> {
            String name = invocation.getMethod().getName();
            if (name.startsWith("selectSeries")) return policy;
            if (name.equals("isActiveParticipant")) return 1;
            if (name.equals("countActiveRegistration")) return 0;
            if (name.equals("selectClubOwnerForUpdate")) return 999L;
            if (name.equals("selectActiveClubMemberForUpdate")) return 9L;
            try {
                return invocation.getMethod().invoke(realMapper, invocation.getArguments());
            } catch (InvocationTargetException cause) {
                throw cause.getCause();
            }
        });
        DataSourceTransactionManager tx = new DataSourceTransactionManager(ds);
        codec = new ClubWaitlistTokenCodec("waitlist-token-scratch-secret");
        ClubWaitlistServiceImpl target = new ClubWaitlistServiceImpl(mapper, ticketMapper,
                mock(IClubParticipationGuard.class), codec, tx);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        Object bean = proxy.getProxy();
        service = (IClubWaitlistService) bean;
        orderBridge = (IClubWaitlistOrderBridge) bean;
        epoch = System.currentTimeMillis() / 1000 * 1000;
        jdbc.update("INSERT INTO club(id,member_id,name,status,del_flag) VALUES(7,999,'waitlist-test',1,0)");
        jdbc.update("INSERT INTO club_member(id,club_id,member_id,role,status,del_flag) VALUES(1,7,9,0,1,0)");
        jdbc.update("INSERT INTO cms_activity(id,member_id,name,status,publish_status,del_flag,club_id) "
                + "VALUES(20,999,'waitlist-activity',1,1,0,7)");
        jdbc.update("INSERT INTO oms_ticket(id,owner_type,owner_id,total_inventory,remaining_inventory,del_flag) VALUES(97,2,20,1,1,0)");
        jdbc.update("INSERT INTO club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,version) VALUES(61,7,20,97,9,'WAITING',NOW(),0)");
    }

    @AfterEach
    void cleanup() {
        if (created) {
            admin.execute("DROP DATABASE " + mysql.databaseName());
            int remaining = admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    Integer.class, mysql.databaseName());
            assertEquals(0, remaining);
            System.out.println("WAITLIST_TOKEN_SCRATCH_DROPPED=" + mysql.databaseName() + " READBACK=" + remaining);
        }
    }

    @Test
    void fractional123OfferCanBeReadAndClaimedAfterDatetimeRoundTrip() {
        assertReadableAndClaimable(123);
    }

    @Test
    void fractional789OfferCanBeReadAndClaimedAfterDatetimeRoundTrip() {
        assertReadableAndClaimable(789);
    }

    private void assertReadableAndClaimable(int millis) {
        Date now = new Date(epoch + millis);
        assertTrue(service.promoteNext(20L, 97L, now));
        ClubWaitlistEntry stored = realMapper.selectWaitlistById(61L);
        assertEquals(epoch + 300_000L, stored.getOfferExpiresAt().getTime(), "Never round the selected deadline upwards");
        ClubWaitlistEntry view = service.myStatus(9L, 20L, 97L);
        assertNotNull(view.getOfferToken());
        assertTrue(codec.matches(stored, view.getOfferToken()));
        assertNotNull(orderBridge.claimForOrder(9L, request(view.getOfferToken()), now));
        assertEquals("CLAIMED", state());
        assertEquals(0, stock());
    }

    @Test
    void wholeSecondOfferPreservesTamperScopeAndDuplicateClaimRejections() {
        Date now = new Date(epoch);
        assertTrue(service.promoteNext(20L, 97L, now));
        String token = service.myStatus(9L, 20L, 97L).getOfferToken();
        assertThrows(ServiceException.class, () -> orderBridge.claimForOrder(9L, request(token + "x"), now));
        assertThrows(ServiceException.class, () -> orderBridge.claimForOrder(8L, request(token), now));
        RegistrationRequest otherTicket = request(token);
        otherTicket.setTicketId(98L);
        assertThrows(ServiceException.class, () -> orderBridge.claimForOrder(9L, otherTicket, now));
        RegistrationRequest otherActivity = request(token);
        otherActivity.setOwnerId(21L);
        assertThrows(ServiceException.class, () -> orderBridge.claimForOrder(9L, otherActivity, now));
        assertEquals("OFFERED", state());
        assertEquals(0, stock());
        assertNotNull(orderBridge.claimForOrder(9L, request(token), now));
        assertThrows(ServiceException.class, () -> orderBridge.claimForOrder(9L, request(token), now));
        assertEquals("CLAIMED", state());
        assertEquals(0, stock());
    }

    @Test
    void signupDeadlineAndFallbackStartRemainHardCaps() {
        for (String column : new String[]{"signup_deadline", "start_time"}) {
            resetOffer();
            long cap = epoch + 2000;
            jdbc.update("UPDATE oms_ticket SET signup_deadline=NULL,start_time=NULL");
            jdbc.update("UPDATE oms_ticket SET " + column + "=?", new Timestamp(cap));
            assertTrue(service.promoteNext(20L, 97L, new Date(epoch + 789)));
            assertEquals(cap, realMapper.selectWaitlistById(61L).getOfferExpiresAt().getTime());
            String token = codec.token(realMapper.selectWaitlistById(61L));
            assertThrows(ServiceException.class, () -> orderBridge.claimForOrder(9L, request(token), new Date(cap)));
            assertEquals("OFFERED", state());
            assertTrue(service.expireOffered(61L, new Date(cap)));
            assertEquals(1, stock());
            resetOffer();
            assertFalse(service.promoteNext(20L, 97L, new Date(cap)), "No offer at the hard cutoff");
            assertEquals("WAITING", state());
            assertEquals(1, stock());
        }
    }

    @Test
    void defaultDayAndConfiguredWindowLimitsRemainUnchanged() {
        policy.setOfferMinutes(null);
        assertTrue(service.promoteNext(20L, 97L, new Date(epoch + 789)));
        assertEquals(epoch + 86_400_000L, realMapper.selectWaitlistById(61L).getOfferExpiresAt().getTime());
        for (int invalid : new int[]{4, 1441}) {
            resetOffer();
            policy.setOfferMinutes(invalid);
            assertThrows(ServiceException.class, () -> service.promoteNext(20L, 97L, new Date(epoch)));
            assertEquals("WAITING", state());
            assertEquals(1, stock());
        }
    }

    @Test
    void concurrentPromotionProducesOneUsableOfferAndOneInventoryHold() throws Exception {
        assertEquals(1, race(() -> service.promoteNext(20L, 97L, new Date(epoch + 789))));
        ClubWaitlistEntry view = service.myStatus(9L, 20L, 97L);
        assertTrue(codec.matches(realMapper.selectWaitlistById(61L), view.getOfferToken()));
        assertEquals(0, stock());
        assertEquals(1, jdbc.queryForObject("SELECT version FROM club_event_waitlist WHERE id=61", Integer.class));
    }

    @Test
    void expiryAtStoredBoundaryReleasesInventoryOnlyOnceUnderConcurrency() throws Exception {
        assertTrue(service.promoteNext(20L, 97L, new Date(epoch + 123)));
        Long offerCampaignId = jdbc.queryForObject(
                "SELECT id FROM club_event_notification_campaign WHERE audience_type='WAITLIST'", Long.class);
        assertEquals(1, realMapper.isEligibleNotificationRecipient(
                offerCampaignId, 7L, 20L, "WAITLIST", 9L));
        Date deadline = realMapper.selectWaitlistById(61L).getOfferExpiresAt();
        assertFalse(service.expireOffered(61L, new Date(deadline.getTime() - 1)));
        assertEquals(0, stock());
        assertEquals(1, race(() -> service.expireOffered(61L, deadline)));
        assertFalse(service.expireOffered(61L, deadline));
        assertEquals("EXPIRED", state());
        assertEquals(1, stock());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM club_event_notification_campaign", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM club_event_notification_campaign WHERE audience_type='ALL_MEMBERS' "
                        + "AND title='候补名额已过期' "
                        + "AND content='你的活动候补名额已过期，如仍需参加，请重新加入候补。'", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM club_event_notification_delivery WHERE recipient_member_id=9", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM club_event_notification_outbox", Long.class));

        Long expiryCampaignId = jdbc.queryForObject(
                "SELECT id FROM club_event_notification_campaign WHERE audience_type='ALL_MEMBERS'", Long.class);
        assertEquals(0, realMapper.isEligibleNotificationRecipient(
                offerCampaignId, 7L, 20L, "WAITLIST", 9L));
        assertEquals(1, realMapper.isEligibleNotificationRecipient(
                expiryCampaignId, 7L, 20L, "ALL_MEMBERS", 9L));

        jdbc.update("UPDATE club_member SET del_flag=2 WHERE club_id=7 AND member_id=9");
        assertEquals(0, realMapper.isEligibleNotificationRecipient(
                expiryCampaignId, 7L, 20L, "ALL_MEMBERS", 9L));
        jdbc.update("UPDATE club_member SET del_flag=0 WHERE club_id=7 AND member_id=9");
        jdbc.update("INSERT INTO club_governance_ban(club_id,target_member_id,status,source_type,ban_reason,"
                        + "banned_by_member_id,banned_at,ban_request_id,create_time) "
                        + "VALUES(7,9,'ACTIVE','PLATFORM','eligibility-test',999,NOW(),'waitlist-expiry-test',NOW())");
        assertEquals(0, realMapper.isEligibleNotificationRecipient(
                expiryCampaignId, 7L, 20L, "ALL_MEMBERS", 9L));
    }

    private int race(java.util.concurrent.Callable<Boolean> operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> contender = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("race start timeout");
            return operation.call();
        };
        try {
            Future<Boolean> first = pool.submit(contender);
            Future<Boolean> second = pool.submit(contender);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private void resetOffer() {
        jdbc.update("UPDATE club_event_waitlist SET state='WAITING',version=0,offered_at=NULL,offer_expires_at=NULL,offer_token_hash=NULL WHERE id=61");
        jdbc.update("UPDATE oms_ticket SET remaining_inventory=1 WHERE id=97");
    }

    private RegistrationRequest request(String token) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(20L);
        request.setTicketId(97L);
        request.setWaitlistOfferId(61L);
        request.setWaitlistOfferToken(token);
        return request;
    }

    private int stock() {
        return jdbc.queryForObject("SELECT remaining_inventory FROM oms_ticket WHERE id=97", Integer.class);
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM club_event_waitlist WHERE id=61", String.class);
    }

    private String tableDdl(String file, String startToken) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        int start = source.indexOf(startToken);
        assertTrue(start >= 0, "Missing real DDL");
        // Base-schema COMMENT text can contain semicolons; stop after the table closing line.
        int tableEnd = source.indexOf("\n) ENGINE=", start);
        return source.substring(start, source.indexOf(';', tableEnd) + 1).replace("IF NOT EXISTS ", "");
    }
}
