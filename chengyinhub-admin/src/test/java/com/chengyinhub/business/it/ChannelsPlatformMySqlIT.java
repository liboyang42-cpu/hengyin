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

import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelProgressMapper;
import com.chengyinhub.business.mapper.team.pool.TeamResourcePoolMapper;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelProgressService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.team.pool.TeamResourcePoolService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频道平台真库：仓库迁移（#726 + 本批）在 MySQL 8 上落地，映射 SQL 与约束真实执行。
 * 只在 GitHub Actions 的 job 本地 MySQL（127.0.0.1）上建/删本套件库；不证明 Spring 事务与 HTTP 装配。
 */
class ChannelsPlatformMySqlIT
{
    static final long OWNER = 1, MEMBER = 2, STRANGER = 3, CLUB = 10, TEAM = 20, TOPIC = 30;

    private static String url;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private ChannelPlatformService platform;
    private ChannelMessageService messages;
    private ChannelProgressService progress;
    private TeamResourcePoolService pool;

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_20261112_im_channels_platform.sql")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Channel platform migration not found");
        url = MybatisItDatabase.url("im_channels_platform");
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            for (String t : new String[] { "play_team_pool_receipt", "play_team_resource_use", "play_team_resource_pool",
                    "im_channel_progress_board", "im_message_mention", "im_message_pin", "im_message_mutation", "im_message_revision",
                    "im_channel_permission_overwrite", "im_guild_role_member", "im_guild_role", "im_channel_request_receipt",
                    "im_channel_lifecycle_receipt", "im_channel_op_log", "im_channel" })
                s.execute("DROP TABLE IF EXISTS `" + t + "`");
            ItSchema.apply(c, "it-schema/channels-platform-it-schema.sql");
            ItSchema.applyMigrations(c, root.resolve("chengyinhub-admin/sql/migration_20261105_l8_channel_lifecycle.sql").toString(),
                    root.resolve("chengyinhub-admin/sql/migration_20261112_im_channels_platform.sql").toString());
        }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("channels-it", new JdbcTransactionFactory(), source));
        for (String resource : new String[] { "mapper/business/channel/platform/ChannelPlatformMapper.xml",
                "mapper/business/channel/platform/ChannelMessageMapper.xml",
                "mapper/business/channel/platform/ChannelProgressMapper.xml",
                "mapper/business/team/pool/TeamResourcePoolMapper.xml" })
        {
            try (InputStream in = ChannelsPlatformMySqlIT.class.getClassLoader().getResourceAsStream(resource))
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
            for (String t : new String[] { "play_team_pool_receipt", "play_team_resource_use", "play_team_resource_pool",
                    "im_channel_progress_board", "im_message_mention", "im_message_pin", "im_message_mutation", "im_message_revision",
                    "im_channel_permission_overwrite", "im_channel_request_receipt", "im_channel_op_log", "im_channel",
                    "im_message", "im_conversation_member", "im_conversation", "club_role_assignment", "club_member", "club",
                    "play_team_member", "play_team", "ums_member", "cms_topic", "cms_topic_node", "player_node_progress" })
                s.executeUpdate("DELETE FROM " + t);
            s.executeUpdate("INSERT INTO ums_member(id,nickname) VALUES (1,'owner'),(2,'member'),(3,'stranger')");
            s.executeUpdate("INSERT INTO club(id,member_id,name,status,del_flag) VALUES (10,1,'club',1,0)");
            s.executeUpdate("INSERT INTO club_member(club_id,member_id,status,del_flag) VALUES (10,2,1,0)");
            s.executeUpdate("INSERT INTO cms_topic(id,del_flag,published_theme_runtime_config_json) VALUES "
                    + "(30,0,'{\"gameplayRuntime\":{\"teamPool\":{\"hints\":1,\"skips\":1,\"timerSec\":0}}}')");
            s.executeUpdate("INSERT INTO cms_topic_node(id,topic_id,status) VALUES (301,30,1),(302,30,1),(303,30,0)");
            s.executeUpdate("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES (20,1,30,1,2)");
            s.executeUpdate("INSERT INTO play_team_member(team_id,member_id,status) VALUES (20,1,1),(20,2,1)");
            s.executeUpdate("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,status,checkin_time) VALUES "
                    + "(2,0,30,301,1,NOW()),(2,0,30,303,1,NOW()),(1,0,30,302,1,NOW())");
        }
        session = sessions.openSession(true);
        ChannelPlatformMapper channelMapper = session.getMapper(ChannelPlatformMapper.class);
        ChannelGuildAccess access = new ChannelGuildAccess();
        ReflectionTestUtils.setField(access, "mapper", channelMapper);
        platform = new ChannelPlatformService();
        ReflectionTestUtils.setField(platform, "mapper", channelMapper);
        ReflectionTestUtils.setField(platform, "access", access);
        messages = new ChannelMessageService();
        ReflectionTestUtils.setField(messages, "messages", session.getMapper(ChannelMessageMapper.class));
        ReflectionTestUtils.setField(messages, "channels", channelMapper);
        ReflectionTestUtils.setField(messages, "access", access);
        progress = new ChannelProgressService();
        ReflectionTestUtils.setField(progress, "progress", session.getMapper(ChannelProgressMapper.class));
        ReflectionTestUtils.setField(progress, "channels", channelMapper);
        ReflectionTestUtils.setField(progress, "access", access);
        ReflectionTestUtils.setField(progress, "platform", platform);
        pool = new TeamResourcePoolService();
        ReflectionTestUtils.setField(pool, "mapper", session.getMapper(TeamResourcePoolMapper.class));
    }

    @AfterEach void close() { if (session != null) session.close(); }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> dir)
    {
        List<Map<String, Object>> all = new java.util.ArrayList<>((List<Map<String, Object>>) dir.get("ungrouped"));
        for (Map<String, Object> g : (List<Map<String, Object>>) dir.get("groups"))
            all.addAll((List<Map<String, Object>>) g.get("channels"));
        return all;
    }

    @Test void directoryProvisionsDefaultChannelBackedByChannelConversation() throws Exception
    {
        Map<String, Object> dir = platform.directory(MEMBER, 1, CLUB, true);
        List<Map<String, Object>> rows = rows(dir);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("is_default"));
        long id = Long.parseLong((String) rows.get(0).get("id"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM im_conversation WHERE id=" + id + " AND type=5"));
        assertTrue(((List<?>) rows.get(0).get("my_permissions_list")).contains("SEND_MESSAGE"));
        assertEquals("GUILD_FORBIDDEN", assertThrows(ChannelRejection.class, () -> platform.directory(STRANGER, 1, CLUB, true)).getErrorCode());
        assertEquals(1, rows(platform.directory(OWNER, 1, CLUB, true)).size(), "second read does not provision again");
    }

    @Test void lifecycleAndOverwritesRunOnRealSchema() throws Exception
    {
        platform.directory(OWNER, 1, CLUB, false);
        Map<String, Object> created = platform.create(OWNER, 1, CLUB, body("name", "events", "topic", null, "kind", 1,
                "parent_id", "0", "visibility", 1, "slowmode_sec", 0, "request_id", "it-create-0001", "reason", "it"), "it");
        long id = Long.parseLong((String) created.get("id"));
        assertEquals("0", created.get("version"));
        assertEquals("CHANNEL_NAME_TAKEN", assertThrows(ChannelRejection.class, () -> platform.create(OWNER, 1, CLUB,
                body("name", "events", "topic", null, "kind", 1, "parent_id", "0", "visibility", 1, "slowmode_sec", 0,
                        "request_id", "it-create-0002", "reason", "it"), "it")).getErrorCode());
        Map<String, Object> edited = platform.edit(OWNER, id, body("topic", "weekly", "expected_version", "0",
                "request_id", "it-edit-0001", "reason", "it"), "it");
        assertEquals("1", edited.get("version"));
        Map<String, Object> put = platform.putOverwrite(OWNER, id, 1, "0", body("allow_bits", "0x0", "deny_bits", "0x1",
                "note", null, "expected_version", "1", "expected_perm_version", "0", "request_id", "it-perm-0001", "reason", "it"), "it");
        assertEquals("2", put.get("version"));
        assertEquals("1", put.get("perm_version"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM im_channel_permission_overwrite WHERE channel_id=" + id));
        assertTrue(rows(platform.directory(MEMBER, 1, CLUB, false)).stream().noneMatch(r -> r.get("id").equals(String.valueOf(id))));
        assertEquals("CHANNEL_NOT_FOUND", assertThrows(ChannelRejection.class, () -> platform.detail(MEMBER, id)).getErrorCode());
        platform.deleteOverwrite(OWNER, id, 1, "0", body("expected_version", "2", "expected_perm_version", "1",
                "request_id", "it-perm-0002", "reason", "it"), "it");
        assertEquals(Long.valueOf(id).toString(), platform.detail(MEMBER, id).get("id"));
        platform.archive(OWNER, id, body("archived", 1, "expected_version", "3", "request_id", "it-arch-0001", "reason", "it"), "it");
        Map<String, Object> deleted = platform.delete(OWNER, id, body("expected_version", "4", "request_id", "it-del-0001", "reason", "it"), "it");
        assertEquals(3, deleted.get("status"));
        assertNotNull(deleted.get("purge_at"));
        assertEquals(1, scalar("SELECT status FROM im_conversation WHERE id=" + id));
        Map<String, Object> restored = platform.restore(OWNER, id, body("expected_version", "5", "request_id", "it-restore-01", "reason", "it"), "it");
        assertEquals(1, restored.get("status"));
        Map<String, Object> log = platform.opLog(OWNER, id, null, 50);
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) log.get("list");
        assertEquals(List.of("RESTORE", "DELETE", "ARCHIVE", "PERMISSION_DELETE", "PERMISSION_PUT", "EDIT", "CREATE"),
                entries.stream().map(e -> e.get("action")).toList());
        Map<String, Object> receipt = platform.receipt(OWNER, "GOV", "it-perm-0001");
        assertEquals("APPLIED", receipt.get("state"));
        assertEquals("CHANNEL_FORBIDDEN", assertThrows(ChannelRejection.class, () -> platform.opLog(MEMBER, id, null, 50)).getErrorCode());
        // 数据库约束兜底：同一位既 allow 又 deny 不能落库。
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_channel_permission_overwrite(channel_id,target_type,target_id,allow_bits,deny_bits) VALUES (" + id + ",1,0,1,1)"));
    }

    @Test void messageMutationsPinsSearchAndContext() throws Exception
    {
        platform.directory(OWNER, 1, CLUB, false);
        long ch = Long.parseLong((String) rows(platform.directory(MEMBER, 1, CLUB, false)).get(0).get("id"));
        exec("INSERT INTO im_message(id,conversation_id,sender_id,msg_type,content,status,check_status,create_time,visible_at) VALUES "
                + "(901," + ch + ",2,1,'hello world',0,1,NOW(),NOW()),(902," + ch + ",1,1,'second',0,1,NOW(),NOW()),"
                + "(903," + ch + ",2,1,'third hello',0,1,NOW(),NOW())");
        Map<String, Object> edit = messages.edit(MEMBER, body("conversation_id", String.valueOf(ch), "message_id", "901",
                "expected_version", 1, "content", "hello there", "mention_member_ids", List.of("1"), "request_id", "it-edit-message-01"));
        assertEquals(2, ((Number) edit.get("version")).intValue());
        assertEquals(1, scalar("SELECT COUNT(*) FROM im_message_revision WHERE message_id=901 AND revision_no=1"));
        Map<String, Object> mutations = messages.mutations(MEMBER, ch, "0", 50);
        assertEquals(1, ((List<?>) mutations.get("list")).size());
        messages.pin(OWNER, body("conversation_id", String.valueOf(ch), "message_id", "902", "request_id", "it-pin-message-001"), true);
        messages.pin(OWNER, body("conversation_id", String.valueOf(ch), "message_id", "901", "request_id", "it-pin-message-002"), true);
        Map<String, Object> pins = messages.pins(MEMBER, ch);
        assertEquals(2, pins.get("total"));
        Map<String, Object> search = messages.search(MEMBER, ch, "hello", null, null, null, null, 20);
        @SuppressWarnings("unchecked") List<Map<String, Object>> found = (List<Map<String, Object>>) search.get("list");
        assertEquals(List.of("903", "901"), found.stream().map(r -> r.get("id")).toList());
        assertEquals(Boolean.TRUE, found.get(1).get("pinned"));
        Map<String, Object> context = messages.context(MEMBER, ch, 902, 20);
        assertEquals("902", context.get("anchor_id"));
        messages.recall(OWNER, body("conversation_id", String.valueOf(ch), "message_id", "901", "expected_version", 2,
                "reason", "", "request_id", "it-recall-message-1"));
        assertEquals(1, ((Number) messages.pins(MEMBER, ch).get("total")).intValue());
        Map<String, Object> revisions = messages.revisions(MEMBER, 901, null, 20);
        assertEquals(1, ((List<?>) revisions.get("list")).size());
        assertEquals("APPLIED", messages.receipt(MEMBER, "it-edit-message-01").get("state"));
    }

    @Test void progressBoardAndTeamPool() throws Exception
    {
        platform.directory(OWNER, 1, CLUB, false);
        long ch = Long.parseLong((String) rows(platform.directory(OWNER, 1, CLUB, false)).get(0).get("id"));
        progress.bind(OWNER, ch, body("board_key", "main", "title", "Main", "topic_id", 30, "activity_id", 0, "request_id", "it-bind-0001"));
        Map<String, Object> overview = progress.overview(MEMBER, ch);
        @SuppressWarnings("unchecked") Map<String, Object> members = (Map<String, Object>) overview.get("members");
        assertEquals(2, members.get("total"));
        Map<String, Object> top = progress.page(MEMBER, ch, "main", "ALL", 20, false, null, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) top.get("list");
        assertEquals(2, list.size());
        assertEquals(1L, ((Number) list.get(0).get("done")).longValue(), "hidden node 303 is not counted");

        Map<String, Object> view = pool.pool(MEMBER, TEAM);
        assertEquals(true, view.get("enabled"));
        assertEquals(2, view.get("teamScore"));
        Map<String, Object> first = pool.consume(MEMBER, "HINT", body("teamId", TEAM, "nodeId", 302, "requestId", "it-team-hint-00001"));
        Map<String, Object> second = pool.consume(OWNER, "HINT", body("teamId", TEAM, "nodeId", 302, "requestId", "it-team-hint-00002"));
        assertEquals(true, first.get("consumed"));
        assertEquals(false, second.get("consumed"));
        assertEquals(1, scalar("SELECT hints_used FROM play_team_resource_pool WHERE team_id=20"));
        assertEquals("TEAM_POOL_EXHAUSTED", assertThrows(ChannelRejection.class, () -> pool.consume(OWNER, "HINT",
                body("teamId", TEAM, "nodeId", 301, "requestId", "it-team-hint-00003"))).getErrorCode());
        assertEquals("TEAM_NOT_MEMBER", assertThrows(ChannelRejection.class, () -> pool.pool(STRANGER, TEAM)).getErrorCode());
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
}
