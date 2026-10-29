package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class WebhookDeliveryLogProducerTest {

    @Test
    void signedDeliveryThenSameEventKeepsOriginalIdAndDisableStopsSend() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(204, "ok");
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), new InMemoryNotifyFixtures.MemoryDeliveryStore(),
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-E"));
        WebhookDeliveryLogProducer.WebhookConfig config = InMemoryNotifyFixtures.fixtureConfig(true, false);
        WebhookDeliveryLogProducer.OriginalEvent event =
                new WebhookDeliveryLogProducer.OriginalEvent("evt-E", "MESSAGE_PIN", "original-body");

        WebhookDeliveryLogProducer.DeliveryLog first = producer.deliver(config, event,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals("dlv-evt-E", first.deliveryId);
        assertEquals("evt-E", first.eventId);
        assertEquals("nonce-E", first.nonce);
        assertNotNull(first.signature);
        assertEquals(WebhookDeliveryLogProducer.STATUS_DELIVERED, first.status);
        assertEquals(1, transport.urls.size());
        assertTrue(transport.bodies.get(0).contains("\"signature\":\"" + first.signature + "\""));
        assertTrue(transport.bodies.get(0).contains("nonce-E"));

        WebhookDeliveryLogProducer.DeliveryLog retried = producer.deliver(config, event,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals(first.deliveryId, retried.deliveryId);
        assertEquals(first.eventId, retried.eventId);
        assertEquals(first.nonce, retried.nonce);
        assertEquals(first.signature, retried.signature);
        assertEquals(2, retried.attemptCount);
        assertEquals(2, transport.urls.size());

        WebhookDeliveryLogProducer.DeliveryLog disabled = producer.retryExisting(retried, config.disable(),
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals(first.deliveryId, disabled.deliveryId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_SKIPPED_DISABLED, disabled.status);
        assertEquals(2, transport.urls.size());

        WebhookDeliveryLogProducer.DeliveryLog revoked = producer.retryExisting(retried, config.revoke(),
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals(first.deliveryId, revoked.deliveryId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_SKIPPED_REVOKED, revoked.status);
        assertEquals(2, transport.urls.size());

        assertThrows(IllegalArgumentException.class, () -> producer.deliver(
                new WebhookDeliveryLogProducer.WebhookConfig("wh-x", "https://hooks.example.test/x",
                        "live-secret", true, false), event,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop())));
        assertTrue(!producer.treatsAppliedProjectionAsOriginalProof(ImmutableReceiptProducer.PROJECTION_APPLIED));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueDeliverApplies() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(204, "ok");
        InMemoryNotifyFixtures.MemoryDeliveryStore store = new InMemoryNotifyFixtures.MemoryDeliveryStore();
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), store,
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-never"));
        WebhookDeliveryLogProducer.DeliveryLog blank = producer.recoverOriginal("  ", null);
        assertNull(blank.eventId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_UNKNOWN, blank.status);
        assertEquals(0, store.size());
        WebhookDeliveryLogProducer.DeliveryLog recovered = producer.recoverOriginal(
                "evt-dlv-never-c09", ImmutableReceiptProducer.PROJECTION_APPLIED);
        assertEquals("evt-dlv-never-c09", recovered.eventId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_UNKNOWN, recovered.status);
        assertNull(store.findByEventId("evt-dlv-never-c09"));
        assertEquals(0, store.size());
        WebhookDeliveryLogProducer.DeliveryLog first = producer.deliver(
                InMemoryNotifyFixtures.fixtureConfig(true, false),
                new WebhookDeliveryLogProducer.OriginalEvent("evt-dlv-never-c09", "PIN", "original-body"),
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals("evt-dlv-never-c09", first.eventId);
        assertEquals("dlv-evt-dlv-never-c09", first.deliveryId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_DELIVERED, first.status);
        assertEquals(1, store.size());
        assertEquals(first.deliveryId, producer.recoverOriginal("evt-dlv-never-c09", null).deliveryId);
        assertEquals(1, store.size());
    }

    @Test
    void unusedRequireOriginalOfNeverSeenDoesNotOccupyThenUniqueDeliverApplies() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(204, "ok");
        InMemoryNotifyFixtures.MemoryDeliveryStore store = new InMemoryNotifyFixtures.MemoryDeliveryStore();
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), store,
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-req"));
        assertThrows(IllegalStateException.class, () -> producer.requireOriginal("evt-req-never-c09"));
        assertEquals(0, store.size(), "未见投递 requireOriginal 观察不得占账");
        assertNull(store.findByEventId("evt-req-never-c09"));
        WebhookDeliveryLogProducer.DeliveryLog unused = producer.recoverOriginal(
                "evt-req-never-c09", ImmutableReceiptProducer.PROJECTION_APPLIED);
        assertEquals("evt-req-never-c09", unused.eventId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_UNKNOWN, unused.status);
        WebhookDeliveryLogProducer.DeliveryLog first = producer.deliver(
                InMemoryNotifyFixtures.fixtureConfig(true, false),
                new WebhookDeliveryLogProducer.OriginalEvent("evt-req-never-c09", "PIN", "original-body"),
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertEquals("evt-req-never-c09", first.eventId);
        assertEquals("dlv-evt-req-never-c09", first.deliveryId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_DELIVERED, first.status);
        assertEquals(1, store.size());
        assertEquals(first.deliveryId, producer.recoverOriginal("evt-req-never-c09", null).deliveryId);
        assertEquals(1, store.size());
    }
}
