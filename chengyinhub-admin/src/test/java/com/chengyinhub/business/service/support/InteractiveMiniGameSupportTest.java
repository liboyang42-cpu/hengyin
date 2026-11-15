package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveMiniGameSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatorAndPublicProjectionStripSecretsUntilRevealed() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.putObject("trueFalse")
                .put("enabled", true)
                .put("title", "鼓浪屿禁止机动车通行")
                .put("answer", true)
                .put("feedbackCorrect", "回答正确")
                .put("feedbackWrong", "其实岛上没有机动车")
                .put("explanation", "景区实行步行保护。")
                .put("maxTries", 0)
                .put("xp", 15);
        root.putObject("imageGuess")
                .put("enabled", true)
                .put("title", "猜猜这是哪座老建筑")
                .put("imageUrl", "https://cdn.example.com/clock.jpg")
                .put("answer", "八卦楼")
                .put("hint", "红色圆顶")
                .put("xp", 20)
                .putArray("revealSteps").add("始建于1907年").add("风琴博物馆所在地");
        root.putObject("jigsaw")
                .put("enabled", true)
                .put("title", "拼出老地图")
                .put("imageUrl", "https://cdn.example.com/map.jpg")
                .put("rows", 2)
                .put("cols", 2)
                .put("xp", 25);
        root.putObject("scratchCard")
                .put("enabled", true)
                .put("title", "刮开茶馆暗语")
                .put("coverLabel", "轻刮涂层")
                .put("threshold", 0.45)
                .put("prizeText", "二楼靠窗第三桌")
                .put("revealImageUrl", "https://cdn.example.com/tea.jpg")
                .put("xp", 10);

        String normalized = new AdvancedGameConfigValidator().validateAndNormalize(root.toString());
        assertNotNull(normalized);
        assertTrue(new AdvancedGameConfigValidator().hasEnabledMechanic(normalized));

        String safeJson = AdvancedGamePublicProjection.safeConfigJson(normalized);
        assertNotNull(safeJson);
        assertFalse(safeJson.contains("八卦楼"));
        assertFalse(safeJson.contains("二楼靠窗第三桌"));
        assertFalse(safeJson.contains("feedbackCorrect"));
        assertFalse(safeJson.contains("景区实行步行保护"));
        assertFalse(safeJson.contains("风琴博物馆所在地"));

        JsonNode safeNode = JSON.readTree(safeJson);
        assertTrue(safeNode.path("trueFalse").path("enabled").asBoolean());
        assertTrue(safeNode.path("imageGuess").path("enabled").asBoolean());
        assertTrue(safeNode.path("jigsaw").path("enabled").asBoolean());
        assertTrue(safeNode.path("scratchCard").path("enabled").asBoolean());
        assertEquals(0.45d, safeNode.path("scratchCard").path("threshold").asDouble(), 0.0001d);
    }

    @Test
    void trueFalseSupportsFreeRetriesAndRevealsExplanationOnPass() {
        ObjectNode config = JSON.createObjectNode();
        config.putObject("trueFalse")
                .put("enabled", true)
                .put("title", "陈述题")
                .put("answer", true)
                .put("explanation", "解析正文")
                .put("maxTries", 0)
                .put("xp", 30);
        ObjectNode state = JSON.createObjectNode();

        int wrongXp = InteractiveMiniGameSupport.submitTrueFalse(config, state, Map.of("answer", false));
        assertEquals(0, wrongXp);
        assertFalse(state.path("trueFalse").path("finished").asBoolean());
        assertFalse(InteractiveMiniGameSupport.mechanicsReady(config, state));

        Map<String, Object> beforeFinish = new LinkedHashMap<>();
        InteractiveMiniGameSupport.populateKit(config, state, beforeFinish);
        @SuppressWarnings("unchecked")
        Map<String, Object> tfBefore = (Map<String, Object>) beforeFinish.get("trueFalse");
        assertFalse(tfBefore.containsKey("answer"));
        assertFalse(tfBefore.containsKey("explanation"));

        int winXp = InteractiveMiniGameSupport.submitTrueFalse(config, state, Map.of("answer", true));
        assertEquals(30, winXp);
        assertTrue(state.path("trueFalse").path("finished").asBoolean());
        assertTrue(state.path("trueFalse").path("passed").asBoolean());
        assertTrue(InteractiveMiniGameSupport.mechanicsReady(config, state));

        Map<String, Object> afterFinish = new LinkedHashMap<>();
        InteractiveMiniGameSupport.populateKit(config, state, afterFinish);
        @SuppressWarnings("unchecked")
        Map<String, Object> tfAfter = (Map<String, Object>) afterFinish.get("trueFalse");
        assertEquals(Boolean.TRUE, tfAfter.get("answer"));
        assertEquals("解析正文", tfAfter.get("explanation"));
    }

    @Test
    void imageGuessUnlocksRevealStepsAndAcceptsAliases() {
        ObjectNode config = JSON.createObjectNode();
        ObjectNode ig = config.putObject("imageGuess")
                .put("enabled", true)
                .put("title", "猜地标")
                .put("imageUrl", "https://cdn.example.com/spot.jpg")
                .put("answer", "日光岩")
                .put("xp", 25);
        ig.putArray("aliases").add("Sunlight Rock");
        ig.putArray("revealSteps").add("最高点").add("龙头山");
        ObjectNode state = JSON.createObjectNode();
        InteractiveMiniGameSupport.initState(config, state);

        assertEquals(0, InteractiveMiniGameSupport.submitImageGuess(config, state, Map.of("action", "REVEAL_STEP")));
        assertEquals(2, state.path("imageGuess").path("revealLevel").asInt());

        Map<String, Object> kit = new LinkedHashMap<>();
        InteractiveMiniGameSupport.populateKit(config, state, kit);
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) kit.get("imageGuess");
        assertEquals(List.of("最高点"), view.get("revealedClues"));
        assertFalse(view.containsKey("answer"));

        assertEquals(25, InteractiveMiniGameSupport.submitImageGuess(config, state, Map.of("guess", "sunlight rock")));
        assertTrue(InteractiveMiniGameSupport.mechanicsReady(config, state));
    }

    @Test
    void jigsawShufflesInitialOrderAndSolvesOnIdentityPermutation() {
        ObjectNode config = JSON.createObjectNode();
        config.putObject("jigsaw")
                .put("enabled", true)
                .put("title", "拼图")
                .put("imageUrl", "https://cdn.example.com/jigsaw.jpg")
                .put("rows", 2)
                .put("cols", 2)
                .put("xp", 40);
        ObjectNode state = JSON.createObjectNode();
        InteractiveMiniGameSupport.initState(config, state);

        assertNotEquals(Arrays.asList(0, 1, 2, 3), InteractiveMiniGameSupport.initialJigsawOrder(4));
        assertThrows(IllegalArgumentException.class,
                () -> InteractiveMiniGameSupport.submitJigsaw(config, state, Map.of("order", Arrays.asList(0, 0, 1, 2))));

        assertEquals(0, InteractiveMiniGameSupport.submitJigsaw(config, state, Map.of("order", Arrays.asList(1, 0, 2, 3))));
        assertFalse(InteractiveMiniGameSupport.mechanicsReady(config, state));

        assertEquals(40, InteractiveMiniGameSupport.submitJigsaw(config, state, Map.of("order", Arrays.asList(0, 1, 2, 3))));
        assertTrue(InteractiveMiniGameSupport.mechanicsReady(config, state));
    }

    @Test
    void scratchCardEnforcesThresholdBeforeRevealingPrize() {
        ObjectNode config = JSON.createObjectNode();
        config.putObject("scratchCard")
                .put("enabled", true)
                .put("title", "刮刮乐")
                .put("threshold", 0.45)
                .put("prizeText", "秘密口令：星海")
                .put("xp", 18);
        ObjectNode state = JSON.createObjectNode();
        InteractiveMiniGameSupport.initState(config, state);

        assertThrows(IllegalArgumentException.class,
                () -> InteractiveMiniGameSupport.submitScratchCard(config, state, Map.of("progress", 0.30d)));
        assertFalse(InteractiveMiniGameSupport.mechanicsReady(config, state));

        assertEquals(18, InteractiveMiniGameSupport.submitScratchCard(config, state, Map.of("progress", 0.50d)));
        assertTrue(InteractiveMiniGameSupport.mechanicsReady(config, state));
        assertThrows(IllegalStateException.class,
                () -> InteractiveMiniGameSupport.submitScratchCard(config, state, Map.of("progress", 0.80d)));
    }
}
