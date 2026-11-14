package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.chengyinhub.common.core.domain.AjaxResult;

/** Route eggs are decommissioned: the app's collect call gets 410 ROUTE_EGG_OFFLINE without login or service access. */
class ApiPlayerGapsControllerEggTest
{
    @Test void eggCollectIsClosedBeforeLoginAndWithoutTheService()
    {
        AjaxResult r = new ApiPlayerGapsController(null).collectEgg();
        assertEquals(410, r.get(AjaxResult.CODE_TAG));
        assertEquals("ROUTE_EGG_OFFLINE", r.get("errorCode"));
    }
}
