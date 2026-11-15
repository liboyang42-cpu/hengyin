package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.marketplace.MerchantDealQuotaService;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * T5 plan feature {@code dealsOffersPerMonth} for the signed-in member's own shop.
 * <ul>
 *   <li>{@code POST /api/merchant/deals/quota}: this month's quota and use (Free: 0).</li>
 *   <li>{@code POST /api/merchant/deals/submit {couponId}}: puts one own coupon forward; it waits for an operator.</li>
 * </ul>
 */
@RestController
public class ApiMerchantDealController extends BaseController {
    private final MerchantDealQuotaService deals;

    public ApiMerchantDealController(MerchantDealQuotaService deals) {
        this.deals = deals;
    }

    @PostMapping("/api/merchant/deals/quota")
    public ResponseEntity<AjaxResult> quota(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> {
            if (body != null && !body.isEmpty()) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            return deals.view(member());
        });
    }

    @PostMapping("/api/merchant/deals/submit")
    public ResponseEntity<AjaxResult> submit(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> deals.submit(member(), body));
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
