package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A's owner selection only. No provider verification, G allocation or transaction proof here. */
class RefundOriginalComponentOwnerTest {
    private RefundServiceImpl service;
    private RefundApplicationMapper refunds;
    private CmsRegistrationMapper registrations;
    private SettlementDetailMapper details;
    private ICoopSettlementClaimService owner;
    private RefundApplication application;

    @BeforeEach void fixtures() {
        service = new RefundServiceImpl(); refunds = mock(RefundApplicationMapper.class);
        registrations = mock(CmsRegistrationMapper.class); details = mock(SettlementDetailMapper.class);
        owner = mock(ICoopSettlementClaimService.class);
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "settlementDetailMapper", details);
        ReflectionTestUtils.setField(service, "coopSettlementClaimService", owner);
        ReflectionTestUtils.setField(service, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(service, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(service, "firstReviewPointsClawback", mock(FirstReviewPointsClawbackService.class));
        application = new RefundApplication(); application.setId(9L); application.setSourceType("registration");
        application.setSourceId(1L); application.setRefundNo("RF1"); application.setRefundAmount(new BigDecimal("30.00"));
        application.setPaidAmount(new BigDecimal("30.00")); application.setStatus(0); application.setPayoutStatus(0);
        when(refunds.selectRefundApplicationById(9L)).thenReturn(application);
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(application));
        when(refunds.updateRefundStatusIfPending(eq(9L), anyInt(), eq(42L), any(), any())).thenReturn(1);
        CmsRegistration registration = new CmsRegistration(); registration.setId(1L); registration.setMemberId(11L);
        registration.setOwnerType(1L); registration.setOwnerId(7L); registration.setRegistrationNo("REG1");
        registration.setPaymentStatus(2); registration.setRegistrationStatus(2); registration.setVerificationStatus(0);
        registration.setPayableAmount(new BigDecimal("30.00"));
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(registration);
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(registration);
        when(registrations.claimRegistrationRefunded(eq(1L), any(), any())).thenReturn(1);
        CmsTopicMapper topics = mock(CmsTopicMapper.class); CmsTopic topic = new CmsTopic(); topic.setId(7L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
    }

    private RefundReviewDTO request(int status) {
        RefundReviewDTO request = new RefundReviewDTO(); request.setId(9L); request.setStatus(status); return request;
    }

    @Test void approvingOriginalComponentOrderCannotAlsoClawBackLegacyDetailsOrPretendChannelSuccess() {
        when(owner.hasFrozenOriginalRefundComponents(9L)).thenReturn(true);
        assertEquals(1, service.reviewRefund(request(1), 42L));
        var sequence = inOrder(registrations, owner, refunds, details);
        sequence.verify(registrations).selectCmsRegistrationByIdForUpdate(1L);
        sequence.verify(owner).hasFrozenOriginalRefundComponents(9L);
        sequence.verify(refunds).updateRefundStatusIfPending(eq(9L), eq(1), eq(42L), any(), any());
        verify(details, never()).selectSettledDetails(anyString(), anyLong());
        verify(details, never()).insertAdjustment(any());
        verify(details).voidPendingBySource("registration", 1L);
        verify(owner, never()).reverseCompletedRegistrationRefund(anyLong());
        verify(refunds, never()).updatePayoutResult(anyLong(), anyInt(), anyString());
    }

    @Test void explicitlyLegacyOrderKeepsItsOriginalDetailOwnerAtApproval() {
        when(owner.hasFrozenOriginalRefundComponents(9L)).thenReturn(false);
        when(details.selectSettledDetails("registration", 1L)).thenReturn(List.of());
        assertEquals(1, service.reviewRefund(request(1), 42L));
        verify(details).selectSettledDetails("registration", 1L);
        verify(owner, never()).reverseCompletedRegistrationRefund(anyLong());
    }

    @Test void missingOriginalComponentsCannotFallBackToLegacyDebitOrCommitApproval() {
        when(owner.hasFrozenOriginalRefundComponents(9L)).thenThrow(new IllegalStateException("ORIGINAL_COMPONENTS_MISSING"));
        assertThrows(IllegalStateException.class, () -> service.reviewRefund(request(1), 42L));
        verify(refunds, never()).updateRefundStatusIfPending(anyLong(), anyInt(), any(), any(), any());
        verify(registrations, never()).claimRegistrationRefunded(anyLong(), any(), any());
        verifyNoInteractions(details);
        verify(owner, never()).reverseCompletedRegistrationRefund(anyLong());
    }

    @Test void rejectionDoesNotSelectAFinancialOwnerOrReverseAnything() {
        assertEquals(1, service.reviewRefund(request(2), 42L));
        verifyNoInteractions(owner, details);
        verify(registrations, never()).claimRegistrationRefunded(anyLong(), any(), any());
    }

    @Test void manualSecondReviewerCannotBeTheOriginalHandlerEvenForANewOwner() {
        application.setPayoutStatus(3); application.setPayoutHandlerId(42L);
        assertThrows(IllegalStateException.class, () -> service.verifyManualPayout(9L, 42L));
        verify(refunds, never()).markPayoutVerified(anyLong(), anyLong(), any());
        verifyNoInteractions(owner);
    }
}
