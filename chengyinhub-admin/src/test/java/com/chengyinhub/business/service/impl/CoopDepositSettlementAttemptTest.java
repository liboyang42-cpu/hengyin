package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit boundary only; real gateway/Mapper/transaction coverage is in CoopDepositSettlementAttemptMySqlIT. */
class CoopDepositSettlementAttemptTest {
    private static final String BUSINESS = "DEP89T100";
    private static final String ATTEMPT = "WXP00000000000000000000000000001";
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");
    private CmsTopicServiceImpl topic;
    private IWechatPayService gateway;
    private ICoopInviteService invites;
    private CoopInviteMapper mapper;
    private CoopInvite row;

    @BeforeEach
    void setup() {
        topic = new CmsTopicServiceImpl();
        gateway = mock(IWechatPayService.class);
        invites = mock(ICoopInviteService.class);
        mapper = mock(CoopInviteMapper.class);
        ReflectionTestUtils.setField(topic, "wechatPayService", gateway);
        ReflectionTestUtils.setField(topic, "coopInviteService", invites);
        ReflectionTestUtils.setField(topic, "coopInviteMapper", mapper);
        row = new CoopInvite();
        row.setId(89L); row.setDepositStatus(1); row.setDepositOutTradeNo(BUSINESS); row.setDepositAmount(AMOUNT);
    }

    @Test
    void payerrorClosesExactObservedAttemptThenFencesBeforeUnpaidCas() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR"));
        when(gateway.closePaymentAttempt(BUSINESS, ATTEMPT)).thenReturn(true);
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(1);
        assertTrue(resolve());
        var order = inOrder(gateway, mapper);
        order.verify(gateway).queryOrderStatus(BUSINESS);
        order.verify(gateway).closePaymentAttempt(BUSINESS, ATTEMPT);
        order.verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        order.verify(mapper).updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT);
        verify(gateway, never()).closeOrder(anyString());
        assertEquals(5, row.getDepositStatus());
        assertEquals(BUSINESS, row.getDepositOutTradeNo());
    }

    @Test
    void closedObservationStillRequiresCurrentAttemptFence() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        when(mapper.updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT)).thenReturn(1);
        assertTrue(resolve());
        var order = inOrder(gateway, mapper);
        order.verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        order.verify(mapper).updateDepositIfCurrent(89L, 1, AMOUNT, 5, AMOUNT);
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void noneOfTheUnpaidObservationsPermitsCasWhenClosureIsUnconfirmed() {
        for (String state : new String[]{"PAYERROR", "NOTPAY", "USERPAYING"}) {
            when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts(state));
            assertThrows(IllegalStateException.class, this::resolve);
            unchanged();
        }
        verifyNoInteractions(mapper, invites);
        verify(gateway, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
    }

    @Test
    void closeExceptionAndUnknownOrRevokedNeverReleaseDeposit() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR"));
        when(gateway.closePaymentAttempt(BUSINESS, ATTEMPT)).thenThrow(new IllegalStateException("unknown"));
        assertThrows(IllegalStateException.class, this::resolve);
        for (String state : new String[]{"UNKNOWN", "REVOKED"}) {
            when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts(state));
            assertThrows(IllegalStateException.class, this::resolve);
        }
        unchanged();
        verifyNoInteractions(mapper, invites);
    }

    @Test
    void missingAttemptIdentityRejectsEvenSuccessWithoutDomainMutation() {
        for (String state : new String[]{"PAYERROR", "CLOSED", "SUCCESS"}) {
            Map<String, String> missing = facts(state);
            missing.remove("wx_out_trade_no");
            when(gateway.queryOrderStatus(BUSINESS)).thenReturn(missing);
            assertThrows(IllegalStateException.class, this::resolve);
        }
        unchanged();
        verifyNoInteractions(mapper, invites);
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void closedOldAttemptCannotCasTheCurrentDeposit() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
        verify(gateway).lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT);
        verifyNoInteractions(mapper, invites);
    }

    @Test
    void lateSuccessAfterUnconfirmedCloseUsesOriginalPaidReadbackOnRetry() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("PAYERROR"), facts("SUCCESS"));
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
        CoopInvite paid = new CoopInvite(); paid.setId(89L); paid.setDepositStatus(2);
        when(invites.markDepositPaid(BUSINESS, 5000L, "WXTX89")).thenReturn(true);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(paid);
        assertFalse(resolve());
        verify(invites).markDepositPaid(BUSINESS, 5000L, "WXTX89");
        verify(mapper).selectCoopInviteByIdForUpdate(89L);
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
    }

    @Test
    void successStillRequiresFrozenPaidReadbackAndCannotEnterUnpaidCas() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("SUCCESS"));
        when(invites.markDepositPaid(BUSINESS, 5000L, "WXTX89")).thenReturn(true);
        when(mapper.selectCoopInviteByIdForUpdate(89L)).thenReturn(row);
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
        verify(mapper, never()).updateDepositIfCurrent(anyLong(), anyInt(), any(), anyInt(), any());
        verify(gateway, never()).closePaymentAttempt(anyString(), anyString());
    }

    @Test
    void failedUnpaidCasDoesNotMutateInMemoryReceipt() {
        when(gateway.queryOrderStatus(BUSINESS)).thenReturn(facts("CLOSED"));
        when(gateway.lockClosablePaymentAttemptForUpdate(BUSINESS, ATTEMPT)).thenReturn(true);
        assertThrows(IllegalStateException.class, this::resolve);
        unchanged();
    }

    private boolean resolve() {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(topic, "resolvePendingDepositPaymentForSettlement", row));
    }

    private void unchanged() {
        assertEquals(1, row.getDepositStatus());
        assertEquals(BUSINESS, row.getDepositOutTradeNo());
        assertEquals(AMOUNT, row.getDepositAmount());
    }

    private Map<String, String> facts(String state) {
        Map<String, String> facts = new HashMap<>();
        facts.put("out_trade_no", BUSINESS); facts.put("wx_out_trade_no", ATTEMPT);
        facts.put("trade_state", state); facts.put("currency", "CNY");
        facts.put("total", "5000"); facts.put("transaction_id", "WXTX89");
        return facts;
    }
}
