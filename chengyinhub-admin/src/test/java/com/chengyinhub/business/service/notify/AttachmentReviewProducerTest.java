package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
