package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.BundleExtrasService;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

/**
 * F3 free-explore bundle routes the app builds beyond Phase 1 (shelf, wallet, voucher, waiver, redemption result,
 * reviews, renewal intent, appeal record). Same envelope as {@link ApiFreeExploreBundleController}: success
 * {code:200,data}, refusal {code:409,msg,businessCode}, login failure LOGIN_REQUIRED. Refund requests and transfers
 * move money or a paid entitlement and always refuse with BUNDLE_MONEY_WRITE_DISABLED in v1.
 */
@RestController
@RequestMapping("/api/free-explore/bundle")
public class ApiFreeExploreBundleExtrasController extends BaseController {
    private final BundleExtrasService service;

    public ApiFreeExploreBundleExtrasController(BundleExtrasService service) { this.service = service; }

    @GetMapping("/shelf")
    public AjaxResult shelf(String cursor, String limit) { return run(() -> { member(); return service.shelf(cursor, limit); }); }

    @GetMapping("/wallet")
    public AjaxResult wallet(String cursor, String limit) { return run(() -> service.wallet(member(), cursor, limit)); }

    @GetMapping("/voucher")
    public AjaxResult voucher(String registrationId) { return run(() -> service.voucher(member(), registrationId)); }

    @GetMapping("/voucher/version")
    public AjaxResult voucherVersion(String registrationId) { return run(() -> service.voucherVersion(member(), registrationId)); }

    @GetMapping("/waiver")
    public AjaxResult waiver(String month) { return run(() -> service.waiver(member(), month)); }

    @GetMapping("/redemption/result")
    public AjaxResult redemptionResult(String registrationId, String unitId) {
        return run(() -> service.redemptionResult(member(), registrationId, unitId));
    }

    @PostMapping("/review")
    public AjaxResult review(@RequestBody Map<String, Object> body) { return run(() -> service.submitReview(member(), body)); }

    @GetMapping("/review/mine")
    public AjaxResult myReview(String registrationId) { return run(() -> service.myReview(member(), registrationId)); }

    @GetMapping("/review/list")
    public AjaxResult reviews(String topicId, String cursor, String limit) {
        return run(() -> { member(); return service.reviewList(topicId, cursor, limit); });
    }

    @PostMapping("/renewal-intent")
    public AjaxResult renewal(@RequestBody Map<String, Object> body) { return run(() -> service.renewalIntent(member(), body)); }

    @GetMapping("/renewal-intent")
    public AjaxResult latestRenewal(String registrationId) { return run(() -> service.latestRenewal(member(), registrationId)); }

    @PostMapping("/voucher/appeal")
    public AjaxResult appeal(@RequestBody Map<String, Object> body) { return run(() -> service.appeal(member(), body)); }

    @GetMapping("/voucher/appeal/receipt")
    public AjaxResult appealReceipt(String idempotencyKey) { return run(() -> service.appealReceipt(member(), idempotencyKey)); }

    @GetMapping("/voucher/appeal")
    public AjaxResult appeals(String registrationId) { return run(() -> service.appeals(member(), registrationId)); }

    // ---- money and paid-entitlement moves: reads answer, writes refuse ----

    @GetMapping("/order/refund")
    public AjaxResult refundProgress(String registrationId) { return run(() -> service.refundProgress(member(), registrationId)); }

    @PostMapping("/order/refund")
    public AjaxResult refund() { return run(() -> { member(); throw BundleExtrasService.moneyWriteDisabled(); }); }

    @GetMapping("/transfer/preview")
    public AjaxResult transferPreview(String registrationId) { return run(() -> service.transferPreview(member(), registrationId)); }

    @GetMapping("/transfer/receipt")
    public AjaxResult transferReceipt(String idempotencyKey) { return run(() -> service.transferReceipt(member(), idempotencyKey)); }

    @GetMapping("/transfer")
    public AjaxResult transfers(String registrationId) {
        return run(() -> { service.transferPreview(member(), registrationId); return java.util.List.of(); });
    }

    @PostMapping({"/transfer", "/transfer/claim", "/transfer/{transferId}/revoke"})
    public AjaxResult transferWrite() { return run(() -> { member(); throw BundleExtrasService.moneyWriteDisabled(); }); }

    private AjaxResult run(Supplier<Object> action) {
        try {
            return success(action.get());
        } catch (FreeExploreBundleException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", e.getBusinessCode());
        } catch (com.chengyinhub.business.appgaps.AppGapException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", "LOGIN_REQUIRED");
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 409 : e.getCode(), e.getMessage());
        }
    }

    private Long member() {
        try {
            LoginAppUser login = getLoginAppUser();
            if (login == null || login.getAppUser() == null || login.getAppUser().getUserId() == null) {
                throw new FreeExploreBundleException("LOGIN_REQUIRED", "请先登录");
            }
            return login.getAppUser().getUserId();
        } catch (ServiceException e) {
            throw new FreeExploreBundleException("LOGIN_REQUIRED", "请先登录");
        }
    }
}
