package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.discovery.DiscoveryNearYouService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * K2 discovery (MKT-10): the personal "near you" list. Signed in, read only, same city-sized box as the offers layer.
 * It never changes the fog map, pin order or search; sponsored quests stay in their own labelled read.
 */
@RestController
@Tag(name = "附近推荐")
@RequestMapping("/api/discovery")
public class ApiDiscoveryController extends BaseController {
    @Autowired private DiscoveryNearYouService nearYouService;

    @Operation(summary = "你附近:地点、已开启的优惠与活动,每条附一个理由")
    @GetMapping("/near-you")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    public AjaxResult nearYou(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(nearYouService.nearYou(minLat, maxLat, minLng, maxLng, memberId));
        } catch (IllegalArgumentException e) {
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
