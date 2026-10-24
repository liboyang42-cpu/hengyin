package com.chengyinhub.framework.web.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {
    @AfterEach void clear() { MDC.clear(); }
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
        for (String incoming : new String[]{null, "", "short", "abc\nFAKE LOG LINE 123", "abc\rFAKE123",
                "abc\u2028FAKE123", "private.header.fixture", "x".repeat(65)}) {
            String id = RequestIdFilter.resolve(incoming);
            assertTrue(id.matches("[0-9a-f]{32}"), String.valueOf(incoming));
        }
    }

    @Test void throwingRequestIsIsolatedAndRestoresPreviousWorkerContext() throws Exception {
        MDC.put("requestId", "previous_123"); MDC.put("commandId", "previous-command");
        MDC.put("Authorization", "private-header-fixture");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fixture");
        request.addHeader(RequestIdFilter.HEADER, "current_1234");
        var failure = new IOException("private-body-fixture");
        assertSame(failure, assertThrows(IOException.class, () -> new RequestIdFilter().doFilter(
                request, new MockHttpServletResponse(), (req, res) -> {
                    assertEquals(Map.of("requestId", "current_1234"), MDC.getCopyOfContextMap());
                    throw failure;
                })));
        assertEquals(previous, MDC.getCopyOfContextMap());
    }

    @Test void responseHeaderFailureAlsoRestoresContext() {
        MDC.put("requestId", "previous_123");
        var response = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("header failure")).when(response).setHeader(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThrows(IllegalStateException.class, () -> new RequestIdFilter().doFilter(
                new MockHttpServletRequest("GET", "/fixture"), response, (req, res) -> fail("must not run")));
        assertEquals("previous_123", MDC.get("requestId"));
    }
}
