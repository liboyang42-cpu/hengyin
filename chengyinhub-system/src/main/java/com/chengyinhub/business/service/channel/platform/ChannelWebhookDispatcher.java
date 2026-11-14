package com.chengyinhub.business.service.channel.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;

/**
 * 频道出站 Webhook 投递器（发件箱 im_webhook_delivery → 订阅 url）。
 * <ul>
 * <li>取件：PENDING（到期）、FAILED（next_retry_at 到期）、INFLIGHT（租约过期，上次结果未知）按 state+attempt CAS 转 INFLIGHT、attempt+1。</li>
 * <li>签名：{@code X-Chengyin-Signature: v1=hex(HMAC-SHA256(secret, timestamp + "." + nonce + "." + body))}，
 *     另带 X-Chengyin-Timestamp（epoch 秒）、X-Chengyin-Nonce（一次性 32 位 hex）、X-Chengyin-Event、X-Chengyin-Delivery、
 *     X-Chengyin-Key-Version。密钥是订阅的 PiiCryptoService 密文，只在内存里解开。</li>
 * <li>结果：2xx → SENT；408/429/5xx/网络错误 → FAILED，按 base·2^(attempt-1)（±20% 抖动，封顶 max）重排；
 *     其余 4xx、地址指向非公网、密钥不可恢复，或第 {@value ChannelWebhookService#MAX_RETRY_ATTEMPT} 次仍失败 → DEAD（死信，可经重试路由手动重投）；
 *     订阅已暂停/删除 → CANCELLED。状态与原因经既有 /deliveries 路由可见。</li>
 * <li>不跟随重定向；只发 https；发送前解析主机，任一地址为回环/内网/链路本地/组播/CGNAT 即拒（DNS 重绑定窗口未覆盖）。</li>
 * </ul>
 * 开关 {@code chengyin.im.webhook.dispatch.enabled}（默认关）；未配 PII 加密密钥时不取件。
 */
