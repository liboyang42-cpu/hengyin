package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayTemplateRulesTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode theme() {
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 8);
        theme.putArray("items").addObject().put("id", "tool").put("kind", "ITEM").put("name", "工具").put("image", "https://www.chengyinhub.com/favicon.ico").put("slotCost", 1);
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
        ObjectNode act = game.putArray("acts").addObject().put("actKey", "walk"); act.putArray("questKeys").add("gate").add("park"); act.putObject("completionRule").put("type", "ALL");
        game.putArray("quests").addObject().put("questKey", "gate").put("actKey", "walk").put("nodeId", 101).put("locationKey", "gate").put("settlementSlot", "gate");
        ((com.fasterxml.jackson.databind.node.ArrayNode) game.get("quests")).addObject().put("questKey", "park").put("actKey", "walk").put("nodeId", 102).put("locationKey", "park").put("settlementSlot", "park");
        return theme;
    }
    private ObjectNode blocks(ObjectNode rule) { ObjectNode blocks = json.createObjectNode(); blocks.putObject("101").set("interactionRules", rule); return blocks; }
    private Map<Long, String> actual() { Map<Long, String> keys = new HashMap<>(); keys.put(101L, "walk"); keys.put(102L, "walk"); return keys; }
    private ObjectNode rule() { return json.createObjectNode().put("hpCost", 2).put("limit", 3); }
    private JsonNode facility(JsonNode compiled) { return compiled.path("gameplayRuntime").path("facilities").get(0); }
    @Test void optionalCostsCompileIntoFacilityAndRejectFixedOrUnknownCosts() {
        ObjectNode rule = json.createObjectNode();
        rule.putArray("costOptions").addObject().put("hpCost", 1);
        rule.withArray("costOptions").addObject().putArray("resourceCosts").addObject().put("itemId", "tool").put("quantity", 2);
        assertEquals(2, facility(GameplayTemplateRules.merge(theme(), blocks(rule), actual())).path("costOptions").size());
        rule.put("hpCost", 0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule)).getMessage().contains("任选费用与固定费用不能同时配置"));
        rule.remove("hpCost"); ((ObjectNode) rule.path("costOptions").get(1).path("resourceCosts").get(0)).put("itemId", "foreign");
        assertNotNull(GameplayTemplateRules.normalize(rule));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(rule), actual()));
    }

    @Test void grantOptionsCompileIntoTheBoundQuestChoicesAndStayExclusiveWithFixedGrants() {
        ObjectNode theme = theme(); theme.withArray("items").addObject().put("id", "ration").put("kind", "ITEM").put("name", "口粮").put("image", "https://www.chengyinhub.com/favicon.ico").put("slotCost", 1);
        ObjectNode rule = json.createObjectNode().put("actionCost", 1);
        com.fasterxml.jackson.databind.node.ArrayNode options = rule.putArray("grantOptions");
        options.addObject().put("label", "口粮").putArray("grants").addObject().put("itemId", "ration").put("quantity", 3);
        ObjectNode mixed = options.addObject(); mixed.putArray("grants").addObject().put("itemId", "ration").put("quantity", 1); mixed.withArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        ObjectNode compiled = GameplayTemplateRules.merge(theme, blocks(rule), actual());
        JsonNode quest = compiled.path("gameplayRuntime").path("quests").get(0);
        assertEquals("tpl_node_101", quest.path("facilityKey").asText());
        assertEquals(2, quest.path("choices").size());
        assertEquals("opt_1", quest.path("choices").get(0).path("choiceKey").asText());
        assertEquals("口粮", quest.path("choices").get(0).path("label").asText());
        assertEquals(3, quest.path("choices").get(0).path("grants").get(0).path("quantity").asInt());
        assertFalse(facility(compiled).has("grantOptions"), "配包只在任务上，设施不重复发放");
        assertFalse(facility(compiled).has("grants"));
        ObjectNode recompiled = GameplayTemplateRules.merge(compiled, blocks(json.createObjectNode().put("actionCost", 1)), actual());
        assertFalse(recompiled.path("gameplayRuntime").path("quests").get(0).has("choices"), "移除任选奖励后模板生成的配包一并移除");
        ObjectNode both = rule.deepCopy(); both.putArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(both)).getMessage().contains("任选奖励与固定奖励不能同时配置"));
        ObjectNode one = json.createObjectNode(); one.putArray("grantOptions").addObject().putArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(one));
        ObjectNode legacy = theme(); ((ObjectNode) legacy.path("gameplayRuntime").path("quests").get(0)).putArray("choices").addObject().put("choiceKey", "food").putArray("grants").addObject().put("itemId", "tool").put("quantity", 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(legacy, blocks(rule), actual())).getMessage().contains("已配置配包"), "主题原有配包不被模板覆盖");
        ObjectNode keep = GameplayTemplateRules.merge(legacy, blocks(json.createObjectNode().put("actionCost", 1)), actual());
        assertEquals("food", keep.path("gameplayRuntime").path("quests").get(0).path("choices").get(0).path("choiceKey").asText(), "无模板配包时原有配包保留");
    }

    @Test void extraNodeFacilitiesStayOpenAfterTheirActWhileOrdinaryFacilitiesDoNot() {
        ObjectNode theme = theme(); ObjectNode game = (ObjectNode) theme.get("gameplayRuntime");
        ObjectNode later = ((com.fasterxml.jackson.databind.node.ArrayNode) game.get("acts")).addObject().put("actKey", "later"); later.putArray("questKeys").add("park"); later.putObject("completionRule").put("type", "ALL");
        ((com.fasterxml.jackson.databind.node.ArrayNode) game.path("acts").get(0).get("questKeys")).remove(1);
        ((ObjectNode) game.path("quests").get(1)).put("actKey", "later");
        Map<Long, String> keys = new HashMap<>(); keys.put(101L, "walk"); keys.put(102L, "later");
        ObjectNode compiled = GameplayTemplateRules.merge(theme, blocks(json.createObjectNode().put("limit", 2)), keys);
        JsonNode facility = facility(compiled);
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.getThemeRuntime().with("gameplay").put("actIndex", 1);
        assertFalse(GameplayCooperationEngine.openExtraFacility(compiled, state, facility), "普通任务绑定的工具只在本幕");
        ((ObjectNode) compiled.path("gameplayRuntime").path("quests").get(0)).put("extraOnly", true);
        assertTrue(GameplayCooperationEngine.openExtraFacility(compiled, state, facility), "额外节点的工具在后续幕仍可用");
        state.getThemeRuntime().with("gameplay").put("actIndex", 0);
        assertFalse(GameplayCooperationEngine.openExtraFacility(compiled, state, facility), "当前幕走原有判断，不提前开放");
    }

    @Test void frozenAdapterUsesRealBindingsAndChargesOnlyFacilityPolicy() {
        ObjectNode source = theme(), blocks = blocks(rule());
        ObjectNode compiled = GameplayTemplateRules.merge(source, blocks, actual());
        JsonNode facility = facility(compiled), first = compiled.path("gameplayRuntime").path("quests").get(0);
        assertEquals(101, facility.path("operationNodeId").asLong()); assertEquals("walk", facility.path("actKey").asText());
        assertEquals("tpl_node_101", first.path("facilityKey").asText()); assertEquals(2, facility.path("hpCost").asInt());
        assertFalse(first.has("hpCost")); assertEquals(3, facility.path("limit").asInt());
        assertFalse(source.path("gameplayRuntime").has("facilities")); assertFalse(source.path("gameplayRuntime").path("quests").get(0).has("facilityKey"));
        blocks.with("101").with("interactionRules").put("hpCost", 9); assertEquals(2, facility.path("hpCost").asInt());
    }
    @Test void absentRulesLeaveLegacyUntouchedAndRulesRequireActualGameplay() {
        ObjectNode legacy = json.createObjectNode().put("schemaVersion", 1);
        assertEquals(legacy, GameplayTemplateRules.merge(legacy, json.createObjectNode(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(legacy, blocks(rule()), actual()));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(rule()), Collections.singletonMap(102L, "walk")));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(rule()), Collections.singletonMap(101L, "foreign")));
    }
    @Test void syntaxRejectsForgedIdsInfiniteLimitsAndSystemAttributeWrites() {
        for (String field : Arrays.asList("facilityKey", "settlementSlot", "operationNodeId", "actKey", "fees", "script")) {
            ObjectNode rule = rule().put(field, "forged"); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule));
        }
        for (int limit : new int[] {0, -1, 100}) { ObjectNode rule = rule().put("limit", limit); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule)); }
        ObjectNode fractional = rule().put("limit", 1.5); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(fractional));
        ObjectNode huge = rule().put("hpCost", Long.MAX_VALUE); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(huge));
        ObjectNode systemCost = rule(); systemCost.putArray("statCosts").addObject().put("statKey", "sys.hp").put("amount", 1); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(systemCost));
        ObjectNode systemEffect = rule(); systemEffect.putArray("effects").addObject().put("var", "sys.luck").put("op", "INC").put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(systemEffect));
    }
    @Test void duplicateSyntaxAndUnregisteredThemeReferencesFailBeforeRun() {
        ObjectNode duplicate = rule(); duplicate.putArray("resourceCosts").addObject().put("itemId", "tool").put("quantity", 1); ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("resourceCosts")).addObject().put("itemId", "tool").put("quantity", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(duplicate));
        ObjectNode unknownItem = rule(); unknownItem.putArray("grants").addObject().put("itemId", "foreign").put("quantity", 1);
        assertNotNull(GameplayTemplateRules.normalize(unknownItem)); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(unknownItem), actual()));
        ObjectNode numeric = rule(); numeric.putArray("statCosts").addObject().put("statKey", "counter.trust").put("amount", 1);
        ObjectNode compiled = GameplayTemplateRules.merge(theme(), blocks(numeric), actual());
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(compiled, new HashSet<>(actual().keySet()), Collections.emptySet()));
        ThemeRuntimeConfig.assertReferences(compiled, new HashSet<>(actual().keySet()), Collections.singleton("counter.trust"));
    }
    @Test void repeatedCompilationUpdatesCanonicalPolicyWhileManualFacilitiesCannotDoubleCharge() {
        ObjectNode source = theme(), compiled = GameplayTemplateRules.merge(source, blocks(rule()), actual());
        assertEquals(compiled, GameplayTemplateRules.merge(compiled, blocks(rule()), actual()));
        ObjectNode updated = GameplayTemplateRules.merge(compiled, blocks(rule().put("hpCost", 4)), actual());
        assertEquals(1, updated.path("gameplayRuntime").path("facilities").size()); assertEquals(4, facility(updated).path("hpCost").asInt()); assertEquals(2, facility(compiled).path("hpCost").asInt());
        source.with("gameplayRuntime").putArray("facilities").addObject().put("facilityKey", "manual").put("actKey", "walk").put("scope", "OWNER").put("targetScope", "OWNER").put("settlementSlot", "manual").put("operationNodeId", 101).put("limit", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(source, blocks(rule()), actual()));
        ObjectNode forged = theme(); forged.with("gameplayRuntime").putArray("facilities").addObject().put("facilityKey", "tpl_node_101").put("settlementSlot", "tpl_node_101").put("actKey", "walk").put("operationNodeId", 101).put("scope", "OWNER").put("targetScope", "OWNER").put("limit", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(forged, blocks(rule()), actual()));
    }
    @Test void participantToolsUseWorldRulesWithoutBindingSingleTeamQuest() {
        ObjectNode shared = theme(); shared.with("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED");
        ObjectNode rule = rule().put("scope", "WORLD").put("targetScope", "PARTICIPANTS");
        ObjectNode compiled = GameplayTemplateRules.merge(shared, blocks(rule), actual());
        assertEquals("PARTICIPANTS", facility(compiled).path("targetScope").asText());
        assertFalse(compiled.path("gameplayRuntime").path("quests").get(0).has("facilityKey"));
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(theme(), blocks(rule), actual()));
        shared.with("gameplayRuntime").put("worldMode", "TEAM_INDEPENDENT"); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(shared, blocks(rule), actual()));
    }
    @Test void disablingCanonicalRulesRemovesTheirFeeAndBindingButKeepsOtherFacilities() {
        ObjectNode compiled = GameplayTemplateRules.merge(theme(), blocks(rule()), actual());
        ((com.fasterxml.jackson.databind.node.ArrayNode) compiled.path("gameplayRuntime").path("facilities")).addObject().put("facilityKey", "manual").put("actKey", "walk").put("scope", "OWNER").put("targetScope", "OWNER").put("settlementSlot", "manual").put("limit", 1);
        ObjectNode blocks = json.createObjectNode(); blocks.putObject("101").put("schemaVersion", 1);
        ObjectNode disabled = GameplayTemplateRules.merge(compiled, blocks, actual());
        assertEquals(1, disabled.path("gameplayRuntime").path("facilities").size()); assertEquals("manual", facility(disabled).path("facilityKey").asText());
        assertFalse(disabled.path("gameplayRuntime").path("quests").get(0).has("facilityKey"));
        assertEquals(2, compiled.path("gameplayRuntime").path("facilities").size());
    }
    @Test void newCopiedNodeIdentityIsDerivedInsteadOfInheritingSourceReceiptSlot() {
        ObjectNode source = theme(); ((ObjectNode) source.path("gameplayRuntime").path("quests").get(0)).put("nodeId", 201);
        ObjectNode blocks = json.createObjectNode(); blocks.putObject("201").set("interactionRules", rule());
        ObjectNode compiled = GameplayTemplateRules.merge(source, blocks, Collections.singletonMap(201L, "walk"));
        assertEquals("tpl_node_201", facility(compiled).path("facilityKey").asText()); assertEquals(201, facility(compiled).path("operationNodeId").asLong());
    }
    @Test void authorPolicySurvivesValidationWhilePublicDetailsHideEffectsAndHiddenCosts() throws Exception {
        ObjectNode block = json.createObjectNode().put("schemaVersion", 1); ObjectNode rules = rule();
        rules.putArray("statCosts").addObject().put("statKey", "counter.secret").put("amount", 2);
        rules.putArray("effects").addObject().put("var", "counter.secret").put("op", "INC").put("value", 3); block.set("interactionRules", rules);
        JsonNode saved = json.readTree(new AdvancedGameConfigValidator().validateAndNormalize(block.toString()));
        assertEquals("counter.secret", saved.path("interactionRules").path("effects").get(0).path("var").asText());
        JsonNode publicView = json.readTree(AdvancedGamePublicProjection.safeConfigJson(saved.toString()));
        assertFalse(publicView.has("interactionRules")); assertFalse(publicView.toString().contains("counter.secret"));
    }
}
