package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamBadgeDeliveryMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoamBadgeDeliveryTest {
    private RoamBadgeDeliveryMapper intents;
    private PlayerBadgeMapper badges;
    private RoamMapper sessions;
    private RoamBadgeDeliveryService service;
    private Map<String, Object> intent;
    @BeforeEach void setup() {
        intents = mock(RoamBadgeDeliveryMapper.class); badges = mock(PlayerBadgeMapper.class); sessions = mock(RoamMapper.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RoamBadgeDeliveryService(intents, badges, sessions, tx);
        intent = new HashMap<>(); intent.put("id", 1L); intent.put("state", "DUE"); intent.put("sourceType", 4);
        intent.put("badgeName", "原50格徽章"); intent.put("iconUrl", "original-icon"); intent.put("obtainTime", new Date(1000));
        when(intents.selectForUpdate(77L, 9L, "ROAM_TILES_50", 50L)).thenReturn(intent);
    }
    @Test void originalSnapshotDeliveryUsesMemberThenIntentAndBadgeLocks() {
        when(badges.insertPlayerBadge(any())).thenReturn(1); when(intents.complete(1L, 77L)).thenReturn(1);
        assertTrue(service.deliver(77L, 9L, "ROAM_TILES_50", 50L));
        org.mockito.InOrder order = inOrder(sessions, intents, badges);
        order.verify(sessions).lockSettlementOwner(77L);
        order.verify(intents).selectForUpdate(77L, 9L, "ROAM_TILES_50", 50L);
        order.verify(badges).selectOne(77L, "ROAM_TILES_50", 50L);
        org.mockito.ArgumentCaptor<PlayerBadge> captured = org.mockito.ArgumentCaptor.forClass(PlayerBadge.class);
        order.verify(badges).insertPlayerBadge(captured.capture()); order.verify(intents).complete(1L, 77L);
        assertEquals("原50格徽章", captured.getValue().getBadgeName()); assertEquals("original-icon", captured.getValue().getIconUrl());
        verify(sessions, never()).updateSessionFinish(any());
    }
    @Test void badgeInsertFailureDoesNotCompleteIntentOrChangeFinish() {
        when(badges.insertPlayerBadge(any())).thenThrow(new IllegalStateException("badge unavailable"));
        assertThrows(IllegalStateException.class, () -> service.deliver(77L, 9L, "ROAM_TILES_50", 50L));
        verify(intents, never()).complete(anyLong(), anyLong()); verify(sessions, never()).updateSessionFinish(any());
    }
    @Test void existingOriginalBadgeConvergesWithoutAnotherInsert() {
        when(badges.selectOne(77L, "ROAM_TILES_50", 50L)).thenReturn(new PlayerBadge());
        when(intents.complete(1L, 77L)).thenReturn(1);
        assertTrue(service.deliver(77L, 9L, "ROAM_TILES_50", 50L)); verify(badges, never()).insertPlayerBadge(any());
        intent.put("state", "DELIVERED"); assertTrue(service.deliver(77L, 9L, "ROAM_TILES_50", 50L));
        verify(badges, times(1)).selectOne(77L, "ROAM_TILES_50", 50L);
    }
    @Test void wrongOriginalOwnerCannotWriteBadge() {
        assertThrows(RuntimeException.class, () -> service.deliver(88L, 9L, "ROAM_TILES_50", 50L)); verifyNoInteractions(badges);
    }
    @Test void oneFailedOriginalBadgeAdvancesCursorAndDoesNotBlockNextPage() {
        RoamBadgeDeliveryService worker = spy(service);
        Map<String, Object> cursor = new HashMap<>(); cursor.put("afterId", 0L); cursor.put("throughId", 2L);
        when(intents.selectCursorForUpdate()).thenReturn(cursor);
        Map<String, Object> first = new HashMap<>(); first.put("id", 1L); first.put("memberId", 77L); first.put("sessionId", 9L);
        first.put("badgeCode", "ROAM_TILES_50"); first.put("sourceId", 50L);
        Map<String, Object> second = new HashMap<>(first); second.put("id", 2L); second.put("badgeCode", "ROAM_SHOP_STREAK"); second.put("sourceId", 0L);
        when(intents.scanPending(0L, 2L, 100)).thenReturn(Collections.singletonList(first));
        when(intents.scanPending(1L, 2L, 100)).thenReturn(Collections.singletonList(second));
        when(intents.advanceCursor(anyLong(), anyLong(), eq(2L))).thenReturn(1);
        doThrow(new IllegalStateException("unavailable")).when(worker).deliver(77L, 9L, "ROAM_TILES_50", 50L);
        doReturn(true).when(worker).deliver(77L, 9L, "ROAM_SHOP_STREAK", 0L);
        worker.reconcile(); verify(worker).deliver(77L, 9L, "ROAM_SHOP_STREAK", 0L); verify(intents).completeRound(2L, 2L);
    }
}
