package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IMerchantDeskService;
import com.chengyinhub.business.service.support.MerchantDeskSwitch;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 开关默认关:任何端点都只回 F4_DISABLED,不解析商家身份、不碰服务。 */
class ApiMerchantDeskControllerTest {

    private ApiMerchantDeskController controller;
    private ISysConfigService config;
    private IMerchantAccessService accessService;
    private IMerchantDeskService deskService;

    @BeforeEach
    void setUp() {
        config = mock(ISysConfigService.class);
        MerchantDeskSwitch deskSwitch = new MerchantDeskSwitch();
        ReflectionTestUtils.setField(deskSwitch, "configService", config);
        accessService = mock(IMerchantAccessService.class);
        deskService = mock(IMerchantDeskService.class);
        controller = spy(new ApiMerchantDeskController());
        ReflectionTestUtils.setField(controller, "deskSwitch", deskSwitch);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "merchantDeskService", deskService);
        doReturn(501L).when(controller).getAppUserId();
    }

    @Test
    void switchMissing_returnsDisabled_withoutTouchingAccessOrService() {
        AjaxResult result = controller.saveProduct(new HashMap<>());
        assertEquals(404, result.get(AjaxResult.CODE_TAG));
        assertEquals("F4_DISABLED", result.get("errorCode"));
        verifyNoInteractions(accessService, deskService);
    }

    @Test
    void switchNotStrictTrue_staysDisabled() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("yes");
        assertEquals("F4_DISABLED", controller.stores().get("errorCode"));
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenThrow(new RuntimeException("db down"));
        assertEquals("F4_DISABLED", controller.reconciliation().get("errorCode"));
        verifyNoInteractions(deskService);
    }

    @Test
    void enabled_requiresMerchantPermission_beforeService() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        when(accessService.require(anyLong(), anyString())).thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        AjaxResult result = controller.reconciliation();
        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(accessService).require(501L, MerchantPermission.FINANCE_READ);
        verifyNoInteractions(deskService);
    }

    @Test
    void enabled_resolveCode_rejectsNodeTaskCode() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        when(accessService.require(501L, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER));
        Map<String, Object> body = new HashMap<>();
        body.put("code", "poi1.55.abcdefghijklmnop");
        body.put("mode", "MANUAL");
        AjaxResult result = controller.resolveCode(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(false, data.get("accepted"));
        assertEquals("F4_NODE_TASK_CODE_NOT_VOUCHER", data.get("errorCode"));
        verify(deskService, never()).receipt(any(), any());
    }

    @Test
    void notLoggedIn_isRejectedFirst() {
        doReturn(null).when(controller).getAppUserId();
        assertEquals(500, controller.stores().get(AjaxResult.CODE_TAG));
        verifyNoInteractions(config, accessService, deskService);
    }
}
