package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Current role read and returned receipt revision must share the latest committed session. */
class OWN_C06_19Test {
    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper mapper;
    private GameSession old,current;
    private CmsActivity activity;
    private GameTeamRoleAssignment active;
    private GameSessionCommand command;
    @BeforeEach void setup() {
        service=new GameSessionRuntimeServiceImpl();mapper=mock(GameSessionRuntimeMapper.class);
        ReflectionTestUtils.setField(service,"runtimeMapper",mapper);
        ReflectionTestUtils.setField(service,"clock",(com.chengyinhub.business.service.support.AdvancedGameClock)()->new Date());
        old=new GameSession();old.setId(30L);old.setStatus(GameSession.PREPARING);old.setRevision(3);
        old.setModuleConfigSnapshotJson("{\"roles\":[{\"code\":\"DETECTIVE\",\"name\":\"Detective\"}]}");
        current=new GameSession();current.setId(30L);current.setStatus(GameSession.READY);current.setRevision(4);
        activity=new CmsActivity();activity.setId(10L);
        active=new GameTeamRoleAssignment();active.setTeamId(80L);active.setMemberId(7L);
        active.setRoleCode("DETECTIVE");active.setStatus("ASSIGNED");
        when(mapper.isActiveTeamMember(10L,80L,7L)).thenReturn(1);
        when(mapper.selectActiveRoleForUpdate(30L,7L)).thenReturn(active);
        when(mapper.selectSessionByActivityForUpdate(10L)).thenReturn(current);
        Map<String,Object> assignment=new LinkedHashMap<>();assignment.put("memberId",7L);assignment.put("roleCode","DETECTIVE");
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("teamId",80L);payload.put("assignments",Collections.singletonList(assignment));
        command=new GameSessionCommand();command.setPayload(payload);
    }
    private Object assign() {return ReflectionTestUtils.invokeMethod(service,"assignRoles",GameActorContext.club(9L),activity,old,command);}
    @Test void matchingCurrentRoleReturnsCurrentRevisionWithoutAnotherMutation() {
        Object result=assign();assertSame(current,ReflectionTestUtils.getField(result,"session"));
        assertEquals(4,ReflectionTestUtils.getField(result,"revision"));
        verify(mapper,never()).insertRole(any());
        verify(mapper,never()).updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any());
        verify(mapper,never()).selectSessionByActivity(10L);
    }
    @Test void insertCollisionAlsoReturnsCurrentRevisionWithoutBumping() {
        when(mapper.selectActiveRoleForUpdate(30L,7L)).thenReturn(null,active);
        when(mapper.insertRole(any())).thenReturn(0);
        Object result=assign();assertSame(current,ReflectionTestUtils.getField(result,"session"));
        assertEquals(Integer.valueOf(3),old.getRevision());
        verify(mapper).selectSessionByActivityForUpdate(10L);
        verify(mapper,never()).updateSessionCas(anyLong(),anyInt(),anyString(),anyString(),any(),any(),any());
    }
}
