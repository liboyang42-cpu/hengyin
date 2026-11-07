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

    /** 第十一轮 i1:弹球、安静挑战、喊一嗓子、计步同罗盘一条规则,自玩两次读不出数后本人可 0 分跳过。 */
    @Test
    void otherSensorGamesFollowTheCompassRuleOnASoloPass() {
        for (String game : new String[]{"ballShake", "quietHold", "shout", "steps"}) {
            assertTrue(AdvancedActionJudgement.SKIPPABLE_SENSOR_SECTIONS.contains(game), game);
            JsonNode config = read("{\"" + game + "\":{\"enabled\":true}}");
            JsonNode solo = read("{\"" + game + "\":{\"soloNoLead\":true,\"failedReads\":{\"9004\":2}}}");
            assertTrue(AdvancedActionJudgement.canSkip(config, solo, game, 9004L), game);
            JsonNode once = read("{\"" + game + "\":{\"soloNoLead\":true,\"failedReads\":{\"9004\":1}}}");
            assertFalse(AdvancedActionJudgement.canSkip(config, once, game, 9004L), game + " 一次失败还不能跳");
            JsonNode led = read("{\"" + game + "\":{\"failedReads\":{\"9004\":2}}}");
            assertFalse(AdvancedActionJudgement.canSkip(config, led, game, 9004L), game + " 带队场次仍要作者或队长");
        }
    }

    /** 走完的段不许再跳;跳过的段算走完(站点能往下走),但不再收成绩。 */
    @Test
    void skippedSensorSectionCompletesTheStationButTakesNoLateScore() throws Exception {
        JsonNode submitted = read("{\"ballShake\":{\"soloNoLead\":true,\"submitted\":true,\"failedReads\":{\"9004\":2}}}");
        assertTrue(AdvancedActionJudgement.sensorSectionFinished(submitted, "ballShake"));
        assertFalse(AdvancedActionJudgement.canSkip(read("{\"ballShake\":{\"enabled\":true}}"), submitted, "ballShake", 9004L));
        JsonNode reached = read("{\"steps\":{\"reached\":true}}");
        assertTrue(AdvancedActionJudgement.sensorSectionFinished(reached, "steps"));
        assertFalse(AdvancedActionJudgement.sensorSectionFinished(read("{\"steps\":{\"todaySteps\":10}}"), "steps"));

        for (String game : new String[]{"ballShake", "quietHold", "shout", "steps"}) {
            com.fasterxml.jackson.databind.node.ObjectNode config = (com.fasterxml.jackson.databind.node.ObjectNode)
                    read("{\"" + game + "\":{\"enabled\":true}}");
            com.fasterxml.jackson.databind.node.ObjectNode open = (com.fasterxml.jackson.databind.node.ObjectNode) read("{}");
            assertFalse(AdvancedActionJudgement.mechanicsReady(config, open), game + " 没走完不算完成");
            com.fasterxml.jackson.databind.node.ObjectNode skipped = (com.fasterxml.jackson.databind.node.ObjectNode)
                    read("{\"" + game + "\":{\"done\":true,\"skipped\":true}}");
            assertTrue(AdvancedActionJudgement.mechanicsReady(config, skipped), game + " 跳过算走完");
            IllegalStateException late = assertThrows(IllegalStateException.class,
                    () -> AdvancedActionJudgement.requireNotSkipped(skipped, game));
            assertEquals("这一站已经跳过了", late.getMessage());
        }
    }

    /** 拒了权限(麦克风 / 微信运动)一次就到门槛;普通读不出数仍要两次。 */
    @Test
    void deniedPermissionReachesTheSkipThresholdAtOnce() {
        JsonNode config = read("{\"quietHold\":{\"enabled\":true}}");
        com.fasterxml.jackson.databind.node.ObjectNode state = (com.fasterxml.jackson.databind.node.ObjectNode)
                read("{\"quietHold\":{\"soloNoLead\":true}}");
        java.util.Map<String, Object> failed = new java.util.HashMap<>();
        failed.put("game", "quietHold");
        AdvancedActionJudgement.reportSensorFailure(config, state, failed, 9004L);
        assertFalse(AdvancedActionJudgement.canSkip(config, state, "quietHold", 9004L));
        java.util.Map<String, Object> denied = new java.util.HashMap<>(failed);
        denied.put("denied", true);
        com.fasterxml.jackson.databind.node.ObjectNode fresh = (com.fasterxml.jackson.databind.node.ObjectNode)
                read("{\"quietHold\":{\"soloNoLead\":true}}");
        AdvancedActionJudgement.reportSensorFailure(config, fresh, denied, 9004L);
        assertTrue(AdvancedActionJudgement.canSkip(config, fresh, "quietHold", 9004L));
    }

    private static JsonNode read(String raw) {
        try { return JSON.readTree(raw); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
