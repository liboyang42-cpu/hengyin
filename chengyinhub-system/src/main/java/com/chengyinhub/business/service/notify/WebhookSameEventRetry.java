package com.chengyinhub.business.service.notify;

import java.util.List;
import java.util.Objects;

/**
 * C09 独占：同 event 重试必须保留原 eventId / 原 deliveryId，不造新 request。
 *
 * <p>原日志缺失时 UNKNOWN_NOT_RECOVERED，不凭 APPLIED 投影补投。
 */
public final class WebhookSameEventRetry {

    public static final String UNKNOWN_NOT_RECOVERED = ImmutableReceiptProducer.UNKNOWN_NOT_RECOVERED;

    private final WebhookDeliveryLogProducer producer;

    public WebhookSameEventRetry(WebhookDeliveryLogProducer producer) {
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    public WebhookDeliveryLogProducer.DeliveryLog retrySameEvent(String originalEventId,
            WebhookDeliveryLogProducer.WebhookConfig config, List<WebhookSsrfHopGuard.Hop> hops) {
        WebhookDeliveryLogProducer.DeliveryLog original = producer.requireOriginal(originalEventId);
        return producer.retryExisting(original, config, hops);
    }

    /**
     * 原日志不存在时不得用当前 APPLIED 投影冒充原事件再投。
     */
    public RecoveredRetry recoverUnknown(String originalEventId, String appliedProjection,
                                         WebhookDeliveryLogProducer.WebhookConfig config,
                                         List<WebhookSsrfHopGuard.Hop> hops) {
        boolean refusedApplied = ImmutableReceiptProducer.PROJECTION_APPLIED.equals(appliedProjection);
        if (ImmutableReceiptProducer.parseRequestId(originalEventId) == null) {
            return new RecoveredRetry(null, UNKNOWN_NOT_RECOVERED, refusedApplied);
        }
        try {
            WebhookDeliveryLogProducer.DeliveryLog original = producer.requireOriginal(originalEventId);
            WebhookDeliveryLogProducer.DeliveryLog retried = producer.retryExisting(original, config, hops);
            return new RecoveredRetry(retried, null, false);
        } catch (RuntimeException missing) {
            return new RecoveredRetry(null, UNKNOWN_NOT_RECOVERED, refusedApplied);
        }
    }

    /**
     * 对齐 iOS {@code recoverOriginal}:解析 requestId 后只读原投递,不占账。
     * 缺 eventId 仍 UNKNOWN_NOT_RECOVERED,不抛。
     */
    public RecoveredRetry recoverOriginal(String originalEventId, String appliedProjection,
                                          WebhookDeliveryLogProducer.WebhookConfig config,
                                          List<WebhookSsrfHopGuard.Hop> hops) {
        return recoverUnknown(originalEventId, appliedProjection, config, hops);
    }

    public boolean keepsOriginalIds(WebhookDeliveryLogProducer.DeliveryLog original,
                                    WebhookDeliveryLogProducer.DeliveryLog retried) {
        return original != null && retried != null
                && original.deliveryId.equals(retried.deliveryId)
                && original.eventId.equals(retried.eventId)
                && Objects.equals(original.nonce, retried.nonce)
                && Objects.equals(original.signature, retried.signature);
    }

    public static final class RecoveredRetry {
        public final WebhookDeliveryLogProducer.DeliveryLog log;
        public final String note;
        public final boolean refusedAppliedProjection;

        RecoveredRetry(WebhookDeliveryLogProducer.DeliveryLog log, String note,
                       boolean refusedAppliedProjection) {
            this.log = log;
            this.note = note;
            this.refusedAppliedProjection = refusedAppliedProjection;
        }

        public boolean recovered() {
            return log != null;
        }
    }
}
