package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ClubEventOpsServiceImplTest {
    private static final Long ACTOR = 41L;
    private static final Long OWNER = 42L;
    private static final Long CLUB = 21L;
    private static final Long TOPIC = 31L;
    private static final Long LEAD = 51L;

    private IClubAccessService accessService;
    private IClubSessionService sessionService;
    private ClubEventOpsMapper mapper;
    private ClubMemberMapper memberMapper;
    private ClubLeadMapper leadMapper;
    private IClubRoleAssignmentService roleAssignmentService;
    private ClubEventOpsServiceImpl service;

    @BeforeEach
    void setUp() {
        accessService = mock(IClubAccessService.class);
        sessionService = mock(IClubSessionService.class);
        mapper = mock(ClubEventOpsMapper.class);
        memberMapper = mock(ClubMemberMapper.class);
        leadMapper = mock(ClubLeadMapper.class);
        roleAssignmentService = mock(IClubRoleAssignmentService.class);
        service = new ClubEventOpsServiceImpl(accessService, new ClubEventScheduleMaterializer(),
                sessionService, mapper, memberMapper, leadMapper, roleAssignmentService);
        when(accessService.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, OWNER, null,
                        Collections.singleton(ClubRoleCode.CLUB_CO_OWNER)));
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(mapper.insertSeries(any())).thenAnswer(invocation -> {
            ClubEventSeries row = invocation.getArgument(0);
            row.setId(61L);
            return 1;
        });
        when(sessionService.open(any(), any(), any())).thenReturn(71L, 72L);
        when(mapper.insertOccurrence(any())).thenReturn(1);
        when(leadMapper.insertClubLeadState(any())).thenReturn(1);
        ClubMember lead = new ClubMember();
        lead.setId(1L);
        lead.setClubId(CLUB);
        lead.setMemberId(LEAD);
        lead.setStatus(1);
        lead.setDelFlag(0);
        when(memberMapper.selectByClubAndMember(CLUB, LEAD)).thenReturn(lead);
    }

    @Test
    void coOwnerCreatesWeeklySeriesThroughActivityManageScopeAndOwnerResponsibility() {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setRecurrenceType("WEEKLY");
        request.setStartDate(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());
        request.setStartTime("14:30");
        request.setOccurrenceCount(2);
        request.setDefaultLeadMemberId(LEAD);
        request.setWaitlistEnabled(true);
        request.setOfferMinutes(20);
        request.setRequestId("series-weekly-1");

        ClubEventSeries created = service.createSeries(ACTOR, "operator", request);

        assertEquals(Long.valueOf(61L), created.getId());
        assertEquals("Asia/Shanghai", created.getTimezone());
        assertEquals(LEAD, created.getDefaultLeadMemberId());
        assertEquals(64, created.getRequestFingerprint().length());
        verify(accessService, org.mockito.Mockito.times(2))
                .require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE);
        ArgumentCaptor<ClubSessionOpenRequest> session = ArgumentCaptor.forClass(ClubSessionOpenRequest.class);
        verify(sessionService, org.mockito.Mockito.times(2)).open(session.capture(),
                org.mockito.ArgumentMatchers.eq(OWNER), org.mockito.ArgumentMatchers.eq("operator"));
        assertEquals(CLUB, session.getAllValues().get(0).getClubId());
        assertEquals(TOPIC, session.getAllValues().get(0).getTopicId());
        // 5.9 修复:开场入参必须带集合时刻,场次 start_date 才有非 00:00 的退款钟锚点。
        assertEquals("14:30", session.getAllValues().get(0).getSessionTime());
        ArgumentCaptor<ClubEventOccurrence> occurrence = ArgumentCaptor.forClass(ClubEventOccurrence.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertOccurrence(occurrence.capture());
        assertEquals(java.util.Arrays.asList(71L, 72L), occurrence.getAllValues().stream()
                .map(ClubEventOccurrence::getActivityId).collect(java.util.stream.Collectors.toList()));
        assertEquals("14:30", occurrence.getAllValues().get(0).getOccurrenceAt().toInstant()
                        .atZone(ZoneId.of("Asia/Shanghai")).toLocalTime().toString(),
                "场次物化时刻必须带上集合时刻,不能被压成 00:00");
        verify(leadMapper, org.mockito.Mockito.times(2)).insertClubLeadState(any(ClubLeadState.class));
        verify(roleAssignmentService).assignEventLeadForOccurrence(
                ACTOR, CLUB, LEAD, 71L, "series-lead-61-71");
        verify(roleAssignmentService).assignEventLeadForOccurrence(
                ACTOR, CLUB, LEAD, 72L, "series-lead-61-72");
    }

    /**
     * CU-C-35:日期就是上海今天、真正过期的是集合时刻。物化器只比日期,这一档原来落到
     * 「日期不能早于今天」上 —— 而表单里的日期正是今天,读的人不知道该怎么办。
     * 基准统一用上海当前时刻(与排期基准日同一个钟)。
     */
    @Test
    void createSeriesRejectsAssemblyMomentThatAlreadyPassedToday() {
        // 2027-01-15T08:00:00Z = 上海 16:00,固定钟让这条不随时区/运行时刻漂
        Date fixed = new Date(1_800_000_000_000L);
        ClubEventOpsServiceImpl clocked = new ClubEventOpsServiceImpl(accessService,
                new ClubEventScheduleMaterializer(), sessionService, mapper, memberMapper, leadMapper,
                roleAssignmentService) {
            @Override protected Date now() { return fixed; }
        };
        String today = fixed.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();

        ClubEventSeriesRequest past = createRequest("series-past-today");
        past.setStartDate(today);
        past.setStartTime("15:59");
        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> clocked.createSeries(ACTOR, "operator", past));

        assertEquals(Integer.valueOf(400), rejected.getCode());
        assertTrue(rejected.getMessage().contains("集合时间必须晚于当前时间"),
                "拒绝原因必须说时刻,实际:" + rejected.getMessage());
        verify(mapper, never()).insertSeries(any());
        verify(sessionService, never()).open(any(), any(), any());

        // 同一档、时刻未到 ⇒ 放行(不允许把「今天」整档一起堵死)
        ClubEventSeriesRequest later = createRequest("series-later-today");
        later.setStartDate(today);
        later.setStartTime("16:01");
        assertEquals(Long.valueOf(61L), clocked.createSeries(ACTOR, "operator", later).getId());
        verify(sessionService, org.mockito.Mockito.times(1)).open(any(), any(), any());
    }

    @Test
    void concurrentDuplicateSeriesReturnsWinnerWithoutOpeningDuplicateOccurrences() {
        AtomicReference<ClubEventSeries> attempted = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new DuplicateKeyException("uk_club_event_series_request");
        }).when(mapper).insertSeries(any());
        when(mapper.selectSeriesByRequestForUpdate(CLUB, ACTOR, "series-concurrent-1"))
                .thenAnswer(invocation -> {
                    ClubEventSeries winner = new ClubEventSeries();
                    winner.setId(99L);
                    winner.setClubId(CLUB);
                    winner.setTopicId(TOPIC);
                    winner.setRequestId("series-concurrent-1");
                    winner.setRequestFingerprint(attempted.get().getRequestFingerprint());
                    return winner;
                });
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setRecurrenceType("ONCE");
        request.setStartDate(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());
        request.setStartTime("09:00");
        request.setOccurrenceCount(1);
        request.setDefaultLeadMemberId(LEAD);
        request.setWaitlistEnabled(true);
        request.setOfferMinutes(20);
        request.setRequestId("series-concurrent-1");

        ClubEventSeries replay = service.createSeries(ACTOR, "operator", request);

        assertEquals(Long.valueOf(99L), replay.getId());
        verify(mapper).selectSeriesByRequestForUpdate(CLUB, ACTOR, "series-concurrent-1");
        verify(sessionService, never()).open(any(), any(), any());
        verify(mapper, never()).insertOccurrence(any());
    }

    /** P1-RF-1:缺集合时刻的系列开场必须在建系列/开场次之前就被拒,不能静默落 00:00。 */
    @Test
    void createSeriesWithoutAssemblyTimeIsRejectedBeforeAnyWrite() {
        ClubEventSeriesRequest request = createRequest("series-missing-time");
        request.setStartTime(null);

        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.createSeries(ACTOR, "operator", request));

        assertEquals(Integer.valueOf(400), rejected.getCode());
        assertTrue(rejected.getMessage().contains("集合时刻"),
                "拒绝原因必须点名缺失的是集合时刻,实际:" + rejected.getMessage());
        verify(mapper, never()).insertSeries(any());
        verify(sessionService, never()).open(any(), any(), any());
    }

    /** 未来编辑同一条链:缺时刻同样拒,不能只堵创建口。 */
    @Test
    void updateFutureWithoutAssemblyTimeIsRejectedBeforeAnyWrite() {
        when(mapper.selectSeriesByIdForUpdate(61L)).thenReturn(storedSeries());
        ClubEventSeriesRequest request = futureRequest(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1));
        request.setStartTime(" ");

        com.chengyinhub.common.exception.ServiceException rejected = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.updateFuture(ACTOR, "operator", request));

        assertEquals(Integer.valueOf(400), rejected.getCode());
        assertTrue(rejected.getMessage().contains("集合时刻"),
                "拒绝原因必须点名缺失的是集合时刻,实际:" + rejected.getMessage());
        verify(sessionService, never()).open(any(), any(), any());
        verify(mapper, never()).updateSeriesScheduleCas(any(), any(), any(), any(), any());
    }

    @Test
    void revokedCoOwnerCannotCreateAfterAuthoritativeClubLockRecheck() {
        when(accessService.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_CO_OWNER), access(ClubRoleCode.CLUB_MEMBER));

        com.chengyinhub.common.exception.ServiceException denied = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.createSeries(ACTOR, "revoked", createRequest("series-revoked")));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accessService, org.mockito.Mockito.times(2))
                .require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE);
        verify(mapper, never()).insertSeries(any());
        verify(sessionService, never()).open(any(), any(), any());
    }

    @Test
    void futureOnlyEditRejectsRemovingOccurrenceWithAnyRegistrationHistory() {
        ClubEventSeries stored = storedSeries();
        when(mapper.selectSeriesByIdForUpdate(61L)).thenReturn(stored);
        LocalDate removableDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        ClubEventOccurrence occurrence = new ClubEventOccurrence();
        occurrence.setId(81L);
        occurrence.setSeriesId(61L);
        occurrence.setActivityId(71L);
        occurrence.setOccurrenceAt(Date.from(removableDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
        occurrence.setDefaultLeadMemberId(LEAD);
        occurrence.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        occurrence.setVersion(0);
        when(mapper.selectFutureOccurrencesForUpdate(org.mockito.ArgumentMatchers.eq(61L), any()))
                .thenReturn(Collections.singletonList(occurrence));
        when(mapper.countProtectedActivityHistory(71L)).thenReturn(1);

        ClubEventSeriesRequest request = futureRequest(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1));

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.updateFuture(ACTOR, "operator", request));
        verify(mapper, never()).cancelFutureActivityCas(any(), any(), any());
        verify(sessionService, never()).open(any(), any(), any());
    }

    @Test
    void revokedCoOwnerCannotUpdateAfterSeriesAndClubLocks() {
        when(mapper.selectSeriesByIdForUpdate(61L)).thenReturn(storedSeries());
        when(accessService.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_CO_OWNER), access(ClubRoleCode.CLUB_MEMBER));

        com.chengyinhub.common.exception.ServiceException denied = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.updateFuture(ACTOR, "revoked", futureRequest(
                        LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1))));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verify(accessService, org.mockito.Mockito.times(2))
                .require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE);
        verify(mapper, never()).selectFutureOccurrencesForUpdate(any(), any());
        verify(mapper, never()).updateSeriesScheduleCas(any(), any(), any(), any(), any());
        verify(sessionService, never()).open(any(), any(), any());
    }

    @Test
    void futureOnlyEditCreatesOnlyMissingDatesAndAdvancesSeriesCas() {
        ClubEventSeries stored = storedSeries();
        when(mapper.selectSeriesByIdForUpdate(61L)).thenReturn(stored);
        when(mapper.selectFutureOccurrencesForUpdate(org.mockito.ArgumentMatchers.eq(61L), any()))
                .thenReturn(Collections.emptyList());
        when(mapper.updateSeriesScheduleCas(org.mockito.ArgumentMatchers.eq(61L),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq("CUSTOM_DATES"),
                org.mockito.ArgumentMatchers.eq("operator"), any())).thenReturn(1);

        ClubEventSeries updated = service.updateFuture(ACTOR, "operator", futureRequest(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)));

        assertEquals(Integer.valueOf(1), updated.getVersion());
        verify(sessionService).open(any(ClubSessionOpenRequest.class),
                org.mockito.ArgumentMatchers.eq(OWNER), org.mockito.ArgumentMatchers.eq("operator"));
        verify(mapper).updateSeriesScheduleCas(org.mockito.ArgumentMatchers.eq(61L),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq("CUSTOM_DATES"),
                org.mockito.ArgumentMatchers.eq("operator"), any());
        verify(roleAssignmentService).assignEventLeadForOccurrence(
                ACTOR, CLUB, LEAD, 71L, "series-lead-61-71");
    }

    @Test
    void activityManagerReadsOnlyActiveSeriesAndFutureDatesInSameClub() {
        ClubEventSeries stored = storedSeries();
        when(mapper.selectActiveSeriesByClub(CLUB)).thenReturn(Collections.singletonList(stored));
        when(mapper.selectActiveSeriesByIdAndClub(61L, CLUB)).thenReturn(stored);
        LocalDate future = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        ClubEventOccurrence occurrence = activeOccurrence(81L, 71L, future);
        when(mapper.selectFutureOccurrences(org.mockito.ArgumentMatchers.eq(61L),
                org.mockito.ArgumentMatchers.eq(CLUB), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(occurrence));

        java.util.List<com.chengyinhub.business.domain.vo.ClubEventSeriesView> rows =
                service.listSeries(ACTOR, CLUB);
        com.chengyinhub.business.domain.vo.ClubEventSeriesView detail =
                service.getSeries(ACTOR, CLUB, 61L);

        assertEquals(1, rows.size());
        assertEquals(Long.valueOf(61L), rows.get(0).getId());
        assertEquals(Collections.singletonList(future.toString()), detail.getFutureDates());
        Date tomorrow = Date.from(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        verify(mapper).selectFutureOccurrences(61L, CLUB, tomorrow);
        assertEquals(Integer.valueOf(0), detail.getVersion());
        verify(accessService, org.mockito.Mockito.times(2))
                .require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE);
    }

    @Test
    void futureOnlyCancellationRevokesCanonicalEventLeadBeforeSeriesVersionAdvances() {
        ClubEventSeries stored = storedSeries();
        when(mapper.selectSeriesByIdForUpdate(61L)).thenReturn(stored);
        LocalDate removable = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        ClubEventOccurrence occurrence = activeOccurrence(81L, 71L, removable);
        when(mapper.selectFutureOccurrencesForUpdate(org.mockito.ArgumentMatchers.eq(61L), any()))
                .thenReturn(Collections.singletonList(occurrence));
        when(mapper.countProtectedActivityHistory(71L)).thenReturn(0);
        when(mapper.cancelFutureActivityCas(org.mockito.ArgumentMatchers.eq(71L), any(),
                org.mockito.ArgumentMatchers.eq("operator"))).thenReturn(1);
        when(mapper.cancelOccurrenceCas(81L, 0)).thenReturn(1);
        when(mapper.updateSeriesScheduleCas(org.mockito.ArgumentMatchers.eq(61L),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq("CUSTOM_DATES"),
                org.mockito.ArgumentMatchers.eq("operator"), any())).thenReturn(1);
        ClubEventSeriesRequest request = futureRequest(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1));

        service.updateFuture(ACTOR, "operator", request);

        verify(roleAssignmentService).revokeEventLeadForCancelledOccurrence(
                ACTOR, CLUB, 71L, "series-cancel-61-71");
        org.mockito.InOrder lifecycle = org.mockito.Mockito.inOrder(
                mapper, roleAssignmentService);
        lifecycle.verify(roleAssignmentService).revokeEventLeadForCancelledOccurrence(
                ACTOR, CLUB, 71L, "series-cancel-61-71");
        lifecycle.verify(mapper).cancelFutureActivityCas(
                org.mockito.ArgumentMatchers.eq(71L), any(), org.mockito.ArgumentMatchers.eq("operator"));
        lifecycle.verify(mapper).cancelOccurrenceCas(81L, 0);
        lifecycle.verify(mapper).updateSeriesScheduleCas(
                org.mockito.ArgumentMatchers.eq(61L), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq("CUSTOM_DATES"),
                org.mockito.ArgumentMatchers.eq("operator"), any());
    }

    @Test
    void auditFailureStopsOccurrenceAndActivityCancellationAndSeriesCas() {
        ClubEventSeries stored = storedSeries();
        when(mapper.selectSeriesByIdForUpdate(61L)).thenReturn(stored);
        LocalDate removable = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        ClubEventOccurrence occurrence = activeOccurrence(81L, 71L, removable);
        when(mapper.selectFutureOccurrencesForUpdate(org.mockito.ArgumentMatchers.eq(61L), any()))
                .thenReturn(Collections.singletonList(occurrence));
        when(mapper.countProtectedActivityHistory(71L)).thenReturn(0);
        when(roleAssignmentService.revokeEventLeadForCancelledOccurrence(
                ACTOR, CLUB, 71L, "series-cancel-61-71"))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException(
                        "业务审计写入失败", com.chengyinhub.common.constant.HttpStatus.CONFLICT));

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.updateFuture(ACTOR, "operator", futureRequest(
                        LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1))));

        verify(mapper, never()).cancelFutureActivityCas(any(), any(), any());
        verify(mapper, never()).cancelOccurrenceCas(any(), any());
        verify(mapper, never()).updateSeriesScheduleCas(any(), any(), any(), any(), any());
    }

    private ClubEventSeries storedSeries() {
        ClubEventSeries series = new ClubEventSeries();
        series.setId(61L);
        series.setClubId(CLUB);
        series.setTopicId(TOPIC);
        series.setRecurrenceType("WEEKLY");
        series.setDefaultLeadMemberId(LEAD);
        series.setDefaultCapacity(null);
        series.setWaitlistEnabled(true);
        series.setOfferMinutes(20);
        series.setStatus(ClubEventSeries.STATUS_ACTIVE);
        series.setVersion(0);
        return series;
    }

    private ClubAccessContext access(String roleCode) {
        return new ClubAccessContext(ACTOR, CLUB, OWNER, null, Collections.singleton(roleCode));
    }

    private ClubEventSeriesRequest createRequest(String requestId) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setRecurrenceType("ONCE");
        request.setStartDate(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());
        request.setStartTime("09:00");
        request.setOccurrenceCount(1);
        request.setDefaultLeadMemberId(LEAD);
        request.setWaitlistEnabled(true);
        request.setOfferMinutes(20);
        request.setRequestId(requestId);
        return request;
    }

    private ClubEventOccurrence activeOccurrence(Long id, Long activityId, LocalDate date) {
        ClubEventOccurrence occurrence = new ClubEventOccurrence();
        occurrence.setId(id);
        occurrence.setSeriesId(61L);
        occurrence.setActivityId(activityId);
        occurrence.setOccurrenceAt(Date.from(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
        occurrence.setDefaultLeadMemberId(LEAD);
        occurrence.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        occurrence.setVersion(0);
        return occurrence;
    }

    private ClubEventSeriesRequest futureRequest(LocalDate date) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setSeriesId(61L);
        request.setExpectedVersion(0);
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setRecurrenceType("CUSTOM_DATES");
        request.setCustomDates(Collections.singletonList(date.toString()));
        request.setStartTime("14:30");
        request.setDefaultLeadMemberId(LEAD);
        request.setWaitlistEnabled(true);
        request.setOfferMinutes(20);
        return request;
    }
}
