package com.chengyinhub.business.service;

import com.chengyinhub.business.support.CircleInteractionPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircleInteractionPolicyTest {
    @Test
    void acceptsOnlyDocumentInteractions() {
        Set<String> enabled = new java.util.HashSet<>(java.util.Arrays.asList("F2", "P4"));
        assertDoesNotThrow(() -> CircleInteractionPolicy.assertAnswer("FITNESS", "PRE_CHOICE", "F2", enabled));
        assertDoesNotThrow(() -> CircleInteractionPolicy.assertAnswer("MIDLIFE", "SELF_MOMENT", "朋友笑起来时", enabled));
        assertDoesNotThrow(() -> CircleInteractionPolicy.assertAnswer("DATE", "QUESTION_CARD", "Q1", enabled));
        assertDoesNotThrow(() -> CircleInteractionPolicy.assertAnswer("FRIENDS", "NEXT_PICK", "P4", enabled));
        assertDoesNotThrow(() -> CircleInteractionPolicy.assertAnswer("SHANGHAI", "CITY_KEYWORDS", "S1:味道,S2:夜色",
                new java.util.HashSet<>(java.util.Arrays.asList("S1", "S2"))));
        assertThrows(IllegalStateException.class,
                () -> CircleInteractionPolicy.assertAnswer("FITNESS", "PRE_CHOICE", "F9", enabled));
        assertThrows(IllegalStateException.class,
                () -> CircleInteractionPolicy.assertAnswer("FRIENDS", "NEXT_PICK", "P8", enabled));
        assertThrows(IllegalStateException.class,
                () -> CircleInteractionPolicy.assertAnswer("DATE", "QUESTION_CARD", "Q4", enabled));
        assertThrows(IllegalStateException.class,
                () -> CircleInteractionPolicy.assertAnswer("SHANGHAI", "CITY_KEYWORDS", "S1:地标", enabled));
    }

    @Test
    void completionMatchesEachThemesLightInteraction() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("PRE_CHOICE", 3);
        counts.put("POST_CHOICE", 3);
        assertTrue(CircleInteractionPolicy.isComplete("FITNESS", 3, key -> counts.getOrDefault(key, 0)));
        counts.put("POST_CHOICE", 2);
        assertFalse(CircleInteractionPolicy.isComplete("FITNESS", 3, key -> counts.getOrDefault(key, 0)));

        counts.clear();
        counts.put("PRE_WISH", 4);
        counts.put("NEXT_PICK", 1);
        assertTrue(CircleInteractionPolicy.isComplete("FRIENDS", 4, key -> counts.getOrDefault(key, 0)));

        counts.clear();
        counts.put("QUESTION_CARD", 1);
        assertTrue(CircleInteractionPolicy.isComplete("DATE", 2, key -> counts.getOrDefault(key, 0)));
        assertFalse(CircleInteractionPolicy.hasRequiredMembers("DATE", 1));
        assertTrue(CircleInteractionPolicy.hasRequiredMembers("DATE", 2));
        assertFalse(CircleInteractionPolicy.hasRequiredMembers("FRIENDS", 1));
    }

    @Test
    void onlyPrimaryThemeMemoryStagesAreSharedWithTheGroup() {
        assertTrue(CircleInteractionPolicy.isSharedStage("MIDLIFE", "SELF_MOMENT"));
        assertTrue(CircleInteractionPolicy.isSharedStage("FRIENDS", "NEXT_PICK"));
        assertFalse(CircleInteractionPolicy.isSharedStage("SOLO", "DECISION"));
    }
}
