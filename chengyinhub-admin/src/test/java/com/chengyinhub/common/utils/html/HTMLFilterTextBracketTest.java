package com.chengyinhub.common.utils.html;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HTMLFilterTextBracketTest {
    @Test void plainComparisonSignsAndEmptyBracketsRemainExact() {
        HTMLFilter filter = new HTMLFilter();
        for (String text : new String[]{"人数<10 且 >5", "< >", "a < b && c > d", "数量<10", ">5", "<你好>"}) {
            assertEquals(text, filter.filter(text));
        }
    }

    @Test void scriptTagsAndDangerousAttributesAndProtocolsStillCannotPass() {
        HTMLFilter filter = new HTMLFilter();
        for (String text : new String[]{"<script>alert(1)</script>", "<svg/onload=alert(1)>",
                "<img src=x onerror=alert(1)>", "<a href='javascript:alert(1)'>link</a>"}) {
            String clean = filter.filter(text).toLowerCase(java.util.Locale.ROOT);
            assertFalse(clean.contains("<script"), clean);
            assertFalse(clean.contains("onerror"), clean);
            assertFalse(clean.contains("onload"), clean);
            assertFalse(clean.contains("javascript:"), clean);
        }
        assertEquals("alert(1) 测试 & < >", filter.filter("<script>alert(1)</script> 测试 & < >"));
    }
}
