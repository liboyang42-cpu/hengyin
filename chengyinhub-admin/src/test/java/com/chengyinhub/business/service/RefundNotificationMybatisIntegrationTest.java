package com.chengyinhub.business.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.notify.NotifyChannel;
import com.chengyinhub.business.notify.NotifyEvent;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.service.impl.NotifyServiceImpl;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实退款服务、MyBatis CAS 与 MySQL 提交边界；仅通知出口为捕获通道，不连接微信。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundNotificationMybatisIntegrationTest {
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate jdbc;
    private boolean created;
    private TransactionTemplate transaction;
    private IRefundService service;
    private NotifyServiceImpl notifications;
    private final List<NotifyMessage> delivered = new CopyOnWriteArrayList<>();
    private final List<Integer> observedPayoutStates = new CopyOnWriteArrayList<>();

    @BeforeAll void setup() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "refund_notify");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        mysql = MysqlIntegrationTestConfig.from(env, "it");
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        System.out.println("Refund notification MySQL version=" + jdbc.queryForObject("SELECT VERSION()", String.class));
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        int start = base.indexOf("CREATE TABLE `refund_application` (");
        jdbc.execute(base.substring(start, base.indexOf("\n/*!40101 SET", start)));
        // Existing migration_merchant_aftercare_timeout_20260917.sql: RefundApplicationMapper 读 platform_takeover_at(退款台转平台呈现)。
        jdbc.execute("ALTER TABLE refund_application ADD COLUMN platform_takeover_at DATETIME NULL");
        Configuration config = new Configuration(new Environment("refund-notification", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        config.addMapper(RefundApplicationMapper.class);
        String resource = "mapper/business/RefundApplicationMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        RefundApplicationMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config))
                .getMapper(RefundApplicationMapper.class);
        notifications = new NotifyServiceImpl();
        setChannel(this::captureCommittedRefund);
        RefundServiceImpl target = new RefundServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(target, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(target, "refundApplicationMapper", mapper);
        CmsRegistration registration = new CmsRegistration();
        registration.setRegistrationNo("REG11");
        registration.setWechatPaymentAmount(new BigDecimal("30.00"));
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(registration);
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "notifyService", notifications);
        // 2026-09-16: 回调 inbox 去重是 handleRefundNotify 的必经一步(cc36b4552),本套此前从未真跑过
        // ⇒ 手工装配漏注入即 NPE。inbox 语义另有 WxPaymentCallbackInboxServiceImplTest 覆盖,这里桩掉。
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService",
                mock(IWxPaymentCallbackInboxService.class));
        DataSourceTransactionManager tx = new DataSourceTransactionManager(ds);
        transaction = new TransactionTemplate(tx);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IRefundService.class);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        service = (IRefundService) proxy.getProxy();
    }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM refund_application");
        delivered.clear();
        observedPayoutStates.clear();
        setChannel(this::captureCommittedRefund);
        jdbc.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,member_id,paid_amount,refund_amount,status,payout_status) VALUES(7,'RF11','registration',11,91,30,12.50,1,0)");
    }

    @AfterAll void cleanup() {
        if (created) new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE " + mysql.databaseName());
    }

    @Test void confirmedCashRefundNotifiesPayerOnlyAfterCommitAndOnlyOnce() {
        transaction.executeWithoutResult(tx -> {
            assertTrue(service.handleRefundNotify(facts("SUCCESS")));
            assertTrue(delivered.isEmpty(), "提交前不能发送到账通知");
        });
        assertEquals(1, payoutStatus());
        assertEquals(1, delivered.size(), "真正到账后必须保留通知能力");
        assertEquals(Collections.singletonList(1), observedPayoutStates, "通知出口从独立连接已读到提交后的SUCCESS");
        assertEquals(NotifyEvent.REFUND, delivered.get(0).event());
        assertEquals(91L, delivered.get(0).memberId());
        assertEquals("12.50", delivered.get(0).get("amount"), "取退款单真实部分退款额，不取原支付额30");
        assertTrue(service.handleRefundNotify(facts("SUCCESS")));
        assertEquals(1, delivered.size(), "重复回调不能再通知");
    }

