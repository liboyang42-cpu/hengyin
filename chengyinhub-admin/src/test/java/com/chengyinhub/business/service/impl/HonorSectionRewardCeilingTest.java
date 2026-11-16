package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GAME-3 (W7 x-rewards-evidence): games the server cannot judge count for progress, XP and badges only.
 * Each reason caps the stop through rewardCappedToXpBadge and the journey award through
 * anyNodeRewardCappedToXpBadge (both read the same list).
 */
class HonorSectionRewardCeilingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> reasons(String config) throws Exception {
        JsonNode state = JSON.createObjectNode();
        return AdvancedGameRuntimeServiceImpl.untrustedCompletionEvidence(JSON.readTree(config), state);
    }

    @Test
    void photoQuestionIsUnjudged() throws Exception {
        assertEquals(Collections.singletonList("qa:PHOTO_UNJUDGED"),
                reasons("{\"qa\":{\"enabled\":true,\"mode\":\"SHOT\"}}"));
    }

    @Test
    void textQuestionStaysTrusted() throws Exception {
        assertTrue(reasons("{\"qa\":{\"enabled\":true,\"mode\":\"TEXT\"}}").isEmpty());
    }

    @Test
    void scratchCardIsSelfReported() throws Exception {
        assertEquals(Collections.singletonList("scratchCard:SELF_REPORTED"),
                reasons("{\"scratchCard\":{\"enabled\":true}}"));
    }

    @Test
    void timedTypingUsesDeviceTimeButUntimedTypingIsTrusted() throws Exception {
        assertEquals(Collections.singletonList("typeIn:DEVICE_TIME"),
                reasons("{\"typeIn\":{\"enabled\":true,\"seconds\":20,\"target\":\"abc\"}}"));
        assertTrue(reasons("{\"typeIn\":{\"enabled\":true,\"seconds\":0,\"target\":\"abc\"}}").isEmpty());
    }

    @Test
    void scoredSilentOrderIsHonorButUnscoredIsNot() throws Exception {
        assertEquals(Collections.singletonList("silentOrder:HONOR"),
                reasons("{\"silentOrder\":{\"enabled\":true,\"scored\":true}}"));
        assertTrue(reasons("{\"silentOrder\":{\"enabled\":true,\"scored\":false}}").isEmpty());
    }

    @Test
    void disabledSectionsAddNothing() throws Exception {
        assertTrue(reasons("{\"qa\":{\"enabled\":false,\"mode\":\"SHOT\"},\"scratchCard\":{\"enabled\":false}}").isEmpty());
    }
}
