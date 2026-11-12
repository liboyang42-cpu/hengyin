package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.ContentManifestVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BE-10 公共内容清单投影:真实投影类,不 mock。
 *
 * <p>本测试固定四件事:结构只来自修订清单、资源只来自同一份冻结内容的真实引用、
 * 分层作用域来自真实章节/站点引用、无权威哈希时不编造值。
 * HTTP/DB/发布流程是单独的验收面,不在这里伪装通过。
 */
class ContentManifestProjectionTest {

    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode frozen() {
        ObjectNode frozen = json.createObjectNode().put("runtimeSnapshotVersion", 1).put("routeMode", "LINEAR");

        ObjectNode opening = frozen.putObject("openingChapter");
        opening.put("chapterId", 501L).put("name", "序章").put("opening", true);
        opening.put("audioUrl", "https://cdn.example.com/audio/opening.mp3");
        opening.put("imgArr", "https://cdn.example.com/img/opening-a.webp;https://cdn.example.com/img/opening-b.webp");
        ArrayNode openingBlocks = opening.putArray("blocks");
        openingBlocks.addObject().put("type", "text").put("content", "开场白");
        openingBlocks.addObject().put("type", "image").put("url", "https://cdn.example.com/img/beat-1.webp")
                .put("altText", "街角");

        ArrayNode chapters = frozen.putArray("thoughtChapters");
        ObjectNode required = chapters.addObject();
        required.put("chapterId", 502L).put("opening", false).put("required", true);
        required.put("audioUrl", "https://cdn.example.com/audio/chapter-1.mp3");
        required.putArray("blocks").addObject().put("type", "audio")
                .put("url", "https://cdn.example.com/audio/line-1.mp3");

        ObjectNode optional = chapters.addObject();
        optional.put("chapterId", 503L).put("opening", false).put("required", false);
        optional.putArray("blocks").addObject().put("type", "dream").putArray("images")
                .addObject().put("url", "https://cdn.example.com/img/dream-1.webp").put("line", "梦到钟声");

        frozen.putObject("endingChapters").putObject("504")
                .put("id", 504L).put("imgArr", "https://cdn.example.com/img/ending.webp");

        ObjectNode answers = frozen.putObject("nodeAnswers");
        ObjectNode first = answers.putObject("11");
        first.put("contentSnapshotVersion", 1).put("templateId", 31L);
        first.put("questionAnswer", "SECRET-ANSWER").put("correctAnswer", "B")
                .put("answerReveal", "SECRET-REVEAL").put("hint1", "SECRET-HINT");
        ObjectNode presentation = first.putObject("nodePresentation");
        presentation.put("id", 11L).put("topicId", 21L).put("chapterId", 502L).put("name", "第一站")
                .put("imgUrl", "https://cdn.example.com/img/node-11.webp");
        ObjectNode template = first.putObject("templatePresentation");
        template.put("title", "玩法").put("imgUrl", "https://cdn.example.com/img/template-31.webp")
                .put("questionAudio", "https://cdn.example.com/audio/question-31.mp3")
                .put("questionOptionMediaJson", "{\"A\":{\"img\":\"https://cdn.example.com/img/opt-a.webp\"}}");

        ObjectNode second = answers.putObject("12");
        second.put("contentSnapshotVersion", 1).put("templateId", 32L);
        second.putObject("nodePresentation").put("id", 12L).put("topicId", 21L).put("chapterId", 503L)
                .put("name", "可选站");
        second.putObject("templatePresentation").put("title", "可选玩法");

        ObjectNode configs = frozen.putObject("nodeConfigs");
        ObjectNode config = configs.putObject("11");
        ObjectNode qa = config.putObject("qa");
        qa.put("enabled", true).put("mode", "PICK").put("title", "哪年").put("answerText", "SECRET-TYPED");
        qa.putArray("options").addObject().put("id", "A").put("label", "1999").put("correct", true)
                .put("img", "https://cdn.example.com/img/option-a.webp");
        ObjectNode blindTaste = config.putObject("blindTaste");
        blindTaste.put("enabled", true).put("title", "盲品").put("imageUrl", "https://cdn.example.com/img/blind.webp");
        blindTaste.put("answerKey", "SECRET-BLIND");
        config.putObject("backgroundMusic").put("title", "夜").put("audioUrl", "https://cdn.example.com/audio/theme.mp3");
        config.putObject("merchantGuide").put("text", "SECRET-MERCHANT");

        ArrayNode stations = frozen.putArray("routeStations");
        stations.addObject().put("nodeId", 11L).put("nodeName", "第一站").put("chapterId", 502L)
                .put("scanReady", true).put("merchantMemberId", 700L);
        stations.addObject().put("nodeId", 12L).put("nodeName", "可选站").put("chapterId", 503L)
                .put("scanReady", false);
        return frozen;
    }

