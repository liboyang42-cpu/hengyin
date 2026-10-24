package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * C09 首个可跑旅程（隔离）：夹具 webhook 配置 → 事件 E 签名投递日志 →
 * 同 event 重试保留原 id → 停用/撤权停止再投；APPLIED 投影不是原请求证明。
 */
class C09FirstJourneyIsolationTest {

    @Test
    void webhookConfigSignedLogSameEventRetryThenDisableRevokeAndAppliedIsNotOriginalProof() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(204, "{\"accepted\":true}");
        InMemoryNotifyFixtures.MemoryDeliveryStore deliveryStore = new InMemoryNotifyFixtures.MemoryDeliveryStore();
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), deliveryStore,
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-E"));
        WebhookSameEventRetry retry = new WebhookSameEventRetry(producer);
        WebhookDeliveryLogProducer.WebhookConfig config = InMemoryNotifyFixtures.fixtureConfig(true, false);
        WebhookDeliveryLogProducer.OriginalEvent eventE =
                new WebhookDeliveryLogProducer.OriginalEvent("evt-E", "PIN", "{\"op\":\"pin\"}");

        WebhookDeliveryLogProducer.DeliveryLog first = producer.deliver(config, eventE,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals("evt-E", first.eventId);
        assertEquals("dlv-evt-E", first.deliveryId);
        assertEquals("nonce-E", first.nonce);
        assertEquals(WebhookDeliveryLogProducer.STATUS_DELIVERED, first.status);
        assertEquals(1, transport.urls.size());
        assertEquals("https://hooks.example.test/c09-e", transport.urls.get(0));
        assertTrue(transport.bodies.get(0).contains(first.signature));
        assertFalse(transport.bodies.get(0).contains("fixture-hmac-c09-not-production"));

        WebhookDeliveryLogProducer.DeliveryLog retried = retry.retrySameEvent("evt-E", config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertTrue(retry.keepsOriginalIds(first, retried));
        assertEquals(2, retried.attemptCount);
        assertEquals(2, transport.urls.size());
        assertEquals(first.asRequeryableView().get("deliveryId"), retried.asRequeryableView().get("deliveryId"));

        WebhookDeliveryLogProducer.DeliveryLog disabled = retry.retrySameEvent("evt-E", config.disable(),
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals(first.deliveryId, disabled.deliveryId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_SKIPPED_DISABLED, disabled.status);
        assertEquals(2, transport.urls.size());

        WebhookDeliveryLogProducer.DeliveryLog revoked = retry.retrySameEvent("evt-E", config.revoke(),
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals(first.eventId, revoked.eventId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_SKIPPED_REVOKED, revoked.status);
        assertEquals(2, transport.urls.size());

        ImmutableReceiptProducer receipts =
                new ImmutableReceiptProducer(new InMemoryNotifyFixtures.MemoryReceiptStore());
        ImmutableReceiptProducer.OriginalReceipt pin = receipts.accept(
                new ImmutableReceiptProducer.OriginalRequest("evt-E", ImmutableReceiptProducer.Kind.PIN,
                        "{\"op\":\"pin\"}", ImmutableReceiptProducer.OUTCOME_UNKNOWN));
        ImmutableReceiptProducer.AppliedProjection applied = new ImmutableReceiptProducer.AppliedProjection(
                "evt-E", ImmutableReceiptProducer.PROJECTION_APPLIED);
        ImmutableReceiptProducer.OriginalReceipt reopened = receipts.offlineReopen("evt-E", applied);
        assertEquals(pin.payloadHash, reopened.payloadHash);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, reopened.outcome);
        assertNotEquals(ImmutableReceiptProducer.PROJECTION_APPLIED, reopened.outcome);
        assertEquals(ImmutableReceiptProducer.UNKNOWN_NOT_RECOVERED,
                receipts.unknownRecover("evt-E", applied).recoveryNote);
        assertFalse(receipts.treatsAppliedProjectionAsOriginalProof(applied));
        assertFalse(producer.treatsAppliedProjectionAsOriginalProof(ImmutableReceiptProducer.PROJECTION_APPLIED));

        WebhookSameEventRetry.RecoveredRetry missing = retry.recoverUnknown("evt-never",
                ImmutableReceiptProducer.PROJECTION_APPLIED, config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertFalse(missing.recovered());
        assertTrue(missing.refusedAppliedProjection);
        assertEquals(2, transport.urls.size());
    }
}
