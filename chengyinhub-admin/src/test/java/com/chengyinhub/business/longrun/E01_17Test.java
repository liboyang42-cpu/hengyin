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
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Production audience/termination/notification XML with H2 transactions; refund is a SQL stub. */
class E01_17Test {
    private static final Long ACTOR = 41L, OWNER = 42L, CLUB = 21L, ACTIVITY = 71L;

    @Test
    void filteredDistinctAudiencesRemainFrozenAfterRefundChangesAndWaitlistTermination() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:e01_17_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa"); source.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        schema(jdbc);
        seed(jdbc);
        jdbc.update("update cms_activity set member_id=? where id=?", OWNER, ACTIVITY);
        Configuration config = new Configuration(new Environment("e01-17", new SpringManagedTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        ClubEventOpsMapper real = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ClubEventOpsMapper.class);
        List<Long> registered = Arrays.asList(101L, 102L, 201L);
        List<Long> waiting = Arrays.asList(201L, 202L, 203L);
        assertEquals(registered, real.selectRegisteredRecipients(CLUB, ACTIVITY));
        assertEquals(waiting, real.selectWaitlistRecipients(CLUB, ACTIVITY));

        ClubEventSeries series = new ClubEventSeries();
        series.setId(61L); series.setClubId(CLUB); series.setStatus(ClubEventSeries.STATUS_ACTIVE);
        ClubEventOccurrence occurrence = new ClubEventOccurrence();
        occurrence.setId(81L); occurrence.setSeriesId(61L); occurrence.setActivityId(ACTIVITY);
        occurrence.setStatus(ClubEventOccurrence.STATUS_ACTIVE); occurrence.setVersion(0);
        // Authorization/scope lookups are fixed fixtures. All audience reads and writes below use production XML.
        ClubEventOpsMapper mapper = (ClubEventOpsMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ClubEventOpsMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("selectSeriesByActivityAnyStatusForUpdate")) return series;
                    if (method.getName().equals("selectClubOwnerForUpdate")) return OWNER;
                    if (method.getName().equals("selectOccurrenceByActivityAndClubForUpdate")) return occurrence;
                    try { return method.invoke(real, args); }
                    catch (InvocationTargetException error) { throw error.getTargetException(); }
                });
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE)).thenReturn(
                new ClubAccessContext(ACTOR, CLUB, OWNER, ACTIVITY, Collections.singleton(ClubRoleCode.CLUB_CO_OWNER)));
        AtomicBoolean refundCalled = new AtomicBoolean();
        ITeamGroupService refundStub = mock(ITeamGroupService.class);
        RegistrationCancellationPayments payments = new RegistrationCancellationPayments(java.util.List.of(
                new RegistrationCancellationPayments.Scope(2L, ACTIVITY, null, OWNER, CLUB, java.util.List.of())));
        when(refundStub.captureCancellationPayments(2L, ACTIVITY)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(source));
            return payments;
        });
        when(refundStub.observeCancellationPayments(payments)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return payments; // 已付款领域夹具没有需要 SDK 查证的 PAYING 原单。
        });
        when(refundStub.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, "场地临时关闭", false, payments)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(source));
            refundCalled.set(true);
            // Existing registered recipients stop matching the live recipient query.
            jdbc.update("update cms_registration set payment_status=3,registration_status=3 "
                    + "where owner_type=2 and owner_id=71 and payment_status=2 and registration_status=2");
            // A later eligible row must not enter either already captured notification audience.
            jdbc.update("insert into cms_registration values(499,2,71,999,2,2)");
            jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,version) "
                    + "values(199,21,71,301,999,'WAITING',0)");
            jdbc.update("update cms_activity set status=0,publish_status=0,cancel_time=now() where id=71");
            return new ActivityCancelResult(1, 0, 0);
        });
        ClubEventOpsServiceImpl target = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), mapper, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class),
                mock(IClubRoleAssignmentService.class), refundStub);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
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
            assertTrue(TransactionSynchronizationManager.hasResource(source));
            java.util.List<CmsActivity> current = jdbc.query(
                    "select id,member_id,club_id,topic_id from cms_activity where id=? for update",
                    new BeanPropertyRowMapper<>(CmsActivity.class), ACTIVITY);
            return current.isEmpty() ? null : current.getFirst();
        });
        ReflectionTestUtils.setField(target, "cancellationActivityMapper", activities);
        // 原夹具是无父主题场次，series.topicId 与当前活动 topic_id 都保持 null。
        ReflectionTestUtils.setField(target, "cancellationTopicMapper", mock(CmsTopicMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IClubEventOpsService.class);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        IClubEventOpsService service = (IClubEventOpsService) proxy.getProxy();
        ClubEventCancellationRequest request = new ClubEventCancellationRequest();
        request.setClubId(CLUB); request.setActivityId(ACTIVITY); request.setReason("场地临时关闭");
        request.setRequestId("e01-17-audience");

        ClubEventCancellationResult result = service.cancelOccurrence(ACTOR, "co-owner", request);

        assertTrue(refundCalled.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        // Independent post-commit reads prove that current eligibility now differs from the captured lists.
        assertEquals(Collections.singletonList(999L), real.selectRegisteredRecipients(CLUB, ACTIVITY));
        assertEquals(Collections.emptyList(), real.selectWaitlistRecipients(CLUB, ACTIVITY));
        assertEquals(5, jdbc.queryForObject("select count(*) from club_event_waitlist "
                + "where club_id=21 and activity_id=71 and state='CANCELLED' and version=1", Integer.class));
        assertEquals(Integer.valueOf(3), result.getRegisteredRecipientCount());
        assertEquals(Integer.valueOf(3), result.getWaitlistRecipientCount());
        assertNotNull(result.getRegisteredCampaignId()); assertNotNull(result.getWaitlistCampaignId());
        assertNotEquals(result.getRegisteredCampaignId(), result.getWaitlistCampaignId());
        assertEquals(registered, deliveries(jdbc, "REGISTERED"));
        assertEquals(waiting, deliveries(jdbc, "WAITLIST"));
        assertEquals(Arrays.asList("REGISTERED", "WAITLIST"), jdbc.queryForList(
                "select audience_type from club_event_notification_campaign order by audience_type", String.class));
        assertEquals(Arrays.asList(3, 3), jdbc.queryForList(
                "select total_count from club_event_notification_campaign order by audience_type", Integer.class));
        assertEquals(6, jdbc.queryForObject("select count(*) from club_event_notification_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from club_event_notification_delivery where recipient_member_id=999", Integer.class));
        // 201 belongs to both original audiences: once per campaign, not globally deduplicated by new policy.
        assertEquals(2, jdbc.queryForObject("select count(*) from club_event_notification_delivery where recipient_member_id=201", Integer.class));
    }

    private List<Long> deliveries(JdbcTemplate jdbc, String audience) {
        return jdbc.queryForList("select d.recipient_member_id from club_event_notification_delivery d "
                + "join club_event_notification_campaign c on c.id=d.campaign_id "
                + "where c.club_id=21 and c.activity_id=71 and c.audience_type=? order by d.recipient_member_id", Long.class, audience);
    }

    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("create table cms_activity(id bigint primary key,club_id bigint,member_id bigint,topic_id bigint,del_flag int,status int,"
                + "publish_status int,cancel_time timestamp)");
        jdbc.execute("create table cms_registration(id bigint primary key,owner_type int,owner_id bigint,"
                + "member_id bigint,payment_status int,registration_status int)");
        jdbc.execute("create table club_event_occurrence(id bigint primary key,status varchar(16),version int)");
        jdbc.execute("create table club_event_waitlist(id bigint primary key,club_id bigint,activity_id bigint,ticket_id bigint,"
                + "member_id bigint,state varchar(16),joined_at timestamp,offered_at timestamp,offer_expires_at timestamp,"
                + "offer_token_hash varchar(64),registration_id bigint,version int)");
        jdbc.execute("create table oms_ticket(id bigint primary key,owner_type int,owner_id bigint,del_flag int,"
                + "remaining_inventory bigint,total_inventory bigint,update_time timestamp)");
        jdbc.execute("create table club_event_notification_campaign(id bigint auto_increment primary key,club_id bigint,"
                + "activity_id bigint,audience_type varchar(24),channel varchar(24),title varchar(255),content varchar(1024),"
                + "status varchar(24),actor_member_id bigint,request_id varchar(64),total_count int,success_count int,"
                + "failed_count int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_event_notification_delivery(id bigint auto_increment primary key,campaign_id bigint,"
                + "recipient_member_id bigint,channel varchar(24),status varchar(24),attempt_count int,version int,"
                + "create_time timestamp,update_time timestamp)");
        jdbc.execute("create table club_event_notification_outbox(delivery_id bigint primary key,status varchar(24),"
                + "next_attempt_at timestamp,create_time timestamp,update_time timestamp)");
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.update("insert into cms_activity(id,club_id,del_flag,status,publish_status) "
                + "values(71,21,0,1,1),(72,21,0,1,1),(73,22,0,1,1),(74,21,1,1,1)");
        jdbc.update("insert into club_event_occurrence values(81,'ACTIVE',0)");
        jdbc.update("insert into cms_registration values "
                + "(401,2,71,101,2,2),(402,2,71,101,2,2),(403,2,71,102,2,2),(404,2,71,201,2,2),"
                + "(405,2,71,901,1,2),(406,2,71,902,2,3),(407,1,71,903,2,2),"
                + "(408,2,72,904,2,2),(409,2,73,905,2,2),(410,2,74,906,2,2)");
        jdbc.update("insert into oms_ticket(id,owner_type,owner_id,del_flag,remaining_inventory,total_inventory) "
                + "values(301,2,71,0,4,5),(302,2,71,0,4,5),(303,2,71,0,4,5),(304,2,71,0,4,5)");
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,registration_id,version) values "
                + "(101,21,71,301,201,'WAITING',null,0),(102,21,71,302,202,'OFFERED',null,0),"
                + "(103,21,71,303,203,'CLAIMED',401,0),(104,21,71,304,201,'OFFERED',null,0),"
                + "(105,21,71,301,907,'CONVERTED',null,0),(106,21,71,301,908,'CANCELLED',null,0),"
                + "(107,21,71,301,909,'EXPIRED',null,0),(108,21,72,301,910,'WAITING',null,0),"
                + "(109,22,71,301,911,'WAITING',null,0),(110,22,73,301,912,'WAITING',null,0),"
                + "(111,21,74,301,913,'WAITING',null,0)");
    }
}
