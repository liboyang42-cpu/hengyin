package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** R1 MKT-08: /api/roam/pois narrows by keyword, merchant category and tag; no filter = the original read. */
class ApiRoamPoiFilterTest {
    private static final long PLAYER = 9L;

    private static class StubController extends ApiRoamController {
        @Override
        Long uid() { return PLAYER; }
    }

    private RoamMapper roamMapper;
    private StubController controller;

    @BeforeEach
    void setUp() {
        roamMapper = mock(RoamMapper.class);
        controller = new StubController();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
    }

    @Test
    void noFilterKeepsTheOriginalQuery() {
        AjaxResult r = controller.pois(31.23, 121.47, 3000, "  ", 0L, "");
        assertEquals(200, r.get("code"));
        verify(roamMapper).selectPoisNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(PLAYER),
                eq(ApiRoamController.POIS_NEAR_LIMIT + 1)); // master A5.2: one extra row probes truncation
        verify(roamMapper, never()).selectPoisNearFiltered(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(),
                anyInt(), any(), any(), any());
    }

    @Test
    void filtersAreTrimmedBoundedAndPassedThrough() {
        controller.pois(31.23, 121.47, 3000, " 咖啡 ", 12L, null);
        verify(roamMapper).selectPoisNearFiltered(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(PLAYER),
                eq(ApiRoamController.POIS_NEAR_LIMIT + 1), eq("咖啡"), eq(12L), isNull());
    }

    @Test
    void likeWildcardsAreMatchedLiterally() {
        assertEquals("100\\%\\_off", ApiRoamController.boundedFilter("100%_off", 40));
        assertEquals("a\\\\b", ApiRoamController.boundedFilter("a\\b", 40));
        assertEquals(20, ApiRoamController.boundedFilter("x".repeat(50), 20).length());
        assertNull(ApiRoamController.boundedFilter("   ", 20));
        assertNull(ApiRoamController.boundedFilter(null, 20));
    }

    @Test
    void invalidCoordinatesStillFailBeforeAnyRead() {
        controller.pois(Double.NaN, 121.47, 3000, "咖啡", null, null);
        verify(roamMapper, never()).selectPoisNearFiltered(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(),
                anyInt(), any(), any(), any());
    }
}
