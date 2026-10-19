package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.RoamRewardDeliveryMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoamRewardDeliveryTest {
    private RoamRewardDeliveryMapper mapper;
    private ISmsCouponHistoryService coupons;
    private RoamRewardDeliveryService service;
    private PlatformTransactionManager transactions;
    private Map<String, Object> intent;
    private com.chengyinhub.business.service.IRewardDropService drops;
    private com.chengyinhub.business.service.IGrowthService growth;
    @BeforeEach void setup() {
        mapper = mock(RoamRewardDeliveryMapper.class); coupons = mock(ISmsCouponHistoryService.class);
        // A包 SmsCouponHistoryServiceImpl.grantCouponBySource 合同：未发出(缺意图/停发/无库存)返回 null；Mockito 对 Long 默认回 0L。
        when(coupons.grantCouponBySource(anyLong(), anyLong(), anyString(), anyLong())).thenReturn(null);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        drops = mock(com.chengyinhub.business.service.IRewardDropService.class);
        growth = mock(com.chengyinhub.business.service.IGrowthService.class);
        service = new RoamRewardDeliveryService(mapper, coupons, transactions, drops, growth);
        Map<String, Object> original = new HashMap<>(); original.put("dropType", 3); original.put("paramJson", "{\"couponTemplateId\":123}");
        when(mapper.selectOriginalRewardDrop(7L, 77L)).thenReturn(original);
        intent = new HashMap<>(); intent.put("memberId", 77L); intent.put("couponId", 123L); intent.put("state", "DUE"); intent.put("rewardKind", "COUPON");
        when(mapper.selectIntent(7L)).thenReturn(intent);
    }
    @Test void originalSourceReceiptAndIndependentIntentCommitPrecedeGrant() {
        when(coupons.grantCouponBySource(77L, 123L, "reward_drop", 7L)).thenReturn(456L);
        assertTrue(service.deliverOriginal(77L, 7L));
        org.mockito.InOrder order = inOrder(transactions, mapper, coupons);
        order.verify(mapper).selectOriginalRewardDrop(7L, 77L);
        order.verify(transactions).getTransaction(argThat(t -> t.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(mapper).prepare(7L, 77L, 123L); order.verify(mapper).selectIntent(7L);
        order.verify(transactions).commit(any());
        order.verify(coupons).grantCouponBySource(77L, 123L, "reward_drop", 7L);
        verify(mapper).completeIfDue(7L, 77L, 123L, "DELIVERED", 456L);
    }
    @Test void missingStockOrCouponRemainsDueAndRetryUsesSameReceipt() {
        assertFalse(service.deliverOriginal(77L, 7L));
        verify(mapper, never()).completeIfDue(anyLong(), anyLong(), anyLong(), anyString(), any());
        when(coupons.grantCouponBySource(77L, 123L, "reward_drop", 7L)).thenReturn(456L);
        assertTrue(service.deliverOriginal(77L, 7L));
        verify(coupons, times(2)).grantCouponBySource(77L, 123L, "reward_drop", 7L);
    }
    @Test void interruptionAfterGrantCommitReplaysOriginalSourceOnly() {
        when(coupons.grantCouponBySource(77L, 123L, "reward_drop", 7L)).thenReturn(456L);
        when(mapper.completeIfDue(7L, 77L, 123L, "DELIVERED", 456L))
                .thenThrow(new IllegalStateException("completion unavailable")).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.deliverOriginal(77L, 7L));
        assertTrue(service.deliverOriginal(77L, 7L));
        verify(coupons, times(2)).grantCouponBySource(77L, 123L, "reward_drop", 7L);
    }
    @Test void disabledNewGrantSkipsButExistingReceiptIsRecoveredBeforeDisabledCheck() {
        when(coupons.isCouponNotIssuable(123L)).thenReturn(true);
        assertTrue(service.deliverOriginal(77L, 7L));
        verify(mapper).completeIfDue(7L, 77L, 123L, "SKIPPED_DISABLED", null);
        reset(coupons); when(coupons.grantCouponBySource(77L, 123L, "reward_drop", 7L)).thenReturn(456L);
        assertTrue(service.deliverOriginal(77L, 7L));
        verify(coupons, never()).isCouponNotIssuable(anyLong());
    }
    @Test void originalOwnerMismatchOrBadParamCannotGrant() {
        assertThrows(RuntimeException.class, () -> service.deliverOriginal(88L, 7L));
        intent.put("couponId", 124L);
        assertThrows(RuntimeException.class, () -> service.deliverOriginal(77L, 7L));
        verifyNoInteractions(coupons);
    }
    @Test void completedOriginalIntentDoesNotReissue() {
        intent.put("state", "DELIVERED");
        assertTrue(service.deliverOriginal(77L, 7L)); verifyNoInteractions(coupons);
    }
    @Test void scannerUsesFixedHighWaterAndAdvancesPastEachFailedOriginalDrop() {
        RoamRewardDeliveryService worker = spy(service);
        Map<String, Object> empty = new HashMap<>(); empty.put("afterId", 0L); empty.put("throughId", 0L);
        Map<String, Object> round = new HashMap<>(); round.put("afterId", 0L); round.put("throughId", 250L);
        when(mapper.selectCursorForUpdate()).thenReturn(empty, round);
        when(mapper.advanceCursor(anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.highWaterMark()).thenReturn(250L);
        Map<String, Object> a = new HashMap<>(); a.put("id", 1L); a.put("memberId", 77L);
        Map<String, Object> b = new HashMap<>(); b.put("id", 200L); b.put("memberId", 77L);
        when(mapper.scanOriginalPending(0L, 250L, 100)).thenReturn(Collections.singletonList(a));
        when(mapper.scanOriginalPending(1L, 250L, 100)).thenReturn(Collections.singletonList(b));
        when(mapper.scanOriginalPending(200L, 250L, 100)).thenReturn(Collections.emptyList());
        doThrow(new IllegalStateException("first unavailable")).when(worker).deliverOriginal(77L, 1L);
        doReturn(true).when(worker).deliverOriginal(77L, 200L);
        assertDoesNotThrow(worker::reconcile);
        verify(worker).deliverOriginal(77L, 200L); verify(mapper).scanOriginalPending(200L, 250L, 100);
        verify(mapper, times(1)).highWaterMark();
    }
    @Test void xpRollCommitsImmutableAmountBeforeAnyDeliveryAndDuplicateDeliveryDoesNotRoll() {
        Map<String, Object> drop = new HashMap<>(); drop.put("dropType", 1); drop.put("dropLogId", 7L); drop.put("extraXp", 20L);
        when(drops.rollDrop(77L, 0L, 5L, 9L, "roam_merchant_poi", 20L)).thenReturn(drop);
        assertEquals(drop, service.rollOriginalDrop(77L, 5L, 9L, 20));
        verify(mapper).prepareXp(7L, 77L, 20); verifyNoInteractions(growth);
        Map<String, Object> original = new HashMap<>(); original.put("dropType", 1);
        when(mapper.selectOriginalRewardDrop(7L, 77L)).thenReturn(original);
        intent.remove("couponId"); intent.put("rewardKind", "XP"); intent.put("extraXp", 20);
        when(mapper.selectIntentForUpdate(7L)).thenReturn(intent);
        when(mapper.completeIfDue(7L, 77L, null, "DELIVERED", null)).thenAnswer(call -> { intent.put("state", "DELIVERED"); return 1; });
        assertTrue(service.deliverOriginal(77L, 7L)); assertTrue(service.deliverOriginal(77L, 7L));
        verify(growth, times(1)).addExp(77L, 20, "掉落·漫游探索值翻倍");
        verify(drops, times(1)).rollDrop(77L, 0L, 5L, 9L, "roam_merchant_poi", 20L);
    }
    @Test void legacyXpWithoutFrozenAmountIsUnknownAndNeverGuessed() {
        Map<String, Object> original = new HashMap<>(); original.put("dropType", 1); original.put("paramJson", "{\"mult\":2}");
        when(mapper.selectOriginalRewardDrop(7L, 77L)).thenReturn(original);
        when(mapper.selectIntentForUpdate(7L)).thenReturn(null); // 旧掉落无冻结意图(Mockito 对 Map 默认回空 Map)
        assertFalse(service.deliverOriginal(77L, 7L));
        verify(mapper).prepareUnknownXp(7L, 77L); verifyNoInteractions(growth, drops, coupons);
    }
    @Test void scannerStopsAt500AndResumesPersistentCursorNextInvocation() {
        RoamRewardDeliveryService worker = spy(service);
        Map<String, Object> cursor = new HashMap<>(); cursor.put("afterId", 0L); cursor.put("throughId", 1000L);
        when(mapper.selectCursorForUpdate()).thenReturn(cursor);
        when(mapper.scanOriginalPending(anyLong(), eq(1000L), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(0); int limit = call.getArgument(2);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long id = after + 1; id <= Math.min(after + limit, 1000); id++) {
                Map<String, Object> row = new HashMap<>(); row.put("id", id); row.put("memberId", 77L); rows.add(row);
            }
            return rows;
        });
        when(mapper.advanceCursor(anyLong(), anyLong(), eq(1000L))).thenAnswer(call -> { cursor.put("afterId", call.getArgument(1)); return 1; });
        doReturn(true).when(worker).deliverOriginal(eq(77L), anyLong());
        worker.reconcile(); assertEquals(500L, cursor.get("afterId"));
        verify(worker, times(500)).deliverOriginal(eq(77L), anyLong());
        worker.reconcile(); assertEquals(1000L, cursor.get("afterId"));
        verify(worker, times(1000)).deliverOriginal(eq(77L), anyLong());
        verify(mapper, never()).highWaterMark(); verify(mapper).completeRound(1000L, 1000L);
    }
}
