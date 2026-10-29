package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Policy/forwarding tests only; the transaction flag is not a database rollback proof. */
class OmsOrderCommerceGuardTest {
    private OmsOrderMapper mapper;
    private OmsOrderServiceImpl service;

    @BeforeEach void setUp() {
        mapper = mock(OmsOrderMapper.class);
        service = new OmsOrderServiceImpl();
        ReflectionTestUtils.setField(service, "omsOrderMapper", mapper);
    }

    @Test void forgedCommercePostsCannotReachGenericInsert() {
        for (String type : List.of("premium_template", "promotion_slot", "custom_event", " PREMIUM_TEMPLATE ")) {
            OmsOrder forged = new OmsOrder(); forged.setBizType(type);
            assertThrows(ServiceException.class, () -> service.insertOmsOrder(forged));
            assertNull(forged.getCreateTime());
        }
        for (String no : List.of("TPLTM_original", "PROMOT_original", "CUSTOM_original", "tpltm_lower")) {
            OmsOrder forged = new OmsOrder(); forged.setOrderSn(no);
            assertThrows(ServiceException.class, () -> service.insertOmsOrder(forged));
        }
        OmsOrder hidden = new OmsOrder(); hidden.setEntitlementMaxUsage(1000);
        assertThrows(ServiceException.class, () -> service.insertOmsOrder(hidden));
        verifyNoInteractions(mapper);
    }

    @Test void normalCartStyleInsertKeepsOriginalFieldsAndGeneratedId() {
        OmsOrder order = ordinary(9L); order.setId(null); order.setStatus(1L); order.setPayType(1L);
        order.setProductQuantity(2L); order.setPointsAmount(new BigDecimal("20"));
        when(mapper.insertOmsOrder(order)).thenAnswer(call -> { order.setId(99L); return 1; });
        assertEquals(1, service.insertOmsOrder(order));
        assertEquals(99L, order.getId()); assertEquals(1L, order.getStatus());
        assertNotNull(order.getCreateTime()); verify(mapper).insertOmsOrder(order);
    }

    @Test void hiddenBizTypeCannotChangeAnyFrozenCommerceEconomicInput() {
        OmsOrder stored = commerce(8L);
        when(mapper.selectOmsOrderByIdForUpdate(8L)).thenReturn(stored);
        List<Consumer<OmsOrder>> changes = List.of(
                p -> p.setMemberId(71L), p -> p.setMmsMerchantId(35L), p -> p.setOrderSn("ordinary-new"),
                p -> p.setBizType("ordinary"), p -> p.setOrderType(2L), p -> p.setPayId(4L),
                p -> p.setPayAmount(new BigDecimal("0.01")), p -> p.setOrderTotalAmount(new BigDecimal("0.01")),
                p -> p.setProductTotalAmount(new BigDecimal("0.01")), p -> p.setProductQuantity(1000L),
                p -> p.setDeliveryFee(BigDecimal.ONE), p -> p.setTaxFee(BigDecimal.ONE),
                p -> p.setPointsAmount(BigDecimal.ONE), p -> p.setCouponAmount(BigDecimal.ONE),
                p -> p.setMemberCouponId(6L), p -> p.setStatus(1L), p -> p.setPayType(1L),
                p -> p.setPaymentTransactionId("forged-tx"), p -> p.setEntitlementDays(999),
                p -> p.setEntitlementMaxUsage(1000), p -> p.setCommerceRequestId("new-request"),
                p -> p.setDelFlag(2), p -> p.setVerifiedPaymentFactId(9L),
                p -> p.setVerifiedPaymentFactHash("b".repeat(64)));
        for (Consumer<OmsOrder> change : changes) {
            OmsOrder patch = new OmsOrder(); patch.setId(8L); change.accept(patch);
            assertThrows(ServiceException.class, () -> inTransaction(() -> service.updateOmsOrder(patch)));
            assertNull(patch.getUpdateTime());
        }
        assertEquals(1, stored.getEntitlementMaxUsage()); assertEquals(70L, stored.getMemberId());
        verify(mapper, never()).updateOmsOrder(any());
        verify(mapper, never()).selectOmsOrderById(any());
    }

    @Test void commerceNotesUseANewWhitelistEntityAndEqualMoneyScaleIsNotAChange() {
        OmsOrder stored = commerce(8L);
        when(mapper.selectOmsOrderByIdForUpdate(8L)).thenReturn(stored);
        OmsOrder patch = commerce(8L); patch.setPayAmount(new BigDecimal("19.900")); patch.setOrderNote("operator note");
        when(mapper.updateOmsOrder(any())).thenReturn(1);
        assertEquals(1, inTransaction(() -> service.updateOmsOrder(patch)).intValue());
        ArgumentCaptor<OmsOrder> write = ArgumentCaptor.forClass(OmsOrder.class);
        verify(mapper).updateOmsOrder(write.capture());
        assertNotSame(patch, write.getValue()); assertEquals("operator note", write.getValue().getOrderNote());
        assertNull(write.getValue().getMemberId()); assertNull(write.getValue().getOrderSn());
        assertNull(write.getValue().getStatus()); assertNull(write.getValue().getEntitlementMaxUsage());
        assertNull(write.getValue().getVerifiedPaymentFactId()); assertNotNull(write.getValue().getUpdateTime());
    }

