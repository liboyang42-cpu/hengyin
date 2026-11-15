package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.sponsoredquest.SponsoredQuestService;
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
 * S3b sponsored quests in the opt-in deals layer. Signed in only, read only. Every row carries the fixed
 * 「广告」/"Ad" label; campaigns never change the fog map, pin order or search order.
 */
@RestController
@Tag(name = "赞助任务")
@RequestMapping("/api/marketplace")
public class ApiSponsoredQuestController extends BaseController {
    @Autowired private SponsoredQuestService service;

    @Operation(summary = "地图范围内正在投放的赞助任务(始终标「广告」)")
    @GetMapping("/sponsored-quests")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    public AjaxResult sponsoredQuests(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        try {
            return success(service.liveInBox(minLat, maxLat, minLng, maxLng));
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
