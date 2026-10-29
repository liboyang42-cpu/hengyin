package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;

/**
 * C12 isolation: another requestId cannot rewrite the original UNKNOWN event;
 * in-process requery keeps original outcome; missing requestId stays Java
 * {@code null}, never {@code ""}.
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
        SupportEventLocator.LocatedEvent original = restarted.recoverOriginal(ORIGINAL, projection);
        assertEquals(recovered.getRequestId(), original.getRequestId());
        assertEquals(recovered.getOriginalOutcome(), original.getOriginalOutcome());
    }

    @Test
    void missingRequestIdIsRejectedNeverEmptyString() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource()
                .add(event(ORIGINAL, "UNKNOWN", 501L));
        SupportEventLocator locator = new SupportEventLocator(source);
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                ORIGINAL, SupportEventLocator.PROJECTION_APPLIED_SUCCESS);
        assertNull(locator.locateByRequestId(null, projection));
        assertNull(locator.locateByRequestId("  ", projection));
        assertNull(locator.requery("", projection));
        assertNull(locator.recoverOriginal(null, projection));
        assertNull(locator.recoverOriginal("", projection));
        assertEquals(ORIGINAL, locator.locateByRequestId(ORIGINAL, projection).getRequestId());
        assertNotEquals("", locator.locateByRequestId(ORIGINAL, projection).getRequestId());
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryOriginalEventSource().add(event(null, "UNKNOWN", 501L)));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueWriteApplies() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource();
        SupportEventLocator locator = new SupportEventLocator(source);
        assertNull(locator.recoverOriginal(null, null));
        assertNull(locator.recoverOriginal("  ", null));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> locator.recoverOriginal("c11-never-seen", null));
        assertTrue(missing.getMessage().contains("c11-never-seen"));
        assertTrue(source.findByRequestId("c11-never-seen").isEmpty());
        source.add(event("c11-never-seen", "UNKNOWN", 11L));
        SupportEventLocator.LocatedEvent recovered = locator.recoverOriginal("c11-never-seen", null);
        assertEquals("c11-never-seen", recovered.getRequestId());
        assertEquals("UNKNOWN", recovered.getOriginalOutcome());
        assertEquals(1, source.findByRequestId("c11-never-seen").size());
        assertNotEquals(SupportEventLocator.PROJECTION_APPLIED_SUCCESS, recovered.getOriginalOutcome());
    }
}
