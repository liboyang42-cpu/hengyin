package com.chengyinhub.business.util;

import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VerifyDynCodeUtil} 动态核销码单测(BE-11)。纯逻辑,无 Spring/DB 依赖。
 */
class VerifyDynCodeUtilTest {

    private static final String SECRET = "test-secret-城瘾-be11-2026";
    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @Test
    void issue_then_verify_ok() {
        String code = VerifyDynCodeUtil.issue(8801, "activity", NOW, TTL, 12345, SECRET);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, NOW + 30_000, SECRET);
        assertTrue(r.valid);
        assertEquals(Long.valueOf(8801), r.regId);
        assertEquals("activity", r.type);
    }

    @Test
    void expired_rejected() {
        String code = VerifyDynCodeUtil.issue(8801, "activity", NOW, TTL, 1, SECRET);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, NOW + TTL + 1, SECRET);
        assertFalse(r.valid);
        assertEquals("已过期", r.reason);
    }

    @Test
    void tampered_regId_rejected() {
        String code = VerifyDynCodeUtil.issue(8801, "activity", NOW, TTL, 1, SECRET);
        String tampered = code.replaceFirst("\\.8801\\.", ".9999.");
        assertNotEquals(code, tampered);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(tampered, NOW + 1000, SECRET);
        assertFalse(r.valid);
        assertEquals("签名不匹配", r.reason);
    }

    @Test
    void wrong_secret_rejected() {
        String code = VerifyDynCodeUtil.issue(8801, "activity", NOW, TTL, 1, SECRET);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, NOW + 1000, "other-secret");
        assertFalse(r.valid);
        assertEquals("签名不匹配", r.reason);
    }

    @Test
    void nonce_changes_code_but_both_valid() {
        String c1 = VerifyDynCodeUtil.issue(8801, "activity", NOW, TTL, 1, SECRET);
        String c2 = VerifyDynCodeUtil.issue(8801, "activity", NOW, TTL, 2, SECRET);
        assertNotEquals(c1, c2);
        assertTrue(VerifyDynCodeUtil.verify(c1, NOW + 1000, SECRET).valid);
        assertTrue(VerifyDynCodeUtil.verify(c2, NOW + 1000, SECRET).valid);
    }

    @Test
    void malformed_rejected() {
        assertFalse(VerifyDynCodeUtil.verify("garbage", NOW, SECRET).valid);
        assertFalse(VerifyDynCodeUtil.verify("", NOW, SECRET).valid);
        assertFalse(VerifyDynCodeUtil.verify(null, NOW, SECRET).valid);
        assertFalse(VerifyDynCodeUtil.verify("v1.8801.activity.999.1", NOW, SECRET).valid); // 缺 sig 段
    }

    // ===== C2 据点核销码:regId=poiId,type="citynode_"+玩家memberId,两者均在签名内 =====

    @Test
    void citynode_roundtrip_carries_poi_and_member() {
        long poiId = 5501, playerId = 30012;
        String code = VerifyDynCodeUtil.issue(poiId, "citynode_" + playerId, NOW, TTL, 777, SECRET);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, NOW + 30_000, SECRET);
        assertTrue(r.valid);
        assertEquals(Long.valueOf(poiId), r.regId);                       // poi 可信
        assertEquals("citynode_" + playerId, r.type);
        assertEquals(playerId, Long.parseLong(r.type.substring("citynode_".length()))); // 发券对象可信
    }

    @Test
    void citynode_tampered_member_rejected() {
        // 商家试图把发券对象从玩家 30012 改成自己人 99999 → 签名不匹配,核销被拒
        String code = VerifyDynCodeUtil.issue(5501, "citynode_30012", NOW, TTL, 1, SECRET);
        String tampered = code.replaceFirst("citynode_30012", "citynode_99999");
        assertNotEquals(code, tampered);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(tampered, NOW + 1000, SECRET);
        assertFalse(r.valid);
        assertEquals("签名不匹配", r.reason);
    }

    @Test
    void citynode_tampered_poi_rejected() {
        // 篡改 poiId(想核销别家据点的券)→ 签名不匹配
        String code = VerifyDynCodeUtil.issue(5501, "citynode_30012", NOW, TTL, 1, SECRET);
        String tampered = code.replaceFirst("\\.5501\\.", ".6602.");
        assertNotEquals(code, tampered);
        assertFalse(VerifyDynCodeUtil.verify(tampered, NOW + 1000, SECRET).valid);
    }
}
