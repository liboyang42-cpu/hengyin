package com.chengyinhub.business.service.coupon;

import com.chengyinhub.business.domain.SmsCoupon;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Versioned, raw recorded definition fields only. No interpretation of a coupon's rights. */
public final class CouponTermsSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList("version", "name", "description",
            "couponType", "platform", "amount", "minPoint", "useType", "startTime", "endTime", "enableTime"));
    private final String json;
    private final String digest;
    private CouponTermsSnapshot(String json) { this.json = json; this.digest = sha256(json); }
    public String getJson() { return json; }
    public String getDigest() { return digest; }
    public String getName() { return text("name"); }
    public String getDescription() { return text("description"); }
    public Long getStartTime() { return instant("startTime"); }
    public Long getEndTime() { return instant("endTime"); }
    private JsonNode fields() {
        try { return JSON.readTree(json); } catch (Exception error) { throw new IllegalStateException("Invalid snapshot", error); }
    }
    private String text(String key) { JsonNode value = fields().get(key); return value.isNull() ? null : value.textValue(); }
    private Long instant(String key) { JsonNode value = fields().get(key); return value.isNull() ? null : value.longValue(); }
    public static CouponTermsSnapshot fromDefinition(SmsCoupon definition) {
        if (definition == null) throw new IllegalArgumentException("Definition required");
        ObjectNode value = JSON.createObjectNode(); value.put("version", 1);
        put(value, "name", definition.getName()); put(value, "description", definition.getDescription());
        put(value, "couponType", definition.getCouponType()); put(value, "platform", definition.getPlatform());
        put(value, "amount", definition.getAmount() == null ? null : definition.getAmount().toPlainString());
        put(value, "minPoint", definition.getMinPoint() == null ? null : definition.getMinPoint().toPlainString());
        put(value, "useType", definition.getUseType());
        put(value, "startTime", millis(definition.getStartTime())); put(value, "endTime", millis(definition.getEndTime()));
        put(value, "enableTime", millis(definition.getEnableTime()));
        String json = value.toString(); return recorded(json, sha256(json));
    }
    public static CouponTermsSnapshot recorded(String json, String digest) {
        if (json == null || json.length() > 8192 || !sha256(json).equals(digest)) throw new IllegalStateException("Snapshot digest mismatch");
        try {
            requireBoundedObject(json);
            JsonNode value = JSON.readTree(json); Set<String> keys = new HashSet<String>();
            Iterator<String> names = value.fieldNames(); while (names.hasNext()) keys.add(names.next());
            if (!value.isObject() || !value.toString().equals(json) || !FIELDS.equals(keys) || !value.get("version").isInt() || value.get("version").intValue() != 1)
                throw new IllegalStateException("Unsupported snapshot schema");
            for (String key : Arrays.asList("name", "description", "amount", "minPoint"))
                if (!value.get(key).isNull() && !value.get(key).isTextual()) throw new IllegalStateException("Invalid recorded text");
            if ((!value.get("name").isNull() && value.get("name").textValue().length() > 100)
                    || (!value.get("description").isNull() && value.get("description").textValue().length() > 500))
                throw new IllegalStateException("Recorded text exceeds source bounds");
            for (String key : Arrays.asList("couponType", "platform", "useType"))
                if (!value.get(key).isNull() && (!value.get(key).isIntegralNumber() || !value.get(key).canConvertToInt()))
                    throw new IllegalStateException("Invalid recorded integer");
            for (String key : Arrays.asList("startTime", "endTime", "enableTime"))
                if (!value.get(key).isNull() && (!value.get(key).isIntegralNumber() || !value.get(key).canConvertToLong()))
                    throw new IllegalStateException("Invalid recorded instant");
            // Decimal strings remain raw source facts, never recalculated discounts.
            for (String key : Arrays.asList("amount", "minPoint")) if (!value.get(key).isNull()
                    && !value.get(key).textValue().matches("-?[0-9]{1,10}(\\.[0-9]{1,2})?"))
                throw new IllegalStateException("Invalid recorded decimal");
            return new CouponTermsSnapshot(json);
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Malformed recorded terms", error); }
    }
    /** Local streaming guard; never changes the application's shared Jackson configuration. */
    private static void requireBoundedObject(String json) throws java.io.IOException {
        try (JsonParser parser = JSON.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            int depth = 0, tokens = 0; boolean completed = false;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (completed) throw new IllegalStateException("Trailing snapshot tokens");
                if (++tokens > 64) throw new IllegalStateException("Snapshot token limit exceeded");
                if (tokens == 1 && token != JsonToken.START_OBJECT) throw new IllegalStateException("Snapshot must be one object");
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > 4) throw new IllegalStateException("Snapshot nesting exceeds limit");
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    if (--depth == 0) completed = true;
                }
                // Brackets and braces inside VALUE_STRING do not affect structural depth.
            }
            if (!completed || depth != 0) throw new IllegalStateException("Incomplete snapshot object");
        }
    }
    private static Long millis(java.util.Date value) { return value == null ? null : value.getTime(); }
    private static void put(ObjectNode node, String key, String value) { if (value == null) node.putNull(key); else node.put(key, value); }
    private static void put(ObjectNode node, String key, Integer value) { if (value == null) node.putNull(key); else node.put(key, value); }
    private static void put(ObjectNode node, String key, Long value) { if (value == null) node.putNull(key); else node.put(key, value); }
    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64); for (byte item : bytes) result.append(String.format("%02x", item & 255)); return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
