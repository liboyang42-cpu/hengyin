package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.commerceconsole.CommerceConsoleService;
import com.chengyinhub.business.commerceconsole.DuesRefundDecisionService;
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
 * T5 MON-16:商品与会费后台。只读列表 + 停售 / 停用(只减少售卖)。权限点迁移不授予任何角色。
 * W7 MERCHANT-12:会费退款申请的处理(驳回不动钱;通过走既有微信原路退款,须资金开关 refunds 打开)。
 */
@RestController
@RequestMapping("/business/commerceConsole")
public class CommerceConsoleController extends BaseController {

    private final CommerceConsoleService service;
    private final DuesRefundDecisionService duesRefunds;

    public CommerceConsoleController(CommerceConsoleService service, DuesRefundDecisionService duesRefunds) {
        this.service = service;
        this.duesRefunds = duesRefunds;
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:list')")
    @GetMapping("/products")
    public AjaxResult products(@RequestParam(required = false) Integer status,
                               @RequestParam(required = false) Integer kind,
                               @RequestParam(required = false) Integer limit) {
        return success(service.products(status, kind, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:list')")
    @GetMapping("/productOrders")
    public AjaxResult productOrders(@RequestParam(required = false) Long productId,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) Integer limit) {
        return success(service.productOrders(productId, status, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:list')")
    @GetMapping("/bundleOrders")
    public AjaxResult bundleOrders(@RequestParam(required = false) Long topicId,
                                   @RequestParam(required = false) Integer limit) {
        return success(service.bundleOrders(topicId, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:list')")
    @GetMapping("/duesPlans")
    public AjaxResult duesPlans(@RequestParam(required = false) Long clubId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Integer limit) {
        return success(service.duesPlans(clubId, status, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:list')")
    @GetMapping("/duesInvoices")
    public AjaxResult duesInvoices(@RequestParam(required = false) Long clubId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) Integer limit) {
        return success(service.duesInvoices(clubId, status, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:edit')")
    @Log(title = "营销商品停售", businessType = BusinessType.UPDATE)
    @PostMapping("/products/{id}/stopSale")
    public AjaxResult stopSale(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return success(service.stopProductSale(getUserId(), id, reason(body)));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:edit')")
    @Log(title = "停用俱乐部会费方案", businessType = BusinessType.UPDATE)
    @PostMapping("/duesPlans/{id}/deactivate")
    public AjaxResult deactivate(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return success(service.deactivateDuesPlan(getUserId(), getUsername(), id, reason(body)));
    }

    @PreAuthorize("@ss.hasPermi('business:commerceConsole:list')")
    @GetMapping("/dues-refunds")
    public AjaxResult duesRefunds(@RequestParam(required = false) Boolean undecided,
                                  @RequestParam(required = false) Integer limit) {
        return success(duesRefunds.reviews(undecided, limit));
    }

    /** Body: {"reason": "..."}。全额原路退款;资金开关 refunds 关闭时拒绝。 */
    @PreAuthorize("@ss.hasPermi('business:commerceConsole:refund')")
    @Log(title = "会费退款通过", businessType = BusinessType.UPDATE)
    @PostMapping("/dues-refunds/{reviewId}/approve")
    public AjaxResult approveDuesRefund(@PathVariable long reviewId, @RequestBody Map<String, Object> body) {
        return success(duesRefunds.approve(getUserId(), getUsername(), reviewId, reason(body)));
    }

    /** Body: {"reason": "..."}。不动钱,只记录决定与说明。 */
    @PreAuthorize("@ss.hasPermi('business:commerceConsole:refund')")
    @Log(title = "会费退款驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/dues-refunds/{reviewId}/reject")
    public AjaxResult rejectDuesRefund(@PathVariable long reviewId, @RequestBody Map<String, Object> body) {
        return success(duesRefunds.reject(getUserId(), getUsername(), reviewId, reason(body)));
    }

    private static String reason(Map<String, Object> body) {
        Object v = body == null ? null : body.get("reason");
        return v == null ? null : v.toString();
    }
}
