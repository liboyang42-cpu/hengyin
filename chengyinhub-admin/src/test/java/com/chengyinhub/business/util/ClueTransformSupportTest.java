package com.chengyinhub.business.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 谜面变换:模式白名单、字素倒序规则、参数封闭,以及“原题面/答案边界不被触碰”。 */
class ClueTransformSupportTest
{
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode section(String mode)
    {
        ObjectNode node = NODES.objectNode();
        node.put("mode", mode);
        return node;
    }

    // ---- 模式解析与白名单 ----

    @Test
    void absentSectionIsStandardSoExistingConfigsDoNotChange()
    {
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(null));
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(NODES.nullNode()));
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(NODES.missingNode()));
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(NODES.objectNode()));
    }

    @Test
    void blankModeFallsBackToStandard()
    {
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(section("")));
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(section("   ")));
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(NODES.objectNode().putNull("mode")));
    }

    @Test
    void approvedModesAreAcceptedRegardlessOfCaseAndPadding()
    {
        assertEquals(ClueTransformSupport.MODE_REVERSE, ClueTransformSupport.mode(section("Reverse")));
        assertEquals(ClueTransformSupport.MODE_REVERSE, ClueTransformSupport.mode(section("  REVERSE  ")));
        assertEquals(ClueTransformSupport.MODE_MIRROR, ClueTransformSupport.mode(section("mirror")));
        assertEquals(ClueTransformSupport.MODE_STANDARD, ClueTransformSupport.mode(section("Standard")));
    }

    @Test
    void cypherAndFlipAreRefusedAsPendingNotSilentlyIgnored()
    {
        IllegalArgumentException cypher = assertThrows(IllegalArgumentException.class,
                () -> ClueTransformSupport.mode(section("CYPHER")));
        assertTrue(cypher.getMessage().contains("CYPHER"));
        assertTrue(cypher.getMessage().contains("不可发布"));
        IllegalArgumentException flip = assertThrows(IllegalArgumentException.class,
                () -> ClueTransformSupport.mode(section("Flip")));
        assertTrue(flip.getMessage().contains("FLIP"));
    }

    @Test
    void unknownModeAndNonTextModeAreRefused()
    {
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.mode(section("ROT13")));
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.mode(NODES.objectNode().put("mode", 13)));
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.mode(NODES.objectNode().put("mode", true)));
    }

    @Test
    void nonObjectSectionAndAuthorSuppliedParametersAreRefused()
    {
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.mode(NODES.textNode("REVERSE")));
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.mode(NODES.arrayNode()));

        ObjectNode withShift = section("REVERSE");
        withShift.put("shift", 3);
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.mode(withShift));

        ObjectNode withScript = section("MIRROR");
        withScript.put("script", "return 1;");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ClueTransformSupport.mode(withScript));
        assertTrue(refused.getMessage().contains("不接受作者自定义参数或脚本"));
    }

    @Test
    void validateOnlyChecksStructureAndIsCalledBySavePath()
    {
        ClueTransformSupport.validate(null);
        ClueTransformSupport.validate(section("REVERSE"));
        assertThrows(IllegalArgumentException.class, () -> ClueTransformSupport.validate(section("FLIP")));
    }

    @Test
    void sectionLookupTreatsBrokenOrMissingConfigAsNotConfigured()
    {
        assertNull(ClueTransformSupport.sectionOf((String) null));
        assertNull(ClueTransformSupport.sectionOf("  "));
        assertNull(ClueTransformSupport.sectionOf("{not json"));
        assertNull(ClueTransformSupport.sectionOf("{}"));
        assertNull(ClueTransformSupport.sectionOf(NODES.objectNode()));
        assertNull(ClueTransformSupport.sectionOf(NODES.textNode("REVERSE")));
        assertEquals("REVERSE", ClueTransformSupport.sectionOf("{\"clueTransform\":{\"mode\":\"REVERSE\"}}")
                .path("mode").asText());
        assertEquals("MIRROR", ClueTransformSupport.sectionOf(
                MAPPER.createObjectNode().set("clueTransform", section("MIRROR"))).path("mode").asText());
    }

    // ---- 保存/发布可用性 ----

    @Test
    void transformWithoutPromptIsRefusedAtPublishTime()
    {
        assertThrows(IllegalArgumentException.class,
                () -> ClueTransformSupport.requireApplicable(section("REVERSE"), null));
        assertThrows(IllegalArgumentException.class,
                () -> ClueTransformSupport.requireApplicable(section("MIRROR"), "   "));
        ClueTransformSupport.requireApplicable(section("REVERSE"), "城外之夜");
        ClueTransformSupport.requireApplicable(section("STANDARD"), null);
        ClueTransformSupport.requireApplicable(null, null);
    }

    // ---- 玩家侧投影:只发模式,不发内容 ----

    @Test
    void standardEmitsNoFieldSoOldClientsAndOldConfigsAreUnaffected()
    {
        assertNull(ClueTransformSupport.publicView(null));
        assertNull(ClueTransformSupport.publicView(section("STANDARD")));
    }

    @Test
    void publicViewCarriesOnlyTheModeName()
    {
        ObjectNode view = ClueTransformSupport.publicView(section("MIRROR"));
        assertEquals("{\"mode\":\"MIRROR\"}", view.toString());
        assertEquals("{\"mode\":\"REVERSE\"}", ClueTransformSupport.publicView(section("REVERSE")).toString());
    }

    @Test
    void mirroredRenderingIsADirectiveAndNeverAPreRenderedString()
    {
        assertTrue(ClueTransformSupport.requiresMirroredRendering(section("MIRROR")));
        assertFalse(ClueTransformSupport.requiresMirroredRendering(section("REVERSE")));
        assertFalse(ClueTransformSupport.requiresMirroredRendering(null));
        // 镜像不改文字:服务端没有经审核的字形表,翻转由呈现层做。
        assertEquals("城外之夜", ClueTransformSupport.renderPrompt("城外之夜", section("MIRROR")));
    }

    // ---- 题面呈现 ----

    @Test
    void standardKeepsPromptByteForByte()
    {
        String prompt = "  第 3 站:看门楣上的年份  ";
        assertSame(prompt, ClueTransformSupport.renderPrompt(prompt, section("STANDARD")));
        assertSame(prompt, ClueTransformSupport.renderPrompt(prompt, null));
    }

    @Test
    void reverseFlipsChineseByCharacter()
    {
        assertEquals("夜之外城", ClueTransformSupport.renderPrompt("城外之夜", section("REVERSE")));
    }

    @Test
    void reverseMovesSpacesAndPunctuationWithTheText()
    {
        assertEquals("c,b a", ClueTransformSupport.reverseGraphemes("a b,c"));
        assertEquals("。好你", ClueTransformSupport.reverseGraphemes("你好。"));
        assertEquals("!dlrow olleh", ClueTransformSupport.reverseGraphemes("hello world!"));
    }

    @Test
    void reverseKeepsSurrogatePairsWhole()
    {
        String emoji = "\uD83D\uDE00"; // U+1F600
        assertEquals("ba" + emoji, ClueTransformSupport.reverseGraphemes(emoji + "ab"));
        assertEquals(emoji + "!" + emoji, ClueTransformSupport.reverseGraphemes(emoji + "!" + emoji));
    }

    @Test
    void reverseKeepsCombiningMarksAttachedToTheirBaseCharacter()
    {
        String eAcute = "e\u0301"; // e + COMBINING ACUTE ACCENT
        assertEquals("x" + eAcute, ClueTransformSupport.reverseGraphemes(eAcute + "x"));
        assertEquals("xi" + eAcute, ClueTransformSupport.reverseGraphemes(eAcute + "ix"));
    }

    @Test
    void reverseKeepsZwjSequencesAndFlagsWhole()
    {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"; // 男 ZWJ 女 ZWJ 女童
        String flag = "\uD83C\uDDE8\uD83C\uDDF3";                        // 区域指示符 CN
        assertEquals("a" + family, ClueTransformSupport.reverseGraphemes(family + "a"));
        assertEquals("b" + flag, ClueTransformSupport.reverseGraphemes(flag + "b"));
        assertEquals(flag + flag, ClueTransformSupport.reverseGraphemes(flag + flag));
    }

    @Test
    void reverseLeavesNothingToDoCasesAlone()
    {
        assertNull(ClueTransformSupport.reverseGraphemes(null));
        assertEquals("", ClueTransformSupport.reverseGraphemes(""));
        assertEquals("城", ClueTransformSupport.reverseGraphemes("城"));
    }

    @Test
    void reverseIsItsOwnInverse()
    {
        String prompt = "第 3 站:门楣上的 \"1947\" 年份?";
        assertEquals(prompt, ClueTransformSupport.reverseGraphemes(
                ClueTransformSupport.reverseGraphemes(prompt)));
    }

    // ---- 秘密边界 ----

    @Test
    void transformNeverTouchesOrEmitsTheSecretAnswer()
    {
        String answer = "1947";
        ObjectNode config = MAPPER.createObjectNode();
        config.set("clueTransform", section("REVERSE"));
        // 调用方只传公开题面;答案字符串本身不参与渲染,也不会出现在投影里。
        String rendered = ClueTransformSupport.renderPrompt("门楣上的年份", config.path("clueTransform"));
        assertEquals("份年的上楣门", rendered);
        assertFalse(rendered.contains(answer));
        ObjectNode view = ClueTransformSupport.publicView(config.path("clueTransform"));
        assertEquals("{\"mode\":\"REVERSE\"}", view.toString());
        assertFalse(view.has("questionAnswer"));
        assertFalse(view.has("correctAnswer"));
        assertFalse(view.has("answerReveal"));
    }
}
