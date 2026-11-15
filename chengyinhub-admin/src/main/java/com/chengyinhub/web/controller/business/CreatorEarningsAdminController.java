package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.creatorearnings.CreatorEarningsService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R12 creator earnings admin report. Reads the append-only ledger; the only write is re-running the idempotent
 * accrual for one day, which appends missing events and credits points (never money).
 */
@RestController
@RequestMapping("/business/creator-earnings")
public class CreatorEarningsAdminController extends BaseController {

    private final CreatorEarningsService earnings;

    public CreatorEarningsAdminController(CreatorEarningsService earnings) {
        this.earnings = earnings;
    }

    @PreAuthorize("@ss.hasPermi('business:creator:list')")
    @GetMapping("/report")
    public TableDataInfo report(@RequestParam(value = "creatorId", required = false) Long creatorId,
                                @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        startPage();
        return getDataTable(earnings.report(creatorId, from, to));
    }

    @PreAuthorize("@ss.hasPermi('business:creator:list')")
    @GetMapping("/ledger")
    public TableDataInfo ledger(@RequestParam(value = "creatorId", required = false) Long creatorId,
                                @RequestParam(value = "eventType", required = false) String eventType) {
        startPage();
        return getDataTable(earnings.adminLedger(creatorId, eventType));
    }

    @PreAuthorize("@ss.hasPermi('business:creator:list')")
    @GetMapping("/rules")
    public AjaxResult rules() {
        var c = earnings.policy().config();
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("adoptPoints", c.adoptPoints());
        rules.put("installPoints", c.installPoints());
        rules.put("playPoints", c.playPoints());
        rules.put("dailyPlayCap", c.dailyPlayCap());
        rules.put("dailyPlayPerPlayer", c.dailyPlayPerPlayer());
        rules.put("saleShareBps", c.saleShareBps());
        rules.put("moneyOn", earnings.moneyOn());
        return AjaxResult.success(rules);
    }

    @PreAuthorize("@ss.hasPermi('business:creator:review')")
    @Log(title = "创作者收益重算", businessType = BusinessType.OTHER)
    @PostMapping("/accrue")
    public AjaxResult accrue(@RequestParam("day") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        LocalDate today = LocalDate.now(CreatorEarningsService.ZONE);
        if (day.isAfter(today) || day.isBefore(today.minusDays(400))) return AjaxResult.error("日期超出可重算范围");
        return AjaxResult.success(earnings.accrueDay(day));
    }
}
