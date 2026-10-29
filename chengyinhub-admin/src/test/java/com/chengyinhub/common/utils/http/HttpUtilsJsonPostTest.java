package com.chengyinhub.common.utils.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Controlled loopback only. Must be executed by the approved JDK21 CI, not the editing VM. */
class HttpUtilsJsonPostTest
{
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ExecutorService> fixtures = new ArrayList<>();

    @BeforeEach
    void initializeClientBeforeMeasuringAttemptBudgets()
    {
        HttpUtils.pendingHttpTaskCount();
    }

    @AfterEach
    void cleanup()
    {
        servers.forEach(server -> server.stop(0));
        fixtures.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void legacyExceptionNeverInventsNotSentEvidence()
    {
        assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT,
                new HttpRequestException("fixed", 0, false).getSendDisposition());
    }

    @Test
    void callerCloseCannotReleaseATransferredRunningAttempt()
    {
        AtomicInteger releases = new AtomicInteger();
        HttpUtils.AttemptLease lease = new HttpUtils.AttemptLease(releases::incrementAndGet);
        assertTrue(lease.transfer());
        HttpUtils.AttemptState state = new HttpUtils.AttemptState(lease);
        assertTrue(state.startWorker());
        assertTrue(state.enterDispatch());
        lease.close();
        assertEquals(0, releases.get());
        assertFalse(state.cancelBeforeDispatch());
        assertEquals(0, releases.get(), "timeout/interrupt must not release a live worker");
        state.finishWorker();
        state.finishWorker();
        lease.close();
        assertEquals(1, releases.get());
        assertFalse(state.isNotSent());
    }

    @Test
    void queuedCancellationWinsCasAndPreventsEveryLaterDispatch()
    {
        AtomicInteger releases = new AtomicInteger();
        HttpUtils.AttemptLease lease = new HttpUtils.AttemptLease(releases::incrementAndGet);
        assertTrue(lease.transfer());
        HttpUtils.AttemptState state = new HttpUtils.AttemptState(lease);
        assertTrue(state.cancelBeforeDispatch());
        assertFalse(state.startWorker());
        assertFalse(state.enterDispatch());
        assertEquals(1, releases.get());
        state.finishWorker();
        assertEquals(1, releases.get());
        assertTrue(state.isNotSent());
    }

    @Test
    void cancellationDuringPreparationWaitsForPhysicalWorkerFinally()
    {
        AtomicInteger releases = new AtomicInteger();
        HttpUtils.AttemptLease lease = new HttpUtils.AttemptLease(releases::incrementAndGet);
        assertTrue(lease.transfer());
        HttpUtils.AttemptState state = new HttpUtils.AttemptState(lease);
        assertTrue(state.startWorker());
        assertTrue(state.cancelBeforeDispatch());
        assertFalse(state.enterDispatch());
        assertEquals(0, releases.get());
        assertTrue(state.isNotSent());
        state.finishWorker();
        assertEquals(1, releases.get());
    }

    @Test
    void concurrentCancelAndDispatchHaveOneWinnerAndNoPrematureRelease() throws Exception
    {
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        fixtures.add(contenders);
        for (int round = 0; round < 50; round++)
        {
            AtomicInteger releases = new AtomicInteger();
            HttpUtils.AttemptLease lease = new HttpUtils.AttemptLease(releases::incrementAndGet);
            assertTrue(lease.transfer());
            HttpUtils.AttemptState state = new HttpUtils.AttemptState(lease);
            assertTrue(state.startWorker());
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> dispatch = contenders.submit(() -> { start.await(); return state.enterDispatch(); });
            Future<Boolean> cancel = contenders.submit(() -> { start.await(); return state.cancelBeforeDispatch(); });
            start.countDown();
            boolean entered = dispatch.get(3, TimeUnit.SECONDS);
            boolean cancelled = cancel.get(3, TimeUnit.SECONDS);
            assertEquals(!entered, cancelled);
            assertEquals(!entered, state.isNotSent());
            assertEquals(0, releases.get(), "the started worker still owns this lease");
            state.finishWorker();
            assertEquals(1, releases.get());
            assertEquals(!entered, state.isNotSent(), "finishing must never erase dispatch evidence");
        }
    }

    @Test
    void invalidHeaderUrlAndExpiredDeadlineAreUnsentAndReleaseOnce()
    {
        for (String target : List.of("http://user:secret@127.0.0.1/", "ftp://127.0.0.1/",
                "http://127.0.0.1/?secret=query", "http://127.0.0.1/#fragment"))
        {
            assertUnsent(target, "synthetic", deadline(1000));
        }
        assertUnsent("http://127.0.0.1:1/", "fake\r\nInjected: header", deadline(1000));
        assertUnsent("http://127.0.0.1:1/", "synthetic", System.nanoTime() - 1);
    }

