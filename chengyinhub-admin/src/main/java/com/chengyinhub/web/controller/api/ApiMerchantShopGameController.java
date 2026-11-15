package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.shopgames.ShopGameProfileService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * S5 shop games for the signed-in member's own shop (app merchant pages, mini-program merchant pages).
 * <ul>
 *   <li>{@code POST /api/merchant/shop-games}: kind, pool, plan allowance, current games, own templates, editable.</li>
 *   <li>{@code POST /api/merchant/shop-games/save}: {@code {"games":[{"key":"qaPick","templateId":12}]}}.</li>
 * </ul>
 */
@RestController
public class ApiMerchantShopGameController extends BaseController {

    private final ShopGameProfileService profiles;

    public ApiMerchantShopGameController(ShopGameProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping("/api/merchant/shop-games")
    public ResponseEntity<AjaxResult> view(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> {
            if (body != null && !body.isEmpty()) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            return profiles.merchantView(member());
        });
    }

    @PostMapping("/api/merchant/shop-games/save")
    public ResponseEntity<AjaxResult> save(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> profiles.merchantSave(member(), body));
    }

    private Long member() {
        Long id;
        try {
            id = getAppUserId();
        } catch (Exception e) {
            throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        }
        if (id == null || id <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        return id;
    }
}
