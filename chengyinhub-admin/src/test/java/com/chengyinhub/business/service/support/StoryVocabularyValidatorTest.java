package com.chengyinhub.business.service.support;

import com.chengyinhub.business.util.StateKeyRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F5:三处配置校验器接受新词汇并做形状校验;存量 op 的拒绝文案不变。 */
class StoryVocabularyValidatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private static final Set<Long> NODES = new LinkedHashSet<Long>(Arrays.asList(1L, 2L, 3L));

    // ---------- 路线图 ----------

    private static String route(String conditions, String effects) {
        return "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[3],\"variables\":{\"counter.alarm\":0}," +
                "\"edges\":[{\"id\":\"e1\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"conditions\":" + conditions + ",\"effects\":" + effects + "}," +
                "{\"id\":\"e2\",\"fromNodeId\":2,\"toNodeId\":3," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}";
    }

    private static final String ALL_CONDITIONS = "[{\"op\":\"OR\",\"conditions\":["
            + "{\"op\":\"NOT\",\"condition\":{\"op\":\"HAS_CONDITION\",\"value\":\"poisoned\"}},"
            + "{\"op\":\"AND\",\"conditions\":[{\"op\":\"VISIT_COUNT\",\"nodeId\":1,\"cmp\":\"GTE\",\"value\":2},"
            + "{\"op\":\"NODE_COMPLETED\",\"nodeId\":1}]}]},"
            + "{\"op\":\"ROLE_IN\",\"values\":[\"NAVIGATOR\"]},"
            + "{\"op\":\"ITEM_EQUIPPED\",\"itemId\":\"lantern\"},"
            + "{\"op\":\"CHECK_RESULT\",\"nodeId\":1,\"result\":\"PASS\"}]";
    private static final String ALL_EFFECTS = "[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":1},"
            + "{\"op\":\"GRANT_BADGE\",\"value\":\"night_owl\"},{\"op\":\"GRANT_XP\",\"value\":20,\"id\":\"bonus\"},"
            + "{\"op\":\"ADD_CONDITION\",\"value\":\"poisoned\"},{\"op\":\"REMOVE_CONDITION\",\"value\":\"poisoned\"},"
            + "{\"op\":\"EQUIP\",\"itemId\":\"lantern\",\"slot\":\"hand\"},{\"op\":\"UNEQUIP\",\"itemId\":\"lantern\"},"
            + "{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4}]";

    @Test
    void routeValidatorAcceptsEveryNewOp() {
        assertNotNull(new TopicRouteConfigValidator().validateAndNormalize(route(ALL_CONDITIONS, ALL_EFFECTS), NODES));
    }

    @Test
    void routeValidatorRejectsBadShapes() {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String[][] badConditions = {
                {"[{\"op\":\"OR\",\"conditions\":[]}]", "OR 的 conditions"},
                {"[{\"op\":\"NOT\",\"condition\":{\"op\":\"NOT\",\"condition\":{\"op\":\"NOT\",\"condition\":"
                        + "{\"op\":\"NOT\",\"condition\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":1}}}}}]", "最多嵌套"},
                {"[{\"op\":\"VISIT_COUNT\",\"nodeId\":9,\"value\":1}]", "不属于本主题"},
                {"[{\"op\":\"VISIT_COUNT\",\"nodeId\":1,\"cmp\":\"LIKE\",\"value\":1}]", "cmp"},
                {"[{\"op\":\"ROLE_IN\",\"values\":[]}]", "ROLE_IN"},
                {"[{\"op\":\"CHECK_RESULT\",\"nodeId\":1,\"result\":\"CRIT\"}]", "PASS 或 FAIL"},
                {"[{\"op\":\"HAS_CONDITION\",\"value\":\"Bad Name\"}]", "value 格式"},
                // 内层叶子走路线图自己的规则:未声明变量照样拒
                {"[{\"op\":\"NOT\",\"condition\":{\"op\":\"EQ\",\"var\":\"ghost\",\"value\":1}}]", "未声明变量"},
                {"[{\"op\":\"NOT\",\"condition\":{\"op\":\"NODE_COMPLETED\",\"nodeId\":9}}]", "nodeId"},
        };
        for (String[] bad : badConditions) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(route(bad[0], "[]"), NODES), bad[0]);
            assertTrue(error.getMessage().startsWith("e1"), error.getMessage());
            assertTrue(error.getMessage().contains(bad[1]), error.getMessage());
        }
        String[][] badEffects = {
                {"[{\"op\":\"GRANT_XP\",\"value\":0}]", "1 至 100"},
                {"[{\"op\":\"GRANT_XP\",\"value\":101}]", "1 至 100"},
                {"[{\"op\":\"GRANT_XP\",\"value\":5,\"points\":100}]", "不支持的字段:points"},
                {"[{\"op\":\"GRANT_BADGE\",\"value\":\"x\",\"couponId\":7}]", "不支持的字段:couponId"},
                {"[{\"op\":\"ADD_ITEM\",\"itemId\":\"map\",\"quantity\":0}]", "quantity"},
                {"[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":5,\"max\":4}]", "value"},
                {"[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.ghost\",\"value\":1,\"max\":4}]", "未声明变量"},
                {"[{\"op\":\"EQUIP\"}]", "itemId"},
        };
        for (String[] bad : badEffects) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validator.validateAndNormalize(route("[]", bad[0]), NODES), bad[0]);
            assertTrue(error.getMessage().contains(bad[1]), error.getMessage());
        }
        // 存量拒绝文案不变
        assertEquals("e1: condition.op 不支持", assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(route("[{\"op\":\"FOO\"}]", "[]"), NODES)).getMessage());
        assertEquals("e1: effect.op 不支持", assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize(route("[]", "[{\"op\":\"FOO\"}]"), NODES)).getMessage());
    }

    @Test
    void routeCopyRemapsNodeReferencesInsideComposition() throws Exception {
        TopicRouteConfigValidator validator = new TopicRouteConfigValidator();
        String draft = "{\"schemaVersion\":1,\"startNodeKey\":\"a\",\"terminalNodeKeys\":[\"b\"]," +
                "\"edges\":[{\"id\":\"e\",\"fromNodeKey\":\"a\",\"toNodeKey\":\"b\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"conditions\":[{\"op\":\"NOT\",\"condition\":{\"op\":\"VISIT_COUNT\",\"nodeKey\":\"a\",\"value\":2}}," +
                "{\"op\":\"CHECK_RESULT\",\"nodeKey\":\"a\",\"result\":\"PASS\"}]}]}";
        Map<String, Long> keys = new LinkedHashMap<String, Long>();
        keys.put("a", 101L); keys.put("b", 102L);
        String remapped = validator.remapClientNodeKeys(draft, keys);
        JsonNode conditions = validator.readTree(remapped).path("edges").get(0).path("conditions");
        assertEquals(101L, conditions.get(0).path("condition").path("nodeId").asLong());
        assertEquals(101L, conditions.get(1).path("nodeId").asLong());

        Map<Long, Long> copy = new LinkedHashMap<Long, Long>();
        copy.put(101L, 201L); copy.put(102L, 202L);
        JsonNode copied = validator.readTree(validator.remapNodeIds(remapped, copy)).path("edges").get(0).path("conditions");
        assertEquals(201L, copied.get(0).path("condition").path("nodeId").asLong());
        assertEquals(201L, copied.get(1).path("nodeId").asLong());

        String stringIds = remapped.replace("\"nodeId\":101", "\"nodeId\":\"101\"");
        JsonNode normalized = json.readTree(validator.validateAndNormalize(stringIds,
                new LinkedHashSet<Long>(Arrays.asList(101L, 102L))));
        assertTrue(normalized.path("edges").get(0).path("conditions").get(0).path("condition").path("nodeId").isNumber());
    }

    // ---------- 节点块配置 ----------

    @Test
    void advancedGameValidatorAcceptsNewOpsInRequiresChecksAndEffects() {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        String config = "{\"schemaVersion\":1,"
                + "\"requires\":[{\"op\":\"OR\",\"conditions\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.a\"},"
                + "{\"op\":\"NOT\",\"condition\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}}]},"
                + "{\"op\":\"CHECK_RESULT\",\"nodeId\":5,\"result\":\"FAIL\"}],"
                + "\"effects\":" + ALL_EFFECTS + "}";
        assertNotNull(validator.validateAndNormalize(config));
        // 内层叶子仍受块配置命名空间约束
        IllegalArgumentException leaf = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndNormalize("{\"schemaVersion\":1,\"requires\":[{\"op\":\"NOT\","
                        + "\"condition\":{\"var\":\"trust\",\"op\":\"GTE\",\"value\":1}}]}"));
        assertTrue(leaf.getMessage().contains("var"), leaf.getMessage());
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"GRANT_XP\",\"value\":5,\"amount\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"TICK_CLOCK\",\"var\":\"clue.x\",\"value\":1,\"max\":4}]}"));
    }

    @Test
    void stateKeyRefsCollectKeysInsideCompositionAndRemapNewNodeRefs() throws Exception {
        String config = "{\"schemaVersion\":1,\"requires\":[{\"op\":\"OR\",\"conditions\":["
                + "{\"op\":\"HAS_TAG\",\"value\":\"tag.a\"},"
                + "{\"op\":\"NOT\",\"condition\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}}]},"
                + "{\"op\":\"VISIT_COUNT\",\"nodeId\":5,\"value\":1}],"
                + "\"effects\":[{\"op\":\"TICK_CLOCK\",\"var\":\"counter.alarm\",\"value\":1,\"max\":4},"
                + "{\"op\":\"GRANT_BADGE\",\"value\":\"night_owl\"}]}";
        Set<String> keys = StateKeyRefs.collect(config);
        assertTrue(keys.contains("tag.a"), keys.toString());
        assertTrue(keys.contains("clue.ledger_page"), keys.toString());
        assertTrue(keys.contains("counter.alarm"), keys.toString());
        assertFalse(keys.contains("night_owl"), keys.toString());

        String copied = StateKeyRefs.remapNodeIds(config, Collections.singletonMap(5L, 50L));
        assertEquals(50L, json.readTree(copied).path("requires").get(1).path("nodeId").asLong());
    }

    // ---------- 剧情 ----------

    @Test
    void journeyStoryValidatorAcceptsCompositionAndChecksNodes() {
        JourneyStoryValidator validator = new JourneyStoryValidator();
        String story = "{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"a\",\"title\":\"甲\",\"summary\":\"好\","
                + "\"when\":[{\"op\":\"OR\",\"conditions\":[{\"op\":\"CHECK_RESULT\",\"nodeId\":12,\"result\":\"PASS\"},"
                + "{\"op\":\"NOT\",\"condition\":{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}}]}]},"
                + "{\"code\":\"default\",\"title\":\"默认\",\"fallback\":true}]}}";
        assertNotNull(validator.validate(story, null));
        JourneyStoryValidator.Context ctx = JourneyStoryValidator.Context.of(null, null,
                Collections.singleton(12L), null);
        assertNotNull(validator.validate(story, ctx));
        JourneyStoryValidator.Context other = JourneyStoryValidator.Context.of(null, null,
                Collections.singleton(13L), null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> validator.validate(story, other));
        assertTrue(error.getMessage().contains("不属于本主题"), error.getMessage());
        // 内层叶子仍走剧情自己的 tag 规则
        String badLeaf = story.replace("tag.x", "TAG X");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(badLeaf, null));
    }
}
