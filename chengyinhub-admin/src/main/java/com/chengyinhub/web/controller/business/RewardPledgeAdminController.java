package com.chengyinhub.web.controller.business;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.rewardpledge.RewardPledgeAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * R10 BE-22 非现金奖励承诺：政策行与平台审核队列。
 * 承诺只承载券类无现金价值的奖励；任何现金路径仍由 CashPayoutSwitch 在 App 端拦截，本控制台不新增付款入口。
 */
@RestController
@RequestMapping("/business/rewardPledge")
public class RewardPledgeAdminController extends BaseController
{
    @Autowired
    private RewardPledgeAdminService service;

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:list')")
    @GetMapping("/policies")
    public AjaxResult policies()
    {
        return success(service.policies());
    }

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:policy')")
    @Log(title = "保存奖励承诺政策", businessType = BusinessType.UPDATE)
    @PostMapping("/policies")
    public AjaxResult savePolicy(@RequestBody Map<String, Object> body)
    {
        Object actor = body.get("actorMemberId");
        Long actorId = actor == null || actor.toString().isBlank() ? null : Long.valueOf(actor.toString());
        return success(service.savePolicy(getUserId(), str(body, "policyCode"), str(body, "ruleVersion"),
                str(body, "decision"), actorId, str(body, "note")));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:list')")
    @GetMapping("/queue")
    public AjaxResult queue(String state, Long storeId)
    {
        return success(service.queue(state, storeId));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:list')")
    @GetMapping("/{pledgeId}/notes")
    public AjaxResult notes(@PathVariable String pledgeId)
    {
        return success(service.notes(pledgeId));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:review')")
    @Log(title = "通过奖励承诺", businessType = BusinessType.UPDATE)
    @PostMapping("/versions/{versionId}/approve")
    public AjaxResult approve(@PathVariable String versionId, @RequestBody Map<String, Object> body)
    {
        return success(service.approve(getUserId(), versionId, str(body, "ruleVersion"), str(body, "note"), System.currentTimeMillis()));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:review')")
    @Log(title = "驳回奖励承诺", businessType = BusinessType.UPDATE)
    @PostMapping("/versions/{versionId}/reject")
    public AjaxResult reject(@PathVariable String versionId, @RequestBody Map<String, Object> body)
    {
        return success(service.reject(getUserId(), versionId, str(body, "note")));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardPledge:review')")
    @Log(title = "奖励承诺审核备注", businessType = BusinessType.UPDATE)
    @PostMapping("/versions/{versionId}/note")
    public AjaxResult note(@PathVariable String versionId, @RequestBody Map<String, Object> body)
    {
        return success(service.note(getUserId(), versionId, str(body, "note")));
    }

    static String str(Map<String, Object> body, String key)
    {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }
}
