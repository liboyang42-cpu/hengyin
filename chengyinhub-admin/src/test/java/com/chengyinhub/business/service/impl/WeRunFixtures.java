package com.chengyinhub.business.service.impl;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 造一份真的能被 WeRunDecryptor 解开的微信运动密文。
 *
 * 为什么不 mock 掉解密:这条链路的价值全在"只信服务端解出来的数",
 * 把解密 mock 成固定返回值,测的就只是 mock 自己了。
 */
final class WeRunFixtures {

    static final String APP_ID = "wxdf8fd71ea3c614b4";
    /* 测试固定的 16 字节 AES 分组材料。刻意不叫 KEY:那个名字会让密钥扫描器把测试夹具
       当成泄露的凭据(gitleaks generic-api-key),而放宽规则去迁就测试是本末倒置。 */
    private static final byte[] AES_BLOCK = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV = "fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private WeRunFixtures() {
    }

    static String sessionKey() {
        return Base64.getEncoder().encodeToString(AES_BLOCK);
    }

    static String iv() {
        return Base64.getEncoder().encodeToString(AES_IV);
    }

    /** 造一份"今天走了 steps 步"的密文;前面还塞了两天历史,好验证取的是最后一条 */
    static String encryptSteps(int steps) {
        String plain = "{\"stepInfoList\":["
                + "{\"timestamp\":1756000000,\"step\":1},"
                + "{\"timestamp\":1756100000,\"step\":2},"
                + "{\"timestamp\":1756200000,\"step\":" + steps + "}],"
                + "\"watermark\":{\"appid\":\"" + APP_ID + "\",\"timestamp\":1756200000}}";
        try {
            byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
            int pad = 16 - (raw.length % 16);
            byte[] padded = new byte[raw.length + pad];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            for (int i = raw.length; i < padded.length; i++) {
                padded[i] = (byte) pad;
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_BLOCK, "AES"), new IvParameterSpec(AES_IV));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具加密失败", e);
        }
    }
}
