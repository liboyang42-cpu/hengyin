package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Producer decisions only: manual synchronization is not a JDBC commit/rollback test. */
class MerchantOfferOwnerNoticeIntentTest {
    private MerchantOfferServiceImpl target;
    private ISystemNotificationIntentService intents;
    private IImService im;
    private WxSubscribeChannel wx;
    private ScheduledExecutorService executor;
    private CommittedBusinessNotice notices;
    private final List<Runnable> wxQueue = new ArrayList<>();

    @BeforeEach void setup() {
        target = new MerchantOfferServiceImpl();
        intents = mock(ISystemNotificationIntentService.class);
        im = mock(IImService.class);
        wx = mock(WxSubscribeChannel.class);
        executor = mock(ScheduledExecutorService.class);
        notices = mock(CommittedBusinessNotice.class);
        ReflectionTestUtils.setField(target, "systemNotificationIntents", intents);
        ReflectionTestUtils.setField(target, "imService", im);
        ReflectionTestUtils.setField(target, "ownerNoticeWxChannel", wx);
        ReflectionTestUtils.setField(target, "ownerNoticeExecutor", executor);
        ReflectionTestUtils.setField(target, "businessNotice", notices);
        when(intents.findIntentId(anyString())).thenReturn(null);
        when(intents.enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString())).thenReturn(501L);
        when(im.deliverSystemNotification(501L)).thenReturn(701L);
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            wxQueue.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        doAnswer(call -> { ((Supplier<?>) call.getArgument(2)).get(); return null; })
                .when(notices).dispatchInNewTransaction(anyString(), any(), any());
    }

    @AfterEach void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    private void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    private void register(Long recipient, String title, String body) {
        ReflectionTestUtils.invokeMethod(target, "registerOwnerWithdrawalNotice", 9L, recipient, title, body);
    }
    private void callbacks() {
        for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void outsideOriginalTransactionRejectsBeforeLookupOrAnyAction() {
        assertThrows(IllegalStateException.class, () -> register(51L, "供给退出", "原正文"));
        verifyNoInteractions(intents, im, wx, executor, notices);
    }
    @Test void firstOriginalIntentIsEnqueuedBeforeCommitAndWakesOnlyItsId() {
        begin(); register(51L, "供给退出", "原正文");
        verify(intents).enqueue(51L, ImMessage.MSG_TEXT, "供给退出\n原正文", null, "chapter-supply-withdraw-owner:9");
        verifyNoInteractions(im, wx, executor, notices);
        callbacks();
        verify(im).deliverSystemNotification(501L);
        verify(im, never()).sendSystem(any(), any(), any(), any());
        verify(notices, never()).sendAfterCommit(any(), any(), any(), any(), any());
        assertEquals(1, wxQueue.size());
        verifyNoInteractions(wx);
    }
    @Test void existingIdentityIsWokenWithoutRebuildingLivePayloadOrRetryingWx() {
        when(intents.findIntentId("chapter-supply-withdraw-owner:9")).thenReturn(501L);
        begin(); register(null, null, null); callbacks();
        verify(im).deliverSystemNotification(501L);
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
        verifyNoInteractions(wx, executor);
    }
    @Test void missingOrInvalidIntentReceiptCannotRegisterCallbacks() {
        when(intents.enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString())).thenReturn(null, 0L);
        begin();
        assertThrows(IllegalStateException.class, () -> register(51L, "供给退出", "原正文"));
        assertThrows(IllegalStateException.class, () -> register(51L, "供给退出", "原正文"));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(im, wx, executor, notices);
    }
    @Test void invalidOriginalRecipientOrContentCannotEnqueueOrWake() {
        begin();
        assertThrows(IllegalStateException.class, () -> register(0L, "供给退出", "原正文"));
        assertThrows(IllegalStateException.class, () -> register(51L, null, "原正文"));
        verify(intents, never()).enqueue(any(), any(), any(), any(), any());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(im, wx, executor, notices);
    }
    @Test void intentConflictCannotBecomeAcceptedWake() {
        when(intents.enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString()))
                .thenThrow(new IllegalStateException("原内容冲突"));
        begin(); assertThrows(IllegalStateException.class, () -> register(51L, "供给退出", "原正文"));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(im, wx, executor, notices);
    }
    @Test void wxFalseIsOneOutsideTransactionAttemptAndCannotRepeatIm() {
        when(wx.send(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            NotifyMessage original = call.getArgument(0);
            assertEquals(51L, original.memberId());
            assertEquals("供给退出", original.get("title")); assertEquals("原正文", original.get("sub"));
            return false;
        });
        begin(); register(51L, "供给退出", "原正文"); callbacks(); wxQueue.forEach(Runnable::run);
        verify(wx, times(1)).send(any());
        verify(im, times(1)).deliverSystemNotification(501L);
        assertEquals(1, wxQueue.size(), "UNKNOWN must not schedule another attempt");
    }
    @Test void rejectedWxQueueDoesNotUndoTheOriginalImWake() {
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RejectedExecutionException("fixture stopped"));
        begin(); register(51L, "供给退出", "原正文"); assertDoesNotThrow(this::callbacks);
        verify(im).deliverSystemNotification(501L);
        verifyNoInteractions(wx);
    }
    @Test void wxChannelExceptionIsUnknownAndCannotCauseAnotherAttemptOrIm() {
        when(wx.send(any())).thenThrow(new IllegalStateException("fixture lost external response"));
        begin(); register(51L, "供给退出", "原正文"); callbacks();
        assertDoesNotThrow(() -> wxQueue.forEach(Runnable::run));
        verify(wx, times(1)).send(any()); verify(im, times(1)).deliverSystemNotification(501L);
        assertEquals(1, wxQueue.size());
    }
    @Test void wronglyInlineExecutorCannotCallWxInsideDatabaseTransaction() {
        begin(); register(51L, "供给退出", "原正文"); callbacks();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        wxQueue.forEach(Runnable::run);
        verifyNoInteractions(wx);
    }
    @Test void repeatedHelperReusesIdButDoesNotScheduleAnotherWxAttempt() {
        when(intents.findIntentId("chapter-supply-withdraw-owner:9")).thenReturn(null, 501L);
        begin(); register(51L, "供给退出", "原正文"); register(99L, "已改变", "已改变"); callbacks();
        verify(intents, times(1)).enqueue(any(), any(), any(), any(), any());
        verify(im, times(2)).deliverSystemNotification(501L); // consumer owns receipt idempotency
        assertEquals(1, wxQueue.size());
    }
}
