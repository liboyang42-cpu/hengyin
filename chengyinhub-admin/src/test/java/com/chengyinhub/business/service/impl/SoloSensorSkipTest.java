package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第十一轮 h5 第 6 项:自玩通行证没有队长能放行。罗盘读不出方向两次后,本人可以 0 分跳过这一站,
 * 不困在这一站;带队场次仍要作者允许或队长放行。
 */
class SoloSensorSkipTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode CONFIG = read("{\"compass\":{\"enabled\":true,\"bearing\":90,\"allowSkip\":false}}");

    @Test
    void soloPassMaySkipAfterTwoFailedReads() {
        JsonNode solo = read("{\"compass\":{\"soloNoLead\":true,\"failedReads\":{\"9004\":2}}}");
        assertEquals("SOLO", AdvancedActionJudgement.skipGrant(CONFIG, solo, "compass", 9004L));
        assertTrue(AdvancedActionJudgement.canSkip(CONFIG, solo, "compass", 9004L));
        JsonNode once = read("{\"compass\":{\"soloNoLead\":true,\"failedReads\":{\"9004\":1}}}");
        assertFalse(AdvancedActionJudgement.canSkip(CONFIG, once, "compass", 9004L), "一次读取失败还不能跳");
    }

    @Test
    void ledSessionStillNeedsTheAuthorOrTheLead() {
        JsonNode led = read("{\"compass\":{\"failedReads\":{\"9004\":2}}}");
        assertNull(AdvancedActionJudgement.skipGrant(CONFIG, led, "compass", 9004L));
        JsonNode released = read("{\"compass\":{\"failedReads\":{\"9004\":2},\"releasedFor\":{\"9004\":true}}}");
        assertEquals("LEADER", AdvancedActionJudgement.skipGrant(CONFIG, released, "compass", 9004L));
        JsonNode author = read("{\"compass\":{\"enabled\":true,\"allowSkip\":true}}");
        assertEquals("AUTHOR", AdvancedActionJudgement.skipGrant(author, led, "compass", 9004L));
    }

    private static JsonNode read(String raw) {
        try { return JSON.readTree(raw); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
