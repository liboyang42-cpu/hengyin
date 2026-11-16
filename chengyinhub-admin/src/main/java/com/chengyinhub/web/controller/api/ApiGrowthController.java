package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.exception.ErrorCode;

import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import com.chengyinhub.business.domain.vo.MemberLeaderboardVO;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 玩家成长接口。
 */
@RestController
@Tag(name = "P3玩家成长")
@RequestMapping("/api/growth")
public class ApiGrowthController extends BaseController {

    @Autowired
    private IGrowthService growthService;

    @Operation(summary = "成长中心")
    @PostMapping("/center")
    public AjaxResult center() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        return success(growthService.getCenter(memberId));
    }

    @Operation(summary = "成长中心排行榜")
    @PostMapping("/leaderboard")
    public AjaxResult leaderboard(LeaderboardQueryDTO request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        // G1 game-loop (GRO-15):好友榜 / 同区榜只多一个可选 scope;不带或不认识的 scope 仍是原来的全站榜。
        String scope = com.chengyinhub.business.growthboard.GrowthScopedLeaderboard.normalizeScope(request == null ? null : request.getScope());
        if (scope != null) return success(scopedLeaderboard.read(memberId, request, scope));
        return success(growthService.getLeaderboard(memberId, request));
    }

    @Autowired
    private com.chengyinhub.business.growthboard.GrowthScopedLeaderboard scopedLeaderboard;

    @Operation(summary = "我是否出现在好友榜与同区榜上")
    @GetMapping("/leaderboard/visibility")
    public AjaxResult boardVisibility() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        // GAME-13: homeAreaSet tells the app whether the area board can work, without sending the area itself.
        return success(java.util.Map.of("visible", scopedLeaderboard.isVisible(memberId),
                "homeAreaSet", scopedLeaderboard.hasHomeArea(memberId)));
    }

    @Autowired(required = false)
    private com.chengyinhub.business.service.IMapGeocodeService mapGeocodeService;

    /**
     * GAME-13: the player sets their home area for the area board ("use my current district"), or clears it.
     * The coordinates are used once to look up the district and are not stored; only the 6-digit district code is.
     */
    @Operation(summary = "设置或清除我的所在地区(同区榜)")
    @PostMapping("/leaderboard/home-area")
    @com.chengyinhub.common.annotation.RateLimiter(time = 60, count = 10, limitType = com.chengyinhub.common.enums.LimitType.MEMBER)
    public AjaxResult setHomeArea(Double lat, Double lng, Boolean clear) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        if (Boolean.TRUE.equals(clear)) {
            return success(java.util.Map.of("homeAreaSet", scopedLeaderboard.setHomeArea(memberId, null)));
        }
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            return refuse(ErrorCode.LOCATION_REQUIRED, "需要定位才能找到你所在的区");
        }
        java.util.Map<String, String> district = mapGeocodeService == null ? null : mapGeocodeService.resolveDistrict(lng, lat);
        if (district == null) {
            return AjaxResult.error("暂时找不到你所在的区，请稍后再试").put("retryable", true);
        }
        boolean set = scopedLeaderboard.setHomeArea(memberId, Long.valueOf(district.get("areaCode")));
        return success(java.util.Map.of("homeAreaSet", set, "areaName", district.get("areaName")));
    }

    @Operation(summary = "开启或关闭:在好友榜与同区榜上显示我")
    @PostMapping("/leaderboard/visibility")
    public AjaxResult setBoardVisibility(Boolean visible) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        if (visible == null) return error("visible 必填");
        return success(java.util.Map.of("visible", scopedLeaderboard.setVisible(memberId, visible)));
    }

    @Autowired
    private com.chengyinhub.business.appgaps.GrowthAnonymousLeaderboard anonymousLeaderboard;

    @Operation(summary = "成长中心匿名排行榜（App）")
    @PostMapping("/leaderboard/anonymous")
    public AjaxResult anonymousLeaderboard(LeaderboardQueryDTO request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        return success(anonymousLeaderboard.read(memberId, request));
    }

    @GetMapping("/leaderboard")
    public AjaxResult publicLeaderboard(LeaderboardQueryDTO request) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { memberId = 0L; }
        MemberLeaderboardVO board = growthService.getLeaderboard(memberId, request);
        if (memberId != null && memberId > 0) return success(board);
        // 2026-11-14：公开榜只含展示字段，不返回用户标识或“我的”排名。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", board.getMetric());
        result.put("period", board.getPeriod());
        result.put("list", board.getList().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", row.getRank());
            item.put("nickname", row.getNickname());
            item.put("avatar", row.getAvatar());
            item.put("score", row.getScore());
            return item;
        }).toList());
        return success(result);
    }

    @Operation(summary = "完成成长任务")
    @PostMapping("/mission/complete")
    public AjaxResult complete(GrowthMissionCompleteDTO request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        }
        return success(growthService.completeMission(memberId, request));
    }
}
