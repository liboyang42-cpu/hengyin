package com.chengyinhub.business.cityboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 内容哈希与审批哈希。审批哈希只由服务端用已落库的内容哈希计算。 */
public final class CityBoardHashes {

    private CityBoardHashes() {
    }

    public static String sha256(String text) {
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

    public static String approval(String contentHash, long draftId, long version, long approverUserId, String requestId) {
        return sha256(contentHash + "\n" + draftId + "\n" + version + "\n" + approverUserId + "\n" + requestId);
    }
}
