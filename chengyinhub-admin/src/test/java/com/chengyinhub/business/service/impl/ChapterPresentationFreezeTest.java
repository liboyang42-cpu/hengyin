package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布冻结的章节呈现与判定方式（producer 合同，只跑编译期冻结代码）。
 *
 * <p>读侧之所以能不查实时章节行、不改判定口径，前提是发布时这些字段真的被写进快照。
 * 因此这里只断言冻结产物：章节呈现、必需标记、以及模板呈现里的 validationMethod。
 */
class ChapterPresentationFreezeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void chapterPresentationAndRequiredFlagAreFrozenWithThePublication() throws Exception {
        Fixture f = new Fixture();
        JsonNode frozen = JSON.readTree(f.compiler.freezePublication(f.topic, null));

        JsonNode chapter = frozen.path("thoughtChapters").path(0);
        assertEquals(20L, chapter.path("chapterId").asLong());
        assertEquals("发布时章名", chapter.path("name").asText());
        assertEquals("发布时简介", chapter.path("description").asText());
        assertEquals("/chapter-img.png", chapter.path("imgArr").asText());
        assertEquals("/chapter-audio.mp3", chapter.path("audioUrl").asText());
        assertEquals("night", chapter.path("atmospherePreset").asText());
        assertEquals(3L, chapter.path("sortId").asLong());
        assertEquals("story", chapter.path("category").asText());
        assertTrue(chapter.path("required").asBoolean());
        assertTrue(chapter.path("blocks").isArray());

        // 结局章同样带呈现,但只在通关后由 /journey/ending 读。
        JsonNode ending = frozen.path("endingChapters").path("90");
        assertEquals("原结局", ending.path("name").asText());
        assertEquals("/ending-audio.mp3", ending.path("audioUrl").asText());
        assertEquals("结局简介", ending.path("description").asText());

        // 判定方式写进模板呈现:键在即为权威,读侧不必再猜。
        JsonNode presentation = frozen.path("nodeAnswers").path("11").path("templatePresentation");
        assertTrue(presentation.has("validationMethod"));
        assertEquals(1, presentation.path("validationMethod").asInt());
    }

    @Test
    void nonTemplateStationHasNoInventedValidationMethod() throws Exception {
        Fixture f = new Fixture();
        f.node.setTemplateId(null);
        f.node.setChapterId(null);
        JsonNode answer = JSON.readTree(f.compiler.freezePublication(f.topic, null)).path("nodeAnswers").path("11");
        assertTrue(answer.path("templatePresentation").isObject());
        assertFalse(answer.path("templatePresentation").has("validationMethod"),
                "没有模板就没有判定方式,不能伪造一个");
    }

    @Test
    void frozenChapterBlocksKeepStoredBlocksRatherThanDescriptionFallback() throws Exception {
        Fixture f = new Fixture();
        f.chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"冻结正文\"}]");
        JsonNode chapter = JSON.readTree(f.compiler.freezePublication(f.topic, null)).path("thoughtChapters").path(0);
        assertEquals("冻结正文", chapter.path("blocks").path(0).path("content").asText());
    }

    private static final class Fixture {
        final CmsTopic topic = new CmsTopic();
        final CmsTopicNode node = new CmsTopicNode();
        final CmsTopicChapter chapter = new CmsTopicChapter();
        final CmsTopicChapter ending = new CmsTopicChapter();
        final CmsMemberTemplate template = new CmsMemberTemplate();
        final TopicRoutePublicationCompiler compiler;

        Fixture() {
            topic.setId(10L); topic.setRouteMode("LINEAR");
            topic.setJourneyStoryJson("{\"schemaVersion\":1,\"ending\":{\"endings\":["
                    + "{\"code\":\"done\",\"title\":\"结束\",\"fallback\":true,\"chapterId\":90}]}}");
            node.setId(11L); node.setTopicId(10L); node.setChapterId(20L); node.setTemplateId(701L);
            node.setStatus(1L); node.setName("站点"); node.setSortId(1L);
            chapter.setId(20L); chapter.setTopicId(10L);
            chapter.setName("发布时章名"); chapter.setDescription("发布时简介");
            chapter.setImgArr("/chapter-img.png"); chapter.setAudioUrl("/chapter-audio.mp3");
            chapter.setAtmospherePreset("night"); chapter.setSortId(3L); chapter.setCategory("story");
            chapter.setRequired(1);
            ending.setId(90L); ending.setTopicId(10L); ending.setName("原结局");
            ending.setDescription("结局简介"); ending.setAudioUrl("/ending-audio.mp3");
            ending.setBlocksJson("[{\"type\":\"text\",\"content\":\"结局正文\"}]");
            template.setId(701L); template.setValidationMethod(1); template.setTitle("原游戏");

            CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
            CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
            CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
            ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(java.util.Arrays.asList(chapter, ending));
            when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
            when(keys.selectByTopicId(10L)).thenReturn(Collections.emptyList());
            compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, keys, Clock.systemUTC(), JSON,
                    new TopicRouteConfigValidator(), mock(CmsTopicMapper.class));
        }
    }
}
