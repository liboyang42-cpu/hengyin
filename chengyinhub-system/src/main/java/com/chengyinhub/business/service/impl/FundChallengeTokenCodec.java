package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
class FundChallengeTokenCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] secret;

    FundChallengeTokenCodec(@Value("${token.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String nonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String token(FundChallengeBinding binding, String nonce) {
        return nonce + "." + hmac(binding.canonical() + "|" + nonce);
    }

    String hash(String value) { return FundActionPreflightPolicy.sha256(value); }
    String counterpartyHash(String actionType, String bankName, String bankAccount) {
        return hmac(actionType + "|" + normalize(bankName) + "|" + normalize(bankAccount));
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private String normalize(String value) { return value == null ? "" : value.trim(); }

    static final class FundChallengeBinding {
        private final Long memberId;
        private final String actionType;
        private final String requestId;
        private final String fingerprint;
        FundChallengeBinding(Long memberId, String actionType, String requestId, String fingerprint) {
            this.memberId = memberId;
            this.actionType = actionType;
            this.requestId = requestId;
            this.fingerprint = fingerprint;
        }
        String canonical() { return memberId + "|" + actionType + "|" + requestId + "|" + fingerprint; }
    }
}
