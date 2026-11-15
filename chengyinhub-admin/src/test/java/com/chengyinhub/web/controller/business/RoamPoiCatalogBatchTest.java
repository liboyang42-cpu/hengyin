package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoamPoiCatalogBatchTest {
    private RoamMapper mapper;
    private RoamPoiAdminController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(RoamMapper.class);
        controller = new RoamPoiAdminController();
        ReflectionTestUtils.setField(controller, "roamMapper", mapper);
    }

    @Test
    void endpointRequiresEditPermission() throws Exception {
        PreAuthorize pre = RoamPoiAdminController.class
                .getMethod("catalogBatchStatus", String.class, String.class, Integer.class, Boolean.class)
                .getAnnotation(PreAuthorize.class);
        assertEquals("@ss.hasPermi('business:roamPoi:edit')", pre.value());
    }

    @Test
    void rejectsBadCityStatusAndTagWithoutTouchingMapper() {
        assertEquals("城市码无效", controller.catalogBatchStatus(null, null, 1, null).get(AjaxResult.MSG_TAG));
        assertEquals("城市码无效", controller.catalogBatchStatus("S;H", null, 1, null).get(AjaxResult.MSG_TAG));
        assertEquals("状态只能是 0 下架或 1 上架", controller.catalogBatchStatus("SH", null, 2, null).get(AjaxResult.MSG_TAG));
        assertEquals("状态只能是 0 下架或 1 上架", controller.catalogBatchStatus("SH", null, null, null).get(AjaxResult.MSG_TAG));
        assertEquals("种子标签格式应为 roam:seed:v1:<id>", controller.catalogBatchStatus("SH", "x%", 1, null).get(AjaxResult.MSG_TAG));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void dryRunOnlyCounts() {
        when(mapper.countCatalogBatchTargets("SH", null, 1)).thenReturn(3);
        AjaxResult r = controller.catalogBatchStatus("sh", "", 1, true);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertEquals(3, data.get("matched"));
        assertEquals(0, data.get("updated"));
        verify(mapper, never()).updateCatalogBatchStatus(anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void updatesWithNormalizedCityAndSeedTag() {
        when(mapper.countCatalogBatchTargets("SH", "roam:seed:v1:sh-luxun-park", 0)).thenReturn(1);
        when(mapper.updateCatalogBatchStatus("SH", "roam:seed:v1:sh-luxun-park", 0)).thenReturn(1);
        AjaxResult r = controller.catalogBatchStatus("sh", "roam:seed:v1:sh-luxun-park", 0, false);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertEquals(1, data.get("updated"));
        assertTrue(data.get("dryRun").equals(false));
    }

    @Test
    void noMatchSkipsWrite() {
        when(mapper.countCatalogBatchTargets("BJ", null, 1)).thenReturn(0);
        controller.catalogBatchStatus("BJ", null, 1, null);
        verify(mapper, never()).updateCatalogBatchStatus(anyString(), isNull(), anyInt());
    }
}
