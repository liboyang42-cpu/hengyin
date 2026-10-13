package com.chengyinhub.business.security;

import com.chengyinhub.common.enums.DesensitizedType;
import com.chengyinhub.common.utils.DesensitizedUtil;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PII 加解密/盲索引/脱敏单测:roundtrip、历史明文兼容读、盲索引确定性、掩码规则、未配密钥退化。
 */
class PiiCryptoServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]); // 32 字节 AES-256
    private final PiiCryptoService svc = new PiiCryptoService(KEY, "hmac-secret");
    private final PiiCryptoService noKey = new PiiCryptoService("", "");

    @Test
    void encryptDecrypt_roundtrip() {
        String plain = "13812341234";
        String enc = svc.encrypt(plain);
        assertTrue(enc.startsWith("v1:"));
        assertNotEquals(plain, enc);
        assertEquals(plain, svc.decrypt(enc));
    }

    @Test
    void encrypt_sameInput_differentCiphertext_butDecryptsSame() {
        String plain = "张三丰";
        String a = svc.encrypt(plain);
        String b = svc.encrypt(plain);
        assertNotEquals(a, b); // 随机 nonce → 每次密文不同
        assertEquals(plain, svc.decrypt(a));
        assertEquals(plain, svc.decrypt(b));
    }

    @Test
    void decrypt_legacyPlaintext_returnedAsIs() {
        // 未迁移的历史明文(无 v1: 前缀)→ 原样返回,不炸
        assertEquals("13800001111", svc.decrypt("13800001111"));
        assertNull(svc.decrypt(null));
        assertEquals("", svc.decrypt(""));
    }

    @Test
    void blindIndex_deterministic_andDistinct() {
        String h1 = svc.blindIndex("13812341234");
        String h2 = svc.blindIndex("13812341234");
        String h3 = svc.blindIndex("13800000000");
        assertEquals(h1, h2);           // 同输入同索引 → 可精确检索
        assertNotEquals(h1, h3);        // 不同输入不同
        assertEquals(64, h1.length());  // SHA-256 hex
        assertNull(svc.blindIndex(null));
    }

    @Test
    void maskPhone_first3last4() {
        assertEquals("138****1234", PiiCryptoService.maskPhone("13812341234"));
        assertNull(PiiCryptoService.maskPhone(null));
    }

    /**
     * 口径已统一为「只保留首字」(全站唯一实现 DesensitizedUtil.username),本用例随之更新:
     *  - 欧阳娜娜 原先→欧***(等长星,暴露姓名长度),现→欧*;
     *  - 单字名 张 原先→张,即原样返回、完全没遮,现→张*。
     * 本掩码读时现算不落库,故改口径无需数据迁移。
     */
    @Test
    void maskName_keepsFirstChar() {
        assertEquals("张*", PiiCryptoService.maskName("张三"));
        assertEquals("欧*", PiiCryptoService.maskName("欧阳娜娜"), "不得暴露姓名长度");
        assertEquals("张*", PiiCryptoService.maskName("张"), "单字名也不得原样返回");
        assertNull(PiiCryptoService.maskName(null), "null 保持原契约,别把 JSON 里的 null 变成空串");
        assertEquals("", PiiCryptoService.maskName(""));
    }

    /** 三处调用点必须落到同一实现上,否则同一个人在不同页面又会长得不一样。 */
    @Test
    void 三套姓名掩码已收敛到同一实现() {
        for (String n : new String[]{"李四", "张三丰", "欧阳建国", "司马相如之"}) {
            assertEquals(DesensitizedUtil.username(n), PiiCryptoService.maskName(n));
            assertEquals(DesensitizedUtil.username(n), WithdrawalPiiProtector.maskName(n));
            assertEquals(DesensitizedUtil.username(n), DesensitizedType.USERNAME.desensitizer().apply(n));
        }
    }

    @Test
    void noKey_disabled_encryptThrows_decryptPassThrough() {
        assertFalse(noKey.isEnabled());
        assertEquals("13800001111", noKey.decrypt("13800001111")); // 明文原样
        assertThrows(IllegalStateException.class, () -> noKey.encrypt("13800001111"));
    }
}
