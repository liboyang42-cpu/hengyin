package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.TopicEditionCost;
import com.chengyinhub.business.service.ITopicEditionFundsService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.Map;

@RestController
@RequestMapping("/business/topic-edition-funds")
public class TopicEditionFundsController extends BaseController {
    private final ITopicEditionFundsService fundsService;
    private final ITopicOperationsScopeAuthorizer scopeAuthorizer;

    public TopicEditionFundsController(ITopicEditionFundsService fundsService,
                                       ITopicOperationsScopeAuthorizer scopeAuthorizer) {
        this.fundsService = fundsService;
        this.scopeAuthorizer = scopeAuthorizer;
    }

    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/{topicId}/current")
    public AjaxResult current(@PathVariable Long topicId) {
        if (!scopeAuthorizer.canViewTopic(topicId)) return error("主题不存在或当前账号无操作范围");
        return success(fundsService.currentProvisional(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/{topicId}/overview")
    public AjaxResult overview(@PathVariable Long topicId) {
        if (!scopeAuthorizer.canViewTopic(topicId)) return error("主题不存在或当前账号无操作范围");
        return success(fundsService.currentFinanceOverview(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:topic:edit')")
    @Log(title = "探店日期级成本录入", businessType = BusinessType.UPDATE)
    @PutMapping("/{topicId}/cost")
    public AjaxResult saveCost(@PathVariable Long topicId, @RequestBody TopicEditionCost cost) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        return success(fundsService.saveCost(topicId, cost, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('business:topic:edit')")
    @Log(title = "探店日期级资金快照重算", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/recompute")
    public AjaxResult recompute(@PathVariable Long topicId, @RequestBody Map<String, Object> body) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        String key = body == null ? null : String.valueOf(body.get("idempotencyKey"));
        if (key == null || "null".equals(key) || key.trim().isEmpty()) return error("idempotencyKey 不能为空");
        return success(fundsService.recomputeProvisional(topicId, new Date(), key, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('business:topic:edit')")
    @Log(title = "探店日未知购票类型修复", businessType = BusinessType.UPDATE)
    @PostMapping("/{topicId}/repair-purchase-kind")
    public AjaxResult repairPurchaseKind(@PathVariable Long topicId) {
        if (!scopeAuthorizer.canEditTopic(topicId)) return error("主题不存在或当前账号无修改范围");
        return success(fundsService.repairUnknownExplorePurchases(topicId));
    }
}
