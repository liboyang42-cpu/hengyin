package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TencentMapClientTest {

    @Test
    void doesNotCallProviderWhenMapIsDisabled() {
        RecordingTencentMapClient client = new RecordingTencentMapClient();
        ReflectionTestUtils.setField(client, "enabled", false);
        ReflectionTestUtils.setField(client, "mapKey", "server-only-key");

        assertNull(client.get("https://example.test/geocode?location=30,120"));
        assertEquals(0, client.requestCount);
    }

    @Test
    void appendsServerSideKeyAtTheClientBoundary() {
        RecordingTencentMapClient client = new RecordingTencentMapClient();
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "mapKey", "server-only-key");

        assertEquals("ok", client.get("https://example.test/geocode?location=30,120"));
        assertEquals("https://example.test/geocode?location=30,120&key=server-only-key", client.lastUrl);
    }

    private static final class RecordingTencentMapClient extends TencentMapClient {
        private int requestCount;
        private String lastUrl;

        @Override
        protected String request(String url) {
            requestCount++;
            lastUrl = url;
            return "ok";
        }
    }
}
