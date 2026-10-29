package com.chengyinhub.business.service.notify;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.chengyinhub.business.service.impl.TransportResponse;
import com.chengyinhub.business.service.impl.WebhookTransport;

/**
 * C09 独占：Webhook 配置 → 签名 / nonce / 每跳 SSRF → 投递日志。
 *
 * <p>包装既有 {@link WebhookTransport} 缝，不改 {@code HttpWebhookTransport}。
 * 测试必须注入不触网的 transport。本类不生成生产凭据、不自己打开套接字。
 */
public final class WebhookDeliveryLogProducer {

    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String STATUS_SKIPPED_REVOKED = "SKIPPED_REVOKED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String FIXTURE_KEY_PREFIX = "fixture-";

    private final WebhookTransport transport;
    private final WebhookSsrfHopGuard ssrfGuard;
    private final DeliveryLogStore store;
    private final NonceSource nonceSource;

    public WebhookDeliveryLogProducer(WebhookTransport transport, WebhookSsrfHopGuard ssrfGuard,
                                      DeliveryLogStore store, NonceSource nonceSource) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.ssrfGuard = Objects.requireNonNull(ssrfGuard, "ssrfGuard");
        this.store = Objects.requireNonNull(store, "store");
        this.nonceSource = Objects.requireNonNull(nonceSource, "nonceSource");
    }

    /**
     * 为原事件 E 写签名投递日志。已有同 eventId 的原日志时返回原 id，不另造事件。
     */
    public DeliveryLog deliver(WebhookConfig config, OriginalEvent event, List<WebhookSsrfHopGuard.Hop> hops) {
        WebhookConfig requiredConfig = requireConfig(config);
        OriginalEvent requiredEvent = requireEvent(event);
        DeliveryLog existing = store.findByEventId(requiredEvent.eventId);
        if (existing != null) {
            return retryExisting(existing, requiredConfig, hops);
        }
        if (requiredConfig.revoked) {
            return insertSkipped(requiredEvent, requiredConfig, STATUS_SKIPPED_REVOKED, hops);
        }
        if (!requiredConfig.enabled) {
            return insertSkipped(requiredEvent, requiredConfig, STATUS_SKIPPED_DISABLED, hops);
        }
        ssrfGuard.assertSafeChain(effectiveHops(requiredConfig.targetUrl, hops));
        String nonce = nonceSource.nextNonce(requiredEvent.eventId);
        String payloadHash = ImmutableReceiptProducer.sha256Hex(requiredEvent.canonicalPayload());
        String signature = sign(requiredConfig.fixtureSigningKey, requiredEvent.eventId, nonce, payloadHash);
        String body = signedBody(requiredEvent.eventId, nonce, payloadHash, signature);
        TransportResponse response = postWithoutLeakingKey(requiredConfig.targetUrl, body);
        String status = classify(response);
        String deliveryId = "dlv-" + requiredEvent.eventId;
        DeliveryLog log = new DeliveryLog(deliveryId, requiredEvent.eventId, requiredConfig.webhookId,
                nonce, payloadHash, signature, status, 1, response == null ? -1 : response.getStatusCode(),
                false);
        store.insert(log);
        return log;
    }

    /** 同 event 再投：保留原 deliveryId / eventId / nonce / signature，只增加 attempt。 */
    public DeliveryLog retryExisting(DeliveryLog original, WebhookConfig config,
                                     List<WebhookSsrfHopGuard.Hop> hops) {
        if (original == null) {
            throw new IllegalStateException("原投递日志不存在");
        }
        WebhookConfig requiredConfig = requireConfig(config);
        if (requiredConfig.revoked) {
            return original.withStatus(STATUS_SKIPPED_REVOKED, original.attemptCount);
        }
        if (!requiredConfig.enabled) {
            return original.withStatus(STATUS_SKIPPED_DISABLED, original.attemptCount);
        }
        ssrfGuard.assertSafeChain(effectiveHops(requiredConfig.targetUrl, hops));
        String nonce = original.nonce != null ? original.nonce : nonceSource.nextNonce(original.eventId);
        String payloadHash = original.payloadHash;
        String signature = original.signature != null
                ? original.signature
                : sign(requiredConfig.fixtureSigningKey, original.eventId, nonce, payloadHash);
        String body = signedBody(original.eventId, nonce, payloadHash, signature);
        TransportResponse response = postWithoutLeakingKey(requiredConfig.targetUrl, body);
        String status = classify(response);
        DeliveryLog next = original.withAttempt(nonce, signature, status, original.attemptCount + 1,
                response == null ? -1 : response.getStatusCode());
        store.replaceSameIdentity(next);
        return next;
    }

    public DeliveryLog requireOriginal(String eventId) {
        String id = ImmutableReceiptProducer.requireRequestId(eventId);
        DeliveryLog original = store.findByEventId(id);
        if (original == null) {
            throw new IllegalStateException("原投递日志不存在 eventId=" + id);
        }
        return original;
    }

    public DeliveryLog recoverOriginal(String originalRequestId, String projection) {
        return recoverUnknown(originalRequestId, projection);
    }

    /**
     * Missing eventId stays Java {@code null} / UNKNOWN and does not occupy the ledger.
     */
    public DeliveryLog recoverUnknown(String originalRequestId, String projection) {
        String id = ImmutableReceiptProducer.parseRequestId(originalRequestId);
        if (id == null) {
            return unknownLog(null);
        }
        DeliveryLog original = store.findByEventId(id);
        if (original != null) {
            return original;
        }
        return unknownLog(id);
    }

    static DeliveryLog unknownLog(String eventId) {
        return new DeliveryLog(null, eventId, null, null, null, null, STATUS_UNKNOWN, 0, -1, false);
    }

    public boolean treatsAppliedProjectionAsOriginalProof(String appliedProjection) {
        return false;
    }

    private DeliveryLog insertSkipped(OriginalEvent event, WebhookConfig config, String status,
                                      List<WebhookSsrfHopGuard.Hop> hops) {
        String payloadHash = ImmutableReceiptProducer.sha256Hex(event.canonicalPayload());
        DeliveryLog log = new DeliveryLog("dlv-" + event.eventId, event.eventId, config.webhookId,
                null, payloadHash, null, status, 0, -1, false);
        store.insert(log);
        return log;
    }

    private TransportResponse postWithoutLeakingKey(String url, String body) {
        try {
            return transport.post(url, body);
        } catch (RuntimeException e) {
            return new TransportResponse(-1, null);
        }
    }

    private static List<WebhookSsrfHopGuard.Hop> effectiveHops(String targetUrl,
                                                               List<WebhookSsrfHopGuard.Hop> hops) {
        if (hops != null && !hops.isEmpty()) {
            return hops;
        }
        return Collections.singletonList(WebhookSsrfHopGuard.Hop.of(targetUrl));
    }

    static WebhookConfig requireConfig(WebhookConfig config) {
        if (config == null || isBlank(config.webhookId) || isBlank(config.targetUrl)) {
            throw new IllegalArgumentException("Webhook 配置不完整");
        }
        if (isBlank(config.fixtureSigningKey) || !config.fixtureSigningKey.startsWith(FIXTURE_KEY_PREFIX)) {
            throw new IllegalArgumentException("只接受 fixture- 前缀签名密钥，禁止生产凭据");
        }
        return config;
    }

    static OriginalEvent requireEvent(OriginalEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("原事件不能为空");
        }
        ImmutableReceiptProducer.requireRequestId(event.eventId);
        return event;
    }

    static String sign(String fixtureKey, String eventId, String nonce, String payloadHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fixtureKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String material = eventId + "\n" + nonce + "\n" + payloadHash;
            return ImmutableReceiptProducer.hex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法签名", e);
        }
    }

    static String signedBody(String eventId, String nonce, String payloadHash, String signature) {
        return "{\"eventId\":\"" + eventId + "\",\"nonce\":\"" + nonce
                + "\",\"payloadHash\":\"" + payloadHash + "\",\"signature\":\"" + signature + "\"}";
    }

    static String classify(TransportResponse response) {
        if (response == null) {
            return STATUS_UNKNOWN;
        }
        if (response.getStatusCode() < 0) {
            return STATUS_UNKNOWN;
        }
        if (response.is2xx()) {
            return STATUS_DELIVERED;
        }
        return STATUS_FAILED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 隔离配置。fixtureSigningKey 必须带 fixture- 前缀。 */
    public static final class WebhookConfig {
        public final String webhookId;
        public final String targetUrl;
        public final String fixtureSigningKey;
        public final boolean enabled;
        public final boolean revoked;

        public WebhookConfig(String webhookId, String targetUrl, String fixtureSigningKey,
                             boolean enabled, boolean revoked) {
            this.webhookId = webhookId;
            this.targetUrl = targetUrl;
            this.fixtureSigningKey = fixtureSigningKey;
            this.enabled = enabled;
            this.revoked = revoked;
        }

        public WebhookConfig disable() {
            return new WebhookConfig(webhookId, targetUrl, fixtureSigningKey, false, revoked);
        }

        public WebhookConfig revoke() {
            return new WebhookConfig(webhookId, targetUrl, fixtureSigningKey, enabled, true);
        }
    }

    public static final class OriginalEvent {
        public final String eventId;
        public final String kind;
        public final String originalBody;

        public OriginalEvent(String eventId, String kind, String originalBody) {
            this.eventId = eventId;
            this.kind = kind;
            this.originalBody = originalBody;
        }

        String canonicalPayload() {
            return eventId + "\n" + nullToEmpty(kind) + "\n" + nullToEmpty(originalBody);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    public static final class DeliveryLog {
        public final String deliveryId;
        public final String eventId;
        public final String webhookId;
        public final String nonce;
        public final String payloadHash;
        public final String signature;
        public final String status;
        public final int attemptCount;
        public final int transportStatusCode;
        public final boolean usedAppliedProjection;

        DeliveryLog(String deliveryId, String eventId, String webhookId, String nonce, String payloadHash,
                    String signature, String status, int attemptCount, int transportStatusCode,
                    boolean usedAppliedProjection) {
            this.deliveryId = deliveryId;
            this.eventId = eventId;
            this.webhookId = webhookId;
            this.nonce = nonce;
            this.payloadHash = payloadHash;
            this.signature = signature;
            this.status = status;
            this.attemptCount = attemptCount;
            this.transportStatusCode = transportStatusCode;
            this.usedAppliedProjection = usedAppliedProjection;
        }

        DeliveryLog withAttempt(String nonce, String signature, String status, int attemptCount,
                                int transportStatusCode) {
            return new DeliveryLog(deliveryId, eventId, webhookId, nonce, payloadHash, signature,
                    status, attemptCount, transportStatusCode, false);
        }

        DeliveryLog withStatus(String status, int attemptCount) {
            return new DeliveryLog(deliveryId, eventId, webhookId, nonce, payloadHash, signature,
                    status, attemptCount, transportStatusCode, false);
        }

        public Map<String, Object> asRequeryableView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("deliveryId", deliveryId);
            view.put("eventId", eventId);
            view.put("webhookId", webhookId);
            view.put("nonce", nonce);
            view.put("payloadHash", payloadHash);
            view.put("signature", signature);
            view.put("status", status);
            view.put("attemptCount", Integer.valueOf(attemptCount));
            view.put("usedAppliedProjection", Boolean.FALSE);
            return Collections.unmodifiableMap(view);
        }
    }

    public interface DeliveryLogStore {
        DeliveryLog findByEventId(String eventId);

        void insert(DeliveryLog log);

        void replaceSameIdentity(DeliveryLog log);
    }

    public interface NonceSource {
        String nextNonce(String eventId);
    }

    /** 测试用稳定 nonce；生产接线不得用随机 UUID 冒充原事件 id。 */
    public static final class SequentialNonceSource implements NonceSource {
        @Override
        public String nextNonce(String eventId) {
            return "nonce-" + eventId + "-" + UUID.randomUUID();
        }
    }
}
