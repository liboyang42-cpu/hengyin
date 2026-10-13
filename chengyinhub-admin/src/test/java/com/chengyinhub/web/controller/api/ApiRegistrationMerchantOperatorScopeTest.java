package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.IMerchantRegistrationDetailReadService;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRegistrationMerchantOperatorScopeTest {
    private static final Long PRINCIPAL = 901L;
    private static final Long OWNER = 900L;

    private IMerchantAccessService access;
    private ICmsRegistrationMerchantService registrationService;
    private IViewRegistrationMerchantService viewService;
    private IMerchantRegistrationDetailReadService detailService;
    private ApiRegistrationMerchantController controller;

    @BeforeEach
    void setUp() {
        access = mock(IMerchantAccessService.class);
        registrationService = mock(ICmsRegistrationMerchantService.class);
        viewService = mock(IViewRegistrationMerchantService.class);
        detailService = mock(IMerchantRegistrationDetailReadService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(viewService.selectViewRegistrationMerchantList(any()))
                .thenReturn(Collections.<ViewRegistrationMerchant>emptyList());
        when(registrationService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.<CmsRegistrationMerchant>emptyList());
        when(registrationService.cancelByMerchant(71L, OWNER, "operator")).thenReturn(true);

        controller = new ApiRegistrationMerchantController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
            @Override public String getAppUserName() { return "operator"; }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);
        ReflectionTestUtils.setField(controller, "viewRegistrationMerchantService", viewService);
        ReflectionTestUtils.setField(controller, "merchantRegistrationDetailReadService", detailService);
    }

    @Test
    void listDetailSelectAndCancelUseResolvedOwnerScope() {
        assertEquals(200, controller.myRegistrationList("0").get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.getRegistrationDetail("71").get(AjaxResult.CODE_TAG));
        controller.select("72");
        assertEquals(200, controller.cancelRegistration("71").get(AjaxResult.CODE_TAG));

        ArgumentCaptor<ViewRegistrationMerchant> listCaptor = ArgumentCaptor.forClass(ViewRegistrationMerchant.class);
        verify(viewService).selectViewRegistrationMerchantList(listCaptor.capture());
        assertEquals(OWNER, listCaptor.getValue().getMemberId());
        verify(detailService).getRegistrationDetail(OWNER, 71L);
        ArgumentCaptor<CmsRegistrationMerchant> selectCaptor = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(registrationService).selectCmsRegistrationMerchantList(selectCaptor.capture());
        assertEquals(OWNER, selectCaptor.getValue().getMemberId());
        verify(registrationService).cancelByMerchant(71L, OWNER, "operator");
    }

    @Test
    void inactiveOrRevokedOperatorCannotReadMerchantRegistrations() {
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        AjaxResult result = controller.myRegistrationList("0");

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(viewService, never()).selectViewRegistrationMerchantList(any());
    }
}
