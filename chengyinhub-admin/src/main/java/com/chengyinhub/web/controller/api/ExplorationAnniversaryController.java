package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.ExplorationAnniversaryService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/exploration-anniversary")
public class ExplorationAnniversaryController extends BaseController {
    private final ExplorationAnniversaryService service;
    public ExplorationAnniversaryController(ExplorationAnniversaryService service) { this.service=service; }
    // Preserve the raw query strings: Spring integer coercion must not accept exponent/fraction/plus/whitespace.
    @GetMapping public AjaxResult read(@RequestParam(required=false) String month, @RequestParam(required=false) String day,
        @RequestParam(required=false) String cursor, @RequestParam(required=false) String limit) {
        return success(service.read(getAppUserId(),month,day,cursor,limit));
    }
}
