package com.chengyinhub.business.service.ai.scope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class AiContractSupport
{
    private AiContractSupport() { }

    static String text(String value, String name)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException(name + " is required");
        }
        requireWellFormedUnicode(value);
        return value;
    }

    static long positive(long value, String name)
    {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static String hash(String value, String name)
    {
        if (value == null || !value.matches("[0-9a-f]{64}"))
        {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return value;
    }

    /** Length prefixes prevent delimiter/empty/null ambiguity; input is never a cache key. */
    static String fingerprint(Object... fields)
    {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields)
        {
            if (field == null) canonical.append("-1:");
            else
            {
                String value = field.toString();
                canonical.append(value.length()).append(':').append(value);
            }
        }
        return sha256(canonical.toString());
    }

    /** UTF-8 replacement of lone surrogates would alias distinct scope/content identities. */
    private static void requireWellFormedUnicode(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current))
            {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("malformed UTF-16 is not an identity or content value");
                i++;
            }
            else if (Character.isLowSurrogate(current))
                throw new IllegalArgumentException("malformed UTF-16 is not an identity or content value");
        }
    }

    static String sha256(String text)
    {
        Objects.requireNonNull(text, "text");
        requireWellFormedUnicode(text);
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException impossible)
        {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
