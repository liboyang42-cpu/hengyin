package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * O5b-7:系列 CLOSED 终态。关闭只翻系列状态(owner/范围/版本 CAS),不再往关闭的系列排新场次,
 * 已排场次、已付报名、票与资金一律不动;开关默认关。
 */
class ClubEventSeriesCloseTest {
    private static final Long ACTOR = 41L;
    private static final Long OWNER = 42L;
    private static final Long CLUB = 21L;
    private static final Long TOPIC = 31L;
    private static final Long LEAD = 51L;
    private static final Long SERIES = 61L;

    private IClubAccessService accessService;
    private IClubSessionService sessionService;
    private ClubEventOpsMapper mapper;
    private ITeamGroupService teamGroupService;
    private ClubEventOpsServiceImpl service;

    @BeforeEach
    void setUp() {
        accessService = mock(IClubAccessService.class);
        sessionService = mock(IClubSessionService.class);
        mapper = mock(ClubEventOpsMapper.class);
        teamGroupService = mock(ITeamGroupService.class);
        service = new ClubEventOpsServiceImpl(accessService, new ClubEventScheduleMaterializer(),
                sessionService, mapper, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class),
                mock(IClubRoleAssignmentService.class), teamGroupService);
        ReflectionTestUtils.setField(service, "seriesCloseEnabled", true);
        when(accessService.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_OWNER));
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenReturn(OWNER);
    }

    @Test
    void switchOffRefusesBeforeAnyRead() {
        ReflectionTestUtils.setField(service, "seriesCloseEnabled", false);

        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.closeSeries(ACTOR, "operator", closeRequest(0)));

        assertEquals(Integer.valueOf(403), denied.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void ownerClosesActiveSeriesWithVersionCasAndTouchesNothingElse() {
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(series(ClubEventSeries.STATUS_ACTIVE, 3));
        when(mapper.closeSeriesCas(eq(SERIES), eq(CLUB), eq(3), eq("operator"), any())).thenReturn(1);

        ClubEventSeries closed = service.closeSeries(ACTOR, "operator", closeRequest(3));

        assertEquals(ClubEventSeries.STATUS_CLOSED, closed.getStatus());
        assertEquals(Integer.valueOf(4), closed.getVersion());
        // 已排场次/报名/票/资金不动:不取消场次、不调退款核心、不开新场
        verify(mapper, never()).cancelOccurrenceCas(any(), any());
        verify(mapper, never()).cancelFutureActivityCas(any(), any(), any());
        verify(mapper, never()).updateSeriesScheduleCas(any(), any(), any(), any(), any());
        verify(sessionService, never()).open(any(), any(), any());
        verifyNoInteractions(teamGroupService);
    }

    @Test
    void closingAnAlreadyClosedSeriesIsIdempotent() {
        ClubEventSeries stored = series(ClubEventSeries.STATUS_CLOSED, 4);
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(stored);

        assertSame(stored, service.closeSeries(ACTOR, "operator", closeRequest(3)));
        verify(mapper, never()).closeSeriesCas(any(), any(), any(), any(), any());
    }

    @Test
    void staleVersionOrLostCasIsAConflict() {
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(series(ClubEventSeries.STATUS_ACTIVE, 5));
        ServiceException stale = assertThrows(ServiceException.class,
                () -> service.closeSeries(ACTOR, "operator", closeRequest(3)));
        assertEquals(Integer.valueOf(409), stale.getCode());

        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(series(ClubEventSeries.STATUS_ACTIVE, 3));
        when(mapper.closeSeriesCas(anyLong(), anyLong(), anyInt(), anyString(), any())).thenReturn(0);
        ServiceException raced = assertThrows(ServiceException.class,
                () -> service.closeSeries(ACTOR, "operator", closeRequest(3)));
        assertEquals(Integer.valueOf(409), raced.getCode());
    }

    @Test
    void plainMemberAndOtherClubsSeriesAreRefused() {
        when(accessService.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_MEMBER));
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.closeSeries(ACTOR, "operator", closeRequest(0)));
        assertEquals(Integer.valueOf(403), forbidden.getCode());

        when(accessService.require(ACTOR, CLUB, null, ClubPermission.ACTIVITY_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_OWNER));
        ClubEventSeries foreign = series(ClubEventSeries.STATUS_ACTIVE, 0);
        foreign.setClubId(99L);
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(foreign);
        ServiceException scope = assertThrows(ServiceException.class,
                () -> service.closeSeries(ACTOR, "operator", closeRequest(0)));
        assertEquals(Integer.valueOf(409), scope.getCode());
        verify(mapper, never()).closeSeriesCas(any(), any(), any(), any(), any());
    }

    /** 关闭后不能再往该系列排新场次:update-future 在锁后拒绝,一场都不开。 */
    @Test
    void closedSeriesNeverGetsNewSessionsScheduled() {
        when(mapper.selectSeriesByIdForUpdate(SERIES)).thenReturn(series(ClubEventSeries.STATUS_CLOSED, 0));
        ClubEventSeriesRequest future = closeRequest(0);
        future.setTopicId(TOPIC);
        future.setRecurrenceType("CUSTOM_DATES");
        future.setCustomDates(Collections.singletonList(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString()));
        future.setStartTime("14:30");
        future.setDefaultLeadMemberId(LEAD);
        future.setWaitlistEnabled(true);
        future.setOfferMinutes(20);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.updateFuture(ACTOR, "operator", future));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        verify(sessionService, never()).open(any(), any(), any());
        verify(mapper, never()).insertOccurrence(any());
    }

    /** 读/写/候补推进所有入口都以 status='ACTIVE' 过滤;CAS 只认 ACTIVE→CLOSED。 */
    @Test
    void mapperClosesOnlyActiveSeriesAndScheduleCasStillRequiresActive() throws Exception {
        Path xml = Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper", "business",
                "ClubEventOpsMapper.xml");
        String text = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertTrue(text.matches(".*<update id=\"closeSeriesCas\">[^<]*set status = 'CLOSED'[^<]*"
                + "status = 'ACTIVE' and version = #\\{expectedVersion\\}[^<]*</update>.*"));
        assertTrue(text.matches(".*<update id=\"updateSeriesScheduleCas\">[^<]*status = 'ACTIVE'[^<]*</update>.*"));
    }

    private ClubAccessContext access(String roleCode) {
        return new ClubAccessContext(ACTOR, CLUB, OWNER, null, Collections.singleton(roleCode));
    }

    private ClubEventSeries series(String status, int version) {
        ClubEventSeries series = new ClubEventSeries();
        series.setId(SERIES);
        series.setClubId(CLUB);
        series.setTopicId(TOPIC);
        series.setRecurrenceType("WEEKLY");
        series.setDefaultLeadMemberId(LEAD);
        series.setWaitlistEnabled(true);
        series.setOfferMinutes(20);
        series.setStatus(status);
        series.setVersion(version);
        return series;
    }

    private ClubEventSeriesRequest closeRequest(int expectedVersion) {
        ClubEventSeriesRequest request = new ClubEventSeriesRequest();
        request.setSeriesId(SERIES);
        request.setClubId(CLUB);
        request.setExpectedVersion(expectedVersion);
        return request;
    }
}
