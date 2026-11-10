package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.payout.EcommercePayoutQueryService;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSubMerchantService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 电商收付通运营端：二级商户进件登记/提交/刷新，分账计划/提交/同步。
 * 全部在 payout.ecommerce.* 开关之后；开关关闭时服务层拒绝或返回 0。
 */
@RestController
@RequestMapping("/business/ecommerce-payout")
public class EcommercePayoutAdminController extends BaseController {
    @Autowired private EcommerceSubMerchantService subMerchantService;
    @Autowired private EcommerceProfitShareService profitShareService;
    @Autowired private EcommercePayoutQueryService queryService;

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/sub-merchant")
    public AjaxResult view(@RequestParam String ownerType, @RequestParam Long ownerId) {
        try {
            return success(subMerchantService.ownerView(ownerType, ownerId));
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/sub-merchant/register")
    public AjaxResult register(@RequestParam String ownerType, @RequestParam Long ownerId) {
        try {
            return success(subMerchantService.register(ownerType, ownerId, getUsername()).getId());
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    /** 进件资料按微信电商收付通进件接口字段原样提交；敏感字段由服务端加密，本系统不落库。 */
    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/sub-merchant/{id}/submit")
    public AjaxResult submit(@PathVariable Long id, @RequestBody Map<String, Object> applymentBody) {
        try {
            return success(subMerchantService.submit(id, applymentBody).getApplymentState());
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/sub-merchant/{id}/refresh")
    public AjaxResult refresh(@PathVariable Long id) {
        try {
            return success(subMerchantService.refresh(id).getApplymentState());
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/profit-share/topic/{topicId}/plan")
    public AjaxResult plan(@PathVariable Long topicId) {
        return success(profitShareService.planTopic(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/profit-share/submit")
    public AjaxResult submitPlanned() {
        return success(profitShareService.submitPlanned(50));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/profit-share/sync")
    public AjaxResult sync() {
        return success(profitShareService.syncProcessing(50));
    }

    // ---- l-payout2：运营端页面（进件审核 / 分账记录 / 回退与退款 / 失败重试） ----

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/switches")
    public AjaxResult switches() {
        return success(queryService.switches());
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/sub-merchants")
    public AjaxResult subMerchants(@RequestParam(required = false) String state, @RequestParam(required = false) Integer limit) {
        return success(queryService.subMerchants(state, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/sub-merchant/sync")
    public AjaxResult syncOnboarding() {
        return success(subMerchantService.refreshInProgress(50));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/profit-share/orders")
    public AjaxResult shareOrders(@RequestParam(required = false) String state, @RequestParam(required = false) Long topicId,
                                  @RequestParam(required = false) Integer limit) {
        return success(queryService.shareOrders(state, topicId, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/profit-share/orders/{id}/lines")
    public AjaxResult shareLines(@PathVariable Long id) {
        return success(queryService.shareLines(id));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/profit-share/orders/{id}/replan")
    public AjaxResult replan(@PathVariable Long id) {
        return success(profitShareService.replan(id));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/profit-share/returns")
    public AjaxResult returns(@RequestParam(required = false) String state, @RequestParam(required = false) Integer limit) {
        return success(queryService.returns(state, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/profit-share/returns/{id}/retry")
    public AjaxResult retryReturn(@PathVariable Long id) {
        return success(profitShareService.retryReturn(id).name());
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:edit')")
    @PostMapping("/profit-share/returns/sync")
    public AjaxResult syncReturns() {
        return success(profitShareService.syncReturns(50));
    }

    @PreAuthorize("@ss.hasPermi('business:ecommercepayout:query')")
    @GetMapping("/refunds")
    public AjaxResult refunds(@RequestParam(required = false) String state, @RequestParam(required = false) Integer limit) {
        return success(queryService.refunds(state, limit));
    }
}
