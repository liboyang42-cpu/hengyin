package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-16 获准片段的内容边界:文字/图片来自冻结配置,首字字母数字由服务端从冻结答案现算,
 * 答案级只给作者揭示文案。推不出来就返回 null(调用方据此拒绝且不扣费)。
 */
class PlayHelpContentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void textLevelPrefersAuthoredPayload() throws Exception {
        String content = PlayHelpContent.approved(1, PlayHelpContent.TYPE_TEXT,
                json("{\"level\":1,\"type\":\"TEXT\",\"payload\":\"观察屋檐下的年份\"}"),
                json("{\"hint1\":\"旧提示\"}"));

        assertEquals("观察屋檐下的年份", content);
    }

    @Test
    void textLevelFallsBackToExistingHintFields() throws Exception {
        assertEquals("看门牌", PlayHelpContent.approved(1, PlayHelpContent.TYPE_TEXT, null,
                json("{\"hint1\":\"看门牌\",\"hint2\":\"再看年份\"}")));
        assertEquals("再看年份", PlayHelpContent.approved(2, PlayHelpContent.TYPE_TEXT, null,
                json("{\"hint1\":\"看门牌\",\"hint2\":\"再看年份\"}")));
        assertNull(PlayHelpContent.approved(3, PlayHelpContent.TYPE_TEXT, null, json("{\"hint1\":\"看门牌\"}")));
    }

    @Test
    void imageLevelOnlyComesFromAuthoredPayload() throws Exception {
        assertEquals("https://cdn.example/a.jpg", PlayHelpContent.approved(2, PlayHelpContent.TYPE_IMAGE,
                json("{\"level\":2,\"type\":\"IMAGE\",\"payload\":\"https://cdn.example/a.jpg\"}"),
                json("{\"hint1\":\"文字提示不是图片\"}")));
        assertNull(PlayHelpContent.approved(2, PlayHelpContent.TYPE_IMAGE, null,
                json("{\"hint1\":\"文字提示不是图片\"}")), "旧数据没有图片提示,不得拿文字冒充");
    }

    @Test
    void letterLevelRevealsExactlyOneLetterFromTheAnswer() throws Exception {
        String content = PlayHelpContent.approved(2, PlayHelpContent.TYPE_LETTER,
                json("{\"level\":2,\"type\":\"LETTER\",\"payload\":\"这是配置里的整段提示\"}"),
                json("{\"questionAnswer\":\"  Bridges 42\"}"));

        assertEquals("B", content);
        assertEquals(1, content.length(), "只发一个字符,绝不下发整串答案");
    }

    @Test
    void digitLevelRevealsExactlyOneDigitFromTheAnswer() throws Exception {
        String content = PlayHelpContent.approved(3, PlayHelpContent.TYPE_DIGIT, null,
                json("{\"questionAnswer\":\"第 1793 年\"}"));

        assertEquals("1", content);
        assertEquals(1, content.length());
    }

    @Test
    void chineseAnswerRevealsItsFirstCharacterAsLetterLevel() throws Exception {
        assertEquals("钟", PlayHelpContent.approved(1, PlayHelpContent.TYPE_LETTER, null,
                json("{\"questionAnswer\":\"钟楼\"}")), "中文按首字,不是拼音");
    }

    @Test
    void answerLevelPrefersAuthorRevealText() throws Exception {
        assertEquals("答案是钟楼", PlayHelpContent.approved(4, PlayHelpContent.TYPE_ANSWER, null,
                json("{\"answerReveal\":\"答案是钟楼\",\"questionAnswer\":\"钟楼\"}")));
        assertEquals("钟楼", PlayHelpContent.approved(4, PlayHelpContent.TYPE_ANSWER, null,
                json("{\"questionAnswer\":\"钟楼\"}")));
    }

    @Test
    void emptyOrMissingSourcesYieldNullInsteadOfHalfTruth() throws Exception {
        assertNull(PlayHelpContent.approved(4, PlayHelpContent.TYPE_ANSWER, null, json("{}")));
        assertNull(PlayHelpContent.approved(1, PlayHelpContent.TYPE_TEXT, null, null));
        assertNull(PlayHelpContent.approved(1, PlayHelpContent.TYPE_LETTER, null, json("{\"questionAnswer\":\"42\"}")),
                "没有字母就不发,不能拿数字顶替首字母");
        assertNull(PlayHelpContent.approved(1, PlayHelpContent.TYPE_DIGIT, null, json("{\"questionAnswer\":\"桥\"}")));
    }

    @Test
    void skipAndUnknownTypesCarryNoContent() throws Exception {
        assertNull(PlayHelpContent.approved(5, PlayHelpContent.TYPE_SKIP, json("{\"payload\":\"不该显示\"}"),
                json("{\"answerReveal\":\"不该显示\"}")));
        assertNull(PlayHelpContent.approved(6, "LOCATION", json("{\"payload\":\"x\"}"), json("{}")));
        assertNull(PlayHelpContent.approved(1, null, null, json("{\"answerReveal\":\"答案\"}")));
    }

    @Test
    void approvedTextIsExactlyThePayloadNotATruncatedAnswer() throws Exception {
        String content = PlayHelpContent.approved(1, PlayHelpContent.TYPE_TEXT,
                json("{\"payload\":\"提示只描述线索\"}"), json("{\"questionAnswer\":\"完整答案\"}"));

        assertTrue(content.contains("线索"));
        assertTrue(!content.contains("完整答案"));
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }
}
