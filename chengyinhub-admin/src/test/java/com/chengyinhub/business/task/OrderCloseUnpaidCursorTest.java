package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICmsRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderCloseUnpaidCursorTest {
    private ICmsRegistrationService service;
    private OrderCloseTask task;
    @BeforeEach void setup() {
        service = mock(ICmsRegistrationService.class);
        task = new OrderCloseTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", service);
    }
    @Test void singleFailureDoesNotStarveLaterPagesAndFixedMaxExcludesNewRows() {
        when(service.getExpiredUnpaidScanUpperBound(any())).thenReturn(401L);
        when(service.listExpiredUnpaidIdsAfter(any(), anyLong(), eq(401L), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(1); int limit = call.getArgument(3);
            return LongStream.rangeClosed(after + 1, Math.min(401, after + limit)).boxed().collect(Collectors.toList());
        });
        when(service.closeExpiredOne(5L)).thenThrow(new IllegalStateException("one order unavailable"));
        task.closeExpired();
        verify(service).closeExpiredOne(401L);
        verify(service, never()).closeExpiredOne(402L);
        verify(service, times(1)).getExpiredUnpaidScanUpperBound(any());
        assertEquals(0L, ReflectionTestUtils.getField(task, "unpaidCursor"));
    }
    @Test void boundedRoundResumesPastPermanentFailurePrefix() {
        when(service.getExpiredUnpaidScanUpperBound(any())).thenReturn(600L);
        when(service.listExpiredUnpaidIdsAfter(any(), anyLong(), eq(600L), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(1); int limit = call.getArgument(3);
            return LongStream.rangeClosed(after + 1, Math.min(600, after + limit)).boxed().collect(Collectors.toList());
        });
        when(service.closeExpiredOne(anyLong())).thenThrow(new IllegalStateException("retry later"));
        task.closeExpired();
        assertEquals(500L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        verify(service, never()).closeExpiredOne(501L);
        task.closeExpired();
        verify(service).closeExpiredOne(600L);
        assertEquals(0L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        verify(service, times(1)).closeExpiredOne(1L);
    }
    @Test void pageQueryFailureRetainsAttemptedProgress() {
        when(service.getExpiredUnpaidScanUpperBound(any())).thenReturn(300L);
        when(service.listExpiredUnpaidIdsAfter(any(), eq(0L), eq(300L), eq(200)))
            .thenReturn(LongStream.rangeClosed(1, 200).boxed().collect(Collectors.toList()));
        when(service.listExpiredUnpaidIdsAfter(any(), eq(200L), eq(300L), eq(200)))
            .thenThrow(new IllegalStateException("page unavailable"));
        assertThrows(IllegalStateException.class, task::closeExpired);
        assertEquals(200L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        when(service.listExpiredUnpaidIdsAfter(any(), eq(200L), eq(300L), eq(200))).thenReturn(Collections.singletonList(300L));
        task.closeExpired();
        verify(service, times(1)).closeExpiredOne(1L);
        verify(service).closeExpiredOne(300L);
    }
    @Test void emptyRoundResetsCursorForNewlyExpiredSmallIds() {
        ReflectionTestUtils.setField(task, "unpaidCursor", 100L);
        when(service.getExpiredUnpaidScanUpperBound(any())).thenReturn(null);
        task.closeExpired();
        assertEquals(0L, ReflectionTestUtils.getField(task, "unpaidCursor"));
        verify(service, never()).closeExpiredOne(anyLong());
    }
}
