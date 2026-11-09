package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端能力声明只读合同：从内容推导、声明缺失不当作不支持、声明不授予任何权限。
 */
class ContentCapabilityRequirementsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode snapshot() {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("routeMode", "LINEAR");
        ObjectNode answer = snapshot.putObject("nodeAnswers").putObject("11");
        answer.put("contentSnapshotVersion", 1).put("templateId", 31L);
        answer.putObject("nodePresentation").put("id", 11L).put("topicId", 21L)
                .put("imgUrl", "/station.png");
        answer.putObject("templatePresentation").put("title", "题").put("validationMethod", 5)
                .put("questionAudio", "/q.mp3");
        ObjectNode chapter = snapshot.putArray("thoughtChapters").addObject();
        chapter.put("chapterId", 20).put("name", "章");
        chapter.putArray("blocks").addObject().put("type", "text").put("content", "正文");
        snapshot.putObject("nodeConfigs").putObject("11").putObject("qa").put("enabled", true);
        return snapshot;
    }

    private List<String> keys(JsonNode rows) {
        List<String> keys = new ArrayList<>();
        for (JsonNode row : rows) keys.add(row.path("key").asText());
        return keys;
    }

    @Test
    void requiredCapabilitiesComeFromActualFrozenContent() {
        List<String> keys = keys(ContentCapabilityRequirements.required(snapshot()));
        assertTrue(keys.contains("answer.gps"), "vm=5 需要定位能力");
        assertTrue(keys.contains("location.gps"));
        assertTrue(keys.contains("media.audio"));
        assertTrue(keys.contains("media.image"));
        assertTrue(keys.contains("chapter.blocks.text"));
        assertTrue(keys.contains("game.qa"), "启用的玩法段必须声明");
        assertFalse(keys.contains("game.sort"), "没启用的玩法段不得凭空声明");
        // 升序且去重:同一能力只出现一次
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        assertEquals(sorted, keys);
    }

    @Test
    void undeclaredClientIsUnconfirmedAndNeverBlocked() {
        ObjectNode compatibility = ContentCapabilityRequirements.compatibility(snapshot(),
                ContentCapabilityRequirements.Declaration.NONE);
        assertTrue(compatibility.path("supported").isNull(), "未声明 = 未确认,不是不支持");
        assertEquals("UNDECLARED", compatibility.path("source").asText());
        assertFalse(compatibility.path("declared").asBoolean());
        assertTrue(compatibility.path("missingCapabilities").isEmpty());
    }

    @Test
    void declaredClientGetsAnExplicitVerdictWithoutGrantingAnything() {
        ContentCapabilityRequirements.Declaration partial = ContentCapabilityRequirements.Declaration.parse(
                "wechat", "8.0.1", "answer.gps@1,media.audio@1");
        ObjectNode partialView = ContentCapabilityRequirements.compatibility(snapshot(), partial);
        assertFalse(partialView.path("supported").asBoolean());
        assertEquals("DECLARED", partialView.path("source").asText());
        List<String> missing = keys(partialView.path("missingCapabilities"));
        assertTrue(missing.contains("chapter.blocks.text"));
        assertFalse(missing.contains("answer.gps"), "已声明的能力不应重复报缺");

        ContentCapabilityRequirements.Declaration full = ContentCapabilityRequirements.Declaration.parse(
                // location.gps 是 vm=5 的派生要求(先到站才能作答),漏了它这份声明就不是「全声明」,
                // 会把「测试夹具不全」误报成「实现判错」。
                "wechat", "8.0.1", "answer.gps@1,location.gps@1,media.audio@1,media.image@1,chapter.blocks@1,"
                        + "chapter.blocks.text@1,station.list@1,game.qa@1");
        ObjectNode fullView = ContentCapabilityRequirements.compatibility(snapshot(), full);
        assertTrue(fullView.path("supported").asBoolean());
        assertTrue(fullView.path("missingCapabilities").isEmpty());
        // 声明不等于授权:响应里没有任何权限 / 权益字段
        assertFalse(fullView.has("entitlement"));
        assertFalse(fullView.has("allowed"));
    }

    @Test
    void malformedDeclarationIsUnconfirmedNotASilentPass() {
        ContentCapabilityRequirements.Declaration malformed = ContentCapabilityRequirements.Declaration.parse(
                "wechat", "8.0.1", "answer.gps@not-a-version");
        ObjectNode view = ContentCapabilityRequirements.compatibility(snapshot(), malformed);
        assertFalse(view.path("declared").asBoolean());
        assertEquals("MALFORMED", view.path("source").asText());
        assertTrue(view.path("supported").isNull());
    }

    @Test
    void choiceStationRequiresChoiceAndItsMediaNotGps() {
        ObjectNode snapshot = snapshot();
        ObjectNode template = (ObjectNode) snapshot.path("nodeAnswers").path("11").path("templatePresentation");
        template.put("validationMethod", 3);
        template.put("questionA", "A");
        List<String> keys = keys(ContentCapabilityRequirements.required(snapshot));
        assertTrue(keys.contains("answer.choice"));
        assertFalse(keys.contains("answer.gps"), "换了完成方式就不能再声明上一个完成方式");
        assertFalse(keys.contains("location.gps"));
    }

    @Test
    void openingObjectAndLegacyBranchEdgesContributeRequiredCapabilities() {
        ObjectNode source = JSON.createObjectNode();
        source.putObject("openingChapter").putArray("blocks").addObject().put("type", "audio");
        source.putArray("edges").addObject().put("fromNodeId", 1).put("toNodeId", 2);
        List<String> required = keys(ContentCapabilityRequirements.required(source));
        assertTrue(required.contains("chapter.blocks"));
        assertTrue(required.contains("chapter.blocks.audio"));
        assertTrue(required.contains("route.branch"));
    }

    @Test
    void explicitEmptyListsAreUnsupportedButAbsentDeclarationsStayUnconfirmed() {
        for (Object raw : java.util.List.of("", java.util.List.of())) {
            var declaration = ContentCapabilityRequirements.Declaration.parseValue("ios", "1", raw);
            assertTrue(declaration.supplied());
            assertTrue(declaration.declared());
            assertFalse(ContentCapabilityRequirements.compatibility(snapshot(), declaration).path("supported").asBoolean());
            assertThrows(com.chengyinhub.business.service.support.ContentCapabilityRequirements.UnsupportedClientException.class,
                    () -> ContentCapabilityRequirements.assertStartCompatible(snapshot(), declaration));
        }
        assertDoesNotThrow(() -> ContentCapabilityRequirements.assertStartCompatible(snapshot(),
                ContentCapabilityRequirements.Declaration.parse("ios", "1", null)));
    }

    @Test
    void startChecksFrozenRequirementsEvenIfAClientOrAuthorSuppliesAnEmptyRequiredList() {
        ObjectNode source = snapshot();
        source.putArray("requiredCapabilities");
        var missing = ContentCapabilityRequirements.Declaration.parse("ios", "1", "game.qa@1");
        assertThrows(com.chengyinhub.business.service.support.ContentCapabilityRequirements.UnsupportedClientException.class,
                () -> ContentCapabilityRequirements.assertStartCompatible(source, missing));
        java.util.Map<String, Integer> versions = new java.util.TreeMap<>();
        for (JsonNode required : ContentCapabilityRequirements.required(source))
            versions.put(required.path("key").asText(), required.path("minVersion").asInt());
        assertDoesNotThrow(() -> ContentCapabilityRequirements.assertStartCompatible(source,
                new ContentCapabilityRequirements.Declaration("ios", "1", versions, false)));
        versions.put("game.qa", 0);
        assertThrows(com.chengyinhub.business.service.support.ContentCapabilityRequirements.UnsupportedClientException.class,
                () -> ContentCapabilityRequirements.assertStartCompatible(source,
                        new ContentCapabilityRequirements.Declaration("ios", "1", versions, false)));
    }

    @Test
    void malformedExplicitStartCannotFallBackToLegacyCompatibility() {
        for (Object raw : java.util.List.of("game.qa@bad", "game.qa@0", java.util.Map.of("requiredCapabilities", java.util.List.of()))) {
            var declaration = ContentCapabilityRequirements.Declaration.parseValue("ios", "1", raw);
            assertThrows(com.chengyinhub.business.service.support.ContentCapabilityRequirements.UnsupportedClientException.class,
                    () -> ContentCapabilityRequirements.assertStartCompatible(snapshot(), declaration));
        }
        var rows = ContentCapabilityRequirements.Declaration.parseValue("ios", "1",
                java.util.List.of(java.util.Map.of("key", "game.qa", "version", 2)));
        assertEquals(2, rows.capabilities().get("game.qa"));
    }
}
