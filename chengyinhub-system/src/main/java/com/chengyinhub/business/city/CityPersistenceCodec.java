package com.chengyinhub.business.city;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import static com.chengyinhub.business.city.CityContracts.*;
import static com.chengyinhub.business.city.CityRuntimeState.*;
import static com.chengyinhub.business.city.CityChecks.*;

/** Explicit, versioned durable codec. No polymorphic class names or client deserialization. */
final class CityPersistenceCodec {
    private final ObjectMapper mapper = new ObjectMapper();
    CityPersistenceCodec() {
        SimpleModule time = new SimpleModule();
        time.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(value.toString());
            }
        });
        mapper.registerModule(time);
    }
    String write(Object value) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("schemaVersion", 1); envelope.put("value", value);
        try { return mapper.writeValueAsString(envelope); }
        catch (IOException e) { throw new IllegalStateException("CITY_PERSISTENCE_ENCODE_FAILED", e); }
    }
    private JsonNode read(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            require(root.path("schemaVersion").asInt(-1) == 1 && root.hasNonNull("value"), "CITY_STORAGE_VERSION_UNSUPPORTED");
            return root.get("value");
        } catch (IOException e) { throw new IllegalStateException("CITY_PERSISTENCE_DECODE_FAILED", e); }
    }
    private static String s(JsonNode n, String field) {
        require(n != null && n.hasNonNull(field) && n.get(field).isTextual(), "CITY_STORAGE_INVALID");
        return n.get(field).textValue();
    }
    private static long l(JsonNode n, String field) {
        require(n != null && n.hasNonNull(field) && n.get(field).isIntegralNumber() && n.get(field).canConvertToLong(), "CITY_STORAGE_INVALID");
        return n.get(field).longValue();
    }
    private static Instant t(JsonNode n, String field) { return Instant.parse(s(n, field)); }
    private static boolean b(JsonNode n, String field) {
        require(n != null && n.hasNonNull(field) && n.get(field).isBoolean(), "CITY_STORAGE_INVALID");
        return n.get(field).booleanValue();
    }
    private Rules rules(JsonNode n) {
        long actions = l(n, "actionsPerRound");
        require(actions <= Integer.MAX_VALUE, "CITY_STORAGE_INVALID");
        return new Rules(s(n,"releaseId"), s(n,"contentHash"), s(n,"allocationPolicyId"), l(n,"maxFee"),
                l(n,"maxGrossReward"), (int) actions, l(n,"protectionSeconds"));
    }
    Board board(String json) {
        JsonNode n = read(json);
        return new Board(s(n,"boardId"), rules(n.get("rules")), BoardStatus.valueOf(s(n,"status")),
                l(n,"version"), t(n,"opensAt"), t(n,"closesAt"));
    }
    Round round(String json) {
        JsonNode n = read(json), g = n.get("grant");
        RoundGrant grant = new RoundGrant(s(g,"boardId"), s(g,"teamId"), s(g,"rulesReleaseId"), l(g,"sequence"),
                t(g,"opensAt"), t(g,"closesAt"), s(g,"outcomeId"));
        long remaining = l(n,"remainingActions");
        require(remaining >= 0 && remaining <= Integer.MAX_VALUE, "CITY_STORAGE_INVALID");
        return new Round(grant, (int) remaining);
    }
    private Map<Long, Long> amounts(JsonNode n) {
        require(n != null && n.isObject(), "CITY_STORAGE_INVALID");
        Map<Long, Long> result = new LinkedHashMap<Long, Long>();
        Iterator<Map.Entry<String, JsonNode>> fields = n.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            require(entry.getValue().isIntegralNumber() && entry.getValue().canConvertToLong(), "CITY_STORAGE_INVALID");
            result.put(Long.valueOf(entry.getKey()), entry.getValue().longValue());
        }
        return result;
    }
    Visit visit(String json, Rules rules) {
        JsonNode n = read(json), o = n.get("offer");
        FeeAllocation allocation = null;
        if (o.hasNonNull("feeAllocation")) {
            JsonNode a = o.get("feeAllocation");
            Map<Long, Long> credits = amounts(a.get("credits"));
            allocation = FeeAllocation.restore(s(a,"approvalId"), s(a,"policyId"), s(a,"boardId"),
                    s(a,"ownerTeamId"), l(a,"ownerMemberVersion"), credits);
        }
        Offer offer = new Offer(s(o,"offerId"), s(o,"boardId"), s(o,"propertyId"), s(o,"ownerTeamId"),
                l(o,"propertyVersion"), s(o,"visitingTeamId"), l(o,"memberVersion"), s(o,"serviceInstanceId"),
                s(o,"visitScope"), s(o,"taskInstanceId"), s(o,"rewardScope"), rules, s(o,"availabilityRevision"),
                l(o,"payerId"), l(o,"fee"), amounts(o.get("rewards")), allocation, t(o,"expiresAt"));
        require(offer.fingerprint.equals(s(o,"fingerprint")), "CITY_STORAGE_FINGERPRINT_MISMATCH");
        JsonNode c = n.get("payerConsent");
        PayerConsent consent = new PayerConsent(s(c,"consentId"), l(c,"payerId"), s(c,"offerFingerprint"),
                l(c,"maximumFee"), t(c,"expiresAt"));
        RewardReservation reservation = null;
        if (n.hasNonNull("reservation")) {
            JsonNode r = n.get("reservation");
            reservation = new RewardReservation(s(r,"reservationId"), offer, l(r,"reservedGrossPoints"), b(r,"active"));
            require(reservation.effectKey.equals(s(r,"effectKey")) && reservation.offerFingerprint.equals(s(r,"offerFingerprint")), "CITY_STORAGE_INVALID");
        }
        return new Visit(offer, consent, reservation, s(n,"acceptedRoundKey"), l(n,"voluntaryActorId"),
                t(n,"acceptedAt"), VisitStatus.valueOf(s(n,"status")));
    }
    Receipt receipt(String json, Map<String, Visit> visits) {
        JsonNode n = read(json);
        CityPointSettlementPlan plan = null;
        if (n.hasNonNull("pointPlan")) {
            Visit visit = visits.get(s(n,"outcomeId"));
            require(visit != null, "CITY_STORAGE_INVALID");
            Map<Long, Long> before = new LinkedHashMap<Long, Long>();
            JsonNode p = n.get("pointPlan");
            for (JsonNode entry : p.get("entries")) before.put(l(entry,"memberId"), l(entry,"expectedBefore"));
            plan = CityPointSettlementPlan.prepare(visit.offer, visit.reservation, before);
            require(plan.effectKey.equals(s(p,"effectKey")) && plan.offerFingerprint.equals(s(p,"offerFingerprint")), "CITY_STORAGE_INVALID");
        }
        return new Receipt(s(n,"requestKey"), s(n,"payloadHash"), s(n,"effectKey"), s(n,"kind"), s(n,"outcomeId"),
                l(n,"resultingStateVersion"), plan, n.hasNonNull("reservationToRelease") ? s(n,"reservationToRelease") : null);
    }
}
