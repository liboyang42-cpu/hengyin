package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结果规则的主玩法识别(2026-11-18 实测 P0):
 * (1) 逐字猜词是主玩法;(2) 拍照与另一个主玩法同开时是证据步,另一个主玩法决定 PASS/FAIL,单开拍照仍是主玩法;
 * (3) 只开分支/扫码却配了结果规则 → 保存/发布期给出明确错误,不留到开局才失败。夹具为中性数据。
 */
class GameplayOutcomeMainKitTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private static final String RULES = "\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\"继续\"},\"fail\":{\"reply\":\"还不对，再看看题面\"}}";
    private static final String QA = "\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"示例站点\",\"answerText\":\"ABC\"}";
    private static final String PHOTO = "\"photoCheck\":{\"enabled\":true,\"mode\":\"CARD\",\"fallback\":\"pass\"}";

    private JsonNode block(String body) throws Exception {
        return json.readTree("{\"schemaVersion\":1," + body + "}");
    }

    @Test
    void hangmanIsAMainGameplay() throws Exception {
        JsonNode hangman = block("\"hangman\":{\"enabled\":true,\"answer\":\"ABC\"}," + RULES);
        assertEquals("hangman", GameplayOutcomeRules.mainKit(hangman));
        assertDoesNotThrow(() -> GameplayOutcomeRules.validateBlock(hangman));
        assertDoesNotThrow(() -> validator.validateAndNormalize(hangman.toString()), "开局同一入口:validateAndNormalize 不再拒绝");
        JsonNode typeIn = block("\"typeIn\":{\"enabled\":true,\"title\":\"t\",\"target\":\"ABC\",\"seconds\":30}," + RULES);
        assertEquals("typeIn", GameplayOutcomeRules.mainKit(typeIn));
    }

    @Test
    void photoBesideAnotherMainKitIsOnlyAnEvidenceStep() throws Exception {
        JsonNode both = block(QA + "," + PHOTO + "," + RULES);
        assertEquals("qa", GameplayOutcomeRules.mainKit(both));
        assertTrue(GameplayOutcomeRules.isEvidenceStep(both, "photoCheck"));
        assertFalse(GameplayOutcomeRules.isEvidenceStep(both, "qa"));
        assertDoesNotThrow(() -> GameplayOutcomeRules.validateBlock(both));

        JsonNode photoOnly = block(PHOTO + "," + RULES);
        assertEquals("photoCheck", GameplayOutcomeRules.mainKit(photoOnly), "单开拍照仍是主玩法");
        assertFalse(GameplayOutcomeRules.isEvidenceStep(photoOnly, "photoCheck"));

        JsonNode twoMains = block(QA + ",\"hangman\":{\"enabled\":true,\"answer\":\"ABC\"}," + RULES);
        assertNull(GameplayOutcomeRules.mainKit(twoMains));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> GameplayOutcomeRules.validateBlock(twoMains));
        assertTrue(refused.getMessage().contains("qa") && refused.getMessage().contains("hangman"));
    }

    @Test
    void branchOnlyWithOutcomeRulesGetsAClearSaveTimeError() throws Exception {
        JsonNode branchOnly = block("\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":[{\"id\":\"start\",\"terminal\":true,\"options\":[]}]}," + RULES);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(branchOnly.toString()));
        assertTrue(refused.getMessage().contains("分支不是主玩法"), refused.getMessage());
        assertTrue(refused.getMessage().contains("问答"), "告诉作者怎么改");
    }
}
