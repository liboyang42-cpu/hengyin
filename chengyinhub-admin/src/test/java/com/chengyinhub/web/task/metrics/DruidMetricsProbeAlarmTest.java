package com.chengyinhub.web.task.metrics;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DruidMetricsProbeAlarmTest {
    @Test
    void waitingPoolAlertsAndKeepsOriginalMetrics() {
        DruidMetricsProbe probe = new DruidMetricsProbe();
        DruidDataSource pool = mock(DruidDataSource.class);
        AlarmService alarms = mock(AlarmService.class);
        ReflectionTestUtils.setField(probe, "masterDataSource", pool);
        ReflectionTestUtils.setField(probe, "alarmService", alarms);
        when(pool.getActiveCount()).thenReturn(20); when(pool.getMaxActive()).thenReturn(20);
        when(pool.getWaitThreadCount()).thenReturn(3);
        assertEquals(4, probe.sample(new Date(0), new Date(1000), new Date(1000)).size());
        verify(alarms).alarmThrottled("runtime.db.pool.waiting", "数据库连接池等待", "active=20 max=20 waiting=3");
    }

    @Test
    void healthyPoolDoesNotAlert() {
        DruidMetricsProbe probe = new DruidMetricsProbe();
        AlarmService alarms = mock(AlarmService.class);
        ReflectionTestUtils.setField(probe, "masterDataSource", mock(DruidDataSource.class));
        ReflectionTestUtils.setField(probe, "alarmService", alarms);
        probe.sample(new Date(0), new Date(1000), new Date(1000));
        verifyNoInteractions(alarms);
    }
}
