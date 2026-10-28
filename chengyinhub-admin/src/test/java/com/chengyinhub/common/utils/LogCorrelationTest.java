package com.chengyinhub.common.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;

class LogCorrelationTest {
    @AfterEach void clear() { MDC.clear(); }

    @Test void oneReusedWorkerSeesSubmissionSnapshotAndRestoresAfterFailure() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        Logger logger = (Logger) LoggerFactory.getLogger(LogCorrelation.class);
        var logs = capture(logger);
        try {
            executor.submit(() -> MDC.put("workerMarker", "original")).get();
            MDC.put("requestId", "request_A_123");
            MDC.put("commandId", "presence-original");
            MDC.put("Authorization", "private-header-fixture");
            Runnable first = LogCorrelation.wrap(() -> {
                assertEquals("request_A_123", MDC.get("requestId"));
                assertEquals("presence-original", MDC.get("commandId"));
                assertNull(MDC.get("Authorization"));
                assertNull(MDC.get("workerMarker"));
            });
            MDC.put("requestId", "request_B_456");
            executor.submit(first).get();
            assertEquals(Map.of("workerMarker", "original"), executor.submit(() -> { return MDC.getCopyOfContextMap(); }).get());
            var failure = new IllegalStateException("private-payload-fixture");
            Runnable second = LogCorrelation.wrap(() -> {
                assertEquals("request_B_456", MDC.get("requestId"));
                MDC.put("requestId", "forged\nrecord");
                MDC.put("Authorization", "private-task-header");
                throw failure;
            });
            ExecutionException observed = assertThrows(ExecutionException.class, () -> executor.submit(second).get());
            assertSame(failure, observed.getCause());
            assertEquals(Map.of("workerMarker", "original"), executor.submit(() -> { return MDC.getCopyOfContextMap(); }).get());
            assertEquals("request_B_456", MDC.get("requestId"));
            assertEquals(1, logs.list.size());
            ILoggingEvent event = logs.list.get(0);
            assertTrue(event.getFormattedMessage().contains("request_B_456"));
            assertFalse(event.getFormattedMessage().contains("private-"));
            assertNull(event.getThrowableProxy());
            assertNull(event.getMDCPropertyMap().get("Authorization"));
        } finally {
            logger.detachAppender(logs);
            executor.shutdownNow();
        }
    }

    @Test void invalidIdsAndUnknownFieldsAreOmittedAndInlineFailureRestoresExactly() {
        MDC.put("requestId", "caller_12345");
        MDC.put("Authorization", "private-header-fixture");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (var scope = LogCorrelation.with(Map.of("commandId", "bad\r\nrecord", "payload", "private-body",
                "event", "unsafe\u2028event", "fence", "lease-secret-fixture"))) {
            assertEquals(Map.of("requestId", "caller_12345"), LogCorrelation.snapshot());
            assertNull(MDC.get("Authorization"));
        }
        var failure = new AssertionError("private-error-fixture");
        assertSame(failure, assertThrows(AssertionError.class, () -> LogCorrelation.wrap(() -> { throw failure; }).run()));
        assertEquals(previous, MDC.getCopyOfContextMap());
        assertEquals("-", LogCorrelation.safe("requestId", "x".repeat(65)));
        assertEquals("-", LogCorrelation.safe("commandId", "a.b.c"));
        assertEquals("-", LogCorrelation.safe("commandId", "x".repeat(97)));
        assertEquals("23", LogCorrelation.safe("fence", "23"));
    }

    @Test void nestedScopesRestoreOriginalAndCloseIsIdempotent() {
        MDC.put("requestId", "outer_12345");
        var scope = LogCorrelation.open(Map.of("requestId", "inner_12345"));
        try (var nested = LogCorrelation.with(Map.of("receiptId", "receipt_original"))) {
            assertEquals("inner_12345", MDC.get("requestId"));
            assertEquals("receipt_original", MDC.get("receiptId"));
        }
        assertNull(MDC.get("receiptId"));
        scope.close();
        scope.close();
        assertEquals(Map.of("requestId", "outer_12345"), MDC.getCopyOfContextMap());
    }

    @Test void executorDiagnosticDoesNotDumpFutureMessageOrCauseAndFutureStillFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(Threads.class);
        var logs = capture(logger);
        try {
            var failure = new IllegalStateException("private-payload-fixture");
            var future = new FutureTask<Void>(() -> { throw failure; });
            future.run();
            Threads.printException(future, null);
            assertSame(failure, assertThrows(ExecutionException.class, future::get).getCause());
            assertEquals(1, logs.list.size());
            assertTrue(logs.list.get(0).getFormattedMessage().contains("IllegalStateException"));
            assertFalse(logs.list.get(0).getFormattedMessage().contains("private-"));
            assertNull(logs.list.get(0).getThrowableProxy());
        } finally { logger.detachAppender(logs); }
    }

    private ListAppender<ILoggingEvent> capture(Logger logger) {
        var logs = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        logs.start(); logger.addAppender(logs); return logs;
    }
}
