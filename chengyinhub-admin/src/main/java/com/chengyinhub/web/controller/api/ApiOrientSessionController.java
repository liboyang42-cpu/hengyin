package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientSessionService;
import com.chengyinhub.business.service.orienteering.EventScoreVersioning;
import com.chengyinhub.business.service.support.OrientSessionException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Doc2 F1a 城市定向(product_type=1)会话生命周期:prepare → start → task/{id}/result → abandon。
 *
 * <p>sys_config {@code feature.flag.orientSession} 非 true 时全部返回 err=ORIENT_SESSION_DISABLED,
 * 既有 /api/play/* 推进入口不受影响。时间、计时与终局只认服务端;客户端不传任何时间。
 * 每个写请求必须带 idempotencyKey:网络结果未知时用同一个键重发,或 GET /receipt 读回同一份回执。
 * 失败时 AjaxResult 带机器码 {@code err}。
 */
@RestController
@Tag(name = "城市定向会话")
@RequestMapping("/api/play/orient/session")
public class ApiOrientSessionController extends BaseController {

    @Autowired
    private IOrientSessionService orientSessionService;

    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "准备:取得/建立本人本场的路线局,返回需确认的规则版本")
    @PostMapping("/prepare")
    public AjaxResult prepare(Long topicId, Long activityId, String idempotencyKey) {
        return call(memberId -> orientSessionService.prepare(memberId, topicId, activityId, idempotencyKey));
    }

    @Operation(summary = "开赛:首次需确认规则版本并由服务端起表;重进续用原计时")
    @PostMapping("/start")
    public AjaxResult start(Long sessionId, Long ackConfigVersion, String idempotencyKey) {
        return call(memberId -> orientSessionService.start(memberId, sessionId, ackConfigVersion, idempotencyKey));
    }

    @Operation(summary = "放弃本局(终态,幂等)")
    @PostMapping("/abandon")
    public AjaxResult abandon(Long sessionId, String idempotencyKey) {
        return call(memberId -> orientSessionService.abandon(memberId, sessionId, idempotencyKey));
    }

    @Operation(summary = "记录任务结果:同一逻辑任务本局只记一次,alternativeTaskType 可为 SCAN/PHOTO/QUIZ")
    @PostMapping("/task/{taskId}/result")
    public AjaxResult taskResult(@PathVariable("taskId") Long taskId, Long sessionId, String alternativeTaskType,
                                 String idempotencyKey) {
        return call(memberId -> orientSessionService.taskResult(memberId, sessionId, taskId, alternativeTaskType,
                idempotencyKey));
    }

    @Operation(summary = "只读查询本人已有路线局;多局时由玩家明确选择")
    @GetMapping("/current")
    public AjaxResult current(Long topicId, Long activityId) {
        return call(memberId -> orientSessionService.current(memberId, topicId, activityId));
    }

    @Operation(summary = "读取本局阶段与服务端计时")
    @GetMapping
    public AjaxResult view(Long sessionId) {
        return call(memberId -> orientSessionService.view(memberId, sessionId));
    }

    @Operation(summary = "按幂等键读回某次写请求的原回执")
    @GetMapping("/receipt")
    public AjaxResult receipt(Long sessionId, String idempotencyKey) {
        return call(memberId -> {
            Map<String, Object> found = orientSessionService.receipt(memberId, sessionId, idempotencyKey);
            Map<String, Object> checked = new EventScoreVersioning().recoverFromSessionReceipt(
                    memberId, sessionId, idempotencyKey, (m, s, k) -> found);
            if (checked == null) {
                throw new OrientSessionException(EventScoreVersioning.SESSION_RECEIPT_NOT_FOUND, 404, "没有这次请求的回执");
            }
            return checked;
        });
    }

    private AjaxResult call(java.util.function.Function<Long, Map<String, Object>> action) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) {
            return AjaxResult.error(401, "请先登录").put("err", "ORIENT_LOGIN_REQUIRED");
        }
        try {
            return success(OrientPlayerWire.response(action.apply(memberId)));
        } catch (OrientSessionException e) {
            AjaxResult result = AjaxResult.error(e.getHttpCode(), e.getMessage()).put("err", e.getErr());
            if (e.getData() != null) result.put(AjaxResult.DATA_TAG, OrientPlayerWire.response(e.getData()));
            return result;
        }
    }
}
