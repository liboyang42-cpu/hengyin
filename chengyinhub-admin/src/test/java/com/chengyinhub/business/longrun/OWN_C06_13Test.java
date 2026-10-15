package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Latest replay facts must never be delegated to the closed default run. */
class OWN_C06_13Test {
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession replay;
    @BeforeEach void setup() throws Exception {
        service = new TopicRouteRuntimeServiceImpl(); routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class); CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "routeMapper", routes);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        replay = new TopicRouteSession(); replay.setId(31L); replay.setTopicId(20L); replay.setActivityId(0L);
        replay.setMemberId(7L); replay.setPlayInstanceId("replay:30"); replay.setStatus(0); replay.setVersion(0);
        replay.setConfigSnapshotJson("{}"); replay.setStateJson(new ObjectMapper().writeValueAsString(new TopicRouteEngine.State()));
        when(routes.selectCurrentByOwner(0L,20L,7L)).thenReturn(replay);
        when(routes.selectAccessibleById(31L,7L)).thenReturn(replay);
        TopicRouteSession old = new TopicRouteSession(); old.setId(30L); old.setTopicId(20L);
        old.setActivityId(0L); old.setMemberId(7L); old.setStatus(2); old.setPlayInstanceId("default");
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(old);
        CmsTopic topic = new CmsTopic(); topic.setId(20L);
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode(); node.setId(40L); node.setTopicId(20L);
        when(nodes.selectCmsTopicNodeById(40L)).thenReturn(node);
        when(routes.insertAction(any())).thenReturn(1); when(routes.updateStateCas(any())).thenReturn(1);
    }
    @Test void compositePassedFactUsesVerifiedReplayInsteadOfDefault() {
        service.recordNodeResult(7L,20L,0L,40L,"replay:30","answer-1",true,false,0,Collections.emptyList());
        ArgumentCaptor<TopicRouteAction> action = ArgumentCaptor.forClass(TopicRouteAction.class);
        verify(routes).insertAction(action.capture()); assertEquals(Long.valueOf(31), action.getValue().getSessionId());
        verify(routes).updateStateCas(replay);
        verify(routes, never()).selectByOwner(0L,20L,7L,"default");
        assertTrue(replay.getStateJson().contains("sys.passed.40"));
    }
    @Test void changedInstanceDuringReloadCannotWriteAnotherRun() {
        TopicRouteSession other = new TopicRouteSession(); other.setId(31L); other.setTopicId(20L);
        other.setActivityId(0L); other.setMemberId(7L); other.setPlayInstanceId("replay:other");
        when(routes.selectAccessibleById(31L,7L)).thenReturn(other);
        assertThrows(ServiceException.class, () -> service.recordNodeResult(
                7L,20L,0L,40L,"replay:30","answer-1",true,false,0,Collections.emptyList()));
        verify(routes, never()).insertAction(any());
    }
    @Test void publicLegacyFactStillKeepsDefaultScopeAndClosedRunNoOp() {
        service.recordPassed(7L,20L,0L,40L,"answer-1",true);
        verify(routes).selectByOwner(0L,20L,7L,"default");
        verify(routes, never()).insertAction(any());
    }
}
