package com.chengyinhub.business.merchantgap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small shared helpers: merchant access, hashing and the app's sorted-key JSON. */
public final class MerchantGapSupport {
    public static final ObjectMapper JSON = new ObjectMapper();
    /** Apple's JSONSerialization with .sortedKeys escapes "/" as "\/"; the app hashes that exact text. */
    private static final ObjectMapper APPLE = new ObjectMapper().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
    public static final Pattern LOWER_UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    public static final Pattern ANY_UUID = Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    public static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private MerchantGapSupport() {}

    public static long member(Long principal) {
        if (principal == null || principal <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        return principal;
    }

    /** The caller's current merchant context with the permission, or 403 MERCHANT_FORBIDDEN. */
    public static MerchantAccessContext merchant(IMerchantAccessService access, Long principal, String permission) {
        long member = member(principal);
        try {
            MerchantAccessContext ctx = access.require(member, permission);
            if (ctx == null || ctx.getMerchantId() == null || ctx.getOwnerMemberId() == null) {
                throw new MerchantGapException(403, "MERCHANT_FORBIDDEN", "没有门店权限");
            }
            return ctx;
        } catch (ServiceException e) {
            throw new MerchantGapException(403, "MERCHANT_FORBIDDEN", "没有门店权限");
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compact JSON with sorted keys and "/" escaped, as Foundation writes it with .sortedKeys. */
    public static String appleSortedJson(Map<String, ?> fields) {
        try {
            String text = APPLE.writeValueAsString(new TreeMap<>(fields));
            return text.replace("/", "\\/");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof String s ? s : null;
    }

    public static Long positive(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof Number n) || value instanceof Double || value instanceof Float) return null;
        long v = n.longValue();
        return v > 0 ? v : null;
    }
}
