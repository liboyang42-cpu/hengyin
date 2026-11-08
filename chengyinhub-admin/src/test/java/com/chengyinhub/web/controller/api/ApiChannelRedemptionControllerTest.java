package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ChannelReceiptRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemResult;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.IChannelRedemptionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 玩家兑换接口：未登录如实 401；兑换/回执走真实会员身份。 */
class ApiChannelRedemptionControllerTest {

    private IChannelRedemptionService service;
    private ApiChannelRedemptionController controller;

    @BeforeEach
    void setUp() {
        service = mock(IChannelRedemptionService.class);
        controller = spy(new ApiChannelRedemptionController(service));
    }

    @Test
    void unauthenticatedCallerIsRejectedAndServiceIsNeverCalled() {
        doThrow(new ServiceException("获取APP用户信息异常", HttpStatus.UNAUTHORIZED))
                .when(controller).getAppUserId();

        assertThrows(ServiceException.class, () -> controller.redeem(new ChannelRedeemRequest()));
        assertThrows(ServiceException.class, () -> controller.receipt(new ChannelReceiptRequest()));
        verify(service, never()).redeem(anyLong(), any());
        verify(service, never()).receipt(anyLong(), any());
    }

    @Test
    void principalWithoutUserIdIsStillUnauthorized() {
        doReturn(null).when(controller).getAppUserId();

        assertThrows(ServiceException.class, () -> controller.redeem(new ChannelRedeemRequest()));
        verify(service, never()).redeem(anyLong(), any());
    }

    @Test
    void authenticatedMemberReachesRedemption() {
        doReturn(7L).when(controller).getAppUserId();
        when(service.redeem(7L, null)).thenReturn(new ChannelRedeemResult());

        AjaxResult result = controller.redeem(null);

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(service).redeem(7L, null);
    }

    @Test
    void distinguishableReasonCodeIsPreserved() {
        AjaxResult rejected = controller.handleRejection(
                ChannelVoucherException.conflict("ALREADY_REDEEMED_BY_OTHER", "已被使用"));

        assertEquals(409, rejected.get(AjaxResult.CODE_TAG));
        assertEquals("ALREADY_REDEEMED_BY_OTHER", rejected.get("reasonCode"));
    }
}
