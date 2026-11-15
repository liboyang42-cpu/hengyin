package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.externalgame.ExternalGameMonetizationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Admin review and configuration for external game monetization, revenue split,
 * anti-fraud traffic caps, and daily settlement execution.
 */
@RestController
@Tag(name = "外部游戏变现审核与配置(后台)")
@RequestMapping("/business/external-game/monetization")
public class ExternalGameMonetizationAdminController extends BaseController {
    private final ExternalGameMonetizationService monetization;

    public ExternalGameMonetizationAdminController(ExternalGameMonetizationService monetization) {
        this.monetization = monetization;
    }

    @Operation(summary = "外部游戏变现配置与待审列表")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String reviewStatus,
                           @RequestParam(required = false) Integer pageNum,
                           @RequestParam(required = false) Integer pageSize) {
        return success(monetization.adminListConfigs(reviewStatus, pageNum, pageSize));
    }

    @Operation(summary = "审核外部游戏变现配置并可同步调整分成与反作弊参数")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/{gameId}/review")
    public AjaxResult review(@PathVariable String gameId, @RequestBody(required = false) Map<String, Object> body) {
        return success(monetization.adminReviewConfig(getUserId(), gameId, body));
    }

    @Operation(summary = "后台调整分成、通道手续费承担方与流量激励规则")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PutMapping("/{gameId}/config")
    public AjaxResult updateConfig(@PathVariable String gameId, @RequestBody(required = false) Map<String, Object> body) {
        return success(monetization.adminUpdateConfig(getUserId(), gameId, body));
    }

    @Operation(summary = "流量激励每日结算台账")
    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/settlements")
    public AjaxResult settlements(@RequestParam(required = false) String settlementDate,
                                  @RequestParam(required = false) String gameId,
                                  @RequestParam(required = false) Integer pageNum,
                                  @RequestParam(required = false) Integer pageSize) {
        return success(monetization.adminSettlements(settlementDate, gameId, pageNum, pageSize));
    }

    @Operation(summary = "立即执行指定日期的外部游戏流量激励日结")
    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @PostMapping("/settle")
    public AjaxResult settle(@RequestBody(required = false) Map<String, Object> body) {
        String dateRaw = body == null || body.get("date") == null ? null : String.valueOf(body.get("date")).trim();
        LocalDate date = dateRaw == null || dateRaw.isEmpty()
                ? LocalDate.now(ExternalGameMonetizationService.SHANGHAI).minusDays(1)
                : LocalDate.parse(dateRaw);
        return success(monetization.settleDailyTraffic(date));
    }

    @ExceptionHandler(AppGapException.class)
    public ResponseEntity<AjaxResult> failed(AppGapException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
