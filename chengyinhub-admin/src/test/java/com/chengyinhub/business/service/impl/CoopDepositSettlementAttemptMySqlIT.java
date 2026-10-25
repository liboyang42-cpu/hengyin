package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Original invite DDL/XML, real B gateway intent/close/fence and transaction; only external WeChat SDK is doubled.
 * Explicit root CI selection only, not a live provider or full monetary settlement test. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoopDepositSettlementAttemptMySqlIT {
    private static final String BUSINESS = "DEP89T100";
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");
    private JdbcTemplate admin, db;
    private String createdDatabase;
    private DataSourceTransactionManager transactions;
    private TransactionTemplate tx;
    private CoopInviteMapper mapper;
    private CmsTopicServiceImpl topic;
    private CoopInviteServiceImpl depositOwner;
    private WechatPaymentSdkGateway gateway;
    private JsapiService sdk;
    private Transaction provider;
    private boolean closesAsPaid;

    @BeforeAll
    void originalSchemaAndMapper() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        for (String key : List.of("HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX")) {
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_" + key), "Explicit scratch connection required: " + key);
        }
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        String database = MybatisItDatabase.databaseName("gdep_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        assertTrue(database.matches("chengyin_mybatis_gdep_[a-z0-9_]+") && database.length() <= 64);
        String prefix = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port() + "/";
        String params = "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(prefix + params, MybatisItDatabase.user(), MybatisItDatabase.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()", String.class).matches("^(8|9)\\..*"));
        admin.execute("CREATE DATABASE `" + database + "`");
        createdDatabase = database; // Cleanup owns only the UUID schema this invocation actually created.
        DriverManagerDataSource source = new DriverManagerDataSource(prefix + database + params,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        assertEquals(createdDatabase, db.queryForObject("SELECT DATABASE()", String.class));
        Path root = Files.exists(Path.of("sql/base_schema_20260818.sql")) ? Path.of("") : Path.of("..");
        String base = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table : List.of("cms_topic", "coop_invite")) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            int close = base.indexOf("\n) ENGINE", start);
            int end = base.indexOf(";\n", close);
            assertTrue(start >= 0 && close > start && end > close);
            db.execute(base.substring(start, end + 1));
        }
        db.execute("ALTER TABLE coop_invite ADD COLUMN supply_station_id BIGINT NULL"); // Mapper prerequisite in this owned scratch schema.
        new ResourceDatabasePopulator(
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_coop_deposit_refund_state_20260913.sql")),
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql")))
                .execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopInviteMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CoopInviteMapper.class);
        transactions = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(transactions);
    }

    @AfterAll
    void cleanupOnlyOwnedSchema() {
        if (createdDatabase != null) {
            assertTrue(createdDatabase.matches("chengyin_mybatis_gdep_[a-z0-9_]+"));
            admin.execute("DROP DATABASE `" + createdDatabase + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    Integer.class, createdDatabase));
        }
    }

    @BeforeEach
    void originalIntentAndRealServices() {
        db.update("DELETE FROM wx_pay_order_intent"); // Only this invocation's owned scratch schema.
        db.update("DELETE FROM coop_invite");
        db.update("DELETE FROM cms_topic");
        db.update("INSERT INTO cms_topic(id,name,product_type,is_settlement) VALUES(11,'deposit proof window',1,0)");
        db.update("INSERT INTO coop_invite(id,topic_id,invite_type,from_type,from_id,to_type,to_id,status,deposit_status,deposit_amount,deposit_out_trade_no,del_flag) "
                + "VALUES(89,11,0,'talent',7,'merchant',8,1,1,50.00,?,0)", BUSINESS);
        WechatPayServiceImpl facade = WechatPayTestWiring.facade();
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant");
        sdk = WechatPaymentIntentTestSupport.absentOrderSdk();
        WechatPayTestWiring.setField(facade, "wxPayConfig", config);
        WechatPayTestWiring.setField(facade, "paymentIntentJdbc", db);
        WechatPayTestWiring.setField(facade, "paymentIntentTransactions", transactions);
        var verified = (WechatVerifiedPaymentFactService) WechatPayTestWiring.getField(facade,"verifiedFacts");
        var originalBusiness = new JdbcWechatPaymentOrderOwner();
        ReflectionTestUtils.setField(originalBusiness,"paymentIntentJdbc",db);
        ReflectionTestUtils.setField(verified,"factBusinessOwner",originalBusiness);
        ReflectionTestUtils.setField(verified,"factIntentJdbc",db);
        ReflectionTestUtils.setField(verified,"factWriteTransactions",transactions);
        WechatPayTestWiring.setField(facade, "jsapiService", sdk);
        gateway = spy((WechatPaymentSdkGateway) WechatPayTestWiring.getField(facade, "gateway"));
        assertEquals(BUSINESS, gateway.freezePaymentIntent(BUSINESS, "merchant", "wx-original", "JSAPI", "CNY", 5000,
                "payer-original", "deposit", "https://example.test/notify"));
        reset(sdk);
        provider = new Transaction();
        provider.setOutTradeNo(BUSINESS); provider.setMchid("merchant"); provider.setAppid("wx-original");
        provider.setTradeType(Transaction.TradeTypeEnum.JSAPI); provider.setTradeState(Transaction.TradeStateEnum.PAYERROR);
        TransactionAmount amount = new TransactionAmount(); amount.setTotal(5000); amount.setCurrency("CNY");
        provider.setAmount(amount);
        when(sdk.queryOrderByOutTradeNo(any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "SDK query must hold no caller business transaction");
            return provider;
        });
        closesAsPaid = false;
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "SDK close must hold no caller business transaction");
            assertEquals(provider.getOutTradeNo(), ((com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest) call.getArgument(0)).getOutTradeNo());
            provider.setTradeState(closesAsPaid ? Transaction.TradeStateEnum.SUCCESS : Transaction.TradeStateEnum.CLOSED);
            if (closesAsPaid) {
                provider.setTransactionId("WXTX89");
                provider.setSuccessTime("2026-10-24T00:00:00+08:00"); // Required original provider fact, never a production default.
            }
            return null;
        }).when(sdk).closeOrder(any());
        depositOwner = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(depositOwner, "coopInviteMapper", mapper);
        ReflectionTestUtils.setField(depositOwner, "depositVerifiedFacts", verified);
        topic = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(topic, "transactionManager", transactions);
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call -> db.queryForObject(
                "SELECT id FROM cms_topic WHERE id=11 FOR UPDATE", (rs,n) -> {
                    var row = new com.chengyinhub.business.domain.CmsTopic(); row.setId(rs.getLong(1)); return row;
                }));
        ReflectionTestUtils.setField(topic, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(topic, "wechatPayService", gateway);
        ReflectionTestUtils.setField(topic, "coopInviteService", depositOwner);
        ReflectionTestUtils.setField(topic, "coopInviteMapper", mapper);
    }

    @Test
    void payerrorRequiresRealExactCloseVerifiedClosedAndFenceBeforeUnpaidCas() {
        assertTrue(resolve());
        assertEquals(5, mapper.selectCoopInviteById(89L).getDepositStatus());
        assertEquals(BUSINESS, mapper.selectCoopInviteById(89L).getDepositOutTradeNo());
        assertNotNull(db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent", java.sql.Timestamp.class));
        var order = inOrder(gateway);
        order.verify(gateway).queryOrderStatus(BUSINESS);
        order.verify(gateway).closePaymentAttempt(BUSINESS, BUSINESS);
        order.verify(gateway).queryOrderStatus(BUSINESS);
        order.verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, BUSINESS);
        verify(gateway, never()).closeOrder(anyString());
    }

    @Test
    void reopenedAppAttemptClosesItsActualAliasAndKeepsOriginalBusinessIdentity() {
        provider.setTradeState(Transaction.TradeStateEnum.CLOSED);
        gateway.queryOrderStatus(BUSINESS);
        String next = gateway.freezePaymentIntent(BUSINESS, "merchant", "wx-next", "APP", "CNY", 5000,
                "", "deposit", "https://example.test/notify");
        assertNotEquals(BUSINESS, next);
        provider.setOutTradeNo(next); provider.setAppid("wx-next");
        provider.setTradeType(Transaction.TradeTypeEnum.APP); provider.setTradeState(Transaction.TradeStateEnum.PAYERROR);
        assertTrue(resolve());
        verify(gateway).closePaymentAttempt(BUSINESS, next);
        verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, next);
        assertEquals(5, mapper.selectCoopInviteById(89L).getDepositStatus());
        assertEquals(BUSINESS, mapper.selectCoopInviteById(89L).getDepositOutTradeNo());
        assertEquals(AMOUNT, mapper.selectCoopInviteById(89L).getDepositAmount());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent WHERE closed_confirmed_at IS NOT NULL", Integer.class));
    }

    @Test
    void closeExceptionPreservesAllOriginalInviteBytesAndIntent() {
        Map<String, Object> before = row();
        doThrow(new IllegalStateException("external result unknown")).when(sdk).closeOrder(any());
        assertThrows(RuntimeException.class, this::resolve);
        assertEquals(before, row());
        assertNull(db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent", java.sql.Timestamp.class));
    }

    @Test
    void lateSuccessDuringCloseCannotDeductAndRetryPersistsOriginalPaidFact() throws Exception {
        CoopDepositSettlementAttemptMySqlIT isolated = new CoopDepositSettlementAttemptMySqlIT();
        try {
            isolated.originalSchemaAndMapper(); isolated.originalIntentAndRealServices();
            var real = isolated.installRealFactRecoveryStorage();
            isolated.closesAsPaid = true;
            var omitFirstSuccessReference = new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(call -> {
                // Only the first caller projection is incomplete. The original real
                // gateway query/verification/fact commit executes before this copy.
                if (!(call.callRealMethod() instanceof Map<?, ?> original))
                    throw new AssertionError("Original gateway must return its payment observation");
                Map<String, String> projected = new java.util.HashMap<>();
                for (var entry : original.entrySet())
                    projected.put(String.class.cast(entry.getKey()), String.class.cast(entry.getValue()));
                if ("SUCCESS".equals(projected.get("trade_state")) && omitFirstSuccessReference.getAndSet(false)) {
                    projected.remove("verified_fact_id"); projected.remove("verified_fact_hash");
                }
                return projected;
            }).when(isolated.gateway).queryOrderStatus(BUSINESS);
            CoopInvite originalPendingObservation = isolated.mapper.selectCoopInviteById(89L);
            Map<String, Object> before = isolated.row();
            IllegalStateException late = assertThrows(IllegalStateException.class, isolated::resolve);
            assertTrue(late.getMessage().contains("保证金原支付事实引用缺失"));
            assertEquals(before, isolated.row());
            var stored = real.findPaymentSuccess("merchant", BUSINESS, "WXTX89");
            assertNotNull(stored, "The original outside-transaction SUCCESS must commit despite the blocked local decision");
            var reference = stored.reference();
            assertEquals(BUSINESS, stored.facts().businessOutTradeNo());
            assertEquals(BUSINESS, stored.facts().wxOutTradeNo());
            assertEquals("WXTX89", stored.facts().transactionId());
            assertEquals(5000L, stored.facts().totalFen());
            assertEquals(isolated.db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",
                    String.class, BUSINESS), stored.facts().intentRequestHash());
            assertEquals(reference, real.requireVerified(reference).reference());
            assertEquals(1, isolated.db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
            assertNull(isolated.db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent", java.sql.Timestamp.class));
            String changedHash = (reference.hash().startsWith("0") ? "1" : "0") + reference.hash().substring(1);
            var conflicting = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(reference.id(), changedHash);
            assertThrows(IllegalStateException.class, () -> isolated.tx.execute(status ->
                    isolated.depositOwner.markDepositPaid(BUSINESS, 5000L, "WXTX89", conflicting)));
            assertEquals(before, isolated.row(), "A syntactically valid reference with the wrong original hash cannot authorize consumption");
            assertEquals(reference, real.requireVerified(reference).reference());
            assertFalse(isolated.resolve());
            assertEquals(2, isolated.mapper.selectCoopInviteById(89L).getDepositStatus());
            assertEquals("WXTX89", isolated.mapper.selectCoopInviteById(89L).getDepositPaidTransactionId());
            assertEquals(AMOUNT, isolated.mapper.selectCoopInviteById(89L).getDepositAmount());
            assertNull(isolated.db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent", java.sql.Timestamp.class));
            assertEquals(reference, real.findPaymentSuccess("merchant", BUSINESS, "WXTX89").reference());
            assertEquals(1, isolated.db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
            Map<String, Object> paid = isolated.row();
            assertFalse(isolated.invoke(originalPendingObservation));
            assertEquals(paid, isolated.row(), "Exact original SUCCESS replay must not mutate the paid receipt again");
            verify(isolated.gateway, times(1)).closePaymentAttempt(BUSINESS, BUSINESS);
            verify(isolated.gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        } finally { isolated.cleanupOnlyOwnedSchema(); }
    }

    @Test
    void lateSuccessWithCommittedExactProofFreezesImmediatelyAndReplayNeverDeducts() throws Exception {
        CoopDepositSettlementAttemptMySqlIT isolated = new CoopDepositSettlementAttemptMySqlIT();
        try {
            isolated.originalSchemaAndMapper(); isolated.originalIntentAndRealServices();
            var real = isolated.installRealFactRecoveryStorage();
            isolated.closesAsPaid = true;
            CoopInvite originalPendingObservation = isolated.mapper.selectCoopInviteById(89L);

            assertFalse(isolated.resolve());

            var stored = real.findPaymentSuccess("merchant", BUSINESS, "WXTX89");
            assertNotNull(stored);
            assertEquals(BUSINESS, stored.facts().businessOutTradeNo());
            assertEquals(BUSINESS, stored.facts().wxOutTradeNo());
            assertEquals("WXTX89", stored.facts().transactionId());
            assertEquals(5000L, stored.facts().totalFen());
            assertEquals(5000L, stored.facts().amountFen());
            assertEquals(isolated.db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",
                    String.class, BUSINESS), stored.facts().intentRequestHash());
            assertEquals(stored.reference(), real.requireVerified(stored.reference()).reference());
            assertEquals(2, isolated.mapper.selectCoopInviteById(89L).getDepositStatus());
            assertEquals("WXTX89", isolated.mapper.selectCoopInviteById(89L).getDepositPaidTransactionId());
            assertEquals(BUSINESS, isolated.mapper.selectCoopInviteById(89L).getDepositOutTradeNo());
            assertEquals(AMOUNT, isolated.mapper.selectCoopInviteById(89L).getDepositAmount());
            assertNull(isolated.mapper.selectCoopInviteById(89L).getDepositRefundNo());
            assertNull(isolated.db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent", java.sql.Timestamp.class));
            Map<String, Object> paid = isolated.row();
            assertFalse(isolated.invoke(originalPendingObservation));
            assertEquals(paid, isolated.row());
            assertEquals(stored.reference(), real.findPaymentSuccess("merchant", BUSINESS, "WXTX89").reference());
            assertEquals(1, isolated.db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact", Integer.class));
            assertEquals(1, isolated.db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
            verify(isolated.gateway, times(1)).closePaymentAttempt(BUSINESS, BUSINESS);
            verify(isolated.gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        } finally { isolated.cleanupOnlyOwnedSchema(); }
    }

    @Test
    void unknownAndRevokedCannotEnterUnpaidCas() {
        Map<String, Object> before = row();
        for (Transaction.TradeStateEnum state : List.of(Transaction.TradeStateEnum.USERPAYING, Transaction.TradeStateEnum.REVOKED)) {
            provider.setTradeState(state);
            // SDK returned no final CLOSED proof, even though close returned without a transport exception.
            doNothing().when(sdk).closeOrder(any());
            assertThrows(IllegalStateException.class, this::resolve);
            assertEquals(before, row());
        }
    }

    @Test
    void missingOriginalProviderIdentityCannotReleaseDeposit() {
        Map<String, Object> before = row();
        provider.setAppid(null);
        assertThrows(RuntimeException.class, this::resolve);
        assertEquals(before, row());
        verify(sdk, never()).closeOrder(any());
    }

    @Test
    void delayedClosedObservationCannotCloseNewAttempt() {
        provider.setTradeState(Transaction.TradeStateEnum.CLOSED);
        Map<String, String> delayed = gateway.queryOrderStatus(BUSINESS);
        // Original gateway establishes the actual CLOSED proof and creates a second frozen intent.
        String next = gateway.freezePaymentIntent(BUSINESS, "merchant", "wx-next", "APP", "CNY", 5000,
                "", "deposit", "https://example.test/notify");
        assertNotEquals(BUSINESS, next);
        doReturn(delayed).when(gateway).queryOrderStatus(BUSINESS); // Only delayed observation; fence remains real SQL.
        Map<String, Object> before = row();
        assertThrows(IllegalStateException.class, this::resolve);
        assertEquals(before, row());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent", Integer.class));
        assertNull(db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent WHERE out_trade_no=?", java.sql.Timestamp.class, next));
    }

    @Test
    void unpaidCasRollsBackButConfirmedExternalCloseSurvivesAndRetryUsesOriginalIdentity() {
        Map<String, Object> before = row();
        CoopInvite observed = mapper.selectCoopInviteById(89L);
        assertTrue(gateway.closePaymentAttempt(BUSINESS, BUSINESS));
        Map<String, String> closed = gateway.queryOrderStatus(BUSINESS);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(topic,
                    "applyObservedDepositPaymentForSettlement", observed, closed)));
            throw new IllegalStateException("abort local deposit mutation");
        }));
        assertEquals(before, row());
        assertNotNull(db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent", java.sql.Timestamp.class),
                "External CLOSED is durable evidence, not rolled back with the local CAS");
        assertEquals(Transaction.TradeStateEnum.CLOSED, provider.getTradeState());
        assertTrue(resolve());
        assertEquals(5, mapper.selectCoopInviteById(89L).getDepositStatus());
        assertEquals(BUSINESS, mapper.selectCoopInviteById(89L).getDepositOutTradeNo());
    }

    @Test
    void realFactAndRecoveryTicketSurviveBusinessFailureThenCompleteWithExactPaidReceipt() throws Exception {
        var real = installRealFactRecoveryStorage();
        db.update("UPDATE coop_invite SET status=3 WHERE id=89"); // Existing canceled-invite payment rule; never reopen it.
        provider.setTradeState(Transaction.TradeStateEnum.SUCCESS);provider.setTransactionId("WXTX89");
        provider.setSuccessTime("2026-10-24T00:00:00+08:00");
        var originalFacts = gateway.queryOrderStatus(BUSINESS); // Real B writer queues the non-payable original business.
        var originalReference = CoopInviteServiceImpl.checkedDepositPaymentReference(originalFacts);
        assertEquals(4,real.findPaymentRecovery(originalReference).orElseThrow().state());
        var target = org.springframework.test.util.AopTestUtils.<WechatVerifiedPaymentFactService>getUltimateTargetObject(real);
        var failing = spy(target);
        doThrow(new IllegalStateException("INJECTED_BEFORE_RECOVERY_RECEIPT_COMPLETION"))
                .when(failing).completePaymentRecovery(any(),eq(89L));
        ReflectionTestUtils.setField(depositOwner,"depositVerifiedFacts",failing);
        Map<String,Object> before = row();
        assertThrows(IllegalStateException.class,()->tx.execute(status -> depositOwner.markDepositPaid(BUSINESS,5000L,"WXTX89",originalReference)));
        assertEquals(before,row(),"Paid CAS must roll back if its recovery receipt cannot commit");
        var reference=real.findPaymentSuccess("merchant",BUSINESS,"WXTX89").reference();
        var pending=real.findPaymentRecovery(reference).orElseThrow();
        assertEquals(89L,pending.businessId());assertEquals(4,pending.state());
        assertEquals(reference,pending.original().reference());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_outbox",Integer.class));
        ReflectionTestUtils.setField(depositOwner,"depositVerifiedFacts",real);
        assertEquals(Boolean.TRUE,tx.execute(status -> depositOwner.markDepositPaid(BUSINESS,5000L,"WXTX89",originalReference)));
        assertEquals(3,mapper.selectCoopInviteById(89L).getStatus()); // The canceled cooperation was not reopened.
        assertEquals(2,mapper.selectCoopInviteById(89L).getDepositStatus());
        assertEquals(BUSINESS,mapper.selectCoopInviteById(89L).getDepositOutTradeNo());
        assertEquals("WXTX89",mapper.selectCoopInviteById(89L).getDepositPaidTransactionId());
        var completed=real.findPaymentRecovery(reference).orElseThrow();
        assertEquals(2,completed.state());assertEquals(reference,completed.original().reference());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_outbox",Integer.class));
        assertEquals(0,db.queryForObject("SELECT money_affecting FROM wx_payment_callback_outbox",Integer.class));
    }

    @Test
    void oldSuccessAfterNewAttemptCannotConsumeOrCompleteButCurrentSuccessAndReplayCan() throws Exception {
        // Separate owned UUID schema: no immutable facts/receipts from the original nine cases are cleared/reused.
        CoopDepositSettlementAttemptMySqlIT isolated = new CoopDepositSettlementAttemptMySqlIT();
        try {
            isolated.originalSchemaAndMapper();isolated.originalIntentAndRealServices();
            var real = isolated.installRealFactRecoveryStorage();
            isolated.provider.setTradeState(Transaction.TradeStateEnum.CLOSED);
            isolated.gateway.queryOrderStatus(BUSINESS);
            String next = isolated.gateway.freezePaymentIntent(BUSINESS,"merchant","wx-next","APP","CNY",5000,
                    "","deposit","https://example.test/notify");
            assertNotEquals(BUSINESS,next);
            isolated.provider.setTradeState(Transaction.TradeStateEnum.SUCCESS);
            isolated.provider.setTransactionId("WXTX89");isolated.provider.setSuccessTime("2026-10-24T00:00:00+08:00");
            Map<String,String> oldFacts = isolated.gateway.verifiedPaymentFacts(independentObservation(isolated.provider));
            assertNotNull(oldFacts,"The original late SUCCESS must be retained, not silently discarded");
            var oldReference = CoopInviteServiceImpl.checkedDepositPaymentReference(oldFacts);
            var oldTicket = real.findPaymentRecovery(oldReference).orElseThrow();
            assertEquals(4,oldTicket.state());assertEquals(89L,oldTicket.businessId());
            Map<String,Object> before = isolated.row();
            assertThrows(IllegalStateException.class,()->isolated.tx.execute(status -> isolated.depositOwner.markDepositPaid(
                    BUSINESS,5000L,"WXTX89",oldReference)));
            assertEquals(before,isolated.row());
            assertEquals(oldReference,real.requireVerified(oldReference).reference());
            assertEquals(4,real.findPaymentRecovery(oldReference).orElseThrow().state());
            assertEquals(2,isolated.db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent",Integer.class));
            assertNull(isolated.db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent WHERE out_trade_no=?",java.sql.Timestamp.class,next));
            assertNull(isolated.mapper.selectCoopInviteById(89L).getDepositRefundNo());
            // A genuine original SUCCESS for the current attempt still consumes; never use closable for SUCCESS.
            isolated.provider.setOutTradeNo(next);isolated.provider.setAppid("wx-next");
            isolated.provider.setTradeType(Transaction.TradeTypeEnum.APP);isolated.provider.setTransactionId("WXTX_NEXT");
            isolated.provider.setSuccessTime("2026-10-24T00:00:01+08:00");
            Map<String,String> currentFacts = isolated.gateway.verifiedPaymentFacts(independentObservation(isolated.provider));
            assertNotNull(currentFacts);
            var currentReference = CoopInviteServiceImpl.checkedDepositPaymentReference(currentFacts);
            assertNotEquals(oldReference,currentReference);
            assertEquals(Boolean.TRUE,isolated.tx.execute(status -> isolated.depositOwner.markDepositPaid(
                    BUSINESS,5000L,"WXTX_NEXT",currentReference)));
            Map<String,Object> paid = isolated.row();
            assertEquals(2,isolated.mapper.selectCoopInviteById(89L).getDepositStatus());
            assertEquals("WXTX_NEXT",isolated.mapper.selectCoopInviteById(89L).getDepositPaidTransactionId());
            assertEquals(Boolean.TRUE,isolated.tx.execute(status -> isolated.depositOwner.markDepositPaid(
                    BUSINESS,5000L,"WXTX_NEXT",currentReference)));
            assertEquals(paid,isolated.row(),"Exact replay must not write another paid receipt or change amounts/times");
            assertEquals(4,real.findPaymentRecovery(oldReference).orElseThrow().state());
            assertEquals(oldReference,real.findPaymentRecovery(oldReference).orElseThrow().original().reference());
            assertEquals(2,isolated.db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact",Integer.class));
            assertEquals(1,isolated.db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_outbox",Integer.class));
            assertNull(isolated.mapper.selectCoopInviteById(89L).getDepositRefundNo());
        } finally { isolated.cleanupOnlyOwnedSchema(); }
    }

    @Test
    void currentButClosedAttemptLateSuccessCannotConsumeOrCompleteRecovery() throws Exception {
        CoopDepositSettlementAttemptMySqlIT isolated = new CoopDepositSettlementAttemptMySqlIT();
        try {
            isolated.originalSchemaAndMapper();isolated.originalIntentAndRealServices();
            var real = isolated.installRealFactRecoveryStorage();
            isolated.provider.setTradeState(Transaction.TradeStateEnum.CLOSED);isolated.gateway.queryOrderStatus(BUSINESS);
            isolated.provider.setTradeState(Transaction.TradeStateEnum.SUCCESS);isolated.provider.setTransactionId("WXTX89");
            isolated.provider.setSuccessTime("2026-10-24T00:00:00+08:00");
            var observed=isolated.gateway.verifiedPaymentFacts(independentObservation(isolated.provider));assertNotNull(observed);
            var reference=CoopInviteServiceImpl.checkedDepositPaymentReference(observed);
            var before=isolated.row();assertEquals(4,real.findPaymentRecovery(reference).orElseThrow().state());
            assertThrows(IllegalStateException.class,()->isolated.tx.execute(status -> isolated.depositOwner.markDepositPaid(
                    BUSINESS,5000L,"WXTX89",reference)));
            assertEquals(before,isolated.row());assertEquals(reference,real.requireVerified(reference).reference());
            assertEquals(4,real.findPaymentRecovery(reference).orElseThrow().state());
            assertEquals(1,isolated.db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent",Integer.class));
            assertNotNull(isolated.db.queryForObject("SELECT closed_confirmed_at FROM wx_pay_order_intent",java.sql.Timestamp.class));
            assertNull(isolated.mapper.selectCoopInviteById(89L).getDepositRefundNo());
        } finally { isolated.cleanupOnlyOwnedSchema(); }
    }

    private WechatVerifiedPaymentFactService installRealFactRecoveryStorage() throws Exception {
        Path root = Files.exists(Path.of("sql/base_schema_20260818.sql")) ? Path.of("") : Path.of("..");
        String base = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table : List.of("wx_payment_callback_inbox", "wx_payment_callback_outbox")) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            int close = base.indexOf("\n) ENGINE", start);int end = base.indexOf(";\n", close);
            assertTrue(start >= 0 && close > start && end > close);db.execute(base.substring(start,end+1));
        }
        new ResourceDatabasePopulator(new FileSystemResource(root.resolve(
                "chengyinhub-admin/sql/migration_20261024_wx_verified_payment_fact.sql"))).execute(db.getDataSource());
        var real = WechatVerifiedFactTestSupport.realFacts(db.getDataSource());
        ReflectionTestUtils.setField(gateway,"verifiedFacts",real);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(gateway,"intents"),"verifiedFacts",real);
        ReflectionTestUtils.setField(depositOwner,"depositVerifiedFacts",real);
        return real;
    }

    private static Transaction independentObservation(Transaction provider) {
        Transaction notification = new Transaction();notification.setOutTradeNo(provider.getOutTradeNo());
        notification.setMchid(provider.getMchid());notification.setAppid(provider.getAppid());
        notification.setTradeState(provider.getTradeState());notification.setTradeType(provider.getTradeType());
        notification.setTransactionId(provider.getTransactionId());notification.setSuccessTime(provider.getSuccessTime());
        TransactionAmount amount = new TransactionAmount();amount.setTotal(provider.getAmount().getTotal());
        amount.setCurrency(provider.getAmount().getCurrency());notification.setAmount(amount);return notification;
    }

    private boolean resolve() {
        return invoke(mapper.selectCoopInviteById(89L)); // Observation only; provider I/O precedes the mutation transaction.
    }

    private boolean invoke(CoopInvite invite) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(topic, "resolvePendingDepositPaymentForSettlement", invite));
    }

    private Map<String, Object> row() {
        return db.queryForMap("SELECT * FROM coop_invite WHERE id=89");
    }
}
