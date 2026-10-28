package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.ProductType;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real withdrawal service, production offer/application XML and B IM/intent mappers on MySQL.
 * Topic/chapter reads and locks use explicit JDBC fixture adapters; audit/canonical coordination
 * is mocked. This is not full supply-review, financial, Quartz or process-kill acceptance.
 * The WX channel is always mocked: zero real external calls.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
public class MerchantOfferOwnerNoticeIntentMySqlIT {
    private static final String KEY = "chapter-supply-withdraw-owner:9";
    private static final String TITLE = "章节供给已退出";
    private static final String BODY = "主题「原主题」有章节供给退出。请核对已有玩家的履约安排。";
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "w19_owner_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    private JdbcTemplate admin, db;
    private boolean created;
    private DriverManagerDataSource source;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private MerchantOfferServiceImpl target;
    private IMerchantOfferService service;
    private MerchantChapterOfferMapper offers;
    private ISystemNotificationIntentService intents;
    private IImService im;
    private ImServiceImpl imTarget;
    private ImNotificationIntentMapper intentMapper;
    private TopicSupplyReviewService review;
    private WxSubscribeChannel wx;
    private INotifyService players;
    private ScheduledExecutorService imExecutor, wxExecutor;
    private final List<Runnable> imQueue = Collections.synchronizedList(new ArrayList<>());
    private final List<Runnable> wxQueue = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failImAfterWrites = new AtomicBoolean();

