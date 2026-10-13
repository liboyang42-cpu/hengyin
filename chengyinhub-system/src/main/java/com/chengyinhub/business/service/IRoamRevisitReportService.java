package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;

import java.time.LocalDate;

public interface IRoamRevisitReportService {
    RoamRevisitReportVO generate(LocalDate cohortDate, RoamRevisitReportVO.Metric metric, int windowDays);
}
