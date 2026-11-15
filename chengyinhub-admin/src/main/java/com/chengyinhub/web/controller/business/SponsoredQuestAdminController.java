package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.sponsoredquest.SponsoredQuestAdminService;
import com.chengyinhub.business.sponsoredquest.SponsoredQuestBudgetService;
import com.chengyinhub.business.sponsoredquest.SponsoredQuestCampaign;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S3b 赞助任务后台:运营创建、修改、上线、暂停、结束;玩家侧只在「优惠」图层里以带「广告」标签的任务出现,
 * 不进迷雾地图、点位排序或搜索。计费只在资金开关(商家资金)打开时可用,关闭时只展示不计费。
 */
@RestController
@RequestMapping("/business/sponsoredQuest")
public class SponsoredQuestAdminController extends BaseController {
    private final SponsoredQuestAdminService service;
    private final SponsoredQuestBudgetService budget;

    @Autowired
    public SponsoredQuestAdminController(SponsoredQuestAdminService service, SponsoredQuestBudgetService budget) {
        this.service = service;
        this.budget = budget;
    }

    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:list')")
    @GetMapping("/list")
    public AjaxResult list() {
        return success(service.list());
    }

    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:list')")
    @GetMapping("/state")
    public AjaxResult state() {
        return success(budget.state());
    }

    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:list')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable("id") Long id) {
        try {
            return success(service.detail(id));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:edit')")
    @Log(title = "赞助任务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody SponsoredQuestCampaign campaign) {
        try {
            return success(service.create(campaign, getUsername(), getUserId()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:edit')")
    @Log(title = "赞助任务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult update(@RequestBody SponsoredQuestCampaign campaign) {
        try {
            service.update(campaign, getUsername(), getUserId());
            return success();
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /** status: 1 上线, 2 暂停, 3 结束(不可恢复)。 */
    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:edit')")
    @Log(title = "赞助任务", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public AjaxResult status(@PathVariable("id") Long id, @RequestParam("status") int status,
                             @RequestParam(value = "reason", required = false) String reason) {
        try {
            service.changeStatus(id, status, getUsername(), getUserId(), reason);
            return success();
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:list')")
    @GetMapping("/{id}/charges")
    public AjaxResult charges(@PathVariable("id") Long id) {
        try {
            return success(budget.charges(id));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /** 计费:只在资金开关(商家资金)打开时可用;关闭时拒绝且不写任何记录。不调用任何支付渠道。 */
    @PreAuthorize("@ss.hasPermi('business:sponsoredQuest:charge')")
    @Log(title = "赞助任务计费", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/charges")
    public AjaxResult charge(@PathVariable("id") Long id, @RequestParam("refKey") String refKey,
                             @RequestParam("amountCent") Long amountCent, @RequestParam("reason") String reason) {
        try {
            return success(budget.charge(id, refKey, amountCent, reason, getUsername(), getUserId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }
}
