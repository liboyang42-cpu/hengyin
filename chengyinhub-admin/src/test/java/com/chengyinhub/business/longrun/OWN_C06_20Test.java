package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Ordinary snapshot and current winner are independent mapper responses. */
class OWN_C06_20Test {
    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper mapper;
    private GameSession session;
    private CmsActivity activity;
    @BeforeEach void setup() {
        service=new GameSessionRuntimeServiceImpl();mapper=mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service,"runtimeMapper",mapper);
        ReflectionTestUtils.setField(service,"clock",(com.chengyinhub.business.service.support.AdvancedGameClock)()->new Date(1000));
        session=new GameSession();session.setId(30L);session.setActivityId(10L);session.setStatus(GameSession.RUNNING);session.setRevision(1);
        session.setModuleConfigSnapshotJson("{\"story\":{\"variables\":{},\"choices\":[{\"id\":\"c\"}],\"endings\":[]}}");
        activity=new CmsActivity();activity.setId(10L);activity.setTopicId(20L);
        activity.setStartDate(new Date(0));activity.setEndDate(new Date(2000));
    }
    private GameStoryState story(String choices) {
        GameStoryState row=new GameStoryState();row.setId(60L);row.setGameSessionId(30L);
        row.setOwnerType("TEAM");row.setOwnerId(80L);row.setRevision(1);row.setStatus("VARIABLES_UPDATED");
        row.setVariablesJson("{}");row.setAppliedChoicesJson(choices);return row;
    }
    @Test void insertCollisionReadsCommittedStoryOutsideOldSnapshot() throws Exception {
        GameStoryState winner=story("[]");
        when(mapper.insertStoryState(any())).thenReturn(0);
        when(mapper.selectStoryStateForUpdate(30L,"TEAM",80L)).thenReturn(winner);
        Object result=ReflectionTestUtils.invokeMethod(service,"storyState",session,80L,
                new ObjectMapper().readTree(session.getModuleConfigSnapshotJson()));
        assertSame(winner,result);verify(mapper).selectStoryStateForUpdate(30L,"TEAM",80L);
    }
    @Test void missingStoryAfterInsertCollisionStillFailsClosed() throws Exception {
        assertThrows(GameSessionException.class,()->ReflectionTestUtils.invokeMethod(service,"storyState",session,80L,
                new ObjectMapper().readTree(session.getModuleConfigSnapshotJson())));
    }
    private void choiceScope() {
        PlayTeamMapper teams=mock(PlayTeamMapper.class);ReflectionTestUtils.setField(service,"playTeamMapper",teams);
        when(teams.hasPaidRegistration(7L,2,10L)).thenReturn(1);when(mapper.selectActiveTeamId(10L,7L)).thenReturn(80L);
        GameStationRun station=new GameStationRun();station.setId(40L);station.setNodeId(50L);station.setStatus(GameStationRun.ACTIVE);
        station.setServiceStartAt(new Date(0));station.setServiceEndAt(new Date(2000));
        when(mapper.selectStation(30L,50L)).thenReturn(station);
        IPlayerNodeProgressService progress=mock(IPlayerNodeProgressService.class);
        ReflectionTestUtils.setField(service,"playerNodeProgressService",progress);
        PlayerNodeProgress arrival=new PlayerNodeProgress();arrival.setMemberId(7L);arrival.setActivityId(10L);
        arrival.setTopicId(20L);arrival.setNodeId(50L);arrival.setCheckinTime(new Date(500));
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(arrival));
        GameTeamRoleAssignment role=new GameTeamRoleAssignment();role.setRoleCode("R");
        when(mapper.selectRole(30L,7L)).thenReturn(role);
        when(mapper.selectPlayerNodeOutcome(30L,7L,50L)).thenReturn(new GamePlayerNodeOutcome());
        when(mapper.selectStoryState(30L,"TEAM",80L)).thenReturn(story("[]"));
        when(mapper.updateStoryStateCas(any())).thenReturn(0);
    }
    private Object choose() {
        GameSessionCommand command=new GameSessionCommand();command.setNodeId(50L);
        command.setPayload(Collections.<String,Object>singletonMap("choiceId","c"));
        return ReflectionTestUtils.invokeMethod(service,"playerChoice",GameActorContext.player(7L),activity,session,command);
    }
    @Test void sameChoiceCasWinnerRecoversItsCurrentSessionRevision() {
        choiceScope();GameStoryState winner=story("[\"c\"]");
        when(mapper.selectStoryStateForUpdate(30L,"TEAM",80L)).thenReturn(winner);
        GameSession current=new GameSession();current.setId(30L);current.setRevision(2);
        when(mapper.selectSessionByActivityForUpdate(10L)).thenReturn(current);
        Object result=choose();assertSame(current,ReflectionTestUtils.getField(result,"session"));
        assertTrue(String.valueOf(ReflectionTestUtils.getField(result,"result")).contains("duplicate=true"));
        verify(mapper,never()).updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any());
    }
    @Test void otherChoiceCasWinnerDoesNotBecomeSameChoiceSuccess() {
        choiceScope();when(mapper.selectStoryStateForUpdate(30L,"TEAM",80L)).thenReturn(story("[\"other\"]"));
        GameSessionException failure=assertThrows(GameSessionException.class,()->choose());
        assertEquals("GAME_STATE_CONFLICT",failure.getErrorCode());
    }
}
