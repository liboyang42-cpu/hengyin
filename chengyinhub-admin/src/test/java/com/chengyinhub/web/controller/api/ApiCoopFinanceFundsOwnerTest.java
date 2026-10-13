package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantClawbackService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 2026-09-17 总控裁定:商家分润页的余额三段只给主体本人。/api/coop/finance 带回 isOwner,
 * 门店员工(操作员)为 false;不是任何商家的员工(看的就是自己的分润)为 true;解析异常 fail-closed 为 false。
 */
class ApiCoopFinanceFundsOwnerTest {

    private static final long VIEWER = 700L;
    private static final long OWNER = 500L;

    private ApiCoopController controller;
    private IMerchantAccessService access;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiCoopController());
        doReturn(VIEWER).when(controller).getAppUserId();
        access = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "coopInviteService", mock(ICoopInviteService.class));
        ReflectionTestUtils.setField(controller, "merchantClawbackService", mock(IMerchantClawbackService.class));
    }

    @Test
    void merchantOwnerSeesStages() {
        when(access.resolve(VIEWER)).thenReturn(new MerchantAccessContext(VIEWER, 1L, VIEWER, MerchantRoleCode.MERCHANT_OWNER));
        assertEquals(Boolean.TRUE, isOwner());
    }

    @Test
    void storeStaffDoesNotSeeStages() {
        when(access.resolve(VIEWER)).thenReturn(
                new MerchantAccessContext(VIEWER, 1L, OWNER, MerchantRoleCode.MERCHANT_FINANCE));
        assertEquals(Boolean.FALSE, isOwner());
    }

    @Test
    void nonMerchantMemberSeesOwnStages() {
        when(access.resolve(VIEWER)).thenReturn(null);
        assertEquals(Boolean.TRUE, isOwner());
    }

    @Test
    void unresolvableAccessFailsClosed() {
        when(access.resolve(VIEWER)).thenThrow(new ServiceException("forbidden"));
        assertEquals(Boolean.FALSE, isOwner());
    }

    @SuppressWarnings("unchecked")
    private Object isOwner() {
        AjaxResult result = controller.finance(null);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        return ((Map<String, Object>) result.get(AjaxResult.DATA_TAG)).get("isOwner");
    }
}
