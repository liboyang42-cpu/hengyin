package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ManualPayoutDTO;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.RefundPayoutServiceImpl;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.task.RefundReconcileTask;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.RefundController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款派发的真实 MyBatis/数据库 CAS 门禁；仅微信适配器使用 mock。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundPayoutMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("refund_payout_it");

    private DataSource dataSource;
    private IRefundPayoutService payoutService;
    private IWechatPayService wechatPayService;
    private IRefundService refundService;
    private RefundApplicationMapper refundApplicationMapper;
    private CmsRegistrationPaymentFactMapper consumptionMapper;
    private DataSourceTransactionManager tx;
    private WechatVerifiedPaymentFactService paymentFacts;
    private Reference originalPayment;

    @BeforeAll
    void createDatabaseAndService() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            // ★ 2026-08-19:先 DROP 再 CREATE,不接受"环境里恰好剩下一个同名库"。
            // 原来是 CREATE DATABASE IF NOT EXISTS ⇒ 本类默默继承上一次(可能是崩在半路的那次)留下的表,
            // 于是 @BeforeEach 建表撞 "Table 'xxx' already exists" —— #741 的资金闸就是这么连红的:
            // 本地怎么跑都绿,只有 runner 上那个库有残留。库名本来就一类一个(见 MysqlIntegrationTestConfig),
            // 这里补的是另一半:**每次开跑都从零建**,隔离才是完整的。
            // 同一条规矩在 business/it/ExploreItDatabase 已经这么写了,这里对齐。
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        refundApplicationMapper = template.getMapper(RefundApplicationMapper.class);
        tx = new DataSourceTransactionManager(dataSource);
        consumptionMapper = template.getMapper(CmsRegistrationPaymentFactMapper.class);
        paymentFacts = WechatVerifiedFactTestSupport.realFacts(dataSource);

        RefundServiceImpl refundTarget = new RefundServiceImpl();
        ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", refundApplicationMapper);
        ProxyFactory refundProxyFactory = new ProxyFactory(refundTarget);
        refundProxyFactory.setInterfaces(IRefundService.class);
        refundProxyFactory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        refundService = (IRefundService) refundProxyFactory.getProxy();

        wechatPayService = mock(IWechatPayService.class);
        RefundPayoutServiceImpl target = new RefundPayoutServiceImpl();
        ReflectionTestUtils.setField(target, "refundApplicationMapper", refundApplicationMapper);
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", template.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "refundService", refundService);
        ReflectionTestUtils.setField(target, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(target, "payoutTransactions", tx);
        ReflectionTestUtils.setField(target, "paymentConsumption", new RegistrationPaymentConsumptionReadService(consumptionMapper));
        payoutService = target;
    }

    @BeforeEach
    void resetSchema() throws Exception {
        assertEquals("1",System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
        assertEquals("1",System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        assertEquals("1",System.getenv("A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"));
        // Only this class-owned scratch schema; keep canonical receipt triggers/FKs enabled.
        execute("DROP TABLE IF EXISTS cms_registration_payment_fact", "DROP TABLE IF EXISTS wx_payment_callback_fact_link",
                "DROP TABLE IF EXISTS wx_verified_payment_fact_rejection", "DROP TABLE IF EXISTS wx_verified_payment_fact",
                "DROP TABLE IF EXISTS wx_payment_callback_outbox", "DROP TABLE IF EXISTS wx_payment_callback_inbox",
                "DROP TABLE IF EXISTS wx_pay_order_intent",
                "DROP TABLE IF EXISTS refund_application", "DROP TABLE IF EXISTS cms_registration",
                "DROP TABLE IF EXISTS cms_topic", "DROP TABLE IF EXISTS cms_activity");
        execute(
                "CREATE TABLE cms_registration ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, registration_no VARCHAR(32) NOT NULL, parent_order_no VARCHAR(64), request_id VARCHAR(64), request_fingerprint CHAR(64),"
                        + "owner_type BIGINT, owner_id BIGINT, activity_title VARCHAR(200), ticket_id BIGINT, ticket_name VARCHAR(100), ticket_price DECIMAL(10,2),"
                        + "member_id BIGINT, real_name VARCHAR(768), phone VARCHAR(256), email VARCHAR(768), address VARCHAR(768), address_id BIGINT,"
                        + "participate_date VARCHAR(64), order_num BIGINT, original_amount DECIMAL(10,2), discount_amount DECIMAL(10,2), total_amount DECIMAL(10,2),"
                        + "point_used BIGINT, point_deduct_amount DECIMAL(10,2), point_deduct_rate DECIMAL(5,4), max_point_deduct DECIMAL(10,2), payable_amount DECIMAL(10,2),"
                        + "payment_status INT, payment_type INT, payment_time DATETIME(3), transaction_id VARCHAR(64), point_payment_amount DECIMAL(10,2),"
                        + "wechat_payment_amount DECIMAL(10,2), fee_platform_rate DECIMAL(10,4), registration_status INT, pay_expire_time DATETIME(3), topic_id BIGINT, purchase_kind INT,"
                        + "refund_policy_code VARCHAR(64), refund_policy_version INT, refund_deadline DATETIME(3), expires_at DATETIME(3),"
                        + "create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3), verification_code VARCHAR(100),"
                        + "qrcode_url VARCHAR(255), verification_status INT, verification_time DATETIME(3), first_successful_scan_at DATETIME, verifier_id BIGINT, points_returned INT) ENGINE=InnoDB",
                "CREATE TABLE refund_application (id BIGINT PRIMARY KEY AUTO_INCREMENT, refund_no VARCHAR(64), source_type VARCHAR(64), source_id BIGINT,"
                        + "owner_type BIGINT, owner_id BIGINT, member_id BIGINT, merchant_id BIGINT, paid_amount DECIMAL(12,2), refund_amount DECIMAL(12,2),"
                        + "status INT, reason VARCHAR(500), reject_reason VARCHAR(500), reviewer_id BIGINT, reviewed_at DATETIME(3), created_by_user_id BIGINT,"
                        + "adjustment_created INT, idempotency_key VARCHAR(255), del_flag INT, payout_status INT, payout_method VARCHAR(64),"
                        + "payout_receipt_url VARCHAR(500), payout_handler_id BIGINT, payout_handled_at DATETIME(3), payout_reviewer2_id BIGINT,"
                        + "payout_reviewed2_at DATETIME(3), payout_remark VARCHAR(500), payout_lease_token VARCHAR(64), payout_lease_owner BIGINT,"
                        + "payout_lease_expires_at DATETIME(3), create_time DATETIME(3), update_time DATETIME(3),"
                        // migration_merchant_aftercare_timeout_20260917.sql:RefundApplicationMapper 读该列
                        + "platform_takeover_at DATETIME NULL,"
                        + "UNIQUE KEY uk_refund_no(refund_no)) ENGINE=InnoDB",
                // 费率快照回填的两条来源表：①自玩单 owner_id→cms_topic;②活动单 owner_id→cms_activity→cms_topic。
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, fee_platform_rate DECIMAL(10,4)) ENGINE=InnoDB",
                "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT) ENGINE=InnoDB");
        java.nio.file.Path root=java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if ("chengyinhub-admin".equals(root.getFileName().toString())) root=root.getParent();
        try (Connection connection=dataSource.getConnection()) {
            for (String migration:new String[]{"migration_20261023_wx_pay_order_intent.sql",
                    "migration_wx_payment_callback_inbox_outbox_20260725.sql",
                    "migration_20261024_wx_verified_payment_fact.sql","migration_20261024_registration_payment_fact.sql"})
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(root.resolve("chengyinhub-admin/sql/"+migration)));
        }
        execute("INSERT INTO cms_topic(id,fee_platform_rate) VALUES(500,0.1000)",
                "INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,topic_id,payable_amount,wechat_payment_amount,fee_platform_rate,payment_status,registration_status) VALUES (11,'REG-PAYOUT-11',1,500,500,100,100,0.1000,1,1)",
                "INSERT INTO refund_application(id,refund_no,source_type,source_id,refund_amount,status,reason,del_flag,payout_status,create_time,update_time)"
                        + " VALUES (20,'RF11','registration',11,100,1,'测试退款',0,5,NOW(),NOW())");
        originalPayment=paymentFacts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "merchant","wx-original","REG-PAYOUT-11","REG-PAYOUT-11","REG-PAYOUT-11","TX11","TX11",
                "JSAPI","CNY",10000,10000,java.time.Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),null,"SUCCESS"));
        new TransactionTemplate(tx).executeWithoutResult(status -> {
            new JdbcTemplate(dataSource).update("update cms_registration set payment_status=2,registration_status=2,transaction_id='TX11' where id=11");
            assertEquals(1,consumptionMapper.insertConsumed(11L,originalPayment.id(),originalPayment.hash()));
        });
        assertEquals(originalPayment,consumptionMapper.selectByRegistration(11L).reference());
        org.mockito.Mockito.reset(wechatPayService);
        when(wechatPayService.submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.ACCEPTED);
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentDispatchCallsWechatOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> payoutAfter(ready, start));
            Future<?> b = pool.submit(() -> payoutAfter(ready, start));
            ready.await();
            start.countDown();
            a.get();
            b.get();
            verify(wechatPayService, times(1)).submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any());
            assertEquals(RefundApplication.PAYOUT_PROCESSING,
                    number("SELECT payout_status FROM refund_application WHERE id=20"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void processingRefundIsPolledNotRedispatched() throws Exception {
        execute("UPDATE refund_application SET payout_status=0 WHERE id=20");
        payoutService.payoutAfterReview(20L);
        verify(wechatPayService, times(0)).submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any());
        assertEquals(RefundApplication.PAYOUT_PROCESSING,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
    }

    @Test
    void staleDispatchingCanBeReclaimedOnlyForOriginalQuery() throws Exception {
        execute("UPDATE refund_application SET payout_status=6,update_time=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id=20");
        when(wechatPayService.queryRefundFacts(originalPayment,"RF11")).thenReturn(java.util.Map.of(
                "outRefundNo","RF11","outTradeNo","REG-PAYOUT-11","transactionId","TX11",
                "currency","CNY","refund","10000","total","10000","status","PROCESSING"));
        payoutService.payoutAfterReview(20L);
        verify(wechatPayService, times(0)).submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any());
        verify(wechatPayService).queryRefundFacts(originalPayment,"RF11");
        assertEquals(RefundApplication.PAYOUT_PROCESSING,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
    }

    @Test
    void uncertainWechatCallKeepsOriginalDispatchWithoutRedispatch() throws Exception {
        when(wechatPayService.submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.UNKNOWN);
        payoutService.payoutAfterReview(20L);
        assertEquals(RefundApplication.PAYOUT_DISPATCHING,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
        payoutService.payoutAfterReview(20L);
        verify(wechatPayService,times(1)).submitRefund(eq(originalPayment),eq("REG-PAYOUT-11"),eq("RF11"),any(),any(),any());
        assertEquals(originalPayment,consumptionMapper.selectByRegistration(11L).reference());
    }

    @Test
    void reclaimedLeaseFencesLateReleaseAndResultFromOldWorker() throws Exception {
        String first = "lease-first";
        String second = "lease-second";
        assertEquals(1, refundApplicationMapper.claimPayoutDispatch(20L, 30L, first));
        execute("UPDATE refund_application SET update_time=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id=20");
        assertEquals(1, refundApplicationMapper.claimPayoutDispatch(20L, 30L, second));

        assertEquals(0, refundApplicationMapper.releasePayoutDispatch(20L, first, "旧 worker 释放"));
        assertEquals(0, refundApplicationMapper.updatePayoutResultWithLease(20L, first,
                RefundApplication.PAYOUT_PENDING_MANUAL, "旧 worker 结果"));

        assertEquals(RefundApplication.PAYOUT_DISPATCHING,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertEquals(second, text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
    }

    @Test
    void manualClaimAllowsOnlyOneOperatorAndLostCompletionResponseIsIdempotent() throws Exception {
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");

        String token = refundService.claimManualPayout(20L, 100L);
        assertNotNull(token);
        assertNull(refundService.claimManualPayout(20L, 200L));

        assertEquals(1, refundService.handleManualPayout(
                20L, "offline_transfer", "/profile/upload/receipt.png", "done", 100L, token));
        assertEquals(1, refundService.handleManualPayout(
                20L, "offline_transfer", "/profile/upload/receipt.png", "done", 100L, token));
        assertEquals(3, number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertEquals(100, number("SELECT payout_handler_id FROM refund_application WHERE id=20"));
    }

    @Test
    void expiredManualLeaseCannotBeStolenAfterMoneyMayHaveMoved() throws Exception {
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String token = refundService.claimManualPayout(20L, 100L);
        assertNotNull(token);
        execute("UPDATE refund_application SET payout_lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=20");

        assertNull(refundService.claimManualPayout(20L, 200L));
        assertEquals(100, number("SELECT payout_lease_owner FROM refund_application WHERE id=20"));
        assertEquals(token, text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
    }

    @Test
    void expiredManualLeaseCannotWriteUntilOwnerRenews() throws Exception {
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String token = refundService.claimManualPayout(20L, 100L);
        assertNotNull(token);
        execute("UPDATE refund_application SET payout_lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=20");

        assertEquals(0, refundApplicationMapper.completeManualPayout(
                20L, "offline_transfer", "receipt-after-timeout", 100L, new java.util.Date(),
                "过期 token 不得补登记", token));
        assertEquals(2, number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertNull(text("SELECT payout_handler_id FROM refund_application WHERE id=20"));

        String renewed = refundService.claimManualPayout(20L, 100L);
        assertNotNull(renewed, "已线下付款后需要补登记时，原 owner 必须先显式续租");
        assertEquals(1, refundApplicationMapper.completeManualPayout(
                20L, "offline_transfer", "receipt-after-renew", 100L, new java.util.Date(),
                "续租后补登记", renewed));
        assertEquals(3, number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertEquals(100, number("SELECT payout_handler_id FROM refund_application WHERE id=20"));
    }

    @Test
    void manualCompletionRequiresExactUnexpiredToken() throws Exception {
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String token = refundService.claimManualPayout(20L, 100L);
        assertNotNull(token);

        assertEquals(0, refundApplicationMapper.completeManualPayout(
                20L, "offline_transfer", "wrong-token", 100L, new java.util.Date(),
                "错误 token", "other-token"));
        assertEquals(2, number("SELECT payout_status FROM refund_application WHERE id=20"));

        execute("UPDATE refund_application SET payout_lease_expires_at=NULL WHERE id=20");
        assertEquals(0, refundApplicationMapper.completeManualPayout(
                20L, "offline_transfer", "null-expiry", 100L, new java.util.Date(),
                "空租约期限", token));
        assertEquals(2, number("SELECT payout_status FROM refund_application WHERE id=20"));

        execute("UPDATE refund_application SET payout_lease_expires_at=DATE_ADD(NOW(),INTERVAL 1 MINUTE) WHERE id=20");
        assertEquals(1, refundApplicationMapper.completeManualPayout(
                20L, "offline_transfer", "valid-token", 100L, new java.util.Date(),
                "有效 token", token));
        assertEquals(3, number("SELECT payout_status FROM refund_application WHERE id=20"));
    }

    @Test
    void expiredManualLeaseCanBeReleasedByItsOwnerAndRequeued() throws Exception {
        // M1:租约过期后 claim 要求 token IS NULL、release 又要求未过期 ⇒ 这笔人工退款永久卡死,
        // 队列里既处理不了也放不掉。释放不动钱,owner 任何时候都该能放回队列。
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String token = refundService.claimManualPayout(20L, 100L);
        assertNotNull(token);
        execute("UPDATE refund_application SET payout_lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=20");

        assertTrue(refundService.releaseManualPayoutClaim(20L, 100L, token));

        assertNull(text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
        assertNotNull(refundService.claimManualPayout(20L, 200L), "释放后该单必须能被重新领取");
    }

    @Test
    void releaseIsFencedToLeaseOwnerAndExactToken() throws Exception {
        // 负控:释放同样是 owner+token 双条件,别人和旧 token 都不许把在途租约摘掉。
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String token = refundService.claimManualPayout(20L, 100L);
        assertNotNull(token);

        assertFalse(refundService.releaseManualPayoutClaim(20L, 200L, token), "非 owner 不得释放");
        assertFalse(refundService.releaseManualPayoutClaim(20L, 100L, "other-token"), "旧 token 不得释放");
        assertEquals(token, text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
    }

    @Test
    void sameOperatorCanRenewOwnExpiredLease() throws Exception {
        // 处理人自己的浏览器丢了 token / 超时回来续租,是同一个人,不涉及跨人接管。
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String first = refundService.claimManualPayout(20L, 100L);
        assertNotNull(first);
        execute("UPDATE refund_application SET payout_lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=20");

        String renewed = refundService.claimManualPayout(20L, 100L);

        assertNotNull(renewed, "同一处理人必须能续自己的过期租约");
        assertEquals(renewed, text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
        assertEquals(100, number("SELECT payout_lease_owner FROM refund_application WHERE id=20"));
    }

    @Test
    void expiredLeaseTakeoverIsAuditedAndOnlyAfterExpiry() throws Exception {
        execute("UPDATE refund_application SET payout_status=2,payout_lease_token=NULL,payout_lease_owner=NULL,"
                + "payout_lease_expires_at=NULL WHERE id=20");
        String owned = refundService.claimManualPayout(20L, 100L);
        assertNotNull(owned);

        assertNull(refundService.takeoverExpiredManualPayout(20L, 200L), "未过期的租约不许接管");

        execute("UPDATE refund_application SET payout_lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=20");
        String taken = refundService.takeoverExpiredManualPayout(20L, 200L);

        assertNotNull(taken);
        assertEquals(200, number("SELECT payout_lease_owner FROM refund_application WHERE id=20"));
        assertTrue(text("SELECT payout_remark FROM refund_application WHERE id=20").contains("100"),
                "接管必须把前一个 owner 写进备注留痕");
        assertEquals(0, refundApplicationMapper.completeManualPayout(20L, "offline_transfer", "r",
                100L, new java.util.Date(), "旧 owner 迟到登记", owned),
                "被接管后旧 owner 的 token 必须失效,不能双人各自登记一次");
    }

    @Test
    void refundNoConflictEndsInManualQueueInsteadOfStuckDispatching() throws Exception {
        // M2:uk_refund_no 被另一张退款单占着时,ensureRefundNo 抛 1062 被 payoutAfterReview 吞掉,
        // 记录永远停在 DISPATCHING(6),对账每 30 分钟重试一次且待人工积压计数看不见它。
        execute("UPDATE refund_application SET refund_no=NULL WHERE id=20",
                "INSERT INTO refund_application(id,refund_no,source_type,source_id,refund_amount,status,"
                        + "del_flag,payout_status,create_time,update_time) "
                        + "VALUES (21,'RF11','registration',11,1,1,0,2,NOW(),NOW())");

        payoutService.payoutAfterReview(20L);

        verify(wechatPayService, times(0)).submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any());
        assertEquals(RefundApplication.PAYOUT_PENDING_MANUAL,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertTrue(text("SELECT payout_remark FROM refund_application WHERE id=20").contains("RF11"));
    }

    @Test
    void stuckDispatchIsCountedForAlarmNotOnlyManualQueue() throws Exception {
        // 积压计数只数 (2,3),卡在 5/6 的单一个都不告警 —— 正是 M2 里"退款不动、零告警"的那半边。
        execute("UPDATE refund_application SET payout_status=6,"
                + "update_time=DATE_SUB(NOW(),INTERVAL 120 MINUTE) WHERE id=20");

        assertEquals(0, refundApplicationMapper.countManualPayoutBacklog());
        assertEquals(1, refundApplicationMapper.countStuckPayoutDispatch(30));
    }

    @Test
    void conflictEscalatedRefundWithoutRefundNoCanBeClaimedManuallyViaController() throws Exception {
        // H-A:冲突单落人工队列时本行 refund_no 仍为 NULL(上一用例已证 1062 发生在提交微信之前)。
        // 旧 controller 闸拿 null 去问微信恒无终态 ⇒ claim 永久被拒,又没有任何补号入口。
        // 修后:从未提交微信的单直接放行人工处理,微信压根不该被问。
        execute("UPDATE refund_application SET refund_no=NULL WHERE id=20",
                "INSERT INTO refund_application(id,refund_no,source_type,source_id,refund_amount,status,"
                        + "del_flag,payout_status,create_time,update_time) "
                        + "VALUES (21,'RF11','registration',11,1,1,0,2,NOW(),NOW())");
        payoutService.payoutAfterReview(20L);
        assertEquals(RefundApplication.PAYOUT_PENDING_MANUAL,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertNull(text("SELECT refund_no FROM refund_application WHERE id=20"));

        AjaxResult result = manualController(100L).manualClaim(manualDto(20L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG),
                "无 refund_no 的冲突单必须能进入人工处理,而不是被微信终态闸永久挡死");
        verify(wechatPayService, times(0)).refreshPayoutFromWx(any());
        assertEquals(100, number("SELECT payout_lease_owner FROM refund_application WHERE id=20"));
        assertNotNull(text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
    }

    @Test
    void manualClaimWithRefundNoStillGatedByWechatTerminalStateOnRealRow() throws Exception {
        // 负控配对:已有 refund_no 的单微信终态闸一寸不松 —— 在途(PROCESSING)禁止签发人工处理权,
        // 且数据库里不许留下任何租约痕迹。
        execute("UPDATE refund_application SET payout_status=2 WHERE id=20");
        when(wechatPayService.refreshPayoutFromWx("RF11")).thenReturn("PROCESSING");

        AjaxResult result = manualController(100L).manualClaim(manualDto(20L));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertNull(text("SELECT payout_lease_token FROM refund_application WHERE id=20"));
        assertNull(text("SELECT payout_lease_owner FROM refund_application WHERE id=20"));
    }

    @Test
    void blankRefundNoManualClaimConcurrentOperatorsOnlyOneWins() throws Exception {
        // 并发负控:绕过微信闸不等于绕过租约 CAS —— 两个操作员同时领同一张无号冲突单,恰好一人成功。
        execute("UPDATE refund_application SET refund_no=NULL,payout_status=2 WHERE id=20");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> a = pool.submit(() -> claimAfter(ready, start, 100L));
            Future<AjaxResult> b = pool.submit(() -> claimAfter(ready, start, 200L));
            ready.await();
            start.countDown();
            int wins = 0;
            wins += Integer.valueOf(200).equals(a.get().get(AjaxResult.CODE_TAG)) ? 1 : 0;
            wins += Integer.valueOf(200).equals(b.get().get(AjaxResult.CODE_TAG)) ? 1 : 0;
            assertEquals(1, wins, "无号冲突单的并发领取必须恰好一人成功");
            assertEquals(1, number("SELECT COUNT(*) FROM refund_application WHERE id=20 "
                    + "AND payout_lease_owner IN (100,200) AND payout_lease_token IS NOT NULL"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retryRefreshesUpdateTimeSoStuckCountMustBeCountedBeforeRetry() throws Exception {
        // H-B 前提坐实(真 SQL):对卡滞单重试一次(claim→微信异常→保留原派发)后 update_time 被刷新,
        // 同一 stale 谓词立即数到 0 —— 这就是"先重试后计数"的旧顺序恒 0 的机理。
        execute("UPDATE refund_application SET payout_status=5,"
                + "update_time=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id=20");
        assertEquals(1, refundApplicationMapper.countStuckPayoutDispatch(30));
        when(wechatPayService.submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("微信网络异常"));

        payoutService.payoutAfterReview(20L);

        assertEquals(RefundApplication.PAYOUT_DISPATCHING,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
        assertEquals(0, refundApplicationMapper.countStuckPayoutDispatch(30),
                "重试后同一 stale 谓词必然数到 0 —— 卡滞计数必须发生在重试之前");
    }

    @Test
    void reconcileStillAlarmsStuckDispatchAlthoughRetryRefreshesUpdateTime() throws Exception {
        // H-B 红绿钉(真 SQL 全链路):本轮重试刷新 update_time 后,告警仍必须报出扫描时的卡滞数。
        // 旧顺序(先重试后计数)下 countStuckPayoutDispatch 恒 0、"退款派发卡滞"永久沉默 —— 本用例红。
        execute("UPDATE refund_application SET payout_status=5,"
                + "update_time=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id=20");
        when(wechatPayService.submitRefund(eq(originalPayment), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("微信网络异常"));
        AlarmService alarmService = mock(AlarmService.class);
        RefundReconcileTask task = new RefundReconcileTask();
        // 保证金退款对账是独立队列(RefundReconcileTask#reconcile 首个动作);本用例只钉报名退款的
        // 卡滞计数时序,注入 mock 免去手搭 ICoopInviteService 的依赖。d7593ff37 加了该依赖时漏了本类。
        ReflectionTestUtils.setField(task, "coopInviteService", mock(ICoopInviteService.class));
        ReflectionTestUtils.setField(task, "refundApplicationMapper", refundApplicationMapper);
        ReflectionTestUtils.setField(task, "refundPayoutService", payoutService);
        ReflectionTestUtils.setField(task, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.reconcile();

        assertEquals(RefundApplication.PAYOUT_DISPATCHING,
                number("SELECT payout_status FROM refund_application WHERE id=20"));
        // F-W3-06:卡滞告警改走按键限频出口，事件名与扫描时卡滞数不变。
        verify(alarmService).alarmThrottled(eq("monitor.refund.dispatch_stuck"), eq("退款派发卡滞"), contains("退款单=1"));
    }

    @Test
    void specMigrationRepairsWrongRefundIndexAndResumesAfterMissingColumns() throws Exception {
        execute("ALTER TABLE refund_application DROP INDEX uk_refund_no",
                "ALTER TABLE refund_application ADD INDEX uk_refund_no(source_type,refund_no)",
                "ALTER TABLE refund_application DROP COLUMN payout_lease_token",
                "ALTER TABLE refund_application DROP COLUMN payout_lease_owner",
                "ALTER TABLE refund_application DROP COLUMN payout_lease_expires_at",
                "ALTER TABLE cms_registration DROP COLUMN fee_platform_rate");

        applySpecMigration();
        applySpecMigration();

        assertEquals("0:refund_no", indexDefinition("refund_application", "uk_refund_no"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='refund_application' AND column_name='payout_lease_token'"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='refund_application' AND column_name='payout_lease_owner'"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='refund_application' AND column_name='payout_lease_expires_at'"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='cms_registration' AND column_name='fee_platform_rate'"));
    }

    @Test
    void specMigrationFailsClosedOnDuplicateRefundBusinessNumber() throws Exception {
        execute("ALTER TABLE refund_application DROP INDEX uk_refund_no",
                "INSERT INTO refund_application(id,refund_no,source_type,source_id,status,del_flag) "
                        + "VALUES (21,'RF11','other',99,1,0)");

        Exception error = assertThrows(Exception.class, this::applySpecMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void specMigrationBackfillsSelfPlayFeeSnapshotFromSellerTopicInsteadOfBlocking() throws Exception {
        // H1:生产的真实起点是「列刚建出来,所有存量行都是 NULL」。旧写法在建列后立刻对
        // payable>0 且快照为空的行 fail-closed ⇒ 迁移在真库上永远跑不完,且 ALTER 已经落下=半应用。
        // 正确顺序是先按运行时同源(卖方主题冻结费率)安全回填,再对剩余不可推导的已支付行才变红。
        execute("ALTER TABLE cms_registration DROP COLUMN fee_platform_rate",
                "INSERT INTO cms_topic(id,fee_platform_rate) VALUES (501,0.1000)",
                "INSERT INTO cms_registration(registration_no,owner_type,owner_id,topic_id,payable_amount,"
                        + "wechat_payment_amount,payment_status,registration_status,transaction_id) "
                        + "VALUES ('LEGACY-SELFPLAY',1,501,501,88.00,88.00,2,2,'WX-SELFPLAY')");

        applySpecMigration();
        applySpecMigration();

        assertEquals("0.1000", text("SELECT CAST(fee_platform_rate AS CHAR) FROM cms_registration "
                + "WHERE registration_no='LEGACY-SELFPLAY'"));
    }

    @Test
    void specMigrationBackfillsActivityFeeSnapshotFromSourceTopic() throws Exception {
        execute("ALTER TABLE cms_registration DROP COLUMN fee_platform_rate",
                "INSERT INTO cms_topic(id,fee_platform_rate) VALUES (502,0.0300)",
                "INSERT INTO cms_activity(id,topic_id) VALUES (901,502)",
                "INSERT INTO cms_registration(registration_no,owner_type,owner_id,payable_amount,"
                        + "wechat_payment_amount,payment_status,registration_status,transaction_id) "
                        + "VALUES ('LEGACY-ACTIVITY',2,901,50.00,50.00,2,2,'WX-ACTIVITY')");

        applySpecMigration();

        assertEquals("0.0300", text("SELECT CAST(fee_platform_rate AS CHAR) FROM cms_registration "
                + "WHERE registration_no='LEGACY-ACTIVITY'"));
    }

    @Test
    void specMigrationLeavesUnpaidLegacyOrderWithoutDerivableRateAsExplicitResidual() throws Exception {
        // 存量策略:未支付的历史单永远不会进结算,不允许它把迁移堵死;快照保持 NULL 并在末端回读里显性计数。
        execute("INSERT INTO cms_registration(registration_no,owner_type,owner_id,payable_amount,"
                + "payment_status,registration_status) VALUES ('LEGACY-UNPAID-NO-RATE',2,777,88.00,0,3)");

        applySpecMigration();
        applySpecMigration();

        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration "
                + "WHERE registration_no='LEGACY-UNPAID-NO-RATE' AND fee_platform_rate IS NULL"));
    }

    @Test
    void specMigrationFailsClosedOnPaidOrderWithoutDerivableFeeRate() throws Exception {
        // 可变红配对:同一条单只把 payment_status 从 0 改成 2(已支付=会进结算),就必须红。
        execute("INSERT INTO cms_registration(registration_no,owner_type,owner_id,payable_amount,"
                + "wechat_payment_amount,payment_status,registration_status,transaction_id) "
                + "VALUES ('LEGACY-PAID-NO-RATE',2,777,88.00,88.00,2,2,'WX-NO-RATE')");

        Exception error = assertThrows(Exception.class, this::applySpecMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
    }

    @Test
    void specMigrationPassesPayableOrderWithFeeSnapshotPresent() throws Exception {
        // 可变红配对:上面的红用例(缺 fee 快照)证明 guard 会拦;本绿用例只把唯一变量
        //   fee_platform_rate 补成合法值,证明 guard 恰好只对"已支付但缺费率快照"变红,而不是对所有
        //   payable>0 的单恒红。缺了这条绿,红就可能是"对任何已支付单都恒真"的假证据。
        execute("INSERT INTO cms_registration(registration_no,payable_amount,fee_platform_rate,"
                + "payment_status,registration_status) VALUES ('LEGACY-WITH-FEE',88.00,0.1000,0,1)");

        applySpecMigration();
        applySpecMigration();

        assertEquals("0:refund_no", indexDefinition("refund_application", "uk_refund_no"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_registration "
                + "WHERE payable_amount > 0 AND fee_platform_rate IS NULL"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration "
                + "WHERE registration_no='LEGACY-WITH-FEE'"),
                "合法快照单不该被 half-state 修复动到(payment_status=0 不进修复条件)");
    }

    @Test
    void specMigrationRepairsPaidHalfStateOnlyWithTransactionAndFrozenAmountProof() throws Exception {
        execute("INSERT INTO cms_registration(registration_no,payable_amount,wechat_payment_amount,fee_platform_rate,"
                + "payment_status,registration_status,transaction_id) "
                + "VALUES ('LEGACY-HALF-PROVEN',88.00,88.00,0.1000,2,1,'WX-PROVEN')");

        applySpecMigration();

        assertEquals(2, number("SELECT registration_status FROM cms_registration "
                + "WHERE registration_no='LEGACY-HALF-PROVEN'"));
        assertEquals("funds-half-state-migration", text("SELECT update_by FROM cms_registration "
                + "WHERE registration_no='LEGACY-HALF-PROVEN'"));
    }

    @Test
    void specMigrationFailsClosedOnUnprovenPaidHalfState() throws Exception {
        execute("INSERT INTO cms_registration(registration_no,payable_amount,wechat_payment_amount,fee_platform_rate,"
                + "payment_status,registration_status,transaction_id) "
                + "VALUES ('LEGACY-HALF-UNPROVEN',88.00,87.99,0.1000,2,1,'WX-UNPROVEN')");

        Exception error = assertThrows(Exception.class, this::applySpecMigration);

        assertTrue(allMessages(error).contains("Duplicate entry"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration "
                + "WHERE registration_no='LEGACY-HALF-UNPROVEN'"));
    }

    @Test
    void specMigrationGuardFiresBeforeAnyDataRepairSoNoHalfAppliedState() throws Exception {
        // 「失败不留下半应用状态」:同一次执行里既有可自证的半态又有不可自证的半态时,
        // guard 必须在任何 UPDATE 之前拦下 —— 否则可自证那条已经被改写,而迁移整体是失败的。
        execute("INSERT INTO cms_registration(registration_no,payable_amount,wechat_payment_amount,fee_platform_rate,"
                + "payment_status,registration_status,transaction_id) "
                + "VALUES ('HALF-PROVEN-SIBLING',88.00,88.00,0.1000,2,1,'WX-PROVEN-SIBLING')",
                "INSERT INTO cms_registration(registration_no,payable_amount,wechat_payment_amount,fee_platform_rate,"
                        + "payment_status,registration_status,transaction_id) "
                        + "VALUES ('HALF-UNPROVEN-SIBLING',88.00,87.99,0.1000,2,1,'WX-UNPROVEN-SIBLING')");

        assertThrows(Exception.class, this::applySpecMigration);

        assertEquals(1, number("SELECT registration_status FROM cms_registration "
                + "WHERE registration_no='HALF-PROVEN-SIBLING'"),
                "迁移失败时可自证半态也不能被改写,否则就是半应用状态");
        assertNull(text("SELECT update_by FROM cms_registration "
                + "WHERE registration_no='HALF-PROVEN-SIBLING'"));
    }

    private Void payoutAfter(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        payoutService.payoutAfterReview(20L);
        return null;
    }

    private AjaxResult claimAfter(CountDownLatch ready, CountDownLatch start, Long operatorId) throws Exception {
        ready.countDown();
        start.await();
        return manualController(operatorId).manualClaim(manualDto(20L));
    }

    /** 真 refundService + 真 mapper 接进 controller,只 stub 登录态;微信适配器沿用类级 mock。 */
    private RefundController manualController(Long operatorId) {
        RefundController controller = spy(new RefundController());
        ReflectionTestUtils.setField(controller, "refundService", refundService);
        ReflectionTestUtils.setField(controller, "wechatPayService", wechatPayService);
        doReturn(operatorId).when(controller).getUserId();
        return controller;
    }

    private ManualPayoutDTO manualDto(Long refundId) {
        ManualPayoutDTO dto = new ManualPayoutDTO();
        dto.setRefundId(refundId);
        return dto;
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String text(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String indexDefinition(String tableName, String indexName) throws Exception {
        return text("SELECT CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)) "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='" + tableName + "' AND index_name='" + indexName + "'");
    }

    private void applySpecMigration() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve(
                    "chengyinhub-admin/sql/migration_p1_funds_spec_contracts_20260724.sql")));
        }
    }

    private String allMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("refund-payout-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, RefundApplicationMapper.class, "mapper/business/RefundApplicationMapper.xml");
        addMapper(configuration, CmsRegistrationMapper.class, "mapper/business/CmsRegistrationMapper.xml");
        addMapper(configuration, CmsRegistrationPaymentFactMapper.class, "mapper/business/CmsRegistrationPaymentFactMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            }
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
