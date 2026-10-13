package com.chengyinhub.business.task;

import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核积压监控单测:积压超阈值 / 有超期 → 告警;都正常 → 不告警。
 */
class AuditBacklogMonitorTaskTest {

    private AuditTaskMapper mapper;
    private AlarmService alarm;
    private AuditBacklogMonitorTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditTaskMapper.class);
        alarm = mock(AlarmService.class);
        task = new AuditBacklogMonitorTask();
        ReflectionTestUtils.setField(task, "auditTaskMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarm);
        ReflectionTestUtils.setField(task, "slaHours", 24);
        ReflectionTestUtils.setField(task, "backlogThreshold", 50);
    }

    @Test
    void backlogOverThreshold_alarms() {
        when(mapper.countPendingBacklog(any())).thenReturn(60); // > 50
        when(mapper.countPendingOverdue(any())).thenReturn(0);

        task.run();

        verify(alarm, times(1)).alarm(anyString(), anyString());
    }

    @Test
    void hasOverdue_alarms_evenIfBacklogUnderThreshold() {
        when(mapper.countPendingBacklog(any())).thenReturn(3);  // < 50
        when(mapper.countPendingOverdue(any())).thenReturn(2);  // 有超期

        task.run();

        verify(alarm, times(1)).alarm(anyString(), anyString());
    }

    @Test
    void allHealthy_noAlarm() {
        when(mapper.countPendingBacklog(any())).thenReturn(10); // < 50
        when(mapper.countPendingOverdue(any())).thenReturn(0);

        task.run();

        verify(alarm, never()).alarm(anyString(), anyString());
    }
}
