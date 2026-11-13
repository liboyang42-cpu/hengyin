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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import com.chengyinhub.business.mapper.friend.NativeBlacklistMapper;
import com.chengyinhub.business.mapper.team.pool.TeamResourcePoolMapper;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.friend.NativeBlacklistService;
import com.chengyinhub.business.service.team.pool.TeamResourcePoolService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频道第四批真库：fourth_pass 迁移（回执族 BLOCK）在 MySQL 8 上落地；黑名单（im_block）分页/当前/取消/回执/结算与
 * 本局队伍 id（play_team owner_type 1/2 + 在岗成员）的映射 SQL 真实执行，含两个并发取消只删一次。
 * 只在 GitHub Actions 风格的 job 本地 MySQL（127.0.0.1）上建/删本套件库；不证明 Spring 事务与 HTTP 装配
 * （并发用例用两个手动提交的会话代替 @Transactional）。
 */
class ChannelsFourthPassMySqlIT
{
    static final long ME = 1, PEER = 2, OTHER = 3;
    static final String[] MIGRATIONS = { "migration_20261105_l8_channel_lifecycle.sql", "migration_20261112_im_channels_platform.sql",
            "migration_20261105_l9_friend_foundation.sql", "migration_20261105_l8_chain_storage.sql",
            "migration_20261113_im_channels_second_pass.sql", "migration_20261012_app_media_asset.sql",
            "migration_20261012_app_media_asset_namespaces.sql", "migration_20261109_app_media_provenance.sql",
            "migration_20261114_im_channels_third_pass.sql", "migration_20261115_im_channels_fourth_pass.sql" };

