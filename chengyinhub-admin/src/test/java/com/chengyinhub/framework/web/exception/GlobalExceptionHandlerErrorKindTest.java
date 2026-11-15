package com.chengyinhub.framework.web.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** R9 BE-05: a business refusal and a system fault share code 500 but carry different errorKind markers. */
class GlobalExceptionHandlerErrorKindTest
{
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");

    @Test
    void uncodedBusinessRefusalKeepsCode500AndIsMarkedBusiness()
    {
        AjaxResult res = handler.handleServiceException(new ServiceException("活动未开始"), request);
        assertEquals(500, res.get("code"));
        assertEquals("活动未开始", res.get("msg"));
        assertEquals("BUSINESS", res.get(GlobalExceptionHandler.ERROR_KIND));
    }

    @Test
    void codedRefusalWithDataIsMarkedBusiness()
    {
        AjaxResult res = handler.handleServiceException(new ServiceException("冲突", 409).setData("world"), request);
        assertEquals(409, res.get("code"));
        assertEquals("world", res.get("data"));
        assertEquals("BUSINESS", res.get(GlobalExceptionHandler.ERROR_KIND));
    }

    @Test
    void unexpectedRuntimeFailureIsMarkedSystem()
    {
        AjaxResult res = handler.handleRuntimeException(new NullPointerException(), request);
        assertEquals(500, res.get("code"));
        assertEquals("SYSTEM", res.get(GlobalExceptionHandler.ERROR_KIND));
        assertEquals("SYSTEM", handler.handleException(new Exception("boom"), request).get(GlobalExceptionHandler.ERROR_KIND));
    }
}
