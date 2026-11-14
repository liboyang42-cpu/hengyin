package com.chengyinhub.business.merchantgap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production avatar provider behind {@link MerchantAvatarProvider}: one JSON POST to a configured HTTPS endpoint.
 * Loaded only with {@code chengyin.merchant.avatar.provider=http}; keys (no secrets in the repo, see
 * docs/delivery/be-gap-merchant/PROVIDERS.md):
 * <pre>
 * chengyin.merchant.avatar.provider=http
 * chengyin.merchant.avatar.http.base-url=https://...        # HTTPS only
 * chengyin.merchant.avatar.http.api-key=${CHENGYIN_MERCHANT_AVATAR_API_KEY:}
 * chengyin.merchant.avatar.http.timeout-ms=15000
 * chengyin.merchant.avatar.http.styles=CARTOON,REALISTIC
 * </pre>
 * Request {@code {merchantId, imageUrl, style}}; response {@code {status, modelUrl, thumbUrl, failReason}} with status
 * SUCCEEDED / PENDING / FAILED and HTTPS result URLs. Anything else (missing key, transport error, non-2xx, malformed
 * body) is a FAILED job with a reason code; nothing is retried here.
 */
@Component
@ConditionalOnProperty(name = "chengyin.merchant.avatar.provider", havingValue = "http")
public class HttpMerchantAvatarProvider implements MerchantAvatarProvider {
    private static final Logger log = LoggerFactory.getLogger(HttpMerchantAvatarProvider.class);
    static final Set<String> STATUSES = Set.of("SUCCEEDED", "PENDING", "FAILED");

    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final List<String> styles;
    private final Transport transport;

    /** Seam for tests: one POST, returns {status code, body}. */
    interface Transport {
        Object[] post(URI endpoint, String apiKey, String body, Duration timeout) throws Exception;
    }

    public HttpMerchantAvatarProvider(@Value("${chengyin.merchant.avatar.http.base-url:}") String baseUrl,
            @Value("${chengyin.merchant.avatar.http.api-key:}") String apiKey,
            @Value("${chengyin.merchant.avatar.http.timeout-ms:15000}") long timeoutMs,
            @Value("${chengyin.merchant.avatar.http.styles:CARTOON,REALISTIC}") String styles) {
        this(baseUrl, apiKey, timeoutMs, styles, HttpMerchantAvatarProvider::send);
    }

    HttpMerchantAvatarProvider(String baseUrl, String apiKey, long timeoutMs, String styles, Transport transport) {
        URI parsed = null;
        try {
            if (baseUrl != null && baseUrl.startsWith("https://")) parsed = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        this.endpoint = parsed;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(60_000, timeoutMs)));
        List<String> list = new ArrayList<>();
        for (String s : (styles == null ? "" : styles).split(",")) {
            String style = s.trim().toUpperCase();
            if (style.matches("[A-Z_]{1,32}") && !list.contains(style)) list.add(style);
        }
        this.styles = List.copyOf(list);
        this.transport = transport;
        if (endpoint == null || this.apiKey.isEmpty()) {
            log.warn("[avatar] provider=http but base-url (https) or api-key is missing; every job will fail with PROVIDER_NOT_CONFIGURED");
        }
    }

    @Override
    public String id() { return "http"; }

    @Override
    public List<String> styles() { return styles; }

    @Override
    public Result generate(long merchantId, String imageUrl, String style) {
        if (endpoint == null || apiKey.isEmpty()) return failed("PROVIDER_NOT_CONFIGURED");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("merchantId", merchantId);
        request.put("imageUrl", imageUrl);
        request.put("style", style);
        Object[] answer;
        try {
            answer = transport.post(endpoint, apiKey, MerchantGapSupport.JSON.writeValueAsString(request), timeout);
        } catch (Exception e) {
            log.warn("[avatar] provider call failed merchantId={} type={}", merchantId, e.getClass().getSimpleName());
            return failed("PROVIDER_UNAVAILABLE");
        }
        int code = (Integer) answer[0];
        if (code < 200 || code > 299) return failed("PROVIDER_HTTP_" + code);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MerchantGapSupport.JSON.readValue((String) answer[1], Map.class);
            Object status = body.get("status");
            if (!(status instanceof String s) || !STATUSES.contains(s)) return failed("PROVIDER_MALFORMED");
            String model = https(body.get("modelUrl")), thumb = https(body.get("thumbUrl"));
            if ("SUCCEEDED".equals(s) && (model == null || thumb == null)) return failed("PROVIDER_MALFORMED");
            if ("FAILED".equals(s)) {
                Object reason = body.get("failReason");
                return failed(reason instanceof String r && r.matches("[A-Z0-9_]{1,64}") ? r : "PROVIDER_FAILED");
            }
            return new Result(s, model, thumb, null);
        } catch (Exception e) {
            return failed("PROVIDER_MALFORMED");
        }
    }

    private static String https(Object value) {
        return value instanceof String s && s.startsWith("https://") && s.length() <= 1024 ? s : null;
    }

    private static Result failed(String reason) {
        return new Result("FAILED", null, null, reason);
    }

    private static Object[] send(URI endpoint, String apiKey, String body, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Object[] {response.statusCode(), response.body()};
    }
}
