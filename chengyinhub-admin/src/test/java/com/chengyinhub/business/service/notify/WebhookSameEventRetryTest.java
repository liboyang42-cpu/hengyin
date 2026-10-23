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
        WebhookDeliveryLogProducer producer = new WebhookDeliveryLogProducer(
                transport, new WebhookSsrfHopGuard(), new InMemoryNotifyFixtures.MemoryDeliveryStore(),
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
    }
}
