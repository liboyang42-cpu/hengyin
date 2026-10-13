package com.chengyinhub.web.controller.business;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.service.IMerchantClawbackService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * M块:商家违规倒扣(2026-07-25 拍板)。
 *
 * <p>独立权限点,不与日常投诉/结算处理权限捆绑(参照 P1-6 P块冻结功能的权限设计):
 * {@code business:merchantclawback:apply}(发起)与
 * {@code business:merchantclawback:confirm}(复核确认/驳回)拆成两个点 ——
 * 发起和复核不该是同一个人能一次点完的事。
 */
@RestController
@RequestMapping("/business/merchantclawback")
public class MerchantClawbackController extends BaseController {

    @Autowired
    private IMerchantClawbackService merchantClawbackService;

    @PreAuthorize("@ss.hasPermi('business:merchantclawback:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) Long merchantMemberId,
                           @RequestParam(required = false) String status) {
        return AjaxResult.success(merchantClawbackService.list(merchantMemberId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:merchantclawback:list')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return AjaxResult.success(merchantClawbackService.selectById(id));
    }

    /**
     * 发起倒扣申请。金额由运营人工核实投诉后填写,系统不计算,只自动判定结算路径。
     */
    @PreAuthorize("@ss.hasPermi('business:merchantclawback:apply')")
    @Log(title = "M块商家违规倒扣发起", businessType = BusinessType.INSERT)
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody Map<String, Object> body) {
        Long merchantMemberId = body.get("merchantMemberId") == null ? null : Long.valueOf(String.valueOf(body.get("merchantMemberId")));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        BigDecimal amount = body.get("amount") == null ? null : new BigDecimal(String.valueOf(body.get("amount")));
        try {
            MerchantClawbackApplication a = merchantClawbackService.initiate(merchantMemberId, reason, amount, getUsername());
            return AjaxResult.success("已发起,待复核", a);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 复核通过并执行:PENDING_REVIEW → EXECUTED。
     *
     * <p>⚠️ 复核人必须≠发起人 —— 与 {@code MerchantPayableBatchController.markPaid} 的
     * 双人复核同型。
     */
    @PreAuthorize("@ss.hasPermi('business:merchantclawback:confirm')")
    @Log(title = "M块商家违规倒扣复核执行", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/confirm")
    public AjaxResult confirm(@PathVariable Long id) {
        try {
            return merchantClawbackService.confirmExecute(id, getUsername())
                    ? AjaxResult.success("已执行")
                    : AjaxResult.error("执行失败:该申请已不是待复核状态(可能已执行或已驳回)");
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 驳回:PENDING_REVIEW → REJECTED。不移动任何资金。
     */
    @PreAuthorize("@ss.hasPermi('business:merchantclawback:confirm')")
    @Log(title = "M块商家违规倒扣驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reject")
    public AjaxResult reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String rejectReason = body.get("rejectReason") == null ? null : String.valueOf(body.get("rejectReason"));
        try {
            return merchantClawbackService.reject(id, getUsername(), rejectReason)
                    ? AjaxResult.success("已驳回")
                    : AjaxResult.error("驳回失败:该申请已不是待复核状态");
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }
}
