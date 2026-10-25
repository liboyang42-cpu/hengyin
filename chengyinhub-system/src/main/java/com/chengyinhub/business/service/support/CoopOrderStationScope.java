package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Pure frozen-source validation; no lookup, payment verification or economic write. */
public final class CoopOrderStationScope {
    public static final String VERSION = "REGISTRATION_STATION_V1";
    public static final String ACTION = "REGISTRATION_STATION_REDEEM";
    private final Long stationId;
    private final String termsHash;

    private CoopOrderStationScope(Long stationId, String termsHash) {
        this.stationId = stationId; this.termsHash = termsHash;
    }
    public Long getStationId() { return stationId; }
    public String getTermsHash() { return termsHash; }

    public static Optional<CoopOrderStationScope> read(CoopOrder order) {
        if (order == null) throw fail("ORDER_REQUIRED");
        String raw = order.getTermsSnapshot();
        if (raw == null || raw.isBlank()) return Optional.empty(); // no new confirmed contract, never infer one
        JSONObject terms;
        try { terms = JSON.parseObject(raw); } catch (RuntimeException invalid) { throw fail("INVALID_TERMS_JSON"); }
        if (terms == null) throw fail("INVALID_TERMS_JSON");
        if (!terms.containsKey("supplyBillingScope")) return Optional.empty();
        Object value = terms.get("supplyBillingScope");
        if (!(value instanceof JSONObject)) throw fail("INVALID_V1_SCOPE");
        JSONObject scope = (JSONObject) value;
        if (!scope.keySet().equals(Set.of("version", "stationId", "topicId", "merchantMemberId"))
                || !VERSION.equals(scope.get("version")) || !"merchant".equals(order.getToType())
                || !"SUPPLY".equals(order.getRoleSnapshot())) throw fail("INVALID_V1_SCOPE");
        Long station = positiveJsonId(scope.get("stationId"));
        if (!Objects.equals(positiveJsonId(scope.get("topicId")), order.getTopicId())
                || !Objects.equals(positiveJsonId(scope.get("merchantMemberId")), order.getToId()))
            throw fail("V1_ORDER_SCOPE_CONFLICT");
        return Optional.of(new CoopOrderStationScope(station, CanonicalDigest.sha256Hex(raw)));
    }

    /** Caller holds the original topic fence and reads this list FOR UPDATE. */
    public static void requireDistinctStations(List<CoopOrder> orders) {
        if (orders == null) throw fail("CURRENT_ORDERS_REQUIRED");
        Set<String> bindings = new HashSet<>();
        for (CoopOrder order : orders) {
            Optional<CoopOrderStationScope> scope = read(order);
            if (scope.isEmpty()) continue;
            if (order.getId() == null || order.getId() <= 0 || !Integer.valueOf(0).equals(order.getStatus())
                    || !Integer.valueOf(0).equals(order.getDelFlag())) throw fail("CURRENT_ORDER_IDENTITY_CONFLICT");
            String binding = order.getTopicId() + ":" + order.getToId() + ":" + scope.get().getStationId();
            if (!bindings.add(binding)) throw fail("DUPLICATE_CONFIRMED_STATION");
        }
    }

    public static void requireCurrentAudit(BusinessAuditEvent source, List<BusinessAuditEvent> events) {
        if (source == null || source.getId() == null || events == null || events.isEmpty()) throw fail("CURRENT_AUDIT_REQUIRED");
        long last = 0;
        for (BusinessAuditEvent event : events) {
            if (event == null || event.getId() == null || event.getId() <= last
                    || !Objects.equals(source.getDomainId(), event.getDomainId())) throw fail("AUDIT_GENERATION_CONFLICT");
            last = event.getId();
        }
        if (source.getId() != last) throw fail("STALE_VERIFICATION_AUDIT");
    }

