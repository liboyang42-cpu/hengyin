package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * 实施文档二 F1b 城市定向计分(玩家端)。总开关 orient.scoring.enabled 默认关闭;
 * 只对本人的 product_type=1 对局生效,他人对局与不存在同一回复。
 * 写接口一律带 idempotencyKey:同键同内容回放首份回执,同键异内容 ORIENT_IDEMPOTENCY_CONFLICT;
 * 结果未知(超时/断网)时用 /receipt 回读,不要换键重提。
 */
@RestController
@Tag(name = "城市定向计分")
@RequestMapping("/api/orient/scoring")
public class ApiOrientScoringController extends BaseController {

    @Autowired(required = false)
    private IOrientScoringService orientScoringService;

    @Operation(summary = "本局计分:分维度汇总 + 时间线 + 结算回执 + 申诉/争议")
    @GetMapping("/session/{sessionId}")
    public AjaxResult breakdown(@PathVariable("sessionId") Long sessionId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.breakdown(memberId, sessionId));
    }

    @Operation(summary = "历史操作缺少原请求号时登记人工核对，不重放原操作")
    @PostMapping("/session/{sessionId}/recovery")
    public AjaxResult recovery(@PathVariable("sessionId") Long sessionId, String kind) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.submitRecovery(memberId, sessionId, kind).playerView());
    }

    @Operation(summary = "本局所在场次完整排名：先排名后截取，同键同名次")
    @GetMapping("/session/{sessionId}/leaderboard")
    public AjaxResult leaderboard(@PathVariable("sessionId") Long sessionId) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.leaderboard(memberId, sessionId));
    }

    @Operation(summary = "把本局已通过的任务入账(幂等)")
    @PostMapping("/session/{sessionId}/sync")
    public AjaxResult sync(@PathVariable("sessionId") Long sessionId, String idempotencyKey) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.syncSession(memberId, sessionId, idempotencyKey));
    }

    @Operation(summary = "局终结算到积分/XP(每局恰好一次)")
    @PostMapping("/session/{sessionId}/settle")
    public AjaxResult settle(@PathVariable("sessionId") Long sessionId, String idempotencyKey) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.settle(memberId, sessionId, idempotencyKey));
    }

    @Operation(summary = "计分申诉(针对某条流水或漏记的任务)")
    @PostMapping("/appeal")
    public AjaxResult appeal(String sessionId, String entryId, String nodeId, String reason, String idempotencyKey) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.submitAppeal(memberId, Convert.toLong(sessionId, null),
                Convert.toLong(entryId, null), Convert.toLong(nodeId, null), reason, idempotencyKey));
    }

    @Operation(summary = "成绩争议(局终后争议窗口内)")
    @PostMapping("/dispute")
    public AjaxResult dispute(String sessionId, String reason, String idempotencyKey) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.submitDispute(memberId, Convert.toLong(sessionId, null), reason,
                idempotencyKey));
    }

    @Operation(summary = "结果未知时回读回执(kind=SYNC/SETTLE/APPEAL/DISPUTE)")
    @GetMapping("/receipt")
    public AjaxResult receipt(String kind, String sessionId, String idempotencyKey) {
        Long memberId = currentMemberId();
        if (memberId == null) return error("请先登录");
        return run(() -> orientScoringService.receipt(memberId, kind, Convert.toLong(sessionId, null), idempotencyKey));
    }

    private AjaxResult run(Supplier<Object> action) {
        if (orientScoringService == null) {
            return error("城市定向计分暂未开放").put("errorCode", OrientScoringException.DISABLED);
        }
        try {
            return success(OrientPlayerWire.response(action.get()));
        } catch (OrientScoringException e) {
            return error(e.getMessage()).put("errorCode", e.getErrorCode());
        }
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
