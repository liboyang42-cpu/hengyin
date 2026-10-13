package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class CommunityMediaReceiptServiceImpl implements ICommunityMediaReceiptService {
    private static final long TTL_SECONDS = 15 * 60L;
    private final byte[] secret;

    public CommunityMediaReceiptServiceImpl(@Value("${community.media.receipt-secret:${token.secret}}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(Long memberId, String objectKey, long byteSize) {
        long expiresAt = System.currentTimeMillis() / 1000L + TTL_SECONDS;
        return expiresAt + "." + signature(payload(memberId, objectKey, byteSize, expiresAt));
    }

    @Override
    public boolean verify(Long memberId, String objectKey, long byteSize, String receipt) {
        if (memberId == null || memberId <= 0 || objectKey == null || receipt == null) return false;
        int separator = receipt.indexOf('.');
        if (separator <= 0 || separator == receipt.length() - 1) return false;
        try {
            long expiresAt = Long.parseLong(receipt.substring(0, separator));
            if (expiresAt < System.currentTimeMillis() / 1000L) return false;
            byte[] expected = signature(payload(memberId, objectKey, byteSize, expiresAt)).getBytes(StandardCharsets.US_ASCII);
            byte[] actual = receipt.substring(separator + 1).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String payload(Long memberId, String objectKey, long byteSize, long expiresAt) {
        return memberId + "\n" + objectKey + "\n" + byteSize + "\n" + expiresAt;
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("社区上传回执签名失败", e);
        }
    }
}
