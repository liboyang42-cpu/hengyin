package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧广场(A 栈)先发后审的两处缺口:
 * AUDIT-G3-2 机审「审不了」(MSG_BUSY / MSG_UNAVAILABLE)不是违规,不得 status=2 永久下架,而是照常公开并送人工复核;
 * AUDIT-G3-3 编辑先审新正文再替换线上内容,换了的图片重新送异步检测。
 */
class ApiCreativeSquareSafetyOrderTest {

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
    private IAuditTaskService auditTaskService;

    @BeforeEach
    void setUp() {
        controller = new StubController();
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        squareService = mock(IUmsCreativeSquareService.class);
        when(squareService.updateUmsCreativeSquare(any())).thenReturn(1);
        when(squareService.insertUmsCreativeSquare(any())).thenAnswer(inv -> {
            ((UmsCreativeSquare) inv.getArgument(0)).setId(POST_ID);
            return 1;
        });
        contentSecurityService = mock(IWxContentSecurityService.class);
        auditTaskService = mock(IAuditTaskService.class);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurityService);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", mock(ICmsRegistrationService.class));
    }

    @Test
    void publishWhenDetectionBusyStaysVisibleAndIsFlaggedForReview() {
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(IWxContentSecurityService.MSG_BUSY);

        AjaxResult result = publish("正常正文");

        assertEquals(200, result.get(AjaxResult.CODE_TAG), "审不了不是违规,发布不能失败");
        assertEquals(Integer.valueOf(1), lastUpdate().getStatus(), "先发后审:照常公开,不能 status=2");
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(auditTaskService).submitSafetyHold(eq(AuditTask.BIZ_SQUARE), eq(POST_ID), eq(ME), eq("NORMAL"),
                snapshot.capture());
        assertTrue(snapshot.getValue().contains("TEXT_DETECTION_UNAVAILABLE"), "复核单要写明为什么留待复核");
    }

    @Test
    void publishWhenDetectionUnavailableIsNotTreatedAsViolation() {
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(IWxContentSecurityService.MSG_UNAVAILABLE);

        AjaxResult result = publish("正常正文");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(Integer.valueOf(1), lastUpdate().getStatus());
    }

    @Test
    void publishWithRealViolationIsStillTakenDown() {
        when(contentSecurityService.checkText(any(), isNull())).thenReturn("内容含违规信息");

        AjaxResult result = publish("违规正文");

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(Integer.valueOf(2), lastUpdate().getStatus());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void editIsCheckedBeforeItReplacesLiveContent() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post("old.jpg"));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(null);

        AjaxResult result = edit("新正文", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        InOrder order = inOrder(contentSecurityService, squareService);
        order.verify(contentSecurityService).checkText(eq("新正文"), isNull());
        order.verify(squareService).updateUmsCreativeSquare(any());
    }

    @Test
    void editWithViolatingTextNeverReachesLivePost() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post("old.jpg"));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn("内容含违规信息");

        AjaxResult result = edit("违规新正文", "new.jpg");

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(squareService, never()).updateUmsCreativeSquare(any());
        verify(contentSecurityService, never()).submitMediaCheck(anyInt(), anyLong(), anyString(), any());
    }

    @Test
    void editWhenDetectionBusyIsSavedAndFlaggedNotTakenDown() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post("old.jpg"));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(IWxContentSecurityService.MSG_BUSY);

        AjaxResult result = edit("新正文", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertNull(lastUpdate().getStatus(), "编辑不写 status,更不能因审不了置 2");
        verify(auditTaskService).submitSafetyHold(eq(AuditTask.BIZ_SQUARE), eq(POST_ID), eq(ME), eq("NORMAL"), any());
    }

    @Test
    void editWithChangedImagesResubmitsThemToAsyncCheck() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post("old.jpg"));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(null);

        edit("新正文", "new.jpg;new2.jpg");

        verify(contentSecurityService).submitMediaCheck(eq(IWxContentSecurityService.BIZ_TYPE_SQUARE), eq(POST_ID),
                eq("new.jpg;new2.jpg"), isNull());
    }

    @Test
    void editWithUnchangedImagesDoesNotResubmit() {
        when(squareService.selectUmsCreativeSquareById(POST_ID)).thenReturn(post("old.jpg"));
        when(contentSecurityService.checkText(any(), isNull())).thenReturn(null);

        edit("新正文", "old.jpg");

        verify(contentSecurityService, never()).submitMediaCheck(anyInt(), anyLong(), anyString(), any());
    }

    private AjaxResult publish(String text) {
        return controller.creativeSquareActoin(null, text, null, null, null, null, null, null, null, null, null);
    }

    private AjaxResult edit(String text, String pics) {
        return controller.creativeSquareActoin(String.valueOf(POST_ID), text, pics,
                null, null, null, null, null, null, null, null);
    }

    private UmsCreativeSquare post(String pics) {
        UmsCreativeSquare row = new UmsCreativeSquare();
        row.setId(POST_ID);
        row.setMemberId(ME);
        row.setStatus(1);
        row.setPics(pics);
        row.setContents("原文");
        return row;
    }

    private UmsCreativeSquare lastUpdate() {
        ArgumentCaptor<UmsCreativeSquare> captor = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService, atLeastOnce()).updateUmsCreativeSquare(captor.capture());
        List<UmsCreativeSquare> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }
}
