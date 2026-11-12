package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.AttributionClaimRequest;
import com.chengyinhub.business.domain.dto.AttributionClaimResult;
import com.chengyinhub.business.domain.dto.ReferralTokenIssueRequest;
import com.chengyinhub.business.domain.dto.ReferralTokenView;
import com.chengyinhub.business.exception.MarketingAttributionException;
import com.chengyinhub.business.service.IMarketingAttributionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归因接口契约:未登录如实失败(401,不降级成伪身份);
 * 已登录才签发凭据/落归因;业务拒绝以可区分 reasonCode + 状态码返回。
 */
class ApiMarketingAttributionControllerTest {

    private IMarketingAttributionService service;
    private ApiMarketingAttributionController controller;

    @BeforeEach
    void setUp() {
        service = mock(IMarketingAttributionService.class);
        controller = spy(new ApiMarketingAttributionController(service));
    }

    @Test
    void unauthenticatedCallerFailsWithUnauthorizedAndNeverReachesTheService() {
        doThrow(new ServiceException("获取APP用户信息异常", HttpStatus.UNAUTHORIZED))
                .when(controller).getAppUserId();

        ServiceException issueRejection = assertThrows(ServiceException.class,
                () -> controller.issueToken(new ReferralTokenIssueRequest()));
        ServiceException claimRejection = assertThrows(ServiceException.class,
                () -> controller.claim(new AttributionClaimRequest()));

        assertEquals(Integer.valueOf(HttpStatus.UNAUTHORIZED), issueRejection.getCode());
        assertEquals(Integer.valueOf(HttpStatus.UNAUTHORIZED), claimRejection.getCode());
        verify(service, never()).issueToken(anyLong(), any());
        verify(service, never()).claim(anyLong(), any());
    }

    @Test
    void authenticatedPrincipalWithoutUserIdIsStillRejectedAsUnauthorized() {
        // 认证通过但 principal 没有 userId:按 401 处理,不得冒充 0 号用户写归因。
        doReturn(null).when(controller).getAppUserId();

        assertThrows(ServiceException.class,
                () -> controller.claim(new AttributionClaimRequest()));
        verify(service, never()).claim(anyLong(), any());
    }

    @Test
    void authenticatedCallerReceivesCredentialAndAttributionResult() {
        doReturn(7L).when(controller).getAppUserId();
        ReferralTokenView view = new ReferralTokenView();
        view.setToken("rf1.abcdef0123456789abcdef.1800000000.sig");
        when(service.issueToken(eq(7L), any())).thenReturn(view);
        when(service.claim(eq(7L), any())).thenReturn(new AttributionClaimResult());

        AjaxResult issued = controller.issueToken(new ReferralTokenIssueRequest());
        AjaxResult attributed = controller.claim(new AttributionClaimRequest());

        assertEquals(HttpStatus.SUCCESS, issued.get(AjaxResult.CODE_TAG));
        assertNotNull(issued.get(AjaxResult.DATA_TAG));
        assertEquals(HttpStatus.SUCCESS, attributed.get(AjaxResult.CODE_TAG));
        assertNotNull(attributed.get(AjaxResult.DATA_TAG));
    }

    @Test
    void rejectionKeepsDistinguishableReasonCodeAndHttpStatus() {
        doReturn(7L).when(controller).getAppUserId();
        when(service.claim(eq(7L), any())).thenThrow(
                MarketingAttributionException.conflict("ATTRIBUTION_CONFLICT", "该订单已归因到其他推广来源"));

        // 直接调用 controller 方法时 @ExceptionHandler 不参与(那由 Spring MVC 分发),业务拒绝必须原样抛出,
        // 不能被吞成 200 —— 这里先断言这一点。
        assertThrows(MarketingAttributionException.class,
                () -> controller.claim(new AttributionClaimRequest()));

        // 再单独验证处理器把 reasonCode + httpStatus 映射进响应体(映射逻辑本身可测)。
        AjaxResult rejected = controller.handleAttributionRejection(
                MarketingAttributionException.conflict("ATTRIBUTION_CONFLICT", "该订单已归因到其他推广来源"));

        assertEquals(409, rejected.get(AjaxResult.CODE_TAG));
        assertEquals("ATTRIBUTION_CONFLICT", rejected.get("reasonCode"));
    }
}
