package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanonicalDigestTest {

    @Test
    void canonicalJoinAndSha256LockTheUtf8ByteContract() {
        String canonical = CanonicalDigest.join("alpha", "β", "1.00");

        assertEquals("alpha\u001fβ\u001f1.00", canonical);
        assertEquals("6ec8302990a495072afdf0fb75d3685a2ac5de35b785759397ef68810f4d8e9a",
                CanonicalDigest.sha256Hex(canonical));
        assertNotEquals(CanonicalDigest.join("ab", "c"), CanonicalDigest.join("a", "bc"),
                "US(0x1f) 分隔必须阻断相邻字段拼接歧义");
    }
}
