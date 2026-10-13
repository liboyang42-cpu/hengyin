package com.chengyinhub.business.task;

import java.util.Arrays;
import java.util.Collections;
import com.chengyinhub.business.mapper.ImNotificationIntentMapper;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

class ImNotificationDeliveryTaskTest {
    @Test void failureDoesNotStarveLaterPagesAndNewIdsStayOutsideSnapshot() {
        ImNotificationIntentMapper mapper = mock(ImNotificationIntentMapper.class);
        IImService messages = mock(IImService.class); ImNotificationDeliveryTask task = new ImNotificationDeliveryTask();
        ReflectionTestUtils.setField(task, "mapper", mapper); ReflectionTestUtils.setField(task, "imService", messages);
        when(mapper.maxPendingId()).thenReturn(150L);
        when(mapper.selectPendingPage(0L, 150L)).thenReturn(Arrays.asList(1L,2L,100L));
        when(mapper.selectPendingPage(100L,150L)).thenReturn(Arrays.asList(149L,150L));
        when(messages.deliverSystemNotification(2L)).thenThrow(new RuntimeException("unavailable"));
        task.deliverPending();
        verify(messages).deliverSystemNotification(1L); verify(messages).deliverSystemNotification(2L);
        verify(messages).deliverSystemNotification(100L); verify(messages).deliverSystemNotification(149L);
        verify(messages).deliverSystemNotification(150L); verify(messages, never()).deliverSystemNotification(151L);
    }
    @Test void emptyOrNonAdvancingPageStopsWithoutRepeatedDelivery() {
        ImNotificationIntentMapper mapper = mock(ImNotificationIntentMapper.class);
        IImService messages = mock(IImService.class); ImNotificationDeliveryTask task = new ImNotificationDeliveryTask();
        ReflectionTestUtils.setField(task, "mapper", mapper); ReflectionTestUtils.setField(task, "imService", messages);
        when(mapper.maxPendingId()).thenReturn(20L);
        when(mapper.selectPendingPage(0L,20L)).thenReturn(Collections.singletonList(0L));
        task.deliverPending(); verifyNoInteractions(messages);
    }
}
