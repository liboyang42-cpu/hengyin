package com.chengyinhub.business.it;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

import com.chengyinhub.business.mapper.channel.platform.ChannelAuditMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelInteractionMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.service.channel.platform.ChannelAuditLogService;
import com.chengyinhub.business.service.channel.platform.ChannelCapability;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelRow;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缺口包真库：im_gap_audit_webhook 迁移在 MySQL 8 上落地（回执族 HOOK、审计/Webhook 表约束）；审计投影从真实 im_channel_op_log /
 * im_moderation_action 行追加、哈希链经 DATETIME(3) 往返后 verify 仍通过、篡改后冻结；Webhook 订阅建/改/删/轮换、测试/重试投递、
 * 原回执重放与列表 SQL 真实执行；会话最新接龙查询。空间关系/有效权限用 Mockito 给定（不证明 Spring 事务与 HTTP 装配）。
 */
class ChannelsGapImMySqlIT
{
    static final long OWNER = 1, CLUB = 10, CHANNEL = 100, OTHER_CHANNEL = 101;
    static final String[] MIGRATIONS = { "migration_20261105_l8_channel_lifecycle.sql", "migration_20261112_im_channels_platform.sql",
            "migration_20261105_l9_friend_foundation.sql", "migration_20261105_l8_chain_storage.sql",
            "migration_20261113_im_channels_second_pass.sql", "migration_20261012_app_media_asset.sql",
            "migration_20261012_app_media_asset_namespaces.sql", "migration_20261109_app_media_provenance.sql",
            "migration_20261114_im_channels_third_pass.sql", "migration_20261115_im_channels_fourth_pass.sql",
            "migration_20261116_im_gap_audit_webhook.sql" };

