package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.readcache.MapBoxReadParityTest;
import com.chengyinhub.business.readcache.MapPoiTileCache;
import com.chengyinhub.business.readcache.ReadCache;
import com.chengyinhub.business.readcache.ReadCacheNamespace;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2 maps-backend: GET /api/roam/pois answers byte for byte the same with the tile cache as without it, through the
 * real controller method: #830's node-level-then-distance order, the limit + 1 "truncated" flag, the three-picture
 * gallery preview, and the guest projection (signed-out viewers get the public fields only, no found / pending).
 * The cache's own off switch is covered in MapBoxReadParityTest; here "uncached" is the controller with no cache bean,
 * which is exactly the base branch's code path.
 */
public class RoamPoisReadCacheParityTest {
    private final ObjectMapper json = new ObjectMapper();
    private MapBoxReadParityTest db;

    public static class Viewer extends ApiRoamController {
        long viewer;

        @Override
        Long uid() {
            return viewer;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = new MapBoxReadParityTest();
        db.setUp();
    }

    private Viewer controller(long viewer, MapPoiTileCache tiles) {
        return controller(db.sessions().getMapper(RoamMapper.class), viewer, tiles);
    }

    /** The real controller with only the place mapper and (optionally) the tile cache wired. */
    public static Viewer controller(RoamMapper mapper, long viewer, MapPoiTileCache tiles) {
        Viewer c = new Viewer();
        c.viewer = viewer;
        ReflectionTestUtils.setField(c, "roamMapper", mapper);
        ReflectionTestUtils.setField(c, "poiTileCache", tiles);
        return c;
    }

    /** GET /api/roam/pois as the app receives it (AjaxResult with data and truncated). */
    public static String pois(Viewer c, double lat, double lng, Integer radius) throws Exception {
        return new ObjectMapper().writeValueAsString(c.pois(lat, lng, radius));
    }

    private String text(AjaxResult r) throws Exception {
        return json.writeValueAsString(r);
    }

    @Test
    void cachedAndUncachedAnswersAreIdenticalForGuestsAndMembers() throws Exception {
        MapPoiTileCache tiles = new MapPoiTileCache(db.sessions().getMapper(RoamMapper.class), db.cache(), 64, 3000);
        boolean sawGuestProjection = false;
        double[][] points = {{30.23, 120.155}, {30.259, 120.189}};
        int[] radii = {200, 380}; // H2 is slow on large boxes; the 3 km default and 20 km (truncated) run on MySQL
                                  // in MapBoxReadParityMySqlIT.controllerAnswersAreIdenticalCachedAndUncached
        for (long viewer : new long[]{0L, MapBoxReadParityTest.MEMBER}) {
            for (double[] p : points) {
                for (int r : radii) {
                    Integer radius = r == 0 ? null : r;
                    String uncached = text(controller(viewer, null).pois(p[0], p[1], radius));
                    String cold = text(controller(viewer, tiles).pois(p[0], p[1], radius));
                    String warm = text(controller(viewer, tiles).pois(p[0], p[1], radius));
                    String where = "viewer " + viewer + " at " + p[0] + "," + p[1] + " r=" + r;
                    assertEquals(uncached, cold, "cold cache, " + where);
                    assertEquals(uncached, warm, "warm cache, " + where);
                    assertTrue(uncached.contains("\"truncated\":false"), where);
                    if (viewer == 0L && uncached.contains("\"name\"")) {
                        sawGuestProjection = true;
                        assertFalse(uncached.contains("\"found\""), "guests never get member marks: " + where);
                        assertFalse(uncached.contains("\"pendingRedeem\""), where);
                    }
                }
            }
        }
        assertTrue(sawGuestProjection);
        assertTrue(db.cache().hits() > 0);
    }

    @Test
    void cachedReadServesFreshObjectsSoTheGalleryTrimNeverReachesTheCache() throws Exception {
        MapPoiTileCache tiles = new MapPoiTileCache(db.sessions().getMapper(RoamMapper.class), db.cache(), 64, 3000);
        controller(MapBoxReadParityTest.MEMBER, tiles).pois(30.23, 120.155, 380);
        boolean fourPictureGallery = false;
        for (Map.Entry<String, String> e : db.store().values.entrySet()) {
            if (e.getKey().contains(":" + ReadCacheNamespace.POI + ":") && e.getValue().contains("d.jpg")) fourPictureGallery = true;
        }
        assertTrue(fourPictureGallery, "tile entries keep the full gallery; the 3-picture preview is cut per response");
        String uncached = text(controller(MapBoxReadParityTest.MEMBER, null).pois(30.23, 120.155, 380));
        assertEquals(uncached, text(controller(MapBoxReadParityTest.MEMBER, tiles).pois(30.23, 120.155, 380)));
        assertFalse(uncached.contains("d.jpg"), "the response itself still carries the preview only");
    }

    @Test
    void filteredReadsNeverUseTheCache() throws Exception {
        MapPoiTileCache tiles = new MapPoiTileCache(db.sessions().getMapper(RoamMapper.class), db.cache(), 64, 3000);
        int before = db.store().calls.get();
        for (String kw : List.of("Cafe")) {
            assertEquals(text(controller(MapBoxReadParityTest.MEMBER, null).pois(30.23, 120.155, 380, kw, null, null)),
                    text(controller(MapBoxReadParityTest.MEMBER, tiles).pois(30.23, 120.155, 380, kw, null, null)));
        }
        assertEquals(text(controller(0L, null).pois(30.23, 120.155, 380, null, 3L, "coffee")),
                text(controller(0L, tiles).pois(30.23, 120.155, 380, null, 3L, "coffee")));
        assertEquals(before, db.store().calls.get(), "keyword / category / tag reads go to MySQL only");
    }

    @Test
    void cacheSwitchDefaultsOff() throws Exception {
        String src = new String(ApiRoamController.class.getClassLoader()
                .getResourceAsStream("application.yml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("enabled: ${CHENGYIN_READ_CACHE_ENABLED:false}"), "read cache must default to off");
        assertFalse(new ReadCache(null, new ReadCache.Settings(true, Map.of())).enabled(), "no Redis, no cache");
    }
}
