package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FE-G5 本店权益展示条款与 FE-G7 perkOnly:只加字段,读失败只让新字段缺席。 */
class PerkDisplayTermsTest {

    private static CmsTopicNode node(long chapterId) {
        CmsTopicNode n = new CmsTopicNode();
        n.setChapterId(chapterId);
        return n;
    }

    @Test
    void termsAreKeyedByChapterAndMerchantAndRenderedAsBooleans() {
        MerchantChapterOfferMapper mapper = mock(MerchantChapterOfferMapper.class);
        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setChapterId(3L); offer.setMerchantId(500L);
        offer.setIncluded(0); offer.setExtraSpendText("饮品另付"); offer.setReserveRequired(1);
        when(mapper.selectLiveDisplayByChapterIds(List.of(3L))).thenReturn(List.of(offer));

        Map<String, MerchantChapterOffer> terms = PerkDisplayTerms.load(mapper, List.of(node(3L), node(3L)));
        Map<String, Object> perk = new LinkedHashMap<>();
        PerkDisplayTerms.put(perk, terms.get("3:500"));
        assertEquals(Boolean.FALSE, perk.get("included"));
        assertEquals("饮品另付", perk.get("extraSpendText"));
        assertEquals(Boolean.TRUE, perk.get("reserveRequired"));
    }

    @Test
    void readFailureOnlyDropsTheNewFields() {
        MerchantChapterOfferMapper mapper = mock(MerchantChapterOfferMapper.class);
        when(mapper.selectLiveDisplayByChapterIds(anyList())).thenThrow(new RuntimeException("Unknown column 'included'"));
        Map<String, MerchantChapterOffer> terms = PerkDisplayTerms.load(mapper, List.of(node(3L)));
        assertTrue(terms.isEmpty());
        Map<String, Object> perk = new LinkedHashMap<>();
        perk.put("name", "美式一杯");
        PerkDisplayTerms.put(perk, terms.get("3:500"));
        assertEquals(Map.of("name", "美式一杯"), perk);
    }

    @Test
    void perkOnlyOnlyWhenEveryFreeExploreNodeHasNoGame() {
        Map<String, Object> noGame = Map.of("hasGame", false);
        Map<String, Object> game = Map.of("hasGame", true);
        Map<String, Object> unknown = Map.of("id", 1L);
        assertTrue(PerkDisplayTerms.perkOnly(2, List.of(noGame, noGame)));
        assertFalse(PerkDisplayTerms.perkOnly(2, List.of(noGame, game)));
        assertFalse(PerkDisplayTerms.perkOnly(2, List.of(noGame, unknown)), "未算 hasGame 的节点不当作无游戏");
        assertFalse(PerkDisplayTerms.perkOnly(1, List.of(noGame)), "经典定向恒为游戏");
        assertNull(PerkDisplayTerms.perkOnly(2, List.of()));
    }
}