@Component
public class ChannelWebhookDispatcher
{
    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookDispatcher.class);
    public static final String HEADER_TIMESTAMP = "X-Chengyin-Timestamp";
    public static final String HEADER_NONCE = "X-Chengyin-Nonce";
    public static final String HEADER_SIGNATURE = "X-Chengyin-Signature";
    public static final String HEADER_EVENT = "X-Chengyin-Event";
    public static final String HEADER_DELIVERY = "X-Chengyin-Delivery";
    public static final String HEADER_KEY_VERSION = "X-Chengyin-Key-Version";
    static final int SNIPPET_LIMIT = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 一次 HTTP 投递的结果；status 为 null 表示没拿到响应。 */
    public record Response(int status, String body) { }

    /** 出站传输（测试替换）。 */
    public interface Transport
    {
        Response post(URI uri, Map<String, String> headers, byte[] body) throws IOException;
    }

    /** 主机解析（测试替换）。 */
    public interface Resolver
    {
        InetAddress[] resolve(String host) throws IOException;
    }

    record Outcome(String state, String reason, Integer httpStatus, Long latencyMs, String snippet, Long nextRetryMs) { }

    @Autowired
    private ChannelWebhookMapper hooks;
    @Autowired
    private PiiCryptoService crypto;

    @Value("${chengyin.im.webhook.dispatch.enabled:false}")
    private boolean enabled;
    @Value("${chengyin.im.webhook.dispatch.batch:20}")
    private int batch = 20;
    @Value("${chengyin.im.webhook.dispatch.lease-ms:60000}")
    private long leaseMs = 60_000;
    @Value("${chengyin.im.webhook.dispatch.backoff-base-ms:30000}")
    private long backoffBaseMs = 30_000;
    @Value("${chengyin.im.webhook.dispatch.backoff-max-ms:3600000}")
    private long backoffMaxMs = 3_600_000;
    @Value("${chengyin.im.webhook.dispatch.connect-timeout-ms:5000}")
    private long connectTimeoutMs = 5_000;
    @Value("${chengyin.im.webhook.dispatch.request-timeout-ms:10000}")
    private long requestTimeoutMs = 10_000;

    private volatile Transport transport;
    private Resolver resolver = InetAddress::getAllByName;
    private volatile boolean warnedCrypto;

    @Scheduled(fixedDelayString = "${chengyin.im.webhook.dispatch.interval-ms:5000}",
            initialDelayString = "${chengyin.im.webhook.dispatch.initial-delay-ms:15000}")
    public void tick()
    {
        if (!enabled) return;
        if (crypto == null || !crypto.isEnabled())
        {
            if (!warnedCrypto) log.error("[webhook] 投递器已开启但未配 chengyin.pii.key，无法解开签名密钥，不取件");
            warnedCrypto = true;
            return;
        }
        try
        {
            runOnce();
        }
        catch (RuntimeException e)
        {
            log.error("[webhook] 投递轮次失败 type={}", e.getClass().getSimpleName());
        }
    }

    /** 处理一批到期投递；返回本轮取到并落了结果的条数。 */
    public int runOnce()
    {
        int done = 0;
        for (Map<String, Object> row : hooks.selectDueDeliveries(batch))
        {
            if (dispatch(row)) done++;
        }
        return done;
    }

    boolean dispatch(Map<String, Object> due)
    {
        long id = ChannelGuildAccess.toLong(due.get("id"));
        String state = String.valueOf(due.get("state"));
        int attempt = (int) ChannelGuildAccess.toLong(due.get("attempt"));
        if (hooks.claimDelivery(id, state, attempt, leaseMs) != 1) return false;   // 别的实例取走了
        int current = attempt + 1;
        Map<String, Object> delivery = hooks.selectDelivery(id);
        long subscriptionId = ChannelGuildAccess.toLong(due.get("subscriptionId"));
        Outcome outcome;
        try
        {
            outcome = attempt(delivery, hooks.selectSubscriptionForDispatch(subscriptionId), current);
        }
        catch (RuntimeException e)
        {
            log.warn("[webhook] 投递 {} 处理异常: {}", id, e.getClass().getSimpleName());
            outcome = retryOrDead(current, "INTERNAL_ERROR", null, null, null);
        }
        Map<String, Object> finish = new LinkedHashMap<>();
        finish.put("id", id);
        finish.put("attempt", current);
        finish.put("state", outcome.state());
        finish.put("reason", outcome.reason());
        finish.put("httpStatus", outcome.httpStatus());
        finish.put("latencyMs", outcome.latencyMs());
        finish.put("responseSnippet", outcome.snippet());
        finish.put("nextRetryMs", outcome.nextRetryMs());
        if (hooks.finishDelivery(finish) != 1) return false;                       // 租约过期后被重取，结果以新一次为准
        if (!"CANCELLED".equals(outcome.state()))
            hooks.recordSubscriptionOutcome(subscriptionId, "SENT".equals(outcome.state()), "SENT".equals(outcome.state()) ? null : outcome.reason());
        return true;
    }

    Outcome attempt(Map<String, Object> delivery, Map<String, Object> sub, int current)
    {
        if (delivery == null) return new Outcome("CANCELLED", "DELIVERY_MISSING", null, null, null, null);
        if (sub == null || ChannelGuildAccess.toLong(sub.get("deleted")) != 0 || ChannelGuildAccess.toLong(sub.get("status")) != 1)
            return new Outcome("CANCELLED", "SUBSCRIPTION_INACTIVE", null, null, null, null);
        Object cipher = sub.get("secretCipher");
        if (cipher == null) return dead("SECRET_UNRECOVERABLE");
        String secret;
        try
        {
            secret = crypto.decrypt(cipher.toString());
        }
        catch (RuntimeException e)
        {
            return dead("SECRET_UNREADABLE");
        }
        URI uri;
        try
        {
            uri = URI.create(String.valueOf(sub.get("url")));
            ChannelWebhookService.url(uri.toString(), true);
            if (!publicHost(uri.getHost())) return dead("URL_BLOCKED");
        }
        catch (ChannelRejection | IllegalArgumentException e)
        {
            return dead("URL_BLOCKED");
        }
        catch (IOException e)
        {
            return retryOrDead(current, "DNS_UNRESOLVED", null, null, null);
        }

        byte[] body = body(delivery, current);
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = HexFormat.of().formatHex(randomBytes(16));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("User-Agent", "Chengyin-Webhook/1");
        headers.put(HEADER_EVENT, String.valueOf(delivery.get("eventType")));
        headers.put(HEADER_DELIVERY, String.valueOf(ChannelGuildAccess.toLong(delivery.get("id"))));
        headers.put(HEADER_KEY_VERSION, String.valueOf(ChannelGuildAccess.toLong(sub.get("keyVersion"))));
        headers.put(HEADER_TIMESTAMP, String.valueOf(timestamp));
        headers.put(HEADER_NONCE, nonce);
        headers.put(HEADER_SIGNATURE, "v1=" + sign(secret, timestamp, nonce, body));
        long started = System.nanoTime();
        Response response;
        try
        {
            response = transport().post(uri, headers, body);
        }
        catch (java.net.http.HttpTimeoutException e)
        {
            return retryOrDead(current, "TIMEOUT", null, elapsed(started), null);
        }
        catch (IOException e)
        {
            return retryOrDead(current, "NETWORK_ERROR", null, elapsed(started), null);
        }
        Long latency = elapsed(started);
        int status = response.status();
        String snippet = snippet(response.body());
        if (status >= 200 && status < 300) return new Outcome("SENT", null, status, latency, snippet, null);
        String reason = "HTTP_" + status;
        if (status == 408 || status == 429 || status >= 500) return retryOrDead(current, reason, status, latency, snippet);
        return new Outcome("DEAD", reason, status, latency, snippet, null);
    }

    private Outcome retryOrDead(int current, String reason, Integer status, Long latency, String snippet)
    {
        if (current >= ChannelWebhookService.MAX_RETRY_ATTEMPT)
            return new Outcome("DEAD", "MAX_ATTEMPTS:" + reason, status, latency, snippet, null);
        return new Outcome("FAILED", reason, status, latency, snippet, backoff(current));
    }

    private static Outcome dead(String reason) { return new Outcome("DEAD", reason, null, null, null, null); }

    /** base·2^(attempt-1)，±20% 抖动，封顶 max。 */
    long backoff(int attempt)
    {
        double raw = backoffBaseMs * Math.pow(2, Math.max(0, attempt - 1));
        double capped = Math.min(raw, backoffMaxMs);
        return Math.max(1L, Math.round(capped * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
    }

    /** 投递正文：{delivery_id, event_id, event_type, attempt, created_at, payload}；payload 即投递行落下的原样 JSON。 */
    static byte[] body(Map<String, Object> delivery, int attempt)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delivery_id", String.valueOf(ChannelGuildAccess.toLong(delivery.get("id"))));
        out.put("event_id", delivery.get("eventId"));
        out.put("event_type", delivery.get("eventType"));
        out.put("attempt", attempt);
        out.put("created_at", ChannelWebhookService.time(delivery.get("createTime")));
        Object payload = delivery.get("payloadJson");
        out.put("payload", payload == null ? null : ChannelReceipts.decode(payload.toString()));
        return ChannelWebhookService.encode(out).getBytes(StandardCharsets.UTF_8);
    }

    /** hex(HMAC-SHA256(secret, timestamp + "." + nonce + "." + body))，入站验签用同一个构造。 */
    public static String sign(String secret, long timestamp, String nonce, byte[] body)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "." + nonce + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    boolean publicHost(String host) throws IOException
    {
        if (host == null || ChannelWebhookService.privateHost(host)) return false;
        InetAddress[] all = resolver.resolve(host);
        if (all == null || all.length == 0) return false;
        for (InetAddress a : all)
            if (!publicAddress(a)) return false;
        return true;
    }

    static boolean publicAddress(InetAddress a)
    {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return false;
        if (a instanceof Inet4Address)
            return !ChannelWebhookService.privateHost(a.getHostAddress());
        if (a instanceof Inet6Address)
            return (a.getAddress()[0] & 0xfe) != 0xfc;                             // fc00::/7 唯一本地
        return false;
    }

    private Transport transport()
    {
        Transport t = transport;
        if (t != null) return t;
        synchronized (this)
        {
            if (transport == null) transport = new HttpTransport(connectTimeoutMs, requestTimeoutMs);
            return transport;
        }
    }

    private static Long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    static String snippet(String body)
    {
        if (body == null || body.isEmpty()) return null;
        String s = body.length() > SNIPPET_LIMIT ? body.substring(0, SNIPPET_LIMIT) : body;
        return s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?");
    }

    private static byte[] randomBytes(int n)
    {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** java.net.http：不跟随重定向，连接/请求超时，响应只读前 SNIPPET_LIMIT 个字节。 */
    static final class HttpTransport implements Transport
    {
        private final HttpClient client;
        private final Duration requestTimeout;

        HttpTransport(long connectTimeoutMs, long requestTimeoutMs)
        {
            this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
            this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        }

        @Override
        public Response post(URI uri, Map<String, String> headers, byte[] body) throws IOException
        {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(request::header);
            try
            {
                HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body())
                {
                    return new Response(response.statusCode(), new String(in.readNBytes(SNIPPET_LIMIT), StandardCharsets.UTF_8));
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
