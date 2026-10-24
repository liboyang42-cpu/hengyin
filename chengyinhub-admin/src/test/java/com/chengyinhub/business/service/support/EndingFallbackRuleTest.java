package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C:U-169:兜底结局规则收口到 EndingFallbackRule,三条链路不再各写一份。 */
class EndingFallbackRuleTest {

    @Test
    void singleFallbackRule() {
        assertTrue(EndingFallbackRule.hasSingleFallback(false, 0));
        assertTrue(EndingFallbackRule.hasSingleFallback(false, 3));
        assertTrue(EndingFallbackRule.hasSingleFallback(true, 1));
        assertFalse(EndingFallbackRule.hasSingleFallback(true, 0));
        assertFalse(EndingFallbackRule.hasSingleFallback(true, 2));
    }

    @Test
    void chosenWinsOverFallback() {
        assertEquals("a", EndingFallbackRule.chosenOrFallback("a", "f"));
        assertEquals("f", EndingFallbackRule.chosenOrFallback(null, "f"));
        assertNull(EndingFallbackRule.<String>chosenOrFallback(null, null));
    }

    @Test
    void threeChainsUseSharedRule() throws Exception {
        String base = System.getProperty("user.dir") + "/../chengyinhub-system/src/main/java/com/chengyinhub/business/service/";
        for (String file : new String[]{"impl/ChapterFlowCompiler.java", "support/GameplayRuntimeConfig.java", "support/ThemeRuntimeEngine.java"}) {
            String src = new String(Files.readAllBytes(Paths.get(base + file)), StandardCharsets.UTF_8);
            assertTrue(src.contains("EndingFallbackRule."), file + " 必须走 EndingFallbackRule");
        }
    }
}
