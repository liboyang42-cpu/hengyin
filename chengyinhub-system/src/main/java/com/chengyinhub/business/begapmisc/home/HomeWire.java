package com.chengyinhub.business.begapmisc.home;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict flat JSON parsing and validators shared by the private home and the home-return routes. */
final class HomeWire {
    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private HomeWire() { }

    /** A flat JSON object with exactly {@code required} keys plus any of {@code optional}; else 400 {@code invalid}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String raw, Set<String> required, Set<String> optional, String invalid) {
        Map<String, Object> body;
        try {
            if (raw == null || raw.length() > 4096) throw new IllegalArgumentException();
            body = JSON.readValue(raw, Map.class);
        } catch (Exception malformed) {
            throw new HomeRejection(400, invalid);
        }
        if (body == null || !body.keySet().containsAll(required)) throw new HomeRejection(400, invalid);
        for (String key : body.keySet()) {
            if (!required.contains(key) && !optional.contains(key)) throw new HomeRejection(400, invalid);
            Object value = body.get(key);
            if (value != null && !(value instanceof String) && !(value instanceof Number)) throw new HomeRejection(400, invalid);
        }
        return body;
    }

    static String text(Map<String, Object> body, String key, String pattern, String invalid) {
        Object value = body.get(key);
        if (!(value instanceof String) || !((String) value).matches(pattern)) throw new HomeRejection(400, invalid);
        return (String) value;
    }

    static long version(Map<String, Object> body, String key, String invalid) {
        Object value = body.get(key);
        if (!(value instanceof java.math.BigInteger)) throw new HomeRejection(400, invalid);
        java.math.BigInteger n = (java.math.BigInteger) value;
        if (n.signum() < 0 || n.compareTo(java.math.BigInteger.valueOf(9_007_199_254_740_991L)) > 0) throw new HomeRejection(400, invalid);
        return n.longValue();
    }

    /** A decimal with at most 6 fractional digits (after stripping insignificant zeros) within ±limit. */
    static BigDecimal coordinate(Map<String, Object> body, String key, int limit, String invalid) {
        BigDecimal value = decimal(body, key, invalid);
        if (value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) throw new HomeRejection(400, invalid);
        return value;
    }

    static BigDecimal decimal(Map<String, Object> body, String key, String invalid) {
        Object value = body.get(key);
        BigDecimal n;
        if (value instanceof BigDecimal) n = (BigDecimal) value;
        else if (value instanceof java.math.BigInteger) n = new BigDecimal((java.math.BigInteger) value);
        else throw new HomeRejection(400, invalid);
        n = n.signum() == 0 ? BigDecimal.ZERO : n.stripTrailingZeros();
        if (n.scale() > 6) throw new HomeRejection(400, invalid);
        if (n.scale() < 0) n = n.setScale(0);
        return n;
    }

    static boolean validLabel(String label) {
        if (label == null || label.trim().isEmpty() || label.length() > 40) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c)) return false;
        }
        return true;
    }

    /** Digest of the request body: sorted keys, decimals in plain form, so a byte-different retry of the same values matches. */
    static String digest(Map<String, Object> body) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<>(body).entrySet()) {
            Object v = e.getValue();
            String text = v instanceof BigDecimal ? ((BigDecimal) v).stripTrailingZeros().toPlainString() : String.valueOf(v);
            canonical.append(e.getKey()).append('=').append(text.length()).append(':').append(text).append('\n');
        }
        return sha256(canonical.toString());
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    /** Great-circle distance in meters (WGS84 mean radius). */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
