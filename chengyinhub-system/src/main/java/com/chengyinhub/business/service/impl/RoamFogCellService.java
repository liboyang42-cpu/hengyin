package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.support.RoamGeohashCover;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Revealed fog cells kept per member (owner decision 2026-11-15: kept for good, with clear consent).
 *
 * <p>{@code roam.fog.retainRevealedCells} (default true) switches the keeping on. Legal confirmation is pending, so
 * false restores today's behaviour exactly: nothing new is kept, every read falls back to the 30-day session window
 * of {@code roam_fog_tile}, and the retention job removes kept cells not revealed again within 30 days. Raw tracks
 * and the per-session tiles keep their own 30-day retention either way.
 */
@Service
public class RoamFogCellService {
    /** Cells returned per bounding-box read. A 50 km box at walking density stays far below this. */
    public static final int MAX_CELLS = 20000;

    private final RoamFogCellMapper mapper;
    private final boolean retain;

    public RoamFogCellService(RoamFogCellMapper mapper,
                              @Value("${roam.fog.retainRevealedCells:true}") boolean retain) {
        this.mapper = mapper;
        this.retain = retain;
    }

    public boolean retainEnabled() { return retain; }

    /** Called inside the reveal transaction, after the consent check, with already validated geohash-7 tiles. */
    public int record(Long memberId, Collection<String> tiles, Date at) {
        if (!retain || memberId == null || memberId <= 0 || tiles == null || at == null) return 0;
        Set<String> cells = new LinkedHashSet<>();
        for (String tile : tiles) {
            String cell = RoamGeohashCover.normalize(tile);
            if (RoamGeohashCover.isCell(cell, 7)) cells.add(cell);
        }
        if (cells.isEmpty()) return 0;
        return mapper.upsertCells(memberId, cells, at);
    }

    /** Result of a bounding-box read. {@code tooLarge} means the box was refused; nothing was read. */
    public static final class BoxRead {
        public final List<String> cells;
        public final boolean retained;
        public final boolean truncated;
        public final boolean tooLarge;
        BoxRead(List<String> cells, boolean retained, boolean truncated, boolean tooLarge) {
            this.cells = cells; this.retained = retained; this.truncated = truncated; this.tooLarge = tooLarge;
        }
    }

    public BoxRead cellsInBox(Long memberId, double south, double west, double north, double east) {
        Set<String> prefixes = RoamGeohashCover.prefixes(south, west, north, east);
        if (prefixes == null) return new BoxRead(List.of(), retain, false, true);
        if (prefixes.isEmpty()) return new BoxRead(List.of(), retain, false, false);
        List<String> rows = retain
                ? mapper.selectRetainedCellsByPrefixes(memberId, prefixes, MAX_CELLS + 1)
                : mapper.selectRecentCellsByPrefixes(memberId, prefixes, MAX_CELLS + 1);
        boolean truncated = rows != null && rows.size() > MAX_CELLS;
        List<String> out = new ArrayList<>();
        if (rows != null) {
            for (String row : rows) {
                if (out.size() >= MAX_CELLS) break;
                String cell = RoamGeohashCover.normalize(row);
                if (RoamGeohashCover.isCell(cell, 7) && RoamGeohashCover.intersects(cell, south, west, north, east)) out.add(cell);
            }
        }
        return new BoxRead(out, retain, truncated, false);
    }

    /** Kept cells for the legacy restore list, or null when keeping is off (caller keeps the 30-day read). */
    public List<String> retainedCells(Long memberId, int limit) {
        if (!retain) return null;
        return mapper.selectRetainedCells(memberId, limit);
    }

    /** Retention job hook. Does nothing while keeping is on. */
    public int purgeWhenDisabled(Date cutoff, int limit) {
        if (retain || cutoff == null || limit <= 0) return 0;
        return mapper.deleteNotRevealedSince(cutoff, limit);
    }
}
