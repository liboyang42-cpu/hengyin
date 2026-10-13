package com.chengyinhub.business.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TypeHandler fail-closed 单测:锁死对抗审查发现 #1(fail-open 明文泄露)的修复,防回退。
 * 未配 chengyin.pii.key → 写入抛 SQLException(绝不明文落库);配了 → 写密文/HMAC。
 * TypeHandler 走 JDBC 层,项目其它测试(纯 Mockito mock mapper)不触达,故单独覆盖。
 */
class PiiTypeHandlerFailClosedTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]); // 32 字节 AES-256

    @AfterEach
    void resetInstance() {
        // TypeHandler 靠 PiiCryptoService 静态单例;测试后清空避免污染其它用例
        ReflectionTestUtils.setField(PiiCryptoService.class, "instance", null);
    }

    @Test
    void cryptoHandler_noKey_throwsAndWritesNothing() throws SQLException {
        new PiiCryptoService("", "").register(); // 未配密钥 → isEnabled=false
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(SQLException.class,
                () -> new PiiCryptoTypeHandler().setNonNullParameter(ps, 1, "13800001111", null));
        verify(ps, never()).setString(anyInt(), anyString()); // 绝不写明文
    }

    @Test
    void blindHandler_noKey_throwsAndWritesNothing() throws SQLException {
        new PiiCryptoService("", "").register();
        PreparedStatement ps = mock(PreparedStatement.class);
        assertThrows(SQLException.class,
                () -> new PiiBlindIndexTypeHandler().setNonNullParameter(ps, 1, "13800001111", null));
        verify(ps, never()).setString(anyInt(), anyString());
    }

    @Test
    void cryptoHandler_withKey_writesCiphertext() throws SQLException {
        new PiiCryptoService(KEY, "h").register(); // 已配密钥
        PreparedStatement ps = mock(PreparedStatement.class);
        new PiiCryptoTypeHandler().setNonNullParameter(ps, 1, "13800001111", null);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(1), cap.capture());
        assertTrue(cap.getValue().startsWith("v1:")); // 落库是密文,非明文
    }

    @Test
    void blindHandler_withKey_writesHash() throws SQLException {
        new PiiCryptoService(KEY, "h").register();
        PreparedStatement ps = mock(PreparedStatement.class);
        new PiiBlindIndexTypeHandler().setNonNullParameter(ps, 1, "13800001111", null);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(1), cap.capture());
        assertTrue(cap.getValue().matches("[0-9a-f]{64}")); // 64 位 HMAC hex,非明文
    }
}
