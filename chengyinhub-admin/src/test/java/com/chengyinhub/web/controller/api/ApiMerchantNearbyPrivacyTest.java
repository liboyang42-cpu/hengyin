package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IMerchantLeadService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantNearbyPrivacyTest {
    private static final long PRINCIPAL = 9002L;

    @Test
    void nearbyDoesNotRequestPhonesWhenPrincipalLacksSensitiveCrmPermission() {
        IMerchantLeadService leads = mock(IMerchantLeadService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.CRM_SENSITIVE_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));
        when(leads.nearby(31.2D, 121.4D, 5000, 30, false))
                .thenReturn(Collections.emptyList());
        ApiMerchantController controller = controller(leads, access);

        Object code = controller.nearby("121.4", "31.2", null, null).get("code");

        assertEquals(200, code);
        verify(leads).nearby(31.2D, 121.4D, 5000, 30, false);
    }

    @Test
    void nearbyRequestsPhonesOnlyAfterSensitiveCrmPermissionSucceeds() {
        IMerchantLeadService leads = mock(IMerchantLeadService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.CRM_SENSITIVE_READ))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, 9001L,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(leads.nearby(31.2D, 121.4D, 5000, 30, true))
                .thenReturn(Collections.emptyList());
        ApiMerchantController controller = controller(leads, access);

        Object code = controller.nearby("121.4", "31.2", null, null).get("code");

        assertEquals(200, code);
        verify(leads).nearby(eq(31.2D), eq(121.4D), eq(5000), eq(30), eq(true));
    }

    private ApiMerchantController controller(IMerchantLeadService leads, IMerchantAccessService access) {
        ApiMerchantController controller = new ApiMerchantController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "merchantLeadService", leads);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        return controller;
    }
}