    private static String url;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private ChannelAuditLogService audit;
    private ChannelWebhookService webhooks;

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_20261116_im_gap_audit_webhook.sql")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Gap-im migration not found");
        Path sql = root.resolve("chengyinhub-admin/sql");
        url = MybatisItDatabase.url("im_gap_audit_webhook");
        try (Connection c = connection())
        {
            ItSchema.apply(c, "it-schema/channels-platform-it-schema.sql");
            String[] paths = new String[MIGRATIONS.length];
            for (int i = 0; i < MIGRATIONS.length; i++) paths[i] = sql.resolve(MIGRATIONS[i]).toString();
            ItSchema.applyMigrations(c, paths);
        }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("gap-im-it", new JdbcTransactionFactory(), source));
        for (String resource : new String[] { "mapper/business/channel/platform/ChannelPlatformMapper.xml",
                "mapper/business/channel/platform/ChannelInteractionMapper.xml",
                "mapper/business/channel/platform/ChannelAuditMapper.xml", "mapper/business/channel/platform/ChannelWebhookMapper.xml" })
        {
            try (InputStream in = ChannelsGapImMySqlIT.class.getClassLoader().getResourceAsStream(resource))
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
            for (String t : new String[] { "im_channel_request_receipt", "im_channel_audit_log", "im_channel_audit_head", "im_channel_op_log",
                    "im_moderation_action", "im_webhook_delivery", "im_webhook_subscription", "im_webhook_endpoint", "im_webhook_inbound_log",
                    "im_chain", "im_channel" })
                s.executeUpdate("DELETE FROM " + t);
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            s.executeUpdate("INSERT INTO im_channel(id,guild_kind,guild_id,kind,name,position,is_default,status) VALUES "
                    + "(100,1,10,1,'大厅',0,1,0),(101,1,10,1,'公告',1,0,0)");
        }
        session = sessions.openSession(true);
        ChannelGuildAccess access = mock(ChannelGuildAccess.class);
        when(access.guildBits(1, CLUB, OWNER)).thenReturn(ChannelCapability.all());
        when(access.effective(any(ChannelRow.class), eq(OWNER))).thenReturn(ChannelCapability.all());
        audit = new ChannelAuditLogService();
        ReflectionTestUtils.setField(audit, "audit", session.getMapper(ChannelAuditMapper.class));
        ReflectionTestUtils.setField(audit, "access", access);
        webhooks = new ChannelWebhookService();
        ReflectionTestUtils.setField(webhooks, "hooks", session.getMapper(ChannelWebhookMapper.class));
        ReflectionTestUtils.setField(webhooks, "channels", session.getMapper(ChannelPlatformMapper.class));
        ReflectionTestUtils.setField(webhooks, "access", access);
    }

    @AfterEach void close() { if (session != null) session.close(); }

    @Test void migrationAdmitsTheHookFamilyAndGuardsTheNewTables()
    {
        assertDoesNotThrow(() -> exec("INSERT INTO im_channel_request_receipt(operator_id,family,request_id,payload_fingerprint,operation) "
                + "VALUES (1,'HOOK','r1','" + "a".repeat(64) + "','X')"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_channel_request_receipt(operator_id,family,request_id,payload_fingerprint,operation) "
                + "VALUES (1,'NOPE','r2','" + "a".repeat(64) + "','X')"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_webhook_subscription(owner_type,owner_id,channel_id,name,url,url_host,events_json,"
                + "status,deleted,secret_sha256,secret_prefix,created_by) VALUES ('CLUB',10,100,'n','https://a.b','a.b','[]',1,1,'x','x',1)"),
                "a deleted subscription must be paused");
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_webhook_delivery(subscription_id,event_id,event_type,state) VALUES (1,'e','t','LOST')"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_channel_audit_log(guild_kind,guild_id,channel_id,source,original_id,actor_type,action,"
                + "target_type,original_time,prev_hash,row_hash) VALUES (1,10,100,'CHANNEL_OP',1,'MEMBER','A','CHANNEL',NOW(3),'x','y')"),
                "hashes must be 64 lowercase hex");
    }

    @Test void auditProjectsRealOriginalsAndVerifiesAfterRoundTrip() throws Exception
    {
        exec("INSERT INTO im_channel_op_log(channel_id,action,from_status,to_status,reason,operator_id,create_time) VALUES "
                + "(101,'CREATE',NULL,0,'新建公告频道',1,'2026-11-13 10:00:00.123'),(101,'ARCHIVE',0,1,'归档',1,'2026-11-13 10:05:00.456')");
        exec("INSERT INTO im_moderation_action(guild_kind,guild_id,target_member_id,action_type,status,scope_type,scope_id,reason_code,reason_text,"
                + "duration_sec,starts_at,operator_id,request_id,revoked_by,revoke_reason,revoked_at) VALUES "
                + "(1,10,5,'TIMEOUT','REVOKED','CHANNEL',100,'SPAM','私下说明',600,'2026-11-13 10:01:00.789',1,'m1',1,'误判','2026-11-13 10:02:00.001'),"
                + "(1,10,6,'BAN','ACTIVE','GUILD',10,'FRAUD','诈骗',0,'2026-11-13 10:03:00.000',1,'m2',NULL,NULL,NULL),"
                + "(1,11,7,'BAN','ACTIVE','GUILD',11,'FRAUD','别的俱乐部',0,'2026-11-13 10:04:00.000',1,'m3',NULL,NULL,NULL)");
        Map<String, Object> page = audit.read(OWNER, 1, CLUB, null, 50, true);
        List<?> list = (List<?>) page.get("list");
        assertEquals(5, list.size(), page.get("verify") + " / 2 channel ops + timeout + its revoke + guild ban; the other club stays out");
        assertEquals(List.of("CHANNEL_ARCHIVE", "MODERATION_BAN", "MODERATION_REVOKE", "MODERATION_TIMEOUT", "CHANNEL_CREATE"),
                list.stream().map(r -> ((Map<?, ?>) r).get("action")).toList());
        Map<?, ?> ban = (Map<?, ?>) list.get(1);
        assertEquals("100", ban.get("channel_id"), "guild-scoped action is attached to the default channel");
        assertFalse(page.toString().contains("私下说明") || page.toString().contains("误判"), "free-text reasons never enter the audit");
        Map<?, ?> proof = (Map<?, ?>) page.get("verify");
        assertEquals(Boolean.TRUE, proof.get("chain_ok"), String.valueOf(proof));
        assertEquals(5L, proof.get("checked_rows"));
        assertEquals(0L, proof.get("unproven_original_rows"));
        assertEquals(proof.get("through_original_id"), proof.get("verified_through_original_id"));
        assertEquals(scalarText("SELECT head_hash FROM im_channel_audit_head WHERE guild_kind=1 AND guild_id=10"), proof.get("head_hash"));

        // Re-reading projects nothing new; a fresh original is appended to the same chain.
        audit.read(OWNER, 1, CLUB, null, 50, false);
        assertEquals(5L, scalar("SELECT COUNT(*) FROM im_channel_audit_log"));
        exec("INSERT INTO im_channel_op_log(channel_id,action,from_status,to_status,reason,operator_id) VALUES (101,'UNARCHIVE',1,0,'恢复',1)");
        Map<?, ?> again = (Map<?, ?>) audit.read(OWNER, 1, CLUB, null, 2, true).get("verify");
        assertEquals(Boolean.TRUE, again.get("chain_ok"));
        assertEquals(6L, again.get("checked_rows"));

        exec("UPDATE im_channel_audit_log SET action='CHANNEL_DELETE' WHERE action='CHANNEL_CREATE'");
        Map<String, Object> frozen = audit.read(OWNER, 1, CLUB, null, 50, true);
        Map<?, ?> broken = (Map<?, ?>) frozen.get("verify");
        assertEquals(Boolean.FALSE, broken.get("linked_projection_ok"));
        assertEquals(Boolean.TRUE, broken.get("frozen"));
        assertEquals(List.of(), frozen.get("list"));
    }

    @Test void auditPagesWithTheBeforeCursor() throws Exception
    {
        for (int i = 0; i < 3; i++)
            exec("INSERT INTO im_channel_op_log(channel_id,action,reason,operator_id,create_time) VALUES (100,'EDIT','改名" + i + "',1,"
                    + "'2026-11-13 11:0" + i + ":00.000')");
        Map<String, Object> first = audit.read(OWNER, 1, CLUB, null, 2, false);
        assertEquals(Boolean.TRUE, first.get("has_more"));
        Map<String, Object> rest = audit.read(OWNER, 1, CLUB, (String) first.get("next_cursor"), 2, false);
        assertEquals(1, ((List<?>) rest.get("list")).size());
        assertEquals(Boolean.FALSE, rest.get("has_more"));
        assertNull(rest.get("next_cursor"));
    }

    @Test void subscriptionLifecycleOnRealRows() throws Exception
    {
        Map<String, Object> created = webhooks.createSubscription(OWNER, change("c1", null));
        String id = (String) created.get("id");
        String secret = (String) created.get("secret");
        assertNotNull(secret);
        assertEquals(ChannelWebhookServiceAccess.sha256(secret), scalarText("SELECT secret_sha256 FROM im_webhook_subscription WHERE id=" + id));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM im_channel_request_receipt WHERE family='HOOK' AND response_json LIKE '%" + secret + "%'"),
                "the receipt never stores the secret");
        assertTrue(String.valueOf(created.get("created_at")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
        Map<String, Object> replay = webhooks.createSubscription(OWNER, change("c1", null));
        assertEquals(Boolean.TRUE, replay.get("replayed"));
        assertNull(replay.get("secret"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM im_webhook_subscription"));

        long sub = Long.parseLong(id);
        Map<String, Object> edit = change("u1", 1);
        edit.put("name", "改名后");
        edit.remove("url");
        assertEquals(2, webhooks.updateSubscription(OWNER, sub, edit).get("revision"));
        assertEquals("hooks.example.com", scalarText("SELECT url_host FROM im_webhook_subscription WHERE id=" + id), "absent url keeps the old address");
        Map<String, Object> stale = change("u2", 1);
        assertEquals("REVISION_CONFLICT", assertThrows(ChannelRejection.class, () -> webhooks.updateSubscription(OWNER, sub, stale)).getErrorCode());

        Map<String, Object> delivery = webhooks.test(OWNER, sub, body("request_id", "t1", "revision", 2, "event_type", "message.created"));
        assertEquals("PENDING", delivery.get("state"));
        long deliveryId = Long.parseLong((String) delivery.get("delivery_id"));
        exec("UPDATE im_webhook_delivery SET state='FAILED', attempt=1 WHERE id=" + deliveryId);
        assertEquals("PENDING", webhooks.retry(OWNER, sub, deliveryId, body("request_id", "x1", "revision", 2)).get("state"));
        assertEquals("RETRY_REQUESTED", scalarText("SELECT reason FROM im_webhook_delivery WHERE id=" + deliveryId));
        Map<String, Object> listed = webhooks.deliveries(OWNER, sub, "PENDING", null, 20, true);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) listed.get("list")).get(0);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) row.get("payload_json")).get("test"));

        Map<String, Object> rotated = webhooks.rotateSecret(OWNER, sub, body("request_id", "r1", "revision", 2));
        assertEquals(2, rotated.get("key_version"));
        assertNotEquals(secret, rotated.get("secret"));
        Map<String, Object> deleted = webhooks.deleteSubscription(OWNER, sub, body("request_id", "d1", "revision", 3));
        assertEquals(Boolean.TRUE, deleted.get("deleted"));
        assertEquals(0, deleted.get("status"));
        assertEquals(List.of(), webhooks.listSubscriptions(OWNER, "CLUB", CLUB, CHANNEL, null, 20).get("list"), "deleted rows leave the list");
        assertEquals(Boolean.TRUE, webhooks.subscription(OWNER, sub).get("deleted"));
        assertEquals("CHANNEL_NOT_FOUND", assertThrows(ChannelRejection.class, () -> webhooks.context(OWNER, 11, CHANNEL)).getErrorCode());
    }

    @Test void endpointReadsRunOnRealRows() throws Exception
    {
        exec("INSERT INTO im_webhook_endpoint(id,owner_type,owner_id,channel_id,name,token_sha256,token_prefix,allowed_ips_json,created_by) "
                + "VALUES (4,'CLUB',10,100,'入站','" + "b".repeat(64) + "','whin_abc','[\"1.2.3.4\"]',1)");
        exec("INSERT INTO im_webhook_inbound_log(endpoint_id,result,reason) VALUES (4,'REJECTED','BAD_SIGNATURE'),(4,'RATE_LIMITED','TOO_FAST')");
        assertEquals(List.of("1.2.3.4"), webhooks.endpoint(OWNER, 4).get("allowed_ips"));
        assertEquals(1, ((List<?>) webhooks.listEndpoints(OWNER, "CLUB", CLUB, CHANNEL, null, 20).get("list")).size());
        Map<String, Object> logs = webhooks.endpointLogs(OWNER, 4, "REJECTED", null, 20);
        assertEquals(1, ((List<?>) logs.get("list")).size());
        assertEquals("WEBHOOK_INBOUND_UNAVAILABLE",
                assertThrows(ChannelRejection.class, () -> webhooks.mutateEndpoint(OWNER, 4, body("request_id", "e1"))).getErrorCode());
    }

    @Test void latestChainOfAConversation() throws Exception
    {
        ChannelInteractionMapper chains = session.getMapper(ChannelInteractionMapper.class);
        assertNull(chains.selectLatestChainId(100));
        exec("INSERT INTO im_chain(conversation_id,message_id,creator_member_id,title,max_slots,deadline_at) VALUES "
                + "(100,1,1,'a',10,'9999-12-31 23:59:59.999'),(100,2,1,'b',10,'9999-12-31 23:59:59.999'),(101,3,1,'c',10,'9999-12-31 23:59:59.999')");
        assertEquals(scalar("SELECT MAX(id) FROM im_chain WHERE conversation_id=100"), chains.selectLatestChainId(100));
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> change(String requestId, Integer revision)
    {
        Map<String, Object> b = body("request_id", requestId, "owner_type", "CLUB", "owner_id", CLUB, "channel_id", CHANNEL, "name", "俱乐部通知",
                "events", List.of("message.created"), "url", "https://hooks.example.com/club");
        if (revision != null) b.put("revision", revision);
        return b;
    }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** sha256 是包内可见；在这里重算同一摘要。 */
    static final class ChannelWebhookServiceAccess
    {
        static String sha256(String text) throws Exception
        {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
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
