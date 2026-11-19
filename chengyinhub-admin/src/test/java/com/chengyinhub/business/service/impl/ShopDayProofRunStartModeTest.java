package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.RunStartMode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CG-ALL-11:开局方式只认 DESK/ALT;读失败按桌面完成封顶;DESK/ALT 写失败让开局失败;正常开局清理失败不拦开局。 */
class ShopDayProofRunStartModeTest {

    @Test
    void normalizeOnlyAcceptsDeskAndAlt() {
        assertEquals("DESK", RunStartMode.normalize(" desk "));
        assertEquals("ALT", RunStartMode.normalize("ALT"));
        assertNull(RunStartMode.normalize("SKIP"));
        assertNull(RunStartMode.normalize(null));
    }

    @Test
    void deskReadFailureCapsRewardsButModeReadsAsNormal() {
        PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class);
        when(mapper.selectRunStartMode(1L, 0L, 2L)).thenThrow(new RuntimeException("db"));
        ShopDayProofServiceImpl service = service(mapper);
        assertTrue(service.deskRun(1L, null, 2L));
        assertNull(service.runStartMode(1L, null, 2L));
    }

    @Test
    void deskOnlyWhenStoredDesk() {
        PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class);
        ShopDayProofServiceImpl service = service(mapper);
        when(mapper.selectRunStartMode(1L, 3L, 2L)).thenReturn("ALT");
        assertFalse(service.deskRun(1L, 3L, 2L));
        when(mapper.selectRunStartMode(1L, 3L, 2L)).thenReturn("DESK");
        assertTrue(service.deskRun(1L, 3L, 2L));
        when(mapper.selectRunStartMode(1L, 3L, 2L)).thenReturn(null);
        assertFalse(service.deskRun(1L, 3L, 2L));
    }

    @Test
    void recordRunStartWritesOrClears() {
        PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class);
        ShopDayProofServiceImpl service = service(mapper);
        service.recordRunStart(1L, null, 2L, "DESK", 9L);
        verify(mapper).upsertRunStartMode(1L, 0L, 2L, "DESK", null);
        service.recordRunStart(1L, 4L, 2L, null, null);
        verify(mapper).deleteRunStartMode(1L, 4L, 2L);
        when(mapper.deleteRunStartMode(anyLong(), anyLong(), anyLong())).thenThrow(new RuntimeException("no table"));
        assertDoesNotThrow(() -> service.recordRunStart(1L, 4L, 2L, null, null));
        when(mapper.upsertRunStartMode(anyLong(), anyLong(), anyLong(), anyString(), any())).thenThrow(new RuntimeException("db"));
        assertThrows(RuntimeException.class, () -> service.recordRunStart(1L, 4L, 2L, "ALT", 5L));
    }

    @Test
    void interfaceDefaultFailsClosedForDeskAndAlt() {
        IShopDayProofService bare = mock(IShopDayProofService.class, CALLS_REAL_METHODS);
        assertThrows(IllegalStateException.class, () -> bare.recordRunStart(1L, 0L, 2L, "DESK", null));
        assertDoesNotThrow(() -> bare.recordRunStart(1L, 0L, 2L, null, null));
    }

    @Test
    void altStationMustBelongToTopic() {
        PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class);
        when(mapper.selectTopicAltStations(2L)).thenReturn(List.of(Map.of("id", 7L, "longitude", "1", "latitude", "2")));
        ShopDayProofServiceImpl service = service(mapper);
        assertNotNull(service.altStation(2L, 7L));
        assertNull(service.altStation(2L, 8L));
        assertNull(service.altStation(2L, null));
    }

    private static ShopDayProofServiceImpl service(PlayerNodeProgressMapper mapper) {
        ShopDayProofServiceImpl service = new ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
        return service;
    }
}
