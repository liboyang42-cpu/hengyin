package com.chengyinhub.web.controller.business;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.shopgames.ShopGameProfileService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * S5 admin console: shop game pools, per-shop overrides and switching games on or off for every shop.
 * Every write needs a reason and writes a platform audit row. Permissions come from the migration and are granted
 * to no role.
 */
@RestController
@RequestMapping("/business/shop-games")
public class ShopGamesAdminController extends BaseController {

    private final ShopGameProfileService profiles;

    public ShopGamesAdminController(ShopGameProfileService profiles) {
        this.profiles = profiles;
    }

    @PreAuthorize("@ss.hasPermi('business:shopgames:list')")
    @GetMapping("/catalog")
    public AjaxResult catalog() {
        return success(profiles.catalogView());
    }

    @PreAuthorize("@ss.hasPermi('business:shopgames:list')")
    @GetMapping("/profiles")
    public AjaxResult profiles(@RequestParam(required = false) Long merchantId) {
        return success(profiles.adminList(merchantId));
    }

    /** Body: {"kindOverride": "CAFE" | "", "games": [{"key": "qaPick"}] | [] | null, "locked": true, "reason": "..."}. */
    @PreAuthorize("@ss.hasPermi('business:shopgames:edit')")
    @Log(title = "店铺小游戏设置", businessType = BusinessType.UPDATE)
    @PutMapping("/profiles/{merchantId}")
    public AjaxResult override(@PathVariable("merchantId") Long merchantId, @RequestBody Map<String, Object> body) {
        return guarded(() -> profiles.adminOverride(merchantId, body, getUserId(), getUsername(), str(body.get("reason"))));
    }

    /** Body: {"enabled": false, "reason": "..."}. */
    @PreAuthorize("@ss.hasPermi('business:shopgames:edit')")
    @Log(title = "店铺小游戏上下架", businessType = BusinessType.UPDATE)
    @PutMapping("/games/{key}")
    public AjaxResult curate(@PathVariable("key") String key, @RequestBody Map<String, Object> body) {
        return guarded(() -> profiles.adminCurate(key, body.get("enabled"), getUserId(), getUsername(), str(body.get("reason"))));
    }

    private AjaxResult guarded(Supplier<Object> call) {
        try {
            return success(call.get());
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return AjaxResult.error(refused.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
