package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }
}
