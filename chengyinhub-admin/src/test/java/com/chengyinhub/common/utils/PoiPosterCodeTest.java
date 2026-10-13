package com.chengyinhub.common.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 据点张贴码(vm=4 到店扫码)。
 *
 * 与核销动态码方向相反:动态码是玩家掏给商家扫、靠 TTL 防转赠;
 * 张贴码是商家打印贴在店里给玩家扫,**必须长期有效**,所以不能有 TTL。
 * 它不存库 —— 码就是签名过的 poiId,不是秘密;想作废换 secret 即可。
 *
 * 真正防「拍下码回家扫」的是 `/api/city/nodes/{id}/complete` 里**先于**验码执行的
 * 服务端 GPS 半径校验。这里只保证:签名保真 + 认得出是哪个 poi。
 */
class PoiPosterCodeTest {

    private static final String SECRET = "test-secret-0806";

    @Test
    @Timeout(5)
    void issueThenVerify_roundTrips() {
        String code = VerifyDynCodeUtil.issuePoiPoster(4242L, SECRET);
        assertEquals(Long.valueOf(4242L), VerifyDynCodeUtil.verifyPoiPoster(code, SECRET));
    }

    /** 长期有效:同一个 poi 反复签发必须是同一串,否则商家印出来的码会时不时失效。 */
    @Test
    @Timeout(5)
    void stableAcrossCalls() {
        assertEquals(VerifyDynCodeUtil.issuePoiPoster(7L, SECRET),
                VerifyDynCodeUtil.issuePoiPoster(7L, SECRET),
                "张贴码必须稳定,变了商家就得重印");
    }

    /** ★负控:改签名 = 伪造,必须不认。 */
    @Test
    @Timeout(5)
    void tamperedSignature_rejected() {
        String code = VerifyDynCodeUtil.issuePoiPoster(4242L, SECRET);
        String[] p = code.split("\\.");
        String forged = p[0] + "." + p[1] + "." + p[2].substring(0, p[2].length() - 1) + "X";
        assertNull(VerifyDynCodeUtil.verifyPoiPoster(forged, SECRET));
    }

    /**
     * ★负控:改 poiId 但留原签名 —— 必须不认。
     * 这条要是过了,拿 A 店的码就能声称自己在 B 店。
     */
    @Test
    @Timeout(5)
    void swappedPoiId_rejected() {
        String code = VerifyDynCodeUtil.issuePoiPoster(4242L, SECRET);
        String[] p = code.split("\\.");
        assertNull(VerifyDynCodeUtil.verifyPoiPoster(p[0] + ".9999." + p[2], SECRET),
                "换了 poiId 签名就对不上,不能认");
    }

    /** ★负控:换密钥即作废 —— 这是唯一的「重置张贴码」手段,必须真能作废。 */
    @Test
    @Timeout(5)
    void otherSecret_rejected() {
        String code = VerifyDynCodeUtil.issuePoiPoster(4242L, SECRET);
        assertNull(VerifyDynCodeUtil.verifyPoiPoster(code, "another-secret"));
        assertNotEquals(code, VerifyDynCodeUtil.issuePoiPoster(4242L, "another-secret"));
    }

    /** ★负控:动态核销码不能被当成张贴码收下 —— 两套码语义相反,混用就是绕过 TTL。 */
    @Test
    @Timeout(5)
    void dynamicCode_notAcceptedAsPoster() {
        String dyn = VerifyDynCodeUtil.issue(1L, "topic", System.currentTimeMillis(), 60_000L, 12345L, SECRET);
        assertNull(VerifyDynCodeUtil.verifyPoiPoster(dyn, SECRET));
    }

    /** 反向也不能混:张贴码没有 exp/nonce 段,动态校验必须判它无效。 */
    @Test
    @Timeout(5)
    void posterCode_notAcceptedAsDynamic() {
        String poster = VerifyDynCodeUtil.issuePoiPoster(4242L, SECRET);
        assertEquals(false, VerifyDynCodeUtil.verify(poster, System.currentTimeMillis(), SECRET).valid);
    }

    @Test
    @Timeout(5)
    void garbage_rejected() {
        assertNull(VerifyDynCodeUtil.verifyPoiPoster(null, SECRET));
        assertNull(VerifyDynCodeUtil.verifyPoiPoster("", SECRET));
        assertNull(VerifyDynCodeUtil.verifyPoiPoster("poi1.abc.sig", SECRET));
        assertNull(VerifyDynCodeUtil.verifyPoiPoster("hello", SECRET));
    }
}
