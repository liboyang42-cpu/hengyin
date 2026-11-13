package com.chengyinhub.business.service.channel.platform;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 出站投递器：取件 CAS、HMAC 签名、成功/退避重试/死信/取消，以及非公网地址拦截。 */
class ChannelWebhookDispatcherTest
{
    static final PiiCryptoService CRYPTO = new PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]), "");
    static final String SECRET = "whsec_test-secret";
    ChannelWebhookMapper hooks;
    ChannelWebhookDispatcher dispatcher;
    Map<String, Object> delivery;
    Map<String, Object> sub;
    final List<Map<String, Object>> finished = new ArrayList<>();
    final List<Object[]> sent = new ArrayList<>();
    ChannelWebhookDispatcher.Response next = new ChannelWebhookDispatcher.Response(200, "ok");
    IOException failure;

    @BeforeEach void setUp()
    {
        hooks = mock(ChannelWebhookMapper.class);
        dispatcher = new ChannelWebhookDispatcher();
        ReflectionTestUtils.setField(dispatcher, "hooks", hooks);
        ReflectionTestUtils.setField(dispatcher, "crypto", CRYPTO);
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        ReflectionTestUtils.setField(dispatcher, "backoffBaseMs", 1000L);
        ReflectionTestUtils.setField(dispatcher, "resolver", (ChannelWebhookDispatcher.Resolver) host -> new InetAddress[] { InetAddress.getByName("93.184.216.34") });
        ReflectionTestUtils.setField(dispatcher, "transport", (ChannelWebhookDispatcher.Transport) (uri, headers, body) -> {
            sent.add(new Object[] { uri, headers, body });
            if (failure != null) throw failure;
            return next;
        });
        delivery = new HashMap<>(Map.of("id", 500L, "subscriptionId", 1L, "eventId", "msg-9", "eventType", "message.created",
                "payloadJson", "{\"event_type\":\"message.created\",\"data\":{\"message_id\":\"9\"}}", "createTime", new Timestamp(1_700_000_000_000L)));
        sub = new HashMap<>(Map.of("id", 1L, "url", "https://hooks.example.com/club", "status", 1, "deleted", 0, "keyVersion", 2,
                "secretCipher", CRYPTO.encrypt(SECRET)));
        when(hooks.selectDelivery(500L)).thenAnswer(i -> new HashMap<>(delivery));
        when(hooks.selectSubscriptionForDispatch(1L)).thenAnswer(i -> new HashMap<>(sub));
        when(hooks.claimDelivery(anyLong(), anyString(), anyInt(), anyLong())).thenReturn(1);
        when(hooks.finishDelivery(anyMap())).thenAnswer(i -> { finished.add(new HashMap<>(i.<Map<String, Object>>getArgument(0))); return 1; });
    }

    void due(String state, int attempt)
    {
        when(hooks.selectDueDeliveries(anyInt())).thenReturn(List.of(new HashMap<>(Map.of("id", 500L, "subscriptionId", 1L, "state", state, "attempt", attempt))));
    }

    Map<String, Object> run(String state, int attempt)
    {
        due(state, attempt);
        finished.clear();
        assertEquals(1, dispatcher.runOnce());
        assertEquals(1, finished.size());
        return finished.get(0);
    }

    @SuppressWarnings("unchecked")
    @Test void sendsASignedBodyAndMarksItSent() throws Exception
    {
        Map<String, Object> f = run("PENDING", 0);
        assertEquals("SENT", f.get("state"));
        assertEquals(1, f.get("attempt"));
        assertEquals(200, f.get("httpStatus"));
        assertNull(f.get("nextRetryMs"));
        verify(hooks).claimDelivery(500L, "PENDING", 0, 60_000L);
        verify(hooks).recordSubscriptionOutcome(1L, true, null);
        Object[] call = sent.get(0);
        assertEquals(URI.create("https://hooks.example.com/club"), call[0]);
        Map<String, String> h = (Map<String, String>) call[1];
        byte[] body = (byte[]) call[2];
        long ts = Long.parseLong(h.get("X-Chengyin-Timestamp"));
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - ts) < 5);
        String nonce = h.get("X-Chengyin-Nonce");
        assertTrue(nonce.matches("[0-9a-f]{32}"));
        assertEquals("v1=" + ChannelWebhookDispatcher.sign(SECRET, ts, nonce, body), h.get("X-Chengyin-Signature"));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String independent = java.util.HexFormat.of().formatHex(mac.doFinal((ts + "." + nonce + "." + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
        assertEquals("v1=" + independent, h.get("X-Chengyin-Signature"), "receivers can verify with a plain HMAC over ts.nonce.body");
        assertEquals("message.created", h.get("X-Chengyin-Event"));
        assertEquals("500", h.get("X-Chengyin-Delivery"));
        assertEquals("2", h.get("X-Chengyin-Key-Version"));
        JsonNode json = new ObjectMapper().readTree(body);
        assertEquals("500", json.get("delivery_id").asText());
        assertEquals("msg-9", json.get("event_id").asText());
        assertEquals(1, json.get("attempt").asInt());
        assertEquals("9", json.get("payload").get("data").get("message_id").asText());
        assertFalse(new String(body, StandardCharsets.UTF_8).contains(SECRET));
    }

    @Test void retriesWithExponentialBackoffThenDeadLetters()
    {
        next = new ChannelWebhookDispatcher.Response(503, "busy");
        Map<String, Object> f1 = run("PENDING", 0);
        assertEquals("FAILED", f1.get("state"));
        assertEquals("HTTP_503", f1.get("reason"));
        long first = (Long) f1.get("nextRetryMs");
        assertTrue(first >= 800 && first <= 1200, "base 1s ±20%: " + first);
        Map<String, Object> f3 = run("FAILED", 2);
        assertEquals(3, f3.get("attempt"));
        long third = (Long) f3.get("nextRetryMs");
        assertTrue(third >= 3200 && third <= 4800, "1s·2^2 ±20%: " + third);
        Map<String, Object> f5 = run("FAILED", 4);
        assertEquals("DEAD", f5.get("state"), "the fifth failed attempt goes to the dead-letter state");
        assertEquals("MAX_ATTEMPTS:HTTP_503", f5.get("reason"));
        assertNull(f5.get("nextRetryMs"));
        verify(hooks, times(3)).recordSubscriptionOutcome(eq(1L), eq(false), anyString());
        failure = new IOException("reset");
        assertEquals("NETWORK_ERROR", run("PENDING", 0).get("reason"));
        failure = new java.net.http.HttpTimeoutException("slow");
        assertEquals("TIMEOUT", run("PENDING", 0).get("reason"));
        ReflectionTestUtils.setField(dispatcher, "backoffMaxMs", 1500L);
        failure = null;
        long capped = (Long) run("FAILED", 3).get("nextRetryMs");
        assertTrue(capped <= 1800, "capped at max ±20%: " + capped);
    }

    @Test void clientErrorsAndBadTargetsAreDeadAtOnce()
    {
        next = new ChannelWebhookDispatcher.Response(401, "bad sig");
        Map<String, Object> f = run("PENDING", 0);
        assertEquals("DEAD", f.get("state"));
        assertEquals("HTTP_401", f.get("reason"));
        assertEquals("bad sig", f.get("responseSnippet"));
        next = new ChannelWebhookDispatcher.Response(429, "");
        assertEquals("FAILED", run("PENDING", 0).get("state"), "429 is retried");

        ReflectionTestUtils.setField(dispatcher, "resolver", (ChannelWebhookDispatcher.Resolver) host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5") });
        int calls = sent.size();
        Map<String, Object> blocked = run("PENDING", 0);
        assertEquals("DEAD", blocked.get("state"));
        assertEquals("URL_BLOCKED", blocked.get("reason"), "a name that resolves to a private address is never called");
        assertEquals(calls, sent.size());

        sub.put("secretCipher", null);
        assertEquals("SECRET_UNRECOVERABLE", run("PENDING", 0).get("reason"), "pre-dispatch subscriptions must rotate their secret");
        sub.put("status", 0);
        Map<String, Object> cancelled = run("PENDING", 0);
        assertEquals("CANCELLED", cancelled.get("state"));
        assertEquals("SUBSCRIPTION_INACTIVE", cancelled.get("reason"));
    }

    @Test void claimAndFinishAreCompareAndSet()
    {
        due("INFLIGHT", 1);
        when(hooks.claimDelivery(500L, "INFLIGHT", 1, 60_000L)).thenReturn(0);
        assertEquals(0, dispatcher.runOnce(), "another instance took it");
        assertTrue(sent.isEmpty());
        when(hooks.claimDelivery(500L, "INFLIGHT", 1, 60_000L)).thenReturn(1);
        when(hooks.finishDelivery(anyMap())).thenReturn(0);
        assertEquals(0, dispatcher.runOnce(), "a lease that expired mid-flight does not overwrite the newer attempt");
        verify(hooks, never()).recordSubscriptionOutcome(anyLong(), anyBoolean(), any());
    }

    @Test void offWithoutTheSwitchOrTheCryptoKey()
    {
        due("PENDING", 0);
        ReflectionTestUtils.setField(dispatcher, "enabled", false);
        dispatcher.tick();
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        ReflectionTestUtils.setField(dispatcher, "crypto", new PiiCryptoService("", ""));
        dispatcher.tick();
        verify(hooks, never()).claimDelivery(anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test void publicAddressRules() throws Exception
    {
        assertTrue(ChannelWebhookDispatcher.publicAddress(InetAddress.getByName("93.184.216.34")));
        for (String ip : new String[] { "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "224.0.0.1", "::1", "fe80::1", "fd00::1" })
            assertFalse(ChannelWebhookDispatcher.publicAddress(InetAddress.getByName(ip)), ip);
        assertEquals("a?b\nc", ChannelWebhookDispatcher.snippet("a\u0000b\nc"));
    }
}
