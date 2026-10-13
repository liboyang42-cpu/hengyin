package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayTemplateResourceSlotsTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode rule() {
        ObjectNode rule = json.createObjectNode();
        ArrayNode slots = rule.putArray("resourceSlots");
        slots.addObject().put("key", "energy").put("kind", "VALUE").put("label", "消耗值域").put("required", true);
        slots.addObject().put("key", "supply").put("kind", "ITEM").put("label", "所需物品").put("required", true);
        rule.putArray("statCosts").addObject().put("statKey", "energy").put("amount", 2);
        rule.putArray("resourceCosts").addObject().put("itemId", "supply").put("quantity", 1);
        return rule;
    }
    private ObjectNode theme(ObjectNode rule, String value, String item) {
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 20).put("actionLimit", 20);
        ArrayNode items = theme.putArray("items");
        for (String id : Arrays.asList("blue", "green")) items.addObject().put("id", id).put("kind", "ITEM").put("name", id).put("image", "https://www.chengyinhub.com/favicon.ico");
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER");
        ObjectNode act = game.putArray("acts").addObject().put("actKey", "walk");
        act.putArray("questKeys").add("gate"); act.putObject("completionRule").put("type", "ALL");
        game.putArray("quests").addObject().put("questKey", "gate").put("actKey", "walk").put("nodeId", 101).put("locationKey", "gate").put("settlementSlot", "gate");
        ObjectNode binding = theme.putObject("templateBindings").putObject("101").put("templateId", 501).put("slotDigest", GameplayTemplateRules.slotDigest(rule.path("resourceSlots")));
        binding.putObject("values").put("energy", value); binding.putObject("items").put("supply", item);
        return theme;
    }
    private ObjectNode nodes(ObjectNode rule) {
        ObjectNode nodes = json.createObjectNode(); nodes.putObject("101").put("schemaVersion", 1).set("interactionRules", rule); return nodes;
    }
    private ObjectNode binding(ObjectNode theme) { return theme.with("templateBindings").with("101"); }
    private ObjectNode merge(ObjectNode theme, ObjectNode rule) {
        return GameplayTemplateRules.merge(theme, nodes(rule), Collections.singletonMap(101L, "walk"), Collections.singletonMap(101L, 501L));
    }
    private JsonNode facility(JsonNode theme) { return theme.path("gameplayRuntime").path("facilities").get(0); }
    private void rejected(ObjectNode theme, ObjectNode rule, String slot) {
        String message = assertThrows(IllegalArgumentException.class, () -> merge(theme, rule)).getMessage();
        assertTrue(message.contains("101"), message); assertTrue(message.contains(slot), message);
    }
    private JourneyRules values() {
        return JourneyRules.parse("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":5,\"min\":0,\"max\":20}]}");
    }

    @Test void sameTemplateResolvesToDifferentThemesWithoutChangingAuthorData() {
        ObjectNode rule = rule(), original = rule.deepCopy();
        ObjectNode first = theme(rule, "sys.hp", "blue"), second = theme(rule, "counter.focus", "green");
        ObjectNode compiledFirst = merge(first, rule), compiledSecond = merge(second, rule);
        assertEquals(2, facility(compiledFirst).path("hpCost").asInt()); assertFalse(facility(compiledFirst).has("statCosts"));
        assertEquals("counter.focus", facility(compiledSecond).path("statCosts").get(0).path("statKey").asText());
        assertEquals("blue", facility(compiledFirst).path("resourceCosts").get(0).path("itemId").asText());
        assertEquals("green", facility(compiledSecond).path("resourceCosts").get(0).path("itemId").asText());
        assertFalse(compiledFirst.has("templateBindings")); assertFalse(facility(compiledFirst).has("resourceSlots"));
        assertEquals(original, rule); assertTrue(first.has("templateBindings"));
    }
    @Test void builtinCostsUseLegalFieldsAndAggregateWithLiteralCosts() {
        String[] targets = {"sys.hp", "sys.luck", "sys.actions"}, fields = {"hpCost", "luckCost", "actionCost"};
        for (int index = 0; index < targets.length; index++) {
            ObjectNode rule = rule(); rule.put(fields[index], 3);
            JsonNode compiled = facility(merge(theme(rule, targets[index], "blue"), rule));
            assertEquals(5, compiled.path(fields[index]).asInt()); assertFalse(compiled.has("statCosts"));
        }
    }
    @Test void optionalGroupsResolveSeparatelyAndMergeAliasedResourcesWithinAGroup() {
        ObjectNode rule = rule(); rule.remove(Arrays.asList("statCosts", "resourceCosts"));
        ArrayNode options = rule.putArray("costOptions");
        for (int amount : Arrays.asList(2, 4)) {
            ObjectNode option = options.addObject(); option.putArray("statCosts").addObject().put("statKey", "energy").put("amount", amount);
            ArrayNode costs = option.putArray("resourceCosts"); costs.addObject().put("itemId", "supply").put("quantity", 1); costs.addObject().put("itemId", "blue").put("quantity", 2);
        }
        JsonNode compiled = facility(merge(theme(rule, "sys.hp", "blue"), rule));
        assertEquals(2, compiled.path("costOptions").get(0).path("hpCost").asInt());
        assertEquals(4, compiled.path("costOptions").get(1).path("hpCost").asInt());
        assertEquals(1, compiled.path("costOptions").get(0).path("resourceCosts").size());
        assertEquals(3, compiled.path("costOptions").get(0).path("resourceCosts").get(0).path("quantity").asInt());
    }
    @Test void allRegisteredReferencesResolveConsistentlyAndEffectsKeepTheirOrder() {
        ObjectNode rule = rule();
        rule.putArray("statChanges").addObject().put("statKey", "energy").put("delta", 2);
        rule.putArray("grants").addObject().put("itemId", "supply").put("quantity", 1);
        ArrayNode when = rule.putArray("when"); when.addObject().put("var", "energy").put("op", "GTE").put("value", 1);
        when.addObject().put("op", "ITEM_HELD").put("itemId", "supply").put("quantity", 1);
        ArrayNode effects = rule.putArray("effects"); effects.addObject().put("var", "energy").put("op", "SET").put("value", 3); effects.addObject().put("var", "energy").put("op", "INC").put("value", 2);
        JsonNode compiled = facility(merge(theme(rule, "counter.focus", "green"), rule));
        assertEquals("counter.focus", compiled.path("statChanges").get(0).path("statKey").asText());
        assertEquals("green", compiled.path("grants").get(0).path("itemId").asText());
        assertEquals("green", compiled.path("when").get(1).path("itemId").asText());
        assertEquals("counter.focus", compiled.path("when").get(0).path("var").asText());
        assertEquals("SET", compiled.path("effects").get(0).path("op").asText()); assertEquals("INC", compiled.path("effects").get(1).path("op").asText());
        assertEquals("counter.focus", compiled.path("effects").get(1).path("var").asText());
    }
    @Test void hpChangesUseHpDeltaAndUnsupportedSystemEffectsAreRejected() {
        ObjectNode rule = rule(); rule.putArray("statChanges").addObject().put("statKey", "energy").put("delta", 3);
        JsonNode compiled = facility(merge(theme(rule, "sys.hp", "blue"), rule));
        assertEquals(3, compiled.path("hpDelta").asInt()); assertFalse(compiled.has("statChanges"));
        rejected(theme(rule, "sys.luck", "blue"), rule, "energy"); rejected(theme(rule, "sys.actions", "blue"), rule, "energy");
        rule.putArray("effects").addObject().put("var", "energy").put("op", "SET").put("value", 1);
        rejected(theme(rule, "sys.hp", "blue"), rule, "energy");
    }
    @Test void missingStaleAndForgedIdentityBindingsNameTheNodeAndSlot() {
        ObjectNode rule = rule(), theme = theme(rule, "sys.hp", "blue");
        binding(theme).with("values").remove("energy"); rejected(theme, rule, "energy");
        theme = theme(rule, "sys.hp", "blue"); binding(theme).put("slotDigest", String.join("", Collections.nCopies(64, "0"))); rejected(theme, rule, "energy");
        theme = theme(rule, "sys.hp", "blue"); binding(theme).put("templateId", 502); rejected(theme, rule, "energy");
        theme = theme(rule, "sys.hp", "blue"); theme.remove("templateBindings"); rejected(theme, rule, "energy");
        ObjectNode valid = theme(rule, "sys.hp", "blue");
        String message = assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.merge(valid, nodes(rule), Collections.singletonMap(101L, "walk"))).getMessage();
        assertTrue(message.contains("101") && message.contains("energy") && message.contains("缺少可信模板身份"));
    }
    @Test void wrongKindDuplicateTargetsAndUnusedDeclarationsFailClosed() {
        ObjectNode rule = rule(), theme = theme(rule, "sys.hp", "blue");
        binding(theme).with("values").remove("energy"); binding(theme).with("items").put("energy", "green"); rejected(theme, rule, "energy");
        rule.withArray("resourceSlots").addObject().put("key", "other").put("kind", "VALUE").put("label", "另一值域").put("required", true);
        rule.withArray("statCosts").addObject().put("statKey", "other").put("amount", 1);
        theme = theme(rule, "sys.hp", "blue"); binding(theme).with("values").put("other", "sys.hp"); rejected(theme, rule, "other");
        rule.withArray("statCosts").remove(1); theme = theme(rule, "sys.hp", "blue"); rejected(theme, rule, "other");
        ((ObjectNode) rule.path("resourceSlots").get(0)).put("kind", "ITEM");
        rejected(theme, rule, "energy");
    }
    @Test void slotSchemaRejectsOptionalDuplicateUnknownAndMalformedDeclarations() {
        ObjectNode rule = rule(); ((ObjectNode) rule.path("resourceSlots").get(0)).put("required", false);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule));
        ((ObjectNode) rule.path("resourceSlots").get(0)).put("required", true).put("path", "statCosts[0]");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule));
        ((ObjectNode) rule.path("resourceSlots").get(0)).remove("path");
        rule.withArray("resourceSlots").add(rule.path("resourceSlots").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule));
        ObjectNode invalid = rule(); ((ObjectNode) invalid.path("resourceSlots").get(0)).put("key", "Bad");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(invalid));
        ((ObjectNode) invalid.path("resourceSlots").get(0)).put("key", "energy").put("label", "abcdefghijklmnopqrstu");
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(invalid));
        invalid.putNull("resourceSlots"); assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(invalid));
    }
    @Test void bindingsRejectDeletedResourcesForeignNodesAndDisabledValues() {
        ObjectNode rule = rule(), theme = theme(rule, "counter.focus", "blue");
        ThemeRuntimeConfig.assertReferences(theme, Collections.singleton(101L), Collections.singleton("counter.focus"), values());
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(theme, Collections.singleton(102L), Collections.singleton("counter.focus")));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertReferences(theme, Collections.singleton(101L), Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertJourneyReferences(theme, JourneyRules.parse(null)));
        binding(theme).with("items").put("supply", "deleted");
        String error = assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(theme.toString())).getMessage(); assertTrue(error.contains("101") && error.contains("supply"));
        ObjectNode actions = theme(rule, "sys.actions", "blue"); actions.remove("actionLimit");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertJourneyReferences(actions, values()));
    }
    @Test void copyRemapsBindingNodeKeysAndKeepsResourceMappings() throws Exception {
        ObjectNode rule = rule(), source = theme(rule, "sys.hp", "blue");
        JsonNode copy = json.readTree(ThemeRuntimeConfig.remapNodes(source.toString(), Collections.singletonMap(101L, 201L)));
        assertFalse(copy.path("templateBindings").has("101")); assertEquals(binding(source), copy.path("templateBindings").path("201"));
        assertTrue(source.path("templateBindings").has("101"));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapNodes(source.toString(), Collections.emptyMap()));
    }
    @Test void digestIsCanonicalAcrossDeclarationAndObjectFieldOrder() throws Exception {
        JsonNode slots = rule().get("resourceSlots");
        ArrayNode reversed = json.createArrayNode().add(slots.get(1)).add(slots.get(0));
        assertEquals(GameplayTemplateRules.slotDigest(slots), GameplayTemplateRules.slotDigest(reversed));
        JsonNode reordered = json.readTree("[{\"required\":true,\"label\":\"消耗值域\",\"kind\":\"VALUE\",\"key\":\"energy\"},{\"required\":true,\"label\":\"所需物品\",\"kind\":\"ITEM\",\"key\":\"supply\"}]");
        assertEquals(GameplayTemplateRules.slotDigest(slots), GameplayTemplateRules.slotDigest(reordered));
        assertEquals(64, GameplayTemplateRules.slotDigest(slots).length());
        ((ObjectNode) reversed.get(0)).put("label", "新名称");
        assertNotEquals(GameplayTemplateRules.slotDigest(reordered), GameplayTemplateRules.slotDigest(reversed));
    }
    @Test void legacyLiteralTemplatesKeepTheirExistingBehavior() {
        ObjectNode slots = rule(), theme = theme(slots, "sys.hp", "blue"); theme.remove("templateBindings");
        ObjectNode literal = json.createObjectNode().put("hpCost", 2);
        assertEquals(GameplayTemplateRules.merge(theme, nodes(literal), Collections.singletonMap(101L, "walk")), merge(theme, literal));
        assertEquals(2, GameplayTemplateRules.normalize(literal).path("hpCost").asInt());
    }
    @Test void advancedValidationRetainsSlotsAndLiteralSystemWritesStayForbidden() throws Exception {
        ObjectNode rule = rule();
        JsonNode saved = json.readTree(new AdvancedGameConfigValidator().validateAndNormalize(nodes(rule).path("101").toString()));
        assertEquals(rule.path("resourceSlots"), saved.path("interactionRules").path("resourceSlots"));
        rule.putArray("effects").addObject().put("var", "sys.hp").put("op", "INC").put("value", 1);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule));
    }
    @Test void declaredOnlySlotsCanResolveVotingMaterialsWithoutCreatingFacility() throws Exception {
        ObjectNode rule = rule(); rule.remove(Arrays.asList("statCosts", "resourceCosts")); rule.withArray("resourceSlots").remove(0);
        ObjectNode theme = theme(rule, "sys.hp", "blue"); binding(theme).with("values").remove("energy");
        theme.with("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED");
        ObjectNode nodes = nodes(rule), tools = nodes.with("101").putObject("interactionTools").put("votePolicy", "MAJORITY");
        ObjectNode plan = tools.putArray("plans").addObject().put("title", "共同方案");
        plan.putArray("materialCost").addObject().put("itemId", "supply").put("quantity", 2);
        plan.putArray("when").addObject().put("op", "ITEM_HELD").put("itemId", "supply").put("quantity", 1);
        assertNotNull(new AdvancedGameConfigValidator().validateAndNormalize(nodes.path("101").toString()));
        ObjectNode compiled = GameplayTemplateRules.merge(theme, nodes, Collections.singletonMap(101L, "walk"), Collections.singletonMap(101L, 501L));
        assertFalse(compiled.path("gameplayRuntime").has("facilities"));
        JsonNode actual = compiled.path("gameplayRuntime").path("plans").get(0);
        assertEquals("blue", actual.path("materialCost").get(0).path("itemId").asText()); assertEquals("blue", actual.path("when").get(0).path("itemId").asText());
    }
    @Test void unresolvedNodeSnapshotsAreResolvedAsCopiesBeforeFreeze() {
        ObjectNode rule = rule(), theme = theme(rule, "sys.hp", "blue"), nodes = nodes(rule);
        theme.set("nodeConfigs", nodes);
        ObjectNode compiled = merge(theme, rule);
        assertFalse(compiled.path("nodeConfigs").path("101").path("interactionRules").has("resourceSlots"));
        assertEquals(2, compiled.path("nodeConfigs").path("101").path("interactionRules").path("hpCost").asInt());
        assertTrue(nodes.path("101").path("interactionRules").has("resourceSlots"));
    }
    @Test void overflowingOrMalformedCostsCannotBeHiddenBySlotResolution() {
        ObjectNode rule = rule(); rule.put("hpCost", 999); rejected(theme(rule, "sys.hp", "blue"), rule, "energy");
        rule.remove("hpCost"); ((ObjectNode) rule.path("statCosts").get(0)).put("amount", 1.5);
        assertThrows(IllegalArgumentException.class, () -> merge(theme(rule, "sys.hp", "blue"), rule));
    }
    @Test void declarationsEnforceTwentySlotLimit() {
        ObjectNode rule = json.createObjectNode();
        ArrayNode slots = rule.putArray("resourceSlots"), costs = rule.putArray("statCosts");
        for (int index = 0; index < 20; index++) {
            slots.addObject().put("key", "value_" + index).put("kind", "VALUE").put("label", "值域").put("required", true);
            costs.addObject().put("statKey", "value_" + index).put("amount", 1);
        }
        assertEquals(20, GameplayTemplateRules.normalize(rule).path("resourceSlots").size());
        slots.addObject().put("key", "extra").put("kind", "VALUE").put("label", "额外值域").put("required", true);
        assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.normalize(rule));
    }
    @Test void bindingShapeRejectsUnknownMissingAndNonTextFields() {
        ObjectNode rule = rule(), valid = theme(rule, "sys.hp", "blue");
        assertNotNull(ThemeRuntimeConfig.parseDraft(valid.toString()));
        ObjectNode invalid = valid.deepCopy(); binding(invalid).put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(invalid.toString()));
        for (String field : Arrays.asList("values", "items", "slotDigest", "templateId")) {
            ObjectNode missing = valid.deepCopy(); binding(missing).remove(field);
            assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(missing.toString()));
        }
        ObjectNode typed = valid.deepCopy(); binding(typed).with("values").put("energy", 1);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(typed.toString()));
        ObjectNode draftKey = valid.deepCopy(); draftKey.with("templateBindings").set("draft_node", binding(draftKey).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(draftKey.toString()));
    }
    @Test void unsupportedWorldAndReorderedHpEffectsNameNodeAndSlot() {
        ObjectNode rule = rule(); rule.putArray("worldEffects").addObject().put("var", "energy").put("op", "INC").put("value", 1);
        rejected(theme(rule, "counter.focus", "blue"), rule, "energy");
        rule.remove("worldEffects");
        ArrayNode changes = rule.putArray("statChanges");
        changes.addObject().put("statKey", "counter.focus").put("delta", 1);
        changes.addObject().put("statKey", "energy").put("delta", 2);
        rejected(theme(rule, "sys.hp", "blue"), rule, "energy");
        changes.remove(0); rule.put("hpDelta", 1);
        rejected(theme(rule, "sys.hp", "blue"), rule, "energy");
    }
    @Test void unsupportedBoundItemCostsNameNodeAndSlot() {
        ObjectNode rule = rule(), theme = theme(rule, "sys.hp", "blue");
        ((ObjectNode) theme.path("items").get(0)).put("kind", "MEDAL");
        rejected(theme, rule, "supply");
        rule.remove(Arrays.asList("statCosts", "resourceCosts")); rule.withArray("resourceSlots").remove(0);
        theme = theme(rule, "sys.hp", "blue"); binding(theme).with("values").remove("energy");
        ((ObjectNode) theme.path("items").get(0)).put("wear", true);
        ObjectNode nodes = nodes(rule);
        ObjectNode tools = nodes.with("101").putObject("interactionTools").put("votePolicy", "MAJORITY");
        tools.putArray("plans").addObject().put("title", "共同方案").putArray("materialCost").addObject().put("itemId", "supply").put("quantity", 1);
        ObjectNode source = theme;
        String error = assertThrows(IllegalArgumentException.class, () -> GameplayTemplateRules.resolveNodeConfigs(source, nodes, Collections.singletonMap(101L, 501L))).getMessage();
        assertTrue(error.contains("101") && error.contains("supply"), error);
    }
}
