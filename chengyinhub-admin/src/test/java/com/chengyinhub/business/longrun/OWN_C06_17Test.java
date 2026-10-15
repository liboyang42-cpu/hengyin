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

/** NOT_RUN. Original activity scope stays unique while projections follow its current replay. */
class OWN_C06_17Test {
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession replay, old;
    @BeforeEach void setup() throws Exception {
        service = new TopicRouteRuntimeServiceImpl(); routes = mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(service,"routeMapper",routes);
        replay = session(31L,"new",3); old = session(30L,"old",9);
        when(routes.selectCurrentByOwner(0L,20L,7L)).thenReturn(replay);
        when(routes.selectByOwner(0L,20L,7L,"default")).thenReturn(old);
    }
    private TopicRouteSession session(long id,String tag,int hp) throws Exception {
        TopicRouteSession row = new TopicRouteSession(); row.setId(id); row.setMemberId(7L);
        row.setTopicId(20L); row.setActivityId(0L); row.setStatus(0);
        row.setConfigSnapshotJson("{\"journeyRules\":{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":10,\"init\":10}},\"nodeAnswers\":{\"40\":{\"questionAnswer\":\""+tag+"\"}}}");
        TopicRouteEngine.State state = new TopicRouteEngine.State(); state.getTags().add(tag);
        state.getVariables().put("sys.hp",hp); row.setStateJson(new ObjectMapper().writeValueAsString(state)); return row;
    }
    @Test void fourReadProjectionsUseReplayStateAndAnswerSnapshot() throws Exception {
        assertEquals(Integer.valueOf(3),service.vitals(7L,20L,0L).get("hp"));
        assertTrue(service.tags(7L,20L,0L).contains("new")); assertFalse(service.tags(7L,20L,0L).contains("old"));
        assertTrue(service.conditionsPass(7L,20L,0L,new ObjectMapper().readTree("[{\"op\":\"HAS_TAG\",\"value\":\"new\"}]")));
        assertEquals("new",service.nodeAnswers(7L,20L,0L,40L).path("questionAnswer").asText());
    }
    @Test void omittedActivityStillRequiresOriginalUniqueScopeThenUsesItsReplay() {
        when(routes.selectMemberTopicSessions(7L,20L)).thenReturn(Collections.singletonList(old));
        assertTrue(service.tags(7L,20L,null).contains("new"));
    }
    @Test void omittedActivityCannotGuessBetweenTwoExistingScopes() {
        TopicRouteSession other = new TopicRouteSession(); other.setActivityId(8L);
        when(routes.selectMemberTopicSessions(7L,20L)).thenReturn(Arrays.asList(old,other));
        assertThrows(IllegalArgumentException.class,()->service.tags(7L,20L,null));
    }
}
