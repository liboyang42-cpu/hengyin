package com.chengyinhub.business.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M-28:性格预设(温柔/可爱/毒舌)保存端与对话读取端认同一份编码;旧版自由文本原样保留。 */
class NpcPersonalityTest {

    @Test
    void presetCodesAndChineseLabelsNormalizeToTheSameCode() {
        assertEquals("gentle", NpcPersonality.normalize(" 温柔 "));
        assertEquals("cute", NpcPersonality.normalize("CUTE"));
        assertEquals("savage", NpcPersonality.normalize("毒舌"));
        assertTrue(NpcPersonality.isPreset("savage"));
    }

    @Test
    void legacyFreeTextIsKeptVerbatimAndNullMeansUnchanged() {
        assertEquals("说话像老朋友", NpcPersonality.normalize("  说话像老朋友  "));
        assertFalse(NpcPersonality.isPreset("说话像老朋友"));
        assertNull(NpcPersonality.normalize(null));
        assertEquals("", NpcPersonality.normalize(""));
    }

    @Test
    void presetExpandsIntoANamedPersonaPromptAndLegacyTextPassesThrough() {
        String prompt = NpcPersonality.prompt("小店长", "savage");
        assertTrue(prompt.contains("「小店长」"), prompt);
        assertTrue(prompt.contains("毒舌"), prompt);
        assertFalse(prompt.contains("savage"), prompt);
        assertTrue(NpcPersonality.prompt(null, "gentle").contains("温柔"));
        assertEquals("说话像老朋友", NpcPersonality.prompt("小店长", "说话像老朋友"));
    }
}
