package com.chengyinhub.business.commerceconsole;

import com.chengyinhub.business.commerceconsole.mapper.DuesRefundDecisionMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneyGate;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W7 MERCHANT-12: a dues refund review can be finished; approval refunds only while the refunds area is open. */
class DuesRefundDecisionServiceTest {
    final DuesRefundDecisionMapper mapper = mock(DuesRefundDecisionMapper.class);
    final OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
    final MoneyGate gate = mock(MoneyGate.class);
    final IWechatPayService payments = mock(IWechatPayService.class);
    final DuesRefundDecisionService service =
            new DuesRefundDecisionService(mapper, audit, gate, payments, mock(PlatformTransactionManager.class));

    private Map<String, Object> review() {
        Map<String, Object> r = new HashMap<>();
        r.put("reviewId", 9L);
        r.put("reviewStatus", "PENDING_REVIEW");
        r.put("invoiceId", 4L);
        r.put("outTradeNo", "CLM0123456789abcdef0123456789abc");
        r.put("amountFen", 9900L);
        r.put("invoiceStatus", "PAID");
        return r;
    }

    @Test
    void refundOnlyWithRefundsOpen() {
        when(gate.isOpen(MoneyArea.REFUNDS)).thenReturn(false);
        when(mapper.selectForDecision(9L)).thenReturn(review());
        ServiceException closed = assertThrows(ServiceException.class, () -> service.approve(1L, "ops", 9L, "member left"));
        assertTrue(closed.getMessage().contains("退款"));
        verifyNoInteractions(payments);
        verify(mapper, never()).insertDecision(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void approvalNeedsANoteAndWritesTheDecisionBeforeTheRefundLeaves() {
        when(gate.isOpen(MoneyArea.REFUNDS)).thenReturn(true);
        assertThrows(ServiceException.class, () -> service.approve(1L, "ops", 9L, " "));
        verifyNoInteractions(payments);

        when(mapper.selectForDecision(9L)).thenReturn(review());
        when(mapper.insertDecision(9L, "APPROVED", "member left", "ops", "CRF9", 9900L, "SUBMITTING")).thenReturn(1);
        when(payments.submitRefund("CLM0123456789abcdef0123456789abc", "CRF9", new BigDecimal("99.00"),
                new BigDecimal("99.00"), "会费退款")).thenReturn(RefundRequestResult.ACCEPTED);
        Map<String, Object> out = service.approve(1L, "ops", 9L, "member left");
        assertEquals("ACCEPTED", out.get("refundState"));
        var order = inOrder(mapper, audit, payments);
        order.verify(mapper).insertDecision(9L, "APPROVED", "member left", "ops", "CRF9", 9900L, "SUBMITTING");
        order.verify(audit).record(eq(1L), eq("CLUB_DUES_REFUND_APPROVE"), anyString(), eq(9L), any(), any(), eq("member left"));
        order.verify(payments).submitRefund(anyString(), eq("CRF9"), any(), any(), anyString());
        order.verify(mapper).updateRefundState(9L, "ACCEPTED");
        order.verify(audit).record(eq(1L), eq("CLUB_DUES_REFUND_SUBMITTED"), anyString(), eq(9L), any(), any(), eq("member left"));
    }

    @Test
    void anUnknownAnswerCanBeSentAgainWithTheSameRefundNumber() {
        when(gate.isOpen(MoneyArea.REFUNDS)).thenReturn(true);
        when(mapper.selectForDecision(9L)).thenReturn(review());
        when(mapper.selectDecision(9L)).thenReturn(Map.of("decision", "APPROVED", "refundState", "UNKNOWN",
                "outRefundNo", "CRF9", "refundAmountFen", 9900L));
        when(payments.submitRefund(anyString(), anyString(), any(), any(), anyString())).thenThrow(new IllegalStateException("down"));
        assertEquals("UNKNOWN", service.approve(1L, "ops", 9L, "retry").get("refundState"));
        verify(payments).submitRefund(anyString(), eq("CRF9"), any(), any(), anyString());
        verify(mapper, never()).insertDecision(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString());

        when(mapper.selectDecision(9L)).thenReturn(Map.of("decision", "APPROVED", "refundState", "ACCEPTED",
                "outRefundNo", "CRF9", "refundAmountFen", 9900L));
        assertThrows(ServiceException.class, () -> service.approve(1L, "ops", 9L, "again"));
        verify(payments, times(1)).submitRefund(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void rejectMovesNoMoneyAndIsAudited() {
        when(gate.isOpen(any())).thenReturn(false);
        when(mapper.selectForDecision(9L)).thenReturn(review());
        when(mapper.insertDecision(9L, "REJECTED", "outside policy", "ops", null, null, "NOT_SENT")).thenReturn(1);
        assertEquals("REJECTED", service.reject(1L, "ops", 9L, "outside policy").get("decision"));
        verify(audit).record(eq(1L), eq("CLUB_DUES_REFUND_REJECT"), anyString(), eq(9L), any(), any(), eq("outside policy"));
        verifyNoInteractions(payments);

        when(mapper.selectDecision(9L)).thenReturn(Map.of("decision", "REJECTED", "refundState", "NOT_SENT"));
        assertThrows(ServiceException.class, () -> service.reject(1L, "ops", 9L, "twice"));
        when(gate.isOpen(MoneyArea.REFUNDS)).thenReturn(true);
        assertThrows(ServiceException.class, () -> service.approve(1L, "ops", 9L, "changed my mind"));
        verifyNoInteractions(payments);
    }

    @Test
    void migrationGrantsTheRefundButtonToNoRoleAndKeepsTheReviewTable() throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        if (!Files.exists(base.resolve("chengyinhub-admin"))) base = base.getParent();
        String sql = Files.readString(base.resolve("chengyinhub-admin/sql/migration_20261130_money_on_operator.sql"));
        assertTrue(sql.contains("'business:commerceConsole:refund'"));
        assertTrue(sql.contains("'business:merchantplans:grantPaid'"));
        assertFalse(sql.toLowerCase().contains("insert into sys_role_menu"));
        assertFalse(sql.toLowerCase().contains("alter table club_membership_refund_review"));
        assertFalse(sql.toLowerCase().contains("cy_money_switch"));
    }
}
