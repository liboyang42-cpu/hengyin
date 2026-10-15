package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. AdvancedGame's singular condition entry uses the same current scope as projections. */
class OWN_C06_26Test {
    private final ObjectMapper json=new ObjectMapper();
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession current,old;
    @BeforeEach void setup() throws Exception {
        service=new TopicRouteRuntimeServiceImpl();routes=mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(service,"routeMapper",routes);
        current=session(31L,"replay",TopicRouteSession.STATUS_RUNNING);
        old=session(30L,"original",TopicRouteSession.STATUS_COMPLETED);
        when(routes.selectCurrentByOwner(0L,20L,7L)).thenReturn(current);
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(old);
    }
    private TopicRouteSession session(long id,String tag,int status) throws Exception {
        TopicRouteSession row=new TopicRouteSession();row.setId(id);row.setMemberId(7L);
        row.setTopicId(20L);row.setActivityId(0L);row.setStatus(status);
        TopicRouteEngine.State state=new TopicRouteEngine.State();state.getTags().add(tag);
        row.setStateJson(json.writeValueAsString(state));return row;
    }
    @Test void singularVariantConditionUsesCurrentReplayInsteadOfEndedOriginal() throws Exception {
        assertTrue(service.conditionPasses(7L,20L,0L,json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"replay\"}")));
        assertFalse(service.conditionPasses(7L,20L,0L,json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"original\"}")));
    }
    @Test void terminalCurrentDoesNotFallBackToRunningOriginal() throws Exception {
        current.setStatus(TopicRouteSession.STATUS_COMPLETED);old.setStatus(TopicRouteSession.STATUS_RUNNING);
        assertFalse(service.conditionPasses(7L,20L,0L,json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"original\"}")));
    }
    @Test void arrayRetainsAnyConditionSemanticsAndNullRemainsFalse() throws Exception {
        assertFalse(service.conditionPasses(7L,20L,0L,null));
        assertFalse(service.conditionPasses(7L,20L,0L,json.readTree("[]")));
        assertTrue(service.conditionPasses(7L,20L,0L,json.readTree("[{\"op\":\"HAS_TAG\",\"value\":\"missing\"},{\"op\":\"HAS_TAG\",\"value\":\"replay\"}]")));
    }
    @Test void omittedActivityKeepsUniqueScopeContract() throws Exception {
        when(routes.selectMemberTopicSessions(7L,20L)).thenReturn(Collections.singletonList(old));
        assertTrue(service.conditionPasses(7L,20L,null,json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"replay\"}")));
        TopicRouteSession other=new TopicRouteSession();other.setActivityId(8L);
        when(routes.selectMemberTopicSessions(7L,20L)).thenReturn(Arrays.asList(old,other));
        assertThrows(IllegalArgumentException.class,()->service.conditionPasses(7L,20L,null,json.readTree("{\"op\":\"HAS_TAG\",\"value\":\"replay\"}")));
    }
}
