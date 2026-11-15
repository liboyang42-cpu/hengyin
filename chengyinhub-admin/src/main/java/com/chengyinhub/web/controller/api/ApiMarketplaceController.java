package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.marketplace.DealClaimException;
import com.chengyinhub.business.marketplace.MarketplaceDealService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R1 marketplace: the opt-in deals layer on the roam map (MKT-04). Signed in only.
 * Deals are an overlay the player switches on; they never change the fog map or pin order.
 */
@RestController
@Tag(name = "城市集市优惠")
@RequestMapping("/api/marketplace")
public class ApiMarketplaceController extends BaseController {
    @Autowired private MarketplaceDealService dealService;

    @Operation(summary = "地图范围内可领的商家优惠(后台逐张开启)")
    @GetMapping("/deals")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    public AjaxResult deals(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(dealService.dealsInBox(minLat, maxLat, minLng, maxLng, memberId));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "领取一张地图优惠(每人每券一次,按库存)")
    @PostMapping("/deals/{couponId}/claim")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult claim(@PathVariable("couponId") Long couponId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(dealService.claim(memberId, couponId));
        } catch (DealClaimException e) {
            return error(e.getMessage());
        }
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id != null && id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }
}
