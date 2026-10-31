package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;

import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.support.CheckReceiptProjection;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** 小程序高级玩法 transport adapter；规则与状态机均在运行模块内。 */
@RestController
@Tag(name = "高级玩法")
@RequestMapping("/api/play/advanced")
public class ApiAdvancedPlayController extends BaseController {

    @Autowired
    private IAdvancedGameRuntimeService runtimeService;

    @Operation(summary = "启动或恢复高级玩法")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/start")
    public AjaxResult start(@RequestBody Map<String, Object> body) {
        try {
            return success(CheckReceiptProjection.response(runtimeService.start(getAppUserId(),
                    longOrZero(body.get("activityId")), longOrZero(body.get("topicId")),
                    longOrZero(body.get("nodeId")), nullableLong(body.get("teamId")))));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "提交高级玩法动作")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/action")
    @SuppressWarnings("unchecked")
    public AjaxResult action(@RequestBody Map<String, Object> body) {
        String action = Convert.toStr(body.get("action"), "");
        Map<String, Object> judged;
        try {
            Map<String, Object> payload = body.get("payload") instanceof Map
                    ? (Map<String, Object>) body.get("payload") : Collections.emptyMap();
            judged = runtimeService.act(getAppUserId(), longOrZero(body.get("sessionId")),
                    body.get("version") == null ? null : Convert.toInt(body.get("version"), -1),
                    Convert.toStr(body.get("idempotencyKey"), ""), action, payload);
        } catch (AiProviderOutcomeUnknownException e) {
            return AjaxResult.error(520, "照片结果还在确认中，请稍后核对").put("intentUnknown", true);
        } catch (ServiceException e) {
            // 冲突回执(fresh view + encounter)在 ServiceException.data 里,交给全局处理器按 code 下发。
            throw e.setData(CheckReceiptProjection.response(e.getData()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            /* 非拍照动作整段在 act 的一个事务里判定,这里抛出的校验拒绝已整体回滚、什么都没写 ——
               是明确的「未执行」,按 4xx 下发;原来走通用 500,客户端只能当「结果待确认」并锁住这一屏,
               玩家改了输入也交不了(实走复核:作品命名被拒后整屏卡在「原请求仍待确认」)。
               拍照动作先调外部模型再落库,仍走原来的未知态处理。 */
            if ("SUBMIT_PHOTO_CHECK".equalsIgnoreCase(action.trim())) return error(e.getMessage());
            return AjaxResult.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
        try {
            // act 返回前已提交玩法事务；铸卡放在提交之后。
            return success(CheckReceiptProjection.response(runtimeService.finishPendingCardMint(judged)));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @Autowired
    private com.chengyinhub.business.service.support.AdvancedActionReceiptReader receipts;

    @GetMapping("/receipt")
    public AjaxResult receipt(Long sessionId, String idempotencyKey) {
        return success(receipts.read(getAppUserId(), sessionId, idempotencyKey));
    }

    @Operation(summary = "读取高级玩法权威状态")
    @GetMapping("/state")
    public AjaxResult state(String sessionId) {
        try {
            return success(CheckReceiptProjection.response(runtimeService.state(getAppUserId(), Convert.toLong(sessionId, 0L))));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "高级玩法排行榜")
    @GetMapping("/leaderboard")
    public AjaxResult leaderboard(String activityId, String topicId, String nodeId) {
        try {
            return success(runtimeService.leaderboard(getAppUserId(), Convert.toLong(activityId, 0L),
                    Convert.toLong(topicId, 0L), Convert.toLong(nodeId, 0L)));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    private Long longOrZero(Object value) { return Convert.toLong(value, 0L); }

    private Long nullableLong(Object value) {
        Long converted = Convert.toLong(value, 0L);
        return converted > 0 ? converted : null;
    }
}
