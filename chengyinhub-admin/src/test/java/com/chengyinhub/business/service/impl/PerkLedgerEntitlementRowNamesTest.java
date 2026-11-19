package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FE-G6:一章恰好一家生效供给才给「店名 · 权益名」,多家或缺店名时留给调用方退回章节名。 */
class PerkLedgerEntitlementRowNamesTest {

    private static MerchantChapterOffer offer(long chapterId, String store, String perk) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(chapterId);
        o.setMerchantName(store);
        o.setPerkName(perk);
        return o;
    }

    @Test
    void namesOnlyUnambiguousChapters() {
        Map<Long, String> names = PerkLedgerServiceImpl.rowNamesOf(List.of(
                offer(1L, "转角咖啡", "美式一杯"),
                offer(2L, "书店", null),
                offer(3L, "甲店", "券"), offer(3L, "乙店", "券"),
                offer(4L, " ", "券")));
        assertEquals(Map.of(1L, "转角咖啡 · 美式一杯", 2L, "书店"), names);
    }
}
