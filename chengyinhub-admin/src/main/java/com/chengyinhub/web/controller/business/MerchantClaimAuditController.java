package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.MerchantClaimDTO;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.service.IMerchantClaimBatchService;
import com.chengyinhub.business.service.IMerchantClaimService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/merchant-claim")
public class MerchantClaimAuditController extends BaseController {
    private final IMerchantClaimService claimService;
    private final IMerchantClaimBatchService batchService;
    public MerchantClaimAuditController(IMerchantClaimService claimService, IMerchantClaimBatchService batchService) {
        this.claimService = claimService;
        this.batchService = batchService;
    }

    @PreAuthorize("@ss.hasPermi('business:merchant:edit')")
    @PostMapping("/audit")
    public AjaxResult audit(@RequestBody(required = false) MerchantClaimDTO.AuditRequest request) {
        try {
            return success(claimService.audit(getUserId(), request));
        } catch (MerchantClaimException e) {
            return new AjaxResult(e.getHttpCode(), e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:merchant:edit')")
    @GetMapping("/pending")
    public AjaxResult pending(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return success(claimService.pending(limit));
    }

    @PreAuthorize("@ss.hasPermi('business:merchant:edit')")
    @PostMapping("/audit-batch")
    public AjaxResult auditBatch(@RequestBody(required = false) MerchantClaimDTO.BatchRequest request) {
        try {
            return success(batchService.auditBatch(getUserId(), request));
        } catch (MerchantClaimException e) {
            return new AjaxResult(e.getHttpCode(), e.getMessage());
        }
    }
}
