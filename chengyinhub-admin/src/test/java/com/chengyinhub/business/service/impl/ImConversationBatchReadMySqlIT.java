package com.chengyinhub.business.service.impl;

import java.sql.Connection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** 只能由获准执行端点名运行；缺显式开关/连接参数直接失败，不跳过、不使用 H2。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class ImConversationBatchReadMySqlIT
{
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private boolean created;
    private ImServiceImpl service;
    private ImConversationMemberMapper members;
    private ImConversationMapper conversations;
    private ImMessageMapper messages;
    private final StatementCounter statements = new StatementCounter();

    @BeforeAll
    void setup() throws Exception
    {
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port > 0 && port <= 65535);
        String user = required("CHENGYIN_MYBATIS_IT_USER");
        assertFalse(user.isBlank());
        String password = required("CHENGYIN_MYBATIS_IT_PASSWORD");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_im_batch_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql")),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql")),
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql"))).execute(source);
        db.execute("create table ums_member(id bigint primary key,name varchar(64),nickname varchar(64),avatar varchar(255))");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        factory.setPlugins(statements);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        members = session.getMapper(ImConversationMemberMapper.class);
        conversations = session.getMapper(ImConversationMapper.class);
        messages = session.getMapper(ImMessageMapper.class);
        service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
    }

    @BeforeEach
    void seed()
    {
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        db.update("delete from ums_member");
        conversation(101, 1, 0, 3, 0, 2L);
        conversation(102, 2, 0, 2, 0, null);
        conversation(103, 3, 0, 4, 1, null);
        conversation(104, 1, 0, 5, 0, 3L);
        conversation(105, 1, 0, 0, 0, 4L);
        db.update("insert into ums_member(id,name,nickname,avatar) values (2,'name fallback',null,'avatar-2'),(4,'old name','current nickname',null)");
        // 图片可见时刻可以晚于 id/创建时刻；不得使用 max(id) 或会话行的旧摘要。
        message(10, 101, 2, 2, 0, 1, "10:00:00", "10:30:00", "approved image");
        message(11, 101, 2, 1, 0, 1, "10:00:00", "10:00:00", "text");
        message(12, 101, 2, 2, 0, 0, "11:00:00", null, "pending image");
        message(13, 101, 2, 1, 2, 1, "12:00:00", "12:00:00", "blocked secret");
        message(20, 102, 0, 1, 0, 1, "10:30:00", "10:30:00", "same time lower id");
        message(21, 102, 0, 1, 1, 1, "10:30:00", "10:30:00", "recalled text");
        message(22, 103, 0, 1, 0, 1, "09:00:00", "09:00:00", "merchant text");
        statements.count.set(0);
    }

    @AfterAll
    void cleanup()
    {
        if (created)
        {
            admin.execute("drop database " + schema);
            assertEquals(0, admin.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name=?", Integer.class, schema));
        }
    }

    @Test
    void batchEqualsLegacyVisibleQueriesIncludingPendingBlockedRecalledAndMissingProfiles()
    {
        for (long viewer : new long[] {1L, 2L})
        {
            List<Map<String, Object>> expected = legacyRows(viewer);
            statements.count.set(0);
            List<Map<String, Object>> actual = service.listConversations(viewer);
            assertEquals(expected, actual);
            assertEquals(1, statements.count.get(), "列表必须只有一条 MyBatis statement");
        }
        List<Map<String, Object>> reader = service.listConversations(1L);
        assertEquals(Arrays.asList(102L, 101L, 103L, 105L, 104L), reader.stream()
                .map(row -> (Long) row.get("conversationId")).toList());
        assertEquals("[图片]", reader.get(1).get("lastMsgText"));
        assertEquals(Map.of("id", 2L, "nickname", "name fallback", "avatar", "avatar-2"),
                reader.get(1).get("counterparty"));
        assertEquals(Map.of("id", 3L, "nickname", "", "avatar", ""), reader.get(4).get("counterparty"));
    }

    @Test
    void inboxAggregateExcludesAllHiddenGroupsMutedDeletedClosedOrForeignMemberships()
    {
        conversation(106, 4, 0, 50, 0, null); // 群资格无论有效/失效，均不计小程序红点。
        conversation(107, 4, 0, 60, 0, null);
        conversation(108, 1, 1, 70, 0, null);
        conversation(109, 1, 0, 80, 0, null);
        db.update("update im_conversation_member set deleted=1 where conversation_id=109 and member_id=1");
        db.update("insert into im_conversation_member(conversation_id,member_id,unread_count) values(999,1,90),(101,9,100)");
        assertTrue(members.sumUnread(1L) > 10, "旧 broad SUM 不能直接复用");
        long before = db.queryForObject("select sum(unread_count) from im_conversation_member", Long.class);

        statements.count.set(0);
        assertEquals(10, service.unreadTotal(1L));
        assertEquals(1, statements.count.get());
        assertEquals(before, db.queryForObject("select sum(unread_count) from im_conversation_member", Long.class));
        statements.count.set(0);
        assertEquals(0, service.unreadTotal(999L));
        assertEquals(1, statements.count.get());
    }

    @Test
    void oneStatementAtSixtyFourConversationsAndEmptyAccountDoesNotLoadUnownedRows()
    {
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        for (int i = 1; i <= 64; i++)
        {
            conversation(1000 + i, 1, 0, 1, 0, 2L);
            message(1000 + i, 1000 + i, 2, 1, 0, 1, "10:00:00", "10:00:00", "m" + i);
        }
        List<Map<String, Object>> expected = legacyRows(1L);
        statements.count.set(0);
        assertEquals(expected, service.listConversations(1L));
        assertEquals(1, statements.count.get());
        statements.count.set(0);
        assertEquals(64, service.unreadTotal(1L));
        assertEquals(1, statements.count.get());
        statements.count.set(0);
        assertTrue(service.listConversations(999L).isEmpty());
        assertEquals(1, statements.count.get());
    }

    @Test
    void moderationVisibilityChangesAreReadImmediatelyAndNeverUseStaleConversationSummary()
    {
        db.update("update im_message set status=2 where id=10");
        assertEquals(legacyRows(1L), service.listConversations(1L));
        Map<String, Object> row = service.listConversations(1L).stream()
                .filter(value -> Long.valueOf(101).equals(value.get("conversationId"))).findFirst().orElseThrow();
        assertEquals("text", row.get("lastMsgText"));
        db.update("update im_message set status=0 where id=10");
        assertEquals(legacyRows(1L), service.listConversations(1L));
        db.update("update im_message set check_status=1,visible_at='2026-10-23 13:00:00' where id=12");
        List<Map<String, Object>> expected = legacyRows(1L);
        statements.count.set(0);
        List<Map<String, Object>> actual = service.listConversations(1L);
        assertEquals(expected, actual);
        assertEquals(101L, actual.get(0).get("conversationId"));
        assertEquals("[图片]", actual.get(0).get("lastMsgText"));
        assertEquals(1, statements.count.get());
    }

    /** 基线保留的逐行查询作独立 SQL oracle，不通过新读模型构造 expected。 */
    private List<Map<String, Object>> legacyRows(long viewer)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Long, ImMessage> latestByConversation = new HashMap<>();
        for (ImConversationMember membership : members.selectMyConversations(viewer))
        {
            ImConversation conversation = conversations.selectImConversationById(membership.getConversationId());
            assertNotEquals(ImConversation.TYPE_GROUP, conversation.getType(), "该 oracle 的夹具只含非群会话");
            List<ImMessage> visible = messages.selectVisiblePage(conversation.getId(), viewer, null, 1);
            ImMessage latest = visible.isEmpty() ? null : visible.get(0);
            latestByConversation.put(conversation.getId(), latest);
            Map<String, Object> row = new HashMap<>();
            row.put("conversationId", conversation.getId()); row.put("type", conversation.getType());
            row.put("lastMsgType", latest == null ? null : latest.getMsgType());
            row.put("lastMsgText", latest == null ? "" : latest.getMsgType() == ImMessage.MSG_IMAGE ? "[图片]" : latest.getContent());
            row.put("lastMsgAt", latest == null ? null : latest.getCreateTime());
            row.put("unread", membership.getUnreadCount());
            row.put("muted", membership.getMuted() == null ? 0 : membership.getMuted());
            Map<String, Object> cp = new HashMap<>();
            cp.put("id", 0); cp.put("nickname", ""); cp.put("avatar", "");
            if (conversation.getType() == ImConversation.TYPE_SYSTEM) cp.put("nickname", "系统通知");
            else if (conversation.getType() == ImConversation.TYPE_MERCHANT) cp.put("nickname", "官方客服");
            else
            {
                for (ImConversationMember part : members.selectByConversation(conversation.getId()))
                {
                    if (part.getMemberId().equals(viewer)) continue;
                    cp.put("id", part.getMemberId());
                    List<Map<String, Object>> profiles = db.queryForList("select name,nickname,avatar from ums_member where id=?", part.getMemberId());
                    if (!profiles.isEmpty())
                    {
                        Map<String, Object> profile = profiles.get(0);
                        cp.put("nickname", profile.get("nickname") != null ? profile.get("nickname") : profile.get("name"));
                        cp.put("avatar", profile.get("avatar"));
                    }
                    break;
                }
            }
            row.put("counterparty", cp);
            result.add(row);
        }
        result.sort((left, right) -> {
            ImMessage a = latestByConversation.get(left.get("conversationId"));
            ImMessage b = latestByConversation.get(right.get("conversationId"));
            String ao = a == null || a.getVisibleOrderAt() == null ? "" : a.getVisibleOrderAt();
            String bo = b == null || b.getVisibleOrderAt() == null ? "" : b.getVisibleOrderAt();
            int order = bo.compareTo(ao);
            return order != 0 ? order : Long.compare(b == null ? 0L : b.getId(), a == null ? 0L : a.getId());
        });
        return result;
    }

    private void conversation(long id, int type, int status, int unread, int muted, Long other)
    {
        db.update("insert into im_conversation(id,type,biz_key,status,last_msg_text,last_msg_at) values(?,?,?,?,?,?)",
                id, type, "fixture-" + id, status, "stale secret summary", "2026-10-23 08:00:" + String.format("%02d", id % 60));
        db.update("insert into im_conversation_member(conversation_id,member_id,unread_count,muted) values(?,1,?,?)", id, unread, muted);
        if (other != null) db.update("insert into im_conversation_member(conversation_id,member_id) values(?,?)", id, other);
    }

    private void message(long id, long conversation, long sender, int type, int status, int check,
                         String createdAt, String visibleAt, String content)
    {
        db.update("insert into im_message(id,conversation_id,sender_id,msg_type,status,check_status,create_time,visible_at,content) values(?,?,?,?,?,?,?,?,?)",
                id, conversation, sender, type, status, check, "2026-10-23 " + createdAt,
                visibleAt == null ? null : "2026-10-23 " + visibleAt, content);
    }

    private String required(String key)
    {
        String value = System.getenv(key);
        assertNotNull(value, "缺少显式测试配置: " + key);
        return value;
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    public static final class StatementCounter implements Interceptor
    {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable
        {
            count.incrementAndGet();
            return invocation.proceed();
        }
    }
}
