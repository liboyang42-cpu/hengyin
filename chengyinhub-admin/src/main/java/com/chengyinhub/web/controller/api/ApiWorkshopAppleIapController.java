package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appleiap.WorkshopAppleIapService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workshop/v2/apple-iap")
public class ApiWorkshopAppleIapController extends BaseController {
    private final WorkshopAppleIapService service;
    public ApiWorkshopAppleIapController(WorkshopAppleIapService service) { this.service = service; }
    public record Transaction(String orderNo, String signedTransactionInfo) { }
    public record Query(String orderNo, String transactionId) { }
    public record Notification(String signedPayload) { }

    @PostMapping("/confirm")
    @RateLimiter(time=60, count=30, limitType=LimitType.MEMBER)
    public AjaxResult confirm(@RequestBody Transaction command) {
        return success(service.confirm(actor(), command.orderNo(), command.signedTransactionInfo()));
    }
    @PostMapping("/restore")
    @RateLimiter(time=60, count=30, limitType=LimitType.MEMBER)
    public AjaxResult restore(@RequestBody Transaction command) {
        return success(service.restore(actor(), command.orderNo(), command.signedTransactionInfo()));
    }
    @PostMapping("/reconcile")
    @RateLimiter(time=60, count=10, limitType=LimitType.MEMBER)
    public AjaxResult reconcile(@RequestBody Query command) {
        return success(service.reconcile(actor(), command.orderNo(), command.transactionId()));
    }
    @com.chengyinhub.common.annotation.Anonymous
    @PostMapping("/notifications")
    public AjaxResult notification(@RequestBody Notification command) {
        return success(service.notification(command.signedPayload()));
    }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> rejected(ServiceException e) {
        int code = e.getCode() == null ? 503 : e.getCode();
        return ResponseEntity.status(code).body(new AjaxResult(code, e.getMessage()));
    }
    private Long actor() {
        Long id = getAppUserId(); if (id == null || id <= 0) throw new ServiceException("LOGIN_REQUIRED", 401); return id;
    }
}
