package com.chengyinhub.business.citygame.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class CityGameOperationsTexts {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private CityGameOperationsTexts() {
    }

    static ObjectMapper mapper() {
        return JSON;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16));
                out.append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 missing");
        }
    }

    static String canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            ArrayList<String> names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(names.get(i))).append(':').append(canonical(node.get(names.get(i))));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(canonical(node.get(i)));
            }
            return out.append(']').toString();
        }
        if (node.isTextual()) {
            return quote(node.asText());
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "true" : "false";
        }
        return "null";
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored json");
        }
    }

    static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored json");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return JSON.convertValue(read(json), LinkedHashMap.class);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                case '\\':
                    out.append('\\').append(c);
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
