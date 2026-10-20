package com.chengyinhub.business.service.support.release;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Contract v1 canonicalization, deliberately independent of runtime JSON serialization. */
final class CanonicalContentJson {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final int MAX_CHARS = 1024 * 1024;
    private static final int MAX_DEPTH = 100;
    private static final int MAX_TOKENS = 100000;
    private static final int MAX_NUMBER_CHARS = 1100;

    private CanonicalContentJson() { }

    static JsonNode object(String raw, String field) {
        try {
            assertWithinLimits(raw);
            JsonNode value = JSON.readTree(raw);
            if (value == null || !value.isObject()) throw invalid(field);
            // Normalize before validation so validators see the same values that are hashed.
            return JSON.readTree(write(value));
        } catch (Exception invalid) {
            // Do not put secret content or Jackson excerpts into error messages.
            throw invalid(field);
        }
    }

    /** Shared aggregate envelope limits for both generated previews and stored receipt parsing. */
    static void assertWithinLimits(String raw) {
        if (raw == null || raw.length() > MAX_CHARS) throw invalid("content limits");
        try {
            preflight(raw);
        } catch (java.io.IOException error) {
            // Never include parser excerpts of secret captured content in failures.
            throw invalid("content limits");
        }
    }

    /** Bound untrusted creator content before Jackson 2.12 recursively materializes its tree. */
    private static void preflight(String raw) throws java.io.IOException {
        int depth = 0;
        int tokens = 0;
        try (JsonParser parser = JSON.getFactory().createParser(raw)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > MAX_TOKENS) throw invalid("token limits");
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > MAX_DEPTH) throw invalid("nesting limits");
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                } else if ((token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
                        && parser.getTextLength() > MAX_NUMBER_CHARS) {
                    throw invalid("number limits");
                }
            }
        }
    }

    static String write(JsonNode value) {
        StringBuilder result = new StringBuilder();
        append(value, result, 0);
        String canonical = result.toString();
        // Child components can each fit while the combined manifest exceeds the token budget.
        // Use the exact same preflight as receipt parsing before any candidate or insert exists.
        assertWithinLimits(canonical);
        return canonical;
    }

    private static void append(JsonNode value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH || out.length() > MAX_CHARS) throw invalid("content limits");
        if (value.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<String, JsonNode>();
            Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String key = normalize(entry.getKey());
                if (fields.put(key, entry.getValue()) != null) throw invalid("duplicate normalized key");
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(entry.getKey(), out); out.append(':');
                append(entry.getValue(), out, depth + 1);
            }
            out.append('}');
        } else if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) out.append(',');
                append(value.get(i), out, depth + 1);
            }
            out.append(']');
        } else if (value.isTextual()) {
            quote(normalize(value.textValue()), out);
        } else if (value.isNumber()) {
            BigDecimal number = value.decimalValue().stripTrailingZeros();
            if (Math.abs((long) number.scale()) > 1000 || number.precision() > 1000) {
                throw invalid("number limits");
            }
            out.append(number.signum() == 0 ? "0" : number.toPlainString());
        } else if (value.isNull() || value.isBoolean()) {
            out.append(value.toString());
        } else {
            throw invalid("JSON value");
        }
    }

    static String normalize(String value) {
        // Reject unpaired surrogates rather than hashing replacement bytes.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid("Unicode");
                }
            } else if (Character.isLowSurrogate(c)) throw invalid("Unicode");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static void quote(String value, StringBuilder out) {
        try { out.append(JSON.writeValueAsString(value)); }
        catch (Exception invalid) { throw invalid("string"); }
    }

    static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid content-release " + field);
    }
}
