package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.BusinessAuditEvent;

class AuthorizedTypedResourceEventPageTest {

    private static final long LARGE_ID = 9007199254740993L;

    @Test
    void authorizedRequestPagesEveryEventOnThatResource() {
        ExactPage rows = new ExactPage();
        rows.add(event(3L, "CHANNEL", 9L, "req-page", "RUN", 5L, 1L, "UNKNOWN"));
        rows.add(event(2L, "CHANNEL", 9L, "req-page", "ORDER", 8L, 2L, "UNKNOWN"));
        rows.add(event(1L, "CHANNEL", 9L, "req-page", "RUN", 6L, 1L, "UNKNOWN"));
        rows.add(event(9L, "CLUB", 9L, "req-page", "RUN", 5L, 1L, "SUCCESS"));
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);
        AuthorizedTypedResourceEventPage.Grant grant = AuthorizedTypedResourceEventPage.Grant.of(
                "CHANNEL", "9", AuthorizedTypedResourceEventPage.VIEW_AUDIT);

        AuthorizedTypedResourceEventPage.Page first = page.read(grant, "req-page", null, null, null, 2, "SUCCESS");
        AuthorizedTypedResourceEventPage.Page second = page.read(
                grant, "req-page", null, null, first.getNextCursor(), 2, "SUCCESS");

