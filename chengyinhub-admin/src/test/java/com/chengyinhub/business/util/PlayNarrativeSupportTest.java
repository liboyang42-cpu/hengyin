package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopicNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class PlayNarrativeSupportTest {

    private CmsTopicNode node(Long id, String name, String desc, String frag) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id); n.setName(name); n.setDescription(desc); n.setFragmentText(frag);
        return n;
    }

    @Test
    void assemblesFragmentsInOrderWithFallbackAndOpener() {
        CmsTopicNode a = node(10L, "老茶馆", "她最后一次出现在这里", "茶馆的碎片");
        CmsTopicNode b = node(20L, "老码头", "潮水退了才看得见", null); // frag 空 → 回退 description
        Map<String, Object> r = PlayNarrativeSupport.buildEnding(Arrays.asList(a, b));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frags = (List<Map<String, Object>>) r.get("fragments");
        assertEquals(2, frags.size());
        assertEquals("老茶馆", frags.get(0).get("name"));
        assertEquals(1, ((Integer) frags.get(0).get("step")).intValue());
        assertEquals("茶馆的碎片", frags.get(0).get("text"));
        assertEquals("潮水退了才看得见", frags.get(1).get("text")); // 回退
        assertEquals(2, ((Integer) frags.get(1).get("step")).intValue());
        String opener = (String) r.get("opener");
        assertNotNull(opener);
        assertTrue(opener.contains("老茶馆") && opener.contains("老码头")); // 引用首/末节点
    }

    @Test
    void emptyWhenNoCompleted() {
        Map<String, Object> r = PlayNarrativeSupport.buildEnding(Collections.emptyList());
        assertEquals("", r.get("opener"));
        assertTrue(((List<?>) r.get("fragments")).isEmpty());
    }

    // ===== S3: pickExploreSlot =====

    @Test
    void pickExploreSlot_totalZeroOrNegative_fallsBackToFirst() {
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_FIRST, PlayNarrativeSupport.pickExploreSlot(0, 0));
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_FIRST, PlayNarrativeSupport.pickExploreSlot(5, 0));
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_FIRST, PlayNarrativeSupport.pickExploreSlot(0, -1));
    }

    @Test
    void pickExploreSlot_justStarted_returnsFirst() {
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_FIRST, PlayNarrativeSupport.pickExploreSlot(0, 10));
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_FIRST, PlayNarrativeSupport.pickExploreSlot(3, 10)); // 0.3 < 1/3
    }

    @Test
    void pickExploreSlot_firstThirdBoundary_goesToMiddleNotFirst() {
        // done/total == 1/3 exactly -> not strictly < 1/3 -> middle
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_MIDDLE, PlayNarrativeSupport.pickExploreSlot(1, 3));
    }

    @Test
    void pickExploreSlot_midway_returnsMiddle() {
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_MIDDLE, PlayNarrativeSupport.pickExploreSlot(4, 10)); // 0.4
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_MIDDLE, PlayNarrativeSupport.pickExploreSlot(6, 10)); // 0.6
    }

    @Test
    void pickExploreSlot_twoThirdsBoundary_goesToLastNotMiddle() {
        // done/total == 2/3 exactly -> not strictly < 2/3 -> last
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_LAST, PlayNarrativeSupport.pickExploreSlot(2, 3));
    }

    @Test
    void pickExploreSlot_nearlyDone_returnsLast() {
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_LAST, PlayNarrativeSupport.pickExploreSlot(7, 10));
        assertEquals(PlayNarrativeSupport.SLOT_EXPLORE_LAST, PlayNarrativeSupport.pickExploreSlot(10, 10));
    }

    // ===== S4: computeOpenStatus 营业态三态 =====

    private static int hm(int h, int m) { return h * 60 + m; }

    @Test
    void computeOpenStatus_openNow_whenInsideAndFarFromClose() {
        // 09:00-22:00,当前 14:00,距打烊 8 小时 → 营业中
        assertEquals("营业中", PlayNarrativeSupport.computeOpenStatus("09:00-22:00", hm(14, 0)));
    }

    @Test
    void computeOpenStatus_closingSoon_whenWithinThreshold() {
        // 09:00-22:00,当前 21:40,距打烊 20 分钟(≤30)→ 即将打烊
        assertEquals("即将打烊", PlayNarrativeSupport.computeOpenStatus("09:00-22:00", hm(21, 40)));
        // 边界:距打烊恰好 30 分钟仍判即将打烊
        assertEquals("即将打烊", PlayNarrativeSupport.computeOpenStatus("09:00-22:00", hm(21, 30)));
        // 距打烊 31 分钟 → 营业中
        assertEquals("营业中", PlayNarrativeSupport.computeOpenStatus("09:00-22:00", hm(21, 29)));
    }

    @Test
    void computeOpenStatus_closed_whenOutsideWindow() {
        // 09:00-22:00,当前 08:00(未开)/ 23:00(已过)→ 已打烊
        assertEquals("已打烊", PlayNarrativeSupport.computeOpenStatus("09:00-22:00", hm(8, 0)));
        assertEquals("已打烊", PlayNarrativeSupport.computeOpenStatus("09:00-22:00", hm(23, 0)));
    }

    @Test
    void computeOpenStatus_emptyWhenNullOrUnparseable() {
        assertEquals("", PlayNarrativeSupport.computeOpenStatus(null, hm(12, 0)));
        assertEquals("", PlayNarrativeSupport.computeOpenStatus("", hm(12, 0)));
        assertEquals("", PlayNarrativeSupport.computeOpenStatus("全天营业", hm(12, 0)));
    }

    @Test
    void computeOpenStatus_crossMidnight_20to02() {
        // 20:00-02:00 跨午夜:23:30=营业中 / 01:50=即将打烊(距打烊10分) / 03:00=已打烊
        assertEquals("营业中", PlayNarrativeSupport.computeOpenStatus("20:00-02:00", hm(23, 30)));
        assertEquals("即将打烊", PlayNarrativeSupport.computeOpenStatus("20:00-02:00", hm(1, 50)));
        assertEquals("已打烊", PlayNarrativeSupport.computeOpenStatus("20:00-02:00", hm(3, 0)));
    }

    @Test
    void computeOpenStatus_acceptsAltSeparators() {
        // 波浪号 / 中文"至" 分隔符也应解析
        assertEquals("营业中", PlayNarrativeSupport.computeOpenStatus("09:00~22:00", hm(14, 0)));
        assertEquals("营业中", PlayNarrativeSupport.computeOpenStatus("09:00至22:00", hm(14, 0)));
    }
}
