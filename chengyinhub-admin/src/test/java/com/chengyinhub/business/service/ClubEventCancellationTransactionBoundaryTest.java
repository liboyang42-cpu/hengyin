package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.business.service.impl.ClubRoleAssignmentServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 取消场次、EVENT_LEAD 撤销和逐条审计必须属于同一个回滚边界。 */
class ClubEventCancellationTransactionBoundaryTest {
    private static final Long ACTOR = 41L;
    private static final Long OWNER = 42L;
    private static final Long CLUB = 21L;
    private static final Long SERIES = 61L;
    private static final Long REMOVE_ACTIVITY = 71L;
    private static final Long KEEP_ACTIVITY = 72L;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_event_cancel_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table event_state(activity_id bigint primary key, active int not null)");
        jdbc.execute("create table occurrence_state(id bigint primary key, active int not null, version int not null)");
        jdbc.execute("create table series_state(id bigint primary key, version int not null)");
        jdbc.execute("create table role_state(id bigint primary key, active int not null, version int not null)");
        jdbc.execute("create table audit_state(id bigint auto_increment primary key, domain_type varchar(16), "
                + "domain_id bigint, action varchar(64), request_id varchar(64), "
                + "unique(domain_type,domain_id,action,request_id))");
        jdbc.update("insert into event_state values (?,1),(?,1)", REMOVE_ACTIVITY, KEEP_ACTIVITY);
        jdbc.update("insert into occurrence_state values (81,1,0),(82,1,0)");
        jdbc.update("insert into series_state values (?,0)", SERIES);
        jdbc.update("insert into role_state values (91,1,0),(92,1,0)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void successfulRemovalCommitsRoleActivityOccurrenceAuditAndVersionTogether() {
        ClubEventOpsServiceImpl service = service(false);

        transaction.executeWithoutResult(status -> service.updateFuture(ACTOR, "operator", request()));

        assertEquals(0, intValue("select active from event_state where activity_id=?", REMOVE_ACTIVITY));
        assertEquals(0, intValue("select active from occurrence_state where id=81"));
        assertEquals(0, intValue("select active from role_state where id=91"));
        assertEquals(0, intValue("select active from role_state where id=92"));
        assertEquals(2, intValue("select count(*) from audit_state"));
        assertEquals(1, intValue("select version from series_state where id=?", SERIES));
    }

    @Test
    void secondAuditFailureRollsBackBothRolesAndEveryCancellationWrite() throws Exception {
        ClubEventOpsServiceImpl service = service(true);

        assertThrows(ServiceException.class,
                () -> transaction.executeWithoutResult(status -> service.updateFuture(ACTOR, "operator", request())));

        assertEquals(1, intValue("select active from event_state where activity_id=?", REMOVE_ACTIVITY));
        assertEquals(1, intValue("select active from occurrence_state where id=81"));
        assertEquals(1, intValue("select active from role_state where id=91"));
        assertEquals(1, intValue("select active from role_state where id=92"));
        assertEquals(0, intValue("select count(*) from audit_state"));
        assertEquals(0, intValue("select version from series_state where id=?", SERIES));

        Transactional outer = ClubEventOpsServiceImpl.class.getDeclaredMethod(
                "updateFuture", Long.class, String.class, ClubEventSeriesRequest.class)
                .getAnnotation(Transactional.class);
        Transactional inner = ClubRoleAssignmentServiceImpl.class.getDeclaredMethod(
                "revokeEventLeadForCancelledOccurrence", Long.class, Long.class, Long.class, String.class)
                .getAnnotation(Transactional.class);
        assertTrue(outer != null && inner != null);
        assertTrue(Arrays.asList(outer.rollbackFor()).contains(Exception.class));
        assertTrue(Arrays.asList(inner.rollbackFor()).contains(Exception.class));
    }

