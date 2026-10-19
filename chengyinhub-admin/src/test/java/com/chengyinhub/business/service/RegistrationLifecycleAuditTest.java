package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.service.support.RegistrationLifecycleAudit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationLifecycleAuditTest {
    private final Date occurred = Date.from(Instant.parse("2026-09-01T16:30:00Z"));
    private final Date received = Date.from(Instant.parse("2026-09-10T00:00:00Z"));

    @Test
    void confirmationKeepsBusinessTimeAndUnknownAttribution() throws Exception {
        BusinessAuditEvent event = RegistrationLifecycleAudit.changed(12L, 23L, 2, 2, occurred, received, "PAYMENT_CONFIRMED");
        JsonNode data = new ObjectMapper().readTree(event.getAfterJson());
        assertEquals("2026-09-01T16:30:00Z", data.get("occurred_at").asText());
        assertEquals("2026-09-10T00:00:00Z", data.get("received_at").asText());
        assertEquals(12L, data.get("registration_id").asLong());
        assertEquals(23L, data.get("member_id").asLong());
        assertFalse(data.get("metric_eligible").asBoolean());
        for (String unknown : new String[] {"content_family_id", "canonical_actor_id",
                "is_test", "is_cheat", "free_paid", "repeat_approved", "release_id", "season_id"}) {
            assertTrue(data.get(unknown).isNull(), unknown);
        }
        assertEquals("UNKNOWN", event.getActorType());
        assertEquals(0L, event.getActorMemberId());
        assertEquals(23L, event.getOwnerMemberId());
        assertEquals("SERVER_EVENT", event.getActorRoleCode());
        assertFalse(event.getAfterJson().contains("phone"));
    }

    @Test
    void replayHasStableBusinessKeyAndCannotMutateInputDate() {
        BusinessAuditEvent first = RegistrationLifecycleAudit.changed(12L, 23L, 2, 2, occurred, received, "PAYMENT_CONFIRMED");
        BusinessAuditEvent replay = RegistrationLifecycleAudit.changed(12L, 23L, 2, 2, occurred,
                new Date(received.getTime() + 1000), "PAYMENT_CONFIRMED");
        assertNotEquals(first.getRequestId(), replay.getRequestId(), "Distinct winning transitions must not collapse");
        assertTrue(first.getRequestId().length() <= 64);
        long captured = first.getCreateTime().getTime();
        received.setTime(0);
        assertEquals(captured, first.getCreateTime().getTime());
    }

    @Test
    void invalidStatesAndMissingFactsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationLifecycleAudit.changed(12L, 23L, 1, 2, occurred, received, "PAYMENT_CONFIRMED"));
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationLifecycleAudit.changed(12L, 23L, 2, 1, occurred, received, "PAYMENT_CONFIRMED"));
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationLifecycleAudit.changed(12L, 23L, 2, 2, null, received, "PAYMENT_CONFIRMED"));
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationLifecycleAudit.changed(0L, 23L, 2, 2, occurred, received, "PAYMENT_CONFIRMED"));
        BusinessAuditEvent skew = RegistrationLifecycleAudit.changed(12L, null, 2, 2,
                received, occurred, "PAYMENT_CONFIRMED");
        assertTrue(skew.getAfterJson().contains("\"clock_skew_detected\":true"));
        assertEquals(0L, skew.getOwnerMemberId());
        assertTrue(skew.getAfterJson().contains("\"member_id\":null"));
    }

    @Test
    void cancellationIsEligibilityEvidenceNotProofOfRefundPayment() throws Exception {
        BusinessAuditEvent event = RegistrationLifecycleAudit.changed(12L, 23L, 4, 3, occurred, received,
                "REFUND_INVALIDATED");
        JsonNode data = new ObjectMapper().readTree(event.getAfterJson());
        assertEquals("registration_cancelled", data.get("event_name").asText());
        assertEquals("REFUND_INVALIDATED", event.getReason());
        assertFalse(event.getAfterJson().contains("refund_succeeded"));
        assertNotEquals(event.getRequestId(),
                RegistrationLifecycleAudit.changed(12L, 23L, 2, 2, occurred, received, "PAYMENT_CONFIRMED").getRequestId());
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationLifecycleAudit.changed(12L, 23L, 4, 3, occurred, received, "arbitrary"));
    }

    @Test
    void sourceHooksStayInsideExistingTransactionsAndBeforeAfterCommitNotification() throws Exception {
        String source = source("CmsRegistrationServiceImpl.java");
        String legacy = source.substring(source.indexOf("private void completePointPayment("),
                source.indexOf("public CmsRegistration getByRegistrationNo("));
        assertFalse(legacy.contains("RegistrationLifecycleAudit"),
                "unchecked legacy writes must not assert authoritative confirmation");
        String payment = source.substring(source.indexOf("private String completePaymentSuccess("),
                source.indexOf("private CmsRegistration freezeTicketSourceOnPayment("));
        assertTrue(payment.contains("if (paymentTransitioned) captureRegistrationState"));
        assertTrue(payment.contains("if (siblingTransitioned) captureRegistrationState"));
        assertTrue(source.contains("RegistrationLifecycleAudit.append(businessAuditEventMapper"));
        assertTrue(source.contains("registrationEvidenceChanged(before, after)"));
        assertTrue(source.contains("\"UNPAID_CANCELLED\""));
        assertTrue(source.contains("\"UNPAID_EXPIRED\""));
        assertTrue(source.contains("\"PAYING_CLOSED\""));
        String refund = source("RefundServiceImpl.java");
        int cas = refund.indexOf("cmsRegistrationMapper.claimRegistrationRefunded(");
        int capture = refund.indexOf("RegistrationLifecycleAudit.append(", cas);
        int release = refund.indexOf("perkLedgerService.releaseOnRefund", cas);
        assertTrue(cas >= 0 && capture > cas && release > capture);

    }

    private String source(String name) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if ("chengyinhub-admin".equals(root.getFileName().toString())) root = root.getParent();
        return new String(Files.readAllBytes(root.resolve(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/" + name)),
                StandardCharsets.UTF_8);
    }
}
