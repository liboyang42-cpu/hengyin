package com.chengyinhub.business.security;

import com.chengyinhub.common.utils.DesensitizedUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 提现敏感资料的 AES-256-GCM 加密与展示脱敏。
 */
@Component
public class WithdrawalPiiProtector {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public WithdrawalPiiProtector(@Value("${withdrawal.pii.key:}") String encodedKey) {
        // 未配置密钥时允许应用照常启动,仅在真正处理提现资料时才要求密钥,避免未上线功能拖垮全站启动;
        // 已配置但格式非法则立即失败,防止带着坏密钥上线。
        this.key = (encodedKey == null || encodedKey.trim().isEmpty())
                ? null
                : new SecretKeySpec(decodeKey(encodedKey), "AES");
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "提现敏感资料加密密钥未配置(withdrawal.pii.key / CHENGYIN_WITHDRAWAL_PII_KEY),无法处理提现资料");
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, nonce));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("提现敏感资料加密失败", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        requireKey();
        if (!encrypted.startsWith("v1:")) {
            throw new IllegalArgumentException("不支持的提现敏感资料密文版本");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encrypted.substring(3));
            if (payload.length <= NONCE_LENGTH) {
                throw new IllegalArgumentException("提现敏感资料密文无效");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            byte[] ciphertext = new byte[payload.length - NONCE_LENGTH];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("提现敏感资料解密失败", e);
        }
    }

    /**
     * 委托给全站唯一实现。行为与原先逐字节一致(张三→张*、null/空→""),
     * 故 realname_mask 那批持久化掩码无需重算、不需要迁移——这也正是选它当锚点的原因。
     */
    public static String maskName(String value) {
        return DesensitizedUtil.username(value);
    }

    public static String maskPhone(String value) {
        if (value == null || value.length() < 7) {
            return "";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    public static String maskBankAccount(String value) {
        if (value == null || value.length() < 4) {
            return "";
        }
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < value.length() - 4; i++) {
            masked.append('*');
        }
        return masked.append(value.substring(value.length() - 4)).toString();
    }

    private static byte[] decodeKey(String encodedKey) {
        try {
            byte[] key = Base64.getDecoder().decode(encodedKey);
            if (key.length != 32) {
                throw new IllegalArgumentException("提现敏感资料密钥必须是 Base64 编码的 32 字节 AES-256 密钥");
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("提现敏感资料密钥必须是 Base64 编码的 32 字节 AES-256 密钥", e);
        }
    }
}
