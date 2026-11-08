package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.ContentManifestVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交付形状合同:三种 status 的字段集合必须与 {@code docs/delivery/content-manifest/} 下的
 * 公开合同一字不差。多一个 getter(尤其是能带出答案的)或改名,这里就红。
 *
 * <p>为什么不引 JSON-Schema 校验库:本仓不新增依赖。字段集合 + 关键取值已能挡住
 * 「投影悄悄加字段」这类真实回归;真正的 schema 校验由端侧拿同一份 schema 做。
 */
class ContentManifestContractShapeTest {

    private static final Set<String> MANIFEST_KEYS = new LinkedHashSet<>(java.util.Arrays.asList(
            "status", "topicId", "activityId", "contentRevisionId", "revisionNo", "schemaVersion",
            "manifestVersion", "contentSha256", "routeMode", "openingChapterId", "openingChapterName",
            "stations", "requiredCapabilities", "assets", "assetVerification", "expiresAt",
            "onlineRequired", "sourceLocale"));

    private static final Set<String> STATION_KEYS = new LinkedHashSet<>(java.util.Arrays.asList(
            "nodeId", "nodeName", "chapterId", "scanReady", "scope"));

    private static final Set<String> ASSET_KEYS = new LinkedHashSet<>(java.util.Arrays.asList(
            "assetKey", "url", "sha256", "bytes", "mime", "priority", "scope", "textAlternative",
            "verified", "unverifiedReason"));

    private static final Set<String> REQUIREMENT_KEYS = new LinkedHashSet<>(java.util.Arrays.asList(
            "key", "minVersion"));

    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode frozen() {
        ObjectNode frozen = json.createObjectNode().put("runtimeSnapshotVersion", 1).put("routeMode", "LINEAR");
        frozen.putObject("openingChapter").put("chapterId", 501L).put("name", "序章")
                .put("audioUrl", "https://cdn.example.com/audio/opening.mp3");
        ObjectNode answer = frozen.putObject("nodeAnswers").putObject("11");
        answer.put("contentSnapshotVersion", 1).putObject("nodePresentation")
                .put("id", 11L).put("topicId", 21L).put("chapterId", 501L);
        frozen.putObject("nodeConfigs").putObject("11").putObject("qa").put("enabled", true);
        frozen.putArray("routeStations").addObject().put("nodeId", 11L).put("chapterId", 501L);
        return frozen;
    }

    private ContentManifestVO revisionManifest() {
        TopicContentRevisionView view = new TopicContentRevisionView();
        view.setId(900L);
        view.setTopicId(21L);
        view.setRevisionNo(7L);
        view.setSchemaVersion(1);
        view.setContentSha256("3d1f0a7c1b4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8");
        view.setPublicManifestJson("{\"manifestVersion\":1,\"topicId\":21,\"revisionNo\":7,"
                + "\"contentSha256\":\"3d1f0a7c1b4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8\","
                + "\"schemaVersion\":1,\"routeMode\":\"LINEAR\",\"openingChapterId\":501,"
                + "\"openingChapterName\":\"序章\",\"stations\":[{\"nodeId\":11,\"nodeName\":\"第一站\","
                + "\"chapterId\":501,\"scanReady\":false}]}");
        return ContentManifestProjection.fromRevision(view, frozen());
    }

    private static void assertKeys(String label, JsonNode node, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual, label + " 字段集合与公开合同不一致");
    }

    @Test
    void revisionManifestShapeMatchesPublicContract() {
        JsonNode serialized = json.valueToTree(revisionManifest());
        assertKeys("REVISION", serialized, MANIFEST_KEYS);
        assertKeys("station", serialized.path("stations").get(0), STATION_KEYS);
        assertKeys("asset", serialized.path("assets").get(0), ASSET_KEYS);
        assertKeys("requirement", serialized.path("requiredCapabilities").get(0), REQUIREMENT_KEYS);
        assertEquals("REVISION", serialized.path("status").asText());
        assertTrue(serialized.path("onlineRequired").isBoolean());
        assertFalse(serialized.path("onlineRequired").asBoolean());
        assertTrue(serialized.path("activityId").isNull());
    }

    @Test
    void runSnapshotShapeMatchesPublicContract() {
        JsonNode serialized = json.valueToTree(
                ContentManifestProjection.fromRunSnapshot(21L, 77L, frozen(), null));
        assertKeys("RUN_SNAPSHOT", serialized, MANIFEST_KEYS);
        assertEquals("RUN_SNAPSHOT", serialized.path("status").asText());
        assertTrue(serialized.path("revisionNo").isNull());
        assertTrue(serialized.path("contentSha256").isNull());
        assertEquals(77L, serialized.path("activityId").asLong());
    }

    @Test
    void legacyShapeMatchesPublicContract() {
        JsonNode serialized = json.valueToTree(ContentManifestProjection.onlineLegacy(21L, 0L));
        assertKeys("ONLINE_LEGACY", serialized, MANIFEST_KEYS);
        assertEquals("ONLINE_LEGACY", serialized.path("status").asText());
        assertTrue(serialized.path("onlineRequired").asBoolean());
        assertTrue(serialized.path("manifestVersion").isNull());
        assertEquals(0, serialized.path("assets").size());
        assertEquals("NONE", serialized.path("assetVerification").asText());
    }

    @Test
    void manifestNeverSerialisesPrivateSnapshotField() {
        String serialized = json.valueToTree(revisionManifest()).toString();
        assertFalse(serialized.contains("snapshotJson"));
        assertFalse(serialized.contains("nodeAnswers"));
    }
}
