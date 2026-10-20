package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Recovering old submissions may add facts; a pure replay must stay revision-neutral. */
class OWN_C06_15Test {
    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper mapper;
    private GameSession session;
    private GameStationRun station;
    private GameTaskSubmission submission;
    @BeforeEach void setup() {
        service = new GameSessionRuntimeServiceImpl(); mapper = mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service, "runtimeMapper", mapper);
        ReflectionTestUtils.setField(service, "clock",
                (com.chengyinhub.business.service.support.AdvancedGameClock) () -> new Date());
        session = new GameSession(); session.setId(30L); session.setActivityId(10L);
        session.setStatus(GameSession.RUNNING); session.setRevision(3); session.setModuleConfigSnapshotJson("{}");
        station = new GameStationRun(); station.setId(40L); station.setNodeId(50L);
        submission = new GameTaskSubmission(); submission.setId(60L); submission.setStatus("APPROVED");
        when(mapper.insertPlayerNodeOutcome(any())).thenReturn(1);
        when(mapper.updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any())).thenReturn(1);
    }
    private Object recover() throws Exception {
        return ReflectionTestUtils.invokeMethod(service, "recoverSubmissionOutcome", session, station,
                80L,7L,submission,new ObjectMapper().readTree("{}"),90L);
    }
    @Test void newlyRecoveredCompletionBumpsSessionRevision() throws Exception {
        assertNotNull(recover()); assertEquals(Integer.valueOf(4), session.getRevision());
        verify(mapper).insertPlayerNodeOutcome(any());
        verify(mapper).updateSessionCas(eq(30L),eq(3),eq(GameSession.RUNNING),eq(GameSession.RUNNING),any(),eq(7L),any());
    }
    @Test void existingCompletionDoesNotBumpPureReplay() throws Exception {
        GamePlayerNodeOutcome existing = new GamePlayerNodeOutcome(); existing.setId(100L);
        when(mapper.selectPlayerNodeOutcome(30L,7L,50L)).thenReturn(existing);
        assertSame(existing, recover()); assertEquals(Integer.valueOf(3), session.getRevision());
        verify(mapper, never()).insertPlayerNodeOutcome(any());
        verify(mapper, never()).updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any());
    }
    @Test void sessionCasLossAfterRecoveredFactThrowsForTransactionRollback() {
        when(mapper.updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any())).thenReturn(0);
        assertThrows(GameSessionException.class, () -> recover());
        assertEquals(Integer.valueOf(3), session.getRevision());
        verify(mapper).insertPlayerNodeOutcome(any());
    }
}
