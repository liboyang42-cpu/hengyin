package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IDataRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataRetentionCleanupTaskFailureTest {
    @Test
    void failureReachesSchedulerInsteadOfBeingRecordedAsSuccess() {
        IDataRetentionService service = mock(IDataRetentionService.class);
        IllegalStateException failure = new IllegalStateException("cleanup unavailable");
        when(service.cleanBrowseRecords(any(), anyInt(), anyInt())).thenThrow(failure);
        DataRetentionCleanupTask task = new DataRetentionCleanupTask();
        ReflectionTestUtils.setField(task, "dataRetentionService", service);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, task::run);
        assertSame(failure, thrown.getCause());
        verify(service, never()).cleanBizEventLogs(any(), anyInt(), anyInt());
    }
}
