package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IDataRetentionService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataRetentionCleanupTaskFailureTest {
    /**
     * F-L4-12 要求:失败上报给调度(不记 SUCCESS)且告警;一步失败不得跳过其余合规删除步骤
     * (此前版本断言「失败后不再执行后续步骤」,正是问题总表指出的缺陷,口径随需求更新)。
     */
    @Test
    void failureReachesSchedulerAndAlarmButLaterStepsStillRun() {
        IDataRetentionService service = mock(IDataRetentionService.class);
        AlarmService alarm = mock(AlarmService.class);
        IllegalStateException failure = new IllegalStateException("cleanup unavailable");
        when(service.cleanBrowseRecords(any(), anyInt(), anyInt())).thenThrow(failure);
        DataRetentionCleanupTask task = new DataRetentionCleanupTask();
        ReflectionTestUtils.setField(task, "dataRetentionService", service);
        ReflectionTestUtils.setField(task, "alarmService", alarm);
        ReflectionTestUtils.setField(task, "batchSize", 1000);
        ReflectionTestUtils.setField(task, "maxBatches", 500);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, task::run);
        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains("browse"));
        verify(service).cleanBizEventLogs(any(), anyInt(), anyInt());
        verify(service).cleanNpcChatLogs(any(), anyInt(), anyInt());
        verify(service).cleanNpcChatRequests(any(), anyInt(), anyInt());
        verify(service).cleanRoamLocations(any(), anyInt(), anyInt());
        verify(service).cleanAiGenerationLogs(any(), anyInt(), anyInt());
        verify(alarm).alarmThrottled(eq("job.data_retention.failed"), anyString(), contains("browse"));
    }

    @Test
    void successfulRunDoesNotAlarm() {
        IDataRetentionService service = mock(IDataRetentionService.class);
        AlarmService alarm = mock(AlarmService.class);
        DataRetentionCleanupTask task = new DataRetentionCleanupTask();
        ReflectionTestUtils.setField(task, "dataRetentionService", service);
        ReflectionTestUtils.setField(task, "alarmService", alarm);
        assertDoesNotThrow(task::run);
        verifyNoInteractions(alarm);
    }

    @Test
    void aiGenerationLogsUseConfiguredRetentionDaysDefault180() {
        IDataRetentionService service = mock(IDataRetentionService.class);
        DataRetentionCleanupTask task = new DataRetentionCleanupTask();
        ReflectionTestUtils.setField(task, "dataRetentionService", service);
        ReflectionTestUtils.setField(task, "batchSize", 1000);
        ReflectionTestUtils.setField(task, "maxBatches", 500);
        long before = System.currentTimeMillis();
        task.run();
        org.mockito.ArgumentCaptor<java.util.Date> cutoff = org.mockito.ArgumentCaptor.forClass(java.util.Date.class);
        verify(service).cleanAiGenerationLogs(cutoff.capture(), eq(1000), eq(500));
        long days = (before - cutoff.getValue().getTime() + 60_000L) / (24L * 60 * 60 * 1000);
        assertEquals(180, days);

        ReflectionTestUtils.setField(task, "aiGenerationDays", 30);
        clearInvocations(service);
        task.run();
        verify(service).cleanAiGenerationLogs(cutoff.capture(), anyInt(), anyInt());
        assertEquals(30, (System.currentTimeMillis() - cutoff.getValue().getTime() + 60_000L) / (24L * 60 * 60 * 1000));
    }

    @Test
    void nonPositiveAiGenerationRetentionSkipsDeletion() {
        IDataRetentionService service = mock(IDataRetentionService.class);
        DataRetentionCleanupTask task = new DataRetentionCleanupTask();
        ReflectionTestUtils.setField(task, "dataRetentionService", service);
        ReflectionTestUtils.setField(task, "aiGenerationDays", 0);
        task.run();
        verify(service, never()).cleanAiGenerationLogs(any(), anyInt(), anyInt());
    }
}
