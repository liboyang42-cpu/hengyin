package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.shopgames.CityShopLandingHook;
import com.chengyinhub.business.shopgames.ShopGamePlayService;
import com.chengyinhub.business.shopgames.ShopGameProfileService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * S5 shop games for players and City Monopoly.
 * <ul>
 *   <li>{@code GET /api/shop-games/profile?poiId=|merchantId=}: a shop's game profile (kind, games, standard rules).</li>
 *   <li>{@code GET /api/shop-games/catalog}: every shop kind with its pool, and the standard rules.</li>
 *   <li>{@code GET /api/shop-games/city-landing?poiId=|merchantId=}: City Monopoly's read-only landing hook.</li>
 *   <li>{@code POST /api/shop-games/launch}: {@code {poiId, source: JOURNEY_NODE|CITY_LANDING, lat, lng}}.
 *       A roam check-in launches inside {@code /api/roam/checkin} (field {@code shopGame}).</li>
 *   <li>{@code POST /api/shop-games/result}: {@code {playId, roundsWon}}; a win awards points under governance caps.</li>
 * </ul>
 */
@RestController
public class ApiShopGameController extends BaseController {

    private final ShopGameProfileService profiles;
    private final ShopGamePlayService plays;
    private final CityShopLandingHook landing;

    public ApiShopGameController(ShopGameProfileService profiles, ShopGamePlayService plays, CityShopLandingHook landing) {
        this.profiles = profiles;
        this.plays = plays;
        this.landing = landing;
    }

    @GetMapping("/api/shop-games/profile")
    public ResponseEntity<AjaxResult> profile(@RequestParam(required = false) Long poiId,
                                              @RequestParam(required = false) Long merchantId) {
        return ApiMerchantGapController.answer(() -> {
            if (poiId != null && poiId > 0) return profiles.profileForPoi(poiId);
            if (merchantId != null && merchantId > 0) return profiles.profile(merchantId);
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        });
    }

    @GetMapping("/api/shop-games/catalog")
    public ResponseEntity<AjaxResult> catalog() {
        return ApiMerchantGapController.answer(profiles::catalogView);
    }

    @GetMapping("/api/shop-games/city-landing")
    public ResponseEntity<AjaxResult> shopLanding(@RequestParam(required = false) Long poiId,
                                                  @RequestParam(required = false) Long merchantId) {
        return ApiMerchantGapController.answer(() -> {
            if ((poiId == null || poiId <= 0) && (merchantId == null || merchantId <= 0)) {
                throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            }
            return landing.onShopLanding(poiId, merchantId);
        });
    }

    @PostMapping("/api/shop-games/launch")
    public ResponseEntity<AjaxResult> launch(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> plays.launch(member(), body));
    }

    @PostMapping("/api/shop-games/result")
    public ResponseEntity<AjaxResult> result(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> plays.finish(member(), body));
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
