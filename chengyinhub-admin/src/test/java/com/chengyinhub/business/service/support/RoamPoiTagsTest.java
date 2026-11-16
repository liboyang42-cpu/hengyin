package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoamPoiTagsTest {
    @Test
    void normalizesCommaSeparatedAndJsonToOneJsonArrayFormat() {
        assertEquals("[\"夜间友好\",\"街区客厅\"]", RoamPoiTags.normalize("夜间友好, 街区客厅"));
        assertEquals("[\"a\",\"b\"]", RoamPoiTags.normalize("a，b、a"));
        assertEquals("[\"roam:park\",\"x\"]", RoamPoiTags.normalize("[\"roam:park\", \"x\"]"));
        assertEquals("[\"[broken\",\"a\"]", RoamPoiTags.normalize("[broken, a"), "坏 JSON 退回分隔符解析");
    }

    @Test
    void blankBecomesNullAndQuotesAreStripped() {
        assertNull(RoamPoiTags.normalize(null));
        assertNull(RoamPoiTags.normalize("  "));
        assertEquals("[\"ab\"]", RoamPoiTags.normalize("a\"b"));
    }

    @Test
    void dropsTrailingTagsToFit255() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("标签").append(i).append(',');
        String out = RoamPoiTags.normalize(sb.toString());
        assertEquals(true, out.length() <= RoamPoiTags.MAX_LENGTH);
        assertEquals(true, out.startsWith("[\"标签0\",\"标签1\""));
    }

    @Test
    void galleryHeadKeepsFirstThreeAndToleratesGarbage() {
        assertEquals("[\"1\",\"2\",\"3\"]", RoamPoiTags.galleryHead("[\"1\",\"2\",\"3\",\"4\"]", 3));
        assertEquals("[\"1\"]", RoamPoiTags.galleryHead("[\"1\"]", 3));
        assertNull(RoamPoiTags.galleryHead("", 3));
        assertNull(RoamPoiTags.galleryHead("[]", 3));
        assertNull(RoamPoiTags.galleryHead("oops", 3));
        assertNull(RoamPoiTags.galleryHead(null, 3));
    }
}
