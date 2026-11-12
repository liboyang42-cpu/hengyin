package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** BE-06:公共 manifest 白名单 与 新局快照头绑定 的边界(单元级)。 */
class TopicContentRevisionManifestAndHeaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String FROZEN = "{"
            + "\"runtimeSnapshotVersion\":1,"
            + "\"routeMode\":\"BRANCH_GRAPH\","
            + "\"r5AuthorValidationVersion\":2,"
            + "\"openingChapter\":{\"chapterId\":5,\"name\":\"第五章\"},"
            + "\"routeStations\":[{\"nodeId\":11,\"nodeName\":\"A\",\"chapterId\":5,\"scanReady\":true,"
            + "\"merchantMemberId\":77}],"
            + "\"nodeAnswers\":{\"11\":{\"questionAnswer\":\"秘密\"}},"
            + "\"nodeConfigs\":{\"11\":{\"kind\":\"qa\"}},"
            + "\"journeyRules\":{\"attributes\":[]},"
            + "\"requiredCapabilities\":[{\"key\":\"qa.text@1\",\"minVersion\":1}]"
            + "}";

    @Test
    void manifestKeepsOnlyTheWhitelistedNavigationFacts() throws Exception {
        JsonNode frozen = JSON.readTree(FROZEN);

        JsonNode manifest = TopicContentPublicManifest.build(frozen, 9L, 3L, "sha-abc");

        assertEquals(1, manifest.path("manifestVersion").asInt());
        assertEquals(9L, manifest.path("topicId").asLong());
        assertEquals(3L, manifest.path("revisionNo").asLong());
        assertEquals("sha-abc", manifest.path("contentSha256").asText());
        assertEquals(1, manifest.path("schemaVersion").asInt());
        assertEquals(2, manifest.path("authorValidationVersion").asInt());
        assertEquals(5L, manifest.path("openingChapterId").asLong());
        assertEquals("qa.text@1", manifest.path("requiredCapabilities").get(0).path("key").asText());
        assertEquals(1, manifest.path("stations").size());

        String text = manifest.toString();
        assertFalse(text.contains("秘密"));
        assertFalse(text.contains("nodeAnswers"));
        assertFalse(text.contains("nodeConfigs"));
        assertFalse(text.contains("journeyRules"));
        assertFalse(text.contains("merchantMemberId"));
    }

    @Test
    void absenceOfCapabilityDeclarationIsNotTurnedIntoAnEmptySupportedList() {
        ObjectNode frozen = JSON.createObjectNode();
        frozen.put("runtimeSnapshotVersion", 1);

        JsonNode manifest = TopicContentPublicManifest.build(frozen, 1L, 1L, "sha");

        assertFalse(manifest.has("requiredCapabilities"), "没有声明就是没有,不能拿空数组冒充「无能力要求」");
        assertFalse(manifest.has("optionalCapabilities"));
        assertEquals(0, manifest.path("stations").size());
    }

    @Test
    void nonObjectPayloadIsRejectedInsteadOfProducingAHalfManifest() {
        assertThrows(IllegalStateException.class,
                () -> TopicContentPublicManifest.build(JSON.readTree("[1]"), 1L, 1L, "sha"));
    }

    @Test
    void sessionSnapshotGetsTheRevisionHeaderOnlyWhenTheTopicHasAnIndexedRevision() throws Exception {
        ObjectNode frozen = (ObjectNode) JSON.readTree(FROZEN);
        TopicContentRevisionView revision = new TopicContentRevisionView();
        revision.setId(55L);
        revision.setRevisionNo(4L);
        revision.setSchemaVersion(1);
        revision.setContentSha256("sha-xyz");

        TopicContentRevisionHeader.bind(frozen, revision);

        assertEquals(55L, frozen.path("contentRevisionId").asLong());
        assertEquals(4L, frozen.path("contentRevisionNo").asLong());
        assertEquals(1, frozen.path("contentSchemaVersion").asInt());
        assertEquals("sha-xyz", frozen.path("contentSha256").asText());
        assertEquals("秘密", frozen.path("nodeAnswers").path("11").path("questionAnswer").asText(),
                "绑定只加头,不改冻结私有内容");
    }

    @Test
    void legacyTopicWithoutRevisionLeavesTheFrozenSnapshotUntouched() throws Exception {
        ObjectNode frozen = (ObjectNode) JSON.readTree(FROZEN);

        TopicContentRevisionHeader.bind(frozen, null);

        assertEquals(JSON.readTree(FROZEN), frozen, "无修订引用时不得伪造 id/hash,老局行为一字不变");
    }
}
