package com.chengyinhub.business.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.RoamPresenceRevokeIntent;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.RoamPresenceRevokeIntentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.task.RoamPresenceRevokeAuditTask;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.utils.LogCorrelation;
import com.chengyinhub.framework.web.trace.RequestIdFilter;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.util.QuartzJobCorrelation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Filter/scope/service/task, mock storage/transactions only. Not a MySQL or running Quartz test. */
class RoamAuditLogCorrelationTest {
    @AfterEach void clear() { MDC.clear(); }

    @Test void failedOriginalCommandIsJoinedToRecoveryRunWithoutLoggingLeaseOrPayload() throws Exception {
        String command = "presence-11111111-1111-1111-1111-111111111111";
        var intents = mock(RoamPresenceRevokeIntentMapper.class);
        var consents = mock(ConsentRecordMapper.class);
        var members = mock(UmsMemberMapper.class);
        var alarms = mock(AlarmService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        when(intents.claim(eq(command), anyString())).thenReturn(1);
        when(intents.fail(eq(command), anyString())).thenReturn(1);
        when(intents.complete(eq(command), anyString())).thenReturn(1);
        when(intents.selectDue(50)).thenReturn(List.of(command));
        var row = new RoamPresenceRevokeIntent(); row.setMemberId(9L); row.setRequestId(command);
        when(intents.selectByRequestId(command)).thenReturn(row);
        var consent = new ConsentRecord(); consent.setMemberId(9L); consent.setRequestId(command);
        consent.setDocType("privacy_policy"); consent.setDocVersion("roam-presence-v1");
        consent.setScene("roam_presence"); consent.setEventType("REVOKE");
        var failFirst = new AtomicBoolean(true);
        when(consents.selectByRequestId(9L, command)).thenAnswer(call -> {
            if (failFirst.getAndSet(false)) throw new IllegalStateException("private-storage-payload");
            return consent;
        });
        var service = new RoamPresenceRevokeAuditService(intents, consents, members, alarms, manager);
        Logger logger = (Logger) LoggerFactory.getLogger(RoamPresenceRevokeAuditService.class);
        var logs = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        logs.start(); logger.addAppender(logs);
        try {
            var task = new AtomicReference<Runnable>();
            var firstResult = new AtomicBoolean(true);
            var request = new MockHttpServletRequest("POST", "/api/roam/presence-visibility");
            request.addHeader(RequestIdFilter.HEADER, "http_original_123");
            new RequestIdFilter().doFilter(request, new MockHttpServletResponse(), (req, res) ->
                    task.set(LogCorrelation.wrap(() -> firstResult.set(service.deliver(command)))));
            assertNull(MDC.get("requestId"));
            task.get().run(); // Same wrapping contract used by the actual AsyncManager submission.
            assertFalse(firstResult.get());
            assertNull(MDC.get("commandId"));
            verify(alarms).alarm("位置分享撤回记录失败", "关闭事实已保存，撤回审计需恢复 commandId=" + command + " jobRunId=-");

            var restarted = new RoamPresenceRevokeAuditService(intents, consents, members, alarms, manager);
            JobExecutionContext context = mock(JobExecutionContext.class);
            JobDataMap data = new JobDataMap(); var job = new SysJob(); job.setJobId(77L);
            data.put(ScheduleConstants.TASK_PROPERTIES, job);
            when(context.getMergedJobDataMap()).thenReturn(data);
            Map<Object, Object> local = new HashMap<>();
            doAnswer(call -> { local.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(context).put(any(), any());
            when(context.get(any())).thenAnswer(call -> local.get(call.getArgument(0)));
            String run;
            try (var scope = QuartzJobCorrelation.open(context)) {
                run = QuartzJobCorrelation.runId(context);
                new RoamPresenceRevokeAuditTask(restarted).run();
                assertNull(MDC.get("commandId"));
                assertEquals(run, MDC.get("jobRunId"));
            }
            assertNull(MDC.get("jobRunId"));
            var owners = ArgumentCaptor.forClass(String.class);
            verify(intents, times(2)).claim(eq(command), owners.capture());
            assertNotEquals(owners.getAllValues().get(0), owners.getAllValues().get(1));
            verify(intents).fail(command, owners.getAllValues().get(0));
            verify(intents).complete(command, owners.getAllValues().get(1));
            verify(intents).selectDue(50);
            verify(consents, never()).insertConsentRecord(any());
            List<ILoggingEvent> results = logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("event=roam.presence.audit.result")).toList();
            assertEquals(2, results.size());
            assertEquals("http_original_123", results.get(0).getMDCPropertyMap().get("requestId"));
            assertTrue(results.get(0).getFormattedMessage().contains("observedResult=NOT_CONFIRMED"));
            assertEquals(run, results.get(1).getMDCPropertyMap().get("jobRunId"));
            assertEquals(command, results.get(1).getMDCPropertyMap().get("commandId"));
            assertTrue(results.get(1).getFormattedMessage().contains("observedResult=DELIVERED"));
            for (ILoggingEvent event : logs.list) {
                assertNull(event.getThrowableProxy());
                assertFalse(event.getFormattedMessage().contains("private-"));
                for (String owner : owners.getAllValues()) assertFalse(event.getFormattedMessage().contains(owner));
            }
        } finally { logger.detachAppender(logs); }
    }
}
