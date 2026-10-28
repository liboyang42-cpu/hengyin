package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantSubscription;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCommerceServiceTest {
    private MerchantSubscriptionMapper subscriptionMapper;
    private OmsOrderMapper orderMapper;
    private ISettlementRuleService settlementRuleService;
    private IWechatPayService wechatPayService;
    private IUserPermissionService permissionService;
    private MerchantCommerceService service;
    private VerifiedPaymentProofFixtures proof;
    private final TestPaymentTransactions transactions = new TestPaymentTransactions();

    @BeforeEach
    void setUp() {
        subscriptionMapper = mock(MerchantSubscriptionMapper.class);
        orderMapper = mock(OmsOrderMapper.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        wechatPayService = mock(IWechatPayService.class);
        when(wechatPayService.lockClosablePaymentAttemptForUpdate(anyString(), anyString())).thenReturn(true);
        when(wechatPayService.lockPaymentAttemptForUpdate(anyString(), anyString())).thenReturn(true);
        permissionService = mock(IUserPermissionService.class);
        service = new MerchantCommerceService(false);
        proof=new VerifiedPaymentProofFixtures();
        ReflectionTestUtils.setField(service,"verifiedPaymentFacts",proof.reader);
        ReflectionTestUtils.setField(service, "subscriptionMapper", subscriptionMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(service, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "paymentTransactions", transactions);
        when(orderMapper.selectCommerceOrderForUpdate(anyString())).thenAnswer(call -> orderMapper.selectOmsOrderByOrderSn(call.getArgument(0)));
        when(orderMapper.bindCommercePaymentProof(any(),anyString(),any(),anyString())).thenAnswer(call -> {
            OmsOrder order=call.getArgument(0);order.setStatus(1L);order.setPaymentTransactionId(call.getArgument(1));
            order.setVerifiedPaymentFactId(call.getArgument(2));order.setVerifiedPaymentFactHash(call.getArgument(3));return 1;
        });
    }

    @Test
    void selfCheckoutDisabled_rejectsBeforeOrderOrWechat() {
        SettlementRule configuredRule = new SettlementRule();
        configuredRule.setUnitPrice(new BigDecimal("199.00"));
        configuredRule.setEntitlementMaxUsage(3);
        configuredRule.setEntitlementDays(30);
        when(settlementRuleService.getActiveRule(0L, MerchantCommerceService.BIZ_PREMIUM_TEMPLATE))
                .thenReturn(configuredRule);
        when(orderMapper.insertOmsOrder(any())).thenReturn(1);
        when(wechatPayService.createGenericOrder(any(), any(), any(), any())).thenReturn(Collections.emptyMap());

        assertThrows(IllegalStateException.class,
                () -> service.createOrder(12L, 34L, "openid", MerchantCommerceService.BIZ_PREMIUM_TEMPLATE,
                        "req-1234567890123456"));
        verify(orderMapper, never()).insertOmsOrder(any());
        verify(wechatPayService, never()).createGenericOrder(any(), any(), any(), any());
    }

    @Test
    void cityNodeQuota_addsActiveSubscriptionToRoleBase() {
        UserPermission permission = new UserPermission();
        permission.setMaxCityNodes(3);
        when(permissionService.getByRole("merchant")).thenReturn(permission);
        MerchantSubscription subscription = new MerchantSubscription();
        subscription.setMaxUsage(2);
        subscription.setUsedCount(1);
        when(subscriptionMapper.selectMerchantSubscriptionList(any()))
                .thenReturn(Collections.singletonList(subscription));

        MerchantCommerceService.Entitlement quota = service.entitlement(12L, "merchant",
                MerchantCommerceService.ENTITLEMENT_CITY_NODE, 3);

        assertEquals(5, quota.getLimit());
        assertEquals(3, quota.getUsed());
        assertEquals(2, quota.getRemaining());
    }

    @Test
    void paidCommerceCallback_isIdempotentAndNeverPaysOut() {
        OmsOrder pending = new OmsOrder();
        pending.setId(8L);
        pending.setOrderSn("TPLTM_8");
        pending.setMemberId(12L); pending.setMmsMerchantId(34L);
        pending.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        pending.setStatus(0L);
        pending.setPayAmount(new BigDecimal("19.90"));
        // One persisted business identity: bindCommercePaymentProof updates this same row before readback.
        // A sequential "paid" shell loses ID/scope/amount/Reference and cannot prove exact consumption.
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_8")).thenReturn(pending);
        var originalProof = proof.issue("TPLTM_8", 1990, "wx-tx-8");
        // B's issued/open fixture is the explicit current, not-CLOSED unit port, not a provider/SQL proof.
        org.mockito.Mockito.doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return proof.reader.requireVerified(originalProof);
        }).when(proof.reader).requireConsumablePayment(originalProof);
        org.mockito.Mockito.doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1L, pending.getStatus());
            assertEquals("wx-tx-8", pending.getPaymentTransactionId());
            assertEquals(Long.valueOf(originalProof.id()), pending.getVerifiedPaymentFactId());
            assertEquals(originalProof.hash(), pending.getVerifiedPaymentFactHash());
            return null;
        }).when(proof.reader).completePaymentRecovery(originalProof, 8L);
        when(orderMapper.markCommerceOrderPaid(8L, "wx-tx-8")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(1);

        assertTrue(paidOrder("TPLTM_8", true, 1990, "wx-tx-8"));
        verify(subscriptionMapper).grantMerchantSubscription(any());

        when(orderMapper.markCommerceOrderPaid(8L, "wx-tx-8")).thenReturn(0);
        assertTrue(paidOrder("TPLTM_8", true, 1990, "wx-tx-8"));
        verify(subscriptionMapper).grantMerchantSubscription(any());
        verify(orderMapper).markCommerceOrderPaid(8L, "wx-tx-8");
        verify(orderMapper).bindCommercePaymentProof(pending, "wx-tx-8", originalProof.id(), originalProof.hash());
        org.mockito.Mockito.verify(proof.reader, org.mockito.Mockito.times(2)).completePaymentRecovery(originalProof, 8L);
        org.mockito.Mockito.verifyNoInteractions(wechatPayService);
    }

    @Test
    void renewalAcceptsMysqlUpdatedRowCountWithoutRetryingGrant() {
        OmsOrder pending = new OmsOrder(); pending.setId(8L); pending.setOrderSn("TPLTM_8");
        pending.setMemberId(12L); pending.setMmsMerchantId(34L); pending.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        pending.setStatus(0L); pending.setPayAmount(new BigDecimal("19.90"));
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_8")).thenReturn(pending);
        when(orderMapper.markCommerceOrderPaid(8L, "wx-tx-8")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(2);
        assertTrue(paidOrder("TPLTM_8", true, 1990, "wx-tx-8"));
        verify(subscriptionMapper).grantMerchantSubscription(any());
    }

    @Test
    void paidCommerceCallback_rejectsAmountMismatchBeforePaidCas() {
        OmsOrder pending = new OmsOrder();
        pending.setId(8L);
        pending.setOrderSn("PROMOT_8");
        pending.setMemberId(12L);pending.setMmsMerchantId(34L);
        pending.setBizType(MerchantCommerceService.BIZ_PROMOTION_SLOT);
        pending.setStatus(0L);
        pending.setPayAmount(new BigDecimal("19.90"));
        when(orderMapper.selectOmsOrderByOrderSn("PROMOT_8")).thenReturn(pending);

        assertFalse(paidOrder("PROMOT_8", true, 1989, "wx-tx-8"));
        verify(orderMapper, never()).markCommerceOrderPaid(any(), any());
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
    }

    @Test
    void paidCommerceCallback_grantFailureThrowsForTransactionRollback() {
        OmsOrder pending = new OmsOrder();
        pending.setId(9L);
        pending.setOrderSn("CUSTOM_9");
        pending.setMemberId(12L);pending.setMmsMerchantId(34L);
        pending.setBizType(MerchantCommerceService.BIZ_CUSTOM_EVENT);
        pending.setStatus(0L);
        pending.setPayAmount(new BigDecimal("88.00"));
        when(orderMapper.selectOmsOrderByOrderSn("CUSTOM_9")).thenReturn(pending);
        when(orderMapper.markCommerceOrderPaid(9L, "wx-tx-9")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> paidOrder("CUSTOM_9", true, 8800, "wx-tx-9"));
    }

    @Test
    void enabledCheckoutRequiresConfiguredPrice() {
        service = new MerchantCommerceService(true);
        ReflectionTestUtils.setField(service, "subscriptionMapper", subscriptionMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(service, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "paymentTransactions", transactions);
        SettlementRule rule = new SettlementRule();
        rule.setUnitPrice(null);
        when(settlementRuleService.getActiveRule(0L, MerchantCommerceService.BIZ_PREMIUM_TEMPLATE)).thenReturn(rule);

        assertThrows(IllegalStateException.class,
                () -> service.createOrder(12L, 34L, "openid", MerchantCommerceService.BIZ_PREMIUM_TEMPLATE,
                        "req-1234567890123456"));
        verify(orderMapper, never()).insertOmsOrder(any());
    }

    @Test
    void createOrderStoresRequestIdAndReturnsStableOrderReference() {
        service = enabledService();
        SettlementRule rule = pricedRule();
        when(settlementRuleService.getActiveRule(0L, MerchantCommerceService.BIZ_PREMIUM_TEMPLATE)).thenReturn(rule);
        when(orderMapper.selectCommerceOrderByRequest(12L, 34L,
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456")).thenReturn(null);
        when(orderMapper.insertOmsOrder(any())).thenAnswer(invocation -> {
            OmsOrder order = invocation.getArgument(0);
            order.setId(99L);
            return 1;
        });
        when(wechatPayService.createGenericOrder(any(), any(), any(), eq("openid")))
                .thenReturn(new java.util.LinkedHashMap<>());

        java.util.Map<String, String> result = service.createOrder(12L, 34L, "openid",
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456");

        org.mockito.ArgumentCaptor<OmsOrder> captor = org.mockito.ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderMapper).insertOmsOrder(captor.capture());
        assertEquals("req-1234567890123456", captor.getValue().getCommerceRequestId());
        assertNotNull(result.get("orderSn"));
        assertEquals("pending", result.get("paymentStatus"));
    }

    @Test
    void duplicateRequestReplaysExistingOrderWithoutSecondInsert() {
        service = enabledService();
        OmsOrder existing = new OmsOrder();
        existing.setId(8L);
        existing.setOrderSn("TPLTM_existing");
        existing.setMemberId(12L);
        existing.setMmsMerchantId(34L);
        existing.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        existing.setPayAmount(new BigDecimal("19.90"));
        existing.setStatus(0L);
        existing.setCommerceRequestId("req-1234567890123456");
        when(orderMapper.selectCommerceOrderByRequest(12L, 34L,
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456")).thenReturn(existing);
        when(wechatPayService.createGenericOrder("TPLTM_existing", new BigDecimal("19.90"),
                "城瘾高级模板权益", "openid")).thenReturn(new java.util.LinkedHashMap<>());

        java.util.Map<String, String> result = service.createOrder(12L, 34L, "openid",
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456");

        verify(orderMapper, never()).insertOmsOrder(any());
        verify(orderMapper, atLeastOnce()).selectCommerceOrderByRequest(12L, 34L,
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456");
        assertEquals("TPLTM_existing", result.get("orderSn"));
        assertEquals("pending", result.get("paymentStatus"));
    }

    @Test
    void orderStatusRejectsCrossAccountRead() {
        service = enabledService();
        OmsOrder order = new OmsOrder();
        order.setOrderSn("TPLTM_owned");
        order.setMemberId(99L);
        order.setMmsMerchantId(34L);
        order.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_owned")).thenReturn(order);

        assertThrows(IllegalArgumentException.class,
                () -> service.orderStatus(12L, 34L, "TPLTM_owned"));
    }

    @Test
    void missedPaidCallbackIsRecoveredFromWechatAndUnknownResultIsNeverClosed() {
        OmsOrder order = new OmsOrder();
        order.setId(8L); order.setOrderSn("TPLTM_8"); order.setStatus(0L); order.setMemberId(12L); order.setMmsMerchantId(34L);
        order.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        order.setPayAmount(new BigDecimal("19.90")); order.setEntitlementDays(30); order.setEntitlementMaxUsage(3);
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_8")).thenReturn(order);
        java.util.Map<String, String> observed = new java.util.HashMap<>();
        observed.put("trade_state", "SUCCESS"); observed.put("out_trade_no", "TPLTM_8");
        observed.put("currency", "CNY"); observed.put("total", "1990"); observed.put("transaction_id", "wx-tx-8");
        var originalProof=proof.issue("TPLTM_8",1990,"wx-tx-8");
        observed.put("verified_fact_id",Long.toString(originalProof.id()));observed.put("verified_fact_hash",originalProof.hash());
        when(wechatPayService.queryOrderStatus("TPLTM_8")).thenReturn(observed);
        when(orderMapper.markCommerceOrderPaid(8L, "wx-tx-8")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(1);
        service.reconcileOrder("TPLTM_8");
        verify(subscriptionMapper).grantMerchantSubscription(any());
        OmsOrder otherPending=new OmsOrder();otherPending.setId(9L);otherPending.setOrderSn("TPLTM_9");
        otherPending.setStatus(0L);otherPending.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_9")).thenReturn(otherPending);
        java.util.Map<String,String> unknown=new java.util.HashMap<>();unknown.put("trade_state","UNKNOWN");unknown.put("out_trade_no","TPLTM_9");
        when(wechatPayService.queryOrderStatus("TPLTM_9")).thenReturn(unknown);
        assertThrows(IllegalStateException.class, () -> service.reconcileOrder("TPLTM_9"));
        verify(orderMapper, never()).markCommerceOrderClosed(any());
        verify(wechatPayService, never()).closePaymentAttempt(any(), any());
    }

    @Test
    void queriedPaymentRejectsWrongCurrencyOrOutTradeNoBeforeGrant() {
        OmsOrder order = new OmsOrder();
        order.setOrderSn("CUSTOM_9"); order.setStatus(0L); order.setBizType(MerchantCommerceService.BIZ_CUSTOM_EVENT);
        when(orderMapper.selectOmsOrderByOrderSn("CUSTOM_9")).thenReturn(order);
        java.util.Map<String, String> observed = new java.util.HashMap<>();
        observed.put("trade_state", "SUCCESS"); observed.put("out_trade_no", "CUSTOM_9");
        observed.put("currency", "USD"); observed.put("total", "8800"); observed.put("transaction_id", "wx-tx");
        when(wechatPayService.queryOrderStatus("CUSTOM_9")).thenReturn(observed);
        assertThrows(IllegalStateException.class, () -> service.reconcileOrder("CUSTOM_9"));
        observed.put("currency", "CNY"); observed.put("out_trade_no", "CUSTOM_other");
        assertThrows(IllegalStateException.class, () -> service.reconcileOrder("CUSTOM_9"));
        verify(orderMapper, never()).markCommerceOrderPaid(any(), any());
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
    }

    @Test
    void oldClosedObservationCannotCloseNewCurrentAttempt() {
        OmsOrder order = new OmsOrder();
        order.setId(18L); order.setOrderSn("PROMOT_18"); order.setStatus(0L);
        order.setBizType(MerchantCommerceService.BIZ_PROMOTION_SLOT);
        when(orderMapper.selectOmsOrderByOrderSn("PROMOT_18")).thenReturn(order);
        when(wechatPayService.queryOrderStatus("PROMOT_18")).thenReturn(java.util.Map.of(
                "out_trade_no", "PROMOT_18", "wx_out_trade_no", "old-wx-attempt", "trade_state", "CLOSED"));
        when(wechatPayService.lockClosablePaymentAttemptForUpdate("PROMOT_18", "old-wx-attempt")).thenReturn(false);
        service.reconcileOrder("PROMOT_18");
        verify(wechatPayService).lockClosablePaymentAttemptForUpdate("PROMOT_18", "old-wx-attempt");
        verify(orderMapper, never()).markCommerceOrderClosed(any());
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
    }

    @Test
    void abandonedUnpaidOrderIsClosedAtWechatBeforeLocalCloseButFreshOneStaysPending() {
        OmsOrder order = new OmsOrder();
        order.setId(18L); order.setOrderSn("PROMOT_18"); order.setStatus(0L);
        order.setBizType(MerchantCommerceService.BIZ_PROMOTION_SLOT);
        order.setCreateTime(new java.util.Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L));
        when(orderMapper.selectOmsOrderByOrderSn("PROMOT_18")).thenReturn(order);
        java.util.Map<String, String> observed = new java.util.HashMap<>();
        observed.put("trade_state", "NOTPAY"); observed.put("out_trade_no", "PROMOT_18");
        observed.put("wx_out_trade_no", "PROMOT_18");
        when(wechatPayService.queryOrderStatus("PROMOT_18")).thenReturn(observed);
        service.reconcileOrder("PROMOT_18");
        verify(wechatPayService, never()).closePaymentAttempt(any(), any());
        verify(orderMapper, never()).markCommerceOrderClosed(any());

        order.setCreateTime(new java.util.Date(System.currentTimeMillis() - 25 * 60 * 60 * 1000L));
        org.mockito.Mockito.doThrow(new RuntimeException("ORDERPAID")).when(wechatPayService).closePaymentAttempt("PROMOT_18", "PROMOT_18");
        assertThrows(RuntimeException.class, () -> service.reconcileOrder("PROMOT_18"));
        verify(orderMapper, never()).markCommerceOrderClosed(any());

        org.mockito.Mockito.reset(wechatPayService);
        when(wechatPayService.lockClosablePaymentAttemptForUpdate(anyString(), anyString())).thenReturn(true);
        when(wechatPayService.queryOrderStatus("PROMOT_18")).thenReturn(observed);
        when(wechatPayService.closePaymentAttempt("PROMOT_18", "PROMOT_18")).thenReturn(true);
        service.reconcileOrder("PROMOT_18");
        verify(wechatPayService).closePaymentAttempt("PROMOT_18", "PROMOT_18");
        verify(orderMapper).markCommerceOrderClosed(18L);
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
    }

    @Test
    void wechatMissingOrderStaysPendingPastAbandonWindowAndRenewalGrantCountsAsSuccess() {
        OmsOrder order = new OmsOrder();
        order.setId(19L); order.setOrderSn("CUSTOM_19"); order.setStatus(0L);
        order.setBizType(MerchantCommerceService.BIZ_CUSTOM_EVENT);
        order.setCreateTime(new java.util.Date(System.currentTimeMillis() - 10 * 60 * 1000L));
        when(orderMapper.selectOmsOrderByOrderSn("CUSTOM_19")).thenReturn(order);
        when(wechatPayService.queryOrderStatus("CUSTOM_19"))
                .thenThrow(new com.chengyinhub.business.service.WechatOrderNotExistException("missing", null));
        service.reconcileOrder("CUSTOM_19");   // NOT_FOUND 无法证明原号未受理，保持待支付供查证。
        verify(orderMapper, never()).markCommerceOrderClosed(any());
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
        order.setCreateTime(new java.util.Date(System.currentTimeMillis() - 25 * 60 * 60 * 1000L));
        service.reconcileOrder("CUSTOM_19");
        verify(orderMapper, never()).markCommerceOrderClosed(any());

        OmsOrder renewal = new OmsOrder();
        renewal.setId(20L); renewal.setOrderSn("TPLTM_20"); renewal.setStatus(0L); renewal.setMemberId(12L); renewal.setMmsMerchantId(34L);
        renewal.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        renewal.setPayAmount(new BigDecimal("19.90")); renewal.setEntitlementDays(30); renewal.setEntitlementMaxUsage(3);
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_20")).thenReturn(renewal);
        when(orderMapper.markCommerceOrderPaid(20L, "wx-tx-20")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(2);
        org.junit.jupiter.api.Assertions.assertTrue(paidOrder("TPLTM_20", true, 1990, "wx-tx-20"));
    }

    @Test
    void notFoundCannotCloseAbandonedOriginalOrder() {
        OmsOrder order = new OmsOrder();
        order.setId(18L); order.setOrderSn("PROMOT_18"); order.setStatus(0L);
        order.setBizType(MerchantCommerceService.BIZ_PROMOTION_SLOT);
        order.setCreateTime(new java.util.Date(System.currentTimeMillis() - 25 * 60 * 60 * 1000L));
        when(orderMapper.selectOmsOrderByOrderSn("PROMOT_18")).thenReturn(order);
        when(wechatPayService.queryOrderStatus("PROMOT_18"))
                .thenThrow(new WechatOrderNotExistException("not found", null));
        service.reconcileOrder("PROMOT_18");
        verify(orderMapper, never()).markCommerceOrderClosed(any());
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
    }

    @Test
    void orderCommitsBeforeSdkAndSdkTimeoutCannotRollBackOriginalOrder() {
        service = enabledService();
        when(settlementRuleService.getActiveRule(0L, MerchantCommerceService.BIZ_PREMIUM_TEMPLATE))
                .thenReturn(pricedRule());
        when(orderMapper.insertOmsOrder(any())).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        when(wechatPayService.createGenericOrder(any(), any(), any(), any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, transactions.commits);
            throw PaymentPreorderException.resultUncertain("timeout", null);
        });
        assertThrows(PaymentPreorderException.class, () -> service.createOrder(12L, 34L, "openid",
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456"));
        verify(orderMapper).insertOmsOrder(any());
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
    }

    /** Exercises Spring's thread transaction boundary; does not simulate MySQL persistence. */
    private static final class TestPaymentTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
    }

    private MerchantCommerceService enabledService() {
        MerchantCommerceService enabled = new MerchantCommerceService(true);
        ReflectionTestUtils.setField(enabled, "subscriptionMapper", subscriptionMapper);
        ReflectionTestUtils.setField(enabled, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(enabled, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(enabled, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(enabled, "permissionService", permissionService);
        ReflectionTestUtils.setField(enabled, "paymentTransactions", transactions);
        ReflectionTestUtils.setField(enabled,"verifiedPaymentFacts",proof.reader);
        return enabled;
    }

    private SettlementRule pricedRule() {
        SettlementRule rule = new SettlementRule();
        rule.setUnitPrice(new BigDecimal("19.90"));
        rule.setEntitlementMaxUsage(3);
        rule.setEntitlementDays(30);
        return rule;
    }
    @Test
    void paymentNetworkEntriesRejectActualOuterTransactionBeforeIntentOrSdk() {
        MerchantCommerceService enabled = enabledService();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> enabled.createOrder(12L, 34L, "openid",
                    MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, "req-1234567890123456"));
            assertThrows(IllegalStateException.class, () -> enabled.reconcileOrder("TPLTM_8"));
            org.mockito.Mockito.verifyNoInteractions(subscriptionMapper, orderMapper, settlementRuleService, wechatPayService, permissionService);
            assertEquals(0, transactions.commits);
            assertEquals(0, transactions.rollbacks);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void paymentNetworkEntriesNeverSuspendLocksAndLocalPhasesStayRequiresNew() throws Exception {
        for (java.lang.reflect.Method method : MerchantCommerceService.class.getMethods()) {
            if (java.util.Set.of("createOrder", "reconcileOrder").contains(method.getName())) {
                assertEquals(org.springframework.transaction.annotation.Propagation.NEVER,
                        method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation());
            }
        }
        org.springframework.transaction.support.TransactionTemplate transaction =
                ReflectionTestUtils.invokeMethod(service, "paymentTransaction");
        assertNotNull(transaction);
        assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW, transaction.getPropagationBehavior());
    }

    @Test
    void allCommercePrefixesKeepTheFullUuidValueWithinCanonicalOrderNumberLength() {
        service = enabledService();
        when(orderMapper.insertOmsOrder(any())).thenAnswer(call -> { call.getArgument(0, OmsOrder.class).setId(99L); return 1; });
        when(wechatPayService.createGenericOrder(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        String[] types = { MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, MerchantCommerceService.BIZ_PROMOTION_SLOT, MerchantCommerceService.BIZ_CUSTOM_EVENT };
        String[] prefixes = { "TPLTM_", "PROMOT_", "CUSTOM_" };
        for (int i = 0; i < types.length; i++) {
            when(settlementRuleService.getActiveRule(0L, types[i])).thenReturn(pricedRule());
            var result = service.createOrder(12L, 34L, "openid", types[i], "req-1234567890123456-" + i);
            String number = result.get("orderSn");
            assertTrue(number.startsWith(prefixes[i]));
            assertTrue(number.length() <= 32);
            String encodedUuid = number.substring(prefixes[i].length());
            assertTrue(encodedUuid.matches("[0-9a-z]{1,25}"));
            assertTrue(new java.math.BigInteger(encodedUuid, 36).bitLength() <= 128);
            assertTrue(service.supportsOutTradeNo(number));
        }
    }

    private boolean paidOrder(String no,boolean paid,Integer fen,String tx) {
        return service.handlePaidOrder(no,paid,fen,tx,proof.issue(no,fen,tx));
    }

}
