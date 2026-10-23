package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientGeoService;
import com.chengyinhub.business.service.IOrientSafetyService;
import com.chengyinhub.business.service.IOrientTaskService;
import com.chengyinhub.business.service.IOrientTeamBoardService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.LimitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.Map;

/**
 * 城市定向(Doc2 F1d):坐标系归一 / AR 替代任务 / 队伍看板 / 安全闸。
 * 全部默认关(sys_config orient.* 键缺失即关);业务拒绝带机器可读 errorCode。
 */
@RestController
@Tag(name = "城市定向")
@RequestMapping("/api/orient")
public class ApiOrientController extends BaseController {

    @Autowired
    private IOrientGeoService geoService;
    @Autowired
    private IOrientTaskService taskService;
    @Autowired
    private IOrientTeamBoardService teamBoardService;
    @Autowired
    private IOrientSafetyService safetyService;

    @Operation(summary = "发布者:节点坐标按声明坐标系归一到 WGS84(apply=false 只出脏值报告)")
    @PostMapping("/geo/normalize")
    public AjaxResult normalizeGeo(@RequestBody Map<String, Object> body) {
        try {
            return AjaxResult.success(geoService.normalizeTopic(getAppUserId(),
                    Convert.toLong(body.get("topicId"), 0L), Convert.toStr(body.get("coordSys"), null),
                    Convert.toBool(body.get("apply"), false)));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "发布者:为任务配置 SCAN/PHOTO/QUIZ 替代完成方式")
    @PostMapping("/task/alternative")
    public AjaxResult configureAlternative(@RequestBody Map<String, Object> body) {
        try {
            return AjaxResult.success(taskService.configureAlternative(getAppUserId(),
                    Convert.toLong(body.get("topicId"), 0L), Convert.toLong(body.get("taskId"), 0L),
                    Convert.toStr(body.get("alternativeTaskType"), null), Convert.toStr(body.get("promptText"), null),
                    Convert.toStr(body.get("answer"), null),
                    body.get("enabled") == null ? null : Convert.toBool(body.get("enabled"), true)));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "玩家:任务完成状态与可用替代方式")
    @GetMapping("/task/state")
    public AjaxResult taskState(Long topicId, Long activityId, String playInstanceId, Long taskId) {
        try {
            return AjaxResult.success(taskService.taskState(getAppUserId(), topicId, activityId, playInstanceId, taskId));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "玩家:经替代方式完成同一逻辑任务(幂等键必填)")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/task/alternative/submit")
    public AjaxResult submitAlternative(@RequestBody Map<String, Object> body) {
        try {
            IOrientTaskService.SubmitCommand cmd = new IOrientTaskService.SubmitCommand();
            cmd.memberId = getAppUserId();
            cmd.topicId = Convert.toLong(body.get("topicId"), 0L);
            cmd.activityId = Convert.toLong(body.get("activityId"), 0L);
            cmd.playInstanceId = Convert.toStr(body.get("playInstanceId"), null);
            cmd.taskId = Convert.toLong(body.get("taskId"), 0L);
            cmd.alternativeTaskType = Convert.toStr(body.get("alternativeTaskType"), null);
            cmd.code = Convert.toStr(body.get("code"), null);
            cmd.answer = Convert.toStr(body.get("answer"), null);
            cmd.picUrl = Convert.toStr(body.get("picUrl"), null);
            cmd.coordSys = Convert.toStr(body.get("coordSys"), null);
            cmd.lng = Convert.toDouble(body.get("lng"), null);
            cmd.lat = Convert.toDouble(body.get("lat"), null);
            cmd.idempotencyKey = Convert.toStr(body.get("idempotencyKey"), null);
            Map<String, Object> data = taskService.submit(cmd);
            Object errorCode = data.get("errorCode");
            if (errorCode != null) {
                // 校验不通过也落了 attempt(重放同键得到同一结果),所以这里不是异常而是带码的失败回执。
                AjaxResult result = error("答案或扫码内容不正确");
                result.put("errorCode", errorCode);
                result.put("data", data);
                return result;
            }
            return AjaxResult.success(data);
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "发布者:审核拍照替代提交(通过才算完成)")
    @PostMapping("/task/alternative/review")
    public AjaxResult reviewAlternative(@RequestBody Map<String, Object> body) {
        try {
            return AjaxResult.success(taskService.review(getAppUserId(),
                    Convert.toLong(body.get("attemptId"), 0L), Convert.toBool(body.get("approve"), false)));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "队伍大厅:公开可加入的队伍(不含邀请码)")
    @GetMapping("/team/lobby")
    public AjaxResult teamLobby(Integer ownerType, Long ownerId) {
        try {
            return AjaxResult.success(teamBoardService.lobby(getAppUserId(), ownerType, ownerId));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "队伍详情 + 进度矩阵(仅在岗成员)")
    @GetMapping("/team/detail")
    public AjaxResult teamDetail(Long teamId) {
        try {
            return AjaxResult.success(teamBoardService.detail(getAppUserId(), teamId));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "队伍排名(按在岗成员现算)")
    @GetMapping("/team/ranking")
    public AjaxResult teamRanking(Integer ownerType, Long ownerId) {
        try {
            return AjaxResult.success(teamBoardService.ranking(getAppUserId(), ownerType, ownerId));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "参与前安全预检(保险不强制;夜间/天气/年龄监护为真阻断;SOS 恒可用)")
    @PostMapping("/safety/check")
    public AjaxResult safetyCheck(@RequestBody Map<String, Object> body) {
        try {
            return AjaxResult.success(safetyService.check(getAppUserId(),
                    Convert.toLong(body.get("topicId"), 0L), Convert.toLong(body.get("activityId"), 0L)));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    @Operation(summary = "发布者:设置夜间时段与天气停玩")
    @PostMapping("/safety/rule")
    public AjaxResult saveSafetyRule(@RequestBody Map<String, Object> body) {
        try {
            Long until = Convert.toLong(body.get("weatherBlockUntil"), null);
            return AjaxResult.success(safetyService.saveRule(getAppUserId(),
                    Convert.toLong(body.get("topicId"), 0L),
                    Convert.toInt(body.get("nightStartMinute"), null), Convert.toInt(body.get("nightEndMinute"), null),
                    Convert.toInt(body.get("weatherBlock"), 0), Convert.toStr(body.get("weatherReason"), null),
                    until == null ? null : new Date(until),
                    Convert.toInt(body.get("insuranceRequired"), null)));
        } catch (OrientRejection e) {
            return fail(e);
        }
    }

    private AjaxResult fail(OrientRejection e) {
        AjaxResult result = error(e.getMessage());
        result.put("errorCode", e.getErrorCode());
        return result;
    }
}
