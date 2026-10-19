package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ordinary service regression only; not a real-MySQL claim. Written but NOT_RUN. */
class E03_01Test {
    private static final String STORY = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
            + "{\"code\":\"selected\",\"title\":\"selected\",\"chapterId\":88,\"fallback\":true}]}}";
    private CmsTopicChapterMapper chapters;
    private PlayJourneyStoryServiceImpl service;
    private GameRuntimeSessionMapper gameSessions;

    @BeforeEach
    void setup() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        chapters = mock(CmsTopicChapterMapper.class);
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        when(keys.listByTopic(700L)).thenReturn(Collections.emptyList());
        service = new PlayJourneyStoryServiceImpl(topics, chapters, mock(CmsTopicNodeMapper.class),
                mock(TopicRouteSessionMapper.class), keys, mock(NpcProfileService.class));
        gameSessions = mock(GameRuntimeSessionMapper.class);
        ReflectionTestUtils.setField(service, "gameSessionMapper", gameSessions);
    }

    @Test
    void fourArgumentsUseFrozenBodyAndProjectConditionsWithoutReadingLiveChapter() {
        TopicRouteSession session = session("{\"endingChapters\":{\"88\":{\"name\":\"old chapter\",\"blocks\":["
                + "{\"type\":\"text\",\"content\":\"old body\"},"
                + "{\"type\":\"text\",\"content\":\"private\",\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.secret\"}}]},"
                + "\"89\":{\"name\":\"unselected\",\"blocks\":[]}}}");
        Map<String, Object> result = service.freezeEnding(700L, STORY, new TopicRouteEngine.State(), session);
        Map<?, ?> chapter = (Map<?, ?>) result.get("chapter");
        assertEquals("old chapter", chapter.get("name"));
        assertTrue(chapter.get("blocks").toString().contains("old body"));
        assertFalse(chapter.toString().contains("private"));
        assertFalse(result.toString().contains("unselected"));
        assertFalse(result.containsKey("archive"), "freeze must not persist identity/photos/notes");
        verifyNoInteractions(gameSessions);
        verify(chapters, never()).selectCmsTopicChapterById(anyLong());
    }

    @Test
    void fourArgumentsDoNotRefillPresentEmptyFrozenChapterMap() {
        Map<String, Object> result = service.freezeEnding(700L, STORY, new TopicRouteEngine.State(),
                session("{\"endingChapters\":{}}"));
        assertNull(result.get("chapter"));
        verify(chapters, never()).selectCmsTopicChapterById(anyLong());
    }

    @Test
    void fourArgumentsKeepMissingKeyLegacyFallback() {
        liveChapter();
        Map<String, Object> result = service.freezeEnding(700L, STORY, new TopicRouteEngine.State(), session("{}"));
        assertEquals("live chapter", ((Map<?, ?>) result.get("chapter")).get("name"));
        verify(chapters).selectCmsTopicChapterById(88L);
    }

    @Test
    void originalThreeArgumentsKeepLiveCompatibilityBehavior() {
        liveChapter();
        Map<String, Object> result = service.freezeEnding(700L, STORY, new TopicRouteEngine.State());
        assertEquals("live chapter", ((Map<?, ?>) result.get("chapter")).get("name"));
        assertFalse(result.containsKey("archive"), "old entry retains its original result shape");
        verifyNoInteractions(gameSessions);
        verify(chapters).selectCmsTopicChapterById(88L);
    }

    private void liveChapter() {
        CmsTopicChapter live = new CmsTopicChapter();
        live.setId(88L);
        live.setTopicId(700L);
        live.setName("live chapter");
        live.setBlocksJson("[{\"type\":\"text\",\"content\":\"live body\"}]");
        when(chapters.selectCmsTopicChapterById(88L)).thenReturn(live);
    }

    private static TopicRouteSession session(String snapshot) {
        TopicRouteSession session = new TopicRouteSession();
        session.setTopicId(700L);
        session.setMemberId(97L);
        session.setActivityId(0L);
        session.setConfigSnapshotJson(snapshot);
        return session;
    }
}
