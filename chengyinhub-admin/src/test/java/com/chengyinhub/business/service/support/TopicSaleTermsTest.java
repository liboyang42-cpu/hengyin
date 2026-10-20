package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUDIT-P1:开售审核条款指纹 —— 改故事/文案不算条款变化,改价格/条款算。 */
class TopicSaleTermsTest {

    private static Map<String, Object> chapter(long id, String name, String blocks, String termsMode) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("id", id);
        c.put("name", name);
        c.put("description", "简介-" + name);
        c.put("blocksJson", blocks);
        c.put("recruitEnabled", 1);
        c.put("required", 1);
        c.put("termsMode", termsMode);
        c.put("maxMerchant", 1);
        return c;
    }

    private static Map<String, Object> ticket(long id, String price) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("id", id);
        t.put("name", "成人票");
        t.put("price", price);
        t.put("totalInventory", 20);
        t.put("refundRule", "开始前 24h 可退");
        return t;
    }

    private static Map<String, Object> sale(String ticketPrice, String selfPlayPrice, List<Map<String, Object>> chapters) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("topicId", 7L);
        m.put("productType", 4);
        m.put("startDate", 1000L);
        m.put("endDate", 2000L);
        m.put("saleEndAt", 1500L);
        m.put("selfPlay", 1);
        m.put("selfPlayPrice", selfPlayPrice);
        m.put("tickets", new ArrayList<Object>(Arrays.asList(ticket(501L, ticketPrice))));
        m.put("chapters", chapters);
        m.put("offers", new ArrayList<Object>());
        return m;
    }

    @Test
    void storyEdit_rebuiltChapterIdsAndTexts_isNotATermsChange() {
        Map<String, Object> before = TopicSaleTerms.of(sale("9.9", "5",
                Arrays.asList(chapter(11L, "第一章", "{\"beat\":\"旧\"}", "PERK"))));
        Map<String, Object> after = TopicSaleTerms.of(sale("9.9", "5",
                Arrays.asList(chapter(99L, "第一章·改", "{\"beat\":\"新故事\"}", "PERK"))));

        assertFalse(TopicSaleTerms.changed(before, after));
    }

    @Test
    void ticketPriceEdit_isATermsChange() {
        List<Map<String, Object>> chapters = Arrays.asList(chapter(11L, "第一章", "{}", "PERK"));
        assertTrue(TopicSaleTerms.changed(TopicSaleTerms.of(sale("9.9", "5", chapters)),
                TopicSaleTerms.of(sale("19.9", "5", chapters))));
    }

    @Test
    void selfPlayPriceEdit_isATermsChange() {
        List<Map<String, Object>> chapters = Arrays.asList(chapter(11L, "第一章", "{}", "PERK"));
        assertTrue(TopicSaleTerms.changed(TopicSaleTerms.of(sale("9.9", "5", chapters)),
                TopicSaleTerms.of(sale("9.9", "8", chapters))));
    }

    @Test
    void chapterTermsModeEdit_isATermsChange() {
        assertTrue(TopicSaleTerms.changed(
                TopicSaleTerms.of(sale("9.9", "5", Arrays.asList(chapter(11L, "第一章", "{}", "PERK")))),
                TopicSaleTerms.of(sale("9.9", "5", Arrays.asList(chapter(11L, "第一章", "{}", "REVSHARE"))))));
    }

    @Test
    void unknownSide_isTreatedAsChanged_failSafe() {
        Map<String, Object> known = TopicSaleTerms.of(sale("9.9", "5", new ArrayList<Map<String, Object>>()));
        assertTrue(TopicSaleTerms.changed(null, known));
        assertTrue(TopicSaleTerms.changed(known, null));
    }
}
