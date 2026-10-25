package com.chengyinhub.business.it;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** SOURCE controls for the owned provider decoder, plus the actual loopback client path. */
@ResourceLock("t19-process-global-http-ai-map")
class CrossDomainTransportStageTest {
    private static final String PREFIX = "{\"model\":\"fixture-model\",\"messages\":[{\"role\":\"system\",\"content\":\"s\"},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,";
    private static final String SUFFIX = "\"}},{\"type\":\"text\",\"text\":\"controlled requirement\"}]}]}";
    private static final String SMALL = "{\"model\":\"fixture-model\",\"messages\":[{},{}]}";

    @Test @Timeout(30)
    void fourOriginalLargeImagesAreDecodedWithoutMaterializingImageText() throws Exception {
        // The image token exceeds the parser's 1024-char materialized-string limit:
        // it can pass only by syntax-checking and skipping content, not getText/readValueAsTree.
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> reads = new ArrayList<>();
            for (int i = 0; i < 4; i++) reads.add(workers.submit(() -> {
                try (var input = new ObservedInput(concat(bytes(PREFIX),
                        new RepeatedInput('A', 16 * 1024 * 1024), bytes(SUFFIX)))) {
                    CrossDomainTransportStage.validateRequest(input);
                    assertTrue(input.closed);
                    assertEquals(PREFIX.getBytes(StandardCharsets.UTF_8).length + 16 * 1024 * 1024
                            + SUFFIX.getBytes(StandardCharsets.UTF_8).length, input.count);
                    assertTrue(input.largestRead <= 8192, "decoder must pull bounded chunks");
                } catch (IOException error) { throw new UncheckedIOException(error); }
            }));
            for (Future<?> read : reads) read.get(25, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void exactByteLimitIsAcceptedButOneExtraByteIsRejectedAndClosed() throws Exception {
        int padding = CrossDomainTransportStage.REQUEST_LIMIT - SMALL.getBytes(StandardCharsets.UTF_8).length;
        var exact = new ObservedInput(concat(bytes(SMALL), new RepeatedInput(' ', padding)));
        CrossDomainTransportStage.validateRequest(exact);
        assertEquals(CrossDomainTransportStage.REQUEST_LIMIT, exact.count); assertTrue(exact.closed);
        var extra = new ObservedInput(concat(bytes(SMALL), new RepeatedInput(' ', padding + 1L)));
        assertThrows(CrossDomainTransportStage.RequestProtocolException.class,
                () -> CrossDomainTransportStage.validateRequest(extra));
        assertEquals(CrossDomainTransportStage.REQUEST_LIMIT + 1, extra.count); assertTrue(extra.closed);
    }

    @Test
    void wrongModelCountDuplicateFieldsTrailingDocumentAndDepthRemainFailures() {
        for (String invalid : List.of(
                SMALL.replace("fixture-model", "foreign-model"),
                SMALL.replace("[{},{}]", "[{}]"), SMALL.replace("[{},{}]", "[{},{},{}]"),
                SMALL.replace("\"messages\"", "\"model\":\"fixture-model\",\"messages\""),
                SMALL + SMALL, SMALL.substring(0, SMALL.length() - 1),
                SMALL.replace("[{},{}]", "[{\"deep\":" + "[".repeat(20) + "0" + "]".repeat(20) + "},{}]"))) {
            assertThrows(CrossDomainTransportStage.RequestProtocolException.class,
                    () -> CrossDomainTransportStage.validateRequest(bytes(invalid)));
        }
    }

    @Test
    void malformedInputCannotLeakParserBodyCanaryOrBecomeAnAllowedDisconnect() {
        String canary = "CONTROLLED_SECRET_BODY_CANARY";
        var error = assertThrows(CrossDomainTransportStage.RequestProtocolException.class,
                () -> CrossDomainTransportStage.validateRequest(bytes("{\"model\":\"" + canary + "\"")));
        assertEquals("CONTROLLED_PROVIDER_REQUEST_INVALID_OR_OVER_BUDGET", error.getMessage());
        assertNull(error.getCause()); assertFalse(error.toString().contains(canary));
    }

    @Test
    void callerAndProviderQueuesAreFiniteAndPreserveWorkerBudgets() throws Exception {
        var stage = new CrossDomainTransportStage();
        try {
            var provider = (ThreadPoolExecutor) stage.providers;
            var caller = (ThreadPoolExecutor) stage.callers;
            assertEquals(4, provider.getMaximumPoolSize()); assertEquals(4, provider.getQueue().remainingCapacity());
            assertEquals(8, caller.getMaximumPoolSize()); assertEquals(8, caller.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, provider.getRejectedExecutionHandler());
        } finally {
            // This control has not opened the process-global transport witness.
            stage.callers.shutdownNow(); stage.providers.shutdownNow();
            assertTrue(stage.callers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(stage.providers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test @Timeout(20)
    void faultPhaseProtocolFailureStillSurfacesFromOwnedTransportCleanup() throws Exception {
        var stage = new CrossDomainTransportStage();
        Exception cleanupFailure = null;
        try {
            stage.open(); stage.fault = true; stage.releaseAi.countDown();
            var connection = (java.net.HttpURLConnection) new java.net.URL(
                    stage.origins.get(0) + "/chat/completions").openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer fixture-token");
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] invalid = "{\"model\":\"foreign-model\",\"messages\":[{},{}]}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(invalid.length);
            try {
                try (var output = connection.getOutputStream()) { output.write(invalid); }
                assertThrows(IOException.class, connection::getResponseCode);
            } finally { connection.disconnect(); }
        } finally {
            try { stage.close(); } catch (Exception failure) { cleanupFailure = failure; }
        }
        assertNotNull(cleanupFailure);
        assertEquals("owned transport cleanup failed", cleanupFailure.getMessage());
        assertEquals(1, stage.providerFailures.size());
        assertInstanceOf(CrossDomainTransportStage.RequestProtocolException.class, stage.providerFailures.get(0));
        assertTrue(Arrays.stream(cleanupFailure.getSuppressed())
                .anyMatch(CrossDomainTransportStage.RequestProtocolException.class::isInstance));
    }

    @Test @Timeout(20)
    void realVisionClientStillPostsAndParsesTheControlledHttpResponse() throws Exception {
        try (var stage = new CrossDomainTransportStage()) {
            stage.open();
            String response = stage.client(0).chatVision("s", CrossDomainTransportStage.SMALL_IMAGE,
                    "controlled requirement");
            assertTrue(response.contains("\"pass\":true"));
            assertEquals(1, stage.aiRequests.get()); assertTrue(stage.providerFailures.isEmpty());
        }
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
    private static InputStream concat(InputStream... inputs) {
        return new SequenceInputStream(Collections.enumeration(List.of(inputs)));
    }
    private static final class RepeatedInput extends InputStream {
        private final byte value;
        private long remaining;
        RepeatedInput(char value, long count) { this.value = (byte) value; remaining = count; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; return value & 255; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            Arrays.fill(bytes, offset, offset + count, value); remaining -= count; return count;
        }
    }
    private static final class ObservedInput extends FilterInputStream {
        long count;
        int largestRead;
        boolean closed;
        ObservedInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read(); if (value != -1) count++; return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            largestRead = Math.max(largestRead, length);
            int size = in.read(bytes, offset, length); if (size > 0) count += size; return size;
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
