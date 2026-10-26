package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IFreeExploreBundleRefundService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationRefundFulfillmentRecoveryTest {
    @Test
    void consumesExactOriginalIdsAndOnlyWritesRecoveryMetadata() {
        RefundApplicationMapper mapper = mapper();
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        when(mapper.selectRegistrationRefundRecoveryUpperBound()).thenReturn(9007199254740993L);
        when(mapper.selectRegistrationRefundRecoveryBatch(eq(9007199254740993L), eq(200), anyList()))
                .thenReturn(List.of(7L, 8L, 9007199254740993L));
        recovery(mapper, sync).reconcile();
        for (Long id : List.of(7L, 8L, 9007199254740993L)) {
            verify(mapper).markRegistrationRefundRecoveryAttempt(id); verify(sync).sync(id);
        }
        verifyNoMoreInteractions(sync);
        verify(mapper).selectRegistrationRefundRecoveryUpperBound();
        verify(mapper).selectRegistrationRefundRecoveryBatch(eq(9007199254740993L), eq(200), anyList());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void failedOriginalIdIsRetriedWithoutBlockingOtherIds() {
        RefundApplicationMapper mapper = singlePage();
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        doThrow(new IllegalStateException("F3 unavailable")).doNothing().when(sync).sync(1L);
        RegistrationRefundFulfillmentRecovery recovery = recovery(mapper, sync);
        assertDoesNotThrow(recovery::reconcile); verify(sync).sync(2L);
        recovery.reconcile(); verify(sync, times(2)).sync(1L); verify(sync, times(2)).sync(2L);
    }

    @Test
    void processRestartStillReadsOriginalDurableRows() {
        RefundApplicationMapper mapper = singlePage();
        RegistrationRefundFulfillmentSyncService failed = mock(RegistrationRefundFulfillmentSyncService.class);
        doThrow(new IllegalStateException("F3 unavailable")).when(failed).sync(anyLong());
        recovery(mapper, failed).reconcile();
        RegistrationRefundFulfillmentSyncService restarted = mock(RegistrationRefundFulfillmentSyncService.class);
        recovery(mapper, restarted).reconcile(); verify(restarted).sync(1L); verify(restarted).sync(2L);
    }

    @Test
    void moreThanBudgetSingleFailureAndRestartReach1001AndRetryFailedOriginalId() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        Map<Long, Long> durableQueueOrder = new TreeMap<>();
        LongStream.rangeClosed(1, 1201).forEach(id -> durableQueueOrder.put(id, 0L));
        AtomicLong clock = new AtomicLong();
        when(mapper.selectRegistrationRefundRecoveryUpperBound()).thenReturn(1201L);
        when(mapper.selectRegistrationRefundRecoveryBatch(anyLong(), anyInt(), anyList())).thenAnswer(call -> {
            long upper = call.getArgument(0); int limit = call.getArgument(1);
            Set<Long> excluded = new HashSet<>(call.<List<Long>>getArgument(2));
            return durableQueueOrder.keySet().stream().filter(id -> id <= upper && !excluded.contains(id))
                    .sorted(Comparator.<Long>comparingLong(durableQueueOrder::get).thenComparingLong(Long::longValue))
                    .limit(limit).collect(Collectors.toList());
        });
        when(mapper.markRegistrationRefundRecoveryAttempt(anyLong())).thenAnswer(call -> {
            durableQueueOrder.put(call.getArgument(0), clock.incrementAndGet()); return 1;
        });
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        doThrow(new IllegalStateException("one F3 failure")).doNothing().when(sync).sync(7L);
        recovery(mapper, sync).reconcile();
        verify(sync, times(1000)).sync(anyLong()); verify(sync, never()).sync(1001L);
        // Old queue times are durable; arrivals after this sweep must not preempt the failed old ID forever.
        durableQueueOrder.put(1202L, clock.incrementAndGet());
        when(mapper.selectRegistrationRefundRecoveryUpperBound()).thenReturn(1202L);
        // Drop the entire process-local recovery object; only persisted queue ordering survives.
        recovery(mapper, sync).reconcile();
        verify(sync).sync(1001L); verify(sync).sync(1201L); verify(sync, times(2)).sync(7L);
        assertTrue(LongStream.rangeClosed(1, 1201).allMatch(id -> durableQueueOrder.get(id) > 0));
        verify(sync, times(2000)).sync(anyLong());
    }

    @Test
    void unknownBatchIsNotMarkedAsCompleted() {
        RefundApplicationMapper mapper = mapper();
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        when(mapper.selectRegistrationRefundRecoveryUpperBound()).thenReturn(1L);
        when(mapper.selectRegistrationRefundRecoveryBatch(eq(1L), eq(200), anyList()))
                .thenReturn(null, Collections.singletonList(1L));
        RegistrationRefundFulfillmentRecovery recovery = recovery(mapper, sync);
        assertThrows(IllegalStateException.class, recovery::reconcile); verifyNoInteractions(sync);
        recovery.reconcile(); verify(sync).sync(1L);
    }

    @Test
    void rejectsOutOfRangeIdBeforeCallingF3() {
        RefundApplicationMapper mapper = mapper();
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        when(mapper.selectRegistrationRefundRecoveryUpperBound()).thenReturn(1L);
        when(mapper.selectRegistrationRefundRecoveryBatch(eq(1L), eq(200), anyList())).thenReturn(List.of(2L));
        assertThrows(IllegalStateException.class, recovery(mapper, sync)::reconcile); verifyNoInteractions(sync);
    }

    @Test
    void metadataFailureIsReportedBeforePretendingProgressOrCallingF3() {
        RefundApplicationMapper mapper = singlePage();
        doThrow(new IllegalStateException("database unavailable")).when(mapper).markRegistrationRefundRecoveryAttempt(1L);
        RegistrationRefundFulfillmentSyncService sync = mock(RegistrationRefundFulfillmentSyncService.class);
        assertThrows(IllegalStateException.class, recovery(mapper, sync)::reconcile); verifyNoInteractions(sync);
    }

    @Test
    void syncAdapterPassesOnlyOriginalIdToProvidedContract() {
        IFreeExploreBundleRefundService f3 = mock(IFreeExploreBundleRefundService.class);
        RegistrationRefundFulfillmentSyncService sync = new RegistrationRefundFulfillmentSyncService();
        ReflectionTestUtils.setField(sync, "freeExploreBundleRefundService", f3);
        sync.sync(9007199254740993L); verify(f3).syncRefundOutcome(9007199254740993L); verifyNoMoreInteractions(f3);
    }

    @Test
    void syncAdapterRejectsInvalidIdentityWithoutCallingF3() {
        IFreeExploreBundleRefundService f3 = mock(IFreeExploreBundleRefundService.class);
        RegistrationRefundFulfillmentSyncService sync = new RegistrationRefundFulfillmentSyncService();
        ReflectionTestUtils.setField(sync, "freeExploreBundleRefundService", f3);
        assertThrows(IllegalArgumentException.class, () -> sync.sync(null));
        assertThrows(IllegalArgumentException.class, () -> sync.sync(0L));
        assertThrows(IllegalArgumentException.class, () -> sync.sync(-1L)); verifyNoInteractions(f3);
    }

    private RefundApplicationMapper mapper() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.markRegistrationRefundRecoveryAttempt(anyLong())).thenReturn(1); return mapper;
    }
    private RefundApplicationMapper singlePage() {
        RefundApplicationMapper mapper = mapper();
        when(mapper.selectRegistrationRefundRecoveryUpperBound()).thenReturn(2L);
        when(mapper.selectRegistrationRefundRecoveryBatch(eq(2L), eq(200), anyList())).thenReturn(List.of(1L, 2L));
        return mapper;
    }
    private RegistrationRefundFulfillmentRecovery recovery(RefundApplicationMapper mapper, RegistrationRefundFulfillmentSyncService sync) {
        RegistrationRefundFulfillmentRecovery recovery = new RegistrationRefundFulfillmentRecovery();
        ReflectionTestUtils.setField(recovery, "refundApplicationMapper", mapper);
        ReflectionTestUtils.setField(recovery, "fulfillmentSyncService", sync); return recovery;
    }
}
