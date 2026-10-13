package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.impl.ClubEventNotificationTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubEventNotificationTransactionBoundaryTest {
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private ClubEventOpsMapper mapper;
    private IImService transactionalFailingIm;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_event_notify_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table campaign (id bigint auto_increment primary key, created_at timestamp not null)");
        jdbc.execute("create table club_lock (id bigint primary key, member_id bigint not null, "
                + "status bigint not null, del_flag int not null)");
        jdbc.execute("create table delivery (id bigint auto_increment primary key, campaign_id bigint not null, "
                + "recipient_member_id bigint not null, status varchar(32) not null, attempt_count int not null, "
                + "message_id bigint, last_error varchar(500), sent_at timestamp, version int not null)");
        jdbc.execute("create table outbox (delivery_id bigint primary key, status varchar(32) not null, "
                + "next_attempt_at timestamp not null)");
        jdbc.execute("create table im_message (id bigint auto_increment primary key, recipient_member_id bigint not null)");
        jdbc.update("insert into delivery(id,campaign_id,recipient_member_id,status,attempt_count,version) "
                + "values (11,9,51,'PENDING',0,0)");
        jdbc.update("insert into outbox(delivery_id,status,next_attempt_at) values (11,'PENDING',current_timestamp)");
        jdbc.update("insert into club_lock(id,member_id,status,del_flag) values (2,90,1,0)");

        mapper = databaseBackedMapper();
        transactionalFailingIm = transactionalFailingIm();
    }

    @Test
    void legacySharedTransactionRollsBackBothImWriteAndCaughtFailureReceipt() {
        LegacyFlow target = new LegacyFlowImpl(jdbc, transactionalFailingIm);
        LegacyFlow legacy = proxy(target, LegacyFlow.class, new AnnotationTransactionAttributeSource());

        assertThrows(UnexpectedRollbackException.class, legacy::sendAndCatch);

        assertEquals(0, count("select count(*) from im_message"));
        assertEquals("PENDING", jdbc.queryForObject("select status from delivery where id=11", String.class));
    }

    @Test
    void requiresNewDeliveryRollbackIsFollowedByDurableFailureReceipt() {
        ClubEventNotificationTransactionService target =
                new ClubEventNotificationTransactionService(mapper, transactionalFailingIm);
        IClubEventNotificationTransactionService service = proxy(target,
                IClubEventNotificationTransactionService.class, new AnnotationTransactionAttributeSource());
        ClubEventCampaign campaign = campaign();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.deliver(campaign, 11L));
        assertTrue(failure.getMessage().contains("IM unavailable"));
        assertEquals(0, count("select count(*) from im_message"));
        assertEquals("PENDING", jdbc.queryForObject("select status from delivery where id=11", String.class));

        Date nextAttempt = new Date(System.currentTimeMillis() + 60_000L);
        assertTrue(service.recordFailure(9L, 11L, "IM unavailable", nextAttempt));

        assertEquals("FAILED", jdbc.queryForObject("select status from delivery where id=11", String.class));
        assertEquals(1, count("select attempt_count from delivery where id=11"));
        assertEquals(1, count("select version from delivery where id=11"));
        assertEquals("IM unavailable", jdbc.queryForObject(
                "select last_error from delivery where id=11", String.class));
        assertTrue(jdbc.queryForObject("select next_attempt_at > current_timestamp from outbox where delivery_id=11",
                Boolean.class));
    }

    @Test
    void ineligibleRecipientCommitsTerminalSkipWithoutImWriteOrAttempt() {
        when(mapper.isEligibleNotificationRecipient(9L, 2L, 3L, "REGISTERED", 51L)).thenReturn(0);
        ClubEventNotificationTransactionService target =
                new ClubEventNotificationTransactionService(mapper, transactionalFailingIm);
        IClubEventNotificationTransactionService service = proxy(target,
                IClubEventNotificationTransactionService.class, new AnnotationTransactionAttributeSource());

        assertFalse(service.deliver(campaign(), 11L));

        assertEquals("SKIPPED_INELIGIBLE", jdbc.queryForObject(
                "select status from delivery where id=11", String.class));
        assertEquals(0, count("select attempt_count from delivery where id=11"));
        assertEquals(1, count("select version from delivery where id=11"));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "select status from outbox where delivery_id=11", String.class));
        assertEquals(0, count("select count(*) from im_message"));
    }

    @Test
    void missingOutboxReceiptRollsBackIneligibleTerminalState() {
        when(mapper.isEligibleNotificationRecipient(9L, 2L, 3L, "REGISTERED", 51L)).thenReturn(0);
        doReturn(0).when(mapper).completeOutbox(11L);
        ClubEventNotificationTransactionService target =
                new ClubEventNotificationTransactionService(mapper, transactionalFailingIm);
        IClubEventNotificationTransactionService service = proxy(target,
                IClubEventNotificationTransactionService.class, new AnnotationTransactionAttributeSource());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.deliver(campaign(), 11L));

        assertTrue(failure.getMessage().contains("ineligible receipt missing"));
        assertEquals("PENDING", jdbc.queryForObject("select status from delivery where id=11", String.class));
        assertEquals(0, count("select version from delivery where id=11"));
        assertEquals("PENDING", jdbc.queryForObject(
                "select status from outbox where delivery_id=11", String.class));
        assertEquals(0, count("select count(*) from im_message"));
    }

    @Test
    void missingCreateOutboxReceiptRollsBackCampaignAndRecipientSnapshot() {
        IClubAccessService access = mock(IClubAccessService.class);
        ClubMapper clubs = mock(ClubMapper.class);
        when(clubs.selectClubByIdForUpdate(2L)).thenAnswer(call -> {
            jdbc.queryForObject("select id from club_lock where id=2 for update", Long.class);
            return activeClub();
        });
        when(access.require(7L, 2L, 3L, ClubPermission.EVENT_OPERATE)).thenReturn(
                new ClubAccessContext(7L, 2L, 90L, 3L,
                        Collections.singleton(ClubRoleCode.EVENT_LEAD)));
        doReturn(0).when(mapper).insertOutbox(any(), any());
        ClubEventNotificationTransactionService target =
                new ClubEventNotificationTransactionService(mapper, transactionalFailingIm, access, clubs);
        IClubEventNotificationTransactionService service = proxy(target,
                IClubEventNotificationTransactionService.class, new AnnotationTransactionAttributeSource());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.create(createCampaign(), Collections.singletonList(51L), new Date()));

        assertTrue(failure.getMessage().contains("recipient receipt missing"));
        assertEquals(0, count("select count(*) from campaign"));
        assertEquals(1, count("select count(*) from delivery"));
        assertEquals(1, count("select count(*) from outbox"));
    }

    @Test
    void failedLaterClaimReceiptRollsBackEarlierOutboxLease() {
        jdbc.update("insert into delivery(id,campaign_id,recipient_member_id,status,attempt_count,version) "
                + "values (12,9,52,'PENDING',0,0)");
        jdbc.update("insert into outbox(delivery_id,status,next_attempt_at) values (12,'PENDING',current_timestamp)");
        when(mapper.selectCampaignById(9L)).thenReturn(campaign());
        org.mockito.Mockito.doAnswer(call -> {
            Long deliveryId = call.getArgument(0);
            if (Long.valueOf(12L).equals(deliveryId)) return 0;
            return jdbc.update("update outbox set next_attempt_at=? "
                            + "where delivery_id=? and status='PENDING' and next_attempt_at<=current_timestamp",
                    call.getArgument(1), deliveryId);
        }).when(mapper).claimNotificationOutbox(any(), any());
        ClubEventNotificationTransactionService target =
                new ClubEventNotificationTransactionService(mapper, transactionalFailingIm);
        IClubEventNotificationTransactionService service = proxy(target,
                IClubEventNotificationTransactionService.class, new AnnotationTransactionAttributeSource());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.claim(campaign(), 2,
                        new Date(System.currentTimeMillis() + 300_000L)));

        assertTrue(failure.getMessage().contains("claim receipt missing"));
        assertEquals(2, count("select count(*) from outbox where next_attempt_at<=current_timestamp"));
    }

    private ClubEventOpsMapper databaseBackedMapper() {
        ClubEventOpsMapper result = mock(ClubEventOpsMapper.class);
        when(result.insertCampaign(any())).thenAnswer(call -> {
            int receipt = jdbc.update("insert into campaign(created_at) values(current_timestamp)");
            Long id = jdbc.queryForObject("select max(id) from campaign", Long.class);
            call.getArgument(0, ClubEventCampaign.class).setId(id);
            return receipt;
        });
        when(result.insertDelivery(any())).thenAnswer(call -> {
            ClubEventDelivery row = call.getArgument(0, ClubEventDelivery.class);
            int receipt = jdbc.update("insert into delivery(campaign_id,recipient_member_id,status,attempt_count,version) "
                            + "values (?,?,?,?,?)", row.getCampaignId(), row.getRecipientMemberId(), row.getStatus(),
                    row.getAttemptCount(), row.getVersion());
            row.setId(jdbc.queryForObject("select max(id) from delivery", Long.class));
            return receipt;
        });
        when(result.insertOutbox(any(), any())).thenAnswer(call -> jdbc.update(
                "insert into outbox(delivery_id,status,next_attempt_at) values (?,'PENDING',?)",
                call.getArgument(0), call.getArgument(1)));
        when(result.selectDispatchableByCampaign(any(), any(Integer.class))).thenAnswer(call -> {
            List<ClubEventDelivery> rows = jdbc.query(
                    "select d.id,d.campaign_id,d.recipient_member_id,d.status,d.attempt_count,d.message_id,"
                            + "d.last_error,d.sent_at,d.version from delivery d "
                            + "inner join outbox o on o.delivery_id=d.id and o.status='PENDING' "
                            + "and o.next_attempt_at<=current_timestamp "
                            + "where d.campaign_id=? and d.status in ('PENDING','FAILED') "
                            + "order by d.id limit ? for update",
                    (rs, rowNum) -> delivery(rs), call.getArgument(0), call.getArgument(1));
            return rows;
        });
        when(result.claimNotificationOutbox(any(), any())).thenAnswer(call -> jdbc.update(
                "update outbox set next_attempt_at=? "
                        + "where delivery_id=? and status='PENDING' and next_attempt_at<=current_timestamp",
                call.getArgument(1), call.getArgument(0)));
        when(result.selectDeliveryByIdForUpdate(11L)).thenAnswer(call -> jdbc.queryForObject(
                "select id,campaign_id,recipient_member_id,status,attempt_count,message_id,last_error,sent_at,version "
                        + "from delivery where id=11 for update",
                (rs, rowNum) -> delivery(rs)));
        when(result.isEligibleNotificationRecipient(any(), any(), any(), any(), any())).thenReturn(1);
        when(result.markDeliveryFailedCas(any(), any(), any(), any())).thenAnswer(call -> jdbc.update(
                "update delivery set status='FAILED',attempt_count=attempt_count+1,last_error=?,version=version+1 "
                        + "where id=? and version=? and status in ('PENDING','FAILED')",
                call.getArgument(2), call.getArgument(0), call.getArgument(1)));
        when(result.markDeliverySkippedIneligibleCas(any(), any())).thenAnswer(call -> jdbc.update(
                "update delivery set status='SKIPPED_INELIGIBLE',message_id=null,sent_at=null,"
                        + "last_error='RECIPIENT_NO_LONGER_ELIGIBLE',version=version+1 "
                        + "where id=? and version=? and status in ('PENDING','FAILED')",
                (Object) call.getArgument(0), (Object) call.getArgument(1)));
        when(result.completeOutbox(any())).thenAnswer(call -> jdbc.update(
                "update outbox set status='COMPLETED' where delivery_id=? and status='PENDING'",
                (Object) call.getArgument(0)));
        when(result.rescheduleOutbox(any(), any())).thenAnswer(call -> jdbc.update(
                "update outbox set next_attempt_at=? where delivery_id=? and status='PENDING'",
                new Object[] { call.getArgument(1), call.getArgument(0) }));
        return result;
    }

    private IImService transactionalFailingIm() {
        IImService target = mock(IImService.class);
        when(target.sendSystem(any(), any(), any(), any())).thenAnswer(call -> {
            jdbc.update("insert into im_message(recipient_member_id) values (?)",
                    (Object) call.getArgument(0));
            throw new RuntimeException("IM unavailable after insert");
        });
        RuleBasedTransactionAttribute required = new RuleBasedTransactionAttribute();
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        MatchAlwaysTransactionAttributeSource source = new MatchAlwaysTransactionAttributeSource();
        source.setTransactionAttribute(required);
        return proxy(target, IImService.class, source);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Object target, Class<T> serviceInterface,
                        org.springframework.transaction.interceptor.TransactionAttributeSource source) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(serviceInterface);
        factory.addAdvice(new TransactionInterceptor(transactionManager, source));
        return (T) factory.getProxy();
    }

    private ClubEventCampaign campaign() {
        ClubEventCampaign row = new ClubEventCampaign();
        row.setId(9L);
        row.setClubId(2L);
        row.setActivityId(3L);
        row.setAudienceType("REGISTERED");
        row.setTitle("集合提醒");
        row.setContent("请提前十分钟到场");
        return row;
    }

    private ClubEventCampaign createCampaign() {
        ClubEventCampaign row = campaign();
        row.setActorMemberId(7L);
        row.setChannel("IN_APP");
        row.setRequestId("tx-create-rollback");
        return row;
    }

    private Club activeClub() {
        Club row = new Club();
        row.setId(2L);
        row.setMemberId(90L);
        row.setStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    private ClubEventDelivery delivery(ResultSet rs) throws SQLException {
        ClubEventDelivery row = new ClubEventDelivery();
        row.setId(rs.getLong("id"));
        row.setCampaignId(rs.getLong("campaign_id"));
        row.setRecipientMemberId(rs.getLong("recipient_member_id"));
        row.setStatus(rs.getString("status"));
        row.setAttemptCount(rs.getInt("attempt_count"));
        row.setMessageId((Long) rs.getObject("message_id"));
        row.setLastError(rs.getString("last_error"));
        row.setSentAt(rs.getTimestamp("sent_at"));
        row.setVersion(rs.getInt("version"));
        return row;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private interface LegacyFlow {
        void sendAndCatch();
    }

    private static final class LegacyFlowImpl implements LegacyFlow {
        private final JdbcTemplate jdbc;
        private final IImService im;

        private LegacyFlowImpl(JdbcTemplate jdbc, IImService im) {
            this.jdbc = jdbc;
            this.im = im;
        }

        @Override
        @Transactional(rollbackFor = Exception.class)
        public void sendAndCatch() {
            try {
                im.sendSystem(51L, 1, "legacy", "{}");
            } catch (RuntimeException ignored) {
                jdbc.update("update delivery set status='FAILED' where id=11");
            }
        }
    }
}
