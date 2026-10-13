package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantClaimDTO;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.service.IMerchantClaimService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/claims")
public class ApiMerchantClaimController extends BaseController {
    private final IMerchantClaimService claimService;
    public ApiMerchantClaimController(IMerchantClaimService claimService) { this.claimService = claimService; }

    @PostMapping("/submit")
    public AjaxResult submit(@RequestBody(required = false) MerchantClaimDTO.SubmitRequest request) {
        try {
            return success(claimService.submit(getAppUserId(), request));
        } catch (MerchantClaimException e) {
            return new AjaxResult(e.getHttpCode(), e.getMessage());
        }
    }

    @GetMapping("/status")
    public AjaxResult status() {
        try {
            return success(claimService.status(getAppUserId()));
        } catch (MerchantClaimException e) {
            return new AjaxResult(e.getHttpCode(), e.getMessage());
        }
    }
}
