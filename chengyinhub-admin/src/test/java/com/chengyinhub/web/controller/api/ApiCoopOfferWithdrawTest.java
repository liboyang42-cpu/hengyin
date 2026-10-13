package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiCoopOfferWithdrawTest {
    private static final Long PRINCIPAL = 7002L;
    private static final Long OWNER = 9001L;
    private static final Long OFFER = 77L;
    private static final String REASON = "暂停营业";
    private IMerchantAccessService accessService;
    private IMerchantOfferService offerService;
    private ApiCoopController controller;

    @BeforeEach
    void setUp() {
        accessService = mock(IMerchantAccessService.class);
        offerService = mock(IMerchantOfferService.class);
        controller = controllerFor(PRINCIPAL);
    }

    private ApiCoopController controllerFor(Long memberId) {
        ApiCoopController result = new ApiCoopController() {
            @Override public Long getAppUserId() { return memberId; }
        };
        ReflectionTestUtils.setField(result, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(result, "merchantOfferService", offerService);
        return result;
    }

    private void allowMerchant() {
        when(accessService.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
    }

    private Map<String, Object> request() {
        Map<String, Object> body = new HashMap<>();
        body.put("offerId", OFFER);
        body.put("reason", REASON);
        return body;
    }

    @Test
    void postRouteMatchesExistingMiniProgramWithdrawalCall() throws Exception {
        assertArrayEquals(new String[]{"/offer/withdraw"}, ApiCoopController.class
                .getMethod("offerWithdraw", Map.class).getAnnotation(PostMapping.class).value());
    }

    @Test
    void missingOrNonPositivePrincipalCannotReachMerchantOrBusinessService() {
        for (Long memberId : new Long[]{null, 0L, -1L}) {
            AjaxResult result = controllerFor(memberId).offerWithdraw(request());
            assertEquals(500, result.get("code"));
            assertEquals("请先登录", result.get("msg"));
        }
        verifyNoInteractions(accessService, offerService);
    }

    @Test
    void loginResolutionFailureReturnsExistingLoginError() {
        ApiCoopController loggedOut = new ApiCoopController() {
            @Override public Long getAppUserId() { throw new ServiceException("未登录", 401); }
        };
        assertEquals("请先登录", loggedOut.offerWithdraw(request()).get("msg"));
        verifyNoInteractions(accessService, offerService);
    }

    @Test
    void permissionDenialPreservesCodeAndCannotWithdraw() {
        when(accessService.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        AjaxResult result = controller.offerWithdraw(request());
        assertEquals(403, result.get("code"));
        assertEquals("商家身份无效或权限不足", result.get("msg"));
        verifyNoInteractions(offerService);
    }

    @Test
    void accessOwnerMustBePresentBeforeBusinessWrite() {
        when(accessService.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, null,
                        MerchantRoleCode.MERCHANT_MANAGER));
        assertEquals("请先登录", controller.offerWithdraw(request()).get("msg"));
        verifyNoInteractions(offerService);
    }

    @Test
    void malformedOfferAndReasonShapeCannotReachBusinessWrite() {
        allowMerchant();
        assertEquals(500, controller.offerWithdraw(null).get("code"));
        for (Object id : new Object[]{null, 0L, -1L, "bad", "1.5"}) {
            Map<String, Object> body = request();
            body.put("offerId", id);
            assertEquals("缺少供给记录", controller.offerWithdraw(body).get("msg"));
        }
        Map<String, Object> body = request();
        body.put("reason", 123);
        assertEquals("退出原因格式不正确", controller.offerWithdraw(body).get("msg"));
        verifyNoInteractions(offerService);
    }

    @Test
    void requiredWithdrawalUsesResolvedOwnerAndExposesEnrollmentPaused() {
        allowMerchant();
        when(offerService.withdrawOwnChapterOffer(OFFER, OWNER, REASON)).thenReturn(true);
        Map<String, Object> body = request();
        body.put("merchantId", 12345L);
        body.put("ownerMemberId", 12345L);
        body.put("memberId", 12345L);
        AjaxResult result = controller.offerWithdraw(body);
        assertEquals(200, result.get("code"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) result.get("data")).get("enrollmentPaused"));
        verify(accessService).require(PRINCIPAL, MerchantPermission.COOP_MANAGE);
        verify(offerService).withdrawOwnChapterOffer(OFFER, OWNER, REASON);
    }

    @Test
    void optionalWithdrawalReturnsSuccessWithoutClaimingEnrollmentPaused() {
        allowMerchant();
        when(offerService.withdrawOwnChapterOffer(OFFER, OWNER, REASON)).thenReturn(false);
        AjaxResult result = controller.offerWithdraw(request());
        assertEquals(200, result.get("code"));
        assertEquals("已退出供给", result.get("msg"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) result.get("data")).get("enrollmentPaused"));
    }

    @Test
    void serviceOwnsReasonValidationAndBusinessFailureIsVisible() {
        allowMerchant();
        when(offerService.withdrawOwnChapterOffer(OFFER, OWNER, null))
                .thenThrow(new IllegalStateException("请填写1至500字的退出原因"));
        Map<String, Object> body = request();
        body.remove("reason");
        assertEquals("请填写1至500字的退出原因", controller.offerWithdraw(body).get("msg"));
        when(offerService.withdrawOwnChapterOffer(OFFER, OWNER, REASON))
                .thenThrow(new IllegalStateException("只能退出自己的章节供给"));
        AjaxResult result = controller.offerWithdraw(request());
        assertEquals(500, result.get("code"));
        assertEquals("只能退出自己的章节供给", result.get("msg"));
    }

    @Test
    void unexpectedProgrammingFailureIsNotTurnedIntoSuccess() {
        allowMerchant();
        when(offerService.withdrawOwnChapterOffer(OFFER, OWNER, REASON))
                .thenThrow(new NullPointerException("unexpected"));
        assertThrows(NullPointerException.class, () -> controller.offerWithdraw(request()));
    }
}
