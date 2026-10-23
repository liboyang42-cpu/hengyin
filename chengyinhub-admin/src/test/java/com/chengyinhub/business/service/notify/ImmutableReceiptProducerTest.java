package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImmutableReceiptProducerTest {

    @Test
    void replyMentionEditWithdrawPinSearchKeepOriginalReceipt() {
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(new InMemoryNotifyFixtures.MemoryReceiptStore());
        ImmutableReceiptProducer.AppliedProjection applied = new ImmutableReceiptProducer.AppliedProjection(
                "req-reply", ImmutableReceiptProducer.PROJECTION_APPLIED);
        for (ImmutableReceiptProducer.Kind kind : ImmutableReceiptProducer.Kind.values()) {
            String requestId = "req-" + kind.name().toLowerCase();
            ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                    new ImmutableReceiptProducer.OriginalRequest(requestId, kind, "body-" + kind, "UNKNOWN"));
            ImmutableReceiptProducer.OriginalReceipt replay = producer.accept(
                    new ImmutableReceiptProducer.OriginalRequest(requestId, kind, "body-" + kind, "UNKNOWN"));
            assertEquals(first.payloadHash, replay.payloadHash);
            assertEquals("UNKNOWN", producer.offlineReopen(requestId,
                    new ImmutableReceiptProducer.AppliedProjection(requestId,
                            ImmutableReceiptProducer.PROJECTION_APPLIED)).outcome);
            assertEquals(ImmutableReceiptProducer.UNKNOWN_NOT_RECOVERED,
                    producer.unknownRecover(requestId, applied).recoveryNote);
            assertTrue(producer.offlineReopen(requestId, applied).originalIsNotProjectionSuccess());
            assertFalse(producer.treatsAppliedProjectionAsOriginalProof(applied));
        }
        assertThrows(IllegalStateException.class, () -> producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("req-reply", ImmutableReceiptProducer.Kind.REPLY,
                        "tampered", "UNKNOWN")));
    }
}
