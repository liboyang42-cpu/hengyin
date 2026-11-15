package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Q5 money switch, admin console. Read the six areas, change one area with a required reason, read the audit trail.
 * Switching on is refused while the server ceiling ({@code chengyin.money-switch.server-ceiling}) is off.
 * Permissions are created by the migration and granted to no role; the owner grants them explicitly.
 */
@RestController
@RequestMapping("/business/money-switch")
public class MoneySwitchAdminController extends BaseController {

    private final MoneySwitchService moneySwitchService;

    public MoneySwitchAdminController(MoneySwitchService moneySwitchService) {
        this.moneySwitchService = moneySwitchService;
    }

    @PreAuthorize("@ss.hasPermi('business:moneyswitch:query')")
    @GetMapping
    public AjaxResult view() {
        return success(moneySwitchService.adminView());
    }

    /** Body: {"area": "tickets", "enabled": true, "reason": "..."}. */
    @PreAuthorize("@ss.hasPermi('business:moneyswitch:edit')")
    @Log(title = "资金开关", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult change(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return AjaxResult.error("缺少请求内容");
        }
        Object area = body.get("area");
        Object enabled = body.get("enabled");
        Object reason = body.get("reason");
        if (!(enabled instanceof Boolean)) {
            return AjaxResult.error("enabled 必须是 true 或 false");
        }
        try {
            boolean changed = moneySwitchService.set(area == null ? null : area.toString(), (Boolean) enabled,
                    getUsername(), reason == null ? null : reason.toString());
            AjaxResult result = success(moneySwitchService.adminView());
            result.put("changed", changed);
            return result;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return AjaxResult.error(refused.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:moneyswitch:query')")
    @GetMapping("/audit")
    public AjaxResult audit(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return success(moneySwitchService.audit(limit));
    }
}
