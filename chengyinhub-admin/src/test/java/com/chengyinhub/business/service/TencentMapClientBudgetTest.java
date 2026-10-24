package com.chengyinhub.business.service;

import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real synchronous client/admission, controlled URL connections; no provider or HTTP networking. */
class TencentMapClientBudgetTest {
    private static final String ENDPOINT = "https://apis.map.qq.com/ws/geocoder/v1/?location=30,120";

    @Test void fourPhysicalRequestsAcrossClientsKeepPermitsThroughInterruptAndDisconnect() throws Exception {
        CountDownLatch reading = new CountDownLatch(4), releaseRead = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1), disconnecting = new CountDownLatch(4);
        CountDownLatch releaseDisconnect = new CountDownLatch(1), completed = new CountDownLatch(4);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), opened = new AtomicInteger();
        Supplier<Connection> connections = () -> {
            opened.incrementAndGet();
            int now = active.incrementAndGet(); peak.accumulateAndGet(now, Math::max);
            return new Connection(200, "ok".getBytes(StandardCharsets.UTF_8), -1) {
                @Override public InputStream getInputStream() {
                    return new ByteArrayInputStream(body) {
                        private boolean first = true;
                        @Override public synchronized int read(byte[] buffer, int off, int len) {
                            if (first) {
                                first = false; reading.countDown();
                                awaitIgnoringInterrupt(releaseRead, interrupted);
                            }
                            return super.read(buffer, off, len);
                        }
                    };
                }
                @Override public void disconnect() {
                    disconnecting.countDown(); awaitIgnoringInterrupt(releaseDisconnect, interrupted);
                    active.decrementAndGet(); super.disconnect();
                }
            };
        };
        ControlledClient first = client(new ControlledClient(connections));
        ControlledClient second = client(new ControlledClient(connections));
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<String>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                final ControlledClient current = i % 2 == 0 ? first : second;
                calls.add(workers.submit(() -> {
                    try { return current.get(ENDPOINT); }
                    finally { completed.countDown(); }
                }));
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertNull(second.get(ENDPOINT));
            assertEquals(4, opened.get());
            assertTrue(calls.get(0).cancel(true));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertNull(first.get(ENDPOINT), "caller cancellation is not physical exit");
            assertEquals(4, active.get());
            releaseRead.countDown();
            assertTrue(disconnecting.await(5, TimeUnit.SECONDS));
            assertNull(second.get(ENDPOINT), "disconnect still owns its admission permit");
            assertEquals(4, opened.get());
            releaseDisconnect.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            for (int i = 1; i < calls.size(); i++) assertEquals("ok", calls.get(i).get(5, TimeUnit.SECONDS));
            assertEquals(0, active.get());
            assertEquals(4, peak.get());
            assertEquals("recovered", client(new ControlledClient(() -> connection("recovered"))).get(ENDPOINT));
        } finally {
            releaseRead.countDown(); releaseDisconnect.countDown();
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void declaredAndChunkedOversizedResponsesAreRejectedWithoutRetryOrTruncation() {
        AtomicInteger opens = new AtomicInteger();
        Connection declared = new Connection(200, new byte[0], TencentMapClient.MAX_RESPONSE_BYTES + 1L);
        ControlledClient client = client(new ControlledClient(() -> { opens.incrementAndGet(); return declared; }));
        assertThrows(HttpRequestException.class, () -> client.get(ENDPOINT));
        assertEquals(0, declared.inputReads);
        assertTrue(declared.disconnected);
        Connection chunked = new Connection(200, new byte[TencentMapClient.MAX_RESPONSE_BYTES + 1], -1);
        client.connections = () -> { opens.incrementAndGet(); return chunked; };
        assertThrows(HttpRequestException.class, () -> client.get(ENDPOINT));
        assertEquals(2, opens.get(), "response limits must not schedule a retry");
        assertTrue(chunked.disconnected);
        byte[] exact = new byte[TencentMapClient.MAX_RESPONSE_BYTES];
        java.util.Arrays.fill(exact, (byte) 'x');
        client.connections = () -> new Connection(200, exact, exact.length);
        String maximum = client.get(ENDPOINT);
        assertEquals(TencentMapClient.MAX_RESPONSE_BYTES, maximum.length());
        assertEquals('x', maximum.charAt(0));
        assertEquals('x', maximum.charAt(maximum.length() - 1));
        AtomicInteger truncatedAttempts = new AtomicInteger();
        client.connections = () -> {
            truncatedAttempts.incrementAndGet();
            return new Connection(200, "{}".getBytes(StandardCharsets.UTF_8), 20);
        };
        HttpRequestException incomplete = assertThrows(HttpRequestException.class, () -> client.get(ENDPOINT));
        assertEquals("map response incomplete", incomplete.getMessage());
        assertEquals(2, truncatedAttempts.get(), "partial body is never a successful response");
        client.connections = () -> connection("healthy");
        assertEquals("healthy", client.get(ENDPOINT));
    }

    @Test void retriesOnlyExistingRetryableGetStatusAndClosesBeforeSecondAttempt() {
        AtomicInteger opens = new AtomicInteger();
        Connection unavailable = new Connection(503, new byte[0], 0);
        Connection healthy = connection("healthy");
        ControlledClient client = client(new ControlledClient(() -> {
            if (opens.incrementAndGet() == 1) return unavailable;
            assertTrue(unavailable.disconnected);
            return healthy;
        }));
        assertEquals("healthy", client.get(ENDPOINT));
        assertEquals(2, opens.get());
        assertTrue(healthy.disconnected);
        assertFalse(healthy.getInstanceFollowRedirects());
        assertTrue(healthy.getConnectTimeout() > 0 && healthy.getConnectTimeout() <= 3000);
        assertTrue(healthy.getReadTimeout() > 0 && healthy.getReadTimeout() <= 5000);
        Connection denied = new Connection(403, "not a successful body".getBytes(StandardCharsets.UTF_8), -1);
        client.connections = () -> { opens.incrementAndGet(); return denied; };
        HttpRequestException error = assertThrows(HttpRequestException.class, () -> client.get(ENDPOINT));
        assertEquals(403, error.getStatusCode());
        assertEquals(3, opens.get());
        assertEquals(0, denied.inputReads);
        assertFalse(error.isRetryable());
        assertNull(error.getCause());
    }

    @Test void absoluteDeadlineIsCheckedWhileReadingAndReleasesOnlyAfterClose() {
        AtomicLong now = new AtomicLong();
        Connection late = new Connection(200, new byte[0], -1) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() { now.set(TimeUnit.SECONDS.toNanos(10)); return 1; }
                    @Override public int read(byte[] b, int off, int len) { b[off] = 1; return read(); }
                };
            }
        };
        ControlledClient client = client(new ControlledClient(() -> late) {
            @Override protected long nanoTime() { return now.get(); }
        });
        HttpRequestException timeout = assertThrows(HttpRequestException.class, () -> client.get(ENDPOINT));
        assertEquals("map total timeout", timeout.getMessage());
        assertTrue(late.disconnected);
        client.connections = () -> connection("healthy");
        assertEquals("healthy", client.get(ENDPOINT));
    }

    @Test void hugeConfigurationRequestAndForeignOriginNeverOpenAConnection() {
        AtomicInteger opens = new AtomicInteger();
        ControlledClient client = client(new ControlledClient(() -> { opens.incrementAndGet(); return connection("ok"); }));
        ReflectionTestUtils.setField(client, "mapKey", "k".repeat(513));
        assertNull(client.get(ENDPOINT));
        ReflectionTestUtils.setField(client, "mapKey", "test-map");
        assertNull(client.get(ENDPOINT + "x".repeat(TencentMapClient.MAX_REQUEST_CHARACTERS)));
        assertThrows(HttpRequestException.class, () -> client.get("https://foreign.invalid/?location=30,120"));
        assertThrows(HttpRequestException.class, () -> client.get("https://apis.map.qq.com:444/?location=30,120"));
        assertEquals(0, opens.get());
        assertEquals("ok", client.get(ENDPOINT));
    }

    private static Connection connection(String body) {
        return new Connection(200, body.getBytes(StandardCharsets.UTF_8), -1);
    }

    private static <T extends TencentMapClient> T client(T client) {
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "mapKey", "test-map");
        return client;
    }

    private static class ControlledClient extends TencentMapClient {
        Supplier<Connection> connections;
        ControlledClient(Supplier<Connection> connections) { this.connections = connections; }
        @Override protected HttpURLConnection openConnection(URL target) { return connections.get(); }
        @Override protected long nanoTime() { return 0; }
    }

    private static class Connection extends HttpURLConnection {
        final byte[] body;
        final long length;
        final int status;
        volatile boolean disconnected;
        int inputReads;
        Connection(int status, byte[] body, long length) {
            super(null); this.status = status; this.body = body; this.length = length;
        }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() { inputReads++; return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() throws IOException { }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch, CountDownLatch interrupted) {
        boolean wasInterrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier not released");
                    return;
                } catch (InterruptedException ignored) { wasInterrupted = true; interrupted.countDown(); }
            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }
}
