package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.modcommerce.ModCommerceService;
import com.chengyinhub.business.modcommerce.ModCommerceService.OfferCommand;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R6c workshop-iap, admin console: map an approved template release to an App Store product id, price and currency,
 * and put it on or off sale. Permissions come from the migration and are granted to no role.
 */
@RestController
@RequestMapping("/business/mod-commerce/offers")
public class ModCommerceOfferAdminController extends BaseController {
    private final ModCommerceService service;

    public ModCommerceOfferAdminController(ModCommerceService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('business:modcommerce:query')")
    @GetMapping
    public AjaxResult list() {
        AjaxResult result = success(service.adminOffers());
        result.put("open", service.open());
        return result;
    }

    /** Body: {"versionId": "WKV_…", "market": "CN", "productId": "com.example.template.x", "priceMinor": 600}. */
    @PreAuthorize("@ss.hasPermi('business:modcommerce:edit')")
    @Log(title = "工坊内购商品", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody(required = false) OfferCommand command) {
        try {
            return success(service.createOffer(command, getUsername()));
        } catch (ServiceException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** Body: {"enabled": true}. */
    @PreAuthorize("@ss.hasPermi('business:modcommerce:edit')")
    @Log(title = "工坊内购商品", businessType = BusinessType.UPDATE)
    @PutMapping("/{offerId}/enabled")
    public AjaxResult enable(@PathVariable String offerId, @RequestBody(required = false) Map<String, Object> body) {
        Object enabled = body == null ? null : body.get("enabled");
        if (!(enabled instanceof Boolean)) return AjaxResult.error("enabled 必须是 true 或 false");
        try {
            return success(service.setOfferEnabled(offerId, (Boolean) enabled, getUsername()));
        } catch (ServiceException e) {
            return AjaxResult.error(e.getMessage());
        }
    }
}
