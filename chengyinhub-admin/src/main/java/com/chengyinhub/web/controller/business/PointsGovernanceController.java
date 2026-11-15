package com.chengyinhub.web.controller.business;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.pointsgov.PointsGovernanceService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;

/**
 * R7 MON-09: points governance in the admin console — daily caps per award source, monthly budget per programme,
 * and the issued / redeemed / outstanding liability dashboard. Read-only for city coin.
 */
@RestController
@RequestMapping("/business/pointsgov")
public class PointsGovernanceController extends BaseController {

    @Autowired
    private PointsGovernanceService governanceService;

    @PreAuthorize("@ss.hasPermi('business:pointsgov:list')")
    @GetMapping("/dashboard")
    public AjaxResult dashboard(String period) {
        try {
            return success(governanceService.dashboard(period));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:pointsgov:edit')")
    @Log(title = "积分治理-每日上限", businessType = BusinessType.UPDATE)
    @PutMapping("/cap")
    public AjaxResult updateCap(@RequestBody Map<String, Object> body) {
        try {
            Long eventType = toLong(body.get("eventType"));
            Long cap = toLong(body.get("dailyCapPoints"));
            if (cap != null && cap > Integer.MAX_VALUE) throw new ServiceException("每日上限过大");
            return toAjax(governanceService.updateCap(eventType, cap == null ? null : cap.intValue(),
                    body.get("status") == null ? null : body.get("status").toString(), getUsername()));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:pointsgov:edit')")
    @Log(title = "积分治理-月度预算", businessType = BusinessType.UPDATE)
    @PutMapping("/budget")
    public AjaxResult updateBudget(@RequestBody Map<String, Object> body) {
        try {
            return toAjax(governanceService.updateBudget(
                    body.get("programme") == null ? null : body.get("programme").toString(),
                    toLong(body.get("monthlyBudgetPoints")),
                    body.get("status") == null ? null : body.get("status").toString(), getUsername()));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    static Long toLong(Object v) {
        if (v == null || v.toString().isBlank()) return null;
        if (v instanceof Number n) {
            if (n.doubleValue() != Math.floor(n.doubleValue())) throw new ServiceException("请输入整数");
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("请输入整数");
        }
    }
}
