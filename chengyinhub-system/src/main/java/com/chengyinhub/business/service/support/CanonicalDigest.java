package com.chengyinhub.business.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 两个资金快照共用的最小 canonical SHA-256 字节口径。 */
public final class CanonicalDigest {
    private static final String SEPARATOR = "\u001f";

    private CanonicalDigest() {
    }

    public static String join(String... values) {
        return String.join(SEPARATOR, values);
    }

    public static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
