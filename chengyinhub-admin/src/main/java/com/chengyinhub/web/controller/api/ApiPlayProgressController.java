package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.domain.dto.PreferenceTagCorrectionRequest;
import com.chengyinhub.web.service.play.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

/** HTTP adapter for player progress. Actor authentication stays at this boundary. */
@RestController
@RequestMapping("/api/play")
public class ApiPlayProgressController extends BaseController {
    @Autowired
    private PlayBasicCommandApplication playBasicCommandApplication;
    @Autowired
    private PlayBasicIntentApplication playBasicIntentApplication;
    @Autowired
    private PlayHistoryQueryApplication playHistoryQueryApplication;
    @Autowired
    private PlayEntryApplication playEntryApplication;
    @Autowired
    private PlayPhotoCommandApplication playPhotoCommandApplication;
    @Autowired
    private PlayPlayerQueryApplication playPlayerQueryApplication;
    @Autowired
    private PlayPreferenceApplication playPreferenceApplication;
    @Autowired
    private PlayPuzzleCommandApplication playPuzzleCommandApplication;

    @Operation(summary = "游玩节点列表+进度")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping("/nodes")
    public AjaxResult nodes(Long activityId, Long topicId)
    {
        return playEntryApplication.nodes(currentMemberId(), activityId, topicId);
    }

    @Operation(summary = "读取主题地点路线状态")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping("/route-state")
    public AjaxResult routeState(@RequestParam(name = "topicId", required = false) Long topicId,
            @RequestParam(name = "activityId", required = false) Long activityId,
            @RequestParam(name = "actId", required = false) Long actId)
    {
        return playEntryApplication.routeState(currentMemberId(), topicId, activityId, actId);
    }

    public AjaxResult routeState(Long topicId, Long actId) {
        return playEntryApplication.routeState(currentMemberId(), topicId, actId);
    }

    @Operation(summary = "自由定向:终章结局信(按完成顺序拼合碎片)")
    @GetMapping("/ending")
    public AjaxResult ending(Long activityId, Long topicId)
    {
        return playHistoryQueryApplication.ending(currentMemberId(), activityId, topicId);
    }

    @Operation(summary = "自由定向途中台词(NPC气泡)")
    @GetMapping("/companionLine")
    public AjaxResult companionLine(Long activityId, Long topicId)
    {
        return playPlayerQueryApplication.companionLine(currentMemberId(), activityId, topicId);
    }

    @GetMapping("/action-context")
    public AjaxResult basicActionContext(String activityId, String topicId, String nodeId) {
        return playBasicIntentApplication.basicActionContext(currentMemberId(), activityId, topicId, nodeId);
    }

    @GetMapping("/action-receipt")
    public AjaxResult basicActionReceipt(String activityId, String topicId, String nodeId, String routeActionId) {
        return playBasicIntentApplication.basicActionReceipt(currentMemberId(), activityId, topicId, nodeId, routeActionId);
    }

    @Operation(summary = "扫码打卡")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/checkin")
    @DeadlockRetry
    public AjaxResult checkin(String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long registrationId,
            String nodeId, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicCommandApplication.checkin(currentMemberId(), activityId, code, topicId, routeActionId, expectedRouteVersion, registrationId, nodeId, routeSessionId, runtimeInstance, authorityEpoch);
    }

    public AjaxResult checkin(String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long registrationId) {
        return playBasicCommandApplication.checkin(activityId, code, topicId, routeActionId, expectedRouteVersion, registrationId);
    }

    public AjaxResult checkin(String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion) {
        return playBasicCommandApplication.checkin(activityId, code, topicId, routeActionId, expectedRouteVersion);
    }

    public AjaxResult checkin(String activityId, String code, String topicId) {
        return playBasicCommandApplication.checkin(activityId, code, topicId);
    }

    @Operation(summary = "门口游戏码落地")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/scan-entry")
    public AjaxResult scanEntry(String code)
    {
        return playBasicCommandApplication.scanEntry(currentMemberId(), code);
    }

