package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import com.chengyinhub.business.domain.MarketingPromotionRefundFact;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.mapper.MarketingPromotionRefundFactMapper;
import com.chengyinhub.business.service.IMarketingPromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款到账 → 促销撤销的适配器契约。
 *
 * <p>要证明的四件事:
 * <ol>
 *   <li><b>订单解析</b>:多票订单的促销快照在首单上,子单退款必须归并到同一订单,否则永远不会释放;
 *       但只按 parent_order_no + member_id 这两条既有事实归并,命中多条要拒绝而不是挑一个。</li>
 *   <li><b>部分退款不撤销</b>:单笔 50% 不撤;两张不同子单各 50% 累计到 100% 才撤。</li>
 *   <li><b>幂等</b>:同一退款事实重投不新增账本行,累计不变。</li>
 *   <li><b>事实冲突要响亮</b>:币种、缺 hash、缺失 id、负数、同一单两个原支付总额 —— 一律抛异常由回调重投。</li>
 * </ol>
 *
 * <p>不证明 InnoDB 事务与 SQL 幂等 —— 那是 MySqlIT。
 */
class MarketingPromotionRefundSettledConsumerTest {

    private static final Long PRIMARY_REGISTRATION_ID = 900L;
    private static final Long CHILD_REGISTRATION_ID = 901L;
    private static final Long REFUND_APPLICATION_ID = 41L;
    private static final String FACT_HASH = "a".repeat(64);
    private static final long PAID_FEN = 5000L;
    private static final Integer ORDER_TYPE = MarketingPromotionRefundFact.ORDER_REGISTRATION;

    private IMarketingPromotionService promotionService;
    private MarketingPromotionRefundFactMapper refundFacts;
    private MarketingPromotionRefundSettledConsumer consumer;

    @BeforeEach
    void setUp() {
        promotionService = mock(IMarketingPromotionService.class);
        refundFacts = mock(MarketingPromotionRefundFactMapper.class);
        consumer = new MarketingPromotionRefundSettledConsumer(promotionService, refundFacts);
        givenResolvesTo(PRIMARY_REGISTRATION_ID, PRIMARY_REGISTRATION_ID);
        givenPaidTotal(PRIMARY_REGISTRATION_ID, PAID_FEN);
    }

    // ------------------------------------------------------------------ 订单解析

