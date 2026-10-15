package com.chengyinhub.common.utils.ip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedProxyIpResolverTest
{
    @Test
    void directExternalClientCannotSpoofForwardingHeaders()
    {
        assertEquals("198.51.100.20",
                TrustedProxyIpResolver.resolve("198.51.100.20", "203.0.113.7"));
    }

    @Test
    void localNginxMaySupplyItsOverwrittenRealIpHeader()
    {
        assertEquals("203.0.113.7",
                TrustedProxyIpResolver.resolve("127.0.0.1", "203.0.113.7"));
    }

    @Test
    void malformedProxyHeaderFallsBackToImmediatePeer()
    {
        assertEquals("127.0.0.1",
                TrustedProxyIpResolver.resolve("127.0.0.1", "203.0.113.7, 8.8.8.8"));
    }

    /** F-L5S-08：IpUtils（登录黑名单、IP 限流、合规留痕）不得再采信客户端可控的转发头。 */
    @Test
    void ipUtilsIgnoresClientControlledForwardingHeaders()
    {
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.20");
        direct.addHeader("X-Forwarded-For", "203.0.113.7");
        direct.addHeader("Proxy-Client-IP", "203.0.113.8");
        direct.addHeader("WL-Proxy-Client-IP", "203.0.113.9");
        direct.addHeader("X-Real-IP", "203.0.113.10");
        assertEquals("198.51.100.20", IpUtils.getIpAddr(direct));

        MockHttpServletRequest viaLocalNginx = new MockHttpServletRequest();
        viaLocalNginx.setRemoteAddr("127.0.0.1");
        viaLocalNginx.addHeader("X-Forwarded-For", "203.0.113.7");
        viaLocalNginx.addHeader("X-Real-IP", "198.51.100.30");
        assertEquals("198.51.100.30", IpUtils.getIpAddr(viaLocalNginx));
    }
}
