package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.RoamFogCellService;
import com.chengyinhub.business.support.RoamGeohashCover;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoamFogCellsRouteTest {
    private ApiRoamController controller(RoamFogCellService service, RoamMapper roamMapper, long member) {
        ApiRoamController controller = spy(new ApiRoamController());
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "fogCellService", service);
        doReturn(member).when(controller).getAppUserId();
        return controller;
    }

    @Test
    void returnsTheCurrentPlayersCellsInTheBox() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        String inside = RoamGeohashCover.encode(31.232, 121.474, 7);
        when(mapper.selectRetainedCellsByPrefixes(eq(77L), anyCollection(), anyInt())).thenReturn(List.of(inside));
        AjaxResult result = controller(new RoamFogCellService(mapper, true), mock(RoamMapper.class), 77L)
                .fogCells(31.228, 121.468, 31.236, 121.480);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(List.of(inside), data.get("cells"));
        assertEquals(Boolean.TRUE, data.get("retained"));
        assertEquals(Boolean.FALSE, data.get("truncated"));
        verify(mapper).selectRetainedCellsByPrefixes(eq(77L), anyCollection(), anyInt());
    }

    @Test
    void refusesSignedOutInvalidAndOversizedBoxes() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        RoamFogCellService service = new RoamFogCellService(mapper, true);
        assertNotEquals(200, controller(service, mock(RoamMapper.class), 0L).fogCells(31.2, 121.4, 31.3, 121.5).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller(service, mock(RoamMapper.class), 5L).fogCells(31.3, 121.4, 31.2, 121.5).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller(service, mock(RoamMapper.class), 5L).fogCells(null, 121.4, 31.2, 121.5).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller(service, mock(RoamMapper.class), 5L).fogCells(29.0, 119.0, 33.0, 123.0).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(mapper);
    }

    @Test
    void legacyTileListReadsKeptCellsOnlyWhileKeepingIsOn() {
        RoamFogCellMapper mapper = mock(RoamFogCellMapper.class);
        RoamMapper roamMapper = mock(RoamMapper.class);
        when(mapper.selectRetainedCells(9L, 5000)).thenReturn(List.of("wtw3sjq"));
        assertEquals(List.of("wtw3sjq"), controller(new RoamFogCellService(mapper, true), roamMapper, 9L).tiles(null).get(AjaxResult.DATA_TAG));
        verify(roamMapper, never()).selectTilesByMember(any(), any());

        when(roamMapper.selectTilesByMember(9L, 5000)).thenReturn(List.of("wtw3sjr"));
        assertEquals(List.of("wtw3sjr"), controller(new RoamFogCellService(mapper, false), roamMapper, 9L).tiles(null).get(AjaxResult.DATA_TAG));
    }
}
