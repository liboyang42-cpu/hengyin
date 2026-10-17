package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ordinary read-service regression. All methods are written only: NOT_RUN. */
class E03_02Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PlayJourneyStoryServiceImpl service;
    private TopicRouteSession session;

    @BeforeEach
    void setup() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(700L);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"legacy\",\"title\":\"legacy title\",\"fallback\":true}]}}");
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        session = new TopicRouteSession();
        session.setTopicId(700L);
        session.setMemberId(97L);
        session.setActivityId(0L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setConfigSnapshotJson("{}");
        when(sessions.selectByOwner(0L, 700L, 97L, "default")).thenReturn(session);
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        when(keys.listByTopic(700L)).thenReturn(Collections.emptyList());
        service = new PlayJourneyStoryServiceImpl(topics, mock(CmsTopicChapterMapper.class),
                mock(CmsTopicNodeMapper.class), sessions, keys, mock(NpcProfileService.class));
    }

    @Test
    void presentNullScalarArrayAndEmptyObjectFailWithExistingBusinessError() {
        for (String broken : new String[]{"null", "false", "1", "\"bad\"", "[]", "[{}]", "{}"}) {
            session.setStateJson("{\"themeRuntime\":{\"result\":" + broken + "}}");
            ServiceException failure = assertThrows(ServiceException.class,
                    () -> service.ending(97L, 700L, 0L), broken);
            assertEquals("本局结局快照不可用", failure.getMessage());
        }
    }

    @Test
    void missingResultRetainsLegacyStoryEvaluation() {
        session.setStateJson("{\"themeRuntime\":{}}");
        Map<String, Object> result = service.ending(97L, 700L, 0L);
        assertEquals("legacy", result.get("code"));
        assertEquals("legacy title", result.get("title"));
    }

    @Test
    void actualTerminalWithoutEndingCandidateDoesNotRequireEndingId() throws Exception {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        ThemeRuntimeEngine engine = new ThemeRuntimeEngine();
        com.fasterxml.jackson.databind.node.ObjectNode config = ThemeRuntimeConfig.parse("{\"schemaVersion\":1}");
        engine.initialize(config, state);
        engine.terminal(config, state, new JourneyRules(), "COMPLETED", 1L);
        assertFalse(state.getThemeRuntime().path("result").has("endingId"));
        session.setStateJson(JSON.writeValueAsString(state));
        Map<String, Object> result = service.ending(97L, 700L, 0L);
        assertEquals("本局完成", result.get("title"));
        assertNull(result.get("code"));
        assertEquals("COMPLETED", result.get("reason"));
    }

    @Test
    void sparseHistoricalObjectKeepsExistingFieldsWithoutNewSchemaRequirements() {
        session.setStateJson("{\"themeRuntime\":{\"result\":{\"endingId\":\"old\",\"title\":\"old title\"}}}");
        Map<String, Object> result = service.ending(97L, 700L, 0L);
        assertEquals("old", result.get("code"));
        assertEquals("old title", result.get("title"));
        assertFalse(result.containsKey("summary"));
        assertFalse(result.containsKey("reason"));
        assertFalse(result.containsKey("story"));
    }
}
