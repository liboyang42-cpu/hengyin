package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Explicit approved CI invocation only. Real MySQL gateway/table contract, with SDK doubles and JDBC-backed domain mapper adapters. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatPaymentIntentMySqlIT {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private String database;
    private WechatPayServiceImpl service;
    private WechatVerifiedPaymentFactService facts;

    @BeforeAll
    void schema() throws Exception {
        // No default local/VPS connection and no silent skips. GitHub-hosted scratch DDL is explicitly approved; production DDL remains prohibited.
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        String suite = "bpayintent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        source = new DriverManagerDataSource(MybatisItDatabase.url(suite), MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(suite);
        jdbc = new JdbcTemplate(source);
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        Path proposal = Path.of("chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql");
        if (!Files.exists(proposal)) proposal = Path.of("..").resolve(proposal);
        String ddl = Files.readString(proposal);
        int start = ddl.indexOf("CREATE TABLE wx_pay_order_intent");
        jdbc.execute(ddl.substring(start, ddl.indexOf(';', start)));
        Path inboxDdl = proposal.getParent().resolve("migration_wx_payment_callback_inbox_outbox_20260725.sql");
        Path factDdl = proposal.getParent().resolve("migration_20261024_wx_verified_payment_fact.sql");
        for (Path migration : java.util.List.of(inboxDdl,factDdl)) {
            var populator = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.FileSystemResource(migration));
            populator.execute(source);
        }
        // Narrow fixtures exercise gateway SQL. Full domain DDL/CAS/ledger coverage remains in domain IT suites.
        jdbc.execute("CREATE TABLE club_membership_invoice(id BIGINT PRIMARY KEY,out_trade_no VARCHAR(64),amount_fen BIGINT,status VARCHAR(16) DEFAULT 'PAYING') ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE coop_invite(id BIGINT PRIMARY KEY,deposit_out_trade_no VARCHAR(64),deposit_amount DECIMAL(10,2),deposit_status INT DEFAULT 1,status INT DEFAULT 1,del_flag INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE oms_order(id BIGINT PRIMARY KEY,order_sn VARCHAR(64),pay_amount DECIMAL(10,2),status BIGINT DEFAULT 0,payment_transaction_id VARCHAR(64),member_id BIGINT,mms_merchant_id BIGINT,biz_type VARCHAR(32),verified_payment_fact_id BIGINT UNSIGNED,verified_payment_fact_hash BINARY(64)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE cms_registration(id BIGINT PRIMARY KEY,registration_no VARCHAR(64),wechat_payment_amount DECIMAL(10,2),payment_status INT DEFAULT 1,registration_status INT DEFAULT 1) ENGINE=InnoDB");
        jdbc.update("INSERT INTO club_membership_invoice(id,out_trade_no,amount_fen) VALUES(1,'CLM1',1000)");
        jdbc.update("INSERT INTO coop_invite(id,deposit_out_trade_no,deposit_amount) VALUES(1,'DEP1',10.00)");
        jdbc.update("INSERT INTO oms_order(id,order_sn,pay_amount,member_id,mms_merchant_id,biz_type) VALUES(1,'TPLTM_1',10.00,12,34,?)",MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        jdbc.execute("CREATE TABLE b_payment_grant(order_no VARCHAR(64) PRIMARY KEY,transaction_id VARCHAR(64)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO cms_registration(id,registration_no,wechat_payment_amount) VALUES(1,'REG1',10.00)");
    }

    @BeforeEach
    void gateway() {
        jdbc.update("DELETE FROM wx_payment_callback_fact_link");
        jdbc.update("DELETE FROM wx_verified_payment_fact_rejection");
        jdbc.update("DELETE FROM wx_verified_payment_fact");
        jdbc.update("DELETE FROM wx_pay_order_intent"); // This class's unique scratch schema only.
        jdbc.update("UPDATE oms_order SET status=0,payment_transaction_id=NULL,verified_payment_fact_id=NULL,verified_payment_fact_hash=NULL");
        jdbc.update("DELETE FROM b_payment_grant");
        service = spy(WechatVerifiedFactTestSupport.gateway());
        facts=WechatVerifiedFactTestSupport.realFacts(source);
        WechatVerifiedFactTestSupport.setField(service,"verifiedFacts",facts);
        WechatVerifiedFactTestSupport.setField(service, "paymentIntentJdbc", jdbc);
        WechatVerifiedFactTestSupport.setField(service, "paymentIntentTransactions", new DataSourceTransactionManager(source));
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", WechatPaymentIntentTestSupport.absentOrderSdk());
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
    }

    @AfterAll
    void cleanup() {
        if (jdbc != null && database != null) {
            assertTrue(database.matches("chengyin_mybatis_bpayintent_[a-z0-9_]+"));
            jdbc.execute("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void fourDomainsRetainOriginalParametersAndConflictRollsBackWithoutOverwriting() {
        for (String no : new String[]{"CLM1", "DEP1", "TPLTM_1", "REG1"}) {
            assertEquals(no, freeze(no, "wx-mini", "JSAPI"));
            String hash = jdbc.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, no);
            assertTrue(assertThrows(PaymentPreorderException.class, () -> freeze(no, "wx-app", "APP")).isResultUncertain());
            assertEquals(hash, jdbc.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, no));
            assertEquals("openid", jdbc.queryForObject("SELECT payer_openid FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, no));
        }
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
        jdbc.update("UPDATE club_membership_invoice SET status='CLOSED'");
        jdbc.update("UPDATE coop_invite SET deposit_status=2");
        jdbc.update("UPDATE oms_order SET status=5");
        jdbc.update("UPDATE cms_registration SET payment_status=3");
        try {
            for (String no : new String[]{"CLM1", "DEP1", "TPLTM_1", "REG1"}) {
                assertThrows(PaymentPreorderException.class, () -> freeze(no, "wx-mini", "JSAPI"));
            }
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
        } finally {
            jdbc.update("UPDATE club_membership_invoice SET status='PAYING'");
            jdbc.update("UPDATE coop_invite SET deposit_status=1");
            jdbc.update("UPDATE oms_order SET status=0");
            jdbc.update("UPDATE cms_registration SET payment_status=1");
        }

    }

    @Test
    void concurrentChannelChoicesHaveOneCommittedWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> mini = () -> attempt(start, "wx-mini", "JSAPI");
            Callable<Boolean> app = () -> attempt(start, "wx-app", "APP");
            Future<Boolean> first = threads.submit(mini);
            Future<Boolean> second = threads.submit(app);
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
    }

    @Test
    void oldClosedObservationCannotCloseNewAttemptAndNewSuccessCreditsExactlyOnce() throws Exception {
        freeze("TPLTM_1", "wx-mini", "JSAPI");
        var sdk = mock(JsapiService.class);
        when(sdk.queryOrderByOutTradeNo(any())).thenAnswer(call -> {
            var request = (com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest)call.getArgument(0);
            return providerFacts(request.getOutTradeNo(), "TPLTM_1".equals(request.getOutTradeNo()));
        });
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", sdk);
        var orders = mock(com.chengyinhub.business.mapper.OmsOrderMapper.class);
        org.mockito.stubbing.Answer<com.chengyinhub.business.domain.OmsOrder> originalOrder = call -> {
            boolean locked="selectCommerceOrderForUpdate".equals(call.getMethod().getName());
            var row = jdbc.queryForMap("SELECT *,CONVERT(verified_payment_fact_hash USING ascii) AS proof_hash FROM oms_order WHERE id=1"+(locked?" FOR UPDATE":""));
            var order = new com.chengyinhub.business.domain.OmsOrder();
            order.setId(((Number)row.get("id")).longValue()); order.setOrderSn((String)row.get("order_sn"));
            order.setMemberId(((Number)row.get("member_id")).longValue());
            order.setMmsMerchantId(((Number)row.get("mms_merchant_id")).longValue());
            order.setBizType((String)row.get("biz_type"));
            order.setPayAmount((java.math.BigDecimal)row.get("pay_amount"));
            order.setStatus(((Number)row.get("status")).longValue());
            order.setPaymentTransactionId((String)row.get("payment_transaction_id"));
            Number proofId=(Number)row.get("verified_payment_fact_id");
            order.setVerifiedPaymentFactId(proofId==null?null:proofId.longValue());
            order.setVerifiedPaymentFactHash((String)row.get("proof_hash"));
            return order;
        };
        when(orders.selectOmsOrderByOrderSn("TPLTM_1")).thenAnswer(originalOrder);
        when(orders.selectCommerceOrderForUpdate("TPLTM_1")).thenAnswer(originalOrder);
        when(orders.markCommerceOrderClosed(1L)).thenAnswer(call ->
                jdbc.update("UPDATE oms_order SET status=5 WHERE id=1 AND status=0"));
        when(orders.markCommerceOrderPaid(eq(1L), anyString())).thenAnswer(call ->
                jdbc.update("UPDATE oms_order SET status=1,payment_transaction_id=? WHERE id=1 AND status=0", (String)call.getArgument(1)));
        when(orders.bindCommercePaymentProof(any(),anyString(),anyLong(),anyString())).thenAnswer(call -> {
            var order=(com.chengyinhub.business.domain.OmsOrder)call.getArgument(0);
            // Same original identity/CAS predicates as OmsOrderMapper.bindCommercePaymentProof.
            return jdbc.update("UPDATE oms_order SET verified_payment_fact_id=?,verified_payment_fact_hash=CAST(? AS BINARY) WHERE id=? AND order_sn=? AND member_id=? AND mms_merchant_id=? AND biz_type=? AND pay_amount=? AND status=1 AND payment_transaction_id=? AND verified_payment_fact_id IS NULL AND verified_payment_fact_hash IS NULL",
                    call.getArgument(2),call.getArgument(3),order.getId(),order.getOrderSn(),order.getMemberId(),
                    order.getMmsMerchantId(),order.getBizType(),order.getPayAmount(),call.getArgument(1));
        });
        var grants = mock(com.chengyinhub.business.mapper.MerchantSubscriptionMapper.class);
        when(grants.grantMerchantSubscription(any())).thenAnswer(call ->
                jdbc.update("INSERT INTO b_payment_grant VALUES('TPLTM_1','WX-NEW')"));
        var domain = new MerchantCommerceService(false);
        var tm = new DataSourceTransactionManager(source);
        WechatVerifiedFactTestSupport.setField(domain, "orderMapper", orders);
        WechatVerifiedFactTestSupport.setField(domain, "subscriptionMapper", grants);
        WechatVerifiedFactTestSupport.setField(domain, "wechatPayService", service);
        WechatVerifiedFactTestSupport.setField(domain, "paymentTransactions", tm);
        WechatVerifiedFactTestSupport.setField(domain, "verifiedPaymentFacts", facts);
        var proxy = new org.springframework.aop.framework.ProxyFactory(domain);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tm,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var commerce = (com.chengyinhub.business.service.IMerchantCommerceService)proxy.getProxy();
        WechatVerifiedFactTestSupport.setField(service, "merchantCommerceService", commerce);
        WechatVerifiedFactTestSupport.setField(service, "alarmService", mock(AlarmService.class));
        CountDownLatch oldObserved = new CountDownLatch(1);
        CountDownLatch allowOldClose = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod(); // commits old CLOSED under business lock
            oldObserved.countDown();
            assertTrue(allowOldClose.await(10, TimeUnit.SECONDS)); // before domain close transaction
            return result;
        }).when(service).queryOrderStatus("TPLTM_1");
        String alias;
        try (ExecutorService threads = Executors.newSingleThreadExecutor()) {
            Future<?> oldReconcile = threads.submit(() -> commerce.reconcileOrder("TPLTM_1"));
            assertTrue(oldObserved.await(10, TimeUnit.SECONDS));
            try {
                alias = freeze("TPLTM_1", "wx-app", "APP");
                assertTrue(alias.matches("WXP[0-9a-f]{29}"));
                assertEquals(0, jdbc.queryForObject("SELECT status FROM oms_order WHERE id=1", Integer.class));
                String originalAppHash = jdbc.queryForObject(
                        "SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, alias);
                assertEquals(alias, freeze("TPLTM_1", "wx-app", "APP"),
                        "same APP request while payable must replay the existing alias");
                assertEquals(originalAppHash, jdbc.queryForObject(
                        "SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, alias));
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
            } finally { allowOldClose.countDown(); }
            oldReconcile.get(15, TimeUnit.SECONDS);
        }
        assertEquals(0, jdbc.queryForObject("SELECT status FROM oms_order WHERE id=1", Integer.class));
        verify(orders, never()).markCommerceOrderClosed(anyLong());
        var parser = mock(com.wechat.pay.java.core.notification.NotificationParser.class);
        when(parser.parse(any(), eq(Transaction.class))).thenReturn(providerFacts(alias, false));
        WechatVerifiedFactTestSupport.setField(service, "notificationParser", parser);
        var headers = java.util.Map.of("wechatpay-serial", "serial", "wechatpay-nonce", "nonce",
                "wechatpay-signature", "signature", "wechatpay-timestamp", "1");
        assertTrue(service.handlePayCallback("{}", headers).contains("SUCCESS"));
        assertTrue(service.handlePayCallback("{}", headers).contains("SUCCESS"));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM oms_order WHERE id=1", Integer.class));
        assertEquals("WX-NEW", jdbc.queryForObject("SELECT payment_transaction_id FROM oms_order WHERE id=1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM b_payment_grant", Integer.class));
        var payment=facts.findPaymentSuccess("TPLTM_1");
        assertEquals(payment.reference().id(),jdbc.queryForObject("SELECT verified_payment_fact_id FROM oms_order WHERE id=1",Long.class));
        assertEquals(payment.reference().hash(),jdbc.queryForObject("SELECT CONVERT(verified_payment_fact_hash USING ascii) FROM oms_order WHERE id=1",String.class));
        assertEquals(alias,payment.facts().wxOutTradeNo());
        assertFalse(Boolean.TRUE.equals(new TransactionTemplate(tm).execute(status ->
                service.lockPaymentAttemptForUpdate("TPLTM_1", "TPLTM_1"))));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
        assertEquals("wx-mini", jdbc.queryForObject("SELECT appid FROM wx_pay_order_intent WHERE out_trade_no='TPLTM_1'", String.class));
        assertEquals("wx-app", jdbc.queryForObject("SELECT appid FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, alias));
        assertNotNull(jdbc.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent WHERE out_trade_no='TPLTM_1'", java.sql.Timestamp.class));
    }

    private static Transaction providerFacts(String no, boolean oldClosed) {
        Transaction tx = new Transaction();
        tx.setOutTradeNo(no); tx.setMchid("merchant"); tx.setAppid(oldClosed ? "wx-mini" : "wx-app");
        tx.setTradeState(oldClosed ? Transaction.TradeStateEnum.CLOSED : Transaction.TradeStateEnum.SUCCESS);
        tx.setTradeType(oldClosed ? Transaction.TradeTypeEnum.JSAPI : Transaction.TradeTypeEnum.APP);
        if (!oldClosed) tx.setTransactionId("WX-NEW"); tx.setSuccessTime("2026-10-24T00:00:00Z");
        TransactionAmount amount = new TransactionAmount(); amount.setTotal(1000); amount.setCurrency("CNY"); tx.setAmount(amount);
        return tx;
    }

    @Test
    void sdkObservesCommittedIntentOnIndependentConnectionAndAmbientTransactionCannotSubmit() {
        var sdk = mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "appServiceExtension", sdk);
        when(sdk.prepayWithRequestPayment(any())).thenAnswer(call -> {
            try (var observer = source.getConnection(); var statement = observer.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM wx_pay_order_intent WHERE out_trade_no='DEP1'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            throw new RuntimeException("timeout");
        });
        var request = new com.wechat.pay.java.service.payments.app.model.PrepayRequest();
        request.setOutTradeNo("DEP1"); request.setMchid("merchant"); request.setAppid("wx-app");
        request.setDescription("description"); request.setNotifyUrl("https://example.test/notify");
        var amount = new com.wechat.pay.java.service.payments.app.model.Amount(); amount.setTotal(1000); amount.setCurrency("CNY"); request.setAmount(amount);
        assertThrows(PaymentPreorderException.class, () -> WechatVerifiedFactTestSupport.invokeMethod(service, "prepayApp", request));
        verify(sdk).prepayWithRequestPayment(any());
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            assertThrows(PaymentPreorderException.class, () -> freeze("REG1", "wx-mini", "JSAPI"));
            return null;
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
    }

    private boolean attempt(CountDownLatch start, String appid, String type) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { freeze("REG1", appid, type); return true; }
        catch (PaymentPreorderException conflict) { assertTrue(conflict.isResultUncertain()); return false; }
    }

    private String freeze(String no, String appid, String type) {
        return service.freezePaymentIntent(no, "merchant", appid, type, "CNY", 1000,
                "JSAPI".equals(type) ? "openid" : "", "description", "https://example.test/notify");
    }
}
