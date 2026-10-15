package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.route.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Legacy empty node map is completed before the first compatibility advance. */
class OWN_C06_23Test {
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession session;
    private final Map<Long,CmsTopicNode> live = new LinkedHashMap<>();
    private final Set<Long> done = new LinkedHashSet<>();
    private final ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        service=new TopicRouteRuntimeServiceImpl();routes=mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(service,"routeMapper",routes);
        CmsTopicMapper topics=mock(CmsTopicMapper.class);ReflectionTestUtils.setField(service,"topicMapper",topics);
        CmsTopicNodeMapper nodes=mock(CmsTopicNodeMapper.class);ReflectionTestUtils.setField(service,"nodeMapper",nodes);
        PlayerNodeProgressMapper progress=mock(PlayerNodeProgressMapper.class);ReflectionTestUtils.setField(service,"progressMapper",progress);
        CurrentPlayTestFixtures.installOn(service);
        CmsTopic topic=new CmsTopic();topic.setId(20L);topic.setRouteMode("LINEAR");topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        live.clear();done.clear();done.add(1L);
        node(1L,1L);node(2L,2L);node(3L,3L);
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(call->new ArrayList<>(live.values()));
        when(nodes.selectCmsTopicNodeById(anyLong())).thenAnswer(call->live.get(call.<Long>getArgument(0)));
        when(progress.selectPlayerNodeProgressList(any())).thenAnswer(call->{
            PlayerNodeProgress probe=call.getArgument(0);List<PlayerNodeProgress> rows=new ArrayList<>();
            for(Long id:done) if(probe.getNodeId()==null || id.equals(probe.getNodeId())) {
                PlayerNodeProgress row=new PlayerNodeProgress();row.setNodeId(id);row.setStatus(1);rows.add(row);
            }
            return rows;
        });
        session=new TopicRouteSession();session.setId(30L);session.setMemberId(7L);session.setTopicId(20L);
        session.setActivityId(0L);session.setPlayInstanceId("default");session.setStatus(0);session.setVersion(0);
        session.setConfigSnapshotJson("{}");
        TopicRouteEngine.State state=new TopicRouteEngine.State();state.setStatus("ACTIVE");
        state.setCurrentNodeId(2L);state.setRecommendedNodeId(2L);state.getCompletedNodeIds().add(1L);
        state.getNodeStates().put(1L,TopicRouteState.COMPLETED);state.getNodeStates().put(2L,TopicRouteState.PLAYABLE);
        state.getNodeStates().put(3L,TopicRouteState.DISCOVERED_LOCKED);session.setStateJson(json.writeValueAsString(state));
        when(routes.selectCurrentByOwner(0L,20L,7L)).thenReturn(session);
        when(routes.insertAction(any())).thenAnswer(call->{((TopicRouteAction)call.getArgument(0)).setId(90L);return 1;});
        when(routes.updateStateCas(any())).thenReturn(1);when(routes.updateActionResponse(anyLong(),anyString())).thenReturn(1);
    }
    private void node(long id,long sort) {
        CmsTopicNode node=new CmsTopicNode();node.setId(id);node.setTopicId(20L);node.setSortId(sort);node.setStatus(1L);live.put(id,node);
    }
    private void clearLegacyMap() throws Exception {
        TopicRouteEngine.State state=json.readValue(session.getStateJson(),TopicRouteEngine.State.class);
        state.getNodeStates().clear();session.setStateJson(json.writeValueAsString(state));
    }
    private TopicRouteState finishTwo() {
        done.add(2L);
        return service.advance(7L,20L,0L,0,"legacy-2","CHOICE",new NodeOutcome(2L,"COMPLETED","progress-2"));
    }
    @Test void legacyAdvanceKeepsAllFallbackNodesInsteadOfOnlyCompletedAndNext() throws Exception {
        clearLegacyMap();TopicRouteState view=finishTwo();
        assertEquals(Arrays.asList(1L,2L,3L),new ArrayList<>(view.getNodeStates().keySet()));
        TopicRouteEngine.State stored=json.readValue(session.getStateJson(),TopicRouteEngine.State.class);
        assertEquals(Arrays.asList(1L,2L,3L),new ArrayList<>(stored.getNodeStates().keySet()));
        assertEquals(3L,view.getCurrentNodeId());
    }
    @Test void laterAdvanceUsesTheNowCompleteCollectionAndExcludesNewNode() throws Exception {
        clearLegacyMap();finishTwo();node(4L,0L);done.add(3L);
        TopicRouteState view=service.advance(7L,20L,0L,1,"legacy-3","CHOICE",new NodeOutcome(3L,"COMPLETED","progress-3"));
        assertNull(view.getCurrentNodeId());assertEquals("COMPLETED",view.getStatus());
        assertFalse(view.getNodeStates().containsKey(4L));
    }
    @Test void existingCompleteCollectionIsNotReplacedByNewLiveOrder() {
        node(4L,0L);live.get(3L).setSortId(-1L);
        TopicRouteState view=finishTwo();
        assertEquals(Arrays.asList(1L,2L,3L),new ArrayList<>(view.getNodeStates().keySet()));
        assertFalse(view.getNodeStates().containsKey(4L));assertEquals(3L,view.getCurrentNodeId());
    }
}
