package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AigcLabelTest {
    @Test
    void onlyAiSimplePublishModeIsLabelled() {
        assertTrue(AigcLabel.isAiGenerated("ai_simple"));
        assertTrue(AigcLabel.isAiGenerated(" AI_SIMPLE "));
        assertFalse(AigcLabel.isAiGenerated("simple"));
        assertFalse(AigcLabel.isAiGenerated("pro"));
        assertFalse(AigcLabel.isAiGenerated(""));
        assertFalse(AigcLabel.isAiGenerated(null));
    }
}
