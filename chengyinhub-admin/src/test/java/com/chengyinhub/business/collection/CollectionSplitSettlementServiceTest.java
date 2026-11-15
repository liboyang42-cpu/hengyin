package com.chengyinhub.business.collection;

import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.PayoutSwitches;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S2 collection model, settlement side: wallet exclusion, profit-sharing planning after T+N, refunds first. */
class CollectionSplitSettlementServiceTest {
    static final String SUB = "1900000999";
    static final PayoutSwitches RAILS_ON = new PayoutSwitches(true, true, true, "1900000001", "wx_mini", "", 30, false, "none");

    CollectionModelService model;
    CollectionModelMapper mapper;
    EcommerceProfitShareService profitSharing;
    CoopOrderMapper coop;

    @BeforeEach
    void setUp() {
        model = mock(CollectionModelService.class);
        mapper = mock(CollectionModelMapper.class);
        profitSharing = mock(EcommerceProfitShareService.class);
        coop = mock(CoopOrderMapper.class);
        when(coop.selectEffectiveOrdersByTopicId(anyLong())).thenReturn(List.of());
    }

    CollectionSplitSettlementService service(PayoutSwitches switches) {
        return new CollectionSplitSettlementService(model, mapper, switches, profitSharing, coop);
    }

    static Map<String, Object> due(long ownerType, Object rate) {
        Map<String, Object> row = new HashMap<>();
        row.put("registrationNo", "R-S2-1");
        row.put("registrationId", 11L);
        row.put("ownerType", ownerType);
        row.put("ownerId", 40L);
        row.put("sellerType", "CLUB");
        row.put("sellerId", 3L);
        row.put("subMchid", SUB);
        row.put("totalFen", 6900L);
        row.put("takeRate", rate);
        row.put("transactionId", "TX9");
        return row;
    }

    @Test
    void allOffMeansNoReadAndNothingSubtracted() {
        CollectionSplitSettlementService s = service(PayoutSwitches.allOff());
        assertEquals(BigDecimal.ZERO, s.splitSalesForActivity(40L));
        assertFalse(s.isSplitCollected(11L));
        assertEquals(0, s.planDue(50));
        verifyNoInteractions(mapper, profitSharing);
    }

    @Test
    void splitActivitySalesAreLeftOutOfTheWalletSettlement() {
        when(model.active()).thenReturn(true);
        when(mapper.sumSplitPaidSalesForActivity(40L)).thenReturn(new BigDecimal("138.00"));
        when(mapper.sumSplitRetainedSalesForActivity(40L)).thenReturn(new BigDecimal("34.50"));
        when(mapper.countByRegistrationId(11L)).thenReturn(1);
        CollectionSplitSettlementService s = service(PayoutSwitches.allOff());
        assertEquals(new BigDecimal("172.50"), s.splitSalesForActivity(40L));
        assertEquals(true, s.isSplitCollected(11L));
    }

    @Test
    void splitOrdersStayExcludedAfterTheModelClosesWhileTheRailsAreOn() {
        when(model.active()).thenReturn(false);
        when(mapper.countByRegistrationId(11L)).thenReturn(1);
        assertEquals(true, service(RAILS_ON).isSplitCollected(11L));
    }

    @Test
    void dueOrderIsPlannedWithTheTakeFromTheFrozenRate() {
        when(mapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(due(2L, new BigDecimal("0.03"))));
        when(profitSharing.planSingleSeller(eq(-40L), any(), eq(207L), eq(null))).thenReturn(901L);
        when(mapper.updateOrderState("R-S2-1", "OPEN", "PLANNED", 901L, null)).thenReturn(1);
        assertEquals(1, service(RAILS_ON).planDue(50));
        ArgumentCaptor<EcomOrderSeller> trade = ArgumentCaptor.forClass(EcomOrderSeller.class);
        verify(profitSharing).planSingleSeller(eq(-40L), trade.capture(), eq(207L), eq(null));
        assertEquals(SUB, trade.getValue().getSubMchid());
        assertEquals("TX9", trade.getValue().getTransactionId());
        assertEquals(6900L, trade.getValue().getTotalFen());
    }

    @Test
    void missingRateGoesToManualReviewNeverToAGuessedRate() {
        when(mapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(due(1L, null)));
        when(profitSharing.planSingleSeller(eq(40L), any(), eq(0L), eq("TAKE_RATE_MISSING"))).thenReturn(902L);
        when(mapper.updateOrderState("R-S2-1", "OPEN", "PLANNED", 902L, null)).thenReturn(1);
        assertEquals(1, service(RAILS_ON).planDue(50));
        verify(profitSharing).planSingleSeller(eq(40L), any(), eq(0L), eq("TAKE_RATE_MISSING"));
    }

    @Test
    void refundsComeFirst() {
        when(mapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(due(2L, new BigDecimal("0.03"))));
        when(mapper.countOpenRefunds("R-S2-1")).thenReturn(1);
        assertEquals(0, service(RAILS_ON).planDue(50));
        verify(profitSharing, never()).planSingleSeller(anyLong(), any(), anyLong(), any());

        when(mapper.countOpenRefunds("R-S2-1")).thenReturn(0);
        when(mapper.sumSucceededRefundFen("R-S2-1")).thenReturn(6900L);
        assertEquals(0, service(RAILS_ON).planDue(50));
        verify(mapper).updateOrderState("R-S2-1", "OPEN", "REFUNDED", null, "FULLY_REFUNDED");
        verify(profitSharing, never()).planSingleSeller(anyLong(), any(), anyLong(), any());
    }

    @Test
    void coopTopicIsLeftToTheCoopLedgerPlanner() {
        when(mapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(due(1L, new BigDecimal("0.03"))));
        when(coop.selectEffectiveOrdersByTopicId(40L)).thenReturn(List.of(new CoopOrder()));
        assertEquals(0, service(RAILS_ON).planDue(50));
        verify(mapper).updateOrderState("R-S2-1", "OPEN", "COOP", null, "COOP_LEDGER");
    }

    @Test
    void oneBadRowDoesNotStopTheBatch() {
        Map<String, Object> bad = due(2L, new BigDecimal("0.03"));
        bad.put("registrationNo", "R-BAD");
        when(mapper.selectDueOrders(any(), anyInt())).thenReturn(List.of(bad, due(2L, new BigDecimal("0.03"))));
        when(mapper.countOpenRefunds("R-BAD")).thenThrow(new IllegalStateException("boom"));
        when(profitSharing.planSingleSeller(eq(-40L), any(), eq(207L), eq(null))).thenReturn(901L);
        when(mapper.updateOrderState("R-S2-1", "OPEN", "PLANNED", 901L, null)).thenReturn(1);
        assertEquals(1, service(RAILS_ON).planDue(50));
    }

    @Test
    void replanRefusesWithoutAFrozenRate() {
        Map<String, Object> row = due(2L, null);
        row.put("shareOrderId", 901L);
        when(mapper.selectOrder("R-S2-1")).thenReturn(row);
        assertEquals("TAKE_RATE_MISSING", service(RAILS_ON).replan("R-S2-1"));
        assertEquals("NOT_A_SPLIT_PLAN", service(RAILS_ON).replan("R-NONE"));
    }

    @Test
    void sourceIdsUseTheNegativeNamespaceForActivities() {
        assertEquals(40L, CollectionSplitSettlementService.sourceId(1L, 40L));
        assertEquals(-40L, CollectionSplitSettlementService.sourceId(2L, 40L));
    }
}
