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
}
