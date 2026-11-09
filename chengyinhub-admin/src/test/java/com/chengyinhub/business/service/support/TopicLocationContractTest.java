package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地点合同格式/采用/缺口判定的纯函数口径。
 *
 * <p>关键负例:「LEGACY 采用(旧 /use 不带参数)必须仍是提示级」——把这一条写成阻塞,
 * 存量模板副本会在发布检查里集体变红,这是新规则倒着拦存量。
 */
class TopicLocationContractTest {

    private static CmsTopic source(String contractJson, String cityCode) {
        CmsTopic topic = new CmsTopic();
        topic.setId(100L);
        topic.setName("原主题");
        topic.setCityCode(cityCode);
        topic.setConfigVersion(7L);
        topic.setContentContractJson(contractJson);
        return topic;
    }

    private static CmsTopicNode node(long id, String address) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(100L);
        node.setName("节点" + id);
        node.setAddress(address);
        node.setLongitude(address == null ? null : "121.47");
        node.setLatitude(address == null ? null : "31.23");
        return node;
    }

    private static String slot(String key, boolean required) {
        return "{\"slotKey\":\"" + key + "\",\"purpose\":\"EVIDENCE\",\"required\":" + required
                + ",\"title\":\"桥面\"}";
    }

    @Test
    void contractNormalizesAndKeepsUnknownFieldsForLaterConsumers() {
        String raw = "{\"schemaVersion\":1,\"locationSlots\":[" + slot("bridge", true)
                + "],\"locationBindings\":[{\"slotKey\":\"bridge\",\"nodeId\":\"456\",\"cityCode\":\"310100\","
                + "\"bindingRevision\":1,\"verificationStatus\":\"BOUND_UNVERIFIED\","
                + "\"futureField\":{\"kept\":true}}],\"futureRoot\":\"kept\"}";
        ObjectNode parsed = TopicLocationContract.parse(raw);
        assertEquals(1, parsed.path("schemaVersion").asInt());
        assertEquals("kept", parsed.path("futureRoot").asText());
        JsonNode binding = parsed.path("locationBindings").get(0);
        assertEquals("456", binding.path("nodeId").asText());
        assertTrue(binding.path("futureField").path("kept").asBoolean());
    }

    @Test
    void cityFactsAreRejectedInsideRequirements() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TopicLocationContract.parse("{\"schemaVersion\":1,\"locationSlots\":[{"
                        + "\"slotKey\":\"a\",\"purpose\":\"SPACE\",\"required\":true,"
                        + "\"latitude\":\"31.23\"}]}"));
        assertTrue(rejected.getMessage().contains("latitude"), "错误要指出具体城市事实字段");
    }

    @Test
    void unknownStatusAndUnknownPurposeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.parse(
                "{\"schemaVersion\":1,\"locationSlots\":[{\"slotKey\":\"a\",\"purpose\":\"BOOTH\",\"required\":true}]}"));
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.parse(
                "{\"schemaVersion\":1,\"locationSlots\":[" + slot("a", true) + "],"
                        + "\"locationBindings\":[{\"slotKey\":\"a\",\"nodeId\":\"1\","
                        + "\"verificationStatus\":\"VERIFIED\"}]}"));
    }

    @Test
    void newCityCopyMarksRebindAndDropsOldFactsButKeepsSourceNodeIdentity() {
        List<CmsTopicNode> nodes = Arrays.asList(node(11L, "上海市黄浦区"), node(12L, null));
        Map<Long, Long> nodeIdMap = new LinkedHashMap<Long, Long>();
        nodeIdMap.put(11L, 111L);
        nodeIdMap.put(12L, 112L);

        String contract = TopicLocationContract.buildAdoptionContract(
                source(null, "310100"), nodes, nodeIdMap, TopicLocationContract.INTENT_NEW_CITY,
                "320100", new Date());

        ObjectNode parsed = TopicLocationContract.parse(contract);
        ArrayNode slots = (ArrayNode) parsed.path("locationSlots");
        assertEquals(1, slots.size(), "无地点节点不生成虚假地点槽");
        assertEquals("11", slots.get(0).path("sourceNodeId").asText(),
                "跨城后仍要保留原节点→槽映射，重绑才知道这个槽原来对应哪个节点");
        JsonNode binding = parsed.path("locationBindings").get(0);
        assertEquals(TopicLocationContract.STATUS_REBIND_REQUIRED, binding.path("verificationStatus").asText());
        assertTrue(binding.path("nodeId").isNull());
        assertEquals("11", binding.path("previousBinding").path("nodeId").asText());
        assertEquals("310100", binding.path("previousBinding").path("cityCode").asText());
        assertEquals("320100", parsed.path("adoption").path("targetCityCode").asText());
        assertEquals("NEW_CITY", parsed.path("adoption").path("bindingIntent").asText());
        assertEquals(7L, parsed.path("adoption").path("sourceConfigVersion").asLong());
        assertEquals("100", parsed.path("adoption").path("sourceTemplateTopicId").asText());
    }

    @Test
    void newCityContractBlocksPublishingOnlyForRequiredUnboundSlots() {
        List<CmsTopicNode> nodes = Collections.singletonList(node(11L, "上海市黄浦区"));
        Map<Long, Long> nodeIdMap = Collections.singletonMap(11L, 111L);
        String newCity = TopicLocationContract.buildAdoptionContract(source(null, "310100"), nodes, nodeIdMap,
                TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());
        List<Map<String, Object>> issues = TopicLocationContract.bindingIssues(newCity, "320100");
        assertFalse(issues.isEmpty());
        for (Map<String, Object> issue : issues) {
            assertEquals(TopicLocationContract.LEVEL_ERROR, issue.get("level"));
        }

        String legacy = TopicLocationContract.buildAdoptionContract(source(null, "310100"), nodes, nodeIdMap,
                TopicLocationContract.INTENT_LEGACY, null, new Date());
        List<Map<String, Object>> legacyIssues = TopicLocationContract.bindingIssues(legacy, "310100");
        assertFalse(legacyIssues.isEmpty(), "旧版采用也应有提示，说明沿用未复核");
        for (Map<String, Object> issue : legacyIssues) {
            assertEquals(TopicLocationContract.LEVEL_WARN, issue.get("level"),
                    "旧版采用不得阻塞发布");
        }
    }

    @Test
    void sameCityCopyKeepsMappedNodeBinding() {
        List<CmsTopicNode> nodes = Collections.singletonList(node(11L, "上海市黄浦区"));
        Map<Long, Long> nodeIdMap = Collections.singletonMap(11L, 111L);
        String contract = TopicLocationContract.buildAdoptionContract(source(null, "310100"), nodes, nodeIdMap,
                TopicLocationContract.INTENT_SAME_CITY, "310100", new Date());
        JsonNode binding = TopicLocationContract.parse(contract).path("locationBindings").get(0);
        assertEquals("111", binding.path("nodeId").asText());
        assertEquals(TopicLocationContract.STATUS_BOUND_UNVERIFIED, binding.path("verificationStatus").asText());
        assertEquals("310100", binding.path("cityCode").asText());
        assertTrue(TopicLocationContract.bindingIssues(contract, "310100").isEmpty());
    }

    @Test
    void newCityRequiresTargetCityAndRejectsSameCity() {
        List<CmsTopicNode> nodes = new ArrayList<CmsTopicNode>();
        Map<Long, Long> nodeIdMap = new LinkedHashMap<Long, Long>();
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.buildAdoptionContract(
                source(null, "310100"), nodes, nodeIdMap, TopicLocationContract.INTENT_NEW_CITY, null, new Date()));
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.buildAdoptionContract(
                source(null, "310100"), nodes, nodeIdMap, TopicLocationContract.INTENT_NEW_CITY, "310100", new Date()));
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.buildAdoptionContract(
                source(null, "310100"), nodes, nodeIdMap, TopicLocationContract.INTENT_SAME_CITY, "320100", new Date()));
        String legacyNoCity = TopicLocationContract.buildAdoptionContract(
                source(null, null), nodes, nodeIdMap, TopicLocationContract.INTENT_LEGACY, null, new Date());
        assertEquals("LEGACY", TopicLocationContract.parse(legacyNoCity).path("adoption")
                .path("bindingIntent").asText(), "旧版采用无城市也必须能落合同");
    }

    @Test
    void sourceContractWithExplicitSlotsIsCarriedAndItsBindingsRemapped() {
        String sourceContract = "{\"schemaVersion\":1,\"locationSlots\":[" + slot("bridge", true) + "],"
                + "\"locationBindings\":[{\"slotKey\":\"bridge\",\"nodeId\":\"11\",\"cityCode\":\"310100\","
                + "\"bindingRevision\":3,\"verificationStatus\":\"BOUND_UNVERIFIED\","
                + "\"confirmedAt\":\"2026-11-01 10:00:00\",\"observationNote\":\"店内在二楼\","
                + "\"referenceMediaIds\":[\"m-1\"],\"approvedFallbackNodeIds\":[\"12\"]}]}";
        List<CmsTopicNode> nodes = Arrays.asList(node(11L, "上海市黄浦区"), node(12L, "上海市静安区"));
        Map<Long, Long> nodeIdMap = new LinkedHashMap<Long, Long>();
        nodeIdMap.put(11L, 111L);
        nodeIdMap.put(12L, 112L);

        String sameCity = TopicLocationContract.buildAdoptionContract(
                source(sourceContract, "310100"), nodes, nodeIdMap,
                TopicLocationContract.INTENT_SAME_CITY, "310100", new Date());
        JsonNode binding = TopicLocationContract.parse(sameCity).path("locationBindings").get(0);
        assertEquals("111", binding.path("nodeId").asText());
        assertEquals("112", binding.path("approvedFallbackNodeIds").get(0).asText());
        assertTrue(binding.path("observationNote").isNull(), "来源作者的现场备注不随采用继承");
        assertTrue(binding.path("referenceMediaIds").isEmpty(), "来源媒体引用不随采用继承");
        assertEquals("BOUND_UNVERIFIED", binding.path("verificationStatus").asText());

        String newCity = TopicLocationContract.buildAdoptionContract(
                source(sourceContract, "310100"), nodes, nodeIdMap,
                TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());
        JsonNode moved = TopicLocationContract.parse(newCity).path("locationBindings").get(0);
        assertTrue(moved.path("nodeId").isNull());
        assertTrue(moved.path("approvedFallbackNodeIds").isEmpty(), "旧城备用点不得当新城的已批准备用");
    }

    @Test
    void applyBindingWritesServerFactsAndEnforcesRevision() {
        List<CmsTopicNode> nodes = Collections.singletonList(node(11L, "上海市黄浦区"));
        String contract = TopicLocationContract.buildAdoptionContract(source(null, "310100"), nodes,
                Collections.singletonMap(11L, 111L), TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());

        String bound = TopicLocationContract.applyBinding(contract, "loc-11", 501L,
                Collections.singletonList(502L), "09:00-18:00", "站在东侧",
                Collections.singletonList("media-9"), 0, "320100", 2000L, new Date());
        JsonNode binding = TopicLocationContract.parse(bound).path("locationBindings").get(0);
        assertEquals("501", binding.path("nodeId").asText());
        assertEquals(1, binding.path("bindingRevision").asInt());
        assertEquals("BOUND_UNVERIFIED", binding.path("verificationStatus").asText());
        assertEquals("2000", binding.path("confirmedBy").asText());
        assertNotNull(binding.path("confirmedAt").asText());
        assertEquals("502", binding.path("approvedFallbackNodeIds").get(0).asText());
        assertTrue(TopicLocationContract.bindingIssues(bound, "320100").isEmpty());

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> TopicLocationContract.applyBinding(bound, "loc-11", 503L, null, null, null, null,
                        0, "320100", 2000L, new Date()),
                "旧 revision 必须被拒");

        String unbound = TopicLocationContract.applyBinding(bound, "loc-11", null, null, null, null, null,
                1, "320100", 2000L, new Date());
        assertEquals(TopicLocationContract.STATUS_UNBOUND,
                TopicLocationContract.parse(unbound).path("locationBindings").get(0)
                        .path("verificationStatus").asText());
    }

    @Test
    void bindingRejectsCityMismatchAndUnknownSlot() {
        List<CmsTopicNode> nodes = Collections.singletonList(node(11L, "上海市黄浦区"));
        String contract = TopicLocationContract.buildAdoptionContract(source(null, "310100"), nodes,
                Collections.singletonMap(11L, 111L), TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> TopicLocationContract.applyBinding(contract, "loc-11", 501L, null, null, null, null,
                        0, "310100", 2000L, new Date()));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> TopicLocationContract.applyBinding(contract, "missing", 501L, null, null, null, null,
                        0, "320100", 2000L, new Date()));
    }

    @Test
    void summaryExposesPendingRebindWithoutPrivateAuthorFields() {
        List<CmsTopicNode> nodes = Collections.singletonList(node(11L, "上海市黄浦区"));
        String contract = TopicLocationContract.buildAdoptionContract(source(null, "310100"), nodes,
                Collections.singletonMap(11L, 111L), TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());
        Map<String, Object> summary = TopicLocationContract.summary(contract);
        assertEquals("100", summary.get("sourceTemplateTopicId"));
        assertEquals(7L, summary.get("sourceConfigVersion"));
        assertEquals("NEW_CITY", summary.get("bindingIntent"));
        assertEquals("320100", summary.get("targetCityCode"));
        assertEquals(Collections.singletonList("loc-11"), summary.get("pendingRebindSlotKeys"));
        JsonNode view = TopicLocationContract.view(contract);
        assertFalse(view.path("locationBindings").get(0).has("observationNote")
                && !view.path("locationBindings").get(0).path("observationNote").isNull());
    }

    @Test
    void adoptionRevisionSurvivesCanonicalReadbackAndLegacyStaysNull() {
        String adopted = TopicLocationContract.buildAdoptionContract(source(null, "310100"),
                Collections.singletonList(node(11L, "原城地址")), Collections.singletonMap(11L, 111L),
                "NEW_CITY", "320100", new Date(), 9001L);
        assertEquals("9001", TopicLocationContract.summary(adopted).get("sourceContentRevisionId"));
        assertEquals("9001", TopicLocationContract.parse(adopted).path("adoption")
                .path("sourceContentRevisionId").asText());
        String legacy = TopicLocationContract.buildAdoptionContract(source(null, "310100"),
                Collections.singletonList(node(11L, "原城地址")), Collections.singletonMap(11L, 111L),
                "LEGACY", null, new Date());
        assertNull(TopicLocationContract.summary(legacy).get("sourceContentRevisionId"));
        com.fasterxml.jackson.databind.node.ObjectNode invalid = TopicLocationContract.parse(adopted);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("adoption"))
                .put("sourceContentRevisionId", "not-a-revision");
        assertThrows(IllegalArgumentException.class, () -> TopicLocationContract.canonical(invalid.toString()));
    }

    @Test
    void malformedContractIsWarningNotABlock() {
        List<Map<String, Object>> issues = TopicLocationContract.bindingIssues("{not json", "310100");
        assertEquals(1, issues.size());
        assertEquals(TopicLocationContract.LEVEL_WARN, issues.get(0).get("level"));
        assertEquals(TopicLocationContract.ISSUE_INVALID, issues.get(0).get("code"));
    }

    @Test
    void emptyContractHasNoIssuesAndNoSlots() {
        assertTrue(TopicLocationContract.bindingIssues(null, null).isEmpty());
        assertEquals(0, TopicLocationContract.view(null).path("locationSlots").size());
        assertEquals(TopicLocationContract.INTENT_LEGACY,
                TopicLocationContract.intent("  "));
    }
}
