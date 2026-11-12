package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-82 章节级奖励文案的取值域:一行、去首尾空白、按字符计不超过 24。 */
class ChapterRewardTextTest
{
    @Test
    void nullMeansNotSentAndBlankMeansCleared()
    {
        assertNull(ChapterRewardText.requireValid(null));
        assertEquals("", ChapterRewardText.requireValid("   "));
        assertNull(ChapterRewardText.requireValidOrNull("   "), "整章重建路径:空白不落库");
        assertNull(ChapterRewardText.requireValidOrNull(null));
    }

    @Test
    void trimsAndKeepsText()
    {
        assertEquals("隐藏结局线索 ×1", ChapterRewardText.requireValid("  隐藏结局线索 ×1 "));
        assertEquals("隐藏结局线索 ×1", ChapterRewardText.requireValidOrNull("隐藏结局线索 ×1"));
    }

    @Test
    void lengthCountsCharactersNotBytes()
    {
        String max = "奖".repeat(ChapterRewardText.MAX_CODE_POINTS);
        assertEquals(max, ChapterRewardText.requireValid(max));
        // emoji 是代理对:按 code point 计一个字,不能被 String.length() 算成两个而误拒
        String emoji = "🎁".repeat(ChapterRewardText.MAX_CODE_POINTS);
        assertEquals(emoji, ChapterRewardText.requireValid(emoji));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChapterRewardText.requireValid(max + "奖"));
        assertTrue(e.getMessage().contains("24"), e.getMessage());
    }

    @Test
    void rejectsMultiLineAndControlCharacters()
    {
        assertThrows(IllegalStateException.class, () -> ChapterRewardText.requireValid("第一行\n第二行"));
        assertThrows(IllegalStateException.class, () -> ChapterRewardText.requireValid("奖\u0007励"));
        assertThrows(IllegalStateException.class, () -> ChapterRewardText.requireValidOrNull("a\tb"));
    }
}
