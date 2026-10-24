package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueAcceptApplies() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        ImmutableReceiptProducer.OriginalReceipt blank = producer.recoverOriginal(null, null);
        assertNull(blank.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, blank.outcome);
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt recovered = producer.recoverOriginal("c09-rcpt-never", null);
        assertEquals("c09-rcpt-never", recovered.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, recovered.outcome);
        assertNull(store.find("c09-rcpt-never"), "未见 id 的 recoverOriginal 不得占账");
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("c09-rcpt-never",
                        ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED));
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED, first.outcome);
        assertEquals("c09-rcpt-never", first.requestId);
        assertEquals(1, store.size());
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED,
                producer.recoverOriginal("c09-rcpt-never", null).outcome);
        assertEquals(1, store.size());
    }

    @Test
    void unusedOfflineReopenOfNeverSeenDoesNotOccupyThenUniqueAcceptApplies() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        assertThrows(IllegalStateException.class, () -> producer.offlineReopen("c09-reopen-never", null));
        assertEquals(0, store.size(), "未见回执 offlineReopen 观察不得占账");
        assertNull(store.find("c09-reopen-never"));
        ImmutableReceiptProducer.OriginalReceipt unused = producer.recoverOriginal("c09-reopen-never", null);
        assertEquals("c09-reopen-never", unused.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, unused.outcome);
        ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("c09-reopen-never",
                        ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED));
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED, first.outcome);
        assertEquals("c09-reopen-never", first.requestId);
        assertEquals(1, store.size());
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED,
                producer.recoverOriginal("c09-reopen-never", null).outcome);
        assertEquals(1, store.size());
    }
}
