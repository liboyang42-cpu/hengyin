package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;
import com.chengyinhub.business.domain.vo.ClubEventOccurrenceRowVO;
import com.chengyinhub.business.domain.vo.ClubEventSeriesView;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real service reads and update, with mocked persistence. SQL/VPS readback remains separate. */
class B04ClubEventScheduleReadbackTest {
    private static final Long ACTOR = 41L, OWNER = 42L, CLUB = 21L, TOPIC = 31L, SERIES = 61L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final Date fixedNow = new Date(1_800_000_000_000L);
    private final LocalDate future = fixedNow.toInstant().atZone(SHANGHAI).toLocalDate().plusDays(2);
    private ClubEventOpsMapper mapper;
    private IClubSessionService sessions;
    private ClubEventOpsServiceImpl service;

    @BeforeEach
    void setUp() {
        IClubAccessService access = mock(IClubAccessService.class);
        mapper = mock(ClubEventOpsMapper.class);
        sessions = mock(IClubSessionService.class);
        ClubLeadMapper leads = mock(ClubLeadMapper.class);
        service = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(), sessions,
                mapper, mock(ClubMemberMapper.class), leads, mock(IClubRoleAssignmentService.class)) {
            @Override protected Date now() { return fixedNow; }
        };
        when(access.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, OWNER, null,
                        Collections.singleton(ClubRoleCode.CLUB_CO_OWNER)));
        ClubEventSeries series = new ClubEventSeries();
        series.setId(SERIES);
        series.setClubId(CLUB);
        series.setTopicId(TOPIC);
        series.setRecurrenceType("WEEKLY");
        series.setTimezone("Asia/Shanghai");
        series.setDefaultLeadMemberId(OWNER);
        series.setWaitlistEnabled(true);
        series.setOfferMinutes(20);
        series.setVersion(0);
        series.setStatus(ClubEventSeries.STATUS_ACTIVE);
        when(mapper.selectActiveSeriesByIdAndClub(SERIES, CLUB)).thenReturn(series);
        when(mapper.selectActiveSeriesByClub(CLUB)).thenReturn(Collections.singletonList(series));
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(series);
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(sessions.open(any(), eq(OWNER), eq("operator"))).thenReturn(72L);
        when(mapper.insertOccurrence(any())).thenReturn(1);
        when(leads.insertClubLeadState(any())).thenReturn(1);
        when(mapper.updateSeriesScheduleCas(eq(SERIES), eq(0), eq("CUSTOM_DATES"),
                eq("operator"), any())).thenReturn(1);
    }

    private ClubEventOccurrence occurrence(Long id, Long activity, LocalDate day, String time) {
        ClubEventOccurrence row = new ClubEventOccurrence();
        row.setId(id);
        row.setSeriesId(SERIES);
        row.setActivityId(activity);
        row.setOccurrenceAt(Date.from(day.atTime(LocalTime.parse(time)).atZone(SHANGHAI).toInstant()));
        row.setDefaultLeadMemberId(OWNER);
        row.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        row.setVersion(0);
        return row;
    }

    private ClubEventSeriesRequest futureRequest(String startTime) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setSeriesId(SERIES);
        request.setExpectedVersion(0);
        request.setRecurrenceType("CUSTOM_DATES");
        request.setCustomDates(Arrays.asList(future.toString(), future.plusDays(7).toString()));
        request.setStartTime(startTime);
        return request;
    }

    @Test
    void reopeningAndAddingDateRetainsNonDefaultShanghaiAssemblyTime() {
        ClubEventOccurrence old = occurrence(81L, 71L, future, "00:30");
        when(mapper.selectFutureOccurrences(eq(SERIES), eq(CLUB), any()))
                .thenReturn(Collections.singletonList(old));
        when(mapper.selectFutureOccurrencesForUpdate(eq(SERIES), any()))
                .thenReturn(Collections.singletonList(old));

        ClubEventSeriesView detail = service.getSeries(ACTOR, CLUB, SERIES);
        assertEquals("00:30", detail.getStartTime(), "上海跨UTC日期边界不能变成09:00或UTC时刻");
        assertEquals(Collections.singletonList(future.toString()), detail.getFutureDates());
        assertEquals(detail.getStartTime(), service.listSeries(ACTOR, CLUB).get(0).getStartTime());
        service.updateFuture(ACTOR, "operator", futureRequest(detail.getStartTime()));

        // The same expectedVersion cannot materialize the added date twice.
        assertThrows(ServiceException.class,
                () -> service.updateFuture(ACTOR, "operator", futureRequest(detail.getStartTime())));

        ArgumentCaptor<ClubSessionOpenRequest> open = ArgumentCaptor.forClass(ClubSessionOpenRequest.class);
        verify(sessions).open(open.capture(), eq(OWNER), eq("operator"));
        assertEquals("00:30", open.getValue().getSessionTime());
        assertEquals(future.plusDays(7).atTime(0, 30),
                open.getValue().getSessionDate().toInstant().atZone(SHANGHAI).toLocalDateTime());
    }

    @Test
    void mixedAssemblyTimesDoNotInventSeriesDefault() {
        when(mapper.selectFutureOccurrences(eq(SERIES), eq(CLUB), any()))
                .thenReturn(Arrays.asList(occurrence(81L, 71L, future, "14:30"),
                        occurrence(82L, 72L, future.plusDays(7), "16:45")));
        assertNull(service.getSeries(ACTOR, CLUB, SERIES).getStartTime());
        assertNull(service.listSeries(ACTOR, CLUB).get(0).getStartTime());
    }

    @Test
    void noFutureOccurrenceDoesNotInventNineOClock() {
        when(mapper.selectFutureOccurrences(eq(SERIES), eq(CLUB), any()))
                .thenReturn(Collections.emptyList());
        assertNull(service.getSeries(ACTOR, CLUB, SERIES).getStartTime());
        assertNull(service.listSeries(ACTOR, CLUB).get(0).getStartTime());
    }

    private Map<String, Object> occurrenceReadback(Integer signup, int protectedHistory) {
        ClubEventOccurrenceRowVO row = new ClubEventOccurrenceRowVO();
        row.setOccurrenceId(81L);
        row.setActivityId(71L);
        row.setOccurrenceAt(occurrence(81L, 71L, future, "14:30").getOccurrenceAt());
        row.setStatus(ClubEventOccurrence.STATUS_ACTIVE);
        row.setSignupCount(signup);
        row.setRefundedCount(0);
        row.setVersion(0);
        when(mapper.selectSeriesOccurrenceRows(SERIES, CLUB)).thenReturn(Collections.singletonList(row));
        when(mapper.countProtectedActivityHistory(71L)).thenReturn(protectedHistory);
        return service.listSeriesOccurrences(ACTOR, CLUB, SERIES).get(0);
    }

    @Test
    void zeroSignupWithProtectedWaitlistOrHistoryIsNotEditableAndCannotBeRemoved() {
        Map<String, Object> row = occurrenceReadback(0, 1);
        assertEquals(false, row.get("editable"));
        assertEquals("已有报名、候补或结算事实", row.get("lockReason"));
        when(mapper.selectFutureOccurrencesForUpdate(eq(SERIES), any()))
                .thenReturn(Collections.singletonList(occurrence(81L, 71L, future.plusDays(1), "14:30")));
        assertThrows(ServiceException.class,
                () -> service.updateFuture(ACTOR, "operator", futureRequest("14:30")));
        verify(mapper, never()).cancelFutureActivityCas(any(), any(), any());
    }

    @Test
    void emptyFutureOccurrenceRemainsEditable() {
        assertEquals(true, occurrenceReadback(0, 0).get("editable"));
    }

    @Test
    void unknownSignupRemainsClosed() {
        Map<String, Object> row = occurrenceReadback(null, 0);
        assertEquals(false, row.get("editable"));
        assertEquals("报名数待确认", row.get("lockReason"));
    }
}
