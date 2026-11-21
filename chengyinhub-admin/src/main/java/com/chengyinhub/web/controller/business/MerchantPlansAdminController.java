package com.chengyinhub.web.controller.business;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.merchantplans.MerchantPlanService;
import com.chengyinhub.business.merchantplans.MerchantPlansMoneyGate;
import com.chengyinhub.business.merchantplans.MerchantVisitBillingService;
import com.chengyinhub.business.merchantplans.TakeRateService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.framework.web.service.PermissionService;

/**
 * S3a admin console: take rates per product type, merchant plans, plan orders and grants, verified-visit billing.
 * Every write needs a reason and writes a platform audit row (business_audit_event) in the same transaction.
 * Plan purchase, renewal, paid grants, visit accrual and invoices are refused while the money switch area
 * {@code merchantMoney} is closed. Permissions come from the migration and are granted to no role.
 */
@RestController
@RequestMapping("/business/merchant-plans")
public class MerchantPlansAdminController extends BaseController {

    private final TakeRateService takeRates;
    private final MerchantPlanService plans;
    private final MerchantVisitBillingService billing;
    private final PermissionService permissions;

    public MerchantPlansAdminController(TakeRateService takeRates, MerchantPlanService plans,
                                        MerchantVisitBillingService billing, PermissionService permissions) {
        this.takeRates = takeRates;
        this.plans = plans;
        this.billing = billing;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- take rates

    @PreAuthorize("@ss.hasPermi('business:merchantplans:query')")
    @GetMapping("/take-rates")
    public AjaxResult takeRates() {
        return success(takeRates.view());
    }

    /** Body: {"product": "official_ticket", "source": "", "rateBps": 300 | null, "reason": "..."}; null = back to today's value. */
    @PreAuthorize("@ss.hasPermi('business:merchantplans:take')")
    @Log(title = "平台抽成设置", businessType = BusinessType.UPDATE)
    @PutMapping("/take-rates")
    public AjaxResult setTakeRate(@RequestBody Map<String, Object> body) {
        return guarded(() -> {
            Object bps = body.get("rateBps");
            if (bps != null && !(bps instanceof Integer)) throw new IllegalArgumentException("rateBps must be a whole number or null");
            boolean changed = takeRates.set(str(body.get("product")), str(body.get("source")), (Integer) bps,
                    getUserId(), getUsername(), str(body.get("reason")));
            return Map.of("changed", changed, "rows", takeRates.view());
        });
    }

    // ---------------------------------------------------------------- plans

    @PreAuthorize("@ss.hasPermi('business:merchantplans:query')")
    @GetMapping
    public AjaxResult plans() {
        return success(plans.adminView());
    }

    /** Body: {"patch": {...nameZh, nameEn, monthlyPriceFen, visitFeeFen, features, offered}, "reason": "..."}. */
    @PreAuthorize("@ss.hasPermi('business:merchantplans:edit')")
    @Log(title = "商家套餐设置", businessType = BusinessType.UPDATE)
    @PutMapping("/{code}")
    @SuppressWarnings("unchecked")
    public AjaxResult updatePlan(@PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        return guarded(() -> {
            Object patch = body.get("patch");
            if (!(patch instanceof Map)) throw new IllegalArgumentException("patch is required");
            boolean changed = plans.updatePlan(code, (Map<String, Object>) patch, getUserId(), getUsername(), str(body.get("reason")));
            Map<String, Object> out = new LinkedHashMap<>(plans.adminView());
            out.put("changed", changed);
            return out;
        });
    }

    @PreAuthorize("@ss.hasPermi('business:merchantplans:query')")
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(value = "status", required = false) String status,
                             @RequestParam(value = "merchantId", required = false) Long merchantId) {
        return success(plans.orders(status, merchantId));
    }

    @PreAuthorize("@ss.hasPermi('business:merchantplans:query')")
    @GetMapping("/subscriptions")
    public AjaxResult subscriptions(@RequestParam(value = "merchantId", required = false) Long merchantId) {
        return success(plans.subscriptions(merchantId));
    }

