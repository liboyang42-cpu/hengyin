package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.mapper.CmsPointsSettingMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImNotificationIntentMapper;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CmsPointsSettingServiceImpl;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.business.service.impl.WechatPayServiceImpl;
import com.chengyinhub.business.service.impl.WxPaymentCallbackClaimService;
import com.chengyinhub.business.service.impl.WxPaymentCallbackInboxServiceImpl;
import com.chengyinhub.business.service.impl.WxPaymentCallbackMutationService;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Original schema/XML + real registration/inbox/claim/mutation/proxies + signed SDK parser. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationCallbackIntentMySqlIT {
    private static final String BUSINESS_NO = "REG_CALLBACK_INTENT_11";
    private static final String MERCHANT = "merchant-callback-it";
    private static final String SERIAL = "TEST-CALLBACK-INTENT-KEY";
    private static final String[] TABLES = {"cms_registration", "oms_ticket", "ums_member",
            "ums_member_points_detail", "cms_points_setting", "cms_topic_node", "cms_member_template",
            "cms_registration_scan", "registration_entitlement", "cms_topic"};
    private String suite;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Transaction> providerOrders = new HashMap<>();
    private final AtomicReference<String> activeHash = new AtomicReference<>();
    private final AtomicInteger scheduledNotices = new AtomicInteger();
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private DataSourceTransactionManager transactions;
    private CommittedBusinessNotice notices;
    private SqlSessionTemplate sql;
    private final AtomicCommitProbe probe = new AtomicCommitProbe();
    private WechatPayServiceImpl gateway;
    private JsapiService sdk;
    private KeyPair keys;
    private String apiV3Key;

    private void originalSchemaAndXml() throws Exception {
        // This suite is an explicit root CI selection, never a default local or production connection.
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        assertEquals("1", System.getenv("A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite), db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(db.queryForObject("SELECT VERSION()", String.class)));
        // Original repository DDL verbatim; no hand-created domain tables or missing-column fallback.
        P0AcceptanceSchema.baseTables(db, TABLES);
        P0AcceptanceSchema.migrations(source,
                "migration_app_open_id.sql",
                "migration_20260930_registration_first_successful_scan.sql",
                "migration_wx_payment_callback_inbox_outbox_20260725.sql",
                "migration_20261023_wx_pay_order_intent.sql",
                "migration_20261024_wx_verified_payment_fact.sql",
                "migration_20261024_registration_payment_fact.sql");
        P0AcceptanceSchema.createTableFromMigration(db, "migration_20261013_b_im_notification_intent.sql");
        // Reuse the existing repository helper: exact cms_topic ALTERs from original migrations only.
        // The original topic FOR UPDATE mapper selects these post-snapshot columns; no hand-shaped test table.
        for (String migration : List.of("migration_20260820_new_city_setup_plan.sql",
                "migration_20260821_topic_beta_zone.sql", "migration_20260821_topic_team_config.sql",
                "migration_circle_theme_batch2_20260821.sql", "migration_20260823_game_session_runtime.sql",
                "migration_20260823_topic_route_graph.sql", "migration_20260823_topic_route_published_snapshot.sql",
                "migration_club_topic_display_settings_20260906.sql", "migration_topic_journey_rules_20260916.sql",
                "migration_topic_journey_story_20260916.sql", "migration_theme_runtime_20261004.sql")) {
            String script=java.nio.file.Files.readString(P0AcceptanceSchema.repoRoot().resolve("chengyinhub-admin/sql").resolve(migration));
            List<String> alters=FreeExploreNodeSupplyMySqlIT.alterStatements(script,"cms_topic");
            assertFalse(alters.isEmpty(),migration+" must supply the original topic schema");
            for(String alter:alters) db.execute(alter);
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(List.of("CmsRegistrationMapper", "UmsMemberMapper", "UmsMemberPointsDetailMapper",
                        "CmsPointsSettingMapper", "CmsRegistrationScanMapper", "WxPaymentCallbackInboxMapper", "CmsTopicMapper", "CmsRegistrationPaymentFactMapper", "ImNotificationIntentMapper").stream()
                .map(name -> new ClassPathResource("mapper/business/" + name + ".xml"))
                .toArray(ClassPathResource[]::new));
        factory.setPlugins(probe);
        sql = new SqlSessionTemplate(factory.getObject());
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void seedAndWireRealCallbackServices() throws Exception {
        source=null; db=null;
        suite="bregcb_"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        originalSchemaAndXml(); // Never delete immutable consumption receipts to reset a fixture.
        assertEquals(0,number("SELECT COUNT(*) FROM cms_registration_payment_fact"));
        for (String table : TABLES) assertEquals(0,number("SELECT COUNT(*) FROM " + table),"fresh original domain schema: "+table);
        for (String table : List.of("wx_payment_callback_fact_link", "wx_verified_payment_fact_rejection", "wx_verified_payment_fact", "wx_payment_callback_inbox", "wx_payment_callback_outbox", "wx_pay_order_intent"))
            assertEquals(0,number("SELECT COUNT(*) FROM " + table),"fresh original payment schema: "+table);
        providerOrders.clear(); activeHash.set(null); scheduledNotices.set(0); probe.reset();
        db.update("INSERT INTO ums_member(id,point,balance,status,del_flag) VALUES(7,80,12.34,1,0)");
        db.update("INSERT INTO cms_topic(id,name) VALUES(23,'Callback transaction fixture')");
        db.update("INSERT INTO oms_ticket(id,remaining_inventory) VALUES(9,5)");
        db.update("INSERT INTO cms_member_template(id,member_id,title,validation_method,del_flag) VALUES(90,8,'Scan fixture',1,0)");
        db.update("INSERT INTO cms_topic_node(id,topic_id,template_id,name) VALUES(91,23,90,'Checkpoint')");
        db.update("INSERT INTO cms_points_setting(id,title,value,type,event_type,status,del_flag) VALUES"
                + "(2,'First registration',5,1,2,1,0),(3,'Participation',10,1,3,1,0)");
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,"
                + "member_id,order_num,original_amount,total_amount,point_used,point_payment_amount,payable_amount,"
                + "wechat_payment_amount,fee_platform_rate,payment_status,registration_status,pay_expire_time,purchase_kind,points_returned)"
                + " VALUES(11,?,1,23,9,10.20,7,1,10.20,10.20,20,0.20,10.00,10.00,0.1234,1,1,DATE_ADD(NOW(),INTERVAL 1 HOUR),1,0)", BUSINESS_NO);
        db.update("INSERT INTO registration_entitlement(registration_id,chapter_id,topic_id,status) VALUES(11,35,23,0)");

        sdk = mock(JsapiService.class);
        ServiceException missing = mock(ServiceException.class);
        when(missing.getHttpStatusCode()).thenReturn(404);
        when(missing.getErrorCode()).thenReturn("ORDER_NOT_EXIST");
        when(sdk.queryOrderByOutTradeNo(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "official query must be outside callback mutation");
            var request = (com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest)call.getArgument(0);
            assertEquals(MERCHANT, request.getMchid());
            Transaction original = providerOrders.get(request.getOutTradeNo());
            if (original == null) throw missing;
            return original;
        });
        gateway = WechatVerifiedFactTestSupport.gateway();
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.getMerchantId()).thenReturn(MERCHANT);
        WechatVerifiedFactTestSupport.setField(gateway, "wxPayConfig", config);
        WechatVerifiedFactTestSupport.setField(gateway, "paymentIntentJdbc", db);
        WechatVerifiedFactTestSupport.setField(gateway, "paymentIntentTransactions", transactions);
        WechatVerifiedFactTestSupport.setField(gateway, "jsapiService", sdk);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.bind(gateway,sdk,null,MERCHANT);
        WechatVerifiedFactTestSupport.setField(gateway,"verifiedFacts",
                com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.realFacts(source));
        WechatVerifiedFactTestSupport.setField(gateway, "alarmService", mock(AlarmService.class));
        IMerchantCommerceService commerce = mock(IMerchantCommerceService.class);
        when(commerce.supportsOutTradeNo(anyString())).thenReturn(false); // This fixture is a registration, not commerce.
        WechatVerifiedFactTestSupport.setField(gateway, "merchantCommerceService", commerce);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys = generator.generateKeyPair(); apiV3Key = UUID.randomUUID().toString().replace("-", "");
        WechatVerifiedFactTestSupport.setField(gateway, "notificationParser", new NotificationParser(
                new RSAPublicKeyNotificationConfig.Builder().publicKey(keys.getPublic())
                        .publicKeyId(SERIAL).apiV3Key(apiV3Key).build()));

        WxPaymentCallbackInboxMapper inboxMapper = sql.getMapper(WxPaymentCallbackInboxMapper.class);
        WxPaymentCallbackClaimService claims = new WxPaymentCallbackClaimService();
        WechatVerifiedFactTestSupport.setField(claims, "mapper", inboxMapper);
        WxPaymentCallbackMutationService mutations = new WxPaymentCallbackMutationService();
        WechatVerifiedFactTestSupport.setField(mutations, "mapper", inboxMapper);
        WechatVerifiedFactTestSupport.setField(mutations, "registrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        WechatVerifiedFactTestSupport.setField(mutations, "topicMapper", sql.getMapper(CmsTopicMapper.class));
        WxPaymentCallbackInboxServiceImpl inbox = new WxPaymentCallbackInboxServiceImpl();
        WechatVerifiedFactTestSupport.setField(inbox, "mapper", inboxMapper);
        WechatVerifiedFactTestSupport.setField(inbox, "claimService", transactional(claims));
        WechatVerifiedFactTestSupport.setField(inbox, "mutationService", transactional(mutations));
        WechatVerifiedFactTestSupport.setField(inbox, "alarmService", mock(AlarmService.class));
        WechatVerifiedFactTestSupport.setField(inbox,"verifiedFacts",WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts"));
        CmsRegistrationServiceImpl registration = new CmsRegistrationServiceImpl();
        WechatVerifiedFactTestSupport.setField(registration, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "cmsTopicMapper", sql.getMapper(CmsTopicMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "verifiedPaymentFacts", WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts"));
        WechatVerifiedFactTestSupport.setField(registration, "coopSettlementClaimService", mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class));
        WechatVerifiedFactTestSupport.setField(registration, "registrationPaymentFactMapper", sql.getMapper(CmsRegistrationPaymentFactMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "umsMemberMapper", sql.getMapper(UmsMemberMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "umsMemberPointsDetailMapper", sql.getMapper(UmsMemberPointsDetailMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "cmsRegistrationScanMapper", sql.getMapper(CmsRegistrationScanMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "alarmService", mock(AlarmService.class));
        WechatVerifiedFactTestSupport.setField(registration, "wxPaymentCallbackInboxService", transactional(inbox));
        CmsPointsSettingServiceImpl settings = new CmsPointsSettingServiceImpl();
        WechatVerifiedFactTestSupport.setField(settings, "cmsPointsSettingMapper", sql.getMapper(CmsPointsSettingMapper.class));
        WechatVerifiedFactTestSupport.setField(registration, "cmsPointsSettingService", transactional(settings));
        // Original owner_type=1 policy defers merchant accrual until verification; this is the real service, not a success stub.
        WechatVerifiedFactTestSupport.setField(registration, "settlementEntryService", transactional(new SettlementEntryServiceImpl()));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", WechatVerifiedFactTestSupport.getField(gateway,"gateway"));
        WechatVerifiedFactTestSupport.setField(registration, "paymentAttemptGateway", beans.getBeanProvider(WechatPaymentOperations.class));
        // Real notification owner joins the payment transaction; delivery remains an unexecuted after-commit effect.
        ImServiceImpl im = new ImServiceImpl();
        WechatVerifiedFactTestSupport.setField(im, "notificationIntentMapper", sql.getMapper(ImNotificationIntentMapper.class));
        ImServiceImpl transactionalIm = transactional(im);
        WechatVerifiedFactTestSupport.setField(registration, "systemNotificationIntents", (ISystemNotificationIntentService) transactionalIm);
        WechatVerifiedFactTestSupport.setField(registration, "imService", transactionalIm);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            assertVisibleOutside(2, WxPaymentCallbackInbox.STATE_DONE, 95, 2, 1);
            scheduledNotices.incrementAndGet();
            return mock(ScheduledFuture.class); // Do not execute QR/HTTP effects; only verify post-commit enqueue.
        });
        notices = spy(new CommittedBusinessNotice(
                mock(com.chengyinhub.business.service.INotifyService.class), mock(AlarmService.class), transactions, executor));
        WechatVerifiedFactTestSupport.setField(registration, "committedBusinessNotice", notices);
        WechatVerifiedFactTestSupport.setField(gateway, "registrationService", transactional(registration));
        assertEquals(BUSINESS_NO, freeze("wx-mini", "JSAPI"));
        clearInvocations(sdk); // First preflight 404 is separate from the callback observations below.
    }

    @AfterEach
    void removeOnlyOwnedScratch() {
        if (db == null) return;
        assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_bregcb_[a-z0-9_]+"));
        P0AcceptanceSchema.drop(db, suite);
    }

    @Test
    void signedSuccessCommitsRegistrationLedgerScansAndDoneTogetherAndDuplicateDoesNotRebook() throws Exception {
        providerOrders.put(BUSINESS_NO, original(BUSINESS_NO, "wx-mini", "JSAPI", "SUCCESS", "WX-SUCCESS"));
        SignedNotification signed = notification(BUSINESS_NO, "wx-mini", "JSAPI", "SUCCESS", "WX-SUCCESS", 1000);
        assertEquals("FAIL", deliver(new SignedNotification(signed.body() + " ", signed.headers()), "WX-SUCCESS", "SUCCESS"));
        verifyNoInteractions(sdk);
        assertEquals(0, number("SELECT COUNT(*) FROM wx_payment_callback_inbox"));
        assertPendingResources();
        SignedNotification wrongAmount = notification(BUSINESS_NO, "wx-mini", "JSAPI", "SUCCESS", "WX-SUCCESS", 999);
        assertEquals("FAIL", deliver(wrongAmount, "WX-SUCCESS", "SUCCESS"));
        assertEquals(0, number("SELECT COUNT(*) FROM wx_payment_callback_inbox"));
        assertPendingResources();
        SignedNotification success = notification(BUSINESS_NO, "wx-mini", "JSAPI", "SUCCESS", "WX-SUCCESS", 1000);
        assertEquals("SUCCESS", deliver(success, "WX-SUCCESS", "SUCCESS"));
        assertEquals(1, probe.doneProbes.get());
        assertEquals(2, scheduledNotices.get());
        assertCompleted("WX-SUCCESS", 1);
        long rowId = inboxNumber("id"), fence = inboxNumber("fence_token");
        assertEquals("SUCCESS", deliver(success, "WX-SUCCESS", "SUCCESS"));
        assertCompleted("WX-SUCCESS", 1);
        assertEquals(rowId, inboxNumber("id")); assertEquals(fence, inboxNumber("fence_token"));
        assertEquals(1, probe.doneProbes.get()); assertEquals(2, scheduledNotices.get());
        verify(sdk, never()).closeOrder(any());
    }

    @Test
    void exceptionAfterBusinessAndDoneSqlRollsBothBackAndSameInboxIdentityRecovers() throws Exception {
        providerOrders.put(BUSINESS_NO, original(BUSINESS_NO, "wx-mini", "JSAPI", "SUCCESS", "WX-RETRY"));
        SignedNotification success = notification(BUSINESS_NO, "wx-mini", "JSAPI", "SUCCESS", "WX-RETRY", 1000);
        probe.failAfterDone.set(true);
        assertEquals("FAIL", deliver(success, "WX-RETRY", "SUCCESS"));
        assertEquals(1, probe.doneProbes.get(), "injected only after actual business and actual DONE UPDATE ran");
        assertPendingResources();
        assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE, inboxNumber("state"));
        assertEquals(1, inboxNumber("attempt_count")); assertEquals(1, inboxNumber("fence_token"));
        assertEquals(0, scheduledNotices.get());
        long originalId = inboxNumber("id");
        assertEquals("SUCCESS", deliver(success, "WX-RETRY", "SUCCESS"));
        assertCompleted("WX-RETRY", 1);
        assertEquals(originalId, inboxNumber("id"));
        assertEquals(2, inboxNumber("attempt_count")); assertEquals(2, inboxNumber("fence_token"));
        assertEquals(11, inboxNumber("biz_id")); assertEquals(1, inboxNumber("biz_type"));
        assertEquals("SUCCESS", db.queryForObject("SELECT event_type FROM wx_payment_callback_inbox WHERE id=?", String.class, originalId));
        assertEquals(2, probe.doneProbes.get()); assertEquals(2, scheduledNotices.get());
        assertEquals("SUCCESS", deliver(success, "WX-RETRY", "SUCCESS"));
        assertCompleted("WX-RETRY", 1);
        assertEquals(2, inboxNumber("fence_token")); assertEquals(2, scheduledNotices.get());
    }

    @Test
    void oldSignedClosedW1CannotCloseOrReleaseNewW2AndLaterW2SuccessStaysPaid() throws Exception {
        providerOrders.put(BUSINESS_NO, original(BUSINESS_NO, "wx-mini", "JSAPI", "CLOSED", "WX-OLD"));
        assertEquals("CLOSED", gateway.queryOrderStatus(BUSINESS_NO).get("trade_state"));
        String originalHash = db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, BUSINESS_NO);
        String w2 = freeze("wx-app", "APP");
        assertNotEquals(BUSINESS_NO, w2); assertTrue(w2.matches("WXP[0-9a-f]{29}"));
        providerOrders.put(w2, original(w2, "wx-app", "APP", "SUCCESS", "WX-NEW"));
        SignedNotification old = notification(BUSINESS_NO, "wx-mini", "JSAPI", "CLOSED", "WX-OLD", 1000);
        assertEquals("FAIL", deliver(old, "WX-OLD", "CLOSED"));
        assertPendingResources();
        assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE, inboxNumber("state"));
        assertEquals(0, probe.doneProbes.get());
        assertEquals(0, number("SELECT COUNT(*) FROM wx_pay_order_intent WHERE out_trade_no='" + w2 + "' AND closed_confirmed_at IS NOT NULL"));
        SignedNotification success = notification(w2, "wx-app", "APP", "SUCCESS", "WX-NEW", 1000);
        assertEquals("SUCCESS", deliver(success, "WX-NEW", "SUCCESS"));
        assertCompleted("WX-NEW", 2);
        assertEquals("FAIL", deliver(old, "WX-OLD", "CLOSED"));
        assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE, inboxNumber("state"));
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals("WX-NEW", db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=11", String.class));
        assertPaidResources();
        assertEquals(originalHash, db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, BUSINESS_NO));
        assertEquals("wx-mini", db.queryForObject("SELECT appid FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, BUSINESS_NO));
        assertEquals("wx-app", db.queryForObject("SELECT appid FROM wx_pay_order_intent WHERE out_trade_no=?", String.class, w2));
        assertEquals(2, number("SELECT COUNT(*) FROM wx_pay_order_intent"));
        String newHash = WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:WX-NEW:SUCCESS");
        assertEquals(WxPaymentCallbackInbox.STATE_DONE, db.queryForObject(
                "SELECT state FROM wx_payment_callback_inbox WHERE notify_hash=UNHEX(?)", Integer.class, newHash));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM wx_pay_order_intent WHERE out_trade_no=? AND closed_confirmed_at IS NOT NULL", Integer.class, w2));
        assertEquals(1, probe.doneProbes.get()); assertEquals(2, scheduledNotices.get());
        verify(sdk, never()).closeOrder(any());
    }

    @Test
    void durableSuccessRetriesTheRealRegistrationAndInboxWhileProviderQueryIsUnavailable() throws Exception {
        providerOrders.put(BUSINESS_NO,original(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-DURABLE"));
        SignedNotification success=notification(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-DURABLE",1000);
        probe.failAfterDone.set(true);
        assertEquals("FAIL",deliver(success,"WX-DURABLE","SUCCESS"));
        assertEquals(1,probe.doneProbes.get(),"failure injected after real business SQL and DONE SQL");
        assertPendingResources();assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE,inboxNumber("state"));
        assertEquals(0,scheduledNotices.get());
        long inboxId=inboxNumber("id");
        long factId=db.queryForObject("SELECT id FROM wx_verified_payment_fact WHERE provider_id='WX-DURABLE'",Long.class);
        String factHash=db.queryForObject("SELECT fact_hash FROM wx_verified_payment_fact WHERE id=?",String.class,factId);
        providerOrders.clear();
        doThrow(new RuntimeException("controlled provider query outage")).when(sdk).queryOrderByOutTradeNo(any());
        clearInvocations(sdk);
        String intentHash=db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",String.class,BUSINESS_NO);
        var appRejected=assertThrows(com.chengyinhub.business.service.PaymentPreorderException.class,()->freeze("wx-app","APP"));
        var originalRejected=assertThrows(com.chengyinhub.business.service.PaymentPreorderException.class,()->freeze("wx-mini","JSAPI"));
        assertTrue(appRejected.isResultUncertain());assertTrue(originalRejected.isResultUncertain());
        assertEquals(1,number("SELECT COUNT(*) FROM wx_pay_order_intent"));
        assertEquals(intentHash,db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",String.class,BUSINESS_NO));
        SignedNotification tampered=new SignedNotification(success.body()+" ",success.headers());
        var unknownHeaders=new HashMap<>(success.headers());unknownHeaders.put("wechatpay-serial","UNKNOWN-CERTIFICATE");
        for (SignedNotification rejected:List.of(tampered,new SignedNotification(success.body(),unknownHeaders),
                notification(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-DURABLE",999),
                notification(BUSINESS_NO,"wx-app","APP","SUCCESS","WX-DURABLE",1000),
                notification(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-OTHER",1000))) {
            assertEquals("FAIL",deliver(rejected,"WX-DURABLE","SUCCESS"));
            assertPendingResources();assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE,inboxNumber("state"));
            assertEquals(1,inboxNumber("attempt_count"));assertEquals(inboxId,inboxNumber("id"));
            assertEquals(factHash,db.queryForObject("SELECT fact_hash FROM wx_verified_payment_fact WHERE id=?",String.class,factId));
        }
        // A fresh envelope/signature with identical economic facts is a legitimate provider redelivery.
        SignedNotification redelivered=notification(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-DURABLE",1000);
        assertNotEquals(success.body(),redelivered.body());
        assertEquals("SUCCESS",deliver(redelivered,"WX-DURABLE","SUCCESS"));
        assertCompleted("WX-DURABLE",1);assertEquals(inboxId,inboxNumber("id"));
        assertEquals(2,inboxNumber("attempt_count"));assertEquals(2,inboxNumber("fence_token"));
        assertEquals(factHash,db.queryForObject("SELECT fact_hash FROM wx_verified_payment_fact WHERE id=?",String.class,factId));
        assertEquals(1,number("SELECT COUNT(*) FROM wx_verified_payment_fact"));
        assertEquals("SUCCESS",deliver(success,"WX-DURABLE","SUCCESS"));assertCompleted("WX-DURABLE",1);
        assertEquals(2,probe.doneProbes.get());
        assertEquals(2,scheduledNotices.get());verifyNoInteractions(sdk);
    }

    @Test
    void oldOuterRepeatableReadViewCanVerifyAndApplyANewCommittedFactInItsOriginalDomainTransaction() {
        providerOrders.put(BUSINESS_NO,original(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-RR"));
        var store=(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService)
                WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts");
        var registration=(CmsRegistrationServiceImpl)WechatVerifiedFactTestSupport.getField(gateway,"registrationService");
        var inbox=(com.chengyinhub.business.service.IWxPaymentCallbackInboxService)
                WechatVerifiedFactTestSupport.getField(registration,"wxPaymentCallbackInboxService");
        var suspended=new org.springframework.transaction.support.TransactionTemplate(transactions);
        suspended.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status->{
            assertEquals(BUSINESS_NO,sql.getMapper(CmsRegistrationMapper.class).selectByRegistrationNo(BUSINESS_NO).getRegistrationNo());
            assertEquals(0,number("SELECT COUNT(*) FROM wx_verified_payment_fact"));
            Map<String,String> facts=suspended.execute(ignored->gateway.queryOrderStatus(BUSINESS_NO));
            var reference=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                    Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash"));
            assertEquals(0,number("SELECT COUNT(*) FROM wx_verified_payment_fact"),"outer RR snapshot stays old");
            assertEquals("WX-RR",store.requireVerified(reference).facts().transactionId());
            assertEquals(0,number("SELECT COUNT(*) FROM wx_verified_payment_fact"),"reader must not weaken caller isolation");
            assertNotNull(sql.getMapper(CmsRegistrationMapper.class).selectCmsRegistrationByIdForUpdate(11L));
            String notify="PAY:WX-RR:SUCCESS";String hash=WxPaymentCallbackInboxServiceImpl.sha256Hex(notify);activeHash.set(hash);
            assertTrue(Boolean.TRUE.equals(suspended.execute(ignored->inbox.registerVerified(notify,1,11L,"SUCCESS",reference))));
            assertEquals(0,number("SELECT COUNT(*) FROM wx_payment_callback_fact_link"),"link committed after old RR view");
            suspended.executeWithoutResult(ignored->db.update("UPDATE wx_payment_callback_fact_link SET notify_hash=UPPER(notify_hash)"));
            assertTrue(store.hasInboxLinkForNotify(hash.toUpperCase(java.util.Locale.ROOT),reference));
            assertTrue(inbox.processVerified(notify,reference,new com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation(){
                @Override public boolean isCurrent(WxPaymentCallbackInbox row) {
                    return Long.valueOf(11L).equals(row.getBizId()) && "SUCCESS".equals(row.getEventType());
                }
                @Override public boolean apply(WxPaymentCallbackInbox row) {
                    return "SUCCESS".equals(registration.applyConfirmedWechatPaymentWithinTransaction(facts));
                }
            }));
        });
        assertCompleted("WX-RR",1);assertEquals(2,scheduledNotices.get());
        assertEquals(1,number("SELECT COUNT(*) FROM wx_payment_callback_fact_link l JOIN wx_payment_callback_inbox i ON i.id=l.inbox_id AND i.notify_hash=UNHEX(l.notify_hash)"));
        assertEquals(0,number("SELECT COUNT(*) FROM wx_payment_callback_fact_link l JOIN wx_payment_callback_inbox i ON i.notify_hash=l.notify_hash"));
    }

    @Test
    void falseMutationReleasesOriginalOwnerAndFenceOnlyAfterOuterRollback() {
        participatingCompletion("false");
    }

    @Test
    void throwingMutationReleasesOriginalOwnerAndFenceOnlyAfterExplicitOuterRollback() {
        participatingCompletion("exception");
    }

    @Test
    void successfulMutationAndDoneRollBackTogetherAndThenReleaseTheSameClaimOnOuterRollback() {
        participatingCompletion("rollback");
    }

    @Test
    void successfulParticipatingMutationAndDoneCommitTogetherWithoutReleasingTheCompletedClaim() {
        participatingCompletion("commit");
    }

    private void participatingCompletion(String ending) {
        String transactionId="WX-OUTER-"+ending;
        providerOrders.put(BUSINESS_NO,original(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS",transactionId));
        Map<String,String> facts=gateway.queryOrderStatus(BUSINESS_NO);
        var reference=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash"));
        var registration=(CmsRegistrationServiceImpl)WechatVerifiedFactTestSupport.getField(gateway,"registrationService");
        var inbox=(com.chengyinhub.business.service.IWxPaymentCallbackInboxService)
                WechatVerifiedFactTestSupport.getField(registration,"wxPaymentCallbackInboxService");
        String notify="PAY:"+transactionId+":SUCCESS";
        activeHash.set(WxPaymentCallbackInboxServiceImpl.sha256Hex(notify));
        assertTrue(inbox.registerVerified(notify,1,11L,"SUCCESS",reference));
        long inboxId=inboxNumber("id");
        var originalOwner=new AtomicReference<String>();
        var originalFence=new java.util.concurrent.atomic.AtomicLong();
        Runnable work=()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertNotNull(sql.getMapper(CmsRegistrationMapper.class).selectByRegistrationNo(BUSINESS_NO));
            boolean completed=inbox.processVerified(notify,reference,new com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation(){
                @Override public boolean isCurrent(WxPaymentCallbackInbox row) {
                    assertEquals(inboxId,row.getId().longValue());assertEquals(11L,row.getBizId().longValue());
                    originalOwner.set(row.getOwnerToken());originalFence.set(row.getFenceToken());return true;
                }
                @Override public boolean apply(WxPaymentCallbackInbox row) {
                    // Actual registration CAS, points, scans and original business transaction; no domain success stub.
                    assertEquals("SUCCESS",registration.applyConfirmedWechatPaymentWithinTransaction(facts));
                    if ("false".equals(ending)) return false;
                    if ("exception".equals(ending)) throw new IllegalStateException("controlled failure after actual business SQL");
                    return true;
                }
            });
            boolean success="rollback".equals(ending) || "commit".equals(ending);
            assertEquals(success,completed);
            assertNotNull(originalOwner.get());assertEquals(1,originalFence.get());
            assertEquals(0,probe.releaseProbes.get(),"no independent release while the outer owner still holds its X lock");
            assertEquals(2,number("SELECT payment_status FROM cms_registration WHERE id=11"));
            assertPaidResources();
            assertConsumptionInOriginalTransaction(transactionId);
            assertEquals(success?WxPaymentCallbackInbox.STATE_DONE:WxPaymentCallbackInbox.STATE_PROCESSING,inboxNumber("state"));
            assertVisibleOutside(1,WxPaymentCallbackInbox.STATE_PROCESSING,80,0,0);
            assertEquals(0,scheduledNotices.get());
            if ("rollback".equals(ending) || "exception".equals(ending)) status.setRollbackOnly();
        });
        if ("false".equals(ending)) {
            assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,work::run,
                    "REQUIRED mutation failure marks the original outer transaction rollback-only");
        } else work.run();
        assertEquals(inboxId,inboxNumber("id"));assertEquals(originalFence.get(),inboxNumber("fence_token"));
        assertEquals(1,inboxNumber("attempt_count"));
        assertEquals(1,number("SELECT COUNT(*) FROM wx_verified_payment_fact"));
        assertEquals(reference.hash(),db.queryForObject("SELECT fact_hash FROM wx_verified_payment_fact WHERE id=?",String.class,reference.id()));
        if ("commit".equals(ending)) {
            assertCompleted(transactionId,1);assertEquals(1,probe.doneProbes.get());
            assertEquals(0,probe.releaseProbes.get());assertEquals(2,scheduledNotices.get());
            assertEquals(originalOwner.get().toUpperCase(java.util.Locale.ROOT),
                    db.queryForObject("SELECT HEX(owner_token) FROM wx_payment_callback_inbox WHERE id=?",String.class,inboxId));
        } else {
            assertPendingResources();assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE,inboxNumber("state"));
            assertEquals(1,probe.releaseProbes.get());assertEquals(0,scheduledNotices.get());
            assertEquals("rollback".equals(ending)?1:0,probe.doneProbes.get());
            assertNull(db.queryForObject("SELECT owner_token FROM wx_payment_callback_inbox WHERE id=?",byte[].class,inboxId));
            assertNull(db.queryForObject("SELECT lease_until FROM wx_payment_callback_inbox WHERE id=?",java.sql.Timestamp.class,inboxId));
            assertEquals(1,number("SELECT COUNT(*) FROM wx_payment_callback_fact_link"));
        }
    }

    @Test
    void repeatedVerifiedCallbackInOneActualTransactionUsesOnlyItsExactFactAndOriginalReceipt() {
        CallbackContext callback=verifiedContext("WX-SAME-TX");Object key=transactionWorkKey(callback);
        var applies=new AtomicInteger();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertTrue(callback.inbox().processVerified(callback.notifyId(),callback.reference(),
                    new com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation(){
                @Override public boolean isCurrent(WxPaymentCallbackInbox row) { return Long.valueOf(11L).equals(row.getBizId()); }
                @Override public boolean apply(WxPaymentCallbackInbox row) {
                    applies.incrementAndGet();
                    assertFalse(callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()),
                            "in-progress re-entry must not independently claim its own locked inbox");
                    return "SUCCESS".equals(callback.registration().applyConfirmedWechatPaymentWithinTransaction(callback.facts()));
                }
            }));
            assertTrue(TransactionSynchronizationManager.hasResource(key));
            assertTrue(callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()));
            var conflicting=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(callback.reference().id(),"b".repeat(64));
            assertThrows(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.ConflictException.class,
                    ()->callback.inbox().processVerified(callback.notifyId(),conflicting,mustNotMutate()));
            assertFalse(callback.inbox().process(callback.notifyId(),mustNotMutate()),
                    "a bare legacy call cannot borrow a verified uncommitted acknowledgement");
            assertEquals(1,probe.claimUpdates.get());assertEquals(1,applies.get());assertEquals(1,probe.doneProbes.get());
            assertEquals(0,probe.releaseProbes.get());assertVisibleOutside(1,WxPaymentCallbackInbox.STATE_PROCESSING,80,0,0);
        });
        assertFalse(TransactionSynchronizationManager.hasResource(key));
        assertCompleted("WX-SAME-TX",1);assertEquals(2,scheduledNotices.get());
        assertTrue(callback.inbox().process(callback.notifyId(),mustNotMutate()),"committed DONE keeps its original legacy duplicate ACK");
        assertEquals(1,probe.claimUpdates.get());
    }

    @Test
    void suspendedNewTransactionsAndAnotherThreadCannotBorrowTheOuterVerifiedResult() throws Exception {
        CallbackContext callback=verifiedContext("WX-NESTED-TX");Object key=transactionWorkKey(callback);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertTrue(applyOriginal(callback));Object outerWork=TransactionSynchronizationManager.getResource(key);assertNotNull(outerWork);
            var nested=new org.springframework.transaction.support.TransactionTemplate(transactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThrows(org.springframework.dao.DataAccessException.class,()->nested.executeWithoutResult(inner->{
                assertFalse(TransactionSynchronizationManager.hasResource(key),"suspended outer marker is unavailable to a new actual transaction");
                callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate());
            }));
            assertSame(outerWork,TransactionSynchronizationManager.getResource(key));
            // Real independent storage contention, bounded to one second per claim query by the test interceptor.
            try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var other=worker.submit(()->callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()));
                var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->other.get(10,TimeUnit.SECONDS));
                assertInstanceOf(org.springframework.dao.DataAccessException.class,failure.getCause());
            }
            assertSame(outerWork,TransactionSynchronizationManager.getResource(key));
            assertTrue(callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()));
            assertEquals(1,probe.claimUpdates.get());assertEquals(1,probe.doneProbes.get());assertEquals(0,probe.releaseProbes.get());
        });
        assertFalse(TransactionSynchronizationManager.hasResource(key));
        assertCompleted("WX-NESTED-TX",1);assertEquals(2,scheduledNotices.get());
    }

    @Test
    void exceptionalOuterRollbackClearsTheMarkerAndTheSameStoredCallbackCanRecoverInANewTransaction() {
        CallbackContext callback=verifiedContext("WX-CLEANUP-TX");Object key=transactionWorkKey(callback);probe.failAfterDone.set(true);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertFalse(applyOriginal(callback));
            assertFalse(callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()));
            assertEquals(1,probe.claimUpdates.get());assertEquals(0,probe.releaseProbes.get());
            status.setRollbackOnly();
        });
        assertFalse(TransactionSynchronizationManager.hasResource(key));assertPendingResources();
        assertEquals(WxPaymentCallbackInbox.STATE_RETRYABLE,inboxNumber("state"));assertEquals(1,probe.releaseProbes.get());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertFalse(TransactionSynchronizationManager.hasResource(key));assertTrue(applyOriginal(callback));
            assertTrue(callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()));
        });
        assertFalse(TransactionSynchronizationManager.hasResource(key));assertCompleted("WX-CLEANUP-TX",1);
        assertEquals(2,inboxNumber("fence_token"));assertEquals(2,inboxNumber("attempt_count"));
        assertEquals(2,probe.claimUpdates.get());assertEquals(2,probe.doneProbes.get());assertEquals(2,scheduledNotices.get());
    }

    @Test
    void differentNotificationsInTheSameOuterTransactionKeepIndependentClaimAndReceiptIdentities() {
        CallbackContext callback=verifiedContext("WX-INDEPENDENT-TX");String stale="ORIGINAL-STALE-CONTROL";
        assertTrue(callback.inbox().register(stale,WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT,11L,"STALE"));
        String staleHash=WxPaymentCallbackInboxServiceImpl.sha256Hex(stale);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertTrue(applyOriginal(callback));
            assertTrue(callback.inbox().process(stale,new com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation(){
                @Override public boolean isCurrent(WxPaymentCallbackInbox row) {
                    assertEquals(11L,row.getBizId().longValue());assertEquals("STALE",row.getEventType());return false;
                }
                @Override public boolean apply(WxPaymentCallbackInbox row) { throw new AssertionError("stale control is never a fabricated business success"); }
            }));
            assertTrue(callback.inbox().processVerified(callback.notifyId(),callback.reference(),mustNotMutate()));
            assertEquals(2,probe.claimUpdates.get());assertEquals(1,probe.doneProbes.get());assertEquals(0,probe.releaseProbes.get());
        });
        assertCompleted("WX-INDEPENDENT-TX",2);assertEquals(2,scheduledNotices.get());
        assertEquals(WxPaymentCallbackInbox.STATE_IGNORED_STALE,db.queryForObject(
                "SELECT state FROM wx_payment_callback_inbox WHERE notify_hash=UNHEX(?)",Integer.class,staleHash));
        assertEquals(1,db.queryForObject("SELECT attempt_count FROM wx_payment_callback_inbox WHERE notify_hash=UNHEX(?)",Integer.class,staleHash));
    }

    @Test
    void originalBusinessRowSerializesFactCommitAndCloseCasAndRetainsLateSuccessForRecovery() throws Exception {
        // Both closers use the real original mapper CAS. This test does not simulate resource release/refund policy.
        var facts=(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService)
                WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts");
        var writerLocked=new java.util.concurrent.CountDownLatch(1);
        var releaseWriter=new java.util.concurrent.CountDownLatch(1);
        var closerEntered=new java.util.concurrent.CountDownLatch(1);
        var closeGuardRead=new java.util.concurrent.CountDownLatch(1);
        var lateWriterEntered=new java.util.concurrent.CountDownLatch(1);
        var releaseCloseCas=new java.util.concurrent.CountDownLatch(1);
        String lateNo="REG_CALLBACK_LATE_12";
        var owner=new com.chengyinhub.business.service.impl.JdbcWechatPaymentOrderOwner(){
            @Override public com.chengyinhub.business.service.WechatPaymentOrderOwner.Snapshot lock(String no) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                if(lateNo.equals(no)) lateWriterEntered.countDown();
                var locked=super.lock(no); // Original cms_registration SELECT ... FOR UPDATE, not a mock lock.
                if(BUSINESS_NO.equals(no)) { writerLocked.countDown();awaitGate(releaseWriter); }
                return locked;
            }
        };
        WechatVerifiedFactTestSupport.setField(owner,"paymentIntentJdbc",db);
        WechatVerifiedFactTestSupport.setField(facts,"factBusinessOwner",owner);
        providerOrders.put(BUSINESS_NO,original(BUSINESS_NO,"wx-mini","JSAPI","CLOSED","WX-FIRST"));
        assertEquals("CLOSED",gateway.queryOrderStatus(BUSINESS_NO).get("trade_state"));
        String originalHash=db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",String.class,BUSINESS_NO);
        providerOrders.put(BUSINESS_NO,original(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS","WX-FIRST"));
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writer=threads.submit(()->gateway.queryOrderStatus(BUSINESS_NO));
            try {
                assertTrue(writerLocked.await(10,TimeUnit.SECONDS));
                var close=threads.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                    closerEntered.countDown();
                    if(!gateway.lockClosablePaymentAttemptForUpdate(BUSINESS_NO,BUSINESS_NO)) return 0;
                    return sql.getMapper(CmsRegistrationMapper.class).closeExpiredPaying(11L,"fact-race",3);
                }));
                assertTrue(closerEntered.await(10,TimeUnit.SECONDS));releaseWriter.countDown();
                assertEquals("SUCCESS",writer.get(20,TimeUnit.SECONDS).get("trade_state"));
                assertEquals(0,close.get(20,TimeUnit.SECONDS).intValue(),"writer commits before closer can pass its guard");
            } finally { releaseWriter.countDown(); }
        }
        assertEquals(1,number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1,number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(originalHash,db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",String.class,BUSINESS_NO));

        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,"
                +"original_amount,total_amount,point_used,point_payment_amount,payable_amount,wechat_payment_amount,fee_platform_rate,"
                +"payment_status,registration_status,pay_expire_time,purchase_kind,points_returned)"
                +" VALUES(12,?,1,23,9,10.00,7,1,10.00,10.00,0,0.00,10.00,10.00,0.1234,1,1,DATE_ADD(NOW(),INTERVAL 1 HOUR),1,0)",lateNo);
        assertEquals(lateNo,WechatVerifiedFactTestSupport.invokeMethod(gateway,"freezePaymentIntent",lateNo,MERCHANT,
                "wx-mini","JSAPI","CNY",1000,"openid-original","late-success integration","https://example.test/notify"));
        providerOrders.put(lateNo,original(lateNo,"wx-mini","JSAPI","CLOSED","WX-LATE"));
        assertEquals("CLOSED",gateway.queryOrderStatus(lateNo).get("trade_state"));
        String lateHash=db.queryForObject("SELECT request_hash FROM wx_pay_order_intent WHERE out_trade_no=?",String.class,lateNo);
        providerOrders.put(lateNo,original(lateNo,"wx-mini","JSAPI","SUCCESS","WX-LATE"));
        Map<String,String> lateResult;
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var close=threads.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                assertTrue(gateway.lockClosablePaymentAttemptForUpdate(lateNo,lateNo));
                closeGuardRead.countDown();awaitGate(releaseCloseCas);
                return sql.getMapper(CmsRegistrationMapper.class).closeExpiredPaying(12L,"fact-race",3);
            }));
            try {
                assertTrue(closeGuardRead.await(10,TimeUnit.SECONDS));
                var writer=threads.submit(()->gateway.queryOrderStatus(lateNo));
                assertTrue(lateWriterEntered.await(10,TimeUnit.SECONDS));releaseCloseCas.countDown();
                assertEquals(1,close.get(20,TimeUnit.SECONDS).intValue());
                lateResult=writer.get(20,TimeUnit.SECONDS);
            } finally { releaseCloseCas.countDown(); }
        }
        assertEquals("SUCCESS",lateResult.get("trade_state"),"actual official SDK observation, never a historical status fallback");
        var reference=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                Long.parseLong(lateResult.get("verified_fact_id")),lateResult.get("verified_fact_hash"));
        var ticket=facts.findPaymentRecovery(reference).orElseThrow();
        assertEquals(12L,ticket.businessId());assertEquals(4,ticket.state());
        assertEquals("VERIFIED_SUCCESS_AFTER_CLOSED",ticket.reason());
        assertEquals(reference,ticket.original().reference());
        var original=ticket.original().facts();
        assertEquals(lateNo,original.businessOutTradeNo());assertEquals(lateNo,original.wxOutTradeNo());
        assertEquals("WX-LATE",original.transactionId());assertEquals(MERCHANT,original.mchid());
        assertEquals("wx-mini",original.appid());assertEquals("JSAPI",original.tradeType());
        assertEquals("CNY",original.currency());assertEquals(1000L,original.totalFen());assertEquals(lateHash,original.intentRequestHash());
        assertEquals(3,number("SELECT payment_status FROM cms_registration WHERE id=12"));
        assertEquals(3,number("SELECT registration_status FROM cms_registration WHERE id=12"));
        assertNull(db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=12",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact_rejection WHERE original_fact_id=? AND rejection_reason=?",Integer.class,reference.id(),ticket.reason()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_outbox WHERE id=? AND state IN (0,3)",Integer.class,ticket.id()),"manual handoff cannot enter automatic dispatch");
        assertEquals(reference,new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                Long.parseLong(gateway.queryOrderStatus(lateNo).get("verified_fact_id")),reference.hash()));
        assertEquals(ticket.id(),facts.findPaymentRecovery(reference).orElseThrow().id());
        assertEquals(2,number("SELECT COUNT(*) FROM wx_pay_order_intent"));
        assertEquals(2,number("SELECT COUNT(*) FROM wx_verified_payment_fact"));
        assertEquals(2,number("SELECT COUNT(*) FROM wx_payment_callback_outbox"));
        assertEquals(0,number("SELECT COUNT(*) FROM cms_registration_payment_fact"));
        assertPendingResources();verify(sdk,never()).closeOrder(any());
    }

    private static void awaitGate(java.util.concurrent.CountDownLatch gate) {
        try { assertTrue(gate.await(15,TimeUnit.SECONDS),"bounded deterministic transaction barrier"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
    }

    private record CallbackContext(Map<String,String> facts,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference,
            com.chengyinhub.business.service.IWxPaymentCallbackInboxService inbox,
            CmsRegistrationServiceImpl registration,String notifyId) { }

    private CallbackContext verifiedContext(String transactionId) {
        providerOrders.put(BUSINESS_NO,original(BUSINESS_NO,"wx-mini","JSAPI","SUCCESS",transactionId));
        Map<String,String> facts=gateway.queryOrderStatus(BUSINESS_NO);
        var reference=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                Long.parseLong(facts.get("verified_fact_id")),facts.get("verified_fact_hash"));
        var registration=(CmsRegistrationServiceImpl)WechatVerifiedFactTestSupport.getField(gateway,"registrationService");
        var inbox=(com.chengyinhub.business.service.IWxPaymentCallbackInboxService)
                WechatVerifiedFactTestSupport.getField(registration,"wxPaymentCallbackInboxService");
        String notify="PAY:"+transactionId+":SUCCESS";activeHash.set(WxPaymentCallbackInboxServiceImpl.sha256Hex(notify));
        assertTrue(inbox.registerVerified(notify,1,11L,"SUCCESS",reference));
        return new CallbackContext(facts,reference,inbox,registration,notify);
    }

    private Object transactionWorkKey(CallbackContext callback) {
        return WechatVerifiedFactTestSupport.getField(callback.inbox(),"transactionWorkKey");
    }

    private boolean applyOriginal(CallbackContext callback) {
        return callback.inbox().processVerified(callback.notifyId(),callback.reference(),
                new com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation(){
            @Override public boolean isCurrent(WxPaymentCallbackInbox row) { return Long.valueOf(11L).equals(row.getBizId()); }
            @Override public boolean apply(WxPaymentCallbackInbox row) {
                return "SUCCESS".equals(callback.registration().applyConfirmedWechatPaymentWithinTransaction(callback.facts()));
            }
        });
    }

    private com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation mustNotMutate() {
        return new com.chengyinhub.business.service.IWxPaymentCallbackInboxService.Mutation(){
            @Override public boolean isCurrent(WxPaymentCallbackInbox row) { throw new AssertionError("no second owner check/mutation"); }
            @Override public boolean apply(WxPaymentCallbackInbox row) { throw new AssertionError("no second business mutation"); }
        };
    }

    private String freeze(String appid, String channel) {
        return WechatVerifiedFactTestSupport.invokeMethod(gateway, "freezePaymentIntent", BUSINESS_NO, MERCHANT,
                appid, channel, "CNY", 1000, "JSAPI".equals(channel) ? "openid-original" : "",
                "registration callback integration", "https://example.test/notify");
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    private String deliver(SignedNotification notification, String transactionId, String state) throws Exception {
        activeHash.set(WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:" + transactionId + ":" + state));
        probe.businessConnection.set(null);
        return json.readTree(gateway.handlePayCallback(notification.body(), notification.headers())).path("code").asText();
    }

    private long number(String query) { return db.queryForObject(query, Long.class); }
    private long inboxNumber(String column) {
        assertTrue(List.of("id", "state", "fence_token", "attempt_count", "biz_type", "biz_id").contains(column));
        // The independent claim may be newer than this transaction's intentional old RR snapshot.
        String currentRead = TransactionSynchronizationManager.isActualTransactionActive() ? " FOR UPDATE" : "";
        return db.queryForObject("SELECT " + column + " FROM wx_payment_callback_inbox WHERE notify_hash=UNHEX(?)" + currentRead, Long.class, activeHash.get());
    }

    private void assertPendingResources() {
        assertEquals(0,number("SELECT COUNT(*) FROM cms_registration_payment_fact WHERE registration_id=11"));
        assertEquals(0,number("SELECT COUNT(*) FROM im_notification_intent"));
        assertEquals(1, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(1, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertNull(db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=11", String.class));
        assertEquals(80, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_points_detail"));
        assertEquals(0, number("SELECT COUNT(*) FROM cms_registration_scan"));
        assertReservedInventoryAndOwner();
    }

    private void assertPaidResources() {
        assertEquals(95, number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(2, number("SELECT COUNT(*) FROM ums_member_points_detail"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE reward_business_key='registration:2:0' AND change_points=5 AND member_id=7"));
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_points_detail WHERE reward_business_key='registration:3:11' AND change_points=10 AND member_id=7"));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration_scan WHERE registration_id=11 AND member_id=8 AND template_id=90 AND status=0"));
        assertReservedInventoryAndOwner();
    }

    private void assertReservedInventoryAndOwner() {
        assertEquals(5, number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(1, number("SELECT COUNT(*) FROM registration_entitlement WHERE registration_id=11 AND chapter_id=35 AND topic_id=23 AND status=0"));
        assertEquals(1, number("SELECT COUNT(*) FROM registration_entitlement"));
        assertEquals(1, number("SELECT COUNT(*) FROM cms_registration WHERE id=11 AND member_id=7 AND owner_type=1 AND owner_id=23"
                + " AND ticket_id=9 AND wechat_payment_amount=10.00 AND fee_platform_rate=0.1234 AND point_used=20 AND points_returned=0"));
        assertEquals("12.34", db.queryForObject("SELECT balance FROM ums_member WHERE id=7", java.math.BigDecimal.class).toPlainString());
    }

    private void assertCompleted(String transactionId, long inboxRows) {
        assertCommittedConsumption(transactionId);
        assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
        assertEquals(2, number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(transactionId, db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=11", String.class));
        assertEquals(WxPaymentCallbackInbox.STATE_DONE, inboxNumber("state"));
        assertEquals(inboxRows, number("SELECT COUNT(*) FROM wx_payment_callback_inbox"));
        assertPaidResources();
        assertOriginalSignupIntent();
        verify(notices, times(1)).dispatchInNewTransaction(eq("registration-qrcode"), eq(11L), any());
        verify(notices, times(1)).dispatchInNewTransaction(eq("registration-signup"), eq(11L), any());
    }

    private void assertOriginalSignupIntent() {
        assertEquals(1, number("SELECT COUNT(*) FROM im_notification_intent"));
        var intent = db.queryForMap("SELECT id,event_key,recipient,msg_type,content,extra_json,request_hash,status,message_id FROM im_notification_intent");
        assertTrue(((Number) intent.get("id")).longValue() > 0);
        assertEquals("registration-signup:11", intent.get("event_key"));
        assertEquals(7L, ((Number) intent.get("recipient")).longValue());
        assertEquals(com.chengyinhub.business.domain.ImMessage.MSG_CARD, ((Number) intent.get("msg_type")).intValue());
        assertEquals("报名成功", intent.get("content"));
        String expectedExtra = "{\"cardType\":\"signup\",\"topicId\":23}";
        assertEquals(expectedExtra, intent.get("extra_json"));
        assertEquals(com.chengyinhub.business.service.support.CanonicalDigest.sha256Hex(com.alibaba.fastjson2.JSON.toJSONString(
                java.util.Arrays.asList(7L, ((Number) intent.get("msg_type")).intValue(), "报名成功", expectedExtra))), intent.get("request_hash"));
        assertEquals("PENDING", intent.get("status"));
        assertNull(intent.get("message_id"), "a wakeup is not delivery");
    }

    private void assertConsumptionInOriginalTransaction(String transactionId) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertOriginalSignupIntent();
        var consumed=sql.getMapper(CmsRegistrationPaymentFactMapper.class).selectByRegistrationForUpdate(11L);
        assertNotNull(consumed);assertEquals(11L,consumed.registrationId().longValue());
        assertEquals(BUSINESS_NO,consumed.registrationNo());assertEquals(transactionId,consumed.transactionId());
        assertEquals(probe.businessConnection.get(),db.queryForObject("SELECT CONNECTION_ID()",Long.class));
        var provider=(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService)
                WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts");
        var original=provider.requireVerified(consumed.reference()).facts();
        assertEquals(BUSINESS_NO,original.businessOutTradeNo());assertEquals(transactionId,original.transactionId());
        assertEquals(MERCHANT,original.mchid());assertEquals("CNY",original.currency());assertEquals(1000L,original.totalFen());
    }

    private void assertCommittedConsumption(String transactionId) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var consumed=sql.getMapper(CmsRegistrationPaymentFactMapper.class).selectByRegistration(11L);
        assertNotNull(consumed);assertEquals(11L,consumed.registrationId().longValue());
        assertEquals(BUSINESS_NO,consumed.registrationNo());assertEquals(transactionId,consumed.transactionId());
        assertEquals(1,number("SELECT COUNT(*) FROM cms_registration_payment_fact"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact r JOIN wx_payment_callback_fact_link l "
                +"ON l.fact_id=r.payment_fact_id AND BINARY l.fact_hash=BINARY r.payment_fact_hash "
                +"JOIN wx_payment_callback_inbox i ON i.id=l.inbox_id AND i.notify_hash=UNHEX(l.notify_hash) "
                +"WHERE r.registration_id=11 AND i.state=2",Integer.class));
    }

    /** A raw new connection (not DataSourceUtils) must see the prior committed snapshot while mutation is open. */
    private void assertVisibleOutside(int payment, int inboxState, int points, int rewardRows, int scanRows) {
        try (Connection observer = source.getConnection(); PreparedStatement statement = observer.prepareStatement(
                "SELECT payment_status,(SELECT state FROM wx_payment_callback_inbox WHERE notify_hash=UNHEX(?)),"
                        + "(SELECT point FROM ums_member WHERE id=7),(SELECT COUNT(*) FROM ums_member_points_detail),"
                        + "(SELECT COUNT(*) FROM cms_registration_scan),CONNECTION_ID(),"
                        + "(SELECT COUNT(*) FROM cms_registration_payment_fact WHERE registration_id=11),"
                        + "(SELECT COUNT(*) FROM im_notification_intent WHERE event_key='registration-signup:11') FROM cms_registration WHERE id=11")) {
            assertTrue(observer.getAutoCommit());
            statement.setString(1, activeHash.get());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals(payment, rows.getInt(1)); assertEquals(inboxState, rows.getInt(2));
                assertEquals(points, rows.getInt(3)); assertEquals(rewardRows, rows.getInt(4)); assertEquals(scanRows, rows.getInt(5));
                assertNotEquals(probe.businessConnection.get(), Long.valueOf(rows.getLong(6)), "observer must use a different physical connection");
                assertEquals(payment==2?1:0,rows.getInt(7),"local consumption becomes visible with the real business and DONE commit only");
                assertEquals(payment==2?1:0,rows.getInt(8),"original notification intent shares the payment commit/rollback");
            }
        } catch (java.sql.SQLException observerFailure) {
            throw new AssertionError("Independent original transaction readback failed",observerFailure);
        }
    }

    @Intercepts({@Signature(type=Executor.class, method="update", args={MappedStatement.class, Object.class}),
            @Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,
                    org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class})})
    private final class AtomicCommitProbe implements Interceptor {
        final AtomicBoolean failAfterDone = new AtomicBoolean();
        final AtomicInteger doneProbes = new AtomicInteger();
        final AtomicInteger releaseProbes = new AtomicInteger();
        final AtomicInteger claimUpdates = new AtomicInteger();
        final AtomicReference<Long> businessConnection = new AtomicReference<>();
        void reset() { failAfterDone.set(false); doneProbes.set(0); releaseProbes.set(0); claimUpdates.set(0); businessConnection.set(null); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement)invocation.getArgs()[0]).getId();
            if (id.equals(WxPaymentCallbackInboxMapper.class.getName()+".selectForUpdate")) {
                try(var statement=((Executor)invocation.getTarget()).getTransaction().getConnection().createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout=1"); // Bound real nested/other-thread claim contention.
                }
            }
            if (id.equals(WxPaymentCallbackInboxMapper.class.getName()+".claim")) claimUpdates.incrementAndGet();
            if (id.equals(WxPaymentCallbackInboxMapper.class.getName() + ".release")) {
                releaseProbes.incrementAndGet();
                assertNotEquals(businessConnection.get(),mappedConnectionId(invocation),"release uses its original independent transaction after outer completion");
                // Test-only bound: an unsafe pre-completion release must fail promptly, never hang CI for the server default.
                try (var statement=((Executor)invocation.getTarget()).getTransaction().getConnection().createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                }
                return invocation.proceed();
            }
            if (id.equals(CmsRegistrationMapper.class.getName() + ".claimPaymentSuccess")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                Object result = invocation.proceed();
                if (Integer.valueOf(1).equals(result)) {
                    businessConnection.set(mappedConnectionId(invocation));
                    assertEquals(businessConnection.get(), db.queryForObject("SELECT CONNECTION_ID()", Long.class));
                }
                return result;
            }
            if (!id.equals(WxPaymentCallbackInboxMapper.class.getName() + ".markDone")) return invocation.proceed();
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertNotNull(businessConnection.get());
            assertEquals(businessConnection.get(), mappedConnectionId(invocation));
            assertEquals(businessConnection.get(), db.queryForObject("SELECT CONNECTION_ID()", Long.class));
            assertEquals(2, number("SELECT payment_status FROM cms_registration WHERE id=11"));
            assertEquals(WxPaymentCallbackInbox.STATE_PROCESSING, inboxNumber("state"));
            assertPaidResources();
            assertConsumptionInOriginalTransaction(db.queryForObject("SELECT transaction_id FROM cms_registration WHERE id=11",String.class));
            assertVisibleOutside(1, WxPaymentCallbackInbox.STATE_PROCESSING, 80, 0, 0);
            Object updated = invocation.proceed();
            assertEquals(1, updated);
            assertEquals(WxPaymentCallbackInbox.STATE_DONE, inboxNumber("state"));
            assertVisibleOutside(1, WxPaymentCallbackInbox.STATE_PROCESSING, 80, 0, 0);
            doneProbes.incrementAndGet();
            if (failAfterDone.getAndSet(false)) throw new IllegalStateException("test fault after business and DONE SQL, before commit");
            return updated;
        }
        private Long mappedConnectionId(Invocation invocation) throws Exception {
            Connection mappedConnection = ((Executor)invocation.getTarget()).getTransaction().getConnection();
            try (var statement = mappedConnection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private Transaction original(String no, String appid, String channel, String state, String transactionId) {
        Transaction tx = new Transaction(); tx.setOutTradeNo(no); tx.setMchid(MERCHANT); tx.setAppid(appid);
        tx.setTradeType(Transaction.TradeTypeEnum.valueOf(channel)); tx.setTradeState(Transaction.TradeStateEnum.valueOf(state));
        tx.setTransactionId(transactionId); tx.setSuccessTime("2026-10-24T00:00:00Z");
        TransactionAmount amount = new TransactionAmount(); amount.setTotal(1000); amount.setCurrency("CNY"); tx.setAmount(amount);
        return tx;
    }

    private SignedNotification notification(String no, String appid, String channel, String state, String transactionId, int fen) throws Exception {
        String plaintext = json.writeValueAsString(Map.of("out_trade_no", no, "appid", appid, "mchid", MERCHANT,
                "trade_type", channel, "trade_state", state, "transaction_id", transactionId,
                "success_time", "2026-10-24T00:00:00Z", "amount", Map.of("total", fen, "currency", "CNY")));
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ciphertext = new AeadAesCipher(apiV3Key.getBytes(StandardCharsets.UTF_8)).encrypt(
                "transaction".getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8), plaintext.getBytes(StandardCharsets.UTF_8));
        String body = json.writeValueAsString(Map.of("id", "test-event", "event_type", "TRANSACTION." + state,
                "create_time", "2026-10-23T00:00:00Z", "resource_type", "encrypt-resource",
                "resource", Map.of("original_type", "transaction", "algorithm", "AEAD_AES_256_GCM",
                        "ciphertext", ciphertext, "associated_data", "transaction", "nonce", nonce)));
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String headerNonce = UUID.randomUUID().toString().replace("-", "");
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update((timestamp + "\n" + headerNonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        return new SignedNotification(body, Map.of("wechatpay-serial", SERIAL, "wechatpay-timestamp", timestamp,
                "wechatpay-nonce", headerNonce, "wechatpay-signature", Base64.getEncoder().encodeToString(signer.sign())));
    }

    private record SignedNotification(String body, Map<String, String> headers) { }
}
