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

    @BeforeEach
    void setUp() {
        subscriptionMapper = mock(MerchantSubscriptionMapper.class);
        orderMapper = mock(OmsOrderMapper.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        wechatPayService = mock(IWechatPayService.class);
        permissionService = mock(IUserPermissionService.class);
        service = new MerchantCommerceService(false);
        ReflectionTestUtils.setField(service, "subscriptionMapper", subscriptionMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(service, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
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
        pending.setMemberId(12L);
        pending.setBizType(MerchantCommerceService.BIZ_PREMIUM_TEMPLATE);
        pending.setStatus(0L);
        pending.setPayAmount(new BigDecimal("19.90"));
        OmsOrder paid = new OmsOrder();
        paid.setStatus(1L);
        paid.setPaymentTransactionId("wx-tx-8");
        when(orderMapper.selectOmsOrderByOrderSn("TPLTM_8")).thenReturn(pending, pending, paid);
        when(orderMapper.markCommerceOrderPaid(8L, "wx-tx-8")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(1);

        assertTrue(service.handlePaidOrder("TPLTM_8", true, 1990, "wx-tx-8"));
        verify(subscriptionMapper).grantMerchantSubscription(any());

        when(orderMapper.markCommerceOrderPaid(8L, "wx-tx-8")).thenReturn(0);
        assertTrue(service.handlePaidOrder("TPLTM_8", true, 1990, "wx-tx-8"));
        verify(subscriptionMapper).grantMerchantSubscription(any());
    }

    @Test
    void paidCommerceCallback_rejectsAmountMismatchBeforePaidCas() {
        OmsOrder pending = new OmsOrder();
        pending.setId(8L);
        pending.setOrderSn("PROMOT_8");
        pending.setBizType(MerchantCommerceService.BIZ_PROMOTION_SLOT);
        pending.setStatus(0L);
        pending.setPayAmount(new BigDecimal("19.90"));
        when(orderMapper.selectOmsOrderByOrderSn("PROMOT_8")).thenReturn(pending);

        assertFalse(service.handlePaidOrder("PROMOT_8", true, 1989, "wx-tx-8"));
        verify(orderMapper, never()).markCommerceOrderPaid(any(), any());
        verify(subscriptionMapper, never()).grantMerchantSubscription(any());
    }

    @Test
    void paidCommerceCallback_grantFailureThrowsForTransactionRollback() {
        OmsOrder pending = new OmsOrder();
        pending.setId(9L);
        pending.setOrderSn("CUSTOM_9");
        pending.setBizType(MerchantCommerceService.BIZ_CUSTOM_EVENT);
        pending.setStatus(0L);
        pending.setPayAmount(new BigDecimal("88.00"));
        when(orderMapper.selectOmsOrderByOrderSn("CUSTOM_9")).thenReturn(pending);
        when(orderMapper.markCommerceOrderPaid(9L, "wx-tx-9")).thenReturn(1);
        when(subscriptionMapper.grantMerchantSubscription(any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.handlePaidOrder("CUSTOM_9", true, 8800, "wx-tx-9"));
    }

    @Test
    void enabledCheckoutRequiresConfiguredPrice() {
        service = new MerchantCommerceService(true);
        ReflectionTestUtils.setField(service, "subscriptionMapper", subscriptionMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(service, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
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

    private MerchantCommerceService enabledService() {
        MerchantCommerceService enabled = new MerchantCommerceService(true);
        ReflectionTestUtils.setField(enabled, "subscriptionMapper", subscriptionMapper);
        ReflectionTestUtils.setField(enabled, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(enabled, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(enabled, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(enabled, "permissionService", permissionService);
        return enabled;
    }

    private SettlementRule pricedRule() {
        SettlementRule rule = new SettlementRule();
        rule.setUnitPrice(new BigDecimal("19.90"));
        rule.setEntitlementMaxUsage(3);
        rule.setEntitlementDays(30);
        return rule;
    }
}
