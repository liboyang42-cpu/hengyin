package com.chengyinhub.business.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 JDK {@link HttpURLConnection} 的 webhook 投递实现:
 * 显式 connect/read timeout,限制响应体读取,直接返回状态码。
 *
 * <p>与 {@code HttpUtils.sendPost} 的区别:
 * <ul>
 *   <li>非 2xx 不作为异常抛出,而是作为普通 TransportResponse 返回;</li>
 *   <li>超时与读上限均可通过构造函数按 use-case 设定,不走全局系统属性;</li>
 *   <li>不经过共享线程池,告警投递不占业务 HTTP 资源。</li>
 * </ul>
 */
public class HttpWebhookTransport implements WebhookTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookTransport.class);

    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4096;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxResponseBytes;

    public HttpWebhookTransport(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, DEFAULT_MAX_RESPONSE_BYTES);
    }

    HttpWebhookTransport(int connectTimeoutMs, int readTimeoutMs, int maxResponseBytes) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public TransportResponse post(String url, String jsonBody) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setInstanceFollowRedirects(false);

            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn);
            return new TransportResponse(statusCode, responseBody);
        } catch (SocketTimeoutException e) {
            return new TransportResponse(-1, null);
        } catch (IOException e) {
            return new TransportResponse(-1, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readResponseBody(HttpURLConnection conn) {
        InputStream stream = null;
        try {
            try {
                stream = conn.getInputStream();
            } catch (IOException e) {
                stream = conn.getErrorStream();
            }
            if (stream == null) {
                return "";
            }
            byte[] buf = new byte[maxResponseBytes];
            int total = 0;
            int n;
            while (total < buf.length && (n = stream.read(buf, total, buf.length - total)) != -1) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
            }
        }
    }
}
