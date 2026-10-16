package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.CurrentPlayTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Existing check/ask/call scope follows the same run selected by state and playInstance. */
class OWN_C06_18Test {
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession replay, old;
    @BeforeEach void setup() {
        service=new TopicRouteRuntimeServiceImpl(
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
                com.chengyinhub.business.service.support.RouteReachabilityProvider.UNKNOWN); routes=mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(service,"routeMapper",routes);
        CmsTopicMapper topics=mock(CmsTopicMapper.class);ReflectionTestUtils.setField(service,"topicMapper",topics);
        CurrentPlayTestFixtures.installOn(service);
        CmsTopic topic=new CmsTopic();topic.setId(20L);topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        replay=session(31L,0);old=session(30L,2);
        when(routes.selectCurrentByOwner(0L,20L,7L)).thenReturn(replay);
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(old);
        TopicRouteAction receipt=new TopicRouteAction();receipt.setResponseJson("{\"a\":\"replay-answer\"}");
        when(routes.selectActionByAction(31L,7L,"ask:40:why")).thenReturn(receipt);
    }
    private TopicRouteSession session(long id,int status) {
        TopicRouteSession row=new TopicRouteSession();row.setId(id);row.setMemberId(7L);row.setTopicId(20L);
        row.setActivityId(0L);row.setStatus(status);return row;
    }
    @Test void endedDefaultCannotBlockOriginalReceiptInRunningReplay() {
        assertEquals("replay-answer",service.ask(7L,20L,0L,40L,"why").get("a"));
        verify(routes).selectActionByAction(31L,7L,"ask:40:why");
        verify(routes,never()).selectActionByAction(30L,7L,"ask:40:why");
    }
    @Test void closedCurrentReplayCannotFallBackToEarlierRunningDefault() {
        replay.setStatus(2);old.setStatus(0);
        assertThrows(IllegalStateException.class,()->service.ask(7L,20L,0L,40L,"why"));
        verify(routes,never()).selectActionByAction(anyLong(),anyLong(),anyString());
    }
}
