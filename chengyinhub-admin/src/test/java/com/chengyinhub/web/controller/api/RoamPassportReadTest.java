package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamPassportMapper;
import com.chengyinhub.business.service.support.RoamPassportReadService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoamPassportReadTest {
    private RoamSession row(long id) {
        RoamSession row = new RoamSession(); row.setId(id);
        row.setStartedAt(new Date(1000)); row.setFinishedAt(new Date(3000)); row.setDistanceM(100);
        row.setFinishResultJson("{\"sessionShops\":2,\"track\":[{\"lat\":31}],\"photos\":[\"private\"]}");
        return row;
    }
    @Test void usesAuthenticatedOwnerAndExactDescendingCursor() {
        RoamPassportMapper mapper = mock(RoamPassportMapper.class);
        RoamPassportReadService service = new RoamPassportReadService(mapper);
        ApiRoamPassportController controller = spy(new ApiRoamPassportController(service));
        doReturn(7L).when(controller).getAppUserId();
        when(mapper.page(7L, null, 3)).thenReturn(List.of(row(9007199254740995L), row(9007199254740994L), row(9007199254740993L)));
        Map<?, ?> result = (Map<?, ?>) controller.history(null, 2).get(AjaxResult.DATA_TAG);
        assertEquals("9007199254740994", result.get("nextBefore"));
        assertEquals(true, result.get("hasMore"));
        Map<?, ?> fact = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertEquals(Set.of("sessionId", "startedAt", "finishedAt", "distanceM", "shops"), fact.keySet());
        assertEquals(2L, fact.get("shops"));
        when(mapper.page(7L, 9007199254740994L, 3)).thenReturn(List.of(row(9007199254740993L)));
        assertEquals(false, service.page(7L, "9007199254740994", 2).get("hasMore"));
        verify(mapper).page(7L, 9007199254740994L, 3);
    }
    @Test void unknownHistoricalStatisticsStayUnknownAndForeignRecordIsNotReturned() {
        RoamPassportMapper mapper = mock(RoamPassportMapper.class);
        RoamPassportReadService service = new RoamPassportReadService(mapper);
        RoamSession old = row(1); old.setFinishResultJson(null); old.setDistanceM(null);
        when(mapper.detail(7L, 1L)).thenReturn(old);
        assertNull(service.detail(7L, "1").get("shops"));
        assertNull(service.detail(7L, "1").get("distanceM"));
        assertThrows(ServiceException.class, () -> service.detail(8L, "1"));
        verify(mapper).detail(8L, 1L);
        assertThrows(ServiceException.class, () -> service.page(7L, "9223372036854775808", 30));
        assertThrows(ServiceException.class, () -> service.page(7L, null, 101));
        assertThrows(ServiceException.class, () -> service.summary(null));
    }
}
