package com.chengyinhub.quartz.util;

import java.util.HashMap;
import java.util.Map;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.quartz.domain.SysJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuartzJobCorrelationTest {
    @AfterEach void clear() { MDC.clear(); }

    @Test void independentRunIdsSurviveForAlarmButNeverLeakIntoReusedWorkerOrJobData() {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        SysJob job = new SysJob(); job.setJobId(99L);
        data.put(ScheduleConstants.TASK_PROPERTIES, job);
        when(context.getMergedJobDataMap()).thenReturn(data);
        Map<String, Object> local = new HashMap<>();
        doAnswer(call -> { local.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(context).put(anyString(), any());
        when(context.get(any())).thenAnswer(call -> local.get(call.getArgument(0)));
        MDC.put("requestId", "previous_123"); MDC.put("commandId", "previous-command");
        MDC.put("Authorization", "private-header-fixture");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String first;
        try (var scope = QuartzJobCorrelation.open(context)) {
            first = QuartzJobCorrelation.runId(context);
            assertTrue(first.matches("[0-9a-f]{32}"));
            assertEquals(Map.of("requestId", first, "jobRunId", first), MDC.getCopyOfContextMap());
        }
        assertEquals(first, QuartzJobCorrelation.runId(context), "alarm callback can join after MDC closes");
        assertEquals(previous, MDC.getCopyOfContextMap());
        var failure = new IllegalStateException("private-payload-fixture");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> {
            try (var scope = QuartzJobCorrelation.open(context)) {
                assertNotEquals(first, QuartzJobCorrelation.runId(context));
                throw failure;
            }
        }));
        assertEquals(previous, MDC.getCopyOfContextMap());
        assertEquals(1, data.size());
        assertSame(job, data.get(ScheduleConstants.TASK_PROPERTIES));
        assertFalse(data.containsKey(QuartzJobCorrelation.RUN_ATTRIBUTE));
    }

    @Test void untrustedOrAbsentContextValueIsNotAnInventedRunId() {
        JobExecutionContext context = mock(JobExecutionContext.class);
        assertEquals("-", QuartzJobCorrelation.runId(context));
        when(context.get(QuartzJobCorrelation.RUN_ATTRIBUTE)).thenReturn("private\r\nforged-record");
        assertEquals("-", QuartzJobCorrelation.runId(context));
        Object untrusted = new Object() {
            @Override public String toString() { throw new AssertionError("must not stringify context payload"); }
        };
        when(context.get(QuartzJobCorrelation.RUN_ATTRIBUTE)).thenReturn(untrusted);
        assertEquals("-", QuartzJobCorrelation.runId(context));
    }
}
