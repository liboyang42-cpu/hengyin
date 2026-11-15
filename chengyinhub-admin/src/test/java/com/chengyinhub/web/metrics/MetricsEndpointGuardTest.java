package com.chengyinhub.web.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** R9 BE-27: the scraper chain only opens on a separate management port, and envelope codes classify safely. */
class MetricsEndpointGuardTest
{
    private static MockHttpServletRequest at(int port, String path)
    {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.setLocalPort(port);
        return r;
    }

    @Test
    void scraperChainMatchesOnlyTheManagementPort()
    {
        assertTrue(MetricsEndpointGuard.isManagementScrape(at(9091, "/actuator/prometheus"), 8080, 9091));
        assertFalse(MetricsEndpointGuard.isManagementScrape(at(8080, "/actuator/prometheus"), 8080, 9091));
        assertFalse(MetricsEndpointGuard.isManagementScrape(at(9091, "/actuator/health"), 8080, 9091));
        assertFalse(MetricsEndpointGuard.isManagementScrape(at(8080, "/actuator/prometheus"), 8080, 8080),
                "same-port (default) mode never opens the scrape");
        assertFalse(MetricsEndpointGuard.isManagementScrape(at(9091, "/actuator/prometheusx"), 8080, 9091));
    }

    @Test
    void envelopeCodeClasses()
    {
        assertEquals("2xx", ApiEnvelopeMetricsAdvice.codeClass(200));
        assertEquals("4xx", ApiEnvelopeMetricsAdvice.codeClass("409"));
        assertEquals("5xx", ApiEnvelopeMetricsAdvice.codeClass(503));
        assertEquals("other", ApiEnvelopeMetricsAdvice.codeClass(null));
        assertEquals("other", ApiEnvelopeMetricsAdvice.codeClass(601));
    }

    @Test
    void plainErrorReturnsAreMarkedWithoutTouchingCodeOrMessage()
    {
        assertEquals("SYSTEM", ApiEnvelopeMetricsAdvice.inferKind("系统繁忙，请稍后重试"));
        assertEquals("SYSTEM", ApiEnvelopeMetricsAdvice.inferKind("操作失败，请稍后重试"));
        assertEquals("BUSINESS", ApiEnvelopeMetricsAdvice.inferKind("格式错误"));
        assertEquals("BUSINESS", ApiEnvelopeMetricsAdvice.inferKind(null));

        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ApiEnvelopeMetricsAdvice advice = new ApiEnvelopeMetricsAdvice(registry);
        com.chengyinhub.common.core.domain.AjaxResult refused = com.chengyinhub.common.core.domain.AjaxResult.error("格式错误");
        advice.beforeBodyWrite(refused, null, null, null,
                new org.springframework.http.server.ServletServerHttpRequest(new MockHttpServletRequest()), null);
        assertEquals(500, refused.get("code"));
        assertEquals("格式错误", refused.get("msg"));
        assertEquals("BUSINESS", refused.get("errorKind"));

        com.chengyinhub.common.core.domain.AjaxResult ok = com.chengyinhub.common.core.domain.AjaxResult.success();
        advice.beforeBodyWrite(ok, null, null, null, null, null);
        assertFalse(ok.containsKey("errorKind"), "successes are left alone");
        assertEquals(2.0, registry.find(ApiEnvelopeMetricsAdvice.METRIC).counters().stream()
                .mapToDouble(c -> c.count()).sum());
    }
}
