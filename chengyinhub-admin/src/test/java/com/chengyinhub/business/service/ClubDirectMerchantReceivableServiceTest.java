package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.ClubDirectMerchantReceivableMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubDirectMerchantReceivableServiceTest {
    ClubDirectMerchantReceivableMapper mapper;
    ISettlementClaimService claims;
    ITopicOperationsScopeAuthorizer scope;
    ClubMapper clubs;
    ClubDirectMerchantReceivableService service;
    Redemption redemption;
    MerchantChapterOffer offer;
    ClubDirectMerchantReceivable row;

    @BeforeEach void setup() {
        mapper = mock(ClubDirectMerchantReceivableMapper.class);
        claims = mock(ISettlementClaimService.class);
        scope = mock(ITopicOperationsScopeAuthorizer.class);
        clubs = mock(ClubMapper.class);
        service = new ClubDirectMerchantReceivableService(mapper, claims, scope, clubs);
        redemption = new Redemption(); redemption.setId(11L); redemption.setOfferId(21L);
        redemption.setTopicId(31L); redemption.setChapterId(41L); redemption.setMerchantId(51L);
        redemption.setStatus("ACTIVE");
        offer = new MerchantChapterOffer(); offer.setId(21L); offer.setTopicId(31L);
        offer.setChapterId(41L); offer.setMerchantId(51L); offer.setPayerClubId(61L);
        offer.setTermsMode("REVSHARE"); offer.setPerHeadFee(new BigDecimal("0.01"));
        when(claims.claim(any())).thenAnswer(call -> { SettlementClaim c = call.getArgument(0); c.setId(71L); return c; });
        when(mapper.insertReceivable(any())).thenReturn(1);
        row = new ClubDirectMerchantReceivable(); row.setId(81L); row.setRedemptionId(11L); row.setOfferId(21L);
        row.setTopicId(31L); row.setChapterId(41L); row.setPayerClubId(61L); row.setMerchantMemberId(51L);
        row.setPerHeadFeeSnapshot(new BigDecimal("0.01")); row.setStatus("OPEN");
    }

    @Test void accrualFreezesClubAndFeeAndClaimsDirectRoute() {
        service.accrue(redemption, offer);
        ArgumentCaptor<ClubDirectMerchantReceivable> ledger = ArgumentCaptor.forClass(ClubDirectMerchantReceivable.class);
        verify(mapper).insertReceivable(ledger.capture());
        assertEquals(61L, ledger.getValue().getPayerClubId());
        assertEquals(new BigDecimal("0.01"), ledger.getValue().getPerHeadFeeSnapshot());
        assertEquals(71L, ledger.getValue().getClaimId());
        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claims).claim(claim.capture()); assertEquals("CLUB_DIRECT", claim.getValue().getSettlementRoute());
    }

    @Test void retriedAccrualDoesNotCreateAnotherClaimOrRow() {
        when(mapper.selectByRedemptionForUpdate(11L)).thenReturn(row);
        service.accrue(redemption, offer);
        verify(mapper, never()).insertReceivable(any()); verifyNoInteractions(claims);
    }

    @Test void retriedAccrualCannotSwapDebtor() {
        row.setPayerClubId(62L); when(mapper.selectByRedemptionForUpdate(11L)).thenReturn(row);
        assertThrows(IllegalStateException.class, () -> service.accrue(redemption, offer));
        verifyNoInteractions(claims);
    }

    @Test void rejectedReversedOrMismatchedRedemptionCannotAccrue() {
        redemption.setStatus("REVERSED");
        assertThrows(IllegalStateException.class, () -> service.accrue(redemption, offer));
        redemption.setStatus("ACTIVE"); offer.setMerchantId(52L);
        assertThrows(IllegalStateException.class, () -> service.accrue(redemption, offer));
        verify(mapper, never()).insertReceivable(any());
    }

    @Test void invalidTermsOrFeeCannotAccrue() {
        offer.setTermsMode("PERK");
        assertThrows(IllegalStateException.class, () -> service.accrue(redemption, offer));
        offer.setTermsMode("REVSHARE"); offer.setPerHeadFee(new BigDecimal("-1"));
        assertThrows(IllegalStateException.class, () -> service.accrue(redemption, offer));
        offer.setPerHeadFee(null);
        assertThrows(IllegalStateException.class, () -> service.accrue(redemption, offer));
        verifyNoInteractions(claims);
    }

    @Test void reversalDoesNotReadCurrentOfferOrRewriteSnapshot() {
        row.setStatus("SETTLED"); when(mapper.selectByRedemptionForUpdate(11L)).thenReturn(row);
        when(mapper.reverse(row)).thenReturn(1); service.reverse(11L);
        verify(mapper).reverse(row); assertEquals(new BigDecimal("0.01"), row.getPerHeadFeeSnapshot());
        verifyNoInteractions(claims);
    }

    @Test void repeatedReversalIsNoOp() {
        row.setStatus("REFUND_DUE"); when(mapper.selectByRedemptionForUpdate(11L)).thenReturn(row);
        service.reverse(11L); verify(mapper, never()).reverse(any());
    }

    @Test void readSeparatesTwoPayerClubsForSameMerchant() {
        Club club = new Club(); club.setMemberId(91L); when(clubs.selectClubById(61L)).thenReturn(club);
        service.listForClub(31L, 61L, 91L); verify(mapper).selectScoped(31L, 61L, null);
        assertThrows(IllegalStateException.class, () -> service.listForClub(31L, 61L, 92L));
        service.listForMerchant(31L, 51L); verify(mapper).selectScoped(31L, null, 51L);
        assertThrows(IllegalStateException.class, () -> service.listForAdmin(31L));
    }

    @Test void writeScopeIsIndependentOfReadScope() {
        when(scope.canViewTopic(31L)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> record("voucher", new BigDecimal("0.01"), false));
        verify(mapper, never()).selectOwnedForUpdate(any(), any(), any(), any());
    }

    @Test void wholeLedgerStillRequiresFrozenPayerClubOrLoggedInMerchantOwner() {
        Club club = new Club(); club.setMemberId(91L); when(clubs.selectClubById(61L)).thenReturn(club);
        service.listForClub(null, 61L, 91L); verify(mapper).selectScoped(null, 61L, null);
        service.listForMerchant(null, 51L); verify(mapper).selectScoped(null, null, 51L);
        assertThrows(IllegalStateException.class, () -> service.listForClub(null, 61L, 92L));
        assertThrows(IllegalStateException.class, () -> service.listForClub(null, 62L, 91L));
        assertThrows(IllegalStateException.class, () -> service.listForMerchant(null, null));
        assertThrows(IllegalStateException.class, () -> service.listForMerchant(null, 0L));
        assertThrows(IllegalStateException.class, () -> service.listForAdmin(null));
        verify(mapper, never()).selectScoped(null, null, null);
    }

    @Test void paymentRequiresMatchingPartiesAmountAndVoucher() {
        when(scope.canEditTopic(31L)).thenReturn(true);
        when(mapper.selectOwnedForUpdate(81L, 31L, 61L, 51L)).thenReturn(row);
        assertThrows(IllegalStateException.class, () -> record("", new BigDecimal("0.01"), false));
        assertThrows(IllegalStateException.class, () -> record("v", BigDecimal.ZERO, false));
        assertThrows(IllegalStateException.class, () -> record("v", new BigDecimal("1.00"), false));
        assertThrows(IllegalStateException.class, () -> service.record(81L,31L,62L,51L,new BigDecimal("0.01"),"operator","v",false));
        verify(mapper, never()).recordSettled(any(), any(), any());
    }

    @Test void settledAndRefundedRecordSeparateFactsWithoutChangingFee() {
        when(scope.canEditTopic(31L)).thenReturn(true);
        when(mapper.selectOwnedForUpdate(81L, 31L, 61L, 51L)).thenReturn(row);
        when(mapper.recordSettled(row, "operator", "payment-1")).thenReturn(1);
        when(mapper.recordRefunded(row, "operator", "return-1")).thenReturn(1);
        assertTrue(record(" payment-1 ", new BigDecimal("0.01"), false));
        assertTrue(record("return-1", new BigDecimal("0.01"), true));
        assertEquals(new BigDecimal("0.01"), row.getPerHeadFeeSnapshot());
        verifyNoInteractions(claims);
    }

    private boolean record(String voucher, BigDecimal amount, boolean refund) {
        return service.record(81L,31L,61L,51L,amount,"operator",voucher,refund);
    }
}
