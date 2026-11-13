package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台：通行证整单退款；用过的须先经人工复核通过（复用同一权限点）。权限点 business:platformPass:refund 需管理员显式授权。 */
@RestController
@RequestMapping("/business/platformPass")
public class PlatformPassAdminController extends BaseController {

    private final PlatformPassService service;

    public PlatformPassAdminController(PlatformPassService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('business:platformPass:refund')")
    @Log(title = "通行证退款", businessType = BusinessType.UPDATE)
    @PostMapping("/orders/{orderNo}/refund")
    public AjaxResult refund(@PathVariable String orderNo, @RequestParam(required = false) String reason) {
        return success(service.requestRefund(orderNo, getUsername(), reason));
    }

    @PreAuthorize("@ss.hasPermi('business:platformPass:refund')")
    @PostMapping("/orders/{orderNo}/refund/refresh")
    public AjaxResult refresh(@PathVariable String orderNo) {
        return success(service.refreshXpayRefund(orderNo));
    }

    @PreAuthorize("@ss.hasPermi('business:platformPass:refund')")
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false) Integer status,
                             @RequestParam(required = false) Integer reviewStatus,
                             @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return success(service.adminOrders(status, reviewStatus, limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:platformPass:refund')")
    @Log(title = "通行证退款复核", businessType = BusinessType.UPDATE)
    @PostMapping("/orders/{orderNo}/review")
    public AjaxResult review(@PathVariable String orderNo, @RequestParam boolean approve,
                             @RequestParam(required = false) String note) {
        return success(service.reviewRefund(orderNo, getUsername(), approve, note));
    }

    @ExceptionHandler(PlatformPassException.class)
    public AjaxResult handleRejection(PlatformPassException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }
}
