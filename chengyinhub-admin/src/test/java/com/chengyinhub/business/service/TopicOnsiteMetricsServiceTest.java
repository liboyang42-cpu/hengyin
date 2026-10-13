package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.TopicOnsiteTicketFact;
import com.chengyinhub.business.domain.dto.TopicWaitFact;
import com.chengyinhub.business.domain.vo.TopicOnsiteMetricsVO;
import com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper;
import com.chengyinhub.business.service.impl.TopicOnsiteMetricsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicOnsiteMetricsServiceTest {
    private static final long MINUTE = 60_000L;
    private TopicOnsiteMetricsMapper mapper;
    private TopicOnsiteMetricsServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(TopicOnsiteMetricsMapper.class);
        service = new TopicOnsiteMetricsServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
        when(mapper.selectRepeatPayments(anyLong())).thenReturn(Collections.emptyList());
    }

    @Test
    void computesSevenStageFunnelAndExcludesPreStartRefundOnly() {
        Date start = new Date(1_000_000L);
        TopicOnsiteTicketFact before = fact(1L, start, new Date(start.getTime() - MINUTE), 0, 0, 3);
        before.setRefundRequestedAt(new Date(start.getTime() - MINUTE));
        before.setPaymentStatus(4);
        before.setRegistrationStatus(3);
        TopicOnsiteTicketFact after = fact(2L, start, new Date(start.getTime() + MINUTE), 1, 3, 3);
        after.setRefundRequestedAt(new Date(start.getTime() + MINUTE));
        after.setPaymentStatus(4);
        after.setRegistrationStatus(3);
        after.setJoinedWithinSevenDays(true);
        after.setLastRedemptionAt(new Date(start.getTime() + 10 * MINUTE));
        after.setLastSessionEnd(new Date(start.getTime() + 20 * MINUTE));
        TopicOnsiteTicketFact active = fact(3L, start, null, 1, 2, 3);
        TopicOnsiteTicketFact future = fact(4L, Date.from(Instant.parse("2026-08-16T00:00:00Z")), null, 1, 3, 3);
        when(mapper.selectTicketFacts(7L)).thenReturn(Arrays.asList(before, after, active, future));
        when(mapper.selectWaitFacts(7L)).thenReturn(Collections.emptyList());

        TopicOnsiteMetricsVO result = service.get(7L);

        assertEquals(2, result.getNService());
        assertEquals(2, result.getArrived());
        assertEquals(2, result.getS1());
        assertEquals(2, result.getS3());
        assertEquals(1, result.getCompleted());
        assertEquals(1, result.getJoined());
        assertEquals(1, result.getRefunded());
        assertEquals(0.5d, result.getCompletionRate(), 0.0001d, "C 的比率分母必须是 A，不是 N_service");
        assertFalse(result.isWaitGatePassed(), "没有等待样本必须失败，不能伪装为 0 分钟通过");
    }

    @Test
    void waitP90IsPerChapterAndNegativeSamplesReduceCoverage() {
        when(mapper.selectTicketFacts(7L)).thenReturn(Collections.emptyList());
        List<TopicWaitFact> waits = new ArrayList<>();
        for (int i = 1; i <= 5; i++) waits.add(wait(i, 101L, 1_000L, 1_000L + i * MINUTE));
        for (int i = 1; i <= 4; i++) waits.add(wait(10 + i, 102L, 2_000L, 2_000L + i * MINUTE));
        waits.add(wait(20, 102L, 3_000L, 2_000L));
        when(mapper.selectWaitFacts(7L)).thenReturn(waits);

        TopicOnsiteMetricsVO result = service.get(7L);

        assertEquals(0.9d, result.getWaitCoverage(), 0.0001d);
        assertEquals(1, result.getNegativeWaitSamples());
        assertTrue(result.isNegativeWaitWarning());
        assertFalse(result.isWaitGatePassed());
        assertEquals(2, result.getChapterWaits().size());
        assertEquals(5, result.getChapterWaits().get(0).getSampleSize());
    }

    @Test
    void g2UsesArrivalOverServiceCompletionOverArrivalRefundAndMeasuredWait() {
        Date start = new Date(1_000_000L);
        List<TopicOnsiteTicketFact> facts = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            int arrived = id <= 4 ? 1 : 0;
            int redeemed = id <= 3 ? 3 : 0;
            facts.add(fact(id, start, null, arrived, redeemed, 3));
        }
        when(mapper.selectTicketFacts(7L)).thenReturn(facts);
        List<TopicWaitFact> waits = new ArrayList<>();
        for (int i = 1; i <= 5; i++) waits.add(wait(i, 101L, 1_000L, 1_000L + i * MINUTE));
        when(mapper.selectWaitFacts(7L)).thenReturn(waits);

        TopicOnsiteMetricsVO result = service.get(7L);

        assertEquals(0.8d, result.getArrivalRate(), 0.0001d);
        assertEquals(0.75d, result.getCompletionRate(), 0.0001d);
        assertEquals(0d, result.getRefundRate(), 0.0001d);
        assertTrue(result.isWaitGatePassed());
        assertTrue(result.isG2Passed(), result.getG2Reasons().toString());
    }

    @Test
    void g2RejectsEffectiveServiceComplaintUserRateAboveFivePercent() {
        Date start = new Date(1_000_000L);
        List<TopicOnsiteTicketFact> facts = new ArrayList<>();
        for (long id = 1; id <= 5; id++) facts.add(fact(id, start, null, 1, 3, 3));
        when(mapper.selectTicketFacts(7L)).thenReturn(facts);
        List<TopicWaitFact> waits = new ArrayList<>();
        for (int i = 1; i <= 5; i++) waits.add(wait(i, 101L, 1_000L, 1_000L + i * MINUTE));
        when(mapper.selectWaitFacts(7L)).thenReturn(waits);
        when(mapper.countEffectiveComplaintUsers(7L)).thenReturn(2);

        TopicOnsiteMetricsVO result = service.get(7L);

        assertEquals(0.4d, result.getComplaintUserRate(), 0.0001d);
        assertFalse(result.isG2Passed());
        assertTrue(result.getG2Reasons().contains("有效服务投诉用户率>5%"));
    }

    @Test
    void complaintRateUsesArrivedAndZeroArrivalsFailClosed() {
        Date start = new Date(1_000_000L);
        when(mapper.selectTicketFacts(7L)).thenReturn(Arrays.asList(
                fact(1L, start, null, 0, 0, 3),
                fact(2L, start, null, 0, 0, 3)));
        when(mapper.selectWaitFacts(7L)).thenReturn(Collections.emptyList());
        when(mapper.countEffectiveComplaintUsers(7L)).thenReturn(1);

        TopicOnsiteMetricsVO result = service.get(7L);

        assertEquals(0d, result.getComplaintUserRate(), 0.0001d);
        assertFalse(result.isG2Passed());
        assertTrue(result.getG2Reasons().contains("A=0"));
    }

    private TopicOnsiteTicketFact fact(Long id, Date start, Date refund, int arrived, int redeemed, int required) {
        TopicOnsiteTicketFact f = new TopicOnsiteTicketFact();
        f.setRegistrationId(id);
        f.setMemberId(id + 100);
        f.setTicketStart(start);
        f.setRefundTime(refund);
        f.setPaymentStatus(2);
        f.setRegistrationStatus(2);
        f.setArrived(arrived == 1);
        f.setRedeemedCount(redeemed);
        f.setRequiredCount(required);
        return f;
    }

    private TopicWaitFact wait(long registrationId, long chapterId, long joined, long started) {
        TopicWaitFact f = new TopicWaitFact();
        f.setRegistrationId(registrationId);
        f.setChapterId(chapterId);
        f.setQueueJoinedAt(new Date(joined));
        f.setServiceStartedAt(new Date(started));
        return f;
    }
}
