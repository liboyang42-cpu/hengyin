package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubWaitlistEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 可重建、不可伪造的 offer token；持久层只保存 sha256(rawToken)。 */
@Component
public class ClubWaitlistTokenCodec {
    private final byte[] secret;

    public ClubWaitlistTokenCodec(@Value("${token.secret}") String secret) {
        if (secret == null || secret.trim().isEmpty()) throw new IllegalArgumentException("waitlist token secret missing");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String token(ClubWaitlistEntry entry) {
        if (entry == null || entry.getId() == null || entry.getMemberId() == null
                || entry.getActivityId() == null || entry.getTicketId() == null
                || entry.getOfferExpiresAt() == null) throw new IllegalArgumentException("offer token facts missing");
        String canonical = entry.getId() + ":" + entry.getMemberId() + ":" + entry.getActivityId()
                + ":" + entry.getTicketId() + ":" + entry.getOfferExpiresAt().getTime();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    public String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest) value.append(String.format("%02x", b & 0xff));
            return value.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public boolean matches(ClubWaitlistEntry entry, String rawToken) {
        if (entry == null || rawToken == null || entry.getOfferTokenHash() == null) return false;
        byte[] actual = hash(rawToken).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = entry.getOfferTokenHash().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual) && MessageDigest.isEqual(
                token(entry).getBytes(StandardCharsets.US_ASCII), rawToken.getBytes(StandardCharsets.US_ASCII));
    }
}
