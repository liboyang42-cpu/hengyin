package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantAftercareRespondRequest;
import com.chengyinhub.business.service.IMerchantAftercareService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "商家售后")
@RequestMapping("/api/merchant/aftercare")
public class ApiMerchantAftercareController extends BaseController {
    @Autowired private IMerchantAftercareService merchantAftercareService;

    @Operation(summary = "商家退款售后列表")
    @PostMapping("/list")
    public AjaxResult list(@RequestParam(value = "bucket", required = false) String bucket,
                           @RequestParam(value = "pageNum", required = false) Integer pageNum,
                           @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantAftercareService.list(principal, bucket, pageNum, pageSize));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "商家退款售后详情")
    @PostMapping("/detail")
    public AjaxResult detail(@RequestParam(value = "refundId", required = false) Long refundId) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantAftercareService.detail(principal, refundId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "追加商家退款意见或凭证（不改变审核/打款状态）")
    @PostMapping("/respond")
    public AjaxResult respond(@RequestParam(value = "refundId", required = false) Long refundId,
                              @RequestBody(required = false) MerchantAftercareRespondRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantAftercareService.respond(principal, refundId, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try {
            Long principal = getAppUserId();
            return principal != null && principal > 0 ? principal : null;
        } catch (Exception e) {
            return null;
        }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
