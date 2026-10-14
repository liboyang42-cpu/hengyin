package com.chengyinhub.web.task;

import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorJobRegistrationCheckTest {
    private static SysJob job(String target, String status) {
        SysJob job = new SysJob();
        job.setInvokeTarget(target);
        job.setStatus(status);
        return job;
    }

    @Test
    void allRegisteredDoesNotAlarmEvenWhenSomeArePausedDeliberately() {
        AlarmService alarms = mock(AlarmService.class);
        List<SysJob> jobs = new ArrayList<>();
        for (String target : MonitorJobRegistrationCheck.REQUIRED_MONITOR_TARGETS) jobs.add(job(target, "0"));
        jobs.set(3, job("settlementReconciliationTask.run()", "1"));
        List<String> missing = new MonitorJobRegistrationCheck(mock(SysJobMapper.class), alarms).check(jobs);
        assertTrue(missing.isEmpty());
        verifyNoInteractions(alarms);
    }

    @Test
    void missingTargetsAlarmWithExactTargetsOnly() {
        AlarmService alarms = mock(AlarmService.class);
        List<SysJob> jobs = List.of(job("metricsProbeTask.run()", "0"), job(" auditBacklogMonitorTask.run() ", "0"),
                job("someOtherTask.run()", "0"));
        List<String> missing = new MonitorJobRegistrationCheck(mock(SysJobMapper.class), alarms).check(jobs);
        assertEquals(5, missing.size());
        assertFalse(missing.contains("auditBacklogMonitorTask.run()"));
        verify(alarms).alarmThrottled(eq("monitor.jobs.missing"), eq("巡检任务未登记"),
                contains("withdrawReconciliationTask.run(),settlementReconciliationTask.run()"));
    }

    @Test
    void lookupFailureNeverBlocksStartup() {
        SysJobMapper mapper = mock(SysJobMapper.class);
        when(mapper.selectJobAll()).thenThrow(new IllegalStateException("db down"));
        AlarmService alarms = mock(AlarmService.class);
        assertDoesNotThrow(() -> new MonitorJobRegistrationCheck(mapper, alarms).onReady());
        verifyNoInteractions(alarms);
    }
}
