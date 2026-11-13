package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Frozen producer contract only; consumer projection/permissions remain in the original owners. */
class TopicPublicationContentSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void freezesPresentationAndOriginalIdentityWhileKeepingSecretsOutsidePresentation() throws Exception {
        Fixture f = new Fixture();
        JsonNode frozen = JSON.readTree(f.compiler.freezePublication(f.topic, null));
        assertFalse(frozen.has("contentSnapshotOnly"));
        JsonNode answer = frozen.path("nodeAnswers").path("11");
        assertEquals(1, answer.path("contentSnapshotVersion").asInt());
        assertEquals(701L, answer.path("templateId").asLong());
        assertEquals(701L, answer.path("templatePresentation").path("id").asLong());
        assertEquals("hidden_menu", answer.path("templatePresentation").path("interactionType").asText());
        assertEquals("original question", answer.path("templatePresentation").path("questionName").asText());
        assertEquals("original title", answer.path("templatePresentation").path("title").asText());
        assertEquals("question-image", answer.path("templatePresentation").path("questionImg").asText());
        assertEquals("option-a", answer.path("templatePresentation").path("questionA").asText());
        assertEquals(88L, answer.path("templatePresentation").path("couponId").asLong());
        assertEquals("chapter-audio", answer.path("chapterAudioUrl").asText());
        assertEquals(10L, answer.path("nodePresentation").path("topicId").asLong());
        assertEquals("station hook", answer.path("nodePresentation").path("hookText").asText());
        for (String field : new String[]{"questionAnswer","correctAnswer","answerReveal","hint1","hint2", "advancedConfigJson", "rewardRulesJson", "xpValue"})
            assertFalse(answer.path("templatePresentation").has(field), field);
        assertEquals("secret", answer.path("questionAnswer").asText());
        assertEquals("hint-one", answer.path("hint1").asText());
        assertEquals("private-reveal", answer.path("answerReveal").asText());
        assertFalse(answer.path("nodePresentation").has("checkinCode"));
    }

    /**
     * BE-08:答案别名政策与 canonical answer 同源(模板私有 advancedConfigJson 根层),
     * 发布/建局时一起冻结进 nodeAnswers;没有别名就不写键 —— 老快照读回后仍走旧比较器。
     */
    @Test void freezesExplicitAnswerAliasesBesideTheAnswerAndOmitsTheKeyWhenNotConfigured() throws Exception {
        Fixture withPolicy = new Fixture();
        withPolicy.template.setFeedbackText("暗号正确");
        withPolicy.template.setAdvancedConfigJson("{\"schemaVersion\":1,\"answerPolicy\":"
                + "{\"version\":1,\"normalizationPolicy\":\"LEGACY_V1\",\"acceptedAliases\":[\"暗号\"]}}");
        JsonNode answer = JSON.readTree(withPolicy.compiler.freezePublication(withPolicy.topic, null))
                .path("nodeAnswers").path("11");
        assertEquals("secret", answer.path("questionAnswer").asText(), "canonical answer 保留");
        assertEquals(1, answer.path("answerPolicy").path("version").asInt());
        assertEquals("LEGACY_V1", answer.path("answerPolicy").path("normalizationPolicy").asText());
        assertEquals("暗号", answer.path("answerPolicy").path("acceptedAliases").path(0).asText());
        // 判题反馈复用既有冻结公开反馈字段(templatePresentation.feedbackText),不另造一段。
        assertEquals("暗号正确", answer.path("templatePresentation").path("feedbackText").asText());
        // 呈现白名单是玩家可见面:政策与答案一样不进 templatePresentation。
        assertFalse(answer.path("templatePresentation").has("answerPolicy"));
        assertFalse(answer.path("templatePresentation").has("acceptedAliases"));

        Fixture withoutPolicy = new Fixture();
        JsonNode legacy = JSON.readTree(withoutPolicy.compiler.freezePublication(withoutPolicy.topic, null))
                .path("nodeAnswers").path("11");
        assertFalse(legacy.has("answerPolicy"), "没配别名就不写键,旧局口径不变");
    }

    /** m6 M2:自由探索开放招商可零节点发布进招商,商家建点过审后再刷新快照;其余零节点仍拒。 */
    @Test void freeExploreRecruitmentPublishesWithoutNodesButOtherThemesStillNeedOne() throws Exception {
        Fixture f = new Fixture();
        when(f.nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        f.topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        f.chapter.setRecruitEnabled(1);
        JsonNode frozen = JSON.readTree(f.compiler.freezePublication(f.topic, null));
        assertEquals(0, frozen.path("routeStations").size());
        assertFalse(frozen.has("startNodeId"));

        f.chapter.setRecruitEnabled(0);
        IllegalArgumentException noRecruit = assertThrows(IllegalArgumentException.class,
                () -> f.compiler.freezePublication(f.topic, null));
        assertEquals("主题没有可运行节点", noRecruit.getMessage());
        f.chapter.setRecruitEnabled(1);
        f.topic.setProductType(1);
        assertThrows(IllegalArgumentException.class, () -> f.compiler.freezePublication(f.topic, null));
        f.topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        when(f.chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> f.compiler.freezePublication(f.topic, null));
    }

    @Test void legitimateNonTemplateStationHasNoInventedTemplateIdentity() throws Exception {
        Fixture f = new Fixture(); f.node.setTemplateId(null);
        JsonNode answer = JSON.readTree(f.compiler.freezePublication(f.topic, null)).path("nodeAnswers").path("11");
        assertEquals(1, answer.path("contentSnapshotVersion").asInt());
        assertTrue(answer.path("templateId").isNull());
        assertTrue(answer.path("templatePresentation").isObject());
        assertTrue(answer.path("templatePresentation").isEmpty());
        assertTrue(answer.path("nodePresentation").path("templateId").isNull());
        assertFalse(answer.has("validationMethod"));
    }

    @Test void rejectsInvalidNodeChapterOrTemplateIdentityInsteadOfMarkingAnIncompleteV1() {
        Fixture f = new Fixture(); f.node.setTopicId(99L);
        assertThrows(IllegalStateException.class, () -> f.compiler.freezePublication(f.topic, null));
        f.node.setTopicId(10L); f.chapter.setTopicId(99L);
        assertThrows(IllegalStateException.class, () -> f.compiler.freezePublication(f.topic, null));
        f.chapter.setTopicId(10L);
        when(f.templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> f.compiler.freezePublication(f.topic, null));
    }

    @Test void oldPublishedV1WithoutTheNewMarkersIsNotBackfilledFromLiveContent() throws Exception {
        Fixture f = new Fixture();
        ObjectNode old = (ObjectNode) JSON.readTree(f.compiler.freezePublication(f.topic, null));
        ((ObjectNode) old.path("nodeAnswers").path("11")).remove(java.util.Arrays.asList(
                "contentSnapshotVersion","templateId","templatePresentation","nodePresentation","chapterAudioUrl","hint1","hint2"));
        old.remove(java.util.Arrays.asList("stateKeySnapshotVersion", "contentSnapshotOnly"));
        f.topic.setPublishedConfigVersion(1L); f.topic.setPublishedRouteGraphJson(old.toString());
        f.template.setTitle("new title"); f.node.setName("new node");
        clearInvocations(f.nodes, f.templates, f.chapters, f.keys);
        JsonNode restored = JSON.readTree(f.compiler.freezePublishedJourney(f.topic));
        assertEquals(old, restored);
        verifyNoInteractions(f.nodes, f.templates, f.chapters, f.keys);
    }

    @Test void originalCheckRequiresAndEffectsKeepTheirNeedForARealRun() throws Exception {
        String[] blocks = {
                "{\"schemaVersion\":1,\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}",
                "{\"schemaVersion\":1,\"effects\":[{\"op\":\"SET\",\"var\":\"clue.a\",\"value\":1}]}",
                "{\"schemaVersion\":1,\"check\":{\"enabled\":true,\"checkId\":\"ask\"}}"
        };
        for (String block : blocks) {
            Fixture f = new Fixture(); f.template.setAdvancedConfigJson(block);
            if (block.contains("checkId")) f.topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
            java.util.List<ThemeStateKey> declarations = new java.util.ArrayList<>();
            for (String name : new String[]{"tag.x", "clue.a"}) {
                ThemeStateKey key = new ThemeStateKey(); key.setTopicId(10L); key.setStateKey(name);
                key.setKeyType(name.startsWith("tag.") ? "tag" : "flag"); key.setScope("run"); key.setInitialValue(0);
                declarations.add(key);
            }
            when(f.keys.selectByTopicId(10L)).thenReturn(declarations);
            JsonNode frozen = JSON.readTree(f.compiler.freezePublication(f.topic, null));
            assertFalse(frozen.has("contentSnapshotOnly"));
            assertTrue(com.chengyinhub.business.service.support.NodeJourneySnapshot.needsJourneyState(
                    frozen.path("nodeConfigs").path("11").toString()), block);
            assertEquals(JSON.readTree(block), frozen.path("nodeConfigs").path("11"));
        }
    }

    @Test void openingAndPublishedThemeConfigurationAreNotMistakenForMetadataOnly() throws Exception {
        Fixture opening = new Fixture();
        opening.topic.setJourneyStoryJson("{\"schemaVersion\":1,\"openingChapterId\":20}");
        opening.chapter.setBlocksJson("[]");
        opening.chapter.setVideoUrl("media-asset:41");
        opening.chapter.setVideoPosterUrl("media-asset:42");
        JsonNode withOpening = JSON.readTree(opening.compiler.freezePublication(opening.topic, null));
        assertFalse(withOpening.has("contentSnapshotOnly"));
        assertEquals("media-asset:41", withOpening.path("openingChapter").path("videoUrl").asText());
        assertEquals("media-asset:42", withOpening.path("openingChapter").path("videoPosterUrl").asText());
        assertEquals(Long.valueOf(20L), com.chengyinhub.business.domain.JourneyStory.openingChapterId(withOpening.path("journeyStory").asText()));
        Fixture theme = new Fixture(); theme.topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"endings\":[{\"id\":\"end\",\"title\":\"结束\",\"fallback\":true,\"success\":false}]}");
        JsonNode withTheme = JSON.readTree(theme.compiler.freezePublication(theme.topic, null));
        assertFalse(withTheme.has("contentSnapshotOnly"));
        assertTrue(withTheme.path("themeConfig").isObject());
        assertTrue(withTheme.path("themeDefinition").isObject());
    }

    /** D-82 章节级奖励随发布冻结进 thoughtChapters,玩家端结束卡读的是这一份。 */
    @Test void freezesChapterRewardTextIntoThoughtChapters() throws Exception {
        Fixture f = new Fixture();
        f.chapter.setRewardText("隐藏线索 ×1");
        JsonNode row = JSON.readTree(f.compiler.freezePublication(f.topic, null)).path("thoughtChapters").get(0);
        assertEquals(20L, row.path("chapterId").asLong());
        assertEquals("隐藏线索 ×1", row.path("rewardText").asText());
        Fixture none = new Fixture();
        assertTrue(JSON.readTree(none.compiler.freezePublication(none.topic, null))
                .path("thoughtChapters").get(0).path("rewardText").isNull(), "没设奖励 = 冻结 null,读侧不出那一行");
    }

    private static final class Fixture {
        final CmsTopic topic = new CmsTopic(); final CmsTopicNode node = new CmsTopicNode();
        final CmsTopicChapter chapter = new CmsTopicChapter(); final CmsMemberTemplate template = new CmsMemberTemplate();
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        final ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
        final TopicRoutePublicationCompiler compiler;
        Fixture() {
            topic.setId(10L); topic.setRouteMode("LINEAR");
            node.setId(11L); node.setTopicId(10L); node.setChapterId(20L); node.setTemplateId(701L);
            node.setStatus(1L); node.setName("station"); node.setHookText("station hook"); node.setCheckinCode("private-scan");
            chapter.setId(20L); chapter.setTopicId(10L); chapter.setAudioUrl("chapter-audio");
            template.setId(701L); template.setValidationMethod(1); template.setQuestionAnswer("secret");
            template.setTitle("original title"); template.setQuestionName("original question"); template.setQuestionImg("question-image");
            template.setQuestionA("option-a"); template.setInteractionType("hidden_menu"); template.setCouponId(88L);
            template.setHint1("hint-one"); template.setHint2("hint-two"); template.setAnswerReveal("private-reveal");
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
            when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
            when(keys.selectByTopicId(10L)).thenReturn(Collections.emptyList());
            compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, keys, Clock.systemUTC(), JSON,
                    new TopicRouteConfigValidator(), mock(CmsTopicMapper.class));
        }
    }
}
