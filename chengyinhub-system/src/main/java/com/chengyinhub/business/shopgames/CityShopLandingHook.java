package com.chengyinhub.business.shopgames;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * S5 hook for City Monopoly: what happens when a player lands on a shop cell.
 *
 * <p>City Monopoly's board, dice, claim and yield rules are not changed by shop games. This hook is read only: it
 * returns the shop's game profile and how to launch the game, and the City Monopoly owner decides whether and when
 * to offer it (see {@code docs/roadmap/shop-games/CITY_HOOK.md}). Launching goes through
 * {@code POST /api/shop-games/launch} with {@code source=CITY_LANDING}, which applies location trust and the
 * one-play-per-shop-per-day rule; a shop-game win never changes a City Monopoly outcome.
 */
@Component
public class CityShopLandingHook {

    public static final String LAUNCH_PATH = "/api/shop-games/launch";

    private final ShopGameProfileService profiles;

    public CityShopLandingHook(ShopGameProfileService profiles) {
        this.profiles = profiles;
    }

    /** The landing offer for a board cell: by place id when the cell has one, else by shop id. Never throws. */
    public Map<String, Object> onShopLanding(Long poiId, Long merchantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> profile;
        try {
            if (poiId != null && poiId > 0) profile = profiles.profileForPoi(poiId);
            else if (merchantId != null && merchantId > 0) profile = profiles.profile(merchantId);
            else profile = Map.of("available", false, "reason", "NOT_A_SHOP");
        } catch (RuntimeException e) {
            profile = Map.of("available", false, "reason", "UNAVAILABLE");
        }
        out.put("profile", profile);
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("method", "POST");
        launch.put("path", LAUNCH_PATH);
        launch.put("source", ShopGamePlayService.SOURCE_CITY_LANDING);
        launch.put("needsPoiId", true);
        out.put("launch", launch);
        out.put("changesCityRules", false);
        return out;
    }
}
