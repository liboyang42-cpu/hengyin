package com.chengyinhub.business.security;

import com.chengyinhub.common.utils.DesensitizedUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 通用 PII 加解密 / 脱敏 / 盲索引(报名与收货信息,合规《隐私政策》§6.3)。
 *
 * 沿用提现 PII 的 AES-256-GCM 方式,另加:
 *  - 兼容读:密文以 "v1:" 前缀区分;未迁移的历史明文原样返回(迁移窗口内不炸)。
 *  - 盲索引:HMAC-SHA256(手机号)→ 十六进制,供加密后仍按手机号精确检索。
 *  - 脱敏:手机号前3后4(138****1234)、姓名首字+*、通用中间掩码。
 *
 * 密钥沿用提现同款管理方式(env / 配置,未配则启动不拦、真正处理 PII 写入时 fail-closed 报错):
 *  chengyin.pii.key       Base64 的 32 字节 AES-256 密钥(★部署 PII jar 前必须先配,否则报名/收货写入被拒)
 *  chengyin.pii.hmac-key  盲索引 HMAC 密钥(任意非空串;未配则回退用 AES 密钥material,建议单独配以做密钥隔离)
 */
@Component
public class PiiCryptoService {

    private static final Logger log = LoggerFactory.getLogger(PiiCryptoService.class);

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String PREFIX = "v1:";
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] aesKey;   // 32 bytes or null
    private final byte[] hmacKey;  // non-null when aesKey present

    public PiiCryptoService(@Value("${chengyin.pii.key:}") String encodedKey,
                            @Value("${chengyin.pii.hmac-key:}") String hmacSecret) {
        if (encodedKey == null || encodedKey.trim().isEmpty()) {
            this.aesKey = null;
            this.hmacKey = null;
        } else {
            byte[] k = Base64.getDecoder().decode(encodedKey.trim());
            if (k.length != 32) {
                throw new IllegalStateException("chengyin.pii.key 必须是 Base64 的 32 字节 AES-256 密钥");
            }
            this.aesKey = k;
            this.hmacKey = (hmacSecret == null || hmacSecret.trim().isEmpty())
                    ? deriveBlindIndexKey(k) // 未单独配则从 AES 材料域分离派生,避免加密与盲索引复用同一密钥
                    : hmacSecret.trim().getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 未单独配 hmac-key 时,从 AES 密钥材料域分离派生盲索引专用密钥(HMAC-SHA256(aesKey, 固定标签)),
     *  使加密与盲索引不复用同一 32 字节材料。功能为绿地(尚无盲索引数据),派生方式无历史兼容负担。 */
    private static byte[] deriveBlindIndexKey(byte[] aesKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(aesKey, "HmacSHA256"));
            return mac.doFinal("chengyin-pii-blind-index-v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("盲索引密钥派生失败", e);
        }
    }

    /** MyBatis TypeHandler 由 MyBatis(非 Spring)实例化,靠此静态引用拿到本 Spring 单例。 */
    private static volatile PiiCryptoService instance;

    @PostConstruct
    public void register() {
        instance = this;
        if (aesKey == null) {
            log.error("PiiCryptoService 未配 chengyin.pii.key:报名/收货 PII 写入将被【fail-closed 拒绝】,请先配密钥再放量!");
        } else {
            log.info("PiiCryptoService 初始化:加密已启用");
        }
    }

    public static PiiCryptoService getInstance() {
        return instance;
    }

    public boolean isEnabled() {
        return aesKey != null;
    }

    private void requireKey() {
        if (aesKey == null) {
            throw new IllegalStateException("报名/收货 PII 加密密钥未配置(chengyin.pii.key),无法处理敏感资料");
        }
    }

    /** 明文→密文(带 v1: 前缀)。null/空原样返回。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ct, 0, payload, nonce.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("PII 加密失败", e);
        }
    }

    /**
     * 密文→明文。兼容读:无 v1: 前缀视为未迁移的历史明文,原样返回(不抛)。
     * 未配密钥但遇到 v1: 密文时抛错(带着坏配置上线要早暴露)。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored; // 历史明文或空,原样
        }
        requireKey();
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] nonce = new byte[NONCE_LEN];
            byte[] ct = new byte[payload.length - NONCE_LEN];
            System.arraycopy(payload, 0, nonce, 0, NONCE_LEN);
            System.arraycopy(payload, NONCE_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII 解密失败", e);
        }
    }

    /** 盲索引:HMAC-SHA256(明文)→ 十六进制小写。供加密后按手机号精确检索。null/空返回 null。 */
    public String blindIndex(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        requireKey();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] h = mac.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("PII 盲索引失败", e);
        }
    }

    /** 手机号脱敏:前3后4,中间****。非11位则通用中间掩码。 */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.trim();
        if (p.length() == 11) {
            return p.substring(0, 3) + "****" + p.substring(7);
        }
        return maskMiddle(p);
    }

    /**
     * 姓名脱敏:委托给全站唯一实现(只保留首字,张三→张*、张三丰→张*)。
     *
     * <p>原实现是等长星(张三丰→张**),会暴露姓名长度;且对单字名直接原样返回(张→张),
     * 等于完全没遮。本掩码是读时现算、不落库,故改口径无需任何数据迁移。
     * null/空串保持原契约原样返回,以免把 JSON 里的 null 变成空串。
     */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return DesensitizedUtil.username(name);
    }

    /** 通用中间掩码:保留首尾,中间*。 */
    public static String maskMiddle(String s) {
        if (s == null || s.length() <= 2) {
            return s == null ? null : "*";
        }
        int keep = Math.min(2, s.length() / 3);
        String head = s.substring(0, keep);
        String tail = s.substring(s.length() - keep);
        return head + "****" + tail;
    }
}
