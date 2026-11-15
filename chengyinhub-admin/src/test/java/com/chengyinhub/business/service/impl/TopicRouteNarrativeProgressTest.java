package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import java.util.Collections;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.ThemeRuntimeEngine;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicRouteNarrativeProgressTest {

    private final TopicRouteNarrativeProgress progress = new TopicRouteNarrativeProgress(
            mock(CmsTopicMapper.class),
            new ObjectMapper(),
            new TopicRouteEngine(),
            new TopicCompletionRulePolicy(),
            new ThemeRuntimeEngine());

    @Test
    void readEngineStateReturnsInitialStateForNullBlankAndNullLiteralJson() {
        for (String input : new String[] { null, "", "   ", "null" }) {
            TopicRouteEngine.State state = progress.readEngineState(input);
            assertNotNull(state);
            assertNull(state.getStatus());
            assertNotNull(state.getNodeStates());
            assertTrue(state.getNodeStates().isEmpty());
            assertNotNull(state.getVariables());
            assertNotNull(state.getTags());
            assertNotNull(state.getDecisionLog());
        }
    }

    @Test
    void readEngineStateIgnoresUnknownLegacyFieldsAndNullCollections() {
        String legacyJson = "{\"status\":\"COMPLETED\",\"earnedXp\":680,\"visitedNodeCount\":6,"
                + "\"variables\":{\"hp\":3},\"nodeStates\":null,\"tags\":null,\"decisionLog\":null}";

        TopicRouteEngine.State state = progress.readEngineState(legacyJson);

        assertEquals("COMPLETED", state.getStatus());
        assertEquals(3, state.getVariables().get("hp").intValue());
        assertNotNull(state.getNodeStates());
        assertTrue(state.getNodeStates().isEmpty());
        assertNotNull(state.getTags());
        assertNotNull(state.getDecisionLog());
    }

    @Test
    void readEngineStateRejectsMalformedJsonWhileOrEmptyDegradesSafely() {
        assertThrows(IllegalStateException.class, () -> progress.readEngineState("{not-json"));

        TopicRouteEngine.State fallback = progress.readEngineStateOrEmpty("{not-json");
        assertNotNull(fallback);
        assertNull(fallback.getStatus());
        assertNotNull(fallback.getNodeStates());
    }

    @Test
    void queryStateToleratesLegacySessionWithoutConfigSnapshotOrWithExtraStateFields() {
        TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(
                routeMapper,
                topicMapper,
                mock(CmsTopicNodeMapper.class),
                mock(CmsMemberTemplateMapper.class),
                mock(PlayerNodeProgressMapper.class),
                activityMapper,
                registrationMapper,
                mock(PlayerActivityRoleMapper.class),
                () -> 1L,
                Clock.system(ZoneId.of("Asia/Shanghai")),
                RouteReachabilityProvider.UNKNOWN);

        CmsTopic topic = new CmsTopic();
        topic.setId(992001L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedConfigVersion(1L);
        when(topicMapper.selectCmsTopicById(992001L)).thenReturn(topic);

        CmsActivity activity = new CmsActivity();
        activity.setId(992031L);
        activity.setTopicId(992001L);
        activity.setStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(992031L)).thenReturn(activity);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(992101L);
        reg.setMemberId(9004L);
        reg.setOwnerType(2L);
        reg.setOwnerId(992031L);
        reg.setRegistrationStatus(2);
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(reg));

        TopicRouteSession session = new TopicRouteSession();
        session.setId(993803L);
        session.setTopicId(992001L);
        session.setActivityId(992031L);
        session.setMemberId(9004L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setVersion(1);
        session.setConfigSnapshotJson(null);
        session.setStateJson("{\"status\":\"COMPLETED\",\"earnedXp\":680,\"variables\":{\"clue\":1},\"visitedNodeCount\":6}");
        when(routeMapper.selectCurrentByOwner(anyLong(), anyLong(), anyLong())).thenReturn(session);

        TopicRouteState view = runtime.queryState(9004L, 992001L, 992031L);

        assertNotNull(view);
        assertEquals(993803L, view.getSessionId());
        assertEquals("COMPLETED", view.getStatus());
    }
}
