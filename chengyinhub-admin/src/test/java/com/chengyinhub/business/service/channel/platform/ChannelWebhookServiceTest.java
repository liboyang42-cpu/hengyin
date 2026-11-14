package com.chengyinhub.business.service.channel.platform;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** /api/im/webhook/*：上下文、订阅建/改/删/轮换（回执、一次性密钥、密钥密文）、测试/重试/死信重投、事件发件箱、入站端点建/改/删/轮换。 */
class ChannelWebhookServiceTest
{
    static final long OWNER = 7, CLUB = 10, CHANNEL = 100;
    ChannelWebhookMapper hooks;
    ChannelPlatformMapper channels;
    ChannelGuildAccess access;
    ChannelWebhookService service;
    final Map<Long, Map<String, Object>> subs = new HashMap<>();
    final Map<Long, Map<String, Object>> deliveries = new HashMap<>();
    final Map<Long, Map<String, Object>> endpoints = new HashMap<>();
    static final PiiCryptoService CRYPTO = new PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]), "");

    @BeforeEach void setUp()
    {
        hooks = mock(ChannelWebhookMapper.class);
        channels = mock(ChannelPlatformMapper.class);
        access = mock(ChannelGuildAccess.class);
        ReceiptFake.install(channels);
        service = new ChannelWebhookService();
        ReflectionTestUtils.setField(service, "hooks", hooks);
        ReflectionTestUtils.setField(service, "channels", channels);
        ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "crypto", CRYPTO);
        ReflectionTestUtils.setField(service, "inboundBaseUrl", "https://api.chengyin.test/");
        ChannelRow channel = new ChannelRow();
        ReflectionTestUtils.setField(channel, "id", CHANNEL);
        ReflectionTestUtils.setField(channel, "guildKind", 1);
        ReflectionTestUtils.setField(channel, "guildId", CLUB);
        ReflectionTestUtils.setField(channel, "status", 0);
        ReflectionTestUtils.setField(channel, "permVersion", 3L);
        when(channels.selectChannel(CHANNEL)).thenReturn(channel);
        when(access.effective(any(ChannelRow.class), eq(OWNER))).thenReturn(ChannelCapability.all());
        when(access.effective(any(ChannelRow.class), eq(8L))).thenReturn(ChannelPermissionCalculator.ADMIN_BASE);
        when(hooks.insertSubscription(anyMap())).thenAnswer(i -> {
            Map<String, Object> row = new HashMap<>(i.<Map<String, Object>>getArgument(0));
            long id = subs.size() + 1L;
            i.<Map<String, Object>>getArgument(0).put("id", id);
            row.put("id", id);
            row.put("ownerType", "CLUB");
            row.put("deleted", 0);
            row.put("revision", 1);
            row.put("keyVersion", 1);
            row.put("failCount", 0L);
            row.put("createTime", new Timestamp(1_700_000_000_000L));
            row.put("updateTime", new Timestamp(1_700_000_000_000L));
            subs.put(id, row);
            return 1;
        });
        when(hooks.selectSubscription(anyLong())).thenAnswer(i -> copy(subs.get(i.<Long>getArgument(0))));
        when(hooks.lockSubscription(anyLong())).thenAnswer(i -> copy(subs.get(i.<Long>getArgument(0))));
        when(hooks.updateSubscription(anyMap())).thenAnswer(i -> {
            Map<String, Object> c = i.getArgument(0);
            Map<String, Object> row = subs.get((Long) c.get("id"));
            if (row == null || (int) row.get("revision") != (int) c.get("revision")) return 0;
            row.put("name", c.get("name"));
            row.put("eventsJson", c.get("eventsJson"));
            row.put("status", c.get("status"));
            if (c.get("url") != null) row.put("urlHost", c.get("urlHost"));
            row.put("revision", (int) row.get("revision") + 1);
            return 1;
        });
        when(hooks.deleteSubscription(anyLong(), anyInt())).thenAnswer(i -> {
            Map<String, Object> row = subs.get(i.<Long>getArgument(0));
            if ((int) row.get("revision") != i.<Integer>getArgument(1)) return 0;
            row.put("deleted", 1);
            row.put("status", 0);
            row.put("revision", (int) row.get("revision") + 1);
            return 1;
        });
        when(hooks.rotateSubscriptionSecret(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            Map<String, Object> row = subs.get(i.<Long>getArgument(0));
            if ((int) row.get("revision") != i.<Integer>getArgument(1)) return 0;
            row.put("secretSha256", i.getArgument(2));
            row.put("secretCipher", i.getArgument(4));
            row.put("keyVersion", (int) row.get("keyVersion") + 1);
            row.put("revision", (int) row.get("revision") + 1);
            return 1;
        });
        when(hooks.insertDelivery(anyMap())).thenAnswer(i -> {
            Map<String, Object> d = new HashMap<>(i.<Map<String, Object>>getArgument(0));
            long id = 500L + deliveries.size();
            i.<Map<String, Object>>getArgument(0).put("id", id);
            d.put("id", id);
            d.put("state", "PENDING");
            d.put("attempt", 0);
            d.put("createTime", new Timestamp(1_700_000_000_000L));
            deliveries.put(id, d);
            return 1;
        });
        when(hooks.insertEndpoint(anyMap())).thenAnswer(i -> {
            Map<String, Object> row = new HashMap<>(i.<Map<String, Object>>getArgument(0));
            long id = endpoints.size() + 1L;
            i.<Map<String, Object>>getArgument(0).put("id", id);
            row.put("id", id); row.put("ownerType", "CLUB"); row.put("deleted", 0); row.put("revision", 1); row.put("keyVersion", 1);
            row.put("createTime", new Timestamp(1_700_000_000_000L)); row.put("updateTime", new Timestamp(1_700_000_000_000L));
            endpoints.put(id, row);
            return 1;
        });
        when(hooks.selectEndpoint(anyLong())).thenAnswer(i -> copy(endpoints.get(i.<Long>getArgument(0))));
        when(hooks.lockEndpoint(anyLong())).thenAnswer(i -> copy(endpoints.get(i.<Long>getArgument(0))));
        when(hooks.updateEndpoint(anyMap())).thenAnswer(i -> {
            Map<String, Object> c = i.getArgument(0);
            Map<String, Object> row = endpoints.get((Long) c.get("id"));
            if (row == null || (int) row.get("revision") != (int) c.get("revision")) return 0;
            for (String k : new String[] { "name", "avatarUrl", "status", "rateLimitPerMin", "allowedIpsJson" }) row.put(k, c.get(k));
            row.put("revision", (int) row.get("revision") + 1);
            return 1;
        });
        when(hooks.deleteEndpoint(anyLong(), anyInt())).thenAnswer(i -> {
            Map<String, Object> row = endpoints.get(i.<Long>getArgument(0));
            if ((int) row.get("revision") != i.<Integer>getArgument(1)) return 0;
            row.put("deleted", 1); row.put("status", 0); row.put("revision", (int) row.get("revision") + 1);
            return 1;
        });
        when(hooks.rotateEndpointSecret(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            Map<String, Object> row = endpoints.get(i.<Long>getArgument(0));
            if ((int) row.get("revision") != i.<Integer>getArgument(1)) return 0;
            row.put("tokenSha256", i.getArgument(2)); row.put("tokenPrefix", i.getArgument(3)); row.put("secretCipher", i.getArgument(4));
            row.put("keyVersion", (int) row.get("keyVersion") + 1); row.put("revision", (int) row.get("revision") + 1);
            row.put("updateTime", new Timestamp(1_700_000_100_000L));
            return 1;
        });
        when(hooks.selectDelivery(anyLong())).thenAnswer(i -> copy(deliveries.get(i.<Long>getArgument(0))));
        when(hooks.lockDelivery(anyLong(), anyLong())).thenAnswer(i -> copy(deliveries.get(i.<Long>getArgument(1))));
        when(hooks.requeueDelivery(anyLong(), anyString(), anyInt(), anyBoolean())).thenAnswer(i -> {
            deliveries.get(i.<Long>getArgument(0)).put("state", "PENDING");
            if (i.<Boolean>getArgument(3)) deliveries.get(i.<Long>getArgument(0)).put("attempt", 0);
            return 1;
        });
    }

    static Map<String, Object> copy(Map<String, Object> row) { return row == null ? null : new HashMap<>(row); }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> create(String requestId)
    {
        return body("request_id", requestId, "owner_type", "CLUB", "owner_id", CLUB, "channel_id", CHANNEL, "name", "俱乐部通知",
                "events", List.of("message.created"), "url", "https://hooks.example.com/club");
    }

    static String code(Runnable r) { return assertThrows(ChannelRejection.class, r::run).getErrorCode(); }

    @Test void contextNamesTheManagerAndTheEventCatalogue()
    {
        Map<String, Object> ctx = service.context(OWNER, CLUB, CHANNEL);
        assertEquals("7", ctx.get("actor_id"));
        assertEquals("CLUB", ctx.get("owner_type"));
        assertEquals("10", ctx.get("owner_id"));
        assertEquals("100", ctx.get("channel_id"));
        assertEquals("im:webhook:manage", ctx.get("management_permission"));
        assertEquals("im-webhook-events-v1", ctx.get("event_revision"));
        assertTrue(String.valueOf(ctx.get("authorization_version")).startsWith("v1-"));
        assertEquals(5, ((List<?>) ctx.get("events")).size());
        assertEquals("WEBHOOK_FORBIDDEN", code(() -> service.context(8, CLUB, CHANNEL)), "admins need MANAGE_WEBHOOKS");
        assertEquals("CHANNEL_NOT_FOUND", code(() -> service.context(OWNER, 11, CHANNEL)), "channel must belong to the club");
        when(access.effective(any(ChannelRow.class), eq(9L))).thenReturn(0L);
        assertEquals("GUILD_FORBIDDEN", code(() -> service.context(9, CLUB, CHANNEL)));
    }

    @Test void createShowsTheSecretOnceAndReplaysWithoutIt()
    {
        String rid = UUID.randomUUID().toString();
        Map<String, Object> first = service.createSubscription(OWNER, create(rid));
        assertEquals("1", first.get("id"));
        assertEquals(1, first.get("revision"));
        assertEquals(1, first.get("key_version"));
        assertEquals(1, first.get("status"));
        assertEquals(Boolean.FALSE, first.get("deleted"));
        assertEquals(List.of("message.created"), first.get("events"));
        assertEquals("hooks.example.com", first.get("url_host"));
        assertEquals(Boolean.FALSE, first.get("replayed"));
        assertEquals(Boolean.TRUE, first.get("secret_shown_once"));
        String secret = (String) first.get("secret");
        assertTrue(secret.startsWith("whsec_"));
        assertEquals(ChannelWebhookService.sha256(secret), subs.get(1L).get("secretSha256"));
        String cipher = (String) subs.get(1L).get("secretCipher");
        assertTrue(cipher.startsWith("v1:") && !cipher.contains(secret), "the signing secret is stored encrypted, never in clear");
        assertEquals(secret, CRYPTO.decrypt(cipher), "the dispatcher can recover it");
        assertNull(first.get("url"), "the full URL is never echoed");
        Map<String, Object> replay = service.createSubscription(OWNER, create(rid));
        assertEquals(Boolean.TRUE, replay.get("replayed"));
        assertNull(replay.get("secret"));
        assertNull(replay.get("secret_shown_once"));
        assertEquals(1, subs.size());
        Map<String, Object> other = create(rid);
        other.put("name", "别的");
        assertEquals("REQUEST_ID_CONFLICT", code(() -> service.createSubscription(OWNER, other)));
    }

    @Test void createValidatesTheChange()
    {
        Map<String, Object> b = create("r1");
        b.put("url", "http://hooks.example.com");
        assertEquals("URL_INVALID", code(() -> service.createSubscription(OWNER, b)));
        b.put("url", "https://192.168.1.2/x");
        assertEquals("URL_INVALID", code(() -> service.createSubscription(OWNER, b)));
        b.put("url", "https://localhost/x");
        assertEquals("URL_INVALID", code(() -> service.createSubscription(OWNER, b)));
        b.remove("url");
        assertEquals("URL_REQUIRED", code(() -> service.createSubscription(OWNER, b)));
        Map<String, Object> e = create("r2");
        e.put("events", List.of("message.created", "message.created"));
        assertEquals("EVENT_UNKNOWN", code(() -> service.createSubscription(OWNER, e)));
        e.put("events", List.of("payment.done"));
        assertEquals("EVENT_UNKNOWN", code(() -> service.createSubscription(OWNER, e)));
        Map<String, Object> n = create("r3");
        n.put("name", "  ");
        assertEquals("WEBHOOK_CHANGE_INVALID", code(() -> service.createSubscription(OWNER, n)));
        Map<String, Object> r = create("r4");
        r.put("revision", 1);
        assertEquals("REVISION_CONFLICT", code(() -> service.createSubscription(OWNER, r)));
        Map<String, Object> bad = create("bad id!");
        assertEquals("INVALID_FIELD", code(() -> service.createSubscription(OWNER, bad)));
        verify(hooks, never()).insertSubscription(anyMap());
    }

    @Test void updateDeleteAndRotateUseTheRevision()
    {
        service.createSubscription(OWNER, create("c1"));
        Map<String, Object> change = create("u1");
        change.put("name", "改名");
        change.put("events", List.of("message.created", "member.joined"));
        change.remove("url");
        change.put("revision", 2);
        assertEquals("REVISION_CONFLICT", code(() -> service.updateSubscription(OWNER, 1, change)));
        change.put("request_id", "u2");
        change.put("revision", 1);
        Map<String, Object> updated = service.updateSubscription(OWNER, 1, change);
        assertEquals(2, updated.get("revision"));
        assertEquals("改名", updated.get("name"));
        assertEquals(List.of("message.created", "member.joined"), updated.get("events"));

        Map<String, Object> rotated = service.rotateSecret(OWNER, 1, body("request_id", "rot1", "revision", 2));
        assertEquals(3, rotated.get("revision"));
        assertEquals(2, rotated.get("key_version"));
        assertTrue(String.valueOf(rotated.get("secret")).startsWith("whsec_"));
        assertEquals(rotated.get("secret"), CRYPTO.decrypt((String) subs.get(1L).get("secretCipher")), "rotation re-encrypts the new secret");
        assertNull(service.rotateSecret(OWNER, 1, body("request_id", "rot1", "revision", 2)).get("secret"), "replay");

        Map<String, Object> deleted = service.deleteSubscription(OWNER, 1, body("request_id", "del1", "revision", 3));
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertEquals(0, deleted.get("status"));
        assertEquals(4, deleted.get("revision"));
        assertEquals("WEBHOOK_NOT_FOUND", code(() -> service.rotateSecret(OWNER, 1, body("request_id", "rot2", "revision", 4))));
    }

    @Test void testDeliveryIsQueuedNotClaimedSent()
    {
        service.createSubscription(OWNER, create("c1"));
        Map<String, Object> d = service.test(OWNER, 1, body("request_id", "t1", "revision", 1, "event_type", "message.created"));
        assertEquals("500", d.get("id"));
        assertEquals(d.get("id"), d.get("delivery_id"));
        assertEquals("PENDING", d.get("state"));
        assertEquals(0, d.get("attempt"));
        assertEquals(ChannelWebhookService.DISPATCH_REASON, d.get("reason"));
        assertEquals("message.created", d.get("event_type"));
        assertEquals("EVENT_UNKNOWN", code(() -> service.test(OWNER, 1, body("request_id", "t2", "revision", 1, "event_type", "member.left"))),
                "event must be subscribed");
        ReflectionTestUtils.setField(service, "dispatchEnabled", true);
        assertNull(service.test(OWNER, 1, body("request_id", "t4", "revision", 1, "event_type", "message.created")).get("reason"),
                "with the dispatcher on, a test delivery is plainly queued");
        subs.get(1L).put("status", 0);
        assertEquals("WEBHOOK_PAUSED", code(() -> service.test(OWNER, 1, body("request_id", "t3", "revision", 1, "event_type", "message.created"))));
    }

    @Test void secretsAreNotIssuedWithoutTheCryptoKey()
    {
        ReflectionTestUtils.setField(service, "crypto", new PiiCryptoService("", ""));
        assertEquals("WEBHOOK_CRYPTO_UNAVAILABLE", code(() -> service.createSubscription(OWNER, create("nk"))));
        assertTrue(subs.isEmpty(), "no subscription that could never be signed");
    }

    @Test void enqueueFansOutToSubscribedChannelsOnce()
    {
        Map<String, Object> a = new HashMap<>(Map.of("id", 1L, "ownerId", CLUB, "channelId", CHANNEL, "eventsJson", "[\"message.created\"]"));
        Map<String, Object> b = new HashMap<>(Map.of("id", 2L, "ownerId", CLUB, "channelId", CHANNEL, "eventsJson", "[\"member.joined\"]"));
        when(hooks.listActiveConversationSubscriptions(CHANNEL)).thenReturn(List.of(a, b));
        assertEquals(1, service.enqueueForConversation(CHANNEL, "message.created", "msg-9", Map.of("message_id", "9")));
        Map<String, Object> d = deliveries.get(500L);
        assertEquals(1L, d.get("subscriptionId"));
        assertEquals("msg-9", d.get("eventId"));
        assertTrue(String.valueOf(d.get("payloadJson")).contains("\"message_id\":\"9\""));
        when(hooks.insertDelivery(anyMap())).thenThrow(new org.springframework.dao.DuplicateKeyException("dup",
                new java.sql.SQLIntegrityConstraintViolationException("dup", "23000", 1062)));
        assertEquals(0, service.enqueueForConversation(CHANNEL, "message.created", "msg-9", Map.of()), "same event id is queued once");
        assertThrows(IllegalArgumentException.class, () -> service.enqueueForConversation(CHANNEL, "payment.done", "x", Map.of()));
    }

    @Test void retryOnlyRequeuesFailedOrUnknownUnderFiveAttempts()
    {
        service.createSubscription(OWNER, create("c1"));
        service.test(OWNER, 1, body("request_id", "t1", "revision", 1, "event_type", "message.created"));
        assertEquals("DELIVERY_NOT_RETRYABLE", code(() -> service.retry(OWNER, 1, 500, body("request_id", "x1", "revision", 1))), "pending");
        deliveries.get(500L).put("state", "FAILED");
        deliveries.get(500L).put("attempt", 5);
        assertEquals("DELIVERY_NOT_RETRYABLE", code(() -> service.retry(OWNER, 1, 500, body("request_id", "x2", "revision", 1))));
        deliveries.get(500L).put("attempt", 2);
        Map<String, Object> d = service.retry(OWNER, 1, 500, body("request_id", "x3", "revision", 1));
        assertEquals("PENDING", d.get("state"));
        assertEquals("500", d.get("delivery_id"));
        assertEquals("DELIVERY_NOT_FOUND", code(() -> service.retry(OWNER, 1, 999, body("request_id", "x4", "revision", 1))));
        deliveries.get(500L).put("state", "DEAD");
        deliveries.get(500L).put("attempt", 5);
        Map<String, Object> redriven = service.retry(OWNER, 1, 500, body("request_id", "x5", "revision", 1));
        assertEquals("PENDING", redriven.get("state"), "a dead letter can be redriven");
        assertEquals(0, redriven.get("attempt"), "with a fresh attempt budget");
        deliveries.get(500L).put("state", "SENT");
        assertEquals("DELIVERY_NOT_RETRYABLE", code(() -> service.retry(OWNER, 1, 500, body("request_id", "x6", "revision", 1))));
    }

    @Test void listsPageWithCursorAndHideSecrets()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 3; id >= 1; id--)
        {
            Map<String, Object> r = new HashMap<>();
            r.put("id", id); r.put("ownerType", "CLUB"); r.put("ownerId", CLUB); r.put("channelId", CHANNEL); r.put("name", "n" + id);
            r.put("urlHost", "hooks.example.com"); r.put("eventsJson", "[\"message.created\"]"); r.put("status", 1); r.put("deleted", 0);
            r.put("revision", 1); r.put("keyVersion", 1); r.put("failCount", 0L);
            rows.add(r);
        }
        when(hooks.listSubscriptions(CLUB, CHANNEL, null, 3)).thenReturn(rows);
        Map<String, Object> page = service.listSubscriptions(OWNER, "CLUB", CLUB, CHANNEL, null, 2);
        assertEquals(2, ((List<?>) page.get("list")).size());
        assertEquals(Boolean.TRUE, page.get("has_more"));
        assertEquals("2", page.get("next_cursor"));
        assertFalse(page.toString().contains("secret"));
        assertEquals("LIMIT_INVALID", code(() -> service.listSubscriptions(OWNER, "CLUB", CLUB, CHANNEL, null, 101)));
        assertEquals("INVALID_FIELD", code(() -> service.listSubscriptions(OWNER, "TEAM", CLUB, CHANNEL, null, 20)));
        when(hooks.listDeliveries(1L, "FAILED", null, 21)).thenReturn(List.of());
        subs.put(1L, new HashMap<>(rows.get(2)));
        assertEquals(List.of(), service.deliveries(OWNER, 1, "FAILED", null, 20, false).get("list"));
        assertEquals("INVALID_FIELD", code(() -> service.deliveries(OWNER, 1, "LOST", null, 20, false)));
    }

    static Map<String, Object> endpoint(String requestId)
    {
        return body("request_id", requestId, "owner_type", "CLUB", "owner_id", CLUB, "channel_id", CHANNEL, "name", "入站",
                "allowed_ips", List.of("203.0.113.0/24"), "rate_limit_per_min", 30);
    }

    @Test void endpointLifecycleIssuesTheSigningSecretOnce()
    {
        when(hooks.listEndpoints(CLUB, CHANNEL, null, 21)).thenReturn(List.of());
        assertEquals(List.of(), service.listEndpoints(OWNER, "CLUB", CLUB, CHANNEL, null, 20).get("list"));
        assertEquals("WEBHOOK_FORBIDDEN", code(() -> service.createEndpoint(8, endpoint("e0"))), "authorization is checked first");
        Map<String, Object> created = service.createEndpoint(OWNER, endpoint("e1"));
        assertEquals("1", created.get("id"));
        assertEquals(1, created.get("revision"));
        assertEquals(1, created.get("key_version"));
        assertEquals(List.of("203.0.113.0/24"), created.get("allowed_ips"));
        assertEquals(30, created.get("rate_limit_per_min"));
        assertEquals(Boolean.TRUE, created.get("secret_shown_once"));
        assertEquals(Boolean.TRUE, created.get("url_shown_once"));
        String secret = (String) created.get("signing_secret");
        assertTrue(secret.startsWith("whin_"));
        assertEquals("https://api.chengyin.test/api/im/webhook/inbound/1", created.get("url"));
        assertEquals(secret, CRYPTO.decrypt((String) endpoints.get(1L).get("secretCipher")));
        assertEquals(secret.substring(0, 12), created.get("token_prefix"));
        Map<String, Object> replay = service.createEndpoint(OWNER, endpoint("e1"));
        assertEquals(Boolean.TRUE, replay.get("replayed"));
        assertNull(replay.get("signing_secret"));
        assertNull(replay.get("url"));
        assertNull(replay.get("url_shown_once"));

        Map<String, Object> change = endpoint("e2");
        change.put("name", "改名");
        change.put("allowed_ips", List.of());
        change.put("rate_limit_per_min", 60);
        change.put("avatar_url", "https://cdn.example.com/a.png");
        change.put("revision", 1);
        Map<String, Object> updated = service.updateEndpoint(OWNER, 1, change);
        assertEquals(2, updated.get("revision"));
        assertEquals("改名", updated.get("name"));
        assertEquals(List.of(), updated.get("allowed_ips"));
        assertEquals(60, updated.get("rate_limit_per_min"));
        assertEquals("https://cdn.example.com/a.png", updated.get("avatar_url"));
        Map<String, Object> stale = endpoint("e3");
        stale.put("revision", 1);
        assertEquals("REVISION_CONFLICT", code(() -> service.updateEndpoint(OWNER, 1, stale)));

        Map<String, Object> rotated = service.rotateEndpointToken(OWNER, 1, body("request_id", "rt1", "revision", 2));
        assertEquals(3, rotated.get("revision"));
        assertEquals(2, rotated.get("key_version"));
        assertNotEquals(secret, rotated.get("signing_secret"));
        assertEquals(rotated.get("signing_secret"), CRYPTO.decrypt((String) endpoints.get(1L).get("secretCipher")));
        assertEquals("https://api.chengyin.test/api/im/webhook/inbound/1", rotated.get("url"));
        assertNotNull(rotated.get("old_token_invalid_after"));

        Map<String, Object> deleted = service.deleteEndpoint(OWNER, 1, body("request_id", "d1", "revision", 3));
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertEquals(0, deleted.get("status"));
        assertEquals("WEBHOOK_NOT_FOUND", code(() -> service.rotateEndpointToken(OWNER, 1, body("request_id", "rt2", "revision", 4))));

        Map<String, Object> log = new HashMap<>();
        log.put("id", 9L); log.put("endpointId", 1L); log.put("result", "REJECTED");
        when(hooks.listInboundLogs(1L, null, null, 21)).thenReturn(List.of(log));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) service.endpointLogs(OWNER, 1, null, null, 20).get("list")).get(0);
        assertEquals("REJECTED", row.get("state"));
        assertEquals("1", row.get("endpoint_id"));
        assertNull(row.get("message_id"));
    }

    @Test void endpointChangesAreValidated()
    {
        Map<String, Object> b = endpoint("v1");
        b.put("allowed_ips", List.of("10.0.0.1/33"));
        assertEquals("INVALID_FIELD", code(() -> service.createEndpoint(OWNER, b)));
        b.put("allowed_ips", List.of("1.2.3.4", "1.2.3.4"));
        assertEquals("INVALID_FIELD", code(() -> service.createEndpoint(OWNER, b)));
        b.put("allowed_ips", List.of("1.2.3.4"));
        b.put("rate_limit_per_min", 301);
        assertEquals("INVALID_FIELD", code(() -> service.createEndpoint(OWNER, b)));
        b.put("rate_limit_per_min", 30);
        b.put("url", "https://hooks.example.com");
        assertEquals("INVALID_FIELD", code(() -> service.createEndpoint(OWNER, b)), "endpoints have no outbound url");
        b.remove("url");
        b.put("avatar_url", "http://cdn.example.com/a.png");
        assertEquals("INVALID_FIELD", code(() -> service.createEndpoint(OWNER, b)));
        verify(hooks, never()).insertEndpoint(anyMap());
        ReflectionTestUtils.setField(service, "inboundBaseUrl", "");
        assertEquals("WEBHOOK_INBOUND_UNAVAILABLE", code(() -> service.createEndpoint(OWNER, endpoint("v2"))),
                "no receiving address configured: nothing to hand out");
        ReflectionTestUtils.setField(service, "inboundBaseUrl", "http://api.chengyin.test");
        assertEquals("WEBHOOK_INBOUND_UNAVAILABLE", code(() -> service.createEndpoint(OWNER, endpoint("v3"))));
    }

    @Test void serverTimeIsShanghaiMillis()
    {
        assertEquals("2023-11-15 06:13:20.000", ChannelWebhookService.time(new Timestamp(1_700_000_000_000L)));
        assertNull(ChannelWebhookService.time(null));
    }
}