    /**
     * Body: {"paymentReference": "...", "amountReceivedFen": 19900, "reason": "..."}. Money switch must be open; the
     * amount received must equal the order amount (MERCHANT-12).
     */
    @PreAuthorize("@ss.hasPermi('business:merchantplans:sell')")
    @Log(title = "商家套餐收款确认", businessType = BusinessType.UPDATE)
    @PostMapping("/orders/{orderNo}/confirm-paid")
    public AjaxResult confirmPaid(@PathVariable("orderNo") String orderNo, @RequestBody Map<String, Object> body) {
        return guarded(() -> plans.confirmPaid(orderNo, str(body.get("paymentReference")), body.get("amountReceivedFen"),
                getUserId(), getUsername(), str(body.get("reason"))));
    }

    /**
     * Body: {"merchantId": 1, "planCode": "BASIC", "months": 1, "reason": "..."}. Paid plans need the money switch open
     * and, because no order backs them, also {@code business:merchantplans:grantPaid} (MERCHANT-12).
     */
    @PreAuthorize("@ss.hasPermi('business:merchantplans:sell')")
    @Log(title = "商家套餐开通", businessType = BusinessType.UPDATE)
    @PostMapping("/grant")
    public AjaxResult grant(@RequestBody Map<String, Object> body) {
        return guarded(() -> {
            Object merchant = body.get("merchantId");
            Long merchantId = merchant instanceof Number n ? n.longValue() : null;
            return plans.grant(merchantId, str(body.get("planCode")), body.get("months"), getUserId(), getUsername(),
                    str(body.get("reason")), permissions.hasPermi("business:merchantplans:grantPaid"));
        });
    }

    // ---------------------------------------------------------------- visit billing

    @PreAuthorize("@ss.hasPermi('business:merchantplans:query')")
    @GetMapping("/visit-charges")
    public AjaxResult charges(@RequestParam(value = "merchantId", required = false) Long merchantId,
                              @RequestParam(value = "month", required = false) String month) {
        return guarded(() -> billing.charges(merchantId, month));
    }

    @PreAuthorize("@ss.hasPermi('business:merchantplans:query')")
    @GetMapping("/visit-invoices")
    public AjaxResult invoices(@RequestParam(value = "merchantId", required = false) Long merchantId,
                               @RequestParam(value = "month", required = false) String month) {
        return guarded(() -> billing.invoices(merchantId, month));
    }

    /** Re-runs accrual for one day (yyyy-MM-dd). Writes nothing while the money switch is closed. */
    @PreAuthorize("@ss.hasPermi('business:merchantplans:billing')")
    @Log(title = "到店计费入账", businessType = BusinessType.UPDATE)
    @PostMapping("/visit-charges/accrue")
    public AjaxResult accrue(@RequestParam("day") String day) {
        return guarded(() -> billing.accrueDay(LocalDate.parse(day)));
    }

    /** Body: {"month": "2026-10", "reason": "..."}. Money switch must be open; the month must have ended. */
    @PreAuthorize("@ss.hasPermi('business:merchantplans:billing')")
    @Log(title = "到店计费月结", businessType = BusinessType.INSERT)
    @PostMapping("/visit-invoices/issue")
    public AjaxResult issue(@RequestBody Map<String, Object> body) {
        return guarded(() -> billing.issueInvoices(YearMonth.parse(str(body.get("month"))), getUserId(), getUsername(),
                str(body.get("reason"))));
    }

    private AjaxResult guarded(Supplier<Object> call) {
        try {
            return success(call.get());
        } catch (IllegalStateException refused) {
            if (MerchantPlansMoneyGate.CLOSED_CODE.equals(refused.getMessage())) {
                AjaxResult closed = AjaxResult.error("资金开关「商家资金」未打开：套餐售卖与到店计费暂未开放");
                closed.put("errorCode", MerchantPlansMoneyGate.CLOSED_CODE);
                return closed;
            }
            return AjaxResult.error(refused.getMessage());
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException refused) {
            return AjaxResult.error(refused.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
