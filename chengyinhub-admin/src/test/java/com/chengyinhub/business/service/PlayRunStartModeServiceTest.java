package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PlayRunStartModeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CG-ALL-11:开局方式只认 DESK/ALT;读失败按桌面完成封顶奖励;写失败让开局失败。 */
class PlayRunStartModeServiceTest {

    @Test
    void normalizeOnlyAcceptsDeskAndAlt() {
        assertEquals("DESK", PlayRunStartModeService.normalize(" desk "));
        assertEquals("ALT", PlayRunStartModeService.normalize("ALT"));
        assertNull(PlayRunStartModeService.normalize("SKIP"));
        assertNull(PlayRunStartModeService.normalize(null));
    }

    @Test
    void deskReadFailureCapsRewards() {
        PlayRunStartModeMapper mapper = mock(PlayRunStartModeMapper.class);
        PlayRunStartModeService service = service(mapper);
        when(mapper.selectRunMode(1L, 0L, 2L)).thenThrow(new RuntimeException("db"));
        assertTrue(service.deskRun(1L, null, 2L));
        assertNull(service.runMode(1L, null, 2L));
    }

    @Test
    void deskOnlyWhenStoredDesk() {
        PlayRunStartModeMapper mapper = mock(PlayRunStartModeMapper.class);
        PlayRunStartModeService service = service(mapper);
        when(mapper.selectRunMode(1L, 3L, 2L)).thenReturn("ALT");
        assertFalse(service.deskRun(1L, 3L, 2L));
        when(mapper.selectRunMode(1L, 3L, 2L)).thenReturn("DESK");
        assertTrue(service.deskRun(1L, 3L, 2L));
        when(mapper.selectRunMode(1L, 3L, 2L)).thenReturn(null);
        assertFalse(service.deskRun(1L, 3L, 2L));
    }

    @Test
    void recordStartWritesOrClearsAndPropagatesWriteFailure() {
        PlayRunStartModeMapper mapper = mock(PlayRunStartModeMapper.class);
        PlayRunStartModeService service = service(mapper);
        service.recordStart(1L, null, 2L, "DESK", 9L);
        verify(mapper).upsertRunMode(1L, 0L, 2L, "DESK", null);
        service.recordStart(1L, 4L, 2L, null, null);
        verify(mapper).deleteRunMode(1L, 4L, 2L);
        when(mapper.upsertRunMode(anyLong(), anyLong(), anyLong(), anyString(), any())).thenThrow(new RuntimeException("db"));
        assertThrows(RuntimeException.class, () -> service.recordStart(1L, 4L, 2L, "ALT", 5L));
        assertThrows(IllegalStateException.class, () -> service(null).recordStart(1L, 4L, 2L, "DESK", null));
    }

    @Test
    void altStationMustBelongToTopic() {
        PlayRunStartModeMapper mapper = mock(PlayRunStartModeMapper.class);
        when(mapper.selectAltStations(2L)).thenReturn(List.of(Map.of("id", 7L, "longitude", "1", "latitude", "2")));
        PlayRunStartModeService service = service(mapper);
        assertNotNull(service.altStation(2L, 7L));
        assertNull(service.altStation(2L, 8L));
        assertNull(service.altStation(2L, null));
    }

    private static PlayRunStartModeService service(PlayRunStartModeMapper mapper) {
        PlayRunStartModeService service = new PlayRunStartModeService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }
}
