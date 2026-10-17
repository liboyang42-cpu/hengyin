package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.dto.PlatformSettlementAccountRequest;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/business/settlementrule")
public class SettlementRuleController extends BaseController {
    @Autowired
    private ISettlementRuleService settlementRuleService;

    @PreAuthorize("@ss.hasPermi('business:settlementrule:list')")
    @GetMapping("/list")
    public TableDataInfo list(SettlementRule query) {
        startPage();
        return getDataTable(settlementRuleService.selectRuleList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:settlementrule:list')")
    @GetMapping("/active")
    public AjaxResult active(@RequestParam(required = false) Long merchantId, @RequestParam String bizType) {
        return success(settlementRuleService.getActiveRule(merchantId, bizType));
    }

    @PreAuthorize("@ss.hasPermi('business:settlementrule:list')")
    @GetMapping("/platform-account")
    public AjaxResult platformAccount() {
        return success(settlementRuleService.getPlatformSettlementAccount());
    }

    @PreAuthorize("@ss.hasPermi('business:settlementrule:edit')")
    @Log(title = "平台活动分润收款账户", businessType = BusinessType.UPDATE)
    @PutMapping("/platform-account")
    public AjaxResult updatePlatformAccount(@Valid @RequestBody PlatformSettlementAccountRequest request) {
        return toAjax(settlementRuleService.updatePlatformSettlementAccount(request.getMemberId()));
    }

    @PreAuthorize("@ss.hasPermi('business:settlementrule:edit')")
    @Log(title = "结算规则", businessType = BusinessType.UPDATE)
    @PostMapping
    public AjaxResult add(@RequestBody SettlementRule rule) {
        return toAjax(settlementRuleService.saveRule(rule));
    }

    @PreAuthorize("@ss.hasPermi('business:settlementrule:edit')")
    @Log(title = "结算规则", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SettlementRule rule) {
        return toAjax(settlementRuleService.saveRule(rule));
    }
}
