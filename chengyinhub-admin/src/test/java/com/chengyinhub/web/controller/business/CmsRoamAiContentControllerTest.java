package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.RoamAiContent;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.mapper.RoamAiContentMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.RoamAiContentAdminService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CmsRoamAiContentControllerTest {

    private RoamAiContentMapper mapper;
    private RoamMapper roamMapper;
    private CmsRoamAiContentController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(RoamAiContentMapper.class);
        roamMapper = mock(RoamMapper.class);
        RoamAiContentAdminService adminService = new RoamAiContentAdminService();
        ReflectionTestUtils.setField(adminService, "roamAiContentMapper", mapper);
        ReflectionTestUtils.setField(adminService, "roamMapper", roamMapper);
        controller = new CmsRoamAiContentController();
        ReflectionTestUtils.setField(controller, "roamAiContentAdminService", adminService);
    }

    @Test
    void addForcesPendingAuditAndStablePoiKey() {
        RoamAiContent request = new RoamAiContent();
        request.setContentType(RoamAiContent.TYPE_POI_MEANING);
        request.setPoiId(12L);
        request.setContentText("一条待审核地点说明");
        request.setAuditStatus(1);
        RoamPoi landmark = new RoamPoi();
        landmark.setId(12L);
        landmark.setType(1);
        when(roamMapper.selectPoisByIds(Collections.singletonList(12L))).thenReturn(Collections.singletonList(landmark));
        when(mapper.insertRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class))).thenReturn(1);

        controller.add(request);

        ArgumentCaptor<RoamAiContent> captor = ArgumentCaptor.forClass(RoamAiContent.class);
        verify(mapper).insertRoamAiContent(captor.capture());
        assertEquals(0, captor.getValue().getAuditStatus());
        assertEquals("poi-12", captor.getValue().getContentKey());
    }

    @Test
    void editCannotKeepPassedAuditStatusAfterTextChanged() {
        RoamAiContent stored = new RoamAiContent();
        stored.setId(9L);
        stored.setContentType(RoamAiContent.TYPE_ROUTE_STORY);
        stored.setContentKey("route-default-1");
        when(mapper.selectRoamAiContentById(9L)).thenReturn(stored);
        when(mapper.updateRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class))).thenReturn(1);

        RoamAiContent request = new RoamAiContent();
        request.setId(9L);
        request.setContentText("更新后的路线故事");
        request.setAuditStatus(1);
        controller.edit(request);

        ArgumentCaptor<RoamAiContent> captor = ArgumentCaptor.forClass(RoamAiContent.class);
        verify(mapper).updateRoamAiContent(captor.capture());
        assertEquals(0, captor.getValue().getAuditStatus());
        assertNull(captor.getValue().getContentType());
        assertNull(captor.getValue().getPoiId());
    }

    @Test
    void editCannotChangeAuditStatusWithoutAuditPermission() {
        RoamAiContent stored = new RoamAiContent();
        stored.setId(10L);
        when(mapper.selectRoamAiContentById(10L)).thenReturn(stored);
        when(mapper.updateRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class))).thenReturn(1);

        RoamAiContent request = new RoamAiContent();
        request.setId(10L);
        request.setEnabled(1);
        request.setAuditStatus(1);
        controller.edit(request);

        ArgumentCaptor<RoamAiContent> captor = ArgumentCaptor.forClass(RoamAiContent.class);
        verify(mapper).updateRoamAiContent(captor.capture());
        assertNull(captor.getValue().getAuditStatus());
    }

    @Test
    void auditRejectsUnknownStatusBeforeUpdating() {
        AjaxResult result = controller.audit(9L, 7);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void addRejectsClientReportedDistancePlaceholder() {
        RoamAiContent request = new RoamAiContent();
        request.setContentType(RoamAiContent.TYPE_ROUTE_STORY);
        request.setContentKey("route-distance");
        request.setContentText("走了 {{distanceKm}} 公里");

        AjaxResult result = controller.add(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(mapper, never()).insertRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class));
    }

    @Test
    void editRejectsClientReportedDistancePlaceholder() {
        RoamAiContent stored = new RoamAiContent();
        stored.setId(13L);
        stored.setContentType(RoamAiContent.TYPE_ROUTE_STORY);
        when(mapper.selectRoamAiContentById(13L)).thenReturn(stored);
        RoamAiContent request = new RoamAiContent();
        request.setId(13L);
        request.setContentText("走了 {{distanceKm}} 公里");

        AjaxResult result = controller.edit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(mapper, never()).updateRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class));
    }

    @Test
    void addRejectsForwardLookingRouteStoryBeforeWriting() {
        RoamAiContent request = new RoamAiContent();
        request.setContentType(RoamAiContent.TYPE_ROUTE_STORY);
        request.setContentKey("route-forward-looking");
        request.setContentText("下次建议先去公园，再去街角小店。");

        AjaxResult result = controller.add(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("足迹回顾不得包含下次、建议或先后指引", result.get(AjaxResult.MSG_TAG));
        verify(mapper, never()).insertRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class));
    }

    @Test
    void addRejectsMerchantPoiBeforeWritingContent() {
        RoamAiContent request = new RoamAiContent();
        request.setContentType(RoamAiContent.TYPE_POI_MEANING);
        request.setPoiId(22L);
        request.setContentText("不应写入商家内容");
        RoamPoi merchant = new RoamPoi();
        merchant.setId(22L);
        merchant.setType(2);
        merchant.setMerchantId(8L);
        when(roamMapper.selectPoisByIds(Collections.singletonList(22L))).thenReturn(Collections.singletonList(merchant));

        AjaxResult result = controller.add(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(mapper, never()).insertRoamAiContent(org.mockito.ArgumentMatchers.any(RoamAiContent.class));
    }
}
