package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项媒体的两个方向口径不同，两边都要钉死：
 * 写入端畸形必须抛（别让脏数据进库），读出端畸形必须容忍（别让一条坏 JSON 挡住整张题）。
 */
class QuestionOptionMediaTest
{
    /**
     * ★ 返回值分三档，别把前两档合并。mapper 用 {@code <if test="... != null">} 决定
     * 「这一列写不写」，所以 null 与空串的区别就是「不动库里的值」与「把列清空」的区别。
     *
     * <p>合并成一档的后果是真的：选项题 → 改成拍照 → 再改回选项题，旧的选项图会复活，
     * 因为清空时发的 null 让 update 直接跳过了这一列。
     */
    @Test
    void nullMeansLeaveAloneButEmptyMeansClear()
    {
        assertNull(QuestionOptionMedia.sanitize(null), "字段没传 ⇒ null ⇒ mapper 跳过该列");
        assertEquals("", QuestionOptionMedia.sanitize(""), "显式清空 ⇒ 空串 ⇒ mapper 真把列写空");
        assertEquals("", QuestionOptionMedia.sanitize("   "));
        assertEquals("", QuestionOptionMedia.sanitize("{}"), "传了个空对象也是显式清空");
        assertEquals("", QuestionOptionMedia.sanitize("{\"A\":{}}"),
                "传了字段但一个 url 都没剩，同样是清空");
    }

    @Test
    void sanitizeKeepsOnlyLetterKeysAndKnownMediaKeys() throws Exception
    {
        String out = QuestionOptionMedia.sanitize(
                "{\"A\":{\"img\":\"https://cdn/a.jpg\",\"audio\":\"https://cdn/a.mp3\"},"
                        + "\"C\":{\"img\":\" https://cdn/c.jpg \"}}");

        Map<String, Map<String, String>> parsed = QuestionOptionMedia.parse(out);
        assertEquals(2, parsed.size());
        assertEquals("https://cdn/a.jpg", parsed.get("A").get("img"));
        assertEquals("https://cdn/a.mp3", parsed.get("A").get("audio"));
        assertEquals("https://cdn/c.jpg", parsed.get("C").get("img"), "url 两端空白要归一掉");
    }

    /**
     * ★ 越界的键必须报错而不是静默丢：作者给「选项 E」配了图，无声吞掉的话
     * 他会以为自己配过了，而玩家永远看不到。
     */
    @Test
    void outOfRangeLetterIsRejectedNotSilentlyDropped()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> QuestionOptionMedia.sanitize("{\"E\":{\"img\":\"https://cdn/e.jpg\"}}"));
        assertTrue(error.getMessage().contains("只允许 A/B/C/D"), error.getMessage());
    }

    @Test
    void malformedShapesAreRejectedOnWrite()
    {
        assertThrows(IllegalArgumentException.class,
                () -> QuestionOptionMedia.sanitize("不是 json"));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionOptionMedia.sanitize("{\"A\":\"https://cdn/a.jpg\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionOptionMedia.sanitize("{\"A\":{\"img\":123}}"));
    }

    @Test
    void urlLengthIsGatedBeforeItHitsTheVarcharColumn()
    {
        StringBuilder url = new StringBuilder("https://cdn/");
        while (url.length() <= QuestionOptionMedia.MAX_URL_LENGTH)
        {
            url.append('x');
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> QuestionOptionMedia.sanitize("{\"A\":{\"img\":\"" + url + "\"}}"));
        assertTrue(error.getMessage().contains("超过"), error.getMessage());
    }

    /**
     * 归一化后的结果必须能被 parse 原样读回来 —— 否则库里存的形状与读出端的口径漂移，
     * 而漂移只会在「某个选项的图不显示」时才被发现。
     */
    @Test
    void sanitizeOutputRoundTripsThroughParse()
    {
        String out = QuestionOptionMedia.sanitize(
                "{\"B\":{\"audio\":\"https://cdn/b.mp3\"},\"A\":{\"img\":\"https://cdn/a.jpg\"}}");

        assertEquals(QuestionOptionMedia.parse(out), QuestionOptionMedia.parse(QuestionOptionMedia.sanitize(out)));
    }

    @Test
    void parseIsImmuneToGarbageSoTheQuestionStillShipsWithoutMedia()
    {
        assertTrue(QuestionOptionMedia.parse("不是 json").isEmpty());
        assertTrue(QuestionOptionMedia.parse("[1,2,3]").isEmpty());
        assertTrue(QuestionOptionMedia.parse("{\"A\":\"字符串不是对象\"}").isEmpty());
        assertTrue(QuestionOptionMedia.parse(null).isEmpty());
    }

    @Test
    void parseIgnoresUnknownKeysThatSomehowLandedInTheColumn()
    {
        Map<String, Map<String, String>> parsed = QuestionOptionMedia.parse(
                "{\"A\":{\"img\":\"https://cdn/a.jpg\",\"video\":\"https://cdn/a.mp4\"},\"Z\":{\"img\":\"x\"}}");

        assertEquals(1, parsed.size());
        assertEquals(1, parsed.get("A").size(), "未知媒体键不该跟着下发到客户端");
        assertEquals("https://cdn/a.jpg", parsed.get("A").get("img"));
    }
}
