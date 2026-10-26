package com.chengyinhub.web.service.audit;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayBasicIntentApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual adapter behavior with Mapper doubles; lock/TX behavior still requires real MySQL evidence. */
class GuardArrivalEvidenceReaderTest {
    private PlayArriveGuardLogMapper logs;
    private GameSessionRuntimeMapper commands;
    private TopicRouteSessionMapper routes;
    private PlayBasicIntentApplication basic;
    private GuardArrivalEvidenceReaderImpl reader;
    private TopicRouteSession session;
    private GameCommandReceipt command;
    private String key;
    @BeforeEach void prepare() throws Exception {
        logs = mock(PlayArriveGuardLogMapper.class); commands = mock(GameSessionRuntimeMapper.class);
        routes = mock(TopicRouteSessionMapper.class); basic = mock(PlayBasicIntentApplication.class);
        reader = new GuardArrivalEvidenceReaderImpl(logs, commands, routes, basic, mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class));
        PlayArriveGuardLog log = new PlayArriveGuardLog(); log.setId(91L); log.setMemberId(10L);
        log.setActivityId(0L); log.setTopicId(77L); log.setNodeId(99L); log.setVerdict("REJECT"); log.setReasonCode("ARRIVE_GUARD_SPEED");
        when(logs.selectByIdAndMemberId(91L, 10L)).thenReturn(log);
        key = new PlayBasicIntentApplication().basicRequestId("arrive-1");
        command = new GameCommandReceipt(); command.setActorMemberId(10L); command.setActivityId(0L); command.setNodeId(99L);
        command.setRequestId(key); command.setActorPerspective("PLAYER"); command.setAction("PLAY_BASIC_ARRIVE"); command.setOutcome("FAILED");
        command.setResultJson("{\"code\":500,\"intentTerminal\":true,\"appealable\":true,\"appealBizType\":\"play_arrive_guard\",\"guardLogId\":\"91\",\"nodeId\":\"99\",\"routeSessionId\":\"101\",\"errorCode\":\"ARRIVE_GUARD_SPEED\"}");
        when(commands.selectReceipt(0L, 10L, key)).thenReturn(command);
        when(basic.basicActionReceipt(10L,"0","77","99","arrive-1"))
                .thenReturn(new ObjectMapper().readValue(command.getResultJson(), AjaxResult.class));
        session = new TopicRouteSession(); session.setId(101L); session.setMemberId(10L); session.setActivityId(0L); session.setTopicId(77L); session.setStatus(0);
        when(routes.selectAccessibleById(101L,10L)).thenReturn(session);
        when(routes.selectAccessibleByIdForUpdate(101L,10L)).thenReturn(session);
    }
    @Test void readsExactOriginalSessionAndNeverUsesCurrentSessionOrRefresh() {
        assertTrue(reader.readOwned(10L,91L,"arrive-1").originalRunActive());
        verify(routes).selectAccessibleById(101L,10L); verifyNoMoreInteractions(routes);
        verify(commands, never()).selectSessionByActivityForUpdate(any()); verifyNoInteractions(basic);
        session.setStatus(2); assertFalse(reader.readOwned(10L,91L,"arrive-1").originalRunActive());
    }
    @Test void foreignActorWrongCommandAndCrossTopicNeverAuthorizeOriginalRun() {
        assertThrows(ServiceException.class, () -> reader.readOwned(11L,91L,"arrive-1"));
        command.setAction("PLAY_BASIC_CHECKIN"); assertThrows(ServiceException.class, () -> reader.readOwned(10L,91L,"arrive-1"));
        command.setAction("PLAY_BASIC_ARRIVE"); session.setTopicId(78L);
        assertFalse(reader.readOwned(10L,91L,"arrive-1").originalRunActive());
        assertThrows(ServiceException.class, () -> reader.readOwned(10L,91L,"different-command"));
    }
    @Test void newWriteReusesOriginalEntryQualificationAndLocksAggregateBeforeRoute() {
        assertTrue(reader.lockOwned(10L,91L,"arrive-1").originalRunActive());
        InOrder order = inOrder(basic, commands, routes);
        order.verify(commands).selectReceipt(0L,10L,key);
        order.verify(basic).basicActionReceipt(10L,"0","77","99","arrive-1");
        order.verify(commands).selectSessionByActivityForUpdate(0L);
        order.verify(routes).selectAccessibleByIdForUpdate(101L,10L);
        when(basic.basicActionReceipt(10L,"0","77","99","arrive-1")).thenReturn(AjaxResult.error("no registration"));
        assertThrows(ServiceException.class, () -> reader.lockOwned(10L,91L,"arrive-1"));
    }
}
