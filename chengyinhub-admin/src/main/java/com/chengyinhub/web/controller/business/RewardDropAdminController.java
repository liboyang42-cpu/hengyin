package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.GameRandomAudit;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.IRewardDropRuleService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M6 随机掉落规则、基础概率与可审计随机流水后台接口。
 */
@RestController
@RequestMapping("/business/reward-drop")
public class RewardDropAdminController extends BaseController {

    @Autowired
    private IRewardDropRuleService ruleService;

    @Autowired(required = false)
    private IGameRandomService gameRandomService;

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:list')")
    @GetMapping("/rules/list")
    public TableDataInfo listRules(RewardDropRule filter) {
        startPage();
        List<RewardDropRule> list = ruleService.selectRuleList(filter);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:query')")
    @GetMapping("/rules/{id}")
    public AjaxResult getRule(@PathVariable Long id) {
        return AjaxResult.success(ruleService.selectRuleById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:add')")
    @Log(title = "随机掉落规则", businessType = BusinessType.INSERT)
    @PostMapping("/rules")
    public AjaxResult addRule(@RequestBody RewardDropRule rule) {
        try {
            return AjaxResult.success(ruleService.insertRule(rule));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:edit')")
    @Log(title = "随机掉落规则", businessType = BusinessType.UPDATE)
    @PutMapping("/rules")
    public AjaxResult editRule(@RequestBody RewardDropRule rule) {
        try {
            return AjaxResult.success(ruleService.updateRule(rule));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:remove')")
    @Log(title = "随机掉落规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/rules/{id}")
    public AjaxResult removeRule(@PathVariable Long id) {
        return toAjax(ruleService.deleteRuleById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:query')")
    @GetMapping("/base-prob")
    public AjaxResult getBaseProb() {
        int bp = ruleService.getBaseProbBp();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configKey", IRewardDropRuleService.BASE_PROB_BP_CONFIG_KEY);
        data.put("baseProbBp", bp);
        data.put("baseProbPercent", Math.round((bp / 10000.0) * 10000.0) / 100.0);
        return AjaxResult.success(data);
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:edit')")
    @Log(title = "掉落基础概率", businessType = BusinessType.UPDATE)
    @PutMapping("/base-prob")
    public AjaxResult updateBaseProb(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("baseProbBp");
        if (raw == null) {
            return AjaxResult.error("缺少 baseProbBp 参数");
        }
        try {
            int bp = Integer.parseInt(String.valueOf(raw).trim());
            int saved = ruleService.updateBaseProbBp(bp, safeUsername());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("configKey", IRewardDropRuleService.BASE_PROB_BP_CONFIG_KEY);
            data.put("baseProbBp", saved);
            data.put("baseProbPercent", Math.round((saved / 10000.0) * 10000.0) / 100.0);
            return AjaxResult.success(data);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:query')")
    @GetMapping("/preview")
    public AjaxResult preview(@RequestParam(required = false) Long topicId,
                              @RequestParam(required = false) String sceneKey) {
        return AjaxResult.success(ruleService.previewProbabilities(topicId, sceneKey));
    }

    @PreAuthorize("@ss.hasPermi('business:rewarddrop:list')")
    @GetMapping("/audit/list")
    public AjaxResult listAudits(GameRandomAudit filter) {
        if (gameRandomService == null) {
            return AjaxResult.success(List.of());
        }
        return AjaxResult.success(gameRandomService.listAudits(filter));
    }

    private String safeUsername() {
        try {
            return getUsername();
        } catch (Exception e) {
            return "admin";
        }
    }
}
