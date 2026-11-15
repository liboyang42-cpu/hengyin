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
        return success(java.util.Map.of("visible", scopedLeaderboard.isVisible(memberId)));
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