    @Operation(summary = "答题完成(文字/选项)")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/answer")
    @DeadlockRetry
    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicCommandApplication.answer(currentMemberId(), activityId, nodeId, answer, topicId, routeActionId, expectedRouteVersion, routeSessionId, runtimeInstance, authorityEpoch);
    }

    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId,
            String routeActionId, Integer expectedRouteVersion) {
        return playBasicCommandApplication.answer(activityId, nodeId, answer, topicId, routeActionId, expectedRouteVersion);
    }

    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId) {
        return playBasicCommandApplication.answer(activityId, nodeId, answer, topicId);
    }

    @Operation(summary = "城市定向谜题查看答案并辅助完成")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/puzzle/reveal")
    @DeadlockRetry
    public AjaxResult revealPuzzle(String activityId, String nodeId, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playPuzzleCommandApplication.revealPuzzle(currentMemberId(), activityId, nodeId, topicId, routeActionId, expectedRouteVersion, routeSessionId, runtimeInstance, authorityEpoch);
    }

    public AjaxResult revealPuzzle(String activityId, String nodeId, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        return error("必须提供原运行时身份");
    }

    public AjaxResult revealPuzzle(String activityId, String nodeId, String topicId) {
        return playPuzzleCommandApplication.revealPuzzle(currentMemberId(), activityId, nodeId, topicId);
    }

    @Operation(summary = "城市定向谜题逐级提示")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/puzzle/hint")
    public AjaxResult puzzleHint(String activityId, String nodeId, String topicId, Integer level)
    {
        return playPuzzleCommandApplication.puzzleHint(currentMemberId(), activityId, nodeId, topicId, level);
    }

    @Operation(summary = "下发偏好题组")
    @GetMapping("/preference/{nodeId}")
    public AjaxResult preference(@PathVariable Long nodeId, String activityId, String topicId)
    {
        return playPreferenceApplication.preference(currentMemberId(), nodeId, activityId, topicId);
    }

    @Operation(summary = "提交偏好题组")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/preference/{nodeId}/submit")
    public AjaxResult submitPreference(@PathVariable Long nodeId, @RequestBody PreferenceSubmitRequest request)
    {
        return playPreferenceApplication.submitPreference(currentMemberId(), nodeId, request);
    }

    @Operation(summary = "确认偏好标签")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/tag/{tagId}/confirm")
    public AjaxResult confirmPreferenceTag(@PathVariable Long tagId)
    {
        return playPreferenceApplication.confirmPreferenceTag(currentMemberId(), tagId);
    }

    @Operation(summary = "撤回偏好标签")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/tag/{tagId}/revoke")
    public AjaxResult revokePreferenceTag(@PathVariable Long tagId)
    {
        return playPreferenceApplication.revokePreferenceTag(currentMemberId(), tagId);
    }

    @Operation(summary = "确认前修正偏好标签档位")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/tag/{tagId}/correct")
    public AjaxResult correctPreferenceTag(@PathVariable Long tagId,
                                           @RequestBody PreferenceTagCorrectionRequest request)
    {
        return playPreferenceApplication.correctPreferenceTag(currentMemberId(), tagId, request);
    }

    @Operation(summary = "我的新生活操作系统")
    @GetMapping("/os/{topicId}")
    public AjaxResult newLifeOperatingSystem(@PathVariable Long topicId)
    {
        return playPreferenceApplication.newLifeOperatingSystem(currentMemberId(), topicId);
    }

    @Operation(summary = "App 传感器挑战完成上报")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/sensor-result")
    @DeadlockRetry
    public AjaxResult sensorResult(@RequestBody SensorResultRequest request)
    {
        return playBasicCommandApplication.sensorResult(currentMemberId(), request);
    }

    @Operation(summary = "GPS到达/到点即完成")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/arrive")
    @DeadlockRetry
    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion, String accuracy, Long routeSessionId,
            String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicCommandApplication.arrive(currentMemberId(), activityId, nodeId, longitude, latitude, topicId, routeActionId, expectedRouteVersion, accuracy, routeSessionId, runtimeInstance, authorityEpoch);
    }

    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion, String accuracy) {
        return playBasicCommandApplication.arrive(activityId, nodeId, longitude, latitude, topicId, routeActionId, expectedRouteVersion, accuracy);
    }

    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion) {
        return playBasicCommandApplication.arrive(activityId, nodeId, longitude, latitude, topicId, routeActionId, expectedRouteVersion);
    }

    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId) {
        return playBasicCommandApplication.arrive(activityId, nodeId, longitude, latitude, topicId);
    }

    @Operation(summary = "拍照打卡(提交即完成,审核后置)")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/photo")
    @DeadlockRetry
    public AjaxResult photo(String activityId, String nodeId, String picUrl, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playPhotoCommandApplication.photo(currentMemberId(), activityId, nodeId, picUrl, topicId, routeActionId, expectedRouteVersion, routeSessionId, runtimeInstance, authorityEpoch);
    }

    public AjaxResult photo(String activityId, String nodeId, String picUrl, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        return error("必须提供原运行时身份");
    }

    public AjaxResult photo(String activityId, String nodeId, String picUrl, String topicId) {
        return playPhotoCommandApplication.photo(currentMemberId(), activityId, nodeId, picUrl, topicId);
    }

    @Operation(summary = "我已通关的活动列表(广场发布选择)")
    @PostMapping("/my-completed")
    public AjaxResult myCompleted(@RequestParam(value = "for_public_reference", defaultValue = "false")
            boolean forPublicReference)
    {
        return playHistoryQueryApplication.myCompleted(currentMemberId(), forPublicReference);
    }

    public AjaxResult myCompleted()
    {
        return playHistoryQueryApplication.myCompleted(currentMemberId());
    }

    @Operation(summary = "活动排行榜(线性:完成数降序;分支:到达结局优先、用时短者靠前)")
    @GetMapping("/leaderboard")
    public AjaxResult leaderboard(String activityId, String topicId)
    {
        return playPlayerQueryApplication.leaderboard(currentMemberId(), activityId, topicId);
    }

    @Operation(summary = "我的成长+徽章")
    @PostMapping("/growth")
    public AjaxResult growth()
    {
        return playPlayerQueryApplication.growth(currentMemberId());
    }

    @PostMapping("/achievements")
    public AjaxResult achievements()
    {
        return playPlayerQueryApplication.achievements(currentMemberId());
    }

    @Operation(summary = "积分解锁节点提示")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/hint/unlock")
    public AjaxResult unlockHint(Long nodeId, String activityId, String topicId)
    {
        return playPuzzleCommandApplication.unlockHint(currentMemberId(), nodeId, activityId, topicId);
    }

    public AjaxResult unlockHint(Long nodeId)
    {
        return playPuzzleCommandApplication.unlockHint(currentMemberId(), nodeId);
    }

    private Long currentMemberId()
    {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }
}
