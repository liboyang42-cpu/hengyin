package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.domain.dto.RegistrationRejectDTO;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驳回入口必须统一委托调配服务，并保留已处理/待处理两种返回语义。
 */
class RegistrationRejectGuardTest {

    private RegistrationRejectDTO rejectRequest(String reason) {
        RegistrationRejectDTO request = new RegistrationRejectDTO();
        request.setReason(reason);
        return request;
    }

    private IDispatchService dispatchService;
    private CmsRegistrationMerchantController controller;

    @BeforeEach
    void setUp() {
        dispatchService = mock(IDispatchService.class);
        com.chengyinhub.business.service.ICmsRegistrationMerchantService registrationService =
                mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class);
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        com.chengyinhub.business.domain.CmsRegistrationMerchant registration =
                new com.chengyinhub.business.domain.CmsRegistrationMerchant();
        registration.setId(1L);
        registration.setTopicId(1L);
        when(registrationService.selectCmsRegistrationMerchantById(1L)).thenReturn(registration);
        when(scopeAuthorizer.canViewTopic(1L)).thenReturn(true);
        controller = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(controller, "dispatchService", dispatchService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
    }

    @Test
    void rejectProcessedRegistration_returnsError() {
        when(dispatchService.rejectRegistration(1L, "地点容量已满")).thenReturn(
                DispatchResult.rejected(DispatchResult.Code.ALREADY_PROCESSED, "报名已被处理"));

        AjaxResult res = controller.reject(1L, rejectRequest("地点容量已满"));

        assertNotEquals(200, res.get(AjaxResult.CODE_TAG), "已中标报名驳回应被拒(非200)");
        verify(dispatchService).rejectRegistration(1L, "地点容量已满");
    }

    @Test
    void rejectPending_proceeds() {
        when(dispatchService.rejectRegistration(1L, "地点容量已满"))
                .thenReturn(DispatchResult.registrationRejected(1L));

        AjaxResult res = controller.reject(1L, rejectRequest("地点容量已满"));

        assertEquals(200, res.get(AjaxResult.CODE_TAG), "待调配报名可正常驳回");
        verify(dispatchService).rejectRegistration(1L, "地点容量已满");
    }

    @Test
    void rejectWithoutReason_isRefusedBeforeStateMachine() {
        AjaxResult res = controller.reject(1L, rejectRequest("  "));

        assertNotEquals(200, res.get(AjaxResult.CODE_TAG), "驳回必须留下可追溯原因");
        verify(dispatchService, org.mockito.Mockito.never()).rejectRegistration(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString());
    }

    @Test
    void rejectWithOverlongReason_isRefusedBeforeStateMachine() {
        AjaxResult res = controller.reject(1L, rejectRequest(new String(new char[201]).replace('\0', 'a')));

        assertNotEquals(200, res.get(AjaxResult.CODE_TAG), "驳回原因不能绕过前端长度上限");
        verify(dispatchService, org.mockito.Mockito.never()).rejectRegistration(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString());
    }

    @Test
    void retiredSharingRateEndpoint_isRejectedWithoutWritingRegistration() {
        ICmsRegistrationMerchantService registrationService = mock(ICmsRegistrationMerchantService.class);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);

        AjaxResult res = controller.setSharingRates();

        assertNotEquals(200, res.get(AjaxResult.CODE_TAG), "已废弃入口不得返回成功");
        org.mockito.Mockito.verify(registrationService, org.mockito.Mockito.never())
                .updateSharingRates(org.mockito.Mockito.any());
    }
}
