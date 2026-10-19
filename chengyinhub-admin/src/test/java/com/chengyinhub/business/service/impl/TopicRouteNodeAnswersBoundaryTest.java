package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual runtime method; mocked session persistence. No live body read or snapshot mutation. */
class TopicRouteNodeAnswersBoundaryTest {
    private final TopicRouteSessionMapper sessions=mock(TopicRouteSessionMapper.class);
    private final CmsMemberTemplateMapper bodies=mock(CmsMemberTemplateMapper.class);
    private final LegacyCmsTemplateBodyReader reader=mock(LegacyCmsTemplateBodyReader.class);
    private final TopicRouteSession session=new TopicRouteSession();
    private TopicRouteRuntimeServiceImpl runtime;
    @BeforeEach void setup(){
        runtime=new TopicRouteRuntimeServiceImpl(sessions,mock(CmsTopicMapper.class),mock(CmsTopicNodeMapper.class),
                bodies,mock(PlayerNodeProgressMapper.class),mock(CmsActivityMapper.class),mock(CmsRegistrationMapper.class),
                null,()->1L,Clock.systemUTC(),RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime,"legacyTemplateBodyReader",reader);
        session.setMemberId(7L);session.setTopicId(10L);session.setActivityId(0L);session.setPlayInstanceId("default");
        when(sessions.selectByOwner(0L,10L,7L,"default")).thenReturn(session);
    }
    private JsonNode read(String payload){session.setConfigSnapshotJson(payload);return runtime.nodeAnswers(7L,10L,0L,1L);}
    @Test void genuinelyAbsentLegacyPayloadOrKeyMayReturnNull(){
        for(String old:new String[]{null,""," \n ","{}","{\"nodeJourney\":{}}"})assertNull(read(old));
        verifyNoInteractions(bodies,reader);
    }
    @Test void completeFrozenAnswerAndEmptyObjectRemainAuthoritative()throws Exception{
        String bytes="{\"nodeAnswers\":{\"1\":{\"validationMethod\":1,\"questionAnswer\":\"frozen\"}}}";
        assertEquals(new ObjectMapper().readTree(bytes).get("nodeAnswers").get("1"),read(bytes));
        assertEquals(bytes,session.getConfigSnapshotJson());
        assertTrue(read("{\"nodeAnswers\":{\"1\":{}}}").isObject());verifyNoInteractions(bodies,reader);
    }
    @Test void malformedOrTrailingPayloadNeverBecomesFallback(){
        for(String bad:new String[]{"{","null","[]","42","{} {}","{} trailing"}) {
            ServiceException error = assertThrows(ServiceException.class,()->read(bad),bad);
            assertEquals("本局冻结配置不可读取，请重新进入", error.getMessage(), bad);
        }
        verifyNoInteractions(bodies,reader);
    }
    @Test void presentAnswerKeyMustBeAnObject(){
        for(String bad:new String[]{"{\"nodeAnswers\":null}","{\"nodeAnswers\":[]}","{\"nodeAnswers\":\"old\"}"})assertThrows(IllegalStateException.class,()->read(bad),bad);
        verifyNoInteractions(bodies,reader);
    }
    @Test void presentAnswerMapMustContainRequestedNodeObject(){
        for(String bad:new String[]{"{\"nodeAnswers\":{}}","{\"nodeAnswers\":{\"2\":{}}}","{\"nodeAnswers\":{\"1\":null}}","{\"nodeAnswers\":{\"1\":[]}}"})
            assertThrows(IllegalStateException.class,()->read(bad),bad);
        verifyNoInteractions(bodies,reader);
    }
    @Test void missingSessionStillReturnsNullWithoutLiveRead(){
        when(sessions.selectByOwner(0L,10L,7L,"default")).thenReturn(null);
        assertNull(runtime.nodeAnswers(7L,10L,0L,1L));verifyNoInteractions(bodies,reader);
    }
    @Test void crossOwnerSessionRemainsRejected(){
        session.setMemberId(8L);assertThrows(ServiceException.class,()->runtime.nodeAnswers(7L,10L,0L,1L));verifyNoInteractions(bodies,reader);
    }
    @Test void methodRemainsReadOnlyAndDoesNotWriteSession()throws Exception{
        assertTrue(TopicRouteRuntimeServiceImpl.class.getMethod("nodeAnswers",Long.class,Long.class,Long.class,Long.class)
                .getAnnotation(Transactional.class).readOnly());
        read("{\"nodeAnswers\":{\"1\":{}}}");
        verify(sessions).selectCurrentByOwner(0L,10L,7L);
        verify(sessions).selectByOwner(0L,10L,7L,"default");verifyNoMoreInteractions(sessions);verifyNoInteractions(bodies,reader);
    }
}
