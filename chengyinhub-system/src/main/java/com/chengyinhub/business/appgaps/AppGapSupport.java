package com.chengyinhub.business.appgaps;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Small shared helpers for the app-gaps routes: ids, hashes, canonical JSON and time formats. */
public final class AppGapSupport {
    private static final Pattern UUID_LOWER = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern UUID_ANY = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,95}");
    public static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AppGapSupport() {}

    public static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw AppGapException.unauthorized();
    }

    public static boolean lowerUuid(String value) { return value != null && UUID_LOWER.matcher(value).matches(); }
    public static boolean anyUuid(String value) { return value != null && UUID_ANY.matcher(value).matches(); }
    public static boolean hash(String value) { return value != null && HASH.matcher(value).matches(); }
    public static boolean id(String value) { return value != null && ID.matcher(value).matches(); }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }

    /** Canonical JSON (sorted keys at every level) for request fingerprints. */
    public static String canonical(Object value) {
        return JSON.toJSONString(sorted(value), JSONWriter.Feature.WriteMapNullValue);
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new TreeMap<>();
            ((Map<String, Object>) value).forEach((k, v) -> out.put(k, sorted(v)));
            return out;
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object v : (java.util.List<Object>) value) out.add(sorted(v));
            return out;
        }
        return value;
    }

    public static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder out = new StringBuilder(bytes * 2);
        for (byte b : raw) out.append(String.format("%02x", b));
        return out.toString();
    }

    public static String iso(long millis) { return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis)); }

    public static long millis(Object value) {
        if (value == null) return 0;
        if (value instanceof Date) return ((Date) value).getTime();
        if (value instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) value).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof Number) return ((Number) value).longValue();
        throw new IllegalStateException("not a time: " + value.getClass());
    }

    public static long num(Object value) { return value == null ? 0 : ((Number) value).longValue(); }

    public static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** Integer JSON number only (no strings, booleans or fractions). */
    public static Long integer(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        if (value instanceof java.math.BigInteger) return ((java.math.BigInteger) value).longValue();
        if (value instanceof java.math.BigDecimal) {
            java.math.BigDecimal d = (java.math.BigDecimal) value;
            try { return d.longValueExact(); } catch (ArithmeticException e) { return null; }
        }
        return null;
    }

    public static boolean exactKeys(Map<String, Object> body, java.util.Set<String> keys) {
        return body != null && body.keySet().equals(keys);
    }
}