    @Test
    void orderWithoutPromotionSnapshotIsIgnoredAndNothingIsRecorded() {
        givenResolvesTo(PRIMARY_REGISTRATION_ID);

        consumer.onRefundSettled(fact(PRIMARY_REGISTRATION_ID, PAID_FEN, PAID_FEN, REFUND_APPLICATION_ID));

        verify(refundFacts, never()).upsertRefundFact(any());
        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));
    }

    @Test
    void childRegistrationRefundIsAccumulatedAgainstThePrimaryPromotionOrder() {
        givenResolvesTo(CHILD_REGISTRATION_ID, PRIMARY_REGISTRATION_ID);
        givenCumulative(PRIMARY_REGISTRATION_ID, PAID_FEN);
        givenPaidTotal(PRIMARY_REGISTRATION_ID, PAID_FEN);

        consumer.onRefundSettled(fact(CHILD_REGISTRATION_ID, PAID_FEN, PAID_FEN, REFUND_APPLICATION_ID));

        ArgumentCaptor<MarketingPromotionRefundFact> captor =
                ArgumentCaptor.forClass(MarketingPromotionRefundFact.class);
        verify(refundFacts).upsertRefundFact(captor.capture());
        assertEquals(PRIMARY_REGISTRATION_ID, captor.getValue().getOrderId(),
                "账本必须记在促销成交订单(首单)上,否则累计永远读不到");
        assertEquals(CHILD_REGISTRATION_ID, captor.getValue().getRefundRegistrationId(),
                "被退那张要单独留档,便于审计");
        verify(promotionService, times(1)).revokeForOrder(
                eq(MarketingOrderAdjustment.ORDER_REGISTRATION), eq(PRIMARY_REGISTRATION_ID), any(Date.class));
    }

    @Test
    void multipleSnapshotsUnderOneOrderAreRejectedInsteadOfMerged() {
        givenResolvesTo(CHILD_REGISTRATION_ID, PRIMARY_REGISTRATION_ID, PRIMARY_REGISTRATION_ID + 5);

        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(
                        fact(CHILD_REGISTRATION_ID, PAID_FEN, PAID_FEN, REFUND_APPLICATION_ID)));
        assertTrue(rejection.getMessage().contains("多份促销成交快照"), rejection.getMessage());
        verify(refundFacts, never()).upsertRefundFact(any());
    }

    // ------------------------------------------------------------------ 部分退款 / 累计

    @Test
    void fullRefundRevokesOnceUnderTheRegistrationOrderNamespace() {
        givenCumulative(PRIMARY_REGISTRATION_ID, PAID_FEN);

        consumer.onRefundSettled(fact(PRIMARY_REGISTRATION_ID, PAID_FEN, PAID_FEN, REFUND_APPLICATION_ID));

        verify(promotionService, times(1)).revokeForOrder(
                eq(MarketingOrderAdjustment.ORDER_REGISTRATION), eq(PRIMARY_REGISTRATION_ID), any(Date.class));
    }

    @Test
    void firstPartialRefundRecordsTheFactButKeepsThePromotion() {
        givenCumulative(PRIMARY_REGISTRATION_ID, 1000L);

        consumer.onRefundSettled(fact(PRIMARY_REGISTRATION_ID, 1000L, PAID_FEN, REFUND_APPLICATION_ID));

        ArgumentCaptor<MarketingPromotionRefundFact> captor =
                ArgumentCaptor.forClass(MarketingPromotionRefundFact.class);
        verify(refundFacts).upsertRefundFact(captor.capture());
        assertEquals(Long.valueOf(1000L), captor.getValue().getRefundedFen());
        assertEquals(ORDER_TYPE, captor.getValue().getOrderType());
        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));
    }

    @Test
    void twoDifferentChildRefundsFiftyPercentEachRevokeOnlyWhenCumulativeReachesPaidTotal() {
        // 两张不同子单,各自与首单同 parent_order_no → 都解析到首单的促销订单
        givenResolvesTo(CHILD_REGISTRATION_ID, PRIMARY_REGISTRATION_ID);

        // 第一笔 50%:累计 2500 < 5000 → 不撤
        givenCumulative(PRIMARY_REGISTRATION_ID, 2500L);
        consumer.onRefundSettled(fact(CHILD_REGISTRATION_ID, 2500L, PAID_FEN, REFUND_APPLICATION_ID));
        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));

        // 第二笔(另一张子单)50%:累计 5000 = 5000 → 撤一次
        givenResolvesTo(CHILD_REGISTRATION_ID + 1, PRIMARY_REGISTRATION_ID);
        givenCumulative(PRIMARY_REGISTRATION_ID, PAID_FEN);
        consumer.onRefundSettled(fact(CHILD_REGISTRATION_ID + 1, 2500L, PAID_FEN, REFUND_APPLICATION_ID + 1));

        verify(promotionService, times(1)).revokeForOrder(
                eq(MarketingOrderAdjustment.ORDER_REGISTRATION), eq(PRIMARY_REGISTRATION_ID), any(Date.class));
    }

    @Test
    void replayingTheSameRefundKeepsOneLedgerRowAndDoesNotDoubleCount() {
        givenCumulative(PRIMARY_REGISTRATION_ID, 2500L);
        RefundSettledFact settled = fact(PRIMARY_REGISTRATION_ID, 2500L, PAID_FEN, REFUND_APPLICATION_ID);

        consumer.onRefundSettled(settled);
        consumer.onRefundSettled(settled);

        // 写入两次都走同一幂等键(on duplicate key update id = id),累计只算一次 ⇒ 仍不撤。
        verify(refundFacts, times(2)).upsertRefundFact(any());
        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));
    }

    @Test
    void unknownPaidTotalKeepsThePromotionInsteadOfReleasingBlindly() {
        givenPaidTotal(PRIMARY_REGISTRATION_ID, 0L);
        givenCumulative(PRIMARY_REGISTRATION_ID, 1000L);

        consumer.onRefundSettled(fact(PRIMARY_REGISTRATION_ID, 1000L, 0L, REFUND_APPLICATION_ID));

        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));
    }

    // ------------------------------------------------------------------ 事实冲突（负例）

    @Test
    void conflictingPaidTotalsOnTheSameOrderAreRejected() {
        when(refundFacts.distinctTotalFen(ORDER_TYPE, PRIMARY_REGISTRATION_ID))
                .thenReturn(Arrays.asList(PAID_FEN, PAID_FEN + 100L));

        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(
                        fact(PRIMARY_REGISTRATION_ID, 2500L, PAID_FEN, REFUND_APPLICATION_ID)));
        assertTrue(rejection.getMessage().contains("多个原支付总额"), rejection.getMessage());
        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));
    }

    @Test
    void factWithoutVerifiedHashIsRejected() {
        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(fact(PRIMARY_REGISTRATION_ID, PAID_FEN, PAID_FEN,
                        REFUND_APPLICATION_ID, "CNY", " ")));
        assertTrue(rejection.getMessage().contains("不可变事实摘要"), rejection.getMessage());
        verify(refundFacts, never()).upsertRefundFact(any());
    }

    @Test
    void factWithoutRegistrationIdIsRejected() {
        RefundSettledFact broken = new RefundSettledFact(null, REFUND_APPLICATION_ID, "RF900", "WXREFUND1",
                PAID_FEN, PAID_FEN, "CNY", 77L, FACT_HASH);
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(broken));
    }

    @Test
    void factWithoutRefundApplicationIdIsRejected() {
        RefundSettledFact broken = new RefundSettledFact(PRIMARY_REGISTRATION_ID, null, "RF900", "WXREFUND1",
                PAID_FEN, PAID_FEN, "CNY", 77L, FACT_HASH);
        assertThrows(IllegalStateException.class, () -> consumer.onRefundSettled(broken));
    }

    @Test
    void nonCnyFactIsRejectedInsteadOfSilentlyApplyingYuanAmounts() {
        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(
                        fact(PRIMARY_REGISTRATION_ID, PAID_FEN, PAID_FEN, REFUND_APPLICATION_ID, "USD", FACT_HASH)));
        assertTrue(rejection.getMessage().contains("币种"), rejection.getMessage());
    }

    @Test
    void negativeRefundedAmountIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> consumer.onRefundSettled(fact(PRIMARY_REGISTRATION_ID, -1L, PAID_FEN, REFUND_APPLICATION_ID)));
        verify(refundFacts, never()).upsertRefundFact(any());
        verify(promotionService, never()).revokeForOrder(anyInt(), anyLong(), any(Date.class));
    }

    // ------------------------------------------------------------------ helpers

    private void givenResolvesTo(Long refundedRegistrationId, Long... promoOrderIds) {
        when(refundFacts.resolvePromoOrderIds(ORDER_TYPE, refundedRegistrationId))
                .thenReturn(new ArrayList<>(Arrays.asList(promoOrderIds)));
    }

    private void givenCumulative(Long promoOrderId, long refundedFen) {
        when(refundFacts.sumRefundedFen(ORDER_TYPE, promoOrderId)).thenReturn(refundedFen);
    }

    private void givenPaidTotal(Long promoOrderId, long totalFen) {
        when(refundFacts.distinctTotalFen(ORDER_TYPE, promoOrderId))
                .thenReturn(totalFen <= 0 ? Collections.emptyList() : Arrays.asList(totalFen));
    }

    private RefundSettledFact fact(Long registrationId, long refundedFen, long totalFen, Long refundApplicationId) {
        return fact(registrationId, refundedFen, totalFen, refundApplicationId, "CNY", FACT_HASH);
    }

    private RefundSettledFact fact(Long registrationId, long refundedFen, long totalFen,
                                  String currency, String hash) {
        return fact(registrationId, refundedFen, totalFen, REFUND_APPLICATION_ID, currency, hash);
    }

    private RefundSettledFact fact(Long registrationId, long refundedFen, long totalFen,
                                  Long refundApplicationId, String currency, String hash) {
        return new RefundSettledFact(registrationId, refundApplicationId, "RF" + registrationId, "WXREFUND1",
                refundedFen, totalFen, currency, 77L, hash);
    }
}
