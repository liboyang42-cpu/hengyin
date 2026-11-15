package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.citycoin.CityCoinService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * R7: the player's own city coin balance and history (read only). City coin is non-cashable; this controller has
 * no write route, and no route anywhere converts city coin (CityCoinNoConversionGuardTest).
 */
@RestController
@Tag(name = "城市币")
@RequestMapping("/api/city-coin")
public class ApiCityCoinController extends BaseController {

    @Autowired
    private CityCoinService cityCoinService;

    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "本人城市币余额与最近记录")
    @GetMapping("/me")
    public AjaxResult me() {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try {
            return success(cityCoinService.summary(memberId));
        } catch (RuntimeException e) {
            logger.warn("city coin summary unavailable member={}: {}", memberId, e.toString());
            return error("城市币暂时无法读取,请稍后再试");
        }
    }
}
