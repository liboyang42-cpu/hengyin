package com.chengyinhub.business.moderation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SEC-12: Tencent Cloud TMS/IMS provider, without network. */
class TencentCloudModerationProviderTest {

    static class Fake extends TencentCloudModerationProvider {
        final List<String> actions = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        final List<String> auths = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        boolean fail;

        Fake(String id, String key) { super(true, id, key, "ap-guangzhou", "", ""); }

        @Override protected String send(String host, String action, String payload, long timestamp, String authorization) throws Exception {
            if (fail) throw new java.io.IOException("down");
            actions.add(host + "/" + action);
            payloads.add(payload);
            auths.add(authorization);
            return answers.isEmpty() ? "{\"Response\":{\"Suggestion\":\"Pass\"}}" : answers.remove(0);
        }
    }

    static String answer(String suggestion) {
        return "{\"Response\":{\"Suggestion\":\"" + suggestion + "\",\"RequestId\":\"r\"}}";
    }

    @Test void unconfiguredProviderCannotJudgeAndNeverCallsOut() {
        Fake fake = new Fake("", "");
        assertFalse(fake.configured());
        assertFalse(fake.canJudge(null));
        assertEquals(ModerationVerdict.UNAVAILABLE, fake.checkText("hello", null));
        assertEquals(ModerationVerdict.UNAVAILABLE, fake.checkImage("https://cdn.example/a.jpg", null));
        assertTrue(fake.actions.isEmpty());
        assertFalse(new TencentCloudModerationProvider(false, "id", "key", "", "", "").configured(), "kill switch");
    }

    @Test void suggestionsMapToVerdictsAndErrorsAreNeverAPass() throws Exception {
        assertEquals(ModerationVerdict.PASS, TencentCloudModerationProvider.parse(answer("Pass")));
        assertEquals(ModerationVerdict.RISK, TencentCloudModerationProvider.parse(answer("Block")));
        assertEquals(ModerationVerdict.REVIEW, TencentCloudModerationProvider.parse(answer("Review")));
        assertEquals(ModerationVerdict.UNAVAILABLE, TencentCloudModerationProvider.parse(answer("")));
        assertEquals(ModerationVerdict.UNAVAILABLE, TencentCloudModerationProvider.parse(
                "{\"Response\":{\"Error\":{\"Code\":\"AuthFailure.SignatureFailure\"},\"Suggestion\":\"Pass\"}}"));
        assertEquals(ModerationVerdict.UNAVAILABLE, TencentCloudModerationProvider.parse(null));
    }

    @Test void textIsBase64ChunkedAndAnyBlockedChunkBlocks() {
        Fake fake = new Fake("AKIDtest", "secret");
        fake.answers.add(answer("Pass"));
        fake.answers.add(answer("Block"));
        String text = "a".repeat(TencentCloudModerationProvider.TEXT_CHUNK) + "违规内容";
        assertEquals(ModerationVerdict.RISK, fake.checkText(text, null));
        assertEquals(2, fake.actions.size());
        assertEquals("tms.tencentcloudapi.com/TextModeration", fake.actions.get(0));
        String second = fake.payloads.get(1);
        String content = second.replaceAll(".*\"Content\":\"([^\"]+)\".*", "$1");
        assertEquals("违规内容", new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8));
    }

    @Test void reviewAndOutagesAreNotPasses() {
        Fake fake = new Fake("AKIDtest", "secret");
        fake.answers.add(answer("Review"));
        assertEquals(ModerationVerdict.REVIEW, fake.checkText("hello", null));
        fake.fail = true;
        assertEquals(ModerationVerdict.UNAVAILABLE, fake.checkText("hello", null));
        assertEquals(ModerationVerdict.UNAVAILABLE, fake.checkImage("https://cdn.example/a.jpg", null));
    }

    @Test void imagesNeedAPublicHttpAddress() {
        Fake fake = new Fake("AKIDtest", "secret");
        assertEquals(ModerationVerdict.UNAVAILABLE, fake.checkImage("private/object/key.jpg", null));
        assertTrue(fake.actions.isEmpty());
        fake.answers.add(answer("Block"));
        assertEquals(ModerationVerdict.RISK, fake.checkImage("https://cdn.example/a.jpg", null));
        assertEquals("ims.tencentcloudapi.com/ImageModeration", fake.actions.get(0));
        assertTrue(fake.payloads.get(0).contains("\"FileUrl\":\"https://cdn.example/a.jpg\""));
    }

    @Test void requestsAreSignedWithTc3AndNeverCarryTheSecretKey() throws Exception {
        Fake fake = new Fake("AKIDtest", "very-secret-key");
        String a = fake.authorization("tms.tencentcloudapi.com", "tms", "TextModeration", "{}", 1_700_000_000L);
        String b = fake.authorization("tms.tencentcloudapi.com", "tms", "TextModeration", "{}", 1_700_000_000L);
        String c = fake.authorization("tms.tencentcloudapi.com", "tms", "TextModeration", "{\"x\":1}", 1_700_000_000L);
        assertEquals(a, b, "deterministic for the same request");
        assertNotEquals(a, c, "payload is covered by the signature");
        assertTrue(a.startsWith("TC3-HMAC-SHA256 Credential=AKIDtest/2023-11-14/tms/tc3_request, "
                + "SignedHeaders=content-type;host;x-tc-action, Signature="));
        assertTrue(a.matches(".*Signature=[0-9a-f]{64}$"));
        assertFalse(a.contains("very-secret-key"));
    }
}
