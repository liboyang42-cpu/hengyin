package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.CompletedRouteFactMapper;
import com.chengyinhub.business.service.support.CompletedRouteFactReader;
import com.chengyinhub.business.service.support.CompletedRouteFactReader.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** WP06-B: real existing reader, mapper doubles only; no execution claim for SQL. */
class CompletedRouteProgressPageReaderTest {
    @Test void invalidOrForeignScopesAreRejectedBeforeAnyQuery() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        CompletedRouteFactReader reader=new CompletedRouteFactReader(mapper);
        NodeProgressRequest good=request("one",7,0,10,11,null);
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(null,List.of(good)));
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,null));
        for(NodeProgressRequest bad:Arrays.asList(null,request("one",8,0,10,11,null),
                new NodeProgressRequest("member-null",null,0L,10L,11L,null),
                new NodeProgressRequest("activity-null",7L,null,10L,11L,null),
                new NodeProgressRequest("topic-null",7L,0L,null,11L,null),
                new NodeProgressRequest("node-null",7L,0L,10L,null,null),request("",7,0,10,11,null),
                request("one",7,-1,10,11,null),request("one",7,0,0,11,null),
                request("one",7,0,10,0,null),request("one",7,0,10,11," "))) {
            assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,Arrays.asList(good,bad)));
        }
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,List.of(good,good)));
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,Collections.nCopies(101,good)));
        verifyNoInteractions(mapper);
    }

    @Test void emptyPageDoesNotQueryOrFallBackToAllRows() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        assertTrue(new CompletedRouteFactReader(mapper).findNodeProgressPage(7L,List.of()).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test void everyInputIncludingMissingSourceIsReturnedInOriginalOrderInOneCall() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        NodeProgressRequest a=request("a",7,0,10,11,null),b=request("b",7,0,10,12,null);
        NodeProgressRow first=row(a,"replay:1",0),missing=row(b,"replay:1",null);
        when(mapper.selectNodeProgressPage(7L,List.of(a,b))).thenReturn(List.of(missing,first));
        Map<String,NodeProgressFact> page=new CompletedRouteFactReader(mapper).findNodeProgressPage(7L,List.of(a,b));
        assertEquals(List.of("a","b"),new ArrayList<>(page.keySet()));
        assertEquals(Observation.OBSERVED,page.get("a").getArrival());
        assertEquals(Observation.NOT_OBSERVED,page.get("a").getNodeCompleted());
        assertFalse(page.get("b").hasSource());
        assertEquals(Observation.UNKNOWN,page.get("b").getNodeCompleted());
        assertEquals(Observation.UNKNOWN,page.get("b").getTaskVerified());
        verify(mapper,times(1)).selectNodeProgressPage(7L,List.of(a,b));verifyNoMoreInteractions(mapper);
    }

    @Test void photoPendingSolvedAndRevealedNeverBecomeVerifiedTaskReceipts() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        List<NodeProgressRequest> inputs=List.of(request("photo",7,0,10,11,"default"),
                request("solved",7,0,10,12,"default"),request("revealed",7,0,10,13,"default"));
        NodeProgressRow photo=row(inputs.get(0),"default",0);photo.setPicUrl("proof");photo.setReviewStatus(0);
        NodeProgressRow solved=row(inputs.get(1),"default",1);solved.setCompletionMode("SOLVED");
        NodeProgressRow revealed=row(inputs.get(2),"default",1);revealed.setCompletionMode("REVEALED");
        when(mapper.selectNodeProgressPage(7L,inputs)).thenReturn(List.of(photo,solved,revealed));
        Map<String,NodeProgressFact> page=new CompletedRouteFactReader(mapper).findNodeProgressPage(7L,inputs);
        assertEquals(Observation.NOT_OBSERVED,page.get("photo").getNodeCompleted());
        assertEquals(0,page.get("photo").getSourceProgress().getReviewStatus());
        for(String id:List.of("solved","revealed"))assertEquals(Observation.OBSERVED,page.get(id).getNodeCompleted());
        assertEquals("REVEALED",page.get("revealed").getSourceProgress().getCompletionMode());
        assertTrue(page.values().stream().allMatch(f->f.getTaskVerified()==Observation.UNKNOWN));
    }

    @Test void exactDefaultReplayAndCurrentRemainSeparateAndUnknownVersionsStayNull() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        List<NodeProgressRequest> inputs=List.of(request("old",7,0,10,11,"default"),
                request("replay",7,0,10,11,"replay:1"),request("current",7,0,10,11,null));
        NodeProgressRow old=row(inputs.get(0),"default",1),replay=row(inputs.get(1),"replay:1",0),current=row(inputs.get(2),"replay:1",0);
        replay.setRouteSessionId(2L);replay.setFrozenConfigVersion(9L);
        when(mapper.selectNodeProgressPage(7L,inputs)).thenReturn(List.of(old,replay,current));
        Map<String,NodeProgressFact> page=new CompletedRouteFactReader(mapper).findNodeProgressPage(7L,inputs);
        assertEquals(Observation.OBSERVED,page.get("old").getNodeCompleted());
        assertEquals(Observation.NOT_OBSERVED,page.get("replay").getNodeCompleted());
        assertEquals("replay:1",page.get("current").getResolvedPlayInstanceId());
        assertNull(page.get("old").getFrozenConfigVersion());assertEquals(9L,page.get("replay").getFrozenConfigVersion());
    }

    @Test void omittedDuplicateOrUnrequestedInputCannotBeSilentlyAccepted() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        NodeProgressRequest a=request("a",7,0,10,11,null),b=request("b",7,0,10,12,null);
        CompletedRouteFactReader reader=new CompletedRouteFactReader(mapper);
        for(List<NodeProgressRow> bad:List.of(List.of(row(a,"default",1)),
                List.of(row(a,"default",1),row(a,"default",1)),
                List.of(row(a,"default",1),row(request("foreign",7,0,10,12,null),"default",1)))) {
            when(mapper.selectNodeProgressPage(7L,List.of(a,b))).thenReturn(bad);
            assertThrows(IllegalStateException.class,()->reader.findNodeProgressPage(7L,List.of(a,b)));
        }
    }

    @Test void wrongSourceMemberActivityTopicInstanceAndDeletedRowsFailClosed() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        NodeProgressRequest input=request("one",7,0,10,11,"default");
        List<NodeProgressRow> bad=new ArrayList<>();
        NodeProgressRow member=row(input,"default",1);member.setMemberId(8L);bad.add(member);
        NodeProgressRow activity=row(input,"default",1);activity.setActivityId(9L);bad.add(activity);
        NodeProgressRow topic=row(input,"default",1);topic.setTopicId(20L);bad.add(topic);
        NodeProgressRow instance=row(input,"default",1);instance.setPlayInstanceId("replay:other");bad.add(instance);
        NodeProgressRow deleted=row(input,"default",1);deleted.setDelFlag(2);bad.add(deleted);
        for(NodeProgressRow projection:bad) {
            when(mapper.selectNodeProgressPage(7L,List.of(input))).thenReturn(List.of(projection));
            assertThrows(IllegalStateException.class,()->new CompletedRouteFactReader(mapper).findNodeProgressPage(7L,List.of(input)));
        }
    }

    @Test void originalQueryFailurePropagatesWithoutDefaultOrOlderSourceFallback() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        DataAccessResourceFailureException failure=new DataAccessResourceFailureException("controlled read failure");
        when(mapper.selectNodeProgressPage(eq(7L),anyList())).thenThrow(failure);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->new CompletedRouteFactReader(mapper)
                .findNodeProgressPage(7L,List.of(request("one",7,0,10,11,null)))));
        verify(mapper,times(1)).selectNodeProgressPage(eq(7L),anyList());verifyNoMoreInteractions(mapper);
    }

    @Test void oneHundredInputsStillUseOneMapperCall() {
        CompletedRouteFactMapper mapper=mock(CompletedRouteFactMapper.class);
        List<NodeProgressRequest> inputs=new ArrayList<>();List<NodeProgressRow> rows=new ArrayList<>();
        for(int i=1;i<=100;i++){NodeProgressRequest input=request("id"+i,7,0,10,i,null);inputs.add(input);rows.add(row(input,"default",null));}
        when(mapper.selectNodeProgressPage(7L,inputs)).thenReturn(rows);
        assertEquals(100,new CompletedRouteFactReader(mapper).findNodeProgressPage(7L,inputs).size());
        verify(mapper,times(1)).selectNodeProgressPage(7L,inputs);verifyNoMoreInteractions(mapper);
    }

    private NodeProgressRequest request(String id,long member,long activity,long topic,long node,String instance) {
        return new NodeProgressRequest(id,member,activity,topic,node,instance);
    }
    private NodeProgressRow row(NodeProgressRequest input,String resolved,Integer status) {
        NodeProgressRow row=new NodeProgressRow();row.setInputId(input.getInputId());
        row.setRequestedMemberId(input.getMemberId());row.setRequestedActivityId(input.getActivityId());
        row.setRequestedTopicId(input.getTopicId());row.setRequestedNodeId(input.getNodeId());row.setResolvedPlayInstanceId(resolved);
        if(status!=null){row.setId(input.getNodeId());row.setMemberId(input.getMemberId());row.setActivityId(input.getActivityId());
            row.setTopicId(input.getTopicId());row.setNodeId(input.getNodeId());row.setPlayInstanceId(resolved);
            row.setStatus(status);row.setCheckinTime(new Date(1000));row.setDelFlag(0);}
        return row;
    }
}
