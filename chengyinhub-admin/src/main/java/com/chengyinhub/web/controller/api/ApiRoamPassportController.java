package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.RoamPassportReadService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.*;

/** 登录用户自己的历史，不接收客户端 owner。 */
@RestController
@RequestMapping("/api/roam/passport")
public class ApiRoamPassportController extends BaseController {
    private final RoamPassportReadService service;
    public ApiRoamPassportController(RoamPassportReadService service) { this.service = service; }
    private Long owner() {
        try { return getAppUserId(); }
        catch (Exception e) { throw new ServiceException("请先登录", 401); }
    }
    @GetMapping("/history")
    public AjaxResult history(@RequestParam(required = false) String before, @RequestParam(required = false) Integer limit) {
        return success(service.page(owner(), before, limit));
    }
    @GetMapping("/record")
    public AjaxResult record(@RequestParam String sessionId) { return success(service.detail(owner(), sessionId)); }
    @GetMapping("/summary")
    public AjaxResult summary() { return success(service.summary(owner())); }
}
