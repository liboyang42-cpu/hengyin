package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;

import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.support.CheckReceiptProjection;
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
        try {
            Map<String, Object> payload = body.get("payload") instanceof Map
                    ? (Map<String, Object>) body.get("payload") : Collections.emptyMap();
            Map<String, Object> judged = runtimeService.act(getAppUserId(), longOrZero(body.get("sessionId")),
                    body.get("version") == null ? null : Convert.toInt(body.get("version"), -1),
                    Convert.toStr(body.get("idempotencyKey"), ""),
                    Convert.toStr(body.get("action"), ""), payload);
            // act 是 @Transactional,它一返回就已提交、已还连接;铸卡(最多等 4 秒)放在这之后,不占库连接。
            return success(CheckReceiptProjection.response(runtimeService.finishPendingCardMint(judged)));
        } catch (ServiceException e) {
            // 冲突回执(fresh view + encounter)在 ServiceException.data 里,交给全局处理器按 code 下发。
            throw e.setData(CheckReceiptProjection.response(e.getData()));
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
