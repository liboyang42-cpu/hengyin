package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-06:广场编辑必须区分「未提交字段」与「显式清空」。
 * 编辑态:参数非 null(含空串)= 用户明确提交,空串要真清空;参数缺失(null)= 不碰原值。
 * 发布态维持既有「非空才写」语义,老调用者不回归。
 */
class ApiCreativeSquareEditClearTest {

    private static final Long ME = 9L;
    private static final Long POST_ID = 50L;

    private static class StubController extends ApiCreativeSquareController {
        @Override
        public Long getAppUserId() {
            return ME;
        }
    }

    private StubController controller;
    private IUmsCreativeSquareService squareService;

    @BeforeEach
    void setUp() {
        controller = new StubController();
        // 手工 new 不走 @Value 默认值,旧写门禁显式开闸(与 Spring 运行时默认一致)。
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        squareService = mock(IUmsCreativeSquareService.class);
        when(squareService.updateUmsCreativeSquare(any())).thenReturn(1);
        when(squareService.insertUmsCreativeSquare(any())).thenReturn(1);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", mock(ICmsRegistrationService.class));
    }

    @Test
    void editExplicitEmptyActuallyClearsMediaAndLocation() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(ownedPost());

        AjaxResult result = controller.creativeSquareActoin(String.valueOf(POST_ID), "新正文",
                "", "", "", "", "", null, "", "", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        UmsCreativeSquare firstUpdate = firstUpdate();
        assertEquals("", firstUpdate.getPics(), "显式传空图片必须真清空");
        assertEquals("", firstUpdate.getAddress(), "显式传空地点必须真清空");
        assertEquals("", firstUpdate.getLongitude(), "显式传空经度必须真清空");
        assertEquals("", firstUpdate.getLatitude(), "显式传空纬度必须真清空");
        assertEquals("新正文", firstUpdate.getContents());
    }

    @Test
    void editOmittedFieldsAreNotTouched() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(ownedPost());

        controller.creativeSquareActoin(String.valueOf(POST_ID), "只改正文",
                null, null, null, null, null, null, null, null, null);

        UmsCreativeSquare firstUpdate = firstUpdate();
        assertNull(firstUpdate.getPics(), "未提交图片不得进入更新");
        assertNull(firstUpdate.getAddress(), "未提交地点不得进入更新");
        assertNull(firstUpdate.getLongitude(), "未提交经度不得进入更新");
        assertNull(firstUpdate.getLatitude(), "未提交纬度不得进入更新");
        assertNull(firstUpdate.getIsCrowdfunding(), "未提交众筹开关不得被重置");
        assertEquals("只改正文", firstUpdate.getContents());
    }

    @Test
    void editExplicitEmptyClearsFileAndRoutePreview() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(ownedPost());

        controller.creativeSquareActoin(String.valueOf(POST_ID), "清附件",
                null, "", null, null, null, null, null, null, "");

        UmsCreativeSquare firstUpdate = firstUpdate();
        assertEquals("", firstUpdate.getFileUrl(), "显式传空 file_url 必须真清空");
        assertEquals("", firstUpdate.getRoutePreviewImg(), "显式传空 route_preview_img 必须真清空");
    }

    @Test
    void publishModeKeepsLegacyNonEmptyWriteSemantics() {
        controller.creativeSquareActoin(null, "发布正文", "", "", "", "", "", null, "", "", null);

        ArgumentCaptor<UmsCreativeSquare> inserted = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService).insertUmsCreativeSquare(inserted.capture());
        UmsCreativeSquare row = inserted.getValue();
        assertEquals("发布正文", row.getContents());
        assertNull(row.getPics(), "发布态空图片仍不写字段");
        assertNull(row.getAddress(), "发布态空地点仍不写字段");
        assertEquals(0, row.getDelFlag());
    }

    private UmsCreativeSquare ownedPost() {
        UmsCreativeSquare row = new UmsCreativeSquare();
        row.setId(POST_ID);
        row.setMemberId(ME);
        row.setPics("old-a;old-b");
        row.setAddress("旧地点");
        row.setLongitude("1.1");
        row.setLatitude("2.2");
        return row;
    }

    private UmsCreativeSquare firstUpdate() {
        ArgumentCaptor<UmsCreativeSquare> captor = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService, atLeastOnce()).updateUmsCreativeSquare(captor.capture());
        List<UmsCreativeSquare> all = captor.getAllValues();
        return all.get(0);
    }
}
