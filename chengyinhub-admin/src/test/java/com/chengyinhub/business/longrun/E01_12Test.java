package com.chengyinhub.business.longrun;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.business.service.impl.ClubSessionServiceImpl;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Real schedule -> session.open service chain, with mocked persistence/authorization. */
class E01_12Test {
    private static final Long OWNER = 42L, CLUB = 21L, TOPIC = 31L, SERIES = 61L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private ClubEventOpsMapper mapper;
    private ClubLeadMapper leads;
    private ICmsActivityService activities;
    private IOmsTicketService tickets;
    private ClubEventOpsServiceImpl service;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEventOpsMapper.class);
        leads = mock(ClubLeadMapper.class);
        activities = mock(ICmsActivityService.class);
        tickets = mock(IOmsTicketService.class);
        IClubService clubs = mock(IClubService.class);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(CLUB);
        topic.setDelFlag(0);
        topic.setStatus(1);
        topic.setUserStatus(1);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        IContentSafetyGate gate = mock(IContentSafetyGate.class);
        when(gate.decide(any())).thenReturn(SafetyDecision.pass());
        ActivityReviewPolicy policy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService", mock(ISysConfigService.class));
        ClubSessionServiceImpl realOpen = new ClubSessionServiceImpl(clubs, topics, activities,
                tickets, mock(CoopOrderMapper.class), mock(SettlementDebtLedger.class),
                gate, policy, mock(IAuditTaskService.class), mock(IUmsMemberService.class));
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(eq(OWNER), eq(CLUB), isNull(), anyString())).thenReturn(
                new ClubAccessContext(OWNER, CLUB, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        service = new ClubEventOpsServiceImpl(access, new ClubEventScheduleMaterializer(), realOpen,
                mapper, mock(ClubMemberMapper.class), leads, mock(IClubRoleAssignmentService.class));
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
        when(mapper.insertSeries(any())).thenAnswer(invocation -> {
            ClubEventSeries series = invocation.getArgument(0);
            series.setId(SERIES);
            return 1;
        });
        when(activities.insertCmsActivity(any())).thenAnswer(invocation -> {
            CmsActivity activity = invocation.getArgument(0);
            activity.setId(71L);
            return 1;
        });
        when(mapper.insertOccurrence(any())).thenReturn(1);
        when(leads.insertClubLeadState(any())).thenReturn(1);
        ClubEventSeries stored = new ClubEventSeries();
        stored.setId(SERIES);
        stored.setClubId(CLUB);
        stored.setTopicId(TOPIC);
        stored.setStatus(ClubEventSeries.STATUS_ACTIVE);
        stored.setVersion(0);
        stored.setDefaultLeadMemberId(OWNER);
        stored.setWaitlistEnabled(false);
        stored.setOfferMinutes(1440);
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(stored);
        day = LocalDate.now(SHANGHAI).plusDays(2);
    }

    @Test
    void createRejectsSmartMidnightAndMalformedTimesBeforeAnyInsert() {
        for (String time : Arrays.asList("24:00", "24:01", "25:00", "12:60", "1:00", "00:00:00", "", null)) {
            ServiceException rejected = assertThrows(ServiceException.class,
                    () -> service.createSeries(OWNER, "e01", request(time)), String.valueOf(time));
            assertEquals(Integer.valueOf(400), rejected.getCode());
            assertTrue(rejected.getMessage().contains("集合时刻"));
        }
        verify(mapper, never()).insertSeries(any());
        verify(mapper, never()).insertOccurrence(any());
        verifyNoInteractions(activities, tickets);
    }

    @Test
    void updateRejectsSmartMidnightBeforeRegenerationOrScheduleCas() {
        for (String time : Arrays.asList("24:00", "24:01", "25:00", "12:60", "1:00", "00:00:00", "", null)) {
            ServiceException rejected = assertThrows(ServiceException.class,
                    () -> service.updateFuture(OWNER, "e01", request(time)), String.valueOf(time));
            assertEquals(Integer.valueOf(400), rejected.getCode());
        }
        verify(mapper, never()).selectFutureOccurrencesForUpdate(anyLong(), any());
        verify(mapper, never()).insertOccurrence(any());
        verify(mapper, never()).updateSeriesScheduleCas(any(), any(), any(), any(), any());
        verifyNoInteractions(activities, tickets);
    }

    @Test
    void legalMidnightAndNonDefaultTimesReachRealOpenWithoutShanghaiDateShift() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : Arrays.asList("UTC", "America/Los_Angeles", "Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (String time : Arrays.asList("00:00", "00:30", "16:45", "23:59")) {
                    clearInvocations(activities, tickets, mapper);
                    service.createSeries(OWNER, "e01", request(time));
                    ArgumentCaptor<CmsActivity> activity = ArgumentCaptor.forClass(CmsActivity.class);
                    verify(activities).insertCmsActivity(activity.capture());
                    ArgumentCaptor<ClubEventOccurrence> occurrence = ArgumentCaptor.forClass(ClubEventOccurrence.class);
                    verify(mapper).insertOccurrence(occurrence.capture());
                    Date expected = Date.from(day.atTime(LocalTime.parse(time)).atZone(SHANGHAI).toInstant());
                    assertEquals(expected, activity.getValue().getStartDate(), zone + " " + time);
                    assertEquals(expected, occurrence.getValue().getOccurrenceAt());
                    verify(tickets).copyTopicTicketsToActivity(TOPIC, 71L, OWNER);
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private ClubEventSeriesRequest request(String time) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setClubId(CLUB);
        request.setTopicId(TOPIC);
        request.setSeriesId(SERIES);
        request.setExpectedVersion(0);
        request.setRecurrenceType("CUSTOM_DATES");
        request.setCustomDates(Collections.singletonList(day.toString()));
        request.setStartTime(time);
        request.setRequestId("e01-12");
        return request;
    }
}
