package com.chengyinhub.business.marketplace.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ContractChecks {
    private ContractChecks() { }

    static String text(String value, String name) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank and trimmed");
        }
        // String.getBytes(UTF_8) replaces malformed UTF-16 with '?'. Reject before any
        // identity comparison or snapshot hashing so distinct policies cannot share that encoding.
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException(name + " contains malformed UTF-16");
                }
                i++; // Preserve the exact valid supplementary code point, without normalization.
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " contains malformed UTF-16");
            }
        }
        return value;
    }

    static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static String hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    static String sha256(byte[] content) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("empty snapshot");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.clone());
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static <T> Set<T> set(Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        Set<T> copy = new LinkedHashSet<T>();
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " element"));
        return Collections.unmodifiableSet(copy);
    }

    static <K, V> Map<K, V> map(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        Map<K, V> copy = new LinkedHashMap<K, V>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
