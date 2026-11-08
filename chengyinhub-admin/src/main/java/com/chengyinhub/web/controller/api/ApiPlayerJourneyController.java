package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayerJourneyChoicesApplication;
import com.chengyinhub.web.service.play.PlayerJourneyMemoryApplication;
import com.chengyinhub.web.service.play.PlayerJourneySeatsApplication;
import com.chengyinhub.web.service.play.PlayerRunReportApplication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 玩家本局战报 / 模式选择 / 席位摘要 / 私人回忆的 HTTP 适配（P4/P5/P7/P8）。
 *
 * <p>四个读口都只读：不建局、不结算、不发奖、不初始化席位、不受理退款。
 * 身份与归属校验在 application 层；这里只做参数透传与匿名态识别。
 */
@RestController
@Tag(name = "玩家本局战报")
@RequestMapping("/api/play/player-journey")
public class ApiPlayerJourneyController extends BaseController {

    @Autowired
    private PlayerRunReportApplication runReport;
    @Autowired
    private PlayerJourneyChoicesApplication choices;
    @Autowired
    private PlayerJourneySeatsApplication seats;
    @Autowired
    private PlayerJourneyMemoryApplication memory;

    @Operation(summary = "本局战报(完成/结束/辅助完成/成绩/名次/已得奖励);runId 可重开历史局")
    @GetMapping("/report")
    public AjaxResult report(Long activityId, Long topicId, Long runId) {
        return runReport.report(currentMemberId(), activityId, topicId, runId);
    }

    @Operation(summary = "本主题实际支持的游玩选择与作用域")
    @GetMapping("/choices")
    public AjaxResult choices(Long activityId, Long topicId) {
        return choices.choices(currentMemberId(), activityId, topicId);
    }

    @Operation(summary = "本人已付多人票的席位摘要")
    @GetMapping("/seats")
    public AjaxResult seats(Long registrationId) {
        return seats.summary(currentMemberId(), registrationId);
    }

    @Operation(summary = "本局私人回忆(本人已完成节点与本人照片);runId 可重开历史局")
    @GetMapping("/memory")
    public AjaxResult memory(Long activityId, Long topicId, Long runId) {
        return memory.memory(currentMemberId(), activityId, topicId, runId);
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0L ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
