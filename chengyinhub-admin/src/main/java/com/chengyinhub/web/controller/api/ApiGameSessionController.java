package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.enums.LimitType;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.support.rpg.adapter.ResourceSourceAdapter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.aspectj.LogAspect;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/** 三身份活动级游戏 transport adapter；演员身份和所有权限均由服务端派生。 */
@RestController
@Tag(name = "三身份游戏局")
@RequestMapping("/api/game/session")
public class ApiGameSessionController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ApiGameSessionController.class);

    @Autowired
    private IGameSessionRuntimeService runtimeService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Operation(summary = "读取玩家/商家/俱乐部安全投影")
    @GetMapping("/view")
    public AjaxResult view(String activityId, String perspective) {
        try {
            return success(runtimeService.view(actor(perspective), Convert.toLong(activityId, 0L)));
        } catch (GameSessionException e) {
            return gameError(e);
        } catch (RuntimeException e) {
            return internalError("view", e);
        }
    }

    @Operation(summary = "读取当前商家被授权的活动站点入口")
    @GetMapping("/merchant/entries")
    public AjaxResult merchantEntries() {
        try {
            return success(runtimeService.merchantEntries(actor("MERCHANT")));
        } catch (GameSessionException e) {
            return gameError(e);
        } catch (RuntimeException e) {
            return internalError("merchantEntries", e);
        }
    }

    @Operation(summary = "导出当前俱乐部活动的聚合复盘")
    @GetMapping("/recap/export")
    public AjaxResult exportRecap(String activityId) {
        try {
            return success(runtimeService.exportRecap(actor("CLUB"), Convert.toLong(activityId, 0L)));
        } catch (GameSessionException e) {
            return gameError(e);
        } catch (RuntimeException e) {
            return internalError("exportRecap", e);
        }
    }

    @Operation(summary = "提交带版本和幂等键的游戏命令")
    @RateLimiter(time = 10, count = 120, limitType = LimitType.MEMBER)
    @Log(title = "游戏场次操作", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/command")
    public AjaxResult command(@RequestBody GameSessionCommand command) {
        GameActorContext commandActor = null;
        try {
            commandActor = actor(perspectiveFor(command == null ? null : command.getAction()));
            return commandReceipt(runtimeService.command(commandActor, command));
        } catch (GameSessionException e) {
            try {
                if (commandActor != null) {
                    GameCommandReceiptView receipt = runtimeService.recordFailure(commandActor, command, e);
                    if (receipt != null) return commandReceipt(receipt);
                }
            } catch (RuntimeException auditError) {
                log.warn("Game session failure receipt could not be recorded", auditError);
            }
            return gameError(e);
        } catch (RuntimeException e) {
            return internalError("command", e);
        }
    }

    /**
     * SC-CITY-RESUME-RACE:另一终端已完成同一暂停/恢复时,本次请求没有改变任何状态,
     * 回执是 FAILED + 无变化原因码;这类请求不再写 sys_oper_log,一次迁移只留一条操作日志。
     */
    private AjaxResult commandReceipt(GameCommandReceiptView receipt) {
        if (receipt != null && "FAILED".equals(receipt.getOutcome())
                && GameSessionException.isNoChange(receipt.getReasonCode())) {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                attributes.setAttribute(LogAspect.SKIP_OPER_LOG_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
            }
        }
        return success(receipt);
    }

    @Operation(summary = "按活动和请求编号读取权威回执")
    @GetMapping("/receipt")
    public AjaxResult receipt(String activityId, String requestId, @RequestParam(required = false) String perspective) {
        try {
            GameActorContext actor = actor(perspective);
            Long activity = Convert.toLong(activityId, 0L);
            GameCommandReceiptView found = runtimeService.receipt(actor, activity, requestId);
            ResourceSourceAdapter.GameReceiptRead checked = new ResourceSourceAdapter()
                    .recoverFromGameReceipt(actor, activity, requestId, (a, act, id) -> found);
            if (checked == null) {
                throw new GameSessionException("GAME_RECEIPT_IDENTITY", "回执与本次活动或请求不符");
            }
            return success(found);
        } catch (GameSessionException e) {
            return gameError(e);
        } catch (RuntimeException e) {
            return internalError("receipt", e);
        }
    }

    public AjaxResult receipt(String activityId, String requestId) { return receipt(activityId, requestId, null); }

    @Operation(summary = "共享运行预览，不提交状态")
    @RateLimiter(time = 10, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/shared/preview")
    public AjaxResult sharedPreview(@RequestBody GameSessionCommand command) {
        try {
            if (command == null) throw new GameSessionException("GAME_BAD_REQUEST", "缺少预览请求");
            return success(runtimeService.sharedPreview(actor("PLAYER"), command.getActivityId(), command.getAction(), command.getPayload()));
        } catch (GameSessionException e) { return gameError(e); }
        catch (RuntimeException e) { return internalError("sharedPreview", e); }
    }

    @Operation(summary = "本场授权运营人员查看共享控制台")
    @GetMapping("/shared/control")
    public AjaxResult sharedControl(String activityId) {
        try { return success(runtimeService.sharedControlView(actor("CLUB"), Convert.toLong(activityId, 0L))); }
        catch (GameSessionException e) { return gameError(e); }
        catch (RuntimeException e) { return internalError("sharedControl", e); }
    }

    @Operation(summary = "本场授权运营人员导出高水位与人工账")
    @GetMapping("/shared/export")
    public AjaxResult sharedExport(String activityId) {
        try { return success(runtimeService.exportSharedRun(actor("CLUB"), Convert.toLong(activityId, 0L))); }
        catch (GameSessionException e) { return gameError(e); }
        catch (RuntimeException e) { return internalError("sharedExport", e); }
    }

    @Operation(summary = "保存服务器高水位锚点供真正离线纸账接管")
    @RateLimiter(time = 10, count = 10, limitType = LimitType.MEMBER)
    @Log(title = "游戏场次操作", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/shared/export/anchor")
    public AjaxResult sharedExportAnchor(@RequestBody GameSessionCommand request) {
        try {
            if (request == null || request.getActivityId() == null) throw new GameSessionException("GAME_BAD_REQUEST", "缺少实际活动");
            return success(runtimeService.anchorSharedRun(actor("CLUB"), request.getActivityId()));
        } catch (GameSessionException e) { return gameError(e); }
        catch (RuntimeException e) { return internalError("sharedExportAnchor", e); }
    }

    /**
     * 商家视角不再把「登录人」当商家主体:先解析出所属商家与岗位,再交给服务层。
     * 站点动作要求 VERIFY(核验提交)或 PROJECT_MANAGE(接受/就绪/暂停/恢复);
     * 非商家成员/无任何站点权限的人在这里就被拦下,错误形状与其他游戏错误一致。
     */
    private GameActorContext actor(String perspective) {
        Long memberId = getAppUserId();
        if (!"MERCHANT".equalsIgnoreCase(perspective)) {
            return new GameActorContext(memberId, perspective);
        }
        MerchantAccessContext access;
        try {
            access = merchantAccessService.resolve(memberId);
        } catch (ServiceException invalid) {
            throw new GameSessionException("GAME_FORBIDDEN_SCOPE", "当前商家不负责本场站点");
        }
        if (access == null
                || !(access.hasPermission(MerchantPermission.VERIFY)
                    || access.hasPermission(MerchantPermission.PROJECT_MANAGE))) {
            throw new GameSessionException("GAME_FORBIDDEN_SCOPE", "当前商家不负责本场站点");
        }
        return GameActorContext.merchant(memberId, access);
    }

    private String perspectiveFor(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if ("QUEST_GUIDE".equals(normalized) || "PLAN_FALLBACK".equals(normalized)) return "CLUB";
        if (normalized.startsWith("STATION_") || "VERIFY_SUBMISSION".equals(normalized)) return "MERCHANT";
        if (normalized.startsWith("TRADE_") || normalized.startsWith("FACILITY_") || normalized.startsWith("PLAN_")
                || normalized.startsWith("VOTE_") || normalized.startsWith("CONSENSUS_") || normalized.startsWith("OPERATION_")
                || "ACCEPT_SET".equals(normalized) || "ARRIVAL_ISSUE".equals(normalized) || "MANUAL_SIGN".equals(normalized)
                || "RECOVER".equals(normalized) || "ZERO_HP_STEPS".equals(normalized) || "ZERO_HP_SYNC".equals(normalized) || "TEAM_EXIT".equals(normalized)) return "PLAYER";
        if (normalized.startsWith("PLAYER_") || normalized.startsWith("QUEST_") || normalized.startsWith("ITEM_") || "CONFIRM_ROLE".equals(normalized)) return "PLAYER";
        return "CLUB";
    }

    private AjaxResult gameError(GameSessionException error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reasonCode", error.getErrorCode());
        return AjaxResult.error(error.getMessage(), data);
    }

    private AjaxResult internalError(String operation, RuntimeException error) {
        log.error("Game session {} failed", operation, error);
        return error("游戏服务暂时不可用，请稍后重试");
    }
}