    @Test void ordinaryUpdateRemainsBroadButCannotBecomeCommerce() {
        OmsOrder stored = ordinary(9L);
        when(mapper.selectOmsOrderByIdForUpdate(9L)).thenReturn(stored);
        OmsOrder patch = ordinary(9L); patch.setStatus(2L); patch.setDeliverySn("real-shipping-number");
        when(mapper.updateOmsOrder(patch)).thenReturn(1);
        assertEquals(1, inTransaction(() -> service.updateOmsOrder(patch)).intValue());
        verify(mapper).updateOmsOrder(patch);
        OmsOrder conversion = new OmsOrder(); conversion.setId(9L); conversion.setBizType("premium_template");
        assertThrows(ServiceException.class, () -> inTransaction(() -> service.updateOmsOrder(conversion)));
        verify(mapper, times(1)).updateOmsOrder(any());
    }

    @Test void mixedDeleteLocksAllSortedOriginalRowsBeforeRefusalAndWritesNothing() {
        when(mapper.selectOmsOrderByIdForUpdate(1L)).thenReturn(commerce(1L));
        when(mapper.selectOmsOrderByIdForUpdate(5L)).thenReturn(ordinary(5L));
        when(mapper.selectOmsOrderByIdForUpdate(9L)).thenReturn(ordinary(9L));
        assertThrows(ServiceException.class, () -> inTransaction(() -> service.deleteOmsOrderByIds(new Long[]{9L,1L,5L,1L})));
        InOrder ordered = inOrder(mapper);
        ordered.verify(mapper).selectOmsOrderByIdForUpdate(1L);
        ordered.verify(mapper).selectOmsOrderByIdForUpdate(5L);
        ordered.verify(mapper).selectOmsOrderByIdForUpdate(9L);
        verify(mapper, never()).deleteOmsOrderByIds(any()); verify(mapper, never()).deleteOmsOrderById(any());
    }

    @Test void ordinaryDeletesPreserveMissingAndDuplicateIdSemanticsAndCheckAffectedRows() {
        when(mapper.selectOmsOrderByIdForUpdate(5L)).thenReturn(ordinary(5L));
        when(mapper.selectOmsOrderByIdForUpdate(9L)).thenReturn(null);
        when(mapper.deleteOmsOrderByIds(any())).thenReturn(1);
        assertEquals(1, inTransaction(() -> service.deleteOmsOrderByIds(new Long[]{9L,5L,5L})).intValue());
        ArgumentCaptor<Long[]> ids = ArgumentCaptor.forClass(Long[].class);
        verify(mapper).deleteOmsOrderByIds(ids.capture()); assertArrayEquals(new Long[]{5L}, ids.getValue());
        when(mapper.deleteOmsOrderByIds(any())).thenReturn(0);
        assertThrows(ServiceException.class, () -> inTransaction(() -> service.deleteOmsOrderById(5L)));
    }

    @Test void singleDeleteAndNonProxiedCurrentWritesCannotBypassAdmission() {
        when(mapper.selectOmsOrderByIdForUpdate(8L)).thenReturn(commerce(8L));
        assertThrows(ServiceException.class, () -> inTransaction(() -> service.deleteOmsOrderById(8L)));
        OmsOrder patch = new OmsOrder(); patch.setId(8L);
        assertThrows(IllegalStateException.class, () -> service.updateOmsOrder(patch));
        assertThrows(IllegalStateException.class, () -> service.deleteOmsOrderByIds(new Long[]{8L}));
        verify(mapper, never()).updateOmsOrder(any()); verify(mapper, never()).deleteOmsOrderByIds(any());
    }

    private static OmsOrder commerce(Long id) {
        OmsOrder order = new OmsOrder(); order.setId(id); order.setBizType("premium_template");
        order.setOrderSn("TPLTM_original"); order.setMemberId(70L); order.setMmsMerchantId(34L);
        order.setPayAmount(new BigDecimal("19.90")); order.setStatus(0L); order.setPayType(0L);
        order.setEntitlementDays(30); order.setEntitlementMaxUsage(1); order.setCommerceRequestId("original-request");
        return order;
    }
    private static OmsOrder ordinary(Long id) {
        OmsOrder order = new OmsOrder(); order.setId(id); order.setOrderSn("MALL_original_"+id);
        order.setMemberId(70L); order.setMmsMerchantId(34L); return order;
    }
    private static <T> T inTransaction(Supplier<T> action) {
        boolean before = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { return action.get(); } finally { TransactionSynchronizationManager.setActualTransactionActive(before); }
    }
}
