package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.moneypayout.PayoutRequestService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R6b withdrawal review queue. Approve sends the payout through the existing WeChat transfer and is refused unless
 * the money switch area {@code withdrawal} and {@code withdrawal.fast-withdraw.enabled} are both on. Reject moves
 * no money. Every decision needs a note and writes a platform audit row. Permissions are granted to no role.
 */
@RestController
@RequestMapping("/business/payout-request")
public class PayoutRequestAdminController extends BaseController {

    private final PayoutRequestService requests;

    public PayoutRequestAdminController(PayoutRequestService requests) {
        this.requests = requests;
    }

    @PreAuthorize("@ss.hasPermi('business:payoutRequest:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(value = "status", required = false) String status,
                           @RequestParam(value = "limit", required = false) Integer limit) {
        return success(Map.of("gate", requests.adminGate(), "rows", requests.adminList(status, limit)));
    }

    /** Body: {"note": "..."}. */
    @PreAuthorize("@ss.hasPermi('business:payoutRequest:review')")
    @Log(title = "提现审核-通过并打款", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/approve")
    public AjaxResult approve(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return success(requests.approve(id, getUserId(), getUsername(), note(body)));
    }

    /** Body: {"note": "..."}. */
    @PreAuthorize("@ss.hasPermi('business:payoutRequest:review')")
    @Log(title = "提现审核-驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reject")
    public AjaxResult reject(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return success(requests.reject(id, getUserId(), getUsername(), note(body)));
    }

    private static String note(Map<String, Object> body) {
        Object value = body == null ? null : body.get("note");
        return value == null ? null : String.valueOf(value);
    }
}
