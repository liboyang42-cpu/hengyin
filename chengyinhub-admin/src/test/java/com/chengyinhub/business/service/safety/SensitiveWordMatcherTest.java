package com.chengyinhub.business.service.safety;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Round5D:自建敏感词匹配。词条全部是编造的测试记号,仓库不放任何真实词表。 */
class SensitiveWordMatcherTest {

    private static final SensitiveWordMatcher M = SensitiveWordMatcher.build(Arrays.asList(
            new SensitiveWordMatcher.Entry("测敏甲乙", "GAMBLING", "BLOCK"),
            new SensitiveWordMatcher.Entry("zqxbad", "AD", "BLOCK"),
            new SensitiveWordMatcher.Entry("試記號", "OTHER", "BLOCK"),
            new SensitiveWordMatcher.Entry("观察丙", "OTHER", "LOG")),
            Arrays.asList("测敏甲乙票"));

    @Test
    void plainWordHitReturnsOnlyCategoryNeverTheWord() {
        SensitiveWordMatcher.Hit hit = M.find("前面的话测敏甲乙后面的话");
        assertNotNull(hit);
        assertEquals("GAMBLING", hit.getCategory());
        assertTrue(hit.isBlock());
        assertFalse(hit.toString().contains("测敏甲乙"));
    }

    @Test
    void symbolsSpacesAndEmojiBetweenCharactersDoNotEvadeMatching() {
        assertNotNull(M.find("测.敏 甲*乙"));
        assertNotNull(M.find("测😀敏😀甲😀乙"));
        assertNotNull(M.find("测　敏\n甲_乙"));
    }

    @Test
    void fullWidthCaseAndTraditionalAreNormalised() {
        assertNotNull(M.find("ＺＱＸbad"));
        assertNotNull(M.find("ZqX-BaD"));
        // 词条按繁体录入,文本写简体也命中(两边都归一成简体)
        assertNotNull(M.find("这是试记号"));
        assertNotNull(M.find("這是試記號"));
    }

    @Test
    void allowListedPhraseCoveringTheWordIsNotAHit() {
        assertNull(M.find("买一张测敏甲乙票"));
        assertNull(M.find("买一张测-敏-甲-乙-票"));
        // 同一段文本里另有一处不在放行短语里的,仍然命中
        assertNotNull(M.find("测敏甲乙票和测敏甲乙"));
    }

    @Test
    void logActionIsReportedButNotBlockingAndBlockWins() {
        SensitiveWordMatcher.Hit log = M.find("只有观察丙");
        assertNotNull(log);
        assertFalse(log.isBlock());
        assertTrue(M.find("观察丙和zqxbad").isBlock());
    }

    @Test
    void cleanTextAndEmptyLibraryDoNotHit() {
        assertNull(M.find("今天天气不错"));
        assertNull(SensitiveWordMatcher.empty().find("测敏甲乙"));
        assertNull(SensitiveWordMatcher.build(Collections.<SensitiveWordMatcher.Entry>emptyList(), null).find("测敏甲乙"));
    }
}
