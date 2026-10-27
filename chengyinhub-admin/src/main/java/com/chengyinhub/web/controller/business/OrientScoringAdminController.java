package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.support.OrientScoringException;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * F1b 城市定向计分后台:申诉/争议处理、整条冲正、复算核对。
 * 权限 business:orientScoring:list/review 由 migration_20261027_orient_scoring_menu_grants.sql 登记,仅授 cy_operator(owner 2026-10-27)。
 */
@RestController
@RequestMapping("/business/orient/scoring")
public class OrientScoringAdminController extends BaseController {

    @Autowired
    private IOrientScoringService orientScoringService;

    @PreAuthorize("@ss.hasPermi('business:orientScoring:list')")
    @GetMapping("/appeals")
    public AjaxResult appeals(Integer status, Integer limit) {
        return success(orientScoringService.listAppeals(status, limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:list')")
    @GetMapping("/disputes")
    public AjaxResult disputes(Integer status, Integer limit) {
        return success(orientScoringService.listDisputes(status, limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:list')")
    @GetMapping("/session/{sessionId}")
    public AjaxResult session(@PathVariable("sessionId") Long sessionId) {
        return run(() -> orientScoringService.breakdown(null, sessionId));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:list')")
    @GetMapping("/session/{sessionId}/verify")
    public AjaxResult verify(@PathVariable("sessionId") Long sessionId) {
        return run(() -> orientScoringService.verify(sessionId));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:review')")
    @Log(title = "城市定向计分申诉", businessType = BusinessType.UPDATE)
    @PostMapping("/appeals/{id}/resolve")
    public AjaxResult resolveAppeal(@PathVariable("id") Long id, Boolean accept, String adjustCenti, String note) {
        return run(() -> orientScoringService.resolveAppeal(id, Boolean.TRUE.equals(accept),
                Convert.toInt(adjustCenti, null), note, getUsername(), getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:review')")
    @Log(title = "城市定向成绩争议", businessType = BusinessType.UPDATE)
    @PostMapping("/disputes/{id}/resolve")
    public AjaxResult resolveDispute(@PathVariable("id") Long id, Boolean accept, String note) {
        return run(() -> orientScoringService.resolveDispute(id, Boolean.TRUE.equals(accept), note, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:review')")
    @Log(title = "城市定向计分冲正", businessType = BusinessType.UPDATE)
    @PostMapping("/entries/{id}/reverse")
    public AjaxResult reverse(@PathVariable("id") Long id, String reasonCode, String idempotencyKey) {
        return run(() -> orientScoringService.reverseEntry(id, reasonCode, getUserId(), idempotencyKey));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:list')")
    @GetMapping("/recoveries")
    public AjaxResult recoveries(Integer status, Integer limit) {
        return success(orientScoringService.listRecoveries(status, limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:orientScoring:review')")
    @Log(title = "历史计分人工核对", businessType = BusinessType.UPDATE)
    @PostMapping("/recoveries/{id}/review")
    public AjaxResult reviewRecovery(@PathVariable("id") Long id, String referenceId, String note) {
        return run(() -> {
            Long reference = referenceId == null || referenceId.isBlank() ? null : Convert.toLong(referenceId, null);
            if (referenceId != null && !referenceId.isBlank() && (reference == null || reference <= 0)) {
                throw new OrientScoringException(OrientScoringException.INVALID_ARGUMENT, "关联编号不合法");
            }
            return orientScoringService.reviewRecovery(id, reference, note, getUsername());
        });
    }

    private AjaxResult run(Supplier<Object> action) {
        try {
            return success(action.get());
        } catch (OrientScoringException e) {
            return error(e.getMessage()).put("errorCode", e.getErrorCode());
        }
    }
}
