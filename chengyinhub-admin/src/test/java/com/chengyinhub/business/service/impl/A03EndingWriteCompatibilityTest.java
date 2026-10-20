package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class A03EndingWriteCompatibilityTest {
    private ChapterDTO chapter(String ending) throws Exception {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setSchemaVersion(1);
        chapter.setBlocks(Collections.emptyList());
        chapter.setEnding(new ObjectMapper().readTree(ending));
        return chapter;
    }

    @Test void booleanFallbackStillCompiles() throws Exception {
        assertNotNull(ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(
                chapter("{\"fallback\":true}"))));
    }

    @Test void malformedFallbackCannotBecomeAnUnconditionalEnding() throws Exception {
        for (String value : new String[]{"\"true\"", "1", "null", "{}"}) {
            ChapterDTO chapter = chapter("{\"fallback\":" + value + "}");
            ChapterFlowValidationException failure = assertThrows(ChapterFlowValidationException.class,
                    () -> ChapterFlowCompiler.compileChapterFlow(Collections.singletonList(chapter)));
            assertTrue(failure.getMessage().contains("fallback 必须是布尔值"));
        }
    }
}
