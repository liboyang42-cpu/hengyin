package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;

class SupportEventLocatorTest {

    @Test
    void locateByRequestIdReturnsOriginalUnknownNotCurrentProjectionSuccess() {
        BusinessAuditEvent original = event("req-unknown-1", "RUN", 11L, "SUBMIT", "UNKNOWN");
        SupportEventLocator locator = new SupportEventLocator(new InMemoryOriginalEventSource().add(original));
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                "req-unknown-1", SupportEventLocator.PROJECTION_APPLIED_SUCCESS);

        SupportEventLocator.LocatedEvent located = locator.locateByRequestId("req-unknown-1", projection);

        assertEquals("UNKNOWN", located.getOriginalOutcome());
        assertTrue(located.currentProjectionClaimsSuccess());
        assertTrue(located.originalIsNotProjectionSuccess());
        assertEquals(Boolean.FALSE, located.asRequeryableView().get("usedCurrentProjectionAsSuccess"));
        assertEquals(11L, located.getRunId());
    }

    @Test
    void requeryKeepsOriginalOutcome() {
        BusinessAuditEvent original = event("req-fail-1", "ORDER", 88L, "PAY", "FAILED");
        SupportEventLocator locator = new SupportEventLocator(new InMemoryOriginalEventSource().add(original));
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                "req-fail-1", SupportEventLocator.PROJECTION_APPLIED_SUCCESS);

        SupportEventLocator.LocatedEvent first = locator.locateByRequestId("req-fail-1", projection);
        SupportEventLocator.LocatedEvent second = locator.requery("req-fail-1", projection);

        assertEquals("FAILED", first.getOriginalOutcome());
        assertEquals(first.getOriginalOutcome(), second.getOriginalOutcome());
        assertEquals(88L, second.getOrderId());
        assertNotEquals(SupportEventLocator.PROJECTION_APPLIED_SUCCESS, second.getOriginalOutcome());
    }

    @Test
    void locateByRunAndOrderShareSameRequest() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource();
        source.add(event("req-same", "RUN", 2L, "START", "PENDING"));
        source.add(event("req-same", "ORDER", 9L, "CREATE", "PENDING"));
        SupportEventLocator locator = new SupportEventLocator(source);

        SupportEventLocator.LocatedEvent byRun = locator.locateByRunId(2L, null);
        SupportEventLocator.LocatedEvent byOrder = locator.locateByOrderId(9L, null);

        assertEquals("req-same", byRun.getRequestId());
        assertEquals("req-same", byOrder.getRequestId());
        assertEquals(2L, byRun.getRunId());
        assertEquals(9L, byOrder.getOrderId());
    }

    @Test
    void missingOriginalIsNotInferredFromProjection() {
        SupportEventLocator locator = new SupportEventLocator(new InMemoryOriginalEventSource());
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                "ghost", SupportEventLocator.PROJECTION_APPLIED_SUCCESS);
        assertThrows(IllegalStateException.class, () -> locator.locateByRequestId("ghost", projection));
    }

    @Test
    void missingRequestIdStaysNullNeverEmptyString() {
        SupportEventLocator locator = new SupportEventLocator(new InMemoryOriginalEventSource());
        assertNull(locator.locateByRequestId(null, null));
        assertNull(locator.locateByRequestId("  ", null));
        assertNull(locator.requery("", null));
        assertNull(SupportEventLocator.parseRequestId(null));
        assertNull(SupportEventLocator.parseRequestId(""));
        assertNotEquals("", SupportEventLocator.parseRequestId("req-unknown-1"));
    }

    private static BusinessAuditEvent event(String requestId, String targetType, Long targetId,
                                            String action, String afterJson) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setRequestId(requestId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setAction(action);
        event.setAfterJson(afterJson);
        event.setCreateTime(new Date(1L));
        return event;
    }
}
