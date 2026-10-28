package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL/Spring transactions and current repository DDL; WeChat and non-cash after-effects are isolated. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundRetryAndRemainderMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("refund_retry_remainder_it");
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private boolean schemaCreated;
    private IRefundService service;
    private RefundServiceImpl refundTarget;
    private DataSourceTransactionManager transactions;
    private IRefundPayoutService payouts;
    private RefundApplicationMapper refunds;
    private IWechatPayService wechat;
    private SettlementDetailMapper settlements;

    @BeforeAll
    void setup() throws Exception {
        admin = new JdbcTemplate(source(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        schemaCreated = true;
        DriverManagerDataSource dataSource = source(mysql.databaseUrl());
        db = new JdbcTemplate(dataSource);
        Path root = Files.exists(Paths.get("sql/base_schema_20260818.sql")) ? Paths.get("") : Paths.get("..");
        String snapshot = new String(Files.readAllBytes(root.resolve("sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[] {"cms_registration", "cms_topic", "cms_activity", "refund_application", "wx_payment_callback_inbox"}) {
            String start = "CREATE TABLE `" + table + "` (";
            int from = snapshot.indexOf(start);
            int closing = snapshot.indexOf("\n) ENGINE", from);
            int to = snapshot.indexOf(';', closing);
            assertTrue(from >= 0 && closing > from && to > closing, "missing source DDL: " + table);
            db.execute(snapshot.substring(from, to + 1));
        }
        new ResourceDatabasePopulator(
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20260930_registration_first_successful_scan.sql").toFile()),
                new FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_merchant_aftercare_timeout_20260917.sql").toFile())).execute(dataSource);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsRegistrationMapper.xml"),
                new ClassPathResource("mapper/business/RefundApplicationMapper.xml"),
                new ClassPathResource("mapper/business/WxPaymentCallbackInboxMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        refunds = sql.getMapper(RefundApplicationMapper.class);
        settlements = mock(SettlementDetailMapper.class);
        RefundServiceImpl target = new RefundServiceImpl();
        refundTarget = target;
        ReflectionTestUtils.setField(target, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "settlementDetailMapper", settlements);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(target, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(target, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(target, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(target, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        WxPaymentCallbackInboxServiceImpl inbox = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(inbox, "mapper", sql.getMapper(WxPaymentCallbackInboxMapper.class));
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService", inbox);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IRefundService.class);
        transactions = new DataSourceTransactionManager(dataSource);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (IRefundService) proxy.getProxy();
        wechat = mock(IWechatPayService.class);
        RefundPayoutServiceImpl payout = new RefundPayoutServiceImpl();
        ReflectionTestUtils.setField(payout, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(payout, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(payout, "refundService", service);
        ReflectionTestUtils.setField(payout, "wechatPayService", wechat);
        payouts = payout;
    }

    @BeforeEach
    void clear() {
        db.update("delete from wx_payment_callback_inbox");
        db.update("delete from refund_application");
        db.update("delete from cms_registration");
        reset(wechat, settlements);
        when(settlements.selectSettledDetails(anyString(), anyLong())).thenReturn(Collections.emptyList());
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), any())).thenReturn(RefundRequestResult.ACCEPTED);
    }

    @AfterAll
    void cleanup() {
        if (admin != null && schemaCreated) {
            admin.execute("drop database if exists " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
        }
    }

    @Test
    void zeroInsertReceiptRollsBackRealRefundRowAndOuterTransaction() {
        assertLostInsertReceiptRollsBack(false);
    }

    @Test
    void missingGeneratedKeyRollsBackRealRefundRowAndOuterTransaction() {
        assertLostInsertReceiptRollsBack(true);
    }

    /** Delegate the INSERT to real MyBatis/MySQL, then lose only its caller-visible receipt. */
    private void assertLostInsertReceiptRollsBack(boolean loseKey) {
        paid(99L, null, "68.00");
        RefundApplicationMapper lostReceipt = (RefundApplicationMapper) java.lang.reflect.Proxy.newProxyInstance(
                RefundApplicationMapper.class.getClassLoader(), new Class<?>[] {RefundApplicationMapper.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(refunds, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if ("insertRefundApplication".equals(method.getName())) {
                        assertEquals(1, result);
                        RefundApplication inserted = (RefundApplication) args[0];
                        assertNotNull(inserted.getId(), "real useGeneratedKeys must execute before fault injection");
                        assertTrue(inserted.getId() > 0);
                        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
                        if (loseKey) inserted.setId(null);
                        else return 0;
                    }
                    return result;
                });
        ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", lostReceipt);
        java.util.concurrent.atomic.AtomicBoolean downstreamReached = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                        // A preceding cancellation write must roll back together with the actual refund INSERT.
                        assertEquals(1, db.update("update cms_registration set registration_status=3 where id=99"));
                        RefundApplication request = request("20.00");
                        service.saveBackOfficeRefund(request);
                        downstreamReached.set(true);
                        service.reviewRefund(review(request.getId(), 1), 7L);
                        return null;
                    }));
            assertTrue(failure.getMessage().contains("写入回执异常"));
        } finally {
            ReflectionTestUtils.setField(refundTarget, "refundApplicationMapper", refunds);
        }
        assertFalse(downstreamReached.get(), "approval and dispatch must not follow an invalid receipt");
        assertEquals(0, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(2, db.queryForObject("select registration_status from cms_registration where id=99", Integer.class));
        assertEquals(2, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        verify(settlements, never()).voidPendingBySource(anyString(), anyLong());
        verify(wechat, never()).submitRefund(anyString(), anyString(), any(), any(), any());
        // Retrying only after the failed transaction rolled back creates one valid original intent.
        RefundApplication retry = save("20.00");
        assertTrue(retry.getId() > 0);
        assertEquals("RF99", retry.getRefundNo());
        assertEquals("registration:99", retry.getIdempotencyKey());
        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(0, refunds.selectRefundApplicationById(retry.getId()).getStatus());
        assertEquals(RefundApplication.PAYOUT_DISPATCH_PENDING,
                refunds.selectRefundApplicationById(retry.getId()).getPayoutStatus());
    }

    @Test
    void lockedHistoryKeepsApprovedRefundFactAfterProviderFailureWithoutChangingFunds() {
        paid(99L, null, "68.00");
        RefundApplication request = save("20.00");
        assertTrue(lockedFacts(99L).hasPendingOrApproved());
        assertEquals(0, lockedFacts(99L).refunds().get(0).reviewStatus());
        assertEquals(1, service.reviewRefund(review(request.getId(), 1), 7L));
        when(wechat.submitRefund(anyString(), anyString(), any(), any(), any()))
                .thenReturn(RefundRequestResult.DEFINITIVE_FAILED);
        payouts.payoutAfterReview(request.getId());
        for (int repeat = 0; repeat < 2; repeat++) {
            var facts = lockedFacts(99L);
            assertTrue(facts.hasPendingOrApproved());
            assertFalse(facts.unknownHistory());
            assertEquals(1, facts.refunds().get(0).reviewStatus());
            assertEquals(RefundApplication.PAYOUT_PENDING_MANUAL, facts.refunds().get(0).payoutStatus());
            assertEquals(new BigDecimal("20.00"), facts.refunds().get(0).refundAmount());
        }
        assertEquals(1, db.queryForObject("select count(*) from refund_application", Integer.class));
        assertEquals(4, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        verify(wechat, times(1)).submitRefund(eq("REG99"), eq(request.getRefundNo()),
                eq(new BigDecimal("20.00")), eq(new BigDecimal("68.00")), any());
    }

    private com.chengyinhub.business.service.support.RegistrationRefundFacts lockedFacts(Long registrationId) {
        return new org.springframework.transaction.support.TransactionTemplate(
                new DataSourceTransactionManager(db.getDataSource())).execute(status -> {
            assertEquals(registrationId, db.queryForObject(
                    "select id from cms_registration where id=? for update", Long.class, registrationId));
            return com.chengyinhub.business.service.support.RegistrationRefundFacts.fromHistory(
                    registrationId, service.selectRegistrationHistoryForUpdate(registrationId));
        });
    }

    @Test
    void forgedRefundIdentityIsPersistedAsOriginalBuyerAndScopeThroughPayoutReadback() {
        paid(99L, null, "68.00");
        RefundApplication request = request("20.00");
        request.setMemberId(72L);
        request.setOwnerType(3L);
        request.setOwnerId(9007199254740993L);
        request.setMerchantId(999L);
        assertEquals(1, service.saveBackOfficeRefund(request));
        RefundApplication persisted = refunds.selectRefundApplicationById(request.getId());
        assertEquals(9L, persisted.getMemberId());
        assertEquals(2L, persisted.getOwnerType());
        assertEquals(20L, persisted.getOwnerId());
        assertNull(persisted.getMerchantId(), "missing authoritative merchant is not caller-provided merchant");
        RefundApplication buyer = new RefundApplication();
        buyer.setSourceType("registration");
        buyer.setSourceId(99L);
        buyer.setMemberId(9L);
        assertEquals(1, service.selectRefundList(buyer).size());
        buyer.setMemberId(72L);
        assertTrue(service.selectRefundList(buyer).isEmpty());
        assertEquals(1, service.reviewRefund(review(request.getId(), 1), 7L));
        payouts.payoutAfterReview(request.getId());
        verify(wechat).submitRefund(eq("REG99"), eq(request.getRefundNo()),
                eq(new BigDecimal("20.00")), eq(new BigDecimal("68.00")), any());
        assertTrue(service.handleRefundNotify(facts(request)));
        assertEquals(RefundApplication.PAYOUT_SUCCESS,
                refunds.selectRefundApplicationById(request.getId()).getPayoutStatus());
        assertEquals(9L, refunds.selectRefundApplicationById(request.getId()).getMemberId());
    }

    @Test
    void rejectionThenTwentyAndFortyEightUseDistinctRefundNumbersAndCallbacks() {
        paid(99L, null, "68.00");
        RefundApplication rejected = save("20.00");
        assertEquals(1, service.reviewRefund(review(rejected.getId(), 2), 7L));
        RefundApplication first = save("20.00");
        assertEquals("RF99-" + rejected.getId(), first.getRefundNo());
        assertEquals(1, service.reviewRefund(review(first.getId(), 1), 7L));
        payouts.payoutAfterReview(first.getId());
        RefundApplication second = save("48.00");
        assertEquals("RF99-" + first.getId(), second.getRefundNo());
        assertNotEquals(first.getRefundNo(), second.getRefundNo());
        assertEquals(1, service.reviewRefund(review(second.getId(), 1), 8L));
        payouts.payoutAfterReview(second.getId());
        verify(wechat).submitRefund(eq("REG99"), eq(first.getRefundNo()), eq(new BigDecimal("20.00")), eq(new BigDecimal("68.00")), any());
        verify(wechat).submitRefund(eq("REG99"), eq(second.getRefundNo()), eq(new BigDecimal("48.00")), eq(new BigDecimal("68.00")), any());
        assertTrue(service.handleRefundNotify(facts(first)));
        assertEquals(RefundApplication.PAYOUT_SUCCESS, refunds.selectRefundApplicationById(first.getId()).getPayoutStatus());
        assertEquals(RefundApplication.PAYOUT_PROCESSING, refunds.selectRefundApplicationById(second.getId()).getPayoutStatus());
        assertTrue(service.handleRefundNotify(facts(second)));
        assertTrue(service.handleRefundNotify(facts(first)));
        assertEquals(2, db.queryForObject("select count(distinct biz_id) from wx_payment_callback_inbox where biz_type=2", Integer.class));
        assertEquals(new BigDecimal("68.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
        assertEquals(0, service.reviewRefund(review(first.getId(), 1), 7L));
        assertEquals(0, service.reviewRefund(review(second.getId(), 1), 8L));
        assertThrows(RuntimeException.class, () -> save("0.01"));
        assertEquals(3, db.queryForObject("select count(*) from refund_application", Integer.class));
    }

    /** F-L4-02:玩家/后台待审核申请不能挡住主办方取消的全额退款;取代后新退款单真实落库、可批准,且旧单为驳回。 */
    @Test
    void pendingApplicationIsSupersededBySystemFullRefundInsteadOfBlockingCancellation() {
        paid(99L, null, "68.00");
        RefundApplication pending = save("20.00");
        assertThrows(RuntimeException.class, () -> service.saveRefund(request("68.00")));
        assertEquals(1, service.supersedePendingRegistrationRefunds(99L, "主办方取消活动,全额退款"));
        RefundApplication full = request("68.00");
        assertEquals(1, service.saveRefund(full));
        assertEquals("RF99-" + pending.getId(), full.getRefundNo());
        assertEquals(1, service.reviewRefund(review(full.getId(), 1), 0L));
        assertEquals(Integer.valueOf(2), refunds.selectRefundApplicationById(pending.getId()).getStatus());
        assertEquals(Integer.valueOf(1), refunds.selectRefundApplicationById(full.getId()).getStatus());
        assertEquals(0, service.supersedePendingRegistrationRefunds(99L, "again"));
        assertEquals(new BigDecimal("68.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
    }

    @Test
    void twoLegacyPendingEightyRefundsOnOneParentCanApproveOnlyOne() throws Exception {
        paid(99L, "P99", "100.00");
        paid(100L, "P99", "0.00");
        RefundApplication a = legacyPending("legacy-a");
        RefundApplication b = legacyPending("legacy-b");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = workers.submit(() -> approveAfter(start, a.getId(), 7L));
            Future<Boolean> second = workers.submit(() -> approveAfter(start, b.getId(), 8L));
            start.countDown();
            int approved = (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, approved);
            assertEquals(new BigDecimal("80.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
            assertEquals(1, db.queryForObject("select count(*) from refund_application where status=0", Integer.class));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void parentRemainingCashSubtractsBothPayerAndSiblingApprovedHistory() {
        paid(99L, "P99", "100.00");
        paid(100L, "P99", "0.00");
        for (long id : new long[] {99L, 100L}) {
            db.update("update cms_registration set payment_status=4, registration_status=3 where id=?", id);
            RefundApplication history = request(id == 99L ? "40.00" : "30.00");
            history.setSourceId(id); history.setStatus(1); history.setDelFlag(0);
            history.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
            history.setIdempotencyKey("historical-approved-" + id); history.setRefundNo("RF" + id);
            assertEquals(1, refunds.insertRefundApplication(history));
        }
        assertThrows(ServiceException.class, () -> save("30.01"));
        RefundApplication remainder = save("30.00");
        assertEquals(1, service.reviewRefund(review(remainder.getId(), 1), 7L));
        assertEquals(new BigDecimal("100.00"), db.queryForObject("select sum(refund_amount) from refund_application where status=1", BigDecimal.class));
    }

    @Test
    void failedApprovalRollsBackApplicationAndRegistrationTogether() {
        paid(99L, null, "68.00");
        RefundApplication request = save("20.00");
        doThrow(new IllegalStateException("fixture after-write failure")).when(settlements).voidPendingBySource("registration", 99L);
        assertThrows(IllegalStateException.class, () -> service.reviewRefund(review(request.getId(), 1), 7L));
        assertEquals(0, refunds.selectRefundApplicationById(request.getId()).getStatus());
        assertEquals(2, db.queryForObject("select payment_status from cms_registration where id=99", Integer.class));
        assertEquals(2, db.queryForObject("select registration_status from cms_registration where id=99", Integer.class));
    }

    private static Map<String, String> facts(RefundApplication refund) {
        return Map.of("outRefundNo", refund.getRefundNo(), "outTradeNo", "REG99", "status", "SUCCESS",
                "refund", Long.toString(refund.getRefundAmount().movePointRight(2).longValueExact()),
                "total", "6800", "currency", "CNY");
    }

    private boolean approveAfter(CountDownLatch start, Long id, Long operator) throws InterruptedException {
        start.await();
        try { return service.reviewRefund(review(id, 1), operator) == 1; }
        catch (ServiceException | IllegalArgumentException | IllegalStateException expectedBusinessRejection) { return false; }
    }

    private RefundApplication legacyPending(String key) {
        RefundApplication request = request("80.00");
        request.setStatus(0); request.setDelFlag(0); request.setPaidAmount(new BigDecimal("100.00"));
        request.setPayoutStatus(RefundApplication.PAYOUT_DISPATCH_PENDING);
        request.setIdempotencyKey(key); request.setRefundNo("RF99-" + (key.endsWith("a") ? "9001" : "9002"));
        assertEquals(1, refunds.insertRefundApplication(request));
        return request;
    }

    private RefundApplication save(String amount) {
        RefundApplication request = request(amount);
        assertEquals(1, service.saveBackOfficeRefund(request));
        assertNotNull(request.getId());
        return request;
    }

    private RefundApplication request(String amount) {
        RefundApplication request = new RefundApplication();
        request.setSourceType("registration"); request.setSourceId(99L);
        request.setRefundAmount(new BigDecimal(amount)); request.setReason("fixture refund");
        return request;
    }

    private RefundReviewDTO review(Long id, int status) {
        RefundReviewDTO review = new RefundReviewDTO();
        review.setId(id); review.setStatus(status); review.setReviewReason("fixture review");
        return review;
    }

    private void paid(Long id, String parent, String cash) {
        db.update("insert into cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status,pay_expire_time) values(?,?,?,2,20,1,?,9,?,?,?, ?,2,2,0,date_add(now(),interval 1 day))", id, "REG" + id, parent, cash, cash, cash, cash, cash);
    }

    private DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url, mysql.user(), mysql.password());
    }
}
