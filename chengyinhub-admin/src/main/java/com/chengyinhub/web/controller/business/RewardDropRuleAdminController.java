package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.opsconsole.RewardDropRuleAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/** R10 BE-21 随机掉落规则编辑器（reward_drop_rule）。新建默认停用，启停单独一步；每次写入落平台审计。 */
@RestController
@RequestMapping("/business/rewardDropRule")
public class RewardDropRuleAdminController extends BaseController
{
    @Autowired
    private RewardDropRuleAdminService service;

    @PreAuthorize("@ss.hasPermi('business:rewardDrop:list')")
    @GetMapping("/list")
    public AjaxResult list(RewardDropRule filter)
    {
        return success(service.list(filter));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardDrop:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id)
    {
        return success(service.get(id));
    }

    /** 只校验不保存：给编辑表单即时反馈规范化后的参数。 */
    @PreAuthorize("@ss.hasPermi('business:rewardDrop:list')")
    @PostMapping("/validate")
    public AjaxResult validate(@RequestBody RewardDropRule rule)
    {
        return success(RewardDropRuleAdminService.normalize(rule));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardDrop:add')")
    @Log(title = "新建掉落规则", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody RewardDropRule rule)
    {
        return success(service.create(getUserId(), rule));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardDrop:edit')")
    @Log(title = "编辑掉落规则", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public AjaxResult edit(@PathVariable Long id, @RequestBody RewardDropRule rule)
    {
        return success(service.update(getUserId(), id, rule));
    }

    @PreAuthorize("@ss.hasPermi('business:rewardDrop:edit')")
    @Log(title = "启停掉落规则", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status/{status}")
    public AjaxResult status(@PathVariable Long id, @PathVariable Integer status)
    {
        return success(service.setStatus(getUserId(), id, status));
    }
}
