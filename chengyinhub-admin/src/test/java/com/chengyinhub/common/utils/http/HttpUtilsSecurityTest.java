package com.chengyinhub.common.utils.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.net.ssl.SSLHandshakeException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUtilsSecurityTest
{
    private static final String CONNECT_TIMEOUT_PROPERTY = "chengyinhub.http.connectTimeoutMs";
    private static final String READ_TIMEOUT_PROPERTY = "chengyinhub.http.readTimeoutMs";
    private static final String TOTAL_TIMEOUT_PROPERTY = "chengyinhub.http.totalTimeoutMs";
    private static final String LEGACY_MAX_RESPONSE_PROPERTY = "chengyinhub.http.maxResponseBytes";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /** 灌满清理执行器的提交上限;真实容量是 max(4) + queue(64) = 68,留一倍余量兜底防死循环。 */
    private static final int SATURATION_SUBMIT_LIMIT = 200;
    /** 与 HttpUtils.DISCONNECT_EXECUTOR 的 maximumPoolSize 对齐:饱和后需要有这么多 worker 被锁死。 */
    private static final int DISCONNECT_MAX_POOL_SIZE = 4;

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setFastTestPolicy()
    {
        System.setProperty(CONNECT_TIMEOUT_PROPERTY, "100");
        System.setProperty(READ_TIMEOUT_PROPERTY, "150");
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "450");
    }

    @AfterEach
    void cleanup()
    {
        servers.forEach(server -> server.stop(0));
        System.clearProperty(CONNECT_TIMEOUT_PROPERTY);
        System.clearProperty(READ_TIMEOUT_PROPERTY);
        System.clearProperty(TOTAL_TIMEOUT_PROPERTY);
        System.clearProperty(LEGACY_MAX_RESPONSE_PROPERTY);
    }

    @Test
    void infoLogsOnlyMetadataAndNeverQueryCredentialsPhoneOrResponseBody() throws Exception
    {
        String responseSecret = "response-stable-token-should-not-log";
        String remoteShortToken = "t0k3n7";
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("X-Request-Id",
                    "13912345678-" + remoteShortToken + ";Token=header-token;PASSWORD=header-password"
                            + ";api-Key=header-key;client_secret=header-secret");
            respond(exchange, 200, "{\"token\":\"" + responseSecret
                    + "\",\"phoneNumber\":\"13912345678\",\"session_key\":\"body-session-secret\""
                    + ",\"openid\":\"body-openid-secret\",\"refresh_token\":\"body-refresh-secret\""
                    + ",\"secret\":\"body-client-secret\",\"password\":\"body-password-secret\""
                    + ",\"apiKey\":\"body-api-key-secret\"}");
        });
        Logger logger = (Logger) LoggerFactory.getLogger(HttpUtils.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try
        {
            String result = HttpUtils.sendGet(url(server, "/privacy"),
                    "access_token=query-access-secret&session_key=query-session-secret"
                            + "&phoneNumber=13812345678&openid=query-openid-secret"
                            + "&refresh_token=query-refresh-secret&secret=query-client-secret"
                            + "&password=query-password-secret&apiKey=query-api-key-secret");

            assertTrue(result.contains(responseSecret));
            List<String> info = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
            String joined = String.join("\n", info);
            assertTrue(joined.contains("method=GET"));
            assertTrue(joined.contains("status=200"));
            assertTrue(joined.contains("requestId=present"));
            assertFalse(joined.contains("sha256:"));
            assertFalse(joined.contains(remoteShortToken));
            assertFalse(joined.contains("header-token"));
            assertFalse(joined.contains("header-password"));
            assertFalse(joined.contains("header-key"));
            assertFalse(joined.contains("header-secret"));
            assertFalse(joined.contains("query-access-secret"));
            assertFalse(joined.contains("query-session-secret"));
            assertFalse(joined.contains("query-openid-secret"));
            assertFalse(joined.contains("query-refresh-secret"));
            assertFalse(joined.contains("query-client-secret"));
            assertFalse(joined.contains("query-password-secret"));
            assertFalse(joined.contains("query-api-key-secret"));
            assertFalse(joined.contains("body-session-secret"));
            assertFalse(joined.contains("body-openid-secret"));
            assertFalse(joined.contains("body-refresh-secret"));
            assertFalse(joined.contains("body-client-secret"));
            assertFalse(joined.contains("body-password-secret"));
            assertFalse(joined.contains("body-api-key-secret"));
            assertFalse(joined.contains("13812345678"));
            assertFalse(joined.contains("13912345678"));
            assertFalse(joined.contains(responseSecret));
            assertFalse(joined.contains("/privacy"));
        }
        finally
        {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void defaultGetAndPostNeverRetry()
    {
        AtomicInteger getCount = new AtomicInteger();
        AtomicInteger postCount = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if ("GET".equals(exchange.getRequestMethod()))
            {
                getCount.incrementAndGet();
            }
            else
            {
                postCount.incrementAndGet();
            }
            respond(exchange, 503, "provider unavailable");
        });

        HttpRequestException getError = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGet(url(server, "/retry"), "access_token=never-log"));
        HttpRequestException postError = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendPost(url(server, "/no-retry"), "phone=13712345678"));

        assertEquals(1, getCount.get());
        assertEquals(1, postCount.get());
        assertEquals(503, getError.getStatusCode());
        assertEquals(503, postError.getStatusCode());
        assertFalse(getError.getMessage().contains("never-log"));
        assertFalse(postError.getMessage().contains("13712345678"));
    }

    @Test
    void noRetryGetIsNotAutomaticallyReplayedByTransport() throws Exception
    {
        try (RawRequestServer server = new RawRequestServer(Integer.MAX_VALUE))
        {
            assertThrows(HttpRequestException.class,
                    () -> HttpUtils.sendGet(server.url("/one-time-code")));

            assertEquals(1, server.awaitStableRequestsSeen());
        }
    }

    @Test
    void postIsNotAutomaticallyReplayedByTransport() throws Exception
    {
        try (RawRequestServer server = new RawRequestServer(Integer.MAX_VALUE))
        {
            assertThrows(HttpRequestException.class,
                    () -> HttpUtils.sendPost(server.url("/post"), ""));

            assertEquals(1, server.awaitStableRequestsSeen());
        }
    }

    @Test
    void sslPostEntryPointIsNotAutomaticallyReplayedByTransport() throws Exception
    {
        try (RawRequestServer server = new RawRequestServer(Integer.MAX_VALUE))
        {
            assertThrows(HttpRequestException.class,
                    () -> HttpUtils.sendSSLPost(server.url("/ssl-post"), "code=one-time"));

            assertEquals(1, server.awaitStableRequestsSeen());
        }
    }

    @Test
    void idempotentGetRetryComesOnlyFromOurPolicy() throws Exception
    {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpUtils.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(logs);
        try (RawRequestServer server = new RawRequestServer(1))
        {
            assertEquals("ok", HttpUtils.sendGetWithRetry(server.url("/idempotent")));
            assertEquals(2, server.awaitStableRequestsSeen());
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("attempts=2")));
        }
        finally
        {
            logger.detachAppender(logs);
            logger.setLevel(originalLevel);
            logs.stop();
        }
    }

    @Test
    void transportDoesNotFollowRedirectsForAnyEntryPoint()
    {
        AtomicInteger redirectRequests = new AtomicInteger();
        AtomicInteger replayedRequests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if ("/replayed".equals(exchange.getRequestURI().getPath()))
            {
                replayedRequests.incrementAndGet();
                respond(exchange, 200, "unexpected");
                return;
            }
            redirectRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", urlForPort(
                    exchange.getLocalAddress().getPort(), "/replayed"));
            respond(exchange, 302, "redirect");
        });

        assertEquals(302, assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGet(url(server, "/get"))).getStatusCode());
        assertEquals(302, assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendPost(url(server, "/post"), "")).getStatusCode());
        assertEquals(302, assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendSSLPost(url(server, "/ssl-post"), "code=once"))
                .getStatusCode());
        assertEquals(3, redirectRequests.get());
        assertEquals(0, replayedRequests.get());
    }

    @Test
    void explicitlyIdempotentGetRetriesOnceWithBoundedBackoff()
    {
        AtomicInteger count = new AtomicInteger();
        HttpServer server = server(exchange -> {
            count.incrementAndGet();
            respond(exchange, 503, "provider unavailable");
        });
        long started = System.nanoTime();

        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGetWithRetry(url(server, "/retry")));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(503, error.getStatusCode());
        assertEquals(2, count.get());
        assertTrue(elapsedMillis >= 40L, "retry did not apply its bounded backoff");
        assertTrue(elapsedMillis < 450L, "retry exceeded the call deadline");
    }

    @Test
    void streamedResponseBodyLimitFailsExplicitlyWithoutRetry()
    {
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "2000");
        System.setProperty(LEGACY_MAX_RESPONSE_PROPERTY, Integer.toString(2 * MAX_RESPONSE_BYTES));
        AtomicInteger count = new AtomicInteger();
        HttpServer server = server(exchange -> {
            count.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody())
            {
                byte[] chunk = new byte[8192];
                int remaining = MAX_RESPONSE_BYTES + 1;
                while (remaining > 0)
                {
                    int size = Math.min(remaining, chunk.length);
                    output.write(chunk, 0, size);
                    remaining -= size;
                }
            }
        });

        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGet(url(server, "/large")));

        assertTrue(error.getMessage().contains("response limit"));
        assertEquals(1, count.get());
    }

    @Test
    void retryPolicyAllowsOnlyKnownTransientNetworkFailures()
    {
        assertTrue(HttpUtils.isRetryableNetworkFailure(new ConnectException()));
        assertTrue(HttpUtils.isRetryableNetworkFailure(new SocketTimeoutException()));
        assertTrue(HttpUtils.isRetryableNetworkFailure(new NoRouteToHostException()));
        assertTrue(HttpUtils.isRetryableNetworkFailure(new EOFException()));

        assertFalse(HttpUtils.isRetryableNetworkFailure(new BindException()));
        assertFalse(HttpUtils.isRetryableNetworkFailure(new SocketException()));
        assertFalse(HttpUtils.isRetryableNetworkFailure(new UnknownHostException()));
        assertFalse(HttpUtils.isRetryableNetworkFailure(new ProtocolException()));
        assertFalse(HttpUtils.isRetryableNetworkFailure(new SSLHandshakeException("bad certificate")));
        assertFalse(HttpUtils.isRetryableNetworkFailure(new IOException()));
    }

    @Test
    void retryBackoffIsInterruptibleAndDoesNotStartAnotherAttempt() throws Exception
    {
        CountDownLatch firstResponse = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        HttpServer server = server(exchange -> {
            count.incrementAndGet();
            respond(exchange, 503, "retry later");
            firstResponse.countDown();
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try
            {
                HttpUtils.sendGetWithRetry(url(server, "/interrupt-retry"));
            }
            catch (Throwable error)
            {
                failure.set(error);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "http-utils-interrupt-test");
        caller.start();

        assertTrue(firstResponse.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1_000L);

        assertFalse(caller.isAlive());
        assertTrue(failure.get() instanceof HttpRequestException);
        assertTrue(failure.get().getMessage().contains("interrupted"));
        assertTrue(interruptPreserved.get(), "interrupt status must be preserved");
        assertEquals(1, count.get(), "interrupted backoff must not start a second request");
    }

    @Test
    void realBlockedConnectHonorsConnectTimeoutAndWorkerReturns() throws Exception
    {
        awaitHttpExecutorIdle();
        System.setProperty(CONNECT_TIMEOUT_PROPERTY, "120");
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "1000");
        try (SaturatedConnectServer server = new SaturatedConnectServer())
        {
            long started = System.nanoTime();
            HttpRequestException error = assertThrows(HttpRequestException.class,
                    () -> HttpUtils.sendGet(server.url()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(error.getMessage().contains("connect timeout"));
            assertTrue(elapsedMillis >= 80L, "connect did not actually block");
            assertTrue(elapsedMillis < 600L, "connect timeout exceeded its hard bound");
            awaitHttpExecutorIdle();
        }
    }

    @Test
    void shorterTotalDeadlineCapsRealBlockedConnectAndWorkerReturns() throws Exception
    {
        awaitHttpExecutorIdle();
        System.setProperty(CONNECT_TIMEOUT_PROPERTY, "1000");
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "250");
        try (SaturatedConnectServer server = new SaturatedConnectServer())
        {
            long started = System.nanoTime();
            HttpRequestException error = assertThrows(HttpRequestException.class,
                    () -> HttpUtils.sendGet(server.url()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(error.getMessage().contains("timeout"));
            assertTrue(elapsedMillis >= 150L, "total timeout fired before the deadline");
            assertTrue(elapsedMillis < 600L, "total timeout exceeded its hard bound");
            awaitHttpExecutorIdle();
        }
    }

    @Test
    void disconnectQueueSaturationRejectsWithoutRunningInCaller() throws Exception
    {
        awaitDisconnectExecutorIdle();
        Logger logger = (Logger) LoggerFactory.getLogger(HttpUtils.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch rejectedRelease = new CountDownLatch(1);
        AtomicInteger rejectedCompleted = new AtomicInteger();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        int accepted = 0;
        try
        {
            accepted = saturateDisconnectExecutor(release, completed);
            long rejectionsBefore = HttpUtils.disconnectRejectionCount();
            BlockingDisconnectConnection rejectedConnection = new BlockingDisconnectConnection(
                    rejectedRelease, rejectedCompleted);
            CountDownLatch callerCompleted = new CountDownLatch(1);
            AtomicLong callElapsedNanos = new AtomicLong();
            Thread caller = new Thread(() -> {
                long started = System.nanoTime();
                try
                {
                    HttpUtils.disconnectAsync(rejectedConnection);
                }
                finally
                {
                    callElapsedNanos.set(System.nanoTime() - started);
                    callerCompleted.countDown();
                }
            }, "disconnect-rejection-caller");
            caller.start();
            assertTrue(callerCompleted.await(1, TimeUnit.SECONDS),
                    "rejected cleanup did not return to caller");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(callElapsedNanos.get());

            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("action=rejected")));
            assertEquals(rejectionsBefore + 1L, HttpUtils.disconnectRejectionCount());
            assertEquals(0, rejectedCompleted.get(),
                    "rejected cleanup must remain for the request worker's finally path");
            assertTrue(elapsedMillis < 250L, "rejected cleanup delayed the caller");
        }
        finally
        {
            rejectedRelease.countDown();
            release.countDown();
            awaitCleanupTasksCompleted(accepted, completed);
            awaitDisconnectExecutorIdle();
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    void knownOversizedContentLengthIsRejectedBeforeWaitingForBody()
    {
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "1000");
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, MAX_RESPONSE_BYTES + 1L);
            try
            {
                Thread.sleep(700L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        });
        long started = System.nanoTime();

        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGet(url(server, "/known-large")));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(error.getMessage().contains("response limit"));
        assertTrue(elapsedMillis < 400L, "known oversized response was not rejected from headers");
    }

    @Test
    void totalTimeoutStopsAResponseThatKeepsDrippingBytes() throws Exception
    {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody())
            {
                for (int i = 0; i < 20; i++)
                {
                    output.write('x');
                    output.flush();
                    Thread.sleep(75L);
                }
            }
            catch (IOException ignored)
            {
                // Client is expected to close the connection at the total deadline.
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        });
        long started = System.nanoTime();

        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGet(url(server, "/drip")));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(error.getMessage().contains("total timeout"));
        assertTrue(elapsedMillis >= 300L, "timeout fired before the configured deadline");
        assertTrue(elapsedMillis < 1_200L, "request exceeded its total timeout: " + elapsedMillis);
    }

    @Test
    void readTimeoutAppliesBeforeTheLongerTotalDeadline()
    {
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "1000");
        AtomicInteger count = new AtomicInteger();
        HttpServer server = server(exchange -> {
            count.incrementAndGet();
            try
            {
                Thread.sleep(700L);
                respond(exchange, 200, "too late");
            }
            catch (IOException ignored)
            {
                // Client closes each timed-out attempt.
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        });
        long started = System.nanoTime();

        HttpRequestException error = assertThrows(HttpRequestException.class,
                () -> HttpUtils.sendGetWithRetry(url(server, "/read-timeout")));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(error.getMessage().contains("read timeout"));
        assertEquals(2, count.get());
        assertTrue(elapsedMillis < 700L, "read timeout did not bound the call: " + elapsedMillis);
    }

    @Test
    void successfulResponsesReturnTheirConnectionToTheJdkKeepAlivePool()
    {
        // 本用例只验连接复用，不验超时；放宽 @BeforeEach 的 150/450ms，免得负载高的 runner 上偶发读超时。
        System.setProperty(READ_TIMEOUT_PROPERTY, "5000");
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "10000");
        List<Integer> remotePorts = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            remotePorts.add(exchange.getRemoteAddress().getPort());
            respond(exchange, 200, "ok");
        });
        String endpoint = url(server, "/keep-alive");

        assertEquals("ok", HttpUtils.sendGet(endpoint));
        assertEquals("ok", HttpUtils.sendGet(endpoint));

        assertEquals(2, remotePorts.size());
        assertEquals(remotePorts.get(0), remotePorts.get(1),
                "successful calls should reuse the provider connection");
    }

    @Test
    void totalTimeoutDisconnectsABlockedPostBody() throws Exception
    {
        awaitDisconnectExecutorIdle();
        System.setProperty(TOTAL_TIMEOUT_PROPERTY, "250");
        System.setProperty(READ_TIMEOUT_PROPERTY, "1000");
        CountDownLatch cleanupRelease = new CountDownLatch(1);
        AtomicInteger cleanupCompleted = new AtomicInteger();
        int acceptedCleanups = saturateDisconnectExecutor(cleanupRelease, cleanupCompleted);
        Logger logger = (Logger) LoggerFactory.getLogger(HttpUtils.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        HttpServer server = server(exchange -> {
            try
            {
                Thread.sleep(600L);
                byte[] buffer = new byte[8192];
                while (exchange.getRequestBody().read(buffer) != -1)
                {
                    // Drain until an early EOF or disconnect exposes cancellation.
                }
                respond(exchange, 200, "late");
            }
            catch (IOException expectedDisconnect)
            {
                // Worker finally/disconnect may close the connection after the caller returns.
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            long rejectionsBefore = HttpUtils.disconnectRejectionCount();
            long started = System.nanoTime();
            HttpRequestException error = assertThrows(HttpRequestException.class,
                    () -> HttpUtils.sendPost(url(server, "/blocked-post"),
                            repeat('x', 8 * 1024 * 1024)));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(error.getMessage().contains("total timeout"));
            // 2026-07-25: 600ms 上限是在专用 10 核 Mac runner 上调的,GitHub-hosted ubuntu-latest
            // 只有 2 核,68 个饱和清理任务 + 主 HTTP 客户端抢占同一对 vCPU 时,调度延迟能合理超过 600ms
            // 造成假红——与断线本身是否确定发生无关,那条由下面 disconnectRejectionCount 断言把关,
            // 这里只是给"确实会超时断线,只是判定慢了"留够调度抖动余量,放宽到 3000ms。
            assertTrue(elapsedMillis < 3000L,
                    "69th cleanup request exceeded the HTTP caller deadline: " + elapsedMillis);
            assertEquals(rejectionsBefore + 1L, HttpUtils.disconnectRejectionCount());
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("action=rejected")));
        }
        finally
        {
            cleanupRelease.countDown();
            awaitCleanupTasksCompleted(acceptedCleanups, cleanupCompleted);
            awaitDisconnectExecutorIdle();
            awaitHttpExecutorIdle();
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler)
    {
        try
        {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "http-utils-test-server");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            servers.add(server);
            return server;
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static String url(HttpServer server, String path)
    {
        return urlForPort(server.getAddress().getPort(), path);
    }

    private static String urlForPort(int port, String path)
    {
        return "http://127.0.0.1:" + port + path;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody())
        {
            output.write(bytes);
        }
    }

    private static String repeat(char value, int count)
    {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++)
        {
            result.append(value);
        }
        return result.toString();
    }

    private static void awaitDisconnectExecutorIdle() throws InterruptedException
    {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (HttpUtils.pendingDisconnectCleanupCount() != 0
                && System.nanoTime() < deadlineNanos)
        {
            Thread.sleep(10L);
        }
        assertEquals(0, HttpUtils.pendingDisconnectCleanupCount(),
                "disconnect cleanup executor did not become idle");
    }

    private static void awaitHttpExecutorIdle() throws InterruptedException
    {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (HttpUtils.pendingHttpTaskCount() != 0 && System.nanoTime() < deadlineNanos)
        {
            Thread.sleep(10L);
        }
        assertEquals(0, HttpUtils.pendingHttpTaskCount(),
                "HTTP executor worker/queue did not return to zero");
    }

    /**
     * 把 DISCONNECT_EXECUTOR 灌到"零余量"(队列满 + 线程数到 max),返回**被接受**的任务数。
     *
     * <p>不能按 1+64+3=68 硬算容量。core=1/max=4/queue=64 的池子里,"提交第 N 个会不会被接受"
     * 取决于**已存活的空闲 worker 什么时候把任务从队列取走**:上一个饱和测试(30s keepAlive)
     * 会留下 4 个存活 worker,冷池提交 68 个全被接受,而热池 + 2 核调度争抢时队列可能在空闲
     * worker 醒来前先满,此时 workerCount==max 且队列满 ⇒ 后几个任务被**静默拒绝**
     * (cleanupRejected 只打日志),固定 CountDownLatch(68) 于是永远等不满 —— 这就是
     * GitHub-hosted 2 核 runner 上 #376/#377 CI 的假红根因。
     *
     * <p>但"提交到第一次被拒为止"同样不够:第一次拒绝只说明**那一瞬间**队列满且线程数到 max,
     * 此时可能还有 worker 正处在"已被创建、还没从队列取任务"的中途;它随后取走一个 queued task
     * 就会腾出一个队列槽,后面真正要被拒的那次 abortAsync 反而被接受 ⇒
     * {@code assertEquals(rejectionsBefore + 1, ...)} 假红(PR#379 CI 实测 expected 4 actual 3)。
     *
     * <p>所以分两阶段拿到**稳定**的零余量:
     * <ol>
     *   <li>阶段一:一直提交,直到观测到 {@link #DISCONNECT_MAX_POOL_SIZE} 个任务**真的进入
     *       disconnect() 并阻塞在 release 上**。此后这 4 个 worker 在 release 放开前不会再回去
     *       取队列任务,队列不可能再腾出槽位。</li>
     *   <li>阶段二:worker 全部锁死后再把队列灌满,直到观测到一次新的拒绝。此刻的零余量是**稳定**的。</li>
     * </ol>
     * 只统计被接受的任务,让调用方按实际接受数等待排空。
     */
    private static int saturateDisconnectExecutor(CountDownLatch release,
            AtomicInteger completed) throws Exception
    {
        CountDownLatch workersStarted = new CountDownLatch(DISCONNECT_MAX_POOL_SIZE);
        long rejectionsAtStart = HttpUtils.disconnectRejectionCount();
        int accepted = 0;
        // 阶段一:把 max 个 worker 全部逼进 disconnect() 并阻塞。worker 只有在队列满之后才会在
        // core 之上建线程,所以这一阶段必然顺带把队列灌满;一旦出现拒绝就说明线程数已到 max,
        // 不必再提交,余下的只是等这几个 worker 被调度到。
        for (int i = 0; i < SATURATION_SUBMIT_LIMIT
                && workersStarted.getCount() > 0
                && HttpUtils.disconnectRejectionCount() == rejectionsAtStart; i++)
        {
            long rejectionsBeforeSubmit = HttpUtils.disconnectRejectionCount();
            HttpUtils.disconnectAsync(new BlockingDisconnectConnection(
                    release, completed, workersStarted));
            if (HttpUtils.disconnectRejectionCount() == rejectionsBeforeSubmit)
            {
                accepted++;
            }
        }
        assertTrue(workersStarted.await(5, TimeUnit.SECONDS),
                "disconnect executor did not reach " + DISCONNECT_MAX_POOL_SIZE
                        + " blocked workers; still missing " + workersStarted.getCount());

        // 阶段二:worker 已全部锁死,队列不会再腾槽,这时灌到真被拒才是**稳定**的零余量。
        long rejectionsBeforeFill = HttpUtils.disconnectRejectionCount();
        for (int i = 0; i < SATURATION_SUBMIT_LIMIT
                && HttpUtils.disconnectRejectionCount() == rejectionsBeforeFill; i++)
        {
            HttpUtils.disconnectAsync(new BlockingDisconnectConnection(release, completed, null));
            if (HttpUtils.disconnectRejectionCount() == rejectionsBeforeFill)
            {
                accepted++;
            }
        }
        assertTrue(HttpUtils.disconnectRejectionCount() > rejectionsBeforeFill,
                "disconnect executor never rejected: still had spare capacity after "
                        + accepted + " accepted cleanup tasks");
        return accepted;
    }

    /**
     * 等待饱和阶段**被接受**的清理任务全部跑完。失败信息带 accepted/completed 两个数,
     * 直接指认症状:少掉的那几个是被静默拒绝的,不是"跑得慢"。
     */
    private static void awaitCleanupTasksCompleted(int accepted, AtomicInteger completed)
            throws InterruptedException
    {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (completed.get() < accepted && System.nanoTime() < deadlineNanos)
        {
            Thread.sleep(10L);
        }
        assertEquals(accepted, completed.get(),
                "saturated cleanup tasks did not drain: accepted=" + accepted
                        + " completed=" + completed.get());
    }

    private static class RecordingHttpURLConnection extends HttpURLConnection
    {
        private RecordingHttpURLConnection() throws Exception
        {
            super(new URL("http://127.0.0.1/test"));
        }

        @Override
        public void disconnect()
        {
        }

        @Override
        public boolean usingProxy()
        {
            return false;
        }

        @Override
        public void connect()
        {
        }
    }

    private static final class BlockingDisconnectConnection extends RecordingHttpURLConnection
    {
        private final CountDownLatch release;
        private final AtomicInteger completed;
        private final CountDownLatch started;

        private BlockingDisconnectConnection(CountDownLatch release, AtomicInteger completed)
                throws Exception
        {
            this(release, completed, null);
        }

        private BlockingDisconnectConnection(CountDownLatch release, AtomicInteger completed,
                CountDownLatch started) throws Exception
        {
            this.release = release;
            this.completed = completed;
            this.started = started;
        }

        @Override
        public void disconnect()
        {
            if (started != null)
            {
                started.countDown();
            }
            try
            {
                release.await();
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                completed.incrementAndGet();
            }
        }
    }

    private static final class RawRequestServer implements AutoCloseable
    {
        private final ServerSocket server;
        private final int dropRequests;
        private final AtomicInteger requestsSeen = new AtomicInteger();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread acceptor;

        private RawRequestServer(int dropRequests) throws IOException
        {
            this.dropRequests = dropRequests;
            this.server = new ServerSocket();
            this.server.bind(new InetSocketAddress("127.0.0.1", 0));
            this.acceptor = new Thread(this::acceptRequests, "raw-http-replay-server");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        private String url(String path)
        {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        private void acceptRequests()
        {
            while (running.get())
            {
                try (Socket socket = server.accept())
                {
                    readCompleteRequest(socket.getInputStream());
                    int count = requestsSeen.incrementAndGet();
                    if (count > dropRequests)
                    {
                        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    }
                }
                catch (IOException error)
                {
                    if (running.get())
                    {
                        failure.compareAndSet(null, error);
                    }
                }
            }
        }

        private static void readCompleteRequest(InputStream input) throws IOException
        {
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int matched = 0;
            int next;
            byte[] boundary = new byte[] { '\r', '\n', '\r', '\n' };
            while ((next = input.read()) != -1)
            {
                headers.write(next);
                matched = next == boundary[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
                if (matched == boundary.length)
                {
                    break;
                }
            }
            if (matched != boundary.length)
            {
                throw new EOFException("request headers incomplete");
            }
            String headerText = new String(headers.toByteArray(), StandardCharsets.US_ASCII);
            int contentLength = 0;
            for (String line : headerText.split("\\r\\n"))
            {
                if (line.toLowerCase().startsWith("content-length:"))
                {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            for (int read = 0; read < contentLength; read++)
            {
                if (input.read() == -1)
                {
                    throw new EOFException("request body incomplete");
                }
            }
        }

        private int awaitStableRequestsSeen() throws Exception
        {
            int previous = -1;
            for (int stable = 0; stable < 3;)
            {
                Thread.sleep(50L);
                int current = requestsSeen.get();
                stable = current == previous ? stable + 1 : 0;
                previous = current;
            }
            if (failure.get() != null)
            {
                throw new AssertionError(failure.get());
            }
            return requestsSeen.get();
        }

        @Override
        public void close() throws Exception
        {
            running.set(false);
            server.close();
            acceptor.join(1_000L);
        }
    }

    private static final class SaturatedConnectServer implements AutoCloseable
    {
        private final ServerSocket server;
        private final List<Socket> fillers = new ArrayList<>();

        private SaturatedConnectServer() throws Exception
        {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.getLocalPort());
            boolean saturated = false;
            for (int i = 0; i < 256; i++)
            {
                Socket filler = new Socket();
                try
                {
                    filler.connect(address, 50);
                    fillers.add(filler);
                }
                catch (SocketTimeoutException expected)
                {
                    filler.close();
                    saturated = true;
                    break;
                }
            }
            if (!saturated)
            {
                close();
                throw new IllegalStateException("could not saturate loopback connect backlog");
            }
        }

        private String url()
        {
            return "http://127.0.0.1:" + server.getLocalPort() + "/blocked-connect";
        }

        @Override
        public void close() throws Exception
        {
            for (Socket filler : fillers)
            {
                filler.close();
            }
            server.close();
        }
    }

}
