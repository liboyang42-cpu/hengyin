package com.chengyinhub.web.controller.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.service.DiscoveryFilterContract;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

@RestController
@RequestMapping("/api/discovery-filter")
public class ApiDiscoveryFilterController extends BaseController {
    @PostMapping("/schema")
    public AjaxResult schema(String resource) {
        try {
            return success(DiscoveryFilterContract.schema(resource));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }
}
