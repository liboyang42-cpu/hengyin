package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.impl.WorkshopService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workshop")
public class ApiWorkshopController extends BaseController {
    private final WorkshopService workshop;
    public ApiWorkshopController(WorkshopService workshop) { this.workshop = workshop; }

    @GetMapping("/capabilities")
    public AjaxResult capabilities() { return success(workshop.capabilities()); }

    @PostMapping("/v2/author/source")
    public AjaxResult authorSource(@RequestParam("source_id") long sourceId) {
        return success(workshop.authorSource(actor(),sourceId));
    }
    @PostMapping("/v2/author/versions/freeze")
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    public AjaxResult freeze(@RequestBody WorkshopService.Freeze command) { return success(workshop.freeze(actor(),command)); }

    @PostMapping("/v2/author/versions/submit")
    public AjaxResult submitVersion(@RequestBody WorkshopService.ReviewSubmission command) {
        return success(workshop.submitVersion(actor(),command));
    }
    @PostMapping("/v2/author/versions/detail")
    public AjaxResult authorVersion(@RequestParam("purchased_version_id") String version) {
        return success(workshop.authorVersion(actor(),version));
    }
    @PostMapping("/v2/author/versions/withdraw")
    public AjaxResult withdrawVersion(@RequestParam("purchased_version_id") String version) {
        return success(workshop.withdrawVersion(actor(),version));
    }
    @PostMapping("/v2/offer/list")
    public AjaxResult shelf(@RequestParam(value="before_version_id",required=false) Long before) {
        actor();return success(workshop.shelf(before));
    }

    @PostMapping("/v2/offer/detail")
    public AjaxResult offer(@RequestParam("purchased_version_id") String version) { actor(); return success(workshop.offer(version)); }

    @PostMapping("/v2/purchase/create")
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    public AjaxResult purchase(@RequestBody WorkshopService.Purchase command) { return success(workshop.purchase(actor(),command)); }

    @PostMapping("/v2/purchase/pay")
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    public AjaxResult pay(@RequestParam("order_no") String no) { return success(workshop.preparePayment(actor(),no)); }

    @PostMapping("/v2/purchase/status")
    public AjaxResult order(@RequestParam("order_no") String no) { return success(workshop.order(actor(),no)); }

    @PostMapping("/v2/purchase/reconcile")
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    public AjaxResult reconcile(@RequestParam("order_no") String no) { return success(workshop.reconcilePayment(actor(),no)); }

    @PostMapping("/v2/purchase/refund")
    @RateLimiter(time=60,count=10,limitType=LimitType.MEMBER)
    public AjaxResult refund(@RequestParam("order_no") String no) { return success(workshop.refund(actor(),no)); }

    @PostMapping("/v2/purchase/refund/reconcile")
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    public AjaxResult refundStatus(@RequestParam("order_no") String no) { return success(workshop.reconcileRefund(actor(),no)); }

    @PostMapping("/v2/purchased/list")
    public AjaxResult licenses(@RequestParam(value="before_order_line_id",required=false) Long before) {
        return success(workshop.licenses(actor(),before));
    }
    @PostMapping("/v2/purchased/detail")
    public AjaxResult license(@RequestParam("license_id") String license) { return success(workshop.license(actor(),license)); }

    @PostMapping("/v2/purchased/revoke")
    public AjaxResult revoke(@RequestParam("license_id") String license) { return success(workshop.revoke(actor(),license)); }

    @PostMapping("/v2/purchased/install/targets")
    public AjaxResult targets(@RequestParam("license_id") String license,
                             @RequestParam(value="before_draft_id",required=false) Long before) {
        return success(workshop.targets(actor(),license,before));
    }
    @PostMapping("/v2/purchased/install/submit")
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    public AjaxResult install(@RequestBody String raw) { return success(workshop.install(actor(),raw,getAppUserName())); }

    @PostMapping("/v2/purchased/install/status")
    public AjaxResult installStatus(@RequestParam("request_id") String requestId) { return success(workshop.installStatus(actor(),requestId)); }

    @PostMapping("/v2/purchased/install/history")
    public AjaxResult history(@RequestParam("license_id") String license,
                             @RequestParam(value="before_command_key",required=false) Long before) {
        return success(workshop.history(actor(),license,before));
    }
    @PostMapping("/v2/purchased/installed-text/detail")
    public AjaxResult text(@RequestParam("license_id") String license,@RequestParam("request_id") String requestId) {
        return success(workshop.installedText(actor(),license,requestId));
    }

    // Old clients hard-assert UNRESOLVED/unavailable; a separate v2 contract requires an explicit client upgrade.
    @PostMapping({"/purchased/list","/purchased/detail","/purchased/install/targets","/purchased/install/history",
            "/purchased/install/status","/purchased/install/submit","/purchased/installed-text/detail"})
    public AjaxResult legacy() { actor(); return new AjaxResult(409,"WORKSHOP_CONTRACT_UPGRADE_REQUIRED"); }

    @ExceptionHandler(ServiceException.class)
    public AjaxResult rejected(ServiceException error) { return new AjaxResult(error.getCode(),error.getMessage()); }

    private long actor() {
        Long actor = getAppUserId(); if (actor==null || actor<=0) throw new ServiceException("LOGIN_REQUIRED",401); return actor;
    }
}
