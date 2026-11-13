package com.chengyinhub.business.it;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelInteractionMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelNotifyMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.channel.platform.ProgressPrivacyMapper;
import com.chengyinhub.business.mapper.friend.FriendMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.channel.platform.ChannelGovernanceService;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelInteractionService;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.business.service.channel.platform.ChannelNotifyService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ProgressPrivacyService;
import com.chengyinhub.business.service.friend.FriendProofs;
import com.chengyinhub.business.service.friend.FriendService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频道第二批真库：#726/#727/#728 + 平台迁移 + 第二批迁移在 MySQL 8 上落地，好友/治理/接龙/打卡/通知/进度隐私的映射 SQL 与约束真实执行。
 * 只在 GitHub Actions 风格的 job 本地 MySQL（127.0.0.1）上建/删本套件库；不证明 Spring 事务与 HTTP 装配。
 */
class ChannelsSecondPassMySqlIT
{
    static final long OWNER = 1, MEMBER = 2, STRANGER = 3, CLUB = 10, TOPIC = 30;
    static final String[] TABLES = { "im_space_admission", "im_join_request", "friend_team_offer", "friend_channel_invite", "im_channel_progress_privacy", "member_progress_privacy", "im_notify_pref", "im_channel_checkin",
            "im_channel_member_state", "im_channel_report", "im_moderation_appeal", "im_moderation_action",
            "im_chain_entry_event", "im_chain_command_receipt", "im_chain_entry", "im_chain",
            "member_friend_meta", "member_friend_group", "member_friend",
            "play_team_pool_receipt", "play_team_resource_use", "play_team_resource_pool",
            "im_channel_progress_board", "im_message_mention", "im_message_pin", "im_message_mutation", "im_message_revision",
            "im_channel_permission_overwrite", "im_guild_role_member", "im_guild_role", "im_channel_request_receipt",
            "im_channel_lifecycle_receipt", "im_channel_op_log", "im_channel" };

