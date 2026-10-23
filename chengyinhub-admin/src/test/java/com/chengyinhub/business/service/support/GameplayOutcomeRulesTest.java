package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameplayOutcomeRulesTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
    private ObjectNode template() {
        ObjectNode root = json.createObjectNode().put("schemaVersion", 1);
        root.putObject("qa").put("enabled", true).put("mode", "TYPE").put("title", "Year?").put("answerText", "1930").put("maxTries", 0);
        root.putObject("interactionRules").putArray("resourceSlots").addObject().put("key", "vital").put("kind", "VALUE").put("label", "Vital").put("required", true);
        root.putArray("effects").addObject().put("var", "vital").put("op", "INC").put("value", 1);
        ObjectNode outcomes = root.putObject("outcomeRules").put("version", 1);
        outcomes.putObject("pass").put("reply", "Correct").putArray("costs").addObject().put("statKey", "vital").put("amount", 1);
        outcomes.putObject("fail").putArray("costs").addObject().put("statKey", "vital").put("amount", 2);
        return root;
    }
    @Test void costOnlySlotRoundTripsAndDoesNotBecomeAStandaloneFeeTool() throws Exception {
        ObjectNode authored = template(); authored.remove("effects");
        String normalized = validator.validateAndNormalize(authored.toString());
        assertEquals(authored.path("outcomeRules"), json.readTree(normalized).path("outcomeRules"));
        assertEquals("vital", json.readTree(normalized).path("outcomeRules").path("fail").path("costs").path(0).path("statKey").asText());
        assertEquals(normalized, validator.validateAndNormalize(normalized));
        assertFalse(json.readTree(normalized).path("interactionRules").has("hpCost"));
    }
    @Test void passAndFailFeedbackKeepUpToThreeImages() throws Exception {
        ObjectNode root = template();
        root.with("outcomeRules").with("pass").putArray("images").add("https://cdn.example.com/a.jpg").add("/profile/upload/b.png").add("media-asset:12");
        root.with("outcomeRules").with("fail").putArray("images").add("https://cdn.example.com/c.jpg");
        ObjectNode normalized = (ObjectNode) json.readTree(validator.validateAndNormalize(root.toString()));
        assertEquals(3, normalized.path("outcomeRules").path("pass").path("images").size());
        assertEquals("https://cdn.example.com/c.jpg", normalized.path("outcomeRules").path("fail").path("images").path(0).asText());
    }
    @Test void rejectsTooManyOrUnsafeFeedbackImages() {
        ObjectNode four = template();
        four.with("outcomeRules").with("pass").putArray("images").add("/a.png").add("/b.png").add("/c.png").add("/d.png");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(four.toString())).getMessage().contains("最多 3 张"));
        ObjectNode plain = template();
        plain.with("outcomeRules").with("fail").putArray("images").add("http://insecure.example.com/a.png");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(plain.toString()));
        ObjectNode notList = template();
        notList.with("outcomeRules").with("pass").put("images", "/a.png");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(notList.toString()));
    }
    @Test void backgroundMusicIsPresenceBasedAndValidated() throws Exception {
        ObjectNode root = template();
        root.putObject("backgroundMusic").put("title", "老城茶馆").put("audioUrl", "https://cdn.example.com/tea.mp3").put("loop", true);
        assertEquals("老城茶馆", json.readTree(validator.validateAndNormalize(root.toString())).path("backgroundMusic").path("title").asText());
        ObjectNode flagged = template();
        flagged.putObject("backgroundMusic").put("enabled", true).put("audioUrl", "https://cdn.example.com/tea.mp3");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(flagged.toString()));
        ObjectNode silent = template();
        silent.putObject("backgroundMusic").put("title", "无音频");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(silent.toString()));
        ObjectNode insecure = template();
        insecure.putObject("backgroundMusic").put("audioUrl", "http://cdn.example.com/tea.mp3");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(insecure.toString()));
    }
    @Test void rejectsMalformedOutcomeAndExplicitLegacyCostConflict() {
        ObjectNode root = template();
        ((ObjectNode) root.path("outcomeRules").path("fail").path("costs").get(0)).put("amount", -1);
        final ObjectNode negative = root;
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(negative.toString()));
        root = template(); root.with("interactionRules").put("hpCost", 1); final ObjectNode conflict = root;
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(conflict.toString()));
        root = template(); root.with("outcomeRules").put("passed", true); final ObjectNode extra = root;
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(extra.toString()));
    }
    @Test void rejectsFakeAfterTargetsAndKeepsOldTimeFields() throws Exception {
        ObjectNode root = template(); root.with("outcomeRules").with("fail").put("nextStepId", "foreign");
        final ObjectNode foreign = root;
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(foreign.toString()));
        root = template(); root.with("outcomeRules").with("pass").putArray("effects").addObject().put("var", "vital").put("op", "SET").put("value", 3);
        final ObjectNode illegal = root;
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(illegal.toString()));
        root = template(); root.with("qa").put("maxTries", 0); root.putObject("oldMetadata").put("timeLimit", 17);
        assertEquals(17, json.readTree(validator.validateAndNormalize(root.toString())).path("oldMetadata").path("timeLimit").asInt());
    }
    @Test void rejectsMultipleDrawLocalAfterAndAllowsFeedbackComposition() {
        ObjectNode root=template();root.remove("qa");ObjectNode random=root.putObject("random").put("enabled",true).put("drawCount",2);
        random.putArray("items").addObject().put("id","a").put("label","A").putObject("after");
        assertTrue(assertThrows(IllegalArgumentException.class,()->GameplayOutcomeRules.validateBlock(root)).getMessage().contains("多张抽卡"));
        random.put("drawCount",1);assertDoesNotThrow(()->GameplayOutcomeRules.validateBlock(root));
        root.putObject("branch").put("enabled",true);assertDoesNotThrow(()->GameplayOutcomeRules.validateBlock(root));
    }

    @Test void r5AfterCanReferenceOnlyExistingFeedbackStepsWithoutBranchAfter() throws Exception {
        ObjectNode root=template();root.with("outcomeRules").with("pass").put("nextStepId","feedback");ObjectNode step=root.putObject("branch").put("enabled",true).put("startStepId","feedback").putArray("steps").addObject().put("id","feedback").put("title","反馈").put("body","文字").put("terminal",true);step.putArray("options");
        assertEquals("feedback",json.readTree(validator.validateAndNormalize(root.toString())).path("outcomeRules").path("pass").path("nextStepId").asText());
        step.putArray("options").addObject().put("id","bad").put("label","选项").putObject("after").put("reply","不能再次收费");assertThrows(IllegalArgumentException.class,()->GameplayOutcomeRules.validateBlock(root));
    }
    @Test void timeoutCompletedIsOptInAndRoundTripsOnlyForR5() throws Exception {
        ObjectNode root=template();root.putObject("timer").put("enabled",true).put("durationSeconds",30).put("timeoutResult","COMPLETED");
        assertEquals("COMPLETED",json.readTree(validator.validateAndNormalize(root.toString())).path("timer").path("timeoutResult").asText());
        root.remove("outcomeRules");assertThrows(IllegalArgumentException.class,()->validator.validateAndNormalize(root.toString()));
    }
    @Test void r5PlanAllowsOwnerAfterButRejectsSharedScopeAndLegacyCostStacking() {
        ObjectNode root=template();root.putObject("interactionTools").putArray("plans").addObject().put("planKey","vote");
        assertDoesNotThrow(()->GameplayOutcomeRules.validateBlock(root));
        ObjectNode nodes=json.createObjectNode();nodes.set("11",root);ObjectNode theme=json.createObjectNode();ObjectNode plan=theme.putObject("gameplayRuntime").putArray("plans").addObject().put("operationNodeId",11).put("endingKey","vote");
        assertDoesNotThrow(()->GameplayOutcomeRules.assertBindings(theme,nodes));
        root.with("interactionRules").put("targetScope","WORLD");assertThrows(IllegalArgumentException.class,()->GameplayOutcomeRules.validateBlock(root));assertThrows(IllegalArgumentException.class,()->GameplayOutcomeRules.assertBindings(theme,nodes));
        root.with("interactionRules").put("targetScope","OWNER").put("hpCost",1);assertThrows(IllegalArgumentException.class,()->GameplayOutcomeRules.validateBlock(root));
        root.with("interactionRules").remove("hpCost");plan.put("hpCost",1);assertThrows(IllegalArgumentException.class,()->GameplayOutcomeRules.assertBindings(theme,nodes));
        root.remove("outcomeRules");assertDoesNotThrow(()->GameplayOutcomeRules.assertBindings(theme,nodes));
    }
}