    public static CoopOrderStationFulfillment expected(CoopOrder order, CmsRegistration registration, BusinessAuditEvent audit) {
        CoopOrderStationScope scope = read(order).orElseThrow(() -> fail("CONFIRMED_SCOPE_REQUIRED"));
        if (order.getId() == null || order.getId() <= 0 || order.getCreateTime() == null
                || registration == null || registration.getId() == null || registration.getId() <= 0
                || registration.getMemberId() == null || registration.getMemberId() <= 0
                || !Long.valueOf(1L).equals(registration.getOwnerType()) || !Objects.equals(order.getTopicId(), registration.getOwnerId())
                || !Integer.valueOf(2).equals(registration.getPaymentStatus()) || !Integer.valueOf(2).equals(registration.getRegistrationStatus())
                || !Integer.valueOf(1).equals(registration.getVerificationStatus()) || registration.getVerificationTime() == null
                || !Integer.valueOf(1).equals(registration.getPurchaseKind())
                || audit == null || audit.getId() == null || audit.getId() <= 0 || audit.getCreateTime() == null
                || audit.getCreateTime().before(order.getCreateTime()) || !"REGISTRATION".equals(audit.getDomainType())
                || !Objects.equals(registration.getId(), audit.getDomainId()) || !"CMS_REGISTRATION".equals(audit.getTargetType())
                || !Objects.equals(registration.getId(), audit.getTargetId()) || !ACTION.equals(audit.getAction())
                || !Objects.equals(scope.getStationId(), audit.getRelationId()) || !Objects.equals(order.getToId(), audit.getOwnerMemberId())
                || !Objects.equals(audit.getOwnerMemberId(), registration.getVerifierId())
                || audit.getActorMemberId() == null || audit.getActorMemberId() <= 0
                || audit.getActorRoleCode() == null || audit.getActorRoleCode().isBlank()
                || !(BusinessAuditEvent.ACTOR_TYPE_MEMBER.equals(audit.getActorType()) || BusinessAuditEvent.ACTOR_TYPE_SYS_USER.equals(audit.getActorType()))
                || audit.getRequestId() == null || audit.getRequestId().isBlank()) throw fail("ORIGINAL_SOURCE_IDENTITY_CONFLICT");
        try {
            JSONObject before = JSON.parseObject(audit.getBeforeJson()), after = JSON.parseObject(audit.getAfterJson());
            if (before == null || after == null || !(before.get("verificationStatus") instanceof Number)
                    || !(after.get("verificationStatus") instanceof Number)
                    || new BigDecimal(before.get("verificationStatus").toString()).intValueExact() != 0
                    || new BigDecimal(after.get("verificationStatus").toString()).intValueExact() != 1) throw fail("AUDIT_STATE_CONFLICT");
        } catch (RuntimeException invalid) { throw fail("AUDIT_STATE_CONFLICT"); }
        if (registration.getPayableAmount() == null) throw fail("ORIGINAL_SALES_BASIS_REQUIRED");
        BigDecimal amount = registration.getPayableAmount().setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() < 0) throw fail("ORIGINAL_SALES_BASIS_REQUIRED");
        CoopOrderStationFulfillment receipt = new CoopOrderStationFulfillment();
        receipt.setOrderId(order.getId()); receipt.setTopicId(order.getTopicId()); receipt.setBeneficiaryMemberId(order.getToId());
        receipt.setRegistrationId(registration.getId()); receipt.setStationId(scope.getStationId()); receipt.setVerificationAuditId(audit.getId());
        receipt.setSalesBasisAmount(amount); receipt.setOrderTermsSha256(scope.getTermsHash()); receipt.setFulfilledAt(audit.getCreateTime());
        Map<String,Object> payload = new TreeMap<>();
        payload.put("version", VERSION); payload.put("order", receipt.getOrderId()); payload.put("topic", receipt.getTopicId());
        payload.put("merchant", receipt.getBeneficiaryMemberId()); payload.put("registration", receipt.getRegistrationId());
        payload.put("station", receipt.getStationId()); payload.put("audit", receipt.getVerificationAuditId());
        payload.put("amount", amount.toPlainString()); payload.put("terms", scope.getTermsHash()); payload.put("fulfilledAt", audit.getCreateTime().getTime());
        payload.put("actorType", audit.getActorType()); payload.put("actor", audit.getActorMemberId()); payload.put("actorRole", audit.getActorRoleCode());
        payload.put("buyer", registration.getMemberId()); payload.put("quantity", registration.getOrderNum());
        payload.put("verificationAt", registration.getVerificationTime().getTime());
        payload.put("request", audit.getRequestId()); payload.put("before", audit.getBeforeJson()); payload.put("after", audit.getAfterJson());
        receipt.setSourceFingerprint(CanonicalDigest.sha256Hex(JSON.toJSONString(payload))); receipt.setRecordedAt(new Date());
        return receipt;
    }

    public static void requireSame(CoopOrderStationFulfillment actual, CoopOrderStationFulfillment expected) {
        if (actual == null || actual.getId() == null || actual.getId() <= 0 || actual.getRecordedAt() == null
                || !Objects.equals(actual.getOrderId(), expected.getOrderId()) || !Objects.equals(actual.getTopicId(), expected.getTopicId())
                || !Objects.equals(actual.getBeneficiaryMemberId(), expected.getBeneficiaryMemberId())
                || !Objects.equals(actual.getRegistrationId(), expected.getRegistrationId()) || !Objects.equals(actual.getStationId(), expected.getStationId())
                || !Objects.equals(actual.getVerificationAuditId(), expected.getVerificationAuditId())
                || actual.getSalesBasisAmount() == null || actual.getSalesBasisAmount().compareTo(expected.getSalesBasisAmount()) != 0
                || !Objects.equals(actual.getOrderTermsSha256(), expected.getOrderTermsSha256())
                || !Objects.equals(actual.getSourceFingerprint(), expected.getSourceFingerprint())
                || actual.getFulfilledAt() == null || actual.getFulfilledAt().getTime() != expected.getFulfilledAt().getTime()) throw fail("IMMUTABLE_RECEIPT_CONFLICT");
    }

    private static Long positiveJsonId(Object value) {
        if (!(value instanceof Number)) throw fail("SCOPE_ID_REQUIRED");
        try { long id = new BigDecimal(value.toString()).longValueExact(); if (id > 0) return id; }
        catch (ArithmeticException invalid) { throw fail("SCOPE_ID_REQUIRED"); }
        throw fail("SCOPE_ID_REQUIRED");
    }
    public static IllegalStateException fail(String reason) { return new IllegalStateException("MANUAL_REPAIR:STATION_FULFILLMENT_" + reason); }
}
