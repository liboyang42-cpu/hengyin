package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IRoamPoiApplicationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantNodeOfflineAccessTest {
    private static final Long PRINCIPAL = 88L;
    private static final Long OWNER = 71L;

    private IMerchantAccessService accessService;
    private IRoamPoiApplicationService nodeService;
    private ApiMerchantNodeController controller;

    @BeforeEach
    void setUp() {
        accessService = mock(IMerchantAccessService.class);
        nodeService = mock(IRoamPoiApplicationService.class);
        controller = new ApiMerchantNodeController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "roamPoiApplicationService", nodeService);
    }

    @Test
    void disabledMerchantCannotBringNodeBackOnline() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家账号已停用", 403));

        AjaxResult result = controller.offline(301L, 1);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(nodeService, never()).setOwnedNodeStatus(anyLong(), anyLong(), anyInt());
    }

    @Test
    void operatorWithoutProjectPermissionCannotChangeNodeStatus() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = controller.offline(301L, 1);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(nodeService, never()).setOwnedNodeStatus(anyLong(), anyLong(), anyInt());
    }

    @Test
    void disabledMerchantMayStillTakeOwnedNodeOffline() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家账号已停用", 403));
        when(nodeService.setOwnedNodeStatus(PRINCIPAL, 301L, 0)).thenReturn(true);

        AjaxResult result = controller.offline(301L, 0);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(nodeService).setOwnedNodeStatus(PRINCIPAL, 301L, 0);
    }

    @Test
    void managerChangesStatusThroughOwnerMemberIdentity() {
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(nodeService.setOwnedNodeStatus(OWNER, 301L, 0)).thenReturn(true);

        AjaxResult result = controller.offline(301L, 0);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(nodeService).setOwnedNodeStatus(OWNER, 301L, 0);
    }
}
