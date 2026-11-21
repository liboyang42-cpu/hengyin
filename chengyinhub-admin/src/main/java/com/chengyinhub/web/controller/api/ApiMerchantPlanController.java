package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantplans.MerchantPlanService;
import com.chengyinhub.business.merchantplans.MerchantVisitBillingService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * S3a merchant plans for the signed-in member's own shop.
 * <ul>
 *   <li>{@code POST /api/merchant/plan}: offered plans, the shop's current plan and what it includes, and whether plans
 *       can be bought now ({@code salesState} OPEN or AVAILABLE_SOON). Read only; the iOS app reads this.</li>
 *   <li>{@code POST /api/merchant/plan/checkout}: merchant console (mini-program) only. 409 PLAN_SALES_CLOSED while the
 *       money switch is closed. Records an order for operations to confirm; calls no payment provider.</li>
 *   <li>{@code POST /api/merchant/plan/visit-billing}: this month's billable visits and invoices (finance read).</li>
 * </ul>
 * Refusals answer HTTP status == code with errorCode, like the other merchant routes.
 */
@RestController
public class ApiMerchantPlanController extends BaseController {
    private final MerchantPlanService plans;
    private final MerchantVisitBillingService billing;

    public ApiMerchantPlanController(MerchantPlanService plans, MerchantVisitBillingService billing) {
        this.plans = plans;
        this.billing = billing;
    }

    @PostMapping("/api/merchant/plan")
    public ResponseEntity<AjaxResult> plan(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> {
            if (body != null && !body.isEmpty()) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            return plans.merchantView(member());
        });
    }

    @PostMapping("/api/merchant/plan/checkout")
    public ResponseEntity<AjaxResult> checkout(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> plans.checkout(member(), body));
    }

    @PostMapping("/api/merchant/plan/visit-billing")
    public ResponseEntity<AjaxResult> visitBilling(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> {
            if (body != null && !body.isEmpty()) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            return billing.merchantView(member());
        });
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
