package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExploreScanReplayCandidateTest {
    private PerkLedgerServiceImpl service;
    private MerchantChapterOfferMapper offers;
    private RedemptionMapper receipts;
    private RegistrationEntitlementMapper entitlements;
    @BeforeEach void setup() {
        service = new PerkLedgerServiceImpl();
        offers = mock(MerchantChapterOfferMapper.class);
        receipts = mock(RedemptionMapper.class);
        entitlements = mock(RegistrationEntitlementMapper.class);
        ReflectionTestUtils.setField(service, "offerMapper", offers);
        ReflectionTestUtils.setField(service, "redemptionMapper", receipts);
        ReflectionTestUtils.setField(service, "entitlementMapper", entitlements);
    }
    private RegistrationEntitlement entitlement(long chapter, int status) {
        RegistrationEntitlement row = new RegistrationEntitlement();
        row.setRegistrationId(9L); row.setTopicId(7L); row.setChapterId(chapter); row.setStatus(status); return row;
    }
    private Redemption receipt(long chapter) {
        Redemption row = new Redemption();
        row.setId(66L); row.setRegistrationId(9L); row.setTopicId(7L); row.setChapterId(chapter);
        row.setMerchantId(5L); row.setStatus("ACTIVE"); return row;
    }
    @Test void pendingPlusRealSameMerchantReceiptRemainDistinctSortedChoices() {
        MerchantChapterOffer offer = new MerchantChapterOffer(); offer.setMerchantId(5L); offer.setChapterId(12L);
        when(offers.selectByTopic(7L)).thenReturn(Collections.singletonList(offer));
        when(entitlements.selectByRegistration(9L)).thenReturn(Arrays.asList(entitlement(11, 1), entitlement(12, 0)));
        when(receipts.selectActiveByRegistration(9L)).thenReturn(Collections.singletonList(receipt(11)));
        Map<Long, Long> candidates = service.resolveScannableChapters(9L, 7L, 5L);
        assertEquals(Arrays.asList(11L, 12L), new java.util.ArrayList<>(candidates.keySet()));
        assertEquals(66L, candidates.get(11L)); assertTrue(candidates.containsKey(12L)); assertNull(candidates.get(12L));
    }
    @Test void foreignMerchantForeignTopicReversedAndInvalidEntitlementsCannotBecomeReplayChoices() {
        RegistrationEntitlement redeemed = entitlement(11, 1);
        when(entitlements.selectByRegistration(9L)).thenReturn(Collections.singletonList(redeemed));
        for (int condition = 0; condition < 4; condition++) {
            Redemption row = receipt(11);
            if (condition == 0) row.setMerchantId(6L);
            if (condition == 1) row.setTopicId(8L);
            if (condition == 2) row.setStatus("REVERSED");
            if (condition == 3) redeemed.setStatus(2);
            when(receipts.selectActiveByRegistration(9L)).thenReturn(Collections.singletonList(row));
            assertTrue(service.resolveScannableChapters(9L, 7L, 5L).isEmpty());
        }
    }
    @Test void lockedReplayReadsOriginalReceiptAndCurrentRedeemedEntitlementWithoutMutations() {
        when(receipts.selectActiveByIdempotencyKeyForUpdate("redeem:9:11:5")).thenReturn(receipt(11));
        when(entitlements.selectByRegistrationForUpdate(9L)).thenReturn(Collections.singletonList(entitlement(11, 1)));
        assertEquals(66L, service.findReplayableChapterRedemptionIdForUpdate(9L, 7L, 11L, 5L));
        org.mockito.InOrder locks = inOrder(receipts, entitlements);
        locks.verify(receipts).selectActiveByIdempotencyKeyForUpdate("redeem:9:11:5");
        locks.verify(entitlements).selectByRegistrationForUpdate(9L);
        verify(entitlements, never()).markRedeemed(any(), any());
        verify(offers, never()).consumeQuota(any());
        verify(receipts, never()).insertRedemption(any());
    }
    @Test void mismatchedActiveReceiptOrUnredeemedEntitlementCannotFallThroughToNewGrant() {
        when(receipts.selectActiveByIdempotencyKeyForUpdate("redeem:9:11:5")).thenReturn(receipt(11));
        for(int status : new int[]{0, 2}) {
            when(entitlements.selectByRegistrationForUpdate(9L)).thenReturn(Collections.singletonList(entitlement(11, status)));
            assertThrows(IllegalStateException.class, () -> service.findReplayableChapterRedemptionIdForUpdate(9L, 7L, 11L, 5L));
        }
        Redemption foreign = receipt(11); foreign.setTopicId(8L);
        when(receipts.selectActiveByIdempotencyKeyForUpdate("redeem:9:11:5")).thenReturn(foreign);
        assertThrows(IllegalStateException.class, () -> service.findReplayableChapterRedemptionIdForUpdate(9L, 7L, 11L, 5L));
    }
}
