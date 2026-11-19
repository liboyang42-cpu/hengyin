package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** FE-G4:自由探索主题详情逐店卡片只放玩家可见字段,只收必选位置的承接。 */
class FreeExploreMerchantCardsTest {

    @Test
    void cardsCarryStorePerkTermsAndValidityButNoSettlementFields() {
        Date validEnd = new Date(1_900_000_000_000L);
        MerchantChapterOffer a = new MerchantChapterOffer();
        a.setId(1L); a.setChapterId(10L); a.setMerchantId(500L); a.setMerchantName("转角咖啡");
        a.setPerkName("美式一杯"); a.setIncluded(0); a.setExtraSpendText("甜点另付"); a.setReserveRequired(1);
        a.setValidEnd(validEnd);
        MerchantChapterOffer legacy = new MerchantChapterOffer();
        legacy.setId(2L); legacy.setChapterId(11L); legacy.setMerchantName("书店");
        MerchantChapterOffer optional = new MerchantChapterOffer();
        optional.setId(3L); optional.setChapterId(99L); optional.setMerchantName("可选章");

        List<Map<String, Object>> cards = FreeExploreBundleServiceImpl.merchantCards(
                List.of(a, legacy, optional), Map.of(10L, "第一站", 11L, "第二站"));

        assertEquals(2, cards.size(), "只收必选位置");
        Map<String, Object> first = cards.get(0);
        assertEquals("第一站", first.get("chapterName"));
        assertEquals("转角咖啡", first.get("storeName"));
        assertEquals("美式一杯", first.get("perkName"));
        assertEquals(Boolean.FALSE, first.get("included"));
        assertEquals("甜点另付", first.get("extraSpendText"));
        assertEquals(Boolean.TRUE, first.get("reserveRequired"));
        assertEquals(validEnd, first.get("validEnd"));
        assertFalse(first.containsKey("merchantId"));
        assertFalse(first.containsKey("perHeadFee"));
        assertNull(cards.get(1).get("included"), "未声明的保持 null,不替商家下结论");
        assertEquals(Boolean.FALSE, cards.get(1).get("reserveRequired"));
    }
}
