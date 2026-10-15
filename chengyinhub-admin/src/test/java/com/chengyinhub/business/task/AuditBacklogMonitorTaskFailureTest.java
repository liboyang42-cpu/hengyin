package com.chengyinhub.business.task;

import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditBacklogMonitorTaskFailureTest {
    @Test
    void databaseFailureReachesQuartzInsteadOfBeingRecordedAsSuccess() {
        AuditTaskMapper mapper = mock(AuditTaskMapper.class);
        AlarmService alarm = mock(AlarmService.class);
        AuditBacklogMonitorTask task = task(mapper, alarm);
        RuntimeException failure = new IllegalStateException("fixture database failure");
        when(mapper.countPendingBacklog(any(Date.class))).thenThrow(failure);
        IllegalStateException observed = assertThrows(IllegalStateException.class, task::run);
        assertSame(failure, observed.getCause());
        verify(mapper, never()).countPendingOverdue(any(Date.class));
        verifyNoInteractions(alarm);
    }

    @Test
    void noBacklogCompletesWithoutAlarmWhileAlarmFailureReachesQuartz() {
        AuditTaskMapper mapper = mock(AuditTaskMapper.class);
        AlarmService alarm = mock(AlarmService.class);
        AuditBacklogMonitorTask task = task(mapper, alarm);
        assertDoesNotThrow(task::run);
        verifyNoInteractions(alarm);
        when(mapper.countPendingOverdue(any(Date.class))).thenReturn(1);
        RuntimeException failure = new IllegalStateException("fixture alarm failure");
        doThrow(failure).when(alarm).alarmThrottled(anyString(), anyString(), anyString());
        assertSame(failure, assertThrows(IllegalStateException.class, task::run).getCause());
    }

    private AuditBacklogMonitorTask task(AuditTaskMapper mapper, AlarmService alarm) {
        AuditBacklogMonitorTask task = new AuditBacklogMonitorTask();
        ReflectionTestUtils.setField(task, "auditTaskMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarm);
        ReflectionTestUtils.setField(task, "slaHours", 24);
        ReflectionTestUtils.setField(task, "backlogThreshold", 50);
        return task;
    }
}
