package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.web.service.play.PlayHelpApplication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * BE-16 提示阶梯的玩家 transport adapter。身份只看登录态;原局归属、节点范围与冻结层级都在
 * {@link PlayHelpApplication} 内判定,客户端参数不参与授权。
 *
 * <p>业务拒绝在事务之外映射文案:应用层的 use 是外层事务,异常必须穿过事务边界再在这里转成 AjaxResult。
 * 旧入口 /api/play/puzzle/hint、/api/play/puzzle/unlock-hint 保持原语义,本控制器不替换它们。
 */
@RestController
@Tag(name = "游玩帮助层级")
@RequestMapping("/api/play/help")
public class ApiPlayHelpController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(ApiPlayHelpController.class);

    @Autowired
    private PlayHelpApplication playHelpApplication;


    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;

    @Operation(summary = "真实合作局共享帮助、跳过与计时成绩读回")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/shared")
    public AjaxResult shared(@RequestBody Map<String, Object> body) {
        try {
            if (body == null) return error("参数有误");
            return success(gameSessionRuntimeService.cooperativePuzzleHelp(currentMemberId(),
                    Convert.toLong(body.get("activityId")), Convert.toLong(body.get("gameSessionId")),
                    Convert.toLong(body.get("nodeId")), Convert.toStr(body.get("runtimeInstance")),
                    Convert.toInt(body.get("authorityEpoch")), Convert.toStr(body.get("operation"), "VIEW"),
                    Convert.toInt(body.get("level")), Convert.toStr(body.get("requestId")), Convert.toStr(body.get("capabilities"))));
        } catch (com.chengyinhub.business.service.GameSessionException refused) {
            return AjaxResult.error(409, refused.getMessage()).put("reasonCode", refused.getErrorCode());
        } catch (RuntimeException refused) {
            return refusal("shared", null, null, refused);
        }
    }

    @Operation(summary = "本局可用的帮助层级、每层免费与代价（不含任何提示正文）")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping
    public AjaxResult view(String runId, String nodeId) {
        try {
            return success(playHelpApplication.view(currentMemberId(), runId, nodeId));
        } catch (RuntimeException refused) {
            return refusal("view", runId, nodeId, refused);
        }
    }

    @Operation(summary = "使用获准的帮助层级;同局同级只落一次账,重看不重复扣")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/use")
    public AjaxResult use(@RequestBody Map<String, Object> body) {
        Object rawLevel = body == null ? null : body.get("level");
        String runId = body == null ? null : Convert.toStr(body.get("runId"), "");
        String nodeId = body == null ? null : Convert.toStr(body.get("nodeId"), "");
        try {
            return success(playHelpApplication.use(
                    currentMemberId(),
                    runId,
                    nodeId,
                    rawLevel == null ? null : Convert.toInt(rawLevel, -1),
                    body == null ? null : Convert.toStr(body.get("action"), ""),
                    body == null ? null : Convert.toStr(body.get("requestId"), "")));
        } catch (RuntimeException refused) {
            return refusal("use", runId, nodeId, refused);
        }
    }

    @Operation(summary = "跳过本题：本题记 0 分、只留辅助完成，不伪造答对/到达/扫码/支付")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/skip")
    public AjaxResult skip(@RequestBody Map<String, Object> body) {
        String runId = body == null ? null : Convert.toStr(body.get("runId"), "");
        String nodeId = body == null ? null : Convert.toStr(body.get("nodeId"), "");
        try {
            return success(playHelpApplication.skip(currentMemberId(), runId, nodeId,
                    body == null ? null : Convert.toStr(body.get("requestId"), "")));
        } catch (RuntimeException refused) {
            return refusal("skip", runId, nodeId, refused);
        }
    }

    @Operation(summary = "重进后的只读成绩明细：base/加成/提示扣分/跳过/净分 + 积分消费 + 层使用")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping("/receipt")
    public AjaxResult receipt(String runId, String nodeId) {
        try {
            return success(playHelpApplication.receipt(currentMemberId(), runId, nodeId));
        } catch (RuntimeException refused) {
            return refusal("receipt", runId, nodeId, refused);
        }
    }

    @Operation(summary = "高级玩法局的只读成绩明细（同一份计算）")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping("/advanced/receipt")
    public AjaxResult receiptAdvanced(String sessionId, String nodeId) {
        try {
            return success(playHelpApplication.receiptAdvanced(currentMemberId(), sessionId, nodeId));
        } catch (RuntimeException refused) {
            return refusal("receipt-advanced", sessionId, nodeId, refused);
        }
    }

    @Operation(summary = "高级玩法局的帮助层级与剩余额度（同一套规则，仅原局身份来源不同）")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @GetMapping("/advanced")
    public AjaxResult viewAdvanced(String sessionId, String nodeId) {
        try {
            return success(playHelpApplication.viewAdvanced(currentMemberId(), sessionId, nodeId));
        } catch (RuntimeException refused) {
            return refusal("view-advanced", sessionId, nodeId, refused);
        }
    }

    @Operation(summary = "高级玩法局使用帮助层级；扣减/幂等与主题局同一条链")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/advanced/use")
    public AjaxResult useAdvanced(@RequestBody Map<String, Object> body) {
        Object rawLevel = body == null ? null : body.get("level");
        String sessionId = body == null ? null : Convert.toStr(body.get("sessionId"), "");
        String nodeId = body == null ? null : Convert.toStr(body.get("nodeId"), "");
        try {
            return success(playHelpApplication.useAdvanced(
                    currentMemberId(),
                    sessionId,
                    nodeId,
                    rawLevel == null ? null : Convert.toInt(rawLevel, -1),
                    body == null ? null : Convert.toStr(body.get("action"), ""),
                    body == null ? null : Convert.toStr(body.get("requestId"), "")));
        } catch (RuntimeException refused) {
            return refusal("use-advanced", sessionId, nodeId, refused);
        }
    }

    @Operation(summary = "高级玩法局跳过本题：0 分 + 辅助完成，不伪造任何现场凭证")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @PostMapping("/advanced/skip")
    public AjaxResult skipAdvanced(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : Convert.toStr(body.get("sessionId"), "");
        String nodeId = body == null ? null : Convert.toStr(body.get("nodeId"), "");
        try {
            return success(playHelpApplication.skipAdvanced(currentMemberId(), sessionId, nodeId,
                    body == null ? null : Convert.toStr(body.get("requestId"), "")));
        } catch (RuntimeException refused) {
            return refusal("skip-advanced", sessionId, nodeId, refused);
        }
    }

    private AjaxResult refusal(String operation, String runId, String nodeId, RuntimeException refused) {
        if (refused instanceof PlayHelpCostApplication.PlayHelpCostException costRefusal) {
            return AjaxResult.error(400, costRefusal.getMessage()).put("reasonCode", costRefusal.code());
        }
        if (refused instanceof IllegalArgumentException || refused instanceof IllegalStateException) {
            return AjaxResult.error(400, refused.getMessage());
        }
        logger.error("Play help {} failed run={} node={}", operation, runId, nodeId, refused);
        return error("帮助规则暂不可用，请稍后重试");
    }

    private Long currentMemberId() {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }
}
