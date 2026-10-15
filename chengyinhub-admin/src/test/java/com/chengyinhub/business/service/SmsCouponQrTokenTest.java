package com.chengyinhub.business.service;

import com.chengyinhub.business.service.impl.SmsCouponServiceImpl;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.common.utils.sign.Md5Utils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * F5:券核销 token 必须由服务端配置密钥 HMAC-SHA256 签发;源码里能看到的任何东西都不足以伪造。
 */
class SmsCouponQrTokenTest {

    private static SmsCouponServiceImpl service(String secret) {
        SmsCouponServiceImpl s = new SmsCouponServiceImpl();
        ReflectionTestUtils.setField(s, "couponQrSecret", secret);
        return s;
    }

    @Test
    void tokenRoundTripsOnlyUnderTheSameServerSecret() {
        String token = service("test-secret-A").generateCouponQrToken(301L);
        assertEquals(301L, service("test-secret-A").verifyCouponQrToken(token));
        // 负控:换一把密钥(= 拿到源码/jar 但没有生产配置的人)签不出可用 token。
        assertNull(service("test-secret-B").verifyCouponQrToken(token));
    }

    @Test
    void legacyMd5TokenBuiltFromTheFormerlyHardcodedSaltIsRejected() {
        long now = System.currentTimeMillis() / 1000L;
        // 旧口径:md5(historyId|epochSec|源码常量盐)。切换后必须无效,否则伪造路径仍在。
        String forged = "301." + now + "." + Md5Utils.hash("301|" + now + "|cy#couponqr$2026@salt!v1");
        assertNull(service("test-secret-A").verifyCouponQrToken(forged));
    }

    @Test
    void tamperedHistoryIdExpiredAndFutureTokensAreRejected() {
        SmsCouponServiceImpl s = service("test-secret-A");
        String token = s.generateCouponQrToken(301L);
        assertNull(s.verifyCouponQrToken(token.replace(".301.", ".302.")));
        long now = System.currentTimeMillis() / 1000L;
        assertNull(s.verifyCouponQrToken(VerifyDynCodeUtil.issueCoupon(301L, now - 91, "test-secret-A")));
        assertNull(s.verifyCouponQrToken(VerifyDynCodeUtil.issueCoupon(301L, now + 61, "test-secret-A")));
        assertEquals(301L, s.verifyCouponQrToken(VerifyDynCodeUtil.issueCoupon(301L, now - 89, "test-secret-A")));
    }

    @Test
    void couponTokenAndPlayerTicketCodeAreNotInterchangeable() {
        String secret = "shared-secret";
        String coupon = service(secret).generateCouponQrToken(301L);
        // 同一把密钥下,券码不能被报名单动态码校验器当成 v1 票码,反之亦然(防跨域混淆)。
        assertFalse(VerifyDynCodeUtil.verify(coupon, System.currentTimeMillis(), secret).valid);
        String ticket = VerifyDynCodeUtil.issue(301L, "coupon", System.currentTimeMillis(), 60_000L, 7L, secret);
        assertNull(service(secret).verifyCouponQrToken(ticket));
    }

    @Test
    void previouslyDisplayedJwtSignedCodeRequiresANewIndependentCouponCode() {
        long now = System.currentTimeMillis() / 1000L;
        String oldCode = VerifyDynCodeUtil.issueCoupon(301L, now, "fixture-jwt-material");
        SmsCouponServiceImpl independent = service("fixture-independent-coupon-material");
        assertNull(independent.verifyCouponQrToken(oldCode));
        assertEquals(301L, independent.verifyCouponQrToken(independent.generateCouponQrToken(301L)));
    }

    @Test
    void missingConfigurationDoesNotInstallAStartupFailureHook() throws Exception {
        assertFalse(SmsCouponServiceImpl.class.getMethod("requireCouponQrSecret")
                .isAnnotationPresent(jakarta.annotation.PostConstruct.class));
    }

    @Test
    void missingSecretDisablesBothOperationsWithoutAStartupHook() {
        for (String blank : new String[]{null, "", "   "}) {
            SmsCouponServiceImpl s = service(blank);
            assertThrows(com.chengyinhub.common.exception.ServiceException.class, s::requireCouponQrSecret);
            assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> s.generateCouponQrToken(301L));
            assertEquals(503, assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> s.verifyCouponQrToken("cq1.301.1.x")).getCode());
        }
    }
}
