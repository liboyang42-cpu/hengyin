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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** /api/im/webhook/*：上下文、订阅建/改/删/轮换（回执与一次性密钥）、测试/重试投递、端点读与入站写拒绝。 */
class ChannelWebhookServiceTest
{
    static final long OWNER = 7, CLUB = 10, CHANNEL = 100;
    ChannelWebhookMapper hooks;
    ChannelPlatformMapper channels;
    ChannelGuildAccess access;
    ChannelWebhookService service;
    final Map<Long, Map<String, Object>> subs = new HashMap<>();
    final Map<Long, Map<String, Object>> deliveries = new HashMap<>();

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
        when(hooks.rotateSubscriptionSecret(anyLong(), anyInt(), anyString(), anyString())).thenAnswer(i -> {
            Map<String, Object> row = subs.get(i.<Long>getArgument(0));
            if ((int) row.get("revision") != i.<Integer>getArgument(1)) return 0;
            row.put("secretSha256", i.getArgument(2));
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
        when(hooks.selectEndpoint(anyLong())).thenReturn(null);
        when(hooks.selectDelivery(anyLong())).thenAnswer(i -> copy(deliveries.get(i.<Long>getArgument(0))));
        when(hooks.lockDelivery(anyLong(), anyLong())).thenAnswer(i -> copy(deliveries.get(i.<Long>getArgument(1))));
        when(hooks.requeueDelivery(anyLong(), anyString(), anyInt())).thenAnswer(i -> {
            deliveries.get(i.<Long>getArgument(0)).put("state", "PENDING");
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
        assertEquals(ChannelWebhookService.sha256(secret), subs.get(1L).get("secretSha256"), "only the hash is stored");
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
        subs.get(1L).put("status", 0);
        assertEquals("WEBHOOK_PAUSED", code(() -> service.test(OWNER, 1, body("request_id", "t3", "revision", 1, "event_type", "message.created"))));
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

    @Test void endpointsAreReadableButInboundWritesAreRefused()
    {
        when(hooks.listEndpoints(CLUB, CHANNEL, null, 21)).thenReturn(List.of());
        assertEquals(List.of(), service.listEndpoints(OWNER, "CLUB", CLUB, CHANNEL, null, 20).get("list"));
        Map<String, Object> b = body("request_id", "e1", "owner_type", "CLUB", "owner_id", CLUB, "channel_id", CHANNEL, "name", "入站",
                "allowed_ips", List.of(), "rate_limit_per_min", 30);
        assertEquals("WEBHOOK_INBOUND_UNAVAILABLE", code(() -> service.createEndpoint(OWNER, b)));
        assertEquals("WEBHOOK_FORBIDDEN", code(() -> service.createEndpoint(8, b)), "authorization is checked first");
        assertEquals("WEBHOOK_NOT_FOUND", code(() -> service.endpoint(OWNER, 1)));
        assertEquals("WEBHOOK_NOT_FOUND", code(() -> service.endpointLogs(OWNER, 1, null, null, 20)));
        assertEquals("WEBHOOK_NOT_FOUND", code(() -> service.mutateEndpoint(OWNER, 1, body("request_id", "e2"))));
        Map<String, Object> ep = new HashMap<>();
        ep.put("id", 4L); ep.put("ownerType", "CLUB"); ep.put("ownerId", CLUB); ep.put("channelId", CHANNEL); ep.put("name", "x");
        ep.put("status", 1); ep.put("deleted", 0); ep.put("revision", 1); ep.put("keyVersion", 1); ep.put("tokenPrefix", "whin_abc");
        ep.put("rateLimitPerMin", 30); ep.put("allowedIpsJson", "[]");
        when(hooks.selectEndpoint(4L)).thenReturn(ep);
        Map<String, Object> view = service.endpoint(OWNER, 4);
        assertEquals(30, view.get("rate_limit_per_min"));
        assertEquals(List.of(), view.get("allowed_ips"));
        Map<String, Object> log = new HashMap<>();
        log.put("id", 9L); log.put("endpointId", 4L); log.put("result", "REJECTED");
        when(hooks.listInboundLogs(4L, null, null, 21)).thenReturn(List.of(log));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) service.endpointLogs(OWNER, 4, null, null, 20).get("list")).get(0);
        assertEquals("REJECTED", row.get("state"));
        assertEquals("4", row.get("endpoint_id"));
        assertNull(row.get("message_id"));
        assertEquals("WEBHOOK_INBOUND_UNAVAILABLE", code(() -> service.mutateEndpoint(OWNER, 4, body("request_id", "e3"))));
    }

    @Test void serverTimeIsShanghaiMillis()
    {
        assertEquals("2023-11-15 06:13:20.000", ChannelWebhookService.time(new Timestamp(1_700_000_000_000L)));
        assertNull(ChannelWebhookService.time(null));
    }
}