        assertEquals(List.of(3L, 2L), ids(first));
        assertEquals(2L, first.getNextCursor());
        assertEquals(List.of(1L), ids(second));
        assertNull(second.getNextCursor());
        assertEquals("UNKNOWN", first.getOutcome());
        assertFalse(first.usedProjection());
        assertEquals("SUCCESS", first.getIgnoredProjection());
        assertEquals(2, rows.calls.size());
        assertEquals("CHANNEL", rows.calls.get(0).getDomainType());
        assertEquals(9L, rows.calls.get(0).getDomainId());
        assertEquals("req-page", rows.calls.get(0).getRequestId());
        assertEquals(3, rows.calls.get(0).getFetchLimit());
        assertEquals(2L, rows.calls.get(1).getCursor());
    }

    @Test
    void sameNumberDifferentTargetTypeDoesNotCrossRead() {
        ExactPage rows = new ExactPage();
        rows.add(event(4L, "CHANNEL", 9L, "req-target", "RUN", 5L, 1L, "PENDING"));
        rows.add(event(3L, "CHANNEL", 9L, "req-target", "ORDER", 5L, 2L, "SUCCESS"));
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);

        AuthorizedTypedResourceEventPage.Page found = page.read(
                AuthorizedTypedResourceEventPage.Grant.of("CHANNEL", "9", "VIEW_AUDIT"),
                "req-target", "RUN", 5L, null, 10, "SUCCESS");

        assertEquals(List.of(4L), ids(found));
        assertEquals("PENDING", found.getOutcome());
        assertEquals("RUN", rows.calls.get(0).getTargetType());
        assertEquals(5L, rows.calls.get(0).getTargetId());
    }

    @Test
    void sameActorOnAnotherDomainIsNotAuthorized() {
        ExactPage rows = new ExactPage();
        rows.add(event(6L, "CHANNEL", 9L, "req-actor", "RUN", 1L, 7L, "PENDING"));
        rows.add(event(5L, "CLUB", 9L, "req-actor", "RUN", 1L, 7L, "SUCCESS"));
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);

        AuthorizedTypedResourceEventPage.Page found = page.read(
                AuthorizedTypedResourceEventPage.Grant.of("CHANNEL", "9", "VIEW_AUDIT"),
                "req-actor", null, null, null, 10, null);

        assertEquals(List.of(6L), ids(found));
        assertEquals(7L, found.getItems().get(0).getActorMemberId());
    }

    @Test
    void revokedGrantDoesNotRead() {
        ExactPage rows = new ExactPage();
        rows.add(event(1L, "CHANNEL", 9L, "req-live", "RUN", 1L, 1L, "PENDING"));
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);
        AuthorizedTypedResourceEventPage.Grant grant = AuthorizedTypedResourceEventPage.Grant.of(
                "CHANNEL", "9", "VIEW_AUDIT").revoke();

        AuthorizedTypedResourceEventPage.TypedResourceAuthorizationException denied = assertThrows(
                AuthorizedTypedResourceEventPage.TypedResourceAuthorizationException.class,
                () -> page.read(grant, "req-live", null, null, null, 10, null));

        assertEquals("TYPED_RESOURCE_DENIED", denied.getMessage());
        assertTrue(rows.calls.isEmpty());
    }

    @Test
    void blankOrIllegalRequestDoesNotBroadenSearch() {
        ExactPage rows = new ExactPage();
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);
        AuthorizedTypedResourceEventPage.Grant grant = AuthorizedTypedResourceEventPage.Grant.of(
                "CHANNEL", "9", "VIEW_AUDIT");

        assertThrows(IllegalArgumentException.class, () -> page.read(grant, null, null, null, null, 10, null));
        assertThrows(IllegalArgumentException.class, () -> page.read(grant, "  ", null, null, null, 10, null));
        assertThrows(IllegalArgumentException.class, () -> page.read(grant, "req id", null, null, null, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> page.read(grant, "a".repeat(65), null, null, null, 10, null));
        assertTrue(rows.calls.isEmpty());
    }

    @Test
    void largeIdStaysLongAndMissingRowStaysUnconfirmed() {
        assertEquals(LARGE_ID, AuthorizedTypedResourceEventPage.exactLong("9007199254740993"));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedTypedResourceEventPage.exactLong("1.5"));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedTypedResourceEventPage.exactLong("true"));
        assertThrows(IllegalArgumentException.class, () -> AuthorizedTypedResourceEventPage.exactLong("false"));
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizedTypedResourceEventPage.Grant.of("CHANNEL", "1.5", "VIEW_AUDIT"));
        assertThrows(AuthorizedTypedResourceEventPage.TypedResourceAuthorizationException.class,
                () -> AuthorizedTypedResourceEventPage.Grant.of("CHANNEL", "9", "platformAdmin"));

        ExactPage rows = new ExactPage();
        rows.add(event(LARGE_ID, "CHANNEL", LARGE_ID, "req-large", "RUN", 1L, 1L, "UNKNOWN"));
        rows.add(event(1L, "CHANNEL", LARGE_ID, "req-large", "RUN", 2L, 2L, "UNKNOWN"));
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);
        AuthorizedTypedResourceEventPage.Grant grant = AuthorizedTypedResourceEventPage.Grant.of(
                "CHANNEL", "9007199254740993", "VIEW_AUDIT");

        AuthorizedTypedResourceEventPage.Page first = page.read(grant, "req-large", null, null, null, 1, "SUCCESS");
        assertEquals(LARGE_ID, first.getDomainId());
        assertEquals(LARGE_ID, first.getNextCursor());
        assertEquals(LARGE_ID, rows.calls.get(0).getDomainId());

        AuthorizedTypedResourceEventPage.Page missing = page.read(
                grant, "req-absent", null, null, null, 10, "SUCCESS");
        assertFalse(missing.isConfirmed());
        assertEquals("UNKNOWN", missing.getOutcome());
        assertTrue(missing.getItems().isEmpty());
        assertFalse(missing.usedProjection());
    }

    @Test
    void redactsSecretAndKeepsUnknown() {
        ExactPage rows = new ExactPage();
        BusinessAuditEvent row = event(8L, "CHANNEL", 9L, "req-redact", "RUN", 1L, 1L, "UNKNOWN");
        row.setReason("call 13800138000");
        row.setBeforeJson("{\"token\":\"secret\",\"status\":\"UNKNOWN\"}");
        rows.add(row);
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(rows);

        AuthorizedTypedResourceEventPage.Page found = page.read(
                AuthorizedTypedResourceEventPage.Grant.of("CHANNEL", "9", "VIEW_AUDIT"),
                "req-redact", null, null, null, 10, "SUCCESS");

        assertEquals("UNKNOWN", found.getOutcome());
        assertEquals("call ***", found.getItems().get(0).getReason());
        assertEquals("{\"token\":\"***\",\"status\":\"UNKNOWN\"}", found.getItems().get(0).getBeforeJson());
        assertNull(found.getItems().get(0).getOwnerMemberId());
    }

    @Test
    void consumerDropsAForeignRowTheSourceStillReturns() {
        BusinessAuditEvent channel = event(2L, "CHANNEL", 9L, "req-hostile", "RUN", 1L, 4L, "PENDING");
        BusinessAuditEvent club = event(1L, "CLUB", 9L, "req-hostile", "RUN", 1L, 4L, "SUCCESS");
        AuthorizedTypedResourceEventPage page = new AuthorizedTypedResourceEventPage(
                request -> List.of(club, channel));

        AuthorizedTypedResourceEventPage.Page found = page.read(
                AuthorizedTypedResourceEventPage.Grant.of("CHANNEL", "9", "VIEW_AUDIT"),
                "req-hostile", null, null, null, 10, "SUCCESS");

        assertEquals(List.of(2L), ids(found));
        assertEquals("PENDING", found.getOutcome());
    }

    @Test
    void pageRequestHasNoAdminOwnerOrActorAuthority() {
        for (Field field : AuthorizedTypedResourceEventPage.PageRequest.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("admin"), name);
            assertFalse(name.contains("owner"), name);
            assertFalse(name.contains("actor"), name);
            assertFalse(name.contains("platform"), name);
        }
    }

    private static List<Long> ids(AuthorizedTypedResourceEventPage.Page page) {
        List<Long> ids = new ArrayList<Long>();
        for (BusinessAuditEvent item : page.getItems()) {
            ids.add(item.getId());
        }
        return ids;
    }

    private static BusinessAuditEvent event(Long id, String domainType, Long domainId, String requestId,
                                            String targetType, Long targetId, Long actorMemberId, String afterJson) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setId(id);
        event.setDomainType(domainType);
        event.setDomainId(domainId);
        event.setRequestId(requestId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setActorMemberId(actorMemberId);
        event.setAction("VIEW");
        event.setAfterJson(afterJson);
        event.setCreateTime(new Date(id));
        return event;
    }

    private static final class ExactPage implements AuthorizedTypedResourceEventPage.PageSource {
        private final List<BusinessAuditEvent> rows = new ArrayList<BusinessAuditEvent>();
        private final List<AuthorizedTypedResourceEventPage.PageRequest> calls =
                new ArrayList<AuthorizedTypedResourceEventPage.PageRequest>();

        void add(BusinessAuditEvent event) {
            rows.add(event);
        }

        @Override
        public List<BusinessAuditEvent> selectAuditLogPage(AuthorizedTypedResourceEventPage.PageRequest request) {
            calls.add(request);
            List<BusinessAuditEvent> matched = new ArrayList<BusinessAuditEvent>();
            for (BusinessAuditEvent row : rows) {
                if (!request.getDomainType().equals(row.getDomainType())) {
                    continue;
                }
                if (row.getDomainId() == null || request.getDomainId() != row.getDomainId()) {
                    continue;
                }
                if (!request.getRequestId().equals(row.getRequestId())) {
                    continue;
                }
                if (request.getTargetType() != null) {
                    if (!request.getTargetType().equals(row.getTargetType())
                            || !request.getTargetId().equals(row.getTargetId())) {
                        continue;
                    }
                }
                if (request.getCursor() != null && (row.getId() == null || row.getId() >= request.getCursor())) {
                    continue;
                }
                matched.add(row);
            }
            matched.sort(Comparator.comparing(BusinessAuditEvent::getId).reversed());
            if (matched.size() > request.getFetchLimit()) {
                return new ArrayList<BusinessAuditEvent>(matched.subList(0, request.getFetchLimit()));
            }
            return matched;
        }
    }
}
