package com.chengyinhub.business.platformpass;

import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.business.platformpass.mapper.PlatformPassAppleMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** T5: App Store refund reversal (REVIEW) — operator re-grant or close, with a note and an audit row. */
class PlatformPassReversalReviewTest {
    static final String NO = "PP20261115000001";
    static final long ORDER_ID = 31L, MEMBER = 42L;

    PlatformPassMapper passMapper;
    PlatformPassAppleMapper mapper;
    OpsConsoleAudit audit;
    PlatformPassService pass;
    PlatformPassReversalReviewService service;
    Map<String, Object> apple;
    Map<String, Object> order;

    @BeforeEach
    void setUp() {
        passMapper = mock(PlatformPassMapper.class);
        mapper = mock(PlatformPassAppleMapper.class);
        audit = mock(OpsConsoleAudit.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        pass = spy(new PlatformPassService(passMapper, new PlatformPassProperties(), tm));
        service = new PlatformPassReversalReviewService(pass, passMapper, mapper, audit, tm);
        apple = new HashMap<>(Map.of("order_no", NO, "state", "REVIEW", "transaction_id", "2000000111"));
        order = new HashMap<>(Map.of("id", ORDER_ID, "member_id", MEMBER, "order_no", NO, "status",
                PlatformPassService.REFUNDED, "valid_days", 30));
        when(mapper.selectByOrderNoForUpdate(NO)).thenAnswer(i -> apple);
        when(mapper.selectByOrderNo(NO)).thenAnswer(i -> apple);
        when(passMapper.selectOrderByNoForUpdate(NO)).thenAnswer(i -> order);
        when(passMapper.selectOrderByNo(NO)).thenAnswer(i -> order);
        when(mapper.resolveReview(eq(NO), anyString())).thenAnswer(i -> {
            apple.put("state", i.getArgument(1));
            return 1;
        });
    }

    @Test
    void listShowsReviewOrdersWithTheirAppleNotifications() {
        Map<String, Object> row = new HashMap<>(Map.of("order_no", NO, "transaction_id", "2000000111"));
        when(mapper.selectByState("REVIEW", 50)).thenReturn(List.of(row));
        when(mapper.selectNotificationsByTransaction("2000000111")).thenReturn(List.of(
                Map.of("notification_type", "REFUND"), Map.of("notification_type", "REFUND_REVERSED")));
        List<Map<String, Object>> out = service.list(null, null);
        assertEquals(1, out.size());
        assertEquals(2, ((List<?>) out.get(0).get("notifications")).size());
        assertFalse(out.get(0).containsKey("app_account_token"));
        service.list("granted", 999);
        verify(mapper).selectByState("GRANTED", PlatformPassReversalReviewService.MAX_LIMIT);
        assertThrows(PlatformPassException.class, () -> service.list("PENDING", 10));
    }

    @Test
    void aNoteIsRequiredAndNothingIsWrittenWithoutOne() {
        assertEquals("PASS_REVIEW_NOTE_REQUIRED",
                assertThrows(PlatformPassException.class, () -> service.regrant(1L, NO, "  ")).getReasonCode());
        assertEquals("PASS_REVIEW_NOTE_TOO_LONG",
                assertThrows(PlatformPassException.class, () -> service.close(1L, NO, "x".repeat(501))).getReasonCode());
        verifyNoInteractions(audit);
        verify(mapper, never()).resolveReview(anyString(), anyString());
    }

    @Test
    void regrantReopensTheRevokedSegmentFromNowAndAudits() {
        when(passMapper.reinstateRefundedOrder(ORDER_ID)).thenAnswer(i -> {
            order.put("status", PlatformPassService.PAID);
            return 1;
        });
        when(passMapper.selectMembershipByOrder(ORDER_ID)).thenReturn(Map.of("id", 5L, "status", 3));
        when(passMapper.reopenMembership(eq(ORDER_ID), any(), any())).thenReturn(1);
        long before = System.currentTimeMillis();
        Map<String, Object> out = service.regrant(9L, NO, "Apple reversed the refund; customer paid");
        assertEquals("GRANTED", out.get("state"));
        assertEquals(PlatformPassService.PAID, out.get("orderStatus"));
        verify(passMapper).reopenMembership(eq(ORDER_ID), argThat(from -> from.getTime() >= before),
                argThat(to -> Math.abs(to.getTime() - before - 30L * 86_400_000L) < 60_000));
        verify(passMapper).insertMemberLock(MEMBER);
        verify(audit).record(eq(9L), eq("PASS_REVERSAL_REGRANT"), eq("PLATFORM_PASS_ORDER"), eq(ORDER_ID),
                any(), any(), eq("Apple reversed the refund; customer paid"));
    }

    @Test
    void regrantStartsAfterTheMembersLastSegment() {
        Date later = new Date(System.currentTimeMillis() + 10L * 86_400_000L);
        when(passMapper.reinstateRefundedOrder(ORDER_ID)).thenReturn(1);
        when(passMapper.selectLatestValidToForUpdate(eq(MEMBER), any())).thenReturn(later);
        when(passMapper.selectMembershipByOrder(ORDER_ID)).thenReturn(Map.of("id", 5L, "status", 3));
        when(passMapper.reopenMembership(eq(ORDER_ID), any(), any())).thenReturn(1);
        service.regrant(9L, NO, "ok");
        verify(passMapper).reopenMembership(ORDER_ID, later, new Date(later.getTime() + 30L * 86_400_000L));
    }

    @Test
    void regrantWhenTheRefundArrivedBeforeTheGrantPaysAndActivatesOnce() {
        order.put("status", PlatformPassService.CANCELLED);
        when(mapper.markOrderPaid(eq(ORDER_ID), eq("2000000111"), any())).thenReturn(1);
        doNothing().when(pass).activate(any(), any(), any());
        service.regrant(9L, NO, "ok");
        verify(pass).activate(eq(order), any(), isNull());
        verify(passMapper, never()).reinstateRefundedOrder(anyLong());
    }

    @Test
    void regrantWaitsWhileARefundIsInFlight() {
        order.put("status", PlatformPassService.REFUNDING);
        assertEquals("PASS_REVIEW_ORDER_BUSY",
                assertThrows(PlatformPassException.class, () -> service.regrant(9L, NO, "ok")).getReasonCode());
        verify(mapper, never()).resolveReview(anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void closeKeepsTheRefundAndAudits() {
        Map<String, Object> out = service.close(9L, NO, "refund stands");
        assertEquals("REVOKED", out.get("state"));
        verify(passMapper, never()).reinstateRefundedOrder(anyLong());
        verify(passMapper, never()).insertMembership(any());
        verify(pass, never()).activate(any(), any(), any());
        verify(audit).record(eq(9L), eq("PASS_REVERSAL_CLOSE"), eq("PLATFORM_PASS_ORDER"), eq(ORDER_ID),
                any(), any(), eq("refund stands"));
    }

    @Test
    void aResolvedOrderCannotBeResolvedAgain() {
        apple.put("state", "GRANTED");
        assertEquals("PASS_REVIEW_ALREADY_RESOLVED",
                assertThrows(PlatformPassException.class, () -> service.close(9L, NO, "again")).getReasonCode());
        verifyNoInteractions(audit);
    }
}
