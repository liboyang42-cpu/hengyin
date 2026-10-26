package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.FreeExploreRefundContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundF3PreparationTest {
    @Test
    void consumedUnitsRejectNewRefundBeforeInsertOrAnyApproval() {
        Fixture f = fixture();
        when(f.f3.prepareFullRefund(99L, new BigDecimal("100.00")))
                .thenThrow(new IllegalStateException("FORFEITED is consumed"));
        assertThrows(IllegalStateException.class, () -> f.service.saveRefund(request()));
        verify(f.refunds, never()).insertRefundApplication(any());
        verify(f.refunds, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
        verify(f.registrations, never()).claimRegistrationRefunded(any(), any(), any());
    }

    @Test
    void newIntentBindsExactGeneratedIdAfterInsertBeforeReturning() {
        Fixture f = fixture();
        RefundApplication request = request();
        assertEquals(1, f.service.saveRefund(request));
        assertEquals(Long.valueOf(9007199254740993L), request.getId());
        var order = inOrder(f.f3, f.refunds);
        order.verify(f.f3).prepareFullRefund(99L, new BigDecimal("100.00"));
        order.verify(f.refunds).insertRefundApplication(request);
        order.verify(f.f3).bindFullRefund(any(), eq(request.getId()), isNull());
    }

    @Test
    void missingWriteReceiptCannotBindOrApproveFreeze() {
        Fixture f = fixture();
        doReturn(0).when(f.refunds).insertRefundApplication(any());
        assertThrows(IllegalStateException.class, () -> f.service.saveRefund(request()));
        verify(f.f3, never()).bindFullRefund(any(), any(), any());
        verify(f.refunds, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
    }

    @Test
    void replacementReusesPreparedTransactionContextAndOriginalReplacementId() {
        Fixture f = fixture();
        FreeExploreRefundContext context = new FreeExploreRefundContext(99L, new BigDecimal("100.00"), List.of(99L), Set.of(31L));
        RefundApplication request = request();
        assertEquals(1, f.service.savePreparedRegistrationRefund(request, context, 31L));
        verify(f.f3).bindFullRefund(context, request.getId(), 31L);
        verify(f.f3, never()).prepareFullRefund(any(), any());
    }

    @Test
    void oldPendingApprovalBindsOriginalIntentBeforeApprovalCas() {
        Fixture f = fixture();
        RefundApplication pending = request();
        pending.setId(501L); pending.setStatus(0); pending.setPayoutStatus(5); pending.setDelFlag(0);
        when(f.refunds.selectRefundApplicationById(501L)).thenReturn(pending);
        when(f.refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(List.of(pending));
        when(f.refunds.updateRefundStatusIfPending(eq(501L), eq(1), eq(7L), any(), any())).thenReturn(1);
        assertEquals(1, f.service.reviewRefund(review(1), 7L));
        var order = inOrder(f.f3, f.refunds);
        order.verify(f.f3).prepareFullRefund(99L, new BigDecimal("100.00"));
        order.verify(f.f3).bindFullRefund(any(), eq(501L), isNull());
        order.verify(f.refunds).updateRefundStatusIfPending(eq(501L), eq(1), eq(7L), any(), any());
        verify(f.f3).onRegistrationRefundApproved(99L);
        verify(f.refunds, never()).insertRefundApplication(any());
    }

    @Test
    void penaltyBeforeOldApprovalLeavesItPendingAndDoesNotCancelRegistration() {
        Fixture f = fixture();
        RefundApplication pending = request(); pending.setId(501L); pending.setStatus(0);
        when(f.refunds.selectRefundApplicationById(501L)).thenReturn(pending);
        when(f.refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(List.of(pending));
        when(f.f3.prepareFullRefund(any(), any())).thenThrow(new IllegalStateException("FORFEITED"));
        assertThrows(IllegalStateException.class, () -> f.service.reviewRefund(review(1), 7L));
        verify(f.refunds, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
        verify(f.registrations, never()).claimRegistrationRefunded(any(), any(), any());
    }

    @Test
    void alreadyApprovedReplayDoesNotPrepareAnotherIntent() {
        Fixture f = fixture();
        RefundApplication approved = request(); approved.setId(501L); approved.setStatus(1);
        when(f.refunds.selectRefundApplicationById(501L)).thenReturn(approved);
        when(f.refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(List.of(approved));
        assertEquals(0, f.service.reviewRefund(review(1), 7L));
        verifyNoInteractions(f.f3);
        verify(f.refunds, never()).insertRefundApplication(any());
    }

    @Test
    void uncertainPendingDispatchCannotBeSuperseded() {
        Fixture f = fixture();
        RefundApplication pending = request(); pending.setId(501L); pending.setStatus(0); pending.setPayoutStatus(6);
        when(f.refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(List.of(pending));
        assertThrows(IllegalStateException.class, () -> f.service.supersedePendingRegistrationRefunds(99L, "cancel"));
        verify(f.refunds, never()).updateRefundStatusIfPending(any(), any(), any(), any(), any());
    }

    private RefundApplication request() {
        RefundApplication request = new RefundApplication();
        request.setSourceType("registration"); request.setSourceId(99L);
        request.setRefundAmount(new BigDecimal("100.00"));
        return request;
    }
    private RefundReviewDTO review(int status) {
        RefundReviewDTO review = new RefundReviewDTO(); review.setId(501L); review.setStatus(status);
        return review;
    }
    private Fixture fixture() {
        RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        IFreeExploreBundleRefundService f3 = mock(IFreeExploreBundleRefundService.class);
        CmsRegistration row = new CmsRegistration();
        row.setId(99L); row.setMemberId(9L); row.setOwnerType(2L); row.setOwnerId(20L);
        row.setPaymentStatus(2); row.setRegistrationStatus(2); row.setVerificationStatus(0);
        row.setPayableAmount(new BigDecimal("100.00")); row.setWechatPaymentAmount(new BigDecimal("100.00"));
        when(registrations.selectCmsRegistrationById(99L)).thenReturn(row);
        when(registrations.selectCmsRegistrationByIdForUpdate(99L)).thenReturn(row);
        when(registrations.claimRegistrationRefunded(any(), any(), any())).thenReturn(1);
        when(refunds.selectRegistrationHistoryForUpdate(99L)).thenReturn(List.of());
        when(refunds.insertRefundApplication(any())).thenAnswer(call -> {
            ((RefundApplication) call.getArgument(0)).setId(9007199254740993L); return 1;
        });
        when(f3.prepareFullRefund(any(), any())).thenAnswer(call ->
                new FreeExploreRefundContext(call.getArgument(0), call.getArgument(1), List.of(99L), Set.of()));
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "freeExploreBundleRefundService", f3);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "settlementDetailMapper", mock(SettlementDetailMapper.class));
        ReflectionTestUtils.setField(service, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(service, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(service, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        return new Fixture(service, refunds, registrations, f3);
    }
    private record Fixture(RefundServiceImpl service, RefundApplicationMapper refunds,
                           CmsRegistrationMapper registrations, IFreeExploreBundleRefundService f3) { }
}
