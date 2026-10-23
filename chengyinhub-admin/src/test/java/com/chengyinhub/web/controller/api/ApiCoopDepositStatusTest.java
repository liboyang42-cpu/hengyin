package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ApiCoopDepositStatusTest {
    private ApiCoopController controller;
    private ICoopInviteService service;
    private com.chengyinhub.business.service.IWechatPayService pay;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiCoopController());
        doReturn(1001L).when(controller).getAppUserId();
        service = mock(ICoopInviteService.class);
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        pay = mock(com.chengyinhub.business.service.IWechatPayService.class);
        ReflectionTestUtils.setField(controller, "wechatPayService", pay);
    }

    @Test
    void payerReadsPaidTerminalState() {
        CoopInvite invite = invite(2);
        when(service.selectCoopInviteById(7L)).thenReturn(invite);
        when(service.depositOwedParty(invite)).thenReturn(1001L);

        AjaxResult result = controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("success", ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("paymentStatus"));
    }

    @Test
    void otherMemberCannotReadDepositState() {
        CoopInvite invite = invite(2);
        when(service.selectCoopInviteById(7L)).thenReturn(invite);
        when(service.depositOwedParty(invite)).thenReturn(2002L);

        AjaxResult result = controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权查看该保证金状态", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void deductionAndRefundDoNotInventPaymentFacts() {
        for (int state : new int[]{3, 4}) {
            CoopInvite invite = invite(state);
            when(service.selectCoopInviteById(7L)).thenReturn(invite);
            when(service.depositOwedParty(invite)).thenReturn(1001L);
            AjaxResult result = controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L));
            assertEquals("unknown", ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("paymentStatus"));
            invite.setDepositPaidTransactionId("verified-wx-transaction");
            result = controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L));
            assertEquals("success", ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("paymentStatus"));
        }
    }

    @Test
    void pendingDepositQueriesVerifiedOriginalThenUsesDomainAmountAndTransactionCas() {
        CoopInvite pending = invite(1);
        pending.setDepositOutTradeNo("DEP7");
        CoopInvite paid = invite(2);
        when(service.selectCoopInviteById(7L)).thenReturn(pending, paid);
        when(service.depositOwedParty(pending)).thenReturn(1001L);
        when(pay.queryOrderStatus("DEP7")).thenReturn(java.util.Map.of("out_trade_no", "DEP7", "wx_out_trade_no", "WXP_ALIAS",
                "trade_state", "SUCCESS", "currency", "CNY", "total", "5000", "transaction_id", "TX7"));
        when(service.markDepositPaid("DEP7", 5000L, "TX7")).thenReturn(true);
        AjaxResult result = controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L));
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("success", ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("paymentStatus"));
        org.mockito.Mockito.verify(service).markDepositPaid("DEP7", 5000L, "TX7");
    }

    @Test
    void unknownOrConflictingDepositQueryCannotMutatePayment() {
        CoopInvite pending = invite(1);
        pending.setDepositOutTradeNo("DEP7");
        when(service.selectCoopInviteById(7L)).thenReturn(pending);
        when(service.depositOwedParty(pending)).thenReturn(1001L);
        for (String state : new String[]{"CLOSED", "NOTPAY", "UNKNOWN"}) {
            when(pay.queryOrderStatus("DEP7")).thenReturn(java.util.Map.of("out_trade_no", "DEP7", "trade_state", state));
            AjaxResult result = controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L));
            assertEquals("pending", ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("paymentStatus"));
        }
        when(pay.queryOrderStatus("DEP7")).thenThrow(new RuntimeException("timeout"));
        assertEquals(409, controller.depositStatus(Collections.<String, Object>singletonMap("inviteId", 7L)).get(AjaxResult.CODE_TAG));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).markDepositPaid(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static CoopInvite invite(int depositStatus) {
        CoopInvite invite = new CoopInvite();
        invite.setId(7L);
        invite.setDelFlag(0);
        invite.setDepositStatus(depositStatus);
        return invite;
    }
}
