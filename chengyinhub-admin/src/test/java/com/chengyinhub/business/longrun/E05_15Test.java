package com.chengyinhub.business.longrun;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Real normalize/serialize/normalizeStored path, not a replacement text helper. */
class E05_15Test {
    private static final long TS = 7_200_001L;
    private static final String EMOJI = "\uD83D\uDE00";
    private final RoamSharePrivacyService privacy = new RoamSharePrivacyService();

    @Test
    void crossingFortyAndSixteenUnitBoundariesDropsOnlyTheSplitPair() {
        assertPaths(bmp(39) + EMOJI + "尾", bmp(15) + EMOJI + "尾", bmp(39), bmp(15));
    }

    @Test
    void completePairsInsideOrExactlyAtTheBudgetRemainIntact() {
        assertPaths(bmp(38) + EMOJI + "尾", bmp(14) + EMOJI + "尾", bmp(38) + EMOJI, bmp(14) + EMOJI);
        assertPaths(bmp(38) + EMOJI, bmp(14) + EMOJI, bmp(38) + EMOJI, bmp(14) + EMOJI);
        assertPaths(bmp(37) + EMOJI, bmp(13) + EMOJI, bmp(37) + EMOJI, bmp(13) + EMOJI);
    }

    @Test
    void existingControlRemovalAndTrimHappenBeforePairBoundarySelection() {
        assertPaths(" \n" + bmp(39) + "\t" + EMOJI + "尾\r ",
                " \n" + bmp(15) + "\t" + EMOJI + "尾\r ", bmp(39), bmp(15));
    }

    @Test
    void bmpBudgetsAndOriginalDefaultsRemainUnchanged() {
        assertPaths(bmp(41), bmp(17), bmp(40), bmp(16));
        JSONObject empty = privacy.normalize(TS, "{\"routeName\":\" \",\"time\":\"bad\",\"pois\":[{}]}");
        assertEquals("城市漫游足迹", empty.getString("routeName"));
        assertEquals("—", empty.getString("time"));
        assertEquals("landmark", empty.getJSONArray("pois").getJSONObject(0).getString("cat"));
        assertEquals(empty, privacy.normalizeStored(empty.toJSONString()));
    }

    private void assertPaths(String forty, String sixteen, String expectedForty, String expectedSixteen) {
        JSONObject raw = new JSONObject();
        raw.put("ts", TS);
        raw.put("routeName", forty); raw.put("medal", forty); raw.put("shopMedalName", forty);
        JSONObject poi = new JSONObject(); poi.put("name", forty); poi.put("catLabel", sixteen); poi.put("cat", "landmark");
        JSONArray pois = new JSONArray(); pois.add(poi); raw.put("pois", pois);
        JSONObject published = privacy.normalize(TS, raw.toJSONString());
        JSONObject reread = privacy.normalizeStored(published.toJSONString());
        JSONObject historicalRead = privacy.normalizeStored(raw.toJSONString());
        for (JSONObject view : new JSONObject[]{published, reread, historicalRead}) {
            for (String field : new String[]{"routeName", "medal", "shopMedalName"}) {
                assertEquals(expectedForty, view.getString(field), field);
                assertValidUtf16(view.getString(field), 40);
            }
            JSONObject item = view.getJSONArray("pois").getJSONObject(0);
            assertEquals(expectedForty, item.getString("name"));
            assertEquals(expectedSixteen, item.getString("catLabel"));
            assertValidUtf16(item.getString("name"), 40);
            assertValidUtf16(item.getString("catLabel"), 16);
        }
        assertEquals(published, reread, "Stored normalization must remain idempotent for these legal timestamps");
    }

    private static String bmp(int count) {
        char[] chars = new char[count]; Arrays.fill(chars, '路'); return new String(chars);
    }
    private static void assertValidUtf16(String text, int budget) {
        assertTrue(text.length() <= budget);
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)));
                i++;
            } else assertFalse(Character.isLowSurrogate(value));
        }
    }
}
