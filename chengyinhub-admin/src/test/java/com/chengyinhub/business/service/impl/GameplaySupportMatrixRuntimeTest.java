package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GameplaySupportMatrixRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings = {"scan", "qaText", "qaPick", "sort", "match", "classify"})
    void everyProofTypeSavesPublishesAndChargesTheBoundTeamResource(String key) throws Exception {
        GameplayReskinFixture f = new GameplayReskinFixture(); CmsMemberTemplate template = f.templates.get(501L);
        ObjectNode config = GameplaySupportMatrixTest.config(key);
        config.set("interactionRules", JSON.readTree(template.getAdvancedConfigJson()).path("interactionRules"));
        template.setValidationMethod("scan".equals(key) ? 4 : key.startsWith("qa") ? "qaText".equals(key) ? 1 : 3 : 0);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        JsonNode skin = GameplayReskinFixture.variants().get(0); f.publish(skin); f.startRun(); f.unlock("a1");
        java.util.List<com.chengyinhub.business.domain.ThemeStateKey> declarations=f.keyMapper.selectByTopicId(f.topic.getId());
        assertFalse(declarations.isEmpty());
        assertEquals(JSON.valueToTree(declarations),JSON.valueToTree(f.keyMapper.selectByTopicIdForUpdate(f.topic.getId())));
        for(com.chengyinhub.business.domain.ThemeStateKey row:declarations) {
            assertEquals(f.topic.getId(),row.getTopicId());assertNotNull(row.getId());assertTrue(row.getId()>0);
        }
        if (!"scan".equals(key)) assertTrue(f.route.verifyGuidedRouteAnswer(f.snapshot, f.node(1001), GameplaySupportMatrixTest.answers(key)));
        f.resolve(7, 1001, "food", null);
        assertEquals(skin.path("initialHp").asInt() - 1, f.hp(7)); assertEquals(3, f.stock(7, "ration"));
        int hp = f.hp(7), stock = f.stock(7, "ration");
        f.command(7, "QUEST_RESOLVE", f.payload().put("questKey", "q1001").put("choiceKey", "food"));
        assertEquals(hp, f.hp(7)); assertEquals(stock, f.stock(7, "ration"));
    }

    @Test void publishRejectsUnsupportedOrdinarySensorWithNodeTypeAndTool() throws Exception {
        GameplayReskinFixture f = new GameplayReskinFixture(); CmsMemberTemplate template = f.templates.get(501L);
        ObjectNode config = JSON.readTree(template.getAdvancedConfigJson()).deepCopy(); config.remove("scan");
        config.putObject("ballShake").put("enabled", true).put("goal", 10).put("timed", false).put("seconds", 12);
        template.setAdvancedConfigJson(new AdvancedGameConfigValidator().validateAndNormalize(config.toString()));
        String message = assertThrows(IllegalArgumentException.class, () -> f.publish(GameplayReskinFixture.variants().get(0))).getMessage();
        assertTrue(message.matches("节点 [0-9]+.*玩法 shake 工具 costs.*"), message);
    }

    @Test void legacyChallengeAnswerWithoutAdvancedProofCannotPublishAndFixedReferenceCanBeJudged() throws Exception {
        GameplayReskinFixture f = new GameplayReskinFixture();
        f.templates.get(505L).setAdvancedConfigJson("{\"schemaVersion\":1}");
        String message = assertThrows(IllegalArgumentException.class, () -> f.publish(GameplayReskinFixture.variants().get(0))).getMessage();
        assertTrue(message.matches("节点 [0-9]+.*恢复问答.*zeroChallenge.*"), message);
        GameplayReskinFixture fixed = new GameplayReskinFixture(); fixed.publish(GameplayReskinFixture.variants().get(0));
        ObjectNode answers = JSON.createObjectNode(); answers.putObject("qa").put("input", "错误口令");
        long nodeId = fixed.snapshot.path("themeConfig").path("gameplayRuntime").path("survival").path("zeroChallenge").path("alternativeNodeIds").path(0).asLong();
        assertFalse(fixed.route.verifyGuidedRouteAnswer(fixed.snapshot, nodeId, answers));
        answers.with("qa").put("input", "恢复");
        assertTrue(fixed.route.verifyGuidedRouteAnswer(fixed.snapshot, nodeId, answers));
    }
}
