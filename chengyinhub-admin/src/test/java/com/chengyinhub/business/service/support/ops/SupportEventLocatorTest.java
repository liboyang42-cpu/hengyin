package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;

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

        InMemoryOriginalEventSource mixed = new InMemoryOriginalEventSource();
        mixed.add(event("req-mix", "RUN", 2L, "START", "PENDING"));
        mixed.add(event("req-mix", "RUN", 7L, "START", "PENDING"));
        SupportEventLocator mixedLocator = new SupportEventLocator(mixed);
        IllegalStateException other = assertThrows(IllegalStateException.class,
                () -> mixedLocator.locateByRunId(7L, null));
        assertTrue(other.getMessage().contains("runId=7"));
    }

    @Test
    void missingOriginalIsNotInferredFromProjection() {
        SupportEventLocator locator = new SupportEventLocator(new InMemoryOriginalEventSource());
        SupportEventLocator.CurrentProjection projection = new SupportEventLocator.CurrentProjection(
                "ghost", SupportEventLocator.PROJECTION_APPLIED_SUCCESS);
        assertThrows(IllegalStateException.class, () -> locator.locateByRequestId("ghost", projection));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueWriteApplies() {
        InMemoryOriginalEventSource source = new InMemoryOriginalEventSource();
        SupportEventLocator locator = new SupportEventLocator(source);
        assertNull(locator.recoverOriginal(null, null));
        assertNull(locator.recoverOriginal("  ", null));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> locator.recoverOriginal("c11-loc-class", null));
        assertTrue(missing.getMessage().contains("c11-loc-class"));
        assertTrue(source.findByRequestId("c11-loc-class").isEmpty(), "未见 recover 不得占账");
        source.add(event("c11-loc-class", "RUN", 11L, "SUBMIT", "UNKNOWN"));
        SupportEventLocator.LocatedEvent recovered = locator.recoverOriginal("c11-loc-class", null);
        assertEquals("c11-loc-class", recovered.getRequestId());
        assertEquals("UNKNOWN", recovered.getOriginalOutcome());
        assertEquals(1, source.findByRequestId("c11-loc-class").size());
        assertNotEquals(SupportEventLocator.PROJECTION_APPLIED_SUCCESS, recovered.getOriginalOutcome());
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

    @Test
    void mapperSourceLocatesByRunViaSelectByTargetAndRequestIdStaysWaitingSource() {
        BusinessAuditEvent original = event("req-map-1", "RUN", 42L, "SUBMIT", "UNKNOWN");
        BusinessAuditEventMapper mapper = mock(BusinessAuditEventMapper.class);
        when(mapper.selectByTarget("RUN", 42L)).thenReturn(List.of(original));
        SupportEventLocator locator = new SupportEventLocator(
                new SupportEventLocator.MapperOriginalEventSource(mapper));
        SupportEventLocator.WaitingSourceException waiting = assertThrows(
                SupportEventLocator.WaitingSourceException.class,
                () -> locator.locateByRequestId("req-map-1", null));
        assertEquals(SupportEventLocator.REQUEST_ID_QUERY, waiting.getWaitingCapability());
        SupportEventLocator.LocatedEvent byRun = locator.locateByRunId(42L,
                new SupportEventLocator.CurrentProjection("req-map-1",
                        SupportEventLocator.PROJECTION_APPLIED_SUCCESS));
        assertEquals("req-map-1", byRun.getRequestId());
        assertEquals("UNKNOWN", byRun.getOriginalOutcome());
        assertEquals(42L, byRun.getRunId());
        assertTrue(byRun.originalIsNotProjectionSuccess());
        assertTrue(byRun.currentProjectionClaimsSuccess());
        BusinessAuditEvent otherTarget = event("req-other", "ORDER", 99L, "PAY", "UNKNOWN");
        when(mapper.selectByTarget("RUN", 7L)).thenReturn(List.of(otherTarget));
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> locator.locateByRunId(7L, null));
        assertTrue(mismatch.getMessage().contains("runId=7"));
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