    private static String url;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private NativeBlacklistService blacklist;
    private TeamResourcePoolService teams;

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_20261115_im_channels_fourth_pass.sql")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Fourth-pass migration not found");
        Path sql = root.resolve("chengyinhub-admin/sql");
        url = MybatisItDatabase.url("im_channels_fourth_pass");
        try (Connection c = connection())
        {
            ItSchema.apply(c, "it-schema/channels-platform-it-schema.sql");
            String[] paths = new String[MIGRATIONS.length];
            for (int i = 0; i < MIGRATIONS.length; i++) paths[i] = sql.resolve(MIGRATIONS[i]).toString();
            ItSchema.applyMigrations(c, paths);
        }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("channels-p4-it", new JdbcTransactionFactory(), source));
        for (String resource : new String[] { "mapper/business/channel/platform/ChannelPlatformMapper.xml",
                "mapper/business/friend/NativeBlacklistMapper.xml", "mapper/business/team/pool/TeamResourcePoolMapper.xml" })
        {
            try (InputStream in = ChannelsFourthPassMySqlIT.class.getClassLoader().getResourceAsStream(resource))
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
            for (String t : new String[] { "im_channel_request_receipt", "im_block", "play_team_member", "play_team", "ums_member" })
                s.executeUpdate("DELETE FROM " + t);
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            s.executeUpdate("INSERT INTO ums_member(id,nickname,name) VALUES (1,'me',NULL),(2,'peer',NULL),(3,'',NULL)");
        }
        session = sessions.openSession(true);
        blacklist = service(session);
        teams = new TeamResourcePoolService();
        ReflectionTestUtils.setField(teams, "mapper", session.getMapper(TeamResourcePoolMapper.class));
    }

    @AfterEach void close() { if (session != null) session.close(); }

    static NativeBlacklistService service(SqlSession s)
    {
        NativeBlacklistService service = new NativeBlacklistService();
        ReflectionTestUtils.setField(service, "blocks", s.getMapper(NativeBlacklistMapper.class));
        ReflectionTestUtils.setField(service, "receipts", s.getMapper(ChannelPlatformMapper.class));
        return service;
    }

    @Test void migrationAdmitsTheBlockFamilyOnly() throws Exception
    {
        exec("INSERT INTO im_channel_request_receipt(operator_id,family,request_id,payload_fingerprint,operation) VALUES (1,'BLOCK','r1',REPEAT('a',64),'unblock')");
        SQLException refused = assertThrows(SQLException.class, () ->
                exec("INSERT INTO im_channel_request_receipt(operator_id,family,request_id,payload_fingerprint,operation) VALUES (1,'NOPE','r2',REPEAT('a',64),'x')"));
        assertTrue(refused.getMessage().contains("ck_channel_receipt_family"), refused.getMessage());
    }

    @Test void pageAndCurrentReadImBlockDescendingWithNicknames() throws Exception
    {
        long peerBlock = block(ME, PEER), otherBlock = block(ME, OTHER);
        block(PEER, ME); // the reverse direction is the peer's own list, never mine
        Map<String, Object> page = blacklist.page(ME, 0);
        List<?> items = (List<?>) page.get("items");
        assertEquals(2, items.size());
        assertEquals(otherBlock, ((Map<?, ?>) items.get(0)).get("block_id"));
        assertEquals("用户3", ((Map<?, ?>) items.get(0)).get("nickname"), "an empty nickname falls back like the friend list");
        assertEquals(peerBlock, ((Map<?, ?>) items.get(1)).get("block_id"));
        assertEquals("peer", ((Map<?, ?>) items.get(1)).get("nickname"));
        assertEquals(false, page.get("has_more"));
        List<?> after = (List<?>) blacklist.page(ME, otherBlock).get("items");
        assertEquals(1, after.size(), "cursor excludes the cursor row and newer rows");

        Map<String, Object> current = blacklist.current(ME, PEER);
        assertEquals(true, current.get("blocked"));
        assertEquals(peerBlock, current.get("block_id"));
    }

    @Test void pagesOfFiftyCarryTheLastBlockIdAsCursor() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            for (int i = 0; i < 55; i++)
            {
                long peer = 100 + i;
                s.executeUpdate("INSERT INTO ums_member(id,nickname) VALUES (" + peer + ",'p" + peer + "')");
                s.executeUpdate("INSERT INTO im_block(member_id,blocked_member_id,create_time) VALUES (1," + peer + ",NOW())");
            }
        }
        Map<String, Object> first = blacklist.page(ME, 0);
        List<?> items = (List<?>) first.get("items");
        assertEquals(50, items.size());
        assertEquals(true, first.get("has_more"));
        assertEquals(((Map<?, ?>) items.get(49)).get("block_id"), first.get("next_cursor"));
        Map<String, Object> second = blacklist.page(ME, (Long) first.get("next_cursor"));
        assertEquals(5, ((List<?>) second.get("items")).size());
        assertEquals(false, second.get("has_more"));
        assertNull(second.get("next_cursor"));
    }

    @Test void unblockDeletesOnlyTheExpectedIncarnationAndReplays() throws Exception
    {
        long first = block(ME, PEER);
        String id = UUID.randomUUID().toString();
        Map<String, Object> applied = blacklist.unblock(ME, body(PEER, first, id));
        assertEquals("APPLIED", applied.get("outcome"));
        assertEquals("UNBLOCKED", applied.get("reason"));
        assertEquals(0, count("SELECT COUNT(*) FROM im_block WHERE member_id=1 AND blocked_member_id=2"));
        assertEquals(applied, blacklist.unblock(ME, body(PEER, first, id)), "replay returns the stored receipt");
        assertEquals(applied, blacklist.receipt(ME, PEER, first, id));
        assertEquals(applied, blacklist.settle(ME, body(PEER, first, id)), "settle after apply is the original");

        long second = block(ME, PEER);
        assertNotEquals(first, second, "a re-block is a new row incarnation");
        Map<String, Object> stale = blacklist.unblock(ME, body(PEER, first, UUID.randomUUID().toString()));
        assertEquals("REJECTED", stale.get("outcome"));
        assertEquals("BLOCK_CHANGED", stale.get("reason"));
        assertEquals(1, count("SELECT COUNT(*) FROM im_block WHERE id=" + second));
        assertEquals("REQUEST_ID_CONFLICT", code(() -> blacklist.unblock(ME, body(PEER, second, id))));
    }

    @Test void settleFencesAnUnsentOriginal() throws Exception
    {
        long row = block(ME, PEER);
        String id = UUID.randomUUID().toString();
        assertEquals("UNKNOWN", blacklist.receipt(ME, PEER, row, id).get("outcome"));
        assertEquals(0, count("SELECT COUNT(*) FROM im_channel_request_receipt WHERE family='BLOCK'"), "a receipt read never writes");
        Map<String, Object> settled = blacklist.settle(ME, body(PEER, row, id));
        assertEquals("FINALIZED_WITHOUT_CHANGE", settled.get("reason"));
        assertEquals("FINALIZED_WITHOUT_CHANGE", blacklist.unblock(ME, body(PEER, row, id)).get("reason"));
        assertEquals(1, count("SELECT COUNT(*) FROM im_block WHERE id=" + row), "the fenced original never deletes");
    }

    @Test void concurrentUnblocksDeleteOnce() throws Exception
    {
        long row = block(ME, PEER);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Map<String, Object>> a = CompletableFuture.supplyAsync(() -> unblockInOwnTransaction(start, row));
        CompletableFuture<Map<String, Object>> b = CompletableFuture.supplyAsync(() -> unblockInOwnTransaction(start, row));
        start.countDown();
        Map<String, Object> ra = a.get(30, TimeUnit.SECONDS), rb = b.get(30, TimeUnit.SECONDS);
        List<Object> reasons = List.of(ra.get("reason"), rb.get("reason"));
        assertTrue(reasons.contains("UNBLOCKED") && reasons.contains("BLOCK_ABSENT"), reasons.toString());
        assertEquals(2, count("SELECT COUNT(*) FROM im_channel_request_receipt WHERE family='BLOCK' AND response_json IS NOT NULL"));
    }

    private Map<String, Object> unblockInOwnTransaction(CountDownLatch start, long row)
    {
        try (SqlSession own = sessions.openSession(false))
        {
            start.await();
            Map<String, Object> out = service(own).unblock(ME, body(PEER, row, UUID.randomUUID().toString()));
            own.commit();
            return out;
        }
        catch (InterruptedException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Test void viewerRunTeamMatchesOwnerTypeMembershipAndLiveStatus() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES "
                    + "(40,2,500,1,2),(41,1,30,1,0),(42,2,501,1,3),(43,2,502,2,1),(44,2,503,2,1)");
            s.executeUpdate("INSERT INTO play_team_member(team_id,member_id,status) VALUES (40,1,1),(41,1,1),(42,1,1),(43,1,0),(44,2,1)");
        }
        assertEquals(40L, teams.viewerRunTeam(ME, 500, 30), "activity run → the activity-owned team");
        assertEquals(41L, teams.viewerRunTeam(ME, 0, 30), "self-play → the topic-owned team");
        assertNull(teams.viewerRunTeam(ME, 501, 30), "an ended team is not the run's team");
        assertNull(teams.viewerRunTeam(ME, 502, 30), "a member who left has no team");
        assertNull(teams.viewerRunTeam(ME, 503, 30), "someone else's team is never returned");
        assertEquals(44L, teams.viewerRunTeam(PEER, 503, 30));
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> body(long peer, long expected, String requestId)
    {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("peer_id", peer);
        b.put("expected_block_id", expected);
        b.put("request_id", requestId);
        return b;
    }

    static long block(long member, long peer) throws SQLException
    {
        exec("INSERT INTO im_block(member_id,blocked_member_id,create_time) VALUES (" + member + "," + peer + ",NOW())");
        return count("SELECT id FROM im_block WHERE member_id=" + member + " AND blocked_member_id=" + peer);
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getErrorCode(); }
    }

    private static Connection connection() throws SQLException
    {
        return DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    static void exec(String statement) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement()) { s.executeUpdate(statement); }
    }

    static long count(String query) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query))
        {
            rs.next();
            return rs.getLong(1);
        }
    }
}