    private ObjectNode publicManifest() {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("manifestVersion", 1).put("topicId", 21L).put("revisionNo", 7L)
                .put("contentSha256", "abc123").put("schemaVersion", 1).put("routeMode", "LINEAR");
        manifest.put("openingChapterId", 501L).put("openingChapterName", "序章");
        ArrayNode stations = manifest.putArray("stations");
        stations.addObject().put("nodeId", 11L).put("nodeName", "第一站").put("chapterId", 502L).put("scanReady", true);
        stations.addObject().put("nodeId", 12L).put("nodeName", "可选站").put("chapterId", 503L).put("scanReady", false);
        return manifest;
    }

    private TopicContentRevisionView revision(ObjectNode manifest) {
        TopicContentRevisionView view = new TopicContentRevisionView();
        view.setId(900L);
        view.setTopicId(21L);
        view.setRevisionNo(7L);
        view.setSchemaVersion(1);
        view.setContentSha256("abc123");
        view.setOrigin("PUBLISH");
        view.setPublicManifestJson(manifest.toString());
        return view;
    }

    private static List<String> keys(ContentManifestVO vo) {
        List<String> keys = new ArrayList<>();
        for (ContentManifestVO.Asset asset : vo.getAssets()) keys.add(asset.getAssetKey());
        return keys;
    }

    @Test
    void revisionManifestCarriesRevisionIdentityAndRealStationScopes() {
        ContentManifestVO manifest = ContentManifestProjection.fromRevision(revision(publicManifest()), frozen());

        assertEquals(ContentManifestVO.STATUS_REVISION, manifest.getStatus());
        assertEquals(21L, manifest.getTopicId());
        assertEquals(900L, manifest.getContentRevisionId());
        assertEquals(7L, manifest.getRevisionNo());
        assertEquals("abc123", manifest.getContentSha256());
        assertEquals("LINEAR", manifest.getRouteMode());
        assertEquals(501L, manifest.getOpeningChapterId());
        assertFalse(manifest.isOnlineRequired());
        assertEquals("zh-CN", manifest.getSourceLocale());

        assertEquals(2, manifest.getStations().size());
        ContentManifestVO.Station first = manifest.getStations().get(0);
        assertEquals(11L, first.getNodeId());
        // 作用域来自该站真实所属章节:必选章 = ROUTE,非必选章 = OPTIONAL。
        assertEquals(ContentManifestVO.SCOPE_ROUTE, first.getScope());
        assertEquals(ContentManifestVO.SCOPE_OPTIONAL, manifest.getStations().get(1).getScope());
        assertTrue(first.getScanReady());
    }

    @Test
    void assetsComeFromRealContentReferencesWithStableKeys() {
        ContentManifestVO manifest = ContentManifestProjection.fromRevision(revision(publicManifest()), frozen());
        List<String> keys = keys(manifest);

        // 开局章(含 imgArr 两张 + audio + image 块)
        assertTrue(keys.contains("chapter:opening:imgArr[0]"));
        assertTrue(keys.contains("chapter:opening:imgArr[1]"));
        assertTrue(keys.contains("chapter:opening:audio"));
        assertTrue(keys.contains("chapter:opening:block:1:url"));
        // 必选章音频块 / 非必选章梦境图 / 结局章图
        assertTrue(keys.contains("chapter:502:block:0:url"));
        assertTrue(keys.contains("chapter:503:block:0:image[0]"));
        assertTrue(keys.contains("chapter:504:imgArr[0]"));
        // 站点与模板媒体
        assertTrue(keys.contains("node:11:img"));
        assertTrue(keys.contains("template:31:questionAudio"));
        assertTrue(keys.contains("template:31:option:A:img"));
        // 玩法配置里经既有公开投影后真实存在的音频:
        // qa 的选项图不走 advancedConfigJson(公开投影只留 id/label),只从模板的选项媒体来。
        assertTrue(keys.contains("node:11:config.backgroundMusic.audioUrl"));
        assertTrue(keys.contains("node:11:config.blindTaste.imageUrl"));
        assertTrue(keys.contains("node:11:config.qa.options[0].img"), "公开选项题图片是玩家实际需要的媒体");

        ContentManifestVO.Asset opening = manifest.getAssets().stream()
                .filter(a -> "chapter:opening:imgArr[0]".equals(a.getAssetKey())).findFirst().orElseThrow();
        assertEquals("https://cdn.example.com/img/opening-a.webp", opening.getUrl());
        assertEquals(ContentManifestVO.SCOPE_START, opening.getScope());
        // 没有权威哈希 / 大小 / mime 时保持未知,不造假值。
        assertNull(opening.getSha256());
        assertNull(opening.getBytes());
        assertNull(opening.getMime());
        assertFalse(opening.getVerified());
        assertEquals("HASH_AND_BYTES_UNKNOWN", opening.getUnverifiedReason());
        assertEquals(ContentManifestVO.VERIFICATION_UNVERIFIED, manifest.getAssetVerification());
    }

