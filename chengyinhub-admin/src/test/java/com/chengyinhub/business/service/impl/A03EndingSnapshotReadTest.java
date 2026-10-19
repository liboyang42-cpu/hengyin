package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class A03EndingSnapshotReadTest {
    private final com.chengyinhub.business.mapper.CmsTopicChapterMapper chapterMapper =
            org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicChapterMapper.class);

    private PlayJourneyStoryServiceImpl service() {
        return new PlayJourneyStoryServiceImpl(
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicMapper.class),
                chapterMapper,
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class),
                org.mockito.Mockito.mock(com.chengyinhub.business.service.support.ThemeStateKeyService.class),
                org.mockito.Mockito.mock(NpcProfileService.class));
    }

    private JourneyStory.EndingCase chosen() {
        return JourneyStory.parse("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"old\",\"title\":\"旧\",\"chapterId\":88,\"fallback\":true}]}}")
                .getEnding().getEndings().get(0);
    }
    @Test void readsOnlyChosenFrozenChapterAndProjectsPrivateConditions() {
        TopicRouteSession session = new TopicRouteSession();
        session.setConfigSnapshotJson("{\"endingChapters\":{\"88\":{\"name\":\"旧正文\",\"blocks\":[{\"type\":\"text\",\"content\":\"旧结局\"},{\"type\":\"text\",\"content\":\"秘密\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.secret\"}}]},\"89\":{\"blocks\":[{\"type\":\"text\",\"content\":\"未选结局\"}]}}}");
        Map<String, Object> result = service().endingChapter(700L, chosen(), new TopicRouteEngine.State(), session);
        assertEquals("旧正文", result.get("name"));
        assertTrue(result.get("blocks").toString().contains("旧结局"));
        assertFalse(result.toString().contains("秘密"));
        assertFalse(result.toString().contains("未选结局"));
    }
    @Test void presentEmptySnapshotDoesNotFallBackToLiveChapter() {
        TopicRouteSession session = new TopicRouteSession();
        session.setConfigSnapshotJson("{\"endingChapters\":{}}");
        assertNull(service().endingChapter(700L, chosen(), new TopicRouteEngine.State(), session));
    }
    @Test void legacySnapshotWithoutKeyKeepsLiveFallback() {
        PlayJourneyStoryServiceImpl service = service();
        com.chengyinhub.business.mapper.CmsTopicChapterMapper mapper = chapterMapper;
        com.chengyinhub.business.domain.CmsTopicChapter chapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        chapter.setId(88L); chapter.setTopicId(700L); chapter.setName("旧局回退");
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"兼容正文\"}]");
        org.mockito.Mockito.when(mapper.selectCmsTopicChapterById(88L)).thenReturn(chapter);
        TopicRouteSession session = new TopicRouteSession(); session.setConfigSnapshotJson("{}");
        assertEquals("旧局回退", service.endingChapter(700L, chosen(), new TopicRouteEngine.State(), session).get("name"));
        session.setConfigSnapshotJson("{\"endingChapters\":{}}");
        assertNull(service.endingChapter(700L, chosen(), new TopicRouteEngine.State(), session));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1)).selectCmsTopicChapterById(88L);
    }
}
