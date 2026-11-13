package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AppOriginalEnvelopeTest {
    static final String RID = "eeeeeeee-1111-4111-8111-111111111111";

    static Map<String, Object> submit(String protocol, String json, int chunk) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < b64.length(); i += chunk) chunks.add(b64.substring(i, Math.min(b64.length(), i + chunk)));
        Map<String, Object> m = new HashMap<>();
        m.put("protocolVersion", protocol); m.put("requestId", RID);
        m.put("payloadSHA256", AppOriginalRequestLedger.sha256Hex(bytes)); m.put("payloadBase64Chunks", chunks);
        return m;
    }

    @Test void activityEnvelopeVerifiesDigestOverRawBytesAndJoinsChunks() {
        Map<String, Object> body = submit(AppOriginalEnvelope.ACTIVITY, "{\"name\":\"夜行\",\"clubId\":7}", 8);
        var original = AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, body);
        assertEquals("夜行", original.payload().get("name"));
        body.put("payloadSHA256", "0".repeat(64));
        assertEquals(400, assertThrows(ServiceException.class, () -> AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, body)).getCode());
    }

    @Test void activityRejectsScopeIdOverridesAndMerchantBranch() {
        assertThrows(ServiceException.class, () -> AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, submit(AppOriginalEnvelope.ACTIVITY, "{\"scope\":\"MERCHANT\"}", 100)));
        Map<String, Object> body = submit(AppOriginalEnvelope.ACTIVITY, "{\"name\":\"x\"}", 100);
        body.put("merchantId", 3);
        assertThrows(ServiceException.class, () -> AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, body));
        body.put("merchantId", null);
        assertNotNull(AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.ACTIVITY, body));
    }

    @Test void templateOnlyPersonalDraftOrPublish() {
        assertNotNull(AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.TEMPLATE,
                submit(AppOriginalEnvelope.TEMPLATE, "{\"fields\":{\"title\":\"t\"},\"originalPath\":\"api/template/draft\"}", 100)));
        assertThrows(ServiceException.class, () -> AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.TEMPLATE,
                submit(AppOriginalEnvelope.TEMPLATE, "{\"fields\":{},\"originalPath\":\"api/template/adopt\"}", 100)));
        Map<String, Object> wrongProtocol = submit(AppOriginalEnvelope.ACTIVITY, "{\"fields\":{},\"originalPath\":\"api/template/draft\"}", 100);
        assertThrows(ServiceException.class, () -> AppOriginalEnvelope.parseSubmit(AppOriginalEnvelope.TEMPLATE, wrongProtocol));
    }

    @Test void receiptProjectionMatchesAppStates() {
        String sha = "a".repeat(64);
        Map<String, Object> committed = AppOriginalEnvelope.receipt(AppOriginalEnvelope.TEMPLATE, RID, sha,
                new AppOriginalRequestLedger.Fact("COMMITTED", RID, "", sha, Map.of("memberTemplateId", 12L), null, null, false));
        assertEquals("COMMITTED", committed.get("state")); assertEquals(Map.of("memberTemplateId", 12L), committed.get("originalResult"));
        assertTrue(committed.containsKey("merchant")); assertNull(committed.get("merchant")); assertNull(committed.get("processing"));
        Map<String, Object> missing = AppOriginalEnvelope.receipt(AppOriginalEnvelope.ACTIVITY, RID, sha,
                new AppOriginalRequestLedger.Fact("NOT_FOUND", RID, "", sha, null, null, null, false));
        assertEquals("NOT_FOUND", missing.get("state")); assertNull(missing.get("originalResult")); assertTrue(missing.containsKey("actorId"));
    }
}
