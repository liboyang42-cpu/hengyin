package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantInfoAccessTest {
    private static final Long PRINCIPAL = 7002L;
    private static final Long OWNER = 9001L;

    private IMmsMerchantService merchantService;
    private IMerchantAccessService accessService;
    private ApiMerchantController controller;

    @BeforeEach
    void setUp() {
        merchantService = mock(IMmsMerchantService.class);
        accessService = mock(IMerchantAccessService.class);
        controller = new ApiMerchantController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "sysCategoryService", mock(ISysCategoryService.class));
    }

    @Test
    void newPlayerWithoutMerchantOrOperatorReceivesExplicitNoApplication() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROFILE_WRITE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = controller.getUserInfo();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("NONE", result.get("applicationState"));
        assertNull(result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void operatorWithoutProfilePermissionDoesNotBecomeAnEmptyApplication() {
        when(accessService.resolve(PRINCIPAL)).thenReturn(new MerchantAccessContext(
                PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_CHECKIN));
        when(accessService.require(PRINCIPAL, MerchantPermission.PROFILE_WRITE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        assertEquals(403, assertThrows(ServiceException.class, controller::getUserInfo).getCode());
        verify(merchantService, never()).selectMmsMerchantById(11L);
    }

    @Test
    void pendingOwnerCanReadOwnApplicationWithoutActiveMerchantGuard() {
        MmsMerchant pending = merchant(11L, PRINCIPAL, 0L, 0L, 0);
        when(merchantService.selectMmsMerchantByMemberId(PRINCIPAL)).thenReturn(pending);

        AjaxResult result = controller.getUserInfo();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(pending, result.get(AjaxResult.DATA_TAG));
        verify(accessService, never()).require(PRINCIPAL, MerchantPermission.PROFILE_WRITE);
    }

    @Test
    void operatorUsesActiveAccessContextAndCannotSelectByPrincipalCollision() {
        MmsMerchant owned = merchant(11L, OWNER, 1L, 1L, 0);
        when(merchantService.selectMmsMerchantByMemberId(PRINCIPAL)).thenReturn(null);
        when(accessService.require(PRINCIPAL, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(
                        PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        when(accessService.resolve(PRINCIPAL)).thenReturn(new MerchantAccessContext(
                PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        when(merchantService.selectMmsMerchantById(11L)).thenReturn(owned);

        AjaxResult result = controller.getUserInfo();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(owned, result.get(AjaxResult.DATA_TAG));
        verify(merchantService).selectMmsMerchantById(11L);
    }

    @Test
    void inactiveOwnersReceiveTheirExactApplicationStateWithoutOperatorPermissions() {
        ApiMerchantOperatorController accessController = accessController();
        AjaxResult none = accessController.accessMe();
        assertEquals("NONE", ((java.util.Map<?, ?>) none.get("data")).get("applicationState"));
        long[][] states = {{0,0},{0,1},{0,2},{1,0},{1,2},{2,0},{2,1},{2,2}};
        String[] expected = {"PENDING","PENDING","DISABLED","DISABLED","DISABLED","REJECTED","REJECTED","DISABLED"};
        for (int i=0; i<states.length; i++) {
            MmsMerchant owner = merchant(11L, PRINCIPAL, states[i][0], states[i][1], 0);
            owner.setName("本人门店"); owner.setPhone("private-phone"); owner.setBusinessLicense("private-license");
            when(merchantService.selectMmsMerchantByMemberId(PRINCIPAL)).thenReturn(owner);
            AjaxResult result = accessController.accessMe();
            assertEquals(200, result.get(AjaxResult.CODE_TAG));
            java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.get("data");
            assertEquals(expected[i], data.get("applicationState"));
            assertEquals(false, data.get("active"));
            assertEquals(false, data.get("canManageOperators"));
            assertEquals(java.util.Collections.emptyList(), data.get("permissions"));
            assertEquals(MerchantRoleCode.MERCHANT_OWNER, data.get("roleCode"));
            java.util.Map<?, ?> summary = (java.util.Map<?, ?>) data.get("merchant");
            assertEquals(11L, summary.get("id")); assertNull(summary.get("phone")); assertNull(summary.get("businessLicense"));
        }
    }

    @Test
    void unavailableOrCorruptOwnerStateAndResolverErrorsRemainExplicitlyForbidden() {
        ApiMerchantOperatorController accessController = accessController();
        MmsMerchant[] invalid = {merchant(11L, OWNER, 0L,0L,0), merchant(11L,PRINCIPAL,0L,0L,2),
                merchant(11L,PRINCIPAL,null,0L,0), merchant(11L,PRINCIPAL,0L,null,0),
                merchant(11L,PRINCIPAL,3L,0L,0), merchant(11L,PRINCIPAL,0L,3L,0),
                merchant(11L,PRINCIPAL,1L,1L,0)};
        for (MmsMerchant row : invalid) {
            when(merchantService.selectMmsMerchantByMemberId(PRINCIPAL)).thenReturn(row);
            assertEquals(403, accessController.accessMe().get(AjaxResult.CODE_TAG));
        }
        when(merchantService.selectMmsMerchantByMemberId(PRINCIPAL)).thenReturn(null);
        when(accessService.resolve(PRINCIPAL)).thenThrow(new ServiceException("resolver unavailable",403));
        assertEquals(403, accessController.accessMe().get(AjaxResult.CODE_TAG));
        assertEquals(403, assertThrows(ServiceException.class, controller::getUserInfo).getCode());
    }

    private ApiMerchantOperatorController accessController() {
        ApiMerchantOperatorController api = new ApiMerchantOperatorController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(api, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(api, "merchantAccessService", accessService);
        return api;
    }

    private static MmsMerchant merchant(Long id, Long owner, Long status,
                                        Long accountStatus, Integer delFlag) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(id);
        merchant.setMemberId(owner);
        merchant.setStatus(status);
        merchant.setAccountStatus(accountStatus);
        merchant.setDelFlag(delFlag);
        return merchant;
    }
}
