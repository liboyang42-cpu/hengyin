package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientSessionRow;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.OrientTaskEvidenceVerifier;
import com.chengyinhub.business.service.support.OrientSessionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrientSessionCurrentTest {
    private final OrientSessionFeatureGate gate = mock(OrientSessionFeatureGate.class);
    private final OrientSessionMapper mapper = mock(OrientSessionMapper.class);
    private final TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
    private final ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final OrientTaskEvidenceVerifier verifier = mock(OrientTaskEvidenceVerifier.class);
    private final OrientSessionServiceImpl service = new OrientSessionServiceImpl(gate, mapper, routes, runtime, topics, nodes, verifier);

    @BeforeEach
    void setup() {
        when(gate.isEnabled()).thenReturn(true);
        CmsTopic topic = new CmsTopic();
        topic.setProductType(1);
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
    }

    @Test
    void emptyDoesNotCreateOrPrepareAnything() {
        when(mapper.selectOwnedSessions(7L, 10L, null)).thenReturn(List.of());
        assertEquals(List.of(), service.current(7L, 10L, null).get("sessions"));
        verify(mapper).selectOwnedSessions(7L, 10L, null);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(routes, runtime, nodes, verifier);
    }

    @Test
    void returnsAllExistingSessionsIncludingTerminalWithoutPickingOrWriting() {
        OrientSessionRow running = row(20L, 0);
        OrientSessionRow finished = row(21L, 1);
        when(mapper.selectOwnedSessions(7L, 10L, 0L)).thenReturn(List.of(running, finished));
        Map<String, Object> result = service.current(7L, 10L, 0L);
        List<?> sessions = (List<?>) result.get("sessions");
        assertEquals(2, sessions.size());
        assertEquals("PREPARED", ((Map<?, ?>) sessions.get(0)).get("phase"));
        assertEquals("FINISHED", ((Map<?, ?>) sessions.get(1)).get("phase"));
        assertFalse(result.containsKey("sessionId"));
        assertNull(running.getPhase());
        verify(mapper).selectOwnedSessions(7L, 10L, 0L);
        verify(mapper).selectClock(20L, 7L);
        verify(mapper).selectClock(21L, 7L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(routes, runtime, nodes, verifier);
    }

    @Test
    void memberAndActivityArePassedAsScopeWithoutRuntimeFallback() {
        when(mapper.selectOwnedSessions(8L, 10L, 99L)).thenReturn(List.of());
        assertEquals(List.of(), service.current(8L, 10L, 99L).get("sessions"));
        verify(mapper).selectOwnedSessions(8L, 10L, 99L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(routes, runtime);
    }

    @Test
    void disabledAndInvalidInputsDoNotReadSessionsOrWrite() {
        when(gate.isEnabled()).thenReturn(false);
        assertErr("ORIENT_SESSION_DISABLED", () -> service.current(7L, 10L, null));
        when(gate.isEnabled()).thenReturn(true);
        assertErr("ORIENT_BAD_REQUEST", () -> service.current(null, 10L, null));
        assertErr("ORIENT_BAD_REQUEST", () -> service.current(0L, 10L, null));
        assertErr("ORIENT_BAD_REQUEST", () -> service.current(7L, null, null));
        assertErr("ORIENT_BAD_REQUEST", () -> service.current(7L, 0L, null));
        assertErr("ORIENT_BAD_REQUEST", () -> service.current(7L, 10L, -1L));
        assertErr("ORIENT_TOPIC_NOT_FOUND", () -> service.current(7L, 11L, null));
        CmsTopic other = new CmsTopic();
        other.setProductType(2);
        when(topics.selectCmsTopicById(10L)).thenReturn(other);
        assertErr("ORIENT_PRODUCT_TYPE_UNSUPPORTED", () -> service.current(7L, 10L, null));
        verifyNoInteractions(mapper, routes, runtime, nodes, verifier);
    }

    private static OrientSessionRow row(long id, int status) {
        OrientSessionRow row = new OrientSessionRow();
        row.setId(id);
        row.setMemberId(7L);
        row.setTopicId(10L);
        row.setActivityId(0L);
        row.setStatus(status);
        return row;
    }

    private static void assertErr(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(OrientSessionException.class, action).getErr());
    }
}
