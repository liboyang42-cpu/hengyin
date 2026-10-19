package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. E03's public four-argument compatibility overload is a required dependency. */
class OWN_C06_07Test {
    @Test void terminalFreezePassesOriginalSessionInsteadOfLiveFallback() {
        TopicRouteRuntimeServiceImpl service = new TopicRouteRuntimeServiceImpl(
                mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class),
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class),
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class),
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class),
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerActivityRoleMapper.class),
                mock(com.chengyinhub.business.service.IShopDayProofService.class),
                () -> 12345L,
                () -> 10,
                java.time.Clock.systemUTC(),
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
        PlayJourneyStoryServiceImpl stories = mock(PlayJourneyStoryServiceImpl.class);
        ReflectionTestUtils.setField(service, "themeStoryService", stories);
        TopicRouteSession session = new TopicRouteSession(); session.setId(30L); session.setTopicId(20L);
        session.setMemberId(7L); session.setConfigSnapshotJson("{\"journeyStory\":\"frozen-story\",\"endingChapters\":{}}");
        TopicRouteEngine.State state = new TopicRouteEngine.State(); state.getThemeRuntime().putObject("result");
        when(stories.freezeEnding(20L, "frozen-story", state, session)).thenReturn(Collections.<String,Object>singletonMap("code", "old"));
        ReflectionTestUtils.invokeMethod(service, "freezeThemeStory", session, state);
        verify(stories).freezeEnding(20L, "frozen-story", state, session);
        verify(stories, never()).freezeEnding(20L, "frozen-story", state);
        assertEquals("old", state.getThemeRuntime().path("result").path("story").path("code").asText());
    }
    @Test void noTerminalResultDoesNotFreezeAnEnding() {
        TopicRouteRuntimeServiceImpl service = new TopicRouteRuntimeServiceImpl(
                mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class),
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class),
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class),
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class),
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class),
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class),
                mock(com.chengyinhub.business.mapper.PlayerActivityRoleMapper.class),
                mock(com.chengyinhub.business.service.IShopDayProofService.class),
                () -> 12345L,
                () -> 10,
                java.time.Clock.systemUTC(),
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN);
        PlayJourneyStoryServiceImpl stories = mock(PlayJourneyStoryServiceImpl.class);
        ReflectionTestUtils.setField(service, "themeStoryService", stories);
        ReflectionTestUtils.invokeMethod(service, "freezeThemeStory", new TopicRouteSession(), new TopicRouteEngine.State());
        verifyNoInteractions(stories);
    }
}
