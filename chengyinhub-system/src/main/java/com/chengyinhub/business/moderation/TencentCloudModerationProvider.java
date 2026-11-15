package com.chengyinhub.business.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SEC-12: Tencent Cloud text (TMS {@code TextModeration}) and image (IMS {@code ImageModeration}) moderation for
 * authors without a WeChat mini-program openid. Both APIs answer synchronously with {@code Suggestion}
 * Pass / Review / Block.
 *
 * <p>Enabled only when its keys are configured:
 * <pre>
 * chengyin.moderation.tencent.secret-id   (env TENCENTCLOUD_SECRET_ID)
 * chengyin.moderation.tencent.secret-key  (env TENCENTCLOUD_SECRET_KEY)
 * chengyin.moderation.tencent.region      (default ap-guangzhou)
 * chengyin.moderation.tencent.text-biz-type / image-biz-type  (optional console policy ids)
 * chengyin.moderation.tencent.enabled     (kill switch, default true; keys are still required)
 * </pre>
 * Requests are signed with TC3-HMAC-SHA256. Keys, content and response bodies are never logged.
 */
@Component
public class TencentCloudModerationProvider implements ContentModerationProvider {
    private static final Logger log = LoggerFactory.getLogger(TencentCloudModerationProvider.class);
    public static final String NAME = "TENCENT_CLOUD";
    static final String TEXT_HOST = "tms.tencentcloudapi.com";
    static final String IMAGE_HOST = "ims.tencentcloudapi.com";
    static final String VERSION = "2020-12-29";
    /** TMS accepts up to 10000 characters per call; stay well below. */
    static final int TEXT_CHUNK = 4000;
    static final int MAX_RESPONSE = 262_144;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final String secretId;
    private final String secretKey;
    private final String region;
    private final String textBizType;
    private final String imageBizType;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    Clock clock = Clock.systemUTC();

    @Autowired
    public TencentCloudModerationProvider(
            @Value("${chengyin.moderation.tencent.enabled:true}") boolean enabled,
            @Value("${chengyin.moderation.tencent.secret-id:${TENCENTCLOUD_SECRET_ID:}}") String secretId,
            @Value("${chengyin.moderation.tencent.secret-key:${TENCENTCLOUD_SECRET_KEY:}}") String secretKey,
            @Value("${chengyin.moderation.tencent.region:ap-guangzhou}") String region,
            @Value("${chengyin.moderation.tencent.text-biz-type:}") String textBizType,
            @Value("${chengyin.moderation.tencent.image-biz-type:}") String imageBizType) {
        this.enabled = enabled;
        this.secretId = trim(secretId);
        this.secretKey = trim(secretKey);
        this.region = trim(region).isEmpty() ? "ap-guangzhou" : trim(region);
        this.textBizType = trim(textBizType);
        this.imageBizType = trim(imageBizType);
    }

    @Override public String name() { return NAME; }

    /** Keys present and not switched off. Independent of the author's openid. */
    public boolean configured() {
        return enabled && !secretId.isEmpty() && !secretKey.isEmpty();
    }

    @Override public boolean canJudge(String openid) { return configured(); }

    @Override public boolean judgesImagesSynchronously() { return true; }

    @Override
    public ModerationVerdict checkText(String text, String openid) {
        if (text == null || text.trim().isEmpty()) return ModerationVerdict.PASS;
        if (!configured()) return ModerationVerdict.UNAVAILABLE;
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        ModerationVerdict worst = ModerationVerdict.PASS;
        int[] cps = text.codePoints().toArray();
        for (int start = 0; start < cps.length; start += TEXT_CHUNK) {
            String chunk = new String(cps, start, Math.min(TEXT_CHUNK, cps.length - start));
            ObjectNode body = JSON.createObjectNode();
            body.put("Content", Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8)));
            if (!textBizType.isEmpty()) body.put("BizType", textBizType);
            ModerationVerdict verdict = call(TEXT_HOST, "tms", "TextModeration", body);
            if (verdict == ModerationVerdict.RISK) return ModerationVerdict.RISK;
            worst = worse(worst, verdict);
        }
        return worst;
    }

    @Override
    public ModerationVerdict checkImage(String imageUrl, String openid) {
        if (!configured() || imageUrl == null) return ModerationVerdict.UNAVAILABLE;
        String url = imageUrl.trim();
        // IMS fetches the file itself: only absolute public http(s) addresses can be judged.
        if (!(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 2048) {
            return ModerationVerdict.UNAVAILABLE;
        }
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        ObjectNode body = JSON.createObjectNode();
        body.put("FileUrl", url);
        if (!imageBizType.isEmpty()) body.put("BizType", imageBizType);
        return call(IMAGE_HOST, "ims", "ImageModeration", body);
    }

    private ModerationVerdict call(String host, String service, String action, ObjectNode body) {
        try {
            String payload = JSON.writeValueAsString(body);
            long timestamp = clock.instant().getEpochSecond();
            String responseBody = send(host, action, payload, timestamp, authorization(host, service, action, payload, timestamp));
            return parse(responseBody);
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception e) {
            log.warn("[Tencent moderation] {} could not judge: {}", action, e.getClass().getSimpleName());
            return ModerationVerdict.UNAVAILABLE;
        }
    }

    /** Maps a TMS/IMS response. Errors, missing or unknown suggestions are UNAVAILABLE, never PASS. */
    static ModerationVerdict parse(String responseBody) throws Exception {
        if (responseBody == null) return ModerationVerdict.UNAVAILABLE;
        JsonNode response = JSON.readTree(responseBody).path("Response");
        if (response.has("Error")) {
            log.warn("[Tencent moderation] provider error code={}", response.path("Error").path("Code").asText(""));
            return ModerationVerdict.UNAVAILABLE;
        }
        String suggestion = response.path("Suggestion").asText("");
        if ("Pass".equalsIgnoreCase(suggestion)) return ModerationVerdict.PASS;
        if ("Block".equalsIgnoreCase(suggestion)) return ModerationVerdict.RISK;
        if ("Review".equalsIgnoreCase(suggestion)) return ModerationVerdict.REVIEW;
        return ModerationVerdict.UNAVAILABLE;
    }

    /** TC3-HMAC-SHA256 Authorization header (https://cloud.tencent.com/document/api/1124/51860). */
    String authorization(String host, String service, String action, String payload, long timestamp) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(timestamp));
        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalRequest = "POST\n/\n\n"
                + "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n"
                + "x-tc-action:" + action.toLowerCase() + "\n\n"
                + signedHeaders + "\n" + sha256Hex(payload);
        String scope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /** One signed HTTPS POST. Protected for tests. */
    protected String send(String host, String action, String payload, long timestamp, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + "/"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-TC-Action", action)
                .header("X-TC-Timestamp", Long.toString(timestamp))
                .header("X-TC-Version", VERSION)
                .header("X-TC-Region", region)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().length() > MAX_RESPONSE) return null;
        return response.body();
    }

    private static ModerationVerdict worse(ModerationVerdict a, ModerationVerdict b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] hmac(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
