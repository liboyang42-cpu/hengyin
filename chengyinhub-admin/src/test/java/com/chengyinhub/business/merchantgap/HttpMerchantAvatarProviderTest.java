package com.chengyinhub.business.merchantgap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The configurable production avatar provider: config, request and the result mapping. */
class HttpMerchantAvatarProviderTest {
    final List<String> sent = new ArrayList<>();

    HttpMerchantAvatarProvider provider(String baseUrl, String key, int code, String body) {
        return new HttpMerchantAvatarProvider(baseUrl, key, 15000, "cartoon, REALISTIC,bad style", (URI endpoint, String apiKey, String json, java.time.Duration t) -> {
            sent.add(endpoint + " " + apiKey + " " + json);
            return new Object[] {code, body};
        });
    }

    @Test
    void succeededJobKeepsOnlyHttpsResultUrls() {
        HttpMerchantAvatarProvider p = provider("https://avatar.example.test/v1/jobs", "k", 200,
                "{\"status\":\"SUCCEEDED\",\"modelUrl\":\"https://cdn.example.test/m.glb\",\"thumbUrl\":\"https://cdn.example.test/t.png\"}");
        assertEquals(List.of("CARTOON", "REALISTIC"), p.styles());
        MerchantAvatarProvider.Result r = p.generate(8001L, "https://img.example.test/a.png", "CARTOON");
        assertEquals("SUCCEEDED", r.status);
        assertEquals("https://cdn.example.test/m.glb", r.modelUrl);
        assertNull(r.failReason);
        assertTrue(sent.get(0).contains("\"merchantId\":8001"));
        assertTrue(sent.get(0).startsWith("https://avatar.example.test/v1/jobs k "));
    }

    @Test
    void missingConfigTransportErrorsAndMalformedAnswersAreFailedJobs() {
        assertEquals("PROVIDER_NOT_CONFIGURED", provider("http://plain.example.test", "k", 200, "{}").generate(1, "https://i", "CARTOON").failReason);
        assertEquals("PROVIDER_NOT_CONFIGURED", provider("https://a.example.test", "", 200, "{}").generate(1, "https://i", "CARTOON").failReason);
        assertTrue(sent.isEmpty());
        assertEquals("PROVIDER_HTTP_502", provider("https://a.example.test", "k", 502, "").generate(1, "https://i", "CARTOON").failReason);
        assertEquals("PROVIDER_MALFORMED", provider("https://a.example.test", "k", 200, "{\"status\":\"SUCCEEDED\",\"modelUrl\":\"http://x\",\"thumbUrl\":\"https://t\"}")
                .generate(1, "https://i", "CARTOON").failReason);
        assertEquals("NSFW_REJECTED", provider("https://a.example.test", "k", 200, "{\"status\":\"FAILED\",\"failReason\":\"NSFW_REJECTED\"}")
                .generate(1, "https://i", "CARTOON").failReason);
        assertEquals("PENDING", provider("https://a.example.test", "k", 200, "{\"status\":\"PENDING\"}").generate(1, "https://i", "CARTOON").status);
        HttpMerchantAvatarProvider broken = new HttpMerchantAvatarProvider("https://a.example.test", "k", 15000, "CARTOON",
                (e, k, b, t) -> { throw new java.io.IOException("down"); });
        assertEquals("PROVIDER_UNAVAILABLE", broken.generate(1, "https://i", "CARTOON").failReason);
    }
}
