package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;
import com.chengyinhub.business.service.IRoamRevisitReportService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 后台只读漫游复访报表；不复用 ApiRoamController 的状态写路径。 */
@RestController
@RequestMapping("/business/roamRevisitReport")
public class RoamRevisitReportController extends BaseController {
    private final IRoamRevisitReportService reportService;

    @Autowired
    public RoamRevisitReportController(IRoamRevisitReportService reportService) {
        this.reportService = reportService;
    }

    @PreAuthorize("@ss.hasPermi('business:roamRevisitReport:list')")
    @GetMapping
    public AjaxResult get(
            @RequestParam("cohortDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cohortDate,
            @RequestParam("metric") RoamRevisitReportVO.Metric metric,
            @RequestParam("windowDays") int windowDays) {
        return success(reportService.generate(cohortDate, metric, windowDays));
    }
}
