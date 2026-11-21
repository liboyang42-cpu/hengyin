package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProofService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * Q1 merchant-reach: the shop's weekly proof (verified visits, plays at the node, redemptions, returning players).
 * Read only, the signed-in member's own shop only (BASIC_READ). Refusals answer HTTP status == code with errorCode.
 */
@RestController
public class ApiMerchantProofController extends BaseController {
    private final MerchantWeeklyProofService proof;

    public ApiMerchantProofController(MerchantWeeklyProofService proof) {
        this.proof = proof;
    }

    @PostMapping("/api/merchant/proof/weekly")
    public ResponseEntity<AjaxResult> weekly(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> proof.weekly(member(), body));
    }

    private Long member() {
        Long id;
        try {
            id = getAppUserId();
        } catch (Exception e) {
            throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        }
        if (id == null || id <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        return id;
    }
}
