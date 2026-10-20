package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GameplayReskinFrozenThemeComparisonTest {
    @Test
    void onlyGeneratedFacilityPermutationIsEquivalentAndRealChangesRemainRejected() throws Exception {
        ObjectNode expected = (ObjectNode) GameplayReskinFixture.JSON.readTree(
                "{\"chapters\":[{\"index\":0},{\"index\":1}],\"gameplayRuntime\":{"
                + "\"acts\":[{\"actKey\":\"a1\"},{\"actKey\":\"a2\"}],\"facilities\":["
                + "{\"facilityKey\":\"heating\",\"hpDelta\":3},"
                + "{\"facilityKey\":\"verify\",\"limit\":1},"
                + "{\"facilityKey\":\"tpl_node_1\",\"operationNodeId\":1,\"settlementSlot\":\"tpl_node_1\","
                + "\"actKey\":\"a1\",\"costOptions\":[{\"hpCost\":1},{\"hpCost\":2}]},"
                + "{\"facilityKey\":\"tpl_node_2\",\"operationNodeId\":2,\"settlementSlot\":\"tpl_node_2\","
                + "\"actKey\":\"a2\",\"resourceCosts\":[{\"itemId\":\"fuel\",\"quantity\":1}]}]}}");
        ObjectNode reordered = expected.deepCopy();
        ArrayNode facilities = (ArrayNode) reordered.path("gameplayRuntime").path("facilities");
        JsonNode firstGenerated = facilities.get(2);
        facilities.set(2, facilities.get(3));
        facilities.set(3, firstGenerated);
        assertDoesNotThrow(() -> GameplayReskinFixture.assertSameFrozenTheme(expected, reordered));

        List<Consumer<ObjectNode>> mutations = Arrays.asList(
                value -> ((ObjectNode) value.path("gameplayRuntime").path("facilities").get(3)
                        .path("resourceCosts").get(0)).put("quantity", 9),
                value -> ((ObjectNode) value.path("gameplayRuntime").path("facilities").get(2))
                        .remove("operationNodeId"),
                value -> ((ObjectNode) value.path("gameplayRuntime").path("facilities").get(2))
                        .remove("actKey"),
                value -> ((ArrayNode) value.path("gameplayRuntime").path("facilities"))
                        .add(value.path("gameplayRuntime").path("facilities").get(2).deepCopy()),
                value -> {
                    ArrayNode options = (ArrayNode) value.path("gameplayRuntime").path("facilities").get(2)
                            .path("costOptions");
                    JsonNode first = options.get(0); options.set(0, options.get(1)); options.set(1, first);
                },
                value -> {
                    ArrayNode manual = (ArrayNode) value.path("gameplayRuntime").path("facilities");
                    JsonNode first = manual.get(0); manual.set(0, manual.get(1)); manual.set(1, first);
                },
                value -> {
                    ArrayNode acts = (ArrayNode) value.path("gameplayRuntime").path("acts");
                    JsonNode first = acts.get(0); acts.set(0, acts.get(1)); acts.set(1, first);
                });
        for (int index = 0; index < mutations.size(); index++) {
            ObjectNode changed = expected.deepCopy();
            mutations.get(index).accept(changed);
            assertThrows(AssertionError.class,
                    () -> GameplayReskinFixture.assertSameFrozenTheme(expected, changed),
                    "负控不能把真实配置变化规范成相等: " + index);
        }
    }
}
