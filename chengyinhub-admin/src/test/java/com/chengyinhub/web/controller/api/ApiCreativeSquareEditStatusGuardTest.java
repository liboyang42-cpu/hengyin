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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-03:违规帖不得靠「编辑正文」复活。
 *
 * 编辑与发布收口在同一个方法,靠 isEdit 区分。原实现两条分支都无条件 setStatus(1),
 * 平台媒体审核把 status 置 2 之后,作者改一个字保存就把 2 覆盖回 1,而编辑 payload 不带
 * pics、图片不会二次送检 —— 违规图片原样重新公开。
 *
 * 本测试钉住三条:原 2 编辑后仍 2(不写 status 列)、原 1 编辑后不被写坏、文本违规拒绝编辑(AUDIT-G3-3)。
 * 负控:把编辑分支改回无条件写 1,第一条断言必须真红。
 */
class ApiCreativeSquareEditStatusGuardTest {

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
    private IWxContentSecurityService contentSecurityService;

    @BeforeEach
    void setUp() {
        controller = new StubController();
        // 手工 new 不走 @Value 默认值,旧写门禁显式开闸(与 Spring 运行时默认一致)。
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        squareService = mock(IUmsCreativeSquareService.class);
        when(squareService.updateUmsCreativeSquare(any())).thenReturn(1);
        when(squareService.insertUmsCreativeSquare(any())).thenReturn(1);
        contentSecurityService = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurityService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", mock(ICmsRegistrationService.class));
    }

    @Test
    void editOfPlatformHiddenPostStaysHiddenWithExplicitHint() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post(2));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(null);

        AjaxResult result = controller.creativeSquareActoin(String.valueOf(POST_ID), "改一个字的正文",
                null, null, null, null, null, null, null, null, null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG), "曾被下架的内容不能回成功(否则客户端以为已恢复公开)");
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("下架"),
                "必须给出明确提示,不能静默不回显也不解释");
        for (UmsCreativeSquare update : capturedUpdates()) {
            assertNull(update.getStatus(), "编辑分支不得写 status 列 —— 原值 2 必须原样保留");
        }
    }

    @Test
    void editOfVisiblePostDoesNotTouchStatusColumn() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post(1));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(null);

        AjaxResult result = controller.creativeSquareActoin(String.valueOf(POST_ID), "正常编辑",
                null, null, null, null, null, null, null, null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG), "正常帖编辑必须成功");
        for (UmsCreativeSquare update : capturedUpdates()) {
            assertNull(update.getStatus(), "未提交审核态,status 列不得被重写(mapper 只写非 null 字段)");
        }
    }

    /**
     * AUDIT-G3-3 起口径调整:编辑提交的正文违规 → 拒绝编辑,新正文一个字都不落库,线上继续是审过的旧内容。
     * (原口径是先把违规新正文写进线上帖子再置 2 下架,违规文本会公开一个往返。)
     */
    @Test
    void editWithViolatingTextIsRejectedBeforeReplacingLiveContent() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post(1));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn("内容违规");

        AjaxResult result = controller.creativeSquareActoin(String.valueOf(POST_ID), "违规新正文",
                null, null, null, null, null, null, null, null, null);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(squareService, never()).updateUmsCreativeSquare(any());
    }

    private UmsCreativeSquare post(int status) {
        UmsCreativeSquare row = new UmsCreativeSquare();
        row.setId(POST_ID);
        row.setMemberId(ME);
        row.setStatus(status);
        row.setPics("bad-pic.jpg");
        row.setContents("原文");
        return row;
    }

    private List<UmsCreativeSquare> capturedUpdates() {
        ArgumentCaptor<UmsCreativeSquare> captor = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService, atLeastOnce()).updateUmsCreativeSquare(captor.capture());
        return captor.getAllValues();
    }
}
