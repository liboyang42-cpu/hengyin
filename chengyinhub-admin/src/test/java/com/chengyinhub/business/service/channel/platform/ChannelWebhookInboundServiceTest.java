package com.chengyinhub.business.service.channel.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.ISensitiveContentLibrary;
import com.chengyinhub.business.service.IWxContentSecurityService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 入站接收：时间窗、签名、nonce 防重放、IP 白名单、限流、审核三态，以及每次判定落的日志形状（App inboundLogs 规则）。 */
class ChannelWebhookInboundServiceTest
{
    static final PiiCryptoService CRYPTO = new PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]), "");
    static final String SECRET = "whin_inbound-secret";
    static final long EP = 4, CHANNEL = 100, CONVERSATION = 100;
    ChannelWebhookMapper hooks;
    IWxContentSecurityService security;
    ChannelWebhookInboundService service;
    Map<String, Object> ep;
    final List<Map<String, Object>> logs = new ArrayList<>();
    final List<Map<String, Object>> messages = new ArrayList<>();
    final Set<String> nonces = new HashSet<>();
    int recent;

    @BeforeEach void setUp()
    {
        hooks = mock(ChannelWebhookMapper.class);
        security = mock(IWxContentSecurityService.class);
        service = new ChannelWebhookInboundService();
        ReflectionTestUtils.setField(service, "hooks", hooks);
        ReflectionTestUtils.setField(service, "crypto", CRYPTO);
        ReflectionTestUtils.setField(service, "security", security);
        ep = new HashMap<>(Map.of("id", EP, "ownerId", 10L, "channelId", CHANNEL, "name", "CI 机器人", "status", 1, "deleted", 0,
                "rateLimitPerMin", 30, "allowedIpsJson", "[]", "secretCipher", CRYPTO.encrypt(SECRET)));
        when(hooks.selectEndpointForIngest(EP)).thenAnswer(i -> new HashMap<>(ep));
        when(hooks.countRecentInbound(eq(EP), eq(60))).thenAnswer(i -> recent);
        when(hooks.insertNonce(eq(EP), anyString(), anyLong())).thenAnswer(i -> nonces.add(i.getArgument(1)) ? 1 : 0);
        when(hooks.insertInboundLog(anyMap())).thenAnswer(i -> {
            Map<String, Object> row = i.getArgument(0);
            row.put("id", 900L + logs.size());
            logs.add(new HashMap<>(row));
            return 1;
        });
        when(hooks.insertWebhookMessage(anyMap())).thenAnswer(i -> {
            Map<String, Object> row = i.getArgument(0);
            row.put("id", 70L + messages.size());
            messages.add(new HashMap<>(row));
            return 1;
        });
        Map<String, Object> bridge = new HashMap<>();
        bridge.put("channelId", CHANNEL); bridge.put("channelStatus", 0); bridge.put("conversationId", CONVERSATION); bridge.put("conversationRow", CONVERSATION);
        when(hooks.selectChannelConversation(CHANNEL)).thenReturn(bridge);
    }

    static byte[] body(String content) { return ("{\"content\":\"" + content + "\"}").getBytes(StandardCharsets.UTF_8); }

    ChannelWebhookInboundService.Result send(byte[] body, long ts, String nonce, String secret, String ip)
    {
        return service.ingest(EP, String.valueOf(ts), nonce, "v1=" + ChannelWebhookDispatcher.sign(secret, ts, nonce, body), body, ip);
    }

    ChannelWebhookInboundService.Result send(byte[] body, String nonce) { return send(body, now(), nonce, SECRET, "203.0.113.9"); }

    static long now() { return System.currentTimeMillis() / 1000; }

    Map<String, Object> lastLog() { return logs.get(logs.size() - 1); }

    void assertPreInsertLog(String result, String reason)
    {
        Map<String, Object> log = lastLog();
        assertEquals(result, log.get("result"));
        assertEquals(reason, log.get("reason"));
        assertNull(log.get("messageId"));
        assertNull(log.get("senderType"));
        assertNull(log.get("webhookId"));
        assertNull(log.get("originalMessageStored"));
    }

    @Test void signedRequestIsAcceptedAsAVisibleWebhookMessage()
    {
        ChannelWebhookInboundService.Result r = send(body("部署完成"), "nonce-aaaaaaaaaaaa01");
        assertEquals(200, r.status());
        assertNull(r.errorCode());
        assertEquals("ACCEPTED", r.data().get("result"));
        assertEquals("70", r.data().get("message_id"));
        Map<String, Object> m = messages.get(0);
        assertEquals(CONVERSATION, m.get("conversationId"));
        assertEquals("部署完成", m.get("content"));
        assertEquals(1, m.get("checkStatus"));
        assertEquals(Boolean.TRUE, m.get("visible"));
        assertTrue(String.valueOf(m.get("extraJson")).contains("\"sender_type\":4"));
        assertTrue(String.valueOf(m.get("extraJson")).contains("\"webhook_id\":\"4\""));
        verify(hooks).touchWebhookConversation(CONVERSATION, "部署完成");
        verify(hooks).fanOutWebhookUnread(CONVERSATION);
        Map<String, Object> log = lastLog();
        assertEquals("ACCEPTED", log.get("result"));
        assertEquals(70L, log.get("messageId"));
        assertEquals(4, log.get("senderType"));
        assertEquals(EP, log.get("webhookId"));
        assertEquals(1, log.get("originalMessageStored"));
        assertEquals(ChannelWebhookInboundService.sha256Hex(body("部署完成")), log.get("requestHash"));
        verify(security).checkText("部署完成", null);
    }

    @Test void replayedNonceIsRejectedWithoutASecondMessage()
    {
        byte[] b = body("hi");
        long ts = now();
        assertEquals(200, send(b, ts, "nonce-aaaaaaaaaaaa02", SECRET, "203.0.113.9").status());
        ChannelWebhookInboundService.Result again = send(b, ts, "nonce-aaaaaaaaaaaa02", SECRET, "203.0.113.9");
        assertEquals(409, again.status());
        assertEquals("WEBHOOK_REPLAYED", again.errorCode());
        assertPreInsertLog("REJECTED", "REPLAYED");
        assertEquals(1, messages.size());
    }

    @Test void timestampWindowAndSignatureAreChecked()
    {
        byte[] b = body("hi");
        assertEquals("WEBHOOK_TIMESTAMP_OUT_OF_WINDOW", send(b, now() - 301, "nonce-aaaaaaaaaaaa03", SECRET, "1.1.1.1").errorCode());
        assertPreInsertLog("REJECTED", "TIMESTAMP_OUT_OF_WINDOW");
        assertEquals("WEBHOOK_TIMESTAMP_OUT_OF_WINDOW", send(b, now() + 301, "nonce-aaaaaaaaaaaa03", SECRET, "1.1.1.1").errorCode());
        assertEquals(200, send(b, now() - 290, "nonce-aaaaaaaaaaaa03", SECRET, "1.1.1.1").status(), "inside the window");
        ChannelWebhookInboundService.Result wrong = send(b, now(), "nonce-aaaaaaaaaaaa04", "whin_other", "1.1.1.1");
        assertEquals(401, wrong.status());
        assertEquals("WEBHOOK_SIGNATURE_INVALID", wrong.errorCode());
        assertPreInsertLog("REJECTED", "SIGNATURE_INVALID");
        assertFalse(nonces.contains("nonce-aaaaaaaaaaaa04"), "a forged request does not burn the nonce");
        long ts = now();
        String sig = "v1=" + ChannelWebhookDispatcher.sign(SECRET, ts, "nonce-aaaaaaaaaaaa05", b);
        assertEquals("WEBHOOK_SIGNATURE_INVALID", service.ingest(EP, String.valueOf(ts), "nonce-aaaaaaaaaaaa05", sig, body("tampered"), "1.1.1.1").errorCode(),
                "the body is covered by the signature");
        assertEquals("WEBHOOK_NONCE_INVALID", send(b, "short").errorCode());
        assertEquals("WEBHOOK_TIMESTAMP_OUT_OF_WINDOW", service.ingest(EP, "abc", "nonce-aaaaaaaaaaaa06", sig, b, "1.1.1.1").errorCode());
        assertEquals(1, messages.size());
    }

    @Test void ipAllowlistRateLimitAndPauseComeFirst()
    {
        ep.put("allowedIpsJson", "[\"203.0.113.0/24\"]");
        assertEquals("WEBHOOK_IP_NOT_ALLOWED", send(body("x"), now(), "nonce-aaaaaaaaaaaa07", SECRET, "198.51.100.1").errorCode());
        assertPreInsertLog("BLOCKED", "IP_NOT_ALLOWED");
        assertEquals(200, send(body("x"), now(), "nonce-aaaaaaaaaaaa08", SECRET, "203.0.113.200").status());
        recent = 30;
        ChannelWebhookInboundService.Result limited = send(body("x"), "nonce-aaaaaaaaaaaa09");
        assertEquals(429, limited.status());
        assertPreInsertLog("RATE_LIMITED", "RATE_LIMITED");
        recent = 0;
        ep.put("status", 0);
        assertEquals("WEBHOOK_ENDPOINT_PAUSED", send(body("x"), "nonce-aaaaaaaaaaaa10").errorCode());
        ep.put("deleted", 1);
        int before = logs.size();
        assertEquals(404, send(body("x"), "nonce-aaaaaaaaaaaa11").status());
        assertEquals(before, logs.size(), "unknown endpoints leave no log");
    }

    @Test void moderationDecidesBetweenAcceptedPendingAndBlocked()
    {
        when(security.checkText(eq("违规"), isNull())).thenReturn(ISensitiveContentLibrary.VIOLATION_MESSAGE);
        ChannelWebhookInboundService.Result blocked = send(body("违规"), "nonce-aaaaaaaaaaaa12");
        assertEquals(403, blocked.status());
        assertPreInsertLog("BLOCKED", "CONTENT_BLOCKED");
        assertTrue(messages.isEmpty());

        when(security.checkText(eq("待审"), isNull())).thenReturn(IWxContentSecurityService.MSG_UNAVAILABLE);
        ChannelWebhookInboundService.Result pending = send(body("待审"), "nonce-aaaaaaaaaaaa13");
        assertEquals(202, pending.status());
        assertEquals("PENDING_REVIEW", pending.data().get("result"));
        Map<String, Object> m = messages.get(0);
        assertEquals(0, m.get("checkStatus"));
        assertEquals(Boolean.FALSE, m.get("visible"), "stored but not visible until reviewed");
        Map<String, Object> log = lastLog();
        assertEquals("PENDING_REVIEW", log.get("result"));
        assertEquals(1, log.get("originalMessageStored"));
        assertEquals(4, log.get("senderType"));
        assertNotNull(log.get("messageId"));
        verify(hooks, never()).fanOutWebhookUnread(anyLong());

        when(security.checkText(eq("异常"), isNull())).thenThrow(new IllegalStateException("down"));
        assertEquals(202, send(body("异常"), "nonce-aaaaaaaaaaaa14").status(), "a moderation outage holds the message for review");
    }

    @Test void bodyAndChannelMustBeUsable()
    {
        assertEquals("WEBHOOK_BODY_INVALID", send("{\"content\":\"x\",\"extra\":1}".getBytes(StandardCharsets.UTF_8), "nonce-aaaaaaaaaaaa15").errorCode());
        assertEquals("WEBHOOK_BODY_INVALID", send("{\"content\":\"   \"}".getBytes(StandardCharsets.UTF_8), "nonce-aaaaaaaaaaaa16").errorCode());
        assertEquals("WEBHOOK_BODY_INVALID", send("not json".getBytes(StandardCharsets.UTF_8), "nonce-aaaaaaaaaaaa17").errorCode());
        when(hooks.selectChannelConversation(CHANNEL)).thenReturn(new HashMap<>(Map.of("channelId", CHANNEL, "channelStatus", 0)));
        assertEquals("WEBHOOK_CHANNEL_UNAVAILABLE", send(body("x"), "nonce-aaaaaaaaaaaa18").errorCode(), "a channel without a conversation bridge");
        ep.put("secretCipher", null);
        assertEquals("WEBHOOK_SECRET_UNRECOVERABLE", send(body("x"), "nonce-aaaaaaaaaaaa19").errorCode());
        assertTrue(messages.isEmpty());
    }

    @Test void cidrRules()
    {
        assertArrayEquals(new long[] { 0xCB007100L, 24 }, ChannelWebhookInboundService.cidr("203.0.113.0/24"));
        assertNull(ChannelWebhookInboundService.cidr("203.0.113.0/7"));
        assertNull(ChannelWebhookInboundService.cidr("203.0.113.256"));
        assertNull(ChannelWebhookInboundService.cidr("203.0.113.01"));
        assertNull(ChannelWebhookInboundService.cidr("::1"));
        assertTrue(ChannelWebhookInboundService.allowedIp("10.1.2.3", List.of("10.1.0.0/16")));
        assertFalse(ChannelWebhookInboundService.allowedIp("10.2.2.3", List.of("10.1.0.0/16")));
        assertFalse(ChannelWebhookInboundService.allowedIp("::1", List.of("10.1.0.0/16")));
    }
}
