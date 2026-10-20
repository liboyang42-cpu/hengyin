package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.UUID;

/**
 * Transactional state evidence, not a W10 cohort projection. Product identity,
 * exclusion flags and qualification remain unknown until authoritative sources exist.
 */
public final class RegistrationLifecycleAudit {
    private RegistrationLifecycleAudit() { }

    /** Caller holds the registration write lock; a replay reuses this event object/key. */
    public static void append(BusinessAuditEventMapper mapper, BusinessAuditEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Registration lifecycle capture requires its business transaction");
        }
        BusinessAuditEvent existing = mapper.selectByDomainActionRequestForUpdate(
                event.getDomainType(), event.getDomainId(), event.getAction(), event.getRequestId());
        if (existing != null) return;
        if (mapper.insertBusinessAuditEvent(event) != 1) {
            throw new IllegalStateException("Registration lifecycle evidence append failed");
        }
    }

    /** Called only for a successful state mutation, with the persisted row read back under its lock. */
    public static BusinessAuditEvent changed(Long registrationId, Long memberId,
                                             Integer paymentStatus, Integer registrationStatus,
                                             Date occurredAt, Date receivedAt, String reason) {
        return changed(registrationId, memberId, paymentStatus, registrationStatus,
                occurredAt, receivedAt, reason, null);
    }

    public static BusinessAuditEvent changed(Long registrationId, Long memberId,
                                             Integer paymentStatus, Integer registrationStatus,
                                             Date occurredAt, Date receivedAt, String reason, Date paymentRecordedAt) {
        if (registrationId == null || registrationId <= 0
                || occurredAt == null || receivedAt == null) {
            throw new IllegalArgumentException("Registration lifecycle evidence is incomplete");
        }
        if (!("PAYMENT_CONFIRMED".equals(reason) || "REFUND_INVALIDATED".equals(reason)
                || "UNPAID_CANCELLED".equals(reason) || "ORGANIZER_CANCELLED".equals(reason)
                || "UNPAID_EXPIRED".equals(reason) || "PAYING_CLOSED".equals(reason)
                || "GENERIC_INSERT".equals(reason) || "GENERIC_UPDATE".equals(reason)
                || "PAYMENT_UPDATE".equals(reason))) {
            throw new IllegalArgumentException("Unsupported registration lifecycle reason");
        }
        if ("PAYMENT_CONFIRMED".equals(reason)
                && (!Integer.valueOf(2).equals(paymentStatus) || !Integer.valueOf(2).equals(registrationStatus))) {
            throw new IllegalArgumentException("Registration confirmation state is not authoritative");
        }
        // Generic administration is observed as a mutation, never promoted to
        // a capacity/payment-validated confirmation solely from numeric statuses.
        boolean generic = reason.startsWith("GENERIC_") || "PAYMENT_UPDATE".equals(reason);
        String eventName = generic ? "registration_state_changed"
                : Integer.valueOf(3).equals(registrationStatus) ? "registration_cancelled"
                : Integer.valueOf(4).equals(registrationStatus) ? "registration_expired"
                : "PAYMENT_CONFIRMED".equals(reason) ? "registration_confirmed"
                : "registration_state_changed";
        String key = "w10-v2-" + UUID.randomUUID();
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("REGISTRATION");
        event.setDomainId(registrationId);
        // Legacy generic rows can lack identity. Preserve that uncertainty and
        // exclude reporting; zero is an audit sentinel, not an invented member.
        Long subject = memberId != null && memberId > 0 ? memberId : null;
        event.setOwnerMemberId(subject == null ? 0L : subject);
        event.setActorType(BusinessAuditEvent.ACTOR_TYPE_UNKNOWN);
        event.setActorMemberId(0L);
        event.setActorRoleCode("SERVER_EVENT");
        event.setAction(eventName.toUpperCase(java.util.Locale.ROOT));
        event.setTargetType("CMS_REGISTRATION");
        event.setTargetId(registrationId);
        event.setRequestId(key);
        event.setReason(reason);
        event.setCreateTime(new Date(receivedAt.getTime()));
        event.setAfterJson("{\"schemaVersion\":2,\"source\":\"registration_server\","
                + "\"event_name\":\"" + eventName + "\","
                + "\"idempotency_key\":\"" + key + "\","
                + "\"registration_id\":" + registrationId + ",\"member_id\":" + subject + ","
                + "\"payment_status\":" + paymentStatus + ",\"registration_status\":" + registrationStatus + ","
                + "\"occurred_at\":\"" + occurredAt.toInstant() + "\","
                + "\"received_at\":\"" + receivedAt.toInstant() + "\","
                + "\"payment_recorded_at\":" + (paymentRecordedAt == null ? "null"
                    : "\"" + paymentRecordedAt.toInstant() + "\"") + ","
                + "\"clock_skew_detected\":" + occurredAt.after(receivedAt) + ","
                + "\"time_basis\":\"PERSISTED_STATE_MUTATION\","
                + "\"content_family_id\":null,\"release_id\":null,\"season_id\":null,"
                + "\"canonical_actor_id\":null,\"is_test\":null,\"is_cheat\":null,"
                + "\"free_paid\":null,\"repeat_approved\":null,"
                + "\"metric_eligible\":false,\"attribution_status\":\"UNKNOWN\"}");
        return event;
    }
}
