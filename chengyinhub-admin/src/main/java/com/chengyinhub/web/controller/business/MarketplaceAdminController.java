package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.marketplace.MarketplaceAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 城市集市后台:地图优惠开启、推广曝光位待处理订单(只读)、未入驻门店销售线索(只读、无个人信息)。 */
@RestController
@RequestMapping("/business/marketplace")
public class MarketplaceAdminController extends BaseController {
    private final MarketplaceAdminService service;

    @Autowired
    public MarketplaceAdminController(MarketplaceAdminService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('business:marketplace:list')")
    @GetMapping("/deals")
    public AjaxResult deals() {
        return success(service.deals());
    }

    @PreAuthorize("@ss.hasPermi('business:marketplace:edit')")
    @Log(title = "城市集市优惠", businessType = BusinessType.UPDATE)
    @PutMapping("/deals/{couponId}")
    public AjaxResult setDeal(@PathVariable("couponId") Long couponId,
                              @RequestParam(value = "enabled", defaultValue = "true") boolean enabled) {
        try {
            service.setDeal(couponId, enabled, getUsername());
            return success();
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:marketplace:edit')")
    @Log(title = "城市集市优惠", businessType = BusinessType.DELETE)
    @DeleteMapping("/deals/{couponId}")
    public AjaxResult removeDeal(@PathVariable("couponId") Long couponId) {
        try {
            service.removeDeal(couponId);
            return success();
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /** 推广曝光位已下架:列出已付款订单(含退款进度),由业主决定是否退款。退款走既有商家原路退款。 */
    @PreAuthorize("@ss.hasPermi('business:marketplace:list')")
    @GetMapping("/promotion-slot/purchases")
    public AjaxResult promotionSlotPurchases() {
        return success(service.promotionSlotPurchases());
    }

    @PreAuthorize("@ss.hasPermi('business:marketplace:list')")
    @GetMapping("/leads")
    public AjaxResult leads(@RequestParam(value = "days", required = false) Integer days,
                            @RequestParam(value = "minVisitors", required = false) Integer minVisitors) {
        return success(service.salesLeads(days, minVisitors));
    }
}