    @BeforeEach void setup() throws Exception {
        for (String required : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(required), "controlled runner must explicitly supply " + required);
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4"); created = true;
        source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        // Isolated scratch fixture only, not a new production migration or a replacement money schema.
        db.execute("create table merchant_chapter_offer (id bigint primary key,topic_id bigint,chapter_id bigint,merchant_id bigint,perk_id bigint,terms_mode varchar(24),payer_club_id bigint,per_head_fee decimal(18,2),quota_total int,quota_used int,valid_end datetime,allowed_validation_methods varchar(255),max_node_xp int,status int,withdraw_reason varchar(500),withdraw_required tinyint(1),replacement_of_offer_id bigint,create_by varchar(64),create_time datetime,update_by varchar(64),update_time datetime) engine=InnoDB");
        db.execute("create table merchant_chapter_application (id bigint primary key,topic_id bigint,chapter_id bigint,merchant_id bigint,status int,del_flag int,update_by varchar(64),update_time datetime) engine=InnoDB");
        db.execute("create table w19_topic_fact(id bigint primary key,member_id bigint,name varchar(64),user_status int) engine=InnoDB");
        db.execute("create table w19_chapter_fact(id bigint primary key,topic_id bigint,required_flag int) engine=InnoDB");
        db.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,terms_mode,per_head_fee,quota_total,quota_used,status) values(9,10,33,500,'REVSHARE',7.00,20,6,1)");
        db.update("insert into merchant_chapter_application(id,topic_id,chapter_id,merchant_id,status,del_flag) values(19,10,33,500,1,0)");
        db.update("insert into w19_topic_fact values(10,501,'原主题',0)");
        db.update("insert into w19_chapter_fact values(33,10,0)");
        Path sqlRoot = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sqlRoot)) sqlRoot = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sqlRoot.resolve("migration_im_send_idempotency_20260913.sql").toFile()),
                new FileSystemResource(sqlRoot.resolve("migration_20261012_im_media_visibility.sql").toFile())).execute(source);
        db.execute("create table sys_job (job_name varchar(64),job_group varchar(64),invoke_target varchar(255),cron_expression varchar(255),misfire_policy varchar(20),concurrent varchar(20),status varchar(20),create_by varchar(64),create_time datetime,remark text)");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_20261013_b_im_notification_intent.sql").toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MerchantChapterOfferMapper.xml"),
                new ClassPathResource("mapper/business/MerchantChapterApplicationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImNotificationIntentMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        offers = sql.getMapper(MerchantChapterOfferMapper.class);
        intentMapper = sql.getMapper(ImNotificationIntentMapper.class);
        manager = new DataSourceTransactionManager(source); transaction = new TransactionTemplate(manager);
        imTarget = spy(new ImServiceImpl());
        ReflectionTestUtils.setField(imTarget, "conversationMapper", sql.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(imTarget, "memberMapper", sql.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(imTarget, "messageMapper", sql.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(imTarget, "notificationIntentMapper", intentMapper);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertExited();
            Object result = call.callRealMethod();
            if (failImAfterWrites.get()) throw new IllegalStateException("fixture post-message-and-receipt failure");
            return result;
        }).when(imTarget).deliverSystemNotification(anyLong());
        ProxyFactory imProxy = proxy(imTarget, IImService.class, ISystemNotificationIntentService.class);
        im = (IImService) imProxy.getProxy(); intents = (ISystemNotificationIntentService) im;
        imExecutor = mock(ScheduledExecutorService.class); wxExecutor = mock(ScheduledExecutorService.class);
        when(imExecutor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            imQueue.add(call.getArgument(0)); return mock(ScheduledFuture.class);
        });
        when(wxExecutor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            wxQueue.add(call.getArgument(0)); return mock(ScheduledFuture.class);
        });
        wx = mock(WxSubscribeChannel.class);
        when(wx.send(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "WX must run outside database transactions");
            return false; // includes UNKNOWN; never automatically retried by the IM recovery task
        });
        players = mock(INotifyService.class);
        target = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(target, "offerMapper", offers);
        ReflectionTestUtils.setField(target, "applicationMapper", sql.getMapper(MerchantChapterApplicationMapper.class));
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        when(chapters.lockChaptersForCapacity(anyList())).thenAnswer(call -> db.queryForList("select id from w19_chapter_fact where id=33 for update", Long.class));
        when(chapters.selectCmsTopicChapterById(33L)).thenAnswer(call -> db.queryForObject("select * from w19_chapter_fact where id=33", (row, n) -> {
            CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(33L); chapter.setTopicId(10L); chapter.setRequired(row.getInt("required_flag")); return chapter;
        }));
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenAnswer(call -> db.queryForObject("select * from w19_topic_fact where id=10 for update", (row, n) -> {
            CmsTopic topic = new CmsTopic(); topic.setId(10L); topic.setMemberId(row.getLong("member_id")); topic.setName(row.getString("name"));
            topic.setDelFlag(0); topic.setProductType(ProductType.FREE_EXPLORE); topic.setLifecycle(3); topic.setUserStatus(row.getInt("user_status")); return topic;
        }));
        review = mock(TopicSupplyReviewService.class);
        doAnswer(call -> { lockTopic(); return null; }).when(review).lockBeforeChapter(33L);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.casUserStatus(10L, 1, 0)).thenAnswer(call -> db.update("update w19_topic_fact set user_status=0 where id=10 and user_status=1"));
        RegistrationEntitlementMapper entitlement = mock(RegistrationEntitlementMapper.class);
        when(entitlement.selectAffectedPaidMembers(10L, 33L)).thenReturn(List.of()); // player path unchanged, not this cut
        ReflectionTestUtils.setField(target, "supplyReviewService", review);
        ReflectionTestUtils.setField(target, "chapterMapper", chapters);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(target, "registrationEntitlementMapper", entitlement);
        ReflectionTestUtils.setField(target, "notifyService", players);
        ReflectionTestUtils.setField(target, "canonicalMutationService", mock(ITopicSaleCanonicalMutationService.class));
        ReflectionTestUtils.setField(target, "systemNotificationIntents", intents);
        ReflectionTestUtils.setField(target, "imService", im);
        ReflectionTestUtils.setField(target, "ownerNoticeWxChannel", wx);
        ReflectionTestUtils.setField(target, "ownerNoticeExecutor", wxExecutor);
        ReflectionTestUtils.setField(target, "businessNotice", new CommittedBusinessNotice(players, mock(AlarmService.class), manager, imExecutor));
        service = (IMerchantOfferService) proxy(target, IMerchantOfferService.class).getProxy();
    }

    @AfterEach void cleanup() {
        if (created && admin != null) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
        }
    }
    private ProxyFactory proxy(Object value, Class<?>... interfaces) {
        ProxyFactory proxy = new ProxyFactory(value); proxy.setInterfaces(interfaces);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return proxy;
    }
    private void lockTopic() { db.queryForObject("select id from w19_topic_fact where id=10 for update", Long.class); }
    private void withdraw() { assertFalse(service.withdrawOwnChapterOffer(9L, 500L, "暂停营业")); }
    private int intentsCount() { return db.queryForObject("select count(*) from im_notification_intent", Integer.class); }
    private int messages() { return db.queryForObject("select count(*) from im_message", Integer.class); }
    private Long intentId() { return db.queryForObject("select id from im_notification_intent where event_key=?", Long.class, KEY); }
    private String intentState() { return db.queryForObject("select status from im_notification_intent where event_key=?", String.class, KEY); }
    private void assertExited() {
        assertEquals(0, db.queryForObject("select status from merchant_chapter_offer where id=9", Integer.class));
        assertEquals("暂停营业", db.queryForObject("select withdraw_reason from merchant_chapter_offer where id=9", String.class));
        assertEquals(2, db.queryForObject("select del_flag from merchant_chapter_application where id=19", Integer.class));
    }
    private void assertNotExited() {
        assertEquals(1, db.queryForObject("select status from merchant_chapter_offer where id=9", Integer.class));
        assertNull(db.queryForObject("select withdraw_reason from merchant_chapter_offer where id=9", String.class));
        assertEquals(0, db.queryForObject("select del_flag from merchant_chapter_application where id=19", Integer.class));
        assertEquals(0, intentsCount()); assertEquals(0, messages());
        assertTrue(imQueue.isEmpty()); assertTrue(wxQueue.isEmpty());
        verifyNoInteractions(wx);
    }
    private com.chengyinhub.business.task.ImNotificationDeliveryTask recovery() {
        com.chengyinhub.business.task.ImNotificationDeliveryTask task = new com.chengyinhub.business.task.ImNotificationDeliveryTask();
        ReflectionTestUtils.setField(task, "mapper", intentMapper); ReflectionTestUtils.setField(task, "imService", im); return task;
    }
    private void assertOneOriginalMessage() {
        assertEquals(1, messages()); assertEquals(1, intentsCount()); assertEquals("DELIVERED", intentState());
        assertEquals(501L, db.queryForObject("select receiver_id from im_message", Long.class));
        assertEquals(TITLE + "\n" + BODY, db.queryForObject("select content from im_message", String.class));
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=501", Integer.class));
    }

    @Test void originalExitAndPendingIntentExistBeforeCommitButNeitherChannelRuns() {
        transaction.executeWithoutResult(status -> {
            withdraw(); assertExited(); assertEquals(1, intentsCount()); assertEquals("PENDING", intentState());
            assertEquals(0, messages()); assertTrue(imQueue.isEmpty()); assertTrue(wxQueue.isEmpty()); verifyNoInteractions(wx);
        });
        assertEquals(2, imQueue.size(), "one owner IM wake and unchanged empty player supplier"); assertEquals(1, wxQueue.size());
        imQueue.forEach(Runnable::run); assertOneOriginalMessage();
        wxQueue.forEach(Runnable::run); verify(wx, times(1)).send(any());
        verify(imTarget, never()).sendSystem(any(), any(), any(), any()); verifyNoInteractions(players);
    }
    @Test void rollbackRestoresExitAndApprovalWithZeroIntentOrWake() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> { withdraw(); throw new IllegalStateException("fixture rollback"); }));
        assertNotExited();
    }
    @Test void intentInsertThenFailureRollsBackOriginalBusinessAndIntentTogether() {
        ISystemNotificationIntentService failed = mock(ISystemNotificationIntentService.class, org.mockito.AdditionalAnswers.delegatesTo(intents));
        doAnswer(call -> { intents.enqueue(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
            throw new IllegalStateException("fixture after original intent insert"); })
                .when(failed).enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString());
        ReflectionTestUtils.setField(target, "systemNotificationIntents", failed);
        assertThrows(RuntimeException.class, this::withdraw); assertNotExited();
    }
    @Test void unauthorizedExitLeavesBusinessIntentMessagesAndWakeUntouched() {
        assertThrows(IllegalStateException.class, () -> service.withdrawOwnChapterOffer(9L, 999L, "抢退")); assertNotExited();
    }
    @Test void losingOfferCasCannotEnqueueIntentOrRetireApproval() {
        MerchantChapterOfferMapper loser = mock(MerchantChapterOfferMapper.class, org.mockito.AdditionalAnswers.delegatesTo(offers));
        doReturn(0).when(loser).withdrawOwned(anyLong(), anyLong(), anyString(), anyBoolean());
        ReflectionTestUtils.setField(target, "offerMapper", loser);
        assertThrows(IllegalStateException.class, this::withdraw); assertNotExited();
    }
    @Test void rejectedImQueueKeepsPendingAndOriginalTaskRecoversIt() {
        when(imExecutor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenThrow(new RejectedExecutionException("fixture stopped"));
        assertDoesNotThrow(this::withdraw); assertExited(); assertEquals("PENDING", intentState()); assertEquals(0, messages());
        recovery().deliverPending(); assertOneOriginalMessage(); verifyNoInteractions(wx);
    }
    @Test void lostWakeRecoversOriginalIdAndLateWakeCannotDuplicateMessageOrUnread() {
        withdraw(); Long original = intentId(); Runnable late = imQueue.get(0);
        imQueue.clear(); wxQueue.clear(); // lost JVM wake model, not an actual process kill
        recovery().deliverPending(); late.run();
        assertEquals(original, intentId()); assertOneOriginalMessage(); verifyNoInteractions(wx);
    }
    @Test void repeatedExitKeepsOriginalReasonAndCannotRegisterAnotherWake() {
        withdraw(); Long original = intentId();
        assertFalse(service.withdrawOwnChapterOffer(9L, 500L, "后来重复提交"));
        assertEquals(original, intentId()); assertEquals(1, intentsCount()); assertEquals(2, imQueue.size()); assertEquals(1, wxQueue.size());
        assertExited(); recovery().deliverPending(); assertOneOriginalMessage();
    }
    @Test void historicalExitWithoutOriginalIntentNeverBackfillsOne() {
        db.update("update merchant_chapter_offer set status=0,withdraw_reason='历史退出' where id=9");
        assertFalse(service.withdrawOwnChapterOffer(9L, 500L, "后来重复提交"));
        assertEquals(0, intentsCount()); assertEquals(0, messages()); assertTrue(imQueue.isEmpty()); assertTrue(wxQueue.isEmpty());
    }
    @Test void historicalExitWithOriginalPendingIsRecoveredWithoutAnotherProducerOrWx() {
        Long original = intents.enqueue(501L, ImMessage.MSG_TEXT, TITLE + "\n" + BODY, null, KEY);
        db.update("update merchant_chapter_offer set status=0,withdraw_reason='暂停营业' where id=9");
        db.update("update merchant_chapter_application set del_flag=2 where id=19");
        withdraw(); assertTrue(imQueue.isEmpty()); assertTrue(wxQueue.isEmpty());
        recovery().deliverPending(); assertEquals(original, intentId()); assertOneOriginalMessage(); verifyNoInteractions(wx);
    }
    @Test void lostExitCommitAcknowledgementReplaysWithoutNewIntentOrWxAttempt() {
        assertThrows(IllegalStateException.class, () -> { withdraw(); throw new IllegalStateException("fixture acknowledgement lost after commit"); });
        Long original = intentId(); imQueue.clear(); wxQueue.clear(); withdraw();
        assertTrue(imQueue.isEmpty()); assertTrue(wxQueue.isEmpty()); assertEquals(original, intentId());
        recovery().deliverPending(); assertOneOriginalMessage(); verifyNoInteractions(wx);
    }
    @Test void lostDeliveryCommitAcknowledgementReadsOriginalReceiptInsteadOfSendingAgain() {
        withdraw(); Long original = intentId(); imQueue.clear(); wxQueue.clear();
        assertThrows(IllegalStateException.class, () -> { im.deliverSystemNotification(original); throw new IllegalStateException("fixture lost delivery response after proxy commit"); });
        Long messageId = db.queryForObject("select message_id from im_notification_intent where id=?", Long.class, original);
        assertEquals(messageId, im.deliverSystemNotification(original)); recovery().deliverPending(); assertOneOriginalMessage();
    }
    @Test void twoWorkersReadOneOriginalMessageAndIncrementUnreadOnce() throws Exception {
        withdraw(); Long original = intentId(); imQueue.clear(); wxQueue.clear();
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> delivery = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return im.deliverSystemNotification(original); };
            Future<Long> first = workers.submit(delivery), second = workers.submit(delivery); start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)); assertOneOriginalMessage();
        } finally { start.countDown(); workers.shutdownNow(); }
    }
    @Test void liveOwnerAndTopicChangesCannotRebuildOriginalRecipientOrPayload() {
        withdraw(); imQueue.clear(); wxQueue.clear(); db.update("update w19_topic_fact set member_id=999,name='已改变' where id=10");
        recovery().deliverPending(); assertOneOriginalMessage(); verifyNoInteractions(wx);
    }
    @Test void existingOriginalIntentOnWinnerIsReusedWithoutLiveRebuildOrNewWx() {
        Long original = intents.enqueue(501L, ImMessage.MSG_TEXT, TITLE + "\n" + BODY, null, KEY);
        db.update("update w19_topic_fact set member_id=999,name='已改变' where id=10");
        withdraw(); assertExited(); assertEquals(original, intentId()); assertEquals(1, intentsCount());
        assertEquals(2, imQueue.size()); assertTrue(wxQueue.isEmpty());
        recovery().deliverPending(); assertOneOriginalMessage(); verifyNoInteractions(wx);
    }
    @Test void invalidOriginalOwnerRollsBackExitAndApprovalWithoutIntentOrExternalAction() {
        db.update("update w19_topic_fact set member_id=0 where id=10");
        assertThrows(IllegalStateException.class, this::withdraw); assertNotExited();
    }
    @Test void intentFailureAlsoRollsBackRequiredChapterPauseWithoutChangingFrozenTerms() {
        db.update("update w19_chapter_fact set required_flag=1 where id=33");
        db.update("update w19_topic_fact set user_status=1 where id=10");
        ISystemNotificationIntentService failed = mock(ISystemNotificationIntentService.class, org.mockito.AdditionalAnswers.delegatesTo(intents));
        doAnswer(call -> { intents.enqueue(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
            throw new IllegalStateException("fixture intent acknowledgement failure"); })
                .when(failed).enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString());
        ReflectionTestUtils.setField(target, "systemNotificationIntents", failed);
        assertThrows(IllegalStateException.class, () -> service.withdrawOwnChapterOffer(9L, 500L, "暂停营业"));
        assertNotExited();
        assertEquals(1, db.queryForObject("select user_status from w19_topic_fact where id=10", Integer.class));
        assertEquals(6, db.queryForObject("select quota_used from merchant_chapter_offer where id=9", Integer.class));
        assertEquals(new java.math.BigDecimal("7.00"), db.queryForObject("select per_head_fee from merchant_chapter_offer where id=9", java.math.BigDecimal.class));
    }
    @Test void messageUnreadAndReceiptFailureRollBackTogetherAndPendingCanRecover() {
        withdraw(); failImAfterWrites.set(true); imQueue.forEach(Runnable::run);
        assertExited(); assertEquals("PENDING", intentState()); assertEquals(0, messages());
        assertEquals(0, db.queryForObject("select count(*) from im_conversation_member", Integer.class));
        failImAfterWrites.set(false); recovery().deliverPending(); assertOneOriginalMessage();
    }
    @Test void sameEventDifferentRecipientOrContentIsRejectedWithoutChangingOriginal() {
        withdraw(); Long original = intentId();
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> intents.enqueue(999L, ImMessage.MSG_TEXT, "changed", null, KEY));
        assertEquals(original, intentId()); assertEquals(1, intentsCount()); assertEquals(0, messages());
        assertEquals(501L, db.queryForObject("select recipient from im_notification_intent", Long.class));
        recovery().deliverPending(); assertOneOriginalMessage();
    }
    @Test void concurrentExitRequestsOnlyLetOneWinnerProduceIntentAndChannelAttempts() throws Exception {
        CyclicBarrier contenders = new CyclicBarrier(2);
        doAnswer(call -> { contenders.await(10, TimeUnit.SECONDS); lockTopic(); return null; }).when(review).lockBeforeChapter(33L);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(this::withdraw), second = workers.submit(this::withdraw);
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
            assertExited(); assertEquals(1, intentsCount()); assertEquals(2, imQueue.size()); assertEquals(1, wxQueue.size());
            recovery().deliverPending(); assertOneOriginalMessage();
        } finally { workers.shutdownNow(); }
    }
    @Test void wxUnknownIsOneOutsideTransactionAttemptAndImRecoveryNeverResendsIt() {
        withdraw(); wxQueue.forEach(Runnable::run); verify(wx, times(1)).send(any());
        recovery().deliverPending(); withdraw(); recovery().deliverPending();
        assertOneOriginalMessage(); assertEquals(1, wxQueue.size()); verify(wx, times(1)).send(any());
    }
}