    private ClubEventOpsServiceImpl service(boolean failSecondAudit) {
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, OWNER, null,
                        Collections.singleton(ClubRoleCode.CLUB_CO_OWNER)));

        ClubRoleAssignmentMapper roles = mock(ClubRoleAssignmentMapper.class);
        when(roles.selectActiveEventLeadsForUpdate(CLUB, REMOVE_ACTIVITY)).thenAnswer(call -> {
            if (intValue("select count(*) from role_state where active=1") == 0) return Collections.emptyList();
            return Arrays.asList(role(91L, 51L), role(92L, 52L));
        });
        when(roles.revokeCas(any(Long.class), any(Long.class), any(Integer.class),
                any(Long.class), any(String.class), any(String.class))).thenAnswer(call -> jdbc.update(
                "update role_state set active=0,version=version+1 where id=? and active=1 and version=?",
                new Object[] { call.getArgument(0, Long.class), call.getArgument(2, Integer.class) }));
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        AtomicInteger auditAttempt = new AtomicInteger();
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenAnswer(call -> {
            BusinessAuditEvent event = call.getArgument(0);
            if (failSecondAudit && auditAttempt.incrementAndGet() == 2) return 0;
            return jdbc.update("insert into audit_state(domain_type,domain_id,action,request_id) values (?,?,?,?)",
                    event.getDomainType(), event.getDomainId(), event.getAction(), event.getRequestId());
        });
        ClubRoleAssignmentServiceImpl roleService = new ClubRoleAssignmentServiceImpl();
        ReflectionTestUtils.setField(roleService, "clubAccessService", access);
        ReflectionTestUtils.setField(roleService, "clubMapper", clubMapper());
        ReflectionTestUtils.setField(roleService, "clubMemberMapper", mock(ClubMemberMapper.class));
        ReflectionTestUtils.setField(roleService, "cmsActivityMapper", activityMapper());
        ReflectionTestUtils.setField(roleService, "clubRoleAssignmentMapper", roles);
        ReflectionTestUtils.setField(roleService, "businessAuditEventMapper", audits);

        return new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(),
                mock(IClubSessionService.class), eventMapper(), mock(ClubMemberMapper.class),
                mock(ClubLeadMapper.class), roleService);
    }

    private ClubEventOpsMapper eventMapper() {
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(series());
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(mapper.selectFutureOccurrencesForUpdate(any(Long.class), any(Date.class)))
                .thenReturn(Arrays.asList(
                        occurrence(81L, REMOVE_ACTIVITY, LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2)),
                        occurrence(82L, KEEP_ACTIVITY, LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1))));
        when(mapper.countProtectedActivityHistory(any(Long.class))).thenReturn(0);
        when(mapper.cancelFutureActivityCas(any(Long.class), any(Date.class), any(String.class)))
                .thenAnswer(call -> jdbc.update(
                        "update event_state set active=0 where activity_id=? and active=1",
                        new Object[] { call.getArgument(0, Long.class) }));
        when(mapper.cancelOccurrenceCas(any(Long.class), any(Integer.class))).thenAnswer(call -> jdbc.update(
                "update occurrence_state set active=0,version=version+1 where id=? and active=1 and version=?",
                new Object[] { call.getArgument(0, Long.class), call.getArgument(1, Integer.class) }));
        when(mapper.updateSeriesScheduleCas(any(Long.class), any(Integer.class),
                any(String.class), any(String.class), any(Date.class))).thenAnswer(call -> jdbc.update(
                "update series_state set version=version+1 where id=? and version=?",
                new Object[] { call.getArgument(0, Long.class), call.getArgument(1, Integer.class) }));
        return mapper;
    }

    private ClubMapper clubMapper() {
        ClubMapper mapper = mock(ClubMapper.class);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(mapper.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        return mapper;
    }

    private CmsActivityMapper activityMapper() {
        CmsActivityMapper mapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(REMOVE_ACTIVITY);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        when(mapper.selectCmsActivityById(REMOVE_ACTIVITY)).thenReturn(activity);
        return mapper;
    }

    private ClubEventSeries series() {
        ClubEventSeries row = new ClubEventSeries();
        row.setId(SERIES);
        row.setClubId(CLUB);
        row.setTopicId(31L);
        row.setRecurrenceType("WEEKLY");
        row.setDefaultLeadMemberId(51L);
        row.setWaitlistEnabled(true);
        row.setOfferMinutes(30);
        row.setStatus(ClubEventSeries.STATUS_ACTIVE);
        row.setVersion(0);
        return row;
    }

    private ClubEventOccurrence occurrence(Long id, Long activityId, LocalDate date) {
        ClubEventOccurrence row = new ClubEventOccurrence();
        row.setId(id);
        row.setSeriesId(SERIES);
        row.setActivityId(activityId);
        row.setOccurrenceAt(Date.from(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
        row.setDefaultLeadMemberId(51L);
        row.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        row.setVersion(0);
        return row;
    }

    private ClubRoleAssignment role(Long id, Long memberId) {
        ClubRoleAssignment row = new ClubRoleAssignment();
        row.setId(id);
        row.setClubId(CLUB);
        row.setTargetMemberId(memberId);
        row.setRoleCode(ClubRoleCode.EVENT_LEAD);
        row.setScopeType(ClubRoleAssignment.SCOPE_EVENT);
        row.setScopeId(REMOVE_ACTIVITY);
        row.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        row.setVersion(0);
        return row;
    }

    private ClubEventSeriesRequest request() {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setSeriesId(SERIES);
        request.setExpectedVersion(0);
        request.setClubId(CLUB);
        request.setTopicId(31L);
        request.setRecurrenceType("CUSTOM_DATES");
        request.setCustomDates(Collections.singletonList(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString()));
        request.setStartTime("14:30");
        request.setDefaultLeadMemberId(51L);
        request.setWaitlistEnabled(true);
        request.setOfferMinutes(30);
        return request;
    }

    private int intValue(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }
}
