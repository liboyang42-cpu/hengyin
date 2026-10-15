package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Existing default completion must not bypass a replay's opening. */
class OWN_C06_16Test {
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession replay;
    @BeforeEach void setup() throws Exception {
        service = new TopicRouteRuntimeServiceImpl(
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
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN); routes = mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(service,"routeMapper",routes);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service,"topicMapper",topics);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service,"nodeMapper",nodes);
        PlayerNodeProgressMapper progress = mock(PlayerNodeProgressMapper.class);
        ReflectionTestUtils.setField(service,"progressMapper",progress);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        CurrentPlayTestFixtures.installOn(service);
        CmsTopic topic = new CmsTopic(); topic.setId(20L); topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        replay = session(31L, "replay:30", false, 5);
        when(routes.selectCurrentByOwner(0L,20L,7L)).thenReturn(replay);
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(session(30L,"default",true,9));
    }
    private TopicRouteSession session(long id,String instance,boolean done,int version) throws Exception {
        TopicRouteSession row = new TopicRouteSession(); row.setId(id); row.setTopicId(20L);
        row.setMemberId(7L); row.setActivityId(0L); row.setStatus(0); row.setVersion(version); row.setPlayInstanceId(instance);
        row.setConfigSnapshotJson("{\"openingChapter\":{\"chapterId\":70,\"blocks\":[{\"type\":\"node\",\"nodeId\":40}]}}");
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        if(done) state.getTags().add("sys.opening.done");
        row.setStateJson(new ObjectMapper().writeValueAsString(state)); return row;
    }
    @Test void defaultDoneDoesNotAllowNodeOutsideReplayOpening() {
        assertThrows(IllegalStateException.class, () -> service.requireOpeningAccess(7L,20L,0L,41L));
        verify(routes,never()).selectByOwner(0L,20L,7L,"default");
    }
    @Test void replayNextOpeningNodeStillAllowed() {
        assertDoesNotThrow(() -> service.requireOpeningAccess(7L,20L,0L,40L));
    }
    @Test void completeOpeningChecksReplayVersionInsteadOfReturningOldDefaultDone() {
        assertThrows(ServiceException.class, () -> service.completeOpening(7L,20L,0L,70L,4));
        verify(routes,never()).updateStateCas(any());
    }
}
