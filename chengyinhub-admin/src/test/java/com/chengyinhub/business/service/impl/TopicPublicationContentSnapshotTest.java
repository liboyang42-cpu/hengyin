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
        JsonNode withOpening = JSON.readTree(opening.compiler.freezePublication(opening.topic, null));
        assertFalse(withOpening.has("contentSnapshotOnly"));
        assertEquals(Long.valueOf(20L), com.chengyinhub.business.domain.JourneyStory.openingChapterId(withOpening.path("journeyStory").asText()));
        Fixture theme = new Fixture(); theme.topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"endings\":[{\"id\":\"end\",\"title\":\"结束\",\"fallback\":true,\"success\":false}]}");
        JsonNode withTheme = JSON.readTree(theme.compiler.freezePublication(theme.topic, null));
        assertFalse(withTheme.has("contentSnapshotOnly"));
        assertTrue(withTheme.path("themeConfig").isObject());
        assertTrue(withTheme.path("themeDefinition").isObject());
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