@Test void manualVerificationNotifiesAfterCommitButNeverOnRollback() {
        jdbc.update("UPDATE refund_application SET payout_status=3,payout_handler_id=41 WHERE id=7");
        transaction.executeWithoutResult(tx -> {
            assertEquals(1, service.verifyManualPayout(7L, 42L));
            assertTrue(delivered.isEmpty(), "人工复核事务提交前不能发送");
            tx.setRollbackOnly();
        });
        assertEquals(3, payoutStatus());
        assertTrue(delivered.isEmpty(), "人工复核回滚不得发送");
        transaction.executeWithoutResult(tx -> {
            assertEquals(1, service.verifyManualPayout(7L, 42L));
            assertTrue(delivered.isEmpty());
        });
        assertEquals(4, payoutStatus());
        assertEquals(1, delivered.size());
        assertEquals(Collections.singletonList(4), observedPayoutStates);
        assertEquals(91L, delivered.get(0).memberId());
        assertEquals("12.50", delivered.get(0).get("amount"));
        assertEquals(0, service.verifyManualPayout(7L, 43L));
        assertEquals(1, delivered.size());
    }

@Test void cancellationNeverClaimsCashArrivedForCashZeroOrPointsOnly() {
        for (long points : new long[]{0, 20}) {
            for (String cash : new String[]{"0", "30.00"}) {
                delivered.clear();
                IRefundPayoutService payout = mock(IRefundPayoutService.class);
                TeamGroupServiceImpl cancellation = cancellation(cash, points, payout);
                transaction.executeWithoutResult(tx -> {
                    assertEquals(1, cancellation.cancelPaidRegistration(11L, 91L));
                    assertTrue(delivered.isEmpty(), "取消受理不能发送到账: cash=" + cash + " points=" + points);
                    verifyNoInteractions(payout);
                });
                assertTrue(delivered.isEmpty(), "提交取消也不等于渠道到账");
                if ("0".equals(cash)) verifyNoInteractions(payout);
                else verify(payout).payoutAfterReview(7L);
            }
        }
    }

    private TeamGroupServiceImpl cancellation(
            String cash, long points, IRefundPayoutService payout) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(11L); registration.setMemberId(91L);
        registration.setPaymentStatus(2); registration.setVerificationStatus(0);
        registration.setOwnerType(2L); registration.setOwnerId(22L);
        registration.setWechatPaymentAmount(new BigDecimal(cash)); registration.setPointUsed(points);
        CmsRegistrationMapper registrations =
                mock(CmsRegistrationMapper.class);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(registration);
        when(registrations.claimRegistrationRefunded(
                eq(11L), any(), any())).thenReturn(1);
        when(registrations.claimPointsReturn(
                eq(11L), any(), any())).thenReturn(1);
        UmsMemberMapper members =
                mock(UmsMemberMapper.class);
        when(members.addPoints(91L, (int) points)).thenReturn(1);
        CmsActivity activity = new CmsActivity();
        // 场次退款窗口 = 集合前 24 小时(RefundPolicy,5.9 拍板);+24h 恰好落在边界,
        // 执行时多走一毫秒即判「已错过集合前24小时」—— 用 +72h 表示「远未到窗口」。
        activity.setStartDate(new Date(System.currentTimeMillis() + 3 * 86400000L));
        CmsActivityMapper activities =
                mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(22L)).thenReturn(activity);
        IRefundService application = mock(IRefundService.class);
        when(application.saveRefund(any())).thenAnswer(call -> {
            ((RefundApplication) call.getArgument(0)).setId(7L);
            return 1;
        });
        TeamGroupServiceImpl target =
                new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(target, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "umsMemberMapper", members);
        ReflectionTestUtils.setField(target, "refundService", application);
        ReflectionTestUtils.setField(target, "refundPayoutService", payout);
        ReflectionTestUtils.setField(target, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(target, "notifyService", notifications);
        return target;
    }

