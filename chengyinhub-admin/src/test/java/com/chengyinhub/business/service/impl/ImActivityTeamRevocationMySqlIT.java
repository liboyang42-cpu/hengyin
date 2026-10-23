package com.chengyinhub.business.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 点名执行、缺连接即失败。真实队伍退出/资格、IM 服务/事务/SQL；只有外部边界使用 mock。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class ImActivityTeamRevocationMySqlIT
{
    private static final long TEAM = 31L;
    private static final long CONVERSATION = 131L;
    private static final long LEADER = 7L;
    private static final long MEMBER = 8L;
    private static final String TITLE = "private-team-title-canary";
    private static final String CANARY = "private-team-message-canary";
    private static final String ORIGINAL_CONTENT = "original-member-message";
    private static final String ORIGINAL_CLIENT_ID = "team_original_00000001";
    private static final List<String> TABLES = List.of("play_team", "play_team_member", "im_conversation",
            "im_conversation_member", "im_message", "im_block", "ums_member");

    private JdbcTemplate admin;
    private JdbcTemplate db;
    private DataSource source;
    private String schema;
    private boolean created;
    private IImService im;
    private IPlayTeamService teams;
    private IWxContentSecurityService safety;
    private IWxContentCallbackInboxService inbox;
    private IAuditTaskService audit;
    private INotifyService notify;
    private IUmsMemberService profiles;
    private ImMessage original;

    @BeforeAll
    void setup() throws Exception
    {
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port > 0 && port <= 65535);
        String user = required("CHENGYIN_MYBATIS_IT_USER");
        String password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        assertNotNull(password, "必须显式提供 CHENGYIN_MYBATIS_IT_PASSWORD（允许空值）");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_im_revoke_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        created = true;
        source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        new ResourceDatabasePopulator(
                new FileSystemResource(sql.resolve("migration_im.sql")),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql")),
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql")),
                new FileSystemResource(sql.resolve("migration_20260820_play_team_core.sql")),
                new FileSystemResource(sql.resolve("migration_20260822_play_team_invite_code.sql")),
                new FileSystemResource(sql.resolve("migration_20260915_play_team_public_apply.sql"))).execute(source);
        db.execute("create table ums_member(id bigint primary key,name varchar(64),nickname varchar(64),avatar varchar(255))");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImBlockMapper.xml"),
                new ClassPathResource("mapper/business/PlayTeamMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        PlayTeamMapper teamMapper = session.getMapper(PlayTeamMapper.class);
        ImBlockMapper blockMapper = session.getMapper(ImBlockMapper.class);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        safety = mock(IWxContentSecurityService.class);
        inbox = mock(IWxContentCallbackInboxService.class);
        audit = mock(IAuditTaskService.class);
        notify = mock(INotifyService.class);
        profiles = mock(IUmsMemberService.class);

        ImServiceImpl imTarget = new ImServiceImpl();
        ReflectionTestUtils.setField(imTarget, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(imTarget, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(imTarget, "messageMapper", session.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(imTarget, "blockMapper", blockMapper);
        ReflectionTestUtils.setField(imTarget, "playTeamMapper", teamMapper);
        ReflectionTestUtils.setField(imTarget, "umsMemberService", profiles);
        ReflectionTestUtils.setField(imTarget, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(imTarget, "wxContentCallbackInboxService", inbox);
        ReflectionTestUtils.setField(imTarget, "auditTaskService", audit);
        im = (IImService) transactional(imTarget, transactions);

        PlayTeamServiceImpl teamTarget = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(teamTarget, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(teamTarget, "blockMapper", blockMapper);
        ReflectionTestUtils.setField(teamTarget, "imService", im);
        ReflectionTestUtils.setField(teamTarget, "notifyService", notify);
        ReflectionTestUtils.setField(teamTarget, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(teamTarget, "umsMemberService", profiles);
        ReflectionTestUtils.setField(teamTarget, "auditTaskService", audit);
        teams = (IPlayTeamService) transactional(teamTarget, transactions);
        assertTrue(AopUtils.isAopProxy(im));
        assertTrue(AopUtils.isAopProxy(teams));
    }

    @BeforeEach
    void seed()
    {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        for (String table : TABLES) db.update("delete from " + table);
        reset(safety, inbox, audit, notify, profiles);
        db.update("insert into ums_member(id,name,nickname,avatar) values(7,'leader','leader',null),(8,'member','member',null)");
        // 现有活动队伍，不调用建队/报名/退款；owner_type=2 是生产 create 接受的活动类型。
        db.update("insert into play_team(id,owner_type,owner_id,leader_member_id,invite_code,title,join_mode,"
                + "max_members,joined_count,status,create_time,update_time) values(31,2,9,7,?, ?,2,4,2,0,now(),now())",
                "a".repeat(32), TITLE);
        db.update("insert into play_team_member(team_id,member_id,role,status,create_time,update_time) "
                + "values(31,7,1,1,now(),now()),(31,8,0,1,now(),now())");
        db.update("insert into im_conversation(id,type,biz_key,status) values(131,4,'team_31',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id,role,unread_count) values(131,7,1,0),(131,8,0,2)");
        db.update("insert into im_message(id,conversation_id,sender_id,msg_type,content,status,check_status,create_time,visible_at) "
                + "values(10,131,0,1,?,0,1,'2026-01-01 10:00:00','2026-01-01 10:00:00'),"
                + "(11,131,0,1,?,0,1,'2026-01-01 10:00:01','2026-01-01 10:00:01')", CANARY, CANARY);
    }

    @AfterAll
    void cleanup()
    {
        if (created)
        {
            admin.execute("drop database " + schema);
            assertEquals(0, admin.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name=?", Integer.class, schema));
            created = false;
        }
    }

    @Test
    void committedRealQuitRevokesHistoryIncrementReceiptSendReadMarksAndList()
    {
        proveAccessThenQuit();
        assertDeniedWithoutEffects(false);
    }

    @Test
    void staleVisibleImIndexCannotRestoreRevokedRealTeamQualification()
    {
        proveAccessThenQuit();
        // 仅独占测试库对抗夹具：恢复旧 IM 索引，绝不恢复真实队伍成员资格。
        assertEquals(1, db.update("update im_conversation_member set deleted=0 where conversation_id=131 and member_id=8"));
        assertEquals(2, independent().queryForObject(
                "select status from play_team_member where team_id=31 and member_id=8", Integer.class));
        assertDeniedWithoutEffects(true);
    }

    private void proveAccessThenQuit()
    {
        assertEquals(CONVERSATION, im.getOrCreateTeamGroup(TEAM, MEMBER));
        im.requireActiveGroupMembership(MEMBER, CONVERSATION);
        assertTrue(im.isActiveGroupMember(MEMBER, CONVERSATION));
        List<Map<String, Object>> listed = im.listConversations(MEMBER);
        assertEquals(1, listed.size());
        assertEquals(TITLE, ((Map<?, ?>) listed.get(0).get("counterparty")).get("nickname"));
        assertEquals(CANARY, listed.get(0).get("lastMsgText"));
        assertEquals(List.of(10L, 11L), ids(im.listMessages(MEMBER, CONVERSATION, null, 20)));
        assertEquals(List.of(11L), ids(im.listMessages(MEMBER, CONVERSATION, null, 20, 10L)));
        // 正向发送生成真实请求摘要及收据；原标识重放只返回原行。
        original = im.send(MEMBER, CONVERSATION, null, ImMessage.MSG_TEXT, ORIGINAL_CONTENT, null, ORIGINAL_CLIENT_ID);
        assertNotNull(original.getId());
        assertNotNull(original.getRequestHash());
        assertEquals(original.getRequestHash(), independent().queryForObject(
                "select request_hash from im_message where id=?", String.class, original.getId()));
        assertEquals(original.getId(), im.readSentMessage(MEMBER, CONVERSATION, ORIGINAL_CLIENT_ID).getId());
        assertEquals(original.getId(), im.send(MEMBER, CONVERSATION, null,
                ImMessage.MSG_TEXT, ORIGINAL_CONTENT, null, ORIGINAL_CLIENT_ID).getId());
        assertEquals(3, independent().queryForObject("select count(*) from im_message", Integer.class));
        // send 已读到原收据；测试夹具重置后，验证两种真实已读入口确实可推进。
        db.update("update im_conversation_member set last_read_msg_id=0,unread_count=3 where conversation_id=131 and member_id=8");
        im.markRead(MEMBER, CONVERSATION, 10L);
        assertEquals(10L, independent().queryForObject(
                "select last_read_msg_id from im_conversation_member where conversation_id=131 and member_id=8", Long.class));
        im.markRead(MEMBER, CONVERSATION);
        assertEquals(original.getId(), independent().queryForObject(
                "select last_read_msg_id from im_conversation_member where conversation_id=131 and member_id=8", Long.class));
        // 保留可检测的未读及未推进截止点，避免拒绝后“清零”断言退化成空操作。
        db.update("update im_conversation_member set last_read_msg_id=10,unread_count=2 where conversation_id=131 and member_id=8");
        verify(safety, times(1)).checkText(ORIGINAL_CONTENT, null);
        clearInvocations(safety, inbox, audit, notify, profiles);

        teams.quit(TEAM, MEMBER); // 真正的事务代理调用；返回后退出已提交，不模拟资格撤销。
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        JdbcTemplate committed = independent();
        assertEquals(2, committed.queryForObject("select status from play_team_member where team_id=31 and member_id=8", Integer.class));
        assertEquals(1, committed.queryForObject("select joined_count from play_team where id=31", Integer.class));
        assertEquals(0, committed.queryForObject("select status from play_team where id=31", Integer.class));
        assertEquals(1, committed.queryForObject("select deleted from im_conversation_member where conversation_id=131 and member_id=8", Integer.class));
        assertTrue(im.isActiveGroupMember(LEADER, CONVERSATION), "仅普通成员退出，群仍向队长开放");
        assertEquals(1, im.listConversations(LEADER).size());
        verifyNoInteractions(safety, inbox, audit, notify, profiles);
    }

    private void assertDeniedWithoutEffects(boolean staleIndex)
    {
        Map<String, List<Map<String, Object>>> afterQuit = snapshot();
        unchanged(afterQuit, () -> assertTrue(im.listConversations(MEMBER).isEmpty(), "不返回标题、摘要或会话标识"));
        historyDenied(afterQuit, staleIndex, () -> im.listMessages(MEMBER, CONVERSATION, null, 20));
        historyDenied(afterQuit, staleIndex, () -> im.listMessages(MEMBER, CONVERSATION, 11L, 20, null));
        historyDenied(afterQuit, staleIndex, () -> im.listMessages(MEMBER, CONVERSATION, null, 20, 0L));
        historyDenied(afterQuit, staleIndex, () -> im.listMessages(MEMBER, CONVERSATION, null, 20, 10L));
        forbidden(afterQuit, () -> im.readSentMessage(MEMBER, CONVERSATION, ORIGINAL_CLIENT_ID));
        forbidden(afterQuit, () -> im.readSentMessage(MEMBER, CONVERSATION, "unknown_receipt_00000001"));
        forbidden(afterQuit, () -> im.send(MEMBER, CONVERSATION, null, ImMessage.MSG_TEXT, "new message", null, "team_new_000000000001"));
        forbidden(afterQuit, () -> im.send(MEMBER, CONVERSATION, null, ImMessage.MSG_TEXT, ORIGINAL_CONTENT, null, ORIGINAL_CLIENT_ID));
        forbidden(afterQuit, () -> im.send(MEMBER, CONVERSATION, null, ImMessage.MSG_TEXT, "changed content", null, ORIGINAL_CLIENT_ID));
        forbidden(afterQuit, () -> im.send(MEMBER, CONVERSATION, null, ImMessage.MSG_TEXT, "no client id", null));
        forbidden(afterQuit, () -> im.send(MEMBER, CONVERSATION, null, ImMessage.MSG_IMAGE, "private-image-reference", null, "team_image_0000000001"));
        forbidden(afterQuit, () -> im.markRead(MEMBER, CONVERSATION));
        forbidden(afterQuit, () -> im.markRead(MEMBER, CONVERSATION, original.getId()));
        forbidden(afterQuit, () -> im.requireActiveGroupMembership(MEMBER, CONVERSATION));
        unchanged(afterQuit, () -> assertFalse(im.isActiveGroupMember(MEMBER, CONVERSATION)));
        unchanged(afterQuit, () -> assertEquals("加入队伍后才能进群聊",
                assertThrows(RuntimeException.class, () -> im.getOrCreateTeamGroup(TEAM, MEMBER)).getMessage()));
        // 当前 inbox 聚合排除所有群；0 只验证现有行为，不冒充资格验证。
        unchanged(afterQuit, () -> assertEquals(0, im.unreadTotal(MEMBER)));
    }

    private void historyDenied(Map<String, List<Map<String, Object>>> expected, boolean staleIndex, Executable request)
    {
        if (!staleIndex)
        {
            forbidden(expected, request);
            return;
        }
        // 现有列表消息域拒绝是 RuntimeException；不擅自定义 HTTP 状态或改变生产异常。
        unchanged(expected, () -> assertEquals("已退出队伍，无权查看群消息",
                assertThrows(RuntimeException.class, request).getMessage()));
    }

    private void forbidden(Map<String, List<Map<String, Object>>> expected, Executable request)
    {
        unchanged(expected, () -> {
            ServiceException denied = assertThrows(ServiceException.class, request);
            assertEquals(403, denied.getCode(), "资格拒绝须早于原收据/内容冲突或审核");
            assertNull(denied.getData(), "拒绝不能携带旧收据或群消息 payload");
            assertNull(denied.getDetailMessage(), "现有资格拒绝不携带原群或收据明细");
            assertFalse(denied.getMessage().contains(TITLE));
            assertFalse(denied.getMessage().contains(CANARY));
            assertFalse(denied.getMessage().contains(ORIGINAL_CONTENT));
        });
    }

    private void unchanged(Map<String, List<Map<String, Object>>> expected, Executable assertion)
    {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "每个请求从退出提交后的新边界开始");
        assertDoesNotThrow(assertion);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(expected, snapshot(), "退出提交后，拒绝不能改变消息/收据/摘要/未读/已读/队伍资格");
        verifyNoInteractions(safety, inbox, audit, notify, profiles);
    }

    private Map<String, List<Map<String, Object>>> snapshot()
    {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        JdbcTemplate committed = independent();
        for (String table : TABLES) result.put(table, committed.queryForList("select * from " + table + " order by id"));
        return result;
    }

    private List<Long> ids(Map<String, Object> result)
    {
        return ((List<?>) result.get("list")).stream().map(row -> ((ImMessage) row).getId()).toList();
    }

    private JdbcTemplate independent()
    {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        return new JdbcTemplate(source);
    }

    private static Object transactional(Object target, DataSourceTransactionManager transactions)
    {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return proxy.getProxy();
    }

    private static String required(String name)
    {
        String value = System.getenv(name);
        assertNotNull(value, "必须显式提供 " + name);
        assertFalse(value.isBlank(), "必须显式提供非空 " + name);
        return value;
    }
}
