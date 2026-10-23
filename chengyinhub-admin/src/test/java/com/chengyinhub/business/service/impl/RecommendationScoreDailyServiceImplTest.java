package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberInterestProfile;
import com.chengyinhub.business.domain.RecommendationScoreDaily;
import com.chengyinhub.business.domain.vo.RecommendationCandidateVO;
import com.chengyinhub.business.mapper.RecommendationScoreDailyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationScoreDailyServiceImplTest {
    private static final LocalDate STAT = LocalDate.of(2026, 10, 22);
    private static final Date STAT_DATE = Date.from(STAT.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());

    private RecommendationScoreDailyMapper mapper;
    private PlatformTransactionManager txManager;
    private RecommendationScoreDailyServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(RecommendationScoreDailyMapper.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new RecommendationScoreDailyServiceImpl();
        ReflectionTestUtils.setField(service, "recommendationScoreDailyMapper", mapper);
        ReflectionTestUtils.setField(service, "transactionManager", txManager);
    }

    @Test
    void rebuildsEachMemberAndTypeByDeleteThenInsertInOneTransaction() {
        when(mapper.selectTopicCandidatePool(any(Date.class), eq(200))).thenReturn(Arrays.asList(
                candidate("topic", 11L, 900L, "310100", 10L),
                candidate("topic", 12L, 901L, null, 0L)));
        when(mapper.selectActivityCandidatePool(any(Date.class), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.selectActiveInterestProfiles(any(Date.class), eq(0L), eq(500)))
                .thenReturn(Collections.singletonList(profile(1001L, "310100", "{\"topic\":3,\"activity\":1}")));

        Map<String, Integer> result = service.computeDaily(STAT);

        assertEquals(1, result.get("members"));
        assertEquals(2, result.get("rows"));
        assertEquals(0, result.get("failures"));
        InOrder order = inOrder(mapper);
        order.verify(mapper).deleteMemberDailyScores(STAT_DATE, 1001L, "topic");
        order.verify(mapper).batchInsertRecommendationScoreDaily(anyList());
        // 活动池为空也要先删：同日重跑时把旧结果清掉，而不是残留
        verify(mapper).deleteMemberDailyScores(STAT_DATE, 1001L, "activity");
        verify(mapper, times(1)).batchInsertRecommendationScoreDaily(anyList());
        verify(txManager, times(2)).commit(any());
        // 只保留最近 7 天(含当天)
        Date cutoff = Date.from(STAT.minusDays(6).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        verify(mapper).deleteScoresBefore(cutoff);
    }

    @Test
    void scoreFollowsDocumentedFormulaAndSkipsOwnContent() {
        MemberInterestProfile me = profile(1001L, "310100", "{\"topic\":3,\"activity\":1}");
        List<RecommendationScoreDaily> rows = service.score(STAT_DATE, me, "topic", Arrays.asList(
                candidate("topic", 11L, 900L, "310100", 10L),
                candidate("topic", 12L, 1001L, "310100", 10L),   // 自己发布的
                candidate("topic", 13L, 902L, "440100", 5L)), new Date());

        assertEquals(2, rows.size());
        RecommendationScoreDaily first = rows.get(0);
        assertEquals(Long.valueOf(11L), first.getCandidateId());
        // 0.5×1 + 0.3×0.75 + 0.2 = 0.925
        assertEquals(new BigDecimal("0.9250"), first.getScore());
        assertEquals("popular,interest_topic,same_city", first.getReasonCodes());
        assertEquals(Integer.valueOf(1), first.getRankPosition());
        RecommendationScoreDaily second = rows.get(1);
        // 0.5×0.5 + 0.3×0.75 = 0.475
        assertEquals(new BigDecimal("0.4750"), second.getScore());
        assertEquals(Integer.valueOf(2), second.getRankPosition());
        assertEquals(STAT_DATE, second.getStatDate());
    }

    @Test
    void topNIsCappedAndRerunIsDeterministic() {
        List<RecommendationCandidateVO> pool = new java.util.ArrayList<>();
        for (long i = 1; i <= 30; i++) pool.add(candidate("activity", i, 9L, null, 0L));
        MemberInterestProfile me = profile(1001L, null, null);

        List<RecommendationScoreDaily> a = service.score(STAT_DATE, me, "activity", pool, new Date());
        List<RecommendationScoreDaily> b = service.score(STAT_DATE, me, "activity", pool, new Date());

        assertEquals(20, a.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).getCandidateId(), b.get(i).getCandidateId());
            assertEquals("fresh", a.get(i).getReasonCodes());
        }
    }

    @Test
    void oneMemberFailureDoesNotStopOthersButIsReported() {
        when(mapper.selectTopicCandidatePool(any(Date.class), eq(200)))
                .thenReturn(Collections.singletonList(candidate("topic", 11L, 900L, null, 1L)));
        when(mapper.selectActivityCandidatePool(any(Date.class), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.selectActiveInterestProfiles(any(Date.class), eq(0L), eq(500)))
                .thenReturn(Arrays.asList(profile(1L, null, "{}"), profile(2L, null, "{}")));
        doThrow(new IllegalStateException("db")).when(mapper).deleteMemberDailyScores(STAT_DATE, 1L, "topic");

        Map<String, Integer> result = service.computeDaily(STAT);

        assertEquals(1, result.get("failures"));
        verify(mapper).deleteMemberDailyScores(STAT_DATE, 2L, "topic");
        verify(txManager).rollback(any());
    }

    @Test
    void pagesProfilesByKeysetUntilShortPage() {
        when(mapper.selectTopicCandidatePool(any(Date.class), eq(200))).thenReturn(Collections.emptyList());
        when(mapper.selectActivityCandidatePool(any(Date.class), eq(200))).thenReturn(Collections.emptyList());
        List<MemberInterestProfile> full = new java.util.ArrayList<>();
        for (long i = 1; i <= 500; i++) full.add(profile(i, null, null));
        when(mapper.selectActiveInterestProfiles(any(Date.class), eq(0L), eq(500))).thenReturn(full);
        when(mapper.selectActiveInterestProfiles(any(Date.class), eq(500L), eq(500)))
                .thenReturn(Collections.singletonList(profile(501L, null, null)));

        Map<String, Integer> result = service.computeDaily(STAT);

        assertEquals(501, result.get("members"));
        ArgumentCaptor<Long> after = ArgumentCaptor.forClass(Long.class);
        verify(mapper, times(2)).selectActiveInterestProfiles(any(Date.class), after.capture(), eq(500));
        assertEquals(Arrays.asList(0L, 500L), after.getAllValues());
        verify(mapper, never()).batchInsertRecommendationScoreDaily(anyList());
    }

    @Test
    void interestShareToleratesMalformedOrEmptyProfiles() {
        assertEquals(BigDecimal.ZERO, RecommendationScoreDailyServiceImpl.interestShare("not-json", "topic"));
        assertEquals(BigDecimal.ZERO, RecommendationScoreDailyServiceImpl.interestShare("{\"topic\":-3}", "topic"));
        assertEquals(BigDecimal.ZERO, RecommendationScoreDailyServiceImpl.interestShare(null, "topic"));
        assertEquals(new BigDecimal("0.5000"),
                RecommendationScoreDailyServiceImpl.interestShare("{\"topic\":2,\"activity\":2}", "topic"));
    }

    private static RecommendationCandidateVO candidate(String type, Long id, Long owner, String city, Long popularity) {
        RecommendationCandidateVO c = new RecommendationCandidateVO();
        c.setCandidateType(type);
        c.setCandidateId(id);
        c.setOwnerMemberId(owner);
        c.setCityCode(city);
        c.setPopularity(popularity);
        return c;
    }

    private static MemberInterestProfile profile(Long memberId, String city, String weights) {
        MemberInterestProfile p = new MemberInterestProfile();
        p.setMemberId(memberId);
        p.setCityCode(city);
        p.setCategoryWeightsJson(weights);
        return p;
    }
}
