package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.GameplayPresetDraftService;
import com.chengyinhub.common.core.domain.AjaxResult;

/** Test bridge to the package-private controller projection. */
public final class ApiPresetControllerAccess {
    private ApiPresetControllerAccess() {}

    public static AjaxResult missingFields(GameplayPresetDraftService.MissingFieldsException missing) {
        return ApiPresetController.missingFields(missing);
    }
}
