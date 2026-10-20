package com.chengyinhub.business.service.ai.npc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class NpcDigest
{
    private NpcDigest() { }

    static String hash(String value)
    {
        if (value == null) throw new IllegalArgumentException("missing digest input");
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c))
            {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("malformed UTF-16");
            }
            else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("malformed UTF-16");
        }
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
