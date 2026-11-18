package com.chengyinhub.business.service.f10;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.business.service.support.FreeExploreCanonicalDefaults;
import com.chengyinhub.business.util.TopicEditWindow;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** F10:编辑窗放行票价的条件、作者核销授权、自由探索 canonical 默认值。 */
class FreeExploreAuthorSupportTest {

    private static CmsTopic topic(int productType, int lifecycle, long owner) {
        CmsTopic t = new CmsTopic();
        t.setId(1L);
        t.setMemberId(owner);
        t.setProductType(productType);
        t.setLifecycle(lifecycle);
        t.setStartDate(new Date(1_800_000_000_000L));
        t.setEndDate(new Date(1_800_086_400_000L));
        return t;
    }

    @Test
    void topicEditWindowAllowsPriceOnlyForFreeExplorePricingAuthor() {
        assertTrue(TopicEditWindow.allowsFreeExplorePriceFill(topic(ProductType.FREE_EXPLORE, 2, 7L), 7L));
        assertFalse(TopicEditWindow.allowsFreeExplorePriceFill(topic(ProductType.FREE_EXPLORE, 1, 7L), 7L),
                "招募中票价区置灰,不放行");
        assertFalse(TopicEditWindow.allowsFreeExplorePriceFill(topic(ProductType.FREE_EXPLORE, 3, 7L), 7L));
        assertFalse(TopicEditWindow.allowsFreeExplorePriceFill(topic(ProductType.ORIENTEERING, 2, 7L), 7L));
        assertFalse(TopicEditWindow.allowsFreeExplorePriceFill(topic(ProductType.FREE_EXPLORE, 2, 7L), 8L));
        assertFalse(TopicEditWindow.allowsFreeExplorePriceFill(null, 7L));
    }

    private static MerchantChapterOffer offer(long merchantId, String kind, Long supplierMember, int status, Date validEnd) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setMerchantId(merchantId);
        o.setSupplierKind(kind);
        o.setSupplierMemberId(supplierMember);
        o.setStatus(status);
        o.setValidEnd(validEnd);
        return o;
    }

    @Test
    void authorCanRedeemOwnSelfSuppliedPerkOthersCannot() {
        PerkLedgerServiceImpl ledger = new PerkLedgerServiceImpl();
        MerchantChapterOfferMapper offers = mock(MerchantChapterOfferMapper.class);
        ReflectionTestUtils.setField(ledger, "offerMapper", offers);
        Date future = new Date(System.currentTimeMillis() + 86_400_000L);
        when(offers.selectByTopic(1L)).thenReturn(Arrays.asList(
                offer(7L, MerchantChapterOffer.SUPPLIER_AUTHOR, 7L, 1, future),
                offer(9L, MerchantChapterOffer.SUPPLIER_MERCHANT, null, 1, null)));
        assertTrue(ledger.hasLiveAuthorSupply(1L, 7L));
        assertFalse(ledger.hasLiveAuthorSupply(1L, 9L), "商家行不走作者授权");
        assertFalse(ledger.hasLiveAuthorSupply(1L, 8L));
        when(offers.selectByTopic(2L)).thenReturn(Arrays.asList(
                offer(7L, MerchantChapterOffer.SUPPLIER_AUTHOR, 7L, 0, future),
                offer(7L, MerchantChapterOffer.SUPPLIER_AUTHOR, 7L, 1, new Date(System.currentTimeMillis() - 1000L))));
        assertFalse(ledger.hasLiveAuthorSupply(2L, 7L), "已撤回或已过期的自己承接不能核销");
        assertFalse(ledger.hasLiveAuthorSupply(null, 7L));
    }

    @Test
    void freeExploreCanonicalDefaultsPassDraftValidation() {
        TopicSaleCanonicalV1 draft = FreeExploreCanonicalDefaults.draft(topic(ProductType.FREE_EXPLORE, 2, 7L), 80L);
        draft.validateDraft();
        assertEquals(1, draft.getParticipationUnitPolicy().path("adultCount").asInt());
        assertEquals(FreeExploreCanonicalDefaults.VENUE_RESPONSIBLE,
                draft.getSessionVenues().path("responsibleParty").asText());
        assertEquals(FreeExploreCanonicalDefaults.NONE, draft.getClubWorkPackage().path("contractRef").asText());
        assertEquals(FreeExploreCanonicalDefaults.OFFLINE_SETTLEMENT,
                draft.getRelationshipPolicy().path("merchantSettlement").asText());
        assertFalse(draft.getRefundReschedulePolicy().path("crossEditionTransferAllowed").asBoolean(true));
        assertFalse(draft.requiresFourStoreContract(), "自由探索默认值不得落入四店探店日合同");
    }
}
