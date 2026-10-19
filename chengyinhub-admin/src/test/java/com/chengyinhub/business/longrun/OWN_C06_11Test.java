package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.CurrentPlayTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Persisted response must survive deletion of the live author node. */
class OWN_C06_11Test {
    @Test void originalAskReceiptDoesNotReadDeletedLiveNode() {
        TopicRouteRuntimeServiceImpl service = new TopicRouteRuntimeServiceImpl();
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "routeMapper", routes);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        CurrentPlayTestFixtures.installOn(service);
        CmsTopic topic = new CmsTopic(); topic.setId(20L); topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        TopicRouteSession session = new TopicRouteSession(); session.setId(30L); session.setMemberId(7L);
        session.setTopicId(20L); session.setActivityId(0L); session.setStatus(TopicRouteSession.STATUS_RUNNING);
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(session);
        TopicRouteAction receipt = new TopicRouteAction(); receipt.setResponseJson("{\"nodeId\":40,\"questionId\":\"why\",\"a\":\"original\"}");
        when(routes.selectActionByAction(30L,7L,"ask:40:why")).thenReturn(receipt);
        assertEquals("original", service.ask(7L,20L,0L,40L,"why").get("a"));
        verify(nodes, never()).selectCmsTopicNodeById(anyLong());
        verify(routes, never()).insertAction(any());
    }
    @Test void missingReceiptStillRequiresLiveNodeBeforeNewQuestion() {
        TopicRouteRuntimeServiceImpl service = new TopicRouteRuntimeServiceImpl();
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "routeMapper", routes);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        CurrentPlayTestFixtures.installOn(service);
        CmsTopic topic = new CmsTopic(); topic.setId(20L); topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        TopicRouteSession session = new TopicRouteSession(); session.setId(30L); session.setMemberId(7L);
        session.setTopicId(20L); session.setActivityId(0L); session.setStatus(TopicRouteSession.STATUS_RUNNING);
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(session);
        assertThrows(IllegalStateException.class, () -> service.ask(7L,20L,0L,40L,"why"));
        verify(nodes).selectCmsTopicNodeById(40L);
        verify(routes, never()).insertAction(any());
    }
}
