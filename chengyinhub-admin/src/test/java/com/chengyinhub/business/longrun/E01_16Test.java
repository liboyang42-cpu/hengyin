package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real H2 transactions and production XML; refund/role collaborators are SQL-writing stubs. */
class E01_16Test {
    private static final Long ACTOR = 41L, OWNER = 42L, CLUB = 21L, ACTIVITY = 71L;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private ClubEventOpsMapper realMapper;
    private final AtomicInteger refundCalls = new AtomicInteger();
    private boolean earlierWaitlistWriteObserved;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:e01_16_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa"); dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table club(id bigint primary key, member_id bigint)");
        jdbc.execute("create table cms_topic(id bigint primary key,member_id bigint)");
        jdbc.execute("create table cms_activity(id bigint primary key,club_id bigint,member_id bigint,topic_id bigint,del_flag int,"
                + "status int,publish_status int,cancel_time timestamp,cancel_reason varchar(255))");
        jdbc.execute("create table cms_registration(id bigint primary key,owner_type int,owner_id bigint,"
                + "member_id bigint,payment_status int,registration_status int)");
        jdbc.execute("create table club_event_series(id bigint primary key,club_id bigint,topic_id bigint,"
                + "recurrence_type varchar(32),timezone varchar(64),default_lead_member_id bigint,default_capacity int,"
                + "waitlist_enabled int,offer_minutes int,status varchar(16),version int,created_by_member_id bigint,"
                + "request_id varchar(64),request_fingerprint varchar(64),create_by varchar(64),create_time timestamp,"
                + "update_by varchar(64),update_time timestamp)");
        jdbc.execute("create table club_event_occurrence(id bigint primary key,series_id bigint,activity_id bigint,"
                + "occurrence_at timestamp,default_lead_member_id bigint,status varchar(16),version int,create_time timestamp)");
        jdbc.execute("create table club_event_waitlist(id bigint primary key,club_id bigint,activity_id bigint,"
                + "ticket_id bigint,member_id bigint,state varchar(16),joined_at timestamp,offered_at timestamp,"
                + "offer_expires_at timestamp,offer_token_hash varchar(64),registration_id bigint,version int)");
        jdbc.execute("create table oms_ticket(id bigint primary key,owner_type int,owner_id bigint,del_flag int,"
                + "remaining_inventory bigint,total_inventory bigint,update_time timestamp)");
        jdbc.execute("create table role_fixture(id bigint primary key,active int)");
        jdbc.execute("create table club_event_notification_campaign(id bigint auto_increment primary key,club_id bigint,"
                + "activity_id bigint,audience_type varchar(24),channel varchar(24),title varchar(255),content varchar(1024),"
                + "status varchar(24),actor_member_id bigint,request_id varchar(64),total_count int,success_count int,"
                + "failed_count int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_event_notification_delivery(id bigint auto_increment primary key,campaign_id bigint,"
                + "recipient_member_id bigint,channel varchar(24),status varchar(24),attempt_count int,version int,"
                + "create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_event_notification_outbox(delivery_id bigint primary key,status varchar(24),"
                + "next_attempt_at timestamp,create_time timestamp,update_time timestamp)");
        jdbc.update("insert into club values(21,42)");
        jdbc.update("insert into cms_topic values(31,42)");
        jdbc.update("insert into cms_activity(id,club_id,member_id,topic_id,del_flag,status,publish_status) values(71,21,42,31,0,1,1)");
        jdbc.update("insert into cms_registration values(401,2,71,101,2,2)");
        jdbc.update("insert into club_event_series(id,club_id,topic_id,status,version) values(61,21,31,'ACTIVE',0)");
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,status,version) values(81,61,71,'ACTIVE',0)");
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,version) "
                + "values(91,21,71,301,201,'OFFERED',0),(92,21,71,302,202,'OFFERED',0)");
        jdbc.update("insert into oms_ticket(id,owner_type,owner_id,del_flag,remaining_inventory,total_inventory) "
                + "values(301,2,71,0,4,5),(302,2,71,0,4,5)");
        jdbc.update("insert into role_fixture values(1,1)");
        Configuration config = new Configuration(new Environment("e01-16", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        realMapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ClubEventOpsMapper.class);
        refundCalls.set(0); earlierWaitlistWriteObserved = false;
    }

    @Test
    void occurrenceCasZeroRollsBackEarlierRefundAndRoleWrites() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service("occurrence").cancelOccurrence(ACTOR, "co-owner", request()));
        assertEquals("场次状态已变化，请刷新后重试", error.getMessage());
        assertEquals(1, refundCalls.get());
        assertUnchanged(4);
    }

    @Test
    void secondWaitlistCasZeroRollsBackFirstWaitlistAndRestoredHoldAsWellAsRefund() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service("waitlist").cancelOccurrence(ACTOR, "co-owner", request()));
        assertEquals("候补状态已变化，请刷新后重试", error.getMessage());
        assertTrue(earlierWaitlistWriteObserved, "first real cancellation and hold release must precede the failing SQL");
        assertEquals(1, refundCalls.get());
        assertUnchanged(4);
    }

    @Test
    void secondHoldRestoreZeroRollsBackBothWaitlistsAndFirstHoldRelease() {
        // A full second ticket makes the unmodified production restore SQL return zero.
        jdbc.update("update oms_ticket set remaining_inventory=5 where id=302");
        ServiceException error = assertThrows(ServiceException.class,
                () -> service("hold").cancelOccurrence(ACTOR, "co-owner", request()));
        assertEquals("候补预留名额释放失败，请刷新后重试", error.getMessage());
        assertTrue(earlierWaitlistWriteObserved);
        assertEquals(1, refundCalls.get());
        assertUnchanged(5);
    }

    private IClubEventOpsService service(String failure) {
        ClubEventOpsMapper probed = (ClubEventOpsMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ClubEventOpsMapper.class}, (proxy, method, args) -> {
                    if ("occurrence".equals(failure) && method.getName().equals("cancelOccurrenceCas")) {
                        return realMapper.cancelOccurrenceCas((Long) args[0], ((Integer) args[1]) + 1);
                    }
                    if (method.getName().equals("cancelActiveWaitlistCas") && Long.valueOf(92L).equals(args[0])) {
                        assertEquals("CANCELLED", jdbc.queryForObject("select state from club_event_waitlist where id=91", String.class));
                        assertEquals(5, number("select remaining_inventory from oms_ticket where id=301"));
                        earlierWaitlistWriteObserved = true;
                        if ("waitlist".equals(failure)) {
                            return realMapper.cancelActiveWaitlistCas((Long) args[0], ((Integer) args[1]) + 1,
                                    (String) args[2], (Long) args[3], (Long) args[4], (Long) args[5]);
                        }
                    }
                    try { return method.invoke(realMapper, args); }
                    catch (InvocationTargetException error) { throw error.getTargetException(); }
                });
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE)).thenReturn(
                new ClubAccessContext(ACTOR, CLUB, OWNER, ACTIVITY, Collections.singleton(ClubRoleCode.CLUB_CO_OWNER)));
        IClubRoleAssignmentService roles = mock(IClubRoleAssignmentService.class);
        when(roles.revokeEventLeadForCancelledOccurrence(any(), any(), any(), any()))
                .thenAnswer(call -> jdbc.update("update role_fixture set active=0 where id=1"));
        ITeamGroupService refundStub = mock(ITeamGroupService.class);
        RegistrationCancellationPayments payments = new RegistrationCancellationPayments(java.util.List.of(
                new RegistrationCancellationPayments.Scope(2L, ACTIVITY, 31L, OWNER, CLUB, java.util.List.of())));
        when(refundStub.captureCancellationPayments(2L, ACTIVITY)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
            return payments;
        });
        when(refundStub.observeCancellationPayments(payments)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return payments; // 已付款领域夹具没有需要 SDK 查证的 PAYING 原单。
        });
        when(refundStub.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, "场地临时关闭", false, payments)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
            refundCalls.incrementAndGet();
            assertEquals(1, jdbc.update("update cms_activity set status=0,publish_status=0,cancel_time=now() where id=71"));
            assertEquals(1, jdbc.update("update cms_registration set payment_status=3,registration_status=3 where id=401"));
            return new ActivityCancelResult(1, 0, 0);
        });
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), probed, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), roles, refundStub);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        ReflectionTestUtils.setField(target, "cancellationTransactions", transactions);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(ACTIVITY)).thenAnswer(call -> {
            java.util.List<CmsActivity> current = jdbc.query(
                    "select id,member_id,club_id,topic_id from cms_activity where id=?",
                    new BeanPropertyRowMapper<>(CmsActivity.class), ACTIVITY);
            return current.isEmpty() ? null : current.getFirst();
        });
        when(activities.selectCmsActivityByIdForUpdate(ACTIVITY)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
            java.util.List<CmsActivity> current = jdbc.query(
                    "select id,member_id,club_id,topic_id from cms_activity where id=? for update",
                    new BeanPropertyRowMapper<>(CmsActivity.class), ACTIVITY);
            return current.isEmpty() ? null : current.getFirst();
        });
        ReflectionTestUtils.setField(target, "cancellationActivityMapper", activities);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(31L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
            java.util.List<com.chengyinhub.business.domain.CmsTopic> current = jdbc.query(
                    "select id,member_id from cms_topic where id=? for update",
                    new BeanPropertyRowMapper<>(com.chengyinhub.business.domain.CmsTopic.class), 31L);
            return current.isEmpty() ? null : current.getFirst();
        });
        ReflectionTestUtils.setField(target, "cancellationTopicMapper", topics);
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireReceiptMocks(target);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubEventOpsService.class);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (IClubEventOpsService) factory.getProxy();
    }

    private void assertUnchanged(int secondInventory) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "read facts after proxy rollback");
        assertEquals(1, number("select status from cms_activity where id=71"));
        assertEquals(1, number("select publish_status from cms_activity where id=71"));
        assertNull(jdbc.queryForObject("select cancel_time from cms_activity where id=71", java.sql.Timestamp.class));
        assertEquals(2, number("select payment_status from cms_registration where id=401"));
        assertEquals(2, number("select registration_status from cms_registration where id=401"));
        assertEquals(1, number("select active from role_fixture where id=1"));
        assertEquals("ACTIVE", jdbc.queryForObject("select status from club_event_occurrence where id=81", String.class));
        assertEquals(0, number("select version from club_event_occurrence where id=81"));
        assertEquals(2, number("select count(*) from club_event_waitlist where state='OFFERED' and version=0"));
        assertEquals(4, number("select remaining_inventory from oms_ticket where id=301"));
        assertEquals(secondInventory, number("select remaining_inventory from oms_ticket where id=302"));
        assertEquals(0, number("select count(*) from club_event_notification_campaign"));
        assertEquals(0, number("select count(*) from club_event_notification_delivery"));
        assertEquals(0, number("select count(*) from club_event_notification_outbox"));
    }

    private int number(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private ClubEventCancellationRequest request() {
        ClubEventCancellationRequest request = new ClubEventCancellationRequest();
        request.setClubId(CLUB); request.setActivityId(ACTIVITY);
        request.setReason("场地临时关闭"); request.setRequestId("e01-16-cancel");
        return request;
    }
}
