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
            return error("请先登录");
        }
        return success(growthService.completeMission(memberId, request));
    }
}
