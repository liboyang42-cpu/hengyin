package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeSlotReferencesTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode block() {
        ObjectNode block = json.createObjectNode();
        ObjectNode rules = block.putObject("interactionRules");
        ArrayNode slots = rules.putArray("resourceSlots");
        slots.addObject().put("key", "energy").put("kind", "VALUE").put("label", "值域").put("required", true);
        slots.addObject().put("key", "supply").put("kind", "ITEM").put("label", "物资").put("required", true);
        rules.putArray("statCosts").addObject().put("statKey", "energy").put("amount", 1);
        rules.putArray("statChanges").addObject().put("statKey", "energy").put("delta", 1);
        rules.putArray("effects").addObject().put("var", "energy").put("op", "INC").put("value", 1);
        ArrayNode when = rules.putArray("when");
        for (String op : new String[]{"ITEM_HELD", "ITEM_ACQUIRED"}) when.addObject().put("op", op).put("itemId", "supply").put("quantity", 1);
        when.addObject().put("var", "energy").put("op", "GTE").put("value", 1);
        return block;
    }
    private void references(ObjectNode block) {
        ThemeRuntimeConfig.assertReferences(block, Collections.emptySet(), Collections.emptySet());
        ThemeRuntimeConfig.assertJourneyReferences(block, JourneyRules.parse(null));
    }

    @Test void declaredReferencesAreLocalParametersAndDoNotMutateAuthorData() {
        ObjectNode block = block(), original = block.deepCopy();
        references(block);
        ThemeRuntimeConfig.assertItemReferences(block, null);
        GameplayTemplateRules.normalize(block.get("interactionRules"));
        assertEquals(original, block);
    }
    @Test void undeclaredValuesStillRequireRegistrationAndEnabledAttributes() {
        ObjectNode block = block();
        ((ObjectNode) block.path("interactionRules").path("statCosts").get(0)).put("statKey", "counter.unknown");
        assertThrows(IllegalArgumentException.class, () -> references(block));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertJourneyReferences(block, JourneyRules.parse(null)));
        ObjectNode effect = block();
        ((ObjectNode) effect.path("interactionRules").path("effects").get(0)).put("var", "counter.unknown");
        assertThrows(IllegalArgumentException.class, () -> references(effect));
    }
    @Test void undeclaredItemConditionsRemainStrictForBothOperations() {
        for (int index = 0; index < 2; index++) {
            ObjectNode block = block();
            ((ObjectNode) block.path("interactionRules").path("when").get(index)).put("itemId", "missing");
            assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertItemReferences(block, null));
        }
    }
    @Test void declarationsNeverLeakToSiblingNodesOrThemeConfiguration() {
        ObjectNode root = json.createObjectNode(), nodes = root.putObject("nodeConfigs");
        nodes.set("101", block());
        nodes.putObject("102").putObject("interactionRules").putArray("statCosts").addObject().put("statKey", "energy").put("amount", 1);
        assertThrows(IllegalArgumentException.class, () -> references(root));
        nodes.remove("102");
        root.putArray("when").addObject().put("op", "ITEM_HELD").put("itemId", "supply").put("quantity", 1);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertItemReferences(root, null));
    }
    @Test void referenceKindsRemainStrictEvenWhenLiteralResourceExists() {
        ObjectNode block = block();
        ((ObjectNode) block.path("interactionRules").path("resourceSlots").get(1)).put("kind", "VALUE");
        ObjectNode catalog = json.createObjectNode(); catalog.putArray("items").addObject().put("id", "supply");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertItemReferences(block, catalog));
        ObjectNode wrongValue = block();
        ((ObjectNode) wrongValue.path("interactionRules").path("resourceSlots").get(0)).put("kind", "ITEM");
        assertThrows(IllegalArgumentException.class, () -> references(wrongValue));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.assertJourneyReferences(wrongValue, JourneyRules.parse(null)));
    }
    @Test void toolsAndCostOptionsInheritOnlyTheirOwningBlockDeclarations() {
        ObjectNode block = block();
        ObjectNode tools = block.putObject("interactionTools");
        tools.putArray("plans").addObject().putArray("when").addObject().put("op", "ITEM_ACQUIRED").put("itemId", "supply").put("quantity", 1);
        block.with("interactionRules").putArray("costOptions").addObject().putArray("statCosts").addObject().put("statKey", "energy").put("amount", 1);
        references(block);
        block.with("interactionRules").remove("resourceSlots");
        assertThrows(IllegalArgumentException.class, () -> references(block));
    }
}
