package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.OrientSessionClock;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.OrientSessionMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IOrientScoringService;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrientRouteSettlementTest {
    @Test void terminalClockPrecedesStableSettlementAndFailurePropagates() {
        var scoring = mock(IOrientScoringService.class); var routes = mock(TopicRouteSessionMapper.class); var clocks = mock(OrientSessionMapper.class);
        var service = new OrientRouteSettlement(scoring, routes, clocks);
        TopicRouteSession session = new TopicRouteSession(); session.setId(51L); session.setMemberId(7L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED); session.setCompletedAt(new Date(5000));
        OrientSessionClock clock = new OrientSessionClock(); clock.setStartedAt(new Date(1000));
        when(routes.selectAccessibleByIdForUpdate(51L, 7L)).thenReturn(session);
        when(clocks.selectClock(51L, 7L)).thenReturn(clock); when(clocks.closeClock(51L, 7L, session.getCompletedAt(), 4000)).thenReturn(1);
        when(scoring.settleBySystem(51L, "lifecycle:settle:51")).thenThrow(new IllegalStateException("pending review"));
        assertSame(session, service.lock(session));
        assertThrows(IllegalStateException.class, () -> service.complete(session, new TopicRouteEngine.State()));
        var order = inOrder(routes, clocks, scoring);
        order.verify(routes).selectAccessibleByIdForUpdate(51L, 7L);
        order.verify(clocks).selectClock(51L, 7L);
        order.verify(clocks).closeClock(51L, 7L, session.getCompletedAt(), 4000);
        order.verify(scoring).settleBySystem(51L, "lifecycle:settle:51");
    }
    @Test void explicitAbandonClosesClockWithoutOrdinaryRewards() {
        var scoring = mock(IOrientScoringService.class); var clocks = mock(OrientSessionMapper.class);
        var service = new OrientRouteSettlement(scoring, mock(TopicRouteSessionMapper.class), clocks);
        TopicRouteSession session = new TopicRouteSession(); session.setId(51L); session.setMemberId(7L);
        session.setStatus(TopicRouteSession.STATUS_ENDED); session.setCompletedAt(new Date(5000));
        TopicRouteEngine.State state = new TopicRouteEngine.State(); state.getThemeRuntime().putObject("result").put("reason", "ABANDONED");
        service.beforePersist(session, state);
        service.complete(session, state);
        verify(clocks).selectClock(51L, 7L); verifyNoInteractions(scoring);
    }
}
