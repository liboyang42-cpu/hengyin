package com.chengyinhub.common.utils.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URI;
import java.util.Objects;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.springframework.http.MediaType;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 通用 HTTP 发送方法。
 *
 * @author ruoyi
 */
public class HttpUtils
{
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    private static final String CONNECT_TIMEOUT_PROPERTY = "chengyinhub.http.connectTimeoutMs";
    private static final String READ_TIMEOUT_PROPERTY = "chengyinhub.http.readTimeoutMs";
    private static final String TOTAL_TIMEOUT_PROPERTY = "chengyinhub.http.totalTimeoutMs";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_TOTAL_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int IDEMPOTENT_GET_MAX_ATTEMPTS = 2;
    private static final int GET_RETRY_BACKOFF_MS = 50;

    private static final CloseableHttpClient HTTP_CLIENT = buildHttpClient(false);
    private static final CloseableHttpClient TRUST_ANY_HTTP_CLIENT = buildHttpClient(true);

    private static final ThreadPoolExecutor HTTP_EXECUTOR = new ThreadPoolExecutor(
            8, 32, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
            new DaemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor DISCONNECT_EXECUTOR = new ThreadPoolExecutor(
            1, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            new DaemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicLong DISCONNECT_REJECTIONS = new AtomicLong();

    /** 向指定 URL 发送 GET 请求；默认不重试。 */
    public static String sendGet(String url)
    {
        return sendGet(url, StringUtils.EMPTY);
    }

    /** 向指定 URL 发送带查询参数的 GET 请求；默认不重试。 */
    public static String sendGet(String url, String param)
    {
        return sendGet(url, param, Constants.UTF8);
    }

    /** 向指定 URL 发送带查询参数的 GET 请求；默认不重试。 */
    public static String sendGet(String url, String param, String contentType)
    {
        return sendGet(url, param, contentType, false);
    }

    /** 仅供调用方确认幂等的 GET 使用，最多重试一次。 */
    public static String sendGetWithRetry(String url)
    {
        return sendGetWithRetry(url, StringUtils.EMPTY, Constants.UTF8);
    }

    /** 仅供调用方确认幂等的 GET 使用，最多重试一次。 */
    public static String sendGetWithRetry(String url, String param)
    {
        return sendGetWithRetry(url, param, Constants.UTF8);
    }

    /** 仅供调用方确认幂等的 GET 使用，最多重试一次。 */
    public static String sendGetWithRetry(String url, String param, String contentType)
    {
        return sendGet(url, param, contentType, true);
    }

    private static String sendGet(String url, String param, String contentType, boolean retry)
    {
        String requestUrl = StringUtils.isNotBlank(param) ? url + "?" + param : url;
        return execute("GET", requestUrl, null, null, contentType, false, retry);
    }

    /** 向指定 URL 发送表单 POST 请求。 */
    public static String sendPost(String url, String param)
    {
        return sendPost(url, param, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    /** 向指定 URL 发送 POST 请求。 */
    public static String sendPost(String url, String param, String contentType)
    {
        byte[] body = param == null ? new byte[0] : param.getBytes(StandardCharsets.UTF_8);
        return execute("POST", url, body, contentType, Constants.UTF8, false, false);
    }

    /**
     * Authenticated JSON POST, exactly one secure attempt. deadlineNanos is absolute System.nanoTime.
     * The lease is consumed once; caller close cannot release a submitted/running physical attempt.
     */
    public static String sendJsonPost(String url, String json, String bearer,
            long deadlineNanos, AttemptLease lease)
    {
        return sendJsonPost(url, json, bearer, deadlineNanos, lease, java.util.Map.of());
    }

    public static String sendJsonPost(String url, String json, String bearer,
            long deadlineNanos, AttemptLease lease, java.util.Map<String, String> additionalHeaders)
    {
        return sendJsonPostEncoded(url, json == null ? null : () -> json.getBytes(StandardCharsets.UTF_8),
                bearer, deadlineNanos, lease, additionalHeaders);
    }

    /**
     * Takes exclusive ownership of fresh UTF-8 JSON bytes. The caller must never mutate/reuse them,
     * including after a timeout: the original physical worker may still own the request entity.
     * Same authenticated single-attempt path; no body clone or String re-encoding.
     */
    public static String sendJsonPostBytes(String url, byte[] json, String bearer,
            long deadlineNanos, AttemptLease lease)
    {
        return sendJsonPostEncoded(url, json == null ? null : () -> json,
                bearer, deadlineNanos, lease, java.util.Map.of());
    }

    private static String sendJsonPostEncoded(String url, java.util.function.Supplier<byte[]> encode, String bearer,
            long deadlineNanos, AttemptLease lease, java.util.Map<String, String> additionalHeaders)
    {
        if (lease == null || !lease.transfer())
        {
            throw notSent("third-party HTTP lease unavailable");
        }
        boolean handedToAttempt = false;
        try
        {
            URL target = jsonTarget(url);
            java.util.Map<String, String> headers = checkedJsonHeaders(additionalHeaders);
            if (bearer == null || bearer.isBlank() || bearer.chars().anyMatch(c -> c <= 32 || c >= 127))
            {
                throw notSent("invalid third-party HTTP authorization");
            }
            if (encode == null || Thread.currentThread().isInterrupted())
            {
                throw notSent("third-party HTTP request not started");
            }
            RequestPolicy current = RequestPolicy.current();
            RequestPolicy policy = new RequestPolicy(
                    Math.min(current.connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS),
                    Math.min(current.readTimeoutMs, DEFAULT_READ_TIMEOUT_MS),
                    Math.min(current.totalTimeoutMs, DEFAULT_TOTAL_TIMEOUT_MS));
            long now = System.nanoTime();
            long remaining = deadlineNanos - now;
            if (remaining <= 0) throw notSent("third-party HTTP total timeout");
            long boundedDeadline = now + Math.min(remaining,
                    TimeUnit.MILLISECONDS.toNanos(policy.totalTimeoutMs));
            byte[] body = encode.get();
            if (remainingMillis(boundedDeadline) <= 0) throw notSent("third-party HTTP total timeout");
            handedToAttempt = true;
            return executeAttempt("POST", target, body, MediaType.APPLICATION_JSON_VALUE,
                    Constants.UTF8, false, policy, boundedDeadline, bearer, lease, headers).body;
        }
        catch (HttpRequestException error)
        {
            throw error;
        }
        catch (RuntimeException error)
        {
            // Never export a URL/header/serializer exception or its original cause.
            if (handedToAttempt) throw new HttpRequestException("third-party HTTP request failed", 0, false);
            throw notSent("invalid third-party HTTP request");
        }
        finally
        {
            if (!handedToAttempt) lease.complete();
        }
    }

    /** Only the existing ASR non-streaming control is accepted; never forward arbitrary auth/host/framing headers. */
    private static java.util.Map<String, String> checkedJsonHeaders(java.util.Map<String, String> headers)
    {
        if (headers == null) throw notSent("invalid third-party HTTP headers");
        if (headers.isEmpty()) return java.util.Map.of();
        if (headers.size() != 1 || !"disable".equals(headers.get("X-DashScope-SSE")))
            throw notSent("invalid third-party HTTP headers");
        return java.util.Map.of("X-DashScope-SSE", "disable");
    }


    private static URL jsonTarget(String url)
    {
        try
        {
            URI target = new URI(url);
            String scheme = target.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || target.getHost() == null || target.getRawUserInfo() != null
                    || target.getRawQuery() != null || target.getRawFragment() != null
                    || target.getPort() == 0 || target.getPort() > 65535)
            {
                throw notSent("invalid third-party HTTP URL");
            }
            return target.toURL();
        }
        catch (Exception error)
        {
            throw notSent("invalid third-party HTTP URL");
        }
    }

    private static HttpRequestException notSent(String message)
    {
        return new HttpRequestException(message, 0, false, null,
                HttpRequestException.SendDisposition.NOT_SENT);
    }

    public static String sendSSLPost(String url, String param)
    {
        return sendSSLPost(url, param, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    /**
     * 保留旧接口的查询参数语义；该接口不做重试。
     */
    public static String sendSSLPost(String url, String param, String contentType)
    {
        String requestUrl = StringUtils.isNotBlank(param) ? url + "?" + param : url;
        return execute("POST", requestUrl, null, contentType, Constants.UTF8, true, false);
    }

    private static String execute(String method, String requestUrl, byte[] body,
            String requestContentType, String responseCharset, boolean trustAnyCertificate,
            boolean retryIdempotentGet)
    {
        URL target = parseTarget(requestUrl);
        RequestPolicy policy = RequestPolicy.current();
        int maxAttempts = retryIdempotentGet && "GET".equals(method)
                ? IDEMPOTENT_GET_MAX_ATTEMPTS : 1;
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(policy.totalTimeoutMs);
        HttpRequestException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            if (remainingMillis(deadlineNanos) <= 0)
            {
                lastError = new HttpRequestException("third-party HTTP total timeout",
                        0, true);
                break;
            }
            try
            {
                AttemptResult result = executeAttempt(method, target, body, requestContentType,
                        responseCharset, trustAnyCertificate, policy, deadlineNanos);
                log.info("third-party HTTP method={} host={} status={} elapsedMs={} requestId={} attempts={}",
                        method, host(target), result.statusCode, elapsedMillis(startedNanos),
                        safeRequestId(result.requestId), attempt);
                return result.body;
            }
            catch (HttpRequestException error)
            {
                lastError = error;
                if (!"GET".equals(method) || !error.isRetryable() || attempt == maxAttempts)
                {
                    break;
                }
                waitBeforeRetry(deadlineNanos);
            }
        }

        HttpRequestException error = lastError == null
                ? new HttpRequestException("third-party HTTP request failed", 0, false)
                : lastError;
        log.warn("third-party HTTP failed method={} host={} status={} elapsedMs={} requestId={} reason={}",
                method, host(target), error.getStatusCode(), elapsedMillis(startedNanos),
                safeRequestId(error.getRequestId()), error.getMessage());
        throw error;
    }

    private static AttemptResult executeAttempt(String method, URL target, byte[] body,
            String requestContentType, String responseCharset, boolean trustAnyCertificate,
            RequestPolicy policy, long deadlineNanos)
    {
        return executeAttempt(method, target, body, requestContentType, responseCharset,
                trustAnyCertificate, policy, deadlineNanos, null, null);
    }

    private static AttemptResult executeAttempt(String method, URL target, byte[] body,
            String requestContentType, String responseCharset, boolean trustAnyCertificate,
            RequestPolicy policy, long deadlineNanos, String bearer, AttemptLease lease)
    {
        return executeAttempt(method, target, body, requestContentType, responseCharset,
                trustAnyCertificate, policy, deadlineNanos, bearer, lease, java.util.Map.of());
    }

    private static AttemptResult executeAttempt(String method, URL target, byte[] body,
            String requestContentType, String responseCharset, boolean trustAnyCertificate,
            RequestPolicy policy, long deadlineNanos, String bearer, AttemptLease lease, java.util.Map<String, String> headers)
    {
        AtomicReference<HttpRequestBase> activeRequest = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AttemptState state = lease == null ? null : new AttemptState(lease);
        FutureTask<AttemptResult> attempt = new FutureTask<>(() -> {
            try
            {
                if (state != null && !state.startWorker()) throw notSent("third-party HTTP request cancelled");
                return executeBlocking(method, target, body, requestContentType, responseCharset,
                        trustAnyCertificate, policy, deadlineNanos, activeRequest, cancelled, bearer, state, headers);
            }
            finally
            {
                if (state != null) state.finishWorker();
            }
        });
        try
        {
            HTTP_EXECUTOR.execute(attempt);
        }
        catch (RejectedExecutionException error)
        {
            if (state != null) state.cancelBeforeDispatch();
            throw classify(new HttpRequestException("third-party HTTP client capacity exceeded", 0, false), state);
        }
        try
        {
            return attempt.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException error)
        {
            cancelAttempt(attempt, state, cancelled, activeRequest, target);
            throw classify(new HttpRequestException("third-party HTTP total timeout", 0, true), state);
        }
        catch (InterruptedException error)
        {
            cancelAttempt(attempt, state, cancelled, activeRequest, target);
            Thread.currentThread().interrupt();
            throw classify(new HttpRequestException("third-party HTTP request interrupted", 0, false), state);
        }
        catch (ExecutionException error)
        {
            Throwable cause = error.getCause();
            HttpRequestException failure = cause instanceof HttpRequestException
                    ? (HttpRequestException) cause
                    : new HttpRequestException("third-party HTTP client failure", 0, false);
            throw classify(failure, state);
        }
    }

    private static void cancelAttempt(FutureTask<?> attempt, AttemptState state,
            AtomicBoolean cancelled, AtomicReference<HttpRequestBase> activeRequest, URL target)
    {
        if (state != null) state.cancelBeforeDispatch();
        cancelled.set(true);
        attempt.cancel(true);
        // This is our own task only. Never purge or cancel another caller's queued work.
        if (state != null) HTTP_EXECUTOR.remove(attempt);
        abortAsync(activeRequest.get(), target);
    }

    private static HttpRequestException classify(HttpRequestException error, AttemptState state)
    {
        if (state == null) return error; // Keep all legacy exception/retry contracts unchanged.
        return new HttpRequestException(error.getMessage(), error.getStatusCode(), false,
                error.getRequestId(), state.isNotSent() ? HttpRequestException.SendDisposition.NOT_SENT
                        : HttpRequestException.SendDisposition.MAY_HAVE_BEEN_SENT);
    }

    private static AttemptResult executeBlocking(String method, URL target, byte[] body,
            String requestContentType, String responseCharset, boolean trustAnyCertificate,
            RequestPolicy policy, long deadlineNanos,
            AtomicReference<HttpRequestBase> activeRequest, AtomicBoolean cancelled,
            String bearer, AttemptState state)
    {
        return executeBlocking(method, target, body, requestContentType, responseCharset,
                trustAnyCertificate, policy, deadlineNanos, activeRequest, cancelled, bearer, state, java.util.Map.of());
    }

    private static AttemptResult executeBlocking(String method, URL target, byte[] body,
            String requestContentType, String responseCharset, boolean trustAnyCertificate,
            RequestPolicy policy, long deadlineNanos,
            AtomicReference<HttpRequestBase> activeRequest, AtomicBoolean cancelled,
            String bearer, AttemptState state, java.util.Map<String, String> headers)
    {
        HttpRequestBase request = null;
        boolean reusable = false;
        try
        {
            if (cancelled.get() || Thread.currentThread().isInterrupted())
            {
                throw new HttpRequestException("third-party HTTP total timeout", 0, true);
            }
            request = createRequest(method, target, body, requestContentType,
                    policy, deadlineNanos);
            if (bearer != null)
            {
                request.setHeader("Authorization", "Bearer " + bearer);
                // A provider/proxy auth challenge must not automatically replay an authenticated POST.
                request.setConfig(RequestConfig.copy(request.getConfig()).setAuthenticationEnabled(false).build());
            }
            for (java.util.Map.Entry<String, String> header : headers.entrySet())
                request.setHeader(header.getKey(), header.getValue());
            activeRequest.set(request);
            if (cancelled.get() || Thread.currentThread().isInterrupted())
            {
                throw new HttpRequestException("third-party HTTP total timeout", 0, true);
            }
            CloseableHttpClient client = trustAnyCertificate
                    ? TRUST_ANY_HTTP_CLIENT : HTTP_CLIENT;
            if (state != null && (remainingMillis(deadlineNanos) <= 0 || !state.enterDispatch()))
            {
                throw notSent("third-party HTTP request cancelled");
            }
            // DISPATCH_ENTERED is conservative: even a DNS/connect error after this point is UNKNOWN.
            try (CloseableHttpResponse response = client.execute(request))
            {
                int statusCode = response.getStatusLine().getStatusCode();
                Header requestIdHeader = response.getFirstHeader("X-Request-Id");
                String requestId = requestIdHeader == null ? null : requestIdHeader.getValue();
                if (statusCode < 200 || statusCode >= 300)
                {
                    throw new HttpRequestException("third-party HTTP status " + statusCode,
                            statusCode, isRetryableStatus(statusCode), requestId);
                }
                HttpEntity entity = response.getEntity();
                long contentLength = entity == null ? 0L : entity.getContentLength();
                if (contentLength > MAX_RESPONSE_BYTES)
                {
                    throw new HttpRequestException("third-party HTTP response limit exceeded",
                            statusCode, false, requestId);
                }

                String responseBody = StringUtils.EMPTY;
                if (entity != null)
                {
                    try (InputStream input = entity.getContent())
                    {
                        responseBody = readLimited(input, responseCharset, statusCode, requestId,
                                deadlineNanos);
                    }
                }
                if (remainingMillis(deadlineNanos) <= 0)
                {
                    throw new HttpRequestException("third-party HTTP total timeout",
                            statusCode, true, requestId);
                }
                reusable = true;
                return new AttemptResult(statusCode, requestId, responseBody);
            }
        }
        catch (HttpRequestException e)
        {
            throw e;
        }
        catch (ConnectTimeoutException e)
        {
            throw new HttpRequestException("third-party HTTP connect timeout", 0, true);
        }
        catch (SocketTimeoutException e)
        {
            throw new HttpRequestException("third-party HTTP read timeout", 0, true);
        }
        catch (IOException e)
        {
            if (remainingMillis(deadlineNanos) <= 0)
            {
                throw new HttpRequestException("third-party HTTP total timeout", 0, true);
            }
            throw new HttpRequestException("third-party HTTP network failure ("
                    + e.getClass().getSimpleName() + ")", 0,
                    isRetryableNetworkFailure(e));
        }
        catch (Exception e)
        {
            throw new HttpRequestException("third-party HTTP client failure ("
                    + e.getClass().getSimpleName() + ")", 0, false);
        }
        finally
        {
            if (request != null)
            {
                if (reusable)
                {
                    request.releaseConnection();
                }
                else
                {
                    request.abort();
                }
            }
            activeRequest.compareAndSet(request, null);
        }
    }

    private static HttpRequestBase createRequest(String method, URL target, byte[] body,
            String requestContentType, RequestPolicy policy, long deadlineNanos)
    {
        HttpRequestBase request;
        if ("POST".equals(method))
        {
            HttpPost post = new HttpPost(target.toString());
            if (body != null)
            {
                post.setEntity(new ByteArrayEntity(body));
            }
            request = post;
        }
        else
        {
            request = new HttpGet(target.toString());
        }
        int remaining = Math.max(1, remainingMillis(deadlineNanos));
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(Math.min(policy.connectTimeoutMs, remaining))
                .setSocketTimeout(Math.min(policy.readTimeoutMs, remaining))
                .setConnectionRequestTimeout(Math.min(policy.connectTimeoutMs, remaining))
                .setRedirectsEnabled(false)
                .build());
        request.setHeader("accept", "*/*");
        request.setHeader("connection", "Keep-Alive");
        request.setHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        request.setHeader("Accept-Charset", "utf-8");
        if (StringUtils.isNotBlank(requestContentType))
        {
            request.setHeader("Content-Type", requestContentType);
        }
        return request;
    }

    private static void disconnect(HttpURLConnection connection)
    {
        if (connection != null)
        {
            connection.disconnect();
        }
    }

    private static void abortAsync(HttpRequestBase request, URL target)
    {
        if (request != null)
        {
            try
            {
                DISCONNECT_EXECUTOR.execute(request::abort);
            }
            catch (RejectedExecutionException saturated)
            {
                cleanupRejected(target);
            }
        }
    }

    static void disconnectAsync(HttpURLConnection connection)
    {
        if (connection != null)
        {
            try
            {
                DISCONNECT_EXECUTOR.execute(() -> disconnect(connection));
            }
            catch (RejectedExecutionException saturated)
            {
                cleanupRejected(connection.getURL());
            }
        }
    }

    private static void cleanupRejected(URL target)
    {
        long rejectedCount = DISCONNECT_REJECTIONS.incrementAndGet();
        log.warn("third-party HTTP disconnect cleanup saturated host={} action=rejected rejectedCount={}",
                host(target), rejectedCount);
    }

    static long disconnectRejectionCount()
    {
        return DISCONNECT_REJECTIONS.get();
    }

    static int pendingDisconnectCleanupCount()
    {
        return DISCONNECT_EXECUTOR.getActiveCount() + DISCONNECT_EXECUTOR.getQueue().size();
    }

    static int pendingHttpTaskCount()
    {
        return HTTP_EXECUTOR.getActiveCount() + HTTP_EXECUTOR.getQueue().size();
    }

    private static String readLimited(InputStream input, String charsetName,
            int statusCode, String requestId, long deadlineNanos)
            throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1)
        {
            if (Thread.currentThread().isInterrupted() || remainingMillis(deadlineNanos) <= 0)
            {
                throw new HttpRequestException("third-party HTTP total timeout",
                        statusCode, true, requestId);
            }
            total += read;
            if (total > MAX_RESPONSE_BYTES)
            {
                throw new HttpRequestException("third-party HTTP response limit exceeded",
                        statusCode, false, requestId);
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), Charset.forName(charsetName));
    }

    private static URL parseTarget(String requestUrl)
    {
        try
        {
            return new URL(requestUrl);
        }
        catch (Exception e)
        {
            throw new HttpRequestException("invalid third-party HTTP URL", 0, false);
        }
    }

    private static boolean isRetryableStatus(int statusCode)
    {
        return statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    static boolean isRetryableNetworkFailure(IOException error)
    {
        return error instanceof ConnectException
                || error instanceof ConnectTimeoutException
                || error instanceof SocketTimeoutException
                || error instanceof NoRouteToHostException
                || error instanceof NoHttpResponseException
                || error instanceof EOFException;
    }

    private static CloseableHttpClient buildHttpClient(boolean trustAnyCertificate)
    {
        HttpClientBuilder builder = HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setMaxConnTotal(32)
                .setMaxConnPerRoute(32);
        if (!trustAnyCertificate)
        {
            return builder.build();
        }
        try
        {
            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                    SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build(),
                    NoopHostnameVerifier.INSTANCE);
            return builder.setSSLSocketFactory(sslSocketFactory).build();
        }
        catch (Exception error)
        {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void waitBeforeRetry(long deadlineNanos)
    {
        if (remainingMillis(deadlineNanos) <= GET_RETRY_BACKOFF_MS)
        {
            throw new HttpRequestException("third-party HTTP total timeout", 0, true);
        }
        try
        {
            Thread.sleep(GET_RETRY_BACKOFF_MS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new HttpRequestException("third-party HTTP retry interrupted", 0, false);
        }
    }

    private static int remainingMillis(long deadlineNanos)
    {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0)
        {
            return 0;
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
    }

    private static long elapsedMillis(long startedNanos)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String host(URL target)
    {
        return target.getPort() < 0 ? target.getHost() : target.getHost() + ":" + target.getPort();
    }

    private static String safeRequestId(String requestId)
    {
        return StringUtils.isBlank(requestId) ? "-" : "present";
    }

    private static int positiveIntProperty(String name, int defaultValue, int upperBound)
    {
        String configured = System.getProperty(name);
        if (StringUtils.isBlank(configured))
        {
            return defaultValue;
        }
        try
        {
            int value = Integer.parseInt(configured);
            return value > 0 ? Math.min(value, upperBound) : defaultValue;
        }
        catch (NumberFormatException ignored)
        {
            return defaultValue;
        }
    }

    static final class RequestPolicy
    {
        private final int connectTimeoutMs;
        private final int readTimeoutMs;
        private final int totalTimeoutMs;

        RequestPolicy(int connectTimeoutMs, int readTimeoutMs, int totalTimeoutMs)
        {
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.totalTimeoutMs = totalTimeoutMs;
        }

        static RequestPolicy current()
        {
            return new RequestPolicy(
                    positiveIntProperty(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT_MS, 60_000),
                    positiveIntProperty(READ_TIMEOUT_PROPERTY, DEFAULT_READ_TIMEOUT_MS, 60_000),
                    positiveIntProperty(TOTAL_TIMEOUT_PROPERTY, DEFAULT_TOTAL_TIMEOUT_MS, 120_000));
        }
    }

    /** A release-only lease: close releases only if transport has not claimed ownership. */
    public static final class AttemptLease implements AutoCloseable
    {
        private final Runnable release;
        private final AtomicReference<LeaseOwner> owner = new AtomicReference<>(LeaseOwner.CALLER);
        private final AtomicBoolean released = new AtomicBoolean();

        public AttemptLease(Runnable release)
        {
            this.release = Objects.requireNonNull(release, "release is required");
        }

        boolean transfer() { return owner.compareAndSet(LeaseOwner.CALLER, LeaseOwner.TRANSPORT); }

        @Override
        public void close()
        {
            if (owner.compareAndSet(LeaseOwner.CALLER, LeaseOwner.CLOSED)) complete();
        }

        void complete()
        {
            if (released.compareAndSet(false, true)) release.run();
        }
    }

    private enum LeaseOwner { CALLER, TRANSPORT, CLOSED }

    /** Package visibility is only for deterministic cancellation/lifetime tests. */
    static final class AttemptState
    {
        private enum Phase { QUEUED, PREPARING, DISPATCH_ENTERED, CANCELLED_QUEUED,
            CANCELLED_PREPARING, FINISHED_NOT_SENT, FINISHED_DISPATCHED }
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
        private final AttemptLease lease;

        AttemptState(AttemptLease lease) { this.lease = lease; }
        boolean startWorker() { return phase.compareAndSet(Phase.QUEUED, Phase.PREPARING); }
        boolean enterDispatch() { return phase.compareAndSet(Phase.PREPARING, Phase.DISPATCH_ENTERED); }

        boolean cancelBeforeDispatch()
        {
            for (;;)
            {
                Phase current = phase.get();
                if (current == Phase.QUEUED)
                {
                    if (!phase.compareAndSet(current, Phase.CANCELLED_QUEUED)) continue;
                    lease.complete(); // No worker won startWorker; every future start is forbidden.
                    return true;
                }
                if (current == Phase.PREPARING)
                {
                    if (!phase.compareAndSet(current, Phase.CANCELLED_PREPARING)) continue;
                    return true; // A physical worker exists; only its finally may release.
                }
                return isNotSent();
            }
        }

        boolean isNotSent()
        {
            Phase current = phase.get();
            return current != Phase.DISPATCH_ENTERED && current != Phase.FINISHED_DISPATCHED;
        }

        void finishWorker()
        {
            phase.updateAndGet(current -> current == Phase.DISPATCH_ENTERED || current == Phase.FINISHED_DISPATCHED
                    ? Phase.FINISHED_DISPATCHED : Phase.FINISHED_NOT_SENT);
            lease.complete();
        }
    }

    private static final class AttemptResult
    {
        private final int statusCode;
        private final String requestId;
        private final String body;

        private AttemptResult(int statusCode, String requestId, String body)
        {
            this.statusCode = statusCode;
            this.requestId = requestId;
            this.body = body;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory
    {
        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(runnable, "http-utils-request");
            thread.setDaemon(true);
            return thread;
        }
    }

}
