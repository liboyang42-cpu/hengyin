package com.chengyinhub.business.locationtrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.mapper.LocationTrustMapper;
import com.chengyinhub.system.service.ISysConfigService;

class LocationTrustServiceTest {
    private static final double LAT = 31.2304, LNG = 121.4737;

    private static ISysConfigService config(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        ISysConfigService c = mock(ISysConfigService.class);
        when(c.selectConfigByKey(anyString())).thenAnswer(inv -> m.get(inv.getArgument(0)));
        return c;
    }

    private static Map<String, Object> fixRow(double lat, double lng, long agoMs) {
        Map<String, Object> row = new HashMap<>();
        row.put("lat", lat);
        row.put("lng", lng);
        row.put("receivedAt", new Date(System.currentTimeMillis() - agoMs));
        return row;
    }

    @Test
    void everyScoredWriteLeavesATrailRow_withWithheldDecisionForTeleport() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        when(mapper.selectLastCleanFix(anyLong(), any())).thenReturn(fixRow(LAT - 0.05, LNG, 60_000L)); // ~5.5 km, 60 s
        LocationTrustService svc = new LocationTrustService(config(), mapper, null);
        LocationTrustService.Assessment a = svc.assess(7L, 3L, LocationTrustKind.CHECKIN, "k", LocationFix.of(LAT, LNG));
        assertTrue(a.withheld());
        ArgumentCaptor<LocationTrustFixRow> row = ArgumentCaptor.forClass(LocationTrustFixRow.class);
        verify(mapper).insertFix(row.capture());
        assertEquals("ACCEPT_NO_REWARD", row.getValue().getDecision());
        assertEquals("ACCEPT_NO_REWARD", row.getValue().getAppliedDecision());
        assertEquals("SPEED_IMPOSSIBLE", row.getValue().getReasons());
        assertEquals("CHECKIN", row.getValue().getKind());
    }

    @Test
    void shadowMode_scoresAndRecordsButNeverBlocks() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        LocationTrustService svc = new LocationTrustService(config("location.trust.mode", "SHADOW"), mapper, null);
        LocationTrustService.Assessment a = svc.assess(7L, null, LocationTrustKind.CHECKIN, null,
                new LocationFix(LAT, LNG, 5d, null, true, null));
        assertTrue(a.credited());
        assertEquals(LocationTrustDecision.ACCEPT_NO_REWARD, a.verdict().decision());
        ArgumentCaptor<LocationTrustFixRow> row = ArgumentCaptor.forClass(LocationTrustFixRow.class);
        verify(mapper).insertFix(row.capture());
        assertEquals("ACCEPT", row.getValue().getAppliedDecision());
    }

    @Test
    void offMode_doesNothing() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        LocationTrustService svc = new LocationTrustService(config("location.trust.mode", "OFF"), mapper, null);
        assertTrue(svc.assess(7L, null, LocationTrustKind.CHECKIN, null, new LocationFix(LAT, LNG, 5d, null, true, null)).credited());
        verify(mapper, never()).insertFix(any());
    }

    @Test
    void failOpen_whenTrailTableIsMissing() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        when(mapper.selectLastCleanFix(anyLong(), any())).thenThrow(new RuntimeException("Table doesn't exist"));
        when(mapper.insertFix(any())).thenThrow(new RuntimeException("Table doesn't exist"));
        LocationTrustService svc = new LocationTrustService(config(), mapper, null);
        LocationTrustService.Assessment a = svc.assess(7L, null, LocationTrustKind.CHECKIN, null, LocationFix.of(LAT, LNG));
        assertTrue(a.credited());
        assertEquals(null, a.fixId());
    }

    @Test
    void reveal_simulatedFixCreditsNoTiles_butOldClientsKeepLegacyBudget() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        LocationTrustService svc = new LocationTrustService(config(), mapper, null);
        Date started = new Date(System.currentTimeMillis() - 600_000L);
        List<String> tiles = List.of("wtw3sjq", "wtw3sjr");
        LocationTrustService.RevealFilter sim = svc.creditableTiles(7L, 3L, started, tiles, 0, 10_000,
                new LocationFix(LAT, LNG, 5d, null, true, null));
        assertTrue(sim.credited().isEmpty());
        assertEquals(2, sim.droppedUntrusted());
        LocationTrustService.RevealFilter old = svc.creditableTiles(7L, 3L, started, tiles, 0, 10_000, null);
        assertEquals(tiles, old.credited());
    }

    @Test
    void reveal_tilesFarFromTheFixAreDropped_andTrailDetailIsWritten() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        when(mapper.insertFix(any())).thenAnswer(inv -> {
            ((LocationTrustFixRow) inv.getArgument(0)).setId(55L);
            return 1;
        });
        LocationTrustService svc = new LocationTrustService(config(), mapper, null);
        Date started = new Date(System.currentTimeMillis() - 600_000L);
        LocationTrustService.RevealFilter f = svc.creditableTiles(7L, 3L, started, List.of("wtw3sjq", "wx4g0bm"), 0,
                10_000, LocationFix.of(LAT, LNG));
        assertEquals(List.of("wtw3sjq"), f.credited());
        assertEquals(1, f.droppedFarFromTrack());
        verify(mapper).updateFixDetail(eq(55L), anyString());
    }

    @Test
    void reveal_rollingWindowCapsBurstsAfterIdle() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        when(mapper.countTilesRevealedSince(anyLong(), any())).thenReturn(389);
        LocationTrustService svc = new LocationTrustService(config(), mapper, null);
        Date started = new Date(System.currentTimeMillis() - 3_600_000L);
        LocationTrustService.RevealFilter f = svc.creditableTiles(7L, 3L, started, List.of("wtw3sjq", "wtw3sjr"), 0,
                10_000, null);
        assertEquals(1, f.credited().size());
        assertEquals(1, f.droppedOverBudget());
        verify(mapper, never()).insertFix(any());
    }

    @Test
    void cleanFixHistory_isReadAsLatLngTime() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        when(mapper.selectCleanFixesSince(anyLong(), any(), anyInt())).thenReturn(List.of(fixRow(LAT, LNG, 1000L)));
        List<double[]> out = new LocationTrustService(config(), mapper, null).cleanFixesSince(7L, new Date(0L), 10);
        assertEquals(1, out.size());
        assertEquals(LAT, out.get(0)[0]);
    }
}
