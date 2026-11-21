package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * W7 MERCHANT-15: switching ON is two steps (request with {@code :edit}, approval by a different admin with
 * {@code :approve}); the shared {@code admin} account can do neither. Switching off stays one step.
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
    @GetMapping("/requests")
    public AjaxResult requests(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return success(moneySwitchService.requests(limit));
    }

    /** Body: {"area": "tickets", "reason": "..."}. First person: asks to open; nothing changes yet. */
    @PreAuthorize("@ss.hasPermi('business:moneyswitch:edit')")
    @Log(title = "资金开关打开申请", businessType = BusinessType.INSERT)
    @PostMapping("/requests")
    public AjaxResult requestOpen(@RequestBody Map<String, Object> body) {
        try {
            Object area = body == null ? null : body.get("area");
            Object reason = body == null ? null : body.get("reason");
            return success(moneySwitchService.requestOpen(area == null ? null : area.toString(), getUsername(),
                    reason == null ? null : reason.toString()));
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return AjaxResult.error(refused.getMessage());
        }
    }

    /** Body: {"reason": "..."}. Second person (not the requester) switches the area on. */
    @PreAuthorize("@ss.hasPermi('business:moneyswitch:approve')")
    @Log(title = "资金开关打开批准", businessType = BusinessType.UPDATE)
    @PostMapping("/requests/{id}/approve")
    public AjaxResult approveOpen(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        try {
            Object reason = body == null ? null : body.get("reason");
            boolean changed = moneySwitchService.approveOpen(id, getUsername(), reason == null ? null : reason.toString());
            AjaxResult result = success(moneySwitchService.adminView());
            result.put("changed", changed);
            return result;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return AjaxResult.error(refused.getMessage());
        }
    }

    /** Body: {"reason": "..."}. Turns down or withdraws a request; moves nothing. */
    @PreAuthorize("@ss.hasPermi('business:moneyswitch:edit')")
    @Log(title = "资金开关打开申请驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/requests/{id}/reject")
    public AjaxResult rejectOpen(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        try {
            Object reason = body == null ? null : body.get("reason");
            return success(moneySwitchService.rejectOpen(id, getUsername(), reason == null ? null : reason.toString()));
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
