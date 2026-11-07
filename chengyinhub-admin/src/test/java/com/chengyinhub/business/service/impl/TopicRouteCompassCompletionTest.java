package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TopicRouteCompassCompletionTest {
    private TopicRouteSessionMapper routes;
    private PlayerNodeProgressMapper progress;
    private TopicRouteRuntimeServiceImpl runtime;
    private TopicRouteSession session;

    @BeforeEach void setup() {
        routes=mock(TopicRouteSessionMapper.class);progress=mock(PlayerNodeProgressMapper.class);
        runtime=new TopicRouteRuntimeServiceImpl(routes,mock(CmsTopicMapper.class),mock(CmsTopicNodeMapper.class),
                progress,mock(CmsActivityMapper.class),mock(CmsRegistrationMapper.class));
        session=new TopicRouteSession();session.setId(31L);session.setMemberId(7L);session.setTopicId(10L);
        session.setActivityId(0L);session.setPlayInstanceId("replay-31");session.setStateJson("{}");
        session.setConfigSnapshotJson("{\"nodeConfigs\":{\"11\":{\"compass\":{\"enabled\":true},\"check\":{\"enabled\":true,\"checkId\":\"c1\"}}}}");
        when(routes.selectCurrentByOwner(0L,10L,7L)).thenReturn(session);
    }

    @Test void arrivalCannotCompleteWithoutItsSettledOriginalCheck() {
        assertFalse(runtime.completeCompassCheckArrival(7L,10L,0L,11L,0,"SKIPPED"));
        TopicRouteAction receipt=new TopicRouteAction();receipt.setResponseJson("{\"settled\":false}");
        when(routes.selectActionByAction(31L,7L,"check:11:c1:settle")).thenReturn(receipt);
        assertFalse(runtime.completeCompassCheckArrival(7L,10L,0L,11L,0,"SKIPPED"));
        verifyNoInteractions(progress);
    }

    @Test void settledCheckOnlyPromotesItsOwnArrivalOnceAndKeepsSkipFact() {
        TopicRouteAction receipt=new TopicRouteAction();receipt.setResponseJson("{\"settled\":true}");
        when(routes.selectActionByAction(31L,7L,"check:11:c1:settle")).thenReturn(receipt);
        when(progress.markGameplayQuestDone(any())).thenReturn(1,0);
        assertTrue(runtime.completeCompassCheckArrival(7L,10L,0L,11L,0,"SKIPPED"));
        assertFalse(runtime.completeCompassCheckArrival(7L,10L,0L,11L,0,"SKIPPED"));
        ArgumentCaptor<PlayerNodeProgress> captor=ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progress,times(2)).markGameplayQuestDone(captor.capture());
        PlayerNodeProgress p=captor.getValue();
        assertEquals(7L,p.getMemberId());assertEquals(10L,p.getTopicId());assertEquals(0L,p.getActivityId());
        assertEquals(11L,p.getNodeId());assertEquals("replay-31",p.getPlayInstanceId());
        assertEquals(0,p.getPuzzleScore());assertEquals("SKIPPED",p.getCompletionMode());
        assertFalse(runtime.completeCompassCheckArrival(7L,10L,0L,12L,0,"SKIPPED"));
        assertFalse(runtime.completeCompassCheckArrival(8L,10L,0L,11L,0,"SKIPPED"));
        verifyNoMoreInteractions(progress);
    }

    @Test void genericOutcomeRulesNeverUseLegacyCompletion() {
        session.setConfigSnapshotJson(session.getConfigSnapshotJson().replace("\"compass\"","\"outcomeRules\":{},\"compass\""));
        assertFalse(runtime.completeCompassCheckArrival(7L,10L,0L,11L,0,"SKIPPED"));
        verifyNoInteractions(progress);
    }
}
