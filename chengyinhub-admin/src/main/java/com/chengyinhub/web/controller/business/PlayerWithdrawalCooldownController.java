package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A3 玩家投诉补偿提现冷静期：<b>专用</b>改写入口。
 *
 * <p>审计 2 §「权限、审计和失败语义」开头：「资金页、成本录入、暂结、终结、审核、付款批次和
 * <b>冷却配置</b>都不是普通编辑接口」。RuoYi 通用参数页（{@code system:config:edit}）只写新值、
 * 不留前值，「理由」它本来就不采集 —— 走那条路，「谁在什么时候把 48 改成了 1、为什么」查不出来。
 *
 * <p>本端点：专用权限 + 前值/后值/理由/操作人落审计表 + 审计写不进去就整笔回滚。
 * <b>只影响新事件</b>：{@code cooldown_hours} / {@code cooldown_until} 是逐笔快照，一行既有的都不回写。
 */
@RestController
@RequestMapping("/business/withdrawal-cooldown")
public class PlayerWithdrawalCooldownController extends BaseController {

    private final IPlayerWithdrawalCooldownService cooldownService;

    public PlayerWithdrawalCooldownController(IPlayerWithdrawalCooldownService cooldownService) {
        this.cooldownService = cooldownService;
    }

    @PreAuthorize("@ss.hasPermi('business:withdrawal:cooldown-config')")
    @GetMapping("/config")
    public AjaxResult currentConfig() {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("configKey", IPlayerWithdrawalCooldownService.CONFIG_KEY);
        view.put("hours", cooldownService.currentCooldownHours());
        return success(view);
    }

    /**
     * 改写冷静期小时数。理由必填且<b>落库</b>，不指望 {@code @Log} —— 本仓 LogAspect 不写请求体。
     *
     * <p>⚠️ 不接受 0 / 负数：那等于把冷静期悄悄关掉。
     */
    @PreAuthorize("@ss.hasPermi('business:withdrawal:cooldown-config')")
    @Log(title = "玩家投诉补偿冷静期改写", businessType = BusinessType.UPDATE)
    @PutMapping("/config")
    public AjaxResult rewrite(@RequestBody Map<String, Object> body) {
        Integer hours = body == null ? null : Convert.toInt(body.get("hours"));
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        if (hours == null) return error("hours 必填，且必须是正整数");
        int before = cooldownService.rewriteCooldownHours(hours, reason, getUserId());
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("beforeHours", before);
        view.put("afterHours", hours);
        return success(view);
    }
}