    private static String url;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private ChannelPlatformService platform;
    private ChannelMessageService messages;
    private FriendService friends;
    private com.chengyinhub.business.service.friend.FriendInviteService invites;
    private ChannelGovernanceService governance;
    private ChannelInteractionService interaction;
    private ChannelNotifyService notify;
    private ProgressPrivacyService privacy;
    private ChannelGuildAccess access;
    private final List<String> imCalls = new ArrayList<>();

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_20261113_im_channels_second_pass.sql")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Second-pass migration not found");
        url = MybatisItDatabase.url("im_channels_second_pass");
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String t : TABLES) s.execute("DROP TABLE IF EXISTS `" + t + "`");
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            ItSchema.apply(c, "it-schema/channels-platform-it-schema.sql");
            ItSchema.applyMigrations(c,
                    root.resolve("chengyinhub-admin/sql/migration_20261105_l8_channel_lifecycle.sql").toString(),
                    root.resolve("chengyinhub-admin/sql/migration_20261112_im_channels_platform.sql").toString(),
                    root.resolve("chengyinhub-admin/sql/migration_20261105_l9_friend_foundation.sql").toString(),
                    root.resolve("chengyinhub-admin/sql/migration_20261105_l8_chain_storage.sql").toString(),
                    root.resolve("chengyinhub-admin/sql/migration_20261113_im_channels_second_pass.sql").toString());
        }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("channels-p2-it", new JdbcTransactionFactory(), source));
        for (String resource : new String[] { "mapper/business/channel/platform/ChannelPlatformMapper.xml",
                "mapper/business/channel/platform/ChannelMessageMapper.xml", "mapper/business/channel/platform/ChannelGovernanceMapper.xml",
                "mapper/business/channel/platform/ChannelInteractionMapper.xml", "mapper/business/channel/platform/ChannelNotifyMapper.xml",
                "mapper/business/channel/platform/ProgressPrivacyMapper.xml", "mapper/business/friend/FriendMapper.xml",
                "mapper/business/friend/FriendInviteMapper.xml" })
        {
            try (InputStream in = ChannelsSecondPassMySqlIT.class.getClassLoader().getResourceAsStream(resource))
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
            for (String t : TABLES) s.executeUpdate("DELETE FROM " + t);
            for (String t : new String[] { "im_message", "im_conversation_member", "im_conversation", "club_role_assignment", "club_member",
                    "club", "play_team_member", "play_team", "ums_member", "cms_topic", "cms_topic_node", "player_node_progress", "im_block" })
                s.executeUpdate("DELETE FROM " + t);
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            s.executeUpdate("INSERT INTO ums_member(id,nickname) VALUES (1,'owner'),(2,'member'),(3,'stranger')");
            s.executeUpdate("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES (20,1,30,1,2)");
            s.executeUpdate("INSERT INTO play_team_member(team_id,member_id,status) VALUES (20,1,1)");
            s.executeUpdate("INSERT INTO club(id,member_id,name,status,del_flag) VALUES (10,1,'club',1,0)");
            s.executeUpdate("INSERT INTO club_member(club_id,member_id,status,del_flag) VALUES (10,2,1,0)");
            s.executeUpdate("INSERT INTO cms_topic(id,name,del_flag) VALUES (30,'城市漫游',0)");
            s.executeUpdate("INSERT INTO cms_topic_node(id,topic_id,status) VALUES (301,30,1),(302,30,1)");
            s.executeUpdate("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,status,checkin_time) VALUES (2,0,30,301,1,NOW())");
        }
        session = sessions.openSession(true);
        ChannelPlatformMapper channelMapper = session.getMapper(ChannelPlatformMapper.class);
        ChannelGovernanceMapper governanceMapper = session.getMapper(ChannelGovernanceMapper.class);
        access = new ChannelGuildAccess();
        ReflectionTestUtils.setField(access, "mapper", channelMapper);
        IImService im = (IImService) Proxy.newProxyInstance(IImService.class.getClassLoader(), new Class<?>[] { IImService.class },
                (proxy, method, args) -> { imCalls.add(method.getName()); return null; });
        notify = new ChannelNotifyService();
        ReflectionTestUtils.setField(notify, "notify", session.getMapper(ChannelNotifyMapper.class));
        ReflectionTestUtils.setField(notify, "channels", channelMapper);
        ReflectionTestUtils.setField(notify, "access", access);
        ReflectionTestUtils.setField(notify, "im", im);
        platform = new ChannelPlatformService();
        ReflectionTestUtils.setField(platform, "mapper", channelMapper);
        ReflectionTestUtils.setField(platform, "access", access);
        ReflectionTestUtils.setField(platform, "governance", governanceMapper);
        ReflectionTestUtils.setField(platform, "muteBridge", notify);
        messages = new ChannelMessageService();
        ReflectionTestUtils.setField(messages, "messages", session.getMapper(ChannelMessageMapper.class));
        ReflectionTestUtils.setField(messages, "channels", channelMapper);
        ReflectionTestUtils.setField(messages, "access", access);
        ReflectionTestUtils.setField(messages, "restrictions", governanceMapper);
        governance = new ChannelGovernanceService();
        ReflectionTestUtils.setField(governance, "gov", governanceMapper);
        ReflectionTestUtils.setField(governance, "channels", channelMapper);
        ReflectionTestUtils.setField(governance, "access", access);
        ReflectionTestUtils.setField(governance, "platform", platform);
        ReflectionTestUtils.setField(governance, "muteBridge", notify);
        interaction = new ChannelInteractionService();
        ReflectionTestUtils.setField(interaction, "mapper", session.getMapper(ChannelInteractionMapper.class));
        ReflectionTestUtils.setField(interaction, "channels", channelMapper);
        ReflectionTestUtils.setField(interaction, "messages", messages);
        privacy = new ProgressPrivacyService();
        ReflectionTestUtils.setField(privacy, "privacy", session.getMapper(ProgressPrivacyMapper.class));
        ReflectionTestUtils.setField(privacy, "channels", channelMapper);
        ReflectionTestUtils.setField(privacy, "access", access);
        ReflectionTestUtils.setField(privacy, "platform", platform);
        friends = new FriendService();
        ReflectionTestUtils.setField(friends, "friends", session.getMapper(FriendMapper.class));
        ReflectionTestUtils.setField(friends, "receipts", channelMapper);
        ReflectionTestUtils.setField(friends, "progressGate", privacy);
        invites = new com.chengyinhub.business.service.friend.FriendInviteService();
        ReflectionTestUtils.setField(invites, "invites", session.getMapper(com.chengyinhub.business.mapper.friend.FriendInviteMapper.class));
        ReflectionTestUtils.setField(invites, "friendMapper", session.getMapper(FriendMapper.class));
        ReflectionTestUtils.setField(invites, "friends", friends);
        ReflectionTestUtils.setField(invites, "receipts", channelMapper);
        ReflectionTestUtils.setField(invites, "platform", platform);
        ReflectionTestUtils.setField(invites, "access", access);
    }

    @AfterEach void close() { if (session != null) session.close(); }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String uuid() { return UUID.randomUUID().toString().toUpperCase(); }

    long defaultChannel()
    {
        @SuppressWarnings("unchecked") List<Map<String, Object>> ungrouped = (List<Map<String, Object>>) platform.directory(OWNER, 1, CLUB, false).get("ungrouped");
        @SuppressWarnings("unchecked") List<Map<String, Object>> groups = (List<Map<String, Object>>) platform.directory(OWNER, 1, CLUB, false).get("groups");
        List<Map<String, Object>> all = new ArrayList<>(ungrouped);
        for (Map<String, Object> g : groups) { @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) g.get("channels"); all.addAll(rows); }
        return Long.parseLong((String) all.get(0).get("id"));
    }

    // ---------------------------------------------------------------- friends

    @Test void friendRequestAcceptOpensDirectConversationAndListsBothSides() throws Exception
    {
        String r1 = uuid();
        Map<String, Object> sent = friends.write(OWNER, "request", body("peer_id", 2, "request_id", r1, "msg", "一起玩"));
        assertEquals(FriendProofs.command(2, "request", "一起玩", null, null, 0, 0, null), sent.get("payload_hash"));
        assertEquals(2, ((Map<?, ?>) sent.get("relationship")).get("state"));
        assertEquals(sent, friends.write(OWNER, "request", body("peer_id", 2, "request_id", r1, "msg", "一起玩")), "replay is identical");
        assertEquals("REQUEST_ID_CONFLICT", assertThrows(ChannelRejection.class,
                () -> friends.write(OWNER, "request", body("peer_id", 2, "request_id", r1, "msg", "改了"))).getErrorCode());
        Map<String, Object> incoming = friends.page(MEMBER, true, 0, 50, null);
        @SuppressWarnings("unchecked") Map<String, Object> row = ((List<Map<String, Object>>) incoming.get("incoming")).get(0);
        assertEquals(3, row.get("state"));
        long version = ((Number) row.get("version")).longValue();
        Map<String, Object> accepted = friends.write(MEMBER, "accept", body("peer_id", 1, "request_id", uuid(), "msg", "", "expected_version", version));
        Map<?, ?> rel = (Map<?, ?>) accepted.get("relationship");
        assertEquals(1, rel.get("state"));
        assertEquals(version + 1, ((Number) rel.get("version")).longValue());
        long dm = ((Number) rel.get("conversation_id")).longValue();
        assertEquals(1, scalar("SELECT type FROM im_conversation WHERE id=" + dm));
        assertEquals(2, scalar("SELECT COUNT(*) FROM im_conversation_member WHERE conversation_id=" + dm));
        assertEquals(1, ((List<?>) friends.page(OWNER, false, 0, 50, null).get("friends")).size());
        assertEquals(1, friends.relationship(OWNER, 2).get("state"));
        assertEquals("APPLIED", friends.receipt(MEMBER, 1, "accept", (String) accepted.get("request_id")).get("outcome"));
    }

    @Test void blockedPairIsProjectedAsNoneAndRequestIsRefusedWithoutDisclosure() throws Exception
    {
        exec("INSERT INTO im_block(member_id,blocked_member_id) VALUES (2,1)");
        assertEquals("FRIEND_UNAVAILABLE", assertThrows(ChannelRejection.class,
                () -> friends.write(OWNER, "request", body("peer_id", 2, "request_id", uuid(), "msg", "hi"))).getErrorCode());
        assertEquals(0, scalar("SELECT COUNT(*) FROM member_friend"), "no pair row is written for a blocked peer");
        assertEquals(0, friends.relationship(OWNER, 2).get("state"));
    }

    @Test void metadataGroupsDirectoryProgressAndRemoval() throws Exception
    {
        befriend();
        long friendVersion = ((Number) friends.relationship(OWNER, 2).get("version")).longValue();
        Map<String, Object> group = friends.metadataWrite(OWNER, "group_save", body("id", null, "name", "队友", "position", 1, "expected_version", 0,
                "request_id", uuid()));
        assertEquals("APPLIED", group.get("outcome"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<String, Object>) group.get("groups")).get("list");
        long groupId = ((Number) list.get(1).get("id")).longValue();
        Map<String, Object> remarkBody = body("request_id", uuid(), "expected_version", 0, "peer_id", 2, "expected_friend_version", friendVersion,
                "remark", "小二", "starred", 1, "group_id", groupId);
        Map<String, Object> remark = friends.metadataWrite(OWNER, "remark", remarkBody);
        assertEquals("APPLIED", remark.get("outcome"));
        assertEquals(FriendProofs.metadata(remarkBody), remark.get("payload_hash"));
        Map<String, Object> stale = friends.metadataWrite(OWNER, "remark", body("request_id", uuid(), "expected_version", 0, "peer_id", 2,
                "expected_friend_version", friendVersion, "starred", 0));
        assertEquals("REJECTED", stale.get("outcome"));
        assertEquals("VERSION_CONFLICT", stale.get("reason"));
        Map<String, Object> dir = friends.directory(OWNER, "all", true, groupId, null, 50);
        assertEquals(1, dir.get("total"));
        assertEquals("小二", ((Map<?, ?>) ((List<?>) dir.get("items")).get(0)).get("friend_remark"));
        assertEquals(0, friends.directory(OWNER, "recent", false, null, null, 50).get("total"));
        assertEquals(1, friends.directory(OWNER, "together", false, null, null, 50).get("total"), "same club");
        Map<String, Object> progress = friends.progress(OWNER, 2, friendVersion);
        assertEquals(true, progress.get("visible"));
        @SuppressWarnings("unchecked") Map<String, Object> meter = (Map<String, Object>) ((List<Map<String, Object>>) progress.get("items")).get(0).get("meter");
        assertEquals(50, meter.get("percent"));
        privacy.saveAggregate(MEMBER, null, body("request_id", uuid(), "aggregate_scope", "SELF", "expected_updated_at", null));
        assertEquals(false, friends.progress(OWNER, 2, friendVersion).get("visible"), "peer privacy hides the friend card");
        Map<String, Object> removed = friends.remove(OWNER, body("peer_id", 2, "expected_version", friendVersion, "request_id", uuid()));
        assertEquals("APPLIED", removed.get("outcome"));
        assertEquals(friendVersion + 1, ((Number) removed.get("removed_version")).longValue());
        assertEquals(0, scalar("SELECT COUNT(*) FROM member_friend_meta"));
        Map<String, Object> again = friends.remove(OWNER, body("peer_id", 2, "expected_version", friendVersion, "request_id", uuid()));
        assertEquals("REJECTED", again.get("outcome"));
        assertEquals(404, again.get(FriendService.STATUS_KEY));
    }

    private void befriend()
    {
        friends.write(OWNER, "request", body("peer_id", 2, "request_id", uuid(), "msg", "hi"));
        friends.write(MEMBER, "request", body("peer_id", 1, "request_id", uuid(), "msg", "hi"));
        assertEquals(1, friends.relationship(OWNER, 2).get("state"), "mutual requests become friends");
    }

    // ---------------------------------------------------------------- governance

    @Test void timeoutBlocksSendingUntilRevokedAndAppealIsRecorded() throws Exception
    {
        long ch = defaultChannel();
        String req = UUID.randomUUID().toString();
        Map<String, Object> action = governance.moderate(OWNER, "timeout", body("guild_kind", 1, "guild_id", "10", "target_member_id", "2",
                "scope_type", "CHANNEL", "scope_id", String.valueOf(ch), "duration_sec", 600, "reason_code", "SPAM", "reason_text", "刷屏",
                "request_id", req, "delete_message_sec", 0));
        assertEquals("ACTIVE", action.get("status"));
        com.chengyinhub.common.exception.ServiceException muted = assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> messages.requireSend(MEMBER, ch));
        assertEquals("MEMBER_TIMED_OUT", muted.getDetailMessage());
        assertEquals("APPLIED", governance.moderationReceipt(OWNER, req).get("state"));
        Map<String, Object> mine = governance.mine(MEMBER, 1, CLUB);
        @SuppressWarnings("unchecked") Map<String, Object> view = ((List<Map<String, Object>>) mine.get("list")).get(0);
        assertEquals(1, view.get("appealable"));
        Map<String, Object> appeal = governance.submitAppeal(MEMBER, body("guild_kind", 1, "guild_id", "10", "action_id", view.get("action_id"),
                "reason_code", "MISJUDGE", "reason_text", "误判", "submit_request_id", UUID.randomUUID().toString()));
        assertEquals("PENDING", appeal.get("status"));
        String appealId = (String) appeal.get("appeal_id");
        governance.appealEvidence(MEMBER, Long.parseLong(appealId), body("request_id", UUID.randomUUID().toString(), "evidence_json", Map.of("text", "截图")));
        assertEquals(1, ((List<?>) governance.appeal(MEMBER, Long.parseLong(appealId)).get("evidence")).size());
        assertEquals("WITHDRAWN", governance.appealWithdraw(MEMBER, Long.parseLong(appealId),
                body("request_id", UUID.randomUUID().toString(), "reason", "算了")).get("status"));
        governance.revoke(OWNER, Long.parseLong((String) action.get("action_id")), body("request_id", UUID.randomUUID().toString(),
                "version", "1", "reason", "解除"));
        assertDoesNotThrow(() -> messages.requireSend(MEMBER, ch));
        assertEquals("CHANNEL_FORBIDDEN", assertThrows(ChannelRejection.class, () -> governance.moderate(MEMBER, "timeout",
                body("guild_kind", 1, "guild_id", "10", "target_member_id", "1", "scope_type", "CHANNEL", "scope_id", String.valueOf(ch),
                        "duration_sec", 600, "reason_code", "SPAM", "reason_text", "x", "request_id", UUID.randomUUID().toString(),
                        "delete_message_sec", 0))).getErrorCode());
    }

    @Test void memberStateRolesAndGrants() throws Exception
    {
        long ch = defaultChannel();
        String req = UUID.randomUUID().toString();
        Map<String, Object> state = governance.setMemberState(MEMBER, ch, body("request_id", req, "muted", 1));
        assertEquals(1, state.get("muted"));
        assertEquals(true, state.get("permanent_muted"));
        assertEquals(1, scalar("SELECT permanent_muted FROM im_notify_pref WHERE member_id=2 AND scope_type=1 AND scope_id=" + ch));
        assertEquals(1, ((Map<?, ?>) platform.detail(MEMBER, ch).get("my_state")).get("muted"));
        assertEquals("APPLIED", governance.memberStateReceipt(MEMBER, req).get("status"));
        Map<String, Object> role = governance.createRole(OWNER, body("request_id", UUID.randomUUID().toString(), "guild_kind", 1, "guild_id", "10",
                "name", "主持人", "permissions", "0x300", "hoist", 0, "mentionable", 1, "reason", "it", "color", null, "display_level", null));
        String roleId = (String) role.get("id");
        assertEquals("1", role.get("version"));
        Map<String, Object> granted = governance.setMemberRoles(OWNER, MEMBER, body("request_id", UUID.randomUUID().toString(), "guild_kind", 1,
                "guild_id", "10", "role_ids", List.of(roleId), "scope_type", "GUILD", "scope_id", "10", "expires_at", null, "grant_reason", "it"));
        assertEquals(List.of(roleId), granted.get("granted"));
        assertEquals(List.of(Long.parseLong(roleId)), access.roles(1, CLUB, MEMBER).roleIds());
        assertEquals("CHANNEL_FORBIDDEN", assertThrows(ChannelRejection.class, () -> governance.createRole(MEMBER, body("request_id",
                UUID.randomUUID().toString(), "guild_kind", 1, "guild_id", "10", "name", "x", "permissions", "0x1", "hoist", 0, "mentionable", 0,
                "reason", "it", "color", null, "display_level", null))).getErrorCode());
        Map<String, Object> deleted = governance.deleteRole(OWNER, Long.parseLong(roleId), body("request_id", UUID.randomUUID().toString(),
                "version", "1", "reason", "it"));
        assertEquals(1, deleted.get("revoked_grants"));
        assertEquals("APPLIED", platform.receipt(OWNER, "GOV", (String) deleted.get("request_id")).get("state"));
    }

    // ---------------------------------------------------------------- chain / checkin

    @Test void chainCreateJoinWithdrawCloseAndCheckin() throws Exception
    {
        long ch = defaultChannel();
        String conv = String.valueOf(ch);
        Map<String, Object> created = interaction.chain(OWNER, "create", body("requestId", UUID.randomUUID().toString(), "conversationId", conv,
                "operation", "CREATE", "title", "周六徒步", "maxSlots", 0));
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) created.get("result");
        String chainId = (String) result.get("chainId");
        assertEquals("1", result.get("chainVersion"));
        assertEquals(14, scalar("SELECT msg_type FROM im_message WHERE id=" + result.get("messageId")));
        String joinId = UUID.randomUUID().toString();
        Map<String, Object> joined = interaction.chain(MEMBER, "join", body("requestId", joinId, "conversationId", conv, "operation", "JOIN",
                "chainId", chainId, "content", "我来"));
        assertEquals(ChannelInteractionService.chainDigest("JOIN", conv, chainId, null, null, null, "我来"), joined.get("requestHash"));
        Map<String, Object> page = interaction.chainInfo(MEMBER, ch, Long.parseLong(chainId), 0, 50);
        assertEquals(1, ((Map<?, ?>) page.get("chain")).get("activeCount"));
        assertEquals(0, ((Map<?, ?>) page.get("chain")).get("maxSlots"), "0 = unlimited round-trips");
        assertEquals("APPLIED", interaction.interactionReceipt(MEMBER, ch, joinId).get("state"));
        interaction.chain(MEMBER, "withdraw", body("requestId", UUID.randomUUID().toString(), "conversationId", conv, "operation", "WITHDRAW", "chainId", chainId));
        Map<String, Object> closed = interaction.chain(OWNER, "close", body("requestId", UUID.randomUUID().toString(), "conversationId", conv,
                "operation", "CLOSE", "chainId", chainId));
        assertEquals("CLOSED", ((Map<?, ?>) closed.get("result")).get("status"));

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String checkinId = UUID.randomUUID().toString();
        Map<String, Object> checked = interaction.checkin(MEMBER, body("requestId", checkinId, "channelId", conv, "checkinDate", today));
        assertEquals("CHECKED", ((Map<?, ?>) checked.get("result")).get("resultCode"));
        Map<String, Object> again = interaction.checkin(MEMBER, body("requestId", UUID.randomUUID().toString(), "channelId", conv, "checkinDate", today));
        assertEquals("ALREADY_CHECKED", ((Map<?, ?>) again.get("result")).get("resultCode"));
        Map<String, Object> status = interaction.checkinStatus(MEMBER, ch);
        assertEquals(true, status.get("checked"));
        assertEquals(1, status.get("streak"));
        Map<String, Object> rank = interaction.checkinRank(MEMBER, ch, 30, 10);
        assertEquals(1, ((Map<?, ?>) rank.get("me")).get("rank"));
        assertEquals("APPLIED", interaction.checkinReceipt(MEMBER, ch, checkinId, today).get("state"));
        assertEquals("NOT_FOUND", interaction.checkinReceipt(MEMBER, ch, UUID.randomUUID().toString(), today).get("state"));
    }

    // ---------------------------------------------------------------- notifications / privacy

    @Test void notificationPreferencesMuteAndUnreadSummary() throws Exception
    {
        long ch = defaultChannel();
        Map<String, Object> dir = notify.preferences(MEMBER, 1, String.valueOf(ch));
        @SuppressWarnings("unchecked") Map<String, Object> row = ((List<Map<String, Object>>) dir.get("list")).get(0);
        assertEquals(3, row.get("level"));
        assertEquals(0, row.get("effective_level"), "inherits the account default");
        String req = "pref-" + UUID.randomUUID();
        Map<String, Object> saved = notify.savePreference(MEMBER, body("request_id", req, "scope_type", 1, "scope_id", String.valueOf(ch),
                "level", 1, "version", 0, "muted_until", null, "quiet_start", "22:00", "quiet_end", "07:00", "quiet_tz", "Asia/Shanghai",
                "suppress_broadcast", false, "suppress_team_status", false, "show_preview", null));
        assertEquals("APPLIED", saved.get("status"));
        assertEquals("NOTIFY_PREF_VERSION_CONFLICT", assertThrows(ChannelRejection.class, () -> notify.savePreference(MEMBER, body("request_id",
                "pref-" + UUID.randomUUID(), "scope_type", 1, "scope_id", String.valueOf(ch), "level", 2, "version", 0, "muted_until", null,
                "quiet_start", null, "quiet_end", null, "quiet_tz", null, "suppress_broadcast", false, "suppress_team_status", false,
                "show_preview", null))).getErrorCode());
        assertEquals(req, notify.notificationReceipt(MEMBER, req).get("request_id"));
        Map<String, Object> muted = notify.mute(MEMBER, body("conversation_id", String.valueOf(ch), "request_id", "mute-" + UUID.randomUUID(), "minutes", 60));
        assertNotNull(((Map<?, ?>) muted.get("result")).get("muted_until"));
        assertTrue(imCalls.contains("setMuted"));
        assertEquals(1, scalar("SELECT muted_until > CURRENT_TIMESTAMP(3) FROM im_notify_pref WHERE member_id=2 AND scope_type=1 AND scope_id=" + ch));
        assertTrue(notify.effectiveMuted(MEMBER, ch), "mute bridge sees the timed mute");
        assertEquals(5, scalar("SELECT type FROM im_conversation WHERE id=" + ch));
        exec("UPDATE im_conversation_member SET unread_count=3 WHERE conversation_id=" + ch + " AND member_id=2");
        Map<String, Object> summary = notify.unreadSummary(MEMBER);
        assertEquals(0L, summary.get("total"), "muted channel is excluded from the badge");
        assertEquals(1, ((List<?>) summary.get("by_channel")).size());
    }

    @Test void attachmentDescriptorForLegacySingleImage() throws Exception
    {
        long ch = defaultChannel();
        com.chengyinhub.business.service.channel.platform.ChannelSurfaceService surface = new com.chengyinhub.business.service.channel.platform.ChannelSurfaceService();
        ReflectionTestUtils.setField(surface, "gov", session.getMapper(ChannelGovernanceMapper.class));
        ReflectionTestUtils.setField(surface, "messages", messages);
        ReflectionTestUtils.setField(surface, "platform", platform);
        ReflectionTestUtils.setField(surface, "access", access);
        exec("INSERT INTO im_message(id,conversation_id,sender_id,msg_type,content,status,check_status,create_time,visible_at) VALUES "
                + "(951," + ch + ",1,2,'https://cdn.example/upload/a.jpg',0,1,NOW(),NOW()),(952," + ch + ",1,2,'https://cdn.example/b.jpg',0,0,NOW(),NULL),"
                + "(953," + ch + ",1,1,'text',0,1,NOW(),NOW())");
        Map<String, Object> ready = surface.attachment(MEMBER, 951);
        assertEquals("READY", ready.get("state"));
        assertEquals("https://cdn.example/upload/a.jpg", ready.get("url"));
        assertEquals(300, ready.get("expires_in_seconds"));
        assertEquals("PENDING", surface.attachment(MEMBER, 952).get("state"));
        assertEquals("UNSUPPORTED", surface.attachment(MEMBER, 953).get("state"));
        assertEquals("GUILD_FORBIDDEN", assertThrows(ChannelRejection.class, () -> surface.attachment(STRANGER, 951)).getErrorCode());
    }

    @Test void progressPrivacyBothClientsAndChannelScope() throws Exception
    {
        long ch = defaultChannel();
        Map<String, Object> snapshot = privacy.userSnapshot(MEMBER);
        assertEquals(true, snapshot.get("is_default"));
        String req = uuid();
        Map<String, Object> saved = privacy.saveAggregate(MEMBER, null, body("request_id", req, "aggregate_scope", "FRIEND", "expected_updated_at", null));
        assertEquals("APPLIED", saved.get("outcome"));
        assertEquals(saved, privacy.settingsReceipt(MEMBER, req));
        Map<String, Object> conflict = privacy.saveAggregate(MEMBER, null, body("request_id", uuid(), "aggregate_scope", "SELF", "expected_updated_at", null));
        assertEquals("PRIVACY_CONFLICT", conflict.get("reason"));
        Map<String, Object> page = privacy.progressPrivacy(MEMBER, ch);
        assertEquals("SELF", page.get("aggregate_scope"), "FRIEND is shown as SELF to the channel progress page");
        Map<String, Object> fourScopes = privacy.saveProgress(MEMBER, body("aggregate_scope", "CHANNEL", "detail_scope", "SELF", "photo_scope", "SELF",
                "leaderboard_scope", "OUT", "expected_revision", 1, "request_id", "privacy-request-0001"));
        assertEquals("APPLIED", fourScopes.get("status"));
        assertEquals(2L, ((Number) fourScopes.get("revision")).longValue());
        assertEquals("CHANNEL_FORBIDDEN", assertThrows(ChannelRejection.class, () -> privacy.saveAggregate(MEMBER, ch,
                body("request_id", uuid(), "aggregate_scope", "SELF", "expected_revision", 0))).getErrorCode());
        Map<String, Object> channelSaved = privacy.saveAggregate(OWNER, ch, body("request_id", uuid(), "aggregate_scope", "CHANNEL", "expected_revision", 0));
        assertEquals(1, channelSaved.get("applied_revision"));
        assertEquals(false, privacy.channelSnapshot(MEMBER, ch).get("can_write"));
    }

    // ---------------------------------------------------------------- friend invites

    @Test void channelAndTeamInvitesRecordOriginalCommands() throws Exception
    {
        exec("DELETE FROM club_member WHERE member_id=2");
        befriendLoose();
        long ch = defaultChannel();
        long version = ((Number) friends.relationship(OWNER, 2).get("version")).longValue();
        String batch = uuid();
        Map<String, Object> created = invites.channelCommand(OWNER, body("action", "CREATE", "object_id", ch, "request_id", batch,
                "peers", List.of(Map.of("id", 2, "version", version)), "message", "", "accepted", List.of()));
        assertEquals("APPLIED", created.get("outcome"));
        assertEquals(FriendProofs.channelInvite("CREATE", ch, "", List.of(new long[] { 2, version }), List.of()), created.get("payload_hash"));
        Map<String, Object> view = invites.channelBatch(OWNER, ch, batch);
        assertEquals(1, view.get("invited"));
        assertEquals(1, view.get("pending"));
        @SuppressWarnings("unchecked") String inviteId = (String) ((List<Map<String, Object>>) view.get("items")).get(0).get("invite_id");
        Map<String, Object> landing = invites.channelLanding(MEMBER, Long.parseLong(inviteId));
        assertEquals("NOT_MEMBER", landing.get("join_state"));
        assertEquals("SPACE", landing.get("admission_scope"));
        Map<String, Object> rules = invites.channelCommand(MEMBER, body("action", "ACCEPT_RULES", "object_id", Long.parseLong(inviteId),
                "request_id", uuid(), "peers", List.of(), "message", "", "accepted", List.of()));
        assertEquals("FRIEND_INVITE_REJECTED", rules.get("error_code"));
        invites.channelCommand(OWNER, body("action", "REVOKE", "object_id", Long.parseLong(inviteId), "request_id", uuid(), "peers", List.of(),
                "message", "", "accepted", List.of()));
        assertEquals("INVITE_REVOKED", invites.channelLanding(MEMBER, Long.parseLong(inviteId)).get("join_state"));

        String raw = "{\"action\":\"FRIEND_OFFER\",\"body\":{\"peers\":[{\"id\":2,\"version\":" + version + "}],\"teamId\":20},"
                + "\"expectedTeamId\":20,\"requestId\":\"" + UUID.randomUUID() + "\"}";
        Map<String, Object> offered = invites.teamCommand(OWNER, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("APPLIED", offered.get("outcome"));
        assertEquals(FriendProofs.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)), offered.get("requestFingerprint"));
        @SuppressWarnings("unchecked") String offerId = (String) ((List<Map<String, Object>>) ((Map<String, Object>) offered.get("friendOffer")).get("offers")).get(0).get("offer_id");
        Map<String, Object> offerView = invites.teamLanding(MEMBER, offerId);
        assertEquals(true, offerView.get("can_act"));
        String decline = "{\"action\":\"FRIEND_DECLINE\",\"body\":{\"offerId\":\"" + offerId + "\",\"teamId\":20},\"expectedTeamId\":20,"
                + "\"requestId\":\"" + UUID.randomUUID() + "\"}";
        assertEquals("APPLIED", invites.teamCommand(MEMBER, decline.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("outcome"));
        assertEquals("DECLINED", invites.teamLanding(OWNER, offerId).get("state"));
        assertEquals(1, ((List<?>) invites.teamMine(MEMBER, "0").get("items")).size());
    }

    private void befriendLoose()
    {
        friends.write(OWNER, "request", body("peer_id", 2, "request_id", uuid(), "msg", "hi"));
        friends.write(MEMBER, "request", body("peer_id", 1, "request_id", uuid(), "msg", "hi"));
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
