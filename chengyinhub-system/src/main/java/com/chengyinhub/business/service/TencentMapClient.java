package com.chengyinhub.business.service;

import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpRequestException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 腾讯位置服务的唯一服务端出口，避免 Key 下发或散落在各业务服务中。 */
@Service
public class TencentMapClient {

    private static final Logger log = LoggerFactory.getLogger(TencentMapClient.class);

    // A process-wide map budget, including retries. No executor, admission queue or timed lease.
    public static final int MAX_IN_FLIGHT = 4;
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_REQUEST_CHARACTERS = 8192;
    private static final int MAX_KEY_CHARACTERS = 512;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long TOTAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Semaphore IN_FLIGHT = new Semaphore(MAX_IN_FLIGHT);

    @Value("${tencent.map.key:}")
    private String mapKey;

    @Value("${tencent.map.enabled:false}")
    private boolean enabled;

    /** 启动时提示：未启用或缺 Key 时 /api/map/reverse-geocode 只会返回 manualInputRequired。绝不打印 Key。 */
    @PostConstruct
    public void warnIfNotReady() {
        if (!enabled) {
            log.warn("腾讯位置服务未启用(tencent.map.enabled=false)：首页城市定位将回退为手动选择城市。"
                    + "请在应用机设置 CHENGYIN_TENCENT_MAP_ENABLED=true 与 CHENGYIN_TENCENT_MAP_KEY");
        } else if (StringUtils.isEmpty(mapKey)) {
            log.warn("腾讯位置服务已启用但未配置 Key(tencent.map.key 为空)：首页城市定位将回退为手动选择城市。"
                    + "请设置 CHENGYIN_TENCENT_MAP_KEY");
        }
    }

    public boolean isReady() {
        return enabled && !StringUtils.isEmpty(mapKey);
    }

    public String get(String endpoint) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String key = mapKey;
        if (!isReady() || StringUtils.isEmpty(key) || endpoint == null || key.length() > MAX_KEY_CHARACTERS
                || endpoint.length() > MAX_REQUEST_CHARACTERS - key.length() - 5) {
            return null;
        }
        if (!IN_FLIGHT.tryAcquire()) return null;
        String separator = endpoint.contains("?") ? "&" : "?";
        try {
            return request(endpoint + separator + "key=" + key);
        } finally {
            // Synchronous transport: a timeout never frees a permit while a background worker runs.
            IN_FLIGHT.release();
        }
    }

    /** 保持 HTTP 调用可替换，以便离线验证配置失败和错误处理。 */
    protected String request(String url) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        final URL target;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"apis.map.qq.com".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            target = uri.toURL();
        } catch (Exception invalid) {
            throw new HttpRequestException("map endpoint rejected", 0, false);
        }
        long started = nanoTime();
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection connection = null;
            HttpRequestException failure;
            try {
                int remaining = remainingMillis(started);
                connection = openConnection(target);
                connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, remaining));
                connection.setReadTimeout(Math.min(READ_TIMEOUT_MS, remaining));
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new HttpRequestException("map HTTP status rejected", status,
                            status == 502 || status == 503 || status == 504);
                }
                long expectedBytes = connection.getContentLengthLong();
                if (expectedBytes > MAX_RESPONSE_BYTES) {
                    throw new HttpRequestException("map response limit exceeded", status, false);
                }
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while (true) {
                        connection.setReadTimeout(Math.min(READ_TIMEOUT_MS, remainingMillis(started)));
                        count = input.read(buffer);
                        if (count < 0) break;
                        if (count > MAX_RESPONSE_BYTES - output.size()) {
                            throw new HttpRequestException("map response limit exceeded", status, false);
                        }
                        output.write(buffer, 0, count);
                    }
                    if (expectedBytes >= 0 && expectedBytes != output.size()) {
                        throw new HttpRequestException("map response incomplete", status, true);
                    }
                    remainingMillis(started);
                    return output.toString(StandardCharsets.UTF_8);
                }
            } catch (HttpRequestException rejected) {
                failure = rejected;
            } catch (IOException error) {
                boolean retryable = error instanceof ConnectException || error instanceof SocketTimeoutException
                        || error instanceof NoRouteToHostException || error instanceof EOFException;
                // Never retain the provider URL (including its key), response or IOException cause.
                failure = new HttpRequestException("map network request failed", 0, retryable);
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (attempt >= 1 || !failure.isRetryable()) throw failure;
            remainingMillis(started);
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new HttpRequestException("map request interrupted", 0, false);
            }
        }
    }

    private int remainingMillis(long started) {
        long remaining = TOTAL_NANOS - (nanoTime() - started);
        if (remaining <= 0) throw new HttpRequestException("map total timeout", 0, false);
        return (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /** Test seams keep the actual synchronous admission/close lifetime, without provider networking. */
    protected HttpURLConnection openConnection(URL target) throws IOException {
        return (HttpURLConnection) target.openConnection();
    }

    protected long nanoTime() {
        return System.nanoTime();
    }
}
