package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubEventAccess;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CYV-P1-002:Oracle MySQL 8+ 上的俱乐部主理人转让×场次取消并发证据。
 *
 * <p>三条生产路径均经真 {@link TransactionInterceptor}、真 Mapper XML 与真 InnoDB 行锁：
 * 旧主理人在转让提交后不能取消/退款；主题层已授权的取消在锁后用当前 owner；
 * 重复请求不二次退款，且不留 ACTIVE occurrence/waitlist。
 *
 * <p>负控不靠注释：同一 MySQL RR 事务的普通读在转让提交后仍看到旧 owner，
 * {@code FOR UPDATE} 当前读却必须看到新 owner。把生产锁定读退化成普通读时，该对照立即暴露旧快照。
 *
 * <p>只连用例环境变量显式指定的 localhost MySQL；测试自行建立 UUID scratch 和所需表，
 * 结束 DROP 并从 {@code information_schema} 回读 0。退款网关/通知不外发，只保留调用计数。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class ClubEventTransferCancellationMySqlIT {

    private static final String SUITE = "p1002_cancel";
    private static final long OLD_OWNER = 1101L;
    private static final long NEW_OWNER = 1102L;
    private static final long HOST_ACTOR = 1199L;
    private static final long CLUB = 1201L;
    private static final long OTHER_CLUB = 1202L;
    private static final long TOPIC = 1301L;
    private static final long OTHER_TOPIC = 1302L;
    private static final long ACTIVITY = 1401L;
    private static final long OTHER_ACTIVITY = 1402L;
    private static final long TICKET = 1501L;
    private static final long OTHER_TICKET = 1502L;
    private static final long REGISTRATION = 1601L;
    private static final String REASON = "场地突发取消";
    private static final List<String> TABLES = Arrays.asList(
            "club", "cms_topic", "cms_topic_cancellation_receipt", "cms_topic_chapter", "cms_topic_node", "cms_activity", "oms_ticket", "cms_registration",
            "club_event_series", "club_event_occurrence", "club_event_waitlist",
            "club_event_notification_campaign", "club_event_notification_delivery",
            "club_event_notification_outbox");

    private static String database;
    private static String url;
    private static String adminUrl;
    private static MysqlIntegrationTestConfig mysql;
    private static PooledDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactionManager;

    private final AtomicInteger refundApplications = new AtomicInteger();
    private final AtomicInteger payouts = new AtomicInteger();
    private final AtomicReference<RefundApplication> lastRefund = new AtomicReference<>();
    private DbClubAccess access;
    private IClubEventOpsService eventOps;
    private ITeamGroupService group;
    private final java.util.concurrent.atomic.AtomicBoolean refuseDoneWrite=new java.util.concurrent.atomic.AtomicBoolean();
    private CountDownLatch ownerLockAttempted;
    private CountDownLatch retryTopicLockAttempted;
    private AtomicInteger topicLockCalls;
    private static String mysqlVersion;
    private volatile java.util.function.Consumer<String> observationBoundary=name -> { };

    @BeforeAll
    static void createScratch() throws Exception {
        mysql = MysqlIntegrationTestConfig.fromEnvironment(SUITE);
        adminUrl = mysql.adminUrl();
        try (Connection connection = DriverManager.getConnection(
                adminUrl, mysql.user(), mysql.password());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select version()")) {
            assertTrue(result.next());
            mysqlVersion = result.getString(1);
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(mysqlVersion),
                    "并发证据只接受 Oracle MySQL 8+");
        }

        database = mysql.databaseName();
        url = mysql.databaseUrl();
        try (Connection connection = DriverManager.getConnection(adminUrl, mysql.user(), mysql.password());
             Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists `" + database + "`");
            statement.execute("create database `" + database + "` character set utf8mb4");
        }
        try (Connection connection = DriverManager.getConnection(
                url, mysql.user(), mysql.password())) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
            ItSchema.applyMigrations(connection, "sql/migration_club_role_event_ops_20260823.sql",
                    "sql/migration_20260930_registration_first_successful_scan.sql",
                    "sql/migration_20261029_activity_cancellation_receipt.sql",
                    "sql/migration_20261029_topic_cancellation_receipt.sql");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE cms_topic ADD COLUMN theme_runtime_config_json MEDIUMTEXT NULL,"
                        + " ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT NULL");
            }
        }

        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        // Existing canonical base DDL, not an imitation table: the real listing size guard reads this table.
        com.chengyinhub.business.it.P0AcceptanceSchema.baseTables(jdbc,"cms_topic_node");
        transactionManager = new DataSourceTransactionManager(dataSource);
        sql = mybatisTemplate();
    }

    @AfterAll
    static void dropScratchAndReadBack() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        assertTrue(database != null && database.equals(mysql.databaseName()));
        try (Connection connection = DriverManager.getConnection(
                adminUrl, mysql.user(), mysql.password());
             Statement statement = connection.createStatement()) {
            statement.execute("drop database `" + database + "`");
            try (ResultSet result = statement.executeQuery(
                    "select count(*) from information_schema.schemata where schema_name='" + database + "'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "UUID scratch 必须销毁并回读");
            }
        }
        System.out.println("CYV-P1-002 MYSQL_VERSION=" + mysqlVersion
                + " SCRATCH_DROPPED=true schema=" + database);
    }

    @BeforeEach
    void resetAndWire() {
        refundApplications.set(0);
        payouts.set(0);
        lastRefund.set(null);
        observationBoundary=name -> { };
        refuseDoneWrite.set(false);
        seed();
        wireServices();
    }

    @Test
    void repeatableReadSnapshotIsStaleButLockingReadSeesCommittedTransfer() throws Exception {
        try (Connection rr = open()) {
            rr.setAutoCommit(false);
            rr.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertEquals(OLD_OWNER, selectOwner(rr, false), "RR 首次普通读建立旧快照");

            try (Connection transfer = open(); Statement statement = transfer.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "update club set member_id=" + NEW_OWNER + " where id=" + CLUB));
            }

            assertEquals(OLD_OWNER, selectOwner(rr, false),
                    "负控:转让提交后 RR 普通读仍是旧 owner");
            assertEquals(NEW_OWNER, selectOwner(rr, true),
                    "生产锁定读是 current read，必须看到已提交的新 owner");
            rr.rollback();
        }
    }

    @Test
    void formerOwnerWaitsForTransferThenReadCommittedDeniesBeforeRefund() throws Exception {
        CountDownLatch firstAccessRead = new CountDownLatch(1);
        CountDownLatch releaseFirstAccess = new CountDownLatch(1);
        access.pauseFirstRequire(firstAccessRead, releaseFirstAccess);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection transfer = open()) {
            transfer.setAutoCommit(false);
            transfer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Future<ServiceException> cancellation = pool.submit(() -> {
                try {
                    eventOps.cancelActivityByOwnerWithClubWrapUp(OLD_OWNER, ACTIVITY, REASON);
                    throw new AssertionError("转让后旧主理人不应取消成功");
                } catch (ServiceException denied) {
                    return denied;
                }
            });

            assertTrue(firstAccessRead.await(5, TimeUnit.SECONDS), "旧 owner 必须先读到未转让的授权事实");
            try (Statement statement = transfer.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "update club set member_id=" + NEW_OWNER + " where id=" + CLUB));
            }
            releaseFirstAccess.countDown();
            assertTrue(ownerLockAttempted.await(5, TimeUnit.SECONDS), "取消链必须到达 club FOR UPDATE");
            assertThrows(TimeoutException.class, () -> cancellation.get(250, TimeUnit.MILLISECONDS),
                    "转让未提交时，取消必须真实阻塞在同一 club 行锁");

            transfer.commit();
            ServiceException denied = cancellation.get(5, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(403), denied.getCode());
            assertEquals(Arrays.asList(Connection.TRANSACTION_READ_COMMITTED,
                    Connection.TRANSACTION_READ_COMMITTED), access.isolations(),
                    "人工取消的锁前/锁后权限读必须均处于生产 READ_COMMITTED 事务");
            assertEquals(0, refundApplications.get(), "失权者不得进入退款链");
            assertEquals("ACTIVE", scalar("select status from club_event_occurrence where activity_id=?", ACTIVITY));
            assertEquals(1, number("select count(*) from club_event_waitlist where activity_id=? and state='WAITING'", ACTIVITY));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void authorizedTopicCancelUsesPostTransferOwnerKeepsScopeAndRetryIsSingleRefund() throws Exception {
        ServiceException wrongScope = assertThrows(ServiceException.class,
                () -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                        HOST_ACTOR, TOPIC, OTHER_CLUB, ACTIVITY, REASON));
        assertEquals(Integer.valueOf(409), wrongScope.getCode());
        assertEquals(0, refundApplications.get(), "不匹配的 topic+club+activity scope 不得进入退款");
        topicLockCalls.set(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection transfer = open()) {
            transfer.setAutoCommit(false);
            try (Statement statement = transfer.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "update club set member_id=" + NEW_OWNER + " where id=" + CLUB));
            }

            Future<Object> first = pool.submit(() -> authorizedCancelOutcome());
            assertTrue(ownerLockAttempted.await(5, TimeUnit.SECONDS), "已授权取消必须到达 club FOR UPDATE");
            assertThrows(TimeoutException.class, () -> first.get(250, TimeUnit.MILLISECONDS),
                    "取消必须等转让提交，不得用 series 历史创建人绕锁");

            Future<Object> retry = pool.submit(() -> authorizedCancelOutcome());
            assertTrue(retryTopicLockAttempted.await(5, TimeUnit.SECONDS),
                    "重试未到达原 topic 行锁");
            assertThrows(TimeoutException.class, () -> retry.get(250, TimeUnit.MILLISECONDS),
                    "重试必须被首请求的同 topic 首锁串行化");
            transfer.commit();

            Object accepted = first.get(5, TimeUnit.SECONDS);
            assertTrue(accepted instanceof ActivityCancelResult);
            assertEquals(Integer.valueOf(1), ((ActivityCancelResult) accepted).getRefundedOrders());
            Object replay = retry.get(5, TimeUnit.SECONDS);
            assertTrue(replay instanceof ActivityCancelResult, "并发输家必须恢复同一原持久结果，不要求先发必胜");
            sameResult((ActivityCancelResult)accepted,(ActivityCancelResult)replay);

            assertEquals(NEW_OWNER, number("select member_id from club where id=?", CLUB));
            assertEquals(String.valueOf(NEW_OWNER), scalar("select update_by from cms_activity where id=?", ACTIVITY),
                    "退款/取消 actor 必须是锁后当前 owner");
            assertEquals(1, refundApplications.get(), "并发重试不得二次创建退款");
            assertEquals(1, payouts.get(), "提交后打款只派发一次");
            assertNotNull(lastRefund.get());
            assertEquals(Long.valueOf(REGISTRATION), lastRefund.get().getSourceId());

            assertEquals(0, number("select count(*) from club_event_occurrence where activity_id=? and status='ACTIVE'", ACTIVITY));
            assertEquals(0, number("select count(*) from club_event_waitlist where activity_id=? and state in ('WAITING','OFFERED','CLAIMED')", ACTIVITY));
            assertEquals("CANCELLED", scalar("select status from club_event_occurrence where activity_id=?", ACTIVITY));
            assertEquals("CANCELLED", scalar("select state from club_event_waitlist where activity_id=?", ACTIVITY));
            assertEquals(2, number("select count(*) from club_event_notification_campaign where activity_id=?", ACTIVITY));

            assertEquals("ACTIVE", scalar("select status from club_event_occurrence where activity_id=?", OTHER_ACTIVITY),
                    "精确 scope 不得串到其他俱乐部场次");
            assertEquals("WAITING", scalar("select state from club_event_waitlist where activity_id=?", OTHER_ACTIVITY));
            assertEquals(0, number("select count(*) from club_event_notification_campaign where activity_id=?", OTHER_ACTIVITY));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void responseLostRecreatedServiceReadsExactOriginalResultWithoutRepeatingWrapUp() {
        ActivityCancelResult first=(ActivityCancelResult)authorizedCancelOutcome();
        assertEquals(1,first.getRefundedOrders());
        String original=scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY);
        assertEquals("DONE",com.alibaba.fastjson2.JSON.parseObject(original).getString("state"));
        // Non-identity metadata/version changes must not destroy the valid original receipt.
        jdbc.update("update club_event_occurrence set version=version+1 where activity_id=?",ACTIVITY);
        wireServices();
        ActivityCancelResult replay=(ActivityCancelResult)authorizedCancelOutcome();
        sameResult(first,replay);
        assertEquals(original,scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY));
        assertEquals(1,refundApplications.get());assertEquals(1,payouts.get());
        assertEquals(2,number("select count(*) from club_event_notification_campaign where activity_id=?",ACTIVITY));
    }

    @Test
    void firstObserverMayWinAndLoserReplaysOriginalResult() throws Exception {
        eitherObserverMayWinAndLoserReplaysOriginalResult(false);
    }

    @Test
    void secondObserverMayWinAndLoserReplaysOriginalResult() throws Exception {
        eitherObserverMayWinAndLoserReplaysOriginalResult(true);
    }

    private void eitherObserverMayWinAndLoserReplaysOriginalResult(boolean secondWins) throws Exception {
        CountDownLatch captured=new CountDownLatch(2),firstPermit=new CountDownLatch(1),secondPermit=new CountDownLatch(1);
        observationBoundary=name -> {
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            captured.countDown();
            try {
                if(!(name.equals("cancel-first")?firstPermit:secondPermit).await(5,TimeUnit.SECONDS))
                    throw new AssertionError("观察完成后等待原 apply 许可超时");
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        };
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<Object> first=workers.submit(() -> {Thread.currentThread().setName("cancel-first");return authorizedCancelOutcome();});
            Future<Object> second=workers.submit(() -> {Thread.currentThread().setName("cancel-second");return authorizedCancelOutcome();});
            assertTrue(captured.await(5,TimeUnit.SECONDS));
            (secondWins?secondPermit:firstPermit).countDown();
            Object winner=(secondWins?second:first).get(5,TimeUnit.SECONDS);
            assertTrue(winner instanceof ActivityCancelResult);
            (secondWins?firstPermit:secondPermit).countDown();
            Object loser=(secondWins?first:second).get(5,TimeUnit.SECONDS);
            assertTrue(loser instanceof ActivityCancelResult);
            sameResult((ActivityCancelResult)winner,(ActivityCancelResult)loser);
            assertEquals(1,refundApplications.get());assertEquals(1,payouts.get());
            assertEquals(2,number("select count(*) from club_event_notification_campaign where activity_id=?",ACTIVITY));
        } finally {firstPermit.countDown();secondPermit.countDown();workers.shutdownNow();observationBoundary=name -> { };}
    }

    @Test
    void replayRequiresCurrentScopeActorAndSameSemanticRequestBeforeAnyEffect() {
        ActivityCancelResult original=(ActivityCancelResult)authorizedCancelOutcome();
        String receipt=scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY);
        assertThrows(ServiceException.class,() -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                HOST_ACTOR,TOPIC,CLUB,ACTIVITY,REASON+" changed"));
        assertThrows(ServiceException.class,() -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                9999L,TOPIC,CLUB,ACTIVITY,REASON));
        assertThrows(ServiceException.class,() -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                HOST_ACTOR,OTHER_TOPIC,CLUB,ACTIVITY,REASON));
        jdbc.update("update cms_topic set member_id=? where id=?",9999L,TOPIC);
        assertThrows(ServiceException.class,() -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                HOST_ACTOR,TOPIC,CLUB,ACTIVITY,REASON));
        assertEquals(1,original.getRefundedOrders());assertEquals(1,refundApplications.get());assertEquals(1,payouts.get());
        assertEquals(receipt,scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY));
        assertEquals(2,number("select count(*) from club_event_notification_campaign where activity_id=?",ACTIVITY));
    }

    @Test
    void historicalCancelledNullReceiptAndReopenedSameIdAreUnprovableWithoutNewRefunds() {
        jdbc.update("update cms_activity set cancel_time=now(),status=0,publish_status=0 where id=?",ACTIVITY);
        jdbc.update("update club_event_occurrence set status='CANCELLED' where activity_id=?",ACTIVITY);
        assertThrows(ServiceException.class,() -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                HOST_ACTOR,TOPIC,CLUB,ACTIVITY,REASON));
        assertEquals(0,refundApplications.get());assertEquals(0,payouts.get());
        assertEquals(null,scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY));
        seed();wireServices();
        assertTrue(authorizedCancelOutcome() instanceof ActivityCancelResult);
        String receipt=scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY);
        jdbc.update("update cms_activity set cancel_time=null,status=1,publish_status=1 where id=?",ACTIVITY);
        jdbc.update("update club_event_occurrence set status='ACTIVE' where activity_id=?",ACTIVITY);
        assertThrows(ServiceException.class,() -> eventOps.cancelActivityForAuthorizedTopicCancellation(
                HOST_ACTOR,TOPIC,CLUB,ACTIVITY,REASON));
        assertEquals(receipt,scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY));
        assertEquals(1,refundApplications.get());assertEquals(1,payouts.get());
    }

    @Test
    void multiOrderCancellationCommitsEveryOriginalOrderAndReplaysFullBatchCounts() {
        jdbc.update("insert into cms_registration(id,registration_no,owner_type,owner_id,activity_title,ticket_id,ticket_name,ticket_price,member_id,"
                +"order_num,original_amount,total_amount,point_used,payable_amount,payment_status,payment_time,wechat_payment_amount,registration_status,verification_status,create_time) "
                +"select id+100,concat(registration_no,'-2'),owner_type,owner_id,activity_title,ticket_id,ticket_name,ticket_price,member_id+1,"
                +"order_num,original_amount,total_amount,point_used,payable_amount,payment_status,payment_time,wechat_payment_amount,registration_status,verification_status,create_time "
                +"from cms_registration where id=?",REGISTRATION);
        ActivityCancelResult first=(ActivityCancelResult)authorizedCancelOutcome();
        assertEquals(2,first.getRefundedOrders());assertEquals(0,first.getManualOrders());assertEquals(0,first.getClosedPendingOrders());
        assertEquals(2,refundApplications.get());assertEquals(2,payouts.get());
        sameResult(first,(ActivityCancelResult)authorizedCancelOutcome());
        assertEquals(2,refundApplications.get());assertEquals(2,payouts.get());
        assertEquals(2,number("select count(*) from cms_registration where owner_type=2 and owner_id=? and registration_status=3",ACTIVITY));
        assertEquals(2,number("select count(*) from club_event_notification_campaign where activity_id=?",ACTIVITY));
    }

    private void sameResult(ActivityCancelResult original,ActivityCancelResult replay) {
        assertEquals(original.getRefundedOrders(),replay.getRefundedOrders());
        assertEquals(original.getManualOrders(),replay.getManualOrders());
        assertEquals(original.getClosedPendingOrders(),replay.getClosedPendingOrders());
    }

    @Test
    void doneCasFailureRollsBackOriginalDomainBatchAndKeepsPendingIdentityForRecovery() {
        refuseDoneWrite.set(true);
        assertTrue(authorizedCancelOutcome() instanceof ServiceException);
        assertEquals(0,payouts.get(),"回滚事务不得派发 payout");
        assertEquals(2,number("select payment_status from cms_registration where id=?",REGISTRATION));
        assertEquals(2,number("select registration_status from cms_registration where id=?",REGISTRATION));
        assertEquals(0,number("select count(*) from cms_activity where id=? and cancel_time is not null",ACTIVITY));
        assertEquals("ACTIVE",scalar("select status from club_event_occurrence where activity_id=?",ACTIVITY));
        assertEquals("WAITING",scalar("select state from club_event_waitlist where activity_id=?",ACTIVITY));
        assertEquals(0,number("select count(*) from club_event_notification_campaign where activity_id=?",ACTIVITY));
        var pending=com.alibaba.fastjson2.JSON.parseObject(scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY));
        assertEquals("PENDING",pending.getString("state"));assertEquals(null,pending.get("result"));
        refuseDoneWrite.set(false);
        ActivityCancelResult recovered=(ActivityCancelResult)authorizedCancelOutcome();
        assertEquals(1,recovered.getRefundedOrders());assertEquals(1,payouts.get());
        var done=com.alibaba.fastjson2.JSON.parseObject(scalar("select cancellation_receipt_json from cms_activity where id=?",ACTIVITY));
        assertEquals("DONE",done.getString("state"));assertEquals(pending.getString("requestHash"),done.getString("requestHash"));
        sameResult(recovered,(ActivityCancelResult)authorizedCancelOutcome());assertEquals(1,payouts.get());
    }

    @Test
    void topicParentAndBothClubAndOrdinaryChildReceiptsCommitAndReplayEveryOriginalOrder() {
        long ordinary=ACTIVITY+100;
        jdbc.update("update cms_topic set user_status=1 where id=?",TOPIC);
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,end_date,topic_id,create_time) "
                +"select id+100,?,concat(name,' ordinary'),status,publish_status,del_flag,start_date,end_date,topic_id,create_time from cms_activity where id=?",
                HOST_ACTOR,ACTIVITY);
        jdbc.update("insert into oms_ticket(id,member_id,owner_type,owner_id,name,price,total_inventory,remaining_inventory,del_flag,create_time) "
                +"select id+100,?,owner_type,?,name,price,total_inventory,remaining_inventory,del_flag,create_time from oms_ticket where id=?",
                HOST_ACTOR,ordinary,TICKET);
        jdbc.update("insert into cms_registration(id,registration_no,owner_type,owner_id,activity_title,ticket_id,ticket_name,ticket_price,member_id,"
                +"order_num,original_amount,total_amount,point_used,payable_amount,payment_status,payment_time,wechat_payment_amount,registration_status,verification_status,create_time) "
                +"select id+100,concat(registration_no,'-ordinary'),owner_type,?,activity_title,ticket_id+100,ticket_name,ticket_price,member_id+1,"
                +"order_num,original_amount,total_amount,point_used,payable_amount,payment_status,payment_time,wechat_payment_amount,registration_status,verification_status,create_time "
                +"from cms_registration where id=?",ordinary,REGISTRATION);
        ActivityCancelResult first=group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON);
        assertEquals(2,first.getRefundedOrders());assertEquals(2,refundApplications.get());assertEquals(2,payouts.get());
        String parent=scalar("select receipt_json from cms_topic_cancellation_receipt where topic_id=? and cancellation_epoch=0",TOPIC);
        assertEquals("DONE",com.alibaba.fastjson2.JSON.parseObject(parent).getString("state"));
        assertEquals(2,number("select count(*) from cms_activity where topic_id=? and JSON_UNQUOTE(JSON_EXTRACT(cancellation_receipt_json,'$.state'))='DONE'",TOPIC));
        assertEquals("TOPIC_ACTIVITY",com.alibaba.fastjson2.JSON.parseObject(scalar(
                "select cancellation_receipt_json from cms_activity where id=?",ordinary)).getJSONObject("identity").getString("operation"));
        sameResult(first,group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON));
        assertEquals(parent,scalar("select receipt_json from cms_topic_cancellation_receipt where topic_id=? and cancellation_epoch=0",TOPIC));
        assertEquals(2,refundApplications.get());assertEquals(2,payouts.get());
        assertEquals(0,number("select count(*) from cms_registration where owner_type=2 and owner_id in (?,?) and registration_status<>3",ACTIVITY,ordinary));
    }

    @Test
    void pendingBlocksRelistAndTwoListingCyclesKeepBothOriginalResultsAndNewOrdersIndependent() throws Exception {
        jdbc.update("update cms_topic set user_status=1,status=1,is_draft=0,del_flag=0,product_type=1 where id=?",TOPIC);
        var target=new com.chengyinhub.business.service.impl.CmsTopicServiceImpl();
        ReflectionTestUtils.setField(target,"cmsTopicMapper",sql.getMapper(CmsTopicMapper.class));
        var unusedTemplates=mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        var unusedRoles=mock(IProductRoleService.class);
        var unusedCategories=mock(com.chengyinhub.system.service.ISysCategoryService.class);
        var writes=new com.chengyinhub.business.service.support.TopicContentWriteGuard(sql.getMapper(CmsTopicMapper.class),
                sql.getMapper(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class),unusedTemplates,unusedRoles,unusedCategories);
        ReflectionTestUtils.setField(writes,"cmsTopicNodeMapper",sql.getMapper(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class));
        var publication=new com.chengyinhub.business.service.support.TopicPublicationContentGuard();
        ReflectionTestUtils.setField(publication,"contentWriteGuard",writes);
        ReflectionTestUtils.setField(target,"publicationContentGuard",publication);
        ReflectionTestUtils.setField(target,"umsCreativeSquareService",mock(IUmsCreativeSquareService.class));
        ICmsTopicService publisher=transactionalProxy(target,ICmsTopicService.class);
        CountDownLatch observed=new CountDownLatch(1),permit=new CountDownLatch(1);
        observationBoundary=name -> {
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            observed.countDown();
            try {if(!permit.await(5,TimeUnit.SECONDS)) throw new AssertionError("原观察阶段未释放");}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();
        ActivityCancelResult first;
        try {
            Future<ActivityCancelResult> original=worker.submit(() -> group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON,0L));
            assertTrue(observed.await(5,TimeUnit.SECONDS));
            assertEquals(1,publisher.casUserStatus(TOPIC,1,0));
            assertThrows(ServiceException.class,() -> publisher.casUserStatus(TOPIC,0,1));
            assertEquals(0,number("select cancellation_epoch from cms_topic where id=?",TOPIC));assertEquals(0,payouts.get());
            permit.countDown();first=original.get(5,TimeUnit.SECONDS);
        } finally {permit.countDown();worker.shutdownNow();observationBoundary=name -> { };}
        assertEquals(1,first.getRefundedOrders());assertEquals(1,payouts.get());
        String old=scalar("select receipt_json from cms_topic_cancellation_receipt where topic_id=? and cancellation_epoch=0",TOPIC);
        assertEquals(1,publisher.casUserStatus(TOPIC,0,1));
        assertEquals(1,number("select cancellation_epoch from cms_topic where id=?",TOPIC));
        for(int sequence=1;sequence<=2;sequence++) {
            long extra=500L+sequence;
            jdbc.update("insert into oms_ticket(id,member_id,owner_type,owner_id,name,price,total_inventory,remaining_inventory,del_flag,create_time) "
                    +"select id+?, ?,1,?,name,price,total_inventory,remaining_inventory,del_flag,create_time from oms_ticket where id=?",
                    extra,HOST_ACTOR,TOPIC,TICKET);
            jdbc.update("insert into cms_registration(id,registration_no,owner_type,owner_id,activity_title,ticket_id,ticket_name,ticket_price,member_id,"
                    +"order_num,original_amount,total_amount,point_used,payable_amount,payment_status,payment_time,wechat_payment_amount,registration_status,verification_status,create_time) "
                    +"select id+?,concat(registration_no,'-cycle-',?),1,?,activity_title,ticket_id+?,ticket_name,ticket_price,member_id+?,"
                    +"order_num,original_amount,total_amount,point_used,payable_amount,2,payment_time,wechat_payment_amount,2,verification_status,create_time "
                    +"from cms_registration where id=?",extra,sequence,TOPIC,extra,extra,REGISTRATION);
        }
        sameResult(first,group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON,0L));
        assertEquals(2,number("select count(*) from cms_registration where owner_type=1 and owner_id=? and payment_status=2 and registration_status=2",TOPIC));
        assertEquals(1,payouts.get());
        assertThrows(ServiceException.class,() -> group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON));
        assertEquals(1,publisher.casUserStatus(TOPIC,1,0));
        assertEquals(1,number("select cancellation_epoch from cms_topic where id=?",TOPIC));
        ActivityCancelResult second=group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON,1L);
        assertEquals(2,second.getRefundedOrders());assertEquals(3,payouts.get());assertEquals(3,refundApplications.get());
        sameResult(first,group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON,0L));
        sameResult(second,group.cancelTopicByHost(TOPIC,HOST_ACTOR,HOST_ACTOR,REASON,1L));
        assertEquals(3,payouts.get());assertEquals(3,refundApplications.get());
        assertEquals(2,number("select count(*) from cms_topic_cancellation_receipt where topic_id=?",TOPIC));
        assertEquals(old,scalar("select receipt_json from cms_topic_cancellation_receipt where topic_id=? and cancellation_epoch=0",TOPIC));
        assertEquals(2,number("select count(*) from club_event_notification_campaign where activity_id=?",ACTIVITY));
        org.mockito.Mockito.verifyNoInteractions(unusedTemplates,unusedRoles,unusedCategories);
    }

    @Test
    void originalActorReadbackAfterRecreationReturnsStoredBatchWithoutDispatchAndStillChecksCurrentOwner() {
        var request=new com.chengyinhub.business.domain.dto.ClubEventCancellationRequest();
        request.setClubId(CLUB);request.setActivityId(ACTIVITY);request.setReason(REASON);request.setRequestId("original-readback");
        var first=eventOps.cancelOccurrence(OLD_OWNER,"original-owner",request);
        wireServices();
        var state=eventOps.getCancellationState(OLD_OWNER,CLUB,ACTIVITY);
        assertEquals("DONE",state.getCancellationReceiptState());
        assertEquals("original-readback",state.getCancellationRequestId());assertEquals(REASON,state.getCancellationRequestReason());
        assertEquals(first.getRefundedOrders(),state.getOriginalCancellationResult().getRefundedOrders());
        assertEquals(first.getManualOrders(),state.getOriginalCancellationResult().getManualOrders());
        assertEquals(first.getClosedPendingOrders(),state.getOriginalCancellationResult().getClosedPendingOrders());
        assertEquals(1,refundApplications.get());assertEquals(1,payouts.get());
        jdbc.update("update club set member_id=? where id=?",NEW_OWNER,CLUB);
        assertThrows(ServiceException.class,() -> eventOps.getCancellationState(OLD_OWNER,CLUB,ACTIVITY));
        assertEquals(1,refundApplications.get());assertEquals(1,payouts.get());
    }

    private Object authorizedCancelOutcome() {
        try {
            return eventOps.cancelActivityForAuthorizedTopicCancellation(
                    HOST_ACTOR, TOPIC, CLUB, ACTIVITY, REASON);
        } catch (ServiceException rejected) {
            return rejected;
        }
    }

    private void wireServices() {
        IRefundService refundService = mock(IRefundService.class);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication refund = invocation.getArgument(0, RefundApplication.class);
            refund.setId(1700L + refundApplications.incrementAndGet());
            lastRefund.set(refund);
            return 1;
        });
        when(refundService.reviewRefund(any(RefundReviewDTO.class), anyLong())).thenReturn(1);
        IRefundPayoutService payoutService = mock(IRefundPayoutService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            payouts.incrementAndGet();
            return null;
        }).when(payoutService).payoutAfterReview(anyLong());

        TeamGroupServiceImpl teamTarget = org.mockito.Mockito.spy(new TeamGroupServiceImpl());
        org.mockito.Mockito.doAnswer(call -> {
            Object observed=call.callRealMethod();
            observationBoundary.accept(Thread.currentThread().getName());return observed;
        }).when(teamTarget).observeCancellationPayments(any(com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.class));
        ReflectionTestUtils.setField(teamTarget, "cancellationTransactions", transactionManager);
        // 本套只有既有 2/2 已付款领域行；不构造供应商事实或让缺证据读取默认为成功。
        ReflectionTestUtils.setField(teamTarget, "verifiedPaymentFacts",
                mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class, invocation -> {
                    if (invocation.getMethod().getDeclaringClass() == Object.class)
                        return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                    throw new AssertionError("本领域取消夹具不应读取供应商事实: " + invocation.getMethod().getName());
                }));
        CmsActivityMapper realActivities=sql.getMapper(CmsActivityMapper.class);
        CmsActivityMapper activityReceipts=(CmsActivityMapper)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CmsActivityMapper.class},(proxy,method,args) -> {
                    if("compareAndSetCancellationReceipt".equals(method.getName()) && Boolean.FALSE.equals(args[3])
                            && refuseDoneWrite.get()) return 0;
                    try {return method.invoke(realActivities,args);}
                    catch(InvocationTargetException failure) {throw failure.getTargetException();}
                });
        ReflectionTestUtils.setField(teamTarget, "cmsActivityMapper", activityReceipts);
        ReflectionTestUtils.setField(teamTarget, "cmsTopicMapper", sql.getMapper(CmsTopicMapper.class));
        ReflectionTestUtils.setField(teamTarget, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(teamTarget, "omsTicketMapper", sql.getMapper(OmsTicketMapper.class));
        ReflectionTestUtils.setField(teamTarget, "clubMapper", sql.getMapper(ClubMapper.class));
        ReflectionTestUtils.setField(teamTarget, "refundService", refundService);
        ReflectionTestUtils.setField(teamTarget, "refundPayoutService", payoutService);
        ReflectionTestUtils.setField(teamTarget, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(teamTarget, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(teamTarget, "umsMemberMapper", mock(UmsMemberMapper.class));
        ReflectionTestUtils.setField(teamTarget, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(teamTarget, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(teamTarget, "manualRefundCaseService", mock(IActivityCancelManualRefundCaseService.class));
        IImService im=mock(IImService.class);
        when(im.sendSystem(anyLong(),any(),any(),any())).thenReturn(1901L);
        ReflectionTestUtils.setField(teamTarget,"imService",im);
        ICmsTopicService topicService=mock(ICmsTopicService.class);
        when(topicService.casUserStatus(anyLong(),any(),any())).thenAnswer(call -> sql.getMapper(CmsTopicMapper.class)
                .casUserStatus(call.getArgument(0),call.getArgument(1),call.getArgument(2),new java.util.Date()));
        ReflectionTestUtils.setField(teamTarget,"cmsTopicService",topicService);
        ITeamGroupService actualTeam = transactionalProxy(teamTarget, ITeamGroupService.class);
        group=actualTeam;
        ITeamGroupService teamGroup=actualTeam;

        access = new DbClubAccess(jdbc);
        ownerLockAttempted = new CountDownLatch(1);
        retryTopicLockAttempted = new CountDownLatch(1);
        topicLockCalls = new AtomicInteger();
        ClubEventOpsMapper realMapper = sql.getMapper(ClubEventOpsMapper.class);
        ClubEventOpsMapper probedMapper = (ClubEventOpsMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ClubEventOpsMapper.class},
                (proxy, method, args) -> {
                    if ("selectClubOwnerForUpdate".equals(method.getName())) ownerLockAttempted.countDown();
                    try {
                        return method.invoke(realMapper, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getTargetException();
                    }
                });
        CmsTopicMapper actualTopics = sql.getMapper(CmsTopicMapper.class);
        CmsTopicMapper probedTopics = (CmsTopicMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CmsTopicMapper.class}, (proxy, method, args) -> {
                    if ("selectCmsTopicByIdForUpdate".equals(method.getName()) && topicLockCalls.incrementAndGet() == 2)
                        retryTopicLockAttempted.countDown();
                    try { return method.invoke(actualTopics, args); }
                    catch (InvocationTargetException failure) { throw failure.getTargetException(); }
                });
        ClubEventOpsServiceImpl eventTarget = new ClubEventOpsServiceImpl(
                access, new ClubEventScheduleMaterializer(), mock(IClubSessionService.class),
                probedMapper, mock(ClubMemberMapper.class), mock(ClubLeadMapper.class),
                mock(IClubRoleAssignmentService.class), teamGroup);
        ReflectionTestUtils.setField(eventTarget, "cancellationTransactions", transactionManager);
        ReflectionTestUtils.setField(eventTarget, "cancellationActivityMapper", activityReceipts);
        ReflectionTestUtils.setField(eventTarget, "cancellationTopicMapper", probedTopics);
        eventOps = transactionalProxy(eventTarget, IClubEventOpsService.class);
        ReflectionTestUtils.setField(teamTarget,"clubEventOpsService",eventOps);
    }

    private <T> T transactionalProxy(Object target, Class<T> contract) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(contract);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return contract.cast(factory.getProxy());
    }

    private static SqlSessionTemplate mybatisTemplate() throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "cyv-p1002", new SpringManagedTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : Arrays.asList("ClubEventOpsMapper", "ClubMapper",
                "CmsActivityMapper", "CmsTopicMapper", "CmsRegistrationMapper", "OmsTicketMapper","CmsTopicChapterMapper","CmsTopicNodeMapper")) {
            String path = "mapper/business/" + mapper + ".xml";
            try (InputStream input = ClubEventTransferCancellationMySqlIT.class
                    .getClassLoader().getResourceAsStream(path)) {
                if (input == null) throw new IllegalStateException("Mapper XML 不存在:" + path);
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    private void seed() {
        for (int i = TABLES.size() - 1; i >= 0; i--) jdbc.execute("delete from " + TABLES.get(i));
        jdbc.update("insert into club(id,member_id,name,status,del_flag,create_time) values (?,?,?,?,?,now()),(?,?,?,?,?,now())",
                CLUB, OLD_OWNER, "P1-002 target", 1, 0,
                OTHER_CLUB, 2102L, "P1-002 decoy", 1, 0);
        jdbc.update("insert into cms_topic(id,member_id) values (?,?),(?,?)", TOPIC, HOST_ACTOR, OTHER_TOPIC, 2102L);
        jdbc.update("insert into cms_activity(id,member_id,name,status,publish_status,del_flag,start_date,end_date,topic_id,club_id,create_time)"
                        + " values (?,?,?,1,1,0,date_add(now(),interval 1 day),date_add(now(),interval 2 day),?,?,now()),"
                        + " (?,?,?,1,1,0,date_add(now(),interval 1 day),date_add(now(),interval 2 day),?,?,now())",
                ACTIVITY, OLD_OWNER, "target", TOPIC, CLUB,
                OTHER_ACTIVITY, 2102L, "decoy", OTHER_TOPIC, OTHER_CLUB);
        jdbc.update("insert into oms_ticket(id,member_id,owner_type,owner_id,name,price,total_inventory,remaining_inventory,del_flag,create_time)"
                        + " values (?,?,2,?, 'target-ticket',10.00,10,9,0,now()),"
                        + " (?,?,2,?, 'decoy-ticket',10.00,10,10,0,now())",
                TICKET, OLD_OWNER, ACTIVITY, OTHER_TICKET, 2102L, OTHER_ACTIVITY);
        jdbc.update("insert into cms_registration(id,registration_no,owner_type,owner_id,activity_title,ticket_id,ticket_name,ticket_price,member_id,"
                        + "order_num,original_amount,total_amount,point_used,payable_amount,payment_status,payment_time,wechat_payment_amount,"
                        + "registration_status,pay_expire_time,verification_status,create_time)"
                        + " values (?,?,2,?,'target',?,'target-ticket',10.00,?,1,10.00,10.00,0,10.00,2,now(),10.00,2,date_add(now(),interval 1 hour),0,now())",
                REGISTRATION, "P1002-REG", ACTIVITY, TICKET, 3101L);
        jdbc.update("insert into club_event_series(id,club_id,topic_id,recurrence_type,timezone,default_lead_member_id,"
                        + "waitlist_enabled,offer_minutes,status,version,created_by_member_id,request_id,request_fingerprint,create_time)"
                        + " values (601,?,?, 'ONCE','Asia/Shanghai',?,1,60,'ACTIVE',0,?,'p1002-target',?,now()),"
                        + " (602,?,?, 'ONCE','Asia/Shanghai',?,1,60,'ACTIVE',0,?,'p1002-decoy',?,now())",
                CLUB, TOPIC, OLD_OWNER, OLD_OWNER,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                OTHER_CLUB, OTHER_TOPIC, 2102L, 2102L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        jdbc.update("insert into club_event_occurrence(id,series_id,activity_id,occurrence_at,default_lead_member_id,status,version,create_time)"
                        + " values (701,601,?,date_add(now(),interval 1 day),?,'ACTIVE',0,now()),"
                        + " (702,602,?,date_add(now(),interval 1 day),?,'ACTIVE',0,now())",
                ACTIVITY, OLD_OWNER, OTHER_ACTIVITY, 2102L);
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,version)"
                        + " values (801,?,?,?,3201,'WAITING',now(),0),(802,?,?,?,3202,'WAITING',now(),0)",
                CLUB, ACTIVITY, TICKET, OTHER_CLUB, OTHER_ACTIVITY, OTHER_TICKET);
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(url, mysql.user(), mysql.password());
    }

    private long selectOwner(Connection connection, boolean lock) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select member_id from club where id=" + CLUB + (lock ? " for update" : ""))) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private long number(String query, Object... args) {
        Number value = jdbc.queryForObject(query, args, Number.class);
        return value == null ? -1L : value.longValue();
    }

    private String scalar(String query, Object... args) {
        return jdbc.queryForObject(query, args, String.class);
    }

    private static final class DbClubAccess implements IClubAccessService {
        private final JdbcTemplate jdbc;
        private final AtomicInteger requireCalls = new AtomicInteger();
        private final java.util.concurrent.CopyOnWriteArrayList<Integer> isolations =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile CountDownLatch firstRead;
        private volatile CountDownLatch releaseFirst;

        private DbClubAccess(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private void pauseFirstRequire(CountDownLatch read, CountDownLatch release) {
            firstRead = read;
            releaseFirst = release;
        }

        private List<Integer> isolations() {
            return isolations;
        }

        @Override
        public ClubAccessContext resolve(Long principalMemberId, Long clubId, Long activityId) {
            return context(principalMemberId, clubId, activityId, false);
        }

        @Override
        public ClubAccessContext resolveWithEventAccesses(Long principalMemberId, Long clubId) {
            return context(principalMemberId, clubId, null, false);
        }

        @Override
        public ClubAccessContext require(Long principalMemberId, Long clubId, Long activityId, String permission) {
            return context(principalMemberId, clubId, activityId, true);
        }

        private ClubAccessContext context(Long principalMemberId, Long clubId, Long activityId, boolean track) {
            Long owner = jdbc.queryForObject("select member_id from club where id=?", Long.class, clubId);
            if (track) {
                Integer isolation = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)
                        connection -> connection.getTransactionIsolation());
                isolations.add(isolation);
                if (requireCalls.incrementAndGet() == 1 && firstRead != null) {
                    firstRead.countDown();
                    try {
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("等待转让行锁超时");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("等待转让被中断", interrupted);
                    }
                }
            }
            java.util.Set<String> roles = principalMemberId.equals(owner)
                    ? Collections.singleton(ClubRoleCode.CLUB_OWNER) : Collections.emptySet();
            return new ClubAccessContext(principalMemberId, clubId, owner, activityId, roles,
                    Collections.<ClubEventAccess>emptyList());
        }
    }
}
