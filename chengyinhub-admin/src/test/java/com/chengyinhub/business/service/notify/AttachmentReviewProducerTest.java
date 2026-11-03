package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttachmentReviewProducerTest {

    @Test
    void sixteenTypesSubmitOriginalReceiptAndVisibleProjectionIsNotApproval() {
        assertEquals(16, AttachmentReviewProducer.typeCount());
        InMemoryNotifyFixtures.MemoryReviewStore store = new InMemoryNotifyFixtures.MemoryReviewStore();
        AttachmentReviewProducer producer = new AttachmentReviewProducer(store);
        int i = 0;
        for (AttachmentReviewProducer.AttachmentType type : AttachmentReviewProducer.AttachmentType.values()) {
            String requestId = "att-" + (++i);
            AttachmentReviewProducer.ReviewReceipt pending = producer.submit(requestId, type, "digest-" + type);
            assertEquals(AttachmentReviewProducer.STATUS_PENDING, pending.status);
            AttachmentReviewProducer.ReviewReceipt recovered = producer.recover(requestId, "VISIBLE");
            assertEquals(AttachmentReviewProducer.STATUS_PENDING, recovered.status);
            assertTrue(recovered.currentProjectionClaimsVisible);
            assertTrue(recovered.originalIsNotVisibleProjection());
            assertFalse(producer.treatsVisibleProjectionAsApproval(ImmutableReceiptProducer.PROJECTION_APPLIED));
            assertEquals(AttachmentReviewProducer.STATUS_APPROVED,
                    producer.decide(requestId, AttachmentReviewProducer.STATUS_APPROVED).status);
            assertEquals(type, producer.recover(requestId, ImmutableReceiptProducer.PROJECTION_APPLIED).type);
        }
        assertEquals(16, i);
        int occupied = store.size();
        AttachmentReviewProducer.ReviewReceipt missing = producer.recoverUnknown("  ", "VISIBLE");
        assertNull(missing.requestId);
        assertEquals(AttachmentReviewProducer.STATUS_UNKNOWN, missing.status);
        assertNull(store.find("att-ghost"));
        assertEquals(AttachmentReviewProducer.STATUS_UNKNOWN,
                producer.recoverUnknown("att-ghost", ImmutableReceiptProducer.PROJECTION_APPLIED).status);
        assertEquals(occupied, store.size());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueSubmitApplies() {
        InMemoryNotifyFixtures.MemoryReviewStore store = new InMemoryNotifyFixtures.MemoryReviewStore();
        AttachmentReviewProducer producer = new AttachmentReviewProducer(store);
        AttachmentReviewProducer.ReviewReceipt blank = producer.recoverOriginal("  ", null);
        assertNull(blank.requestId);
        assertEquals(AttachmentReviewProducer.STATUS_UNKNOWN, blank.status);
        assertEquals(0, store.size());
        AttachmentReviewProducer.ReviewReceipt recovered = producer.recoverOriginal(
                "att-recover-never-c09", "VISIBLE");
        assertEquals("att-recover-never-c09", recovered.requestId);
        assertEquals(AttachmentReviewProducer.STATUS_UNKNOWN, recovered.status);
        assertNull(store.find("att-recover-never-c09"));
        assertEquals(0, store.size());
        AttachmentReviewProducer.ReviewReceipt first = producer.submit(
                "att-recover-never-c09", AttachmentReviewProducer.AttachmentType.IMAGE, "digest-IMAGE");
        assertEquals("att-recover-never-c09", first.requestId);
        assertEquals(AttachmentReviewProducer.STATUS_PENDING, first.status);
        assertEquals(1, store.size());
        assertEquals(AttachmentReviewProducer.STATUS_PENDING,
                producer.recoverOriginal("att-recover-never-c09", "VISIBLE").status);
        assertEquals(1, store.size());
    }

    @Test
    void unusedDecideOfNeverSeenDoesNotOccupyThenUniqueSubmitApplies() {
        InMemoryNotifyFixtures.MemoryReviewStore store = new InMemoryNotifyFixtures.MemoryReviewStore();
        AttachmentReviewProducer producer = new AttachmentReviewProducer(store);
        assertThrows(IllegalStateException.class, () -> producer.decide(
                "att-decide-never-c09", AttachmentReviewProducer.STATUS_APPROVED));
        assertEquals(0, store.size(), "未见附件 decide 观察不得占账");
        assertNull(store.find("att-decide-never-c09"));
        AttachmentReviewProducer.ReviewReceipt unused = producer.recoverOriginal(
                "att-decide-never-c09", "VISIBLE");
        assertEquals("att-decide-never-c09", unused.requestId);
        assertEquals(AttachmentReviewProducer.STATUS_UNKNOWN, unused.status);
        AttachmentReviewProducer.ReviewReceipt first = producer.submit(
                "att-decide-never-c09", AttachmentReviewProducer.AttachmentType.IMAGE, "digest-IMAGE");
        assertEquals("att-decide-never-c09", first.requestId);
        assertEquals(AttachmentReviewProducer.STATUS_PENDING, first.status);
        assertEquals(1, store.size());
        assertEquals(AttachmentReviewProducer.STATUS_PENDING,
                producer.recoverOriginal("att-decide-never-c09", "VISIBLE").status);
        assertEquals(1, store.size());
    }
}
