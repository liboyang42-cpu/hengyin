package com.chengyinhub.web.controller.api.modcommerce;

import com.chengyinhub.business.modcommerce.ModCommerceService;
import com.chengyinhub.business.modcommerce.ModCommerceService.Intent;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.appgaps.ApiAppGapsWorkshopController;
import com.chengyinhub.web.controller.api.appgaps.NotBuiltFeature;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.LongFunction;

/**
 * R6c workshop-iap: {@code /api/mod-commerce/*} as {@code Core/WorkshopCommerceService.swift} calls it.
 *
 * <p>Two sets of routes (MERCHANT-2: the switch stops selling, never delivery):
 * <ul>
 *   <li><b>Sell</b> ({@code offers*}, {@code POST orders}, {@code orders/by-request/{id}[/lookup]}, {@code payment},
 *       {@code native-context}, {@code native-dispatch}): while {@link ModCommerceService#open()} is false (the default:
 *       {@code chengyin.mod-commerce.enabled=false}, or the money switch's {@code pass} area closed) they answer exactly
 *       what {@link ApiAppGapsWorkshopController#commerceOff} answers today.</li>
 *   <li><b>Deliver</b> ({@code storekit}, order status, {@code native-transaction}, {@code restore}, {@code reconcile}, {@code cancel},
 *       {@code reversal-status}, {@code licenses*}): need only {@link ModCommerceService#deliverable()}, so a purchase
 *       Apple has charged is granted and restorable after the area closes.</li>
 * </ul>
 * Signed out is 401 first in both. Routes not mapped here (install reservations, release records) keep falling
 * through to that catch-all.
 */
@RestController
@RequestMapping("/api/mod-commerce")
public class ApiModCommerceController extends BaseController {
    private final ModCommerceService service;

    public ApiModCommerceController(ModCommerceService service) { this.service = service; }

    public record Signed(String signedTransaction) { }

    /** MERCHANT-9: App Store setup for templates. A deliver route: restore needs it after the area closes. */
    @GetMapping("/storekit")
    public ResponseEntity<AjaxResult> storeKit(HttpServletResponse response) {
        return deliver(response, actor -> service.storeKit());
    }

    @GetMapping("/offers")
    public ResponseEntity<AjaxResult> offers(@RequestParam(value = "before", required = false) String before, HttpServletResponse response) {
        return sell(response, actor -> service.offers(before));
    }

    @GetMapping("/offers/{offerId}")
    public ResponseEntity<AjaxResult> offer(@PathVariable String offerId, HttpServletResponse response) {
        return sell(response, actor -> service.offer(offerId));
    }

    @PostMapping("/orders")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> create(@RequestBody(required = false) Intent intent, HttpServletResponse response) {
        return sell(response, actor -> service.create(actor, intent));
    }

    @GetMapping("/orders/by-request/{requestId}/lookup")
    public ResponseEntity<AjaxResult> lookup(@PathVariable String requestId, HttpServletResponse response) {
        return sell(response, actor -> service.lookup(actor, requestId));
    }

    @GetMapping("/orders/by-request/{requestId}")
    public ResponseEntity<AjaxResult> byRequest(@PathVariable String requestId, HttpServletResponse response) {
        return sell(response, actor -> service.byRequest(actor, requestId));
    }

    @PostMapping("/orders/by-request/cancel")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> cancelRequest(@RequestBody(required = false) Intent intent, HttpServletResponse response) {
        return deliver(response, actor -> service.cancelRequest(actor, intent));
    }

    @GetMapping("/orders/{orderKey}")
    public ResponseEntity<AjaxResult> order(@PathVariable String orderKey, HttpServletResponse response) {
        return deliver(response, actor -> service.order(actor, orderKey));
    }

    @GetMapping("/orders/{orderKey}/offer")
    public ResponseEntity<AjaxResult> orderOffer(@PathVariable String orderKey, HttpServletResponse response) {
        return deliver(response, actor -> service.orderOffer(actor, orderKey));
    }

    @PostMapping("/orders/{orderKey}/payment")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> payment(@PathVariable String orderKey, HttpServletResponse response) {
        return sell(response, actor -> service.payment(actor, orderKey));
    }

    @GetMapping("/orders/{orderKey}/native-context")
    public ResponseEntity<AjaxResult> nativeContext(@PathVariable String orderKey, HttpServletResponse response) {
        return sell(response, actor -> service.nativeContext(actor, orderKey));
    }

    @PostMapping("/orders/{orderKey}/native-dispatch")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> nativeDispatch(@PathVariable String orderKey, HttpServletResponse response) {
        return sell(response, actor -> service.nativeDispatch(actor, orderKey));
    }

    @PostMapping("/orders/{orderKey}/native-transaction")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> nativeTransaction(@PathVariable String orderKey, @RequestBody(required = false) Signed body,
                                                        HttpServletResponse response) {
        return deliver(response, actor -> service.nativeTransaction(actor, orderKey, body == null ? null : body.signedTransaction()));
    }

    @PostMapping("/native-transactions/restore")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> restore(@RequestBody(required = false) Signed body, HttpServletResponse response) {
        return deliver(response, actor -> service.restore(actor, body == null ? null : body.signedTransaction()));
    }

    @PostMapping("/orders/{orderKey}/reconcile")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> reconcile(@PathVariable String orderKey, HttpServletResponse response) {
        return deliver(response, actor -> service.reconcile(actor, orderKey));
    }

    @PostMapping("/orders/{orderKey}/cancel")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> cancel(@PathVariable String orderKey, HttpServletResponse response) {
        return deliver(response, actor -> service.cancel(actor, orderKey));
    }

    @GetMapping("/orders/{orderKey}/reversal-status")
    public ResponseEntity<AjaxResult> reversalStatus(@PathVariable String orderKey, HttpServletResponse response) {
        return deliver(response, actor -> service.reversalStatus(actor, orderKey));
    }

    @GetMapping("/licenses")
    public ResponseEntity<AjaxResult> licenses(@RequestParam(value = "before", required = false) String before, HttpServletResponse response) {
        return deliver(response, actor -> service.licenses(actor, before));
    }

    @GetMapping("/licenses/{licenseId}")
    public ResponseEntity<AjaxResult> license(@PathVariable String licenseId, HttpServletResponse response) {
        return deliver(response, actor -> service.license(actor, licenseId));
    }

    Long signedInMember() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<AjaxResult> sell(HttpServletResponse response, LongFunction<Object> call) {
        return run(response, call, true);
    }

    private ResponseEntity<AjaxResult> deliver(HttpServletResponse response, LongFunction<Object> call) {
        return run(response, call, false);
    }

    private ResponseEntity<AjaxResult> run(HttpServletResponse response, LongFunction<Object> call, boolean sells) {
        Long actor = signedInMember();
        // Same order as the catch-all: signed out first, then the named refusal while the route's gate is closed.
        if (actor == null) return ResponseEntity.ok(AjaxResult.error(401, "请先登录"));
        boolean gate = sells ? service.open() : service.deliverable();
        if (!gate) return ResponseEntity.ok(ApiAppGapsWorkshopController.notBuilt(NotBuiltFeature.MOD_COMMERCE, response));
        try {
            return ResponseEntity.ok(AjaxResult.success(call.apply(actor)));
        } catch (ServiceException e) {
            int code = e.getCode() == null || e.getCode() < 400 ? 503 : e.getCode();
            AjaxResult body = new AjaxResult(code, e.getMessage());
            body.put("errorCode", e.getMessage());
            return ResponseEntity.status(code).body(body);
        }
    }
}
