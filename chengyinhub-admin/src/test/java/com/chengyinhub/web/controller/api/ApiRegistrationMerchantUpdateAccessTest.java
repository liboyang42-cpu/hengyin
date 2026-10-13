package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;
import com.chengyinhub.business.service.IMerchantRegistrationEditService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRegistrationMerchantUpdateAccessTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    @Test
    void updateUsesResolvedMerchantOwner() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IMerchantRegistrationEditService edit = mock(IMerchantRegistrationEditService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(edit.updateByMerchant(any(), any(), any(), any())).thenReturn(true);
        ApiRegistrationMerchantController controller = controller(access, edit);
        RegistrationMerchantDTO request = new RegistrationMerchantDTO();
        request.setId(71L);

        AjaxResult result = controller.updateRegistration(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(edit).updateByMerchant(71L, OWNER, request, "operator");
    }

    @Test
    void inactiveMerchantCannotUpdateRegistration() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IMerchantRegistrationEditService edit = mock(IMerchantRegistrationEditService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));
        ApiRegistrationMerchantController controller = controller(access, edit);
        RegistrationMerchantDTO request = new RegistrationMerchantDTO();
        request.setId(71L);

        AjaxResult result = controller.updateRegistration(request);

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(edit, never()).updateByMerchant(any(), any(), any(), any());
    }

    private ApiRegistrationMerchantController controller(IMerchantAccessService access,
                                                         IMerchantRegistrationEditService edit) {
        ApiRegistrationMerchantController controller = new ApiRegistrationMerchantController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }

            @Override
            public String getAppUserName() {
                return "operator";
            }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "merchantRegistrationEditService", edit);
        return controller;
    }
}
