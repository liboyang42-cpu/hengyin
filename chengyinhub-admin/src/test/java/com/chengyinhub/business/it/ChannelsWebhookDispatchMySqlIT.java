package com.chengyinhub.business.it;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.channel.platform.ChannelCapability;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelRow;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookDispatcher;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookInboundService;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Webhook 第二步真库：20261117 迁移可重跑且形状正确；出站发件箱只对频道会话（同 id 的 type 5 会话）扇出、同 id 普通群不命中、投递器 CAS 取件/签名/SENT、
 * 退避与到期、第 5 次失败进死信、死信重投、过期租约重取都在真实 SQL 上执行；入站端点建出可恢复密钥，
 * 签名请求落 im_message（系统发送者、sender_type 4）、更新会话与未读，nonce 主键挡重放，待审消息不可见，限流计数与 nonce 清理生效。
 * 外发 HTTP 用假传输（不出网），空间权限用 Mockito 给定（不证明 Spring 事务与 HTTP 装配）。
 */
class ChannelsWebhookDispatchMySqlIT
{
    static final long OWNER = 1, CLUB = 10, CHANNEL = 100, CONVERSATION = 100;
    static final PiiCryptoService CRYPTO = new PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]), "");
    static final String[] MIGRATIONS = { "migration_im_send_idempotency_20260913.sql",
            "migration_20261105_l8_channel_lifecycle.sql", "migration_20261112_im_channels_platform.sql",
            "migration_20261105_l9_friend_foundation.sql", "migration_20261105_l8_chain_storage.sql",
            "migration_20261113_im_channels_second_pass.sql", "migration_20261012_app_media_asset.sql",
            "migration_20261012_app_media_asset_namespaces.sql", "migration_20261109_app_media_provenance.sql",
            "migration_20261114_im_channels_third_pass.sql", "migration_20261115_im_channels_fourth_pass.sql",
            "migration_20261116_im_gap_audit_webhook.sql", "migration_20261117_im_webhook_dispatch.sql",
            "migration_20261117_im_webhook_dispatch.sql" };

    private static String url;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private ChannelWebhookService webhooks;
    private ChannelWebhookDispatcher dispatcher;
    private ChannelWebhookInboundService inbound;
    private IWxContentSecurityService security;
    private final List<Object[]> sent = new ArrayList<>();
    private int status = 200;

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_20261117_im_webhook_dispatch.sql")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Webhook dispatch migration not found");
        Path sql = root.resolve("chengyinhub-admin/sql");
        url = MybatisItDatabase.url("im_webhook_dispatch");
        try (Connection c = connection())
        {
            ItSchema.apply(c, "it-schema/channels-platform-it-schema.sql");
            String[] paths = new String[MIGRATIONS.length];
            for (int i = 0; i < MIGRATIONS.length; i++) paths[i] = sql.resolve(MIGRATIONS[i]).toString();
            ItSchema.applyMigrations(c, paths);                                     // 20261117 applied twice: rerunnable
        }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("webhook-dispatch-it", new JdbcTransactionFactory(), source));
        for (String resource : new String[] { "mapper/business/channel/platform/ChannelPlatformMapper.xml",
                "mapper/business/channel/platform/ChannelWebhookMapper.xml" })
        {
            try (InputStream in = ChannelsWebhookDispatchMySqlIT.class.getClassLoader().getResourceAsStream(resource))
            {
                if (in == null) throw new IllegalStateException("Mapper XML not found: " + resource);
                new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @BeforeEach void seed() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String t : new String[] { "im_channel_request_receipt", "im_webhook_delivery", "im_webhook_subscription", "im_webhook_endpoint",
                    "im_webhook_inbound_log", "im_webhook_inbound_nonce", "im_message", "im_conversation_member", "im_conversation", "im_channel" })
                s.executeUpdate("DELETE FROM " + t);
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            // 100: channel whose conversation is the same-id type-5 channel conversation (the shape channels use);
            // 101: channel with no conversation of its own, while an ordinary group happens to have id 101.
            s.executeUpdate("INSERT INTO im_channel(id,guild_kind,guild_id,kind,name,position,is_default,status) VALUES "
                    + "(100,1,10,1,'大厅',0,1,0),(101,1,10,1,'公告',1,0,0)");
            s.executeUpdate("INSERT INTO im_conversation(id,type,biz_key,status) VALUES (100,5,'channel_100',0),(101,2,'group_101',0)");
            s.executeUpdate("INSERT INTO im_conversation_member(conversation_id,member_id,unread_count) VALUES (100,1,0),(100,2,0)");
        }
        session = sessions.openSession(true);
        ChannelGuildAccess access = mock(ChannelGuildAccess.class);
        when(access.effective(any(ChannelRow.class), eq(OWNER))).thenReturn(ChannelCapability.all());
        ChannelWebhookMapper hooks = session.getMapper(ChannelWebhookMapper.class);
        webhooks = new ChannelWebhookService();
        ReflectionTestUtils.setField(webhooks, "hooks", hooks);
        ReflectionTestUtils.setField(webhooks, "channels", session.getMapper(ChannelPlatformMapper.class));
        ReflectionTestUtils.setField(webhooks, "access", access);
        ReflectionTestUtils.setField(webhooks, "crypto", CRYPTO);
        ReflectionTestUtils.setField(webhooks, "dispatchEnabled", true);
        ReflectionTestUtils.setField(webhooks, "inboundBaseUrl", "https://api.chengyin.test");
        dispatcher = new ChannelWebhookDispatcher();
        ReflectionTestUtils.setField(dispatcher, "hooks", hooks);
        ReflectionTestUtils.setField(dispatcher, "crypto", CRYPTO);
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        ReflectionTestUtils.setField(dispatcher, "backoffBaseMs", 60_000L);
        ReflectionTestUtils.setField(dispatcher, "resolver", (ChannelWebhookDispatcher.Resolver) host ->
                new java.net.InetAddress[] { java.net.InetAddress.getByName("93.184.216.34") });
        ReflectionTestUtils.setField(dispatcher, "transport", (ChannelWebhookDispatcher.Transport) (uri, headers, body) -> {
            sent.add(new Object[] { uri, headers, body });
            return new ChannelWebhookDispatcher.Response(status, status == 200 ? "ok" : "boom");
        });
        security = mock(IWxContentSecurityService.class);
        inbound = new ChannelWebhookInboundService();
        ReflectionTestUtils.setField(inbound, "hooks", hooks);
        ReflectionTestUtils.setField(inbound, "crypto", CRYPTO);
        ReflectionTestUtils.setField(inbound, "security", security);
    }

    @AfterEach void close() { if (session != null) session.close(); }

    @Test void migrationIsRerunnableAndShaped() throws Exception
    {
        for (String[] col : new String[][] { { "im_webhook_subscription", "secret_cipher" }, { "im_webhook_endpoint", "secret_cipher" },
                { "im_webhook_delivery", "lease_until" }, { "im_webhook_delivery", "last_attempt_at" } })
            assertEquals(1L, scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + col[0]
                    + "' AND column_name='" + col[1] + "'"), col[0] + "." + col[1]);
        for (String[] idx : new String[][] { { "im_webhook_delivery", "idx_webhook_delivery_due" }, { "im_webhook_subscription", "idx_webhook_sub_channel" },
                { "im_webhook_inbound_log", "idx_webhook_inbound_time" } })
            assertTrue(scalar("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='" + idx[0]
                    + "' AND index_name='" + idx[1] + "'") > 0, idx[1]);
        exec("INSERT INTO im_webhook_inbound_nonce(endpoint_id,nonce,signed_at) VALUES (1,'nonce-1',1)");
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_webhook_inbound_nonce(endpoint_id,nonce,signed_at) VALUES (1,'nonce-1',2)"));
        assertDoesNotThrow(() -> exec("INSERT INTO im_webhook_inbound_nonce(endpoint_id,nonce,signed_at) VALUES (2,'nonce-1',2)"));
    }

    @SuppressWarnings("unchecked")
    @Test void outboxDispatchRetryDeadLetterAndRedriveOnRealRows() throws Exception
    {
        Map<String, Object> created = webhooks.createSubscription(OWNER, body("request_id", "c1", "owner_type", "CLUB", "owner_id", CLUB,
                "channel_id", CHANNEL, "name", "通知", "events", List.of("message.created"), "url", "https://hooks.example.com/club"));
        long sub = Long.parseLong((String) created.get("id"));
        String secret = (String) created.get("secret");
        String cipher = scalarText("SELECT secret_cipher FROM im_webhook_subscription WHERE id=" + sub);
        assertTrue(cipher.startsWith("v1:") && !cipher.contains(secret));

        assertEquals(1, webhooks.enqueueForConversation(CONVERSATION, "message.created", "msg-1", Map.of("message_id", "1", "content", "你好")));
        assertEquals(0, webhooks.enqueueForConversation(CONVERSATION, "message.created", "msg-1", Map.of()), "same event once per subscription");
        webhooks.createSubscription(OWNER, body("request_id", "c2", "owner_type", "CLUB", "owner_id", CLUB, "channel_id", 101L, "name", "公告",
                "events", List.of("message.created"), "url", "https://hooks.example.com/other"));
        assertEquals(0, webhooks.enqueueForConversation(101, "message.created", "msg-x", Map.of()),
                "an ordinary group that shares an id with a channel reaches no channel subscription");
        assertEquals(0, webhooks.enqueueForConversation(CONVERSATION, "member.joined", "j-1", Map.of()), "not subscribed");

        assertEquals(1, dispatcher.runOnce());
        long first = scalar("SELECT id FROM im_webhook_delivery WHERE event_id='msg-1'");
        assertEquals("SENT", scalarText("SELECT state FROM im_webhook_delivery WHERE id=" + first));
        assertEquals(1L, scalar("SELECT attempt FROM im_webhook_delivery WHERE id=" + first));
        assertEquals(200L, scalar("SELECT http_status FROM im_webhook_delivery WHERE id=" + first));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM im_webhook_delivery WHERE id=" + first + " AND (lease_until IS NOT NULL OR last_attempt_at IS NULL)"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_subscription WHERE id=" + sub + " AND last_delivery_at IS NOT NULL AND fail_count=0"));
        Map<String, String> headers = (Map<String, String>) sent.get(0)[1];
        byte[] payload = (byte[]) sent.get(0)[2];
        assertEquals("v1=" + ChannelWebhookDispatcher.sign(secret, Long.parseLong(headers.get("X-Chengyin-Timestamp")), headers.get("X-Chengyin-Nonce"), payload),
                headers.get("X-Chengyin-Signature"), "signed with the secret the owner was shown once");
        assertTrue(new String(payload, StandardCharsets.UTF_8).contains("你好"));
        assertEquals(0, dispatcher.runOnce(), "nothing left");

        status = 500;
        webhooks.enqueueForConversation(CONVERSATION, "message.created", "msg-2", Map.of("message_id", "2"));
        assertEquals(1, dispatcher.runOnce());
        long second = scalar("SELECT id FROM im_webhook_delivery WHERE event_id='msg-2'");
        assertEquals("FAILED", scalarText("SELECT state FROM im_webhook_delivery WHERE id=" + second));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_delivery WHERE id=" + second
                + " AND next_retry_at > CURRENT_TIMESTAMP(3) + INTERVAL 40 SECOND AND next_retry_at < CURRENT_TIMESTAMP(3) + INTERVAL 80 SECOND"),
                "first backoff is base 60s ±20%");
        assertEquals(0, dispatcher.runOnce(), "not due yet");
        exec("UPDATE im_webhook_delivery SET attempt=4, next_retry_at=CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id=" + second);
        assertEquals(1, dispatcher.runOnce());
        assertEquals("DEAD", scalarText("SELECT state FROM im_webhook_delivery WHERE id=" + second));
        assertEquals("MAX_ATTEMPTS:HTTP_500", scalarText("SELECT reason FROM im_webhook_delivery WHERE id=" + second));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_delivery WHERE id=" + second + " AND next_retry_at IS NULL"));
        assertEquals(2L, scalar("SELECT fail_count FROM im_webhook_subscription WHERE id=" + sub));
        Map<?, ?> deadRow = (Map<?, ?>) ((List<?>) webhooks.deliveries(OWNER, sub, "DEAD", null, 20, false).get("list")).get(0);
        assertEquals("DEAD", deadRow.get("state"), "the dead letter is visible on the delivery route");
        assertEquals(500, deadRow.get("http_status"));

        Map<String, Object> redriven = webhooks.retry(OWNER, sub, second, body("request_id", "x1", "revision", 1));
        assertEquals("PENDING", redriven.get("state"));
        assertEquals(0, redriven.get("attempt"));
        status = 200;
        assertEquals(1, dispatcher.runOnce());
        assertEquals("SENT", scalarText("SELECT state FROM im_webhook_delivery WHERE id=" + second));

        webhooks.enqueueForConversation(CONVERSATION, "message.created", "msg-3", Map.of());
        long third = scalar("SELECT id FROM im_webhook_delivery WHERE event_id='msg-3'");
        exec("UPDATE im_webhook_delivery SET state='INFLIGHT', attempt=1, lease_until=CURRENT_TIMESTAMP(3) + INTERVAL 1 MINUTE WHERE id=" + third);
        assertEquals(0, dispatcher.runOnce(), "a live lease is left alone");
        exec("UPDATE im_webhook_delivery SET lease_until=CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id=" + third);
        assertEquals(1, dispatcher.runOnce(), "an expired lease is taken again");
        assertEquals(2L, scalar("SELECT attempt FROM im_webhook_delivery WHERE id=" + third));
        assertEquals("SENT", scalarText("SELECT state FROM im_webhook_delivery WHERE id=" + third));

        exec("UPDATE im_webhook_subscription SET secret_cipher=NULL WHERE id=" + sub);
        webhooks.enqueueForConversation(CONVERSATION, "message.created", "msg-4", Map.of());
        assertEquals(1, dispatcher.runOnce());
        assertEquals("SECRET_UNRECOVERABLE", scalarText("SELECT reason FROM im_webhook_delivery WHERE event_id='msg-4'"));
    }

    @Test void inboundEndpointAcceptsSignedMessagesOnRealRows() throws Exception
    {
        Map<String, Object> created = webhooks.createEndpoint(OWNER, body("request_id", "e1", "owner_type", "CLUB", "owner_id", CLUB,
                "channel_id", CHANNEL, "name", "CI 机器人", "allowed_ips", List.of(), "rate_limit_per_min", 3));
        long ep = Long.parseLong((String) created.get("id"));
        String secret = (String) created.get("signing_secret");
        assertEquals("https://api.chengyin.test/api/im/webhook/inbound/" + ep, created.get("url"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM im_channel_request_receipt WHERE family='HOOK' AND response_json LIKE '%" + secret + "%'"),
                "the receipt never stores the signing secret");

        byte[] b = "{\"content\":\"构建 #42 通过\"}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000;
        String sig = "v1=" + ChannelWebhookDispatcher.sign(secret, ts, "nonce-0000000000000001", b);
        ChannelWebhookInboundService.Result ok = inbound.ingest(ep, String.valueOf(ts), "nonce-0000000000000001", sig, b, "203.0.113.9");
        assertEquals(200, ok.status(), String.valueOf(ok));
        long message = Long.parseLong((String) ok.data().get("message_id"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_message WHERE id=" + message + " AND conversation_id=100 AND sender_id=0 AND msg_type=1 "
                + "AND check_status=1 AND visible_at IS NOT NULL AND content='构建 #42 通过' AND JSON_EXTRACT(extra_json,'$.sender_type')=4"));
        assertEquals("构建 #42 通过", scalarText("SELECT last_msg_text FROM im_conversation WHERE id=100"));
        assertEquals(2L, scalar("SELECT SUM(unread_count) FROM im_conversation_member WHERE conversation_id=100"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_inbound_log WHERE endpoint_id=" + ep + " AND result='ACCEPTED' AND message_id=" + message
                + " AND sender_type=4 AND webhook_id=" + ep + " AND original_message_stored=1"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_endpoint WHERE id=" + ep + " AND last_called_at IS NOT NULL"));

        ChannelWebhookInboundService.Result replay = inbound.ingest(ep, String.valueOf(ts), "nonce-0000000000000001", sig, b, "203.0.113.9");
        assertEquals("WEBHOOK_REPLAYED", replay.errorCode());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_message"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_inbound_log WHERE result='REJECTED' AND reason='REPLAYED' AND message_id IS NULL"));

        when(security.checkText(anyString(), isNull())).thenReturn(IWxContentSecurityService.MSG_UNAVAILABLE);
        String sig2 = "v1=" + ChannelWebhookDispatcher.sign(secret, ts, "nonce-0000000000000002", b);
        assertEquals(202, inbound.ingest(ep, String.valueOf(ts), "nonce-0000000000000002", sig2, b, "203.0.113.9").status());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_message WHERE check_status=0 AND visible_at IS NULL"), "held for review, not visible");
        assertEquals(2L, scalar("SELECT SUM(unread_count) FROM im_conversation_member WHERE conversation_id=100"), "no unread for a held message");

        String sig3 = "v1=" + ChannelWebhookDispatcher.sign(secret, ts, "nonce-0000000000000003", b);
        assertEquals("WEBHOOK_RATE_LIMITED", inbound.ingest(ep, String.valueOf(ts), "nonce-0000000000000003", sig3, b, "203.0.113.9").errorCode(),
                "three calls in the last minute reach rate_limit_per_min=3");

        Map<String, Object> rotated = webhooks.rotateEndpointToken(OWNER, ep, body("request_id", "rt1", "revision", 1));
        exec("DELETE FROM im_webhook_inbound_log");
        String sig4 = "v1=" + ChannelWebhookDispatcher.sign(secret, ts, "nonce-0000000000000004", b);
        assertEquals("WEBHOOK_SIGNATURE_INVALID", inbound.ingest(ep, String.valueOf(ts), "nonce-0000000000000004", sig4, b, "1.1.1.1").errorCode(),
                "the old secret stops working at rotation");
        String sig5 = "v1=" + ChannelWebhookDispatcher.sign((String) rotated.get("signing_secret"), ts, "nonce-0000000000000004", b);
        assertEquals(202, inbound.ingest(ep, String.valueOf(ts), "nonce-0000000000000004", sig5, b, "1.1.1.1").status());

        Map<?, ?> logs = webhooks.endpointLogs(OWNER, ep, null, null, 20);
        assertEquals(2, ((List<?>) logs.get("list")).size());
        exec("UPDATE im_webhook_inbound_nonce SET create_time = CURRENT_TIMESTAMP(3) - INTERVAL 1 HOUR");
        inbound.purgeNonces();
        assertEquals(0L, scalar("SELECT COUNT(*) FROM im_webhook_inbound_nonce"));
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Connection connection() throws SQLException
    {
        return DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    private static void exec(String sql) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement()) { s.executeUpdate(sql); }
    }

    private static long scalar(String sql) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql))
        {
            r.next();
            return r.getLong(1);
        }
    }

    private static String scalarText(String sql) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql))
        {
            r.next();
            return r.getString(1);
        }
    }
}
