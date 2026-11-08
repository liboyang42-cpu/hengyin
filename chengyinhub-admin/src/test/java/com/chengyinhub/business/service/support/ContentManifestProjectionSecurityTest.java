package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.ContentManifestVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BE-10 泄题负例:答案 / 别名 / 提示 / 揭示 / 商家秘密在<b>任意嵌套层</b>都不得出现在清单里。
 *
 * <p>这里的 payload 都是真实形状(nodeAnswers 私有段、高级玩法配置、章节块、BE-06 公共清单)
 * 的合成副本,不是「看起来像」的假字段名。一个字段在深层漏出去,和顶层漏出去后果一样。
 */
class ContentManifestProjectionSecurityTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 序列化后逐字 grep 的禁止串:只要出现即失败。 */
    private static final List<String> FORBIDDEN_VALUES = Arrays.asList(
            "SECRET-ANSWER", "SECRET-TYPED", "SECRET-REVEAL", "SECRET-HINT", "SECRET-MERCHANT",
            "SECRET-ALIAS", "SECRET-CORRECT", "SECRET-NESTED-ANSWER", "SECRET-BLIND", "SECRET-FB",
            "SECRET-CLUE-POOL", "SECRET-OUTCOME");

    private static final List<String> FORBIDDEN_KEYS = Arrays.asList(
            "questionAnswer", "correctAnswer", "answerReveal", "hint1", "hint2", "nodeAnswers",
            "aliases", "correct", "answerKey", "merchantGuide", "snapshotJson", "nodeConfigs",
            "themeConfig", "journeyRules", "checkinCode");

    private ObjectNode frozenWithSecrets() {
        ObjectNode frozen = json.createObjectNode().put("runtimeSnapshotVersion", 1);
        ObjectNode opening = frozen.putObject("openingChapter");
        opening.put("chapterId", 501L).put("name", "序章");
        opening.put("audioUrl", "https://cdn.example.com/audio/opening.mp3");
        ArrayNode blocks = opening.putArray("blocks");
        blocks.addObject().put("type", "text").put("content", "正文")
                .put("when", json.createObjectNode().put("op", "HAS_TAG").put("value", "SECRET-ALIAS"));
        blocks.addObject().put("type", "voice").put("who", "旁白").put("content", "SECRET-NESTED-ANSWER");

        ObjectNode answers = frozen.putObject("nodeAnswers");
        ObjectNode answer = answers.putObject("11");
        answer.put("contentSnapshotVersion", 1).put("templateId", 31L);
        answer.put("questionAnswer", "SECRET-ANSWER").put("correctAnswer", "B")
                .put("answerReveal", "SECRET-REVEAL").put("hint1", "SECRET-HINT").put("hint2", "SECRET-HINT");
        answer.putObject("nodePresentation").put("id", 11L).put("topicId", 21L).put("chapterId", 501L)
                .put("name", "第一站").put("checkinCode", "SECRET-CORRECT");
        answer.putObject("templatePresentation").put("title", "玩法")
                .put("imgUrl", "https://cdn.example.com/img/template.webp");

        ObjectNode config = frozen.putObject("nodeConfigs").putObject("11");
        ObjectNode qa = config.putObject("qa");
        qa.put("enabled", true).put("mode", "PICK").put("title", "题面").put("answerText", "SECRET-TYPED");
        qa.putArray("options").addObject().put("id", "A").put("label", "选项").put("correct", true)
                .put("fb", "SECRET-FB");
        config.putObject("blindTaste").put("enabled", true).put("title", "盲品")
                .put("answerKey", "SECRET-BLIND").put("hint", "SECRET-HINT")
                .put("imageUrl", "https://cdn.example.com/img/blind.webp");
        ObjectNode deep = config.putObject("check").putArray("mods").addObject()
                .put("value", 3).put("label", "加成").put("when", "SECRET-ALIAS");
        deep.put("adv", true);
        config.putObject("merchantGuide").put("secret", "SECRET-MERCHANT");
        config.putObject("outcomeRules").put("version", 1).put("successText", "SECRET-OUTCOME");

        ObjectNode station = frozen.putArray("routeStations").addObject();
        station.put("nodeId", 11L).put("nodeName", "第一站").put("chapterId", 501L)
                .put("merchantMemberId", 700L).put("checkinCode", "SECRET-CORRECT");
        return frozen;
    }

    private ObjectNode publicManifestWithLeak() {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("manifestVersion", 1).put("topicId", 21L).put("revisionNo", 7L)
                .put("contentSha256", "abc123").put("schemaVersion", 1).put("routeMode", "LINEAR");
        // 上游即使漏了这些字段,本层也必须剥掉(纵深防御)。
        manifest.put("aliases", json.createArrayNode().add("SECRET-ALIAS"));
        manifest.put("hint1", "SECRET-HINT");
        manifest.putObject("meta").putObject("inner").put("correctAnswer", "SECRET-ANSWER");
        ArrayNode stations = manifest.putArray("stations");
        stations.addObject().put("nodeId", 11L).put("nodeName", "第一站").put("chapterId", 501L)
                .put("scanReady", true).put("aliases", json.createArrayNode().add("SECRET-ALIAS"));
        return manifest;
    }

    private TopicContentRevisionView revision(ObjectNode manifest) {
        TopicContentRevisionView view = new TopicContentRevisionView();
        view.setId(900L);
        view.setTopicId(21L);
        view.setRevisionNo(7L);
        view.setSchemaVersion(1);
        view.setContentSha256("abc123");
        view.setPublicManifestJson(manifest.toString());
        return view;
    }

    private void assertNoSecrets(String serialized) {
        for (String value : FORBIDDEN_VALUES)
            assertFalse(serialized.contains(value), "清单泄漏了答案/秘密值:" + value);
        for (String key : FORBIDDEN_KEYS)
            assertFalse(serialized.contains("\"" + key + "\""), "清单泄漏了禁止键:" + key);
    }

    @Test
    void revisionManifestCarriesNoAnswersAliasesHintsOrMerchantSecrets() throws Exception {
        ContentManifestVO manifest = ContentManifestProjection.fromRevision(
                revision(publicManifestWithLeak()), frozenWithSecrets());
        String serialized = json.writeValueAsString(manifest);
        assertNoSecrets(serialized);
        // 真实媒介仍然在:剥离不能把合法内容一起删掉。
        assertTrue(serialized.contains("https://cdn.example.com/audio/opening.mp3"));
        assertTrue(serialized.contains("https://cdn.example.com/img/blind.webp"));
        // 商家点位身份不属于玩家清单。
        assertFalse(serialized.contains("merchantMemberId"));
        assertFalse(serialized.contains("700"));
    }

    @Test
    void runSnapshotManifestCarriesNoPrivateSegments() throws Exception {
        ContentManifestVO manifest = ContentManifestProjection.fromRunSnapshot(21L, 77L, frozenWithSecrets(), null);
        assertNoSecrets(json.writeValueAsString(manifest));
    }

    @Test
    void sanitizeRemovesForbiddenKeysAtArbitraryDepthButKeepsPublicFacts() {
        ObjectNode source = json.createObjectNode();
        source.put("manifestVersion", 1).put("topicId", 21L);
        ObjectNode deep = source.putObject("level1").putObject("level2").putArray("level3").addObject();
        deep.put("nodeId", 11L).put("hint2", "SECRET-HINT")
                .put("merchantGuide", "SECRET-MERCHANT");
        deep.putObject("display").put("correct", true).put("label", "合法标签");
        ArrayNode arrayDeep = deep.putArray("nested");
        arrayDeep.addObject().put("answerReveal", "SECRET-REVEAL").put("url", "https://cdn.example.com/a.webp");

        ObjectNode sanitized = ContentManifestProjection.sanitizePublicTree(source);
        String serialized = sanitized.toString();
        assertNoSecrets(serialized);
        assertEquals("合法标签", sanitized.path("level1").path("level2").path("level3").get(0)
                .path("display").path("label").asText());
        assertEquals("https://cdn.example.com/a.webp", sanitized.path("level1").path("level2")
                .path("level3").get(0).path("nested").get(0).path("url").asText());
        // 输入不被就地修改:调用方仍然持有原始对象。
        assertEquals("SECRET-HINT", source.path("level1").path("level2").path("level3").get(0)
                .path("hint2").asText());
    }

    @Test
    void capabilityDerivationIgnoresPrivateConfigSections() {
        // 端能力只按结段名推导;答案 / 别名不会因为出现在配置里就变成「能力」。
        List<ContentManifestVO.Requirement> capabilities =
                ContentManifestProjection.capabilities(frozenWithSecrets());
        assertNotNull(capabilities);
        for (ContentManifestVO.Requirement requirement : capabilities)
            for (String forbidden : FORBIDDEN_VALUES)
                assertFalse(requirement.getKey().contains(forbidden));
    }
}
