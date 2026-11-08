package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.PlayerAffiliateSummaryVO;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.service.IPlayerAffiliateService;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 玩家推广收入接口契约：未登录如实 401、身份只取 principal、拒绝保留 reasonCode。
 */
class ApiPlayerAffiliateControllerTest {

    private IPlayerAffiliateService service;
    private ApiPlayerAffiliateController controller;

    @BeforeEach
    void setUp() {
        service = mock(IPlayerAffiliateService.class);
        controller = spy(new ApiPlayerAffiliateController(service));
    }

    @Test
    void unauthenticatedCallerFailsWithUnauthorizedAndNeverReads() {
        doThrow(new ServiceException("获取APP用户信息异常", HttpStatus.UNAUTHORIZED))
                .when(controller).getAppUserId();

        ServiceException rejection = assertThrows(ServiceException.class, () -> controller.summary(20));

        assertEquals(Integer.valueOf(HttpStatus.UNAUTHORIZED), rejection.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void principalWithoutUserIdIsStillUnauthorized() {
        doReturn(null).when(controller).getAppUserId();

        assertThrows(ServiceException.class, () -> controller.summary(20));
        verify(service, never()).summary(anyLong(), any());
    }

    @Test
    void authenticatedCallerReadsOnlyOwnSummary() {
        doReturn(7L).when(controller).getAppUserId();
        PlayerAffiliateSummaryVO summary = new PlayerAffiliateSummaryVO();
        when(service.summary(7L, 20)).thenReturn(summary);

        AjaxResult result = controller.summary(20);

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertNotNull(result.get(AjaxResult.DATA_TAG));
        verify(service).summary(7L, 20);
    }

    @Test
    void rejectionKeepsReasonCodeAndHttpStatus() {
        AjaxResult rejected = controller.handleAffiliateRejection(
                new MarketingRevenueException("LOGIN_REQUIRED", 401, "请先登录"));

        assertEquals(401, rejected.get(AjaxResult.CODE_TAG));
        assertEquals("LOGIN_REQUIRED", rejected.get("reasonCode"));
    }
}
