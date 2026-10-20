package com.chengyinhub.business.city;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Bounded server-issued identifiers; length framing prevents compound-key ambiguity. */
final class CityChecks {
    private CityChecks() { }

    static String id(String value) {
        require(value != null && value.matches("[A-Za-z0-9_.:-]{1,128}"), "INVALID_ID");
        return value;
    }

    static long positive(long value) {
        require(value > 0, "INVALID_POSITIVE_VALUE");
        return value;
    }

    static long nonnegative(long value) {
        require(value >= 0, "INVALID_NONNEGATIVE_VALUE");
        return value;
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new CityRuleException(code);
    }

    static String key(String... parts) {
        StringBuilder framed = new StringBuilder("CITY:v1:");
        for (String part : parts) {
            require(part != null, "MISSING_KEY_PART");
            framed.append(part.length()).append(':').append(part);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(framed.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Map<Long, Long> amounts(Map<Long, Long> values) {
        require(values != null && values.size() <= 128, "INVALID_RECIPIENTS");
        Map<Long, Long> copy = new TreeMap<Long, Long>();
        for (Map.Entry<Long, Long> entry : values.entrySet()) {
            require(entry.getKey() != null && entry.getValue() != null, "INVALID_RECIPIENT");
            copy.put(positive(entry.getKey()), nonnegative(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    static String amountHash(Map<Long, Long> values) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<Long, Long> entry : amounts(values).entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return key(canonical.toString());
    }
}
