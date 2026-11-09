package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.OutcomeFactRow;
import com.chengyinhub.business.domain.dto.RegistrationCohortFact;
import com.chengyinhub.business.domain.vo.PostGameOutcomeReportVO;
import com.chengyinhub.business.mapper.PostGameOutcomeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 完局维护与有效复报口径的可复算性测试。
 *
 * <p>每条用例都对应一个业务情形：同人两订单、同单多席、全退、部分退、免费、取消、
 * 30 日边界、迟到与未成熟、读失败不假零、客户端观测不进分母。
 * 断言只看报表输出，不探 SQL —— SQL 口径由集成阶段在真库上承重。
 */
class PostGameOutcomeReportServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate COHORT = LocalDate.parse("2026-10-01");

    private PostGameOutcomeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(PostGameOutcomeMapper.class);
        when(mapper.selectCompletionFacts(any(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectRewardFacts(any(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectPrivatePhotoFacts(any(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectPublicShareFacts(any(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectRecommendationClickFacts(any(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectClientObservedOutcomeFacts(any(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectSourceWatermarks()).thenReturn(new ArrayList<>());
        when(mapper.selectPaidRegistrationHistory(anyCollection(), any())).thenReturn(new ArrayList<>());
        when(mapper.selectCohortMemberIds(any(), any())).thenReturn(new ArrayList<>());
    }

    /** 观察窗已走完（cohort+31 天之后的时钟）。 */
    private PostGameOutcomeReportServiceImpl matureService() {
        return new PostGameOutcomeReportServiceImpl(mapper, null,
                Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZONE));
    }

    /** 观察窗未走完（cohort 当天之后的时钟）。 */
    private PostGameOutcomeReportServiceImpl immatureService() {
        return new PostGameOutcomeReportServiceImpl(mapper, null,
                Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZONE));
    }

    @Test
    void sameMemberTwoSeparateOrdersCountsOnceInDenominatorAndOnceInNumerator() {
        // 同一会员：10-01 首单，10-20 另一笔（不同 parent_order_no）⇒ 分母 1、分子 1。
        stubHistory(List.of(
                paid(1L, 11L, "ORD-A", "100.00", at("2026-10-01T10:00:00")),
                paid(1L, 12L, "ORD-B", "120.00", at("2026-10-20T10:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(1, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers());
        assertEquals(new BigDecimal("1.0000"), report.getCohort().retentionRate());
    }

    @Test
    void multipleSeatsOnSameOrderAreNotASecondRegistration() {
        // 同一 parent_order_no 两行（2 席）+ 窗内又是该单 ⇒ 分母 1，分子 0，且记下多席合并。
        stubHistory(List.of(
                paidSeat(2L, 21L, "ORD-SEAT", "200.00", at("2026-10-01T10:00:00"), 2),
                paidSeat(2L, 22L, "ORD-SEAT", "200.00", at("2026-10-01T10:00:05"), 2),
                paid(2L, 23L, "ORD-SEAT", "100.00", at("2026-10-25T10:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(1, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(0), report.getCohort().retainedMembers());
        assertEquals(1, report.getCohort().multiSeatCollapsed());
        assertEquals(1, report.getCohort().duplicateOrdersCollapsed());
    }

    @Test
    void fullyRefundedFirstOrderIsExcludedFromDenominator() {
        // payment_status=4（已退款）⇒ 不进分母。
        RegistrationCohortFact refunded = paid(3L, 31L, "ORD-R", "88.00", at("2026-10-01T09:00:00"));
        refunded.setPaymentStatus(4);
        stubHistory(List.of(refunded));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        assertEquals(1, report.getCohort().fullRefundExcluded());
    }

    @Test
    void settledFullRefundExcludesMemberEvenBeforePaymentStatusFlips() {
        // 退款已到账(status=1,payout=1/4)但 payment_status 仍是 2：两条路并集必须排除。
        RegistrationCohortFact settled = paid(4L, 41L, "ORD-S", "55.00", at("2026-10-01T09:00:00"));
        settled.setSettledRefundAmount(new BigDecimal("55.00"));
        settled.setHasSettledRefund(true);
        stubHistory(List.of(settled));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        assertEquals(1, report.getCohort().fullRefundExcluded());
    }

    @Test
    void pendingRefundIsNotTreatedAsRefunded() {
        // 退款处理中(未确认到账)：仍进分母，单列 pending，不当已退。
        RegistrationCohortFact pending = paid(5L, 51L, "ORD-P", "66.00", at("2026-10-01T09:00:00"));
        pending.setPendingRefundCount(1);
        stubHistory(List.of(pending));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(1, report.getCohort().eligibleMembers());
        assertEquals(0, report.getCohort().fullRefundExcluded());
        assertEquals(1, report.getCohort().pendingRefundMembers());
    }

    @Test
    void partialRefundKeepsMemberInDenominatorAndIsReportedSeparately() {
        // 部分退(30/100)：仍在分母，单列；第二笔有效报名计入分子并标 partialRefundRetained。
        RegistrationCohortFact first = paid(6L, 61L, "ORD-M", "100.00", at("2026-10-01T09:00:00"));
        first.setSettledRefundAmount(new BigDecimal("30.00"));
        first.setHasSettledRefund(true);
        RegistrationCohortFact second = paid(6L, 62L, "ORD-N", "100.00", at("2026-10-05T09:00:00"));
        second.setSettledRefundAmount(new BigDecimal("10.00"));
        second.setHasSettledRefund(true);
        stubHistory(List.of(first, second));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(1, report.getCohort().eligibleMembers());
        assertEquals(1, report.getCohort().partialRefundMembers());
        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers());
        assertEquals(1, report.getCohort().partialRefundRetained());
    }

    @Test
    void freeRegistrationIsSeparatedFromPaidDenominator() {
        // 实付 0 ⇒ 免费桶；免费会员的第二次有效报名进 freeRetained，不进付费留存率。
        stubHistory(List.of(
                paid(7L, 71L, "ORD-F", "0.00", at("2026-10-01T09:00:00")),
                paid(7L, 72L, "ORD-F2", "0.00", at("2026-10-12T09:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        assertEquals(1, report.getCohort().freeMembers());
        assertEquals(Long.valueOf(1), report.getCohort().freeRetainedMembers());
        assertNull(report.getCohort().retentionRate());
        assertEquals(new BigDecimal("1.0000"), report.getCohort().freeRetentionRate());
    }

    @Test
    void cancelledFirstOrderIsExcludedAndReportedSeparately() {
        RegistrationCohortFact cancelled = paid(8L, 81L, "ORD-C", "40.00", at("2026-10-01T09:00:00"));
        cancelled.setRegistrationStatus(3);
        stubHistory(List.of(cancelled));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        assertEquals(1, report.getCohort().cancelledOrExpiredExcluded());
    }

    @Test
    void expiredFirstOrderIsExcludedAndReportedSeparately() {
        RegistrationCohortFact expired = paid(9L, 91L, "ORD-E", "40.00", at("2026-10-01T09:00:00"));
        expired.setRegistrationStatus(4);
        stubHistory(List.of(expired));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        assertEquals(1, report.getCohort().cancelledOrExpiredExcluded());
    }

    @Test
    void memberWhoseFirstValidRegistrationPredatesCohortIsNotInThisCohort() {
        // 全历史口径：首单在 9 月 ⇒ 属于更早 cohort，本次既不计分母也不计分子。
        stubHistory(List.of(
                paid(10L, 101L, "ORD-OLD", "50.00", at("2026-09-20T09:00:00")),
                paid(10L, 102L, "ORD-NEW", "50.00", at("2026-10-01T09:00:00")),
                paid(10L, 103L, "ORD-LATER", "50.00", at("2026-10-15T09:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        assertEquals(0, report.getCohort().freeMembers());
        assertNull(report.getCohort().retainedMembers());
    }

    @Test
    void secondRegistrationExactlyAtWindowEndIsNotCounted() {
        // 30 日窗 = 10-02 00:00 起 30 天 ⇒ 窗末 11-01 00:00（不含）。正好落在窗末的不算。
        stubHistory(List.of(
                paid(11L, 111L, "ORD-W1", "50.00", at("2026-10-01T09:00:00")),
                paid(11L, 112L, "ORD-W2", "50.00", at("2026-11-01T00:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(1, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(0), report.getCohort().retainedMembers());
    }

    @Test
    void secondRegistrationJustBeforeWindowEndIsCounted() {
        stubHistory(List.of(
                paid(12L, 121L, "ORD-V1", "50.00", at("2026-10-01T09:00:00")),
                paid(12L, 122L, "ORD-V2", "50.00", at("2026-10-31T23:59:59"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers());
    }

    @Test
    void immatureWindowReportsNullInsteadOfZero() {
        PostGameOutcomeMapper local = mapper;
        when(local.selectCohortMemberIds(any(), any())).thenReturn(List.of(13L));
        when(local.selectPaidRegistrationHistory(anyCollection(), any())).thenReturn(List.of(
                paid(13L, 131L, "ORD-I1", "50.00", at("2026-10-01T09:00:00")),
                paid(13L, 132L, "ORD-I2", "50.00", at("2026-10-05T09:00:00"))));

        PostGameOutcomeReportVO report = immatureService().generate(COHORT, 30);

        assertFalse(report.isMature());
        assertNull(report.getCohort().retainedMembers(), "未成熟不得展示为 0");
        assertNull(report.getCohort().retentionRate(), "未成熟不得展示 0% 留存");
        assertEquals(1, report.getCohort().notMatureMembers());
        assertEquals(1, report.getCohort().eligibleMembers());
    }

    @Test
    void lateArrivingFactIsCountedAndFlagged() {
        // 支付发生在窗内，但落库(create_time)晚于成熟点 ⇒ 计入且 lateFactCount+1。
        RegistrationCohortFact late = paid(14L, 141L, "ORD-L", "50.00", at("2026-10-20T09:00:00"));
        late.setCreateTime(at("2026-11-10T09:00:00"));
        stubHistory(List.of(
                paid(14L, 140L, "ORD-L0", "50.00", at("2026-10-01T09:00:00")),
                late));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers());
        assertEquals(1, report.getCohort().lateFactCount());
    }

    @Test
    void clientObservedEventsNeverEnterDenominatorOrNumerator() {
        when(mapper.selectCohortMemberIds(any(), any())).thenReturn(List.of(15L));
        when(mapper.selectPaidRegistrationHistory(anyCollection(), any())).thenReturn(List.of(
                paid(15L, 151L, "ORD-O1", "50.00", at("2026-10-01T09:00:00"))));
        OutcomeFactRow click = new OutcomeFactRow();
        click.setMemberId(15L);
        click.setFactCount(3L);
        click.setFirstOccurredAt(at("2026-10-03T09:00:00"));
        click.setLastOccurredAt(at("2026-10-03T09:00:00"));
        when(mapper.selectClientObservedOutcomeFacts(any(), any())).thenReturn(List.of(click));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(1, report.getCohort().eligibleMembers());
        assertEquals(Long.valueOf(0), report.getCohort().retainedMembers());
        PostGameOutcomeReportVO.MetricCount observed = report.getMetrics().get(
                PostGameOutcomeReportServiceImpl.CLIENT_OBSERVED);
        assertNotNull(observed);
        assertFalse(observed.serverFinal(), "客户端观测必须标非服务端终局");
        assertEquals(3L, observed.facts());
    }

    @Test
    void allSixOutcomeKindsAreReportedSeparately() {
        stubHistory(List.of(paid(16L, 161L, "ORD-K1", "50.00", at("2026-10-01T09:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertTrue(report.getMetrics().containsKey(PostGameOutcomeReportServiceImpl.COMPLETION));
        assertTrue(report.getMetrics().containsKey(PostGameOutcomeReportServiceImpl.REWARD));
        assertTrue(report.getMetrics().containsKey(PostGameOutcomeReportServiceImpl.PRIVATE_PHOTO));
        assertTrue(report.getMetrics().containsKey(PostGameOutcomeReportServiceImpl.PUBLIC_SHARE));
        assertTrue(report.getMetrics().containsKey(PostGameOutcomeReportServiceImpl.RECO_CLICK));
        assertTrue(report.getMetrics().containsKey(PostGameOutcomeReportServiceImpl.SECOND_REGISTRATION));
        assertFalse(report.getMetrics().get(PostGameOutcomeReportServiceImpl.PRIVATE_PHOTO).publicVisible(),
                "私人照片默认不公开");
    }

    /**
     * 回归（完成口径缺陷）：一个玩家在路线还在进行时通关了节点小游戏。
     *
     * <p>{@code game_runtime_session} 的 status=1 每通关一个节点就会写一次，
     * 路线仍在 {@code topic_route_session.status=0} 时它已经是 1。
     * 因此「整局完成」必须保持 0 —— 否则会把这局算成已完成。
     */
    @Test
    void nodeMinigameCompletionWhileRouteStillRunningKeepsOfficialCompletionAtZero() {
        // 该会员当天付费报名，窗内也产生了第二笔报名（留存与完成是两件事，互不冒充）。
        stubHistory(List.of(
                paid(20L, 201L, "ORD-N1", "80.00", at("2026-10-01T09:00:00")),
                paid(20L, 202L, "ORD-N2", "80.00", at("2026-10-10T09:00:00"))));

        // 完成事实源在该窗口内只有整局终局事实；节点小游戏不在其中，所以服务端没有任何完成行。
        when(mapper.selectCompletionFacts(any(), any())).thenReturn(new ArrayList<>());
        // 客户端上报了到点成功（节点级），这是观测，不是整局完成。
        List<OutcomeFactRow> clientAtNode = new ArrayList<>();
        OutcomeFactRow nodeArrival = new OutcomeFactRow();
        nodeArrival.setMemberId(20L);
        nodeArrival.setSourceKind("client-reported:play_arrive_success");
        nodeArrival.setFactCount(3L);
        nodeArrival.setFirstOccurredAt(at("2026-10-10T10:00:00"));
        nodeArrival.setLastOccurredAt(at("2026-10-10T10:00:00"));
        clientAtNode.add(nodeArrival);
        when(mapper.selectClientObservedOutcomeFacts(any(), any())).thenReturn(clientAtNode);

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        PostGameOutcomeReportVO.MetricCount completion =
                report.getMetrics().get(PostGameOutcomeReportServiceImpl.COMPLETION);
        assertEquals(0L, completion.members(), "节点小游戏通关不得计入整局完成");
        assertEquals(0L, completion.facts(), "节点小游戏不得产生整局完成事实");
        // 留存照常成立，证明完成口径为 0 不是把整个报表打死。
        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers());
        // 服务层自身声明的来源也必须与 SQL 一致：节点小游戏不能出现在完成口径里。
        assertFalse(completion.sourceTables().contains("game_runtime_session"),
                "完成口径的来源声明不得再包含节点小游戏");
        assertTrue(completion.sourceTables().contains("topic_route_session"),
                "完成口径必须声明真实整局终局来源");
        // 客户端到点上报被记录，但明确标为非服务端终局。
        PostGameOutcomeReportVO.MetricCount observed =
                report.getMetrics().get(PostGameOutcomeReportServiceImpl.CLIENT_OBSERVED);
        assertEquals(1L, observed.members());
        assertFalse(observed.serverFinal(), "节点到点上报不是整局完成");
    }

    /** 真正到达终点的路线会话才算整局完成，且按会员归属。 */
    @Test
    void routeFinalStateCountsAsOfficialCompletion() {
        stubHistory(List.of(paid(21L, 211L, "ORD-R1", "80.00", at("2026-10-01T09:00:00"))));
        List<OutcomeFactRow> completed = new ArrayList<>();
        OutcomeFactRow routeDone = new OutcomeFactRow();
        routeDone.setMemberId(21L);
        routeDone.setSourceKind("TOPIC_ROUTE_SESSION_COMPLETED");
        routeDone.setFactCount(1L);
        routeDone.setFirstOccurredAt(at("2026-10-03T18:00:00"));
        routeDone.setLastOccurredAt(at("2026-10-03T18:00:00"));
        completed.add(routeDone);
        when(mapper.selectCompletionFacts(any(), any())).thenReturn(completed);

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        PostGameOutcomeReportVO.MetricCount completion =
                report.getMetrics().get(PostGameOutcomeReportServiceImpl.COMPLETION);
        assertEquals(1L, completion.members());
        assertEquals(1L, completion.facts());
        assertTrue(completion.serverFinal());
        assertNotNull(completion.firstOccurredAt());
    }

    /** 无法按会员归属的完成来源必须显式列出，而不是静默丢掉或猜一个会员。 */
    @Test
    void unawardableCompletionSourcesAreDeclaredNotGuessed() {
        stubHistory(List.of(paid(22L, 221L, "ORD-U1", "80.00", at("2026-10-01T09:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        String reason = report.getUnavailableSources().get("GAME_SESSION_ACTIVITY_FINISHED");
        assertNotNull(reason, "活动级总局无法按会员归属，必须显式声明为不可用来源");
        assertTrue(reason.contains("finished_by"));
        assertEquals("POST_GAME_OUTCOME_V2_20261108", report.getBasisVersion(),
                "口径已变更，版本号必须前进，避免与旧数字直接比较");
    }

    @Test
    void sourceReadFailureThrowsInsteadOfReportingZero() {
        when(mapper.selectCohortMemberIds(any(), any())).thenReturn(null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> matureService().generate(COHORT, 30));
        assertTrue(failure.getMessage().contains("未知"));
    }

    @Test
    void registrationHistoryReadFailureThrowsInsteadOfReportingZero() {
        when(mapper.selectCohortMemberIds(any(), any())).thenReturn(List.of(17L));
        when(mapper.selectPaidRegistrationHistory(anyCollection(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> matureService().generate(COHORT, 30));
    }

    @Test
    void metricReadFailureThrowsInsteadOfReportingZero() {
        stubHistory(List.of(paid(18L, 181L, "ORD-Z", "50.00", at("2026-10-01T09:00:00"))));
        when(mapper.selectCompletionFacts(any(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> matureService().generate(COHORT, 30));
    }

    @Test
    void emptyCohortStillReturnsWatermarksAndBasisWithoutQueryingHistory() {
        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(0, report.getCohort().eligibleMembers());
        verify(mapper, never()).selectPaidRegistrationHistory(anyCollection(), any());
        assertEquals(PostGameOutcomeReportVO.BASIS_VERSION, report.getBasisVersion());
        assertFalse(report.isHistoricalReplayable(), "只读即时计算不是可回放快照");
        assertNotNull(report.getComputedAt());
    }

    @Test
    void unsupportedWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> matureService().generate(COHORT, 14));
    }

    @Test
    void watermarksArePassedThroughForRecompute() {
        Map<String, Object> row = new HashMap<>();
        row.put("sourceName", "cms_registration.payment_time");
        row.put("watermarkAt", at("2026-11-18T09:00:00"));
        when(mapper.selectSourceWatermarks()).thenReturn(List.of(row));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertNotNull(report.getSourceWatermarks().get("cms_registration.payment_time"));
    }

    @Test
    void repeatSignupV1CountsSameDayIndependentOrderWithoutChangingLegacyV2() {
        stubHistory(List.of(
                paid(30L, 301L, "FIRST", "50.00", at("2026-10-01T09:00:00")),
                paid(30L, 302L, "SECOND", "50.00", at("2026-10-01T10:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals("POST_GAME_OUTCOME_V2_20261108", report.getBasisVersion());
        assertEquals(Long.valueOf(0), report.getCohort().retainedMembers());
        assertEquals(0L, report.getMetrics().get(PostGameOutcomeReportServiceImpl.SECOND_REGISTRATION).members());
        PostGameOutcomeReportVO.RepeatSignupV1 repeat = report.getRepeatSignupV1();
        assertEquals("repeat_signup_v1", repeat.definitionVersion());
        assertEquals(30, repeat.windowDays());
        assertTrue(repeat.available());
        assertEquals(1, repeat.paid().eligibleMembers());
        assertEquals(Long.valueOf(1), repeat.paid().retainedMembers());
        assertEquals(Long.valueOf(1), repeat.all().retainedMembers());
        assertTrue(repeat.identityBasis().contains("member_id"));
        assertTrue(repeat.recomputeBasis().contains("sourceWatermarks"));
        assertNull(matureService().generate(COHORT, 7).getRepeatSignupV1());
        assertNull(matureService().generate(COHORT, 90).getRepeatSignupV1());
    }

    @Test
    void repeatSignupV1CollapsesSeatsAndDoesNotInferOrderAtEqualPaymentTime() {
        stubHistory(List.of(
                paidSeat(31L, 311L, "SEATS", "50.00", at("2026-10-01T09:00:00"), 3),
                paidSeat(31L, 312L, "SEATS", "50.00", at("2026-10-01T10:00:00"), 3),
                paidSeat(31L, 313L, "SEATS", "50.00", at("2026-10-02T09:00:00"), 3),
                paid(32L, 321L, "SAME-TIME-A", "50.00", at("2026-10-01T09:00:00")),
                paid(32L, 322L, "SAME-TIME-B", "50.00", at("2026-10-01T09:00:00"))));

        PostGameOutcomeReportVO.RepeatSignupV1 repeat = matureService().generate(COHORT, 30).getRepeatSignupV1();

        assertEquals(2, repeat.paid().eligibleMembers());
        assertEquals(Long.valueOf(0), repeat.paid().retainedMembers());
        assertEquals(Long.valueOf(0), repeat.all().retainedMembers());
    }

    @Test
    void repeatSignupV1UsesActualThirtyDaysFromPaymentAndExclusiveEnd() {
        stubHistory(List.of(
                paid(33L, 331L, "BEFORE-A", "50.00", at("2026-10-01T09:00:00")),
                paid(33L, 332L, "BEFORE-B", "50.00", at("2026-10-31T08:59:59.999")),
                paid(34L, 341L, "EXACT-A", "50.00", at("2026-10-01T09:00:00")),
                paid(34L, 342L, "EXACT-B", "50.00", at("2026-10-31T09:00:00")),
                paid(35L, 351L, "AFTER-A", "50.00", at("2026-10-01T09:00:00")),
                paid(35L, 352L, "AFTER-B", "50.00", at("2026-10-31T23:59:59"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(Long.valueOf(3), report.getCohort().retainedMembers(), "legacy 次日起算口径保留");
        assertEquals(3, report.getRepeatSignupV1().paid().matureMembers());
        assertEquals(Long.valueOf(1), report.getRepeatSignupV1().paid().retainedMembers());
        assertEquals(new BigDecimal("0.3333"), report.getRepeatSignupV1().paid().retentionRate());
    }

    @Test
    void repeatSignupV1SkipsInvalidFirstOrdersAndPreservesRefundFacts() {
        RegistrationCohortFact refunded = paid(36L, 361L, "FULL", "50.00", at("2026-09-20T09:00:00"));
        refunded.setSettledRefundAmount(new BigDecimal("50.00"));
        refunded.setHasSettledRefund(true);
        RegistrationCohortFact cancelled = paid(36L, 362L, "CANCEL", "50.00", at("2026-09-21T09:00:00"));
        cancelled.setRegistrationStatus(3);
        RegistrationCohortFact expired = paid(36L, 363L, "EXPIRE", "50.00", at("2026-09-22T09:00:00"));
        expired.setRegistrationStatus(4);
        RegistrationCohortFact first = paid(36L, 364L, "VALID-A", "50.00", at("2026-10-01T09:00:00"));
        first.setHasSettledRefund(true);
        first.setSettledRefundAmount(new BigDecimal("5.00"));
        first.setPendingRefundCount(1);
        RegistrationCohortFact second = paid(36L, 365L, "VALID-B", "50.00", at("2026-10-01T10:00:00"));
        second.setHasSettledRefund(true);
        second.setSettledRefundAmount(new BigDecimal("10.00"));
        second.setPendingRefundCount(1);
        stubHistory(List.of(refunded, cancelled, expired, first, second));

        PostGameOutcomeReportVO.RepeatSignupCohort paid =
                matureService().generate(COHORT, 30).getRepeatSignupV1().paid();

        assertEquals(1, paid.eligibleMembers());
        assertEquals(Long.valueOf(1), paid.retainedMembers());
        assertEquals(1, paid.partialRefundMembers());
        assertEquals(1, paid.pendingRefundMembers());
        assertEquals(1, paid.partialRefundRetained());
        assertEquals(1, paid.pendingRefundRetained());
        assertEquals(new BigDecimal("50.00"), refunded.getSettledRefundAmount());
        assertEquals(Integer.valueOf(2), refunded.getPaymentStatus(), "读取不改原退款/支付事实");
    }

    @Test
    void repeatSignupV1ExcludesRefundedSecondOrderButPendingRefundRemainsValid() {
        RegistrationCohortFact second = paid(37L, 372L, "FULL-SECOND", "50.00", at("2026-10-01T10:00:00"));
        second.setPaymentStatus(4);
        RegistrationCohortFact pending = paid(38L, 382L, "PENDING-SECOND", "50.00", at("2026-10-01T10:00:00"));
        pending.setPendingRefundCount(1);
        stubHistory(List.of(
                paid(37L, 371L, "FULL-FIRST", "50.00", at("2026-10-01T09:00:00")), second,
                paid(38L, 381L, "PENDING-FIRST", "50.00", at("2026-10-01T09:00:00")), pending));

        PostGameOutcomeReportVO.RepeatSignupCohort paid =
                matureService().generate(COHORT, 30).getRepeatSignupV1().paid();

        assertEquals(2, paid.eligibleMembers());
        assertEquals(Long.valueOf(1), paid.retainedMembers());
        assertEquals(1, paid.pendingRefundRetained());
    }

    @Test
    void repeatSignupV1KeepsPaidAndAllSignupCohortsSeparateIncludingFreeOrders() {
        stubHistory(List.of(
                paid(39L, 391L, "CASH-A", "50.00", at("2026-10-01T09:00:00")),
                paid(39L, 392L, "FREE-B", "0.00", at("2026-10-01T10:00:00")),
                paid(40L, 401L, "FREE-FIRST", "0.00", at("2026-10-01T09:00:00")),
                paid(40L, 402L, "CASH-FIRST", "50.00", at("2026-10-01T10:00:00")),
                paid(40L, 403L, "CASH-SECOND", "50.00", at("2026-10-02T10:00:00"))));

        PostGameOutcomeReportVO.RepeatSignupV1 repeat = matureService().generate(COHORT, 30).getRepeatSignupV1();

        assertEquals(2, repeat.paid().eligibleMembers());
        assertEquals(Long.valueOf(1), repeat.paid().retainedMembers());
        assertEquals(2, repeat.all().eligibleMembers());
        assertEquals(Long.valueOf(2), repeat.all().retainedMembers());
    }

    @Test
    void repeatSignupV1MaturesEachMemberAtTheirOwnThirtyDayBoundary() {
        stubHistory(List.of(
                paid(41L, 411L, "EARLY-A", "50.00", at("2026-10-01T09:00:00")),
                paid(41L, 412L, "EARLY-B", "50.00", at("2026-10-01T10:00:00")),
                paid(42L, 421L, "LATE-A", "50.00", at("2026-10-01T11:00:00")),
                paid(42L, 422L, "LATE-B", "50.00", at("2026-10-01T12:00:00"))));
        PostGameOutcomeReportServiceImpl boundaryService = new PostGameOutcomeReportServiceImpl(mapper, null,
                Clock.fixed(at("2026-10-31T09:00:00").toInstant(), ZONE));

        PostGameOutcomeReportVO report = boundaryService.generate(COHORT, 30);

        assertFalse(report.isMature(), "legacy 仍等待次日起算的整日窗");
        assertEquals(2, report.getRepeatSignupV1().paid().eligibleMembers());
        assertEquals(1, report.getRepeatSignupV1().paid().matureMembers());
        assertEquals(1, report.getRepeatSignupV1().paid().notMatureMembers());
        assertEquals(Long.valueOf(1), report.getRepeatSignupV1().paid().retainedMembers());
        assertEquals(new BigDecimal("1.0000"), report.getRepeatSignupV1().paid().retentionRate());
        PostGameOutcomeReportVO.RepeatSignupCohort immature =
                immatureService().generate(COHORT, 30).getRepeatSignupV1().paid();
        assertEquals(0, immature.matureMembers());
        assertEquals(2, immature.notMatureMembers());
        assertNull(immature.retainedMembers());
        assertNull(immature.retentionRate());
    }

    @Test
    void repeatSignupV1IgnoresFutureFirstPaymentAndMarksLateFactsForRecompute() {
        RegistrationCohortFact late = paid(43L, 432L, "LATE-B", "50.00", at("2026-10-01T10:00:00"));
        late.setCreateTime(at("2026-11-10T09:00:00"));
        stubHistory(List.of(
                paid(43L, 431L, "LATE-A", "50.00", at("2026-10-01T09:00:00")), late,
                paid(44L, 441L, "FUTURE", "50.00", at("2026-10-01T12:00:00"))));
        Map<String, Object> watermark = new HashMap<>();
        watermark.put("sourceName", "cms_registration.payment_time");
        watermark.put("watermarkAt", at("2026-11-10T09:00:00"));
        when(mapper.selectSourceWatermarks()).thenReturn(List.of(watermark));
        PostGameOutcomeReportServiceImpl sameDayService = new PostGameOutcomeReportServiceImpl(mapper, null,
                Clock.fixed(at("2026-10-01T11:00:00").toInstant(), ZONE));

        assertEquals(1, sameDayService.generate(COHORT, 30).getRepeatSignupV1().paid().eligibleMembers());
        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);
        assertEquals(1, report.getRepeatSignupV1().paid().lateFactCount());
        assertEquals(at("2026-11-10T09:00:00"),
                report.getSourceWatermarks().get("cms_registration.payment_time"));
    }

    @Test
    void repeatSignupV1MissingRelevantOrderIdentityIsUnknownWithoutBreakingLegacy() {
        stubHistory(List.of(
                paid(45L, 451L, "KNOWN", "50.00", at("2026-10-01T09:00:00")),
                paid(45L, 452L, null, "50.00", at("2026-10-02T09:00:00"))));

        PostGameOutcomeReportVO report = matureService().generate(COHORT, 30);

        assertEquals(Long.valueOf(1), report.getCohort().retainedMembers(), "legacy fallback 保留");
        assertFalse(report.getRepeatSignupV1().available());
        assertNotNull(report.getRepeatSignupV1().unknownReason());
        assertNull(report.getRepeatSignupV1().paid());
        assertNull(report.getRepeatSignupV1().all());

        stubHistory(List.of(paid(45L, 451L, "", "50.00", at("2026-10-01T09:00:00"))));
        assertFalse(matureService().generate(COHORT, 30).getRepeatSignupV1().available());
    }

    @Test
    void repeatSignupV1UnrelatedMissingOrderIdentityDoesNotPoisonCurrentCohort() {
        RegistrationCohortFact invalid = paid(47L, 471L, null, "50.00", at("2026-09-20T09:00:00"));
        invalid.setRegistrationStatus(3);
        stubHistory(List.of(
                paid(46L, 461L, null, "50.00", at("2026-09-20T09:00:00")),
                paid(46L, 462L, "OLD-COHORT", "50.00", at("2026-10-01T09:00:00")), invalid,
                paid(47L, 472L, "FIRST-VALID", "50.00", at("2026-10-01T09:00:00")),
                paid(47L, 473L, null, "50.00", at("2026-10-31T09:00:00")),
                paid(47L, 474L, null, "50.00", at("2026-11-01T09:00:00"))));

        PostGameOutcomeReportVO.RepeatSignupV1 repeat = matureService().generate(COHORT, 30).getRepeatSignupV1();

        assertTrue(repeat.available());
        assertEquals(1, repeat.paid().eligibleMembers(), "更早的有效首单仍归原cohort，首个无效单可跳过");
        assertEquals(Long.valueOf(0), repeat.paid().retainedMembers());
    }

    private void stubHistory(List<RegistrationCohortFact> facts) {
        List<Long> members = new ArrayList<>();
        for (RegistrationCohortFact fact : facts) {
            if (!members.contains(fact.getMemberId())) members.add(fact.getMemberId());
        }
        when(mapper.selectCohortMemberIds(any(), any())).thenReturn(members);
        when(mapper.selectPaidRegistrationHistory(anyCollection(), any())).thenReturn(facts);
    }

    private static Date at(String localDateTime) {
        return Date.from(java.time.LocalDateTime.parse(localDateTime)
                .atZone(ZONE).toInstant());
    }

    /** 一条已支付且有效的报名行。 */
    private static RegistrationCohortFact paid(Long memberId, Long registrationId, String parentOrderNo,
                                               String payable, Date paymentTime) {
        return paidSeat(memberId, registrationId, parentOrderNo, payable, paymentTime, 1);
    }

    private static RegistrationCohortFact paidSeat(Long memberId, Long registrationId, String parentOrderNo,
                                                   String payable, Date paymentTime, int orderNum) {
        RegistrationCohortFact fact = new RegistrationCohortFact();
        fact.setMemberId(memberId);
        fact.setRegistrationId(registrationId);
        fact.setParentOrderNo(parentOrderNo);
        fact.setPayableAmount(new BigDecimal(payable));
        fact.setTotalAmount(new BigDecimal(payable));
        fact.setPaymentTime(paymentTime);
        fact.setCreateTime(paymentTime);
        fact.setPaymentStatus(2);
        fact.setRegistrationStatus(2);
        fact.setOrderNum(orderNum);
        fact.setSettledRefundAmount(BigDecimal.ZERO);
        fact.setPendingRefundCount(0);
        fact.setHasSettledRefund(false);
        return fact;
    }
}
