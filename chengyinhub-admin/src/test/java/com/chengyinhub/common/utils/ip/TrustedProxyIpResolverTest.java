package com.chengyinhub.common.utils.ip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
