package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.CreatorAnalyticsService;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** U08-04 creator re-registration board for the app (B8 report scoped to the caller's own topics). */
@RestController
@Tag(name = "创作者二次报名看板")
public class ApiCreatorAnalyticsController extends AppGapController {
    private final CreatorAnalyticsService analytics;

    public ApiCreatorAnalyticsController(CreatorAnalyticsService analytics) { this.analytics = analytics; }

    @Operation(summary = "本人主题的完局维护与有效复报（mature=false 时留存率未成熟，不是 0%）")
    @GetMapping("/api/creator/analytics/post-game-outcome")
    public AjaxResult postGameOutcome(@RequestParam(required = false) String cohortDate,
                                      @RequestParam(required = false) String windowDays,
                                      @RequestParam(required = false) String topicId) {
        return success(analytics.postGameOutcome(member(), cohortDate, windowDays, topicId));
    }
}
