package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RoamRevisitFact;
import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;
import com.chengyinhub.business.mapper.RoamRevisitReportMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Isolated("modifies and restores the JVM default TimeZone")
class RoamRevisitReportServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-16T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void productionConstructorPinsShanghaiDayBoundariesAndMaturityAcrossJvmDefaultZones() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String defaultZone : Arrays.asList(
                    "UTC", "GMT+08:00", "America/Los_Angeles", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
                RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
                when(mapper.selectRoamDataCutoffAt()).thenReturn(at("2026-08-15T16:00:00Z"));
                when(mapper.selectRoamSessionFacts(any(Date.class), any(Date.class))).thenReturn(
                        Arrays.asList(
                                roamFact(1, 100, "2026-08-01T01:00:00Z"),
                                roamFact(1, 101, "2026-08-01T17:00:00Z")));

                RoamRevisitReportVO report = new RoamRevisitReportServiceImpl(mapper).generate(
                        LocalDate.of(2026, 8, 1), RoamRevisitReportVO.Metric.ROAM_REVISIT, 7);

                ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
                ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
                verify(mapper).selectRoamSessionFacts(from.capture(), to.capture());
                assertEquals(at("2026-07-31T16:00:00Z"), from.getValue(), defaultZone + " 起始日界");
                assertEquals(at("2026-08-08T16:00:00Z"), to.getValue(), defaultZone + " 结束日界");
                assertEquals(1, report.getEligibleMembers(), defaultZone + " 成熟度");
                assertEquals(1, report.getRevisitedMembers(), defaultZone + " observationEnd 日界");
                assertEquals(0, report.getNotMatureMembers(), defaultZone + " 成熟度");
            }
        } finally {
            TimeZone.setDefault(original);
            assertEquals(original.getID(), TimeZone.getDefault().getID(), "JVM default time zone 必须恢复");
            assertTrue(original.hasSameRules(TimeZone.getDefault()), "JVM default time zone 规则必须恢复");
        }
    }

    @Test
    void shopRevisit_deduplicatesMembersAndRequiresAnotherSessionWithSameSourceScopedShop() {
        RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
        when(mapper.selectShopDataCutoffAt()).thenReturn(at("2026-08-16T00:00:00Z"));
        when(mapper.selectShopVisitFacts(any(Date.class), any(Date.class))).thenReturn(Arrays.asList(
                fact(1, 100, 1, 10, "2026-08-08T09:00:00Z"),
                fact(1, 100, 1, 10, "2026-08-08T09:01:00Z"),
                fact(1, 101, 1, 10, "2026-08-08T10:00:00Z"),
                fact(1, 101, 1, 10, "2026-08-10T10:00:00Z"),
                fact(1, 102, 1, 10, "2026-08-11T10:00:00Z"),

                fact(2, 200, 2, 10, "2026-08-08T09:00:00Z"),
                fact(2, 201, 1, 10, "2026-08-12T09:00:00Z"),

                fact(3, 300, 1, 30, "2026-08-08T09:00:00Z"),
                fact(3, 301, 1, 31, "2026-08-12T09:00:00Z"),

                fact(4, 400, 1, 40, "2026-08-08T09:00:00Z"),
                fact(4, 400, 1, 40, "2026-08-12T09:00:00Z")
        ));

        RoamRevisitReportServiceImpl service = new RoamRevisitReportServiceImpl(mapper, CLOCK);
        RoamRevisitReportVO report = service.generate(
                LocalDate.of(2026, 8, 8), RoamRevisitReportVO.Metric.SHOP_REVISIT, 7);

        assertEquals(4, report.getEligibleMembers());
        assertEquals(1, report.getRevisitedMembers());
        assertEquals(0, report.getNotMatureMembers());
        assertEquals("cohort=观察日 visited_at 有 roam_shop_visit 的去重成员；"
                + "复访=后续另一 session 同 source-scoped 商家", report.getScope());
        assertEquals("source_type+source_id (source-scoped; no canonical bridge)", report.getIdentityBasis());
    }

    @Test
    void metricsKeepSeparateDenominatorsAndOnlyStartedSessionFactsIncreaseRoamRevisit() {
        RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
        when(mapper.selectShopDataCutoffAt()).thenReturn(at("2026-08-16T00:00:00Z"));
        when(mapper.selectRoamDataCutoffAt()).thenReturn(at("2026-08-16T00:00:00Z"));
        when(mapper.selectShopVisitFacts(any(Date.class), any(Date.class))).thenReturn(Arrays.asList(
                fact(1, 100, 1, 10, "2026-08-08T09:00:00Z"),
                fact(2, 200, 1, 20, "2026-08-08T09:00:00Z")
        ));
        when(mapper.selectRoamSessionFacts(any(Date.class), any(Date.class))).thenReturn(Arrays.asList(
                roamFact(1, 100, "2026-08-08T08:00:00Z"),
                roamFact(1, 101, "2026-08-10T08:00:00Z"),
                roamFact(3, 300, "2026-08-08T08:00:00Z")
        ));

        RoamRevisitReportServiceImpl service = new RoamRevisitReportServiceImpl(mapper, CLOCK);
        RoamRevisitReportVO shop = service.generate(
                LocalDate.of(2026, 8, 8), RoamRevisitReportVO.Metric.SHOP_REVISIT, 7);
        RoamRevisitReportVO roam = service.generate(
                LocalDate.of(2026, 8, 8), RoamRevisitReportVO.Metric.ROAM_REVISIT, 7);

        assertEquals(2, shop.getEligibleMembers());
        assertEquals(0, shop.getRevisitedMembers(), "只有新 roam_session 不能借表提升商家复访");
        assertEquals(2, roam.getEligibleMembers());
        assertEquals(1, roam.getRevisitedMembers());
        assertEquals("cohort=started_at 落在观察日的任意 roam_session 去重成员；"
                + "复访=后续 started_at 落窗的另一 roam_session；不区分是否结算", roam.getScope());
        assertEquals("roam_session.id (started_at 事实，不区分是否结算)", roam.getIdentityBasis());
    }

    @Test
    void maturityBoundaryKeepsD6D29D89OutAndAdmitsD7D30D90() {
        assertMaturity(7, LocalDate.of(2026, 8, 9), 0, 1);
        assertMaturity(7, LocalDate.of(2026, 8, 8), 1, 0);
        assertMaturity(30, LocalDate.of(2026, 7, 17), 0, 1);
        assertMaturity(30, LocalDate.of(2026, 7, 16), 1, 0);
        assertMaturity(90, LocalDate.of(2026, 5, 18), 0, 1);
        assertMaturity(90, LocalDate.of(2026, 5, 17), 1, 0);
    }

    @Test
    void maturityUsesClockAndDoesNotFlipWhenLatestFactChanges() {
        LocalDate cohortDate = LocalDate.of(2026, 8, 8);
        RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
        when(mapper.selectRoamSessionFacts(any(Date.class), any(Date.class))).thenReturn(
                Collections.singletonList(roamFact(1, 100, "2026-08-08T01:00:00Z")));
        Clock shanghaiClock = Clock.fixed(
                Instant.parse("2026-08-16T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RoamRevisitReportServiceImpl service = new RoamRevisitReportServiceImpl(mapper, shanghaiClock);

        when(mapper.selectRoamDataCutoffAt()).thenReturn(at("2026-08-15T00:00:00Z"));
        RoamRevisitReportVO d7AtEight = service.generate(
                cohortDate, RoamRevisitReportVO.Metric.ROAM_REVISIT, 7);
        assertEquals(1, d7AtEight.getEligibleMembers(), "历史观察窗已由时钟走完");
        assertEquals(0, d7AtEight.getNotMatureMembers());

        when(mapper.selectRoamDataCutoffAt()).thenReturn(at("2026-08-15T16:00:00Z"));
        RoamRevisitReportVO d8AtMidnight = service.generate(
                cohortDate, RoamRevisitReportVO.Metric.ROAM_REVISIT, 7);
        assertEquals(1, d8AtMidnight.getEligibleMembers(), "插入更新事实水位不得翻转同一历史查询");
        assertEquals(0, d8AtMidnight.getNotMatureMembers());
    }

    @Test
    void queryFailureIsPropagatedInsteadOfBecomingAnEmptyOrZeroReport() {
        RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
        RuntimeException failure = new RuntimeException("read failed");
        when(mapper.selectShopDataCutoffAt()).thenThrow(failure);

        RoamRevisitReportServiceImpl service = new RoamRevisitReportServiceImpl(mapper, CLOCK);
        RuntimeException actual = assertThrows(RuntimeException.class, () -> service.generate(
                LocalDate.of(2026, 8, 8), RoamRevisitReportVO.Metric.SHOP_REVISIT, 7));

        assertEquals(failure, actual);
        verify(mapper, never()).selectShopVisitFacts(any(Date.class), any(Date.class));
    }

    @Test
    void onlySevenThirtyAndNinetyDayWindowsAreAccepted() {
        RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
        RoamRevisitReportServiceImpl service = new RoamRevisitReportServiceImpl(mapper, CLOCK);

        assertThrows(IllegalArgumentException.class, () -> service.generate(
                LocalDate.of(2026, 8, 8), RoamRevisitReportVO.Metric.ROAM_REVISIT, 14));
        verify(mapper, never()).selectRoamDataCutoffAt();
    }

    private static void assertMaturity(int windowDays, LocalDate cohortDate,
                                       int eligibleMembers, int notMatureMembers) {
        RoamRevisitReportMapper mapper = mock(RoamRevisitReportMapper.class);
        when(mapper.selectRoamDataCutoffAt()).thenReturn(at("2026-08-16T00:00:00Z"));
        when(mapper.selectRoamSessionFacts(any(Date.class), any(Date.class))).thenReturn(Collections.singletonList(
                roamFact(1, 100, cohortDate.atStartOfDay().plusHours(8).toInstant(ZoneOffset.UTC).toString())
        ));
        RoamRevisitReportVO report = new RoamRevisitReportServiceImpl(mapper, CLOCK).generate(
                cohortDate, RoamRevisitReportVO.Metric.ROAM_REVISIT, windowDays);
        assertEquals(eligibleMembers, report.getEligibleMembers(), "eligible D+" + windowDays + " @ " + cohortDate);
        assertEquals(notMatureMembers, report.getNotMatureMembers(), "not mature D+" + windowDays + " @ " + cohortDate);
        assertEquals(0, report.getRevisitedMembers());
    }

    private static RoamRevisitFact fact(long memberId, long sessionId, int sourceType, long sourceId,
                                        String occurredAt) {
        return new RoamRevisitFact(memberId, sessionId, sourceType, sourceId, at(occurredAt));
    }

    private static RoamRevisitFact roamFact(long memberId, long sessionId, String occurredAt) {
        return new RoamRevisitFact(memberId, sessionId, null, null, at(occurredAt));
    }

    private static Date at(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