@Test void rolledBackSuccessRemainsRetryableAndOnlyCommittedRetryNotifies() {
        transaction.executeWithoutResult(tx -> {
            assertTrue(service.handleRefundNotify(facts("SUCCESS")));
            assertEquals(1, payoutStatus());
            tx.setRollbackOnly();
        });
        assertEquals(0, payoutStatus());
        assertTrue(delivered.isEmpty());
        assertTrue(service.handleRefundNotify(facts("SUCCESS")));
        assertEquals(1, payoutStatus());
        assertEquals(1, delivered.size());
    }

    @Test void concurrentSuccessCallbacksProduceOneNotification() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> callback = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return service.handleRefundNotify(facts("SUCCESS"));
            };
            Future<Boolean> a = pool.submit(callback);
            Future<Boolean> b = pool.submit(callback);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(a.get(10, TimeUnit.SECONDS));
            assertTrue(b.get(10, TimeUnit.SECONDS));
            assertEquals(1, payoutStatus());
            assertEquals(1, delivered.size());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test void processingAndManualPendingCannotClaimArrival() {
        assertTrue(service.handleRefundNotify(facts("PROCESSING")));
        assertTrue(delivered.isEmpty());
        jdbc.update("UPDATE refund_application SET payout_status=2 WHERE id=7");
        assertTrue(service.handleRefundNotify(facts("PROCESSING")));
        assertTrue(delivered.isEmpty());
        jdbc.update("UPDATE refund_application SET payout_status=3,payout_handler_id=41 WHERE id=7");
        assertThrows(IllegalStateException.class, () -> service.verifyManualPayout(7L, 41L));
        assertTrue(delivered.isEmpty());
        assertEquals(3, payoutStatus());
    }

    @Test void failedOrThrowingChannelIsLoggedWithoutUndoingConfirmedRefund() {
        Logger logger = (Logger)
                LoggerFactory.getLogger(NotifyServiceImpl.class);
        ListAppender<ILoggingEvent> logs =
                new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
            for (boolean throwsFailure : new boolean[]{false, true}) {
                jdbc.update("UPDATE refund_application SET payout_status=0 WHERE id=7");
                AtomicInteger attempted = new AtomicInteger();
                setChannel(message -> {
                    attempted.incrementAndGet();
                    if (throwsFailure) throw new IllegalStateException("controlled delivery failure");
                    return false;
                });
                assertTrue(service.handleRefundNotify(facts("SUCCESS")));
                assertEquals(1, payoutStatus());
                assertEquals(1, attempted.get());
                assertTrue(service.handleRefundNotify(facts("SUCCESS")));
                assertEquals(1, attempted.get(), "当前best-effort没有重试合同，不能把重复回调当重发");
            }
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("全部通道未送达")));
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("通道违约抛出异常")));
        } finally {
            logger.detachAppender(logs); logs.stop();
        }
    }

    @Test void malformedRecipientOrZeroAmountNeverBecomesFakeCashNotice() {
        for (String change : new String[]{"member_id=NULL", "member_id=0", "refund_amount=0"}) {
            jdbc.update("UPDATE refund_application SET payout_status=0,member_id=91,refund_amount=12.50 WHERE id=7");
            jdbc.update("UPDATE refund_application SET " + change + " WHERE id=7");
            boolean zeroAmount = "refund_amount=0".equals(change);
            assertEquals(!zeroAmount, service.handleRefundNotify(facts("SUCCESS")));
            assertEquals(zeroAmount ? 0 : 1, payoutStatus());
            assertTrue(delivered.isEmpty());
        }
    }

    @Test void cancellationRollbackDispatchesNeitherMoneyNorArrivalNotification() {
        IRefundPayoutService payout = mock(IRefundPayoutService.class);
        TeamGroupServiceImpl cancellation = cancellation("30.00", 0, payout);
        transaction.executeWithoutResult(tx -> {
            assertEquals(1, cancellation.cancelPaidRegistration(11L, 91L));
            tx.setRollbackOnly();
        });
        assertTrue(delivered.isEmpty());
        verifyNoInteractions(payout);
    }

    private static Map<String, String> facts(String status) {
        return Map.of("outRefundNo", "RF11", "outTradeNo", "REG11", "status", status,
                "refund", "1250", "total", "3000", "currency", "CNY");
    }

    private int payoutStatus() {
        return jdbc.queryForObject("SELECT payout_status FROM refund_application WHERE id=7", Integer.class);
    }

private boolean captureCommittedRefund(NotifyMessage message) {
        // 新数据源以独立连接回读，不能拿发送线程自己的未提交事务证明已提交。
        JdbcTemplate observer = new JdbcTemplate(new DriverManagerDataSource(
                mysql.databaseUrl(), mysql.user(), mysql.password()));
        int committed = observer.queryForObject(
                "SELECT payout_status FROM refund_application WHERE id=7", Integer.class);
        observedPayoutStates.add(committed);
        delivered.add(message);
        return true;
    }

    private void setChannel(NotifyChannel channel) {
        ReflectionTestUtils.setField(notifications, "channels", Collections.singletonList(channel));
    }
}
