package com.chengyinhub.common.utils.html;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HTMLFilterTextBracketTest {
    @Test void plainComparisonSignsAndEmptyBracketsRemainExact() {
        HTMLFilter filter = new HTMLFilter();
        for (String text : new String[]{"人数<10 且 >5", "< >", "a < b && c > d", "数量<10", ">5", "<你好>",
                "a <<< b", "<<10", "<<你好>>", "<<<"}) {
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

    /** 清洗删掉标签/注释后,文本 '<' 不得与后面的字母拼回一个新标签。 */
    @Test void strippedTagsCannotSpliceTextBracketsIntoNewTags() {
        HTMLFilter filter = new HTMLFilter();
        for (String text : new String[]{
                "<<script></script>img src=x onerror=alert(1)>",
                "<<b></b>img src=x onerror=alert(1)>",
                "<<!-- x -->script>alert(1)<</script>/script>",
                "<<i></i>/script>",
                "<<x></x>svg onload=alert(1)>"}) {
            String clean = filter.filter(text);
            assertFalse(java.util.regex.Pattern.compile("<[A-Za-z!?/]").matcher(clean).find(), text + " => " + clean);
        }
    }

    /** F-W5-12 端到端:发帖走 /api JSON 体,XSS 包装器读回后普通尖括号原样保留,脚本标签仍被剥离。 */
    @Test void jsonBodyThroughXssWrapperKeepsPlainBracketsAndStripsScript() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/club/post");
        request.setContentType("application/json");
        String body = "{\"contents\":\"<script>alert(1)</script> 测试一下特殊字符 & < > \\\" ' 人数<10 且 >5\"}";
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.chengyinhub.common.filter.XssHttpServletRequestWrapper wrapper =
                new com.chengyinhub.common.filter.XssHttpServletRequestWrapper(request);
        String json = new String(wrapper.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String contents = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("contents").asText();
        assertEquals("alert(1) 测试一下特殊字符 & < > \" ' 人数<10 且 >5", contents);
    }
}