    @Test
    void authenticatedJsonPostPreservesProtocolAndReleasesItsLease() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("Bearer synthetic", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("{\"value\":1}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        AtomicInteger releases = new AtomicInteger();
        assertEquals("ok", send(url(server), "{\"value\":1}", 2000, releases));
        assertEquals(1, calls.get());
        assertEquals(1, releases.get());
    }

    @Test
    void providerErrorsRedirectAndAuthChallengeNeverRetryOrBecomeUnsent() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger followed = new AtomicInteger();
        HttpServer target = server(exchange -> { followed.incrementAndGet(); exchange.close(); });
        for (int status : new int[]{500, 302, 401})
        {
            HttpServer source = server(exchange -> {
                calls.incrementAndGet();
                exchange.getResponseHeaders().add("Location", url(target));
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=synthetic");
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            HttpRequestException error = assertThrows(HttpRequestException.class,
                    () -> send(url(source), "{}", 2000, new AtomicInteger()));
            assertEquals(status, error.getStatusCode());
            assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT, error.getSendDisposition());
            assertFalse(error.isRetryable());
            assertNull(error.getCause());
        }
        assertEquals(3, calls.get());
        assertEquals(0, followed.get());
    }

    @Test
    void lostProviderResponseNeverReplaysThePost() throws Exception
    {
        AtomicInteger received = new AtomicInteger();
        ExecutorService observer = Executors.newSingleThreadExecutor();
        fixtures.add(observer);
        try (ServerSocket socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")))
        {
            socket.setSoTimeout(2000);
            Future<?> observation = observer.submit(() -> {
                for (int i = 0; i < 2; i++)
                {
                    try (Socket connection = socket.accept())
                    {
                        connection.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(
                                connection.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        int length = 0;
                        while ((line = input.readLine()) != null && !line.isEmpty())
                        {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                        for (int read = 0; read < length; read++) if (input.read() == -1) throw new IOException("incomplete fixture body");
                        received.incrementAndGet();
                        socket.setSoTimeout(700);
                        // Acknowledge no response: the provider already received the full POST.
                    }
                    catch (SocketTimeoutException noReplay) { return; }
                    catch (IOException failure) { throw new IllegalStateException("fixture socket failed", failure); }
                }
            });
            HttpRequestException error = assertThrows(HttpRequestException.class,
                    () -> send("http://127.0.0.1:" + socket.getLocalPort() + "/", "{}", 3000, new AtomicInteger()));
            observation.get(4, TimeUnit.SECONDS);
            assertEquals(1, received.get());
            assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT, error.getSendDisposition());
            assertFalse(error.isRetryable());
            assertNull(error.getCause());
        }
    }

    @Test
    void decodedGzipAndChunkedBodiesCannotExceedOneMiB() throws Exception
    {
        byte[] oversized = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        for (boolean compressed : new boolean[]{false, true})
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (compressed)
            {
                try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(oversized); }
            }
            byte[] response = compressed ? bytes.toByteArray() : oversized;
            HttpServer source = server(exchange -> {
                if (compressed) exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(response); }
                finally { exchange.close(); }
            });
            HttpRequestException error = assertThrows(HttpRequestException.class,
                    () -> send(url(source), "{}", 3000, new AtomicInteger()));
            assertTrue(error.getMessage().contains("response limit"));
            assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT, error.getSendDisposition());
        }
    }

    @Test
    void knownOversizedLengthIsRejectedBeforeWaitingForBody() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer source = server(exchange -> {
            exchange.sendResponseHeaders(200, 1024 * 1024 + 1);
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try
        {
            HttpRequestException failure = assertThrows(HttpRequestException.class,
                    () -> send(url(source), "{}", 2000, new AtomicInteger()));
            assertTrue(failure.getMessage().contains("response limit"));
            assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT, failure.getSendDisposition());
        }
        finally { release.countDown(); }
    }

    @Test
    void exactOneMiBResponseIsAccepted() throws Exception
    {
        byte[] response = new byte[1024 * 1024];
        java.util.Arrays.fill(response, (byte) 'x');
        HttpServer source = server(exchange -> {
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        assertEquals(response.length, send(url(source), "{}", 3000, new AtomicInteger()).length());
    }

    @Test
    void slowDripIsBoundedByAbsoluteTotalAndIsNeverUnsent() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = server(exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try
            {
                for (int i = 0; i < 30; i++)
                {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    Thread.sleep(40);
                }
            }
            catch (IOException disconnected) { /* expected cancellation */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long start = System.nanoTime();
        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> send(url(source), "{}", 250, new AtomicInteger()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        assertEquals(1, calls.get());
        assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT, error.getSendDisposition());
    }

    @Test
    void ownQueuedTimeoutRemovesOnlyThatTaskAndSaturationNeverDispatches() throws Exception
    {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(HttpUtils.class, "HTTP_EXECUTOR");
        assertNotNull(executor);
        awaitIdle(executor);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(executor.getMaximumPoolSize());
        List<Future<?>> owned = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = server(exchange -> { calls.incrementAndGet(); exchange.close(); });
        Runnable block = () -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        try
        {
            int capacity = executor.getMaximumPoolSize() + executor.getQueue().remainingCapacity();
            for (int i = 0; i < capacity; i++) owned.add(executor.submit(block));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            int fullSize = executor.getQueue().size();
            assertUnsent(url(source), "synthetic", deadline(500));
            assertEquals(fullSize, executor.getQueue().size());
            Future<?> ownQueued = owned.stream().filter(executor.getQueue()::contains).findFirst().orElseThrow();
            assertTrue(executor.remove((Runnable) ownQueued));
            ownQueued.cancel(false);
            int before = executor.getQueue().size();
            assertUnsent(url(source), "synthetic", deadline(100));
            assertEquals(before, executor.getQueue().size(), "only the timed-out task is removed");
            assertEquals(0, calls.get());
        }
        finally
        {
            release.countDown();
            for (Future<?> future : owned) if (!future.isCancelled()) future.get(5, TimeUnit.SECONDS);
            awaitIdle(executor);
        }
    }

    @Test
    void blockedPhysicalPostRetainsLeaseWhenDeadlineAndCleanupRejectionReturnToCaller() throws Exception
    {
        ThreadPoolExecutor http = (ThreadPoolExecutor) ReflectionTestUtils.getField(HttpUtils.class, "HTTP_EXECUTOR");
        ThreadPoolExecutor cleanup = (ThreadPoolExecutor) ReflectionTestUtils.getField(HttpUtils.class, "DISCONNECT_EXECUTOR");
        assertNotNull(http);
        assertNotNull(cleanup);
        awaitIdle(http);
        awaitIdle(cleanup);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(cleanup.getMaximumPoolSize());
        List<Future<?>> ownedCleanup = new ArrayList<>();
        AtomicInteger releases = new AtomicInteger();
        HttpServer source = server(exchange -> {
            entered.countDown();
            try
            {
                assertTrue(releaseBody.await(5, TimeUnit.SECONDS));
                byte[] buffer = new byte[8192];
                while (exchange.getRequestBody().read(buffer) != -1) { /* drain only after assertion */ }
                exchange.sendResponseHeaders(200, -1);
            }
            catch (IOException disconnected) { /* physical cancellation may win */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try
        {
            int workers = cleanup.getMaximumPoolSize();
            int queued = cleanup.getQueue().remainingCapacity();
            int capacity = workers + queued;
            Runnable blockCleanup = () -> {
                cleanupStarted.countDown();
                try { releaseCleanup.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            // Retained idle workers can still be counted in poolSize before taking queued tasks.
            // Submit beyond the first worker wave only while the pool can grow: a full queue
            // then starts a new worker instead of rejecting against already-counted idle workers.
            for (int i = 0; i < capacity
                    && (i < workers || cleanup.getPoolSize() < workers); i++)
            {
                ownedCleanup.add(cleanup.submit(blockCleanup));
            }
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
            // All workers now hold our blockers, so every remaining submission must stay queued.
            for (int i = ownedCleanup.size(); i < capacity; i++)
            {
                ownedCleanup.add(cleanup.submit(blockCleanup));
            }
            assertEquals(queued, cleanup.getQueue().size(), "all original cleanup queue slots must be held");
            assertEquals(0, cleanup.getQueue().remainingCapacity());
            long rejected = HttpUtils.disconnectRejectionCount();
            String largeBody = "x".repeat(16 * 1024 * 1024);
            HttpRequestException failure = assertThrows(HttpRequestException.class,
                    () -> send(url(source), largeBody, 1000, releases));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT, failure.getSendDisposition());
            assertTrue(HttpUtils.disconnectRejectionCount() > rejected);
            assertEquals(0, releases.get(), "cancelled Future is done but physical write is still blocked");
        }
        finally
        {
            releaseBody.countDown();
            releaseCleanup.countDown();
            for (Future<?> task : ownedCleanup) task.get(5, TimeUnit.SECONDS);
            awaitIdle(cleanup);
            awaitIdle(http);
        }
        assertEquals(1, releases.get(), "real worker finally releases exactly once");
    }

    private void assertUnsent(String url, String bearer, long deadline)
    {
        AtomicInteger releases = new AtomicInteger();
        HttpUtils.AttemptLease lease = new HttpUtils.AttemptLease(releases::incrementAndGet);
        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendJsonPost(url, "{}", bearer, deadline, lease));
        assertEquals(HttpRequestException.SendDisposition.NOT_SENT, error.getSendDisposition());
        lease.close();
        assertEquals(1, releases.get());
        assertNull(error.getCause());
        assertFalse(error.isRetryable());
    }

    private String send(String url, String json, int totalMs, AtomicInteger releases)
    {
        try (HttpUtils.AttemptLease lease = new HttpUtils.AttemptLease(releases::incrementAndGet))
        {
            return HttpUtils.sendJsonPost(url, json, "synthetic", deadline(totalMs), lease);
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService fixture = Executors.newCachedThreadPool();
        fixtures.add(fixture);
        server.setExecutor(fixture);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    private static long deadline(int ms) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms); }

    private static void awaitIdle(ThreadPoolExecutor executor) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getActiveCount() != 0 || !executor.getQueue().isEmpty())
        {
            assertTrue(System.nanoTime() - deadline < 0, "fixture must leave shared executor idle");
            Thread.sleep(10);
        }
    }
}
