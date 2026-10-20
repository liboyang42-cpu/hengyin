package com.chengyinhub.business.longrun;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real service/materializer with a controlled Shanghai clock and mocked persistence. */
class E01_14Test {
    private static final Long OWNER = 42L, CLUB = 21L, TOPIC = 31L;
    private static final LocalDate ORIGINAL_DAY = LocalDate.of(2027, 1, 15);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final AtomicReference<Date> clock = new AtomicReference<>();
    private final AtomicReference<ClubEventSeries> receipt = new AtomicReference<>();
    private ClubEventOpsMapper mapper;
    private IClubSessionService sessions;
    private ClubLeadMapper leads;
    private ClubEventOpsServiceImpl service;

    @BeforeEach
    void setUp() {
        clock.set(Date.from(ORIGINAL_DAY.atTime(8, 0).atZone(SHANGHAI).toInstant()));
        receipt.set(null);
        mapper = mock(ClubEventOpsMapper.class);
        sessions = mock(IClubSessionService.class);
        leads = mock(ClubLeadMapper.class);
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(eq(OWNER), eq(CLUB), isNull(), anyString())).thenReturn(
                new ClubAccessContext(OWNER, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(mapper.selectSeriesByRequest(CLUB, OWNER, "original")).thenAnswer(invocation -> receipt.get());
        when(mapper.insertSeries(any())).thenAnswer(invocation -> {
            ClubEventSeries stored = invocation.getArgument(0);
            stored.setId(61L);
            receipt.set(stored);
            return 1;
        });
        when(sessions.open(any(), eq(OWNER), anyString())).thenReturn(71L, 72L);
        when(mapper.insertOccurrence(any())).thenReturn(1);
        when(leads.insertClubLeadState(any())).thenReturn(1);
        service = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(), sessions,
                mapper, mock(ClubMemberMapper.class), leads, mock(IClubRoleAssignmentService.class)) {
            @Override protected Date now() { return clock.get(); }
        };
    }

    @Test
    void onceOriginalRequestReplaysAfterShanghaiDayRolloverWithoutNewWrites() {
        ClubEventSeries original = service.createSeries(OWNER, "e01", request("ONCE"));
        rolloverAndClearWrites();

        assertSame(original, service.createSeries(OWNER, "e01", request("ONCE")));
        verifyNoReplayWrites();
    }

    @Test
    void customOriginalDatesReplayDespiteReorderDuplicatesAndLaterSeriesEdit() {
        ClubEventSeries original = service.createSeries(OWNER, "e01", request("CUSTOM_DATES"));
        // updateFuture may change current recurrence/version, but the original request fingerprint stays immutable.
        original.setRecurrenceType("WEEKLY");
        original.setVersion(3);
        rolloverAndClearWrites();
        ClubEventSeriesRequest retry = request("CUSTOM_DATES");
        retry.setCustomDates(Arrays.asList(ORIGINAL_DAY.plusDays(7).toString(),
                ORIGINAL_DAY.toString(), ORIGINAL_DAY.toString()));

        assertSame(original, service.createSeries(OWNER, "e01", retry));
        assertEquals(Integer.valueOf(3), original.getVersion());
        verifyNoReplayWrites();
    }

    @Test
    void changedFixedDatePayloadStillConflictsWithOriginalCompleteFingerprint() {
        service.createSeries(OWNER, "e01", request("CUSTOM_DATES"));
        rolloverAndClearWrites();
        ClubEventSeriesRequest changedTime = request("CUSTOM_DATES");
        changedTime.setStartTime("16:45");
        ClubEventSeriesRequest changedDates = request("CUSTOM_DATES");
        changedDates.setCustomDates(Collections.singletonList(ORIGINAL_DAY.toString()));
        ClubEventSeriesRequest changedWaitlist = request("CUSTOM_DATES");
        changedWaitlist.setWaitlistEnabled(true);
        for (ClubEventSeriesRequest changed : Arrays.asList(changedTime, changedDates, changedWaitlist)) {
            ServiceException conflict = assertThrows(ServiceException.class,
                    () -> service.createSeries(OWNER, "e01", changed));
            assertEquals(Integer.valueOf(409), conflict.getCode());
        }
        verifyNoReplayWrites();
    }

    @Test
    void unknownRequestStillCannotCreatePastDatesOrExceedFutureWindow() {
        rolloverAndClearWrites();
        ClubEventSeriesRequest past = request("ONCE");
        past.setRequestId("unknown");
        ClubEventSeriesRequest tooFar = request("CUSTOM_DATES");
        tooFar.setRequestId("unknown");
        tooFar.setCustomDates(Collections.singletonList(ORIGINAL_DAY.plusDays(100).toString()));
        for (ClubEventSeriesRequest invalid : Arrays.asList(past, tooFar)) {
            ServiceException rejected = assertThrows(ServiceException.class,
                    () -> service.createSeries(OWNER, "e01", invalid));
            assertEquals(Integer.valueOf(400), rejected.getCode());
        }
        verifyNoReplayWrites();
    }

    private void rolloverAndClearWrites() {
        clock.set(Date.from(ORIGINAL_DAY.plusDays(1).atTime(8, 0).atZone(SHANGHAI).toInstant()));
        clearInvocations(mapper, sessions, leads);
    }

    private void verifyNoReplayWrites() {
        verify(mapper, never()).insertSeries(any());
        verify(mapper, never()).insertOccurrence(any());
        verifyNoInteractions(sessions, leads);
    }

    private ClubEventSeriesRequest request(String recurrence) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setRecurrenceType(recurrence);
        request.setStartDate(ORIGINAL_DAY.toString());
        request.setCustomDates(Arrays.asList(ORIGINAL_DAY.toString(), ORIGINAL_DAY.plusDays(7).toString()));
        request.setStartTime("14:30");
        request.setRequestId("original");
        return request;
    }
}
