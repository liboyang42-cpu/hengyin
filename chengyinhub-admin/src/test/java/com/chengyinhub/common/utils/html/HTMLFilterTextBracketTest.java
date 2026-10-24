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

    /**
     * PR10 复审:属性区混了正文的 '<字母…>' 不是标签。原先 {@code a<b 且 c>d} 被当成 {@code <b>}、
     * 「且 c」当非法属性删掉存成 {@code a<b>d}。现在正文一个字不丢;紧跟字母的文本 '<' 落成 &lt;
     * (与未闭合的 {@code x<y} 同口径),浏览器不会把它解析成标签。
     */
    @Test void bracketsThatDoNotFormARealTagKeepAllText() {
        HTMLFilter filter = new HTMLFilter();
        assertEquals("a&lt;b 且 c>d", filter.filter("a<b 且 c>d"));
        assertEquals("x < y > z", filter.filter("x < y > z"));
        assertEquals("1<2", filter.filter("1<2"));
        assertEquals("<身份证>", filter.filter("<身份证>"));
        assertEquals("请带上<身份证>和<学生证>", filter.filter("请带上<身份证>和<学生证>"));
        assertEquals("x&lt;y", filter.filter("x<y"));
    }

    /** 不成标签的写法仍不能夹带可执行内容:输出里不得出现任何会被浏览器当标签起始的 '<'。 */
    @Test void textClassifiedBracketsCannotSmuggleMarkup() {
        HTMLFilter filter = new HTMLFilter();
        java.util.regex.Pattern tagStart = java.util.regex.Pattern.compile("<[A-Za-z!?/]");
        for (String text : new String[]{"<img 且 src=x onerror=alert(1)>", "<script 且>alert(1)</script>",
                "a<svg 且/onload=alert(1)>b", "<a 链接 href='javascript:alert(1)'>x</a>", "</b 且>"}) {
            String clean = filter.filter(text);
            assertFalse(tagStart.matcher(clean).find(), text + " => " + clean);
        }
        // 真标签照旧走白名单清洗:危险属性与协议被剥离
        String clean = filter.filter("<b>粗体</b><img src=x onerror=alert(1)><a href='javascript:alert(1)'>x</a>")
                .toLowerCase(java.util.Locale.ROOT);
        assertTrue(clean.contains("<b>粗体</b>"), clean);
        assertFalse(clean.contains("onerror"), clean);
        assertFalse(clean.contains("javascript:"), clean);
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
