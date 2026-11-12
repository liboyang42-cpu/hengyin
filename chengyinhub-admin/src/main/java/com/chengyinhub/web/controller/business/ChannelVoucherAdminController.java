package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ChannelAccountRequest;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationRequest;
import com.chengyinhub.business.domain.dto.ChannelReconciliationView;
import com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherRevokeRequest;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.IChannelOrderVerificationService;
import com.chengyinhub.business.service.IChannelRedemptionService;
import com.chengyinhub.business.service.IChannelVoucherService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * OTA 渠道主体/验证/发码/撤销/对账管理端。
 *
 * <p>权限串是新增的，默认不授任何角色；未授权角色得到 403，不能靠前端隐藏绕过。
 * 真实渠道凭据未接入时，验证接口会如实返回 {@code PROVIDER_NOT_CONFIGURED}，不会发码。
 */
@RestController
@RequestMapping("/business/marketing/channel")
public class ChannelVoucherAdminController extends BaseController {

    private final IChannelOrderVerificationService verificationService;
    private final IChannelVoucherService voucherService;
    private final IChannelRedemptionService redemptionService;

    public ChannelVoucherAdminController(IChannelOrderVerificationService verificationService,
                                         IChannelVoucherService voucherService,
                                         IChannelRedemptionService redemptionService) {
        this.verificationService = verificationService;
        this.voucherService = voucherService;
        this.redemptionService = redemptionService;
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:list')")
    @GetMapping("/accounts")
    public AjaxResult accounts(@RequestParam(required = false) Integer status,
                               @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return success(verificationService.listAccounts(status, limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:manage')")
    @Log(title = "渠道发码主体登记", businessType = BusinessType.INSERT)
    @PostMapping("/accounts")
    public AjaxResult registerAccount(@RequestBody(required = false) ChannelAccountRequest request) {
        return success(verificationService.registerAccount(requireOperator(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:manage')")
    @Log(title = "渠道发码主体状态", businessType = BusinessType.UPDATE)
    @PostMapping("/accounts/{accountId}/status")
    public AjaxResult updateAccountStatus(@PathVariable Long accountId, @RequestParam Integer status) {
        return success(verificationService.updateAccountStatus(requireOperator(), accountId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:verify')")
    @Log(title = "渠道订单验证", businessType = BusinessType.UPDATE)
    @PostMapping("/verifications")
    public AjaxResult verify(@RequestBody(required = false) ChannelOrderVerificationRequest request) {
        return success(verificationService.verifyByReference(requireOperator(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:list')")
    @GetMapping("/verifications")
    public AjaxResult verifications(@RequestParam Long accountId,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(pattern = "yyyy-MM-dd") Date from,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(pattern = "yyyy-MM-dd") Date to,
                                    @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return success(verificationService.listVerifications(accountId, status, from, to,
                limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:list')")
    @GetMapping("/verifications/{verificationId}")
    public AjaxResult verification(@PathVariable Long verificationId) {
        return success(verificationService.getVerification(requireOperator(), verificationId));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:revoke')")
    @Log(title = "渠道订单撤销", businessType = BusinessType.UPDATE)
    @PostMapping("/verifications/{verificationId}/revoke")
    public AjaxResult revokeVerification(@PathVariable Long verificationId,
                                         @RequestBody(required = false) ChannelVoucherRevokeRequest request) {
        String reason = request == null ? null : request.getReason();
        String requestId = request == null ? null : request.getRequestId();
        return success(verificationService.revokeVerification(requireOperator(), verificationId, reason, requestId));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:issue')")
    @Log(title = "渠道兑换码发行", businessType = BusinessType.INSERT, isSaveResponseData = false)
    @PostMapping("/vouchers")
    public AjaxResult issue(@RequestBody(required = false) ChannelVoucherIssueRequest request) {
        return success(voucherService.issue(requireOperator(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:list')")
    @GetMapping("/vouchers/{voucherId}")
    public AjaxResult voucher(@PathVariable Long voucherId) {
        return success(voucherService.get(requireOperator(), voucherId));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:list')")
    @GetMapping("/vouchers")
    public AjaxResult vouchers(@RequestParam Long accountId,
                               @RequestParam(required = false)
                               @DateTimeFormat(pattern = "yyyy-MM-dd") Date from,
                               @RequestParam(required = false)
                               @DateTimeFormat(pattern = "yyyy-MM-dd") Date to,
                               @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return success(voucherService.list(accountId, from, to, limit == null ? 50 : limit));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:revoke')")
    @Log(title = "渠道兑换码撤销", businessType = BusinessType.UPDATE)
    @PostMapping("/vouchers/{voucherId}/revoke")
    public AjaxResult revokeVoucher(@PathVariable Long voucherId,
                                    @RequestBody(required = false) ChannelVoucherRevokeRequest request) {
        return success(voucherService.revoke(requireOperator(), voucherId, request));
    }

    @PreAuthorize("@ss.hasPermi('business:marketing:channel:reconcile')")
    @GetMapping("/reconciliation")
    public AjaxResult reconciliation(@RequestParam Long accountId,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(pattern = "yyyy-MM-dd") Date from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(pattern = "yyyy-MM-dd") Date to) {
        ChannelReconciliationView view = redemptionService.reconcile(accountId, from, to);
        return success(view);
    }

    @ExceptionHandler(ChannelVoucherException.class)
    public AjaxResult handleRejection(ChannelVoucherException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    private long requireOperator() {
        Long userId = getUserId();
        if (userId == null || userId <= 0) {
            throw ChannelVoucherException.unauthorized("LOGIN_REQUIRED", "请先登录管理端");
        }
        return userId;
    }
}
