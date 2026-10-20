package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRuntimeChapterRequirementsTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode config() {
        ObjectNode config = json.createObjectNode().put("schemaVersion", 1).put("capacity", 0);
        config.putArray("chapters").addObject().put("index", 2).put("hpCost", 0).put("luckCost", 0);
        return config;
    }
    private ObjectNode chapter(ObjectNode config) { return (ObjectNode) config.path("chapters").get(0); }
    private void rejected(String field, String value) throws Exception {
        ObjectNode config = config(); chapter(config).set(field, json.readTree(value));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(config.toString()), field + "=" + value);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(config.toString()), field + "=" + value);
    }

    @Test void absentFieldsKeepLegacyConfigurationUnchanged() {
        ObjectNode config = config();
        assertEquals(config, ThemeRuntimeConfig.parseDraft(config.toString()));
        assertFalse(chapter(ThemeRuntimeConfig.parseDraft(config.toString())).has("completion"));
        chapter(config).remove("hpCost");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(config.toString()));
    }
    @Test void allAndAnyNKeepTheirExplicitValuesWithoutCheckingMembershipOrCapacity() {
        ObjectNode config = config(); chapter(config).putObject("completion").put("type", "ALL");
        assertEquals(config, ThemeRuntimeConfig.parse(config.toString()));
        chapter(config).putObject("completion").put("type", "ANY_N").put("quantity", 1);
        assertEquals(config, ThemeRuntimeConfig.parseDraft(config.toString()));
        chapter(config).with("completion").put("quantity", Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, chapter(ThemeRuntimeConfig.parseDraft(config.toString())).path("completion").path("quantity").asInt());
    }
    @Test void completionRejectsMalformedObjectsTypesAndUnexpectedFields() throws Exception {
        for (String value : new String[]{"null", "[]", "true", "{}", "{\"type\":1}", "{\"type\":\"SOME\"}", "{\"type\":\"ALL\",\"quantity\":1}", "{\"type\":\"ALL\",\"extra\":true}"}) rejected("completion", value);
    }
    @Test void anyNRequiresAPositiveIntegerQuantity() throws Exception {
        rejected("completion", "{\"type\":\"ANY_N\"}");
        for (String value : new String[]{"null", "true", "\"1\"", "0", "-1", "1.5", "2147483648", "9223372036854775808"}) rejected("completion", "{\"type\":\"ANY_N\",\"quantity\":" + value + "}");
    }
    @Test void overflowSlotAcceptsValidKeysOnlyWithAnyN() {
        ObjectNode config = config();
        ObjectNode completion = chapter(config).putObject("completion").put("type", "ANY_N").put("quantity", 2).put("overflowSlot", "mobile");
        assertEquals(config, ThemeRuntimeConfig.parse(config.toString()));
        completion.put("overflowSlot", "abcdefghijklmnopqrstuvwxyz12345_");
        assertEquals(config, ThemeRuntimeConfig.parseDraft(config.toString()));
    }
    @Test void overflowSlotRejectsAllAndInvalidKeys() throws Exception {
        rejected("completion", "{\"type\":\"ALL\",\"overflowSlot\":\"mobile\"}");
        for (String slot : new String[]{"null", "true", "1", "\"\"", "\"Upper\"", "\"1slot\"", "\"slot.key\"", "\"slot-key\"", "\" slot\"", "\"abcdefghijklmnopqrstuvwxyz1234567\""})
            rejected("completion", "{\"type\":\"ANY_N\",\"quantity\":2,\"overflowSlot\":" + slot + "}");
    }
    @Test void groupsAcceptEmptyAndTwentyDistinctGroupsIncludingLongNodeIds() {
        ObjectNode config = config(); ArrayNode groups = chapter(config).putArray("exclusiveGroups");
        assertEquals(config, ThemeRuntimeConfig.parseDraft(config.toString()));
        for (int index = 0; index < 20; index++) groups.addArray().add(index * 2 + 1).add(index * 2 + 2);
        ((ArrayNode) groups.get(19)).set(1, json.getNodeFactory().numberNode(Long.MAX_VALUE));
        assertEquals(config, ThemeRuntimeConfig.parse(config.toString()));
        groups.addArray().add(41).add(42);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(config.toString()));
    }
    @Test void groupsRejectNonArraysAndGroupsWithFewerThanTwoNodes() throws Exception {
        for (String value : new String[]{"null", "{}", "1", "[{}]", "[1,2]", "[[]]", "[[1]]"}) rejected("exclusiveGroups", value);
    }
    @Test void groupNodesMustBePositiveLongsAndUniqueAcrossAllGroups() throws Exception {
        for (String node : new String[]{"null", "true", "\"2\"", "0", "-2", "2.5", "9223372036854775808"}) rejected("exclusiveGroups", "[[1," + node + "]]");
        rejected("exclusiveGroups", "[[1,1]]");
        rejected("exclusiveGroups", "[[1,2],[2,3]]");
    }
    @Test void extraNodesAllowSharedSlotNamesWithinAndAcrossChapters() {
        ObjectNode config = config();
        ArrayNode extra = chapter(config).putArray("extraNodes");
        assertEquals(config, ThemeRuntimeConfig.parseDraft(config.toString()));
        extra.addObject().put("nodeId", 1).put("slot", "shared");
        extra.addObject().put("nodeId", Long.MAX_VALUE).put("slot", "shared");
        extra.addObject().put("nodeId", 3).put("slot", "abcdefghijklmnopqrstuvwxyz12345_");
        ObjectNode second = config.withArray("chapters").addObject().put("index", 3).put("hpCost", 0).put("luckCost", 0);
        second.putArray("extraNodes").addObject().put("nodeId", 4).put("slot", "shared");
        assertEquals(config, ThemeRuntimeConfig.parse(config.toString()));
    }
    @Test void extraNodesRejectMalformedRowsAndNodeIds() throws Exception {
        for (String value : new String[]{"null", "{}", "[null]", "[[]]", "[{}]", "[{\"nodeId\":1}]", "[{\"nodeId\":1,\"slot\":\"a\",\"extra\":true}]"}) rejected("extraNodes", value);
        for (String node : new String[]{"null", "true", "\"2\"", "0", "-2", "2.5", "9223372036854775808"}) rejected("extraNodes", "[{\"nodeId\":" + node + ",\"slot\":\"a\"}]");
    }
    @Test void extraNodesRejectInvalidSlotNamesDuplicateNodesAndGroupOverlap() throws Exception {
        for (String slot : new String[]{"null", "1", "\"\"", "\"Upper\"", "\"1slot\"", "\"slot.key\"", "\"slot-key\"", "\" slot\"", "\"abcdefghijklmnopqrstuvwxyz1234567\""}) rejected("extraNodes", "[{\"nodeId\":1,\"slot\":" + slot + "}]");
        rejected("extraNodes", "[{\"nodeId\":1,\"slot\":\"first\"},{\"nodeId\":1,\"slot\":\"second\"}]");
        ObjectNode config = config(); chapter(config).putArray("exclusiveGroups").addArray().add(1).add(2);
        chapter(config).putArray("extraNodes").addObject().put("nodeId", 2).put("slot", "extra");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(config.toString()));
    }
    @Test void copyRemapsGroupsAndExtraNodesOnceWithoutChangingChapterIndexOrSlot() throws Exception {
        ObjectNode config = config(); chapter(config).putObject("completion").put("type", "ANY_N").put("quantity", 1).put("overflowSlot", "shared");
        chapter(config).putArray("exclusiveGroups").addArray().add(1).add(2);
        chapter(config).putArray("extraNodes").addObject().put("nodeId", 3).put("slot", "shared");
        Map<Long, Long> nodes = new LinkedHashMap<>(); nodes.put(1L, 2L); nodes.put(2L, 3L); nodes.put(3L, 4L);
        String[] copies = {ThemeRuntimeConfig.remapNodes(config.toString(), nodes), ThemeRuntimeConfig.remapAll(config.toString(), nodes, Collections.emptyMap(), null), ThemeRuntimeConfig.remapForNodeSave(config.toString(), nodes, Collections.emptyMap(), Collections.emptyMap())};
        for (String raw : copies) {
            JsonNode copied = json.readTree(raw).path("chapters").get(0);
            assertEquals(json.readTree("[[2,3]]"), copied.path("exclusiveGroups"));
            assertEquals(4L, copied.path("extraNodes").get(0).path("nodeId").asLong());
            assertEquals("shared", copied.path("extraNodes").get(0).path("slot").asText());
            assertEquals(2, copied.path("index").asInt()); assertEquals(chapter(config).get("completion"), copied.get("completion"));
        }
        assertEquals(1, chapter(config).path("exclusiveGroups").get(0).get(0).asInt());
        assertEquals(3, chapter(config).path("extraNodes").get(0).path("nodeId").asInt());
    }
    @Test void copyRejectsMissingGroupOrExtraNodeMappings() {
        ObjectNode config = config(); chapter(config).putArray("exclusiveGroups").addArray().add(1).add(2);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapNodes(config.toString(), Collections.singletonMap(1L, 11L)));
        chapter(config).remove("exclusiveGroups"); chapter(config).putArray("extraNodes").addObject().put("nodeId", 3).put("slot", "shared");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapNodes(config.toString(), Collections.singletonMap(1L, 11L)));
    }

    /** 复制成新草稿后,章节规则引用同请求新建站点(client:<key>):只在存站点这一步接受,落库前换成真实编号,共用额度关系不变。 */
    @Test void clientNodeRefsInChapterRulesAreRemappedOnNodeSave() throws Exception {
        ObjectNode config = config();
        chapter(config).set("exclusiveGroups", json.readTree("[[\"client:a\",11]]"));
        chapter(config).set("extraNodes", json.readTree("[{\"nodeId\":\"client:b\",\"slot\":\"shared\"},{\"nodeId\":12,\"slot\":\"shared\"}]"));
        String raw = config.toString();
        // 非存站点路径照旧只收正整数
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(raw));
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parse(raw));
        assertNotNull(ThemeRuntimeConfig.parseForNodeSave(raw));
        // 落库前的中间态不含 client 引用(不足两站的互斥组整组去掉),能被普通解析接受
        JsonNode interim = json.readTree(ThemeRuntimeConfig.withoutClientBindings(raw));
        assertFalse(chapter((ObjectNode) interim).has("exclusiveGroups"));
        assertEquals("[{\"nodeId\":12,\"slot\":\"shared\"}]", chapter((ObjectNode) interim).path("extraNodes").toString());
        assertNotNull(ThemeRuntimeConfig.parseDraft(interim.toString()));
        Map<Long, Long> nodes = new LinkedHashMap<>(); nodes.put(11L, 911L); nodes.put(12L, 912L);
        Map<String, Long> created = new LinkedHashMap<>(); created.put("a", 901L); created.put("b", 902L);
        JsonNode saved = json.readTree(ThemeRuntimeConfig.remapForNodeSave(raw, nodes, Collections.emptyMap(), created));
        assertEquals("[[901,911]]", chapter((ObjectNode) saved).path("exclusiveGroups").toString());
        assertEquals("[{\"nodeId\":902,\"slot\":\"shared\"},{\"nodeId\":912,\"slot\":\"shared\"}]", chapter((ObjectNode) saved).path("extraNodes").toString());
        // 引用了本次没有新建的 client 键:拒绝,不静默改指别的站点
        Map<String, Long> missing = new LinkedHashMap<>(); missing.put("a", 901L);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.remapForNodeSave(raw, nodes, Collections.emptyMap(), missing));
    }
}
