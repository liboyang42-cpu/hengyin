package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dedicated VPS only: explicit credentials, real IM mappers and transaction proxy. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_IM_LEGACY_LOCK_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class ImLegacyReadLockMySqlIT {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private boolean schemaCreated;
    private ImServiceImpl target;
    private IImService service;
    private ImConversationMapper conversations;
    private ImConversationMemberMapper members;
    private ImMessageMapper messages;
    private DataSourceTransactionManager manager;

    private String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "explicit test environment required: " + name);
        return value;
    }

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(env("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port > 0 && port <= 65535);
        String user = env("CHENGYIN_MYBATIS_IT_USER");
        assertFalse(user.trim().isEmpty());
        String password = env("CHENGYIN_MYBATIS_IT_PASSWORD");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_im_legacy_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        schemaCreated = true;
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql").toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        conversations = session.getMapper(ImConversationMapper.class);
        members = session.getMapper(ImConversationMemberMapper.class);
        messages = session.getMapper(ImMessageMapper.class);
        manager = new DataSourceTransactionManager(source);
        target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", conversations);
        ReflectionTestUtils.setField(target, "memberMapper", members);
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        ReflectionTestUtils.setField(target, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (IImService) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        ReflectionTestUtils.setField(target, "conversationMapper", conversations);
        ReflectionTestUtils.setField(target, "memberMapper", members);
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        db.update("insert into im_conversation(id,type,biz_key,status) values(100,1,'1_2',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id) values(100,1),(100,2)");
    }

    @AfterAll
    void cleanup() {
        if (schemaCreated && admin != null && schema != null) admin.execute("drop database if exists " + schema);
    }

    private void clubFixture() {
        db.update("update im_conversation set type=4,biz_key='club_5' where id=100");
        Club club = new Club(); club.setId(5L); club.setMemberId(1L); club.setStatus(1L); club.setDelFlag(0);
        ClubMapper clubs = mock(ClubMapper.class);
        when(clubs.selectClubById(5L)).thenReturn(club);
        when(clubs.selectClubByIdForUpdate(5L)).thenReturn(club);
        ClubMember member = new ClubMember(); member.setId(52L); member.setMemberId(2L); member.setClubId(5L); member.setStatus(1); member.setDelFlag(0); member.setRole(0);
        ClubMemberMapper clubMembers = mock(ClubMemberMapper.class);
        when(clubMembers.selectByClubAndMember(5L, 2L)).thenReturn(member);
        when(clubMembers.selectClubMemberList(any())).thenReturn(Collections.singletonList(member));
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "clubMemberMapper", clubMembers);
        ReflectionTestUtils.setField(target, "clubParticipationGuard", mock(IClubParticipationGuard.class));
    }

    private TransactionTemplate rr() {
        TransactionTemplate result = new TransactionTemplate(manager);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return result;
    }

    private int unread(Long conversation, Long member) {
        return db.queryForObject("select unread_count from im_conversation_member where conversation_id=? and member_id=?", Integer.class, conversation, member);
    }

    private long readTo(Long conversation, Long member) {
        return db.queryForObject("select last_read_msg_id from im_conversation_member where conversation_id=? and member_id=?", Long.class, conversation, member);
    }

    /** Bypass Spring's thread-bound RR connection for independent committed-state evidence. */
    private long[] committedMemberState() {
        try (java.sql.Connection connection = db.getDataSource().getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "select m.unread_count,m.last_read_msg_id,m.deleted,c.status "
                     + "from im_conversation_member m join im_conversation c on c.id=m.conversation_id "
                     + "where m.conversation_id=100 and m.member_id=2");
             java.sql.ResultSet row = statement.executeQuery()) {
            assertTrue(row.next(), "member fixture must exist");
            long[] state = { row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4) };
            assertFalse(row.next(), "member fixture must be unique");
            return state;
        } catch (java.sql.SQLException e) {
            throw new AssertionError("independent committed member read failed", e);
        }
    }

    private <T> T await(Future<T> future) {
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError("independent transaction did not complete", e); }
    }

    @Test
    void closedOrDepartedGroupCannotClearUnreadAfterEarlierSnapshot() {
        for (boolean close : new boolean[] { false, true }) {
            seed(); clubFixture();
            service.send(1L, 100L, null, 1, "before", null);
            assertArrayEquals(new long[] { 1, 0, 0, 0 }, committedMemberState(),
                    "send must establish one unread message before removal");
            AtomicReference<long[]> committedRemoval = new AtomicReference<>();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                assertThrows(ServiceException.class, () -> rr().execute(status -> {
                    assertEquals(0, db.queryForObject("select status from im_conversation where id=100", Integer.class));
                    assertEquals(0, db.queryForObject("select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
                    committedRemoval.set(await(worker.submit(() -> {
                        if (close) service.closeClubGroup(5L); else service.leaveClubGroup(5L, 2L);
                        long[] committed = committedMemberState();
                        assertArrayEquals(new long[] { close ? 0 : 1, 0, 1, close ? 1 : 0 }, committed,
                                "close clears unread; leave only marks the member deleted");
                        return committed;
                    })));
                    service.markRead(2L, 100L);
                    return null;
                }));
                assertNotNull(committedRemoval.get(), "removal must commit before rejected read");
                assertArrayEquals(committedRemoval.get(), committedMemberState(),
                        "rejected read must preserve every committed removal field");
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void legacyReadSeesLaterCommitAndNeverRegressesWhenLatestBecomesHidden() {
        ImMessage first = service.send(1L, 100L, null, 1, "first", null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Long later = rr().execute(status -> {
                assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class));
                Long id = await(worker.submit(() -> service.send(1L, 100L, null, 1, "second", null).getId()));
                assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class), "RR snapshot remains old");
                service.markRead(2L, 100L);
                return id;
            });
            assertTrue(later > first.getId());
            assertEquals(later.longValue(), readTo(100L, 2L));
            assertEquals(0, unread(100L, 2L));
            messages.updateStatus(later, ImMessage.STATUS_BLOCKED);
            service.markRead(2L, 100L);
            assertEquals(later.longValue(), readTo(100L, 2L));
            service.send(1L, 100L, null, 1, "third", null);
            assertEquals(1, unread(100L, 2L));
        } finally { worker.shutdownNow(); }
    }

    @Test
    void groupFanoutPreservesDepartureAndExplicitRejoinRestoresNormalRead() {
        clubFixture();
        service.leaveClubGroup(5L, 2L);
        service.send(1L, 100L, null, 1, "after leave", null);
        assertEquals(1, db.queryForObject("select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
        assertEquals(0, unread(100L, 2L));
        assertThrows(ServiceException.class, () -> service.markRead(2L, 100L));
        assertEquals(Long.valueOf(100L), service.getOrCreateClubGroup(5L, 2L));
        service.send(1L, 100L, null, 1, "after rejoin", null);
        assertEquals(1, unread(100L, 2L));
        service.markRead(2L, 100L);
        assertEquals(0, unread(100L, 2L));
    }

    @Test
    void senderDepartureAfterOrdinarySnapshotRejectsButPreviouslyCompletedSendRemains() throws Exception {
        service.send(1L, 100L, null, 1, "completed", null);
        CountDownLatch snapshot = new CountDownLatch(1), resume = new CountDownLatch(1);
        ImConversationMemberMapper observed = mock(ImConversationMemberMapper.class, delegatesTo(members));
        doAnswer(call -> {
            ImConversationMember old = members.selectByConvAndMember(100L, 1L);
            snapshot.countDown();
            assertTrue(resume.await(10, TimeUnit.SECONDS));
            return old;
        }).when(observed).selectByConvAndMember(100L, 1L);
        ReflectionTestUtils.setField(target, "memberMapper", observed);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ServiceException> failed = worker.submit(() -> assertThrows(ServiceException.class,
                    () -> service.send(1L, 100L, null, 1, "late", null)));
            assertTrue(snapshot.await(10, TimeUnit.SECONDS));
            members.markDeleted(100L, 1L);
            resume.countDown();
            assertEquals(Integer.valueOf(403), await(failed).getCode());
            assertEquals(1, db.queryForObject("select count(*) from im_message", Integer.class));
        } finally { resume.countDown(); worker.shutdownNow(); }
    }

    @Test
    void systemAndAdminWaitAtConversationBeforeMembershipOrMessageMutation() {
        for (boolean adminSend : new boolean[] { false, true }) {
            seed();
            if (adminSend) service.sendFromAdmin(2L, 1, "before", null); else service.sendSystem(2L, 1, "before", null);
            Long id = db.queryForObject("select id from im_conversation where biz_key=?", Long.class, (adminSend ? "cs_" : "sys_") + "2");
            CountDownLatch reachedLock = new CountDownLatch(1), reachedMember = new CountDownLatch(1);
            ImConversationMapper observedConversations = mock(ImConversationMapper.class, delegatesTo(conversations));
            doAnswer(call -> { reachedLock.countDown(); return conversations.selectImConversationByIdForUpdate(id); })
                    .when(observedConversations).selectImConversationByIdForUpdate(id);
            ImConversationMemberMapper observedMembers = mock(ImConversationMemberMapper.class, delegatesTo(members));
            doAnswer(call -> { reachedMember.countDown(); return members.selectByConvAndMember(id, 2L); })
                    .when(observedMembers).selectByConvAndMember(id, 2L);
            ReflectionTestUtils.setField(target, "conversationMapper", observedConversations);
            ReflectionTestUtils.setField(target, "memberMapper", observedMembers);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            AtomicReference<Future<Long>> sent = new AtomicReference<>();
            try {
                rr().execute(status -> {
                    db.queryForObject("select id from im_conversation where id=? for update", Long.class, id);
                    sent.set(worker.submit(() -> adminSend ? service.sendFromAdmin(2L, 1, "later", null) : service.sendSystem(2L, 1, "later", null)));
                    try {
                        assertTrue(reachedLock.await(5, TimeUnit.SECONDS));
                        assertFalse(reachedMember.await(200, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    service.markRead(2L, id);
                    return null;
                });
                assertTrue(await(sent.get()) > readTo(id, 2L));
                assertEquals(1, unread(id, 2L));
            } finally { worker.shutdownNow(); }
        }
    }
}
