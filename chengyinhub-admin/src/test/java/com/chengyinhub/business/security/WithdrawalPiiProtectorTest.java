package com.chengyinhub.business.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WithdrawalPiiProtectorTest {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        WithdrawalPiiProtector protector = new WithdrawalPiiProtector(TEST_KEY);

        String first = protector.encrypt("6222021234567890");
        String second = protector.encrypt("6222021234567890");

        assertNotEquals(first, second);
        assertEquals("6222021234567890", protector.decrypt(first));
    }

    @Test
    void rejectsConfiguredButInvalidKey() {
        // 配置了密钥但格式非法 → 立即失败(意图启用却写错,应尽早暴露)
        assertThrows(IllegalArgumentException.class, () -> new WithdrawalPiiProtector("not-a-256-bit-key"));
    }

    @Test
    void bootsWithoutKeyButRefusesToProcessPii() {
        // 未配置密钥 → 允许构造(应用照常启动),仅在真正处理提现资料时才拒绝
        WithdrawalPiiProtector protector = new WithdrawalPiiProtector("");

        assertEquals(null, protector.encrypt(null));      // 无 PII 可处理时不需要密钥
        assertThrows(IllegalStateException.class, () -> protector.encrypt("6222021234567890"));
        assertThrows(IllegalStateException.class, () -> protector.decrypt("v1:whatever"));
    }

    @Test
    void masksSensitiveValuesForEveryResponseSurface() {
        assertEquals("张*", WithdrawalPiiProtector.maskName("张三"));
        assertEquals("138****8000", WithdrawalPiiProtector.maskPhone("13800138000"));
        assertEquals("************7890", WithdrawalPiiProtector.maskBankAccount("6222021234567890"));
    }
}
