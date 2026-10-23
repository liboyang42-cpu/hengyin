package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;

/**
 * C12 isolation: another requestId cannot rewrite the original UNKNOWN event;
 * in-process requery keeps original outcome; missing requestId is rejected
 * rather than stored as {@code ""}.
 */
class C11AccountSwitchRestartIsolationTest {

    private static final String ORIGINAL = "c11-acct-owner";
    private static final String OTHER = "c11-acct-other";

    private static BusinessAuditEvent event(String requestId, String afterJson, long orderId) {
        BusinessAuditEvent row = new BusinessAuditEvent();
        row.setRequestId(requestId);
        row.setDomainType("ORDER");
        row.setDomainId(Long.valueOf(orderId));
        row.setTargetType("ORDER");
        row.setTargetId(Long.valueOf(orderId));
        row.setAction("COMPENSATION_FREEZE");
        row.setAfterJson(afterJson);
        row.setCreateTime(new Date(1L));
        return row;
    }

    @Test
    void otherRequestDoesNotRewriteOriginalUnknown() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource()
                .add(event(ORIGINAL, "UNKNOWN", 501L))
                .add(event(OTHER, "APPLIED", 777L));
        SupportEventLocator locator = new SupportEventLocator(source);
        SupportEventLocator.CurrentProjection originalProjection = new SupportEventLocator.CurrentProjection(
                ORIGINAL, SupportEventLocator.PROJECTION_APPLIED_SUCCESS);
        SupportEventLocator.CurrentProjection otherProjection = new SupportEventLocator.CurrentProjection(
                OTHER, SupportEventLocator.PROJECTION_APPLIED_SUCCESS);

        SupportEventLocator.LocatedEvent original = locator.locateByRequestId(ORIGINAL, originalProjection);
        assertEquals(ORIGINAL, original.getRequestId());
        assertEquals("UNKNOWN", original.getOriginalOutcome());
        assertTrue(original.originalIsNotProjectionSuccess());

        SupportEventLocator.LocatedEvent other = locator.locateByRequestId(OTHER, otherProjection);
        assertEquals(OTHER, other.getRequestId());
        assertNotEquals(original.getRequestId(), other.getRequestId());
        assertEquals("UNKNOWN", locator.locateByRequestId(ORIGINAL, originalProjection).getOriginalOutcome());
        assertEquals(Long.valueOf(501L), locator.locateByRequestId(ORIGINAL, originalProjection).getOrderId());
    }

    @Test
    void inProcessRequeryKeepsOriginalUnknown() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource()
                .add(event(ORIGINAL, "UNKNOWN", 501L));
        SupportEventLocator locator = new SupportEventLocator(source);
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                ORIGINAL, SupportEventLocator.PROJECTION_APPLIED_SUCCESS);
        SupportEventLocator.LocatedEvent first = locator.locateByRequestId(ORIGINAL, projection);
        SupportEventLocator restarted = new SupportEventLocator(source);
        SupportEventLocator.LocatedEvent recovered = restarted.requery(ORIGINAL, projection);
        assertEquals(first.getRequestId(), recovered.getRequestId());
        assertEquals("UNKNOWN", recovered.getOriginalOutcome());
        assertEquals(first.getOriginalOutcome(), recovered.getOriginalOutcome());
        assertTrue(recovered.originalIsNotProjectionSuccess());
    }

    @Test
    void missingRequestIdIsRejectedNeverEmptyString() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource()
                .add(event(ORIGINAL, "UNKNOWN", 501L));
        SupportEventLocator locator = new SupportEventLocator(source);
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                ORIGINAL, SupportEventLocator.PROJECTION_APPLIED_SUCCESS);
        assertThrows(IllegalArgumentException.class, () -> locator.locateByRequestId(null, projection));
        assertThrows(IllegalArgumentException.class, () -> locator.locateByRequestId("  ", projection));
        assertThrows(IllegalArgumentException.class, () -> locator.requery("", projection));
        assertEquals(ORIGINAL, locator.locateByRequestId(ORIGINAL, projection).getRequestId());
        assertNotEquals("", locator.locateByRequestId(ORIGINAL, projection).getRequestId());
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryOriginalEventSource().add(event(null, "UNKNOWN", 501L)));
    }
}
