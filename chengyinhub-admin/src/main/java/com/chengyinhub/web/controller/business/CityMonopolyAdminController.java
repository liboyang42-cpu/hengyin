package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.citygame.CityGameException;
import com.chengyinhub.business.citygame.CityMonopolyConfigService;
import com.chengyinhub.business.citygame.CityMonopolyService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin console for City Monopoly numbers: config (approved defaults, effective values, diff, validated update),
 * the nightly batches (settle pending rent, upkeep/decay/abuse detection), season settlement and the abuse queue.
 * The batches are manual triggers until a reviewed sys_job registration lands (see CITY-NUMBERS-ATTR.md).
 */
@RestController
@RequestMapping("/business/city-game/monopoly")
public class CityMonopolyAdminController extends BaseController {
    private final CityMonopolyConfigService configs;
    private final CityMonopolyService service;

    public CityMonopolyAdminController(CityMonopolyConfigService configs, CityMonopolyService service) {
        this.configs = configs;
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/config")
    public AjaxResult config() {
        return success(configs.describe());
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "城市大富翁配置", businessType = BusinessType.UPDATE)
    @PutMapping("/config")
    public AjaxResult updateConfig(@RequestBody Map<String, Object> body) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (body != null) body.forEach((k, v) -> changes.put(k, v == null ? null : String.valueOf(v)));
        return success(configs.update(changes, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "城市大富翁收租结算", businessType = BusinessType.UPDATE)
    @PostMapping("/settle")
    public AjaxResult settle() {
        return success(service.settle());
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "城市大富翁夜间批处理", businessType = BusinessType.UPDATE)
    @PostMapping("/nightly")
    public AjaxResult nightly() {
        return success(service.nightly());
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "城市大富翁赛季结算", businessType = BusinessType.UPDATE)
    @PostMapping("/season/{seasonId}/settle")
    public AjaxResult settleSeason(@PathVariable String seasonId, @RequestParam(required = false) String city) {
        return success(service.settleSeason(seasonId, city));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/abuse")
    public AjaxResult abuse(@RequestParam(required = false) String status, @RequestParam(required = false) Integer limit) {
        return success(service.abuseQueue(status, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "城市大富翁风控审核", businessType = BusinessType.UPDATE)
    @PostMapping("/abuse/{signalId}/review")
    public AjaxResult review(@PathVariable Long signalId, @RequestBody Map<String, Object> body) {
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        return success(service.reviewSignal(signalId, confirm, getUserId()));
    }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<AjaxResult> failed(CityGameException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
