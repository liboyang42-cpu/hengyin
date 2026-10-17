package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.CapsuleService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
import com.chengyinhub.common.exception.ServiceException;
@RestController
@RequestMapping("/api/capsules")
public class CapsuleController extends BaseController {
    private final CapsuleService service;
    public CapsuleController(CapsuleService service) { this.service = service; }
    Long owner() { return getAppUserId(); }
    public static class Create { public String requestId; public String body; public String photoUrl; public String unlockDate; }
    @PostMapping("/create") public AjaxResult create(@RequestBody Create input) {
        Long actor = owner();
        try {
            if (input == null) return new AjaxResult(400, "缺少胶囊参数");
            return success(service.create(actor, input.requestId, input.body, input.photoUrl, input.unlockDate));
        } catch (ServiceException rejected) {
            return new AjaxResult(rejected.getCode() == null ? 400 : rejected.getCode(), rejected.getMessage());
        }
    }
    @GetMapping("/list") public AjaxResult list(Long beforeId, Integer limit) { return success(service.list(owner(), beforeId, limit)); }
    @GetMapping("/receipt") public AjaxResult receipt(@RequestParam String requestId) { return success(service.receipt(owner(), requestId)); }
    @GetMapping("/detail") public AjaxResult detail(@RequestParam Long id) { return success(service.detail(owner(), id)); }
    @PostMapping("/delete") public AjaxResult delete(@RequestParam Long id) { return success(service.delete(owner(), id)); }
}
