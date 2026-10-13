package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IServiceStatusFeedService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Anonymous
@RestController
@RequestMapping("/api/status")
public class ApiServiceStatusController extends BaseController {
    private final IServiceStatusFeedService statusFeedService;
    public ApiServiceStatusController(IServiceStatusFeedService statusFeedService) {
        this.statusFeedService = statusFeedService;
    }
    @GetMapping
    public AjaxResult get() { return success(statusFeedService.read()); }
}