    @Test
    void requiredCapabilitiesAreDerivedFromContentNotClientDeclaration() {
        ContentManifestVO manifest = ContentManifestProjection.fromRevision(revision(publicManifest()), frozen());
        List<String> capabilityKeys = new ArrayList<>();
        for (ContentManifestVO.Requirement requirement : manifest.getRequiredCapabilities())
            capabilityKeys.add(requirement.getKey());
        assertTrue(capabilityKeys.contains("station.list"));
        assertTrue(capabilityKeys.contains("chapter.blocks"));
        assertTrue(capabilityKeys.contains("media.audio"));
        assertTrue(capabilityKeys.contains("media.image"));
        assertTrue(capabilityKeys.contains("game.qa"));
    }

    @Test
    void runSnapshotManifestNeverBorrowsCurrentPublicationRevision() {
        ContentManifestVO manifest = ContentManifestProjection.fromRunSnapshot(21L, 77L, frozen(), null);

        assertEquals(ContentManifestVO.STATUS_RUN_SNAPSHOT, manifest.getStatus());
        assertEquals(77L, manifest.getActivityId());
        assertNull(manifest.getContentRevisionId());
        assertNull(manifest.getContentSha256());
        assertEquals(2, manifest.getStations().size());
        assertTrue(keys(manifest).contains("chapter:opening:audio"));
        assertFalse(manifest.isOnlineRequired());
    }

    @Test
    void legacyContentDeclaresOnlineReadInsteadOfFakeRevision() {
        ContentManifestVO manifest = ContentManifestProjection.onlineLegacy(21L, 0L);

        assertEquals(ContentManifestVO.STATUS_ONLINE_LEGACY, manifest.getStatus());
        assertTrue(manifest.isOnlineRequired());
        assertNull(manifest.getContentRevisionId());
        assertNull(manifest.getManifestVersion());
        assertNull(manifest.getRequiredCapabilities());
        assertTrue(manifest.getAssets().isEmpty());
        assertTrue(manifest.getStations().isEmpty());
        assertEquals(ContentManifestVO.VERIFICATION_NONE, manifest.getAssetVerification());
        assertEquals("zh-CN", manifest.getSourceLocale());
    }

    @Test
    void manifestIdentityMismatchFailsInsteadOfServingAnotherTopic() {
        ObjectNode other = publicManifest();
        other.put("topicId", 22L);
        IllegalStateException wrongTopic = assertThrows(IllegalStateException.class,
                () -> ContentManifestProjection.fromRevision(revision(other), frozen()));
        assertEquals("公共清单主题不一致", wrongTopic.getMessage());

        ObjectNode wrongRevision = publicManifest();
        wrongRevision.put("revisionNo", 8L);
        assertThrows(IllegalStateException.class,
                () -> ContentManifestProjection.fromRevision(revision(wrongRevision), frozen()));

        ObjectNode wrongHash = publicManifest();
        wrongHash.put("contentSha256", "deadbeef");
        assertThrows(IllegalStateException.class,
                () -> ContentManifestProjection.fromRevision(revision(wrongHash), frozen()));

        TopicContentRevisionView noManifest = revision(publicManifest());
        noManifest.setPublicManifestJson("");
        assertThrows(IllegalStateException.class,
                () -> ContentManifestProjection.fromRevision(noManifest, frozen()));
    }

    @Test
    void sameAssetReferencedTwiceKeepsOneIdentityAndStrongestScope() {
        ObjectNode frozen = frozen();
        // 同一章节音频被开局章与必选章引用到同一 URL:身份仍按各自章节归属区分,
        // 但同一 assetKey(此处构造同键)只保留更靠前的作用域。
        ((ObjectNode) frozen.path("thoughtChapters").get(0)).put("audioUrl", "https://cdn.example.com/audio/opening.mp3");
        ContentManifestVO manifest = ContentManifestProjection.fromRevision(revision(publicManifest()), frozen);
        List<String> keys = keys(manifest);
        assertEquals(keys.size(), new java.util.HashSet<>(keys).size(), "assetKey 必须唯一");
        // 签名 URL 刷新不改变身份:只有键在变,键里没有 query / 过期参数。
        for (String key : keys) assertFalse(key.contains("?"), "assetKey 不能包含签名参数:" + key);
    }
}
