package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** Pins the existing block/unblock endpoints; no new route, storage or friend prerequisite. */
class ApiImBlockTargetValidationTest
{
    private IImService service;
    private ApiImController controller;

    @BeforeEach void setUp()
    {
        service = mock(IImService.class);
        controller = spy(new ApiImController());
        doReturn(42L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);
    }

    @Test void malformedBlockTargetNeverReachesPersistenceService()
    {
        for (String target : new String[]{null, "", " ", "not-a-member", "0", "-1", "9223372036854775808", "18446744073709551623", "7.9", "7e0"})
        {
            AjaxResult result = controller.block(target);
            assertEquals(400, result.get(AjaxResult.CODE_TAG));
            assertEquals("参数有误", result.get(AjaxResult.MSG_TAG));
        }
        verifyNoInteractions(service);
    }

    @Test void malformedUnblockTargetNeverReachesPersistenceService()
    {
        for (String target : new String[]{null, "", " ", "not-a-member", "0", "-1", "9223372036854775808", "18446744073709551623", "7.9", "7e0"})
        {
            AjaxResult result = controller.unblock(target);
            assertEquals(400, result.get(AjaxResult.CODE_TAG));
            assertEquals("参数有误", result.get(AjaxResult.MSG_TAG));
        }
        verifyNoInteractions(service);
    }

    @Test void validBlockStillUsesAuthenticatedActorAndExistingService()
    {
        assertEquals(200, controller.block("7").get(AjaxResult.CODE_TAG));
        verify(service).block(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void validUnblockStillUsesAuthenticatedActorAndExistingService()
    {
        assertEquals(200, controller.unblock("7").get(AjaxResult.CODE_TAG));
        verify(service).unblock(42L, 7L);
        verifyNoMoreInteractions(service);
    }
}
