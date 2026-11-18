package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F12:亲子票一次核销,回执带 party(构成 + 儿童份说明);非亲子票不带,也不多查供给。 */
class PerkLedgerFamilyRedeemTest {
    private static final String FAMILY = "{\"adultCount\":1,\"childCount\":1,\"childAge\":7}";

    private MerchantChapterOfferMapper offerMapper;
    private PerkLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MerchantChapterOfferMapper.class);
        service = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(31L);
        offer.setMerchantId(41L);
        offer.setStatus(1);
        offer.setFamilyAccepted(1);
        offer.setFamilyPerkText("儿童份小杯");
        when(offerMapper.selectByChapter(31L)).thenReturn(Collections.singletonList(offer));
    }

    @Test
    void familyRedemptionReturnsPartyWithChildPortion() {
        Map<String, Object> party = service.redemptionParty(FAMILY, 31L, 41L);
        assertEquals(1, party.get("adultCount"));
        assertEquals(1, party.get("childCount"));
        assertEquals(7, party.get("childAge"));
        assertEquals("儿童份小杯", party.get("familyPerkText"));
        assertEquals("亲子票：1 大 1 小（儿童 7 岁）· 儿童份：儿童份小杯", party.get("label"));
    }

    @Test
    void plainTicketsCarryNoPartyAndSkipTheOfferLookup() {
        assertNull(service.redemptionParty(null, 31L, 41L));
        assertNull(service.redemptionParty("{\"adultCount\":1,\"childCount\":0}", 31L, 41L));
        verify(offerMapper, never()).selectByChapter(anyLong());
    }

    @Test
    void anotherMerchantsOfferIsNeverUsedForTheChildPortion() {
        Map<String, Object> party = service.redemptionParty(FAMILY, 31L, 99L);
        assertNull(party.get("familyPerkText"));
        assertEquals("亲子票：1 大 1 小（儿童 7 岁）", party.get("label"));
    }
}
