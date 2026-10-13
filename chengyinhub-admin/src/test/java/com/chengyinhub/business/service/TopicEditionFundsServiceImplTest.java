package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicEditionFunds;
import com.chengyinhub.business.domain.TopicEditionFundsRawFacts;
import com.chengyinhub.business.domain.TopicEditionFundsAttribution;
import com.chengyinhub.business.domain.TopicEditionCost;
import com.chengyinhub.business.domain.vo.TopicEditionFinanceOverviewVO;
import com.chengyinhub.business.mapper.TopicEditionFundsMapper;
import com.chengyinhub.business.service.impl.TopicEditionFundsServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TopicEditionFundsServiceImplTest {

    @Test
    void finalRecomputeWritesAnImmutableSnapshotAfterAllFundsHaveSettled() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectByIdempotencyKey("FINAL:23:FUNDS")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("300", "0", "0", "60"));
        when(mapper.selectFinalAttributionFacts(23L, 2, 3)).thenReturn(Arrays.asList(
                attribution(null, 2L, "100"), attribution(7L, 3L, "140")));
        when(mapper.insertSnapshot(any())).thenAnswer(invocation -> {
            ((TopicEditionFunds) invocation.getArgument(0)).setId(91L);
            return 1;
        });
        when(mapper.insertFinalAttribution(any())).thenReturn(1);
        when(mapper.selectAttributionsBySnapshotId(91L)).thenReturn(Arrays.asList(
                persistedAttribution(null, 2L, "100"), persistedAttribution(7L, 3L, "140")));
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        TopicEditionFunds result = service.recompute(23L, new Date(1000L),
                "FINAL:23:FUNDS", "finance-a", TopicEditionFundsPhase.FINAL);

        assertEquals(TopicEditionFunds.KIND_FINAL, result.getSnapshotKind());
        assertEquals(new BigDecimal("240"), result.getGEarned());
        assertEquals(BigDecimal.ZERO, result.getUOut());
        assertEquals(BigDecimal.ZERO, result.getUPending());
        verify(mapper).insertSnapshot(any());
        verify(mapper, org.mockito.Mockito.times(2)).insertFinalAttribution(any());
    }

    @Test
    void finalReplayReadsBackThePersistedAttributionWithoutRecomputingMutableFacts() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds existing = snapshot("FINAL:23:FUNDS", "final-hash");
        existing.setSnapshotKind(TopicEditionFunds.KIND_FINAL);
        existing.setNCash(5L);
        existing.setGEarned(new BigDecimal("240"));
        when(mapper.selectByIdempotencyKey("FINAL:23:FUNDS")).thenReturn(existing);
        when(mapper.selectAttributionsBySnapshotId(existing.getId())).thenReturn(Arrays.asList(
                persistedAttribution(null, 2L, "100"), persistedAttribution(7L, 3L, "140")));

        TopicEditionFunds replay = new TopicEditionFundsServiceImpl(mapper).recompute(23L, new Date(2000L),
                "FINAL:23:FUNDS", "finance-b", TopicEditionFundsPhase.FINAL);

        assertSame(existing, replay);
        verify(mapper, never()).selectProvisionalRawFacts(any(), any(), any(), any());
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void finalProductionRejectsAnyPaidTicketThatIsStillUnfrozen() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectByIdempotencyKey("FINAL:23:UNFROZEN")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("100", "0", "0", "0"));
        TopicEditionFundsAttribution unfrozen = attribution(7L, 5L, "100");
        unfrozen.setUnfrozenCount(1L);
        when(mapper.selectFinalAttributionFacts(23L, 2, 3))
                .thenReturn(Collections.singletonList(unfrozen));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TopicEditionFundsServiceImpl(mapper).recompute(23L, new Date(1000L),
                        "FINAL:23:UNFROZEN", "finance-a", TopicEditionFundsPhase.FINAL));

        assertEquals("EDITION_FUNDS_ATTRIBUTION_INVALID", error.getMessage());
        verify(mapper, never()).insertSnapshot(any());
    }

    /**
     * ★净票数超过毛计数 ⇒ FINAL 生产当场红,不许落库。
     *
     * <p>net_ticket_count 是承接费 HEADCOUNT 的唯一计酬基数。归因查询若写错
     * （退款 join 漏掉、或误抄成 count(*)），多算出来的票数会直接变成多付的钱 ——
     * 而 FINAL 快照一旦落库就不可变,事后只能手工改库。这道闸把它挡在写入前。
     */
    @Test
    void finalProductionRejectsANetTicketCountAboveTheGrossPaidCount() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectByIdempotencyKey("FINAL:23:NETGT")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("100", "0", "0", "0"));
        TopicEditionFundsAttribution row = attribution(7L, 5L, "100");
        row.setNetTicketCount(6L);      // 净 > 毛：不可能，只能是查询写错了
        when(mapper.selectFinalAttributionFacts(23L, 2, 3))
                .thenReturn(Collections.singletonList(row));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TopicEditionFundsServiceImpl(mapper).recompute(23L, new Date(1000L),
                        "FINAL:23:NETGT", "finance-a", TopicEditionFundsPhase.FINAL));

        assertEquals("EDITION_FUNDS_ATTRIBUTION_INVALID", error.getMessage());
        verify(mapper, never()).insertSnapshot(any());
    }

    /** 净票数缺失同样不许落库：新生产的行必须带这个事实，NULL 只允许出现在历史快照里。 */
    @Test
    void finalProductionRejectsAMissingNetTicketCount() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectByIdempotencyKey("FINAL:23:NETNULL")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("100", "0", "0", "0"));
        TopicEditionFundsAttribution row = attribution(7L, 5L, "100");
        row.setNetTicketCount(null);
        when(mapper.selectFinalAttributionFacts(23L, 2, 3))
                .thenReturn(Collections.singletonList(row));

        assertEquals("EDITION_FUNDS_ATTRIBUTION_INVALID", assertThrows(IllegalStateException.class,
                () -> new TopicEditionFundsServiceImpl(mapper).recompute(23L, new Date(1000L),
                        "FINAL:23:NETNULL", "finance-a", TopicEditionFundsPhase.FINAL))
                .getMessage());
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void concurrentFinalWithAnotherKeyCannotSupersedeTheImmutableWinner() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds winner = snapshot("FINAL:23:FUNDS", "final-hash");
        winner.setSnapshotKind(TopicEditionFunds.KIND_FINAL);
        when(mapper.selectByIdempotencyKey("FINAL:23:OTHER")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(winner);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TopicEditionFundsServiceImpl(mapper).recompute(23L, new Date(2000L),
                        "FINAL:23:OTHER", "finance-b", TopicEditionFundsPhase.FINAL));

        assertEquals("EDITION_FUNDS_FINAL_ALREADY_EXISTS", error.getMessage());
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void expiredNoShowLeavesUPendingAndEntersEarnedInFirstThreeEditions() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectByIdempotencyKey("req-1")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_PROVISIONAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("300", "100", "100", "60"));
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        when(mapper.insertSnapshot(any())).thenReturn(1);

        TopicEditionFunds result = service.recomputeProvisional(
                23L, new Date(1000L), "req-1", "finance-a");

        assertEquals(new BigDecimal("100"), result.getUOut());
        assertEquals(new BigDecimal("100"), result.getUPending());
        assertEquals(new BigDecimal("140"), result.getGEarned());
    }

    @Test
    void finalRefusesUnsettledMoneyBeforeWritingAnything() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("100", "1", "1", "0"));
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.recompute(23L, new Date(1000L), "FINAL:23:FUNDS", "finance-a",
                        TopicEditionFundsPhase.FINAL));

        assertEquals("EDITION_FUNDS_FINAL_UNSETTLED", error.getMessage());
        verify(mapper, never()).selectFinalAttributionFacts(any(), any(), any());
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void finalRefusesAnAttributionThatDoesNotConserveEarnedMoney() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("100", "0", "0", "0"));
        when(mapper.selectFinalAttributionFacts(23L, 2, 3))
                .thenReturn(Collections.singletonList(attribution(7L, 5L, "99.99")));
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.recompute(23L, new Date(1000L), "FINAL:23:FUNDS", "finance-a",
                        TopicEditionFundsPhase.FINAL));

        assertEquals("EDITION_FUNDS_ATTRIBUTION_UNBALANCED", error.getMessage());
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void recomputeWritesActivityShellFactsAndDerivesEarned() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectByIdempotencyKey("req-1")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_PROVISIONAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("300", "100", "100", "60"));
        when(mapper.insertSnapshot(any())).thenAnswer(invocation -> {
            TopicEditionFunds value = invocation.getArgument(0);
            value.setId(91L);
            return 1;
        });
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        TopicEditionFunds result = service.recomputeProvisional(23L, new Date(1000L), "req-1", "finance-a");

        assertEquals(91L, result.getId());
        assertEquals(new BigDecimal("140"), result.getGEarned());
        assertEquals(new BigDecimal("100"), result.getUPending());
        assertEquals(1L, result.getVersion());
        assertEquals(1, result.getCurrent());
    }

    @Test
    void sameIdempotencyKeyReadsBackExistingSnapshotWithoutRecomputation() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds existing = snapshot("req-1", "hash-a");
        when(mapper.selectByIdempotencyKey("req-1")).thenReturn(existing);
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        TopicEditionFunds result = service.recomputeProvisional(23L, new Date(1000L), "req-1", "finance-a");

        assertSame(existing, result);
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void reusedIdempotencyKeyForAnotherTopicFailsClosed() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds existing = snapshot("req-1", "hash-a");
        existing.setTopicId(24L);
        when(mapper.selectByIdempotencyKey("req-1")).thenReturn(existing);
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.recomputeProvisional(23L, new Date(1000L), "req-1", "finance-a"));

        assertEquals("EDITION_FUNDS_IDEMPOTENCY_CONFLICT", error.getMessage());
    }

    @Test
    void changedFactsSupersedePriorProvisionalInsteadOfUpdatingHistory() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds current = snapshot("old-key", "old-hash");
        current.setId(7L);
        current.setVersion(3L);
        when(mapper.selectByIdempotencyKey("new-key")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_PROVISIONAL)).thenReturn(current);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any()))
                .thenReturn(facts("300", "100", "100", "60"));
        when(mapper.supersedeCurrentProvisional(7L, "finance-a", "PROVISIONAL_RECOMPUTED")).thenReturn(1);
        when(mapper.insertSnapshot(any())).thenReturn(1);
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        TopicEditionFunds result = service.recomputeProvisional(23L, new Date(1000L), "new-key", "finance-a");

        assertEquals(4L, result.getVersion());
        verify(mapper).supersedeCurrentProvisional(7L, "finance-a", "PROVISIONAL_RECOMPUTED");
    }

    @Test
    void unknownPaidMoneyFailsBeforeAnySnapshotWrite() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFundsRawFacts facts = facts("0", "0", "0", "0");
        facts.setUnknownCount(1L);
        when(mapper.selectByIdempotencyKey("req-1")).thenReturn(null);
        when(mapper.selectCurrentForUpdate(23L, TopicEditionFunds.KIND_PROVISIONAL)).thenReturn(null);
        when(mapper.selectProvisionalRawFacts(any(), any(), any(), any())).thenReturn(facts);
        TopicEditionFundsServiceImpl service = new TopicEditionFundsServiceImpl(mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.recomputeProvisional(23L, new Date(1000L), "req-1", "finance-a"));

        assertEquals("EDITION_FUNDS_UNKNOWN:paidFacts=1", error.getMessage());
        verify(mapper, never()).insertSnapshot(any());
    }

    @Test
    void financeOverviewPrefersFinalAndDoesNotMixInAProvisionalSnapshot() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds finalFunds = snapshot("final-req", "final-hash");
        finalFunds.setSnapshotKind(TopicEditionFunds.KIND_FINAL);
        finalFunds.setGEarned(new BigDecimal("700"));
        TopicEditionFunds provisional = snapshot("provisional-req", "provisional-hash");
        provisional.setSnapshotKind(TopicEditionFunds.KIND_PROVISIONAL);
        provisional.setGEarned(new BigDecimal("500"));
        when(mapper.selectCurrent(23L, TopicEditionFunds.KIND_FINAL)).thenReturn(finalFunds);
        when(mapper.selectCurrent(23L, TopicEditionFunds.KIND_PROVISIONAL)).thenReturn(provisional);
        when(mapper.selectCurrentCost(23L)).thenReturn(cost());
        when(mapper.selectHours(23L)).thenReturn(Collections.emptyList());

        TopicEditionFinanceOverviewVO result =
                new TopicEditionFundsServiceImpl(mapper).currentFinanceOverview(23L);

        assertSame(finalFunds, result.getFunds());
        assertEquals(TopicEditionFunds.KIND_FINAL, result.getSnapshotKind());
        assertEquals(new BigDecimal("350"), result.getContributionMargin());
        verify(mapper, never()).selectCurrent(23L, TopicEditionFunds.KIND_PROVISIONAL);
    }

    @Test
    void financeOverviewFallsBackToProvisionalAndConnectsCostCmAndDisplayOnlyBreakEven() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        TopicEditionFunds funds = snapshot("req-1", "hash-a");
        funds.setSnapshotKind(TopicEditionFunds.KIND_PROVISIONAL);
        funds.setGEarned(new BigDecimal("500"));
        TopicEditionCost cost = cost();
        when(mapper.selectCurrent(23L, TopicEditionFunds.KIND_PROVISIONAL)).thenReturn(funds);
        when(mapper.selectCurrentCost(23L)).thenReturn(cost);
        when(mapper.selectHours(23L)).thenReturn(Collections.emptyList());

        TopicEditionFinanceOverviewVO result =
                new TopicEditionFundsServiceImpl(mapper).currentFinanceOverview(23L);

        assertSame(funds, result.getFunds());
        assertEquals(TopicEditionFunds.KIND_PROVISIONAL, result.getSnapshotKind());
        assertEquals(new BigDecimal("100"), result.getPlatformCostTotal());
        assertEquals(new BigDecimal("150"), result.getContributionMargin());
        assertEquals(13L, result.getBreakEvenTickets());
        assertEquals(false, result.isBreakEvenSaleGate());
    }

    @Test
    void financeOverviewKeepsFundsUnknownWhenNeitherSnapshotExists() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectCurrentCost(23L)).thenReturn(cost());
        when(mapper.selectHours(23L)).thenReturn(Collections.emptyList());

        TopicEditionFinanceOverviewVO result =
                new TopicEditionFundsServiceImpl(mapper).currentFinanceOverview(23L);

        assertEquals(null, result.getFunds());
        assertEquals(null, result.getSnapshotKind());
        assertEquals("EDITION_FUNDS_UNKNOWN", result.getContributionMarginReason());
        verify(mapper).selectCurrent(23L, TopicEditionFunds.KIND_FINAL);
        verify(mapper).selectCurrent(23L, TopicEditionFunds.KIND_PROVISIONAL);
    }

    @Test
    void missingCostFailsClosedAsUnavailableInsteadOfInventingZeroInputs() {
        TopicEditionFundsMapper mapper = mock(TopicEditionFundsMapper.class);
        when(mapper.selectHours(23L)).thenReturn(Collections.emptyList());

        TopicEditionFinanceOverviewVO result =
                new TopicEditionFundsServiceImpl(mapper).currentFinanceOverview(23L);

        assertEquals("EDITION_COST_UNKNOWN", result.getContributionMarginReason());
        assertEquals("EDITION_COST_UNKNOWN", result.getBreakEvenReason());
    }

    private TopicEditionFundsRawFacts facts(String gCash, String uOut, String uPending, String rRefund) {
        TopicEditionFundsRawFacts facts = new TopicEditionFundsRawFacts();
        facts.setNCash(5L);
        facts.setGCash(new BigDecimal(gCash));
        facts.setUOut(new BigDecimal(uOut));
        facts.setUPending(new BigDecimal(uPending));
        facts.setRRefund(new BigDecimal(rRefund));
        facts.setUnknownCount(0L);
        facts.setInvalidRefundCount(0L);
        return facts;
    }

    private TopicEditionFundsAttribution attribution(Long clubId, Long tickets, String earnedAmount) {
        TopicEditionFundsAttribution value = new TopicEditionFundsAttribution();
        value.setSourceClubId(clubId);
        value.setTicketCount(tickets);
        // 默认净票数 == 毛计数（无退款）。要造退款场景的用例自己覆盖它。
        value.setNetTicketCount(tickets);
        value.setEarnedAmount(new BigDecimal(earnedAmount));
        value.setUnfrozenCount(0L);
        return value;
    }

    private TopicEditionFundsAttribution persistedAttribution(Long clubId, Long tickets, String earnedAmount) {
        TopicEditionFundsAttribution value = attribution(clubId, tickets, earnedAmount);
        value.setUnfrozenCount(null);
        return value;
    }

    private TopicEditionFunds snapshot(String key, String hash) {
        TopicEditionFunds value = new TopicEditionFunds();
        value.setId(1L);
        value.setTopicId(23L);
        value.setSnapshotKind(TopicEditionFunds.KIND_PROVISIONAL);
        value.setVersion(1L);
        value.setCurrent(1);
        value.setIdempotencyKey(key);
        value.setInputHash(hash);
        return value;
    }

    private TopicEditionCost cost() {
        TopicEditionCost value = new TopicEditionCost();
        value.setTaxCost(new BigDecimal("10"));
        value.setPaymentCost(new BigDecimal("10"));
        value.setMaterialCost(new BigDecimal("10"));
        value.setKitCost(new BigDecimal("10"));
        value.setInsuranceRiskCost(new BigDecimal("10"));
        value.setAcquisitionCost(new BigDecimal("10"));
        value.setPlatformLaborCost(new BigDecimal("20"));
        value.setOtherCost(new BigDecimal("20"));
        value.setFixedCompensation(new BigDecimal("200"));
        value.setBonuses(new BigDecimal("50"));
        value.setBreakEvenFixedCost(new BigDecimal("500"));
        value.setVariableCostPerTicket(new BigDecimal("9"));
        value.setNote("固定工作示例");
        return value;
    }
}
