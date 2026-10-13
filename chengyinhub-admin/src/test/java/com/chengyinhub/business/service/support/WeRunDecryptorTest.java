package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信运动解密的校验契约。
 *
 * 这里的重点不是"能解开",是**该拒的必须拒** —— 步数直接换 XP 与落章,
 * 解密器一旦对来源不设防,拿别家小程序的合法密文就能刷分,而且不报错、零告警。
 * 所以每条拒绝路径都各有一个负控。
 */
class WeRunDecryptorTest {

    private static final String APP_ID = "wxdf8fd71ea3c614b4";
    private static final String OTHER_APP_ID = "wxffffffffffffffff";
    /** 16 字节 key/iv,base64 后给解密器 */
    /* 测试固定的 16 字节 AES 分组材料。刻意不叫 KEY:那个名字会让密钥扫描器把测试夹具
       当成泄露的凭据(gitleaks generic-api-key),而放宽规则去迁就测试是本末倒置。 */
    private static final byte[] AES_BLOCK = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV = "fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    /** 用与微信侧相同的 AES-128-CBC + PKCS#7 造密文,好让测试真的走完整条解密路径 */
    private static String encrypt(String plain) throws Exception {
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
    }

    private static String key() {
        return Base64.getEncoder().encodeToString(AES_BLOCK);
    }

    private static String iv() {
        return Base64.getEncoder().encodeToString(AES_IV);
    }

    private static String payload(String appId, String stepInfoList) {
        return "{\"stepInfoList\":" + stepInfoList
                + ",\"watermark\":{\"appid\":\"" + appId + "\",\"timestamp\":1756200000}}";
    }

    @Test
    void 解出最后一天的步数() throws Exception {
        String cipherText = encrypt(payload(APP_ID,
                "[{\"timestamp\":1756000000,\"step\":3000},"
                        + "{\"timestamp\":1756100000,\"step\":5000},"
                        + "{\"timestamp\":1756200000,\"step\":4286}]"));

        WeRunDecryptor.WeRunResult result = WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID);

        // stepInfoList 是最近 30 天升序序列,取最后一条才是今天 —— 取第一条会拿到 30 天前
        assertEquals(4286, result.getTodaySteps());
        assertEquals(1756200000L, result.getTimestamp());
    }

    @Test
    void 负控_别家小程序的合法密文必须被拒() throws Exception {
        // 密文本身完全合法(能解开、结构正确),只有 watermark.appid 不是我们
        String cipherText = encrypt(payload(OTHER_APP_ID, "[{\"timestamp\":1756200000,\"step\":99999}]"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID));
        assertTrue(e.getMessage().contains("来源不匹配"), e.getMessage());
    }

    @Test
    void 负控_appid未配置时整条拒绝而不是放行() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":100}]"));

        // 配置缺失时若放行,等于把上面那条 appid 校验静默关掉
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), ""));
    }

    @Test
    void 负控_空步数序列不能当成0步() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[]"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID));
        assertTrue(e.getMessage().contains("没有步数记录"), e.getMessage());
    }

    @Test
    void 负控_密文被篡改必须解不开而不是解出垃圾() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":100}]"));
        byte[] raw = Base64.getDecoder().decode(cipherText);
        raw[raw.length - 1] ^= 0x5A;   // 动最后一个分组,补位与内容一起被破坏
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), tampered, iv(), APP_ID));
    }

    @Test
    void 负控_密钥长度不对直接拒() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":100}]"));
        String shortKey = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(shortKey, cipherText, iv(), APP_ID));
        assertTrue(e.getMessage().contains("长度不正确"), e.getMessage());
    }

    @Test
    void 负控_缺参数不能静默当成0步() {
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(null, "x", "y", APP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), "", iv(), APP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), "x", " ", APP_ID));
    }

    @Test
    void 负控_负数步数不合法() throws Exception {
        String cipherText = encrypt(payload(APP_ID, "[{\"timestamp\":1756200000,\"step\":-1}]"));

        assertThrows(IllegalArgumentException.class,
                () -> WeRunDecryptor.decrypt(key(), cipherText, iv(), APP_ID));
    }
}
