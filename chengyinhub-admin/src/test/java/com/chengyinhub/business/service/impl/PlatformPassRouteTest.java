package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.IRefundService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PPO / RPP 号段只归通行证；缺少承载方时回 FAIL，绝不落到 registration / 退款单链。 */
class PlatformPassRouteTest {
    private final ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
    private final IRefundService refunds = mock(IRefundService.class);

    private WechatPaymentCallbackRouter router() {
        return new WechatPaymentCallbackRouter(mock(IClubMembershipService.class), mock(IMerchantCommerceService.class),
                mock(ICoopInviteService.class), registration, refunds, mock(IMarketingProductPurchaseService.class));
    }

    private static Map<String, String> paid() {
        Map<String, String> f = new HashMap<>();
        f.put("out_trade_no", "PPO20261110000000000000000000001");
        f.put("trade_state", "SUCCESS");
        f.put("total", "29900");
        return f;
    }

    @Test
    void ppoRoutesToPlatformPass() {
        WechatPaymentCallbackRouter router = router();
        PlatformPassService pass = mock(PlatformPassService.class);
        when(pass.handleJsapiPaid(any())).thenReturn(true);
        router.setPlatformPass(pass);
        WechatPaymentCallbackRouter.Outcome outcome = router.payment(paid());
        assertEquals("platform-pass", outcome.route());
        assertEquals("SUCCESS", outcome.result());
        verify(registration, never()).handleWechatPayCallback(any());
    }

    @Test
    void ppoWithoutOwnerFailsInsteadOfFallingThrough() {
        WechatPaymentCallbackRouter.Outcome outcome = router().payment(paid());
        assertEquals("platform-pass", outcome.route());
        assertEquals("FAIL", outcome.result());
        verify(registration, never()).handleWechatPayCallback(any());
    }

    @Test
    void rppRefundRoutesToPlatformPassNotRegistrationChain() {
        WechatPaymentCallbackRouter router = router();
        PlatformPassService pass = mock(PlatformPassService.class);
        when(pass.handleJsapiRefund(any())).thenReturn(true);
        router.setPlatformPass(pass);
        Map<String, String> f = new HashMap<>();
        f.put("outRefundNo", "RPP20261110000000000000000000001");
        assertTrue(router.refund(f));
        verify(refunds, never()).handleRefundNotify(any());
        assertFalse(router().refund(f), "缺少承载方时不认领为成功");
    }
}
