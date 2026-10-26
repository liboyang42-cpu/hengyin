package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.FreeExploreRefundContext;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamGroupF3PreparationTest {
    @Test
    void singleOrderPenaltyRejectionPrecedesInventoryPointsAndIntent() {
        Fixture f = fixture(); CmsRegistration row = row(99L, "10.00");
        when(f.registrations.selectCmsRegistrationByIdForUpdate(99L)).thenReturn(row);
        when(f.refunds.prepareRegistrationFullRefund(99L, new BigDecimal("10.00")))
                .thenThrow(new ServiceException("罚扣权益不得全退", 409));
        ServiceException failure = assertThrows(ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(f.team, "refundOne", row, "ticket", "cancel"));
        assertTrue(failure.getMessage().contains("罚扣"));
        verify(f.registrations, never()).claimRegistrationRefunded(any(), any(), any());
        verify(f.refunds, never()).savePreparedRegistrationRefund(any(), any(), any());
        verifyNoInteractions(f.perks, f.payouts);
    }

    @Test
    void completeParentIsPreparedBeforeAnyNonPayerClaimOrCashEffect() {
        Fixture f = fixture(); CmsRegistration root = row(99L, "10.00"), child = row(100L, "0.00");
        root.setParentOrderNo("P"); child.setParentOrderNo("P");
        when(f.registrations.selectByParentOrderNoForUpdate("P", 20L)).thenReturn(List.of(root, child));
        when(f.refunds.selectRegistrationHistoryForUpdate(anyLong())).thenReturn(List.of());
        when(f.refunds.prepareRegistrationFullRefund(99L, new BigDecimal("10.00")))
                .thenThrow(new ServiceException("子票权益已罚扣", 409));
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(f.team,
                "refundWholeOrder", root, "ticket", "cancel", 9L));
        verify(f.registrations, never()).claimRegistrationRefunded(any(), any(), any());
        verifyNoInteractions(f.perks, f.payouts);
    }

    @Test
    void zeroCashCompletesOnlyAfterConditionalCancellationWithoutCashIntent() {
        Fixture f = fixture(); CmsRegistration row = row(99L, "0.00");
        when(f.registrations.selectCmsRegistrationByIdForUpdate(99L)).thenReturn(row);
        when(f.registrations.claimRegistrationRefunded(eq(99L), any(), any())).thenReturn(1);
        FreeExploreRefundContext context = new FreeExploreRefundContext(99L, BigDecimal.ZERO, List.of(99L), Set.of());
        when(f.refunds.prepareRegistrationFullRefund(99L, new BigDecimal("0.00"))).thenReturn(context);
        ReflectionTestUtils.invokeMethod(f.team, "refundOne", row, "ticket", "cancel");
        var order = inOrder(f.refunds, f.registrations, f.perks);
        order.verify(f.refunds).prepareRegistrationFullRefund(99L, new BigDecimal("0.00"));
        order.verify(f.registrations).claimRegistrationRefunded(eq(99L), any(), any());
        order.verify(f.perks).releaseOnRefund(eq(99L), any());
        order.verify(f.refunds).completeZeroCashRegistrationRefund(context);
        verify(f.refunds, never()).saveRefund(any());
        verify(f.refunds, never()).savePreparedRegistrationRefund(any(), any(), any());
        verifyNoInteractions(f.payouts);
    }

    @Test
    void systemReplacementPassesOldIdAndGeneratedNewIdWithoutReleaseGap() {
        Fixture f = fixture(); CmsRegistration root = row(99L, "10.00"), child = row(100L, "0.00");
        root.setParentOrderNo("P"); child.setParentOrderNo("P");
        when(f.registrations.selectByParentOrderNoForUpdate("P", 20L)).thenReturn(List.of(root, child));
        when(f.registrations.claimRegistrationRefunded(any(), any(), any())).thenReturn(1);
        RefundApplication old = new RefundApplication(); old.setId(31L); old.setStatus(0); old.setPayoutStatus(5);
        when(f.refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(List.of(old));
        when(f.refunds.selectRegistrationHistoryForUpdate(100L)).thenReturn(List.of());
        FreeExploreRefundContext context = new FreeExploreRefundContext(99L, new BigDecimal("10.00"), List.of(99L, 100L), Set.of(31L));
        when(f.refunds.prepareRegistrationFullRefund(99L, new BigDecimal("10.00"))).thenReturn(context);
        when(f.refunds.supersedePendingRegistrationRefunds(eq(99L), any())).thenAnswer(call -> { old.setStatus(2); return 1; });
        when(f.refunds.savePreparedRegistrationRefund(any(), eq(context), eq(31L))).thenAnswer(call -> {
            ((RefundApplication) call.getArgument(0)).setId(9007199254740993L); return 1;
        });
        Boolean refunded = ReflectionTestUtils.invokeMethod(f.team, "refundWholeOrder", root, "ticket", "cancel", 9L, true);
        assertEquals(Boolean.TRUE, refunded);
        var order = inOrder(f.refunds);
        order.verify(f.refunds).prepareRegistrationFullRefund(99L, new BigDecimal("10.00"));
        order.verify(f.refunds).supersedePendingRegistrationRefunds(eq(99L), any());
        order.verify(f.refunds).savePreparedRegistrationRefund(any(), eq(context), eq(31L));
        order.verify(f.refunds).reviewRefund(argThat(review -> Long.valueOf(9007199254740993L).equals(review.getId())), eq(0L));
        verify(f.refunds, never()).saveRefund(any());
        verify(f.refunds, never()).completeZeroCashRegistrationRefund(any());
    }

    private CmsRegistration row(long id, String cash) {
        CmsRegistration row = new CmsRegistration(); row.setId(id); row.setMemberId(9L); row.setOwnerType(2L); row.setOwnerId(20L);
        row.setPaymentStatus(2); row.setRegistrationStatus(2); row.setVerificationStatus(0);
        row.setWechatPaymentAmount(new BigDecimal(cash)); row.setPayableAmount(new BigDecimal(cash)); return row;
    }
    private Fixture fixture() {
        TeamGroupServiceImpl team = new TeamGroupServiceImpl();
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        IRefundService refunds = mock(IRefundService.class); IPerkLedgerService perks = mock(IPerkLedgerService.class);
        IRefundPayoutService payouts = mock(IRefundPayoutService.class);
        ReflectionTestUtils.setField(team, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(team, "refundService", refunds);
        ReflectionTestUtils.setField(team, "perkLedgerService", perks);
        ReflectionTestUtils.setField(team, "refundPayoutService", payouts);
        ReflectionTestUtils.setField(team, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        return new Fixture(team, registrations, refunds, perks, payouts);
    }
    private record Fixture(TeamGroupServiceImpl team, CmsRegistrationMapper registrations, IRefundService refunds,
                           IPerkLedgerService perks, IRefundPayoutService payouts) { }
}
