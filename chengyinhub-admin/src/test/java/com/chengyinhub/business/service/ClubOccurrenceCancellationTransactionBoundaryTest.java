package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.RegistrationCancellationPayments;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 活动、场次、角色、候补与两类通知回执必须一起提交或一起回滚。 */
class ClubOccurrenceCancellationTransactionBoundaryTest {
    private static final Long ACTOR = 41L;
    private static final Long OWNER = 42L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 71L;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_occurrence_cancel_tx_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("create table activity_state(id bigint primary key, active int not null)");
        jdbc.execute("create table refund_state(activity_id bigint primary key, requested int not null)");
        jdbc.execute("create table occurrence_state(id bigint primary key, status varchar(16), version int)");
        jdbc.execute("create table role_state(id bigint primary key, active int not null)");
        jdbc.execute("create table waitlist_state(id bigint primary key, state varchar(16), version int)");
        jdbc.execute("create table ticket_state(id bigint primary key, remaining int, total int)");
        jdbc.execute("create table campaign_state(id bigint primary key, audience varchar(24), request_id varchar(64))");
        jdbc.execute("create table delivery_state(id bigint primary key, campaign_id bigint, recipient bigint)");
        jdbc.execute("create table outbox_state(delivery_id bigint primary key)");
        jdbc.update("insert into activity_state values (?,1)", ACTIVITY);
        jdbc.update("insert into refund_state values (?,0)", ACTIVITY);
        jdbc.update("insert into occurrence_state values (81,'ACTIVE',0)");
        jdbc.update("insert into role_state values (91,1)");
        jdbc.update("insert into waitlist_state values (92,'OFFERED',0)");
        jdbc.update("insert into ticket_state values (302,4,5)");
    }

    @Test
    void successCommitsEveryCancellationStateAndBothDurableAudienceReceipts() {
        ClubEventOpsServiceImpl service = service(false);

        service.cancelOccurrence(ACTOR, "co-owner", request());

        assertEquals(0, value("select active from activity_state where id=?", ACTIVITY));
        assertEquals(1, value("select requested from refund_state where activity_id=?", ACTIVITY));
        assertEquals("CANCELLED", text("select status from occurrence_state where id=81"));
        assertEquals(0, value("select active from role_state where id=91"));
        assertEquals("CANCELLED", text("select state from waitlist_state where id=92"));
        assertEquals(5, value("select remaining from ticket_state where id=302"));
        assertEquals(2, value("select count(*) from campaign_state"));
        assertEquals(2, value("select count(*) from delivery_state"));
        assertEquals(2, value("select count(*) from outbox_state"));
    }

    @Test
    void secondCampaignReceiptFailureRollsBackRefundOccurrenceRoleWaitlistHoldAndFirstOutbox() throws Exception {
        ClubEventOpsServiceImpl service = service(true);

        assertThrows(ServiceException.class, () -> service.cancelOccurrence(ACTOR, "co-owner", request()));

        assertEquals(1, value("select active from activity_state where id=?", ACTIVITY));
        assertEquals(0, value("select requested from refund_state where activity_id=?", ACTIVITY));
        assertEquals("ACTIVE", text("select status from occurrence_state where id=81"));
        assertEquals(1, value("select active from role_state where id=91"));
        assertEquals("OFFERED", text("select state from waitlist_state where id=92"));
        assertEquals(4, value("select remaining from ticket_state where id=302"));
        assertEquals(0, value("select count(*) from campaign_state"));
        assertEquals(0, value("select count(*) from delivery_state"));
        assertEquals(0, value("select count(*) from outbox_state"));

        Transactional boundary = ClubEventOpsServiceImpl.class.getDeclaredMethod(
                "cancelOccurrence", Long.class, String.class, ClubEventCancellationRequest.class)
                .getAnnotation(Transactional.class);
        assertNotNull(boundary);
        assertTrue(Arrays.asList(boundary.rollbackFor()).contains(Exception.class));
    }

    private ClubEventOpsServiceImpl service(boolean failWaitlistCampaign) {
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, OWNER, ACTIVITY,
                        Collections.singleton(ClubRoleCode.CLUB_CO_OWNER)));
        IClubRoleAssignmentService roles = mock(IClubRoleAssignmentService.class);
        when(roles.revokeEventLeadForCancelledOccurrence(any(), any(), any(), any()))
                .thenAnswer(call -> jdbc.update("update role_state set active=0 where id=91 and active=1"));
        ITeamGroupService teamGroup = mock(ITeamGroupService.class);
        RegistrationCancellationPayments payments = CancellationPhaseTestSupport.stubEmptyObservation(teamGroup,ACTIVITY);
        when(teamGroup.cancelActivityWithObservedPayments(ACTIVITY, OWNER, ACTOR, "场地临时关闭", false, payments))
                .thenAnswer(call -> {
                    if (jdbc.update("update activity_state set active=0 where id=? and active=1", ACTIVITY) != 1) {
                        throw new ServiceException("活动状态已变化", 409);
                    }
                    jdbc.update("update refund_state set requested=1 where activity_id=?", ACTIVITY);
                    return new ActivityCancelResult(1, 0, 0);
                });
        ClubEventOpsServiceImpl service = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), mapper(failWaitlistCampaign),
                mock(ClubMemberMapper.class), mock(ClubLeadMapper.class), roles, teamGroup);
        CancellationPhaseTestSupport.wireTransactions(service,transaction.getTransactionManager());
        var activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        var activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(ACTIVITY); activity.setClubId(CLUB); activity.setMemberId(OWNER);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(activity);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"cancellationActivityMapper",activities);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"cancellationTopicMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class));
        return service;
    }

    private ClubEventOpsMapper mapper(boolean failWaitlistCampaign) {
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(ACTIVITY)).thenReturn(series());
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(mapper.selectOccurrenceByActivityAndClubForUpdate(ACTIVITY, CLUB)).thenReturn(occurrence());
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(101L));
        when(mapper.selectWaitlistRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(201L));
        when(mapper.cancelOccurrenceCas(81L, 0)).thenAnswer(call -> jdbc.update(
                "update occurrence_state set status='CANCELLED',version=version+1 "
                        + "where id=81 and status='ACTIVE' and version=0"));
        when(mapper.selectActiveWaitlistsForUpdate(CLUB, ACTIVITY))
                .thenReturn(Collections.singletonList(waitlist()));
        when(mapper.cancelActiveWaitlistCas(92L, 0, ClubWaitlistEntry.OFFERED, 201L, ACTIVITY, 302L))
                .thenAnswer(call -> jdbc.update(
                        "update waitlist_state set state='CANCELLED',version=version+1 "
                                + "where id=92 and state='OFFERED' and version=0"));
        when(mapper.restoreWaitlistHold(302L, ACTIVITY)).thenAnswer(call -> jdbc.update(
                "update ticket_state set remaining=remaining+1 where id=302 and remaining < total"));
        AtomicLong campaignIds = new AtomicLong(900L);
        when(mapper.insertCampaign(any(ClubEventCampaign.class))).thenAnswer(call -> {
            ClubEventCampaign row = call.getArgument(0, ClubEventCampaign.class);
            if (failWaitlistCampaign && "WAITLIST".equals(row.getAudienceType())) return 0;
            row.setId(campaignIds.incrementAndGet());
            return jdbc.update("insert into campaign_state values (?,?,?)",
                    row.getId(), row.getAudienceType(), row.getRequestId());
        });
        AtomicLong deliveryIds = new AtomicLong(1000L);
        when(mapper.insertDelivery(any(ClubEventDelivery.class))).thenAnswer(call -> {
            ClubEventDelivery row = call.getArgument(0, ClubEventDelivery.class);
            row.setId(deliveryIds.incrementAndGet());
            return jdbc.update("insert into delivery_state values (?,?,?)",
                    row.getId(), row.getCampaignId(), row.getRecipientMemberId());
        });
        when(mapper.insertOutbox(any(), any(Date.class))).thenAnswer(call -> jdbc.update(
                "insert into outbox_state values (?)", call.getArgument(0, Long.class)));
        return mapper;
    }

    private ClubEventSeries series() {
        ClubEventSeries row = new ClubEventSeries();
        row.setId(61L);
        row.setClubId(CLUB);
        row.setStatus(ClubEventSeries.STATUS_ACTIVE);
        return row;
    }

    private ClubEventOccurrence occurrence() {
        ClubEventOccurrence row = new ClubEventOccurrence();
        row.setId(81L);
        row.setSeriesId(61L);
        row.setActivityId(ACTIVITY);
        row.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        row.setVersion(0);
        return row;
    }

    private ClubWaitlistEntry waitlist() {
        ClubWaitlistEntry row = new ClubWaitlistEntry();
        row.setId(92L);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setTicketId(302L);
        row.setMemberId(201L);
        row.setState(ClubWaitlistEntry.OFFERED);
        row.setVersion(0);
        return row;
    }

    private ClubEventCancellationRequest request() {
        ClubEventCancellationRequest row = new ClubEventCancellationRequest();
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setReason("场地临时关闭");
        row.setRequestId("cancel-tx-1");
        return row;
    }

    private int value(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? -1 : result;
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }
}
