package com.chengyinhub.framework.web.trace;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {
    @Test
    void safeIncomingIdIsReusedExposedAndClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/topic/list");
        request.addHeader(RequestIdFilter.HEADER, "mini_1234abcd");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        new RequestIdFilter().doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
        assertEquals("mini_1234abcd", seen.get());
        assertEquals("mini_1234abcd", response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void unsafeOrMissingIdIsReplacedToPreventLogInjection() {
        for (String incoming : new String[]{null, "", "short", "abc\nFAKE LOG LINE 123", "x".repeat(65)}) {
            String id = RequestIdFilter.resolve(incoming);
            assertTrue(id.matches("[0-9a-f]{32}"), String.valueOf(incoming));
        }
    }
}
