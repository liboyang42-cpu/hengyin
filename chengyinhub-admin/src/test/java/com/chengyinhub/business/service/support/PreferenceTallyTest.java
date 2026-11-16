package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferenceTallyTest {
    private static final String CONFIG = "{\"dimensions\":[\"aroma\",\"texture\",\"look\"],"
            + "\"results\":{\"aroma\":{\"title\":\"香气派\"},\"texture\":{\"title\":\"口感派\"},\"look\":{\"title\":\"颜值派\"}}}";

    @Test
    void listsEveryAuthorResultInOrderWithCountsAndShares() {
        Map<String, Object> out = PreferenceTally.summarize(CONFIG, Arrays.asList(row("texture", 3), row("aroma", 1)));
        assertEquals(4L, out.get("total"));
        assertTrue((Boolean) out.get("enoughResponses"));
        List<?> results = (List<?>) out.get("results");
        assertEquals(3, results.size(), "没人选的结果也列出来");
        assertEquals("aroma", ((Map<?, ?>) results.get(0)).get("resultCode"));
        assertEquals("香气派", ((Map<?, ?>) results.get(0)).get("title"));
        assertEquals(1L, ((Map<?, ?>) results.get(0)).get("responses"));
        assertEquals(75L, ((Map<?, ?>) results.get(1)).get("percent"));
        assertEquals(0L, ((Map<?, ?>) results.get(2)).get("responses"));
    }

    @Test
    void fewerThanThreeAnswersOnlyReportTheTotal() {
        Map<String, Object> out = PreferenceTally.summarize(CONFIG, Arrays.asList(row("texture", 2)));
        assertEquals(2L, out.get("total"));
        assertFalse((Boolean) out.get("enoughResponses"));
        assertTrue(((List<?>) out.get("results")).isEmpty(), "两个人的选择不能按人认出来");
    }

    @Test
    void retiredResultCodesFallIntoOther() {
        Map<String, Object> out = PreferenceTally.summarize(CONFIG, Arrays.asList(row("aroma", 2), row("price", 2)));
        List<?> results = (List<?>) out.get("results");
        Map<?, ?> last = (Map<?, ?>) results.get(results.size() - 1);
        assertEquals("other", last.get("resultCode"));
        assertEquals(2L, last.get("responses"));
    }

    private static Map<String, Object> row(String code, long n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resultCode", code);
        row.put("responses", n);
        return row;
    }
}
