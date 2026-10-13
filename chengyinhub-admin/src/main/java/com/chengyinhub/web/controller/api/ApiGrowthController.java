package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
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
            return error("请先登录");
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
            return error("请先登录");
        }
        return success(growthService.getLeaderboard(memberId, request));
    }

    @Operation(summary = "完成成长任务")
    @PostMapping("/mission/complete")
    public AjaxResult complete(GrowthMissionCompleteDTO request) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        return success(growthService.completeMission(memberId, request));
    }
}
