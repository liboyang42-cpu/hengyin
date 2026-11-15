package com.chengyinhub.business.readcache;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.mapper.RoamMapper;
import com.fasterxml.jackson.databind.JavaType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T2 maps-backend: the unfiltered player place read ({@code /api/roam/pois}) served from per-tile cache entries.
 *
 * <p>The map is cut into fixed tiles of {@value #TILE_DEGREES} degree. A tile entry holds every published, approved
 * place in the tile with its shop avatar: shared data only, nothing about the member. A request reads the tiles that
 * cover its box (one Redis round trip plus one for the namespace version), loads missing tiles from MySQL, and then
 * does exactly what {@code RoamMapper.selectPoisNearFiltered} does in SQL:
 * <ol>
 *   <li>keeps the places inside the box with the same inclusive bounds (compared as decimals, like MySQL compares
 *       the {@code decimal(10,6)} columns with the bound literals the driver sends);</li>
 *   <li>orders them by the same squared equirectangular distance to the box centre, computed in the same steps and
 *       types as MySQL (exact decimal differences, then double), then by id;</li>
 *   <li>keeps the first {@code limit};</li>
 *   <li>adds {@code found} / {@code pendingRedeem} for the member with one query over those ids (same rules).</li>
 * </ol>
 * It falls back to the SQL read when caching is off, Redis fails, the box needs more than {@code maxTiles} tiles,
 * or a tile holds more than {@code tileCap} places (a marker is cached so the dense tile is not re-counted each time).
 */
public class MapPoiTileCache {
    static final double TILE_DEGREES = 0.02;
    private static final BigDecimal TILES_PER_DEGREE = BigDecimal.valueOf(50);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    /** Cached in place of a tile that holds more than {@code tileCap} places. */
    static final String DENSE = "!";
    /** Bumped when the tile value format changes, so old entries are never decoded. */
    private static final String FORMAT = "t1";

    private final RoamMapper mapper;
    private final ReadCache cache;
    private final int maxTiles;
    private final int tileCap;
    private final JavaType rowsType;

    public MapPoiTileCache(RoamMapper mapper, ReadCache cache, int maxTiles, int tileCap) {
        this.mapper = mapper;
        this.cache = cache;
        this.maxTiles = Math.max(1, maxTiles);
        this.tileCap = Math.max(1, tileCap);
        this.rowsType = cache.listOf(RoamPoi.class);
    }

    public List<RoamPoi> near(double minLat, double maxLat, double minLng, double maxLng, Long memberId, int limit) {
        if (!cache.enabled() || !(minLat <= maxLat) || !(minLng <= maxLng) || limit <= 0) {
            return mapper.selectPoisNear(minLat, maxLat, minLng, maxLng, memberId, limit);
        }
        BigDecimal south = BigDecimal.valueOf(minLat), north = BigDecimal.valueOf(maxLat);
        BigDecimal west = BigDecimal.valueOf(minLng), east = BigDecimal.valueOf(maxLng);
        long y0 = tile(south), y1 = tile(north), x0 = tile(west), x1 = tile(east);
        if ((y1 - y0 + 1) * (x1 - x0 + 1) > maxTiles) {
            return mapper.selectPoisNear(minLat, maxLat, minLng, maxLng, memberId, limit);
        }
        List<long[]> tiles = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> values;
        try {
            String version = cache.version(ReadCacheNamespace.POI);
            for (long y = y0; y <= y1; y++) {
                for (long x = x0; x <= x1; x++) {
                    tiles.add(new long[]{y, x});
                    keys.add(ReadCache.entryKey(ReadCacheNamespace.POI, version, FORMAT + ":" + y + ":" + x));
                }
            }
            values = cache.getMany(keys);
        } catch (Exception e) {
            cache.failed("read", ReadCacheNamespace.POI, e);
            return mapper.selectPoisNear(minLat, maxLat, minLng, maxLng, memberId, limit);
        }

        Map<Long, RoamPoi> inBox = new HashMap<>();
        int hits = 0, misses = 0;
        for (int i = 0; i < tiles.size(); i++) {
            String value = values.get(i);
            boolean dense = DENSE.equals(value);
            List<RoamPoi> rows = null;
            if (!dense && value != null) {
                try {
                    rows = cache.json().readValue(value, rowsType);
                    hits++;
                } catch (Exception e) {
                    cache.failed("decode", ReadCacheNamespace.POI, e);
                }
            }
            if (!dense && rows == null) {
                misses++;
                rows = loadTile(tiles.get(i)[0], tiles.get(i)[1], keys.get(i));
                dense = rows == null;
            }
            if (dense) {
                cache.hit(hits);
                cache.miss(misses);
                return mapper.selectPoisNear(minLat, maxLat, minLng, maxLng, memberId, limit);
            }
            for (RoamPoi p : rows) {
                if (p.getLat() == null || p.getLng() == null || p.getId() == null) continue;
                if (p.getLat().compareTo(south) < 0 || p.getLat().compareTo(north) > 0) continue;
                if (p.getLng().compareTo(west) < 0 || p.getLng().compareTo(east) > 0) continue;
                inBox.putIfAbsent(p.getId(), p); // a place on a tile edge is in both tiles
            }
        }
        cache.hit(hits);
        cache.miss(misses);

        BigDecimal centerLat = south.add(north).divide(TWO);
        BigDecimal centerLng = west.add(east).divide(TWO);
        double k = Math.cos(Math.toRadians(centerLat.doubleValue()));
        record Ranked(RoamPoi poi, double distance) {
        }
        List<Ranked> ranked = new ArrayList<>(inBox.size());
        for (RoamPoi p : inBox.values()) ranked.add(new Ranked(p, squaredDistance(p, centerLat, centerLng, k)));
        ranked.sort(Comparator.comparingDouble(Ranked::distance).thenComparing(r -> r.poi().getId()));
        List<RoamPoi> ordered = new ArrayList<>(Math.min(limit, ranked.size()));
        for (int i = 0; i < ranked.size() && i < limit; i++) ordered.add(ranked.get(i).poi());
        mark(ordered, memberId);
        return ordered;
    }

    /**
     * {@code power(p.lat - c_lat, 2) + power((p.lng - c_lng) * cos(radians(c_lat)), 2)} as MySQL evaluates it:
     * the differences are exact decimals, each is converted to double once, then squared and summed in double.
     */
    static double squaredDistance(RoamPoi p, BigDecimal centerLat, BigDecimal centerLng, double k) {
        double dLat = p.getLat().subtract(centerLat).doubleValue();
        double dLng = p.getLng().subtract(centerLng).doubleValue() * k;
        return Math.pow(dLat, 2) + Math.pow(dLng, 2);
    }

    static long tile(BigDecimal degrees) {
        return degrees.multiply(TILES_PER_DEGREE).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** Loads one tile from MySQL and caches it; null when the tile is denser than {@code tileCap}. */
    private List<RoamPoi> loadTile(long y, long x, String key) {
        double south = BigDecimal.valueOf(y).divide(TILES_PER_DEGREE).doubleValue();
        double north = BigDecimal.valueOf(y + 1).divide(TILES_PER_DEGREE).doubleValue();
        double west = BigDecimal.valueOf(x).divide(TILES_PER_DEGREE).doubleValue();
        double east = BigDecimal.valueOf(x + 1).divide(TILES_PER_DEGREE).doubleValue();
        List<RoamPoi> rows = mapper.selectPoiBaseInBox(south, north, west, east, tileCap + 1);
        int ttl = cache.ttl(ReadCacheNamespace.POI);
        if (rows.size() > tileCap) {
            cache.putRaw(key, DENSE, ttl, ReadCacheNamespace.POI);
            return null;
        }
        try {
            String json = cache.json().writeValueAsString(rows);
            cache.putRaw(key, json, ttl, ReadCacheNamespace.POI);
            // Serve the decoded copy, so a miss answers exactly like the hit that follows it.
            return cache.json().readValue(json, rowsType);
        } catch (Exception e) {
            cache.failed("encode", ReadCacheNamespace.POI, e);
            return rows;
        }
    }

    /** found / pendingRedeem for the member, false for everyone when signed out (as the SQL read). */
    private void mark(List<RoamPoi> rows, Long memberId) {
        Map<Long, Map<String, Object>> marks = new LinkedHashMap<>();
        if (memberId != null && memberId > 0 && !rows.isEmpty()) {
            List<Long> ids = new ArrayList<>(rows.size());
            for (RoamPoi p : rows) ids.add(p.getId());
            for (Map<String, Object> m : mapper.selectPoiMemberMarks(memberId, ids)) {
                Object id = m.get("poiId");
                if (id instanceof Number n) marks.put(n.longValue(), m);
            }
        }
        for (RoamPoi p : rows) {
            Map<String, Object> m = marks.get(p.getId());
            p.setFound(m != null && truthy(m.get("found")));
            p.setPendingRedeem(m != null && truthy(m.get("pendingRedeem")));
        }
    }

    static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.longValue() != 0;
        return value != null && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
    }
}
