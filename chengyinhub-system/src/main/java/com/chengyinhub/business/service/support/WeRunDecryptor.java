package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 微信运动(wx.getWeRunData)加密数据的解密与校验。
 *
 * 为什么必须在服务端做:微信不提供明文步数接口,客户端只拿得到 encryptedData + iv,
 * 解密要用 session_key —— 而 session_key 一旦下发到前端,步数就成了客户端说了算。
 *
 * 本类是纯函数,不碰网络也不碰库:session_key 从哪来是调用方的事
 * (见 IWeChatMiniSessionService —— 上报时用一个新鲜的 code 现换,不做存储,
 *  因为存下来就要管过期与失效,而微信本来就要求「先 wx.login 再 getWeRunData」)。
 *
 * ★ 两条不能省的校验,少一条这个解密就是摆设:
 *   ① watermark.appid 必须等于本小程序的 appid —— 否则拿别家小程序的合法密文来就能过;
 *   ② stepInfoList 必须非空且按时间戳取最后一条 —— 它是最近 30 天的序列,不是单个数。
 */
public final class WeRunDecryptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 微信侧固定使用 AES-128-CBC;密钥与 IV 都是 base64 后的 16 字节 */
    private static final String TRANSFORMATION = "AES/CBC/NoPadding";
    private static final int BLOCK_SIZE = 16;

    private WeRunDecryptor() {
    }

    /** 解密结果:今天的步数 + 它自己带的时间戳,供调用方判断新鲜度。 */
    public static final class WeRunResult {
        private final int todaySteps;
        private final long timestamp;

        public WeRunResult(int todaySteps, long timestamp) {
            this.todaySteps = todaySteps;
            this.timestamp = timestamp;
        }

        public int getTodaySteps() {
            return todaySteps;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * @param sessionKeyBase64   jscode2session 换来的 session_key(base64)
     * @param encryptedDataBase64 wx.getWeRunData 返回的密文(base64)
     * @param ivBase64            wx.getWeRunData 返回的初始向量(base64)
     * @param expectedAppId       本小程序 appid;与 watermark 对不上一律拒绝
     * @throws IllegalArgumentException 任一环节不成立都抛;调用方不要吞掉换成"0 步"
     */
    public static WeRunResult decrypt(String sessionKeyBase64, String encryptedDataBase64,
                                      String ivBase64, String expectedAppId) {
        if (isBlank(sessionKeyBase64) || isBlank(encryptedDataBase64) || isBlank(ivBase64)) {
            throw new IllegalArgumentException("微信运动数据不完整");
        }
        if (isBlank(expectedAppId)) {
            // 配置缺失时宁可整条拒绝:放行等于关掉 appid 校验,那两条校验就只剩一条
            throw new IllegalArgumentException("小程序 appid 未配置,拒绝解密微信运动数据");
        }
        final String plain;
        try {
            byte[] key = Base64.getDecoder().decode(sessionKeyBase64);
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            byte[] data = Base64.getDecoder().decode(encryptedDataBase64);
            if (key.length != BLOCK_SIZE || iv.length != BLOCK_SIZE) {
                throw new IllegalArgumentException("微信运动密钥或向量长度不正确");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            plain = stripPkcs7(cipher.doFinal(data));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // 不把底层异常往上抛:解密失败的细节对攻击者有用,对用户没用
            throw new IllegalArgumentException("微信运动数据解密失败,请重新授权后再试");
        }

        final JsonNode root;
        try {
            root = MAPPER.readTree(plain);
        } catch (Exception e) {
            throw new IllegalArgumentException("微信运动数据格式不正确");
        }

        String appId = root.path("watermark").path("appid").asText("");
        if (!expectedAppId.equals(appId)) {
            // 少了这条,拿别家小程序的合法密文来就能刷步数
            throw new IllegalArgumentException("微信运动数据来源不匹配");
        }

        JsonNode list = root.path("stepInfoList");
        if (!list.isArray() || list.size() == 0) {
            throw new IllegalArgumentException("微信运动数据里没有步数记录");
        }
        // stepInfoList 是最近 30 天的升序序列,今天是最后一条
        JsonNode today = list.get(list.size() - 1);
        int steps = today.path("step").asInt(-1);
        if (steps < 0) {
            throw new IllegalArgumentException("微信运动步数不合法");
        }
        long timestamp = today.path("timestamp").asLong(0L);
        return new WeRunResult(steps, timestamp);
    }

    /**
     * 微信侧用 PKCS#7 补位,但 Java 的 AES/CBC/PKCS5Padding 在补位字节异常时会抛
     * BadPaddingException —— 那样连"数据本身没问题只是补位怪"都区分不了。
     * 这里用 NoPadding 解完自己剥,补位不合法就当密文不可信。
     */
    private static String stripPkcs7(byte[] decrypted) {
        if (decrypted == null || decrypted.length == 0) {
            throw new IllegalArgumentException("微信运动数据为空");
        }
        int pad = decrypted[decrypted.length - 1];
        if (pad < 1 || pad > BLOCK_SIZE || pad > decrypted.length) {
            throw new IllegalArgumentException("微信运动数据补位不合法");
        }
        return new String(decrypted, 0, decrypted.length - pad, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
