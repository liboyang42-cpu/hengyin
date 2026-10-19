package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCloseUnpaidPagingTaskTest {
    private OrderCloseTask task(ICmsRegistrationService service, IWechatPayService wechat) {
        OrderCloseTask task = new OrderCloseTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", service);
        ReflectionTestUtils.setField(task, "wechatPayService", wechat);
        when(service.registrationScanUpperBound()).thenReturn(Long.MAX_VALUE);
        return task;
    }

    @Test void boundedScanResumesPastFailedPrefixThenWrapsAndRestartPreservesIdempotency() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        List<Long> remaining = new ArrayList<>();
        for (long id = 1; id <= 501; id++) remaining.add(id);
        when(service.listExpiredUnpaidIds(anyLong())).thenAnswer(call -> {
            long after = call.getArgument(0);
            return remaining.stream().filter(id -> id > after).limit(200).collect(Collectors.toList());
        });
        AtomicInteger attempts = new AtomicInteger(), released = new AtomicInteger();
        when(service.closeExpiredOne(anyLong())).thenAnswer(call -> {
            attempts.incrementAndGet();
            long id = call.getArgument(0);
            if (id == 501) { remaining.remove(Long.valueOf(id)); released.incrementAndGet(); return true; }
            return false;
        });
        OrderCloseTask first = task(service, wechat);
        first.closeExpired();
        assertEquals(500, attempts.get()); assertEquals(0, released.get());
        first.closeExpired();
        assertEquals(501, attempts.get()); assertEquals(1, released.get());
        first.closeExpired();
        assertEquals(1001, attempts.get()); assertEquals(1, released.get());
        task(service, wechat).closeExpired();
        assertEquals(1501, attempts.get()); assertEquals(1, released.get());
        verify(service, times(1)).closeExpiredOne(501L);
        verifyNoInteractions(wechat);
    }

    @Test void successfulRemovalsDoNotSkipTheNextPage() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        List<Long> remaining = new ArrayList<>();
        for (long id = 1; id <= 401; id++) remaining.add(id);
        when(service.listExpiredUnpaidIds(anyLong())).thenAnswer(call -> {
            long after = call.getArgument(0);
            return remaining.stream().filter(id -> id > after).limit(200).collect(Collectors.toList());
        });
        when(service.closeExpiredOne(anyLong())).thenAnswer(call -> remaining.remove((Long) call.getArgument(0)));
        task(service, mock(IWechatPayService.class)).closeExpired();
        assertTrue(remaining.isEmpty()); verify(service, times(401)).closeExpiredOne(anyLong());
    }

    @Test void fixedUpperBoundRevisitsFailuresEvenWhenNewOrdersKeepArriving() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        OrderCloseTask task = task(service, mock(IWechatPayService.class));
        when(service.registrationScanUpperBound()).thenReturn(501L, 1001L);
        when(service.listExpiredUnpaidIds(anyLong())).thenAnswer(call -> {
            long after = call.getArgument(0);
            return java.util.stream.LongStream.rangeClosed(after + 1, after + 200)
                    .boxed().collect(Collectors.toList());
        });
        task.closeExpired();
        task.closeExpired();
        verify(service, times(1)).closeExpiredOne(1L);
        verify(service, times(1)).closeExpiredOne(501L);
        verify(service, never()).closeExpiredOne(502L);
        task.closeExpired();
        verify(service, times(2)).closeExpiredOne(1L);
        verify(service, times(2)).registrationScanUpperBound();
    }

    @Test void pageQueryFailureKeepsCompletedPrefixAndResumesAtNextPage() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        OrderCloseTask task = task(service, wechat);
        when(service.registrationScanUpperBound()).thenReturn(201L);
        List<Long> firstPage = java.util.stream.LongStream.rangeClosed(1, 200)
                .boxed().collect(Collectors.toList());
        when(service.listExpiredUnpaidIds(0L)).thenReturn(firstPage);
        when(service.listExpiredUnpaidIds(200L))
                .thenThrow(new IllegalStateException("temporary query failure"))
                .thenThrow(new IllegalStateException("temporary query failure"))
                .thenReturn(Collections.singletonList(201L));
        when(service.listExpiredUnpaidIds(201L)).thenReturn(Collections.emptyList());
        when(service.closeExpiredOne(anyLong())).thenAnswer(call -> {
            if ((Long) call.getArgument(0) <= 200L) throw new IllegalStateException("row failure");
            return true;
        });

        assertThrows(IllegalStateException.class, task::closeExpired);
        assertEquals(200L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        assertThrows(IllegalStateException.class, task::closeExpired);
        assertEquals(200L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        task.closeExpired();

        verify(service, times(1)).listExpiredUnpaidIds(0L);
        verify(service, times(1)).closeExpiredOne(1L);
        verify(service, times(1)).closeExpiredOne(200L);
        verify(service, times(1)).closeExpiredOne(201L);
        verify(service, times(1)).registrationScanUpperBound();
        assertEquals(0L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        verifyNoInteractions(wechat);
    }

    @Test void nonAdvancingPageFailsInsteadOfRepeatingTheSameOrders() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnpaidIds(anyLong())).thenReturn(Collections.singletonList(1L));
        assertThrows(IllegalStateException.class, () -> task(service, mock(IWechatPayService.class)).closeExpired());
        verify(service, times(1)).closeExpiredOne(1L);
    }
}
