package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real InnoDB/XML/domain/inbox chain; only the provider SDK and unrelated settlement accounts are stubs. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@EnabledIfEnvironmentVariable(named = "CHENGYIN_B_PAYMENT_FACT_DDL_APPROVED", matches = "1")
@EnabledIfEnvironmentVariable(named = "CHENGYIN_A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingCashOriginalRefundMySqlIT {
    private static final String ORDER = "MKO-CASH-REFUND-10";
    private final String suite = "mko_cash_rf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private JdbcTemplate db;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private MarketingProductOrderMapper orders;
    private CmsTopicMapper topics;
    private CmsRegistrationMapper registrations;
    private RefundApplicationMapper refundRows;
    private CmsRegistrationPaymentFactMapper ordinaryReceipts;
    private WechatVerifiedPaymentFactService facts;
    private MarketingProductPurchaseServiceImpl purchases;
    private GiftEntitlementServiceImpl giftService;
    private RefundServiceImpl refunds;
    private RefundPayoutServiceImpl payout;
    private IWechatPayService provider;
    private WechatVerifiedPaymentFact.Reference paymentRef;
    private Long first, second;
    private String giftToken;

    @BeforeAll void schemaAndRealOwners() throws Exception {
        assertEquals("1", System.getenv("CHENGYIN_B_PAYMENT_FACT_DDL_APPROVED"));
        assertEquals("1", System.getenv("CHENGYIN_A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        DriverManagerDataSource source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite), db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(db.queryForObject("SELECT VERSION()", String.class)));
        manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        P0AcceptanceSchema.baseTables(db,"cms_topic","cms_activity","cms_registration","refund_application","player_node_progress");
        // Existing current-topic projection, confined to this class's UUID scratch schema.
        db.execute("ALTER TABLE cms_topic ADD COLUMN beta_flag TINYINT NOT NULL DEFAULT 0,"
                + " ADD COLUMN city_code VARCHAR(32), ADD COLUMN content_contract_json JSON,"
                + " ADD COLUMN circle_theme_code VARCHAR(24), ADD COLUMN source_template_topic_id BIGINT,"
                + " ADD COLUMN complete_rule_json JSON, ADD COLUMN route_mode VARCHAR(20) DEFAULT 'LINEAR',"
                + " ADD COLUMN route_graph_json JSON, ADD COLUMN config_version BIGINT DEFAULT 1,"
                + " ADD COLUMN published_route_graph_json JSON, ADD COLUMN published_config_version BIGINT DEFAULT 0,"
                + " ADD COLUMN game_module_config_json JSON, ADD COLUMN journey_rules_json JSON, ADD COLUMN journey_story_json JSON,"
                + " ADD COLUMN theme_runtime_config_json MEDIUMTEXT, ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT,"
                + " ADD COLUMN team_mode TINYINT NOT NULL DEFAULT 0, ADD COLUMN team_max_members INT NOT NULL DEFAULT 4,"
                + " ADD COLUMN club_coop_open TINYINT NOT NULL DEFAULT 1, ADD COLUMN club_pinned TINYINT NOT NULL DEFAULT 0,"
                + " ADD COLUMN club_member_only TINYINT NOT NULL DEFAULT 0");
        db.execute("ALTER TABLE refund_application ADD COLUMN platform_takeover_at DATETIME");
        db.execute("ALTER TABLE player_node_progress ADD COLUMN play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',"
                + " ADD COLUMN puzzle_score SMALLINT NULL, ADD COLUMN completion_mode VARCHAR(16) NULL");
        P0AcceptanceSchema.migrations(source,"migration_20260919_cms_registration_selfplay_ticket_columns_nullable.sql",
                "migration_20260930_registration_first_successful_scan.sql", "migration_merchant_aftercare_timeout_20260917.sql",
                "migration_20261023_wx_pay_order_intent.sql", "migration_wx_payment_callback_inbox_outbox_20260725.sql",
                "migration_20261024_wx_verified_payment_fact.sql", "migration_20261024_registration_payment_fact.sql",
                "migration_20261029_activity_cancellation_receipt.sql", "migration_20261029_topic_cancellation_receipt.sql",
                "migration_marketing_product_20261108.sql", "migration_marketing_revenue_allocation_20261108.sql");
        P0AcceptanceSchema.createTableFromMigration(db,"migration_20260823_topic_route_graph.sql");
        var sql = P0AcceptanceSchema.sql(source,"CmsTopicMapper","CmsActivityMapper","CmsRegistrationMapper",
                "RefundApplicationMapper","MarketingProductMapper","MarketingProductOrderMapper","GiftEntitlementMapper",
                "PlayerNodeProgressMapper","MarketingRevenueAllocationMapper","MarketingRevenuePostingTaskMapper",
                "WxPaymentCallbackInboxMapper","CmsRegistrationPaymentFactMapper");
        orders = sql.getMapper(MarketingProductOrderMapper.class);
        registrations = sql.getMapper(CmsRegistrationMapper.class);
        refundRows = sql.getMapper(RefundApplicationMapper.class);
        ordinaryReceipts = sql.getMapper(CmsRegistrationPaymentFactMapper.class);
        topics = sql.getMapper(CmsTopicMapper.class);
        var activities = sql.getMapper(CmsActivityMapper.class);
        var products = sql.getMapper(MarketingProductMapper.class);
        var gifts = sql.getMapper(GiftEntitlementMapper.class);
        var progress = sql.getMapper(PlayerNodeProgressMapper.class);
        facts = WechatVerifiedFactTestSupport.realFacts(source);
        var original = transactional(new MarketingCashRefundOriginalSupport(orders,registrations,topics,gifts,db,refundRows,facts));
        giftService = transactional(new GiftEntitlementServiceImpl(gifts,products,registrations,progress,"cash-refund-test-only"));
        var posting = new MarketingRevenuePostingServiceImpl(null,sql.getMapper(MarketingRevenuePostingTaskMapper.class),refundRows,manager);
        var fulfillment = new MarketingProductFulfillmentServiceImpl();
        set(fulfillment,"cmsRegistrationMapper",registrations); set(fulfillment,"cmsTopicMapper",topics);
        set(fulfillment,"productOrderMapper",orders); set(fulfillment,"verifiedPaymentFacts",facts);
        var productRoles = mock(IProductRoleService.class); when(productRoles.isPlatformOwned(anyLong())).thenReturn(true);
        set(fulfillment,"productRoleService",productRoles); set(fulfillment,"perkLedgerService",mock(IPerkLedgerService.class));
        set(fulfillment,"marketingRevenuePostingService",posting); set(fulfillment,"pointsValuePolicy",PointsValuePolicy.codeDefaults());
        purchases = transactional(new MarketingProductPurchaseServiceImpl(products,orders,gifts,null,null,
                new MarketingProductRules(products,orders,topics,null),PointsValuePolicy.codeDefaults(),null,
                new MarketingCashChannel(false),giftService,transactional(fulfillment),null,facts,null));
        var inboxRows = sql.getMapper(WxPaymentCallbackInboxMapper.class);
        var mutation = new WxPaymentCallbackMutationService();
        set(mutation,"mapper",inboxRows); set(mutation,"registrationMapper",registrations);
        set(mutation,"activityMapper",activities); set(mutation,"topicMapper",topics); set(mutation,"refundMapper",refundRows);
        set(mutation,"marketingCashRefunds",original);
        var claim = new WxPaymentCallbackClaimService(); set(claim,"mapper",inboxRows);
        var inbox = new WxPaymentCallbackInboxServiceImpl(); set(inbox,"mapper",inboxRows);
        set(inbox,"claimService",transactional(claim)); set(inbox,"mutationService",transactional(mutation));
        set(inbox,"verifiedFacts",facts); set(inbox,"alarmService",mock(AlarmService.class));
        var refundTarget = new RefundServiceImpl();
        set(refundTarget,"refundApplicationMapper",refundRows); set(refundTarget,"cmsRegistrationMapper",registrations);
        set(refundTarget,"cmsActivityMapper",activities); set(refundTarget,"cmsTopicMapper",topics);
        set(refundTarget,"verifiedPaymentFacts",facts); set(refundTarget,"marketingCashRefunds",original);
        set(refundTarget,"wxPaymentCallbackInboxService",transactional(inbox));
        set(refundTarget,"coopSettlementClaimService",mock(ICoopSettlementClaimService.class));
        set(refundTarget,"settlementDetailMapper",mock(SettlementDetailMapper.class));
        set(refundTarget,"perkLedgerService",mock(IPerkLedgerService.class));
        set(refundTarget,"merchantReferralBountyService",mock(MerchantReferralBountyService.class));
        set(refundTarget,"firstReviewPointsClawback",mock(FirstReviewPointsClawbackService.class));
        set(refundTarget,"notifyService",mock(INotifyService.class)); set(refundTarget,"alarmService",mock(AlarmService.class));
        set(refundTarget,"refundSettledConsumers",List.of(new MarketingProductRefundConsumer(orders,purchases,registrations,refundRows,gifts,progress),
                new MarketingRevenueRefundSettledConsumer(posting,sql.getMapper(MarketingRevenueAllocationMapper.class))));
        refunds = transactional(refundTarget);
        provider = mock(IWechatPayService.class);
        payout = new RefundPayoutServiceImpl(); set(payout,"refundApplicationMapper",refundRows);
        set(payout,"cmsRegistrationMapper",registrations); set(payout,"refundService",refunds);
        set(payout,"wechatPayService",provider); set(payout,"marketingCashRefunds",original);
        set(payout,"paymentConsumption",new RegistrationPaymentConsumptionReadService(ordinaryReceipts));
        set(payout,"payoutTransactions",manager);
    }

    @BeforeEach void pendingOrderThenConsumeOriginalPayment(TestInfo test) {
        reset(provider);
        for (String table : List.of("wx_payment_callback_fact_link","wx_payment_callback_inbox","wx_payment_callback_outbox",
                "wx_verified_payment_fact_rejection","wx_verified_payment_fact","wx_pay_order_intent",
                "marketing_revenue_posting_task","gift_entitlement","marketing_product_order_line","marketing_product_order",
                "marketing_product_item","marketing_product","refund_application","cms_registration","player_node_progress","cms_topic"))
            db.update("DELETE FROM "+table);
        db.update("INSERT INTO cms_topic(id,member_id,name,self_play_price,fee_platform_rate,start_date,end_date)"
                + " VALUES(41,7,'one',40,0.1,DATE_ADD(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 2 DAY)),"
                + "(42,7,'two',60,0.1,DATE_ADD(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 2 DAY))");
        // Read through the purchase mapper so an incomplete scratch projection cannot masquerade as a missing price.
        assertEquals(money(4000), topics.selectCmsTopicById(41L).getSelfPlayPrice());
        assertEquals(money(6000), topics.selectCmsTopicById(42L).getSelfPlayPrice());
        db.update("INSERT INTO marketing_product(id,product_code,product_name,product_kind,purchase_kind,cash_price,"
                + "validity_kind,seat_count,gift_allowed,status,terms_version,revision,create_time,update_time)"
                + " VALUES(1,'cash-chain','bundle',2,2,100,0,1,1,1,'test-v1',1,NOW(),NOW())");
        db.update("INSERT INTO marketing_product_item(product_id,topic_id,topic_title,content_revision,sort_no,create_time) VALUES(1,41,'one',1,1,NOW()),(1,42,'two',1,2,NOW())");
        MarketingProductOrder order = new MarketingProductOrder(); order.setOrderNo(ORDER); order.setMemberId(7L);
        order.setProductId(1L); order.setProductRevision(1L); order.setFulfillmentKind(1); order.setPayMode(1);
        order.setQuantity(1); order.setUnitCashPrice(money(10000)); order.setTotalCashAmount(money(10000));
        order.setCurrency("CNY"); order.setProductSnapshotJson("{\"seatCount\":1,\"quantity\":1}");
        order.setQuoteSign("a".repeat(64)); order.setQuoteExpiresAt(new Date(System.currentTimeMillis()+86400000));
        order.setStatus(0); order.setFulfillmentState(0); order.setRequestId("cash-chain-10");
        order.setRequestFingerprint("b".repeat(64)); order.setCreateTime(new Date()); order.setUpdateTime(new Date());
        boolean gift = test.getTestMethod().orElseThrow().getName().startsWith("gift");
        if (gift) { order.setFulfillmentKind(2); order.setGiftRecipientMemberId(9L); }
        assertEquals(1,orders.insert(order));
        assertEquals(MarketingProductOrder.STATUS_PENDING_CASH,orders.selectByOrderNo(ORDER).getStatus());
        assertEquals(0,count("cms_registration"));
        db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,"
                + "currency,total_fen,payer_openid,request_description,notify_url,request_hash)"
                + " VALUES('merchant','WX-CASH-10',?,'MARKETING',?,1,'appid','JSAPI','CNY',10000,'fixture','fixture','https://example.invalid',?)",
                ORDER,order.getId(),"c".repeat(64));
        // SDK-boundary fixture enters the real immutable fact owner; this is not a provider acceptance receipt.
        paymentRef = facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "merchant","appid",ORDER,"WX-CASH-10","WX-CASH-10","TX10","TX10","JSAPI","CNY",10000,10000,
                Instant.parse("2026-11-09T00:00:00Z"),"c".repeat(64),"SUCCESS"));
        giftToken = null;
        if (gift) {
            Map<String,Object> result = purchases.settleVerifiedCashPayment(ORDER,paymentRef.id(),paymentRef.hash());
            giftToken = (String)((Map<?,?>)result.get("gift")).get("claimToken");
            assertNotNull(giftToken);
        } else assertTrue(purchases.handlePaidOrder(ORDER,true,10000,"TX10",paymentRef));
        var rows = tx.execute(s -> registrations.selectByParentOrderNoForUpdate(ORDER,null));
        assertNotNull(rows); assertEquals(2,rows.size());
        first = rows.stream().filter(r -> Long.valueOf(41).equals(r.getTopicId())).findFirst().orElseThrow().getId();
        second = rows.stream().filter(r -> Long.valueOf(42).equals(r.getTopicId())).findFirst().orElseThrow().getId();
        var paid = orders.selectByOrderNo(ORDER);
        assertEquals(paymentRef.id(),paid.getPaymentFactId().longValue());
        assertEquals(MarketingProductOrder.STATUS_PAID,paid.getStatus());
        assertEquals(MarketingProductOrder.GRANT_DONE,paid.getFulfillmentState());
        var frozen = orders.selectFrozenLines(ORDER);
        assertEquals(2,frozen.size());
        for (var line : frozen) {
            BigDecimal expected = Long.valueOf(41).equals(line.getTopicId()) ? money(4000) : money(6000);
            assertEquals(expected,line.getListAmount());
            assertEquals(expected,line.getPayableAmount());
        }
        assertEquals(money(10000),frozen.stream().map(MarketingProductOrderLine::getPayableAmount)
                .reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(money(10000),rows.stream().map(CmsRegistration::getPayableAmount)
                .reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(0,count("cms_registration_payment_fact"));
    }

    @AfterAll void dropExactOwnedScratch() { if (db != null) P0AcceptanceSchema.drop(db,suite); }

    @Test void originalParentPaymentRefundsBothRealDeliveryRowsThroughRealInboxAndConsumers() {
        assertTrue(purchases.handlePaidOrder(ORDER,true,10000,"TX10",paymentRef));
        assertEquals(2,count("cms_registration"));
        RefundApplication a = applyAndApprove(second,6000);
        dispatch(a,6000);
        Map<String,String> event = success(a,6000);
        assertTrue(refunds.handleRefundNotify(event)); assertTrue(refunds.handleRefundNotify(event));
        assertEquals(RefundApplication.PAYOUT_SUCCESS,refundRows.selectRefundApplicationById(a.getId()).getPayoutStatus());
        assertEquals(2,registrations.selectCmsRegistrationById(first).getPaymentStatus());
        assertEquals(1,orders.selectByOrderNo(ORDER).getStatus());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE task_kind=2 AND ref_id=?",Integer.class,a.getId()));
        RefundApplication b = applyAndApprove(first,4000); dispatch(b,4000);
        assertTrue(refunds.handleRefundNotify(success(b,4000)));
        assertEquals(3,orders.selectByOrderNo(ORDER).getStatus());
        assertEquals(2,count("refund_application")); assertEquals(0,count("cms_registration_payment_fact"));
        assertEquals(0,registrations.selectCmsRegistrationById(second).getWechatPaymentAmount().signum());
        assertNull(registrations.selectCmsRegistrationById(second).getTransactionId());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox WHERE state=2",Integer.class));
    }

    @Test void wholeBundlePartialAmountAndWrongBindingFailBeforeAnyRefundIntent() {
        assertThrows(RuntimeException.class,() -> apply(first,10000));
        assertThrows(RuntimeException.class,() -> apply(second,3000));
        db.update("UPDATE cms_registration SET member_id=8 WHERE id=?",second);
        assertThrows(RuntimeException.class,() -> apply(second,6000));
        db.update("UPDATE cms_registration SET member_id=7 WHERE id=?",second);
        db.update("UPDATE marketing_product_order SET registration_ids_json=? WHERE order_no=?","["+first+"]",ORDER);
        assertThrows(RuntimeException.class,() -> apply(second,6000));
        assertEquals(0,count("refund_application")); verifyNoInteractions(provider);
    }

    @Test void unconsumedFactAndAllocationDriftCannotAuthorizeAChildRefund() {
        db.update("UPDATE marketing_product_order SET payment_fact_id=payment_fact_id+1000 WHERE order_no=?",ORDER);
        assertThrows(RuntimeException.class,() -> apply(second,6000));
        db.update("UPDATE marketing_product_order SET payment_fact_id=? WHERE order_no=?",paymentRef.id(),ORDER);
        db.update("UPDATE marketing_product_order_line SET payable_amount=59 WHERE order_id=? AND topic_id=42",orders.selectByOrderNo(ORDER).getId());
        assertThrows(RuntimeException.class,() -> apply(second,6000));
        assertEquals(0,count("refund_application")); verifyNoInteractions(provider);
    }

    @Test void callbackBeforeApprovalAndChangedParentIdentityDoNotAdvance() {
        RefundApplication refund = apply(second,6000);
        Map<String,String> event = success(refund,6000);
        assertFalse(refunds.handleRefundNotify(event));
        assertEquals(0,refundRows.selectRefundApplicationById(refund.getId()).getStatus());
        approve(refund); dispatch(refund,6000);
        Map<String,String> changed = new HashMap<>(event); changed.put("transactionId","OTHER");
        assertFalse(refunds.handleRefundNotify(changed));
        changed = new HashMap<>(event); changed.put("outTradeNo",registrations.selectCmsRegistrationById(second).getRegistrationNo());
        assertFalse(refunds.handleRefundNotify(changed));
        changed = new HashMap<>(event); changed.put("verified_fact_hash","e".repeat(64));
        assertFalse(refunds.handleRefundNotify(changed));
        assertTrue(refunds.handleRefundNotify(event));
        assertEquals(1,count("refund_application"));
    }

    @Test void unknownDispatchKeepsOriginalNumberAndOnlyQueriesOriginalParent() {
        RefundApplication refund = applyAndApprove(second,6000);
        when(provider.submitRefund(eq(paymentRef),eq(ORDER),eq(refund.getRefundNo()),any(),any(),any()))
                .thenReturn(RefundRequestResult.UNKNOWN);
        payout.payoutAfterReview(refund.getId());
        assertEquals(RefundApplication.PAYOUT_DISPATCHING,refundRows.selectRefundApplicationById(refund.getId()).getPayoutStatus());
        assertThrows(RuntimeException.class,() -> apply(second,6000));
        db.update("UPDATE refund_application SET update_time=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE id=?",refund.getId());
        when(provider.queryRefundFacts(paymentRef,refund.getRefundNo())).thenReturn(Map.of("status","NOT_FOUND"));
        payout.payoutAfterReview(refund.getId());
        verify(provider,times(1)).submitRefund(eq(paymentRef),eq(ORDER),eq(refund.getRefundNo()),any(),any(),any());
        verify(provider).queryRefundFacts(paymentRef,refund.getRefundNo());
        assertEquals(1,count("refund_application"));
    }

    @Test void concurrentSameLineApplicationsHaveOneWinnerAndNoOverReservation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt = () -> { assertTrue(start.await(10,TimeUnit.SECONDS));
                try { apply(second,6000); return true; } catch (RuntimeException conflict) { return false; } };
            Future<Boolean> a=pool.submit(attempt), b=pool.submit(attempt); start.countDown();
            assertEquals(1,(a.get(30,TimeUnit.SECONDS)?1:0)+(b.get(30,TimeUnit.SECONDS)?1:0));
            assertEquals(1,count("refund_application"));
            assertEquals(6000,db.queryForObject("SELECT SUM(refund_amount)*100 FROM refund_application",Long.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentDifferentLineApprovalsShareParentBudgetWithoutCopyingItsCash() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RefundApplication> a=pool.submit(() -> { assertTrue(start.await(10,TimeUnit.SECONDS)); return applyAndApprove(first,4000); });
            Future<RefundApplication> b=pool.submit(() -> { assertTrue(start.await(10,TimeUnit.SECONDS)); return applyAndApprove(second,6000); });
            start.countDown(); assertNotEquals(a.get(30,TimeUnit.SECONDS).getId(),b.get(30,TimeUnit.SECONDS).getId());
            assertEquals(2,count("refund_application"));
            assertEquals(10000L,db.queryForObject("SELECT SUM(refund_amount)*100 FROM refund_application WHERE status=1",Long.class));
            assertEquals(0,registrations.selectCmsRegistrationById(second).getWechatPaymentAmount().signum());
            verifyNoInteractions(provider);
        } finally { pool.shutdownNow(); }
    }

    @Test void ordinaryReceiptSqlStillRejectsBothMarketingRowsAndWrongPaymentReplayHash() {
        tx.executeWithoutResult(s -> {
            assertEquals(0,ordinaryReceipts.insertConsumed(first,paymentRef.id(),paymentRef.hash()));
            assertEquals(0,ordinaryReceipts.insertConsumed(second,paymentRef.id(),paymentRef.hash()));
        });
        assertThrows(RuntimeException.class,() -> purchases.settleVerifiedCashPayment(ORDER,paymentRef.id(),"e".repeat(64)));
        assertFalse(purchases.handlePaidOrder(ORDER,true,10000,"OTHER",paymentRef));
        assertEquals(2,count("cms_registration")); assertEquals(0,count("cms_registration_payment_fact"));
    }

    @Test void giftClaimRecipientOldRunBlocksCashRefundBeforeAnyProviderSubmission() {
        GiftClaimRequest claim = new GiftClaimRequest(); claim.setToken(giftToken); claim.setRequestId("claim-9");
        giftService.claim(9L,claim);
        assertEquals(9L,db.queryForObject("SELECT claimed_member_id FROM gift_entitlement",Long.class));
        db.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,status,play_instance_id) VALUES(9,0,42,1,1,'older-run')");
        assertThrows(RuntimeException.class,() -> apply(second,6000));
        assertEquals(0,count("refund_application")); verifyNoInteractions(provider);
    }

    @Test void giftSingleLineRefundKeepsClaimAndOtherLineThenFinalRefundRevokesOriginalGift() {
        GiftClaimRequest claim = new GiftClaimRequest(); claim.setToken(giftToken); claim.setRequestId("claim-9");
        giftService.claim(9L,claim);
        RefundApplication a=applyAndApprove(second,6000); dispatch(a,6000);
        assertTrue(refunds.handleRefundNotify(success(a,6000)));
        assertEquals(1,db.queryForObject("SELECT status FROM gift_entitlement",Integer.class));
        assertEquals(2,registrations.selectCmsRegistrationById(first).getPaymentStatus());
        RefundApplication b=applyAndApprove(first,4000); dispatch(b,4000);
        assertTrue(refunds.handleRefundNotify(success(b,4000)));
        assertEquals(3,db.queryForObject("SELECT status FROM gift_entitlement",Integer.class));
        assertEquals(9L,db.queryForObject("SELECT claimed_member_id FROM gift_entitlement",Long.class));
        assertEquals(7L,refundRows.selectRefundApplicationById(b.getId()).getMemberId());
    }

    private RefundApplication apply(long id,long amount) {
        RefundApplication refund = new RefundApplication(); refund.setSourceType("registration"); refund.setSourceId(id);
        refund.setRefundAmount(money(amount)); refund.setPaidAmount(money(999999)); refund.setReason("fixture refund");
        assertEquals(1,refunds.saveBackOfficeRefund(refund)); return refund;
    }
    private void approve(RefundApplication refund) {
        RefundReviewDTO review = new RefundReviewDTO(); review.setId(refund.getId()); review.setStatus(1);
        assertEquals(1,refunds.reviewRefund(review,99L));
    }
    private RefundApplication applyAndApprove(long id,long amount) { var refund=apply(id,amount); approve(refund); return refund; }
    private void dispatch(RefundApplication refund,long amount) {
        when(provider.submitRefund(eq(paymentRef),eq(ORDER),eq(refund.getRefundNo()),eq(money(amount)),eq(money(10000)),any()))
                .thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return RefundRequestResult.ACCEPTED; });
        payout.payoutAfterReview(refund.getId());
        verify(provider).submitRefund(paymentRef,ORDER,refund.getRefundNo(),money(amount),money(10000),refund.getReason());
    }
    private Map<String,String> success(RefundApplication refund,long amount) {
        var fact = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant","appid",ORDER,
                "WX-CASH-10",refund.getRefundNo(),"WR"+refund.getId(),"TX10","JSAPI","CNY",10000,amount,
                Instant.parse("2026-11-09T00:01:00Z"),"c".repeat(64),"REFUND");
        var reference=facts.recordVerified(fact);
        return Map.ofEntries(Map.entry("status","SUCCESS"),Map.entry("outRefundNo",refund.getRefundNo()),
                Map.entry("refundId",fact.providerId()),Map.entry("transactionId","TX10"),Map.entry("outTradeNo",ORDER),
                Map.entry("wxOutTradeNo","WX-CASH-10"),Map.entry("mchid","merchant"),Map.entry("appid","appid"),
                Map.entry("tradeType","JSAPI"),Map.entry("currency","CNY"),Map.entry("refund",Long.toString(amount)),
                Map.entry("total","10000"),Map.entry("verified_fact_id",Long.toString(reference.id())),
                Map.entry("verified_fact_hash",reference.hash()));
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private static BigDecimal money(long fen) { return BigDecimal.valueOf(fen,2); }
    private static void set(Object target,String name,Object value) { ReflectionTestUtils.setField(target,name,value); }
    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource())); return (T)proxy.getProxy();
    }
}
