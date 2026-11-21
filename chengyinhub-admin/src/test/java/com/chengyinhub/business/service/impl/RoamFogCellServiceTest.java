package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.support.RoamGeohashCover;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoamFogCellServiceTest {
    // Shanghai, People's Square area (GCJ-02 numbers; the math is datum-free).
    private static final double S = 31.228, W = 121.468, N = 31.236, E = 121.480;

    @Test
    void encodeMatchesKnownGeohash() {
        assertEquals("u4pruyd", RoamGeohashCover.encode(57.64911, 10.40744, 7));
        double[] b = RoamGeohashCover.bounds("u4pruyd");
        assertNotNull(b);
        assertTrue(b[0] <= 57.64911 && 57.64911 <= b[2] && b[1] <= 10.40744 && 10.40744 <= b[3]);
        assertNull(RoamGeohashCover.bounds("u4pa"));
    }

    @Test
    void prefixesCoverEveryCellInsideTheBox() {
        Set<String> prefixes = RoamGeohashCover.prefixes(S, W, N, E);
        assertNotNull(prefixes);
        for (double lat = S; lat <= N; lat += 0.0004) {
            for (double lng = W; lng <= E; lng += 0.0004) {
                String cell = RoamGeohashCover.encode(lat, lng, 7);
                assertTrue(prefixes.contains(cell.substring(0, 5)), cell);
            }
        }
        // Corners too, including the north-east edge.
        assertTrue(prefixes.contains(RoamGeohashCover.encode(N, E, 5)));
        assertTrue(prefixes.contains(RoamGeohashCover.encode(S, W, 5)));
    }

    @Test
    void prefixesAcrossCellBoundaries() {
        // A box straddling many precision-5 cells (about 20 km).
        Set<String> prefixes = RoamGeohashCover.prefixes(31.10, 121.30, 31.28, 121.52);
        assertNotNull(prefixes);
        for (double lat = 31.10; lat <= 31.28; lat += 0.01)
            for (double lng = 121.30; lng <= 121.52; lng += 0.01)
                assertTrue(prefixes.contains(RoamGeohashCover.encode(lat, lng, 5)));
    }

    @Test
    void oversizedBoxIsRefused() {
        assertNull(RoamGeohashCover.prefixes(30.0, 120.0, 32.0, 122.0));
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        RoamFogCellService.BoxRead read = new RoamFogCellService(mapper, true).cellsInBox(1L, 30.0, 120.0, 32.0, 122.0);
        assertTrue(read.tooLarge);
        verifyNoInteractions(mapper);
    }

    @Test
    void theAppsLargestBoxIsAlwaysAccepted() {
        // The app never asks for more than 0.3 x 0.3 degrees (RoamFogBox.maxSpanDegrees).
        for (double lat = -60; lat <= 60; lat += 7.3)
            for (double lng = -170; lng <= 170; lng += 11.9)
                assertNotNull(RoamGeohashCover.prefixes(lat, lng, lat + 0.3, lng + 0.3), lat + "," + lng);
    }

    @Test
    void boxValidation() {
        assertTrue(RoamGeohashCover.validBox(S, W, N, E));
        assertFalse(RoamGeohashCover.validBox(N, W, S, E));
        assertFalse(RoamGeohashCover.validBox(S, E, N, W));
        assertFalse(RoamGeohashCover.validBox(null, W, N, E));
        assertFalse(RoamGeohashCover.validBox(Double.NaN, W, N, E));
        assertFalse(RoamGeohashCover.validBox(-91.0, W, N, E));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordKeepsOnlyValidCellsWhenEnabled() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        when(mapper.upsertCells(anyLong(), anyCollection(), any())).thenReturn(2);
        Date at = new Date();
        assertEquals(2, new RoamFogCellService(mapper, true).record(7L, List.of("WTW3SJQ", "wtw3sjq", "bad", "wtw3sjr"), at));
        ArgumentCaptor<Collection<String>> cells = ArgumentCaptor.forClass(Collection.class);
        verify(mapper).upsertCells(eq(7L), cells.capture(), eq(at));
        assertEquals(List.of("wtw3sjq", "wtw3sjr"), new ArrayList<>(cells.getValue()));
    }

    @Test
    void aFailedKeptWriteNeverFailsTheReveal() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        when(mapper.upsertCells(anyLong(), anyCollection(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("no table"));
        assertEquals(0, new RoamFogCellService(mapper, true).record(7L, List.of("wtw3sjq"), new Date()));
    }

    @Test
    void disabledKeepsNothingAndReadsTheThirtyDayWindow() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        RoamFogCellService service = new RoamFogCellService(mapper, false);
        assertEquals(0, service.record(7L, List.of("wtw3sjq"), new Date()));
        verify(mapper, never()).upsertCells(any(), any(), any());
        String inside = RoamGeohashCover.encode(31.232, 121.474, 7);
        when(mapper.selectRecentCellsByPrefixes(eq(7L), anyCollection(), eq(RoamFogCellService.MAX_CELLS + 1)))
                .thenReturn(List.of(inside));
        RoamFogCellService.BoxRead read = service.cellsInBox(7L, S, W, N, E);
        assertFalse(read.retained);
        assertEquals(List.of(inside), read.cells);
        verify(mapper, never()).selectRetainedCellsByPrefixes(any(), any(), any());
        assertNull(service.retainedCells(7L, 10));
    }

    @Test
    void enabledReadsKeptCellsAndDropsCellsOutsideTheBox() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        String inside = RoamGeohashCover.encode(31.232, 121.474, 7);
        String outside = RoamGeohashCover.encode(31.200, 121.440, 7); // same precision-5 cell family, outside the box
        when(mapper.selectRetainedCellsByPrefixes(eq(7L), anyCollection(), anyInt())).thenReturn(List.of(inside, outside, "zzz"));
        RoamFogCellService.BoxRead read = new RoamFogCellService(mapper, true).cellsInBox(7L, S, W, N, E);
        assertTrue(read.retained);
        assertFalse(read.truncated);
        assertEquals(List.of(inside), read.cells);
        verify(mapper, never()).selectRecentCellsByPrefixes(any(), any(), any());
    }

    @Test
    void purgeRunsOnlyWhenKeepingIsOff() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        Date cutoff = new Date();
        assertEquals(0, new RoamFogCellService(mapper, true).purgeWhenDisabled(cutoff, 100));
        verifyNoInteractions(mapper);
        when(mapper.deleteNotRevealedSince(cutoff, 100)).thenReturn(3);
        assertEquals(3, new RoamFogCellService(mapper, false).purgeWhenDisabled(cutoff, 100));
    }

    @Test
    void sqlIsOwnerScopedAndThirtyDayFallbackMatchesTheLegacyReaders() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/resources/mapper/business/RoamFogCellMapper.xml")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        assertTrue(xml.contains("where member_id = #{memberId} and cell_prefix5 in"));
        assertTrue(xml.contains("s.status = 0 or (s.status = 1 and s.finished_at &gt; date_sub(now(), interval 30 day))"));
        assertTrue(xml.contains("on duplicate key update last_revealed_at = greatest(last_revealed_at, values(last_revealed_at))"));
        String migration = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "sql/migration_20261115_fog_map.sql")), StandardCharsets.UTF_8);
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS roam_member_fog_cell"));
        assertTrue(migration.contains("Rollback"));
        assertFalse(migration.toLowerCase().contains("drop table if exists roam_fog_tile"));
    }
}
