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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Explicit approved CI invocation only. Real MySQL gateway/table contract, not a complete domain flow. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatPaymentIntentMySqlIT {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private String database;
    private WechatPayServiceImpl service;

    @BeforeAll
    void schema() throws Exception {
        // No default local/VPS connection and no silent skips. New DDL requires coordinator approval before invocation.
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
        // Narrow fixtures exercise gateway SQL. Full domain DDL/CAS/ledger coverage remains in domain IT suites.
        jdbc.execute("CREATE TABLE club_membership_invoice(id BIGINT PRIMARY KEY,out_trade_no VARCHAR(64),amount_fen BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE coop_invite(id BIGINT PRIMARY KEY,deposit_out_trade_no VARCHAR(64),deposit_amount DECIMAL(10,2)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE oms_order(id BIGINT PRIMARY KEY,order_sn VARCHAR(64),pay_amount DECIMAL(10,2)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE cms_registration(id BIGINT PRIMARY KEY,registration_no VARCHAR(64),wechat_payment_amount DECIMAL(10,2)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO club_membership_invoice VALUES(1,'CLM1',1000)");
        jdbc.update("INSERT INTO coop_invite VALUES(1,'DEP1',10.00)");
        jdbc.update("INSERT INTO oms_order VALUES(1,'TPLTM_1',10.00)");
        jdbc.update("INSERT INTO cms_registration VALUES(1,'REG1',10.00)");
    }

    @BeforeEach
    void gateway() {
        jdbc.update("DELETE FROM wx_pay_order_intent"); // This class's unique scratch schema only.
        service = new WechatPayServiceImpl();
        ReflectionTestUtils.setField(service, "paymentIntentJdbc", jdbc);
        ReflectionTestUtils.setField(service, "paymentIntentTransactions", new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(service, "jsapiService", WechatPaymentIntentTestSupport.absentOrderSdk());
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        ReflectionTestUtils.setField(service, "wxPayConfig", config);
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
    void verifiedClosedOriginalAllowsNewAliasAndPreservesOldIdentity() {
        freeze("REG1", "wx-mini", "JSAPI");
        Transaction closed = new Transaction();
        closed.setOutTradeNo("REG1"); closed.setMchid("merchant"); closed.setAppid("wx-mini");
        closed.setTradeState(Transaction.TradeStateEnum.CLOSED);
        TransactionAmount amount = new TransactionAmount(); amount.setTotal(1000); amount.setCurrency("CNY"); closed.setAmount(amount);
        JsapiService sdk = mock(JsapiService.class);
        when(sdk.queryOrderByOutTradeNo(any())).thenReturn(closed);
        ReflectionTestUtils.setField(service, "jsapiService", sdk);
        assertEquals("CLOSED", service.queryOrderStatus("REG1").get("trade_state"));
        String alias = freeze("REG1", "wx-app", "APP");
        assertTrue(alias.matches("WXP[0-9a-f]{29}"));
        assertEquals(alias, freeze("REG1", "wx-app", "APP"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
        assertEquals("wx-mini", jdbc.queryForObject("SELECT appid FROM wx_pay_order_intent WHERE out_trade_no='REG1'", String.class));
        assertNotNull(jdbc.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent WHERE out_trade_no='REG1'", java.sql.Timestamp.class));
    }

    @Test
    void sdkObservesCommittedIntentOnIndependentConnectionAndAmbientTransactionCannotSubmit() {
        var sdk = mock(com.wechat.pay.java.service.payments.app.AppServiceExtension.class);
        ReflectionTestUtils.setField(service, "appServiceExtension", sdk);
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
        assertThrows(PaymentPreorderException.class, () -> ReflectionTestUtils.invokeMethod(service, "prepayApp", request));
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
