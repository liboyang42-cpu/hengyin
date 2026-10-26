package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real service predicate; SQL transaction lifetime requires a separate MySQL test. */
class OriginalSharedGuardEligibilityTest {
    private GameSessionRuntimeServiceImpl service;
    private GameSessionRuntimeMapper runtime;
    private PlayTeamMapper teams;
    private GameSession session;
    private GameStoryState run;
    private GameStationRun station;
    @BeforeEach void prepare() {
        service = new GameSessionRuntimeServiceImpl(); runtime = mock(GameSessionRuntimeMapper.class);
        teams = mock(PlayTeamMapper.class); CmsActivityMapper activities = mock(CmsActivityMapper.class);
        ReflectionTestUtils.setField(service,"runtimeMapper",runtime); ReflectionTestUtils.setField(service,"playTeamMapper",teams);
        ReflectionTestUtils.setField(service,"activityMapper",activities);
        session = new GameSession(); session.setId(101L); session.setActivityId(88L); session.setTopicId(77L); session.setStatus("RUNNING");
        session.setModuleConfigSnapshotJson("{\"playMode\":\"ROUTE\",\"themeConfig\":{\"gameplayRuntime\":{\"runtimeScope\":\"TEAM\",\"worldMode\":\"TEAM_INDEPENDENT\"}}}");
        when(runtime.selectSessionByActivity(88L)).thenReturn(session); when(runtime.selectSessionByActivityForUpdate(88L)).thenReturn(session);
        run = new GameStoryState(); run.setVariablesJson("{\"operationStatus\":\"RUNNING\",\"authorityMode\":\"ONLINE\"}");
        when(runtime.selectStoryState(101L,"RUN",101L)).thenReturn(run);
        GameStoryState team = new GameStoryState(); team.setVariablesJson("{\"currentNodeId\":99,\"themeRuntime\":{}}");
        when(runtime.selectStoryState(101L,"TEAM",55L)).thenReturn(team);
        when(teams.hasPaidRegistration(10L,2,88L)).thenReturn(1); when(runtime.selectActiveTeamId(88L,10L)).thenReturn(55L);
        CmsActivity activity = new CmsActivity(); activity.setId(88L); activity.setTopicId(77L); activity.setStatus(1L);
        activity.setStartDate(new Date(0)); activity.setEndDate(new Date(Long.MAX_VALUE)); when(activities.selectCmsActivityById(88L)).thenReturn(activity);
        station = new GameStationRun(); station.setStatus("ACTIVE"); station.setNodeId(99L);
        station.setServiceStartAt(new Date(0)); station.setServiceEndAt(new Date(Long.MAX_VALUE)); when(runtime.selectStation(101L,99L)).thenReturn(station);
    }
    private boolean read(Long actor, Long node, Long id, String instance, Integer epoch) {
        return service.isOriginalSharedRouteEligible(actor,77L,88L,node,id,instance,epoch);
    }
    @Test void positiveLegacyEpochAndPlainPathHaveNoWritesOrLockingReads() {
        assertTrue(read(10L,99L,101L,"game:101",1));
        for (Object mapper : new Object[]{runtime,teams}) for (org.mockito.invocation.Invocation call : mockingDetails(mapper).getInvocations()) {
            String name = call.getMethod().getName();
            assertFalse(name.contains("ForUpdate") || name.startsWith("insert") || name.startsWith("update") || name.startsWith("delete"), name);
        }
    }
    @Test void rejectsOtherOriginalIdentityAndUnregisteredActor() {
        assertFalse(read(10L,99L,102L,"game:101",1)); assertFalse(read(10L,99L,101L,"game:102",1));
        assertFalse(read(10L,99L,101L,"game:101",2));
        assertThrows(ServiceException.class,()->read(11L,99L,101L,"game:101",1));
        assertThrows(ServiceException.class,()->read(10L,100L,101L,"game:101",1));
    }
    @Test void missingLedgersPausedExitedEndedAndOutsideWindowNeverPass() {
        when(runtime.selectStoryState(101L,"RUN",101L)).thenReturn(null);
        assertThrows(ServiceException.class,()->read(10L,99L,101L,"game:101",1));
        when(runtime.selectStoryState(101L,"RUN",101L)).thenReturn(run);
        run.setVariablesJson("{\"operationStatus\":\"PAUSED\",\"authorityMode\":\"ONLINE\"}"); assertFalse(read(10L,99L,101L,"game:101",1));
        run.setVariablesJson("{\"operationStatus\":\"RUNNING\",\"authorityMode\":\"ONLINE\",\"exitedTeams\":{\"55\":true}}"); assertFalse(read(10L,99L,101L,"game:101",1));
        run.setVariablesJson("{\"operationStatus\":\"RUNNING\",\"authorityMode\":\"ONLINE\"}");
        station.setServiceEndAt(new Date(0)); assertThrows(ServiceException.class,()->read(10L,99L,101L,"game:101",1));
        session.setStatus("FINISHED"); assertFalse(read(10L,99L,101L,"game:101",1));
        session.setStatus("RUNNING"); when(runtime.selectStoryState(101L,"TEAM",55L)).thenReturn(null);
        assertThrows(ServiceException.class,()->read(10L,99L,101L,"game:101",1));
    }
    @Test void lockedEntryRequiresCallerTransactionAndLocksSessionFirst() throws Exception {
        var method = GameSessionRuntimeServiceImpl.class.getMethod("isOriginalSharedRouteEligibleForUpdate",Long.class,Long.class,Long.class,Long.class,Long.class,String.class,Integer.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.MANDATORY,
                method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation());
        assertTrue(service.isOriginalSharedRouteEligibleForUpdate(10L,77L,88L,99L,101L,"game:101",1));
        assertEquals("selectSessionByActivityForUpdate", mockingDetails(runtime).getInvocations().iterator().next().getMethod().getName());
        verify(teams,never()).selectPlayTeamByIdForUpdate(any());
    }
}
