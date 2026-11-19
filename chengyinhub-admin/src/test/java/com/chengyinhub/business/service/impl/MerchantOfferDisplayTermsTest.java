package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FE-G5:供给的玩家可见条款(含在票内 / 另付说明 / 需预约)只收合法值,改动只落本商家的生效行。 */
class MerchantOfferDisplayTermsTest {

    private static MerchantChapterOffer offer(Long merchantId, int status) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(9L);
        o.setMerchantId(merchantId);
        o.setStatus(status);
        return o;
    }

    @Test
    void normalizeTrimsTextAndRejectsOutOfRangeFlags() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setIncluded(0);
        o.setExtraSpendText("  饮品另付  ");
        MerchantOfferServiceImpl.normalizeDisplayTerms(o);
        assertEquals("饮品另付", o.getExtraSpendText());
        o.setExtraSpendText("   ");
        MerchantOfferServiceImpl.normalizeDisplayTerms(o);
        assertNull(o.getExtraSpendText());
        o.setIncluded(2);
        assertThrows(IllegalStateException.class, () -> MerchantOfferServiceImpl.normalizeDisplayTerms(o));
        o.setIncluded(1);
        o.setReserveRequired(-1);
        assertThrows(IllegalStateException.class, () -> MerchantOfferServiceImpl.normalizeDisplayTerms(o));
        o.setReserveRequired(1);
        o.setExtraSpendText("长".repeat(MerchantOfferServiceImpl.EXTRA_SPEND_TEXT_MAX + 1));
        assertThrows(IllegalStateException.class, () -> MerchantOfferServiceImpl.normalizeDisplayTerms(o));
    }

    @Test
    void ownerUpdatesOwnLiveOfferOnly() {
        MerchantChapterOfferMapper mapper = mock(MerchantChapterOfferMapper.class);
        MerchantOfferServiceImpl svc = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(svc, "offerMapper", mapper);
        when(mapper.selectByIdForUpdate(9L)).thenReturn(offer(500L, 1));
        when(mapper.updateDisplayTerms(any())).thenReturn(1);

        svc.setDisplayTerms(9L, 500L, 0, " 酒水另付 ", null);

        ArgumentCaptor<MerchantChapterOffer> patch = ArgumentCaptor.forClass(MerchantChapterOffer.class);
        verify(mapper).updateDisplayTerms(patch.capture());
        assertEquals(500L, patch.getValue().getMerchantId());
        assertEquals(0, patch.getValue().getIncluded());
        assertEquals("酒水另付", patch.getValue().getExtraSpendText());
        assertEquals(0, patch.getValue().getReserveRequired());
    }

    @Test
    void foreignOrInactiveOfferIsRefused() {
        MerchantChapterOfferMapper mapper = mock(MerchantChapterOfferMapper.class);
        MerchantOfferServiceImpl svc = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(svc, "offerMapper", mapper);
        when(mapper.selectByIdForUpdate(9L)).thenReturn(offer(999L, 1));
        assertThrows(IllegalStateException.class, () -> svc.setDisplayTerms(9L, 500L, 1, null, 1));
        when(mapper.selectByIdForUpdate(9L)).thenReturn(offer(500L, 0));
        assertThrows(IllegalStateException.class, () -> svc.setDisplayTerms(9L, 500L, 1, null, 1));
        verify(mapper, never()).updateDisplayTerms(any());
    }

    @Test
    void lostRaceIsReportedNotSilentlyIgnored() {
        MerchantChapterOfferMapper mapper = mock(MerchantChapterOfferMapper.class);
        MerchantOfferServiceImpl svc = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(svc, "offerMapper", mapper);
        when(mapper.selectByIdForUpdate(9L)).thenReturn(offer(500L, 1));
        when(mapper.updateDisplayTerms(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> svc.setDisplayTerms(9L, 500L, 1, null, 0));
    }
}
