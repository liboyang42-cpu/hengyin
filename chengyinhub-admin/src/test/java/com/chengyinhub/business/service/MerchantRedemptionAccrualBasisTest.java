package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantRedemptionAccrualBasisTest {
    private static final class Fixture {
        final MerchantFinanceQueryService service = new MerchantFinanceQueryService();
        final RedemptionMapper redemptions = mock(RedemptionMapper.class);
        final MerchantPayableMapper payables = mock(MerchantPayableMapper.class);
        final MerchantPayableBatchMapper batches = mock(MerchantPayableBatchMapper.class);
        final MerchantChapterOfferMapper offers = mock(MerchantChapterOfferMapper.class);
        final Redemption redemption = new Redemption();
        final MerchantChapterOffer offer = new MerchantChapterOffer();
        Fixture(List<MerchantPayable> facts) {
            redemption.setId(31L); redemption.setMerchantId(7L); redemption.setStatus("ACTIVE");
            redemption.setOfferId(41L); redemption.setChapterId(51L); redemption.setTopicId(61L);
            redemption.setCreateTime(new Date(1787220000000L));
            offer.setId(41L); offer.setTermsMode("REVSHARE"); offer.setPerHeadFee(new BigDecimal("999.00"));
            ReflectionTestUtils.setField(service, "redemptionMapper", redemptions);
            ReflectionTestUtils.setField(service, "payableMapper", payables);
            ReflectionTestUtils.setField(service, "batchMapper", batches);
            ReflectionTestUtils.setField(service, "offerMapper", offers);
            when(redemptions.selectByMerchantAndId(7L, 31L)).thenReturn(redemption);
            when(redemptions.selectByMerchant(7L)).thenReturn(Collections.singletonList(redemption));
            when(payables.selectByMerchant(7L)).thenReturn(facts);
            when(batches.selectByMerchant(7L)).thenReturn(Collections.<MerchantPayableBatch>emptyList());
            when(offers.selectById(41L)).thenReturn(offer);
        }
        MerchantRedemptionView detail() { return service.redemptionDetail(7L, "redemption", "31"); }
    }
    private static MerchantPayable accrual() {
        MerchantPayable fact = new MerchantPayable();
        fact.setId(11L); fact.setClaimId(71L); fact.setEventKey("ORIGIN");
        fact.setMerchantMemberId(7L); fact.setRedemptionId(31L);
        fact.setEntryType("ACCRUAL"); fact.setIdempotencyKey("accrual:31");
        fact.setPerHeadFeeSnapshot(new BigDecimal("12.00")); fact.setAmount(new BigDecimal("12.00"));
        fact.setCreateTime(new Date(1787220000000L));
        return fact;
    }
    private static void noBasis(MerchantRedemptionView row) {
        assertNotNull(row); assertNull(row.getUnitFee()); assertNull(row.getHeadCount());
    }
    @Test void detailUsesFrozenOriginalSnapshotRatherThanCurrentOfferRate() {
        Fixture f = new Fixture(Collections.singletonList(accrual()));
        MerchantRedemptionView row = f.detail();
        assertEquals("12.00", row.getUnitFee()); assertEquals(Integer.valueOf(1), row.getHeadCount());
        assertEquals("12.00", row.getSettlementAmount()); assertEquals("PENDING", row.getSettlementState());
        assertNull(row.getPublicSettlement());
        verify(f.redemptions).selectByMerchantAndId(7L, 31L); verify(f.payables).selectByMerchant(7L);
        verify(f.batches).selectByMerchant(7L);
        verifyNoMoreInteractions(f.redemptions, f.payables, f.batches);
    }
    @Test void listAndDetailShareTheSameProvenBasis() {
        Fixture f = new Fixture(Collections.singletonList(accrual()));
        MerchantRedemptionView detail = f.detail();
        MerchantRedemptionView listed = f.service.redemptions(7L, "all", 1, 20).getRows().get(0);
        assertEquals(detail.getUnitFee(), listed.getUnitFee());
        assertEquals(detail.getHeadCount(), listed.getHeadCount());
    }
    @Test void reversalNeverAddsAnotherPersonOrChangesOriginalRate() {
        MerchantPayable reversed = accrual(); reversed.setId(12L); reversed.setEntryType("REVERSAL");
        reversed.setEventKey("REVERSE-31"); reversed.setIdempotencyKey("reversal:31");
        Fixture f = new Fixture(Arrays.asList(accrual(), reversed));
        MerchantRedemptionView row = f.detail();
        assertEquals("12.00", row.getUnitFee()); assertEquals(Integer.valueOf(1), row.getHeadCount());
        assertEquals("0.00", row.getSettlementAmount());
        assertEquals("ADJUSTMENT_PENDING", row.getSettlementState());
    }
    @Test void duplicateAccrualsDoNotChooseAConvenientRate() {
        MerchantPayable duplicate = accrual(); duplicate.setId(12L);
        MerchantRedemptionView row = new Fixture(Arrays.asList(accrual(), duplicate)).detail();
        noBasis(row); assertEquals("24.00", row.getSettlementAmount());
    }
    @Test void legacyOrNonOriginFactsRemainUnspecified() {
        MerchantPayable legacy = accrual(); legacy.setClaimId(null);
        noBasis(new Fixture(Collections.singletonList(legacy)).detail());
        MerchantPayable adjustment = accrual(); adjustment.setEventKey("ADJUSTMENT");
        noBasis(new Fixture(Collections.singletonList(adjustment)).detail());
    }
    @Test void ownerMismatchCannotExposeAnAccrualBasis() {
        MerchantPayable foreign = accrual(); foreign.setMerchantMemberId(8L);
        noBasis(new Fixture(Collections.singletonList(foreign)).detail());
        Fixture f = new Fixture(Collections.singletonList(accrual())); f.redemption.setMerchantId(8L);
        noBasis(f.detail());
    }
    @Test void missingOrUnsupportedSnapshotDoesNotGetReconstructedFromAmount() {
        MerchantPayable missing = accrual(); missing.setPerHeadFeeSnapshot(null);
        noBasis(new Fixture(Collections.singletonList(missing)).detail());
        MerchantPayable fractional = accrual(); fractional.setPerHeadFeeSnapshot(new BigDecimal("0.123"));
        fractional.setAmount(new BigDecimal("0.123"));
        noBasis(new Fixture(Collections.singletonList(fractional)).detail());
        MerchantPayable twoPersons = accrual(); twoPersons.setAmount(new BigDecimal("24.00"));
        noBasis(new Fixture(Collections.singletonList(twoPersons)).detail());
    }
    @Test void noFactsAndNonCashRoutesNeverGetSyntheticBasis() {
        noBasis(new Fixture(Collections.<MerchantPayable>emptyList()).detail());
        for (String mode : Arrays.asList("PERK", "TRAFFIC")) {
            Fixture f = new Fixture(Collections.singletonList(accrual())); f.offer.setTermsMode(mode);
            MerchantRedemptionView row = f.detail(); noBasis(row); assertEquals("NONE", row.getSettlementRoute());
        }
    }
    @Test void financeDTOUsesStringMoneyAndNullableIntegerWithoutNewPayoutMetadata() throws Exception {
        MerchantRedemptionView row = new Fixture(Collections.singletonList(accrual())).detail();
        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().valueToTree(row);
        assertTrue(json.get("unitFee").isTextual()); assertEquals("12.00", json.get("unitFee").asText());
        assertTrue(json.get("headCount").isIntegralNumber()); assertEquals(1, json.get("headCount").asInt());
        assertTrue(json.get("publicSettlement").isNull()); assertNull(json.get("paidAt"));
    }
}
