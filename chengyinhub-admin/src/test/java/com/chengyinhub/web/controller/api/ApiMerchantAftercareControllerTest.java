package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantAftercareRespondRequest;
import com.chengyinhub.business.domain.vo.MerchantAftercareDetailVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareListVO;
import com.chengyinhub.business.domain.vo.MerchantAftercareResponseVO;
import com.chengyinhub.business.service.IMerchantAftercareService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantAftercareControllerTest {
    private static final long PRINCIPAL = 901L;

    @Test
    void detailAndRespondForwardOnlyPrincipalAndServerRefundId() {
        IMerchantAftercareService service = mock(IMerchantAftercareService.class);
        MerchantAftercareDetailVO detail = new MerchantAftercareDetailVO();
        MerchantAftercareResponseVO response = new MerchantAftercareResponseVO();
        when(service.detail(PRINCIPAL, 41L)).thenReturn(detail);
        MerchantAftercareRespondRequest request = new MerchantAftercareRespondRequest();
        request.setDecision("AGREE");
        request.setRequestId("req-1");
        when(service.respond(PRINCIPAL, 41L, request)).thenReturn(response);
        ApiMerchantAftercareController controller = controller(service);

        AjaxResult detailResult = controller.detail(41L);
        AjaxResult responseResult = controller.respond(41L, request);

        assertEquals(detail, detailResult.get(AjaxResult.DATA_TAG));
        assertEquals(response, responseResult.get(AjaxResult.DATA_TAG));
        verify(service).detail(PRINCIPAL, 41L);
        verify(service).respond(PRINCIPAL, 41L, request);
    }

    @Test
    void listForwardsPrincipalAndPaginationWithoutAcceptingMerchantId() {
        IMerchantAftercareService service = mock(IMerchantAftercareService.class);
        MerchantAftercareListVO page = new MerchantAftercareListVO();
        when(service.list(PRINCIPAL, "PENDING", 2, 20)).thenReturn(page);

        AjaxResult result = controller(service).list("PENDING", 2, 20);

        assertEquals(page, result.get(AjaxResult.DATA_TAG));
        verify(service).list(PRINCIPAL, "PENDING", 2, 20);
    }

    @Test
    void serviceDenialKeepsItsFailClosedStatusCode() {
        IMerchantAftercareService service = mock(IMerchantAftercareService.class);
        when(service.detail(PRINCIPAL, 99L)).thenThrow(new ServiceException("售后单不存在或无权访问", 404));

        AjaxResult result = controller(service).detail(99L);

        assertEquals(404, result.get(AjaxResult.CODE_TAG));
    }

    private ApiMerchantAftercareController controller(IMerchantAftercareService service) {
        ApiMerchantAftercareController controller = new ApiMerchantAftercareController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "merchantAftercareService", service);
        return controller;
    }
}
