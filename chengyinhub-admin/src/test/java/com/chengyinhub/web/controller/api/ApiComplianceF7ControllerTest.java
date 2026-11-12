package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IMinorGuardianService;
import com.chengyinhub.business.service.IMinorProtectionService;
import com.chengyinhub.business.service.IUserInvoiceService;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import com.chengyinhub.business.service.minor.MinorGuardianException;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** DOC2 F7 会员侧接口：开关缺省关闭时不触达服务；会员身份只取 token；业务拒绝带 errorCode。 */
class ApiComplianceF7ControllerTest {

    @Test
    void invoiceEndpointsAreClosedWhileTheSwitchIsOff() {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        ApiUserInvoiceController controller = spy(new ApiUserInvoiceController(service, switches));

        for (AjaxResult r : List.of(controller.titles(), controller.list(), controller.eligibleOrders(),
                controller.apply(Map.of("requestId", "r", "titleId", 1, "orderIds", List.of(1))),
                controller.cancel(Map.of("id", 1)), controller.detail(Map.of("id", 1)),
                controller.saveTitle(Map.of()), controller.deleteTitle(Map.of("id", 1)),
                controller.saveTitleRequest(Map.of("requestId", "save", "id", "invalid")),
                controller.titleSaveReceipt(Map.of("requestId", "save")),
                controller.originalRequest(Map.of("requestId", "r")))) {
            assertEquals(ApiUserInvoiceController.FEATURE_OFF, r.get("errorCode"));
            assertEquals(503, r.get(AjaxResult.CODE_TAG));
            assertEquals("发票功能暂未开放", r.get(AjaxResult.MSG_TAG));
            assertFalse(r.containsKey(AjaxResult.DATA_TAG));
        }
        verifyNoInteractions(service);
        verify(controller, never()).getAppUserId();
    }

    @Test
    void invoiceApplyUsesTheTokenMemberAndMapsRejectionCodes() {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        ApiUserInvoiceController controller = spy(new ApiUserInvoiceController(service, switches));
        doReturn(7L).when(controller).getAppUserId();
        when(service.apply(eq(7L), eq("r1"), eq(31L), any())).thenThrow(
                new UserInvoiceException(UserInvoiceException.ORDER_ALREADY_INVOICED, "所选订单已申请过发票"));

        AjaxResult r = controller.apply(Map.of("requestId", "r1", "titleId", 31, "orderIds", List.of(1, "2"),
                "memberId", 8));

        assertEquals(UserInvoiceException.ORDER_ALREADY_INVOICED, r.get("errorCode"));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("所选订单已申请过发票", r.get(AjaxResult.MSG_TAG));
        verify(service).apply(7L, "r1", 31L, List.of(1L, 2L));
        verify(service, never()).apply(eq(8L), any(), any(), any());
    }

    @Test
    void invoiceEndpointsRequireLogin() {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        ApiUserInvoiceController controller = spy(new ApiUserInvoiceController(service, switches));
        doThrow(new IllegalStateException("no login")).when(controller).getAppUserId();

        assertEquals(500, controller.titles().get(AjaxResult.CODE_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void enabledInvoiceReadsKeepCurrentMemberAndResponseData() {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        ApiUserInvoiceController controller = spy(new ApiUserInvoiceController(service, switches));
        doReturn(7L).when(controller).getAppUserId();
        List<Map<String, Object>> titles = List.of(Map.of("id", "31"));
        List<Map<String, Object>> applications = List.of(Map.of("id", "41"));
        when(service.listTitles(7L)).thenReturn(titles);
        when(service.listApplications(7L)).thenReturn(applications);

        AjaxResult titleResult = controller.titles();
        AjaxResult listResult = controller.list();

        assertEquals(200, titleResult.get(AjaxResult.CODE_TAG));
        assertEquals(200, listResult.get(AjaxResult.CODE_TAG));
        assertEquals("操作成功", titleResult.get(AjaxResult.MSG_TAG));
        assertEquals("操作成功", listResult.get(AjaxResult.MSG_TAG));
        assertSame(titles, titleResult.get(AjaxResult.DATA_TAG));
        assertSame(applications, listResult.get(AjaxResult.DATA_TAG));
        assertFalse(titleResult.containsKey("errorCode"));
        assertFalse(listResult.containsKey("errorCode"));
        verify(service).listTitles(7L);
        verify(service).listApplications(7L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void enabledInvoiceDetailAndCancelIgnoreBodyOwner() {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        ApiUserInvoiceController controller = spy(new ApiUserInvoiceController(service, switches));
        doReturn(7L).when(controller).getAppUserId();
        Map<String, Object> detail = Map.of("id", "41");
        Map<String, Object> cancelled = Map.of("id", "41", "status", "CANCELLED");
        when(service.applicationDetail(7L, 41L)).thenReturn(detail);
        when(service.cancel(7L, 41L)).thenReturn(cancelled);
        Map<String, Object> body = Map.of("id", 41, "memberId", 8);

        AjaxResult detailResult = controller.detail(body);
        AjaxResult cancelResult = controller.cancel(body);

        assertEquals(200, detailResult.get(AjaxResult.CODE_TAG));
        assertEquals(200, cancelResult.get(AjaxResult.CODE_TAG));
        assertSame(detail, detailResult.get(AjaxResult.DATA_TAG));
        assertSame(cancelled, cancelResult.get(AjaxResult.DATA_TAG));
        verify(service).applicationDetail(7L, 41L);
        verify(service).cancel(7L, 41L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void enabledInvoiceReadsAndWritesRejectNonPositiveTokenOwners() {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        ApiUserInvoiceController controller = spy(new ApiUserInvoiceController(service, switches));
        for (Long memberId : new Long[] {null, 0L, -1L}) {
            doReturn(memberId).when(controller).getAppUserId();
            for (AjaxResult result : List.of(controller.titles(), controller.list(),
                    controller.cancel(Map.of("id", 41, "memberId", 8)))) {
                assertEquals(500, result.get(AjaxResult.CODE_TAG));
                assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
                assertFalse(result.containsKey("errorCode"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void minorEndpointsAreClosedWhileTheSwitchIsOffAndMapCodesWhenOn() {
        IMinorGuardianService guardians = mock(IMinorGuardianService.class);
        IMinorProtectionService protection = mock(IMinorProtectionService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        ApiMinorGuardianController controller = spy(new ApiMinorGuardianController(guardians, protection, switches));
        doReturn(2L).when(controller).getAppUserId();

        assertEquals(ApiMinorGuardianController.FEATURE_OFF, controller.policy().get("errorCode"));
        assertEquals(ApiMinorGuardianController.FEATURE_OFF, controller.accept(Map.of("inviteToken", "t")).get("errorCode"));
        verifyNoInteractions(guardians, protection);

        when(switches.minorModeOn()).thenReturn(true);
        when(guardians.revoke(2L, 10L)).thenThrow(
                new MinorGuardianException(MinorGuardianException.NOT_FOUND, "授权不存在"));
        assertEquals(MinorGuardianException.NOT_FOUND, controller.revoke(Map.of("id", 10)).get("errorCode"));
        verify(guardians, never()).revoke(eq(99L), anyLong());
    }
}
