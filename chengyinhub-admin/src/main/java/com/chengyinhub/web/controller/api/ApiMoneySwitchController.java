package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Q5 money switch, app read. {@code GET /api/app/money-switch} returns one boolean per money area
 * (tickets, pass, merchantMoney, payout, withdrawal, refunds). Any error answers all-off, never an error page the app
 * could misread. The app ANDs these with its compiled ceiling and treats anything missing as off.
 */
@RestController
@Tag(name = "Money switch (app read)")
@RequestMapping("/api/app")
public class ApiMoneySwitchController extends BaseController {

    /** How long the app may trust one answer before it must read again; after that it treats every area as off. */
    static final int TTL_SECONDS = 300;
    /**
     * W7 MERCHANT-15: this route is anonymous, so one database read serves every caller for a few seconds. Only this
     * route caches; the server's own money gates read the switch directly, so "off" still stops them at once. A failed
     * read is never cached.
     */
    static final long CACHE_MILLIS = 5_000L;

    private final MoneySwitchService moneySwitchService;
    private volatile Map<String, Boolean> cached;
    private volatile long cachedAt;

    public ApiMoneySwitchController(MoneySwitchService moneySwitchService) {
        this.moneySwitchService = moneySwitchService;
    }

    @Operation(summary = "Per-area money switch (all off by default)")
    @Anonymous
    @GetMapping("/money-switch")
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    public AjaxResult moneySwitch() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Boolean> areas = snapshot(System.currentTimeMillis());
        for (MoneyArea area : MoneyArea.values()) {
            data.put(area.key(), areas != null && Boolean.TRUE.equals(areas.get(area.key())));
        }
        data.put("ttlSeconds", TTL_SECONDS);
        return AjaxResult.success(data);
    }

    /** Null on a read error (answered as all off). */
    public Map<String, Boolean> snapshot(long now) {
        Map<String, Boolean> hit = cached;
        if (hit != null && now - cachedAt >= 0 && now - cachedAt < CACHE_MILLIS) {
            return hit;
        }
        try {
            Map<String, Boolean> fresh = moneySwitchService.snapshot();
            if (fresh != null) {
                cached = Map.copyOf(fresh);
                cachedAt = now;
            }
            return fresh;
        } catch (RuntimeException failure) {
            logger.error("money switch snapshot failed; answering all off", failure);
            return null;
        }
    }
}
