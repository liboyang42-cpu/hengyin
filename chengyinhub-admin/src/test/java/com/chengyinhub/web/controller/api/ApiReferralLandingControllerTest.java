package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ReferralLandingRequest;
import com.chengyinhub.business.domain.vo.GiftLandingView;
import com.chengyinhub.business.domain.vo.ReferralLandingView;
import com.chengyinhub.business.service.IReferralLandingService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落地页接口契约：匿名可读、原样返回稳定状态、不因缺登录而失败。
 * 真正的领取/注册权限仍由原有登录接口承担。
 */
class ApiReferralLandingControllerTest {

    private IReferralLandingService service;
    private ApiReferralLandingController controller;

    @BeforeEach
    void setUp() {
        service = mock(IReferralLandingService.class);
        controller = new ApiReferralLandingController(service);
    }

    @Test
    void anonymousReferralLandingReturnsStableState() {
        ReferralLandingView view = new ReferralLandingView();
        view.setState(ReferralLandingView.STATE_VALID);
        when(service.describeReferral("rf1.abcdef0123456789abcdef.1800000000.sig")).thenReturn(view);

        AjaxResult result = controller.referral(request("rf1.abcdef0123456789abcdef.1800000000.sig"));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertSame(view, result.get(AjaxResult.DATA_TAG));
        verify(service).describeReferral("rf1.abcdef0123456789abcdef.1800000000.sig");
    }

    @Test
    void missingBodyIsPassedThroughAsNullTokenNotAsServerError() {
        ReferralLandingView view = new ReferralLandingView();
        view.setState(ReferralLandingView.STATE_INVALID_TOKEN);
        when(service.describeReferral(null)).thenReturn(view);

        AjaxResult result = controller.referral(null);

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertSame(view, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void anonymousGiftLandingReturnsStableStateWithoutClaiming() {
        GiftLandingView view = new GiftLandingView();
        view.setState(GiftLandingView.STATE_VALID);
        when(service.describeGift("gt1.abcdef0123456789abcdef.0.sig")).thenReturn(view);

        AjaxResult result = controller.gift(request("gt1.abcdef0123456789abcdef.0.sig"));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertSame(view, result.get(AjaxResult.DATA_TAG));
        verify(service).describeGift("gt1.abcdef0123456789abcdef.0.sig");
    }

    private static ReferralLandingRequest request(String token) {
        ReferralLandingRequest request = new ReferralLandingRequest();
        request.setToken(token);
        return request;
    }
}
