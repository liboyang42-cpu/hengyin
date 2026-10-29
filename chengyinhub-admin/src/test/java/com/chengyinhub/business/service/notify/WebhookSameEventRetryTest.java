package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class WebhookSameEventRetryTest {

    @Test
    void retryKeepsOriginalIdsAndUnknownDoesNotMintFromAppliedProjection() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(200, "{}");
        InMemoryNotifyFixtures.MemoryDeliveryStore store = new InMemoryNotifyFixtures.MemoryDeliveryStore();
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), store,
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-E"));
        WebhookSameEventRetry retry = new WebhookSameEventRetry(producer);
        WebhookDeliveryLogProducer.WebhookConfig config = InMemoryNotifyFixtures.fixtureConfig(true, false);
        WebhookDeliveryLogProducer.OriginalEvent event =
                new WebhookDeliveryLogProducer.OriginalEvent("evt-E", "MENTION", "hi");
        WebhookDeliveryLogProducer.DeliveryLog original = producer.deliver(config, event,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));

        WebhookDeliveryLogProducer.DeliveryLog retried = retry.retrySameEvent("evt-E", config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertTrue(retry.keepsOriginalIds(original, retried));
        assertEquals(2, retried.attemptCount);

        WebhookSameEventRetry.RecoveredRetry missing = retry.recoverUnknown("evt-missing",
                ImmutableReceiptProducer.PROJECTION_APPLIED, config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertFalse(missing.recovered());
        assertEquals(WebhookSameEventRetry.UNKNOWN_NOT_RECOVERED, missing.note);
        assertTrue(missing.refusedAppliedProjection);
        assertNull(missing.log);
        assertEquals(2, transport.urls.size());
        int occupied = store.size();
        WebhookSameEventRetry.RecoveredRetry blank = retry.recoverUnknown("  ",
                ImmutableReceiptProducer.PROJECTION_APPLIED, config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertFalse(blank.recovered());
        assertNull(blank.log);
        assertEquals(WebhookSameEventRetry.UNKNOWN_NOT_RECOVERED, blank.note);
        assertTrue(blank.refusedAppliedProjection);
        assertNull(producer.recoverUnknown("  ", ImmutableReceiptProducer.PROJECTION_APPLIED).eventId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_UNKNOWN,
                producer.recoverUnknown("evt-ghost", ImmutableReceiptProducer.PROJECTION_APPLIED).status);
        assertNull(store.findByEventId("evt-ghost"));
        assertEquals(occupied, store.size());
        assertEquals(2, transport.urls.size());

        WebhookSameEventRetry.RecoveredRetry originalBlank = retry.recoverOriginal(null,
                ImmutableReceiptProducer.PROJECTION_APPLIED, config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertFalse(originalBlank.recovered());
        assertNull(originalBlank.log);
        assertEquals(WebhookSameEventRetry.UNKNOWN_NOT_RECOVERED, originalBlank.note);
        WebhookSameEventRetry.RecoveredRetry originalGhost = retry.recoverOriginal("evt-recover-ghost",
                ImmutableReceiptProducer.PROJECTION_APPLIED, config,
                Collections.singletonList(InMemoryNotifyFixtures.publicHop()));
        assertFalse(originalGhost.recovered());
        assertNull(store.findByEventId("evt-recover-ghost"));
        assertEquals(occupied, store.size());
        assertEquals(2, transport.urls.size());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueDeliverApplies() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(204, "ok");
        InMemoryNotifyFixtures.MemoryDeliveryStore store = new InMemoryNotifyFixtures.MemoryDeliveryStore();
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), store,
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-retry-never"));
        WebhookSameEventRetry retry = new WebhookSameEventRetry(producer);
        WebhookDeliveryLogProducer.WebhookConfig config = InMemoryNotifyFixtures.fixtureConfig(true, false);
        java.util.List<WebhookSsrfHopGuard.Hop> hops =
                Collections.singletonList(InMemoryNotifyFixtures.publicHop());
        WebhookSameEventRetry.RecoveredRetry blank = retry.recoverOriginal("  ", null, config, hops);
        assertFalse(blank.recovered());
        assertNull(blank.log);
        assertEquals(WebhookSameEventRetry.UNKNOWN_NOT_RECOVERED, blank.note);
        assertEquals(0, store.size());
        WebhookSameEventRetry.RecoveredRetry recovered = retry.recoverOriginal(
                "evt-retry-never-c09", ImmutableReceiptProducer.PROJECTION_APPLIED, config, hops);
        assertFalse(recovered.recovered());
        assertTrue(recovered.refusedAppliedProjection);
        assertEquals(WebhookSameEventRetry.UNKNOWN_NOT_RECOVERED, recovered.note);
        assertNull(recovered.log);
        assertNull(store.findByEventId("evt-retry-never-c09"));
        assertEquals(0, store.size());
        assertEquals(0, transport.urls.size());
        WebhookDeliveryLogProducer.DeliveryLog first = producer.deliver(
                config,
                new WebhookDeliveryLogProducer.OriginalEvent("evt-retry-never-c09", "PIN", "original-body"),
                hops);
        assertEquals("evt-retry-never-c09", first.eventId);
        assertEquals("dlv-evt-retry-never-c09", first.deliveryId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_DELIVERED, first.status);
        assertEquals(1, first.attemptCount);
        assertEquals(1, store.size());
        WebhookDeliveryLogProducer.DeliveryLog retried = retry.retrySameEvent("evt-retry-never-c09", config, hops);
        assertTrue(retry.keepsOriginalIds(first, retried));
        assertEquals(2, retried.attemptCount);
        assertEquals(1, store.size());
        assertEquals("evt-retry-never-c09", retried.eventId);
    }

    @Test
    void unusedRetrySameEventOfNeverSeenDoesNotOccupyThenUniqueDeliverApplies() {
        InMemoryNotifyFixtures.RecordingTransport transport =
                new InMemoryNotifyFixtures.RecordingTransport(204, "ok");
        InMemoryNotifyFixtures.MemoryDeliveryStore store = new InMemoryNotifyFixtures.MemoryDeliveryStore();
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), store,
                new InMemoryNotifyFixtures.FixedNonceSource("nonce-retry-unused"));
        WebhookSameEventRetry retry = new WebhookSameEventRetry(producer);
        WebhookDeliveryLogProducer.WebhookConfig config = InMemoryNotifyFixtures.fixtureConfig(true, false);
        java.util.List<WebhookSsrfHopGuard.Hop> hops =
                Collections.singletonList(InMemoryNotifyFixtures.publicHop());
        assertThrows(IllegalStateException.class, () -> retry.retrySameEvent("evt-retry-unused-c09", config, hops));
        assertEquals(0, store.size(), "未见同事件 retrySameEvent 观察不得占账");
        assertNull(store.findByEventId("evt-retry-unused-c09"));
        assertEquals(0, transport.urls.size());
        WebhookSameEventRetry.RecoveredRetry unused = retry.recoverOriginal(
                "evt-retry-unused-c09", ImmutableReceiptProducer.PROJECTION_APPLIED, config, hops);
        assertFalse(unused.recovered());
        assertEquals(WebhookSameEventRetry.UNKNOWN_NOT_RECOVERED, unused.note);
        assertNull(unused.log);
        WebhookDeliveryLogProducer.DeliveryLog first = producer.deliver(
                config,
                new WebhookDeliveryLogProducer.OriginalEvent("evt-retry-unused-c09", "PIN", "original-body"),
                hops);
        assertEquals("evt-retry-unused-c09", first.eventId);
        assertEquals(WebhookDeliveryLogProducer.STATUS_DELIVERED, first.status);
        assertEquals(1, first.attemptCount);
        assertEquals(1, store.size());
        WebhookDeliveryLogProducer.DeliveryLog retried = retry.retrySameEvent("evt-retry-unused-c09", config, hops);
        assertTrue(retry.keepsOriginalIds(first, retried));
        assertEquals(2, retried.attemptCount);
        assertEquals(1, store.size());
    }
}
