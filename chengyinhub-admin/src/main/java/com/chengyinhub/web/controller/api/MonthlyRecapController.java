package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.MonthlyRecapService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/monthly-recap")
public class MonthlyRecapController extends BaseController {
    private final MonthlyRecapService service;
    public MonthlyRecapController(MonthlyRecapService service) { this.service=service; }
    Long owner() { return getAppUserId(); }
    @GetMapping public AjaxResult read(@RequestParam String month,
        @RequestParam(defaultValue="routes") String kind, Long beforeId, Integer limit) {
        return success(service.read(owner(),month,kind,beforeId,limit));
    }
}
