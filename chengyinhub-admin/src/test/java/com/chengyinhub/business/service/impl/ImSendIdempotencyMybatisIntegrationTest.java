package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImMessage;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 本地独立库，实际 Service/事务/三个 IM Mapper，内容审核外部服务不出网。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_IM_SEND_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImSendIdempotencyMybatisIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private IImService service;
    private ImServiceImpl target;
    private IWxContentSecurityService safety;
    private IWxContentCallbackInboxService inbox;
    private ImMessageMapper messages;

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PORT", "3306"));
        assertTrue(port > 0 && port <= 65535);
        String user = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_USER", "root");
        String password = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PASSWORD", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        String version = admin.queryForObject("select version()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,实际 " + version);
        schema = "cy_im_retry_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!sql.toFile().isDirectory()) sql = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql").toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(ImMessageMapper.class);
        safety = mock(IWxContentSecurityService.class);
        inbox = mock(IWxContentCallbackInboxService.class);
        target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(target, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(target, "messageMapper", messages);
        ReflectionTestUtils.setField(target, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "wxContentSecurityService", safety);
        ReflectionTestUtils.setField(target, "wxContentCallbackInboxService", inbox);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (IImService) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        db.update("insert into im_conversation(id,type,biz_key,status) values(100,1,'1_2',0),(101,1,'1_3',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id) values(100,1),(100,2),(101,1),(101,3)");
        reset(safety, inbox);
    }

    @AfterAll
    void cleanup() {
        if (schema != null && admin != null) {
            admin.execute("drop database " + schema);
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, schema));
            System.out.println("scratch cleanup verified " + schema);
        }
    }

    @Test
    void pendingImageIsVisibleOnlyToItsSenderUntilPassingCallback() {
        ImMessage image = new ImMessage();
        image.setConversationId(100L); image.setSenderId(1L); image.setReceiverId(2L);
        image.setMsgType(ImMessage.MSG_IMAGE); image.setContent("https://cdn.chengyinhub.com/profile/upload/image.jpg");
        image.setStatus(0); image.setCheckStatus(0); image.setTraceId("fixture-private-image");
        messages.insertImMessage(image);
        assertEquals(1, messages.selectVisiblePage(100L, 1L, null, 20).size());
        assertEquals(0, messages.selectVisiblePage(100L, 2L, null, 20).size());
        assertEquals(0, unread(2));
        service.markRead(2L, 100L);
        assertTrue(messages.applyMediaCheckResult(image.getId(), "fixture-private-image", false) > 0);
        assertEquals(1, unread(2));
        assertEquals(0, messages.applyMediaCheckResult(image.getId(), "fixture-private-image", false));
        assertEquals(1, unread(2));
        assertEquals(1, messages.selectVisiblePage(100L, 2L, null, 20).size());
        image.setId(null); image.setTraceId("fixture-blocked-image");
        messages.insertImMessage(image);
        assertEquals(1, messages.applyMediaCheckResult(image.getId(), "fixture-blocked-image", true));
        assertEquals(1, messages.selectVisiblePage(100L, 1L, null, 20).size());
        assertEquals(1, messages.selectVisiblePage(100L, 2L, null, 20).size());
    }

    @Test
    void concurrentRetriesCreateOneMessageAndOneUnreadButNewSendStillCreatesAnother() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> send = () -> { start.await(); return service.send(1L, 100L, null, 1, "hello", null, "send_concurrent_0001").getId(); };
        try {
            Future<Long> first = workers.submit(send);
            Future<Long> second = workers.submit(send);
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, messages.selectPage(100L, null, 30).size());
            assertEquals(1, unread(2));
            verify(safety).checkText(eq("hello"), any());
            service.send(1L, 100L, null, 1, "hello", null, "send_intentional_0002");
            assertEquals(2, messages.selectPage(100L, null, 30).size());
            assertEquals(2, unread(2));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void retryIdentityCannotChangePayloadOrCrossSenderAndLegacySendsRemainSeparate() {
        String key = "send_identity_0001";
        service.send(1L, 100L, null, 1, "original", null, key);
        assertThrows(ServiceException.class, () -> service.send(1L, 100L, null, 1, "changed", null, key));
        assertEquals(1, unread(2));
        service.send(2L, 100L, null, 1, "other sender", null, key);
        service.send(1L, 101L, null, 1, "other conversation", null, key);
        service.send(1L, 100L, null, 1, "legacy", null);
        service.send(1L, 100L, null, 1, "legacy", null);
        assertEquals(4, messages.selectPage(100L, null, 30).size());
        assertEquals(1, messages.selectPage(101L, null, 30).size());
    }

    @Test
    void failedImageRegistrationRollsBackAndRetryCanSucceedOnlyOnce() {
        when(safety.checkMediaAsync(anyString(), any())).thenReturn("image-check-1");
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(false, true);
        String key = "send_image_0000001";
        assertThrows(RuntimeException.class, () -> service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null, key));
        assertEquals(0, messages.selectPage(100L, null, 30).size());
        assertEquals(0, unread(2));
        ImMessage successful = service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null, key);
        assertEquals(successful.getId(), service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null, key).getId());
        assertEquals(0, unread(2), "pending media must not create recipient unread");
        assertTrue(messages.applyMediaCheckResult(successful.getId(), "image-check-1", false) > 0);
        assertEquals(1, unread(2));
        assertEquals(0, messages.applyMediaCheckResult(successful.getId(), "image-check-1", false));
        assertEquals(1, unread(2));
        verify(inbox, times(2)).register(anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    void lateApprovalAfterReadingLaterTextReturnsAsNewestAndKeepsTuplePagination() {
        when(safety.checkMediaAsync(anyString(), any())).thenReturn("late-image-trace");
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
        ImMessage image = service.send(1L, 100L, null, 2, "https://cdn/late.jpg", null);
        for (int i = 0; i < 36; i++) service.send(1L, 100L, null, 1, "later " + i, null);
        service.markRead(2L, 100L);
        assertEquals(0, unread(2));
        assertTrue(db.queryForObject("select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class) > image.getId());
        assertTrue(messages.applyMediaCheckResult(image.getId(), "late-image-trace", false) > 0);
        assertEquals(1, unread(2));
        assertEquals("[图片]", service.listConversations(2L).get(0).get("lastMsgText"));
        java.util.List<ImMessage> newest = messages.selectVisiblePage(100L, 2L, null, 20);
        assertEquals(image.getId(), newest.get(0).getId(), "approval moves an old ID to the visible first page");
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Long cursor = null;
        do {
            java.util.List<ImMessage> page = messages.selectVisiblePage(100L, 2L, cursor, 7);
            if (page.isEmpty()) break;
            for (ImMessage row : page) assertTrue(seen.add(row.getId()), "tuple cursor must not repeat messages");
            cursor = page.get(page.size() - 1).getId();
        } while (true);
        assertEquals(37, seen.size());
        service.markRead(2L, 100L);
        assertEquals(0, unread(2));
        assertEquals(0, messages.applyMediaCheckResult(image.getId(), "late-image-trace", false));
        assertEquals(0, unread(2));
    }

    @Test
    void equalVisibilityMicrosecondsUseMessageIdAsReadAndPageTieBreak() {
        ImMessage first = service.send(1L, 100L, null, 1, "first", null);
        ImMessage second = service.send(1L, 100L, null, 1, "second", null);
        db.update("update im_message set visible_at='2026-01-01 00:00:00.123456' where conversation_id=100");
        assertEquals(second.getId(), messages.selectVisiblePage(100L, 2L, null, 1).get(0).getId());
        assertEquals(first.getId(), messages.selectVisiblePage(100L, 2L, second.getId(), 1).get(0).getId());
        service.markRead(2L, 100L);
        assertEquals(0, unread(2));
        ImMessage third = service.send(1L, 100L, null, 1, "third", null);
        db.update("update im_message set visible_at='2026-01-01 00:00:00.123456' where id=?", third.getId());
        assertEquals(1, unread(2));
        assertEquals(third.getId(), messages.selectVisiblePage(100L, 2L, null, 1).get(0).getId());
        service.markRead(2L, 100L);
        assertEquals(0, unread(2));
    }

    @Test
    void simultaneousApprovalAndMarkReadPreserveActualUnreadFact() throws Exception {
        for (int round = 0; round < 8; round++) {
            seed();
            when(safety.checkMediaAsync(anyString(), any())).thenReturn("race-image-trace");
            when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
            ImMessage image = service.send(1L, 100L, null, 2, "https://cdn/race.jpg", null);
            service.send(1L, 100L, null, 1, "later", null);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<?> read = workers.submit(() -> { awaitStart(start); service.markRead(2L, 100L); });
                Future<?> approve = workers.submit(() -> { awaitStart(start); messages.applyMediaCheckResult(image.getId(), "race-image-trace", false); });
                start.countDown();
                read.get(10, TimeUnit.SECONDS); approve.get(10, TimeUnit.SECONDS);
                Integer actual = db.queryForObject("select count(*) from im_message msg join im_conversation_member m on m.conversation_id=msg.conversation_id and m.member_id=2 where msg.conversation_id=100 and msg.sender_id!=2 and msg.status!=2 and (msg.msg_type!=2 or msg.check_status=1) and (coalesce(msg.visible_at,msg.create_time)>m.last_read_visible_at or (coalesce(msg.visible_at,msg.create_time)=m.last_read_visible_at and msg.id>m.last_read_msg_id))", Integer.class);
                assertEquals(actual, unread(2), "badge must equal the visible rows after the server read cutoff");
                assertEquals(0, messages.applyMediaCheckResult(image.getId(), "race-image-trace", false));
                assertEquals(actual, unread(2));
            } finally { workers.shutdownNow(); }
        }
    }

    private static void awaitStart(CountDownLatch start) {
        try { start.await(); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    @Test
    void hiddenPrivateConversationReappearsOnlyAfterImageApproval() {
        db.update("update im_conversation_member set deleted=1 where conversation_id=100 and member_id=2");
        when(safety.checkMediaAsync(anyString(), any())).thenReturn("hidden-image-trace");
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
        ImMessage image = service.send(1L, 100L, null, 2, "https://cdn/hidden.jpg", null);
        assertTrue(service.listConversations(2L).isEmpty());
        assertEquals(0, unread(2));
        assertTrue(messages.applyMediaCheckResult(image.getId(), "hidden-image-trace", false) > 0);
        assertEquals(0, db.queryForObject("select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class),
                "passing callback must restore the hidden private recipient in the actual database");
        assertEquals(1, unread(2));
        assertEquals(1, service.listConversations(2L).size());
        assertEquals("[图片]", service.listConversations(2L).get(0).get("lastMsgText"));
        assertEquals(1, unread(2));
    }

    private int unread(long member) {
        return db.queryForObject("select unread_count from im_conversation_member where conversation_id=100 and member_id=?", Integer.class, member);
    }

    @Test
    void conversationOrderFollowsViewersLatestVisibleMessageWithoutChangingSendTime() {
        db.update("insert into im_conversation(id,type,biz_key,status) values(102,1,'2_3',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id) values(102,2),(102,3)");
        when(safety.checkMediaAsync(anyString(), any())).thenReturn("conversation-order-image");
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
        ImMessage image = service.send(1L, 100L, null, 2, "https://cdn/order.jpg", null);
        db.update("update im_message set create_time=date_sub(sysdate(6), interval 10 minute), visible_at=null where id=?", image.getId());
        db.update("update im_conversation set last_msg_at=date_sub(sysdate(), interval 10 minute) where id=100");
        java.util.Date originalSentAt = messages.selectImMessageById(image.getId()).getCreateTime();
        ImMessage text = service.send(3L, 102L, null, 1, "other newer text", null);

        java.util.List<java.util.Map<String, Object>> before = service.listConversations(2L);
        assertEquals(102L, before.get(0).get("conversationId"));
        assertEquals("other newer text", before.get(0).get("lastMsgText"));
        assertEquals(100L, before.get(1).get("conversationId"));
        assertEquals("", before.get(1).get("lastMsgText"), "pending media cannot influence the recipient preview");

        assertTrue(messages.applyMediaCheckResult(image.getId(), "conversation-order-image", false) > 0);
        java.util.List<java.util.Map<String, Object>> after = service.listConversations(2L);
        assertEquals(100L, after.get(0).get("conversationId"));
        assertEquals("[图片]", after.get(0).get("lastMsgText"));
        assertEquals(originalSentAt, after.get(0).get("lastMsgAt"), "the API still reports original send time");
        assertEquals(102L, after.get(1).get("conversationId"));
        assertEquals(0, messages.applyMediaCheckResult(image.getId(), "conversation-order-image", false));
        assertEquals(1, unread(2), "duplicate approval must not increment unread again");

        db.update("update im_message set visible_at='2026-10-12 08:00:00.123456' where id in (?,?)", image.getId(), text.getId());
        assertEquals(102L, service.listConversations(2L).get(0).get("conversationId"),
                "identical visibility microseconds use the actual message ID as the tie breaker");
    }

    @Test
    void locationCardRetryReturnsSanitizedOriginalWithoutRepeatingReview() {
        String card = "{\"cardType\":\"location\",\"name\":\"meeting\",\"lat\":31,\"lng\":121,\"admin\":true}";
        String key = "send_location_0001";
        ImMessage first = service.send(1L, 100L, null, 3, "untrusted title", card, key);
        ImMessage retry = service.send(1L, 100L, null, 3, "untrusted title", card, key);
        assertEquals(first.getId(), retry.getId());
        assertEquals("[位置] meeting", retry.getContent());
        assertFalse(retry.getExtraJson().contains("admin"));
        assertEquals(3, retry.getMsgType());
        assertEquals(1, unread(2));
        verify(safety).checkText(eq("meeting "), any());
        assertThrows(ServiceException.class, () -> service.send(1L, 100L, null, 3,
                "untrusted title", card.replace("meeting", "changed"), key));
        assertEquals(1, messages.selectPage(100L, null, 30).size());
    }

    @Test
    void removedMembershipCannotRetrievePriorSuccessfulSendByRetryKey() {
        String key = "send_revoked_00001";
        service.send(1L, 100L, null, 1, "hello", null, key);
        db.update("delete from im_conversation_member where conversation_id=100 and member_id=1");
        assertThrows(RuntimeException.class, () -> service.send(1L, 100L, null, 1, "hello", null, key));
        assertEquals(1, messages.selectPage(100L, null, 30).size());
        assertEquals(1, unread(2));
    }

    @Test
<<<<<<< public
    void blockedLatestPreviewIsRedactedForBothMembersWithoutDestroyingAuditEvidence() {
        ImMessage message = service.send(1L, 100L, null, 1, "blocked original", null);
        messages.updateStatus(message.getId(), ImMessage.STATUS_BLOCKED);
        for (long member : new long[] {1L, 2L}) {
            assertEquals("", service.listConversations(member).get(0).get("lastMsgText"));
            assertTrue(((java.util.List<?>) service.listMessages(member, 100L, null, 30).get("list")).isEmpty());
        }
        assertEquals("blocked original", messages.selectImMessageById(message.getId()).getContent());
        assertEquals("blocked original", db.queryForObject(
                "select last_msg_text from im_conversation where id=100", String.class));
        messages.updateStatus(message.getId(), ImMessage.STATUS_NORMAL);
        assertEquals("blocked original", service.listConversations(2L).get(0).get("lastMsgText"));
    }

    @Test
    void blockingOlderMessageKeepsLatestNormalPreviewAndLaterNormalSendRecoversPreview() {
        ImMessage older = service.send(1L, 100L, null, 1, "older", null);
        ImMessage latest = service.send(2L, 100L, null, 1, "latest normal", null);
        messages.updateStatus(older.getId(), ImMessage.STATUS_BLOCKED);
        assertEquals("latest normal", service.listConversations(1L).get(0).get("lastMsgText"));
        messages.updateStatus(latest.getId(), ImMessage.STATUS_BLOCKED);
        assertEquals("", service.listConversations(1L).get(0).get("lastMsgText"));
        service.send(1L, 100L, null, 1, "later normal", null);
        assertEquals("later normal", service.listConversations(2L).get(0).get("lastMsgText"));
    }

    @Test
    void asyncRiskyMediaAlsoRedactsCachedPreviewWithoutMutatingOriginal() {
        when(safety.checkMediaAsync(anyString(), any())).thenReturn("preview-media-trace");
        when(inbox.register(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
        ImMessage image = service.send(1L, 100L, null, 2, "https://cdn/image.jpg", null);
        assertEquals(1, messages.applyMediaCheckResult(image.getId(), "preview-media-trace", true));
        assertEquals("", service.listConversations(2L).get(0).get("lastMsgText"));
        assertEquals("https://cdn/image.jpg", messages.selectImMessageById(image.getId()).getContent());
=======
    void closedGroupAfterEarlierSnapshotCannotReviveReadState() throws Exception {
        for (boolean legacyRead : new boolean[] { false, true }) {
            seed();
            ImMessage message = service.send(1L, 100L, null, 1, "before close", null, "send_closed_read_0001");
            db.update("update im_conversation set type=4,biz_key='club_5' where id=100");
            ClubMapper clubs = mock(ClubMapper.class);
            com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
            club.setId(5L);
            club.setMemberId(2L);
            club.setStatus(1L);
            club.setDelFlag(0);
            when(clubs.selectClubById(5L)).thenReturn(club);
            ReflectionTestUtils.setField(target, "clubMapper", clubs);
            assertLateReadRejectedAfterSnapshot(message.getId(), legacyRead, () -> service.closeClubGroup(5L));
            assertEquals(Integer.valueOf(1), db.queryForObject(
                    "select status from im_conversation where id=100", Integer.class));
            assertEquals(Integer.valueOf(1), db.queryForObject(
                    "select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
            assertEquals(Long.valueOf(0), db.queryForObject(
                    "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class));
            assertEquals(0, unread(2));
        }
    }

    @Test
    void deletedMemberAfterEarlierSnapshotCannotClearUnreadOrAdvanceRead() throws Exception {
        for (boolean legacyRead : new boolean[] { false, true }) {
            seed();
            ImMessage message = service.send(1L, 100L, null, 1, "before delete", null, "send_deleted_read_001");
            assertLateReadRejectedAfterSnapshot(message.getId(), legacyRead, () -> service.deleteConversation(2L, 100L));
            assertEquals(Integer.valueOf(1), db.queryForObject(
                    "select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
            assertEquals(Long.valueOf(0), db.queryForObject(
                    "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class));
            assertEquals(1, unread(2), "deleted membership must not let a late read clear unread");
        }
    }

    @Test
    void eachGroupLeavePathRejectsLateReadsAfterEarlierSnapshot() throws Exception {
        for (String kind : new String[] { "club", "hangout", "team" }) {
            for (boolean legacyRead : new boolean[] { false, true }) {
                seed();
                ImMessage message = service.send(1L, 100L, null, 1, "before leave", null, "send_leave_read_001");
                db.update("update im_conversation set type=4,biz_key=? where id=100", kind + "_5");
                assertLateReadRejectedAfterSnapshot(message.getId(), legacyRead, () -> {
                    if ("club".equals(kind)) service.leaveClubGroup(5L, 2L);
                    else if ("hangout".equals(kind)) service.leaveHangoutGroup(5L, 2L);
                    else service.leaveTeamGroup(5L, 2L);
                });
                assertEquals(Long.valueOf(0), db.queryForObject(
                        "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class));
                assertEquals(1, unread(2));
            }
        }
    }

    @Test
    void readAfterEarlierSnapshotUsesLatestCommittedMessageAndPreservesNewerUnread() throws Exception {
        for (boolean legacyRead : new boolean[] { false, true }) {
            seed();
            ImMessage first = service.send(1L, 100L, null, 1, "first", null, "send_read_snapshot_001");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            org.springframework.transaction.support.TransactionTemplate read =
                    new org.springframework.transaction.support.TransactionTemplate(
                            new DataSourceTransactionManager(db.getDataSource()));
            read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try {
                Long latestId = read.execute(status -> {
                    assertEquals(Long.valueOf(first.getId()), db.queryForObject(
                            "select max(id) from im_message where conversation_id=100", Long.class));
                    assertEquals(Integer.valueOf(1), db.queryForObject(
                            "select unread_count from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
                    ImMessage latest;
                    try {
                        latest = worker.submit(() -> service.send(1L, 100L, null, 1,
                                "later", null, "send_read_snapshot_002")).get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (legacyRead) service.markRead(2L, 100L);
                    else service.markRead(2L, 100L, first.getId());
                    return latest.getId();
                });
                assertEquals(legacyRead ? latestId : first.getId(), db.queryForObject(
                        "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class));
                assertEquals(legacyRead ? 0 : 1, unread(2));
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void explicitReadToSeesMessageCommittedAfterEarlierSnapshot() throws Exception {
        ImMessage first = service.send(1L, 100L, null, 1, "first", null, "read_current_new_001");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        org.springframework.transaction.support.TransactionTemplate read =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(db.getDataSource()));
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            Long consumedId = read.execute(status -> {
                assertEquals(Long.valueOf(first.getId()), db.queryForObject(
                        "select max(id) from im_message where conversation_id=100", Long.class));
                ImMessage latest;
                try {
                    latest = worker.submit(() -> service.send(1L, 100L, null, 1,
                            "committed later", null, "read_current_new_002")).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                service.markRead(2L, 100L, latest.getId());
                return latest.getId();
            });
            assertEquals(consumedId, db.queryForObject(
                    "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class));
            assertEquals(0, unread(2));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void explicitReadToRejectsBlockedMessageCommittedAfterEarlierSnapshot() throws Exception {
        ImMessage first = service.send(1L, 100L, null, 1, "first", null, "read_current_block_001");
        Long beforeRead = db.queryForObject(
                "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class);
        int beforeUnread = unread(2);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        org.springframework.transaction.support.TransactionTemplate read =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(db.getDataSource()));
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            ServiceException rejected = assertThrows(ServiceException.class, () -> read.execute(status -> {
                assertEquals(Integer.valueOf(ImMessage.STATUS_NORMAL), db.queryForObject(
                        "select status from im_message where id=?", Integer.class, first.getId()));
                try {
                    assertEquals(Integer.valueOf(1), worker.submit(
                            () -> messages.updateStatus(first.getId(), ImMessage.STATUS_BLOCKED))
                            .get(10, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                service.markRead(2L, 100L, first.getId());
                return null;
            }));
            assertEquals("已读位置不属于当前会话", rejected.getMessage());
            assertEquals(Integer.valueOf(ImMessage.STATUS_BLOCKED), db.queryForObject(
                    "select status from im_message where id=?", Integer.class, first.getId()));
            assertEquals(beforeRead, db.queryForObject(
                    "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=2", Long.class));
            assertEquals(beforeUnread, unread(2));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void groupFanoutDoesNotReactivateDepartedMembersButExplicitRejoinStillWorks() {
        db.update("update im_conversation set type=4,biz_key='club_5' where id=100");
        ClubMapper clubs = mock(ClubMapper.class);
        com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
        club.setId(5L); club.setMemberId(1L); club.setStatus(1L); club.setDelFlag(0);
        when(clubs.selectClubById(5L)).thenReturn(club);
        when(clubs.selectClubByIdForUpdate(5L)).thenReturn(club);
        ClubMemberMapper clubMembers = mock(ClubMemberMapper.class);
        com.chengyinhub.business.domain.ClubMember active = new com.chengyinhub.business.domain.ClubMember();
        active.setMemberId(2L);
        when(clubMembers.selectByClubAndMember(5L, 2L)).thenReturn(active);
        when(clubMembers.selectClubMemberList(any())).thenReturn(java.util.Collections.singletonList(active));
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "clubMemberMapper", clubMembers);
        ReflectionTestUtils.setField(target, "clubParticipationGuard", mock(IClubParticipationGuard.class));
        service.leaveClubGroup(5L, 2L);
        ImMessage sent = service.send(1L, 100L, null, 1, "after leave", null, "send_after_leave_001");
        assertEquals(Integer.valueOf(1), db.queryForObject(
                "select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
        assertEquals(0, unread(2));
        assertThrows(ServiceException.class, () -> service.markRead(2L, 100L, sent.getId()));
        assertEquals(Long.valueOf(100L), service.getOrCreateClubGroup(5L, 2L));
        assertEquals(Integer.valueOf(0), db.queryForObject(
                "select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
        ImMessage afterRejoin = service.send(1L, 100L, null, 1, "after rejoin", null, "send_after_rejoin_001");
        assertEquals(1, unread(2));
        service.markRead(2L, 100L, afterRejoin.getId());
        assertEquals(0, unread(2));
    }

    @Test
    void systemAndAdminSendWaitForReadLockBeforeInsertingOrRestoringMembership() throws Exception {
        for (boolean adminSend : new boolean[] { false, true }) {
            seed();
            if (adminSend) service.sendFromAdmin(2L, 1, "before", null);
            else service.sendSystem(2L, 1, "before", null);
            Long convId = db.queryForObject("select id from im_conversation where biz_key=?",
                    Long.class, (adminSend ? "cs_" : "sys_") + "2");
            ImMessageMapper observed = mock(ImMessageMapper.class, org.mockito.AdditionalAnswers.delegatesTo(messages));
            CountDownLatch inserting = new CountDownLatch(1);
            doAnswer(call -> {
                inserting.countDown();
                return messages.insertImMessage(call.getArgument(0));
            }).when(observed).insertImMessage(any());
            ReflectionTestUtils.setField(target, "messageMapper", observed);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            java.util.concurrent.atomic.AtomicReference<Future<Long>> sent = new java.util.concurrent.atomic.AtomicReference<>();
            org.springframework.transaction.support.TransactionTemplate read =
                    new org.springframework.transaction.support.TransactionTemplate(
                            new DataSourceTransactionManager(db.getDataSource()));
            try {
                read.execute(status -> {
                    db.queryForObject("select id from im_conversation where id=? for update", Long.class, convId);
                    CountDownLatch started = new CountDownLatch(1);
                    sent.set(worker.submit(() -> {
                        started.countDown();
                        return adminSend ? service.sendFromAdmin(2L, 1, "later", null)
                                : service.sendSystem(2L, 1, "later", null);
                    }));
                    try {
                        assertTrue(started.await(2, TimeUnit.SECONDS));
                        assertFalse(inserting.await(250, TimeUnit.MILLISECONDS), "writer must wait at conversation before message insert");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    service.markRead(2L, convId);
                    return null;
                });
                Long latest = sent.get().get(10, TimeUnit.SECONDS);
                assertNotNull(latest);
                assertEquals(Integer.valueOf(1), db.queryForObject(
                        "select unread_count from im_conversation_member where conversation_id=? and member_id=2", Integer.class, convId));
                assertTrue(latest > db.queryForObject(
                        "select last_read_msg_id from im_conversation_member where conversation_id=? and member_id=2", Long.class, convId));
            } finally {
                if (sent.get() != null) sent.get().get(10, TimeUnit.SECONDS);
                worker.shutdownNow();
                ReflectionTestUtils.setField(target, "messageMapper", messages);
            }
        }
    }

    @Test
    void senderDepartureWinsAgainstOldSnapshotButCompletedSendBeforeDepartureRemainsValid() throws Exception {
        db.update("update im_conversation set type=4,biz_key='hangout_5' where id=100");
        RoamHangoutMapper hangouts = mock(RoamHangoutMapper.class);
        com.chengyinhub.business.domain.RoamHangout hangout = new com.chengyinhub.business.domain.RoamHangout();
        hangout.setStatus(com.chengyinhub.business.domain.RoamHangout.STATUS_OPEN);
        when(hangouts.selectByIdForUpdate(5L)).thenReturn(hangout);
        ReflectionTestUtils.setField(target, "roamHangoutMapper", hangouts);
        // The real mapper is kept by the target; a delegate only opens a deterministic race window.
        final ImConversationMemberMapper delegate =
                (ImConversationMemberMapper) ReflectionTestUtils.getField(target, "memberMapper");
        ImConversationMemberMapper observed = mock(ImConversationMemberMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(delegate));
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch departureDone = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            com.chengyinhub.business.domain.ImConversationMember old =
                    delegate.selectByConvAndMember(call.getArgument(0), call.getArgument(1));
            if (once.compareAndSet(false, true)) {
                snapshotRead.countDown();
                assertTrue(departureDone.await(10, TimeUnit.SECONDS));
            }
            return old;
        }).when(observed).selectByConvAndMember(100L, 1L);
        ReflectionTestUtils.setField(target, "memberMapper", observed);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> late = worker.submit(() -> service.send(1L, 100L, null, 1, "late", null, "send_depart_race_001"));
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS));
            service.leaveHangoutGroup(5L, 1L);
            departureDone.countDown();
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> late.get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof ServiceException);
            assertEquals(Integer.valueOf(403), ((ServiceException) rejected.getCause()).getCode());
            assertEquals(Integer.valueOf(0), db.queryForObject(
                    "select count(*) from im_message where conversation_id=100", Integer.class));
            assertEquals(Long.valueOf(0), db.queryForObject(
                    "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=1", Long.class));
            ReflectionTestUtils.setField(target, "memberMapper", delegate);
            seed();
            ImMessage beforeLeave = service.send(1L, 100L, null, 1, "committed first", null, "send_depart_first_001");
            service.deleteConversation(1L, 100L);
            assertNotNull(messages.selectImMessageById(beforeLeave.getId()));
            assertEquals(Integer.valueOf(1), db.queryForObject(
                    "select deleted from im_conversation_member where conversation_id=100 and member_id=1", Integer.class));
            assertEquals(beforeLeave.getId(), db.queryForObject(
                    "select last_read_msg_id from im_conversation_member where conversation_id=100 and member_id=1", Long.class));
        } finally {
            departureDone.countDown();
            worker.shutdownNow();
            ReflectionTestUtils.setField(target, "memberMapper", delegate);
        }
    }

    private void assertLateReadRejectedAfterSnapshot(Long messageId, boolean legacyRead, Runnable revoke) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        org.springframework.transaction.support.TransactionTemplate read =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(db.getDataSource()));
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            read.execute(status -> {
                assertEquals(Integer.valueOf(0), db.queryForObject(
                        "select deleted from im_conversation_member where conversation_id=100 and member_id=2", Integer.class));
                assertEquals(Integer.valueOf(0), db.queryForObject(
                        "select status from im_conversation where id=100", Integer.class));
                try {
                    worker.submit(revoke).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                ServiceException error = assertThrows(ServiceException.class, () -> {
                    if (legacyRead) service.markRead(2L, 100L);
                    else service.markRead(2L, 100L, messageId);
                });
                assertEquals(Integer.valueOf(403), error.getCode());
                assertEquals(null, error.getDetailMessage(), "deleted historical member gets no new terminal metadata");
                status.setRollbackOnly();
                return null;
            });
        } finally {
            worker.shutdownNow();
        }
>>>>>>> private-codex/batch1
    }
}
